# 设计文档：批量查询 schema 下表的一层下游影响（表级）

日期：2026-09-14
状态：已确认（待用户最终审查）

## 1. 背景与目标

现有影响分析（`ImpactAnalysisServiceImpl.analyze`）为**单表**下游分析，且走完整 Neo4j 图计算（含字段/Transform/路径回溯），响应体大、开销高。

新需求：**批量查询某个 schema 下所有物理表，各自的一层（depth=1）直连下游影响**，以「汇总矩阵 + 行下钻明细 + 导出 Excel」形式呈现，服务于"整体盘点某个集市（FDM/ODM/CDM/TDM/PID 等）的影响敞口"。

本设计采用**轻量路线**：因只需要一层直连下游，直接查 MySQL `table_lineage`（`source_table -> target_table` 即是一层血缘），无需走完整 Neo4j analyze。

## 2. 已确认的需求参数

| 项 | 决定 |
|---|---|
| 功能 | 批量查某 schema 下所有物理表的一层直连下游影响（表级） |
| 起点表枚举来源 | MySQL `table_ddl_info`（该 schema 所有已导入 DDL 的物理表） |
| 分析深度 | **固定 1 层**（直连下游，不递归、不做深度配置） |
| 影响范围 | 全库（沿血缘自然跨越 FDM/CDM/PID 等数据层） |
| 被影响表 | 只计**物理表**（`target_is_temp=0`），临时表剔除 |
| 汇总矩阵列 | 表名 / 被影响表数 / 受影响存储过程数 / 涉及报表数（**无最深深度列**，已确认移除） |
| 行下钻明细 | 受影响表 / 受影响存储过程 / 受影响报表 三类**列表**（**无边图**） |
| 受影响存储过程 | 路径中所有 proc（此处即直接产出一层下游表的 proc） |
| 受影响报表 | 现有表->报表血缘（Neo4j `(Table)<-[:QUERIES]-(Dataset)<-[:CONTAINS]-(Report)`） |
| 导出 | Excel，按列信息分 sheet |
| 表名匹配 | 统一 `LOWER` 兜底，不依赖列排序规则 |
| 不含"最深深度"列 | 已确认移除 |

## 3. 数据来源与用例说明

### 3.1 起点表枚举：`table_ddl_info`

```sql
SELECT DISTINCT table_name, full_table_name
FROM table_ddl_info
WHERE LOWER(schema_name) = LOWER(#{schema});
```

- schema 大小写用 `LOWER` 兜底。
- 来源为 MySQL `table_ddl_info`，故只含**已导入 DDL 的物理表**。

### 3.2 一层直连下游：`table_lineage`

`table_lineage` 存 `source_table` / `target_table`（均为全名 `schema.table`）/ `proc_name` / `target_is_temp`。`source_table = 起点表` 的 `target_table` 集合即该起点表的一层直连下游。

```sql
SELECT DISTINCT target_table, proc_name, target_is_temp
FROM table_lineage
WHERE LOWER(source_table) = LOWER(#{tableFullName})
  AND target_is_temp = 0;   -- 只计物理表
```

- 表名匹配**双端 `LOWER`**，规避列排序规则及存储大小写不一致（实测 `alias` 当前存 `PID.a_xxx` 大写 schema，列排序规则 `utf8mb4_0900_ai_ci` 大小写不敏感，仍统一转小写保证健壮）。
- `target_is_temp=0` 只保留物理表；临时表（`CREATE TEMPORARY`/`VT_` 等）排除。
- **不写成递归/多跳**——本需求固定一层。

### 3.3 受影响报表：`ReportImpactService.findReportsByTable(schema, table)`

复用现有实现。内部走 Neo4j：

```cypher
MATCH (t:Table {name: $name})<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report)
RETURN r.file, r.name, d.name AS datasetName, d.connection;
```

对每个受影响的物理表调用一次，按 `file` 去重得到"涉及报表集合"。

### 3.4 示例（FDM 起点表，跨层）

```
fdm.f_ac_ctds_ldr_acct ──(proc 写入)--> cdm.c_agt_ldr_acct_intst
```

起点表 `fdm.f_ac_ctds_ldr_acct` 的直连下游含 `cdm.c_agt_ldr_acct_intst`（跨到 CDM 层）。被影响表、受影响的 proc、关联报表均按此统计。

> 说明"不跨集市串线"：匹配用全名 `schema.table` 的 `LOWER`，短名相同但 schema 不同的表（如 `pid.a_d_fin_mkt_prft_loss_dtl` 与 `rpt.a_d_fin_mkt_prft_loss_dtl`）前缀不同，天然不互相串入；同时跨数据层（FDM->CDM->PID）沿真实血缘正常统计。

## 4. 后端设计

### 4.1 异步任务接口

因 schema 下可能数百张表、全量计算耗时，采用**异步任务 + 进度轮询**，不阻塞请求。

```
POST /api/impact/schema/{schema}/task                        # 发起批量任务 → 立即返回 taskId（异步执行）
GET  /api/impact/schema/{schema}/task/{taskId}/progress      # 轮询进度
GET  /api/impact/schema/{schema}/task/{taskId}/result        # 取最终结果（status=done 后调用）
GET  /api/impact/schema/{schema}/task/{taskId}/export        # 导出 Excel（复用同任务结果）
```

- `{schema}` 为集市名（如 `FDM`/`PID`），大小写不敏感。
- 固定一层、响应为聚合指标与明细，无需分页排序。

### 4.1.1 异步任务状态机

```
idle -> running(P%) -> done(含 result) | failed(含 error)
```

后端以**内存任务仓库 + 线程池**管理（`ConcurrentHashMap<taskId, BatchTask>`），无需持久化：

```java
class BatchTask {
  String taskId;
  String schema;
  String status;            // running / done / failed / expired
  int total;                // 起点表总数
  int done; int failed;     // 已处理表 / 失败表
  List<SummaryRow> rows;    // 结果（done 后填充）
  String error;             // 任务级失败原因
  long createTime;
}
```

- **发起**：创建 task（status=running）→ 提交线程池异步跑 → 立即返回 taskId。
- **进度响应**：`{taskId, status, total, done, failed, percent}`。
- **结果**：status=done 返回完整 VO；running 抛 409（前端提示"进行中"）。
- **单表失败**：done++、failed++，该行 `error` 置失败原因，不中断整体。
- **任务级失败**（如 schema 枚举异常）：status=failed，`error` 置因。
- **兜底**：同一 schema 新任务替换旧任务（旧置 expired）；任务超时/过期清理。

### 4.2 处理流程（后端内部）

```
1. 枚举起点表：table_ddl_info where LOWER(schema)=LOWER(schema)
2. 对每张起点表：
     a. table_lineage 查直连下游（target_is_temp=0）
     b. 受影响物理表 = 去重集合
     c. 受影响存储过程 = 产生这些 target 的 proc 去重
     d. 受影响报表 = 对每个受影响物理表调 findReportsByTable，按 file 去重
3. 聚合为汇总矩阵 + 行明细，返回 VO
```

- 单表逻辑异常：try-catch，将该起点表标记失败（`error` 字段），**不中断整体**。
- 报表查询可对整批受影响的物理表做批量展开（先收集所有受影响表，再统一查报表），减少重复查询。

### 4.3 返回 VO

```java
// SchemaImpactSummaryVO
class SchemaImpactSummaryVO {
    String schema;
    int totalTables;                 // 起点表总数
    int failedCount;                 // 失败起点表数
    List<SummaryRow> rows;           // 每起点表一行
    List<ReportRef> affectedReports; // 全局受影响报表（按 file 去重，供导出 Sheet4）
}

// SummaryRow
class SummaryRow {
    String table;                    // 起点表全名
    Integer affectedTableCount;      // 被影响物理表数
    Integer affectedProcCount;       // 受影响存储过程数
    Integer affectedReportCount;     // 涉及报表数
    String error;                    // 单表失败原因（可选）
    List<AffectedTable> affectedTables;    // 明细：受影响物理表（含到达的 proc）
    List<String> affectedProcs;            // 明细：受影响存储过程
    List<ReportRef> affectedReports;       // 明细：受影响报表
}

// AffectedTable
class AffectedTable {
    String name;      // 受影响表全名
    String procName;  // 产生该直连下游的存储过程
}

// ReportRef（复用现有 ReportImpactVO.ReportRef 结构）
class ReportRef {
    String file;
    String name;
    String datasetName;
    String connection;
}
```

### 4.4 导出 Excel

后端复用现有导出能力（若项目已用 POI/EasyExcel 则沿用）。`export` 接口输出 4 个 sheet：

| Sheet | 内容 |
|---|---|
| Sheet1 汇总矩阵 | 每起点表一行：表名 / 被影响表数 / 受影响存储过程数 / 涉及报表数 |
| Sheet2 受影响表明细 | 起点表 / 受影响表 / 到达的 proc |
| Sheet3 受影响存储过程明细 | 起点表 / 受影响存储过程 |
| Sheet4 受影响报表明细 | 起点表 / 报表文件 / 报表名称 / 数据集 / 连接 |

## 5. 前端设计

### 5.1 页面布局

新增独立页面 `SchemaImpactView.vue`（route `/schema-impact`），与单表 `/impact` 分离。布局如下：

```
┌────────────────────────────────────────────────────────────┐
│ 工具栏（顶部）                                              │
│  [Schema 下拉]  [▶ 批量分析]  [⬇ 导出 Excel]   [进度条%]  │
├────────────────────────────────────────────────────────────┤
│ 汇总矩阵表格（el-table，border）                            │
│  ┌────────────────┬──────────┬───────────┬──────────┐      │
│  │ 表名 (点击下钻) │被影响表数 │受影响存储过│涉及报表数 │      │
│  │                │          │程数       │          │      │
│  └────────────────┴──────────┴───────────┴──────────┘      │
│  （每行 = 一张起点物理表）                                   │
├────────────────────────────────────────────────────────────┤
│ 【下钻弹窗】点击某行 → el-dialog                           │
│  Tab1 受影响表      |  Tab2 受影响存储过程 | Tab3 受影响报表 │
│  · 表全名 + 到达proc   · proc 列表           · 文件/名称/... │
│  （仅列表，不展示边图）                                      │
└────────────────────────────────────────────────────────────┘
```

- **Schema 下拉**：复用现有 schema 选项来源（与单表页同源）。
- **汇总矩阵列**：表名 / 被影响表数 / 受影响存储过程数 / 涉及报表数（无最深深度列）。
- **行下钻**：点行打开弹窗，Tab 切三类明细；明细已随 result 返回，切 Tab 无请求。

### 5.2 异步进度轮询（核心交互）

1. **选 schema → 点「批量分析」** → `POST /api/impact/schema/{schema}/task` 得 `taskId`，按钮进入 loading。
2. **轮询**：`setInterval` 每 2s 调 `GET /progress`，进度条展示 `percent`（`done/total`）：

   | status | 前端动作 |
   |---|---|
   | `done` | 停轮询，`GET /result` 渲染汇总矩阵 + 行明细 |
   | `failed` | 停轮询，`ElMessage` 报错（显示 error） |
   | `running` | 继续轮询，更新进度条 |

3. **导出**：点「导出 Excel」→ `GET /export`（本地下载）。
4. **超时兜底**：累加超时（如 5 分钟）→ 停轮询，提示"分析超时，可重新发起"。
5. **切 schema / 重复发起**：先取消旧轮询与旧任务，再发起新任务。

### 5.3 接口与页面动作映射

| 前端动作 | 接口 | 用途 |
|---|---|---|
| 点批量分析 | `POST /task` | 发起异步任务，拿 taskId |
| 轮询 | `GET /task/{id}/progress` | 查进度 |
| 渲染结果 | `GET /task/{id}/result` | 取汇总矩阵 + 明细 |
| 导出 | `GET /task/{id}/export` | 下载 Excel |

### 5.4 导出按钮

- 触发 `GET /api/impact/schema/{schema}/task/{taskId}/export`，下载 Excel。

### 5.5 实现位置

- **独立批量入口**：新增独立的"集市批量影响"入口（新增页面/路由或独立块），**与现有单表分析完全分离**。
- **保留单表分析**：现有 `ImpactFilter.vue`/`ImpactAnalysis.vue` 单表影响分析**原样保留、不回退、不耦合**；批量分析是新增的并行能力，不替代单表分析。
- 批量入口从 schema 选择直接触发汇总查询；单表入口维持现状。

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| schema 不存在 / 无起点表 | 返回 `totalTables=0`，前端提示无物理表 |
| 单张起点表下游查询失败 | 该行 `error` 置失败原因，其余正常，`failedCount` 累加 |
| 报表查询异常 | 视为该行报表数取不到，置 0 或 `error` 提示，不中断 |
| 数据源表名大小写不一致 | 统一 `LOWER` 匹配兜底 |

## 7. 测试要点

1. 单例：某 schema 起点表，直连下游物理表 / proc / 报表数与手工 SQL 一致。
2. 跨层：FDM 起点表能统计到 CDM/PID 层的一层下游（跨数据层正常）。
3. 串线防护：`pid.xxx` 与 `rpt.xxx` 同短名，分别统计不互相串入。
4. 临时表剔除：`target_is_temp=1` 的直连 target 不计入。
5. 大小写健壮：参数小写/大写命中一致（LOWER 兜底）。
6. 单表失败：注入异常，确认该行标失败、整体仍返回。
7. 导出：4 个 sheet 数据正确。
8. 回归：现有单表影响分析（Neo4j analyze）不受影响。

## 8. 范围边界（YAGNI）

- **不做**多跳递归分析（固定一层）。
- **不做**字段级分析（本功能为表级）。
- **不做**边图展示（下钻仅列表）。
- **不做**最深深度列（已确认移除）。
- **不做**深度参数配置（固定 1）。
- 如需加深层传播、字段级或边图，另立需求，不在本规格实现。