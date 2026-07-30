# 影响分析页面改造设计

> **参考项目：** `D:\HB\data_analysis\lineage-engine`（ImpactAnalyzer.vue + ImpactAnalysisService.java）
> **当前项目：** `d:\HB\work_zuoye\proc-dependency-extractor`
> **日期：** 2026-07-30

## 目标

改造现有影响分析页面，新增两个核心能力：
1. **按深度分层的传播树视图**：源表 → Level 1 → Level 2 ... 每层显示表卡片 + impactSource + 类型色块
2. **代码片段查看功能**：点击表名查看对应存储过程脚本，高亮 impactSource 引用行

不做 LLM Summary 生成。

## 范围

### 包含
- 后端：新增传播树数据（impactedByDepth、propagationChains）
- 前端：新增传播树组件、代码片段弹窗组件、ImpactAnalysis.vue 集成
- 前端：修复 G6 容器宽度问题（已修复，待提交）
- 测试：后端单元测试、前端手动验证清单

### 不包含
- LLM 总结生成
- Neo4j 数据模型改动（保留 DERIVES 关系）
- 新增 REST API 接口（复用现有 /api/impact/analyze 和 /api/proc/{procName}/script）
- 参考项目的 VueFlow 引入（继续用 AntV G6）

## 整体架构

### 页面布局

```
┌─────────────────────────────────────────────────────┐
│ ImpactFilter（现有，保留）                            │
│ [Schema] [表名] [字段] [方向] [深度] [分析]           │
├─────────────────────────────────────────────────────┤
│ ImpactStats（现有，保留）                             │
│ 影响表数 X | 影响字段数 X | 涉及 proc 数 X | 最大深度 X│
├─────────────────────────────────────────────────────┤
│ G6 图（现有，保留 + 已修复宽度问题）                   │
│ [dagre LR 布局，三色节点，点击节点/边交互]            │
├─────────────────────────────────────────────────────┤
│ 传播树视图（新增）                                    │
│ 源表: [SOURCE_TABLE]         [折叠临时表] 开关        │
│   ↓ Level 1（2 张表）                                │
│   [TABLE_A] ← 来自 SOURCE  [代码]                    │
│   [TABLE_B] ← 来自 SOURCE  [代码]                    │
│   ↓ Level 2（1 张表）                                │
│   [TABLE_C] ← 来自 TABLE_A [代码]                    │
├─────────────────────────────────────────────────────┤
│ 详情侧边栏（现有，保留）                               │
└─────────────────────────────────────────────────────┘

代码片段 Modal（新增，点击 [代码] 按钮弹出）
┌─────────────────────────────────────────────────────┐
│ dwa_c_agt_deposit_acct_inf.py  行 1-350  [×]        │
│ 受上游表 CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE 影响      │
├─────────────────────────────────────────────────────┤
│ 45  INSERT INTO CDM.C_AGT_DEPOSIT_ACCT_INF           │
│ 46  SELECT ...                                      │
│ 47  FROM CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE TCB  ← 高亮│
│ 48  LEFT JOIN ...                                   │
└─────────────────────────────────────────────────────┘
```

### 数据流

1. 前端调 `POST /api/impact/analyze` → 后端返回 `ImpactAnalysisVO { nodes, edges, impactedByDepth, propagationChains, stats }`
2. G6 图用 `nodes/edges` 渲染（现有逻辑保留）
3. 传播树用 `impactedByDepth` 渲染（新增）
4. 点击传播树表名的 `[代码]` → 前端调 `GET /api/proc/{procName}/script`（现有接口）→ 弹窗显示脚本 + 前端高亮 impactSource 引用行

## 后端设计

### 数据模型扩展

在现有 `ImpactAnalysisVO` 上新增字段：

```java
// ImpactAnalysisVO.java 新增字段
private String sourceTable;
/** 按深度分组：key=depth(1,2,3...), value=该层受影响表列表 */
private Map<Integer, List<ImpactedTable>> impactedByDepth;
/** 传播链路描述：["SOURCE → TABLE_A", "TABLE_A → TABLE_C"] */
private List<String> propagationChains;

// 新增内嵌类型
@Data
public static class ImpactedTable {
    /** 表全名 schema.table */
    private String table;
    /** 影响深度 1,2,3... */
    private int depth;
    /** 直接上游来源表（impactSource） */
    private String impactSource;
    /** 表类型 SOURCE/TEMP/TARGET */
    private String type;
    /** 涉及的存储过程名（用于代码片段查看） */
    private String procName;
    /** 该表总字段数 */
    private int totalColumns;
}
```

### ImpactAnalysisServiceImpl.analyze() 改造

现有 BFS 查询逻辑保留（返回 nodes + edges），新增传播树构建：

```java
public ImpactAnalysisVO analyze(ImpactAnalysisRequest request) {
    // ... 现有 BFS 查询逻辑保留，返回 nodes + edges ...
    
    // 新增：构建传播树数据
    Map<Integer, List<ImpactedTable>> impactedByDepth = buildImpactedByDepth(
        tableFullName, direction, depth, nodes, edges);
    List<String> chains = buildPropagationChains(tableFullName, impactedByDepth);
    
    vo.setSourceTable(tableFullName);
    vo.setImpactedByDepth(impactedByDepth);
    vo.setPropagationChains(chains);
    return vo;
}
```

### buildImpactedByDepth 核心逻辑

用内存 BFS 代替 Neo4j BFS（现有 collectNodesAndEdges 已返回所有 nodes/edges，直接在内存做 BFS）：

```java
private Map<Integer, List<ImpactedTable>> buildImpactedByDepth(
        String sourceTable, String direction, int maxDepth,
        List<NodeVO> nodes, List<EdgeVO> edges) {
    
    // 1. 用 edges 构建邻接表
    //    DOWNSTREAM: source→target
    //    UPSTREAM: target→source
    //    BOTH: 两个方向都加
    Map<String, List<String>> adjacency = buildAdjacency(edges, direction);
    
    // 2. BFS 遍历，记录每个节点的 depth 和 impactSource（直接前驱）
    Map<String, Integer> depthMap = new HashMap<>();
    Map<String, String> impactSourceMap = new HashMap<>();
    Queue<String> queue = new LinkedList<>();
    queue.offer(sourceTable);
    depthMap.put(sourceTable, 0);
    
    while (!queue.isEmpty()) {
        String current = queue.poll();
        int currentDepth = depthMap.get(current);
        if (currentDepth >= maxDepth) continue;
        
        List<String> neighbors = adjacency.getOrDefault(current, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (!depthMap.containsKey(neighbor)) {
                depthMap.put(neighbor, currentDepth + 1);
                impactSourceMap.put(neighbor, current);
                queue.offer(neighbor);
            }
        }
    }
    
    // 3. 按深度分组，构建 ImpactedTable 列表（排除 sourceTable 自身）
    Map<Integer, List<ImpactedTable>> result = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : depthMap.entrySet()) {
        if (entry.getValue() == 0) continue;
        String table = entry.getKey();
        int d = entry.getValue();
        
        ImpactedTable t = new ImpactedTable();
        t.setTable(table);
        t.setDepth(d);
        t.setImpactSource(impactSourceMap.get(table));
        
        // 从 nodes 找类型
        nodes.stream().filter(n -> n.getId().equals(table)).findFirst()
            .ifPresent(n -> t.setType(n.getType()));
        
        // 从 edges 找 procName
        edges.stream().filter(e -> e.getTarget().equals(table) || e.getSource().equals(table))
            .map(EdgeVO::getProcName).filter(Objects::nonNull).findFirst()
            .ifPresent(t::setProcName);
        
        result.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
    }
    return result;
}
```

**impactSource 语义：** BFS 首次访问某节点时的父节点即 impactSource。当一个下游表有多个上游时（菱形依赖），只记录第一个发现的前驱，与参考项目 `findImpactSource` 的 LIMIT 1 行为一致。

### buildPropagationChains 逻辑

```java
private List<String> buildPropagationChains(
        String sourceTable,
        Map<Integer, List<ImpactedTable>> impactedByDepth) {
    List<String> chains = new ArrayList<>();
    for (Map.Entry<Integer, List<ImpactedTable>> entry : impactedByDepth.entrySet()) {
        for (ImpactedTable t : entry.getValue()) {
            if (t.getImpactSource() != null) {
                chains.add(t.getImpactSource() + " → " + t.getTable());
            }
        }
    }
    return chains;
}
```

### buildAdjacency 逻辑

```java
private Map<String, List<String>> buildAdjacency(
        List<EdgeVO> edges, String direction) {
    Map<String, List<String>> adj = new HashMap<>();
    for (EdgeVO e : edges) {
        String src = e.getSource();
        String tgt = e.getTarget();
        if (src.equals(tgt)) continue;
        
        if ("DOWNSTREAM".equals(direction)) {
            adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
        } else if ("UPSTREAM".equals(direction)) {
            adj.computeIfAbsent(tgt, k -> new ArrayList<>()).add(src);
        } else { // BOTH
            adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
            adj.computeIfAbsent(tgt, k -> new ArrayList<>()).add(src);
        }
    }
    return adj;
}
```

## 前端设计

### 新增组件 1：ImpactPropagationTree.vue

**布局：垂直分层，每层一行卡片，层间用箭头连接**

```
源表
┌─────────────────────────────┐
│ [SOURCE] CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE │
└─────────────────────────────┘
        ↓ Level 1（2 张表）
┌─────────────────────────────┐  ┌─────────────────────────────┐
│ [TEMP] VT_01 ← 来自 SOURCE  │  │ [TEMP] VT_02 ← 来自 SOURCE  │
│                          [代码]│  │                          [代码]│
└─────────────────────────────┘  └─────────────────────────────┘
        ↓ Level 2（1 张表）
┌─────────────────────────────┐
│ [TARGET] C_AGT_DEPOSIT_ACCT_INF ← 来自 VT_01  [代码]│
└─────────────────────────────┘
```

**组件 Props：**

```javascript
const props = defineProps({
  sourceTable: String,                          // 源表全名
  impactedByDepth: Object,                      // 按深度分组的表 { 1: [...], 2: [...] }
  collapsedTempTables: Boolean                  // 是否折叠临时表
})
const emit = defineEmits(['viewScript'])        // 点击 [代码] 按钮触发
```

**卡片内容（ImpactedTable）：**
- 左侧色块：节点类型颜色（SOURCE 绿 #52c41a / TEMP 黄 #faad14 / TARGET 红 #f5222d）
- 表名：全名显示，如 `CDM.C_AGT_DEPOSIT_ACCT_INF`
- impactSource：`← 来自 CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE`（灰色小字）
- [代码] 按钮：点击 emit `viewScript({ table, procName, impactSource })`
- 层级标识：`Level 1（2 张表）`

### 折叠临时表逻辑（前端本地计算）

- 点击"折叠临时表"开关 → `collapsedTempTables = true`
- 过滤掉 impactedByDepth 中 type=TEMP 的表
- 重新计算层级：移除临时表后，原本跨临时表的关系（如 SOURCE → VT_01 → TARGET）压缩为 SOURCE → TARGET
- TARGET 的 depth 从 2 变成 1，impactSource 从 VT_01 变成 SOURCE
- 前端本地计算，无需重新请求后端

**折叠后 impactSource 重算逻辑：**

```javascript
function rebuildWithoutTemp(impactedByDepth, sourceTable) {
  // 1. 构建原始邻接表（基于 impactSource）
  const adjacency = {}
  for (const [depth, tables] of Object.entries(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push(t.table)
      }
    }
  }
  
  // 2. 移除 TEMP 节点，对每个非 TEMP 节点找最近的非 TEMP 祖先
  const nonTempTables = new Set([sourceTable])
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.type !== 'TEMP') nonTempTables.add(t.table)
    }
  }
  
  // 3. 重新 BFS 分配 depth 和 impactSource
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
      // 如果 neighbor 是 TEMP，递归找 TEMP 的下游
      if (!nonTempTables.has(neighbor)) {
        const tempNeighbors = adjacency[neighbor] || []
        for (const tn of tempNeighbors) {
          if (nonTempTables.has(tn) && !(tn in depthMap)) {
            depthMap[tn] = currentDepth + 1
            impactSourceMap[tn] = current
            queue.push(tn)
          }
        }
      }
    }
  }
  
  // 4. 构建原始表信息查找表（table -> { type, procName }），用于后续填充
  const tableInfoMap = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.table] = { type: t.type, procName: t.procName }
    }
  }
  
  // 5. 重新构建 impactedByDepth
  const result = {}
  for (const [table, depth] of Object.entries(depthMap)) {
    if (depth === 0) continue
    if (!result[depth]) result[depth] = []
    result[depth].push({
      table,
      depth,
      impactSource: impactSourceMap[table],
      type: tableInfoMap[table]?.type || 'UNKNOWN',
      procName: tableInfoMap[table]?.procName || null
    })
  }
  return result
}
```

### 新增组件 2：ScriptSnippetModal.vue

**触发方式：** 传播树卡片上的 `[代码]` 按钮点击 → `emit('viewScript', { table, procName, impactSource })`

**数据来源：** 复用现有 `GET /api/proc/{procName}/script`，返回结构：

```javascript
{
  procName: "dwa_c_agt_deposit_acct_inf",
  procFileName: "dwa_c_agt_deposit_acct_inf.py",
  scriptContent: "完整脚本字符串...",
  sqlBlocks: [{ seq: 10, startLine: 45, endLine: 80, mainTable: "VT_..." }]
}
```

**组件 Props：**

```javascript
const props = defineProps({
  visible: Boolean,
  procName: String,          // 存储过程名
  impactSource: String,      // 上游来源表（用于高亮）
  targetTable: String        // 目标表（显示用）
})
const emit = defineEmits(['close'])
```

**高亮逻辑（前端本地计算）：**

```javascript
function computeHighlightLines(scriptContent, impactSource) {
  const lines = scriptContent.split('\n')
  const highlightSet = new Set()
  
  // 提取 impactSource 的表名部分（去掉 schema 前缀）
  const tableName = impactSource.split('.').pop()
  const shortName = tableName.replace(/^VT_/, '')
  
  lines.forEach((line, idx) => {
    const lineNum = idx + 1
    const upperLine = line.toUpperCase()
    if (upperLine.includes(tableName) || upperLine.includes(shortName)) {
      if (!line.trim().startsWith('--') && !line.trim().startsWith('#')) {
        highlightSet.add(lineNum)
      }
    }
  })
  
  return highlightSet
}
```

**弹窗布局：**

```
┌─────────────────────────────────────────────────────────────┐
│ dwa_c_agt_deposit_acct_inf.py         行 1-350  [×]        │
├─────────────────────────────────────────────────────────────┤
│ 受上游表 CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE 影响              │
├─────────────────────────────────────────────────────────────┤
│  44  ...                                                    │
│  45  INSERT INTO CDM.C_AGT_DEPOSIT_ACCT_INF                 │
│  46  SELECT ...                                             │
│  47  FROM CDM.C_AGT_DEPOSIT_ACCT_CALCU_BASE TCB    ← 高亮   │
│  48  LEFT JOIN FDM.F_AC_CTDS_LDR_ACCT TC                    │
│  49    ON T01.acct_no = TCB.acct_no               ← 高亮    │
│  50  ...                                                    │
└─────────────────────────────────────────────────────────────┘
```

**组件特性：**
- 等宽字体：`font-family: 'Consolas', 'Monaco', monospace`
- 行号显示：左侧固定宽度行号列
- 高亮行样式：左侧红色边框 + 浅红背景 `background: #3b1f1f; border-left: 3px solid #ef4444`
- 滚动定位：弹窗打开后自动滚动到第一个高亮行
- 加载状态：`v-loading` 显示"加载脚本中..."
- 最大高度：80vh，内部滚动

**边界情况：**
1. impactSource 在脚本中无匹配：显示提示"脚本中未直接引用依赖表 {impactSource}，影响可能通过中间表传递"
2. procName 为空：不弹窗，显示 warning 提示
3. 脚本获取失败：弹窗内显示错误信息

### ImpactAnalysis.vue 集成

**模板结构：**

```vue
<template>
  <div class="impact-analysis">
    <ImpactFilter :loading="loading" @analyze="handleAnalyze" />
    <el-alert v-if="neo4jError" ...>...</el-alert>
    <ImpactStats :stats="result?.stats" />
    
    <el-row :gutter="10">
      <el-col :span="18">
        <ImpactGraph ref="graphRef" :nodes="renderNodes" :edges="renderEdges" 
                     :isFieldLevel="isFieldLevel" @nodeClick="handleNodeClick" 
                     @edgeClick="handleEdgeClick" />
        
        <div class="propagation-section" v-if="result?.impactedByDepth">
          <div class="section-header">
            <span class="section-title">影响传播树</span>
            <el-switch v-model="collapseTempTables" active-text="折叠临时表" size="small" />
          </div>
          <ImpactPropagationTree
            :sourceTable="result.sourceTable"
            :impactedByDepth="result.impactedByDepth"
            :collapsedTempTables="collapseTempTables"
            @viewScript="handleViewScript"
          />
        </div>
      </el-col>
      
      <el-col :span="6">
        <el-card shadow="never">
          <template #header>详情</template>
          <ImpactNodeDetail v-if="selectedNode" ... />
          <ImpactEdgeDetail v-else-if="selectedEdge" ... />
          <el-empty v-else description="点击节点或边查看详情" />
        </el-card>
      </el-col>
    </el-row>
    
    <ScriptSnippetModal
      v-if="scriptModalVisible"
      :visible="scriptModalVisible"
      :procName="scriptModalData.procName"
      :impactSource="scriptModalData.impactSource"
      :targetTable="scriptModalData.table"
      @close="scriptModalVisible = false"
    />
  </div>
</template>
```

**新增状态：**

```javascript
const collapseTempTables = ref(false)
const scriptModalVisible = ref(false)
const scriptModalData = ref({ table: '', procName: '', impactSource: '' })

const handleViewScript = ({ table, procName, impactSource }) => {
  if (!procName) {
    ElMessage.warning('该表无关联存储过程')
    return
  }
  scriptModalData.value = { table, procName, impactSource }
  scriptModalVisible.value = true
}
```

**handleAnalyze 改造：** 现有逻辑保留，新增 `collapseTempTables.value = false` 重置折叠状态，传播树数据自动通过 `result.impactedByDepth` 传递。

### 错误处理与边界情况

| 场景 | 处理方式 |
|------|---------|
| Neo4j 不可用 | 现有 neo4jError + 重试按钮（保留） |
| analyze 返回空 nodes | G6 显示"未找到影响链路"（保留），传播树不渲染（v-if） |
| impactedByDepth 为空 | 传播树区域不渲染 |
| procName 为空 | 点击[代码]按钮提示 warning |
| 脚本获取失败 | 弹窗内显示错误信息，不崩溃 |
| G6 渲染失败 | 降级表格视图（保留现有），传播树不受影响 |
| impactSource 在脚本中无匹配 | 显示提示，列出可能的中间表 |

## 文件改动清单

### 后端

| 文件 | 操作 | 说明 |
|------|------|------|
| `dto/ImpactAnalysisVO.java` | 修改 | 添加 sourceTable、impactedByDepth、propagationChains 字段 + ImpactedTable 内嵌类 |
| `service/impl/ImpactAnalysisServiceImpl.java` | 修改 | 新增 buildImpactedByDepth、buildPropagationChains、buildAdjacency 方法；analyze 末尾调用 |
| `test/java/.../ImpactAnalysisServiceTest.java` | 修改 | 新增 testAnalyzeReturnsImpactedByDepth、testBuildPropagationChains、testBOTHDirectionMergesUpAndDown |

### 前端

| 文件 | 操作 | 说明 |
|------|------|------|
| `components/impact/ImpactPropagationTree.vue` | 新增 | 传播树组件 |
| `components/impact/ScriptSnippetModal.vue` | 新增 | 代码片段弹窗 |
| `views/ImpactAnalysis.vue` | 修改 | 集成传播树 + 代码弹窗 |
| `components/impact/ImpactGraph.vue` | 修改 | 已修复宽度问题，待提交 |

### 不变文件

| 文件 | 说明 |
|------|------|
| `controller/ImpactAnalysisController.java` | 接口不变 |
| `api/impact.js` | 前端 API 不变，复用现有 analyze |
| `components/impact/ImpactFilter.vue` | 不变 |
| `components/impact/ImpactStats.vue` | 不变 |
| `components/impact/ImpactNodeDetail.vue` | 不变 |
| `components/impact/ImpactEdgeDetail.vue` | 不变 |

## 测试与验证

### 后端单元测试

```java
@Test
void testAnalyzeReturnsImpactedByDepth() {
    // mock Neo4jClient 返回下游表 A(depth=1), B(depth=1), C(depth=2)
    ImpactAnalysisRequest req = new ImpactAnalysisRequest();
    req.setSchema("CDM");
    req.setTableShortName("C_AGT_DEPOSIT_ACCT_CALCU_BASE");
    req.setDirection("DOWNSTREAM");
    req.setMaxDepth(5);
    
    ImpactAnalysisVO vo = service.analyze(req);
    
    assertNotNull(vo.getImpactedByDepth());
    assertTrue(vo.getImpactedByDepth().containsKey(1));
    assertTrue(vo.getImpactedByDepth().containsKey(2));
    
    ImpactedTable tableC = vo.getImpactedByDepth().get(2).get(0);
    assertNotNull(tableC.getImpactSource());
    // impactSource 是 BFS 直接前驱，应为 depth=1 的某个表
    assertTrue(vo.getImpactedByDepth().get(1).stream()
        .anyMatch(t -> t.getTable().equals(tableC.getImpactSource())));
}

@Test
void testBuildPropagationChains() {
    ImpactAnalysisVO vo = service.analyze(req);
    List<String> chains = vo.getPropagationChains();
    assertNotNull(chains);
    assertTrue(chains.stream().anyMatch(c -> c.contains("→")));
}

@Test
void testBOTHDirectionMergesUpAndDown() {
    req.setDirection("BOTH");
    ImpactAnalysisVO vo = service.analyze(req);
    assertTrue(vo.getImpactedByDepth().values().stream()
        .flatMap(List::stream)
        .anyMatch(t -> t.getImpactSource() != null));
}
```

### 前端手动验证清单

| 序号 | 验证项 | 预期结果 |
|------|--------|---------|
| 1 | 选择 schema=CDM, 表=C_AGT_DEPOSIT_ACCT_CALCU_BASE, 点击分析 | G6 图 + 传播树同时显示 |
| 2 | 传播树显示分层结构 | 源表 → Level 1 → Level 2 → ... |
| 3 | 每层卡片显示表名、impactSource、类型色块 | 正确显示 |
| 4 | 点击"折叠临时表"开关 | VT_ 表被隐藏，层级重新计算 |
| 5 | 再次点击"折叠临时表" | 临时表恢复显示 |
| 6 | 点击卡片上的[代码]按钮 | 弹出代码片段 Modal |
| 7 | Modal 显示完整脚本 + 行号 | 正确显示 |
| 8 | impactSource 引用行被高亮 | 红色左边框 + 浅红背景 |
| 9 | Modal 自动滚动到第一个高亮行 | 滚动定位正确 |
| 10 | 关闭 Modal（点击×或遮罩） | 正常关闭 |
| 11 | 切换方向为 UPSTREAM 重新分析 | 传播树显示上游表 |
| 12 | 切换方向为 BOTH 重新分析 | 传播树包含上下游 |
| 13 | G6 图渲染正常（无宽度问题） | 节点边显示完整 |
| 14 | 节点超过 50 提示折叠 | 提示出现 |

### 验证命令

```bash
# 后端测试
& 'D:\HB\maven\bin\mvn.cmd' test -Dtest=ImpactAnalysisServiceTest

# 前端构建
cd frontend; npm run build

# 端到端验证（手动）
# 1. 启动后端
# 2. 浏览器打开 http://localhost:8080/#/impact
# 3. 按上方清单逐项验证
```

### 回归测试

确保现有功能不受影响：
- `/api/impact/analyze` 返回的 `nodes/edges` 结构不变（G6 图不受影响）
- `/api/impact/expand` 路径展开不受影响
- `/api/impact/schemas`、`/api/impact/tables` 不受影响
- `/api/proc/{procName}/script` 接口不变（代码片段复用）

## 约束遵循

- G6 v4.8.24（禁 v5 / Bloom / neovis）
- dagre 布局 rankdir=LR
- 单层 canvas
- 节点颜色 SOURCE=#52c41a / TEMP=#faad14 / TARGET=#f5222d
- 节点 > 50 提示折叠临时表
- API 超时显示错误+重试按钮
- G6 渲染失败降级表格
- Neo4j 连接失败不影响 MySQL 主流程
