# ON/WHERE 条件字段 INPUT_TO 关系增强设计

- **作者**：AI 助手 + 用户协作
- **创建日期**：2026-08-19
- **状态**：待实现
- **关联项目**：proc-dependency-extractor
- **关联文档**：`2026-07-28-neo4j-impact-analysis-design.md`（第 11 章图模型、第 18 章已知限制 #8）

## 1. 背景与目标

### 1.1 背景

当前字段级影响分析只覆盖 SELECT 列表达式中的字段，ON/WHERE 条件中的字段不包含在字段级血缘中。这导致下游影响分析链路在临时表字段处断裂——当临时表字段仅在后续 SQL 块的 ON 条件中使用时，无法通过字段级血缘传播到目标物理表。

典型场景：从 `FDM.f_ac_ctds_ldr_acct.acct_no` 分析下游影响，链路在 `vt_c_agt_deposit_acct_profit_06.acct_no`（depth=2）断裂，无法到达 `c_agt_deposit_acct_profit.acct_no`（目标物理表），因为该字段在 block 13 中仅用于 `LEFT JOIN ... ON T1.acct_no = T2.acct_no`，不在 SELECT 列中。

### 1.2 目标

为 JOIN ON 和 WHERE 子句中的条件字段创建 `INPUT_TO` 关系（role=join_condition/where_condition），打通断裂的字段级下游链路。

### 1.3 范围

- JOIN ON 条件字段：role=join_condition
- WHERE 条件字段：role=where_condition
- 不处理 GROUP BY/HAVING 中的字段
- 不递归子查询内部的条件

## 2. 数据流与架构

```
FieldMappingExtractor.extract(sqlBlock, targetTable, aliasMap, ddlFields)
  │
  ├─ 1. 解析 SELECT 列 → exprInfos (现有逻辑)
  ├─ 2. matchByDdlFields / matchBySelectOnly → List<FieldMapping> (现有逻辑)
  │
  └─ 3. 【新增】解析 ON/WHERE 条件字段
       │  调用 JsqlSqlAnalyzer.extractConditionFields(PlainSelect)
       │  返回 List<ColumnRef>（alias, field, role）
       │
       └─ 为每个 FieldMapping 添加条件字段的 TransformInput
            └─ ProcExtractService 保存到 table_transform_input
                  └─ Neo4jSyncServiceImpl.syncProc 同步
                       └─ MERGE 创建 INPUT_TO 关系（按 role 去重）
```

**核心思路**：条件字段（如 `T2.acct_no`）通过 `aliasMap` 解析出 `T2 → vt_c_agt_deposit_acct_profit_06`，为 SQL 块的每个 FieldMapping 添加条件字段的 TransformInput 记录。Neo4j 同步时，每个 FieldMapping 对应一个 Transform 节点，条件字段的 INPUT_TO 关系通过 MERGE 创建到各 Transform 节点。

**数据冗余说明**：MySQL 中条件字段的 TransformInput 会有 N 份（N = 该 SQL 块的输出字段数），但 Neo4j 中不会重复——每个 Transform 节点不同，INPUT_TO 关系也不同。同一条件字段到同一 Transform 节点的重复 INPUT_TO 由 MERGE 去重。

## 3. 解析器修改

### 3.1 JsqlSqlAnalyzer 新增 extractConditionFields

```java
/**
 * 从 PlainSelect 提取 ON/WHERE 条件中的字段引用。
 * 复用 collectColumnRefs，role 设为 join_condition / where_condition。
 */
public List<ColumnRef> extractConditionFields(PlainSelect select) {
    List<ColumnRef> result = new ArrayList<>();
    if (select == null) return result;
    // JOIN ON 条件
    if (select.getJoins() != null) {
        for (Join join : select.getJoins()) {
            if (join.getOnExpressions() != null) {
                for (Expression onExpr : join.getOnExpressions()) {
                    collectColumnRefs(onExpr, "join_condition", result);
                }
            }
        }
    }
    // WHERE 条件
    if (select.getWhere() != null) {
        collectColumnRefs(select.getWhere(), "where_condition", result);
    }
    return result;
}
```

- 复用已有的 `collectColumnRefs`（JsqlSqlAnalyzer 行 375），递归处理 AND/OR/比较运算符/CASE WHEN 等
- `ColumnRef.role` 从 `"value"/"condition"` 扩展为 `"join_condition"/"where_condition"`

### 3.2 FieldMappingExtractor.extract 末尾新增条件字段处理

在 `matchByDdlFields/matchBySelectOnly` 生成 `List<FieldMapping> result` 之后，追加条件字段处理逻辑。

**sourceTableShort 赋值逻辑**（三种场景）：

| 场景 | 条件 | 处理 |
|------|------|------|
| 有 alias 前缀 | `ref.alias != null && aliasMap.containsKey(ref.alias)` | `aliasMap.get(ref.alias)` |
| 单表无 alias | `aliasMap.size() == 1` | 兜底关联到唯一表 |
| 多表无 alias | `aliasMap.size() > 1` | 通过 DDL 字段匹配（见 3.3） |

**去重 key**：`alias.field/role`，同一字段不同 role 各自独立保留。

### 3.3 多表场景 DDL 字段匹配

对多表 SQL 中无 alias 前缀的条件字段，查询 `table_column_meta` 表匹配字段归属：

| 匹配结果 | 处理 |
|---------|------|
| 唯一匹配（字段只在一个 source_table 中存在） | 归属该表 |
| 多重匹配（字段在多个 source_table 中存在） | 跳过 |
| 无匹配（字段不在任何 source_table 中） | 跳过 |

```java
@Autowired
private TableColumnMetaMapper tableColumnMetaMapper;

/**
 * 多表场景下通过 DDL 字段列表匹配字段归属。
 * @return 唯一匹配的 table_short;多重匹配或无匹配返回 null
 */
private String matchFieldToTable(String field, java.util.Collection<String> tableShorts) {
    if (field == null || tableShorts == null || tableShorts.isEmpty()) return null;
    String fieldUpper = field.toUpperCase();
    String matched = null;
    int matchCount = 0;
    for (String tableShort : tableShorts) {
        if (tableShort == null) continue;
        Long count = tableColumnMetaMapper.selectCount(
            new LambdaQueryWrapper<TableColumnMeta>()
                .eq(TableColumnMeta::getTableShortName, tableShort)
                .eq(TableColumnMeta::getColumnName, fieldUpper));
        if (count != null && count > 0) {
            matched = tableShort;
            matchCount++;
        }
    }
    return matchCount == 1 ? matched : null;
}
```

**性能优化**：一次性查询所有 source_table 的字段列表，缓存在 `Map<String, Set<String>>`（tableShort → 字段名集合）中，同一 SQL 块内复用。

## 4. Neo4j 同步去重

### 4.1 MERGE 修复

当前 `createInputToRelationship`（Neo4jSyncServiceImpl 行 514-525）的 MERGE 不含 role 属性，会导致同一 (Column→Transform) 对的不同 role INPUT_TO 被覆盖。

修复：MERGE 加入 role 作为匹配属性：

```cypher
-- 修复前
MERGE (c)-[r:INPUT_TO]->(t)
SET r.role = $role

-- 修复后
MERGE (c)-[r:INPUT_TO {role: $role}]->(t)
SET r.source_alias = $sourceAlias
```

- 同一 (Column → Transform) + **同一 role** → MERGE 去重，只保留一条
- 同一 (Column → Transform) + **不同 role** → 两条独立 INPUT_TO 关系

### 4.2 同步流程不变

Neo4jSyncServiceImpl 行 202-210 的遍历逻辑无需修改。条件字段的 TransformInput 走正常路径（`sourceTableShort` 不为 null 时创建 INPUT_TO 关系）。

## 5. 影响分析查询与 DIRECT 类型处理

### 5.1 Cypher 查询不变

下游分析查询 `MATCH path = (src)-[:INPUT_TO|OUTPUT_TO*1..N]->(tgt)` 已遍历所有 INPUT_TO（含 join_condition/where_condition）。edgeCypher（行 677）已返回 `role: rIn.role`。

### 5.2 DIRECT 类型处理修改

ImpactAnalysisServiceImpl 行 692-716 当前逻辑：DIRECT 类型只为已在主路径上的输入字段创建 DERIVED_FROM 边，辅助输入字段跳过。

修改：为条件字段（role=join_condition/where_condition）例外处理——补全节点并创建 DERIVED_FROM 边：

```java
if ("DIRECT".equals(logicType)) {
    List<Map<String, Object>> directInputs = (List<Map<String, Object>>) row.get("inputs");
    if (directInputs != null && outputColKey != null) {
        ensureFieldNode(outputColKey, nodeIds, nodes);
        for (Map<String, Object> input : directInputs) {
            String colKey = (String) input.get("colKey");
            if (colKey == null) continue;
            String role = (String) input.get("role");
            boolean isCondition = "join_condition".equals(role) || "where_condition".equals(role);

            if (!nodeIds.contains(colKey)) {
                if (isCondition) {
                    // 条件字段: 补全节点,创建 DERIVED_FROM 边
                    ensureFieldNode(colKey, nodeIds, nodes);
                } else {
                    // SELECT 列辅助字段: 跳过
                    continue;
                }
            }

            ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
            edge.setSource(colKey);
            edge.setTarget(outputColKey);
            edge.setEdgeType("DERIVED_FROM");
            edge.setSourceAlias((String) input.get("alias"));
            edge.setProcName(procName);
            if (isCondition) {
                edge.setRole(role);
            }
            edges.add(edge);
        }
    }
    continue;
}
```

### 5.3 非 DIRECT 类型处理不变

非 DIRECT 类型（CASE/ADDITION 等）已有 Transform 节点，条件字段的 INPUT_TO 边自动通过 `ensureFieldNode`（行 732-750）补全节点。

### 5.4 前端显示（后续优化）

前端可通过 `edge.role` 区分：
- `value`：实线（SELECT 列输入）
- `join_condition`：虚线 + "ON" 标签
- `where_condition`：点线 + "WHERE" 标签

## 6. 边界情况与测试验证

### 6.1 边界情况

| 场景 | 处理方式 |
|------|---------|
| 同一字段在 SELECT 列和 ON 条件中 | 两条独立 INPUT_TO（role=value + role=join_condition） |
| ON 条件含字面量（`T1.acct_no = '123'`） | 字面量不创建 INPUT_TO（collectColumnRefs 只提取 Column） |
| WHERE 无 alias、单表 SQL | 兜底关联到唯一表 |
| WHERE 无 alias、多表 SQL | DDL 字段匹配，唯一匹配才关联 |
| WHERE 无 alias、多表且多重匹配 | 跳过（无法确定归属） |
| 子查询内部的 ON/WHERE | 不处理（只遍历顶层 PlainSelect） |
| 重新解析脚本 | 旧 TransformInput 随 FieldMapping 一起删除 |

### 6.2 测试验证

**步骤 1**：重新解析 `dwa_c_agt_deposit_acct_profit.py`

**步骤 2**：验证 MySQL 数据
```sql
SELECT ti.role, ti.source_table_short, ti.source_field, ti.source_alias
FROM table_transform_input ti
JOIN table_field_mapping fm ON ti.field_mapping_id = fm.id
JOIN table_lineage tl ON fm.lineage_id = tl.id
WHERE tl.proc_name = 'dwa_c_agt_deposit_acct_profit'
  AND tl.sql_block_seq = 13
  AND ti.role IN ('join_condition', 'where_condition');
```
期望：`join_condition | vt_c_agt_deposit_acct_profit_06 | acct_no | T2`

**步骤 3**：验证影响分析链路打通
- 从 `vt_c_agt_deposit_acct_profit_06.acct_no` 出发分析下游
- 期望：能到达 `c_agt_deposit_acct_profit.acct_no`

**步骤 4**：验证从 FDM.acct_no 出发的完整链路
- 期望：depth=3 出现 `c_agt_deposit_acct_profit.acct_no`（目标物理表）

**步骤 5**：回归验证
- 从 FDM.acct_no 出发不应出现平级的非下游节点（SELECT 列辅助字段仍被跳过）
- 现有 SELECT 列字段映射和 INPUT_TO 关系不受影响

## 7. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `JsqlSqlAnalyzer.java` | 新增 `extractConditionFields` 方法 |
| `FieldMappingExtractor.java` | extract 末尾新增条件字段处理 + `matchFieldToTable` 方法 + 注入 `TableColumnMetaMapper` |
| `Neo4jSyncServiceImpl.java` | `createInputToRelationship` 的 MERGE 加入 `{role: $role}` |
| `ImpactAnalysisServiceImpl.java` | DIRECT 类型处理为条件字段补全节点并创建 DERIVED_FROM 边 |
| `ImpactAnalysisVO.EdgeVO.java` | 新增 `role` 字段（如不存在） |
