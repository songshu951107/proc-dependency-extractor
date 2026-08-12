# 报表列溯源 - 设计规格

## 背景

现有报表影响分析页面支持 3 种查询类型：表级（by-table）、字段级（by-field）、口径（by-header）。用户需求：新增第 4 种查询类型"溯源"，支持选择报表+表头列，展示该列来源的上游字段级链路图谱。

## 范围

- 后端：新增 2 个接口（报表表头列列表、报表列溯源分析）
- 前端：扩展 ReportImpactFilter 增加查询类型，扩展 ReportImpactView 增加图谱布局
- 复用现有 ImpactFlowGraph 组件和 ImpactAnalysisService

## 架构

### 后端

#### 新增接口 1：报表表头列列表

- 路径：`GET /api/report-impact/report-headers?reportFile={file}`
- 实现：`ReportImpactController` + `ReportImpactService.getReportHeaders(String reportFile)`
- 逻辑：查询 `report_cell_binding` 表，按 `report_file` 过滤，返回去重的 `header_text` 列表（非空）
- 返回：`Result<List<String>>`

#### 新增接口 2：报表列溯源分析

- 路径：`GET /api/report-impact/trace-lineage?reportFile={file}&headerText={text}`
- 实现：`ReportImpactController` + `ReportImpactService.traceReportLineage(String reportFile, String headerText)`
- 逻辑：
  1. 查询 `report_cell_binding` 拿到 `source_table` 和 `source_field`（取第一条匹配记录）
  2. 从 `source_table` 解析 schema 和 tableShortName
  3. 构造 `ImpactAnalysisRequest`（direction=UPSTREAM, fieldName=source_field, maxDepth=5）
  4. 调用 `ImpactAnalysisService.analyze(request)` 返回 `ImpactAnalysisVO`
- 返回：`Result<ImpactAnalysisVO>`（含 nodes/edges/impactedByDepth/stats，与现有影响分析接口结构一致）

### 前端

#### ReportImpactFilter.vue 扩展

- 查询类型下拉新增第 4 项 `trace`（溯源）
- 选 `trace` 时显示：
  - 报表下拉（el-select filterable，options 从 `getReportList()` 加载）
  - 表头列下拉（el-select filterable，options 从 `getReportHeaders(reportFile)` 联动加载）
  - 分析按钮
- 其他查询类型保持原控件不变

#### ReportImpactView.vue 扩展

- 新增状态：`lineageResult`（ImpactAnalysisVO）、`lineageLoading`、`lineageError`
- 查询类型为 `trace` 时：
  - 隐藏报表列表（ReportList）和字段映射详情表（FieldMappingTable）
  - 显示全屏图谱布局（参考 ImpactAnalysis.vue）：
    - 顶部筛选工具栏（Schema 多选 + 表名输入 + 隐藏未命中开关）
    - 左侧 ImpactFlowGraph 图谱
    - 右侧详情面板（节点详情/边详情）
- 复用 `buildWithoutTemp` 逻辑构建图谱节点

#### report.js 扩展

```js
export function getReportHeaders(reportFile)  // GET /report-impact/report-headers
export function traceReportLineage(reportFile, headerText)  // GET /report-impact/trace-lineage
```

## 数据流

```
1. 用户选"溯源"查询类型 → ReportImpactFilter 显示报表+表头列下拉
2. 用户选报表 → 联动调用 getReportHeaders(reportFile) 加载表头列
3. 用户选表头列 + 点分析 → 调用 traceReportLineage(reportFile, headerText)
4. 后端：
   - 查 report_cell_binding 拿 source_table + source_field
   - 调 ImpactAnalysisService.analyze(UPSTREAM, field)
   - 返回 ImpactAnalysisVO
5. 前端收到 VO → buildWithoutTemp 构建 allNodesByDepth
6. ImpactFlowGraph 渲染字段级上游图谱
```

## 错误处理

- 报表无表头列：返回空列表，前端表头列下拉为空
- 表头列无 source_table/source_field：返回错误提示"该表头列未配置字段来源"
- source_table 无法解析 schema：返回错误提示"源表格式无法识别"
- ImpactAnalysisService.analyze 失败：返回错误提示

## 测试

- 后端：ReportImpactService 单元测试（mock report_cell_binding 和 ImpactAnalysisService）
- 前端：手动验证
  1. 选"溯源"查询类型，控件正确切换
  2. 选报表后表头列下拉正确联动
  3. 选表头列点分析，图谱正确渲染上游链路
  4. 节点点击显示详情
  5. 筛选工具栏功能正常

## 不做

- 不修改现有 3 种查询类型的逻辑
- 不修改 ImpactFlowGraph 组件
- 不修改 ImpactAnalysisService
