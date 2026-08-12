# 存储过程依赖表名提取系统（proc-dependency-extractor）

## 一、项目简介

面向银行数据仓库场景的**数据血缘与影响分析系统**。系统解析存储过程脚本（Python `.py`）与帆软报表文件（`.cpt / .frm / .xml`），提取**表级**与**字段级**血缘关系，落地 MySQL 与 Neo4j 图数据库，并对外提供血缘查询、影响分析、报表溯源等能力，支撑"改一张表/字段会影响谁""报表某列数据从哪来"等典型数据治理问题。

- **技术栈**：Spring Boot 3.2.5 + MyBatis-Plus 3.5.7 + MySQL 8 + Spring Data Neo4j + Vue 3 (Vite + Element Plus)
- **JDK**：17
- **默认端口**：后端 `8080`，MySQL `3306`，Neo4j `7474/7687`

## 二、核心能力

| 能力 | 说明 |
|------|------|
| 存储过程解析 | 解析 `.py` 脚本，提取脚本元信息、schema 变量映射、SQL 块、临时表/依赖表、字段映射 |
| 表级血缘 | 按 SQL 块顺序提取表间派生关系（source→target），支持 CREATE TEMP / INSERT SELECT / MERGE / UPDATE / DELETE |
| 字段级血缘 | 从 `INSERT…SELECT` / `CREATE TEMP…SELECT` 提取目标字段↔源字段映射（含派生表达式分类） |
| 影响分析 | 基于 Neo4j 图，按表/字段做上下游影响分析、链路扩展、路径查询 |
| 报表解析 | 解析帆软 CPT，提取数据集 SQL、单元格与表头绑定、字段映射 |
| 报表影响分析 | 按表/字段/表头汉字反查引用报表，支持报表列溯源至上游字段级链路 |

## 三、系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                       前端 (Vue3 + Element Plus)              │
│  导入脚本 / 存储过程列表 / 依赖表详情 / 血缘图 / 影响分析 / 报表影响分析 │
└───────────────────────────────┬──────────────────────────────┘
                                │ REST API
┌───────────────────────────────▼──────────────────────────────┐
│                        后端 (Spring Boot)                      │
│  Controller ─ Service ─ Parser (脚本/SQL/报表/DDL/字段映射)      │
└───────┬───────────────────────────────────────┬──────────────┘
        │ MyBatis-Plus                           │ Spring Data Neo4j
┌───────▼───────────────┐               ┌────────▼─────────────┐
│   MySQL (持久化)        │               │   Neo4j (图关系)       │
│ proc_info / lineage /  │               │ Table / Column /      │
│ field_mapping / report │               │ Script / Report /     │
│ ...                    │               │ Dataset + 关系边       │
└────────────────────────┘               └──────────────────────┘
```

## 四、模块说明

### 4.1 后端包结构

```
com.hb.extractor
├── config          # MyBatis-Plus / Neo4j 配置、约束初始化、同步重试调度
├── controller      # REST 接口
├── dto             # 请求/响应 VO
├── entity          # 数据库实体
├── mapper          # MyBatis-Plus Mapper
├── parser          # 解析器
│   ├── PyProcParser        # Python 存储过程解析编排
│   ├── ProcHeaderParser    # 头部注释元数据
│   ├── SchemaVarResolver   # schema 变量映射 (CDM=etl.dwa_db_cdm)
│   ├── SqlBlockExtractor   # 三引号 SQL 块抽取
│   ├── SqlTableExtractor   # SQL 临时表/依赖表提取
│   ├── LineageExtractor    # 表间派生关系提取
│   ├── FieldMappingExtractor # 字段映射提取
│   ├── DdlParser           # DDL 文件字段提取
│   ├── CptXmlParser        # 帆软报表 XML 解析
│   └── ReportSqlParser     # 报表 SELECT 语句解析
└── service
    └── impl
        ├── ProcExtractServiceImpl     # 存储过程解析入库
        ├── LineageServiceImpl         # 血缘查询
        ├── ImpactAnalysisServiceImpl  # 影响分析（表级/字段级）
        ├── ReportParseServiceImpl     # 报表解析入库 + Neo4j 同步
        ├── ReportImpactServiceImpl    # 报表影响分析/溯源
        └── Neo4jSyncServiceImpl       # 存储过程→Neo4j 同步
```

### 4.2 REST 接口

| 模块 | 路径前缀 | 主要接口 |
|------|----------|----------|
| 存储过程提取 | `/api/proc` | 单文件/目录批量解析、列表分页、脚本详情 |
| 血缘查询 | `/api/lineage` | 存储过程血缘图、JOIN/WHERE 条件、表上游递归 |
| 影响分析 | `/api/impact` | schema/表/字段选项、analyze、expand、paths、resync |
| 报表解析 | `/api/report` | 报表解析(路径/上传/批量)、列表、详情 |
| 报表影响分析 | `/api/report-impact` | 按表/字段/表头查报表、报表列溯源 |

## 五、数据模型

### 5.1 MySQL（`sql/init.sql` + `sql/report_tables.sql`）

**存储过程侧**：`proc_info`（脚本信息）、`proc_table_dependency`（依赖表）、`table_lineage`（表血缘边）、`table_lineage_filter`（JOIN/WHERE 条件）、`table_field_mapping`（字段映射）、`sql_block_info`（SQL 块）。

**报表侧**：`report_info`（报表）、`report_dataset`（数据集+SQL）、`report_cell_binding`（单元格绑定：cell_pos / header_text / select_alias / source_table / source_field / source_expr）。

### 5.2 Neo4j 图模型

详见 [`docs/neo4j-graph-data-guide.md`](docs/neo4j-graph-data-guide.md)。

**节点**：`Table`、`Column`、`Script`、`Report`、`Dataset`。

**关系**：
- `Column -[:BELONGS_TO]-> Table`
- `Column -[:MAPS_TO {proc_name, source_expr, transformation_type}]-> Column`（字段级血缘，存储过程产出）
- `Table -[:READS {proc_name, operation, join/where_conditions}]-> Table`（表级血缘）
- `Script -[:PRODUCES]-> Table` / `Script -[:CONSUMES]-> Table`
- `Report -[:CONTAINS]-> Dataset`、`Dataset -[:QUERIES]-> Table`、`Dataset -[:REFERENCES {select_alias, cell_refs}]-> Column`

### 5.3 关键标识约定

- `Table.name`：`schema.table` 全小写（如 `cdm.c_agt_deposit_acct_inf`）
- `Column.col_key`：`schema.table.field` 全小写（如 `cdm.c_agt_deposit_acct_inf.acct_id`）
- 临时表识别：`VT_` / `TMP_` 前缀、`_TMP` 后缀（含数字）

## 六、字段级影响分析链路（当前分支重点）

`feature/field-level-lineage` 分支聚焦字段级血缘，核心查询链路：

1. **存储过程同步**（`Neo4jSyncServiceImpl`）：解析 `table_field_mapping`，写 `Column` + `MAPS_TO` 边，按 `source_expr` 分类转换类型（direct / literal / aggregation / derived）。
2. **字段影响分析**（`ImpactAnalysisServiceImpl.analyzeFieldLevel`）：以 `col_key` 为起点，沿 `MAPS_TO` 方向（UPSTREAM/DOWNSTREAM）递归（最大深度受 `impact.analysis.max-depth` 限制，默认 20，接口默认 5）。
3. **报表列溯源**（`ReportImpactServiceImpl.traceReportLineage`）：`report_cell_binding` 取 `source_table + source_field` → 构造字段级 UPSTREAM 分析请求 → 复用 `ImpactAnalysisService.analyze` 返回图谱。

## 七、运行与部署

### 7.1 前置依赖
- JDK 17
- MySQL 8（库 `proc_extractor`，账号 `root/root`）
- Neo4j（`bolt://localhost:7687`，账号 `neo4j/neo4j`）
- Node.js（前端构建）
- Maven 3.8.1（`startup.bat` 内置路径 `D:\anzhuangbao\idea\maven\apache-maven-3.8.1`）

### 7.2 初始化
```bash
# 1. 建库建表
mysql -uroot -proot < sql/init.sql
mysql -uroot -proot < sql/report_tables.sql

# 2. 前端构建（产物输出到 src/main/resources/static，后端静态托管）
cd frontend && npm install && npm run build
```

### 7.3 启动
- **一键启动**（Windows）：`startup.bat`（编译后端 + 启动）
- **手动启动**：`mvn spring-boot:run`

启动后访问：`http://localhost:8080`

### 7.4 配置项（`application.yml`）
- `extractor.ddl.dir`：DDL 文件目录（默认 `d:/HB/work_zuoye/jiaoben`）
- `impact.analysis.default-depth`：影响分析默认深度（5）
- `impact.analysis.max-depth`：最大深度上限（20）
- `impact.sync.auto-retry`：Neo4j 同步失败自动重试（间隔 5 分钟，最多 3 次）

## 八、前端页面

| 路由 | 页面 | 功能 |
|------|------|------|
| `/` | 导入脚本 | 上传/选择脚本文件解析入库 |
| `/procs` | 存储过程列表 | 分页浏览已解析存储过程 |
| `/procs/:name` | 依赖表详情 | 查看依赖表、SQL 块、字段映射 |
| `/procs/:name/lineage` | 血缘图 | 可视化表级血缘图/列表 |
| `/impact` | 影响分析 | 表级/字段级影响图谱、传播链、报表影响 |
| `/report-impact` | 报表影响分析 | 按表/字段/口径/溯源四种查询模式 |

报表影响分析支持四种查询：**表级**（哪些报表用了该表）、**字段级**（哪些报表引用了该字段）、**口径**（按表头汉字反查报表及字段映射）、**溯源**（报表+表头列 → 上游字段级链路图谱）。
