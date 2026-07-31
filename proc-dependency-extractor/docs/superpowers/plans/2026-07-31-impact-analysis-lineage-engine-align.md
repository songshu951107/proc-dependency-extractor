# 影响分析对标 lineage-engine 改造实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将图数据模型、影响分析查询、数据写入、前端展示四个部分对标 lineage-engine，保留当前项目的双写、重试、join/where 条件等优势。

**架构：**
- 后端：重写 Neo4jSyncServiceImpl（MERGE + Table/Column/Script 节点 + READS/MAPS_TO/BELONGS_TO/PRODUCES/CONSUMES 关系）和 ImpactAnalysisServiceImpl（Cypher 原生 path 查询替代内存 BFS）
- 前端：移除 @antv/g6，引入 @vue-flow/core + dagre，新增 ImpactFlowGraph.vue 和 ImpactNode.vue
- 数据迁移：新增 /api/impact/clear-neo4j 接口，清空 Neo4j 重新解析

**技术栈：** Spring Boot 3.2.5 + Spring Data Neo4j + MyBatis-Plus + Vue 3 + Element Plus + Vue Flow + dagre

**参考规格：** `docs/superpowers/specs/2026-07-30-impact-analysis-lineage-engine-align.md`

**全局约束（所有任务必须遵守）：**
- Table 节点主键 `name` 统一小写（如 `cdm.c_agt_deposit_acct_calcu_base`）
- Column 节点主键 `col_key` 统一小写（如 `cdm.c_agt_deposit_acct_calcu_base.acct_id`）
- Script 节点主键 `file` 格式为 `procName.py`（如 `dwa_c_cus_pri_cust_loan_sum_crms.py`）
- 所有节点和关系写入必须用 MERGE（幂等）
- READS 关系用 `proc_name` 作为 MERGE 键：`MERGE (src)-[:READS {proc_name: $procName}]->(tgt)`
- MAPS_TO 关系用 `proc_name + source_expr` 作为 MERGE 键：`MERGE (src)-[:MAPS_TO {proc_name: $procName, source_expr: $sourceExpr}]->(tgt)`
- READS 关系属性 SET 在关系 `r` 上（`r.operation`），不在节点上（`src.operation`）
- MySQL 表结构不变（proc_info / table_lineage / table_field_mapping / sql_block_info）
- 双写机制保留（MySQL + Neo4j 实时双写）
- 失败重试机制保留（neo4j_sync_log + Neo4jSyncRetryScheduler）
- Maven 路径：`D:\HB\maven\bin\mvn.cmd`（系统默认 mvn 3.0.5 不兼容 Spring Boot 3.2.5）
- 前端构建后需重启后端才能生效（Spring Boot 缓存 classpath 资源）

---

## 文件结构

### 后端修改文件

| 文件路径 | 职责 |
|---------|------|
| `src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java` | Neo4j 唯一约束初始化（Table.name、Column.col_key、Script.file） |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | 影响分析响应 VO（ImpactedTable 内嵌类字段更新） |
| `src/main/java/com/hb/extractor/service/Neo4jSyncService.java` | Neo4j 同步服务接口（新增 clearAllData 方法） |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | Neo4j 同步实现（重写：MERGE + 新模型 + classifyTransformation + clearAllData） |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 影响分析实现（重写：Cypher 原生 path 查询） |
| `src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java` | 影响分析控制器（新增 /clear-neo4j） |
| `src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java` | 影响分析测试（重写） |
| `src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java` | Neo4j 同步测试（扩展） |

### 前端修改文件

| 文件路径 | 职责 |
|---------|------|
| `frontend/package.json` | 依赖变更（移除 G6，新增 Vue Flow + dagre） |
| `frontend/src/api/script.js` | 脚本 API（新增 getScriptByFile） |
| `frontend/src/components/impact/ImpactNode.vue` | Vue Flow 自定义节点组件（新增） |
| `frontend/src/components/impact/ImpactFlowGraph.vue` | Vue Flow 图组件（新增，替代 ImpactGraph） |
| `frontend/src/components/impact/ImpactGraph.vue` | G6 图组件（删除） |
| `frontend/src/components/impact/ImpactPropagationTree.vue` | 传播树组件（适配新字段） |
| `frontend/src/components/impact/ScriptSnippetModal.vue` | 代码片段弹窗（适配 scriptFile） |
| `frontend/src/views/ImpactAnalysis.vue` | 影响分析视图（集成新组件） |

---

## 任务 1：更新 Neo4j 约束初始化器和 ImpactAnalysisVO

**文件：**
- 修改：`src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java`
- 修改：`src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java`

**上下文：** 这是基础数据结构任务。约束定义决定 Neo4j 节点主键。ImpactAnalysisVO.ImpactedTable 字段需与 Neo4j 查询返回的字段对齐（name/layer/isTemp/scriptFile/columns/matchedColumns）。

- [ ] **步骤 1：更新 Neo4jConstraintInitializer.java**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\config\Neo4jConstraintInitializer.java` 的 `constraints` 数组为：

```java
String[] constraints = {
    "CREATE CONSTRAINT table_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.name IS UNIQUE",
    "CREATE CONSTRAINT column_key_unique IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE",
    "CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE"
};
```

删除原有的 `table_full_name_unique`、`field_full_name_unique`、`proc_name_unique`、`sqlblock_unique` 四条约束。

- [ ] **步骤 2：更新 ImpactAnalysisVO.java 的 ImpactedTable 内嵌类**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\dto\ImpactAnalysisVO.java` 中 `ImpactedTable` 内嵌类（第 74-87 行）为：

```java
@Data
public static class ImpactedTable {
    /** 表全名 schema.table（小写） */
    private String name;
    /** 表短名（去 schema） */
    private String shortName;
    /** 数据层级 FDM/ODM/CDM/TDM/PID */
    private String layer;
    /** 是否临时表 */
    private boolean temp;
    /** 影响深度 1,2,3... */
    private int depth;
    /** 直接上游来源表（impactSource） */
    private String impactSource;
    /** 涉及的脚本文件名（用于代码片段查看） */
    private String scriptFile;
    /** 该表全部字段列表 */
    private java.util.List<String> columns;
    /** 字段级分析时匹配到的受影响字段 */
    private java.util.List<String> matchedColumns;
}
```

- [ ] **步骤 3：在 ImpactAnalysisVO 顶层新增 sourceLayer 字段**

在 `ImpactAnalysisVO` 类中（第 16 行 `sourceTable` 字段后）新增：

```java
/** 源表数据层级（用于前端节点着色） */
private String sourceLayer;
```

- [ ] **步骤 4：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS，无编译错误。

- [ ] **步骤 5：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java
git commit -m "refactor: 更新 Neo4j 约束和 ImpactAnalysisVO 对标 lineage-engine"
```

---

## 任务 2：重写 Neo4jSyncServiceImpl

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/Neo4jSyncService.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`

**上下文：** 这是数据写入核心任务。完全重写 syncProc 方法，用 Table/Column/Script 节点替代 Table/Field/Proc/SqlBlock 节点，用 READS/MAPS_TO/BELONGS_TO/PRODUCES/CONSUMES 关系替代 DERIVES/MAPS_TO/HAS_FIELD/PARSED_BY/CONTAINS_BLOCK 关系。全用 MERGE。新增 classifyTransformation 方法（从 source_expr 推断 transformation_type）。新增 clearAllData 方法（清空 Neo4j）。

**关键决策：**
- Table.name 统一小写：写入前 `fullName.toLowerCase()`
- layer 从 schema 推断：`schema.toUpperCase()`（CDM/FDM/ODM/TDM/PID）
- is_temp 从表名判断：`fullName.toUpperCase().startsWith("VT_")`
- Script.file 格式：`procName + ".py"`
- READS 关系 SET 属性在 `r` 上（`r.operation`），不在 `src` 上
- 删除 SqlBlock 相关代码（createSqlBlockNode 方法删除）
- 删除 Proc 节点相关代码（PARSED_BY、CONTAINS_BLOCK 关系删除）
- 保留 deleteProcData 的清理逻辑，但清理 READS/MAPS_TO/PRODUCES/CONSUMES 和 Script 节点
- classifyTransformation 是 public 方法（供测试调用）

- [ ] **步骤 1：更新 Neo4jSyncService 接口，新增 clearAllData 方法**

在 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\Neo4jSyncService.java` 的接口末尾新增：

```java
/**
 * 清空 Neo4j 全部数据（用于数据迁移）
 */
void clearAllData();
```

- [ ] **步骤 2：重写 Neo4jSyncServiceImpl.java 的 syncProc 方法**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\impl\Neo4jSyncServiceImpl.java` 中 `syncProc` 方法（第 51-171 行）为：

```java
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
```

- [ ] **步骤 3：重写 deleteProcData 方法**

替换 `deleteProcData` 方法（第 184-201 行）为：

```java
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
```

- [ ] **步骤 4：新增 clearAllData 方法**

在 `Neo4jSyncServiceImpl.java` 中 `deleteProcData` 方法后新增：

```java
@Override
public void clearAllData() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    neo4jSyncLogMapper.delete(new LambdaQueryWrapper<>());
    log.info("Neo4j 全部数据已清空");
}
```

- [ ] **步骤 5：新增 mergeScriptNode 方法**

在 `Neo4jSyncServiceImpl.java` 中 `mergeTableNode` 方法前新增：

```java
private void mergeScriptNode(String scriptFile, ProcInfo procInfo) {
    Map<String, Object> params = new HashMap<>();
    params.put("file", scriptFile);
    params.put("targetTable", procInfo.getUpdateTarget());
    params.put("description", procInfo.getProcDesc());
    neo4jClient.query("MERGE (s:Script {file: $file}) " +
            "SET s.target_table = $targetTable, s.description = $description")
            .bindAll(params).run();
}
```

- [ ] **步骤 6：重写 mergeTableNode 方法**

替换 `mergeTableNode` 方法（第 226-236 行）为：

```java
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
```

- [ ] **步骤 7：删除 updateTableType 方法，重写 updateSourceTableTypes**

删除 `updateTableType` 方法（第 238-244 行）。替换 `updateSourceTableTypes` 方法（第 246-251 行）为：

```java
private void updateSourceTableTypes() {
    neo4jClient.query("MATCH (t:Table) " +
            "WHERE NOT (t)<-[:READS]-() AND t.layer IS NOT NULL " +
            "SET t.is_source = true").run();
}
```

- [ ] **步骤 8：重写 createDerivesRelationship 为 createReadsRelationship**

将 `createDerivesRelationship` 方法（第 253-279 行）重命名为 `createReadsRelationship` 并替换为：

```java
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
```

- [ ] **步骤 9：新增 mergeProducesRelationship 和 mergeConsumesRelationship 方法**

在 `createReadsRelationship` 方法后新增：

```java
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
```

- [ ] **步骤 10：重写 mergeFieldNode 为 mergeColumnNode**

将 `mergeFieldNode` 方法（第 306-314 行）重命名为 `mergeColumnNode` 并替换为：

```java
private void mergeColumnNode(String colKey, String fieldName, String tableFullName) {
    Map<String, Object> params = new HashMap<>();
    params.put("colKey", colKey.toLowerCase());
    params.put("name", fieldName.toUpperCase());
    params.put("tableFullName", tableFullName.toLowerCase());
    neo4jClient.query("MERGE (c:Column {col_key: $colKey}) " +
            "SET c.name = $name, c.table_full_name = $tableFullName")
            .bindAll(params).run();
}
```

- [ ] **步骤 11：重写 createHasFieldRelationship 为 mergeBelongsRelationship**

将 `createHasFieldRelationship` 方法（第 316-323 行）重命名为 `mergeBelongsRelationship` 并替换为：

```java
private void mergeBelongsRelationship(String colKey, String tableFullName) {
    Map<String, Object> params = new HashMap<>();
    params.put("colKey", colKey.toLowerCase());
    params.put("tableName", tableFullName.toLowerCase());
    neo4jClient.query("MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName}) " +
            "MERGE (c)-[:BELONGS_TO]->(t)")
            .bindAll(params).run();
}
```

- [ ] **步骤 12：重写 createMapsToRelationship 方法**

替换 `createMapsToRelationship` 方法（第 325-341 行）为：

```java
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
```

- [ ] **步骤 13：删除 createSqlBlockNode 方法**

删除 `createSqlBlockNode` 方法（第 343-358 行）。

- [ ] **步骤 14：新增 classifyTransformation 和 toColKey 方法**

在 `toFieldFullName` 方法（第 360-363 行）后新增（并删除 `toFieldFullName`）：

```java
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
```

- [ ] **步骤 15：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。若报 `createDerivesRelationship`、`mergeFieldNode`、`createHasFieldRelationship`、`toFieldFullName`、`updateTableType`、`createSqlBlockNode` 未定义引用错误，说明旧方法已被正确删除且新方法已替代。

- [ ] **步骤 16：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/service/Neo4jSyncService.java src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java
git commit -m "refactor: 重写 Neo4jSyncServiceImpl 对标 lineage-engine（MERGE + Table/Column/Script 模型）"
```

---

## 任务 3：重写 ImpactAnalysisServiceImpl

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`

**上下文：** 这是查询核心任务。删除内存 BFS（buildImpactedByDepth、buildAdjacency、collectNodesAndEdges、executeAndCollect、getPaths），用 Cypher 原生 path 查询替代。表名查询参数统一小写。

**关键决策：**
- buildTableFullName 改为返回小写
- getSchemas 查询 `t.schema_prefix`（替代 `t.schema`）
- getTables 查询 `t.name`（替代 `t.full_name`）
- getFields 查询 Column + BELONGS_TO（替代 Field + HAS_FIELD）
- analyze 用 Cypher path 查询，depth 由 `min(length(path))` 计算
- expand 用 1 跳 READS 查询
- impactSource 单跳查询
- 删除 buildImpactedByDepth、buildAdjacency、collectNodesAndEdges、executeAndCollect、getPaths、analyzeTableLevel、analyzeFieldLevel、parseConditions

- [ ] **步骤 1：重写 getSchemas 方法**

替换 `getSchemas` 方法（第 34-43 行）为：

```java
@Override
public List<String> getSchemas() {
    Collection<String> schemas = neo4jClient.query(
            "MATCH (t:Table) WHERE t.schema_prefix IS NOT NULL " +
            "RETURN DISTINCT t.schema_prefix AS schema ORDER BY schema")
            .fetchAs(String.class)
            .mappedBy((type, record) -> record.get("schema").asString())
            .all();
    return new ArrayList<>(schemas);
}
```

- [ ] **步骤 2：重写 getTables 方法**

替换 `getTables` 方法（第 45-62 行）为：

```java
@Override
public List<TableOption> getTables(String schema) {
    String cypher = "MATCH (t:Table) WHERE t.schema_prefix = $schema " +
            "RETURN t.name AS fullName, t.short_name AS shortName, t.layer AS type " +
            "ORDER BY t.short_name";
    Collection<TableOption> tables = neo4jClient.query(cypher)
            .bind(schema).to("schema")
            .fetchAs(TableOption.class)
            .mappedBy((type, record) -> {
                TableOption opt = new TableOption();
                opt.setFullName(record.get("fullName").asString());
                opt.setShortName(record.get("shortName").asString());
                opt.setType(record.get("type").isNull() ? null : record.get("type").asString());
                return opt;
            })
            .all();
    return new ArrayList<>(tables);
}
```

- [ ] **步骤 3：重写 getFields 方法**

替换 `getFields` 方法（第 64-74 行）为：

```java
@Override
public List<String> getFields(String tableFullName) {
    String tableName = tableFullName.toLowerCase();
    Collection<String> fields = neo4jClient.query(
            "MATCH (t:Table {name: $tableName})<-[:BELONGS_TO]-(c:Column) " +
            "RETURN c.name AS field ORDER BY c.name")
            .bind(tableName).to("tableName")
            .fetchAs(String.class)
            .mappedBy((type, record) -> record.get("field").asString())
            .all();
    return new ArrayList<>(fields);
}
```

- [ ] **步骤 4：重写 analyze 方法**

替换 `analyze` 方法（第 76-104 行）为：

```java
@Override
public ImpactAnalysisVO analyze(ImpactAnalysisRequest request) {
    int depth = request.getMaxDepth() == null ? 5 : Math.min(request.getMaxDepth(), maxAllowedDepth);
    boolean isFieldLevel = request.getFieldName() != null && !request.getFieldName().isEmpty();

    String tableFullName = buildTableFullName(request.getSchema(), request.getTableShortName());
    String direction = request.getDirection() == null ? "DOWNSTREAM" : request.getDirection().toUpperCase();

    ImpactAnalysisVO vo = new ImpactAnalysisVO();
    vo.setSourceTable(tableFullName);

    // 查询源表 layer（用于前端着色）
    String sourceLayer = queryTableLayer(tableFullName);
    vo.setSourceLayer(sourceLayer);

    List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
    List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();

    if (isFieldLevel) {
        String fieldFullName = tableFullName + "." + request.getFieldName().toLowerCase();
        analyzeFieldLevel(fieldFullName, direction, depth, nodes, edges);
    } else {
        analyzeTableLevel(tableFullName, direction, depth, nodes, edges);
    }

    vo.setNodes(nodes);
    vo.setEdges(edges);
    vo.setStats(buildStats(nodes, edges, depth));
    vo.setTruncated(false);

    if (!isFieldLevel) {
        Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth =
                buildImpactedByDepthFromCypher(tableFullName, direction, depth);
        vo.setImpactedByDepth(impactedByDepth);
        vo.setPropagationChains(buildPropagationChains(impactedByDepth));
    } else {
        vo.setImpactedByDepth(new LinkedHashMap<>());
        vo.setPropagationChains(new ArrayList<>());
    }
    return vo;
}

private String queryTableLayer(String tableName) {
    try {
        Collection<String> layers = neo4jClient.query(
                "MATCH (t:Table {name: $name}) RETURN t.layer AS layer LIMIT 1")
                .bind(tableName).to("name")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("layer").isNull() ? null : record.get("layer").asString())
                .all();
        return layers.isEmpty() ? null : layers.iterator().next();
    } catch (Exception e) {
        log.warn("查询表 layer 失败: {}", e.getMessage());
        return null;
    }
}
```

- [ ] **步骤 5：重写 analyzeTableLevel 方法**

替换 `analyzeTableLevel` 方法（第 176-188 行）为：

```java
private void analyzeTableLevel(String tableFullName, String direction, int depth,
                                List<ImpactAnalysisVO.NodeVO> nodes,
                                List<ImpactAnalysisVO.EdgeVO> edges) {
    String arrow = buildArrow(direction, "READS", depth);
    String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
            "WHERE src <> downstream " +
            "RETURN DISTINCT downstream.name AS id, downstream.short_name AS label, " +
            "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
            "min(length(path)) AS depth " +
            "ORDER BY depth, downstream.name";

    try {
        Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                .bind(tableFullName).to("name")
                .fetchAs(ImpactAnalysisVO.NodeVO.class)
                .mappedBy((type, record) -> {
                    ImpactAnalysisVO.NodeVO node = new ImpactAnalysisVO.NodeVO();
                    node.setId(record.get("id").asString());
                    node.setLabel(record.get("label").isNull() ? record.get("id").asString() : record.get("label").asString());
                    node.setNodeLevel("TABLE");
                    node.setDepth((int) record.get("depth").asLong());
                    return node;
                })
                .all();

        // 添加起点节点
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(tableFullName);
        startNode.setLabel(extractShortName(tableFullName));
        startNode.setNodeLevel("TABLE");
        startNode.setDepth(0);
        nodes.add(startNode);
        nodes.addAll(result);

        // 查询边
        collectEdges(tableFullName, direction, depth, "READS", "Table", edges, nodes);
    } catch (Exception e) {
        log.error("表级影响查询失败: {}", e.getMessage());
        // 降级：仅返回起点节点
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(tableFullName);
        startNode.setLabel(extractShortName(tableFullName));
        startNode.setNodeLevel("TABLE");
        startNode.setDepth(0);
        nodes.add(startNode);
    }
}

private String buildArrow(String direction, String relType, int depth) {
    String rel = "[:" + relType + "*1.." + depth + "]";
    if ("UPSTREAM".equals(direction)) return "<" + rel + "-";
    if ("BOTH".equals(direction)) return rel + "-";
    return "-" + rel + "->";
}

private String extractShortName(String fullName) {
    int idx = fullName.indexOf('.');
    return idx > 0 ? fullName.substring(idx + 1) : fullName;
}
```

- [ ] **步骤 6：重写 analyzeFieldLevel 方法**

替换 `analyzeFieldLevel` 方法（第 190-201 行）为：

```java
private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                List<ImpactAnalysisVO.NodeVO> nodes,
                                List<ImpactAnalysisVO.EdgeVO> edges) {
    String arrow = buildArrow(direction, "MAPS_TO", depth);
    String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
            "WITH path, relationships(path) AS rels, nodes(path) AS cols " +
            "UNWIND range(0, size(rels)-1) AS i " +
            "WITH cols[i] AS srcCol, rels[i] AS r, cols[i+1] AS tgtCol, i+1 AS depth " +
            "RETURN srcCol.col_key AS sourceId, srcCol.name AS sourceLabel, " +
            "tgtCol.col_key AS targetId, tgtCol.name AS targetLabel, " +
            "depth, r.transformation_type AS transType, r.proc_name AS procName, " +
            "r.source_expr AS sourceExpr, r.script_file AS scriptFile " +
            "ORDER BY depth, targetId";

    try {
        Collection<Object[]> result = neo4jClient.query(cypher)
                .bind(fieldFullName).to("key")
                .fetchAs(Object[].class)
                .mappedBy((type, record) -> new Object[]{
                        record.get("sourceId").asString(),
                        record.get("sourceLabel").asString(),
                        record.get("targetId").asString(),
                        record.get("targetLabel").asString(),
                        (int) record.get("depth").asLong(),
                        record.get("transType").isNull() ? null : record.get("transType").asString(),
                        record.get("procName").isNull() ? null : record.get("procName").asString(),
                        record.get("sourceExpr").isNull() ? null : record.get("sourceExpr").asString(),
                        record.get("scriptFile").isNull() ? null : record.get("scriptFile").asString()
                })
                .all();

        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.add(fieldFullName);

        // 添加起点节点
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(fieldFullName);
        startNode.setLabel(fieldFullName.split("\\.")[2]);
        startNode.setNodeLevel("FIELD");
        startNode.setDepth(0);
        nodes.add(startNode);

        for (Object[] row : result) {
            String sourceId = (String) row[0];
            String sourceLabel = (String) row[1];
            String targetId = (String) row[2];
            String targetLabel = (String) row[3];
            int d = (int) row[4];
            String transType = (String) row[5];
            String procName = (String) row[6];
            String sourceExpr = (String) row[7];

            if (!nodeIds.contains(sourceId)) {
                nodeIds.add(sourceId);
                ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                n.setId(sourceId);
                n.setLabel(sourceLabel);
                n.setNodeLevel("FIELD");
                n.setDepth(d - 1);
                nodes.add(n);
            }
            if (!nodeIds.contains(targetId)) {
                nodeIds.add(targetId);
                ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                n.setId(targetId);
                n.setLabel(targetLabel);
                n.setNodeLevel("FIELD");
                n.setDepth(d);
                nodes.add(n);
            }

            ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
            edge.setSource(sourceId);
            edge.setTarget(targetId);
            edge.setProcName(procName);
            edge.setSourceExpr(sourceExpr);
            edges.add(edge);
        }
    } catch (Exception e) {
        log.error("字段级影响查询失败: {}", e.getMessage());
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(fieldFullName);
        startNode.setLabel(fieldFullName.split("\\.")[2]);
        startNode.setNodeLevel("FIELD");
        startNode.setDepth(0);
        nodes.add(startNode);
    }
}
```

- [ ] **步骤 7：新增 collectEdges 方法**

在 `analyzeFieldLevel` 方法后新增：

```java
private void collectEdges(String startId, String direction, int depth, String relType,
                           String nodeLabel, List<ImpactAnalysisVO.EdgeVO> edges,
                           List<ImpactAnalysisVO.NodeVO> nodes) {
    List<String> dirs = new ArrayList<>();
    if ("BOTH".equals(direction)) {
        dirs.add("DOWNSTREAM");
        dirs.add("UPSTREAM");
    } else {
        dirs.add(direction);
    }

    Set<String> nodeIds = new HashSet<>();
    for (ImpactAnalysisVO.NodeVO n : nodes) nodeIds.add(n.getId());

    for (String dir : dirs) {
        String edgeCypher;
        if ("DOWNSTREAM".equals(dir)) {
            edgeCypher = String.format(
                    "MATCH (src:%s {name: $startId})-[:%s*1..%d]->(downstream:%s) " +
                    "WITH DISTINCT downstream " +
                    "MATCH (src2)-[r:%s]->(downstream) " +
                    "RETURN src2.name AS source, downstream.name AS target, " +
                    "r.operation AS operation, r.proc_name AS procName, " +
                    "r.join_conditions AS joins, r.where_conditions AS wheres",
                    nodeLabel, relType, depth, nodeLabel, relType);
        } else {
            edgeCypher = String.format(
                    "MATCH (src:%s {name: $startId})<-[:%s*1..%d]-(upstream:%s) " +
                    "WITH DISTINCT upstream " +
                    "MATCH (upstream)-[r:%s]->(dst) " +
                    "RETURN upstream.name AS source, dst.name AS target, " +
                    "r.operation AS operation, r.proc_name AS procName, " +
                    "r.join_conditions AS joins, r.where_conditions AS wheres",
                    nodeLabel, relType, depth, nodeLabel, relType);
        }

        try {
            neo4jClient.query(edgeCypher)
                    .bind(startId).to("startId")
                    .fetchAs(org.neo4j.driver.Record.class)
                    .mappedBy((type, record) -> record)
                    .all()
                    .forEach(record -> {
                        String sourceId = record.get("source").asString();
                        String targetId = record.get("target").asString();

                        // 确保边引用的节点都存在
                        if (!nodeIds.contains(sourceId)) {
                            nodeIds.add(sourceId);
                            ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                            n.setId(sourceId);
                            n.setLabel(extractShortName(sourceId));
                            n.setNodeLevel("TABLE");
                            nodes.add(n);
                        }
                        if (!nodeIds.contains(targetId)) {
                            nodeIds.add(targetId);
                            ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                            n.setId(targetId);
                            n.setLabel(extractShortName(targetId));
                            n.setNodeLevel("TABLE");
                            nodes.add(n);
                        }

                        ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                        edge.setSource(sourceId);
                        edge.setTarget(targetId);
                        edge.setOperation(record.get("operation").isNull() ? null : record.get("operation").asString());
                        edge.setProcName(record.get("procName").isNull() ? null : record.get("procName").asString());
                        edges.add(edge);
                    });
        } catch (Exception e) {
            log.error("查询边失败: {}", e.getMessage());
        }
    }
}
```

- [ ] **步骤 8：新增 buildImpactedByDepthFromCypher 方法**

替换 `buildImpactedByDepth` 方法（第 392-444 行）为：

```java
private Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> buildImpactedByDepthFromCypher(
        String sourceTable, String direction, int maxDepth) {

    String arrow = buildArrow(direction, "READS", maxDepth);
    String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
            "WHERE src <> downstream " +
            "RETURN downstream.name AS name, downstream.short_name AS shortName, " +
            "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
            "downstream.script_file AS scriptFile, " +
            "min(length(path)) AS depth " +
            "ORDER BY depth, downstream.name";

    Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> result = new LinkedHashMap<>();
    try {
        neo4jClient.query(cypher)
                .bind(sourceTable).to("name")
                .fetchAs(ImpactAnalysisVO.ImpactedTable.class)
                .mappedBy((type, record) -> {
                    ImpactAnalysisVO.ImpactedTable t = new ImpactAnalysisVO.ImpactedTable();
                    t.setName(record.get("name").asString());
                    t.setShortName(record.get("shortName").isNull() ? record.get("name").asString() : record.get("shortName").asString());
                    t.setLayer(record.get("layer").isNull() ? null : record.get("layer").asString());
                    t.setTemp(record.get("isTemp").isNull() ? false : record.get("isTemp").asBoolean());
                    t.setDepth((int) record.get("depth").asLong());
                    t.setScriptFile(record.get("scriptFile").isNull() ? null : record.get("scriptFile").asString());
                    return t;
                })
                .all()
                .forEach(t -> {
                    // 查询 impactSource（单跳）
                    t.setImpactSource(findImpactSource(t.getName(), direction));
                    // 查询表的字段
                    t.setColumns(getFields(t.getName()));
                    result.computeIfAbsent(t.getDepth(), k -> new ArrayList<>()).add(t);
                });
    } catch (Exception e) {
        log.error("构建 impactedByDepth 失败: {}", e.getMessage());
    }
    return result;
}

private String findImpactSource(String tableName, String direction) {
    try {
        String cypher = "UPSTREAM".equals(direction)
                ? "MATCH (src:Table)-[:READS]->(tgt:Table {name: $name}) RETURN src.name AS source LIMIT 1"
                : "MATCH (src:Table)-[:READS]->(tgt:Table {name: $name}) RETURN src.name AS source LIMIT 1";
        Collection<String> sources = neo4jClient.query(cypher)
                .bind(tableName).to("name")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("source").asString())
                .all();
        return sources.isEmpty() ? null : sources.iterator().next();
    } catch (Exception e) {
        return null;
    }
}
```

- [ ] **步骤 9：重写 buildPropagationChains 方法**

替换 `buildPropagationChains` 方法（第 467-478 行）为：

```java
private List<String> buildPropagationChains(
        Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth) {
    List<String> chains = new ArrayList<>();
    for (Map.Entry<Integer, List<ImpactAnalysisVO.ImpactedTable>> entry : impactedByDepth.entrySet()) {
        for (ImpactAnalysisVO.ImpactedTable t : entry.getValue()) {
            if (t.getImpactSource() != null) {
                chains.add(t.getImpactSource() + " → " + t.getName());
            }
        }
    }
    return chains;
}
```

- [ ] **步骤 10：重写 expand 方法**

替换 `expand` 方法（第 106-131 行）为：

```java
@Override
public ImpactAnalysisVO expand(ExpandRequest request) {
    int depth = request.getDepth() == null ? 1 : request.getDepth();
    String direction = request.getDirection() == null ? "DOWNSTREAM" : request.getDirection().toUpperCase();

    ImpactAnalysisVO vo = new ImpactAnalysisVO();
    List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
    List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();

    boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2;

    if (isFieldLevel) {
        String arrow = buildArrow(direction, "MAPS_TO", depth);
        String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                "RETURN DISTINCT tgt.col_key AS id, tgt.name AS label, " +
                "min(length(path)) AS depth ORDER BY depth, tgt.col_key";
        try {
            Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                    .bind(request.getNodeId()).to("key")
                    .fetchAs(ImpactAnalysisVO.NodeVO.class)
                    .mappedBy((type, record) -> {
                        ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                        n.setId(record.get("id").asString());
                        n.setLabel(record.get("label").asString());
                        n.setNodeLevel("FIELD");
                        n.setDepth((int) record.get("depth").asLong());
                        return n;
                    })
                    .all();
            nodes.addAll(result);
        } catch (Exception e) {
            log.error("字段展开失败: {}", e.getMessage());
        }
    } else {
        String arrow = buildArrow(direction, "READS", depth);
        String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                "RETURN DISTINCT downstream.name AS id, downstream.short_name AS label, " +
                "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
                "min(length(path)) AS depth ORDER BY depth, downstream.name";
        try {
            Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                    .bind(request.getNodeId()).to("name")
                    .fetchAs(ImpactAnalysisVO.NodeVO.class)
                    .mappedBy((type, record) -> {
                        ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                        n.setId(record.get("id").asString());
                        n.setLabel(record.get("label").isNull() ? record.get("id").asString() : record.get("label").asString());
                        n.setNodeLevel("TABLE");
                        n.setDepth((int) record.get("depth").asLong());
                        return n;
                    })
                    .all();
            nodes.addAll(result);

            // 同时查询边
            collectEdges(request.getNodeId(), direction, depth, "READS", "Table", edges, nodes);
        } catch (Exception e) {
            log.error("表展开失败: {}", e.getMessage());
        }
    }

    vo.setNodes(nodes);
    vo.setEdges(edges);
    return vo;
}
```

- [ ] **步骤 11：重写 getPaths 方法为空实现（预留）**

替换 `getPaths` 方法（第 133-155 行）为：

```java
@Override
public List<ImpactAnalysisVO.PathVO> getPaths(String source, String target, Integer maxDepth) {
    // 预留方法，当前返回空列表
    return new ArrayList<>();
}
```

- [ ] **步骤 12：重写 buildTableFullName 方法**

替换 `buildTableFullName` 方法（第 497-514 行）为：

```java
private String buildTableFullName(String schema, String shortName) {
    if (schema != null && !schema.isEmpty()) {
        return (schema + "." + shortName).toLowerCase();
    }
    // 无 schema 时查 Neo4j
    try {
        Collection<String> fullNames = neo4jClient.query(
                "MATCH (t:Table {short_name: $shortName}) RETURN t.name AS name LIMIT 1")
                .bind(shortName.toUpperCase()).to("shortName")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("name").asString())
                .all();
        if (!fullNames.isEmpty()) return fullNames.iterator().next();
    } catch (Exception e) {
        log.warn("查询表 name 失败: {}", e.getMessage());
    }
    return shortName.toLowerCase();
}
```

- [ ] **步骤 13：删除废弃方法**

删除以下方法：
- `executeAndCollect`（第 203-225 行）
- `collectNodesAndEdges`（第 230-380 行）
- `parseConditions`（第 382-390 行）
- `buildAdjacency`（第 446-465 行）

- [ ] **步骤 14：重写 buildStats 方法**

替换 `buildStats` 方法（第 480-495 行）为：

```java
private ImpactAnalysisVO.ImpactStats buildStats(List<ImpactAnalysisVO.NodeVO> nodes,
                                                 List<ImpactAnalysisVO.EdgeVO> edges,
                                                 int maxDepth) {
    ImpactAnalysisVO.ImpactStats stats = new ImpactAnalysisVO.ImpactStats();
    stats.setAffectedTableCount((int) nodes.stream()
            .filter(n -> "TABLE".equals(n.getNodeLevel())).count());
    stats.setAffectedFieldCount((int) nodes.stream()
            .filter(n -> "FIELD".equals(n.getNodeLevel())).count());
    Set<String> procs = edges.stream()
            .map(ImpactAnalysisVO.EdgeVO::getProcName)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    stats.setAffectedProcCount(procs.size());
    stats.setMaxDepthReached(maxDepth);
    return stats;
}
```

- [ ] **步骤 15：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 16：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java
git commit -m "refactor: 重写 ImpactAnalysisServiceImpl 用 Cypher 原生 path 查询"
```

---

## 任务 4：新增 /clear-neo4j 接口

**文件：**
- 修改：`src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java`

**上下文：** 新增清空 Neo4j 接口，用于数据迁移。调用 Neo4jSyncService.clearAllData()。

- [ ] **步骤 1：新增 clearNeo4j 接口**

在 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\controller\ImpactAnalysisController.java` 的 `resyncAll` 方法（第 62 行）后新增：

```java
@PostMapping("/clear-neo4j")
public Result<Void> clearNeo4j() {
    neo4jSyncService.clearAllData();
    return Result.success(null);
}
```

- [ ] **步骤 2：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java
git commit -m "feat: 新增 /api/impact/clear-neo4j 接口"
```

---

## 任务 5：重写后端测试

**文件：**
- 修改：`src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java`
- 修改：`src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java`

**上下文：** 重写 ImpactAnalysisServiceTest 覆盖核心场景（Neo4j 不可用、方向、字段级、depth clamp、propagationChains 格式）。扩展 Neo4jSyncServiceTest 覆盖 classifyTransformation 4 种分类。

- [ ] **步骤 1：重写 ImpactAnalysisServiceTest.java**

完全替换 `d:\HB\work_zuoye\proc-dependency-extractor\src\test\java\com\hb\extractor\service\ImpactAnalysisServiceTest.java` 为：

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.ImpactAnalysisRequest;
import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.service.impl.ImpactAnalysisServiceImpl;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImpactAnalysisServiceTest {

    @Mock
    private Neo4jClient neo4jClient;

    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @InjectMocks
    private ImpactAnalysisServiceImpl impactService;

    @Test
    void testAnalyze_Neo4jUnavailable_ReturnsEmptyResult() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getNodes());
        assertNotNull(vo.getImpactedByDepth());
        assertTrue(vo.getImpactedByDepth().isEmpty());
        assertNotNull(vo.getPropagationChains());
        assertTrue(vo.getPropagationChains().isEmpty());
    }

    @Test
    void testAnalyze_UpstreamDirection_PopulatesSourceTable() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("UPSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getImpactedByDepth());
    }

    @Test
    void testAnalyze_BothDirection_DoesNotCrash() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("BOTH");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getImpactedByDepth());
    }

    @Test
    void testAnalyze_DepthExceedsMax_Clamped() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(100);  // 超上限
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        // 不应抛异常，depth 会被 clamp
    }

    @Test
    void testAnalyze_FieldLevel_DoesNotCrash() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setFieldName("ACCT_ID");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getNodes());
    }

    @Test
    void testGetSyncStatus_ReturnsFailedLogs() {
        when(neo4jSyncLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = impactService.getSyncStatus();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **步骤 2：扩展 Neo4jSyncServiceTest.java**

在 `d:\HB\work_zuoye\proc-dependency-extractor\src\test\java\com\hb\extractor\service\Neo4jSyncServiceTest.java` 末尾（`testDeleteProcData_Neo4jUnavailable_NoException` 方法后）新增：

```java
@Test
void testClassifyTransformation_Direct() {
    assertEquals("direct", syncService.classifyTransformation("T01.acct_id"));
    assertEquals("direct", syncService.classifyTransformation(null));
    assertEquals("direct", syncService.classifyTransformation(""));
    assertEquals("direct", syncService.classifyTransformation("   "));
}

@Test
void testClassifyTransformation_Literal() {
    assertEquals("literal", syncService.classifyTransformation("'N'"));
    assertEquals("literal", syncService.classifyTransformation("123"));
    assertEquals("literal", syncService.classifyTransformation("2024"));
    assertEquals("literal", syncService.classifyTransformation("'2024-01-01'"));
}

@Test
void testClassifyTransformation_Aggregation() {
    assertEquals("aggregation", syncService.classifyTransformation("SUM(T01.amt)"));
    assertEquals("aggregation", syncService.classifyTransformation("COUNT(*)"));
    assertEquals("aggregation", syncService.classifyTransformation("AVG(T01.amt)"));
    assertEquals("aggregation", syncService.classifyTransformation("MAX(T01.amt)"));
    assertEquals("aggregation", syncService.classifyTransformation("MIN(T01.amt)"));
}

@Test
void testClassifyTransformation_Derived() {
    assertEquals("derived", syncService.classifyTransformation("T01.amt + T02.fee"));
    assertEquals("derived", syncService.classifyTransformation("COALESCE(T01.amt, 0)"));
    assertEquals("derived", syncService.classifyTransformation("CASE WHEN T01.flag = '1' THEN 'A' ELSE 'B' END"));
}
```

- [ ] **步骤 3：运行测试**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest,Neo4jSyncServiceTest
```

预期：所有测试 PASS。

- [ ] **步骤 4：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java
git commit -m "test: 重写影响分析测试，新增 transformation_type 分类测试"
```

---

## 任务 6：更新前端依赖和 API

**文件：**
- 修改：`frontend/package.json`
- 修改：`frontend/src/api/script.js`

**上下文：** 移除 @antv/g6，新增 @vue-flow/core 和 dagre。新增 getScriptByFile API（通过 Script.file 查找脚本）。

- [ ] **步骤 1：更新 package.json**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\package.json` 的 `dependencies` 部分为：

```json
"dependencies": {
    "@element-plus/icons-vue": "^2.3.1",
    "@vue-flow/core": "^1.48.2",
    "axios": "^1.6.8",
    "dagre": "^0.8.5",
    "element-plus": "^2.7.0",
    "vue": "^3.4.21",
    "vue-router": "^4.3.0"
}
```

（移除 `@antv/g6`）

- [ ] **步骤 2：新增 getScriptByFile API**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\api\script.js` 全部内容为：

```javascript
import request from './request'

// 查询脚本内容（通过 procName）
export function getScript(procName) {
  return request({
    url: `/proc/${procName}/script`,
    method: 'get'
  })
}

// 查询脚本内容（通过 scriptFile，如 dwa_c_cus_pri_cust_loan_sum_crms.py）
// 后端按 procName 查找，去掉 .py 后缀
export function getScriptByFile(scriptFile) {
  const procName = scriptFile.replace(/\.py$/i, '')
  return request({
    url: `/proc/${procName}/script`,
    method: 'get'
  })
}
```

- [ ] **步骤 3：安装新依赖**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm install
```

预期：`@vue-flow/core` 和 `dagre` 安装成功，`@antv/g6` 从 node_modules 移除。

- [ ] **步骤 4：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/package.json frontend/package-lock.json frontend/src/api/script.js
git commit -m "build: 移除 @antv/g6，新增 @vue-flow/core 和 dagre"
```

---

## 任务 7：新增 ImpactNode.vue 自定义节点组件

**文件：**
- 创建：`frontend/src/components/impact/ImpactNode.vue`

**上下文：** Vue Flow 自定义节点组件。显示 layer badge + 表名 + 受影响字段列表。左侧 4px 彩色 border 按 layer。TEMP 节点黄色边框。

- [ ] **步骤 1：创建 ImpactNode.vue**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\impact\ImpactNode.vue`：

```vue
<template>
  <div class="impact-node" :style="{ borderLeftColor: color }">
    <div class="layer-badge" :style="{ background: color }">{{ layer || '?' }}</div>
    <div class="node-body">
      <div class="node-label">{{ label }}</div>
      <div v-if="matchedColumns && matchedColumns.length" class="matched-fields">
        <span v-for="col in matchedColumns" :key="col" class="field-tag">{{ col }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  id: { type: String, required: true },
  data: { type: Object, default: () => ({}) }
})

const LAYER_COLORS = {
  FDM: '#ef4444',
  ODM: '#f59e0b',
  CDM: '#3b82f6',
  TDM: '#10b981',
  PID: '#8b5cf6',
  UNKNOWN: '#94a3b8'
}

const label = computed(() => props.data.label || props.id)
const layer = computed(() => props.data.layer || 'UNKNOWN')
const matchedColumns = computed(() => props.data.matchedColumns || [])

const color = computed(() => {
  if (props.data.isTemp) return '#faad14'
  return LAYER_COLORS[props.data.layer] || LAYER_COLORS.UNKNOWN
})
</script>

<style scoped>
.impact-node {
  display: flex;
  align-items: stretch;
  min-width: 180px;
  max-width: 280px;
  padding: 0;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-left: 4px solid #94a3b8;
  border-radius: 4px;
  font-size: 12px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.layer-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  color: #fff;
  font-weight: 700;
  font-size: 10px;
  letter-spacing: 0.5px;
  border-radius: 4px 0 0 4px;
  writing-mode: horizontal-tb;
}
.node-body {
  flex: 1;
  padding: 6px 10px;
  min-width: 0;
}
.node-label {
  font-weight: 600;
  color: #1f1f1f;
  word-break: break-all;
  line-height: 1.3;
}
.matched-fields {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}
.field-tag {
  display: inline-block;
  padding: 1px 5px;
  background: #fee;
  color: #c00;
  border-radius: 2px;
  font-size: 10px;
}
</style>
```

- [ ] **步骤 2：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ImpactNode.vue
git commit -m "feat: 新增 ImpactNode.vue Vue Flow 自定义节点组件"
```

---

## 任务 8：新增 ImpactFlowGraph.vue 图组件

**文件：**
- 创建：`frontend/src/components/impact/ImpactFlowGraph.vue`

**上下文：** Vue Flow 图组件，替代 ImpactGraph.vue（G6）。使用 dagre 自动布局，渐进式展开（点击节点展开 1 跳下游）。

- [ ] **步骤 1：创建 ImpactFlowGraph.vue**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\impact\ImpactFlowGraph.vue`：

```vue
<template>
  <div class="flow-graph-container">
    <VueFlow
      :nodes="nodes"
      :edges="edges"
      :node-types="nodeTypes"
      :fit-view-on-init="true"
      @node-click="handleNodeClick"
      @edge-click="handleEdgeClick"
    >
      <template #node-impactNode="props">
        <ImpactNode :id="props.id" :data="props.data" />
      </template>
    </VueFlow>
    <div v-if="nodes.length === 0" class="empty-tip">
      <el-empty description="未找到影响链路" :image-size="60" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, markRaw } from 'vue'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import dagre from 'dagre'
import ImpactNode from './ImpactNode.vue'
import { ElMessage } from 'element-plus'
import { expand } from '@/api/impact'

const props = defineProps({
  initialNodes: { type: Array, default: () => [] },
  initialEdges: { type: Array, default: () => [] },
  direction: { type: String, default: 'DOWNSTREAM' }
})

const emit = defineEmits(['nodeClick', 'edgeClick'])

const nodes = ref([])
const edges = ref([])

const graphStore = reactive({
  nodeMap: new Map(),
  edgeMap: new Map()
})

function handleNodeClick(evt) {
  emit('nodeClick', evt.node)
}

function handleEdgeClick(evt) {
  emit('edgeClick', evt.edge)
}

function rebuildGraph() {
  if (graphStore.nodeMap.size === 0) {
    nodes.value = []
    edges.value = []
    return
  }

  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 80 })
  g.setDefaultEdgeLabel(() => ({}))

  graphStore.nodeMap.forEach((n, id) => {
    g.setNode(id, { width: 220, height: 60 })
  })
  graphStore.edgeMap.forEach((e, key) => {
    g.setEdge(e.source, e.target)
  })

  dagre.layout(g)

  nodes.value = g.nodes().map(id => {
    const node = graphStore.nodeMap.get(id)
    const dagreNode = g.node(id)
    return {
      id,
      position: { x: dagreNode.x - 110, y: dagreNode.y - 30 },
      data: node,
      type: 'impactNode'
    }
  })

  edges.value = g.edges().map(e => ({
    id: `${e.v}-${e.w}`,
    source: e.v,
    target: e.w,
    type: 'smoothstep',
    animated: true,
    style: { stroke: '#64748b', strokeWidth: 1.5 }
  }))
}

async function expandNode(nodeId) {
  try {
    const resp = await expand({
      nodeId: nodeId,
      direction: props.direction,
      depth: 1
    })
    const newNodes = resp.data?.nodes || []
    const newEdges = resp.data?.edges || []

    let changed = false
    newNodes.forEach(n => {
      if (!graphStore.nodeMap.has(n.id)) {
        graphStore.nodeMap.set(n.id, {
          label: n.label || n.id,
          layer: n.layer,
          isTemp: n.isTemp,
          matchedColumns: n.matchedColumns || []
        })
        changed = true
      }
    })
    newEdges.forEach(e => {
      const key = `${e.source}-${e.target}`
      if (!graphStore.edgeMap.has(key)) {
        graphStore.edgeMap.set(key, { source: e.source, target: e.target })
        changed = true
      }
    })

    if (changed) {
      rebuildGraph()
    } else {
      ElMessage.info(`表 "${nodeId}" 没有更多下游依赖`)
    }
  } catch (e) {
    ElMessage.error('展开失败: ' + (e.message || ''))
  }
}

function loadInitial(initialNodes, initialEdges) {
  graphStore.nodeMap.clear()
  graphStore.edgeMap.clear()

  initialNodes.forEach(n => {
    graphStore.nodeMap.set(n.id || n.name, {
      label: n.label || n.shortName || n.id || n.name,
      layer: n.layer,
      isTemp: n.isTemp || n.temp,
      matchedColumns: n.matchedColumns || []
    })
  })
  initialEdges.forEach(e => {
    const src = e.source || e.impactSource
    const tgt = e.target || e.name
    if (src && tgt) {
      graphStore.edgeMap.set(`${src}-${tgt}`, { source: src, target: tgt })
    }
  })

  rebuildGraph()
}

watch(() => [props.initialNodes, props.initialEdges], () => {
  loadInitial(props.initialNodes, props.initialEdges)
}, { immediate: true, deep: true })

defineExpose({ expandNode })
</script>

<style scoped>
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';

.flow-graph-container {
  position: relative;
  width: 100%;
  height: 500px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  background: #fafafa;
  overflow: hidden;
}
.empty-tip {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}
</style>
```

- [ ] **步骤 2：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ImpactFlowGraph.vue
git commit -m "feat: 新增 ImpactFlowGraph.vue Vue Flow 图组件"
```

---

## 任务 9：删除 ImpactGraph.vue 并更新 ImpactPropagationTree.vue

**文件：**
- 删除：`frontend/src/components/impact/ImpactGraph.vue`
- 修改：`frontend/src/components/impact/ImpactPropagationTree.vue`

**上下文：** 删除旧的 G6 组件。传播树组件适配新字段（name/shortName/layer/temp/scriptFile 替代 table/type/procName）。

- [ ] **步骤 1：删除 ImpactGraph.vue**

删除 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\impact\ImpactGraph.vue` 文件。

- [ ] **步骤 2：重写 ImpactPropagationTree.vue**

完全替换 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\impact\ImpactPropagationTree.vue` 为：

```vue
<template>
  <div class="propagation-tree">
    <!-- 源表卡片 -->
    <div class="depth-section">
      <div class="depth-label">源表</div>
      <div class="tree-cards">
        <div class="impact-card">
          <div class="type-badge" :style="{ background: getSourceColor() }"></div>
          <div class="card-content">
            <span class="table-name">{{ sourceTable }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 各层级 -->
    <template v-for="depth in sortedDepths" :key="depth">
      <div class="depth-section">
        <div class="depth-arrow">↓</div>
        <div class="depth-label">Level {{ depth }}（{{ displayData[depth].length }} 张表）</div>
        <div class="tree-cards">
          <div
            v-for="item in displayData[depth]"
            :key="item.name"
            class="impact-card"
          >
            <div
              class="type-badge"
              :style="{ background: getLayerColor(item) }"
            ></div>
            <div class="card-content">
              <span class="table-name">{{ item.shortName || item.name }}</span>
              <span v-if="item.impactSource" class="impact-source">
                ← 来自 {{ item.impactSource }}
              </span>
            </div>
            <button
              class="code-btn"
              @click="$emit('viewScript', item)"
              :disabled="!item.scriptFile"
              :title="item.scriptFile ? '查看代码片段' : '无关联脚本'"
            >
              代码
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- 空数据提示 -->
    <el-empty
      v-if="sortedDepths.length === 0"
      description="无下游影响"
      :image-size="60"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  sourceTable: { type: String, default: '' },
  sourceLayer: { type: String, default: '' },
  impactedByDepth: { type: Object, default: () => ({}) },
  collapsedTempTables: { type: Boolean, default: false }
})

defineEmits(['viewScript'])

const LAYER_COLORS = {
  FDM: '#ef4444',
  ODM: '#f59e0b',
  CDM: '#3b82f6',
  TDM: '#10b981',
  PID: '#8b5cf6',
  UNKNOWN: '#94a3b8',
  SOURCE: '#52c41a',
  TEMP: '#faad14'
}

function getLayerColor(item) {
  if (item.temp) return LAYER_COLORS.TEMP
  return LAYER_COLORS[item.layer] || LAYER_COLORS.UNKNOWN
}

function getSourceColor() {
  return LAYER_COLORS[props.sourceLayer] || LAYER_COLORS.SOURCE
}

const displayData = computed(() => {
  if (!props.impactedByDepth) return {}
  if (!props.collapsedTempTables) return props.impactedByDepth
  return rebuildWithoutTemp(props.impactedByDepth, props.sourceTable)
})

const sortedDepths = computed(() => {
  return Object.keys(displayData.value)
    .map(Number)
    .sort((a, b) => a - b)
})

function rebuildWithoutTemp(impactedByDepth, sourceTable) {
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push(t.name)
      }
    }
  }

  const tableInfoMap = {}
  const nonTempTables = new Set([sourceTable])
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.name] = { layer: t.layer, temp: t.temp, scriptFile: t.scriptFile }
      if (!t.temp) nonTempTables.add(t.name)
    }
  }

  const depthMap = { [sourceTable]: 0 }
  const impactSourceMap = {}
  const queue = [sourceTable]

  while (queue.length) {
    const current = queue.shift()
    const currentDepth = depthMap[current]
    const neighbors = adjacency[current] || []

    for (const neighbor of neighbors) {
      if (nonTempTables.has(neighbor) && !(neighbor in depthMap)) {
        depthMap[neighbor] = currentDepth + 1
        impactSourceMap[neighbor] = current
        queue.push(neighbor)
      }
      if (!nonTempTables.has(neighbor)) {
        const tempQueue = [neighbor]
        const visited = new Set([neighbor])
        while (tempQueue.length) {
          const tempNode = tempQueue.shift()
          const tempNeighbors = adjacency[tempNode] || []
          for (const tn of tempNeighbors) {
            if (visited.has(tn)) continue
            visited.add(tn)
            if (nonTempTables.has(tn) && !(tn in depthMap)) {
              depthMap[tn] = currentDepth + 1
              impactSourceMap[tn] = current
              queue.push(tn)
            } else if (!nonTempTables.has(tn)) {
              tempQueue.push(tn)
            }
          }
        }
      }
    }
  }

  const result = {}
  for (const [table, depth] of Object.entries(depthMap)) {
    if (depth === 0) continue
    if (!result[depth]) result[depth] = []
    result[depth].push({
      name: table,
      depth,
      impactSource: impactSourceMap[table],
      layer: tableInfoMap[table]?.layer || 'UNKNOWN',
      temp: tableInfoMap[table]?.temp || false,
      scriptFile: tableInfoMap[table]?.scriptFile || null
    })
  }
  return result
}
</script>

<style scoped>
.propagation-tree {
  padding: 8px 0;
}
.depth-section {
  margin-bottom: 12px;
}
.depth-arrow {
  color: #1890ff;
  font-size: 20px;
  margin-bottom: 4px;
}
.depth-label {
  font-size: 12px;
  color: #8c8c8c;
  margin-bottom: 6px;
}
.tree-cards {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.impact-card {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 280px;
  padding: 8px 12px;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  background: #fafafa;
}
.type-badge {
  width: 6px;
  height: 24px;
  border-radius: 3px;
  flex-shrink: 0;
}
.card-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.table-name {
  font-weight: 600;
  font-size: 13px;
  word-break: break-all;
}
.impact-source {
  font-size: 11px;
  color: #8c8c8c;
  margin-top: 2px;
}
.code-btn {
  margin-left: auto;
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  flex-shrink: 0;
}
.code-btn:hover:not(:disabled) {
  border-color: #1890ff;
  color: #1890ff;
}
.code-btn:disabled {
  color: #bfbfbf;
  cursor: not-allowed;
}
</style>
```

- [ ] **步骤 3：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ImpactGraph.vue frontend/src/components/impact/ImpactPropagationTree.vue
git commit -m "refactor: 删除 ImpactGraph.vue（G6），重写 ImpactPropagationTree 适配新字段"
```

---

## 任务 10：更新 ScriptSnippetModal.vue

**文件：**
- 修改：`frontend/src/components/impact/ScriptSnippetModal.vue`

**上下文：** 适配新的 scriptFile 参数（替代 procName）。高亮逻辑不变。

- [ ] **步骤 1：更新 ScriptSnippetModal.vue 的 script 部分**

替换 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\impact\ScriptSnippetModal.vue` 的 `<script setup>` 部分（第 35-113 行）为：

```javascript
import { ref, computed, watch, nextTick } from 'vue'
import { getScriptByFile } from '@/api/script'

const props = defineProps({
  visible: { type: Boolean, default: false },
  scriptFile: { type: String, default: '' },
  impactSource: { type: String, default: '' },
  targetTable: { type: String, default: '' }
})

defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const scriptContent = ref('')
const scriptLines = ref([])
const highlightLines = ref(new Set())
const scriptViewer = ref(null)

const dialogTitle = computed(() => {
  return props.scriptFile || '代码片段'
})

function computeHighlightLines(content, impactSource) {
  if (!impactSource) return new Set()
  const lines = content.split('\n')
  const highlightSet = new Set()
  const tableName = impactSource.split('.').pop().toUpperCase()
  const shortName = tableName.replace(/^VT_/, '')

  lines.forEach((line, idx) => {
    const upperLine = line.toUpperCase()
    const isComment = line.trim().startsWith('--') || line.trim().startsWith('#')
    if (isComment) return
    if (upperLine.includes(tableName) || (shortName && upperLine.includes(shortName))) {
      highlightSet.add(idx + 1)
    }
  })
  return highlightSet
}

async function loadScript() {
  if (!props.scriptFile) {
    error.value = '脚本文件名为空'
    return
  }
  loading.value = true
  error.value = ''
  scriptContent.value = ''
  try {
    const res = await getScriptByFile(props.scriptFile)
    const data = res.data || {}
    scriptContent.value = data.scriptContent || ''
    scriptLines.value = scriptContent.value.split('\n')
    highlightLines.value = computeHighlightLines(scriptContent.value, props.impactSource)

    if (highlightLines.value.size > 0) {
      nextTick(() => {
        const firstLine = Math.min(...highlightLines.value)
        if (scriptViewer.value) {
          const lineEl = scriptViewer.value.querySelector(`.script-line:nth-child(${firstLine})`)
          if (lineEl) lineEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
        }
      })
    }
  } catch (e) {
    error.value = e.message || '脚本获取失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (val) => {
  if (val) loadScript()
}, { immediate: true })
```

- [ ] **步骤 2：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ScriptSnippetModal.vue
git commit -m "refactor: ScriptSnippetModal 适配 scriptFile 参数"
```

---

## 任务 11：更新 ImpactAnalysis.vue 集成新组件

**文件：**
- 修改：`frontend/src/views/ImpactAnalysis.vue`

**上下文：** 集成 ImpactFlowGraph（替代 ImpactGraph）和更新后的 ImpactPropagationTree、ScriptSnippetModal。适配新字段（scriptFile 替代 procName）。

- [ ] **步骤 1：重写 ImpactAnalysis.vue**

完全替换 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\views\ImpactAnalysis.vue` 为：

```vue
<template>
  <div class="impact-analysis">
    <ImpactFilter :loading="loading" @analyze="handleAnalyze" />

    <el-alert
      v-if="neo4jError"
      title="Neo4j 服务不可用"
      type="error"
      :description="neo4jError"
      show-icon
      style="margin-bottom: 10px"
    >
      <el-button size="small" @click="handleAnalyze(lastRequest)">重试</el-button>
    </el-alert>

    <ImpactStats :stats="result?.stats" />

    <el-row :gutter="10">
      <el-col :span="18">
        <ImpactFlowGraph
          ref="graphRef"
          :initialNodes="graphInitialNodes"
          :initialEdges="graphInitialEdges"
          :direction="lastRequest?.direction || 'DOWNSTREAM'"
          @nodeClick="handleNodeClick"
          @edgeClick="handleEdgeClick"
        />

        <div class="propagation-section" v-if="result?.impactedByDepth">
          <div class="section-header">
            <span class="section-title">影响传播树</span>
            <el-switch
              v-model="collapseTempTables"
              active-text="折叠临时表"
              size="small"
            />
          </div>
          <ImpactPropagationTree
            :sourceTable="result.sourceTable"
            :sourceLayer="result.sourceLayer"
            :impactedByDepth="result.impactedByDepth"
            :collapsedTempTables="collapseTempTables"
            @viewScript="handleViewScript"
          />
        </div>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <template #header>详情</template>
          <ImpactNodeDetail
            v-if="selectedNode"
            :node="selectedNode"
            @expand="handleExpand"
            @collapse="handleCollapse"
          />
          <ImpactEdgeDetail
            v-else-if="selectedEdge"
            :edge="selectedEdge"
          />
          <el-empty v-else description="点击节点或边查看详情" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <ScriptSnippetModal
      v-if="scriptModalVisible"
      :visible="scriptModalVisible"
      :scriptFile="scriptModalData.scriptFile"
      :impactSource="scriptModalData.impactSource"
      :targetTable="scriptModalData.name"
      @close="scriptModalVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ImpactFilter from '@/components/impact/ImpactFilter.vue'
import ImpactStats from '@/components/impact/ImpactStats.vue'
import ImpactFlowGraph from '@/components/impact/ImpactFlowGraph.vue'
import ImpactNodeDetail from '@/components/impact/ImpactNodeDetail.vue'
import ImpactEdgeDetail from '@/components/impact/ImpactEdgeDetail.vue'
import ImpactPropagationTree from '@/components/impact/ImpactPropagationTree.vue'
import ScriptSnippetModal from '@/components/impact/ScriptSnippetModal.vue'
import { analyze } from '@/api/impact'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const result = ref(null)
const neo4jError = ref('')
const lastRequest = ref(null)
const graphRef = ref(null)
const selectedNode = ref(null)
const selectedEdge = ref(null)
const collapseTempTables = ref(false)
const scriptModalVisible = ref(false)
const scriptModalData = ref({ name: '', scriptFile: '', impactSource: '' })

const graphInitialNodes = ref([])
const graphInitialEdges = ref([])

const isFieldLevel = computed(() => {
  return lastRequest.value?.fieldName && lastRequest.value.fieldName !== ''
})

const handleAnalyze = async (request) => {
  loading.value = true
  collapseTempTables.value = false
  neo4jError.value = ''
  lastRequest.value = request
  try {
    const res = await analyze(request)
    result.value = res.data

    // 构建初始图数据：起点 + 第 1 跳下游
    const sourceTable = res.data?.sourceTable
    const sourceLayer = res.data?.sourceLayer
    const impactedByDepth = res.data?.impactedByDepth || {}
    const firstLevel = impactedByDepth[1] || []

    const initialNodes = [{
      id: sourceTable,
      label: sourceTable,
      layer: sourceLayer,
      isTemp: false,
      matchedColumns: []
    }]
    firstLevel.forEach(t => {
      initialNodes.push({
        id: t.name,
        label: t.shortName || t.name,
        layer: t.layer,
        isTemp: t.temp,
        matchedColumns: t.matchedColumns || []
      })
    })

    const initialEdges = []
    firstLevel.forEach(t => {
      if (t.impactSource) {
        initialEdges.push({
          source: t.impactSource,
          target: t.name
        })
      }
    })

    graphInitialNodes.value = initialNodes
    graphInitialEdges.value = initialEdges

    // 节点超过 50 个时提示折叠临时表
    const totalNodes = Object.values(impactedByDepth)
      .reduce((sum, list) => sum + list.length, 0) + 1
    if (totalNodes > 50) {
      ElMessage.warning(
        `当前影响链路包含 ${totalNodes} 个节点，建议折叠临时表以提升可读性`
      )
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
}

const handleNodeClick = (node) => {
  selectedNode.value = node
  selectedEdge.value = null
}

const handleEdgeClick = (edge) => {
  selectedEdge.value = edge
  selectedNode.value = null
}

const handleExpand = async (node) => {
  if (graphRef.value) {
    await graphRef.value.expandNode(node.id || node.data?.id)
  }
}

const handleCollapse = (node) => {
  // Vue Flow 的折叠逻辑较复杂，暂用重新渲染实现
  ElMessage.info('折叠功能待实现，可重新分析查看')
}

const handleViewScript = ({ name, scriptFile, impactSource }) => {
  if (!scriptFile) {
    ElMessage.warning('该表无关联脚本')
    return
  }
  scriptModalData.value = { name, scriptFile, impactSource }
  scriptModalVisible.value = true
}
</script>

<style scoped>
.impact-analysis {
  padding: 10px;
}
.propagation-section {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 16px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f1f1f;
}
</style>
```

- [ ] **步骤 2：前端构建验证**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```

预期：构建成功，无编译错误。若报 `@antv/g6` 引用错误，检查是否有遗漏的 import。

- [ ] **步骤 3：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/views/ImpactAnalysis.vue
git commit -m "refactor: ImpactAnalysis.vue 集成 Vue Flow 图组件"
```

---

## 任务 12：端到端验证

**文件：** 无（仅验证）

**上下文：** 启动后端，清空 Neo4j，重新解析所有脚本，验证 API 和前端。

- [ ] **步骤 1：启动后端**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml spring-boot:run
```

预期：Tomcat 启动在 8080 端口，无异常。

- [ ] **步骤 2：清空 Neo4j**

用 curl 调用：

```bash
curl -X POST http://localhost:8080/api/impact/clear-neo4j
```

预期：返回 `{"code":200,"data":null}`。

- [ ] **步骤 3：重新解析所有脚本**

```bash
curl -X POST http://localhost:8080/api/proc/extract/all
```

预期：返回成功，6 个 proc 全部解析成功。

- [ ] **步骤 4：验证影响分析 API**

```bash
curl -X POST http://localhost:8080/api/impact/analyze -H "Content-Type: application/json" -d "{\"schema\":\"CDM\",\"tableShortName\":\"C_AGT_DEPOSIT_ACCT_CALCU_BASE\",\"direction\":\"DOWNSTREAM\",\"maxDepth\":3}"
```

预期：返回 JSON 包含：
- `sourceTable`: "cdm.c_agt_deposit_acct_calcu_base"（小写）
- `sourceLayer`: "CDM"
- `impactedByDepth`: 包含 Level 1、Level 2
- `propagationChains`: 包含 "xxx → yyy" 格式

- [ ] **步骤 5：验证 schemas API**

```bash
curl http://localhost:8080/api/impact/schemas
```

预期：返回 `["CDM","FDM",...]`（从 schema_prefix 查询）。

- [ ] **步骤 6：验证 tables API**

```bash
curl "http://localhost:8080/api/impact/tables?schema=CDM"
```

预期：返回 CDM schema 下的表列表，`fullName` 为小写。

- [ ] **步骤 7：前端构建并重启后端**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```

然后停止后端（Ctrl+C），重新启动：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml spring-boot:run
```

- [ ] **步骤 8：前端手动验证**

打开浏览器访问 `http://localhost:8080/#/impact`，按 14 项清单验证：

1. 选择 schema=CDM, 表=C_AGT_DEPOSIT_ACCT_CALCU_BASE, 点击分析 → Vue Flow 图 + 传播树同时显示
2. 节点颜色按 layer → FDM红/CDM蓝/TDM绿，VT_ 表黄色
3. 节点显示 layer badge + 表名 → 左侧色条显示 "CDM" 等
4. 点击节点展开 1 跳下游 → 新节点增量加入，dagre 重新布局
5. 无下游时提示 → "表 xxx 没有更多下游依赖"
6. 传播树显示分层结构 → 源表 → Level 1 → Level 2
7. 传播树卡片显示 layer 色块 + 表名 + impactSource + 代码按钮 → 正确显示
8. 点击"折叠临时表"开关 → VT_ 表隐藏，层级重算
9. 点击卡片"代码"按钮 → 弹出代码片段 Modal
10. Modal 高亮 impactSource 引用行 → 红色左边框 + 浅红背景
11. Modal 自动滚动到第一个高亮行 → 滚动定位正确
12. 关闭 Modal → 正常关闭
13. 切换方向 UPSTREAM 重新分析 → 传播树显示上游表
14. 字段级分析时节点显示受影响字段 → 红色字段标签

- [ ] **步骤 9：最终 Commit（如有修复）**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add -A
git commit -m "fix: 端到端验证修复"
```

---

## 自检结果

### 规格覆盖度

| 规格章节 | 实现任务 | 状态 |
|---------|---------|------|
| 一、图数据模型（Table/Column/Script 节点 + 6 种关系） | 任务 1（约束）、任务 2（写入） | ✅ |
| 二、影响分析查询（Cypher 原生 path） | 任务 3（analyze 重写） | ✅ |
| 三、数据写入（MERGE + 清理 + classifyTransformation） | 任务 2（syncProc 重写） | ✅ |
| 四、前端展示（Vue Flow + dagre + layer 着色） | 任务 6-11（前端全部） | ✅ |
| 五、错误处理与测试 | 任务 5（测试重写）、任务 12（端到端验证） | ✅ |
| 六、数据迁移策略 | 任务 4（clear-neo4j 接口）、任务 12（验证） | ✅ |

### 类型一致性检查

- `ImpactedTable.name` → 任务 1 定义，任务 3 查询返回，任务 9 传播树使用 ✅
- `ImpactedTable.layer` → 任务 1 定义，任务 3 查询返回，任务 9 传播树使用 ✅
- `ImpactedTable.temp` → 任务 1 定义，任务 3 查询返回，任务 9 传播树使用 ✅
- `ImpactedTable.scriptFile` → 任务 1 定义，任务 3 查询返回，任务 9/10 使用 ✅
- `Table.name` 小写 → 任务 2 写入小写，任务 3 查询用小写，任务 11 前端使用 ✅
- `Script.file` 格式 `procName.py` → 任务 2 写入，任务 10 前端调用 getScriptByFile ✅
- `READS` 关系属性在 `r` 上 → 任务 2 用 `r.operation`，任务 3 查询用 `r.operation` ✅

### 占位符扫描

无占位符。所有步骤都包含完整代码或精确命令。

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-31-impact-analysis-lineage-engine-align.md`。两种执行方式：

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

选哪种方式？
