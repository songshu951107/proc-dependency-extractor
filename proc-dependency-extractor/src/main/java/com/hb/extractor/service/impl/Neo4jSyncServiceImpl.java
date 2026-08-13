package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.parser.DdlParser;
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
    private TableTransformInputMapper tableTransformInputMapper;

    @Autowired
    private SqlBlockInfoMapper sqlBlockInfoMapper;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @Autowired
    private DdlParser ddlParser;

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

            // 加载 Transform 输入字段,按 field_mapping_id 分组
            Map<Long, List<TableTransformInput>> inputsByMappingId = new HashMap<>();
            if (!mappings.isEmpty()) {
                List<Long> mappingIds = mappings.stream().map(TableFieldMapping::getId).collect(Collectors.toList());
                List<TableTransformInput> transformInputs = tableTransformInputMapper.selectList(
                        new LambdaQueryWrapper<TableTransformInput>().in(TableTransformInput::getFieldMappingId, mappingIds));
                inputsByMappingId = transformInputs.stream()
                        .collect(Collectors.groupingBy(TableTransformInput::getFieldMappingId));
            }

            // 写入 Script 节点
            String scriptFile = procName + ".py";
            mergeScriptNode(scriptFile, procInfo);

            // 收集目标表（该 script 产出的表）和源表
            Set<String> targetTables = new LinkedHashSet<>();
            Set<String> sourceTables = new LinkedHashSet<>();
            Set<String> involvedTables = new LinkedHashSet<>();
            // 收集通过 CREATE TEMPORARY TABLE 创建的表（targetIsTemp=1）
            Set<String> tempTableNames = new LinkedHashSet<>();
            for (TableLineage edge : edges) {
                involvedTables.add(edge.getSourceTable());
                involvedTables.add(edge.getTargetTable());
                targetTables.add(edge.getTargetTable());
                sourceTables.add(edge.getSourceTable());
                if (Integer.valueOf(1).equals(edge.getTargetIsTemp())) {
                    tempTableNames.add(edge.getTargetTable());
                }
            }

            // 写入 Table 节点：
            // - 目标表：写入 script_file（生成它的存储过程）
            // - 仅作源表的表：不覆盖 script_file（避免被其他 proc 读取时覆盖）
            // - 临时表：is_temp 优先取 targetIsTemp 标记，其次按表名规则判断
            for (String fullName : involvedTables) {
                String shortName = extractShortName(fullName);
                boolean isTemp = tempTableNames.contains(fullName) || isTempTableName(shortName);
                if (targetTables.contains(fullName)) {
                    mergeTableNode(fullName, shortName, scriptFile, isTemp);
                } else {
                    mergeTableNode(fullName, shortName, null, isTemp);
                }
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

            // 写入 Column 节点 + BELONGS_TO + Transform 节点 + INPUT_TO/OUTPUT_TO 关系
            for (TableFieldMapping fm : mappings) {
                TableLineage edge = findEdgeById(edges, fm.getLineageId());
                if (edge == null) continue;

                // 1. 目标 Column 节点 + BELONGS_TO（逻辑不变）
                String targetColKey = toColKey(edge.getTargetTable(), fm.getTargetField());
                mergeColumnNode(targetColKey, fm.getTargetField(), edge.getTargetTable());
                mergeBelongsRelationship(targetColKey, edge.getTargetTable());

                // 2. Transform 节点
                String transformId = generateTransformId(procName, targetColKey);
                String logicType = fm.getLogicType();
                if (logicType == null) {
                    logicType = inferLogicTypeFromExpr(fm.getSourceExpr());
                }
                String sqlHint = generateSqlHint(edge, edges);
                mergeTransformNode(transformId, fm.getSourceExpr(), logicType, sqlHint,
                                   procName, targetColKey, scriptFile);

                // 3. INPUT_TO 关系
                List<TableTransformInput> fmInputs = inputsByMappingId.getOrDefault(fm.getId(), Collections.emptyList());
                if (fmInputs.isEmpty()) {
                    // 降级:旧数据无 inputs 但有 source_alias,构造 1 条 INPUT_TO
                    if (fm.getSourceAlias() != null && fm.getSourceTableShort() != null) {
                        String sourceTableFull = findTableFullName(edges, fm.getSourceTableShort());
                        if (sourceTableFull != null) {
                            String sourceFieldName = extractFieldName(fm.getSourceExpr());
                            if (sourceFieldName != null) {
                                String sourceColKey = toColKey(sourceTableFull, sourceFieldName);
                                mergeColumnNode(sourceColKey, sourceFieldName, sourceTableFull);
                                mergeBelongsRelationship(sourceColKey, sourceTableFull);
                                createInputToRelationship(sourceColKey, transformId, "value", fm.getSourceAlias());
                                log.warn("降级:proc={} target={} 无 inputs,用 source_alias 降级为单源 DIRECT", procName, fm.getTargetField());
                            }
                        }
                    } else {
                        // 字面量场景:创建虚拟 __literal__ Column 节点
                        String literalColKey = "__literal__." + transformId;
                        mergeLiteralColumn(literalColKey, fm.getSourceExpr());
                        createInputToRelationship(literalColKey, transformId, "value", null);
                    }
                } else {
                    for (TableTransformInput ti : fmInputs) {
                        if (ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty()) continue;
                        String sourceTableFull = findTableFullName(edges, ti.getSourceTableShort());
                        if (sourceTableFull == null) continue;
                        String sourceColKey = toColKey(sourceTableFull, ti.getSourceField());
                        mergeColumnNode(sourceColKey, ti.getSourceField(), sourceTableFull);
                        mergeBelongsRelationship(sourceColKey, sourceTableFull);
                        createInputToRelationship(sourceColKey, transformId, ti.getRole(), ti.getSourceAlias());
                    }
                }

                // 4. OUTPUT_TO 关系
                createOutputToRelationship(transformId, targetColKey);
            }

            // DDL 补全字段：对所有涉及的表（源表+目标表，排除临时表），
            // 从 DDL 文件读取全部字段创建 Column 节点，保证字段元数据完整
            // （INSERT SELECT 只覆盖实际引用的列，DDL 中定义但未引用的列不会被字段映射覆盖）
            for (String fullName : involvedTables) {
                String shortName = extractShortName(fullName);
                // 临时表跳过（无 DDL 文件）
                if (isTempTableName(shortName)) continue;
                List<String> ddlFields = ddlParser.parse(shortName);
                if (ddlFields == null || ddlFields.isEmpty()) continue;
                for (String field : ddlFields) {
                    String colKey = toColKey(fullName, field);
                    mergeColumnNode(colKey, field, fullName);
                    mergeBelongsRelationship(colKey, fullName);
                }
            }

            // 更新 SOURCE 类型
            updateSourceTableTypes();

            // 清理孤儿节点（含 Transform）
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column OR n:Transform) AND NOT (n)--() DELETE n").run();

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
            // 1. 删除该 proc 的 READS 关系（不变）
            neo4jClient.query("MATCH ()-[r:READS {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();

            // 2. 删除该 proc 的 MAPS_TO 关系（过渡期兼容旧数据）
            neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();

            // 3. 删除该 proc 的 Transform 节点（DETACH DELETE 清除 INPUT_TO/OUTPUT_TO）
            neo4jClient.query("MATCH (t:Transform {proc_name: $procName}) DETACH DELETE t")
                    .bind(procName).to("procName").run();

            // 4. 删除 Script 节点及其关系（不变）
            neo4jClient.query("MATCH (s:Script {file: $scriptFile})-[r]->() DELETE r")
                    .bind(scriptFile).to("scriptFile").run();
            neo4jClient.query("MATCH (s:Script {file: $scriptFile}) DETACH DELETE s")
                    .bind(scriptFile).to("scriptFile").run();

            // 5. 清理孤儿节点（范围扩展含 Transform）
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column OR n:Transform) AND NOT (n)--() DELETE n").run();
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

    private void mergeTableNode(String fullName, String shortName, String scriptFile, boolean isTemp) {
        if (fullName == null || fullName.isEmpty()) return;
        String name = fullName.toLowerCase();
        String schema = extractSchema(fullName);
        String layer = schema != null ? schema.toUpperCase() : null;

        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("shortName", shortName);
        params.put("schemaPrefix", schema);
        params.put("layer", layer);
        params.put("isTemp", isTemp);
        params.put("scriptFile", scriptFile);
        // script_file 仅在非 null 时写入（目标表）；
        // 仅作源表时不覆盖已有 script_file（避免被其他 proc 读取时覆盖成错误的脚本）
        String cypher;
        if (scriptFile != null) {
            cypher = "MERGE (t:Table {name: $name}) " +
                    "SET t.short_name = $shortName, t.schema_prefix = $schemaPrefix, " +
                    "t.layer = $layer, t.is_temp = $isTemp, t.script_file = $scriptFile";
        } else {
            cypher = "MERGE (t:Table {name: $name}) " +
                    "SET t.short_name = $shortName, t.schema_prefix = $schemaPrefix, " +
                    "t.layer = $layer, t.is_temp = $isTemp";
        }
        neo4jClient.query(cypher).bindAll(params).run();
    }

    private void updateSourceTableTypes() {
        neo4jClient.query("MATCH (t:Table) " +
                "WHERE NOT (t)<-[:READS]-() AND t.layer IS NOT NULL " +
                "SET t.is_source = true").run();
    }

    /**
     * 统一临时表判断规则（不区分大小写）：
     * - VT_ 前缀
     * - TMP_ 前缀
     * - _TMP 后缀（含 _TMP1, _TMP2 等数字后缀）
     */
    private static boolean isTempTableName(String shortName) {
        if (shortName == null || shortName.isEmpty()) return false;
        String upper = shortName.toUpperCase();
        return upper.startsWith("VT_")
                || upper.startsWith("TMP_")
                || upper.matches(".*_TMP\\d*");
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
        String targetUpper = edge.getTargetTable().toUpperCase();
        String targetShort = targetUpper.contains(".") ? targetUpper.substring(targetUpper.lastIndexOf('.') + 1) : targetUpper;
        boolean viaTemp = isTempTableName(targetShort);

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

    /**
     * 生成 Transform 节点 id: md5(proc_name + "|" + target_col_key), 全小写
     */
    private String generateTransformId(String procName, String targetColKey) {
        String raw = procName + "|" + (targetColKey != null ? targetColKey.toLowerCase() : "");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("MD5 计算失败,降级 hashCode: {}", e.getMessage());
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * MERGE Transform 节点
     */
    private void mergeTransformNode(String id, String expression, String logicType,
                                     String sqlHint, String procName,
                                     String targetColKey, String scriptFile) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("expression", expression);
        params.put("logicType", logicType);
        params.put("sqlHint", sqlHint);
        params.put("procName", procName);
        params.put("targetColKey", targetColKey != null ? targetColKey.toLowerCase() : null);
        params.put("scriptFile", scriptFile);
        neo4jClient.query(
                "MERGE (t:Transform {id: $id}) " +
                "SET t.expression = $expression, t.logic_type = $logicType, " +
                "    t.sql_hint = $sqlHint, t.proc_name = $procName, " +
                "    t.target_col_key = $targetColKey, t.script_file = $scriptFile")
                .bindAll(params).run();
    }

    /**
     * 创建 INPUT_TO 关系: Column -[:INPUT_TO {role, source_alias}]-> Transform
     */
    private void createInputToRelationship(String sourceColKey, String transformId,
                                            String role, String sourceAlias) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceColKey", sourceColKey.toLowerCase());
        params.put("transformId", transformId);
        params.put("role", role);
        params.put("sourceAlias", sourceAlias);
        String cypher = "MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId}) " +
                "MERGE (c)-[r:INPUT_TO]->(t) " +
                "SET r.role = $role" +
                (sourceAlias != null ? ", r.source_alias = $sourceAlias" : "");
        neo4jClient.query(cypher).bindAll(params).run();
    }

    /**
     * 创建 OUTPUT_TO 关系: Transform -[:OUTPUT_TO]-> Column
     */
    private void createOutputToRelationship(String transformId, String targetColKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("transformId", transformId);
        params.put("targetColKey", targetColKey.toLowerCase());
        neo4jClient.query(
                "MATCH (t:Transform {id: $transformId}), (c:Column {col_key: $targetColKey}) " +
                "MERGE (t)-[:OUTPUT_TO]->(c)")
                .bindAll(params).run();
    }

    /**
     * MERGE 虚拟字面量 Column 节点
     * col_key = "__literal__." + transformId, 无 BELONGS_TO 关系
     */
    private void mergeLiteralColumn(String colKey, String expr) {
        Map<String, Object> params = new HashMap<>();
        params.put("colKey", colKey.toLowerCase());
        params.put("name", expr);
        params.put("tableFullName", "__literal__");
        neo4jClient.query(
                "MERGE (c:Column {col_key: $colKey}) " +
                "SET c.name = $name, c.table_full_name = $tableFullName")
                .bindAll(params).run();
    }

    /**
     * 生成 sql_hint
     * 格式: block_seq={n}; via_temp={temp_chain}
     */
    private String generateSqlHint(TableLineage edge, List<TableLineage> allEdges) {
        StringBuilder sb = new StringBuilder();
        sb.append("block_seq=").append(edge.getSqlBlockSeq());

        String targetShort = extractShortName(edge.getTargetTable());
        if (isTempTableName(targetShort)) {
            String tempChain = findTempUpstreamChain(edge.getTargetTable(), allEdges);
            if (tempChain != null) {
                sb.append("; via_temp=").append(tempChain);
            }
        }
        return sb.toString();
    }

    /**
     * 反查临时表的上游真实表链路
     * 防环: visited 集合记录已访问表
     */
    private String findTempUpstreamChain(String tempTable, List<TableLineage> allEdges) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = tempTable;
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            chain.add(current);
            String next = null;
            for (TableLineage e : allEdges) {
                if (e.getTargetTable().equalsIgnoreCase(current)) {
                    String sourceShort = extractShortName(e.getSourceTable());
                    if (!isTempTableName(sourceShort)) {
                        chain.add(e.getSourceTable());
                        return String.join("←", chain);
                    }
                    next = e.getSourceTable();
                    break;
                }
            }
            current = next;
        }
        return chain.size() > 1 ? String.join("←", chain) : null;
    }

    /**
     * 降级:从 sourceExpr 推断 logic_type
     * 仅用于兼容旧数据(table_field_mapping 无 logic_type 列时的回退)
     */
    private String inferLogicTypeFromExpr(String expr) {
        if (expr == null || expr.isBlank()) return "DIRECT";
        String t = expr.trim();
        if (t.matches("^'[^']*'$") || t.matches("^\\d+(\\.\\d+)?$")) return "LITERAL";
        if (t.matches("(?i)^CASE\\b.*END$")) return "CASE";
        String upper = t.toUpperCase();
        if (upper.startsWith("SUM(")) return "SUM";
        if (upper.startsWith("COUNT(")) return "COUNT";
        if (upper.startsWith("AVG(")) return "AVG";
        if (upper.startsWith("MAX(")) return "MAX";
        if (upper.startsWith("MIN(")) return "MIN";
        if (upper.startsWith("NVL(") || upper.startsWith("COALESCE(")) return "NVL";
        if (t.contains("||") || upper.startsWith("CONCAT(")) return "CONCAT";
        if (t.contains("+")) return "ADDITION";
        if (t.contains("-")) return "SUBTRACTION";
        if (t.contains("*")) return "MULTIPLICATION";
        if (t.contains("/")) return "DIVISION";
        if (t.matches("(?i)^\\w+\\s*\\(.*")) return "FUNCTION_OTHER";
        return "DIRECT";
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
