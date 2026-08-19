# 影响分析结果表格化重构设计

日期：2026-08-19
状态：已确认（用户逐节批准）

## 1. 背景与目标

现有影响分析页面以 VueFlow 全景图谱为主，节点拥挤（一次分析返回 300+ 节点，含 69 个 depth=null 辅助节点），可读性差。

目标：点击分析后，主区域改为**分层级 Tab + 受影响清单表格**，每行可弹窗查看字段级链路图；支持全层级导出 Excel。

**纯前端改造（方案 1）**：后端零改动。数据基础已完备：
- 字段级 DERIVED_FROM 边带 `procName`、`role`（join_condition/where_condition，任务 4 引入）
- 表级 READS 边带 `procName`、`join_conditions`、`where_conditions`（ImpactAnalysisServiceImpl L866-914）

## 2. 布局设计

主区域变更（[ImpactAnalysis.vue](../../../frontend/src/views/ImpactAnalysis.vue)）：

```
分析前：现有查询表单不变
分析后：ImpactStats 统计卡（保留）
       + 层级 Tab 区（替换 VueFlow 图谱 + ImpactPropagationTree + 右侧详情面板）
       + 当前层级清单表格（el-table）
       + [导出] 按钮（Tab 区右侧）
```

**Tab 结构**：`[第一层级（直接受影响）] [第二层级（间接受影响）] [第三层级] ...`，Tab 数由分析结果最大层级动态决定。字段级与表级统一此模式。

## 3. 层级计算（最大层级规则）

**规则**：受影响表/字段若有多条路径可达，归属**最长链路**（穿透临时表后跳数）。

### 3.1 临时表穿透

- 表级：复用现有 `buildWithoutTemp`（ImpactAnalysis.vue L361）的邻接表 + temp 标记
- 字段级：字段所属表名以 `vt_` 前缀判断为临时表字段（与解析器建表命名一致）

### 3.2 最长路径 DP（替换 BFS 首访）

```javascript
// 松弛直到收敛：depth[t] = max(depth[t], depth[s] + inc(t))
// inc(t) = 1（t 为非临时表节点）；临时表节点透明 inc = 0（depth = 上游最大 depth）
// 起点 depth = 0
```

**环防护**：松弛轮次上限 = 节点数；超限（存在环）节点回退 BFS 最浅深度，保证所有节点有层级值。节点/边量级几百，O(V·E) 毫秒级。

### 3.3 应用位置

- 表级：改造 `buildWithoutTemp`，depthMap 改为最长路径结果
- 字段级：新增 `computeFieldLevels(nodes, edges)`，在 DERIVED_FROM 边上计算

## 4. 清单行推导

### 4.1 字段级（行粒度：脚本+表+字段+原因，用户确认）

遍历 DERIVED_FROM 边（field→field），**只取 target 为非临时表字段的边**，按 `(procName, target表, target字段, 原因)` 分组去重：

| 列 | 取值 |
|---|---|
| 脚本 | edge.procName（点击 → ScriptSnippetModal） |
| 受影响表 | target 字段所属表（非临时表） |
| 受影响字段 | target 字段名 |
| 影响原因 | 无 role → `直接来源`；role=join_condition → `JOIN 条件`；role=where_condition → `WHERE 条件` |
| 操作 | `链路图` 按钮 |

同一字段同脚本既有直接又有条件影响 → 多行（每原因一行）。

条件边 target 为临时表字段（如 vt_06 的条件输入）不入清单——该临时表字段本身不是受影响交付物；非临时目标字段的条件输入边已由任务 2 覆盖（每个 FieldMapping 都带条件字段），无需跨边组合。

**depth=null 辅助节点不入清单**：行只从边推导，天然排除。

### 4.2 表级（行粒度：脚本+表，用户确认）

遍历 READS 边，只取 target 为非临时表的边，按 `(procName, target表)` 分组：

| 列 | 取值 |
|---|---|
| 脚本 | edge.procName |
| 受影响表 | target 表名（非临时表） |
| 层级 | 3.2 计算的 depth |
| 操作 | `链路图` 按钮 |

表级**无字段列、无原因列**（受影响恒为脚本直接写入，原因列无信息量；join/where 条件详情在链路弹窗边上展示）。此为对第 1 节展示稿的修正，已在设计确认时说明。

## 5. 链路弹窗（ImpactPathDialog，新组件）

**触发**：清单行 `链路图` 按钮。标题：`影响链路：{源} → {目标}`（字段级为 `表.字段`，表级为表名）。

### 5.1 路径回溯（只画该行 procName 的边，用户确认选项 A）

- 从行目标节点沿边向分析起点方向 DFS，**只走 `edge.procName === 行.procName` 的边**
- 收集全部可达路径（多路径全画——这正是字段归到最大层级的原因）
- 到达起点即停；未达起点的路径片段也画（该脚本只是链路一环）

### 5.2 渲染（G6 4.8.24，改造 FieldLineageDialog 模式）

- **节点**：字段节点（或表级表节点）按层配色（FDM 绿 / CDM 蓝 / 临时表橙，与 LineageView 图例一致）；Transform 节点小圆点样式
- **边**：实线 = 直接派生；虚线 = 条件影响，边标注 `ON` / `WHERE`
- dagre 自动布局（依赖已有）

### 5.3 交互

- 节点点击 → 弹窗底部小面板：字段名/表名/角色（简化版 ImpactNodeDetail）
- 边点击（带 procName 的边）→ 打开 ScriptSnippetModal 对应 SQL 片段

## 6. 导出（全层级单文件，用户确认方案 a）

- 依赖：新增 `xlsx`（SheetJS，npm 0.18.5）
- 任何 Tab 上点导出都导**全层级**，一个 .xlsx
- 列：层级 / 脚本 / 受影响表 / 受影响字段 / 影响原因（表级字段列空、原因列 `直接写入`）
- 文件名：`影响分析_{源标识}_{方向}_YYYYMMDD.xlsx`
- 不导出链路图

## 7. 边界情况

| 场景 | 处理 |
|---|---|
| 分析无结果 | 显示空态（el-empty），无 Tab |
| 图中有环 | 3.2 环防护：回退 BFS 最浅深度 |
| 边无 procName | 字段级跳过该边；表级脚本列显示 `-` |
| 行字段超长 | el-table show-overflow-tooltip |
| 链路回溯无路径（数据不一致） | 弹窗内 el-empty 提示 |

## 8. 测试与验收

前端无测试框架（package.json 无 vitest/jest）。验证方式：

1. `npm run build` 编译通过
2. 浏览器端到端：分析 `FDM.f_ac_ctds_ldr_acct.acct_no` 下游
   - Tab 层级数正确（穿透临时表后最大链路层级）
   - 核心链路字段出现在正确层级（profit.acct_no = 第二层级，因 vt_06 穿透）
   - 条件影响行存在（JOIN/WHERE 条件原因）
   - 链路弹窗只含该行 procName 的边
   - 导出文件可打开、行数与全层级合计一致
3. 表级分析同流程抽查

## 9. 范围外

- 后端接口、VO、Neo4j 查询：零改动
- 现有 expand 接口及逐级展开交互：随图谱一并下线（表格已按层级分 Tab）
- 报表影响分析页（ReportImpactView）：不动
