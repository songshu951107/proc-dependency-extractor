# 字段级血缘与 SQL 块详情设计

## 概述

在现有表级血缘基础上，增加字段级数据来源追溯、SQL 块详情展示（关联条件/WHERE/SQL 原文）、脚本内容查看功能。前端增加三个主 Tab：血缘图、字段来源列表、SQL 块详情。

## 背景

当前系统只展示表级血缘（表 A → 表 B），缺失：
1. 目标表各字段的数据来源（来自哪个源表哪个字段）
2. 表间关联条件（JOIN ON / WHERE）的集中展示
3. SQL 原文查看能力

### SQL 写法特点

- `INSERT INTO target SELECT ...`（无显式列列表）
- 临时表 SELECT 使用 `AS alias` 命名字段
- 复杂表达式如 `CASE WHEN ... END AS biz_type`
- DDL 文件可提供目标表字段列表

## 第 1 节：数据存储

### 新增表：`table_field_mapping`

```sql
CREATE TABLE table_field_mapping (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    lineage_id         BIGINT NOT NULL COMMENT '关联 table_lineage.id',
    proc_name          VARCHAR(200) NOT NULL,
    target_field       VARCHAR(200) NOT NULL COMMENT '目标表字段名',
    field_order        INT NOT NULL COMMENT 'SELECT 列序号（从1开始）',
    source_expr        TEXT COMMENT 'SELECT 表达式原文',
    source_alias       VARCHAR(100) COMMENT '表达式主别名（如 T01）',
    source_table_short VARCHAR(200) COMMENT '推断的来源表短名',
    extract_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lineage_id (lineage_id),
    INDEX idx_proc_name (proc_name)
);
```

- `lineage_id` 关联 `table_lineage.id`，一条表级边对应多条字段映射
- `target_field` 来源分两种：非临时表来自 DDL 字段顺序，临时表来自 `AS alias`
- `field_order` 是 SELECT 列序号，用于校验列数匹配
- `source_expr` 保存完整表达式（简单字段或 CASE WHEN）
- `source_alias` 和 `source_table_short` 通过已有 aliasMap 反查

### 新增表：`sql_block_info`

```sql
CREATE TABLE sql_block_info (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    proc_name    VARCHAR(200) NOT NULL,
    block_seq    INT NOT NULL COMMENT 'SQL 块序号',
    block_type   VARCHAR(50) COMMENT 'INSERT_SELECT / CREATE_TEMP / OTHER',
    target_table VARCHAR(200) COMMENT '目标表短名',
    main_table   VARCHAR(200) COMMENT '主表（FROM 后第一个表）短名',
    start_line   INT COMMENT '脚本中起始行号',
    end_line     INT COMMENT '脚本中结束行号',
    sql_content  TEXT COMMENT 'SQL 块原文',
    extract_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_proc_block (proc_name, block_seq),
    INDEX idx_proc_target (proc_name, target_table)
);
```

### 字段映射策略

| target 类型 | 字段名来源 | 示例 |
|------------|-----------|------|
| 非临时表（有 DDL） | DDL 字段顺序 + SELECT 位置对应 | `data_dt ← DATE '{ETL_TX_DT}'` |
| 临时表（无 DDL） | SELECT 的 `AS alias` 推断 | `acct_no ← TXX.liabi_acct_no` |

### 递归追溯

存储时只存直接映射，查询时递归追溯：

```
查询 C_AGT_DEPOSIT_ACCT_INF.acct_no 的来源：
  第1层: C_AGT_DEPOSIT_ACCT_INF.acct_no ← T01.acct_no (位置对应, DDL第2列)
         T01 = VT_C_AGT_DEPOSIT_ACCT_INF_03 (临时表)
  第2层: VT_C_AGT_DEPOSIT_ACCT_INF_03.acct_no ← TXX.liabi_acct_no (AS alias)
         TXX = F_AC_CRBS_DEPOSIT_ACCT (真实源表)
  最终: C_AGT_DEPOSIT_ACCT_INF.acct_no ← F_AC_CRBS_DEPOSIT_ACCT.liabi_acct_no
```

递归终止条件：source_table_short 非 `VT_` 开头（真实源表）。防环：visited 集合记录已访问的 (table, field) 对。

## 第 2 节：后端解析逻辑

### 新增类：`DdlParser`

- 职责：解析 DDL txt 文件，提取字段列表
- DDL 文件查找规则：目标表短名小写 + `_ddl.TXT`
- DDL 目录可配置，默认 `d:/HB/work_zuoye/jiaoben`
- 正则提取 `"field_name"` 行的首个引号内容
- 返回 `List<String>` 字段名列表（按 DDL 顺序）

### 新增类：`FieldMappingExtractor`

- 职责：从 INSERT INTO ... SELECT 语句中提取字段级映射
- 输入：SQL 块原文、target 表名、aliasMap、DDL 字段列表（可选）
- 输出：`List<FieldMapping>`

#### SELECT 列表拆分算法（核心）

```
1. 定位 SELECT 到 FROM 之间的文本
2. 遍历字符，跟踪括号深度：
   - 遇到 ( 深度+1，遇到 ) 深度-1
   - 深度=0 时的逗号 → 列分隔符
   - 深度>0 时的逗号 → 列内逗号（不拆分）
3. 每列 strip 后得到表达式列表
```

#### target_field 确定

| 条件 | target_field 来源 |
|------|------------------|
| 有 DDL 字段列表 | `ddlFields.get(order)` 按位置取 |
| 无 DDL（临时表）且列有 `AS alias` | 取 alias |
| 无 DDL 且列是 `T01.acct_no` | 取 `acct_no` |
| 无 DDL 且列是表达式（无别名） | 取 `expr_{order}` 占位 |

#### source_alias 提取

从每列表达式中提取第一个 `别名.字段` 模式，通过 aliasMap 反查源表短名。

### `SqlBlockExtractor` 增强

现有 `SqlBlockExtractor` 拆分 SQL 块时，新增记录每个块的 `startLine`/`endLine`（脚本中的行号范围），并保存 `sql_content`（块原文）。

### `LineageExtractor.parseBlock` 增强

在现有流程（识别 target → 提取 source → 构建 edge → 提取 JOIN/WHERE filter）后新增第 5 步：

```
5. 如果 operation 是 INSERT SELECT 或 CREATE TEMP:
   a. 提取 SELECT 列表（按括号深度拆分）
   b. 判断 target 是否有 DDL:
      - 有 DDL → DdlParser 解析字段列表，按位置对应
      - 无 DDL → 从 AS alias 推断
   c. 构建 List<FieldMapping>，附到 LineageParseResult
```

同时记录 main_table（FROM 后第一个表）到 `LineageEdge`。

### 数据结构变更

`LineageParseResult` 新增字段：
```java
private List<FieldMapping> fieldMappings = new ArrayList<>();
```

`LineageEdge` 新增字段：
```java
private String mainTable;  // FROM 后的主表短名
```

`FieldMapping`（新增 DTO）：
```java
private int edgeIndex;           // 关联 edges 下标，入库时转 lineage_id
private String targetField;
private int fieldOrder;
private String sourceExpr;
private String sourceAlias;
private String sourceTableShort;
```

### 入库变更（`ProcExtractServiceImpl.saveMetadata`）

1. 在现有 edge 入库后，遍历 fieldMappings，用 edgeIndex 取对应 lineage_id，批量插入 `table_field_mapping`
2. 删旧数据时同步删除 `table_field_mapping`（已有逻辑删 table_lineage，补充删 table_field_mapping）
3. 新增 `sql_block_info` 入库：每个 SQL 块一条记录，删旧数据时同步删除

## 第 3 节：API 接口

### 新增接口 1：字段映射（含递归追溯）

```
GET /api/lineage/{procName}/field-mapping?tableName=X&recursive=true

路径参数:
  procName: 存储过程名
查询参数:
  tableName: 目标表短名（必填）
  recursive: 是否递归追溯（可选，默认 false）

返回: Result<FieldMappingVO>
```

返回结构：
```json
{
  "code": 200,
  "data": {
    "targetTable": "C_AGT_DEPOSIT_ACCT_INF",
    "targetTableType": "TARGET",
    "fields": [
      {
        "targetField": "acct_no",
        "fieldOrder": 2,
        "directMappings": [
          {
            "sourceTable": "VT_C_AGT_DEPOSIT_ACCT_INF_03",
            "sourceTableType": "TEMP",
            "sourceExpr": "T01.acct_no",
            "sourceAlias": "T01"
          }
        ],
        "ultimateSource": {
          "sourceTable": "F_AC_CRBS_DEPOSIT_ACCT",
          "sourceTableType": "SOURCE",
          "sourceField": "liabi_acct_no",
          "path": [
            {"table": "C_AGT_DEPOSIT_ACCT_INF", "field": "acct_no"},
            {"table": "VT_C_AGT_DEPOSIT_ACCT_INF_03", "field": "acct_no"},
            {"table": "F_AC_CRBS_DEPOSIT_ACCT", "field": "liabi_acct_no"}
          ]
        }
      }
    ],
    "unmappedFields": ["data_sourc_tab_name"]
  }
}
```

### 新增接口 2：SQL 块详情

```
GET /api/lineage/{procName}/sql-blocks

返回: Result<List<SqlBlockVO>>
```

返回结构：
```json
{
  "code": 200,
  "data": [
    {
      "blockSeq": 8,
      "blockType": "INSERT_SELECT",
      "targetTable": "VT_C_AGT_DEPOSIT_ACCT_INF_03",
      "targetTableType": "TEMP",
      "mainTable": "F_AC_CRBS_DEPOSIT_ACCT",
      "mainTableType": "SOURCE",
      "startLine": 150,
      "endLine": 190,
      "sqlContent": "INSERT INTO ... SELECT ... FROM ... LEFT JOIN ...",
      "joinTables": [
        {
          "tableName": "F_AC_CRBS_CUST_ACCT_NO_COMPAR_RELA",
          "tableType": "SOURCE",
          "alias": "TCA",
          "joinType": "LEFT JOIN",
          "conditions": "TXX.liabi_acct_no = TCA.sys_acct_no AND ..."
        }
      ],
      "whereConditions": "TXX.end_dt > DATE '{ETL_TX_DT}' AND ...",
      "filters": [ ... ]
    }
  ]
}
```

### 新增接口 3：脚本内容

```
GET /api/proc/{procName}/script

返回: Result<ScriptVO>
```

返回结构：
```json
{
  "code": 200,
  "data": {
    "procName": "dwa_c_agt_deposit_acct_inf",
    "fileName": "dwa_c_agt_deposit_acct_inf.py",
    "content": "脚本全文...",
    "sqlBlocks": [
      { "seq": 1, "startLine": 10, "endLine": 50 },
      { "seq": 6, "startLine": 100, "endLine": 120 }
    ]
  }
}
```

### 现有接口变更

`GET /api/lineage/{procName}` 的返回 VO（`LineageGraphVO.Edge`）新增：
```java
private List<FieldMappingBrief> fieldMappings;  // 字段映射概要
private String mainTable;                        // 主表短名

// FieldMappingBrief:
private String targetField;
private String sourceExpr;
private String sourceTableShort;
```

`LineageGraphVO.FilterDetail` 新增：
```java
private List<String> involvedTables;  // 涉及的表名
```

## 第 4 节：前端展示

### 4.1 主 Tab 结构

LineageView.vue 顶部三个主 Tab：
1. **血缘图** — 现有 G6 图，不变
2. **字段来源列表** — 字段级树形展开列表
3. **SQL 块详情** — 按 SQL 块分子 Tab

### 4.2 字段来源列表 Tab

以目标表字段为主视角的树形展开列表。

**列结构：** 展开图标 / 目标字段 / 来源表达式 / 来源表 / 来源字段

**展开规则：**
- 来源表是临时表（`VT_` 开头）→ 显示 ▶ 可展开，点击后懒加载该临时表对应字段的来源行（缩进显示）
- 来源表是源表（非 `VT_`）→ 不可展开，终点
- 来源是常量 → 不可展开，标记"常量"

**交互：**
- 懒加载展开：点击临时表行时，调用 `GET /api/lineage/{procName}/field-mapping?tableName={tempTable}` 获取该临时表的字段映射，动态插入子行
- 多层缩进：每展开一层缩进增加，来源表 tag 颜色区分 TEMP/SOURCE
- 顶部下拉选择目标表（默认选最终目标表，如 `C_AGT_DEPOSIT_ACCT_INF`）

### 4.3 SQL 块详情 Tab

**子 Tab 命名：** 用目标表名（如 `VT_C_AGT_DEPOSIT_ACCT_INF_03`、`C_AGT_DEPOSIT_ACCT_INF`）

**子 Tab 标识：** 目标表名 + 类型标记（TEMP 橙色 / TARGET 红色）

**每个子 Tab 内展示：**

| 区域 | 内容 |
|------|------|
| 目标表 | INSERT INTO 的目标表，显示类型 tag |
| 主表 | FROM 后的主表 |
| 关联表列表 | 每个 JOIN 表一行：序号 / 表名+类型 / ON 条件原文 |
| WHERE 条件 | 独立区域展示过滤条件原文 |
| SQL 片段 | 默认显示预览（截断），点击展开看完整原文 |
| 操作按钮 | "查看字段映射"（跳转到字段来源列表 Tab 并选中该目标表） |

**跳转逻辑：**
- 点击子 Tab 内的**来源表**临时表名（如关联表中的 `VT_C_AGT_DEPOSIT_ACCT_INF_03`）→ 跳转到该临时表作为**目标表**的子 Tab
- 跳转时自动切换到对应子 Tab 并滚动到顶部

### 4.4 边详情面板增强

现有 `LineageDetailPanel.vue` 增加区域：
- **字段映射**（新增）：字段列表，每行显示 `target_field ← source_table.source_expr`
- **递归追溯**（新增）：点击某字段可展开追溯链路
- **关联条件**：现有，增加涉及的表名标注

### 4.5 脚本内容弹窗

**触发入口：**
1. 存储过程列表页 — 点击脚本名称 → 弹窗（仅查看）
2. 血缘图/列表页 — 点击"查看脚本"按钮 → 弹窗（高亮对应 SQL 块）

**弹窗设计：**
- `el-dialog` 全屏弹窗（宽 80%，高 80%）
- 使用 `<pre>` 展示脚本原文，带行号
- 点击边或列表行时，传入 `sqlBlockSeq`，弹窗自动滚动到对应 SQL 块并高亮背景
- 弹窗标题显示脚本文件名

### 4.6 独立血缘列表页

新增 `LineageListView.vue`，从存储过程列表页入口进入：
- 路由：`/procs/:procName/lineage/list`
- 顶部：存储过程名、统计信息
- 主体：与 4.3 相同的 SQL 块子 Tab 详情

### 4.7 前后端字段不匹配修复

修复 `LineageView.vue` 的 `transformToG6` 读取 `e.joinConditions`、`e.whereConditions` 等不存在字段，改为使用 `e.filters`。

### 4.8 路由变更

```
/procs/:procName/lineage      → 血缘图视图（增加 Tab 切换）
/procs/:procName/lineage/list → 独立列表页（SQL 块详情）
```

## 第 5 节：错误处理

- DDL 文件不存在时：target_field 从 `AS alias` 推断（降级为临时表策略），记录警告日志
- SELECT 列数与 DDL 字段数不匹配时：多出的 SELECT 列用 `expr_{order}` 占位，DDL 多出的字段放入 `unmappedFields`
- 递归追溯遇环时：终止该路径，返回已追溯的部分路径
- 递归追溯无记录时：`ultimateSource` 返回 null

## 第 6 节：测试

- `FieldMappingExtractor` 单元测试：简单字段、CASE WHEN、无别名、列数不匹配
- `DdlParser` 单元测试：正常 DDL、文件不存在
- 递归追溯测试：单层、多层、环、无记录
- `SqlBlockExtractor` 行号记录测试
- API 集成测试：字段映射接口、SQL 块详情接口、脚本内容接口
