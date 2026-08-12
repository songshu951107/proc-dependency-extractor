# 存储过程依赖与血缘分析系统 · 项目建设文档

## 第一章 项目建设背景与必要性

### 1.1 建设背景

数据仓库作为银行经营分析、监管报送、风险管控的数据底座，承载着 FDM（贴源层）、ODM（明细层）、CDM（汇总层）、PID（指标层）等多层级海量数据。各层级间的数据加工通过大量存储过程（脚本）实现，单条链路往往跨越多张表、多个存储过程、多段 SQL，并最终通过帆软报表对外呈现。

当前在数据开发与治理实践中面临以下现实问题：

1. **脚本规模大、加工链路复杂**。存储过程动辄数百个，单个脚本内含多段 `INSERT…SELECT`、临时表（`VT_`/`TMP_`）嵌套加工，表与表、字段与字段之间的依赖关系隐含在 SQL 文本中，缺乏结构化记录。
2. **变更影响难以评估**。修改一张底层表的字段或加工逻辑时，无法快速回答"会影响哪些下游表、哪些存储过程、最终影响哪些报表"，只能靠人工逐个排查脚本，效率低、易遗漏，存在数据断供风险。
3. **报表数据来源不透明**。业务报表上的某一列数据，其源头可能经过多张表、多个字段的转换聚合，但报表与底层字段的对应关系分散在报表 SQL 与存储过程中，难以追溯，无法满足数据溯源与口径核查需求。
4. **缺乏统一的血缘资产沉淀**。血缘信息零散存在于开发者记忆与个别文档中，人员变动后知识断层，数据资产的可维护性持续下降。

### 1.2 建设必要性

基于上述背景，建设一套自动化的"存储过程依赖与血缘分析系统"具有明确必要性：

- **支撑变更影响评估**：在表/字段变更前，快速识别全链路影响范围，降低数据断供与口径偏差风险。
- **实现数据血缘可追溯**：从报表列一路追溯到源表源字段，满足数据治理、监管溯源与口径审计要求。
- **沉淀结构化数据资产**：将隐含于 SQL 中的依赖关系自动解析并结构化存储，形成可持续维护的血缘知识库，摆脱对个人经验的依赖。
- **提升开发与运维效率**：以图谱直观呈现复杂链路，替代人工梳理脚本，显著降低排障与影响分析的人力成本。

### 1.3 建设目标

- 解析存储过程脚本与帆软报表，自动提取表级与字段级血缘关系。
- 以图数据库承载血缘网络，支持多跳上下游影响分析与路径追溯。
- 提供 Web 可视化界面，实现"影响分析""报表溯源"等高频数据治理场景的一键化操作。

---

## 第二章 项目架构

### 2.1 总体架构

系统采用前后端分离的分层架构，业务数据持久化于 MySQL，血缘网络存储于 Neo4j 图数据库。

```
┌────────────────────────────────────────────────────────────────┐
│                    表现层（Vue3 + Element Plus）                  │
│   导入脚本 / 存储过程列表 / 依赖表详情 / 血缘图 / 影响分析 / 报表分析 │
└──────────────────────────────┬─────────────────────────────────┘
                               │  HTTP / REST API
┌──────────────────────────────▼─────────────────────────────────┐
│                    应用层（Spring Boot 3.2.5）                    │
│   Controller ─ Service ─ Parser                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ 解析器：脚本解析 / SQL 解析 / 报表解析 / DDL 解析 / 字段映射 │   │
│   └─────────────────────────────────────────────────────────┘   │
└──────────┬──────────────────────────────────────┬──────────────┘
           │ MyBatis-Plus                          │ Spring Data Neo4j
┌──────────▼──────────────┐            ┌──────────▼──────────────┐
│   关系存储（MySQL 8）     │            │   图存储（Neo4j）          │
│ proc_info / lineage /    │            │ Table / Column / Script  │
│ field_mapping / report_* │            │ Report / Dataset + 关系边 │
└──────────────────────────┘            └──────────────────────────┘
```

### 2.2 技术选型

| 层次 | 技术 | 选型理由 |
|------|------|----------|
| 后端框架 | Spring Boot 3.2.5 / JDK 17 | 主流企业级框架，生态成熟 |
| ORM | MyBatis-Plus 3.5.7 | 简化单表 CRUD，灵活扩展 |
| 关系数据库 | MySQL 8 | 持久化解析结果与元数据 |
| 图数据库 | Neo4j + Spring Data Neo4j | 天然适配多跳血缘遍历，Cypher 表达力强 |
| 前端 | Vue 3 + Vite + Element Plus | 组件丰富，图谱可视化能力强 |
| 图谱渲染 | ECharts / G6 关系图 | 支持分层布局与交互 |

### 2.3 后端模块划分

```
com.hb.extractor
├── config        配置与基础设施（MyBatis-Plus、Neo4j、约束初始化、同步重试调度）
├── controller    REST 接口层
├── dto           请求/响应数据对象
├── entity        数据库实体
├── mapper        数据访问层
├── parser        解析器层（核心）
│   ├── PyProcParser           存储过程解析编排
│   ├── ProcHeaderParser       头部注释元数据提取
│   ├── SchemaVarResolver      schema 变量映射解析
│   ├── SqlBlockExtractor      三引号 SQL 块抽取
│   ├── SqlTableExtractor      SQL 临时表/依赖表提取
│   ├── LineageExtractor       表间派生关系提取
│   ├── FieldMappingExtractor  字段映射提取
│   ├── DdlParser              DDL 文件字段提取
│   ├── CptXmlParser           帆软报表 XML 解析
│   └── ReportSqlParser        报表 SELECT 语句解析
└── service
    └── impl
        ├── ProcExtractServiceImpl     存储过程解析入库
        ├── LineageServiceImpl         血缘查询
        ├── ImpactAnalysisServiceImpl  影响分析（表级/字段级）
        ├── ReportParseServiceImpl     报表解析入库 + Neo4j 同步
        ├── ReportImpactServiceImpl    报表影响分析/溯源
        └── Neo4jSyncServiceImpl       存储过程 → Neo4j 同步
```

---

## 第三章 设计方案

### 3.1 数据模型设计

系统采用"MySQL + Neo4j"双存储：MySQL 持久化解析结果与元数据（便于检索与事务），Neo4j 承载血缘网络（便于多跳遍历）。

#### 3.1.1 MySQL 关系模型

**存储过程侧**（`sql/init.sql`）：

| 表 | 说明 |
|----|------|
| `proc_info` | 存储过程基本信息（名称、功能说明、更新对象） |
| `proc_table_dependency` | 存储过程依赖表清单 |
| `table_lineage` | 表级血缘边（源表→目标表、操作类型、是否临时表） |
| `table_lineage_filter` | 血缘边的 JOIN / WHERE 条件 |
| `table_field_mapping` | 字段映射（目标字段↔源字段、来源表达式） |
| `sql_block_info` | SQL 块原文与解析信息 |

**报表侧**（`sql/report_tables.sql`）：

| 表 | 说明 |
|----|------|
| `report_info` | 报表基本信息 |
| `report_dataset` | 数据集（名称、连接、SQL 原文、引用表数） |
| `report_cell_binding` | 单元格绑定（位置、表头文字、别名、源表、源字段、来源表达式） |

#### 3.1.2 Neo4j 图模型

详见 `docs/neo4j-graph-data-guide.md`。

**节点（Label）**：

| 节点 | 主键 | 说明 |
|------|------|------|
| `Table` | `name`（schema.table 全小写） | 表，含层级、是否临时表、是否源表、生成脚本 |
| `Column` | `col_key`（schema.table.field 全小写） | 字段 |
| `Script` | `file` | 存储过程脚本 |
| `Report` | `file` | 帆软报表 |
| `Dataset` | `key` | 报表数据集 |

**关系（Relationship）**：

| 关系 | 方向 | 说明 |
|------|------|------|
| `BELONGS_TO` | Column → Table | 字段归属表 |
| `MAPS_TO` | Column → Column | 字段级血缘（含 proc_name、source_expr、转换类型） |
| `READS` | Table → Table | 表级血缘（含 proc_name、操作类型、JOIN/WHERE 条件） |
| `PRODUCES` / `CONSUMES` | Script → Table | 脚本产出/消费表 |
| `CONTAINS` | Report → Dataset | 报表包含数据集 |
| `QUERIES` | Dataset → Table | 数据集查询表 |
| `REFERENCES` | Dataset → Column | 数据集引用字段（含别名、单元格位置） |

#### 3.1.3 关键标识约定

- `Table.name`：`schema.table` 全小写，如 `cdm.c_agt_deposit_acct_inf`。
- `Column.col_key`：`schema.table.field` 全小写，如 `cdm.c_agt_deposit_acct_inf.acct_id`。
- 临时表识别规则：`VT_` / `TMP_` 前缀、`_TMP` 后缀（含数字后缀）。

### 3.2 解析方案设计

#### 3.2.1 存储过程解析流程

```
.py 脚本
  │
  ├─① ProcHeaderParser     提取头部注释（脚本名/功能/更新对象）
  ├─② SchemaVarResolver    解析 schema 变量映射（CDM=etl.dwa_db_cdm）
  ├─③ SqlBlockExtractor    抽取三引号 SQL 块
  ├─④ SqlTableExtractor    从 SQL 提取临时表与依赖表
  ├─⑤ LineageExtractor     按块顺序提取表间派生关系（支持子查询递归，最大深度 5）
  ├─⑥ FieldMappingExtractor 提取目标字段↔源字段映射
  └─⑦ DdlParser            从 DDL 文件补全表字段定义
        │
        ▼
   MySQL 入库  ──→  Neo4j 同步
```

**字段映射目标字段确定规则**：
- 有 DDL 字段列表 → 按位置对应；
- 无 DDL 且列有 `AS alias` → 取 alias；
- 无 DDL 且列为 `T01.acct_no` → 取 `acct_no`；
- 无 DDL 且为表达式（无别名）→ 记为 `expr_{序号}`。

**转换类型分类**（基于来源表达式）：
- `direct`：直接字段引用
- `literal`：字面量（`'N'`、`123`）
- `aggregation`：聚合函数（SUM/COUNT/AVG/MAX/MIN）
- `derived`：运算或 CASE WHEN 派生表达式

#### 3.2.2 报表解析流程

```
.cpt/.frm/.xml
  │
  ├─① CptXmlParser      解析 XML，提取数据集（名称/连接/SQL）与单元格绑定（位置/表头文字）
  ├─② ReportSqlParser   解析数据集 SELECT 语句
  │       ├─ 提取表引用（FROM/JOIN [schema.]table [alias]）
  │       └─ 提取字段映射（别名→源表、源字段、来源表达式）
  └─③ 合并单元格绑定与字段映射，写入 report_cell_binding
        │
        ▼
   MySQL 入库  ──→  Neo4j 同步（Report/Dataset/QUERIES/REFERENCES）
```

报表 SQL 解析支持帆软动态参数 `${if(...)}` 与注释的预处理，按括号深度拆分 SELECT 列，兼容 `AS alias` 与末尾空格别名两种写法。

### 3.3 影响分析方案设计

#### 3.3.1 双层级分析

- **表级分析**：以 `Table` 为起点，沿 `READS` 关系递归遍历（最大深度 20，接口默认 5）。
- **字段级分析**：以 `Column`（`col_key`）为起点，沿 `MAPS_TO` 关系递归遍历。

#### 3.3.2 深度计算与临时表穿透

实际数据流中，非临时表通常经临时表 JOIN 后才 INSERT 到目标表。系统在深度计算上采用"优先选择最后一跳经过临时表的路径"策略，反映真实数据流；前端图谱与最大深度统计中临时表被穿透（不单独占层级），避免临时表虚增传播深度。

#### 3.3.3 报表列溯源链路

```
报表 + 表头列
  │
  ├─① report_cell_binding 取 source_table + source_field
  ├─② 构造字段级 UPSTREAM 分析请求（maxDepth=5）
  └─③ 复用 ImpactAnalysisService.analyze 沿 MAPS_TO 向上游追溯
        │
        ▼
   字段级血缘图谱（起点字段 → 逐层上游字段 + 转换表达式）
```

### 3.4 同步与容错设计

- **解析与同步解耦**：MySQL 入库为主流程，Neo4j 同步失败不影响主流程，仅记录告警。
- **失败自动重试**：`Neo4jSyncLog` 记录同步状态（PENDING/SUCCESS/FAILED），调度器按 5 分钟间隔自动重试，最多 3 次。
- **幂等处理**：重复解析同名脚本/报表时，先删除旧数据（MySQL + Neo4j）再写入，保证数据一致。

---

## 第四章 实现功能

### 4.1 存储过程解析与管理

- 支持单文件与目录批量解析 Python 存储过程脚本。
- 自动提取脚本元信息、schema 映射、SQL 块、依赖表、字段映射。
- 提供存储过程列表分页浏览、按名称检索、依赖表详情查看。

### 4.2 表级血缘分析

- 以图谱可视化展示存储过程内部表间数据流向。
- 展示相邻表之间的 JOIN 条件与 WHERE 过滤条件。
- 支持对单表递归追溯上游（最大 10 层）。
- 提供图谱视图与列表视图切换。

### 4.3 影响分析（表级与字段级）

- **对象选择**：Schema → 表名（自动过滤临时表）→ 可选字段。
- **方向选择**：下游（DOWNSTREAM）/ 上游（UPSTREAM）/ 双向（BOTH）。
- **结果展示**：
  - 统计栏：影响字段数、涉及存储过程数、最大传播深度。
  - 分层血缘图谱（临时表穿透，不单独占层）。
  - 文字传播链（`源 → 目标` 逐跳）。
  - 报表影响（可选）：该表被哪些帆软报表引用。
- **图谱交互**：按 Schema 筛选、按表名关键字高亮、隐藏未命中节点、节点/边详情面板（字段、深度、存储过程、来源表达式）。
- 字段级分析标注每跳转换类型（直接/字面量/聚合/派生）。

### 4.4 报表解析与管理

- 支持帆软报表文件（`.cpt / .frm / .xml`）的上传解析、单文件路径解析、目录批量解析。
- 解析数据集 SQL、单元格与表头绑定、字段映射。
- 提供报表列表与详情查看。

### 4.5 报表影响分析（四种查询模式）

| 模式 | 解决问题 | 输入 | 输出 |
|------|----------|------|------|
| 表级查询 | 哪些报表用了某张表 | Schema + 表名 | 引用该表的数据集及报表清单 |
| 字段级查询 | 哪些报表引用了某字段 | Schema + 表名 + 字段 | 引用该字段的报表、来源表达式、单元格位置 |
| 口径查询 | 某口径（表头汉字）出现在哪些报表 | 表头汉字（模糊） | 相关报表及字段映射明细 |
| 报表列溯源 | 报表某列最初来自哪个字段 | 报表 + 表头列 | 字段级上游血缘图谱 |

报表列溯源以报表表头列为起点，自动定位源表+源字段，沿 `MAPS_TO` 向上游逐层追溯，展示每一跳来源字段、所属存储过程、转换表达式，并支持图谱筛选与节点详情查看。

### 4.6 数据同步与运维

- 存储过程/报表解析后自动同步至 Neo4j。
- 同步失败自动重试（间隔 5 分钟，最多 3 次），状态可查。
- 支持单存储过程手动重新同步。
- 解析幂等，重复导入覆盖旧数据。

---

## 第五章 运行环境与部署

### 5.1 运行环境

| 项 | 要求 |
|----|------|
| JDK | 17 |
| MySQL | 8.0 |
| Neo4j | 5.x |
| Node.js | 18+（前端构建） |
| Maven | 3.8.1 |

### 5.2 默认配置

- 后端服务：`http://localhost:8080`
- MySQL：`localhost:3306`，库 `proc_extractor`
- Neo4j：`bolt://localhost:7687`，库 `neo4j`
- 影响分析默认深度 5，最大深度上限 20
- DDL 文件目录：`d:/HB/work_zuoye/jiaoben`

### 5.3 部署步骤

```bash
# 1. 初始化数据库
mysql -uroot -proot < sql/init.sql
mysql -uroot -proot < sql/report_tables.sql

# 2. 构建前端（产物输出到 src/main/resources/static，由后端静态托管）
cd frontend && npm install && npm run build

# 3. 启动后端（Windows 可直接运行 startup.bat）
mvn spring-boot:run
```

### 5.4 配置项（application.yml）

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `extractor.ddl.dir` | DDL 文件目录 | `d:/HB/work_zuoye/jiaoben` |
| `impact.analysis.default-depth` | 影响分析默认深度 | 5 |
| `impact.analysis.max-depth` | 最大深度上限 | 20 |
| `impact.sync.auto-retry` | Neo4j 同步失败自动重试 | true |
| `impact.sync.retry-interval` | 重试间隔 | 300000ms |
| `impact.sync.max-retry-count` | 最大重试次数 | 3 |
