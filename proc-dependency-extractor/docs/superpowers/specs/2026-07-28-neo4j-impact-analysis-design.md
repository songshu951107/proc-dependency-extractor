# Neo4j 影响分析功能设计文档

- **作者**：AI 助手 + 用户协作
- **创建日期**：2026-07-28
- **状态**：已实现（2026-08 完成；字段级血缘重构为 Transform 中转模型，新增报表影响分析）
- **关联项目**：proc-dependency-extractor
- **文档说明**：第 1-10 章为原始设计稿（保留历史价值）；第 11 章起为实际实现补充，反映生产代码真实结构。两者差异见第 17 章。

## 1. 背景与目标

### 1.1 背景

现有系统基于 MySQL 存储血缘数据，通过递归 CTE 实现表级上下游追踪。存在以下能力缺口：

- **字段级下游影响分析**：当前仅支持字段级上游溯源（`traceUltimateSource`），且仅限单 proc 内，无法回答"某字段变更影响哪些下游表的哪些字段"
- **跨 proc 全链路追踪**：MySQL CTE 仅按 `table_short` 匹配，不区分 proc，难以正确串联跨 proc 的字段链路
- **按操作类型区分影响**：无法筛选 INSERT/MERGE/UPDATE/DELETE 等不同操作类型的影响

### 1.2 目标

引入 Neo4j 图数据库，增强血缘分析能力，支持：

- **场景 A**：表级影响分析（某表变更影响哪些下游表）
- **场景 B**：字段级影响分析（某字段变更影响哪些下游表的哪些字段）
- **场景 C**：跨 proc 全链路追踪（跨存储过程追踪字段最终来源/最终影响）

### 1.3 非目标

- 不替换 MySQL 作为主存储
- 不改造现有血缘图、字段来源列表、SQL 块详情功能
- 不做历史版本对比

## 2. 整体架构

### 2.1 集成策略

**双写并行：MySQL 主、Neo4j 辅**

- 解析存储过程时，MySQL 事务成功后调用 `Neo4jSyncService.syncProc(procName)` 同步到 Neo4j
- 现有 API 仍走 MySQL，新增"影响分析"API 走 Neo4j
- Neo4j 同步失败不影响解析主流程，通过日志表 + 重试机制保障最终一致性

### 2.2 组件架构

```
┌─────────────────────────────────────────────────────────────┐
│                     前端 (Vue 3 + G6)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  血缘图视图   │  │  字段来源列表 │  │  影响分析页(新增) │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
└─────────┼─────────────────┼────────────────────┼────────────┘
          │                 │                    │
┌─────────┴─────────────────┴────────────────────┴────────────┐
│                    后端 (Spring Boot)                        │
│  ┌──────────────────┐  ┌──────────────────────────────────┐│
│  │ 现有解析/查询API  │  │  影响分析API (新增)               ││
│  │ /api/proc/*      │  │  /api/impact/*                   ││
│  │ /api/lineage/*   │  │                                  ││
│  └────────┬─────────┘  └──────────────┬───────────────────┘│
│           │                           │                     │
│  ┌────────┴─────────┐  ┌──────────────┴───────────────────┐│
│  │ MySQL 查询服务    │  │ Neo4j 查询服务 (新增)             ││
│  │ LineageService   │  │ ImpactAnalysisService             ││
│  └────────┬─────────┘  └──────────────┬───────────────────┘│
│           │                           │                     │
│  ┌────────┴───────────────────────────┴───────────────────┐│
│  │            解析器 (改造)                                ││
│  │  SqlBlockExtractor → LineageExtractor → FieldMapping   ││
│  │           ↓ 解析完成后                                   ││
│  │  MySQL 写入 (现有) + Neo4j 同步 (新增)                  ││
│  │  Neo4jSyncService.syncProc(procName)                   ││
│  └────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
          │                           │
    ┌─────┴─────┐               ┌─────┴─────┐
    │  MySQL    │               │  Neo4j    │
    │  (主存储)  │               │  (图存储)  │
    └───────────┘               └───────────┘
```

### 2.3 数据流

**解析流程**（双写）：

1. 前端调用 `/api/proc/extract`
2. 解析器解析 SQL 块 → 提取表/字段血缘
3. MySQL 事务：删除旧记录 → 插入新记录
4. MySQL 事务成功 → 调用 `Neo4jSyncService.syncProc(procName)`
5. Neo4j 同步：删除该 proc 旧节点/关系 → 写入新节点/关系
6. 同步失败 → 记录到 `neo4j_sync_log` 表，返回解析成功（不影响主流程）

**影响分析流程**：

1. 前端"影响分析"页输入 schema + 表名/字段名 + 方向 + 深度
2. 调用 `/api/impact/analyze`
3. `ImpactAnalysisService` 构造 Cypher 查询
4. Neo4j 执行图遍历，返回路径
5. 转换为 `LineageGraphVO`（复用现有前端 G6 渲染）
6. 前端展示影响图 + 统计信息

### 2.4 关键设计决策

| 决策点 | 方案 | 理由 |
|--------|------|------|
| MySQL 与 Neo4j 事务一致性 | Best Effort：MySQL 成功后异步写 Neo4j，失败不回滚 MySQL | Neo4j 不支持 XA 事务；解析主流程稳定性优先 |
| 失败恢复 | `neo4j_sync_log` 表记录失败 proc，提供重试接口 | 避免一次性全量重同步 |
| 共享表节点 | 同一 `full_name` 的 Table 节点全局唯一，通过 `PARSED_BY` 关系关联多 proc | 支持跨 proc 链路追踪（场景 C） |
| 跨 proc 匹配策略 | 按 `full_name`（schema.table）精确匹配 | 需求 B：精确匹配避免同名异义 |
| proc 重解析同步 | 先按 `proc_name` 删除该 proc 的关系和独占节点，再写新数据 | 避免脏数据残留 |
| Neo4j 连接配置 | `application.yml` 配置，用户名 `neo4j`、密码 `neo4j` | 用户已有实例 |
| 前端展示 | 后端查 Neo4j + 前端 G6 渲染 | 安全（凭据不暴露）+ 复用现有渲染逻辑 |

## 3. Neo4j 图模型

### 3.1 节点定义

**Table 节点**（全局唯一，跨 proc 共享）：

```cypher
(t:Table {
  full_name: "CDM.C_AGT_DEPOSIT_ACCT_INF",   // 唯一标识（schema.table）
  short_name: "C_AGT_DEPOSIT_ACCT_INF",      // 短名（用于显示和匹配）
  schema: "CDM",                              // schema 名
  type: "SOURCE"                              // SOURCE / TEMP / TARGET
})
```

**Field 节点**（全局唯一，归属某 Table）：

```cypher
(f:Field {
  full_name: "CDM.C_AGT_DEPOSIT_ACCT_INF.ACCT_NO",  // 唯一标识（table_full_name.FIELD 大写）
  field: "ACCT_NO",                                  // 字段名（统一大写存储）
  table_full_name: "CDM.C_AGT_DEPOSIT_ACCT_INF"      // 所属表 full_name
})
```

**Proc 节点**：

```cypher
(p:Proc {
  name: "dwa_c_agt_deposit_acct_inf",        // proc_name
  update_target: "CDM.C_AGT_DEPOSIT_ACCT_INF" // 更新对象
})
```

**SqlBlock 节点**：

```cypher
(b:SqlBlock {
  proc_name: "dwa_c_agt_deposit_acct_inf",
  seq: 3,                                    // block_seq
  operation: "INSERT SELECT",
  target_table: "VT_C_CUS_PRI_CUST_LOAN_SUM_06",
  main_table: "F_EV_CRMS_LOAN_BIZ_APPL_INF"
})
```

### 3.2 关系定义

**表级派生**（核心，对应 MySQL `table_lineage` 一行）：

```cypher
(source:Table)-[:DERIVES {
  proc_name: "dwa_c_agt_deposit_acct_inf",
  operation: "INSERT SELECT",
  sql_block_seq: 3,
  main_table: "F_EV_CRMS_LOAN_BIZ_APPL_INF",
  join_conditions: "[{\"clause\":\"a.id = x.a_id\",\"fields\":[\"a.id\",\"x.a_id\"]}]",
  where_conditions: "[{\"clause\":\"x.status = '1'\",\"fields\":[\"x.status\"]}]"
}]->(target:Table)
```

**字段级映射**（对应 MySQL `table_field_mapping` 一行）：

```cypher
(source_field:Field)-[:MAPS_TO {
  proc_name: "dwa_c_agt_deposit_acct_inf",
  sql_block_seq: 3,
  field_order: 5,
  source_expr: "SUM(CASE WHEN A.appl_dt... THEN LR_DISTR.dist_amt_thmonth END)",
  source_alias: "LR_DISTR"
}]->(target_field:Field)
```

**表包含字段**：

```cypher
(t:Table)-[:HAS_FIELD]->(f:Field)
```

**表被 proc 解析**（跨 proc 关联，场景 C 关键）：

```cypher
(t:Table)-[:PARSED_BY {
  proc_name: "dwa_c_agt_deposit_acct_inf",
  operation: "INSERT SELECT"
}]->(p:Proc)
```

**Proc 包含 SqlBlock**：

```cypher
(p:Proc)-[:CONTAINS_BLOCK]->(b:SqlBlock)
```

### 3.3 唯一性约束

```cypher
CREATE CONSTRAINT table_full_name_unique IF NOT EXISTS
FOR (t:Table) REQUIRE t.full_name IS UNIQUE;

CREATE CONSTRAINT field_full_name_unique IF NOT EXISTS
FOR (f:Field) REQUIRE f.full_name IS UNIQUE;

CREATE CONSTRAINT proc_name_unique IF NOT EXISTS
FOR (p:Proc) REQUIRE p.name IS UNIQUE;

CREATE CONSTRAINT sqlblock_unique IF NOT EXISTS
FOR (b:SqlBlock) REQUIRE (b.proc_name, b.seq) IS UNIQUE;
```

### 3.4 大小写处理

| 场景 | 规则 |
|------|------|
| `Table.full_name` | 保留原样（如 `CDM.C_AGT_DEPOSIT_ACCT_INF`），查询时 `toUpper()` 匹配 |
| `Field.full_name` | 统一大写（如 `CDM.C_AGT_DEPOSIT_ACCT_INF.ACCT_NO`） |
| `Field.field` | 统一大写存储，查询时 `toUpper(input)` |
| 跨 proc 匹配 | 按 `Table.full_name` 精确匹配（场景 C 需求 B） |

### 3.5 链路验证示例

假设 SQL 脚本：表 A → 临时表 B → 临时表 C → 表 D

```
FDM.TABLE_A ──DERIVES(seq=1, JOIN:a.id=x.a_id, WHERE:x.status='1')──→ VT_B
FDM.TABLE_X ──DERIVES(seq=1, JOIN:a.id=x.a_id, WHERE:x.status='1')──→ VT_B
VT_B        ──DERIVES(seq=2, WHERE:b.col1>100)──────────────────────→ VT_C
VT_C        ──DERIVES(seq=3, JOIN:c.id=y.c_id)──────────────────────→ CDM.TABLE_D
FDM.TABLE_Y ──DERIVES(seq=3, JOIN:c.id=y.c_id)──────────────────────→ CDM.TABLE_D
```

**表级下游查询**：

```cypher
MATCH path = (src:Table {full_name: 'FDM.TABLE_A'})-[:DERIVES*1..10]->(downstream:Table)
RETURN path
// 结果：FDM.TABLE_A → VT_B → VT_C → CDM.TABLE_D
```

**字段级下游查询**：

```cypher
MATCH path = (src:Field {full_name: 'FDM.TABLE_A.COL1'})
-[:MAPS_TO*1..10]->(downstream:Field)
RETURN path
// 结果：FDM.TABLE_A.COL1 → VT_B.COL1 → VT_C.COL1 → CDM.TABLE_D.COL1
```

**筛选条件查询**：

```cypher
MATCH path = (src:Table {full_name: 'FDM.TABLE_A'})
-[:DERIVES*1..10]->(dst:Table {full_name: 'CDM.TABLE_D'})
UNWIND relationships(path) AS rel
RETURN rel.proc_name AS proc, rel.sql_block_seq AS block,
       rel.join_conditions AS joins, rel.where_conditions AS wheres
```

**路径展开**（逐层）：

```cypher
// 展开 VT_B 的下游 1 层
MATCH (src:Table {full_name: $nodeId})-[:DERIVES]->(downstream:Table)
RETURN src, downstream
```

## 4. API 设计

### 4.1 新增端点

基路径：`/api/impact`

| 方法 | 路径 | 功能 | 请求参数 | 返回类型 |
|------|------|------|---------|---------|
| GET | `/api/impact/schemas` | 查询所有 schema 列表 | 无 | `List<String>` |
| GET | `/api/impact/tables` | 按 schema 查询表列表 | `schema` | `List<TableOption>` |
| GET | `/api/impact/fields` | 按表查询字段列表 | `tableFullName` | `List<String>` |
| POST | `/api/impact/analyze` | 执行影响分析 | `ImpactAnalysisRequest` | `ImpactAnalysisVO` |
| POST | `/api/impact/expand` | 路径展开 | `ExpandRequest` | `LineageGraphVO` |
| GET | `/api/impact/paths` | 查询两点间所有路径 | `source`, `target`, `maxDepth` | `List<List<PathNode>>` |
| POST | `/api/impact/resync` | 手动重同步某 proc | `procName` | `SyncResult` |
| POST | `/api/impact/resync/all` | 全量重同步 | 无 | `SyncResult` |
| GET | `/api/impact/sync-status` | 查询同步状态 | 无 | `List<Neo4jSyncLogVO>` |

### 4.2 请求/响应结构

**ImpactAnalysisRequest**：

```java
public class ImpactAnalysisRequest {
    private String schema;              // FDM/CDM/ODM/PID，可选
    private String tableShortName;      // 表短名，必填
    private String fieldName;           // 字段名，可选（填了则字段级分析）
    private String direction;           // UPSTREAM / DOWNSTREAM / BOTH
    private Integer maxDepth;           // 1/3/5/10/null(全部)，默认 5，上限 20
    private List<String> operations;    // 操作类型筛选，可选
    private Boolean includeConditions;  // 是否返回 JOIN/WHERE 条件，默认 true
    private Boolean includeFieldMapping;// 是否返回字段级映射，默认 false
}
```

**ImpactAnalysisVO**：

```java
public class ImpactAnalysisVO {
    private List<NodeVO> nodes;
    private List<EdgeVO> edges;
    private List<PathVO> paths;          // 完整路径列表
    private ImpactStats stats;           // 影响统计
    private Boolean truncated;           // 是否因深度截断
}

public class NodeVO {
    private String id;                   // full_name 或 field full_name
    private String label;
    private String type;                 // SOURCE / TEMP / TARGET
    private String nodeLevel;            // TABLE / FIELD
    private String tableFullName;        // 字段节点归属的表
    private Integer depth;               // 距起点的深度
}

public class EdgeVO {
    private String source;
    private String target;
    private String operation;
    private Integer sqlBlockSeq;
    private String mainTable;
    private List<ConditionClause> joinConditions;
    private List<ConditionClause> whereConditions;
    private String sourceExpr;           // 字段级边独有
    private String sourceAlias;          // 字段级边独有
}

public class ImpactStats {
    private Integer affectedProcCount;
    private Integer affectedTableCount;
    private Integer affectedFieldCount;
    private Integer maxDepthReached;
    private List<String> affectedProcNames;
}

public class ConditionClause {
    private String clause;
    private List<String> fields;
}
```

**ExpandRequest**：

```java
public class ExpandRequest {
    private String nodeId;
    private String direction;            // UPSTREAM / DOWNSTREAM
    private Integer depth;               // 默认 1
    private List<String> operations;
}
```

### 4.3 Cypher 查询示例

**表级下游**：

```cypher
MATCH path = (src:Table {full_name: $tableFullName})
-[:DERIVES*1..5]->(downstream:Table)
WHERE $operations IS NULL OR ALL(r IN relationships(path)
      WHERE r.operation IN $operations)
WITH path, downstream, length(path) AS depth
RETURN path, depth
ORDER BY depth
```

**字段级下游**：

```cypher
MATCH path = (src:Field {full_name: $fieldFullName})
-[:MAPS_TO*1..5]->(downstream:Field)
WITH path, downstream, length(path) AS depth
RETURN path, depth
ORDER BY depth
```

**两点间路径**：

```cypher
MATCH path = (src:Table {full_name: $source})
-[:DERIVES*1..10]->(dst:Table {full_name: $target})
RETURN path
ORDER BY length(path)
LIMIT 10
```

### 4.4 现有 API 改造

| 现有 API | 改造内容 |
|----------|---------|
| `/api/proc/extract` | 解析完成后调用 `Neo4jSyncService.syncProc(procName)` |
| `/api/proc/extract/batch` | 每个文件解析后各自同步 Neo4j |
| `/api/proc/extract/all` | 全量重解析后全量同步 Neo4j |
| `/api/proc/{procName}` (DELETE) | 删除 MySQL 数据时同步删除 Neo4j 该 proc 的节点和关系 |

### 4.5 错误处理

| 场景 | HTTP 状态 | 响应 |
|------|----------|------|
| Neo4j 连接失败 | 200 | `{code: 500, message: "Neo4j 服务不可用，请稍后重试"}` |
| 表名不存在 | 200 | `{code: 404, message: "表 $tableName 不存在"}` |
| 字段名不存在 | 200 | `{code: 404, message: "字段 $fieldName 不存在"}` |
| maxDepth 超过 20 | 400 | `{code: 400, message: "深度不能超过 20"}` |
| 无影响数据 | 200 | `{code: 200, data: {nodes: [], edges: [], stats: {affectedProcCount:0}}}` |

## 5. 前端设计

### 5.1 页面布局

```
┌──────────────────────────────────────────────────────────────────┐
│  影响分析                                                         │
├──────────────────────────────────────────────────────────────────┤
│  [筛选区]                                                         │
│  Schema: [CDM ▼]  表名: [C_AGT_DEPOSIT_ACCT_INF ▼]               │
│  字段名: [ACCT_NO ▼]  方向: [下游 ▼]  深度: [5 ▼]                 │
│  操作类型: [☑ INSERT_SELECT] [☑ CREATE_TEMP] [☑ MERGE] ...      │
│  [分析] [重置]                                                    │
├──────────────────────────────────────────────────────────────────┤
│  [统计栏]                                                         │
│  影响表数: 12  影响字段数: 45  涉及proc数: 8  最大深度: 4         │
├──────────────────────────────────────────────────────────────────┤
│  [图展示区]                                            [侧边栏]   │
│  ┌──────────────────────────────────┐   ┌──────────────────┐    │
│  │     G6 图（dagre LR 布局）        │   │ 节点详情          │    │
│  │     SOURCE(绿) → TEMP(黄) →      │   │ [展开下游]       │    │
│  │     TARGET(红)                   │   │ [收起下游]       │    │
│  │  ○ 点击节点展开/收起             │   │ [追溯上游]       │    │
│  │  ○ 点击边查看 JOIN/WHERE         │   │ 边详情（JOIN/WHERE）│   │
│  └──────────────────────────────────┘   └──────────────────┘    │
├──────────────────────────────────────────────────────────────────┤
│  [路径列表]（可选展开）                                           │
│  路径1: FDM.TABLE_A → VT_B → VT_C → CDM.TABLE_D (深度 3)         │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 组件结构

```
src/
├── views/
│   └── ImpactAnalysis.vue          # 影响分析页（容器）
├── components/
│   ├── ImpactFilter.vue            # 筛选区
│   ├── ImpactStats.vue             # 统计栏
│   ├── ImpactGraph.vue             # G6 图
│   ├── ImpactNodeDetail.vue        # 节点详情侧边栏
│   ├── ImpactEdgeDetail.vue        # 边详情
│   └── ImpactPathList.vue          # 路径列表
└── api/
    └── impact.js                   # API 封装
```

### 5.3 交互流程

**`analyze` 与 `expand` 职责边界**（重要）：

- `analyze`：返回**完整深度**的所有 nodes + edges + stats（前端一次性拿到全部结果）
- `expand`：仅用于**路径展开交互**的增量加载，返回指定节点的下一层
- **默认渲染策略**：`analyze` 返回完整结果后，前端默认仅渲染第 1 层（起点 + 直接相邻节点），其余层级的节点和边缓存在前端；用户点击"展开下游"时，若该节点下游已在缓存中则直接渲染（不调 `expand`），仅当缓存未命中时才调 `expand`
- **`expand` 的使用场景**：深度较大导致 `analyze` 截断时，用户对截断边界节点点击"展开下游"触发增量加载

**初始查询**：

1. 用户选择 schema → 调用 `/api/impact/tables` 加载表名下拉
2. 用户选择表名 → 调用 `/api/impact/fields` 加载字段名下拉（可选）
3. 用户选择方向/深度/操作类型 → 点击"分析"
4. 调用 `/api/impact/analyze`，返回完整深度的 nodes + edges + stats
5. 前端缓存全部结果，默认仅渲染第 1 层，统计栏展示完整统计
6. G6 渲染第 1 层图，统计栏展示数据

**路径展开**：

1. 默认展示第 1 层（起点 + 直接相邻节点）
2. 点击节点 → 侧边栏显示"展开下游"按钮
3. 点击"展开下游" → 先查前端缓存，缓存命中则直接渲染；未命中则调用 `/api/impact/expand`（`{nodeId, direction, depth:1}`）
4. `expand` 返回新增的 nodes + edges，前端合并到缓存并渲染
5. G6 增量添加节点和边（不重绘已有元素）
6. 点击"收起下游" → 前端本地删除该节点的下游节点和边（缓存保留，便于再次展开）

**边详情查看**：

- 点击 DERIVES 边 → 侧边栏显示 proc_name、sql_block_seq、operation、main_table、JOIN/WHERE 条件列表、[查看字段映射] 按钮
- 点击 MAPS_TO 边 → 显示 sourceExpr + sourceAlias

**视图切换**：

- 表级模式（默认）：节点=Table，边=DERIVES
- 字段级模式（填了 fieldName 后自动切换）：节点=Field，边=MAPS_TO
- 模式切换时清空画布重新渲染

### 5.4 G6 配置

**复用**（与现有 LineageView 一致）：

- G6 v4.8.24
- dagre LR 布局
- 节点颜色：SOURCE(#52c41a 绿) / TEMP(#faad14 黄) / TARGET(#f5222d 红)
- 节点 hover 高亮、边 hover tooltip

**新增**：

- 节点深度标识（右上角徽章）
- 展开/收起状态图标（"+"/"-"）
- 起点节点蓝色边框
- 字段节点圆角矩形（区别于表节点矩形）
- DERIVES 边实线，MAPS_TO 边虚线

### 5.5 错误处理与降级

| 场景 | 处理 |
|------|------|
| Neo4j 服务不可用 | 顶部红色提示 + 重试按钮 |
| 查询超时（>10s） | 提示"查询超时，请缩小深度范围后重试" |
| 节点数 > 100 | 提示"结果节点数过多，建议收起部分临时表或减小深度" |
| 无影响数据 | 空状态"未找到影响链路" |
| G6 渲染失败 | 降级为表格视图 |

### 5.6 菜单集成

现有菜单：血缘图、字段来源列表、SQL 块详情

新增：**影响分析**（独立入口）

## 6. 同步与失败恢复

### 6.1 Neo4jSyncService 核心逻辑

```java
@Service
@Slf4j
public class Neo4jSyncService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult syncProc(String procName) {
        // 1. 记录同步日志（PENDING）
        Long logId = insertSyncLog(procName, "PENDING", null);

        try {
            // 2. 删除该 proc 的旧数据
            deleteOldProcData(procName);

            // 3. 读取 MySQL 数据
            ProcInfo procInfo = procInfoMapper.selectOne(...);
            List<TableLineage> edges = tableLineageMapper.selectList(...);
            List<TableLineageFilter> filters = filterMapper.selectList(...);
            List<TableFieldMapping> mappings = fieldMappingMapper.selectList(...);
            List<SqlBlockInfo> blocks = sqlBlockInfoMapper.selectList(...);

            // 4. 创建约束（幂等）
            createConstraintsIfNotExist();

            // 5. 写入 Proc 节点
            mergeProcNode(procInfo);

            // 6. 写入 Table 节点 + DERIVES 关系（含筛选条件）
            Map<Long, List<TableLineageFilter>> filtersByLineageId = ...;
            for (TableLineage edge : edges) {
                mergeTableNode(...);
                List<TableLineageFilter> edgeFilters = filtersByLineageId.getOrDefault(...);
                createDerivesRelationship(edge, edgeFilters);
                createParsedByRelationship(...);
            }

            // 7. 写入 Field 节点 + HAS_FIELD 关系 + MAPS_TO 关系
            for (TableFieldMapping fm : mappings) {
                mergeFieldNode(...);
                createHasFieldRelationship(fieldTableFullName, fieldFullName);
                createMapsToRelationship(...);
            }

            // 8. 写入 SqlBlock 节点 + CONTAINS_BLOCK 关系
            for (SqlBlockInfo block : blocks) {
                createSqlBlockNode(block);
                createContainsBlockRelationship(...);
            }

            // 9. 更新同步日志（SUCCESS）
            updateSyncLog(logId, "SUCCESS", null);
            return SyncResult.success(procName);

        } catch (Exception e) {
            updateSyncLog(logId, "FAILED", e.getMessage());
            return SyncResult.failure(procName, e.getMessage());
        }
    }

    private void deleteOldProcData(String procName) {
        // 删 SqlBlock 节点
        neo4jClient.query("MATCH (b:SqlBlock {proc_name: $procName}) DETACH DELETE b")...;
        // 删 DERIVES 关系
        neo4jClient.query("MATCH ()-[r:DERIVES {proc_name: $procName}]->() DELETE r")...;
        // 删 MAPS_TO 关系
        neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")...;
        // 删 PARSED_BY 关系
        neo4jClient.query("MATCH ()-[r:PARSED_BY {proc_name: $procName}]->() DELETE r")...;
        // 删 Proc 节点
        neo4jClient.query("MATCH (p:Proc {name: $procName}) DETACH DELETE p")...;
        // 清理孤儿 Table/Field 节点
        neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Field) AND NOT (n)--() DELETE n")...;
    }
}
```

### 6.2 双写集成点

`ProcExtractService` 解析流程改造：

```java
public ExtractResult extract(MultipartFile file) {
    // 1. 解析 SQL
    LineageParseResult parseResult = parseFile(file);

    // 2. MySQL 事务写入（现有逻辑）
    TransactionStatus tx = transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
        deleteOldProcData(procName);
        insertProcInfo(procInfo);
        insertTableLineage(edges);
        insertTableFieldMapping(mappings);
        insertSqlBlockInfo(blocks);
        transactionManager.commit(tx);
    } catch (Exception e) {
        transactionManager.rollback(tx);
        return ExtractResult.failure("MySQL 写入失败: " + e.getMessage());
    }

    // 3. Neo4j 同步（MySQL 成功后，失败不影响主流程）
    try {
        SyncResult syncResult = neo4jSyncService.syncProc(procName);
        if (!syncResult.isSuccess()) {
            log.warn("Neo4j 同步失败 proc={}, 将记录到日志表，可手动重试", procName);
        }
    } catch (Exception e) {
        log.error("Neo4j 同步异常 proc={}", procName, e);
    }

    return ExtractResult.success(procName);
}
```

**关键原则**：

- MySQL 事务成功 → 解析接口返回成功
- Neo4j 同步失败 → 仅记日志，不回滚 MySQL，不改变解析接口返回值
- 用户可通过 `/api/impact/resync` 手动重试失败的同步

### 6.3 失败恢复机制

**自动重试**（定时任务）：

```java
@Scheduled(fixedDelay = 300000) // 每 5 分钟
public void retryFailedSyncs() {
    List<Neo4jSyncLog> failedLogs = syncLogMapper.selectList(
        new LambdaQueryWrapper<Neo4jSyncLog>()
            .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
            .lt(Neo4jSyncLog::getRetryCount, 3));
    for (Neo4jSyncLog log : failedLogs) {
        neo4jSyncService.syncProc(log.getProcName());
    }
}
```

**手动重试**：

- `POST /api/impact/resync?procName=xxx`
- `POST /api/impact/resync/all`

### 6.4 一致性保障

| 风险 | 保障措施 |
|------|---------|
| MySQL 成功 Neo4j 失败 | `neo4j_sync_log` 记录 FAILED，自动重试 + 手动重试 |
| Neo4j 数据脏（残留旧关系） | 同步前先删除该 proc 的所有关系和独占节点 |
| 孤儿共享节点累积 | 每次同步后清理无关系的 Table/Field 节点 |
| 并发同步同一 proc | `@Transactional(REQUIRES_NEW)` + Neo4j 写锁 |
| 全量重解析时大量同步 | `/api/proc/extract/all` 内部串行同步 |

## 7. 测试策略

### 7.1 后端单元测试

| 测试类 | 覆盖范围 | 关键用例 |
|--------|---------|---------|
| `Neo4jSyncServiceTest` | 同步逻辑 | 首次同步、重解析同步、共享表节点、同步失败 |
| `ImpactAnalysisServiceTest` | Cypher 查询 | 表级下游、字段级下游、路径展开、操作类型过滤、两点间路径、深度截断 |
| `Neo4jConfigTest` | 连接配置 | 正常连接、连接失败降级 |
| `ProcExtractServiceTest` | 双写集成 | 解析+同步成功、解析成功+同步失败、解析失败不触发同步 |

### 7.2 后端集成测试

使用 Testcontainers 启动 Neo4j 容器：

```java
@Testcontainers
class ImpactAnalysisIntegrationTest {
    @Container
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.15");

    @Test
    void testTableLevelDownstream() {
        neo4jSyncService.syncProc("dwa_c_agt_deposit_acct_inf");
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setTableFullName("CDM.C_AGT_DEPOSIT_ACCT_INF");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(5);
        ImpactAnalysisVO vo = impactService.analyze(req);
        assertThat(vo.getNodes()).isNotEmpty();
        assertThat(vo.getStats().getAffectedProcCount()).isGreaterThan(0);
    }
}
```

若本地无 Docker，提供降级方案（嵌入式 Neo4j 或 mock Neo4jClient）。

### 7.3 前端手动测试清单（11 项）

1. schema 下拉正确加载 FDM/CDM/ODM/PID
2. 选择 schema 后表名下拉联动
3. 选择表名后字段名下拉联动（可选）
4. 表级下游分析：节点颜色正确（SOURCE 绿/TEMP 黄/TARGET 红）
5. 字段级下游分析：字段节点圆角矩形，MAPS_TO 边虚线
6. 路径展开：点击"展开下游" → 新节点增量添加，不重绘已有节点
7. 路径收起：点击"收起下游" → 下游节点和边移除
8. 边详情：点击 DERIVES 边 → 侧边栏显示 JOIN/WHERE 条件
9. 操作类型筛选：勾选 INSERT_SELECT → 仅显示该操作类型的边
10. Neo4j 不可用：顶部红色提示 + 重试按钮
11. 无影响数据：空状态"未找到影响链路"

## 8. 部署配置

### 8.1 Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>neo4j</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

### 8.2 application.yml

```yaml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: neo4j
    connection:
      max-connection-pool-size: 50
      connection-timeout: 30s
  data:
    neo4j:
      database: neo4j

impact:
  analysis:
    default-depth: 5
    max-depth: 20
    query-timeout: 10s
  sync:
    auto-retry: true
    retry-interval: 300000
    max-retry-count: 3

scheduler:
  neo4j-retry:
    enabled: true
```

### 8.3 配置类

```java
@Configuration
@EnableNeo4jRepositories(basePackages = "com.hb.extractor.repository.neo4j")
public class Neo4jConfig {
    @Value("${spring.neo4j.uri}")
    private String uri;
    @Value("${spring.neo4j.authentication.username}")
    private String username;
    @Value("${spring.neo4j.authentication.password}")
    private String password;

    @Bean
    public Neo4jConnectionDetails neo4jConnectionDetails() {
        return new Neo4jConnectionDetails() {
            @Override public URI getUri() { return URI.create(uri); }
            @Override public AuthDetails getAuthDetails() {
                return new AuthDetails(username, password);
            }
        };
    }
}
```

### 8.4 数据库初始化

**新增 MySQL 表**：

```sql
CREATE TABLE neo4j_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    proc_name VARCHAR(200) NOT NULL,
    sync_status VARCHAR(20) NOT NULL,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (sync_status),
    INDEX idx_proc (proc_name)
) COMMENT='Neo4j 同步日志';
```

**Neo4j 约束初始化**（应用启动时自动执行）：

```java
@Component
public class Neo4jConstraintInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        createConstraint("CREATE CONSTRAINT table_full_name_unique IF NOT EXISTS "
                       + "FOR (t:Table) REQUIRE t.full_name IS UNIQUE");
        createConstraint("CREATE CONSTRAINT field_full_name_unique IF NOT EXISTS "
                       + "FOR (f:Field) REQUIRE f.full_name IS UNIQUE");
        createConstraint("CREATE CONSTRAINT proc_name_unique IF NOT EXISTS "
                       + "FOR (p:Proc) REQUIRE p.name IS UNIQUE");
        createConstraint("CREATE CONSTRAINT sqlblock_unique IF NOT EXISTS "
                       + "FOR (b:SqlBlock) REQUIRE (b.proc_name, b.seq) IS UNIQUE");
    }
}
```

### 8.5 一键启动脚本

`startup.bat` 改造：增加 Neo4j 连接检查，Neo4j 不可用时降级为 MySQL-only 模式并提示用户。

### 8.6 验证命令

```bash
# 后端测试
D:\HB\maven\bin\mvn.cmd test

# 前端构建
cd frontend && npm run build

# 一键启动验证
startup.bat
```

## 9. 已知限制

1. **Neo4j 不可用时降级**：应用仍可启动，影响分析页显示"服务不可用"，其他功能不受影响
2. **字段级链路局限**：仅能追溯 `table_field_mapping` 表中已解析的字段；对于 SELECT 列无别名前缀且无法推断源表的字段（如 `count(*)`），Neo4j 中不生成 MAPS_TO 关系
3. **跨 proc 匹配依赖 full_name**：若不同 proc 对同一表使用了不同的 schema 变量（如 `{FDM}.X` vs `FDM.X`），需解析器统一为完整名才能正确串联

## 10. 实现优先级

1. **P0**：Neo4j 配置 + 约束初始化 + `Neo4jSyncService` + 双写集成
2. **P0**：`/api/impact/analyze` + `/api/impact/expand` + `/api/impact/resync`
3. **P1**：前端影响分析页（筛选区 + 图展示 + 节点详情 + 路径展开）
4. **P1**：`/api/impact/schemas` + `/api/impact/tables` + `/api/impact/fields`
5. **P2**：`/api/impact/paths` + 路径列表展示
6. **P2**：同步状态查询 + 自动重试定时任务
7. **P2**：边详情侧边栏 + 字段映射查看

---

## 11. 实现状态与图模型演进

### 11.1 实现状态

- **状态**：已实现（2026-08 完成）
- **关键演进**：字段级血缘从设计稿的 `MAPS_TO`（Column→Column 直连）重构为 **Transform 中转模型**（Column→Transform→Column），以表达 CASE WHEN、函数运算等复杂转换逻辑
- **新增能力**：报表影响分析（第 13 章）、字段级上下游双向分析、转换节点输入字段溯源

### 11.2 生产图模型（替代 3.1/3.2）

原设计稿的 `Table/Field/DERIVES/MAPS_TO` 已演变为以下生产模型：

**节点**：

| 节点 | 标签 | 关键属性 | 唯一约束 |
|------|------|---------|---------|
| Table | `Table` | `name`(=full_name 全小写), `schema_prefix`, `short_name`, `is_temp`, `layer`(大写), `script_file`(仅目标表), `is_source` | `name` |
| Column | `Column` | `col_key`(=schema.table.field 全小写), `name`(字段名), `table_full_name` | `col_key` |
| Transform | `Transform` | `id`(=md5(procName+│target_col_key)), `expression`, `logic_type`, `proc_name`, `target_col_key`, `script_file`, `sql_hint` | `id` |
| Script | `Script` | `file`, `target_table`, `description` | `file` |
| Proc | `Proc` | `name`, `update_target` | `name` |
| SqlBlock | `SqlBlock` | `proc_name`, `seq`, `operation`, `target_table`, `main_table` | `(proc_name, seq)` |
| Report | `Report` | `file`, `name`, `type`, `description` | `file` |
| Dataset | `Dataset` | `key`(=report_file#dataset_name), `name`, `connection`, `sql` | `key` |

**关系**：

| 关系 | 方向 | 属性 | 用途 |
|------|------|------|------|
| `READS` | Table→Table | `operation, proc_name, main_table, via_temp, join_conditions(JSON), where_conditions(JSON)` | 表级血缘（替代 DERIVES） |
| `BELONGS_TO` | Column→Table | 无 | 字段归属表（DDL 补全阶段为涉及表所有字段建该关系） |
| `INPUT_TO` | Column→Transform | `role(condition/value), source_alias` | 转换输入字段（source_alias 非空时才设置） |
| `OUTPUT_TO` | Transform→Column | 无 | 转换输出字段 |
| `DERIVED_FROM` | Column→Column | `expression, proc_name` | DIRECT 类型合并边（合并 INPUT_TO+OUTPUT_TO，不建 Transform 节点） |
| `PRODUCES` | Script→Table | 无 | 脚本产出表（目标表） |
| `CONSUMES` | Script→Table | 无 | 脚本消费表（依赖表） |
| `PARSED_BY` | Table→Proc | `proc_name, operation` | 表被 proc 解析（跨 proc 关联） |
| `CONTAINS_BLOCK` | Proc→SqlBlock | 无 | proc 包含 SQL 块 |
| `CONTAINS` | Report→Dataset | 无 | 报表包含数据集 |
| `QUERIES` | Dataset→Table | `alias` | 数据集查询表 |
| `REFERENCES` | Dataset→Column | `select_alias, source_expr, cell_refs(JSON)` | 数据集引用字段 |

> 注：设计稿的 `MAPS_TO / DERIVES` 在生产代码中已废弃（仅 `Neo4jSyncServiceImpl.deleteProcData` 拦留删除语句）。表级血缘已迁移至 `READS`，字段级血缘迁移至 `INPUT_TO`+`OUTPUT_TO`+`DERIVED_FROM`，并新增 `Script` 节点 + `PRODUCES`/`CONSUMES` 关系表达脚本产出消费关系。

### 11.3 Transform 节点设计

- **logic_type 分类**：`DIRECT / CASE / SUBTRACTION / ADDITION / MULTIPLICATION / DIVISION / FUNCTION / LITERAL / COALESCE ...`
- **DIRECT 特殊处理**：不创建 Transform 节点，直接建 `DERIVED_FROM` 边（源 Column→目标 Column），减少图复杂度
- **其他 logic_type**：建 Transform 节点，`label` 截断 expression（>30 字符显示省略号），为每个 input 建 `INPUT_TO` 边，为 output 建 `OUTPUT_TO` 边
- **Transform id**：`md5(procName + "|" + target_col_key)` 32 位 hex，前端用正则 `^[a-f0-9]{32}$` 识别
- **字面量场景**：虚拟节点 `__literal__.<transformId>`（无 BELONGS_TO 归属）

### 11.4 sql_hint 生成逻辑（via_temp 链路反查）

对应代码：`Neo4jSyncServiceImpl.generateSqlHint`（行 560-572）+ `findTempUpstreamChain`（行 578-600）

- **目的**：在 Transform 节点上记录该转换所属的 SQL 块编号及中间经过的临时表链路，便于前端展示与溯源
- **生成格式**：`block_seq={n}; via_temp={temp_chain}`
- **via_temp 链路反查**：从目标表出发，逆向遍历 `READS` 关系（过滤 `via_temp` 非空），收集所有中间临时表名，拼接为 `VT_A -> VT_B -> ...` 字符串
- **使用场景**：前端 `ImpactNodeDetail.vue` 在 TRANSFORM 节点详情中展示 sqlHint，帮助用户理解该字段转换经历的临时表路径

### 11.5 虚拟字面量 Column 节点

对应代码：`Neo4jSyncServiceImpl.mergeLiteralColumn`（行 545-554）

- **触发条件**：字段映射的 source_expr 为字面量（如 `'20260101'`、数字常量）且无法解析出源字段
- **col_key 格式**：`__literal__.<transformId>`（前缀 `__literal__.`
- **特殊处理**：
  - 不创建 `BELONGS_TO` 关系（无归属表）
  - `ImpactAnalysisServiceImpl.extractTableFromId` 对 `__literal__` 前缀返回 null（行 595）
  - 前端在字段级图谱中作为虚拟输入节点显示

## 12. 字段级影响分析实际实现

对应代码：`ImpactAnalysisServiceImpl.analyzeFieldLevel`（行 614-765）

### 12.1 查询流程

1. **起点**：Column 节点 `col_key = fieldFullName`（schema.table.field 全小写）
2. **跳数换算**：`cypherMaxHops = depth * 2`（API 字段跳数 → Cypher 关系跳数；一对 INPUT_TO+OUTPUT_TO 算 1 个字段跳）
3. **方向箭头**（`buildFieldArrow`，行 572-577）生成 `[:INPUT_TO|OUTPUT_TO*1..cypherMaxHops]`：
   - DOWNSTREAM: `-[:...]->`
   - UPSTREAM: `<-[:...]-`
   - BOTH: `-[:...]-`（无方向）
   - 注：关系跳数通过**字符串拼接**注入 Cypher（Neo4j 不支持关系跳数参数化）
4. **步骤1 查 Column 节点**（行 636-662）：`depth = (minRelLen + 1) / 2`，nodeLevel=FIELD，layer 从 col_key 解析 schema 大写
5. **步骤2 查 Transform 节点 + 边**（行 665-761）：`OPTIONAL MATCH (in:Column)-[rIn:INPUT_TO]->(n)` + `(n)-[rOut:OUTPUT_TO]->(out:Column)`
6. **节点/边构建**：DIRECT → DERIVED_FROM 边；其他 → Transform 节点 + INPUT_TO/OUTPUT_TO 边
7. **ensureFieldNode 补全**（见 12.3）

### 12.2 role（condition/value）

- 取自 Neo4j `INPUT_TO` 关系 `role` 属性
- 写入侧由 `Neo4jSyncServiceImpl.createInputToRelationship` 设置，值来自 `TableTransformInput.role`（由 `FieldMappingExtractor.classifyLogicType` 推导）
- 当前只覆盖 SELECT 列表达式，role 实际值以 `value` 为主；CASE WHEN 的 condition 分支字段 role=condition
- **ON/WHERE 字段未建 INPUT_TO**（已知限制，见 18.2）

### 12.3 ensureFieldNode 字段节点补全（2026-08-19 修复）

- **问题**：CASE 多分支等辅助输入字段不在从源字段出发的主路径上，前端 `updateVisibleGraph` 按"两端节点可见"过滤边时会丢弃这些字段的 INPUT_TO 边，导致转换节点详情只显示部分输入
- **修复**（[ImpactAnalysisServiceImpl.java#L772](file:///d:/HB/work_zuoye/proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java#L772)）：对每条 INPUT_TO/OUTPUT_TO 引用的 col_key 调用 `ensureFieldNode`，若 nodeIds 中不存在则创建 FIELD 节点（label=field 大写，depth=null）
- **效果**：转换节点详情显示全部输入字段及其来源表

### 12.4 字段级→表级聚合

`buildImpactedByDepthFromFieldNodes`（行 248-330）：字段节点 col_key split 出 `schema.table` → 聚合为 `ImpactedTable`；表级 `impactSource` 通过 INPUT_TO+OUTPUT_TO 配对推导（源表A.field → Transform → 表B.field ⇒ A→B），DERIVED_FROM 边直接推导 A→B。

### 12.5 验证实例

- 下游：`FDM.f_ac_ctds_ldr_acct.acct_no` → 14 节点 17 边（含 CASE Transform，4 个输入字段全部补全）
- 上游：`PID.a_d_dp_deposit_acct_inf.agr_retn_amt` → 13 节点 13 边（CASE Transform 4 输入：acct_no/ldr_frozen_principal/acct_bal，role condition/value 齐全）

## 13. 报表影响分析

### 13.1 数据模型

```
Report -[:CONTAINS]-> Dataset -[:QUERIES]-> Table
                              └-[:REFERENCES]-> Column
```

报表节点不直接连 Table/Column，全部通过 Dataset 中转。

### 13.2 四种查询场景

对应代码：`ReportImpactServiceImpl`

| 场景 | 方法 | 数据源 | 返回 |
|------|------|--------|------|
| 按表 | `findReportsByTable` | Neo4j: `Table<-[:QUERIES]-Dataset<-[:CONTAINS]-Report` | 报表清单（file/name/datasetName/connection） |
| 按字段 | `findReportsByField` | Neo4j: `Column<-[REFERENCES]-Dataset<-[:CONTAINS]-Report` | 报表清单 + select_alias/source_expr/cell_refs |
| 按表头汉字（口径） | `findReportsByHeaderText` | MySQL `report_cell_binding` LIKE | reports + bindings |
| 报表+表头列溯源 | `traceReportLineage` | MySQL 取 source_table+source_field → `ImpactAnalysisRequest(UPSTREAM)` → `ImpactAnalysisService.analyze` | ImpactAnalysisVO（图谱复用 buildWithoutTemp 渲染） |

### 13.3 API 端点（`/api/report-impact`，6 个）

| 方法 | 路径 | 参数 | 返回 |
|------|------|------|------|
| GET | `/fields` | `schema, table` | `List<String>`（表被报表引用的字段） |
| GET | `/by-table` | `schema, table` | `ReportImpactVO` |
| GET | `/by-field` | `schema, table, field` | `ReportImpactVO` |
| GET | `/by-header` | `headerText` | `ReportImpactVO`（口径查询） |
| GET | `/report-headers` | `reportFile` | `List<String>`（报表所有表头汉字） |
| GET | `/trace-lineage` | `reportFile, headerText` | `ImpactAnalysisVO`（上游溯源图谱） |

### 13.4 前端 ReportImpactView

- 4 种查询类型筛选（table/field/caliber/trace）
- 报表解析弹窗（全部重解析/单文件拖拽/批量目录），调用 `/api/report/parse/all`、`/api/report/parse/upload`、`/api/report/parse/batch` 端点
- **溯源模式**：全屏图谱布局，复用 `ImpactFlowGraph`（direction=UPSTREAM），右侧 el-descriptions 显示字段/深度/所属表/来源字段/目标字段/存储过程/来源表达式
- **非溯源模式**：左侧 ReportList 报表清单 + 右侧 FieldMappingTable 字段映射详情

### 13.5 报表影响集成进 analyze（跨链分析）

对应代码：`ImpactAnalysisServiceImpl.analyze`（行 200-234）+ `buildReportImpacts`（行 433-460）

- **触发条件**：`ImpactAnalysisRequest.analyzeReportImpact == true`
- **表级分支**（行 201-213）：调 `buildReportImpacts(tableFullName, impactedByDepth)`
- **字段级分支**（行 222-234）：将字段级 `impactedByDepth` 转换为表级后调 `buildReportImpacts`
- **buildReportImpacts 逻辑**：源表 depth=0 + 受影响表逐个调 `queryReportsForTable`（Neo4j `Table<-[:QUERIES]-Dataset<-[:CONTAINS]-Report`），按报表文件去重
- **统计输出**：`ImpactStats.affectedReportCount`（按报表文件去重计数）
- **前端展示**：
  - `ImpactFilter.vue` 暴露"分析报表影响"开关（行 52-54）
  - `ImpactStats.vue` 显示"影响报表数量"卡片（行 16-18）
  - `ImpactAnalysis.vue` 渲染"报表影响（跨链分析）"表格（行 95-108）

### 13.6 两个 ReportRef 类的差异

- `ImpactAnalysisVO.ReportRef`（ImpactAnalysisVO.java 行 36-46）：仅 `file/name/datasetName/connection`（跨链分析用）
- `ReportImpactVO.ReportRef`（ReportImpactVO.java 行 25-34）：`file/name/datasetName/connection + selectAlias/sourceExpr/cellRefs`（报表影响查询用）
- 两者字段不同，不可互换使用

## 14. 实际 API 端点清单

### 14.1 `/api/impact`（10 个端点）

| # | 方法 | 路径 | 参数 | 返回 |
|---|------|------|------|------|
| 1 | GET | `/schemas` | 无 | `List<String>` |
| 2 | GET | `/tables` | `schema` | `List<TableOption>` |
| 3 | GET | `/fields` | `tableFullName` | `List<String>` |
| 4 | POST | `/analyze` | `ImpactAnalysisRequest` | `ImpactAnalysisVO` |
| 5 | POST | `/expand` | `ExpandRequest` | `ImpactAnalysisVO` |
| 6 | GET | `/paths` | `source, target, maxDepth=10` | `List<PathVO>`（预留，返回空） |
| 7 | POST | `/resync` | `procName` | `SyncResult` |
| 8 | POST | `/resync/all` | 无 | `SyncResult` |
| 9 | POST | `/clear-neo4j` | 无 | `Void`（清空 Neo4j 全部数据） |
| 10 | GET | `/sync-status` | 无 | `List<Neo4jSyncLogVO>`（仅 FAILED） |

### 14.2 `/api/report-impact`（6 个端点）

见 13.3。

## 15. 请求/响应 VO 实际字段

### 15.1 ImpactAnalysisRequest（实际）

| 字段 | 类型 | 默认 | 说明 | 实现状态 |
|------|------|------|------|---------|
| `schema` | String | - | schema 名，可选 | ✓ |
| `tableShortName` | String | - | 表短名，必填 | ✓ |
| `fieldName` | String | - | 填则字段级分析 | ✓ |
| `direction` | String | `DOWNSTREAM` | UPSTREAM/DOWNSTREAM/BOTH | ✓ |
| `maxDepth` | Integer | 5 | 上限 20 | ✓ |
| `operations` | List<String> | - | 操作类型筛选 | ⚠ 未实际使用 |
| `analyzeReportImpact` | Boolean | false | 是否分析报表影响 | ✓（新增） |
| `includeConditions` | Boolean | true | 返回 JOIN/WHERE | ⚠ 未实际使用 |
| `includeFieldMapping` | Boolean | false | 返回字段级映射 | ⚠ 未实际使用 |

### 15.2 ImpactAnalysisVO（实际新增字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| `sourceTable` | String | 源表全名 |
| `sourceLayer` | String | 源表数据层级 |
| `impactedByDepth` | `Map<Integer, List<ImpactedTable>>` | 按深度分组的受影响表 |
| `propagationChains` | `List<String>` | 传播链路描述（如 "A -> B"） |
| `reportImpacts` | `List<ReportImpact>` | 报表影响（勾选 analyzeReportImpact 时） |

**NodeVO 实际字段**：`id, label, type, nodeLevel(TABLE/FIELD/TRANSFORM), layer, tableFullName, depth, isTemp, expression, logicType, sqlHint, procName, scriptFile`

**EdgeVO 实际字段**：`source, target, operation, sqlBlockSeq, mainTable, joinConditions, whereConditions, sourceExpr, sourceAlias, procName, edgeType, role, expression`

**ImpactStats 实际字段**：`affectedProcCount, affectedTableCount, affectedFieldCount, maxDepthReached, affectedProcNames(⚠未填充), affectedReportCount(新增)`

**ImpactedTable 实际字段**：`name, shortName, layer, temp, depth, impactSource, scriptFile, columns, matchedColumns`（注意：设计稿字段名为 `table`，实际为 `name`）

### 15.3 死字段清单（DTO 声明但未实际使用）

| 字段 | 所属类 | 状态 | 说明 |
|------|--------|------|------|
| `includeConditions` | ImpactAnalysisRequest | ⚠ 未读取 | `analyze` 方法完全未引用 |
| `includeFieldMapping` | ImpactAnalysisRequest | ⚠ 未读取 | `analyze` 方法完全未引用 |
| `operations` | ImpactAnalysisRequest | ⚠ 未读取 | 字段级/表级分支均未做操作类型筛选 |
| `truncated` | ImpactAnalysisVO | ⚠ 恒 false | 行 190 硬编码 `vo.setTruncated(false)` |
| `paths` | ImpactAnalysisVO | ⚠ 未填充 | `analyze` 未设置，仅 `getPaths` 方法返回（而该方法为桩） |
| `affectedProcNames` | ImpactStats | ⚠ 未填充 | `buildStats` 未设置该字段 |
| `fieldSources` | ReportImpactVO | ⚠ 未填充 | 四个查询方法均未设置 |

## 16. 前端实际实现

### 16.1 图谱引擎切换

设计稿 AntV G6 → 实际 **VueFlow + dagre**（`ImpactFlowGraph.vue`，rankdir=LR, nodesep=30, ranksep=80）。

### 16.2 图谱筛选工具栏（新增）

- `filterSchemas`（多选 Schema）
- `filterTableKw`（表名关键字，可输入筛选）
- `hideUnmatched`（隐藏未匹配节点）
- `preservedNodeIds`（BFS 从命中节点回溯祖先，保留路径上所有节点）

**筛选规则**（`ImpactAnalysis.vue` 行 200-279）：
- 第一遍匹配非 TRANSFORM 节点（源表始终保留）
- 第二遍 TRANSFORM 节点：其任一 INPUT_TO 源字段或 OUTPUT_TO 目标字段命中则保留
- hideUnmatched 时 BFS 回溯祖先路径，避免命中节点变成孤岛

### 16.3 转换节点详情

`ImpactNodeDetail.vue`：
- TRANSFORM 节点显示：logic_type 徽章（带颜色）、expression、sqlHint、procName、目标字段
- **输入字段列表**：从 edges 过滤 `target===node.id && edgeType==='INPUT_TO'`，映射为 `{colKey, role, alias}`
- 每条输入显示：role 标签（condition=warning 橙 / value=success 绿）+ colKey + alias
- 底部 `展开下游` / `收起下游` 按钮

### 16.4 边详情与样式

`ImpactEdgeDetail.vue` 显示 edgeType/role/operation/sourceExpr/expression/sourceAlias + JOIN/WHERE 条件列表。

边样式（`ImpactFlowGraph.vue` 行 138-143）：
- INPUT_TO: 灰色虚线 `#94a3b8` + strokeDasharray 5,5
- OUTPUT_TO: 蓝色 `#3b82f6`
- DERIVED_FROM: 绿色 `#10b981`
- 其他: 灰色 `#64748b`
- 筛选时命中边高亮蓝色+加粗+animate，未命中变淡 opacity=0.15

### 16.5 临时表穿透

`buildWithoutTemp`（`ImpactAnalysis.vue` 行 361-447）：构建 impactSource→name 邻接表，BFS 跳过临时表（临时表被穿透，不增加层级），重建表级节点/边。表级分析默认展示 depth<=3 节点（`DEFAULT_VISIBLE_DEPTH=3`）。

### 16.6 默认渲染策略

- 字段级：直接用后端 nodes/edges，所有节点默认可见（跳过 buildWithoutTemp）
- 表级：调 buildWithoutTemp 穿透临时表重建
- 节点 >50 时 ElMessage 提示

### 16.7 前端组件清单（components/impact 目录）

| 组件 | 文件 | 行数 | 职责 |
|------|------|------|------|
| `ImpactFlowGraph` | ImpactFlowGraph.vue | 274 | VueFlow 图谱主组件，含 `expandNode` 异步展开方法（通过 defineExpose 暴露，但主视图实际走本地缓存） |
| `ImpactNodeDetail` | ImpactNodeDetail.vue | 123 | 节点详情侧边栏，TRANSFORM/TABLE/FIELD 三分支渲染 |
| `ImpactEdgeDetail` | ImpactEdgeDetail.vue | 76 | 边详情，支持 INPUT_TO/OUTPUT_TO/DERIVED_FROM/READS 标签 |
| `ImpactFilter` | ImpactFilter.vue | 140 | 查询表单，含"分析报表影响"开关（行 52-54） |
| `ImpactStats` | ImpactStats.vue | 41 | 统计卡片，含 affectedReportCount |
| `ImpactPropagationTree` | ImpactPropagationTree.vue | 249 | 传播树视图，含 `rebuildWithoutTemp` 临时表折叠 |
| `ImpactNode` | ImpactNode.vue | - | 节点渲染组件 |
| `ScriptSnippetModal` | ScriptSnippetModal.vue | - | 代码片段弹窗 |

**报表影响组件**（components/report 目录）：`ReportImpactFilter.vue`、`ReportList.vue`、`FieldMappingTable.vue`

**前端 API 层**：
- `frontend/src/api/impact.js`：导出 getSchemas/getTables/getFields/analyze/expand/getPaths/resync/resyncAll/getSyncStatus（**注意：未导出 clearNeo4j 函数，后端 `/clear-neo4j` 端点无前端 UI 入口**）
- `frontend/src/api/report.js`：导出 findReportsByTable/findReportsByField/findByHeaderText/getReportFields/getReportHeaders/traceReportLineage 等

### 16.8 buildWithoutTemp 重复实现（已知技术债）

临时表穿透逻辑在多处重复实现，存在维护风险：

| 位置 | 方法 | 行号 |
|------|------|------|
| `ImpactAnalysis.vue` | `buildWithoutTemp` | 361-447 |
| `ReportImpactView.vue` | `buildWithoutTemp` | 361-436 |
| `ImpactPropagationTree.vue` | `rebuildWithoutTemp` | 103-174 |
| `ImpactAnalysisServiceImpl.java` | `calcNonTempMaxDepth` | 373-427 |

建议后续抽取为公共 composable 或后端统一计算。

### 16.9 expand API 接线状态

- `expand` 函数在 `api/impact.js` 已导出
- `ImpactFlowGraph.vue` 内部 `expandNode` 方法（行 154-205）调用该 API，并通过 `defineExpose`（行 252）暴露
- **但主视图 `ImpactAnalysis.vue` 的 `handleExpand`（行 481-507）使用本地 `allNodesByDepth` 数据过滤，未调用 `graphRef.value.expandNode()`**
- 结论：expand API 与组件方法已接线，但主视图的展开流程走本地缓存；`expandNode` 暴露方法实际上是未使用的预留接口，仅在 `analyze` 截断时才可能被触发

## 17. 与原设计稿差异对照表

| 维度 | 原设计稿（1-10 章） | 实际实现（11-16 章） |
|------|-------------------|--------------------|
| 字段级血缘关系 | `MAPS_TO`（Column→Column 直连） | `INPUT_TO`+`OUTPUT_TO`（Column→Transform→Column） |
| 字段级分析遍历 | 沿 `MAPS_TO` 递归 | 沿 `INPUT_TO│OUTPUT_TO` 递归（跳数×2） |
| 表级血缘关系 | `DERIVES` | `READS`（含 join_conditions/where_conditions 属性） |
| 字段节点标签 | `Field` | `Column`（标识符 col_key） |
| DIRECT 转换 | 未定义 | 不建 Transform，直接 `DERIVED_FROM` 边 |
| 报表影响分析 | 未设计 | 新增 Report/Dataset 节点 + 6 个 API（第 13 章） |
| 前端图谱引擎 | AntV G6 | VueFlow + dagre |
| 图谱筛选工具栏 | 未设计 | filterSchemas + filterTableKw + hideUnmatched + preservedNodeIds |
| 转换节点详情 | 未设计 | ImpactNodeDetail 显示输入字段+role |
| 下拉数据源 | Neo4j 主源 | MySQL `table_ddl_info`/`table_column_meta` 主源 + Neo4j fallback（2026-08-19 修复） |
| 临时表过滤规则 | 未定义 | `VT_.*│TMP_.*│.*_TMP\d*` 前缀过滤 |
| ImpactedTable.name | 设计为 `table` | 实际为 `name`，新增 columns/matchedColumns/scriptFile |
| analyzeReportImpact | 未设计 | 新增请求字段，返回 reportImpacts + affectedReportCount |
| affectedProcNames | 设计填充 | 实际未填充 |
| /paths 路径查询 | 设计实现 | 预留，返回空列表（`getPaths` 行 1029-1033 桩方法） |
| operations 筛选 | 设计实现 | 字段保留但未实际使用 |
| Script 节点 + PRODUCES/CONSUMES | 未设计 | 新增脚本产出消费关系建模 |
| sql_hint 生成 | 未设计 | Transform 节点记录 block_seq + via_temp 链路 |
| 虚拟字面量 Column | 未设计 | `__literal__.<transformId>` 表达式常量输入 |
| /clear-neo4j 端点 | 未设计 | 后端存在但前端无 UI 入口（管理端点） |
| expand API 接线 | 设计为增量加载主路径 | 实际主视图走本地缓存，expand 为预留 |
| 多个 DTO 字段 | 设计填充 | 死字段：includeConditions/includeFieldMapping/truncated/paths/fieldSources（见 15.3） |
| buildWithoutTemp | 未设计 | 前后端 4 处重复实现（见 16.8） |

## 18. 已知限制（更新）

1. **Neo4j 不可用降级**（同原）：应用仍可启动，影响分析页显示"服务不可用"，其他功能不受影响
2. **ON/WHERE 字段不进字段级链路**：字段级影响分析只覆盖 SELECT 列表达式中的字段；ON/WHERE 条件字段被提取到 `table_lineage_filter.fields_involved`，同步时仅作为 `READS` 关系的 `join_conditions`/`where_conditions` 属性存储，**不建** `INPUT_TO` 关系到 Transform。若字段同时出现在 SELECT 表达式则可分析到。增强方案（未实现）：为 ON/WHERE 字段建 INPUT_TO 关系到相关 Transform，role 标记 `join_condition`/`where_condition`
3. **跨 proc 匹配依赖 full_name**（同原）：不同 proc 对同一表使用不同 schema 变量需解析器统一为完整名
4. **gbk 编码脚本**：批量解析时需编码自动检测（`PyProcParser.readContentWithEncodingDetection`，UTF-8 失败回退 GBK，2026-08-19 修复）
5. **fields_involved 列长度**：需 LONGTEXT（已 ALTER，2026-08-19 修复，避免 Data truncation）
6. **批量解析备份文件污染**：`extractAndSaveBatch` 已加 `isBackupOrTestFile` 过滤（排除 `_bak/_bakN/_temp/_tempN/_sjpttest/_test/_old` 结尾文件）
7. **字段级 role 单一**：当前 SELECT 列表达式提取的 role 以 value 为主，CASE WHEN condition 分支为 condition；ON/WHERE 的 condition 角色未建模（见限制 2）
8. **buildWithoutTemp 重复实现**：临时表穿透逻辑在 4 处重复（前端 ImpactAnalysis.vue/ReportImpactView.vue/ImpactPropagationTree.vue + 后端 calcNonTempMaxDepth），存在维护风险（见 16.8）
9. **多个 DTO 死字段**：includeConditions/includeFieldMapping/operations/truncated/paths/affectedProcNames/fieldSources 已声明但未实际使用（见 15.3）
10. **/clear-neo4j 无前端入口**：后端端点存在（ImpactAnalysisController.java 行 66-70），但前端 `api/impact.js` 未导出 clearNeo4j 函数，无 UI 入口，仅作管理端点
11. **expand API 未实际使用**：`ImpactFlowGraph.expandNode` 已暴露但主视图走本地缓存，仅在 analyze 截断时才可能触发（见 16.9）

## 19. 实现状态速查

| 能力 | 状态 | 关键代码 |
|------|------|---------|
| Neo4j 配置 + 约束初始化 | ✅ | `Neo4jConfig`, `Neo4jConstraintInitializer` |
| Neo4jSyncService 双写 | ✅ | `Neo4jSyncServiceImpl.syncProc` |
| Script 节点 + PRODUCES/CONSUMES | ✅ | `Neo4jSyncServiceImpl.mergeScriptNode/mergeProducesRelationship/mergeConsumesRelationship` |
| Transform sql_hint 生成 | ✅ | `Neo4jSyncServiceImpl.generateSqlHint` + `findTempUpstreamChain` |
| 虚拟字面量 Column 节点 | ✅ | `Neo4jSyncServiceImpl.mergeLiteralColumn` |
| 表级影响分析 | ✅ | `ImpactAnalysisServiceImpl.analyzeTableLevel`（READS BFS） |
| 字段级影响分析 | ✅ | `ImpactAnalysisServiceImpl.analyzeFieldLevel`（Transform 模型） |
| 字段级上下游双向 | ✅ | `buildFieldArrow`（UPSTREAM/DOWNSTREAM/BOTH） |
| 转换节点输入字段补全 | ✅ | `ensureFieldNode`（2026-08-19） |
| 报表影响分析（4 场景） | ✅ | `ReportImpactServiceImpl` |
| 报表影响集成进 analyze（跨链） | ✅ | `ImpactAnalysisServiceImpl.buildReportImpacts`（analyzeReportImpact 标志） |
| 报表溯源复用影响分析图谱 | ✅ | `traceReportLineage` → `ImpactAnalysisService.analyze` |
| 下拉 MySQL 主源 + Neo4j fallback | ✅ | `getSchemas/getTables/getFields`（2026-08-19） |
| 前端 VueFlow 图谱 | ✅ | `ImpactFlowGraph.vue` |
| 前端传播树视图 | ✅ | `ImpactPropagationTree.vue` |
| 图谱筛选 + preservedNodeIds | ✅ | `ImpactAnalysis.vue` |
| 转换节点详情 | ✅ | `ImpactNodeDetail.vue` |
| 临时表穿透 | ✅ | `buildWithoutTemp`（⚠ 4 处重复，见 16.8） |
| 同步失败重试（定时） | ✅ | `@Scheduled` retryFailedSyncs |
| 手动重同步 + 清空 | ✅ | `/resync`, `/resync/all`, `/clear-neo4j`（⚠ /clear-neo4j 无前端 UI） |
| 两点间路径查询 | ⏳ 预留 | `/paths` 返回空（`getPaths` 行 1029-1033 桩方法） |
| operations 操作类型筛选 | ⏳ 未实现 | 字段保留未使用 |
| expand API 增量加载 | ⏳ 预留 | 主视图走本地缓存，`expandNode` 已暴露但未调用 |
| DTO 死字段清理 | ⏳ 待清理 | includeConditions/includeFieldMapping/truncated/paths/affectedProcNames/fieldSources（见 15.3） |
| ON/WHERE 字段级链路 | ❌ 未实现 | 见 18.2 增强方案 |
