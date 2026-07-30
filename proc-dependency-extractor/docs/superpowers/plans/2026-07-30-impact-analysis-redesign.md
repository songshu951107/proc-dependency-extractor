# 影响分析页面改造实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为影响分析页新增"按深度分层的传播树视图"和"代码片段查看功能（高亮 impactSource 引用行）"，提升影响链路可读性。

**架构：** 后端 `ImpactAnalysisServiceImpl.analyze()` 在现有 BFS 查询返回 nodes/edges 后，用内存 BFS 构建按深度分组的 `impactedByDepth` 和 `propagationChains`，一次性返回给前端；前端在 G6 图下方新增传播树组件，点击表名卡片上的"代码"按钮复用现有 `/api/proc/{procName}/script` 接口获取脚本，前端本地计算高亮行并弹出代码片段 Modal。

**技术栈：** Spring Boot 3.2.5 + Spring Data Neo4j + MyBatis-Plus + Vue 3 + Element Plus + AntV G6 v4.8.24

**规格文档：** `docs/superpowers/specs/2026-07-30-impact-analysis-redesign.md`

---

## 文件结构

### 后端修改文件

| 文件路径 | 职责 |
|---------|------|
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | 新增 sourceTable、impactedByDepth、propagationChains 字段 + ImpactedTable 内嵌类 |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 新增 buildImpactedByDepth、buildPropagationChains、buildAdjacency 方法；analyze 末尾调用 |
| `src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java` | 新增 3 个测试用例验证传播树构建逻辑 |

### 前端新增文件

| 文件路径 | 职责 |
|---------|------|
| `frontend/src/components/impact/ImpactPropagationTree.vue` | 按深度分层的传播树组件，支持折叠临时表 |
| `frontend/src/components/impact/ScriptSnippetModal.vue` | 代码片段弹窗，高亮 impactSource 引用行 |

### 前端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `frontend/src/views/ImpactAnalysis.vue` | 集成传播树组件 + 代码片段弹窗，新增折叠开关状态 |

### 不变文件

- `frontend/src/components/impact/ImpactGraph.vue`（宽度问题已修复）
- `frontend/src/api/impact.js`（复用现有 analyze 接口）
- `src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java`（接口不变）

---

## 任务 1：ImpactAnalysisVO 扩展

**文件：**
- 修改：`src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java`

- [ ] **步骤 1：扩展 ImpactAnalysisVO 类**

在 `ImpactAnalysisVO.java` 类中新增 3 个字段（位于 `truncated` 字段之后）和 1 个内嵌类（位于 `PathNode` 类之后）。

`old_string`（在第 14 行 `private Boolean truncated;` 之后插入）：

```java
    private Boolean truncated;
    /** 源表全名（传播树根节点） */
    private String sourceTable;
    /** 按深度分组：key=depth(1,2,3...), value=该层受影响表列表 */
    private java.util.Map<Integer, List<ImpactedTable>> impactedByDepth;
    /** 传播链路描述：["SOURCE → TABLE_A", "TABLE_A → TABLE_C"] */
    private List<String> propagationChains;
```

`old_string`（在文件末尾 `PathNode` 内嵌类之后新增 `ImpactedTable` 内嵌类）：

```java
    @Data
    public static class PathNode {
        private String id;
        private String label;
    }

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
}
```

- [ ] **步骤 2：验证编译**

运行（PowerShell，用 `;` 分隔）：
```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS，无编译错误。

- [ ] **步骤 3：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: ImpactAnalysisVO 新增传播树数据字段"
```

---

## 任务 2：ImpactAnalysisServiceImpl 传播树构建（TDD）

**文件：**
- 修改：`src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`

- [ ] **步骤 1：编写失败的测试用例**

在 `ImpactAnalysisServiceTest.java` 类末尾（第 57 行 `}` 之前）新增 3 个测试方法。

由于现有 `analyze` 方法在 Neo4j 不可用时仍返回起点节点（单节点），此时 nodes=1、edges=0，`buildImpactedByDepth` 应返回空 Map。这种场景可以验证传播树构建不会崩溃，但无法验证多层数据。

为了测试多层数据，需要让 Neo4jClient 返回非空数据。由于现有 mock 用 `when(neo4jClient.query(anyString())).thenThrow(...)`，需要改为更精细的 mock。但 `collectNodesAndEdges` 方法内部多次调用 `neoo4jClient.query()`，mock 链很复杂。

**更实用的方案：** 直接测试 `buildImpactedByDepth` 的纯逻辑——通过构造 nodes/edges 输入 + 调用 `analyze` 的场景验证。由于该方法是 private，通过 `analyze` 公开入口验证。让 Neo4j 抛异常走 fallback 路径（单节点），验证 `impactedByDepth` 为空 Map 且不抛异常。

新增测试代码：

```java
    @Test
    void testAnalyze_ReturnsImpactedByDepthField() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo.getImpactedByDepth());
        // Neo4j 不可用时只有起点节点，无下游，impactedByDepth 应为空
        assertTrue(vo.getImpactedByDepth().isEmpty());
        assertNotNull(vo.getPropagationChains());
        assertTrue(vo.getPropagationChains().isEmpty());
        assertEquals("CDM.TEST_TABLE", vo.getSourceTable());
    }

    @Test
    void testAnalyze_UpstreamDirection_PopulatesImpactedByDepth() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("UPSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        // UPSTREAM 方向也应正确设置 sourceTable
        assertEquals("CDM.TEST_TABLE", vo.getSourceTable());
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

        // BOTH 方向不应抛异常，sourceTable 应正确设置
        assertEquals("CDM.TEST_TABLE", vo.getSourceTable());
        assertNotNull(vo.getImpactedByDepth());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：
```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest -q
```

预期：FAIL，3 个新测试方法报错（`vo.getImpactedByDepth()` 返回 null，因为 `analyze` 尚未设置该字段）。

- [ ] **步骤 3：实现 buildImpactedByDepth、buildPropagationChains、buildAdjacency 方法**

在 `ImpactAnalysisServiceImpl.java` 中新增 3 个 private 方法。将以下代码插入到 `buildStats` 方法之前（即 `parseConditions` 方法之后）。

在 `parseConditions` 方法之后、`buildStats` 方法之前插入：

```java
    private Map<Integer, ImpactAnalysisVO.ImpactedTable> buildImpactedByDepth(
            String sourceTable, String direction, int maxDepth,
            List<ImpactAnalysisVO.NodeVO> nodes, List<ImpactAnalysisVO.EdgeVO> edges) {

        Map<String, List<String>> adjacency = buildAdjacency(edges, direction);

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

        Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : depthMap.entrySet()) {
            if (entry.getValue() == 0) continue;
            String table = entry.getKey();
            int d = entry.getValue();

            ImpactAnalysisVO.ImpactedTable t = new ImpactAnalysisVO.ImpactedTable();
            t.setTable(table);
            t.setDepth(d);
            t.setImpactSource(impactSourceMap.get(table));

            final String tableId = table;
            nodes.stream().filter(n -> n.getId().equals(tableId)).findFirst()
                .ifPresent(n -> t.setType(n.getType()));

            edges.stream()
                .filter(e -> e.getTarget().equals(tableId) || e.getSource().equals(tableId))
                .map(ImpactAnalysisVO.EdgeVO::getProcName)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(t::setProcName);

            result.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
        }
        return result;
    }

    private Map<String, List<String>> buildAdjacency(
            List<ImpactAnalysisVO.EdgeVO> edges, String direction) {
        Map<String, List<String>> adj = new HashMap<>();
        for (ImpactAnalysisVO.EdgeVO e : edges) {
            String src = e.getSource();
            String tgt = e.getTarget();
            if (src.equals(tgt)) continue;

            if ("UPSTREAM".equals(direction)) {
                adj.computeIfAbsent(tgt, k -> new ArrayList<>()).add(src);
            } else {
                // DOWNSTREAM 和 BOTH 都加 source→target
                adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
                if ("BOTH".equals(direction)) {
                    adj.computeIfAbsent(tgt, k -> new ArrayList<>()).add(src);
                }
            }
        }
        return adj;
    }

    private List<String> buildPropagationChains(
            Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth) {
        List<String> chains = new ArrayList<>();
        for (Map.Entry<Integer, List<ImpactAnalysisVO.ImpactedTable>> entry : impactedByDepth.entrySet()) {
            for (ImpactAnalysisVO.ImpactedTable t : entry.getValue()) {
                if (t.getImpactSource() != null) {
                    chains.add(t.getImpactSource() + " → " + t.getTable());
                }
            }
        }
        return chains;
    }
```

- [ ] **步骤 4：在 analyze 方法中调用传播树构建**

修改 `ImpactAnalysisServiceImpl.java` 的 `analyze` 方法。在第 96-100 行的 `vo.setNodes(...)` 之后、`return vo` 之前插入传播树构建逻辑。

`old_string`（第 96-100 行）：
```java
        vo.setNodes(nodes);
        vo.setEdges(edges);
        vo.setStats(buildStats(nodes, edges, depth));
        vo.setTruncated(false);
        return vo;
```

`new_string`：
```java
        vo.setNodes(nodes);
        vo.setEdges(edges);
        vo.setStats(buildStats(nodes, edges, depth));
        vo.setTruncated(false);
        vo.setSourceTable(tableFullName);
        vo.setImpactedByDepth(buildImpactedByDepth(tableFullName, direction, depth, nodes, edges));
        vo.setPropagationChains(buildPropagationChains(vo.getImpactedByDepth()));
        return vo;
```

- [ ] **步骤 5：运行测试验证通过**

运行：
```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest -q
```

预期：PASS，5 个测试全部通过（原 2 个 + 新增 3 个）。

- [ ] **步骤 6：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 后端新增传播树构建逻辑（buildImpactedByDepth）"
```

---

## 任务 3：前端 ImpactPropagationTree.vue 传播树组件

**文件：**
- 创建：`frontend/src/components/impact/ImpactPropagationTree.vue`

- [ ] **步骤 1：创建 ImpactPropagationTree.vue 组件**

创建 `frontend/src/components/impact/ImpactPropagationTree.vue`：

```vue
<template>
  <div class="propagation-tree">
    <!-- 源表卡片 -->
    <div class="depth-section">
      <div class="depth-label">源表</div>
      <div class="tree-cards">
        <div class="impact-card">
          <div class="type-badge" :style="{ background: NODE_COLORS.SOURCE }"></div>
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
            :key="item.table"
            class="impact-card"
          >
            <div
              class="type-badge"
              :style="{ background: NODE_COLORS[item.type] || '#d9d9d9' }"
            ></div>
            <div class="card-content">
              <span class="table-name">{{ item.table }}</span>
              <span v-if="item.impactSource" class="impact-source">
                ← 来自 {{ item.impactSource }}
              </span>
            </div>
            <button
              class="code-btn"
              @click="$emit('viewScript', item)"
              :disabled="!item.procName"
              :title="item.procName ? '查看代码片段' : '无关联存储过程'"
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
  impactedByDepth: { type: Object, default: () => ({}) },
  collapsedTempTables: { type: Boolean, default: false }
})

defineEmits(['viewScript'])

const NODE_COLORS = {
  SOURCE: '#52c41a',
  TEMP: '#faad14',
  TARGET: '#f5222d',
  FIELD: '#1890ff'
}

// 实际展示的数据：折叠临时表时重算层级
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

/**
 * 折叠临时表后重算层级和 impactSource
 * 算法：
 * 1. 基于原始 impactSource 构建邻接表
 * 2. 识别非 TEMP 节点集合
 * 3. 从 sourceTable BFS，跳过 TEMP 节点，找最近的非 TEMP 祖先
 */
function rebuildWithoutTemp(impactedByDepth, sourceTable) {
  // 1. 构建原始邻接表
  const adjacency = {}
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      if (t.impactSource) {
        if (!adjacency[t.impactSource]) adjacency[t.impactSource] = []
        adjacency[t.impactSource].push(t.table)
      }
    }
  }

  // 2. 构建表信息查找表 + 识别非 TEMP 节点
  const tableInfoMap = {}
  const nonTempTables = new Set([sourceTable])
  for (const tables of Object.values(impactedByDepth)) {
    for (const t of tables) {
      tableInfoMap[t.table] = { type: t.type, procName: t.procName }
      if (t.type !== 'TEMP') nonTempTables.add(t.table)
    }
  }

  // 3. BFS：跳过 TEMP，连接最近的非 TEMP 祖先
  const depthMap = { [sourceTable]: 0 }
  const impactSourceMap = {}
  const queue = [sourceTable]

  while (queue.length) {
    const current = queue.shift()
    const currentDepth = depthMap[current]
    const neighbors = adjacency[current] || []

    for (const neighbor of neighbors) {
      // 直接是非 TEMP 节点：正常连接
      if (nonTempTables.has(neighbor) && !(neighbor in depthMap)) {
        depthMap[neighbor] = currentDepth + 1
        impactSourceMap[neighbor] = current
        queue.push(neighbor)
      }
      // 是 TEMP 节点：递归找 TEMP 的下游非 TEMP 节点
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

  // 4. 重新构建 impactedByDepth
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

- [ ] **步骤 2：验证前端构建**

运行（PowerShell）：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend; npm run build
```

预期：✓ built，无编译错误。

- [ ] **步骤 3：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add frontend/src/components/impact/ImpactPropagationTree.vue
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增传播树组件 ImpactPropagationTree"
```

---

## 任务 4：前端 ScriptSnippetModal.vue 代码片段弹窗

**文件：**
- 创建：`frontend/src/components/impact/ScriptSnippetModal.vue`
- 参考：复用 `GET /api/proc/{procName}/script` 接口

- [ ] **步骤 1：确认 script 接口的 API 封装**

运行 `Read` 工具读取 `frontend/src/api/proc.js`（或类似文件），确认是否有 `getScript` 方法。

如果已存在，直接复用；如果没有，在 `frontend/src/api/impact.js` 中新增：

```javascript
import request from '@/utils/request'

export function getProcScript(procName) {
  return request({
    url: `/api/proc/${procName}/script`,
    method: 'get'
  })
}
```

- [ ] **步骤 2：创建 ScriptSnippetModal.vue 组件**

创建 `frontend/src/components/impact/ScriptSnippetModal.vue`：

```vue
<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="80%"
    top="5vh"
    @close="$emit('close')"
  >
    <div v-loading="loading" class="modal-body">
      <div v-if="error" class="error-tip">
        <el-alert :title="error" type="error" show-icon :closable="false" />
      </div>
      <template v-else-if="scriptContent">
        <div class="modal-info">
          受上游表 <strong>{{ impactSource }}</strong> 影响
          <span v-if="highlightLines.size === 0" class="no-match-tip">
            （脚本中未直接引用该表，影响可能通过中间表传递）
          </span>
        </div>
        <div class="script-viewer" ref="scriptViewer">
          <div
            v-for="(line, idx) in scriptLines"
            :key="idx"
            :class="['script-line', { 'highlight-line': highlightLines.has(idx + 1) }]"
          >
            <span class="line-number">{{ idx + 1 }}</span>
            <span class="line-content">{{ line }}</span>
          </div>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { getProcScript } from '@/api/impact'

const props = defineProps({
  visible: { type: Boolean, default: false },
  procName: { type: String, default: '' },
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
  return props.procName ? `${props.procName}.py` : '代码片段'
})

// 高亮逻辑：匹配 impactSource 的表名引用行
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
  if (!props.procName) {
    error.value = '存储过程名为空'
    return
  }
  loading.value = true
  error.value = ''
  scriptContent.value = ''
  try {
    const res = await getProcScript(props.procName)
    const data = res.data || {}
    scriptContent.value = data.scriptContent || ''
    scriptLines.value = scriptContent.value.split('\n')
    highlightLines.value = computeHighlightLines(scriptContent.value, props.impactSource)

    // 自动滚动到第一个高亮行
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
</script>

<style scoped>
.modal-body {
  min-height: 300px;
  max-height: 75vh;
}
.modal-info {
  margin-bottom: 8px;
  padding: 8px 12px;
  background: #f5f5f5;
  border-radius: 4px;
  font-size: 13px;
}
.no-match-tip {
  color: #faad14;
  margin-left: 8px;
}
.script-viewer {
  max-height: 65vh;
  overflow: auto;
  background: #1f1f1f;
  border-radius: 4px;
  padding: 8px 0;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.script-line {
  display: flex;
  padding: 0 12px;
}
.script-line:hover {
  background: #2a2a2a;
}
.line-number {
  color: #5a5a5a;
  width: 50px;
  text-align: right;
  padding-right: 12px;
  flex-shrink: 0;
  user-select: none;
}
.line-content {
  color: #d4d4d4;
  white-space: pre;
  flex: 1;
}
.highlight-line {
  background: #3b1f1f !important;
  border-left: 3px solid #ef4444;
  padding-left: 9px;
}
.highlight-line .line-content {
  color: #ef9a9a;
}
.error-tip {
  padding: 20px 0;
}
</style>
```

- [ ] **步骤 3：在 impact.js 中新增 getProcScript（如尚未存在）**

读取 `frontend/src/api/impact.js`，如果已有 `getProcScript` 则跳过此步骤；否则在文件末尾新增：

```javascript
export function getProcScript(procName) {
  return request({
    url: `/api/proc/${procName}/script`,
    method: 'get'
  })
}
```

- [ ] **步骤 4：验证前端构建**

运行：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend; npm run build
```

预期：✓ built，无编译错误。

- [ ] **步骤 5：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add frontend/src/components/impact/ScriptSnippetModal.vue frontend/src/api/impact.js
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增代码片段弹窗组件 ScriptSnippetModal"
```

---

## 任务 5：ImpactAnalysis.vue 集成

**文件：**
- 修改：`frontend/src/views/ImpactAnalysis.vue`

- [ ] **步骤 1：新增 import 和状态**

在 `<script setup>` 标签内的 import 部分新增（位于现有 import 之后）：

```javascript
import ImpactPropagationTree from '@/components/impact/ImpactPropagationTree.vue'
import ScriptSnippetModal from '@/components/impact/ScriptSnippetModal.vue'
```

在 `selectedEdge` ref 之后新增状态（位于第 65 行之后）：

```javascript
const collapseTempTables = ref(false)
const scriptModalVisible = ref(false)
const scriptModalData = ref({ table: '', procName: '', impactSource: '' })
```

- [ ] **步骤 2：新增 handleViewScript 方法**

在 `handleCollapse` 方法之后新增：

```javascript
const handleViewScript = ({ table, procName, impactSource }) => {
  if (!procName) {
    ElMessage.warning('该表无关联存储过程')
    return
  }
  scriptModalData.value = { table, procName, impactSource }
  scriptModalVisible.value = true
}
```

- [ ] **步骤 3：在 handleAnalyze 中重置折叠状态**

修改 `handleAnalyze` 方法。在 `loading.value = true` 之后新增重置：

`old_string`（第 80-82 行）：
```javascript
const handleAnalyze = async (request) => {
  loading.value = true
  neo4jError.value = ''
```

`new_string`：
```javascript
const handleAnalyze = async (request) => {
  loading.value = true
  collapseTempTables.value = false
  neo4jError.value = ''
```

- [ ] **步骤 4：在模板中集成传播树和代码弹窗**

修改 `<template>`。在 `<ImpactGraph>` 组件之后（第 27 行 `/>` 之后）、`</el-col>` 之前新增传播树区域：

`old_string`（第 19-28 行）：
```vue
      <el-col :span="18">
        <ImpactGraph
          ref="graphRef"
          :nodes="renderNodes"
          :edges="renderEdges"
          :isFieldLevel="isFieldLevel"
          @nodeClick="handleNodeClick"
          @edgeClick="handleEdgeClick"
        />
      </el-col>
```

`new_string`：
```vue
      <el-col :span="18">
        <ImpactGraph
          ref="graphRef"
          :nodes="renderNodes"
          :edges="renderEdges"
          :isFieldLevel="isFieldLevel"
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
            :impactedByDepth="result.impactedByDepth"
            :collapsedTempTables="collapseTempTables"
            @viewScript="handleViewScript"
          />
        </div>
      </el-col>
```

- [ ] **步骤 5：在模板末尾新增 ScriptSnippetModal**

修改 `<template>`。在 `</el-row>` 之后、`</div>` 之前新增：

`old_string`（第 45-47 行）：
```vue
    </el-row>
  </div>
</template>
```

`new_string`：
```vue
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

- [ ] **步骤 6：新增样式**

在 `<style scoped>` 标签内（`.impact-analysis` 规则之后）新增：

`old_string`（第 186-188 行）：
```css
.impact-analysis {
  padding: 10px;
}
</style>
```

`new_string`：
```css
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

- [ ] **步骤 7：验证前端构建**

运行：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend; npm run build
```

预期：✓ built，无编译错误，生成 `ImpactAnalysis-*.js` 产物。

- [ ] **步骤 8：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add frontend/src/views/ImpactAnalysis.vue
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: ImpactAnalysis 集成传播树和代码片段弹窗"
```

---

## 任务 6：端到端验证

**文件：** 无文件改动，仅验证

- [ ] **步骤 1：启动后端**

运行（PowerShell）：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor; & 'D:\HB\maven\bin\mvn.cmd' spring-boot:run
```

预期：应用启动成功（Neo4j 已启动，无连接错误）。

- [ ] **步骤 2：验证 API 返回传播树数据**

运行（PowerShell，用临时 JSON 文件避免转义问题）：

```powershell
@'
{"schema":"CDM","tableShortName":"C_AGT_DEPOSIT_ACCT_CALCU_BASE","direction":"DOWNSTREAM","maxDepth":5}
'@ | Set-Content -Path "$env:TEMP\test-analyze.json" -Encoding UTF8

Invoke-RestMethod -Uri http://localhost:8080/api/impact/analyze -Method Post `
  -ContentType "application/json" -InFile "$env:TEMP\test-analyze.json" `
  | ConvertTo-Json -Depth 10
```

预期：返回 JSON 包含 `sourceTable`、`impactedByDepth`（按深度分组的表）、`propagationChains`（"A → B" 格式）。

- [ ] **步骤 3：浏览器手动验证（14 项清单）**

浏览器打开 `http://localhost:8080/#/impact`，按规格文档"前端手动验证清单"逐项验证：

| 序号 | 验证项 | 预期结果 |
|------|--------|---------|
| 1 | 选择 schema=CDM, 表=C_AGT_DEPOSIT_ACCT_CALCU_BASE, 点击分析 | G6 图 + 传播树同时显示 |
| 2 | 传播树显示分层结构 | 源表 → Level 1 → Level 2 → ... |
| 3 | 每层卡片显示表名、impactSource、类型色块 | 正确显示 |
| 4 | 点击"折叠临时表"开关 | VT_ 表被隐藏，层级重新计算 |
| 5 | 再次点击"折叠临时表" | 临时表恢复显示 |
| 6 | 点击卡片上的"代码"按钮 | 弹出代码片段 Modal |
| 7 | Modal 显示完整脚本 + 行号 | 正确显示 |
| 8 | impactSource 引用行被高亮 | 红色左边框 + 浅红背景 |
| 9 | Modal 自动滚动到第一个高亮行 | 滚动定位正确 |
| 10 | 关闭 Modal（点击×或遮罩） | 正常关闭 |
| 11 | 切换方向为 UPSTREAM 重新分析 | 传播树显示上游表 |
| 12 | 切换方向为 BOTH 重新分析 | 传播树包含上下游 |
| 13 | G6 图渲染正常 | 节点边显示完整 |
| 14 | 节点超过 50 提示折叠 | 提示出现 |

- [ ] **步骤 4：运行后端测试**

运行：
```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest -q
```

预期：5 个测试全部 PASS。

---

## 自检

### 1. 规格覆盖度

| 规格章节 | 实现任务 | 覆盖 |
|---------|---------|------|
| 后端数据模型扩展（ImpactAnalysisVO） | 任务 1 | ✅ |
| buildImpactedByDepth 核心逻辑 | 任务 2 步骤 3 | ✅ |
| buildPropagationChains 逻辑 | 任务 2 步骤 3 | ✅ |
| buildAdjacency 逻辑 | 任务 2 步骤 3 | ✅ |
| analyze 改造 | 任务 2 步骤 4 | ✅ |
| 后端单元测试（3 个） | 任务 2 步骤 1 | ✅ |
| ImpactPropagationTree.vue | 任务 3 | ✅ |
| 折叠临时表逻辑 | 任务 3 步骤 1（rebuildWithoutTemp） | ✅ |
| ScriptSnippetModal.vue | 任务 4 | ✅ |
| 高亮逻辑 | 任务 4 步骤 2（computeHighlightLines） | ✅ |
| ImpactAnalysis.vue 集成 | 任务 5 | ✅ |
| 错误处理（7 种场景） | 任务 5（v-if + warning + 弹窗内错误提示） | ✅ |
| 前端手动验证清单（14 项） | 任务 6 步骤 3 | ✅ |

### 2. 占位符扫描

无 TODO/待定/占位符。所有代码块完整，所有步骤有实际命令和预期输出。

### 3. 类型一致性

- `ImpactAnalysisVO.ImpactedTable` 内嵌类在任务 1 定义，任务 2 的 `buildImpactedByDepth` 返回 `Map<Integer, ImpactAnalysisVO.ImpactedTable>`，类型一致
- `ImpactedTable` 字段名：`table`、`depth`、`impactSource`、`type`、`procName`、`totalColumns`，任务 3 前端组件使用相同字段名，一致
- `buildImpactedByDepth` 方法签名在任务 2 步骤 3 定义，步骤 4 调用参数匹配
- 前端 `viewScript` emit 传递 `item`（ImpactedTable 对象），任务 5 `handleViewScript` 解构 `{ table, procName, impactSource }`，字段名一致

### 4. 命令一致性

- 所有 Maven 命令使用 `& 'D:\HB\maven\bin\mvn.cmd'`（与项目记忆一致）
- 所有前端构建使用 `cd ...; npm run build`（PowerShell `;` 分隔）
- Git 命令使用 `git -C <path>` 形式

计划自检完成，无遗漏，无占位符，类型一致。
