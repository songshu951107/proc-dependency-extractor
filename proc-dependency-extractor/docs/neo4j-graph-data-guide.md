# Neo4j 图数据模型与查询指南

> 访问地址：http://localhost:7474/browser/
> 用户名/密码：与项目配置相同（neo4j/neo4j）

---

## 一、图数据模型总览

```
┌──────────┐  BELONGS_TO   ┌──────────┐
│  Column  │ ────────────> │  Table   │
└────┬─────┘               └────┬─────┘
     │                          │
     │ MAPS_TO                  │ READS
     v                          v
┌──────────┐               ┌──────────┐
│  Column  │               │  Table   │
└──────────┘               └──────────┘
                                ^  |
                                |  v
                           ┌──────────┐
                           │  Script  │
                           └──────────┘
                           PRODUCES / CONSUMES
```

---

## 二、节点（Labels）

### 1. Table 节点

存储表信息，主键 `name`（小写）。

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `name` | string | **唯一标识**，schema.table 全小写 | `cdm.c_agt_deposit_acct_calcu_base` |
| `short_name` | string | 表短名（去 schema） | `C_AGT_DEPOSIT_ACCT_CALCU_BASE` |
| `schema_prefix` | string | schema 前缀 | `CDM` |
| `layer` | string | 数据层级 FDM/ODM/CDM/TDM/PID | `CDM` |
| `is_temp` | boolean | 是否临时表（VT_ 前缀） | `false` |
| `is_source` | boolean | 是否源表（无入边的表） | `true` |
| `script_file` | string | 关联脚本文件名 | `dwa_c_agt_deposit_acct_inf.py` |

**唯一约束：**
```cypher
CREATE CONSTRAINT table_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.name IS UNIQUE
```

### 2. Column 节点

存储字段信息，主键 `col_key`（小写）。

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `col_key` | string | **唯一标识**，schema.table.field 全小写 | `cdm.c_agt_deposit_acct_inf.acct_id` |
| `name` | string | 字段名（大写） | `ACCT_ID` |
| `table_full_name` | string | 所属表全名（小写） | `cdm.c_agt_deposit_acct_inf` |

**唯一约束：**
```cypher
CREATE CONSTRAINT column_key_unique IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE
```

### 3. Script 节点

存储脚本信息，主键 `file`。

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `file` | string | **唯一标识**，脚本文件名 | `dwa_c_cus_pri_cust_loan_sum_crms.py` |
| `target_table` | string | 脚本更新目标表 | `CDM.C_CUS_PRI_CUST_LOAN_SUM` |
| `description` | string | 脚本描述 | "存款产品客户贷款汇总" |

**唯一约束：**
```cypher
CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE
```

---

## 三、关系（Relationships）

### 1. READS（表级读取关系）

`(:Table)-[:READS]->(:Table)`

表示一个脚本中源表被读取后写入目标表。

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `proc_name` | string | **MERGE 键**，存储过程名 | `dwa_c_agt_deposit_acct_inf` |
| `operation` | string | SQL 操作类型 | `INSERT SELECT` / `CREATE TEMP` / `MERGE` / `UPDATE FROM` / `DELETE FROM` |
| `via_temp` | boolean | 是否经过临时表 | `true` |
| `join_conditions` | string(JSON) | JOIN 条件 JSON | `[{"clause":"...","fields":["..."]}]` |
| `where_conditions` | string(JSON) | WHERE 条件 JSON | `[{"clause":"...","fields":["..."]}]` |

**MERGE 语句：**
```cypher
MATCH (src:Table {name: $sourceTable}), (tgt:Table {name: $targetTable})
MERGE (src)-[r:READS {proc_name: $procName}]->(tgt)
SET r.operation = $operation, r.via_temp = $viaTemp,
    r.join_conditions = $joinConditions, r.where_conditions = $whereConditions
```

### 2. MAPS_TO（字段级映射关系）

`(:Column)-[:MAPS_TO]->(:Column)`

表示源字段映射到目标字段。

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `proc_name` | string | **MERGE 键**，存储过程名 | `dwa_c_agt_deposit_acct_inf` |
| `source_expr` | string | **MERGE 键**，源表达式 | `T1.ACCT_ID` |
| `transformation_type` | string | 转换类型 | `direct` / `derived` / `literal` / `aggregation` |
| `script_file` | string | 关联脚本文件 | `dwa_c_agt_deposit_acct_inf.py` |

**transformation_type 取值规则：**
- `literal`：字面量（如 `'N'`、`123`）
- `aggregation`：聚合函数（SUM/COUNT/AVG/MAX/MIN/STDDEV/VARIANCE）
- `derived`：表达式派生（含运算符、函数调用、CASE WHEN）
- `direct`：直接字段引用

**MERGE 语句：**
```cypher
MATCH (s:Column {col_key: $sourceColKey}), (t:Column {col_key: $targetColKey})
MERGE (s)-[r:MAPS_TO {proc_name: $procName, source_expr: $sourceExpr}]->(t)
SET r.transformation_type = $transType, r.script_file = $scriptFile
```

### 3. BELONGS_TO（字段归属关系）

`(:Column)-[:BELONGS_TO]->(:Table)`

表示字段属于某张表。无属性。

```cypher
MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName})
MERGE (c)-[:BELONGS_TO]->(t)
```

### 4. PRODUCES（脚本产出关系）

`(:Script)-[:PRODUCES]->(:Table)`

表示脚本产出某张表。无属性。

```cypher
MATCH (s:Script {file: $scriptFile}), (t:Table {name: $tableName})
MERGE (s)-[:PRODUCES]->(t)
```

### 5. CONSUMES（脚本消费关系）

`(:Script)-[:CONSUMES]->(:Table)`

表示脚本消费（读取）某张表。无属性。

```cypher
MATCH (s:Script {file: $scriptFile}), (t:Table {name: $tableName})
MERGE (s)-[:CONSUMES]->(t)
```

---

## 四、基础查询语句

### 1. 查看全局统计

```cypher
// 节点统计
MATCH (n) RETURN labels(n) AS label, count(*) AS count

// 关系统计
MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS count
```

### 2. 查询所有 Table 节点

```cypher
// 全部表（按层级分组）
MATCH (t:Table)
RETURN t.layer AS layer, t.name AS name, t.short_name AS shortName, t.is_temp AS isTemp
ORDER BY layer, name

// 只看临时表
MATCH (t:Table) WHERE t.is_temp = true RETURN t.name, t.short_name

// 只看源表
MATCH (t:Table) WHERE t.is_source = true RETURN t.name, t.short_name, t.layer
```

### 3. 查询表的直接上下游

```cypher
// 某张表的直接下游（被它影响的表）
MATCH (t:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS]->(downstream:Table)
RETURN downstream.name, downstream.layer, downstream.is_temp

// 某张表的直接上游（影响它的表）
MATCH (upstream:Table)-[:READS]->(t:Table {name: 'cdm.c_agt_deposit_acct_inf'})
RETURN upstream.name, upstream.layer

// 某张表的上下游（双向）
MATCH (t:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS]-(n:Table)
RETURN n.name, n.layer
```

### 4. 查询影响链路（多跳）

```cypher
// 下游影响链路（深度 1..5）
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..5]->(downstream:Table)
RETURN path

// 下游影响链路（带深度）
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..5]->(downstream:Table)
RETURN downstream.name AS name, downstream.short_name AS shortName,
       downstream.layer AS layer, downstream.is_temp AS isTemp,
       min(length(path)) AS depth
ORDER BY depth, name

// 上游溯源链路
MATCH path = (src:Table)-[:READS*1..5]->(tgt:Table {name: 'cdm.c_agt_deposit_acct_inf'})
RETURN path
```

### 5. 查询 READS 关系详情

```cypher
// 查看某条 READS 关系的 JOIN/WHERE 条件
MATCH (src:Table)-[r:READS]->(tgt:Table)
WHERE src.name = 'cdm.c_agt_deposit_acct_calcu_base' AND tgt.name = 'cdm.c_agt_deposit_acct_inf'
RETURN src.name, tgt.name, r.operation, r.proc_name,
       r.join_conditions, r.where_conditions

// 按操作类型过滤
MATCH (src:Table)-[r:READS {operation: 'INSERT SELECT'}]->(tgt:Table)
RETURN src.name, tgt.name, r.proc_name
```

### 6. 查询字段级血缘

```cypher
// 某表的全部字段
MATCH (c:Column)-[:BELONGS_TO]->(t:Table {name: 'cdm.c_agt_deposit_acct_inf'})
RETURN c.name AS field, c.col_key AS colKey

// 某字段的直接映射来源
MATCH (src:Column)-[r:MAPS_TO]->(tgt:Column {col_key: 'cdm.c_agt_deposit_acct_inf.acct_id'})
RETURN src.col_key, src.name, r.source_expr, r.transformation_type, r.proc_name

// 某字段的多跳溯源（深度 1..5）
MATCH path = (src:Column)-[:MAPS_TO*1..5]->(tgt:Column {col_key: 'cdm.c_agt_deposit_acct_inf.acct_id'})
RETURN path

// 查看某脚本的字段映射
MATCH (src:Column)-[r:MAPS_TO {proc_name: 'dwa_c_agt_deposit_acct_inf'}]->(tgt:Column)
RETURN src.col_key, tgt.col_key, r.source_expr, r.transformation_type
```

### 7. 查询脚本信息

```cypher
// 全部脚本
MATCH (s:Script) RETURN s.file, s.target_table, s.description

// 某脚本产出的表
MATCH (s:Script {file: 'dwa_c_agt_deposit_acct_inf.py'})-[:PRODUCES]->(t:Table)
RETURN t.name, t.layer

// 某脚本消费的表
MATCH (s:Script {file: 'dwa_c_agt_deposit_acct_inf.py'})-[:CONSUMES]->(t:Table)
RETURN t.name, t.layer

// 某张表由哪些脚本产出
MATCH (s:Script)-[:PRODUCES]->(t:Table {name: 'cdm.c_agt_deposit_acct_inf'})
RETURN s.file, s.description
```

---

## 五、常用分析查询

### 1. 跨存储过程的表级影响分析

```cypher
// 查找 cdm.c_agt_deposit_acct_calcu_base 的所有下游（跨过程）
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..10]->(downstream:Table)
WHERE src <> downstream
RETURN DISTINCT downstream.name AS name,
       downstream.short_name AS shortName,
       downstream.layer AS layer,
       downstream.is_temp AS isTemp,
       min(length(path)) AS depth
ORDER BY depth, name
```

### 2. 影响传播路径（含中间节点）

```cypher
// 查看完整路径（含所有中间表）
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..5]->(tgt:Table)
WHERE NOT tgt.is_temp  // 过滤临时表
RETURN [n IN nodes(path) | n.name] AS chain, length(path) AS depth
ORDER BY depth
```

### 3. 路径上的直接前驱（impactSource 查询）

```cypher
// 查找某表在源表路径上的直接前驱
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..10]->(tgt:Table {name: 'cdm.c_agt_deposit_acct_inf'})
WITH path, nodes(path) AS ns
WITH ns[size(ns)-2] AS predecessor
RETURN predecessor.name AS source LIMIT 1
```

### 4. 字段级影响分析（带转换类型）

```cypher
// 字段级影响链路（展开每跳）
MATCH path = (src:Column {col_key: 'cdm.c_agt_deposit_acct_calcu_base.acct_id'})-[:MAPS_TO*1..5]->(tgt:Column)
WITH path, relationships(path) AS rels, nodes(path) AS cols
UNWIND range(0, size(rels)-1) AS i
WITH cols[i] AS srcCol, rels[i] AS r, cols[i+1] AS tgtCol, i+1 AS depth
RETURN srcCol.col_key AS sourceId, srcCol.name AS sourceLabel,
       tgtCol.col_key AS targetId, tgtCol.name AS targetLabel,
       depth, r.transformation_type AS transType,
       r.proc_name AS procName, r.source_expr AS sourceExpr,
       r.script_file AS scriptFile
ORDER BY depth, targetId
```

### 5. 按操作类型筛选影响

```cypher
// 只看 INSERT SELECT 产生的影响
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..5]->(downstream:Table)
WHERE ALL(r IN relationships(path) WHERE r.operation = 'INSERT SELECT')
RETURN downstream.name, length(path) AS depth

// 排除经过临时表的路径
MATCH path = (src:Table {name: 'cdm.c_agt_deposit_acct_calcu_base'})-[:READS*1..5]->(tgt:Table)
WHERE ALL(n IN nodes(path) WHERE NOT n.is_temp)
RETURN path
```

### 6. Schema 级统计

```cypher
// 各 schema 的表数量
MATCH (t:Table) WHERE t.schema_prefix IS NOT NULL
RETURN toUpper(t.schema_prefix) AS schema, count(*) AS count
ORDER BY schema

// 各 layer 的表数量
MATCH (t:Table) WHERE t.layer IS NOT NULL
RETURN t.layer AS layer, count(*) AS count
ORDER BY layer

// 各脚本的 READS 关系数
MATCH (s:Script)-[:CONSUMES]->(t:Table)
RETURN s.file, count(*) AS consumedTables
ORDER BY consumedTables DESC
```

---

## 六、维护查询

### 1. 数据清理

```cypher
// 清空全部数据（慎用）
MATCH (n) DETACH DELETE n

// 删除某 proc 的数据
MATCH ()-[r:READS {proc_name: 'dwa_c_agt_deposit_acct_inf'}]->() DELETE r
MATCH ()-[r:MAPS_TO {proc_name: 'dwa_c_agt_deposit_acct_inf'}]->() DELETE r
MATCH (s:Script {file: 'dwa_c_agt_deposit_acct_inf.py'}) DETACH DELETE s
MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n
```

### 2. 孤儿节点检查

```cypher
// 无关系的 Table 节点
MATCH (t:Table) WHERE NOT (t)--() RETURN t.name

// 无 BELONGS_TO 关系的 Column 节点
MATCH (c:Column) WHERE NOT (c)-[:BELONGS_TO]->() RETURN c.col_key
```

### 3. 约束查看

```cypher
SHOW CONSTRAINTS
```

### 4. 索引查看

```cypher
SHOW INDEXES
```

---

## 七、可视化建议

在 Neo4j Browser 中使用以下技巧：

1. **限制返回数量**：查询末尾加 `LIMIT 50` 避免浏览器卡顿
2. **路径可视化**：`RETURN path` 会自动渲染为图
3. **节点颜色**：Browser 默认按 label 自动着色
4. **全屏**：点击图区域右上角的全屏按钮

---

**文档生成时间：** 2026-07-31
**项目：** proc-dependency-extractor
**Neo4j 实例：** localhost:7687
