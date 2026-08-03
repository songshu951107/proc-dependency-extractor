# 报表影响分析功能设计规格

> 基于现有存储过程血缘系统，扩展支持帆软报表（.cpt/.frm/.xml）的影响分析。

## 目标

解析帆软报表文件中的数据集 SQL 和单元格绑定，建立"报表→数据集→表→字段"的完整字段链路，支持按 schema/表名/字段名查询影响的报表，以及字段口径溯源。

## 已确认决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 文件类型 | .cpt / .frm / .xml | 初期本地文件，.frm 按 .cpt 逻辑解析 |
| 数据集类型 | SQL 查询数据集 | 不含存储过程/内嵌数据集 |
| 字段映射粒度 | 完整字段链路 | 单元格→数据集列→SQL别名→源表字段 |
| 图模型 | 新增 Report + Dataset 节点 | 与现有 Table/Column 节点直接关联 |
| SQL 解析 | 新增 ReportSqlParser 专用解析器 | 不改现有存储过程解析器 |
| 查询场景 | 5 个场景全支持 | 表级/字段级/口径/详情/跨链 |
| 前端布局 | 独立 /report-impact 页 | 不影响现有影响分析页 |

---

## 一、图数据模型

### 新增节点

#### Report 节点

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `file` | string | **唯一标识**，报表文件名 | `公司类存款明细表.cpt` |
| `name` | string | 报表名称（去扩展名） | `公司类存款明细表` |
| `type` | string | 文件类型 | `cpt` / `frm` / `xml` |
| `description` | string | 报表描述 | `存款明细` |

唯一约束：
```cypher
CREATE CONSTRAINT report_file_unique IF NOT EXISTS FOR (r:Report) REQUIRE r.file IS UNIQUE
```

#### Dataset 节点

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `key` | string | **唯一标识**，report_file + "#" + dataset_name | `公司类存款明细表.cpt#ds1` |
| `name` | string | 数据集名称 | `ds1` |
| `connection` | string | 数据源连接名 | `139.0.10.11_rpt(gbase8A)` |
| `sql` | string | 查询语句 | `select a.STAT_DT ... from RPT.X a` |

唯一约束：
```cypher
CREATE CONSTRAINT dataset_key_unique IF NOT EXISTS FOR (d:Dataset) REQUIRE d.key IS UNIQUE
```

### 新增关系

| 关系 | 方向 | 属性 | 说明 |
|------|------|------|------|
| `CONTAINS` | Report→Dataset | 无 | 报表包含数据集 |
| `QUERIES` | Dataset→Table | `alias`：SQL中表别名（如 a/b） | 数据集查询表 |
| `REFERENCES` | Dataset→Column | `select_alias`：select输出列名（如 STAT_DT/a2）<br>`source_expr`：源表达式（如 a.GL_NUM）<br>`cell_refs`：单元格位置JSON数组（如 `["A3","B3"]`） | 数据集引用字段 |

### MERGE 语句

```cypher
// Report 节点
MERGE (r:Report {file: $file})
SET r.name = $name, r.type = $type, r.description = $description

// Dataset 节点
MERGE (d:Dataset {key: $key})
SET d.name = $name, d.connection = $connection, d.sql = $sql

// CONTAINS
MATCH (r:Report {file: $file}), (d:Dataset {key: $key})
MERGE (r)-[:CONTAINS]->(d)

// QUERIES
MATCH (d:Dataset {key: $key}), (t:Table {name: $tableName})
MERGE (d)-[q:QUERIES {alias: $alias}]->(t)

// REFERENCES
MATCH (d:Dataset {key: $key}), (c:Column {col_key: $colKey})
MERGE (d)-[ref:REFERENCES {select_alias: $selectAlias}]->(c)
SET ref.source_expr = $sourceExpr, ref.cell_refs = $cellRefs
```

### 与现有模型的关系

Report/Dataset 直接引用现有 Table/Column 节点。存储过程血缘的 Table 节点同时被 Script(READS) 和 Dataset(QUERIES) 引用，实现跨链查询。

---

## 二、MySQL 存储与解析器

### MySQL 表设计（3 张表）

#### report_info

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 主键 |
| file | varchar(255) UNIQUE | 文件名 |
| name | varchar(255) | 报表名 |
| type | varchar(20) | cpt/frm/xml |
| description | varchar(500) | 描述 |
| parse_time | datetime | 解析时间 |
| dataset_count | int | 数据集数 |
| cell_count | int | 单元格绑定数 |
| parse_status | varchar(20) | SUCCESS/PARTIAL/FAILED |

#### report_dataset

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 主键 |
| report_file | varchar(255) | 所属报表文件 |
| dataset_name | varchar(100) | 数据集名（ds1） |
| connection | varchar(255) | 数据源连接名 |
| sql_text | text | SQL 语句 |
| table_count | int | 涉及表数 |
| create_time | datetime | 创建时间 |

#### report_cell_binding

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 主键 |
| report_file | varchar(255) | 所属报表文件 |
| dataset_name | varchar(100) | 数据集名 |
| cell_pos | varchar(20) | 单元格位置（A3） |
| column_name | varchar(100) | 数据集列名（STAT_DT） |
| select_alias | varchar(100) | SQL select 别名 |
| source_table | varchar(255) | 源表全名（小写） |
| source_field | varchar(100) | 源字段名 |
| source_expr | text | 源表达式原文 |

说明：cell_binding 表存"单元格→数据集列→源表字段"完整链路，一条记录即一个绑定关系。source_expr 含 trim/nvl/CASE 等表达式原文。

### ReportSqlParser 解析流程

新增 `com.hb.extractor.parser.ReportSqlParser`，输入 SQL 字符串，输出：
- `List<TableRef>`：schema.table + alias（FROM/JOIN 提取）
- `List<ColumnMapping>`：selectAlias + sourceExpr + sourceTable + sourceField

解析步骤：
1. **预处理**：剥离 `${if(...)}` 为占位符 `__PARAM__`，去除 `--` 和 `/* */` 注释
2. **表提取**：正则 `FROM/JOIN\s+(\w+)\.(\w+)\s+(\w+)?` 提取 schema.table + alias，构建 alias→table 映射
3. **SELECT 列拆分**：按逗号拆分 SELECT 到 FROM 之间内容（注意括号层级，避免拆分函数参数内的逗号）
4. **列解析**（每列）：
   - 有 AS 别名 → selectAlias = 别名
   - 无 AS 但末尾有空格别名（如 `nvl(a.CURR_BAL,0) CURR_BAL`）→ selectAlias = 末尾标识符
   - 无别名 → selectAlias = alias.field 的 field 部分，或 expr_{序号}
   - sourceExpr = 列原文（去别名）
   - sourceTable/sourceField = 从 sourceExpr 中提取 `alias.field`，查 alias→table 映射得 sourceTable

### CptXmlParser XML 解析

新增 `com.hb.extractor.parser.CptXmlParser`，用 JDK DOM 解析 XML：
- 解析 `TableDataMap/TableData` → 数据集（name + Connection/DatabaseName + Query）
- 解析 `CellElementList/C` → 单元格（c=列号 r=行号）
- 识别 `<O t="DSColumn"><Attributes dsName="ds1" columnName="X"/>` → 单元格绑定
- 输出：ReportMetadata（数据集列表 + 单元格绑定列表）

.frm 格式：初期按 .cpt 相同逻辑解析（frm 也是 XML，TableDataMap 结构一致），如有差异后续适配。

---

## 三、后端服务与 API

### 服务层

#### ReportParseService

解析报表文件 → 存 MySQL + 同步 Neo4j：
- `parseAndSave(String filePath)`：单文件解析
- `parseBatch(String dirPath)`：目录批量解析
- `reParseAll()`：重新解析全部
- `deleteReport(String file)`：删除报表（级联 MySQL + Neo4j）

Neo4j 同步：复用 Neo4jSyncService 模式，MERGE 写入 Report/Dataset 节点和关系；删除旧数据用 file 作为键。失败记录到 neo4j_sync_log（复用现有表，proc_name 字段存 report_file）。

#### ReportImpactService

影响分析查询（Cypher）：
- `findReportsByTable(schema, table)`：场景1 表级
- `findReportsByField(schema, table, field)`：场景2 字段级
- `traceFieldSource(reportFile, datasetName, columnName)`：场景3 口径
- `getReportDetail(reportFile)`：场景4 报表详情
- `analyzeCrossImpact(schema, table, field)`：场景5 跨链（存储过程+报表）

### API 端点

新增 `ReportController`（/api/report）和 `ReportImpactController`（/api/report-impact）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/report/parse` | 单文件解析（body: {path}） |
| POST | `/api/report/parse/batch` | 目录批量解析（body: {path}） |
| POST | `/api/report/parse/all` | 重新解析全部报表 |
| GET | `/api/report/list?pageNum=1&pageSize=10&name=xxx` | 报表列表（分页，name 可选筛选） |
| GET | `/api/report/{file}` | 场景4：报表详情（数据集+单元格绑定） |
| DELETE | `/api/report/{file}` | 删除报表 |
| GET | `/api/report-impact/by-table` | 场景1：表级查询（schema, table） |
| GET | `/api/report-impact/by-field` | 场景2：字段级查询（schema, table, field） |
| GET | `/api/report-impact/field-source` | 场景3：口径查询（reportFile, datasetName, columnName） |
| GET | `/api/report-impact/cross-impact` | 场景5：跨链影响（schema, table, field?） |

### 关键 Cypher 查询

**场景1 表级：**
```cypher
MATCH (t:Table {name: $table})<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report)
RETURN r.file, r.name, d.name, d.connection, d.sql
```

**场景2 字段级（含单元格）：**
```cypher
MATCH (c:Column {col_key: $colKey})<-[ref:REFERENCES]-(d:Dataset)<-[:CONTAINS]-(r:Report)
RETURN r.file, r.name, d.name, ref.select_alias, ref.source_expr, ref.cell_refs
```

**场景3 口径（数据集列→源字段）：**
```cypher
MATCH (d:Dataset {key: $datasetKey})-[ref:REFERENCES]->(c:Column)-[:BELONGS_TO]->(t:Table)
WHERE ref.select_alias = $columnName
RETURN t.name AS sourceTable, c.name AS sourceField,
       ref.source_expr AS expr, d.connection AS db
```

**场景5 跨链（表被存储过程+报表共同引用）：**
```cypher
MATCH (t:Table {name: $table})
OPTIONAL MATCH (t)<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report)
OPTIONAL MATCH (t)-[reads:READS]->(downstream:Table)
RETURN t, d, r, reads, downstream
```

---

## 四、前端页面与组件

### 路由

新增路由 `/report-impact`，对应 `ReportImpactView.vue`，菜单新增"报表影响分析"入口。

### 页面布局

```
┌─────────────────────────────────────────────────┐
│ 筛选区：查询类型[表级|字段级|口径|跨链]            │
│ + schema下拉 + 表名下拉 + 字段名输入 + 查询按钮    │
├──────────────────┬──────────────────────────────┤
│ 左侧：报表清单     │ 右侧：字段映射详情             │
│ 卡片列表          │ 表格：单元格→select别名→       │
│ 点击展开数据集树   │ 源表.字段→源表达式            │
├──────────────────┴──────────────────────────────┤
│ 底部：口径/跨链视图（查询类型为口径或跨链时显示）    │
│ 口径：字段来源链路图                              │
│ 跨链：Vue Flow 图，Table 连接 Script 和 Dataset   │
└─────────────────────────────────────────────────┘
```

### 组件清单

| 组件 | 职责 |
|------|------|
| `ReportImpactFilter.vue` | 筛选区：查询类型切换 + schema/表/字段输入 |
| `ReportList.vue` | 左侧报表清单卡片列表 + 展开数据集树 |
| `FieldMappingTable.vue` | 右侧字段映射表格（单元格/select别名/源表/源字段/表达式） |
| `FieldSourceGraph.vue` | 口径查询的来源链路图（Vue Flow） |
| `CrossImpactGraph.vue` | 跨链影响图（复用 ImpactFlowGraph 思路，节点含 Report/Dataset/Table） |

schema/表名下拉复用现有 `/api/impact/schemas` 和 `/api/impact/tables` 接口，保持一致。

---

## 五、数据流、错误处理与测试

### 解析与同步数据流

单文件解析流程：
1. 用户调用 `POST /api/report/parse` 传文件路径
2. `CptXmlParser` 解析 XML → 提取数据集(name/connection/sql) + 单元格绑定(cell/dsName/columnName)
3. `ReportSqlParser` 解析每个数据集 SQL → 提取 TableRef列表 + ColumnMapping列表
4. 合并 XML 单元格绑定与 SQL 字段映射：通过 dsName+columnName 关联，填充 sourceTable/sourceField/sourceExpr
5. MySQL 事务写入：先按 report_file 删除旧记录，再插入 report_info / report_dataset / report_cell_binding
6. Neo4j 同步（MySQL 提交后）：MERGE Report/Dataset 节点 + CONTAINS/QUERIES/REFERENCES 关系；删除旧数据用 file 作为键
7. 失败记录到 neo4j_sync_log（复用现有表，proc_name 字段存 report_file）

### 错误处理

- **XML 解析失败**：记录文件名+错误日志，跳过该文件，不影响批量解析其他文件
- **SQL 解析失败**（如 ${if()} 残留、语法异常）：降级为表级映射（仅存 QUERIES 关系，不存 REFERENCES），标记 parse_status=PARTIAL
- **单元格无 DSColumn 绑定**（纯文本/公式单元格）：跳过，不生成 cell_binding 记录
- **Neo4j 同步失败**：不影响 MySQL 主流程，记录 neo4j_sync_log，支持手动重试（复用现有 /api/impact/resync 机制）
- **字段映射匹配失败**（SQL 别名无法对应源表字段）：sourceTable/sourceField 留空，sourceExpr 存原文，前端展示"未解析"

### 测试策略

**单元测试：**

`ReportSqlParserTest`（用 reports/ 下的真实 SQL 测试）：
- testExtractTables：验证 FROM/JOIN 提取 RPT.A_CFIN_DEPT_ACCT_INFO(alias=a) + FDM.F_AC_CRBS_CUST_ACCT_NO(alias=b)
- testSelectAliasAs：验证 `trim(a.GL_NUM) as a2` → selectAlias=a2, sourceField=GL_NUM
- testSelectAliasSpace：验证 `nvl(a.CURR_BAL,0) CURR_BAL` → selectAlias=CURR_BAL
- testSelectAliasField：验证 `a.STAT_DT` → selectAlias=STAT_DT, sourceExpr=a.STAT_DT
- testParamPlaceholder：验证 ${if(...)} 被替换为占位符不影响解析
- testCommentStripping：验证 -- 注释和 /* */ 注释被去除

`CptXmlParserTest`（用 公司类存款明细表.cpt 测试）：
- testExtractDatasets：验证 3 个数据集 ds1/ds2/ds3 提取
- testExtractCellBindings：验证 r=2 行的 DSColumn 单元格绑定
- testConnectionName：验证数据源连接名 139.0.10.11_rpt(gbase8A)

**集成测试：**

`ReportImpactServiceTest`：Neo4j 查询验证（场景1-5 各一个用例）

**端到端验证：**
- 解析 reports/ 下 2 个文件 → MySQL + Neo4j 数据正确
- 前端查询 RPT.A_CFIN_DEPT_ACCT_INFO → 显示公司类存款明细表
- 口径查询 ds1.STAT_DT → 显示来源 RPT.A_CFIN_DEPT_ACCT_INFO.STAT_DT

### 约束

- Maven 路径：`D:\HB\maven\bin\mvn.cmd`
- 前端构建后需重启后端
- Neo4j 节点/关系写入用 MERGE（幂等）
- Table/Column 节点复用现有，不重复创建
- 解析器单文件失败不影响批量其他文件
