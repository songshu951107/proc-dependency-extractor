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
        // 查找该 proc 最近一次失败记录，决定是复用还是新建
        // 避免每次重试都新建 PENDING 记录导致 retryCount 永远为 1、syncLog 表无限膨胀
        Neo4jSyncLog lastFailed = neo4jSyncLogMapper.selectOne(
                new LambdaQueryWrapper<Neo4jSyncLog>()
                        .eq(Neo4jSyncLog::getProcName, procName)
                        .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
                        .orderByDesc(Neo4jSyncLog::getUpdateTime)
                        .last("LIMIT 1"));
        Long logId;
        if (lastFailed != null && (lastFailed.getRetryCount() == null || lastFailed.getRetryCount() < 3)) {
            // 复用旧记录，更新状态为 PENDING，使后续 updateSyncLog 能在 existing.retryCount 基础上累加
            logId = lastFailed.getId();
            Neo4jSyncLog updatePending = new Neo4jSyncLog();
            updatePending.setId(logId);
            updatePending.setSyncStatus("PENDING");
            neo4jSyncLogMapper.updateById(updatePending);
        } else {
            // 无失败记录或已达重试上限，新建记录
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
            List<SqlBlockInfo> blocks = sqlBlockInfoMapper.selectList(
                    new LambdaQueryWrapper<SqlBlockInfo>().eq(SqlBlockInfo::getProcName, procName));

            if (!edgeIds.isEmpty()) {
                List<TableLineageFilter> filters = tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>().in(TableLineageFilter::getLineageId, edgeIds));
                filtersByLineageId = filters.stream().collect(Collectors.groupingBy(TableLineageFilter::getLineageId));

                mappings = tableFieldMappingMapper.selectList(
                        new LambdaQueryWrapper<TableFieldMapping>().in(TableFieldMapping::getLineageId, edgeIds));
            }

            // 写入 Proc 节点
            Map<String, Object> procParams = new HashMap<>();
            procParams.put("name", procName);
            procParams.put("updateTarget", procInfo.getUpdateTarget());
            neo4jClient.query("MERGE (p:Proc {name: $name}) SET p.update_target = $updateTarget")
                    .bindAll(procParams).run();

            // 写入 Table 节点 + DERIVES 关系 + PARSED_BY 关系
            for (TableLineage edge : edges) {
                mergeTableNode(edge.getSourceTable(), edge.getSourceTableShort());
                String targetType = edge.getTargetIsTemp() == 1 ? "TEMP" : "TARGET";
                mergeTableNode(edge.getTargetTable(), edge.getTargetTableShort());
                updateTableType(edge.getTargetTable(), targetType);

                List<TableLineageFilter> edgeFilters = filtersByLineageId.getOrDefault(edge.getId(), Collections.emptyList());
                createDerivesRelationship(edge, edgeFilters);

                Map<String, Object> parsedByParams = new HashMap<>();
                parsedByParams.put("procName", procName);
                parsedByParams.put("targetTable", edge.getTargetTable());
                parsedByParams.put("operation", edge.getOperation());
                neo4jClient.query("MATCH (t:Table {full_name: $targetTable}), (p:Proc {name: $procName}) " +
                        "MERGE (t)-[:PARSED_BY {proc_name: $procName, operation: $operation}]->(p)")
                        .bindAll(parsedByParams).run();
            }

            // 写入 Field 节点 + HAS_FIELD 关系 + MAPS_TO 关系
            for (TableFieldMapping fm : mappings) {
                TableLineage edge = findEdgeById(edges, fm.getLineageId());
                if (edge == null) continue;

                String targetFieldFullName = toFieldFullName(edge.getTargetTable(), fm.getTargetField());
                mergeFieldNode(targetFieldFullName, fm.getTargetField(), edge.getTargetTable());
                createHasFieldRelationship(edge.getTargetTable(), targetFieldFullName);

                String sourceTableShort = fm.getSourceTableShort();
                if (sourceTableShort == null || sourceTableShort.isEmpty()) continue;
                String sourceTableFull = findTableFullName(edges, sourceTableShort);
                if (sourceTableFull == null) continue;
                String sourceFieldName = extractFieldName(fm.getSourceExpr());
                if (sourceFieldName == null) continue;
                String sourceFieldFullName = toFieldFullName(sourceTableFull, sourceFieldName);
                mergeFieldNode(sourceFieldFullName, sourceFieldName, sourceTableFull);
                createHasFieldRelationship(sourceTableFull, sourceFieldFullName);

                createMapsToRelationship(fm, edge, sourceFieldFullName, targetFieldFullName);
            }

            // 写入 SqlBlock 节点 + CONTAINS_BLOCK 关系
            for (SqlBlockInfo block : blocks) {
                createSqlBlockNode(block, procName);
            }

            // 更新 SOURCE 类型
            updateSourceTableTypes();

            // 清理孤儿节点
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Field) AND NOT (n)--() DELETE n").run();

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
        try {
            neo4jClient.query("MATCH (b:SqlBlock {proc_name: $procName}) DETACH DELETE b")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:DERIVES {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:PARSED_BY {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH (p:Proc {name: $procName}) DETACH DELETE p")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Field) AND NOT (n)--() DELETE n").run();
        } catch (Exception e) {
            log.warn("删除 Neo4j 旧数据失败 proc={}: {}", procName, e.getMessage());
        }
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

    private void mergeTableNode(String fullName, String shortName) {
        if (fullName == null || fullName.isEmpty()) return;
        String schema = extractSchema(fullName);
        Map<String, Object> params = new HashMap<>();
        params.put("fullName", fullName);
        params.put("shortName", shortName);
        params.put("schema", schema);
        neo4jClient.query("MERGE (t:Table {full_name: $fullName}) " +
                "SET t.short_name = $shortName, t.schema = $schema")
                .bindAll(params).run();
    }

    private void updateTableType(String fullName, String type) {
        Map<String, Object> params = new HashMap<>();
        params.put("fullName", fullName);
        params.put("type", type);
        neo4jClient.query("MATCH (t:Table {full_name: $fullName}) SET t.type = $type")
                .bindAll(params).run();
    }

    private void updateSourceTableTypes() {
        neo4jClient.query("MATCH (t:Table) " +
                "WHERE NOT (t)<-[:DERIVES]-() " +
                "AND t.type IS NULL " +
                "SET t.type = 'SOURCE'").run();
    }

    private void createDerivesRelationship(TableLineage edge, List<TableLineageFilter> filters) {
        List<TableLineageFilter> joins = filters.stream()
                .filter(f -> "JOIN".equals(f.getFilterType()))
                .collect(Collectors.toList());
        List<TableLineageFilter> wheres = filters.stream()
                .filter(f -> "WHERE".equals(f.getFilterType()))
                .collect(Collectors.toList());

        String joinJson = serializeConditions(joins);
        String whereJson = serializeConditions(wheres);

        Map<String, Object> params = new HashMap<>();
        params.put("sourceTable", edge.getSourceTable());
        params.put("targetTable", edge.getTargetTable());
        params.put("procName", edge.getProcName());
        params.put("operation", edge.getOperation());
        params.put("sqlBlockSeq", edge.getSqlBlockSeq());
        params.put("mainTable", edge.getMainTable());
        params.put("joinConditions", joinJson);
        params.put("whereConditions", whereJson);

        neo4jClient.query("MATCH (s:Table {full_name: $sourceTable}), (t:Table {full_name: $targetTable}) " +
                "CREATE (s)-[:DERIVES {proc_name: $procName, operation: $operation, " +
                "sql_block_seq: $sqlBlockSeq, main_table: $mainTable, " +
                "join_conditions: $joinConditions, where_conditions: $whereConditions}]->(t)")
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

    private void mergeFieldNode(String fieldFullName, String fieldName, String tableFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("fieldFullName", fieldFullName);
        params.put("fieldName", fieldName.toUpperCase());
        params.put("tableFullName", tableFullName);
        neo4jClient.query("MERGE (f:Field {full_name: $fieldFullName}) " +
                "SET f.field = $fieldName, f.table_full_name = $tableFullName")
                .bindAll(params).run();
    }

    private void createHasFieldRelationship(String tableFullName, String fieldFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("tableFullName", tableFullName);
        params.put("fieldFullName", fieldFullName);
        neo4jClient.query("MATCH (t:Table {full_name: $tableFullName}), (f:Field {full_name: $fieldFullName}) " +
                "MERGE (t)-[:HAS_FIELD]->(f)")
                .bindAll(params).run();
    }

    private void createMapsToRelationship(TableFieldMapping fm, TableLineage edge,
                                           String sourceFieldFullName, String targetFieldFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceFieldFullName", sourceFieldFullName);
        params.put("targetFieldFullName", targetFieldFullName);
        params.put("procName", fm.getProcName());
        params.put("sqlBlockSeq", edge.getSqlBlockSeq());
        params.put("fieldOrder", fm.getFieldOrder());
        params.put("sourceExpr", fm.getSourceExpr());
        params.put("sourceAlias", fm.getSourceAlias());

        neo4jClient.query("MATCH (s:Field {full_name: $sourceFieldFullName}), " +
                "(t:Field {full_name: $targetFieldFullName}) " +
                "CREATE (s)-[:MAPS_TO {proc_name: $procName, sql_block_seq: $sqlBlockSeq, " +
                "field_order: $fieldOrder, source_expr: $sourceExpr, source_alias: $sourceAlias}]->(t)")
                .bindAll(params).run();
    }

    private void createSqlBlockNode(SqlBlockInfo block, String procName) {
        Map<String, Object> params = new HashMap<>();
        params.put("procName", procName);
        params.put("seq", block.getBlockSeq());
        params.put("operation", block.getBlockType());
        params.put("targetTable", block.getTargetTable());
        params.put("mainTable", block.getMainTable());

        neo4jClient.query("CREATE (b:SqlBlock {proc_name: $procName, seq: $seq, " +
                "operation: $operation, target_table: $targetTable, main_table: $mainTable})")
                .bindAll(params).run();

        neo4jClient.query("MATCH (p:Proc {name: $procName}), (b:SqlBlock {proc_name: $procName, seq: $seq}) " +
                "MERGE (p)-[:CONTAINS_BLOCK]->(b)")
                .bindAll(params).run();
    }

    private String toFieldFullName(String tableFullName, String fieldName) {
        if (fieldName == null) return null;
        return tableFullName + "." + fieldName.toUpperCase();
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
