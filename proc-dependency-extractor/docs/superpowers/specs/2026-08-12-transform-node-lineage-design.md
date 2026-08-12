# Transform 节点血缘模型设计规格

- **文档编号**：2026-08-12-transform-node-lineage-design
- **创建日期**：2026-08-12
- **状态**：审查修订 v2
- **修订记录**：v2 修复审查发现的 S1-S5 + M1-M6 共 11 项问题
- **作者**：brainstorming 会话产出
- **适用项目**：proc-dependency-extractor

---

## 1. 概述与背景

### 1.1 问题陈述

当前字段级血缘采用 `(:Column)-[:MAPS_TO]->(:Column)` 单跳关系模型，仅能表达"目标字段来自某个源字段"的一对一映射。对于"多源字段经计算转换（如 `A.C + B.D`）生成目标字段"的复杂链路，存在以下问题：

- **多源丢失**：`MAPS_TO` 边只能挂一个 `source_expr` 字符串，B.D 无法作为独立节点参与图查询
- **计算逻辑不可见**：转换表达式仅作为边属性，无法在图上呈现为可点击、可下钻的节点
- **影响分析失真**：基于 `MAPS_TO*1..N` 的变长路径查询，遇到多源运算时只能追溯主别名路径

### 1.2 设计目标

引入 **Transform 节点**作为计算中间节点，封装多源输入与目标字段产出之间的关系：

```
(Column)-[:INPUT_TO {role}]->(Transform)-[:OUTPUT_TO]->(Column)
```

支持：
- 多源字段独立成节点，各自挂载到 Transform
- Transform 节点携带 `expression` / `logic_type` / `sql_hint` 等属性
- 影响分析查询沿 `INPUT_TO` / `OUTPUT_TO` 变长路径递归
- 前端图谱可视化 Transform 节点，点击查看计算逻辑

### 1.3 不做的事

- **不做 Join 节点**：表之间的关联条件仍由 `READS` 关系的 `join_conditions` 属性承载，不单独建 Join 节点
- **不做表达式语法树解析**：Transform 仅记录表达式原文，不解析为 AST
- **不做计算执行**：Transform 仅作为血缘标记，不参与实际数据计算

---

## 2. 关键决策汇总

| # | 决策点 | 选择 | 理由 |
|---|--------|------|------|
| 1 | MAPS_TO 去留 | **完全替换**为 Column→Transform→Column | 彻底解决多源丢失，避免双模型并存 |
| 2 | Transform id 粒度 | `md5(proc_name + "\|" + target_col_key)` | 同 proc 同目标字段唯一，跨 proc 不复用 |
| 3 | 表达式拆分 | 提取所有 alias.field，INPUT_TO 边带 role | 多源独立成边，role 区分取值/条件 |
| 4 | Join 节点 | **不做** | 关联条件已在 READS 边属性，避免过度建模 |
| 5 | logic_type 粒度 | 中粒度 15 类 | 兼顾分类清晰度与实现成本 |
| 6 | MySQL 存储 | 新增 `table_transform_input` 表 | MySQL 侧也能查多源输入，与 Neo4j 一一对应 |
| 7 | 深度语义 | `max-depth` 仍为字段跳数，Cypher 跳数 = `max-depth * 2` | 保持 API 契约不变 |
| 8 | 字面量处理 | 虚拟 `__literal__` Column 节点 | 避免 Transform 无入边成为孤儿 |
| 9 | sql_hint 格式 | `block_seq={n}; via_temp={chain}` | 含 SQL 块序号 + 临时表穿透链路 |
| 10 | 数据迁移 | 零迁移脚本，先 `reExtractAll` 再 `syncAll` | 复用现有删旧+重建模式 |
| 11 | 前端 TRANSFORM 样式 | 矩形 + 左边框粗色条 + logic_type 徽章 | 避免 VueFlow 菱形布局问题 |
| 12 | 字段级模式 | 跳过 `buildWithoutTemp`，直接使用后端返回数据 | Transform 节点不适合折叠 |

---

## 3. 图模型与数据模型

### 3.1 Neo4j 图模型

#### 3.1.1 节点

| 节点标签 | 唯一约束 | 属性 | 变更 |
|---------|---------|------|------|
| `Table` | `name` | `name, short_name, schema_prefix, layer, is_temp, is_source, script_file` | 不变 |
| `Column` | `col_key` | `col_key, name, table_full_name` | 不变 |
| `Script` | `file` | `file, target_table, description` | 不变 |
| `Report` / `Dataset` | — | — | 不变 |
| **`Transform`** | **`id`** | `id, expression, logic_type, sql_hint, proc_name, target_col_key, script_file` | **新增** |

**Transform.id 生成规则**：`md5(proc_name + "|" + target_col_key)`，全小写。

**Transform 节点属性**：

| 属性 | 类型 | 示例 | 说明 |
|------|------|------|------|
| `id` | string | `a1b2c3...` | md5 哈希值 |
| `expression` | string | `T01.amt + T02.fee` | SELECT 表达式原文 |
| `logic_type` | string | `ADDITION` | 中粒度分类（见 3.5） |
| `sql_hint` | string | `block_seq=8; via_temp=VT_03←F_AC_CRBS_DEPOSIT_ACCT` | 辅助提示 |
| `proc_name` | string | `dwa_c_agt_deposit_acct_inf` | 所属存储过程 |
| `target_col_key` | string | `cdm.c_agt_deposit_acct_inf.acct_no` | 目标字段 key |
| `script_file` | string | `dwa_c_agt_deposit_acct_inf.py` | 脚本文件名 |

#### 3.1.2 关系

| 关系 | 方向 | 属性 | 变更 |
|------|------|------|------|
| `(Column)-[:BELONGS_TO]->(Table)` | Column→Table | 无 | 不变 |
| `(Table)-[:READS]->(Table)` | Table→Table | `proc_name, operation, via_temp, join_conditions, where_conditions` | 不变 |
| `(Script)-[:PRODUCES]->(Table)` / `(Script)-[:CONSUMES]->(Table)` | — | 无 | 不变 |
| `(Report)-[:CONTAINS]->(Dataset)` / `(Dataset)-[:QUERIES]->(Table)` / `(Dataset)-[:REFERENCES]->(Column)` | — | — | 不变 |
| **`(Column)-[:INPUT_TO]->(Transform)`** | Column→Transform | `role, source_alias` | **新增** |
| **`(Transform)-[:OUTPUT_TO]->(Column)`** | Transform→Column | 无 | **新增** |
| ~~`(Column)-[:MAPS_TO]->(Column)`~~ | — | — | **删除** |

**INPUT_TO 关系属性**：

| 属性 | 取值 | 说明 |
|------|------|------|
| `role` | `value` / `condition` | value=取值字段，condition=条件字段 |
| `source_alias` | `T01` | 表达式中该字段的别名 |

#### 3.1.3 图示例

`T01.c + T02.d → T.x`：
```
(T01.c) -[:INPUT_TO {role:value, source_alias:T01}]-> (Transform{id, expression:"T01.c+T02.d", logic_type:ADDITION})
(T02.d) -[:INPUT_TO {role:value, source_alias:T02}]-> ───┘
(Transform) -[:OUTPUT_TO]-> (T.x)
```

`CASE WHEN A.flag='1' THEN B.amt ELSE C.amt END → T.x`：
```
(A.flag) -[:INPUT_TO {role:condition}]-> (Transform{logic_type:CASE})
(B.amt)  -[:INPUT_TO {role:value}]->     ───┘
(C.amt)  -[:INPUT_TO {role:value}]->     ───┘
(Transform) -[:OUTPUT_TO]-> (T.x)
```

### 3.2 Neo4j 约束

在 `Neo4jConstraintInitializer` 现有 `constraints` 数组末尾追加一条（保持幂等创建模式）：

```cypher
CREATE CONSTRAINT transform_id_unique IF NOT EXISTS
FOR (t:Transform) REQUIRE t.id IS UNIQUE
```

实现方式：在 [Neo4jConstraintInitializer.java#L22-L28](file:///d:/HB/work_zuoye/proc-dependency-extractor/src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java#L22-L28) 的 `String[] constraints` 数组末尾追加该 Cypher 字符串，复用现有循环创建逻辑。

### 3.3 MySQL 数据模型

#### 3.3.1 新增表：`table_transform_input`

```sql
DROP TABLE IF EXISTS `table_transform_input`;
CREATE TABLE `table_transform_input` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `field_mapping_id`    BIGINT       NOT NULL COMMENT '关联 table_field_mapping.id',
    `lineage_id`          BIGINT       NOT NULL COMMENT '关联 table_lineage.id(冗余)',
    `proc_name`           VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `target_field`        VARCHAR(200) NOT NULL COMMENT '目标字段名(冗余)',
    `source_table_short`  VARCHAR(200)          DEFAULT NULL COMMENT '输入字段来源表短名',
    `source_field`        VARCHAR(200)          DEFAULT NULL COMMENT '输入字段名',
    `source_alias`        VARCHAR(100)          DEFAULT NULL COMMENT '表达式中该字段别名',
    `role`                VARCHAR(20)  NOT NULL DEFAULT 'value' COMMENT 'value/condition',
    `input_order`         INT          NOT NULL COMMENT '在表达式中出现顺序(从1开始)',
    `extract_time`        DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_field_mapping_id` (`field_mapping_id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_source_table` (`source_table_short`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transform 输入字段';
```

#### 3.3.2 现有表 `table_field_mapping` 调整

新增列：

```sql
ALTER TABLE table_field_mapping
ADD COLUMN logic_type VARCHAR(30) DEFAULT NULL
COMMENT 'Transform logic_type(DIRECT/LITERAL/ADDITION/...)'
AFTER source_table_short;
```

`source_expr` / `source_alias` / `source_table_short` 保留，作为表达式原文载体与降级回退。

**降级回退触发条件**（仅在以下场景生效）：
1. 旧数据未重新解析：`table_transform_input` 为空但 `table_field_mapping` 有记录
2. sync 层检测到 `fm.getInputs()` 为空（inputs 加载失败或旧数据）
3. 此时降级为单源 DIRECT 模型：用 `source_alias` + `source_table_short` 构造 1 条 INPUT_TO 边，logic_type 用 `inferLogicTypeFromExpr` 推断
4. **降级不正确**：多源运算场景会丢失非主别名字段。应在 syncLog 记录 WARN 提示用户先 `reExtractAll`

#### 3.3.3 数据流

```
解析期:
  FieldMappingExtractor.extract()
    → List<FieldMapping>  (一行 = 一个 target_field + 整个 source_expr)
    → 每个 FieldMapping 新增 List<TransformInput> inputs
        (拆分 source_expr 得到,含 source_field + role + input_order)

入库期 (ProcExtractServiceImpl):
  1. table_field_mapping 插入一行 (含 logic_type)
  2. table_transform_input 批量插入 N 行

同步期 (Neo4jSyncServiceImpl):
  读 table_field_mapping + table_transform_input
    → 写 Transform 节点 (属性来自 field_mapping)
    → 写 INPUT_TO 边 (来自 transform_input, 多条)
    → 写 OUTPUT_TO 边 (1 条)
```

### 3.4 sql_hint 生成规则

格式：`block_seq={n}; via_temp={temp_table_chain}`

- `block_seq`：来自 `sql_block_info.block_seq`
- `via_temp`：目标表若为临时表，附上其上游真实表链路（从 `table_lineage` 反查，防环）
- 非临时表：仅 `block_seq={n}`

### 3.5 logic_type 枚举（15 类）

| logic_type | 匹配规则 | 示例 |
|------------|----------|------|
| `DIRECT` | 单一 alias.field，无运算符 | `T01.acct_no` |
| `LITERAL` | 字面量 | `'N'`、`123`、`DATE '{ETL_TX_DT}'` |
| `ADDITION` | 含 `+` | `T01.amt + T02.fee` |
| `SUBTRACTION` | 含 `-` | `T01.amt - T02.fee` |
| `MULTIPLICATION` | 含 `*` | `T01.rate * T01.amt` |
| `DIVISION` | 含 `/` | `T01.amt / T01.days` |
| `CASE` | `CASE WHEN...` | `CASE WHEN T01.flag='1' THEN T02.a ELSE T03.b END` |
| `SUM` | `SUM(...)` | `SUM(T01.amt)` |
| `COUNT` | `COUNT(...)` | `COUNT(DISTINCT T01.acct_no)` |
| `AVG` | `AVG(...)` | `AVG(T01.amt)` |
| `MAX` | `MAX(...)` | `MAX(T01.amt)` |
| `MIN` | `MIN(...)` | `MIN(T01.amt)` |
| `NVL` | `NVL(...)` / `COALESCE(...)` | `NVL(T01.a, T02.b)` |
| `CONCAT` | `\|\|` 或 `CONCAT(...)` | `T01.prefix \|\| T01.code` |
| `FUNCTION_OTHER` | 其他函数调用 | `SUBSTR(T01.code, 1, 4)` |

**优先级**（按顺序匹配，命中即停）：

1. LITERAL
2. CASE
3. 聚合函数（SUM/COUNT/AVG/MAX/MIN）
4. NVL/COALESCE
5. CONCAT
6. 算术运算符（按 +、-、*、/ 顺序，混合时取首个出现的运算符，仅识别括号深度 0 处的运算符）
7. FUNCTION_OTHER
8. DIRECT

### 3.6 数据一致性约束

| 约束 | 说明 |
|------|------|
| Transform 节点不会孤儿 | 每个 Transform 至少 1 条 INPUT_TO（虚拟 `__literal__` 兜底）+ 1 条 OUTPUT_TO |
| Transform id 全局唯一 | `md5(proc_name + "\|" + target_col_key)` |
| INPUT_TO 边的源必为 Column | 包括虚拟 `__literal__` Column |
| OUTPUT_TO 边的目标必为 Column | Transform 与目标 Column 1:1 |
| `table_field_mapping.logic_type` 与 Transform 节点 `logic_type` 一致 | 解析期写入 field_mapping，同步期透传 |

---

## 4. 解析器改造

### 4.1 数据结构变更

#### 4.1.1 新增 DTO：`TransformInput`

文件：`parser/dto/TransformInput.java`（新增）

```java
@Data
public class TransformInput {
    private String sourceTableShort;
    private String sourceField;
    private String sourceAlias;
    private String role;          // value / condition
    private int inputOrder;
}
```

#### 4.1.2 修改 DTO：`FieldMapping`

新增字段：

```java
private List<TransformInput> inputs = new ArrayList<>();
private String logicType;
```

### 4.2 FieldMappingExtractor 改造

| 方法 | 改动 |
|------|------|
| `extract()` | 构造每个 `FieldMapping` 后调用 `populateInputsAndLogicType()` 填充 inputs + logicType |
| `extractAliasAndTable()` | 不变 |
| `extractPrimaryAliasField()` | 不变 |
| **`populateInputsAndLogicType()`** | 新增。调用 `extractInputs()` + `classifyLogicType()` 回填 |
| **`extractInputs()`** | 新增。表达式拆分核心 |
| **`classifyLogicType()`** | 新增。按优先级分类 |

### 4.3 表达式拆分算法：`extractInputs()`

**输入**：`expr`、`aliasMap`、`singleSourceTable`
**输出**：`List<TransformInput>`

**算法**：

1. 预处理：trim 表达式
2. 识别表达式类型并按类型提取字段 + role：
   - **CASE WHEN**：正则匹配 `WHEN...THEN` 段，条件段字段 → role=condition，取值段字段 → role=value
   - **普通表达式**：提取所有 alias.field 模式，全部 role=value
3. 对每个匹配到的 alias.field：
   - 通过 aliasMap 反查 source_table_short
   - aliasMap 未命中且 singleSourceTable != null → 用 singleSourceTable
   - 两者都失败 → sourceTableShort=null（同步层跳过该输入）
4. 去重：同一 (sourceTableShort, sourceField, role) 多次出现 → 保留首次
5. 按 input_order 排序返回

**关键正则**：

```java
private static final Pattern CASE_PATTERN = Pattern.compile("(?i)CASE\\s+(.+?)\\s+END");
private static final Pattern WHEN_THEN_PATTERN = Pattern.compile(
    "(?i)WHEN\\s+(.+?)\\s+THEN\\s+(.+?)(?=\\s+(?:WHEN|ELSE|END)\\b)");
private static final Pattern ELSE_PATTERN = Pattern.compile(
    "(?i)ELSE\\s+(.+?)(?=\\s+END\\b)");
```

**示例验证**：

| 表达式 | 拆分结果 |
|--------|----------|
| `T01.acct_no` | `[{T01.acct_no, value, 1}]` |
| `T01.amt + T02.fee` | `[{T01.amt, value, 1}, {T02.fee, value, 2}]` |
| `SUM(T01.amt)` | `[{T01.amt, value, 1}]` |
| `NVL(T01.a, T02.b)` | `[{T01.a, value, 1}, {T02.b, value, 2}]` |
| `CASE WHEN A.flag='1' THEN B.amt ELSE C.amt END` | `[{A.flag, condition, 1}, {B.amt, value, 2}, {C.amt, value, 3}]` |
| `'N'`（字面量） | `[]`（空列表） |
| `acct_no`（无 alias 前缀，单源表 T01） | `[{T01.acct_no, value, 1}]` |

### 4.4 logic_type 分类：`classifyLogicType()`

按 §3.5 优先级顺序匹配，命中即返回。

**`findFirstTopLevelOperator()`**：在括号深度 0 处扫描首个算术运算符，排除负号开头和连续运算符场景。

### 4.5 入库改造

#### 4.5.1 新增实体：`TableTransformInput`

文件：`entity/TableTransformInput.java`（新增）

```java
@Data
@TableName("table_transform_input")
public class TableTransformInput {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fieldMappingId;
    private Long lineageId;
    private String procName;
    private String targetField;
    private String sourceTableShort;
    private String sourceField;
    private String sourceAlias;
    private String role;
    private Integer inputOrder;
    private LocalDateTime extractTime;
}
```

#### 4.5.2 新增 Mapper：`TableTransformInputMapper`

```java
@Mapper
public interface TableTransformInputMapper extends BaseMapper<TableTransformInput> {
}
```

#### 4.5.3 `ProcExtractServiceImpl.saveMetadata()` 改造

- **删旧数据**：删除 `table_transform_input`（按 proc_name）
- **插入字段映射后插入 inputs**：每个 `FieldMapping` 插入 `table_field_mapping` 后，遍历 `fm.getInputs()` 批量插入 `table_transform_input`

#### 4.5.4 `ProcExtractServiceImpl.deleteProc()` 改造

删除 `table_field_mapping` 后同步删除 `table_transform_input`。

---

## 5. Neo4j 同步改造

### 5.1 改造点概览

文件：`service/impl/Neo4jSyncServiceImpl.java`

| 区域 | 改动 |
|------|------|
| 依赖注入 | 新增 `TableTransformInputMapper` + `SqlBlockInfoMapper` |
| `syncProc()` 主流程 | 字段血缘写入从 MAPS_TO 改为 Transform + INPUT_TO + OUTPUT_TO |
| `deleteProcData()` | 删除 MAPS_TO 改为删除 Transform 节点及其关系 |
| `clearAllData()` | 不变 |
| `classifyTransformation()` | **删除**（逻辑迁移到解析期） |
| `createMapsToRelationship()` | **删除** |
| 孤儿清理 | 范围扩展到 Transform |
| 新增方法 | `mergeTransformNode()` / `createInputToRelationship()` / `createOutputToRelationship()` / `generateTransformId()` / `generateSqlHint()` / `mergeLiteralColumn()` / `inferLogicTypeFromExpr()` / `findTempUpstreamChain()` |

### 5.2 `deleteProcData()` 改造

```java
// 1. 删除该 proc 的 READS 关系（不变）
neo4jClient.query("MATCH ()-[r:READS {proc_name: $procName}]->() DELETE r")
        .bind(procName).to("procName").run();

// 2. 删除该 proc 的 MAPS_TO 关系（过渡期兼容旧数据,新数据无 MAPS_TO 但旧数据可能残留）
neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
        .bind(procName).to("procName").run();

// 3. 删除该 proc 的 Transform 节点（DETACH DELETE 自动清除 INPUT_TO/OUTPUT_TO 关系）
neo4jClient.query("MATCH (t:Transform {proc_name: $procName}) DETACH DELETE t")
        .bind(procName).to("procName").run();

// 4. 删除 Script 节点及其关系（不变）
neo4jClient.query("MATCH (s:Script {file: $scriptFile})-[r]->() DELETE r")
        .bind(scriptFile).to("scriptFile").run();
neo4jClient.query("MATCH (s:Script {file: $scriptFile}) DETACH DELETE s")
        .bind(scriptFile).to("scriptFile").run();

// 5. 清理孤儿节点（范围扩展含 Transform）
neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column OR n:Transform) AND NOT (n)--() DELETE n").run();
```

**关键点**：步骤 2 保留 MAPS_TO 删除逻辑，因为旧 Neo4j 数据可能残留 MAPS_TO 关系（迁移未完成时）。DETACH DELETE Transform 不会清除这些 MAPS_TO 关系（它们是 Column→Column 直接关系，不经过 Transform）。迁移完成后此步骤成为空操作，可保留无害。

### 5.3 `syncProc()` 字段血缘写入

**数据加载**：加载 `mappings`（table_field_mapping）后，加载 `transformInputs`（table_transform_input）并按 `field_mapping_id` 分组。

**写入逻辑**（每个 FieldMapping）：

1. 目标 Column 节点 + BELONGS_TO（不变）
2. Transform 节点：`mergeTransformNode(id, expression, logicType, sqlHint, procName, targetColKey, scriptFile)`
   - logicType 为 null 时降级调用 `inferLogicTypeFromExpr()`
3. INPUT_TO 关系：
   - inputs 非空：遍历 inputs，跳过 sourceTableShort 为空的，创建 Column 节点 + INPUT_TO 边
   - inputs 为空（字面量）：创建虚拟 `__literal__` Column + INPUT_TO 边
4. OUTPUT_TO 关系：1 条

### 5.4 关键方法实现

#### `generateTransformId()`

```java
private String generateTransformId(String procName, String targetColKey) {
    String raw = procName + "|" + targetColKey.toLowerCase();
    return md5Hex(raw);  // 失败降级用 hashCode
}
```

#### `mergeTransformNode()`

```cypher
MERGE (t:Transform {id: $id})
SET t.expression = $expression, t.logic_type = $logicType,
    t.sql_hint = $sqlHint, t.proc_name = $procName,
    t.target_col_key = $targetColKey, t.script_file = $scriptFile
```

#### `createInputToRelationship()`

```cypher
MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId})
MERGE (c)-[r:INPUT_TO]->(t)
SET r.role = $role[, r.source_alias = $sourceAlias]
```

#### `createOutputToRelationship()`

```cypher
MATCH (t:Transform {id: $transformId}), (c:Column {col_key: $targetColKey})
MERGE (t)-[:OUTPUT_TO]->(c)
```

#### `mergeLiteralColumn()`

- col_key = `__literal__.{transformId}`（保证唯一）
- table_full_name = `__literal__`
- **不创建 BELONGS_TO 关系**

#### `generateSqlHint()`

```java
sb.append("block_seq=").append(edge.getSqlBlockSeq());
if (isTempTableName(targetShort)) {
    String tempChain = findTempUpstreamChain(edge.getTargetTable(), allEdges);
    if (tempChain != null) sb.append("; via_temp=").append(tempChain);
}
```

#### `findTempUpstreamChain()`

反查 `table_lineage`，遇到非临时表终止，`visited` 集合防环，返回 `VT_03←F_AC_CRBS_DEPOSIT_ACCT` 格式。

#### `inferLogicTypeFromExpr()`（降级兼容）

简化版 `classifyLogicType`，仅用于旧数据（logic_type=null）兼容。

### 5.5 删除的方法

- `classifyTransformation(String sourceExpr)` — 逻辑迁移到解析期 `FieldMappingExtractor.classifyLogicType`
- `createMapsToRelationship(...)` — 被 Transform + INPUT_TO + OUTPUT_TO 替代
- `extractFieldName(String expr)` — 新模型 inputs 已在解析期拆分出 sourceField，sync 层不再需要从 sourceExpr 提取主字段

### 5.6 保留复用的方法

- `findTableFullName(edges, sourceTableShort)` — 保留不变。新增方法 `createInputToRelationship` 复用之，将 inputs 的 sourceTableShort 转为全名构造 sourceColKey
- `findEdgeById(edges, lineageId)` — 保留不变
- `toColKey(tableFullName, fieldName)` — 保留不变
- `extractShortName(fullName)` — 保留不变，`generateSqlHint` 复用
- `isTempTableName(shortName)` — 保留不变，`generateSqlHint` 复用

---

## 6. 影响分析查询改造

### 6.1 改造点概览

文件：`service/impl/ImpactAnalysisServiceImpl.java`

| 方法 | 改动 |
|------|------|
| `analyzeFieldLevel()` | Cypher 从 `MAPS_TO*1..N` 改为 `(INPUT_TO\|OUTPUT_TO)*` 变长路径 |
| `expand()`（字段级分支） | 同上；新增 `isTransformId()` 判断避免 Transform 节点误走表级分支 |
| `buildArrow()` | **保留不变**（表级查询仍用 `READS` 关系） |
| **`buildFieldArrow()`** | **新增**。字段级专用，关系类型为 `INPUT_TO\|OUTPUT_TO`，跳数 = `depth * 2` |
| `buildImpactedByDepthFromFieldNodes()` | 新增 `extractTableFromId` + INPUT_TO/OUTPUT_TO 配对推导表级 impactSource |
| `buildStats()` | 排除 TRANSFORM 节点（兼容表级和字段级两种模式） |
| 表级查询 | **不变**，仍走 READS 关系 |
| `ReportImpactServiceImpl.traceReportLineage()` | **不变**，透明继承 |

### 6.2 深度语义对齐

| 概念 | 旧模型 | 新模型 |
|------|--------|--------|
| 1 个字段跳 | 1 个 MAPS_TO 关系 | 2 个关系（INPUT_TO + OUTPUT_TO） |
| `max-depth=5` | `MAPS_TO*1..5` | `(INPUT_TO\|OUTPUT_TO)*1..10` |
| Cypher 跳数公式 | `depth` | `depth * 2` |

### 6.3 `analyzeFieldLevel()` 实现

**双查询策略**：

**步骤 1：查询所有可达 Column 节点 + 深度**

```cypher
MATCH path = (src:Column {col_key: $key})-[:INPUT_TO|OUTPUT_TO*1..{cypherMaxHops}]->(tgt:Column)
WHERE src <> tgt
WITH tgt, min(length(path)) AS minRelLen
RETURN tgt.col_key AS id, tgt.name AS label,
       tgt.table_full_name AS tableFullName,
       (minRelLen + 1) / 2 AS depth
ORDER BY depth, tgt.col_key
```

**步骤 2：查询路径上的所有 Transform 节点 + 边**

```cypher
MATCH path = (src:Column {col_key: $key})-[:INPUT_TO|OUTPUT_TO*1..{cypherMaxHops}]->(tgt:Column)
WHERE src <> tgt
UNWIND nodes(path) AS n
WITH DISTINCT n
WHERE n:Transform
OPTIONAL MATCH (in:Column)-[rIn:INPUT_TO]->(n)
OPTIONAL MATCH (n)-[rOut:OUTPUT_TO]->(out:Column)
RETURN n.id AS transformId, n.expression AS expression,
       n.logic_type AS logicType, n.sql_hint AS sqlHint,
       n.proc_name AS procName, n.script_file AS scriptFile,
       collect(DISTINCT {colKey: in.col_key, role: rIn.role, alias: rIn.source_alias}) AS inputs,
       out.col_key AS outputColKey
```

**实现注意**：
- `{cypherMaxHops}` 通过**字符串拼接**注入 Cypher（Neo4j 参数化查询不支持关系跳数参数化），非参数绑定
- 起点字段 label 提取需安全处理，避免 `__literal__.{transformId}` 等无 3 段点分隔的 id 数组越界：

```java
String[] parts = fieldFullName.split("\\.");
String label = parts.length >= 3 ? parts[2] : fieldFullName;
startNode.setLabel(label);
```

**方向控制**：`buildFieldArrow(direction, cypherMaxHops)` 生成 `<-[:...]-` / `-[:...]->` / `-[:...]-`。

### 6.4 `expand()` 字段级分支改造

复用 `analyzeFieldLevel` 的步骤 2 逻辑，抽取为共享方法 `queryTransformNodesAndEdges()`。

**`isFieldLevel` 判断改造**：

现有代码 [L704](file:///d:/HB/work_zuoye/proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java#L704) 通过点数判断字段级：
```java
boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2;
```

Transform id 是 md5 hash（无点分隔），点击 Transform 节点展开时会误走表级分支。新增 `isTransformId` 判断：

```java
private boolean isTransformId(String id) {
    if (id == null || id.isEmpty()) return false;
    // Transform id 是 32 位 md5 hex,无点分隔
    return id.matches("^[a-f0-9]{32}$") && !id.contains(".");
}

// expand() 中改造:
boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2
        || isTransformId(request.getNodeId());
```

**Transform 节点展开语义**：Transform 节点点击"展开下游"时，沿其 OUTPUT_TO 目标 Column 继续展开。Cypher 以 Transform id 为起点：

```cypher
MATCH (t:Transform {id: $key})-[:OUTPUT_TO]->(start:Column)
MATCH path = (start)-[:INPUT_TO|OUTPUT_TO*1..{cypherMaxHops}]->(tgt:Column)
...
```

或简化处理：前端点击 Transform 节点时不触发 `expand`（Transform 无下游展开语义，仅作为中间节点展示），规格 §7.3 明确说明前端禁用 Transform 节点的展开按钮。**推荐简化处理**。

### 6.5 `buildImpactedByDepthFromFieldNodes()` 改造

**问题背景**：新模型没有直接的 `Column→Column` 边，只有 `Column→Transform`（INPUT_TO）和 `Transform→Column`（OUTPUT_TO）。现有代码通过边 source/target 的 split 推导表级 impactSource，但 INPUT_TO/OUTPUT_TO 边的 source 或 target 是 Transform id（md5 hash，无点分隔），会被跳过，导致 `tableImpactSourceMap` 为空。

**解决方案**：通过 INPUT_TO + OUTPUT_TO 配对推导表级 impactSource。

`extractTableFromId(id)`：

```java
private String extractTableFromId(String id) {
    if (id == null) return null;
    if (id.startsWith("__literal__")) return null;
    String[] parts = id.split("\\.");
    if (parts.length < 3) return null;  // Transform id 是 md5 hash,无点分隔
    return (parts[0] + "." + parts[1]).toLowerCase();
}
```

Transform id（md5 hash）和 `__literal__` 节点自动被排除。

**表级 impactSource 推导改造**（替换现有 [L186-L195](file:///d:/HB/work_zuoye/proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java#L186-L195)）：

```java
// 2. 表级 impactSource：通过 Transform 节点配对 INPUT_TO + OUTPUT_TO 推导
Map<String, String> tableImpactSourceMap = new LinkedHashMap<>();

// 2.1 构造 transformId -> outputColKey 映射
Map<String, String> transformOutputMap = new HashMap<>();
for (ImpactAnalysisVO.EdgeVO e : edges) {
    if ("OUTPUT_TO".equals(e.getEdgeType())) {
        transformOutputMap.put(e.getSource(), e.getTarget());
    }
}

// 2.2 对每个 INPUT_TO 边,配对同 Transform 的 OUTPUT_TO,推导 表A → 表B
for (ImpactAnalysisVO.EdgeVO e : edges) {
    if (!"INPUT_TO".equals(e.getEdgeType())) continue;
    String transformId = e.getTarget();
    String outputColKey = transformOutputMap.get(transformId);
    if (outputColKey == null) continue;
    String srcTable = extractTableFromId(e.getSource());
    String tgtTable = extractTableFromId(outputColKey);
    if (srcTable == null || tgtTable == null) continue;
    if (!tgtTable.equals(sourceTable) && !tableImpactSourceMap.containsKey(tgtTable)) {
        tableImpactSourceMap.put(tgtTable, srcTable);
    }
}
```

**兼容降级**：若所有边都没有 `edgeType`（旧数据未迁移），回退到原 split 逻辑：

```java
// 降级:边无 edgeType 时(旧数据),用原 split 逻辑
if (tableImpactSourceMap.isEmpty()) {
    for (ImpactAnalysisVO.EdgeVO e : edges) {
        String[] srcParts = e.getSource().split("\\.");
        String[] tgtParts = e.getTarget().split("\\.");
        if (srcParts.length < 3 || tgtParts.length < 3) continue;
        String srcTable = (srcParts[0] + "." + srcParts[1]).toLowerCase();
        String tgtTable = (tgtParts[0] + "." + tgtParts[1]).toLowerCase();
        if (!tgtTable.equals(sourceTable) && !tableImpactSourceMap.containsKey(tgtTable)) {
            tableImpactSourceMap.put(tgtTable, srcTable);
        }
    }
}
```

### 6.6 `buildStats()` 改造

**问题背景**：`buildStats` 是表级和字段级**共用**的方法。表级分析时 nodes 全是 TABLE 节点，若 `maxDepthReached` 改为仅保留 FIELD 节点会导致表级 `maxDepthReached` 恒为 0。

**改造原则**：排除 TRANSFORM 节点（无 depth），保留 FIELD 和 TABLE 节点，兼容两种模式。

```java
// affectedFieldCount:现有逻辑已是 FIELD 过滤,TRANSFORM 不会误计入(确认无需改动)
stats.setAffectedFieldCount((int) nodes.stream()
        .filter(n -> "FIELD".equals(n.getNodeLevel()))
        .count());

// maxDepthReached:改为排除 TRANSFORM 节点(保留 FIELD 和 TABLE,兼容两种模式)
// 现有代码 filter(n -> !Boolean.TRUE.equals(n.getIsTemp())) 仍保留(表级用)
// 新增 filter(n -> !"TRANSFORM".equals(n.getNodeLevel())) 排除 TRANSFORM
int actualMax = nodes.stream()
        .filter(n -> !"TRANSFORM".equals(n.getNodeLevel()))  // 排除 TRANSFORM
        .filter(n -> !Boolean.TRUE.equals(n.getIsTemp()))     // 排除临时表(表级用)
        .map(ImpactAnalysisVO.NodeVO::getDepth)
        .filter(Objects::nonNull)
        .mapToInt(Integer::intValue)
        .max()
        .orElse(0);
stats.setMaxDepthReached(actualMax);
```

**注意**：`affectedFieldCount` 现有代码已是 `FIELD` 过滤，TRANSFORM 节点不会误计入，无需改动。仅 `maxDepthReached` 需要新增 TRANSFORM 排除。

### 6.7 `ImpactAnalysisVO` 扩展

#### `NodeVO` 新增字段

```java
private String nodeLevel;      // TABLE / FIELD / TRANSFORM
private String expression;     // Transform 专用
private String logicType;      // Transform 专用
private String sqlHint;        // Transform 专用
private String procName;       // Transform 专用（冗余）
```

#### `EdgeVO` 新增字段

```java
private String edgeType;       // INPUT_TO / OUTPUT_TO / READS
private String role;           // INPUT_TO 的 role
private String expression;     // OUTPUT_TO 边的 expression
```

### 6.8 报表列溯源影响

`ReportImpactServiceImpl.traceReportLineage` 无需改动，调用 `analyze()` 透明继承，返回 VO 自动含 Transform 节点。

---

## 7. 前端展示改造

### 7.1 改造点概览

| 文件 | 改动 |
|------|------|
| `components/impact/ImpactNode.vue` | 新增 TRANSFORM 节点渲染 |
| `components/impact/ImpactFlowGraph.vue` | 节点类型分流 + 边样式区分 + 字段透传 |
| `components/impact/ImpactNodeDetail.vue` | 新增 TRANSFORM 详情面板 |
| `components/impact/ImpactEdgeDetail.vue` | 新增 INPUT_TO / OUTPUT_TO 边详情 |
| `views/ImpactAnalysis.vue` | 字段级跳过 buildWithoutTemp + 筛选适配 + edges 透传 |
| `components/impact/ImpactStats.vue` | 可选：新增 Transform 节点数 |
| `views/ReportImpactView.vue` | 不变（复用 ImpactFlowGraph） |

### 7.2 ImpactNode.vue 改造

#### 7.2.1 TRANSFORM 节点模板

矩形 + 左边框粗色条 + logic_type 徽章：

```vue
<div v-if="data.nodeLevel === 'TRANSFORM'" class="impact-node transform-node"
     :style="{ borderLeftColor: logicColor }">
  <div class="layer-badge" :style="{ background: logicColor }">{{ logicBadge }}</div>
  <div class="node-body">
    <div class="node-label" :title="data.expression">{{ expressionShort }}</div>
    <div v-if="data.logicType" class="logic-tag">{{ data.logicType }}</div>
  </div>
</div>
```

#### 7.2.2 logic_type 着色与徽章

| logic_type | 颜色 | 徽章 |
|------------|------|------|
| DIRECT | #94a3b8 灰 | · |
| LITERAL | #64748b 深灰 | " |
| ADDITION | #3b82f6 蓝 | + |
| SUBTRACTION | #06b6d4 青 | - |
| MULTIPLICATION | #8b5cf6 紫 | × |
| DIVISION | #a855f7 浅紫 | ÷ |
| CASE | #f59e0b 橙 | ? |
| SUM | #10b981 绿 | Σ |
| COUNT | #22c55e 浅绿 | # |
| AVG | #16a34a 深绿 | Ā |
| MAX | #15803d 更深绿 | ↑ |
| MIN | #166534 最深绿 | ↓ |
| NVL | #ef4444 红 | N |
| CONCAT | #ec4899 粉 | ∥ |
| FUNCTION_OTHER | #6366f1 靛蓝 | ƒ |

expression 截断显示前 30 字符 + `...`。

### 7.3 ImpactFlowGraph.vue 改造

#### 7.3.1 注册新节点类型

```vue
<template #node-transformNode="props">
  <ImpactNode :id="props.id" :data="props.data" />
</template>
```

#### 7.3.2 节点类型分流

```js
type: node.nodeLevel === 'TRANSFORM' ? 'transformNode' : 'impactNode'
```

#### 7.3.3 边样式区分

| edgeType | 样式 |
|----------|------|
| INPUT_TO | 虚线灰色（`strokeDasharray: '5,5'`），不动画 |
| OUTPUT_TO | 实线蓝色（#3b82f6），动画 |
| READS | 保持原样 |

#### 7.3.4 字段透传

`expandNode` 和 `loadInitial` 中，nodeMap 和 edgeMap 新增字段：
- nodeMap：`nodeLevel, expression, logicType, sqlHint, procName`
- edgeMap：`edgeType, role, expression, sourceAlias, procName`

#### 7.3.5 Transform 节点禁用展开按钮

Transform 节点作为中间计算节点，无下游展开语义。点击 Transform 节点时：
- `ImpactNodeDetail` 不显示"展开下游"按钮（仅显示表达式详情 + 输入字段列表）
- `ImpactFlowGraph` 的 `handleNodeClick` 对 TRANSFORM 节点不触发 `expand` 请求

```js
function handleNodeClick(node) {
  selectedNode.value = node
  // Transform 节点不触发展开
  if (node.data.nodeLevel === 'TRANSFORM') return
  // 原逻辑:加载详情面板
}
```

对应 §6.4 的简化处理方案：后端 `expand` 仍保留 `isTransformId` 判断（防御性编程），但前端不主动触发。

### 7.4 ImpactNodeDetail.vue 改造

TRANSFORM 节点详情面板：

```vue
<template v-if="node.nodeLevel === 'TRANSFORM'">
  <el-descriptions :column="1" border size="small">
    <el-descriptions-item label="类型">
      <el-tag type="warning" size="small">TRANSFORM</el-tag>
    </el-descriptions-item>
    <el-descriptions-item label="logic_type">
      <el-tag :color="logicColor" size="small">{{ node.logicType }}</el-tag>
    </el-descriptions-item>
    <el-descriptions-item label="表达式">
      <code class="expression-code">{{ node.expression }}</code>
    </el-descriptions-item>
    <el-descriptions-item label="SQL 提示" v-if="node.sqlHint">
      <code class="hint-code">{{ node.sqlHint }}</code>
    </el-descriptions-item>
    <el-descriptions-item label="存储过程" v-if="node.procName">{{ node.procName }}</el-descriptions-item>
    <el-descriptions-item label="目标字段" v-if="node.id">{{ node.id }}</el-descriptions-item>
  </el-descriptions>
  <div v-if="inputFields.length" style="margin-top: 10px">
    <div style="font-weight: bold; margin-bottom: 5px">输入字段</div>
    <div v-for="(inp, i) in inputFields" :key="i" class="input-item">
      <el-tag :type="inp.role === 'condition' ? 'warning' : 'success'" size="small">{{ inp.role }}</el-tag>
      <code>{{ inp.colKey }}</code>
      <span v-if="inp.alias" style="color: #999; margin-left: 6px">({{ inp.alias }})</span>
    </div>
  </div>
</template>
```

`inputFields` 从 `edges` prop 动态查询（filter `edgeType === 'INPUT_TO' && target === node.id`）。

父组件 `ImpactAnalysis.vue` 传递 `:edges="allEdgesList"`。

### 7.5 ImpactEdgeDetail.vue 改造

新增展示：`edgeType`、`role`（INPUT_TO）、`expression`（OUTPUT_TO）。

### 7.6 ImpactAnalysis.vue 改造

#### 7.6.1 `handleAnalyze` 字段级适配

```js
if (isFieldLevel.value) {
  allNodesByDepth.value = res.data?.nodes || []
  allEdgesList.value = res.data?.edges || []
  visibleNodeIds.value = allNodesByDepth.value.map(n => n.id)
} else {
  // 表级:沿用 buildWithoutTemp
}
```

#### 7.6.2 筛选逻辑适配

`availableSchemas`：跳过 TRANSFORM 节点。

`matchedNodeIds`：TRANSFORM 节点根据其上下游字段是否命中来决定保留。

#### 7.6.3 字段级隐藏"折叠临时表"开关

字段级模式不调用 `buildWithoutTemp`，UI 隐藏该开关。

### 7.7 ImpactStats.vue 改造（可选）

新增 Transform 节点数统计（仅字段级显示）。

---

## 8. 错误处理与边界

### 8.1 跨层边界场景

| # | 场景 | 触发层 | 处理策略 |
|---|------|--------|----------|
| 1 | `sourceExpr` 为 null | 解析器 | inputs 为空列表，logic_type=DIRECT |
| 2 | 表达式无 alias.field（纯字面量） | 解析器 | inputs 为空列表，logic_type=LITERAL；sync 层生成虚拟 `__literal__` Column |
| 3 | aliasMap 未命中 alias | 解析器 | 该输入 sourceTableShort=null，sync 层跳过该 INPUT_TO 边 |
| 4 | CASE WHEN 无 ELSE | 解析器 | 仅 WHEN...THEN 段，取值字段从 THEN 后提取 |
| 5 | 表达式拆分异常 | 解析器 | catch 异常，inputs 置空列表，记录警告日志，不中断整体解析 |
| 6 | `singleSourceTable` 推断失败 | 解析器 | sourceTableShort=null，sync 层跳过 |
| 7 | 混合运算优先级 | 解析器 | 取首个运算符作 logic_type，不遵循数学优先级 |
| 8 | `table_transform_input` 为空（旧数据） | 同步 | 检测到 inputs 为空且 `source_alias`/`source_table_short` 非空时，降级为单源 DIRECT 模型（构造 1 条 INPUT_TO 边）。syncLog 记 WARN 提示先 `reExtractAll`。**多源场景降级不正确** |
| 9 | `fm.getLogicType()` 为 null | 同步 | 调用 `inferLogicTypeFromExpr` 降级推断 |
| 10 | `findTableFullName` 返回 null | 同步 | 该输入跳过，不创建 INPUT_TO 边 |
| 11 | `md5` 失败 | 同步 | 降级用 hashCode，记录警告 |
| 12 | Transform 节点写入失败 | 同步 | 抛异常，事务回滚，syncLog 记 FAILED |
| 13 | 临时表反查链路成环 | 同步 | `visited` 集合终止，返回已追溯链路 |
| 14 | 同一 proc 内同名字段（不同 SQL 块） | 同步 | Transform id 相同会被 MERGE 覆盖。**已知限制** |
| 15 | 起点字段无任何 INPUT_TO/OUTPUT_TO 链路 | 查询 | 仅返回起点节点 |
| 16 | Transform 的 OUTPUT_TO 目标 Column 不在路径上 | 查询 | 边仍记录，前端按节点存在性渲染 |
| 17 | 虚拟 `__literal__` Column 出现在链路中 | 查询 | `extractTableFromId` 返回 null，跳过表级聚合 |
| 18 | Cypher 查询超时 | 查询 | catch 异常，降级返回起点节点，记录错误日志 |
| 19 | `max-depth=20` 时 Cypher 跳数 = 40 | 查询 | 前端默认 `max-depth=5`（跳数 10） |
| 20 | Transform 节点已被删除（未重新 sync） | 查询 | 查询返回空，前端显示"无血缘数据" |
| 21 | 字段级模式 + 表级筛选器 | 前端 | TRANSFORM 节点不参与 schema 筛选，通过关联字段命中保留 |
| 22 | Transform 节点表达式过长 | 前端 | `expressionShort` 截断显示前 30 字符 |
| 23 | 字段级模式下"折叠临时表"开关 | 前端 | 不生效，UI 隐藏该开关 |
| 24 | `__literal__` 虚拟节点渲染 | 前端 | 渲染为灰色字段节点，无 layer 徽章 |

### 8.2 旧数据兼容策略

| 场景 | 兼容方式 |
|------|----------|
| 旧 `table_field_mapping` 无 `logic_type` 列 | ALTER TABLE 新增列，旧数据该字段为 null，sync 层用 `inferLogicTypeFromExpr` 降级 |
| 旧 `table_field_mapping` 无对应 `table_transform_input` 记录 | sync 层检测到 inputs 为空且 `source_alias`/`source_table_short` 非空时，降级为单源 DIRECT 模型（构造 1 条 INPUT_TO 边），syncLog 记 WARN。**多源场景降级不正确**，需先 `reExtractAll` |
| 旧 Neo4j 数据含 MAPS_TO 关系 | `deleteProcData` 保留 MAPS_TO 删除逻辑（过渡期兼容），每次 sync 会清理旧 MAPS_TO。全量 `syncAll` 后 MAPS_TO 关系数归零 |
| 旧前端不认识 TRANSFORM 节点 | 升级前端后才能正确渲染 |

### 8.3 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| Transform id 按 proc + target_col_key 唯一 | 同 proc 内同名字段（不同 SQL 块）会 MERGE 覆盖 | 极少数场景下，后写入的 Transform 覆盖前者，表达式以最后一个 SQL 块为准 |
| logic_type 不遵循数学优先级 | `a + b * c` 归为 ADDITION | 仅影响分类着色，不影响血缘关系 |
| 表达式不解析为 AST | 复杂嵌套表达式拆分可能不完整 | 极少数场景下，inputs 列表可能遗漏嵌套字段 |

---

## 9. 数据迁移路径

```
1. 执行 DDL:
   - ALTER TABLE table_field_mapping ADD COLUMN logic_type ...
   - CREATE TABLE table_transform_input ...
   - Neo4j: CREATE CONSTRAINT transform_id_unique ...（应用启动时 Neo4jConstraintInitializer 自动执行）

2. 重新解析(必须先于同步):
   POST /api/proc/re-extract-all  (接口已存在于 ProcExtractController#L76)
   → 重新解析所有存储过程
   → 填充 table_transform_input + table_field_mapping.logic_type

3. 全量同步:
   POST /api/impact/resync
   → deleteProcData 清理所有 proc 的旧 MAPS_TO 关系 + Transform 节点
   → syncProc 重建所有 Transform + INPUT_TO + OUTPUT_TO

4. 验证:
   - Neo4j: MATCH (t:Transform) RETURN count(t) > 0
   - Neo4j: MATCH ()-[r:MAPS_TO]->() RETURN count(r) = 0
   - MySQL: SELECT count(*) FROM table_transform_input > 0
```

### 9.1 错误恢复

| 故障点 | 恢复策略 |
|--------|----------|
| 解析器异常导致部分 proc 失败 | `reExtractAll` 跳过失败 proc，记录日志，其他 proc 正常 |
| 同步层异常导致部分 proc 失败 | `syncAll` 跳过失败 proc，syncLog 记 FAILED |
| DDL 执行失败 | 回滚 DDL，保持旧模型，不升级代码 |
| Neo4j 约束创建失败 | 跳过约束创建，Transform id 唯一性由 MERGE 保证（性能略差） |

---

## 10. 测试策略

### 10.1 测试分层

```
单元测试 (parser/dto/mapper 层)
    ↓
集成测试 (service 层,Mock Neo4j)
    ↓
端到端测试 (Controller → Service → MySQL + Neo4j)
    ↓
前端手动验证
```

### 10.2 单元测试：FieldMappingExtractorTest

| 用例 | 输入表达式 | 期望 logic_type | 期望 inputs |
|------|-----------|-----------------|-------------|
| 单字段 | `T01.acct_no` | DIRECT | `[{T01.acct_no, value, 1}]` |
| 字面量-字符串 | `'N'` | LITERAL | `[]` |
| 字面量-数字 | `123` | LITERAL | `[]` |
| 字面量-schema变量 | `DATE '{ETL_TX_DT}'` | LITERAL | `[]` |
| 加法 | `T01.amt + T02.fee` | ADDITION | `[{T01.amt, value, 1}, {T02.fee, value, 2}]` |
| 减法 | `T01.amt - T02.fee` | SUBTRACTION | `[{T01.amt, value, 1}, {T02.fee, value, 2}]` |
| 乘法 | `T01.rate * T01.amt` | MULTIPLICATION | `[{T01.rate, value, 1}, {T01.amt, value, 2}]` |
| 除法 | `T01.amt / T01.days` | DIVISION | `[{T01.amt, value, 1}, {T01.days, value, 2}]` |
| CASE WHEN 单分支 | `CASE WHEN A.flag='1' THEN B.amt ELSE C.amt END` | CASE | `[{A.flag, condition, 1}, {B.amt, value, 2}, {C.amt, value, 3}]` |
| CASE WHEN 多分支 | `CASE WHEN A.x>0 AND A.y<10 THEN B.z WHEN A.x<0 THEN C.z ELSE D.z END` | CASE | 5 条 inputs |
| SUM | `SUM(T01.amt)` | SUM | `[{T01.amt, value, 1}]` |
| COUNT DISTINCT | `COUNT(DISTINCT T01.acct_no)` | COUNT | `[{T01.acct_no, value, 1}]` |
| NVL | `NVL(T01.a, T02.b)` | NVL | `[{T01.a, value, 1}, {T02.b, value, 2}]` |
| COALESCE | `COALESCE(T01.a, T02.b, T03.c)` | NVL | 3 条 inputs |
| CONCAT 运算符 | `T01.prefix \|\| T01.code` | CONCAT | 2 条 inputs |
| CONCAT 函数 | `CONCAT(T01.prefix, T01.code)` | CONCAT | 2 条 inputs |
| 其他函数 | `SUBSTR(T01.code, 1, 4)` | FUNCTION_OTHER | `[{T01.code, value, 1}]` |
| 混合运算 | `SUM(T01.amt) + T02.fee` | ADDITION | `[{T01.amt, value, 1}, {T02.fee, value, 2}]` |
| 无 alias 前缀（单源表 T01） | `acct_no` | DIRECT | `[{T01.acct_no, value, 1}]` |

**优先级测试**：

- `priority_literal_over_case`
- `priority_case_over_aggregate`
- `priority_aggregate_over_arithmetic`
- `priority_nvl_over_concat`
- `priority_concat_over_arithmetic`
- `mixed_arithmetic_takes_first_op`（`a + b - c` → ADDITION）
- `operator_in_parentheses_ignored`（`SUM(a + b)` → SUM）

### 10.3 集成测试

#### ProcExtractServiceImplTest

| 用例 | 验证点 |
|------|--------|
| 解析单 proc 后查 `table_transform_input` | 行数 = 该 proc 所有 FieldMapping 的 inputs 总和 |
| 解析多源运算 proc | `table_transform_input` 含多条记录，input_order 正确 |
| 解析字面量 proc | `table_transform_input` 为 0 行 |
| `deleteProc` 后查 `table_transform_input` | 该 proc 的记录全部删除 |
| `table_field_mapping.logic_type` | 与表达式分类一致 |

#### Neo4jSyncServiceTest

| 用例 | 验证点 |
|------|--------|
| 单字段 DIRECT 同步 | 1 Transform + 1 INPUT_TO + 1 OUTPUT_TO |
| 字面量 LITERAL 同步 | 1 Transform + 1 虚拟 `__literal__` INPUT_TO + 1 OUTPUT_TO |
| 多源 ADDITION 同步 | 1 Transform + 2 INPUT_TO + 1 OUTPUT_TO |
| CASE WHEN 同步 | 1 Transform + N INPUT_TO（含 condition/value 角色）+ 1 OUTPUT_TO |
| 删除 proc 后验证 | Transform 节点和 INPUT_TO/OUTPUT_TO 关系被清除 |
| 重新同步后验证 | Transform 节点重建，id 不变 |
| `inferLogicTypeFromExpr` 降级 | 旧数据（logic_type=null）同步后 Transform.logic_type 正确 |
| `generateSqlHint` 临时表场景 | sql_hint 含 `via_temp=...` |
| `generateSqlHint` 非临时表场景 | sql_hint 仅含 `block_seq=...` |
| 旧数据降级（inputs 为空但有 source_alias） | 构造 1 条 INPUT_TO 边，syncLog 记 WARN |
| 残留 MAPS_TO 关系清理 | syncProc 后该 proc 的 MAPS_TO 关系数为 0 |

#### ImpactAnalysisServiceTest

| 用例 | 验证点 |
|------|--------|
| 单字段 DIRECT 查询 | 起点 + 1 Transform + 1 目标 Column，2 条边 |
| 多源 ADDITION 查询 | 起点 + 1 Transform + 2 源 Column，3 条边 |
| 字面量 LITERAL 查询 | 起点 + 1 Transform + 1 `__literal__` Column |
| 深度递归（3 跳） | depth=1/2/3 的 Column 节点正确 |
| UPSTREAM 方向 | 沿 INPUT_TO 反向查询 |
| DOWNSTREAM 方向 | 沿 OUTPUT_TO 正向查询 |
| `buildImpactedByDepthFromFieldNodes` 跳过 TRANSFORM | 节点聚合不含 TRANSFORM，depth 来自 FIELD 节点 |
| `buildImpactedByDepthFromFieldNodes` 表级 impactSource 推导 | INPUT_TO+OUTPUT_TO 配对推导出 表A→表B 关系 |
| `buildImpactedByDepthFromFieldNodes` 旧数据降级 | 边无 edgeType 时回退 split 逻辑 |
| `buildStats` 表级模式 | maxDepthReached 来自 TABLE 节点，不受 TRANSFORM 影响 |
| `buildStats` 字段级模式 | maxDepthReached 来自 FIELD 节点，TRANSFORM 不计入 |
| `extractTableFromId` Transform id | 返回 null |
| `extractTableFromId` `__literal__` | 返回 null |
| `isTransformId` 判断 | 32 位 hex 返回 true，字段 id 返回 false |
| Cypher 异常降级 | 仅返回起点节点 |
| 起点 label 安全提取 | `__literal__.{transformId}` 不抛数组越界 |

### 10.4 端到端测试

**测试数据**：3 个测试存储过程脚本（`proc_direct.py` / `proc_addition.py` / `proc_case.py`）

**流程**：

1. `POST /api/proc/parse/path` 解析 3 个脚本
2. 验证 MySQL：`table_field_mapping` + `table_transform_input` 数据正确
3. `POST /api/impact/resync` 全量同步
4. 验证 Neo4j：Transform 节点数 + INPUT_TO/OUTPUT_TO 关系数
5. `POST /api/impact/analyze` 字段级分析
6. 验证返回 VO：nodes 含 TRANSFORM 节点，edges 含 INPUT_TO/OUTPUT_TO
7. 前端渲染验证

**报表列溯源端到端**：

1. 准备测试报表文件
2. `POST /api/report/parse` 解析报表
3. `POST /api/report-impact/trace` 字段溯源
4. 验证返回 VO 含 Transform 节点
5. 前端渲染验证

### 10.5 前端手动验证清单

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 字段级分析 | 图谱正确渲染 Column + Transform + Column 链路 |
| 2 | Transform 着色 | ADDITION 蓝色、CASE 橙色、SUM 绿色等 |
| 3 | INPUT_TO 边样式 | 虚线灰色 |
| 4 | OUTPUT_TO 边样式 | 实线蓝色 |
| 5 | 点击 Transform 节点 | 显示详情（expression / sql_hint / 输入字段列表） |
| 6 | 点击 INPUT_TO 边 | 显示详情（edgeType / role） |
| 7 | 点击 OUTPUT_TO 边 | 显示详情（edgeType / expression） |
| 8 | 字面量场景 | `__literal__` 虚拟节点灰色显示 |
| 9 | 多源 ADDITION | 1 Transform + 2 INPUT_TO 边 + 2 源 Column |
| 10 | CASE WHEN | condition/value 角色标签正确显示 |
| 11 | schema 筛选 | TRANSFORM 节点根据关联字段命中保留 |
| 12 | 报表列溯源 | 图谱正确渲染 Transform 节点 |
| 13 | 表级分析 | 不受影响，仍走 buildWithoutTemp |
| 14 | Transform 节点数统计 | ImpactStats 显示正确 |
| 15 | Transform 节点不触发展开 | 点击 Transform 节点不发起 expand 请求，详情面板无"展开下游"按钮 |
| 16 | 旧数据降级提示 | syncLog 含 WARN 提示先 reExtractAll |

### 10.6 回归测试

| 场景 | 验证点 |
|------|--------|
| 表级影响分析 | 不受 Transform 改造影响，结果与旧版一致 |
| 报表解析 | 不受影响 |
| 存储过程列表/详情 | 不受影响 |
| 血缘图查询 | 不受影响（仍走 READS 关系） |
| 旧数据兼容 | 旧 `table_field_mapping` 无 logic_type 时降级推断正确 |

### 10.7 性能测试

| 指标 | 预期 |
|------|------|
| 单 proc 解析耗时 | 与旧版相当（拆分逻辑开销 < 10%） |
| 单 proc 同步耗时 | 略增（Transform 节点 + 边写入，预计 +20%） |
| 字段级查询耗时 | 略增（变长路径跳数翻倍，预计 +30%） |
| 大批量同步（100+ proc） | 可接受（< 5 分钟） |

---

## 11. 改造影响范围汇总

### 11.1 后端文件清单

| 文件 | 变更类型 |
|------|----------|
| `parser/dto/TransformInput.java` | 新增 |
| `parser/dto/FieldMapping.java` | 修改（+inputs, +logicType） |
| `parser/FieldMappingExtractor.java` | 修改（新增 3 个方法） |
| `entity/TableTransformInput.java` | 新增 |
| `entity/TableFieldMapping.java` | 修改（+logicType） |
| `mapper/TableTransformInputMapper.java` | 新增 |
| `service/impl/ProcExtractServiceImpl.java` | 修改（saveMetadata + deleteProc） |
| `service/impl/Neo4jSyncServiceImpl.java` | 大改（删除 2 方法，新增 8 方法，改写主流程） |
| `service/impl/ImpactAnalysisServiceImpl.java` | 大改（analyzeFieldLevel + expand + buildStats + buildImpactedByDepth） |
| `dto/ImpactAnalysisVO.java` | 修改（NodeVO + EdgeVO 新增字段） |
| `config/Neo4jConstraintInitializer.java` | 修改（新增 Transform 唯一约束） |
| `sql/init.sql` | 修改（新增表7 + table_field_mapping 加列） |

### 11.2 前端文件清单

| 文件 | 变更类型 |
|------|----------|
| `components/impact/ImpactNode.vue` | 修改（新增 TRANSFORM 模板分支 + logic_type 着色） |
| `components/impact/ImpactFlowGraph.vue` | 修改（节点类型分流 + 边样式区分 + 字段透传） |
| `components/impact/ImpactNodeDetail.vue` | 修改（新增 TRANSFORM 详情面板 + 输入字段列表） |
| `components/impact/ImpactEdgeDetail.vue` | 修改（新增 edgeType/role/expression 展示） |
| `views/ImpactAnalysis.vue` | 修改（字段级跳过 buildWithoutTemp + 筛选适配 + edges 透传） |
| `components/impact/ImpactStats.vue` | 可选修改（新增 Transform 节点数统计） |

### 11.3 不受影响的文件

- `controller/*` — 接口签名不变
- `service/impl/ReportImpactServiceImpl.java` — 透明继承
- `views/ReportImpactView.vue` — 复用 ImpactFlowGraph
- 表级查询相关代码 — 仍走 READS 关系
- 报表解析相关代码 — 不涉及字段级血缘

---

## 12. 实现顺序建议

按依赖关系编排，避免中间状态系统不可用：

1. **DDL 变更**：`sql/init.sql` 新增表 + ALTER 列
2. **实体 + DTO**：`TableTransformInput` / `FieldMapping` 扩展 / `TransformInput` 新增
3. **Mapper**：`TableTransformInputMapper`
4. **解析器**：`FieldMappingExtractor` 改造
5. **入库**：`ProcExtractServiceImpl` 改造
6. **Neo4j 约束**：`Neo4jConstraintInitializer`
7. **Neo4j 同步**：`Neo4jSyncServiceImpl` 改造
8. **VO 扩展**：`ImpactAnalysisVO`
9. **查询改造**：`ImpactAnalysisServiceImpl`
10. **前端**：`ImpactNode` → `ImpactFlowGraph` → `ImpactNodeDetail` → `ImpactEdgeDetail` → `ImpactAnalysis`
11. **测试**：单元测试 → 集成测试 → 端到端测试

---

## 附录 A：Cypher 速查

### A.1 创建 Transform 节点

```cypher
MERGE (t:Transform {id: $id})
SET t.expression = $expression, t.logic_type = $logicType,
    t.sql_hint = $sqlHint, t.proc_name = $procName,
    t.target_col_key = $targetColKey, t.script_file = $scriptFile
```

### A.2 创建 INPUT_TO 关系

```cypher
MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId})
MERGE (c)-[r:INPUT_TO]->(t)
SET r.role = $role, r.source_alias = $sourceAlias
```

### A.3 创建 OUTPUT_TO 关系

```cypher
MATCH (t:Transform {id: $transformId}), (c:Column {col_key: $targetColKey})
MERGE (t)-[:OUTPUT_TO]->(c)
```

### A.4 字段级影响分析（DOWNSTREAM）

```cypher
MATCH path = (src:Column {col_key: $key})
      -[:INPUT_TO|OUTPUT_TO*1..{cypherMaxHops}]->(tgt:Column)
WHERE src <> tgt
WITH tgt, min(length(path)) AS minRelLen
RETURN tgt.col_key AS id, (minRelLen + 1) / 2 AS depth
ORDER BY depth
```

### A.5 删除 proc 的 Transform 节点

```cypher
MATCH (t:Transform {proc_name: $procName}) DETACH DELETE t
```

### A.6 验证迁移完成

```cypher
// Transform 节点数 > 0
MATCH (t:Transform) RETURN count(t)

// MAPS_TO 关系数 = 0
MATCH ()-[r:MAPS_TO]->() RETURN count(r)

// INPUT_TO + OUTPUT_TO 关系数
MATCH ()-[r:INPUT_TO]->() RETURN count(r)
MATCH ()-[r:OUTPUT_TO]->() RETURN count(r)
```

---

## 附录 B：logic_type 决策树

```
expression
  ├── 匹配字面量正则? → LITERAL
  ├── 以 CASE 开头且以 END 结尾? → CASE
  ├── 以 SUM(/COUNT(/AVG(/MAX(/MIN( 开头? → 对应聚合类型
  ├── 以 NVL( 或 COALESCE( 开头? → NVL
  ├── 含 || 或以 CONCAT( 开头? → CONCAT
  ├── findFirstTopLevelOperator() 返回运算符?
  │     ├── + → ADDITION
  │     ├── - → SUBTRACTION
  │     ├── * → MULTIPLICATION
  │     └── / → DIVISION
  ├── 以 函数名( 开头? → FUNCTION_OTHER
  └── 默认 → DIRECT
```

---

**文档结束**
