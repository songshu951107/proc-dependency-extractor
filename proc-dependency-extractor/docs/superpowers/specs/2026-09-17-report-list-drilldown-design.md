# 报表列表 + 数据集详情下钻（报表影响分析页）设计

日期：2026-09-17
状态：已批准

## 功能目标

在「报表影响分析」页"报表解析"入口按钮左侧新增「报表列表」按钮，点击弹出结果清单（序号 / 报表名 / 文件所在路径 / 解析时间，分页 + 报表名搜索）。点击报表名下钻，弹出该报表每个数据集的 SQL 详情、连接数据库、数据集信息，并补充展示源表与字段映射。

## 涉及文件

- 后端：`ReportInfo`（加列）、`ReportParseServiceImpl`（存路径 + 下钻解析）、`ReportController`（新增下钻端点）、`ReportSqlParser`（复用，不改）
- 前端：`ReportImpactView.vue`（入口按钮）、新增 `ReportListDialog.vue`、新增 `ReportDatasetDrilldownDialog.vue`、`api/report.js`（追加下钻接口）

## 后端

1. **路径存储**：`report_info` 加列 `file_path VARCHAR(512) NULL`。`parseAndSave` 写入传入的 `filePath`（完整路径）。
2. **旧数据兜底**：清单接口返回时 `file_path` 为空则拼 `reportDir + "/" + file`。
3. **清单接口**：复用 `GET /api/report/list`（已支持 pageNum/pageSize/name 分页 + 搜索）。新增返回 `file_path`。
4. **下钻接口**：新增 `GET /api/report/{file}/datasets`，每数据集返回：
   - `datasetName` / `connection`（连接数据库）/ `tableCount` / `sqlText`
   - `sourceTables`：`extractTables(sqlText)`
   - `fieldMappings`：`extractColumns(sqlText)` → `selectAlias` / `sourceTable.description` / `sourceField` / `sourceExpr`
   - 复用 `ReportSqlParser`，前端不做 SQL 解析。
5. **DB 变更**：`ALTER TABLE report_info ADD COLUMN file_path VARCHAR(512) NULL;`

## 前端

1. "报表解析"入口按钮左侧新增「报表列表」按钮 → 打开报表清单弹窗。
2. **报表清单弹窗**：顶部报表名搜索框 + 分页；表格列 = 序号（`(page-1)*size+index+1`）/ 报表名 / 文件所在路径 / 解析时间。点报表名 → 打开下钻弹窗。
3. **下钻弹窗（tab 布局）**：
   - 左栏：数据集 tab 纵向列表（可滚动、高亮当前）
   - 右栏：元信息（连接数据库、涉及表数）+ 源表列表 + 字段映射表格（别名 / 源表.源字段 / 源表达式）+ SQL 代码块（滚动 + 复制按钮）
   - 空态：无数据集或解析失败显示占位。

## 错误处理

- `{file}` 含扩展名 `.`，前端用 `encodeURIComponent`。
- 路径为空 / 源表为空 / SQL 解析失败均给空态或兜底，不报错中断。

## 测试

- 后端：下钻接口集成测试（源表 + 字段映射解析），`ReportSqlParserTest` 回归。
- 前端：手工验证清单分页 + 搜索 + 路径兜底、下钻 tab 切换 + SQL 复制 + 空态。