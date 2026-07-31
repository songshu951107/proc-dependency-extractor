package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Neo4jSyncServiceImpl implements Neo4jSyncService {

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private TableLineageMapper tableLineageMapper;

    @Autowired
    private TableLineageFilterMapper tableLineageFilterMapper;

    @Autowired
    private TableFieldMappingMapper tableFieldMappingMapper;

    @Autowired
    private SqlBlockInfoMapper sqlBlockInfoMapper;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult syncProc(String procName) {
        Neo4jSyncLog lastFailed = neo4jSyncLogMapper.selectOne(
                new LambdaQueryWrapper<Neo4jSyncLog>()
                        .eq(Neo4jSyncLog::getProcName, procName)
                        .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
                        .orderByDesc(Neo4jSyncLog::getUpdateTime)
                        .last("LIMIT 1"));
        Long logId;
        if (lastFailed != null && (lastFailed.getRetryCount() == null || lastFailed.getRetryCount() < 3)) {
            logId = lastFailed.getId();
            Neo4jSyncLog updatePending = new Neo4jSyncLog();
            updatePending.setId(logId);
            updatePending.setSyncStatus("PENDING");
            neo4jSyncLogMapper.updateById(updatePending);
        } else {
            logId = insertSyncLog(procName);
        }

        try {
            deleteProcData(procName);

            ProcInfo procInfo = procInfoMapper.selectOne(
                    new LambdaQueryWrapper<ProcInfo>().eq(ProcInfo::getProcName, procName));
            if (procInfo == null) {
                updateSyncLog(logId, "FAILED", "proc_info 不存在");
                return SyncResult.failure(procName, "proc_info 不存在");
            }

            List<TableLineage> edges = tableLineageMapper.selectList(
                    new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getProcName, procName));
            List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

            Map<Long, List<TableLineageFilter>> filtersByLineageId = new HashMap<>();
            List<TableFieldMapping> mappings = new ArrayList<>();

            if (!edgeIds.isEmpty()) {
                List<TableLineageFilter> filters = tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>().in(TableLineageFilter::getLineageId, edgeIds));
                filtersByLineageId = filters.stream().collect(Collectors.groupingBy(TableLineageFilter::getLineageId));

                mappings = tableFieldMappingMapper.selectList(
                        new LambdaQueryWrapper<TableFieldMapping>().in(TableFieldMapping::getLineageId, edgeIds));
            }

            // 写入 Script 节点
            String scriptFile = procName + ".py";
            mergeScriptNode(scriptFile, procInfo);

            // 收集所有涉及的表（源表 + 目标表）
            Set<String> involvedTables = new LinkedHashSet<>();
            for (TableLineage edge : edges) {
                involvedTables.add(edge.getSourceTable());
                involvedTables.add(edge.getTargetTable());
            }

            // 写入 Table 节点
            for (String fullName : involvedTables) {
                String shortName = extractShortName(fullName);
                mergeTableNode(fullName, shortName, scriptFile);
            }

            // 写入 READS 关系 + PRODUCES + CONSUMES
            for (TableLineage edge : edges) {
                List<TableLineageFilter> edgeFilters = filtersByLineageId.getOrDefault(edge.getId(), Collections.emptyList());
                createReadsRelationship(edge, edgeFilters);

                // 目标表由 Script 产出
                mergeProducesRelationship(scriptFile, edge.getTargetTable());
                // 源表被 Script 消费
                mergeConsumesRelationship(scriptFile, edge.getSourceTable());
            }

            // 写入 Column 节点 + BELONGS_TO 关系 + MAPS_TO 关系
            for (TableFieldMapping fm : mappings) {
                TableLineage edge = findEdgeById(edges, fm.getLineageId());
                if (edge == null) continue;

                String targetColKey = toColKey(edge.getTargetTable(), fm.getTargetField());
                mergeColumnNode(targetColKey, fm.getTargetField(), edge.getTargetTable());
                mergeBelongsRelationship(targetColKey, edge.getTargetTable());

                String sourceTableShort = fm.getSourceTableShort();
                if (sourceTableShort == null || sourceTableShort.isEmpty()) continue;
                String sourceTableFull = findTableFullName(edges, sourceTableShort);
                if (sourceTableFull == null) continue;
                String sourceFieldName = extractFieldName(fm.getSourceExpr());
                if (sourceFieldName == null) continue;
                String sourceColKey = toColKey(sourceTableFull, sourceFieldName);
                mergeColumnNode(sourceColKey, sourceFieldName, sourceTableFull);
                mergeBelongsRelationship(sourceColKey, sourceTableFull);

                String transType = classifyTransformation(fm.getSourceExpr());
                createMapsToRelationship(fm, edge, sourceColKey, targetColKey, transType, scriptFile);
            }

            // 更新 SOURCE 类型
            updateSourceTableTypes();

            // 清理孤儿节点
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n").run();

            updateSyncLog(logId, "SUCCESS", null);
            log.info("Neo4j 同步成功 proc={}", procName);
            return SyncResult.success(procName);

        } catch (Exception e) {
            log.error("Neo4j 同步失败 proc={}", procName, e);
            updateSyncLog(logId, "FAILED", e.getMessage());
            return SyncResult.failure(procName, e.getMessage());
        }
    }

    @Override
    public SyncResult syncAll() {
        List<ProcInfo> allProcs = procInfoMapper.selectList(null);
        int success = 0, failed = 0;
        for (ProcInfo proc : allProcs) {
            SyncResult r = syncProc(proc.getProcName());
            if (r.isSuccess()) success++; else failed++;
        }
        return SyncResult.summary(allProcs.size(), success, failed);
    }

    @Override
    public void deleteProcData(String procName) {
        String scriptFile = procName + ".py";
        try {
            neo4jClient.query("MATCH ()-[r:READS {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH (s:Script {file: $scriptFile})-[r]->() DELETE r")
                    .bind(scriptFile).to("scriptFile").run();
            neo4jClient.query("MATCH (s:Script {file: $scriptFile}) DETACH DELETE s")
                    .bind(scriptFile).to("scriptFile").run();
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n").run();
        } catch (Exception e) {
            log.warn("删除 Neo4j 旧数据失败 proc={}: {}", procName, e.getMessage());
        }
    }

    @Override
    public void clearAllData() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        neo4jSyncLogMapper.delete(new LambdaQueryWrapper<>());
        log.info("Neo4j 全部数据已清空");
    }

    private Long insertSyncLog(String procName) {
        Neo4jSyncLog logEntry = new Neo4jSyncLog();
        logEntry.setProcName(procName);
        logEntry.setSyncStatus("PENDING");
        logEntry.setRetryCount(0);
        neo4jSyncLogMapper.insert(logEntry);
        return logEntry.getId();
    }

    private void updateSyncLog(Long logId, String status, String errorMessage) {
        Neo4jSyncLog logEntry = new Neo4jSyncLog();
        logEntry.setId(logId);
        logEntry.setSyncStatus(status);
        logEntry.setErrorMessage(errorMessage);
        if ("FAILED".equals(status)) {
            Neo4jSyncLog existing = neo4jSyncLogMapper.selectById(logId);
            if (existing != null) {
                logEntry.setRetryCount(existing.getRetryCount() == null ? 1 : existing.getRetryCount() + 1);
            }
        }
        neo4jSyncLogMapper.updateById(logEntry);
    }

    private void mergeScriptNode(String scriptFile, ProcInfo procInfo) {
        Map<String, Object> params = new HashMap<>();
        params.put("file", scriptFile);
        params.put("targetTable", procInfo.getUpdateTarget());
        params.put("description", procInfo.getProcDesc());
        neo4jClient.query("MERGE (s:Script {file: $file}) " +
                "SET s.target_table = $targetTable, s.description = $description")
                .bindAll(params).run();
    }

    private void mergeTableNode(String fullName, String shortName, String scriptFile) {
        if (fullName == null || fullName.isEmpty()) return;
        String name = fullName.toLowerCase();
        String schema = extractSchema(fullName);
        String layer = schema != null ? schema.toUpperCase() : null;
        boolean isTemp = fullName.toUpperCase().startsWith("VT_");

        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("shortName", shortName);
        params.put("schemaPrefix", schema);
        params.put("layer", layer);
        params.put("isTemp", isTemp);
        params.put("scriptFile", scriptFile);
        neo4jClient.query("MERGE (t:Table {name: $name}) " +
                "SET t.short_name = $shortName, t.schema_prefix = $schemaPrefix, " +
                "t.layer = $layer, t.is_temp = $isTemp, t.script_file = $scriptFile")
                .bindAll(params).run();
    }

    private void updateSourceTableTypes() {
        neo4jClient.query("MATCH (t:Table) " +
                "WHERE NOT (t)<-[:READS]-() AND t.layer IS NOT NULL " +
                "SET t.is_source = true").run();
    }

    private void createReadsRelationship(TableLineage edge, List<TableLineageFilter> filters) {
        List<TableLineageFilter> joins = filters.stream()
                .filter(f -> "JOIN".equals(f.getFilterType()))
                .collect(Collectors.toList());
        List<TableLineageFilter> wheres = filters.stream()
                .filter(f -> "WHERE".equals(f.getFilterType()))
                .collect(Collectors.toList());

        String joinJson = serializeConditions(joins);
        String whereJson = serializeConditions(wheres);
        boolean viaTemp = edge.getTargetTable().toUpperCase().startsWith("VT_");

        Map<String, Object> params = new HashMap<>();
        params.put("sourceTable", edge.getSourceTable().toLowerCase());
        params.put("targetTable", edge.getTargetTable().toLowerCase());
        params.put("procName", edge.getProcName());
        params.put("operation", edge.getOperation());
        params.put("viaTemp", viaTemp);
        params.put("joinConditions", joinJson);
        params.put("whereConditions", whereJson);

        neo4jClient.query("MATCH (src:Table {name: $sourceTable}), (tgt:Table {name: $targetTable}) " +
                "MERGE (src)-[r:READS {proc_name: $procName}]->(tgt) " +
                "SET r.operation = $operation, r.via_temp = $viaTemp, " +
                "r.join_conditions = $joinConditions, r.where_conditions = $whereConditions")
                .bindAll(params).run();
    }

    private void mergeProducesRelationship(String scriptFile, String targetTable) {
        Map<String, Object> params = new HashMap<>();
        params.put("scriptFile", scriptFile);
        params.put("tableName", targetTable.toLowerCase());
        neo4jClient.query("MATCH (s:Script {file: $scriptFile}), (t:Table {name: $tableName}) " +
                "MERGE (s)-[:PRODUCES]->(t)")
                .bindAll(params).run();
    }

    private void mergeConsumesRelationship(String scriptFile, String sourceTable) {
        Map<String, Object> params = new HashMap<>();
        params.put("scriptFile", scriptFile);
        params.put("tableName", sourceTable.toLowerCase());
        neo4jClient.query("MATCH (s:Script {file: $scriptFile}), (t:Table {name: $tableName}) " +
                "MERGE (s)-[:CONSUMES]->(t)")
                .bindAll(params).run();
    }

    private String serializeConditions(List<TableLineageFilter> filters) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TableLineageFilter f : filters) {
            Map<String, Object> item = new HashMap<>();
            item.put("clause", f.getFilterClause());
            item.put("fields", parseFields(f.getFieldsInvolved()));
            list.add(item);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseFields(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(fieldsJson, List.class);
        } catch (Exception e) {
            return Collections.singletonList(fieldsJson);
        }
    }

    private void mergeColumnNode(String colKey, String fieldName, String tableFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("colKey", colKey.toLowerCase());
        params.put("name", fieldName.toUpperCase());
        params.put("tableFullName", tableFullName.toLowerCase());
        neo4jClient.query("MERGE (c:Column {col_key: $colKey}) " +
                "SET c.name = $name, c.table_full_name = $tableFullName")
                .bindAll(params).run();
    }

    private void mergeBelongsRelationship(String colKey, String tableFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("colKey", colKey.toLowerCase());
        params.put("tableName", tableFullName.toLowerCase());
        neo4jClient.query("MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName}) " +
                "MERGE (c)-[:BELONGS_TO]->(t)")
                .bindAll(params).run();
    }

    private void createMapsToRelationship(TableFieldMapping fm, TableLineage edge,
                                           String sourceColKey, String targetColKey,
                                           String transType, String scriptFile) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceColKey", sourceColKey.toLowerCase());
        params.put("targetColKey", targetColKey.toLowerCase());
        params.put("procName", fm.getProcName());
        params.put("sourceExpr", fm.getSourceExpr());
        params.put("transType", transType);
        params.put("scriptFile", scriptFile);

        neo4jClient.query("MATCH (s:Column {col_key: $sourceColKey}), " +
                "(t:Column {col_key: $targetColKey}) " +
                "MERGE (s)-[r:MAPS_TO {proc_name: $procName, source_expr: $sourceExpr}]->(t) " +
                "SET r.transformation_type = $transType, r.script_file = $scriptFile")
                .bindAll(params).run();
    }

    public String classifyTransformation(String sourceExpr) {
        if (sourceExpr == null || sourceExpr.isBlank()) return "direct";
        String trimmed = sourceExpr.trim();

        // 字面量: 'N'、123、'2024-01-01'
        if (trimmed.matches("^'[^']*'$") || trimmed.matches("^\\d+(\\.\\d+)?$")) {
            return "literal";
        }
        // 聚合函数: SUM/COUNT/AVG/MAX/MIN(...)
        if (trimmed.matches("^(?i)(SUM|COUNT|AVG|MAX|MIN|STDDEV|VARIANCE)\\s*\\(.*")) {
            return "aggregation";
        }
        // 表达式派生: 含运算符或函数调用
        if (trimmed.matches(".*[+\\-*/].*") || trimmed.matches("^(?i)\\w+\\s*\\(.*")) {
            return "derived";
        }
        // 默认: 直接字段引用
        return "direct";
    }

    private String toColKey(String tableFullName, String fieldName) {
        if (fieldName == null) return null;
        return (tableFullName + "." + fieldName).toLowerCase();
    }

    private String extractShortName(String fullName) {
        int idx = fullName.indexOf('.');
        return idx > 0 ? fullName.substring(idx + 1) : fullName;
    }

    private String extractFieldName(String expr) {
        if (expr == null) return null;
        Matcher m = FIELD_PATTERN.matcher(expr);
        if (m.find()) return m.group(2);
        return null;
    }

    private String extractSchema(String fullName) {
        int idx = fullName.indexOf('.');
        return idx > 0 ? fullName.substring(0, idx) : null;
    }

    private TableLineage findEdgeById(List<TableLineage> edges, Long id) {
        return edges.stream().filter(e -> e.getId().equals(id)).findFirst().orElse(null);
    }

    private String findTableFullName(List<TableLineage> edges, String shortName) {
        for (TableLineage e : edges) {
            if (shortName.equalsIgnoreCase(e.getSourceTableShort())) return e.getSourceTable();
        }
        for (TableLineage e : edges) {
            if (shortName.equalsIgnoreCase(e.getTargetTableShort())) return e.getTargetTable();
        }
        List<TableLineage> all = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getSourceTableShort, shortName));
        if (!all.isEmpty()) return all.get(0).getSourceTable();
        all = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getTargetTableShort, shortName));
        if (!all.isEmpty()) return all.get(0).getTargetTable();
        return shortName;
    }
}
