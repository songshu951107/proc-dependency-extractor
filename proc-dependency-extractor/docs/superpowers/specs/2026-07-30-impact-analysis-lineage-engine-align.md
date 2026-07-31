# 影响分析对标 lineage-engine 改造设计

> **背景：** 当前项目影响分析"效果一般"，根因是 path 查询未实现导致路径信息丢失、深度需内存 BFS 重算。本次改造完全对标 `D:\HB\data_analysis\lineage-engine` 项目的图数据模型、Cypher 查询、数据写入和前端展示，提升影响分析的准确性和可视化效果。

**目标：** 将图模型、影响分析查询、数据写入、前端展示四个部分对标 lineage-engine，保留当前项目的双写、重试、join/where 条件等优势。

**架构：**
- 后端：Spring Boot 3.2.5 + Spring Data Neo4j（Neo4jClient）+ MyBatis-Plus
- 前端：Vue 3 + Element Plus + **Vue Flow + dagre**（替代 AntV G6）
- 数据库：MySQL（主存储，不变）+ Neo4j（图存储，模型重写）

**参考项目：** `D:\HB\data_analysis\lineage-engine\src\main\java\com\bank\lineage`

**约束：**
- MySQL 表结构不变（proc_info / table_lineage / table_field_mapping / sql_block_info 保留）
- 双写机制保留（MySQL + Neo4j 实时双写）
- 失败重试机制保留（neo4j_sync_log + Neo4jSyncRetryScheduler）
- 保留传播树、代码片段弹窗、折叠临时表功能
- 不实现 LLM 总结
- 不实现 Job 节点（无调度数据源）

---

## 一、图数据模型设计

### 1.1 节点类型（4 类，对标 lineage-engine）

完全对标 lineage-engine，删除当前项目的 Proc 和 SqlBlock 节点，用 Script 节点替代 Proc，SqlBlock 不持久化。

| 节点 | 主键 | 属性 | 数据来源 |
|------|------|------|---------|
| **Table** | `name`（小写，schema.table） | `short_name`、`schema_prefix`、`layer`(FDM/ODM/CDM/TDM/PID)、`is_temp`(布尔)、`is_source`(布尔)、`table_comment`、`script_file` | table_lineage + proc_info |
| **Column** | `col_key`（小写，table.column） | `name`、`data_type`、`nullable`、`comment`、`table_full_name` | table_field_mapping + DDL |
| **Script** | `file`（proc_name + ".py"） | `target_table`、`description`、`update_mode`、`author`、`created_date` | proc_info |
| **Job** | `name` | `script_name`、`script_path`、`frequency`、`priority` | 暂不实现（无调度数据源） |

### 1.2 关系类型（6 种，全用 MERGE）

| 关系 | 方向 | 属性 | 说明 |
|------|------|------|------|
| **BELONGS_TO** | Column → Table | (无) | 字段归属表 |
| **READS** | Table → Table | `proc_name`、`operation`、`via_temp`(布尔)、`join_conditions`(JSON)、`where_conditions`(JSON) | 表级血缘（替代 DERIVES，保留条件属性） |
| **MAPS_TO** | Column → Column | `transformation_type`(direct/derived/literal/aggregation)、`script_file`、`proc_name`、`source_expr` | 字段级血缘（新增 transformation_type） |
| **PRODUCES** | Script → Table | (无) | 脚本产出表 |
| **CONSUMES** | Script → Table | (无) | 脚本消费表 |
| **DEPENDS_ON** | Job → Job | (无) | 调度依赖（暂不实现） |

### 1.3 唯一约束

```cypher
CREATE CONSTRAINT table_name_unique  IF NOT EXISTS FOR (t:Table)  REQUIRE t.name IS UNIQUE;
CREATE CONSTRAINT column_key_unique  IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE;
CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE;
```

### 1.4 关键设计决策

1. **Table.name 统一小写**：对标 lineage-engine，避免大小写不一致导致匹配失败
2. **layer 从 schema 推断**：schema=CDM → layer=CDM，schema=FDM → layer=FDM，不改 MySQL 表
3. **READS 关系保留 join/where 条件**：当前项目优势保留，比 lineage-engine 的 READS（仅 via_temp）更丰富
4. **transformation_type 实时分类**：从 source_expr 推断，不改 MySQL 表
5. **删除 Proc/SqlBlock 节点**：完全对标 lineage-engine，Script 节点替代 Proc，SqlBlock 不持久化
6. **READS 关系带 proc_name 作为 MERGE 键**：同一对表可由多个 proc 产生，proc_name 区分避免 MERGE 冲突

---

## 二、影响分析查询设计

### 2.1 核心：用 Cypher 原生 BFS 替换内存 BFS

删除当前项目的 `buildImpactedByDepth`、`buildAdjacency`、`collectNodesAndEdges`、`executeAndCollect`、`getPaths` 方法，完全用 Cypher 原生 path 查询。

### 2.2 查询入口：ImpactAnalysisService.analyze

```java
public ImpactAnalysisVO analyze(ImpactAnalysisRequest request) {
    int depth = clampDepth(request.getMaxDepth());  // 默认5，上限20
    boolean isFieldLevel = isNotEmpty(request.getFieldName());
    String tableName = normalize(request.getSchema() + "." + request.getTableShortName()); // 小写
    String direction = normalizeDirection(request.getDirection());

    if (isFieldLevel) {
        return analyzeFieldLevel(tableName, request.getFieldName(), direction, depth);
    }
    return analyzeTableLevel(tableName, direction, depth);
}
```

### 2.3 表级影响查询（对标 lineage-engine Neo4jRepository:279-290）

**下游 BFS（DOWNSTREAM）**：
```cypher
MATCH path = (start:Table {name: $name})-[:READS*1..$depth]->(downstream:Table)
WHERE start <> downstream
  AND NOT downstream.name STARTS WITH 'vt_'
  AND ALL(n IN nodes(path) WHERE NOT n.name STARTS WITH 'vt_' OR n.is_temp = true)
RETURN downstream.name AS name,
       downstream.short_name AS shortName,
       downstream.layer AS layer,
       downstream.is_temp AS isTemp,
       min(length(path)) AS depth
ORDER BY depth, downstream.name
```

**上游 BFS（UPSTREAM）**：箭头反向 `<-[:READS*1..$depth]-`

**双向（BOTH）**：`-[:READS*1..$depth]-`（无方向）

### 2.4 字段级影响查询（对标 lineage-engine Neo4jRepository:310-334）

```cypher
MATCH path = (src:Column {col_key: $key})-[:MAPS_TO*1..$depth]->(tgt:Column)
WITH path, relationships(path) AS rels, nodes(path) AS cols
UNWIND range(0, size(rels)-1) AS i
WITH cols[i] AS srcCol, rels[i] AS r, cols[i+1] AS tgtCol, i+1 AS depth
RETURN srcCol.col_key AS sourceKey, srcCol.name AS sourceColumn,
       tgtCol.col_key AS targetKey, tgtCol.name AS targetColumn,
       depth,
       r.transformation_type AS transType,
       r.script_file AS scriptFile,
       r.proc_name AS procName,
       r.source_expr AS sourceExpr
ORDER BY depth, targetColumn
```

### 2.5 深度控制与循环检测

| 机制 | 实现 |
|------|------|
| **深度控制** | Cypher `*1..N` 可变长度路径 |
| **最短深度** | `min(length(path))` 取最短路径 |
| **循环检测** | Neo4j 原生 BFS 遍历去重 |
| **临时表过滤** | WHERE 子句过滤（可选，前端折叠开关控制） |

### 2.6 受影响表分组流程（对标 lineage-engine ImpactAnalysisService:40-154）

```java
// Step 1: BFS 获取受影响表（Cypher 返回 depth）
List<ImpactedTable> tables = neo4j.getImpactedTablesBFS(tableName, depth, direction);

// Step 2: 按深度分组
Map<Integer, List<ImpactedTable>> impactedByDepth = tables.stream()
    .collect(Collectors.groupingBy(ImpactedTable::getDepth));

// Step 3: 获取每张表的字段
impactedByDepth.values().stream()
    .flatMap(List::stream)
    .forEach(t -> t.setColumns(neo4j.getTableColumns(t.getName())));

// Step 4: 定位 impactSource（直接前驱，单跳查询）
impactedByDepth.values().stream()
    .flatMap(List::stream)
    .forEach(t -> t.setImpactSource(neo4j.findImpactSource(t.getName(), direction)));

// Step 5: 构建传播链路
List<String> chains = buildPropagationChains(impactedByDepth);
```

### 2.7 impactSource 查询（对标 lineage-engine Neo4jRepository:348-367）

```cypher
// 找直接前驱（单跳）
MATCH (src:Table)-[:READS]->(tgt:Table {name: $name})
RETURN src.name AS source
LIMIT 1
```

### 2.8 expand 渐进式展开（对标 lineage-engine getTableGraph）

```cypher
// 展开 1 跳下游
MATCH (src:Table {name: $name})-[:READS]->(downstream:Table)
RETURN downstream.name AS id, downstream.short_name AS label,
       downstream.layer AS layer, downstream.is_temp AS isTemp
```

### 2.9 ImpactAnalysisVO 简化

```java
public class ImpactAnalysisVO {
    private String sourceTable;
    private List<NodeVO> nodes;
    private List<EdgeVO> edges;
    private Stats stats;
    private Map<Integer, List<ImpactedTable>> impactedByDepth;
    private List<String> propagationChains;

    @Data
    public static class ImpactedTable {
        private String name;           // 小写全名
        private String shortName;
        private String layer;          // FDM/CDM/TDM...
        private boolean isTemp;
        private int depth;
        private String impactSource;
        private String scriptFile;     // 替代 procName
        private List<String> columns;
        private List<String> matchedColumns;  // 字段级分析时填充
    }
}
```

### 2.10 删除的代码

- `buildImpactedByDepth` 方法（内存 BFS）
- `buildAdjacency` 方法
- `collectNodesAndEdges` 备用方案
- `executeAndCollect` 的空 forEach
- `getPaths` 预留方法
- `analyzeTableLevel` / `analyzeFieldLevel` 中的 request 参数（operations 筛选未使用）

---

## 三、数据写入设计

### 3.1 写入流程：实时双写（MySQL + Neo4j），全用 MERGE

保留当前项目的双写架构（MySQL 主、Neo4j 辅），但 Neo4j 写入逻辑完全重写以对标 lineage-engine。

### 3.2 syncProc 流程（Neo4jSyncServiceImpl.syncProc）

```
1. 记录同步日志（复用现有 neo4j_sync_log，PENDING）
2. 重新解析清理（仅清理该 Script 相关数据）
3. 读取 MySQL 数据：proc_info、table_lineage、table_field_mapping
4. 写入 Script 节点（MERGE）
5. 写入 Table 节点（MERGE，含 layer 属性）
6. 写入 Column 节点（MERGE）+ BELONGS_TO 关系（MERGE）
7. 写入 READS 关系（MERGE）+ PRODUCES + CONSUMES 关系
8. 写入 MAPS_TO 关系（MERGE，含 transformation_type）
9. 更新 SOURCE 类型 Table（无入边且非 TEMP）
10. 清理孤儿节点
11. 更新同步日志（SUCCESS）
```

### 3.3 重新解析清理策略（MERGE + 清理）

在 MERGE 写入前先清理该 Script 相关的旧关系，避免脏边残留：

```cypher
// 1. 删除该脚本产出的 READS 关系
MATCH ()-[r:READS {proc_name: $procName}]->() DELETE r;

// 2. 删除该脚本产出的 MAPS_TO 关系
MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r;

// 3. 删除该脚本的 PRODUCES/CONSUMES 关系
MATCH (s:Script {file: $scriptFile})-[r]->() DELETE r;

// 4. 删除 Script 节点（稍后 MERGE 重建）
MATCH (s:Script {file: $scriptFile}) DETACH DELETE s;
```

### 3.4 节点写入 Cypher

**Script 节点**：
```cypher
MERGE (s:Script {file: $file})
SET s.target_table = $targetTable,
    s.description = $description,
    s.update_mode = $updateMode,
    s.author = $author,
    s.created_date = $createdDate
```

**Table 节点**（含 layer 从 schema 推断）：
```cypher
MERGE (t:Table {name: $name})
SET t.short_name = $shortName,
    t.schema_prefix = $schemaPrefix,
    t.layer = $layer,           // 从 schema 推断: CDM/FDM/ODM/TDM/PID
    t.is_temp = $isTemp,        // VT_ 前缀为 true
    t.table_comment = $comment,
    t.script_file = $scriptFile
```

**Column 节点**：
```cypher
MERGE (c:Column {col_key: $colKey})
SET c.name = $name,
    c.data_type = $dataType,
    c.nullable = $nullable,
    c.comment = $comment,
    c.table_full_name = $tableFullName
```

### 3.5 关系写入 Cypher

**BELONGS_TO**（Column → Table，MERGE）：
```cypher
MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName})
MERGE (c)-[:BELONGS_TO]->(t)
```

**READS**（Table → Table，MERGE，对标 lineage-engine 但保留条件属性）：
```cypher
MATCH (src:Table {name: $sourceTable}), (tgt:Table {name: $targetTable})
MERGE (src)-[r:READS {proc_name: $procName}]->(tgt)
SET r.operation = $operation,
    r.via_temp = $viaTemp,
    r.join_conditions = $joinConditions,    // JSON
    r.where_conditions = $whereConditions   // JSON
```

**MAPS_TO**（Column → Column，MERGE，含 transformation_type）：
```cypher
MATCH (src:Column {col_key: $sourceColKey}), (tgt:Column {col_key: $targetColKey})
MERGE (src)-[r:MAPS_TO {proc_name: $procName, source_expr: $sourceExpr}]->(tgt)
SET r.transformation_type = $transType,
    r.script_file = $scriptFile
```

**PRODUCES / CONSUMES**（Script → Table，MERGE）：
```cypher
MATCH (s:Script {file: $scriptFile}), (t:Table {name: $tableName})
MERGE (s)-[:PRODUCES]->(t)   // 或 :CONSUMES
```

### 3.6 transformation_type 分类逻辑

不改 MySQL 表，在 Neo4j 同步时从 `source_expr` 实时推断：

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
```

### 3.7 SOURCE 类型更新

```cypher
// 无 READS 入边且 layer 非空的 Table 标记为 SOURCE
MATCH (t:Table)
WHERE NOT (t)<-[:READS]-() AND t.layer IS NOT NULL
SET t.is_source = true
```

### 3.8 孤儿节点清理

```cypher
// 清理无关系的 Table 和 Column
MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n;
```

### 3.9 失败恢复（保留现有机制）

- `neo4j_sync_log` 表记录同步状态
- `Neo4jSyncRetryScheduler` 每 5 分钟重试 FAILED 记录（retryCount < 3）
- `/api/impact/resync` 手动重试接口
- `MybatisPlusConfig` 的 `@Primary` 事务管理器保留

### 3.10 关键设计决策

1. **全用 MERGE**：节点和关系均 MERGE，幂等写入
2. **重新解析清理**：写入前删除该 Script 相关旧关系，避免脏边
3. **layer 实时推断**：从 schema 字段推断，不改 MySQL 表
4. **transformation_type 实时分类**：从 source_expr 推断，不改 MySQL 表
5. **READS 关系带 proc_name**：同一对表可由多个 proc 产生，proc_name 作为 MERGE 键避免冲突
6. **保留 join/where 条件**：当前项目优势保留，比 lineage-engine 更丰富

---

## 四、前端展示设计

### 4.1 图形库替换：AntV G6 → Vue Flow + dagre

完全对标 lineage-engine，移除 AntV G6，引入 `@vue-flow/core` + `dagre`。

### 4.2 依赖变更（package.json）

```json
{
  "dependencies": {
    "@vue-flow/core": "^1.48.2",
    "dagre": "^0.8.5"
  }
}
// 移除：@antv/g6
```

### 4.3 节点颜色（layer 着色 + TEMP 覆盖）

```javascript
const LAYER_COLORS = {
  FDM: '#ef4444',    // 红 - 原始数据层
  ODM: '#f59e0b',    // 橙 - 操作数据层
  CDM: '#3b82f6',    // 蓝 - 公共数据层
  TDM: '#10b981',    // 绿 - 主题数据层
  PID: '#8b5cf6',    // 紫 - 个人数据层
  UNKNOWN: '#94a3b8' // 灰
}

function getNodeColor(node) {
  if (node.isTemp) return '#faad14'  // TEMP 覆盖为黄色
  return LAYER_COLORS[node.layer] || LAYER_COLORS.UNKNOWN
}
```

### 4.4 ImpactAnalysis.vue 布局（保留三栏结构）

```
┌─────────────────────────────────────────────────────┐
│ ImpactFilter（筛选区，保留）                          │
├─────────────────────────────────────────────────────┤
│ ImpactStats（统计栏，保留）                           │
├──────────────────────────────┬──────────────────────┤
│ Vue Flow 图（替代 G6）         │ 详情侧边栏（保留）     │
│ ┌──────────────────────────┐ │ ┌──────────────────┐ │
│ │  dagre 自动布局           │ │ │ ImpactNodeDetail │ │
│ │  节点：layer 色条 + 表名   │ │ │ 或 ImpactEdgeDetail│
│ │  边：smoothstep + 动画    │ │ │ 或空状态          │ │
│ │  点击节点展开 1 跳         │ │ │                  │ │
│ └──────────────────────────┘ │ └──────────────────┘ │
│                              │                      │
│ 传播树区域（保留）             │                      │
│ ┌──────────────────────────┐ │                      │
│ │ 折叠临时表开关             │ │                      │
│ │ 源表 → Level1 → Level2   │ │                      │
│ │ 卡片：layer色块+表名+代码  │ │                      │
│ └──────────────────────────┘ │                      │
└──────────────────────────────┴──────────────────────┘
│ ScriptSnippetModal（代码片段弹窗，保留）               │
```

### 4.5 Vue Flow 自定义节点组件（对标 lineage-engine ImpactAnalyzer.vue:432-440）

**节点结构（ImpactNode.vue）**：
```vue
<template>
  <div class="impact-node" :style="{ borderLeftColor: color }">
    <div class="layer-badge">{{ layer }}</div>
    <div class="node-label">{{ label }}</div>
    <div v-if="matchedColumns && matchedColumns.length" class="matched-fields">
      <span v-for="col in matchedColumns" :key="col" class="field-tag">
        {{ col }}
      </span>
    </div>
  </div>
</template>
```

**节点样式**：
- 左侧 4px 彩色 border（按 layer）
- 左侧色条显示层级缩写（如 "CDM"）
- 节点主体显示表名 + 受影响字段列表（红色高亮）
- TEMP 节点黄色边框

**边样式**：
- `smoothstep` 类型，`animated: true`
- stroke `#64748b`，strokeWidth 1.5
- 字段级边虚线（`lineDash: [4, 4]`）

### 4.6 Vue Flow 图组件（ImpactFlowGraph.vue，替代 ImpactGraph.vue）

**渐进式展开（对标 lineage-engine expandGraph:184-216）**：

```javascript
const graphStore = reactive({
  nodeMap: new Map(),   // name → node
  edgeMap: new Map()    // "source-target" → edge
})

async function expandNode(nodeName) {
  // 调 /api/impact/expand，1 跳下游
  const resp = await expand({ nodeId: nodeName, direction: 'downstream', depth: 1 })
  const newNodes = resp.data.nodes || []
  const newEdges = resp.data.edges || []

  // 增量合并到 graphStore
  let changed = false
  newNodes.forEach(n => {
    if (!graphStore.nodeMap.has(n.id)) {
      graphStore.nodeMap.set(n.id, n)
      changed = true
    }
  })
  newEdges.forEach(e => {
    const key = `${e.source}-${e.target}`
    if (!graphStore.edgeMap.has(key)) {
      graphStore.edgeMap.set(key, e)
      changed = true
    }
  })

  if (changed) {
    rebuildGraph()  // 重新 dagre 布局
  } else {
    ElMessage.info(`表 "${nodeName}" 没有下游依赖`)
  }
}

function rebuildGraph() {
  // dagre 自动布局
  const g = new dagre.graphlib.Graph()
  g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 60 })
  g.setDefaultEdgeLabel(() => ({}))

  graphStore.nodeMap.forEach(n => {
    g.setNode(n.id, { width: 200, height: 60 })
  })
  graphStore.edgeMap.forEach(e => {
    g.setEdge(e.source, e.target)
  })

  dagre.layout(g)

  // 映射到 Vue Flow 节点/边
  nodes.value = g.nodes().map(id => {
    const node = graphStore.nodeMap.get(id)
    const dagreNode = g.node(id)
    return {
      id,
      position: { x: dagreNode.x, y: dagreNode.y },
      data: { ...node, color: getNodeColor(node) },
      type: 'impactNode'
    }
  })
  edges.value = g.edges().map(e => ({
    id: `${e.v}-${e.w}`,
    source: e.v,
    target: e.w,
    type: 'smoothstep',
    animated: true
  }))
}
```

### 4.7 首次分析渲染策略

```javascript
async function handleAnalyze(request) {
  const resp = await analyze(request)
  const data = resp.data

  // 初始化 graphStore：起点 + 第 1 跳下游
  graphStore.nodeMap.clear()
  graphStore.edgeMap.clear()

  graphStore.nodeMap.set(data.sourceTable, {
    id: data.sourceTable,
    label: data.sourceTable,
    layer: data.sourceLayer,
    isSource: true
  })

  // 第 1 跳节点加入 graphStore
  const firstLevel = data.impactedByDepth[1] || []
  firstLevel.forEach(t => {
    graphStore.nodeMap.set(t.name, t)
    graphStore.edgeMap.set(`${t.impactSource}-${t.name}`, {
      source: t.impactSource,
      target: t.name
    })
  })

  rebuildGraph()

  // 传播树数据（全量）
  propagationData.value = {
    sourceTable: data.sourceTable,
    impactedByDepth: data.impactedByDepth,
    propagationChains: data.propagationChains
  }
}
```

### 4.8 传播树组件（保留，适配新字段）

ImpactPropagationTree.vue 保留，仅适配字段名变化：

```vue
<!-- 卡片显示 -->
<div class="impact-card">
  <div class="type-badge" :style="{ background: getLayerColor(item) }"></div>
  <div class="card-content">
    <span class="table-name">{{ item.shortName || item.name }}</span>
    <span v-if="item.impactSource" class="impact-source">
      ← 来自 {{ item.impactSource }}
    </span>
  </div>
  <button class="code-btn" @click="$emit('viewScript', item)"
          :disabled="!item.scriptFile">
    代码
  </button>
</div>

<script>
function getLayerColor(item) {
  if (item.isTemp) return '#faad14'
  return LAYER_COLORS[item.layer] || '#d9d9d9'
}
</script>
```

折叠临时表逻辑保留（前端 BFS 重算层级），仅字段名适配。

### 4.9 代码片段弹窗（保留，适配 Script.file）

ScriptSnippetModal.vue 保留，仅修改 API 调用：

```javascript
// 旧：getScript(procName)
// 新：getScriptByFile(scriptFile) - 通过 Script.file 查找脚本

async function loadScript() {
  if (!props.scriptFile) {
    error.value = '脚本文件名为空'
    return
  }
  const res = await getScriptByFile(props.scriptFile)
  // 其余逻辑不变
}
```

新增 API：
```javascript
// api/script.js
export function getScriptByFile(scriptFile) {
  return request({
    url: `/proc/${scriptFile}/script`,
    method: 'get'
  })
}
```

### 4.10 删除的组件/文件

- `frontend/src/components/impact/ImpactGraph.vue`（G6 组件，删除）
- `package.json` 中 `@antv/g6` 依赖（删除）

### 4.11 新增的组件/文件

- `frontend/src/components/impact/ImpactFlowGraph.vue`（Vue Flow 图组件，替代 ImpactGraph）
- `frontend/src/components/impact/ImpactNode.vue`（Vue Flow 自定义节点组件）

### 4.12 关键设计决策

1. **Vue Flow + dagre**：对标 lineage-engine，渐进式展开
2. **layer 着色 + TEMP 覆盖**：黄色覆盖任何 layer
3. **渐进式展开**：默认显示起点 + 第 1 跳，点击节点展开 1 跳
4. **传播树保留**：全量数据展示，折叠临时表逻辑保留
5. **代码弹窗保留**：API 从 procName 改为 scriptFile
6. **自定义节点组件**：显示 layer badge + 表名 + 受影响字段

---

## 五、错误处理与测试

### 5.1 错误处理（7 种场景，全部保留）

| 场景 | 处理方式 |
|------|---------|
| **Neo4j 不可用** | analyze 返回空 nodes/edges + `sourceTable`，前端显示"Neo4j 服务不可用"+ 重试按钮 |
| **无影响数据** | analyze 返回 200 + 空 nodes/edges + 空 impactedByDepth，前端显示"无下游影响" |
| **表不存在** | Neo4j 查询返回空，同"无影响数据"处理 |
| **字段级查询无结果** | 返回空 matchedColumns，前端不显示字段标签 |
| **节点超 50 个** | 前端 ElMessage.warning 提示折叠临时表 |
| **展开失败** | ElMessage.error + 节点保持原状 |
| **API 超时** | 前端显示错误 + 重试按钮 |
| **代码片段获取失败** | ScriptSnippetModal 显示 el-alert 错误提示 |

### 5.2 后端测试策略

**ImpactAnalysisServiceTest（重写，对标 lineage-engine）**

删除现有 5 个仅覆盖 fallback 的测试，重写为覆盖核心场景：

```java
@ExtendWith(MockitoExtension.class)
class ImpactAnalysisServiceTest {

    @Mock private Neo4jClient neo4jClient;
    @Mock private Neo4jSyncLogMapper neo4jSyncLogMapper;
    @InjectMocks private ImpactAnalysisServiceImpl impactService;

    // 1. Neo4j 不可用时不崩溃，返回空结果 + sourceTable
    @Test void testAnalyze_Neo4jUnavailable_ReturnsEmptyResult()

    // 2. 下游查询返回多层级数据，impactedByDepth 正确分组
    @Test void testAnalyze_Downstream_PopulatesImpactedByDepth()

    // 3. 上游查询正确设置 sourceTable
    @Test void testAnalyze_Upstream_PopulatesSourceTable()

    // 4. BOTH 方向不崩溃
    @Test void testAnalyze_BothDirection_DoesNotCrash()

    // 5. 字段级查询返回 transformation_type
    @Test void testAnalyze_FieldLevel_ReturnsTransformationType()

    // 6. depth 参数超上限时 clamp 到 maxAllowedDepth
    @Test void testAnalyze_DepthExceedsMax_Clamped()

    // 7. propagationChains 正确生成 "A → B" 格式
    @Test void testAnalyze_PropagationChains_Format()
}
```

**Neo4jSyncServiceTest（扩展）**

保留现有 3 个测试，新增：

```java
// 8. syncProc 正确写入 Script 节点
@Test void testSyncProc_CreatesScriptNode()

// 9. syncProc 正确写入 READS 关系（含 join/where 条件）
@Test void testSyncProc_CreatesReadsRelation()

// 10. syncProc 正确写入 MAPS_TO 关系（含 transformation_type）
@Test void testSyncProc_CreatesMapsToRelation()

// 11. classifyTransformation 分类正确
@Test void testClassifyTransformation_Direct()
@Test void testClassifyTransformation_Derived()
@Test void testClassifyTransformation_Literal()
@Test void testClassifyTransformation_Aggregation()

// 12. 重新解析时清理旧关系
@Test void testSyncProc_Reparse_CleansOldRelations()
```

### 5.3 前端验证清单（14 项）

| 序号 | 验证项 | 预期结果 |
|------|--------|---------|
| 1 | 选择 schema=CDM, 表=C_AGT_DEPOSIT_ACCT_CALCU_BASE, 点击分析 | Vue Flow 图 + 传播树同时显示 |
| 2 | 节点颜色按 layer | FDM红/CDM蓝/TDM绿，VT_ 表黄色 |
| 3 | 节点显示 layer badge + 表名 | 左侧色条显示 "CDM" 等 |
| 4 | 点击节点展开 1 跳下游 | 新节点增量加入，dagre 重新布局 |
| 5 | 无下游时提示 | "表 xxx 没有下游依赖" |
| 6 | 传播树显示分层结构 | 源表 → Level 1 → Level 2 |
| 7 | 传播树卡片显示 layer 色块 + 表名 + impactSource + 代码按钮 | 正确显示 |
| 8 | 点击"折叠临时表"开关 | VT_ 表隐藏，层级重算 |
| 9 | 点击卡片"代码"按钮 | 弹出代码片段 Modal |
| 10 | Modal 高亮 impactSource 引用行 | 红色左边框 + 浅红背景 |
| 11 | Modal 自动滚动到第一个高亮行 | 滚动定位正确 |
| 12 | 关闭 Modal | 正常关闭 |
| 13 | 切换方向 UPSTREAM 重新分析 | 传播树显示上游表 |
| 14 | 字段级分析时节点显示受影响字段 | 红色字段标签 |

### 5.4 新增 API：清空 Neo4j

```java
// ImpactAnalysisController.java
@PostMapping("/clear-neo4j")
public Result<Void> clearNeo4j() {
    neo4jSyncService.clearAllData();
    return Result.success(null);
}
```

```java
// Neo4jSyncServiceImpl.java
public void clearAllData() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();
    // 清空 neo4j_sync_log
    neo4jSyncLogMapper.delete(new LambdaQueryWrapper<>());
}
```

### 5.5 验证命令

```bash
# 后端测试
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=Neo4jSyncServiceTest

# 前端构建
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend; npm run build

# 数据迁移（清空 Neo4j 重新解析）
# 1. 启动后端
# 2. 调用 /api/impact/clear-neo4j 清空 Neo4j
# 3. 调用 /api/proc/extract/all 重新解析所有脚本
# 4. 调用 /api/impact/analyze 验证数据
```

### 5.6 关键设计决策

1. **测试重写**：从仅覆盖 fallback 扩展到覆盖核心场景
2. **transformation_type 单元测试**：4 种分类各一个测试
3. **清空 Neo4j 接口**：数据迁移用，一次性
4. **保留现有错误处理**：7 种场景全部保留
5. **前端 14 项清单**：覆盖 Vue Flow 新特性 + 保留功能

---

## 六、数据迁移策略

### 6.1 清空 Neo4j 重新解析

按用户决策"清空 Neo4j 重新解析"：

1. 启动后端（新代码）
2. 调用 `POST /api/impact/clear-neo4j` 清空 Neo4j 全部数据
3. 调用 `POST /api/proc/extract/all` 重新解析所有 6 个 proc
4. 调用 `POST /api/impact/analyze` 验证数据正确性

### 6.2 迁移验证

```bash
# 验证节点
MATCH (t:Table) RETURN count(t)        # 预期: 26+
MATCH (c:Column) RETURN count(c)       # 预期: 100+
MATCH (s:Script) RETURN count(s)       # 预期: 6

# 验证关系
MATCH ()-[r:READS]->() RETURN count(r) # 预期: 30+
MATCH ()-[r:MAPS_TO]->() RETURN count(r) # 预期: 50+
MATCH ()-[r:BELONGS_TO]->() RETURN count(r) # 预期: 100+

# 验证 layer
MATCH (t:Table) RETURN DISTINCT t.layer, count(t)

# 验证 transformation_type
MATCH ()-[r:MAPS_TO]->() RETURN DISTINCT r.transformation_type, count(r)
```

---

## 七、文件清单

### 7.1 后端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java` | 更新约束定义（Table.name、Column.col_key、Script.file） |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | 重写 syncProc，全用 MERGE，新增 classifyTransformation、clearAllData |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 重写 analyze，用 Cypher 原生 path 查询，删除内存 BFS |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | 更新 ImpactedTable 内嵌类字段（name/layer/isTemp/scriptFile/columns） |
| `src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java` | 新增 /clear-neo4j 接口 |
| `src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java` | 重写测试用例 |
| `src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java` | 新增 transformation_type、重新解析清理测试 |

### 7.2 前端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `frontend/package.json` | 移除 @antv/g6，新增 @vue-flow/core、dagre |
| `frontend/src/views/ImpactAnalysis.vue` | 替换 ImpactGraph 为 ImpactFlowGraph，适配新字段 |
| `frontend/src/components/impact/ImpactGraph.vue` | 删除（被 ImpactFlowGraph 替代） |
| `frontend/src/components/impact/ImpactFlowGraph.vue` | 新增，Vue Flow 图组件 |
| `frontend/src/components/impact/ImpactNode.vue` | 新增，Vue Flow 自定义节点 |
| `frontend/src/components/impact/ImpactPropagationTree.vue` | 适配新字段（name/layer/isTemp/scriptFile） |
| `frontend/src/components/impact/ScriptSnippetModal.vue` | 适配 scriptFile 参数 |
| `frontend/src/api/script.js` | 新增 getScriptByFile |

### 7.3 不变文件

- MySQL 表结构（proc_info / table_lineage / table_field_mapping / sql_block_info）
- `ProcExtractController` 双写集成点
- `Neo4jSyncRetryScheduler` 重试调度
- `MybatisPlusConfig` 事务管理器
- `ImpactFilter.vue`、`ImpactStats.vue`、`ImpactNodeDetail.vue`、`ImpactEdgeDetail.vue`

---

## 八、总结

### 8.1 改造收益

1. **准确性提升**：Cypher 原生 BFS 替换内存 BFS，深度计算准确，路径信息完整
2. **可视化提升**：Vue Flow + dagre 渐进式展开，layer 着色有业务语义
3. **字段级增强**：transformation_type 分类，前端可展示转换类型
4. **图模型精简**：删除 Proc/SqlBlock 节点，4 类节点 + 6 种关系，对标 lineage-engine

### 8.2 保留优势

1. **双写 + 重试**：MySQL 主、Neo4j 辅，失败自动重试
2. **重新解析清理**：MERGE + 清理旧关系，无脏边残留
3. **join/where 条件**：READS 关系属性比 lineage-engine 更丰富
4. **传播树 + 代码片段**：保留前端特色功能

### 8.3 风险

1. **Vue Flow 学习成本**：团队需熟悉 @vue-flow/core API
2. **数据迁移**：需清空 Neo4j 重新解析，短暂影响可用性
3. **transformation_type 分类准确性**：基于 source_expr 正则推断，复杂表达式可能误分类
