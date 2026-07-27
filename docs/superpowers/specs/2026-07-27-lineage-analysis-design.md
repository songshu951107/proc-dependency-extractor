# 血缘分析功能设计文档

| 项 | 内容 |
|---|---|
| 项目 | proc-dependency-extractor（存储过程依赖表名提取系统） |
| 主题 | 增加血缘分析功能 |
| 日期 | 2026-07-27 |
| 状态 | 待审查 |

---

## 1. 背景与目标

### 1.1 现状

系统已实现 Python 存储过程脚本依赖表名提取，支持：
- 脚本名称、功能说明、更新对象
- 依赖表清单（去重，含 schema 信息）
- 临时表标记（VT_ 前缀）
- 单文件/批量/上传/全量重解析

数据存储在 `proc_info` 和 `proc_table_dependency` 两张表，前端提供导入、列表、详情三个页面。

### 1.2 问题

当前数据模型存在**血缘断裂**：

- 只记录「存储过程 → 依赖表」的扁平关系
- 临时表被标记为 `is_temp_table=1` 但**不计入 table_count**，被边缘化
- 无法回答「a 如何影响 target」这类链式追溯问题
- 实际脚本中存在大量 `a → 临时表c → target` 的链路，但当前模型无法表达

以 `dwa_c_agt_deposit_acct_inf.py` 为例，其中包含 7 处子查询，每个子查询内都有真实数据源表，当前模型全部丢失。

### 1.3 目标

在现有系统上**最小侵入扩展**血缘分析能力，包括：
- 脚本名称、更新对象（已有，复用）
- 依赖哪些表（已有，复用）
- **表间关联关系**（JOIN 字段）— 新增
- **筛选条件**（WHERE 子句）— 新增
- **完整链式血缘**（a → 临时表c → target）— 新增
- **交互式可视化**（AntV G6）— 新增

### 1.4 关键决策

| 决策项 | 结论 | 理由 |
|---|---|---|
| 血缘范围 | 完整血缘（纵向流向 + 横向 JOIN） | 单一方向无法回答完整血缘问题 |
| 筛选条件存储 | 原文 + 字段列表 | 原文保证可读，字段列表支持检索 |
| 技术栈 | AntV G6 + MySQL | 当前规模 MySQL 递归 CTE 够用，无需引入 Neo4j |
| 可视化深度 | 交互式图 | 链式血缘信息密度高，静态图承载不了 |
| 数据模型 | 新增 `table_lineage` + `table_lineage_filter` 双写并存 | 不破坏现有 `proc_table_dependency` 兼容性 |
| 子查询解析 | 第一版必须支持 | 实际脚本中子查询承载真实数据源，不解析会大量断裂 |

---

## 2. 架构设计

### 2.1 设计原则

- **最小侵入扩展**：不重构既有 `proc_table_dependency` 流程，新增血缘模块并行工作
- **双写并存**：解析时同时写入扁平依赖表和派生链路表，向后兼容
- **服务隔离**：血缘查询服务独立，只读不写
- **入口解耦**：查询血缘走独立 Controller

### 2.2 组件分层

```
┌─────────────────────────────────────────────────┐
│ 前端 Vue3 + AntV G6                              │
│  ├─ ProcListView     （原有，列表+跳转）          │
│  ├─ ProcDetailView   （原有，依赖表清单）          │
│  └─ LineageView（新） 交互式血缘图                │
└────────────────────┬────────────────────────────┘
                     │ HTTP
┌────────────────────▼────────────────────────────┐
│ Controller 层                                    │
│  ├─ ProcExtractController（原有）                 │
│  └─ LineageController（新）                      │
│     GET /api/lineage/{procName}                  │
│     GET /api/lineage/table/{tableName}/upstream  │
│     GET /api/lineage/table/{tableName}/downstream│
│     GET /api/lineage/{procName}/filter           │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ Service 层                                       │
│  ├─ ProcExtractService（原有，不变）              │
│  └─ LineageService（新）                         │
│     ├─ 查询血缘图数据（节点+边）                  │
│     └─ 递归 CTE 多跳追溯                          │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 解析器层（升级）                                  │
│  ├─ ProcHeaderParser     （不变）                 │
│  ├─ SchemaVarResolver    （不变）                 │
│  ├─ SqlBlockExtractor    （新增 extractBlocks）   │
│  ├─ SqlTableExtractor    （不变，提依赖表）        │
│  └─ LineageExtractor（新）按 SQL 块顺序提派生关系 │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 数据层                                           │
│  ├─ proc_info              （原有，不变）         │
│  ├─ proc_table_dependency  （原有，不变）         │
│  ├─ table_lineage（新）    表间派生关系           │
│  └─ table_lineage_filter（新）JOIN/WHERE 详情    │
└─────────────────────────────────────────────────┘
```

### 2.3 与现有代码的关系

| 现有组件 | 改动 |
|---|---|
| `ProcExtractServiceImpl.extractAndSave` | 末尾追加调用 `LineageExtractor.extract` + 入库 |
| `SqlBlockExtractor` | 新增 `extractBlocks` 方法返回 `List<String>` |
| `SqlTableExtractor` | 不改 |
| `proc_info` / `proc_table_dependency` | 不改（向后兼容） |
| 前端 `ProcDetailView` | 新增「查看血缘图」按钮跳转 |
| `DELETE /api/proc/{procName}` | 删除时级联删除 `table_lineage` 和 `table_lineage_filter` |

---

## 3. 数据模型

### 3.1 新增表：`table_lineage`（表间派生关系）

```sql
CREATE TABLE `table_lineage` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_id`         BIGINT       NOT NULL COMMENT '关联proc_info.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `source_table`    VARCHAR(300) NOT NULL COMMENT '派生源表(完整名schema.table)',
    `source_table_short` VARCHAR(200) NOT NULL COMMENT '源表短名(去schema)',
    `target_table`    VARCHAR(300) NOT NULL COMMENT '派生目标表(完整名)',
    `target_table_short` VARCHAR(200) NOT NULL COMMENT '目标表短名',
    `target_is_temp`  TINYINT      NOT NULL DEFAULT 0 COMMENT '目标是否临时表(VT_前缀):0否1是',
    `operation`       VARCHAR(30)  NOT NULL COMMENT '派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM',
    `sql_block_seq`   INT          NOT NULL COMMENT 'SQL块序号(从1开始,标识派生顺序)',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_proc_id` (`proc_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_source` (`source_table_short`),
    KEY `idx_target` (`target_table_short`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表间派生血缘关系';
```

**设计要点：**
- `source_table` / `target_table` 同时存完整名和短名：完整名用于追溯，短名用于 G6 节点显示
- `target_is_temp` 标记目标是否临时表：临时表是「中间节点」，需要在图中显示但不能算最终依赖
- `sql_block_seq` 记录派生顺序：解决「同一临时表被多次写入」的场景，G6 可按序绘制

### 3.2 新增表：`table_lineage_filter`（JOIN/WHERE 详情）

```sql
CREATE TABLE `table_lineage_filter` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `lineage_id`      BIGINT       NOT NULL COMMENT '关联table_lineage.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `filter_type`     VARCHAR(10)  NOT NULL COMMENT '条件类型:JOIN/WHERE',
    `filter_clause`   TEXT         NOT NULL COMMENT '条件原文(如a.cust_id=b.id)',
    `fields_involved` VARCHAR(500) DEFAULT NULL COMMENT '涉及字段JSON数组(如["a.cust_id","b.id"])',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘条件详情(JOIN/WHERE)';
```

**设计要点：**
- `filter_type` 区分 JOIN（横向关联）和 WHERE（筛选条件）
- `filter_clause` 存原文：用户能直接读懂，复杂表达式也不会解析失败
- `fields_involved` 存 JSON 数组：可按字段检索「哪些血缘用了 a.sts」
- `lineage_id` 关联到具体某条派生关系

### 3.3 节点与边的映射

G6 渲染时：
- **节点** = `table_lineage` 中去重的所有 `source_table_short` + `target_table_short`
- **边** = `table_lineage` 每一行（source → target）
- **边标注** = `table_lineage_filter` 中关联到该边的 JOIN/WHERE 文本
- **节点颜色**：
  - 绿色（SOURCE）：在 `source_table` 中出现但不在 `target_table` 中
  - 黄色（TEMP）：`target_is_temp=1` 或表名以 `VT_` 开头
  - 红色（TARGET）：等于 `update_target`，或不在任何 `source_table` 中

### 3.4 多跳追溯的递归 CTE

```sql
-- 查询 target_table 的所有上游（递归到任意深度）
WITH RECURSIVE upstream AS (
    SELECT source_table_short AS tbl, 1 AS depth, id
    FROM table_lineage WHERE target_table_short = 'TARGET_TABLE'
    UNION ALL
    SELECT t.source_table_short, u.depth + 1, t.id
    FROM table_lineage t
    JOIN upstream u ON t.target_table_short = u.tbl
    WHERE u.depth < 10  -- 防止无限循环
)
SELECT DISTINCT tbl FROM upstream;
```

### 3.5 入库策略

与现有 `proc_table_dependency` 保持一致：
- 解析时**事务内先按 `proc_name` 删除两表旧记录，再插入新记录**
- 单文件解析、批量解析、重新解析全走同一入口

### 3.6 字段命名约定

- `*_short`：去掉 schema 前缀的短表名（`F_AC_CRBS_CUST_ACCT_NO`）
- `*_table`：完整表名（`FDM.F_AC_CRBS_CUST_ACCT_NO`）
- 临时表的 `target_table` 也存完整名（如 `VT_TMP_CUST`，无 schema）

---

## 4. 解析器设计

### 4.1 新增组件：`LineageExtractor`

**职责**：按 SQL 块顺序提取表间派生关系，输出 `List<LineageEdge>` + `List<LineageFilter>`。

**位置**：`com.hb.extractor.parser.LineageExtractor`

```java
@Component
public class LineageExtractor {

    @Autowired
    private SchemaVarResolver schemaVarResolver;

    /**
     * 按顺序解析所有 SQL 块，提取派生关系
     * @param sqlBlocks 顺序切分的 SQL 块（来自 SqlBlockExtractor）
     * @param schemaVarMap schema 变量映射
     * @param updateTarget 头部声明的更新目标
     * @return 派生关系列表（每块可能产出多条）
     */
    public LineageParseResult extract(
            List<String> sqlBlocks,
            Map<String, String> schemaVarMap,
            String updateTarget) {
        // ...
    }
}
```

### 4.2 解析逻辑：按 SQL 块类型识别派生关系

每个 SQL 块按其首关键字归类，提取对应的 source → target：

| SQL 块类型 | 识别方式 | 派生关系 |
|---|---|---|
| `CREATE TEMP TABLE VT_x AS SELECT ... FROM a JOIN b` | `CREATE\s+(TEMP|TEMPORARY)` | `a → VT_x`、`b → VT_x` |
| `INSERT INTO target SELECT ... FROM a JOIN VT_x` | `INSERT\s+INTO` | `a → target`、`VT_x → target` |
| `MERGE INTO target USING src ON ...` | `MERGE\s+INTO` | `src → target` |
| `UPDATE target SET ... FROM a` | `UPDATE\s+\w+.*\bFROM\b` | `a → target` |
| `DELETE FROM target USING a` | `DELETE\s+FROM` | `a → target` |

**核心规则**：`INTO/UPDATE/MERGE INTO/CREATE TEMP` 后的表是 **target**，`FROM/JOIN/USING` 后的表是 **source**。

**注**：`SELECT ... INTO target` 形式在实际 GBase 脚本中未出现，暂不支持；若未来需要可补充。

### 4.3 子查询递归解析

**必要性**：实际脚本中子查询承载真实数据源。以 `dwa_c_agt_deposit_acct_inf.py` 为例，包含 7 处子查询：

| 行 | 子查询别名 | 真实源表 |
|---|---|---|
| 226-234 | TA | F_EV_CRBS_AGR_DEPOSIT_BOOK |
| 270-281 | TRP | F_CO_CRBS_ACTUAL_INT_RATE_PARAM |
| 411-423 | TFO | F_EV_CRBS_DEPOSIT_ACCT_FROZEN_BOOK |
| 560-571 | T02 | F_FI_CRBS_NEW_ACCTG_ENTRY_CONFIG_FILE_BIZ |
| 588-600 | TAI | C_AGT_DEPOSIT_ACCT_INTST |
| 782-796 | TCA | F_AC_CTDS_LDR_ACCT |
| 799-812 | T02 | F_AC_CTDS_LDR_FROZEN_INF |

**算法**：括号配对 + 递归

1. 扫描 SQL 文本，遇到 `FROM`/`JOIN` 后跟 `(` 时，进入子查询模式
2. 用括号计数器找到匹配的右括号
3. 把括号内的子查询文本递归调用同一解析逻辑
4. 子查询内的 FROM/JOIN 表名作为 source，与外层 target 建立派生关系
5. 子查询的别名（如 `T02`）不作为表节点

**复杂度**：递归深度限制为 5 层（实际数据基本不会超过 2 层），防止恶意 SQL 拖垮解析器。

### 4.4 JOIN/WHERE 详情提取

每条派生关系（source → target）按 source 表所在 SQL 块进一步抽取：

```
SELECT ...
FROM a
JOIN b ON a.cust_id = b.id        ← JOIN：a.cust_id=b.id
LEFT JOIN c ON a.org_id = c.org_id AND c.sts='1'   ← JOIN：a.org_id=c.org_id, c.sts='1'
WHERE a.crt_dt >= '2024-01-01'    ← WHERE：a.crt_dt>='2024-01-01'
  AND b.typ = 'V'                 ← WHERE：b.typ='V'
```

**提取规则：**
- **JOIN 子句**：用正则 `\bJOIN\s+(\w+)\s+.*?\bON\s+(.+?)(?=\b(JOIN|WHERE|GROUP|ORDER|LIMIT|;|$))` 抽取，按 JOIN 关联到对应 source 表
- **WHERE 子句**：用正则 `\bWHERE\s+(.+?)(?=\b(GROUP|ORDER|LIMIT|;|$))` 抽取，再按 `AND/OR` 拆分，按字段前缀关联到对应 source 表
- **字段列表**：用正则 `(\w+\.\w+)` 从子句中提取所有 `table.field` 形式的字段

**字段归属判定**：条件中涉及 `a.xxx` 的归属于 source 表 `a`。若条件涉及多表字段（如 `a.id=b.id`），归属于 JOIN 关系。

### 4.5 解析结果数据结构

```java
public class LineageParseResult {
    private List<LineageEdge> edges;
    private List<LineageFilter> filters;
}

public class LineageEdge {
    private String sourceTable;        // 完整名
    private String sourceTableShort;   // 短名
    private String targetTable;
    private String targetTableShort;
    private boolean targetIsTemp;
    private String operation;          // CREATE TEMP / INSERT SELECT / ...
    private int sqlBlockSeq;
}

public class LineageFilter {
    private int edgeIndex;             // 关联到 edges 的下标
    private String filterType;         // JOIN / WHERE
    private String filterClause;       // 原文
    private List<String> fieldsInvolved;
}
```

### 4.6 与现有解析流程的集成

`PyProcParser.parse` 流程扩展（双写）：

1. 读取文件
2. 头部元数据（原有）
3. schema 变量映射（原有）
4. SQL 块切分（新增 `SqlBlockExtractor.extractBlocks` 返回 `List<String>`）
5. 表名提取（原有，`SqlTableExtractor.extract` 维护 proc_table_dependency）
6. **新增**：`LineageExtractor.extract(sqlBlocks, schemaVarMap, updateTarget)`，维护 `table_lineage` + `table_lineage_filter`

**关键改动点**：`SqlBlockExtractor` 新增方法返回 `List<String>`：

```java
// 现有方法保留（向后兼容）
public String extract(String content) { ... }

// 新增方法
public List<String> extractBlocks(String content) { ... }
```

### 4.7 边界场景处理

| 场景 | 处理 |
|---|---|
| SQL 块无法识别类型（如纯注释、变量赋值） | 跳过，不产出派生关系 |
| 同一 SQL 块多次 FROM 同一表 | 去重，只记一条 edge |
| 临时表被多次 INSERT 累加 | 多条 edge，按 `sql_block_seq` 区分顺序 |
| JOIN/WHERE 解析失败 | 不阻塞，跳过该 filter，但 edge 正常入库 |
| 更新目标表出现在 FROM | 不视为 source（与现有规则一致） |
| 子查询内的表 | 递归提取，作为 source 计入派生关系 |
| 子查询嵌套超过 5 层 | 停止递归，记录 warn 日志 |
| 括号未闭合 | 放弃子查询解析，按整块扁平提取 |

### 4.8 不做的事（YAGNI）

- 不解析 CTE（WITH 子句）— 实际脚本中未出现
- 不解析 UNION 各分支的血缘 — 实际脚本中未出现
- 不做完整 SQL AST 解析 — 用正则+括号配对即可满足当前规模

---

## 5. API 设计

### 5.1 新增 Controller：`LineageController`

路径：`com.hb.extractor.controller.LineageController`，独立于 `ProcExtractController`。

### 5.2 API 清单

| 方法 | 路径 | 功能 | 入参 | 出参 |
|---|---|---|---|---|
| GET | `/api/lineage/{procName}` | 查询某存储过程的完整血缘图 | procName | LineageGraphVO |
| GET | `/api/lineage/table/{tableName}/upstream` | 查询某表的上游血缘（递归） | tableName, maxDepth? | LineageGraphVO |
| GET | `/api/lineage/table/{tableName}/downstream` | 查询某表的下游血缘（递归） | tableName, maxDepth? | LineageGraphVO |
| GET | `/api/lineage/{procName}/filter` | 查询某存储过程所有 JOIN/WHERE 详情 | procName | List<FilterVO> |

### 5.3 统一响应结构

```java
public class LineageGraphVO {
    private String procName;
    private String updateTarget;
    private List<Node> nodes;
    private List<Edge> edges;
    private Boolean truncated;  // 递归是否被 depth 截断

    @Data
    public static class Node {
        private String id;            // 表短名
        private String fullTableName; // 完整名
        private String nodeType;      // SOURCE / TEMP / TARGET
        private String schemaName;    // schema 变量名
    }

    @Data
    public static class Edge {
        private String source;        // 源表短名
        private String target;        // 目标表短名
        private String operation;     // CREATE TEMP / INSERT SELECT / MERGE ...
        private Integer sqlBlockSeq;  // SQL 块序号
        private List<FilterDetail> filters;
    }

    @Data
    public static class FilterDetail {
        private String filterType;     // JOIN / WHERE
        private String filterClause;   // 原文
        private List<String> fields;   // 涉及字段
    }
}
```

### 5.4 API 详细行为

#### 5.4.1 `GET /api/lineage/{procName}` — 单存储过程血缘图

**查询逻辑：**
```sql
SELECT * FROM table_lineage WHERE proc_name = ? ORDER BY sql_block_seq;
SELECT * FROM table_lineage_filter WHERE proc_name = ?;
SELECT update_target FROM proc_info WHERE proc_name = ?;
```

**节点类型判定：**
- `SOURCE`：在 `source_table` 中出现但不在 `target_table` 中
- `TEMP`：`target_is_temp=1` 或表名以 `VT_` 开头
- `TARGET`：等于 `update_target`，或不在任何 `source_table` 中

#### 5.4.2 `GET /api/lineage/table/{tableName}/upstream` — 递归上游

**用途**：在 G6 中点击某节点，追溯其所有上游源表（跨存储过程）。

**查询逻辑**（MySQL 8 递归 CTE）：
```sql
WITH RECURSIVE upstream AS (
    SELECT source_table_short AS tbl, source_table AS full_name,
           target_is_temp, 1 AS depth, proc_name, id
    FROM table_lineage WHERE target_table_short = ?
    UNION ALL
    SELECT t.source_table_short, t.source_table,
           t.target_is_temp, u.depth + 1, t.proc_name, t.id
    FROM table_lineage t
    JOIN upstream u ON t.target_table_short = u.tbl
    WHERE u.depth < COALESCE(?, 5)
)
SELECT DISTINCT tbl, full_name, target_is_temp, proc_name FROM upstream;
```

**入参**：
- `tableName`（path）：表短名
- `maxDepth`（query，可选）：最大追溯深度，默认 5，上限 10

#### 5.4.3 `GET /api/lineage/table/{tableName}/downstream` — 递归下游

与 upstream 对称，递归方向反转，用于影响分析。

#### 5.4.4 `GET /api/lineage/{procName}/filter` — 条件详情

**响应**：
```json
[
  {
    "edgeId": 123,
    "source": "F_AC_CRBS_DEPOSIT_ACCT",
    "target": "VT_C_AGT_DEPOSIT_ACCT_INF_03",
    "filterType": "JOIN",
    "filterClause": "TXX.liabi_acct_no = TCA.sys_acct_no",
    "fields": ["TXX.liabi_acct_no", "TCA.sys_acct_no"]
  }
]
```

### 5.5 与现有 API 的关系

| 现有 API | 改动 |
|---|---|
| `POST /api/proc/extract` | 不变（内部双写 table_lineage） |
| `POST /api/proc/extract/batch` | 不变 |
| `POST /api/proc/extract/all` | 不变 |
| `POST /api/proc/extract/upload` | 不变 |
| `GET /api/proc/list` | 响应增加 `hasLineage` 字段 |
| `GET /api/proc/{procName}/tables` | 不变（兼容旧前端） |
| `DELETE /api/proc/{procName}` | 删除时级联删除 `table_lineage` 和 `table_lineage_filter` |

### 5.6 错误处理

| HTTP 状态 | 触发条件 | 响应体 |
|---|---|---|
| 400 | maxDepth > 10 或参数格式错误 | `{"code":400,"message":"..."}` |
| 404 | procName 不存在 | `{"code":404,"message":"存储过程不存在"}` |
| 500 | 未捕获异常 | `{"code":500,"message":"服务器内部错误"}` |
| 200 | 正常或「无数据」 | `{"code":0,"data":{...}}` |

「无血缘数据」返回 200 + 空 nodes/edges，不返回 404。

---

## 6. 前端设计

### 6.1 路由与导航

```js
// router/index.js 新增
{
  path: '/procs/:procName/lineage',
  name: 'Lineage',
  component: () => import('../views/LineageView.vue'),
  meta: { title: '血缘图' }
}
```

### 6.2 入口点

`ProcDetailView.vue` 顶部新增按钮：
```html
<el-button type="primary" @click="$router.push(`/procs/${procName}/lineage`)">
  <el-icon><Connection /></el-icon> 查看血缘图
</el-button>
```

### 6.3 页面布局

```
┌────────────────────────────────────────────────────────────┐
│ ← 返回  存储过程血缘图：dwa_c_agt_deposit_acct_inf          │
│ 更新目标：CDM.C_AGT_DEPOSIT_ACCT_INF  · 依赖表 12 · 临时表 4 │
├────────────────────────────────────────────────────────────┤
│ [展开全部] [收起临时表] [布局: dagre▼] [导出 PNG]            │
├──────────────────────────────────────────────┬──────────────┤
│                                              │              │
│              G6 画布                          │  详情面板    │
│                                              │  (默认隐藏)  │
│   F_AC_CRBS_DEPOSIT_ACCT ──┐                 │              │
│                            │ JOIN            │  点击节点/边 │
│   F_AC_CRBS_CUST_ACCT_NO ──┤ a.id=b.aid      │  后显示:     │
│                            │ WHERE           │  - 节点信息  │
│                            ▼ a.sts='1'       │  - 上下游    │
│                       VT_C_03                │  - JOIN/WHERE│
│                            │                 │    原文      │
│                            ▼ INSERT          │              │
│                  C_AGT_DEPOSIT_ACCT_INF      │              │
│                                              │              │
├──────────────────────────────────────────────┴──────────────┤
│ 图例：● 源表  ● 临时表  ● 目标表   ━ JOIN  ━ WHERE  ━ 派生  │
└────────────────────────────────────────────────────────────┘
```

### 6.4 G6 配置

```js
import { Graph } from '@antv/g6'

const graph = new Graph({
  container: 'lineage-container',
  width: 800,
  height: 600,
  layout: {
    type: 'dagre',           // 分层布局，适合血缘 DAG
    rankdir: 'LR',           // 左到右
    nodesep: 40,
    ranksep: 80,
  },
  node: {
    type: 'rect',
    style: {
      size: [160, 40],
      radius: 8,
      fill: ({ data }) => ({
        SOURCE: '#67c23a',
        TEMP:   '#e6a23c',
        TARGET: '#f56c6c',
      }[data.nodeType] || '#909399'),
      fillOpacity: 0.15,
      stroke: ({ data }) => ({
        SOURCE: '#67c23a',
        TEMP:   '#e6a23c',
        TARGET: '#f56c6c',
      }[data.nodeType] || '#909399'),
      lineWidth: 2,
    },
    label: ({ data }) => data.id,
    labelFill: '#303133',
    labelFontSize: 12,
  },
  edge: {
    type: 'quadratic',
    style: {
      stroke: '#909399',
      lineWidth: 1.5,
      endArrow: true,
    },
    label: ({ data }) => data.operation,
    labelFontSize: 10,
    labelFill: '#606266',
  },
  behaviors: [
    'drag-canvas',
    'zoom-canvas',
    'drag-element',
    'click-select',
  ],
})
```

### 6.5 交互行为

| 用户操作 | 响应 |
|---|---|
| 点击节点 | 右侧面板显示：表名、schema、节点类型、上下游表清单、查看上下游按钮 |
| 点击边 | 右侧面板显示：派生操作、SQL 块序号、JOIN/WHERE 详情（含原文+字段列表） |
| 双击节点 | 调用 `/api/lineage/table/{name}/upstream` 跳转到该节点的上游血缘图 |
| 悬停节点 | 高亮该节点的所有直接连接边 |
| 悬停边 | 高亮该边的两端节点，弹出 tooltip 显示 filter 原文 |
| 滚轮 | 缩放画布 |
| 拖拽空白 | 平移画布 |
| 拖拽节点 | 移动节点位置 |
| 点击「收起临时表」 | 隐藏 `nodeType=TEMP` 的节点，其上下游边自动连接 |
| 点击「展开全部」 | 恢复全部节点 |
| 点击「导出 PNG」 | 调用 `graph.toFullDataURL()` 下载图片 |

### 6.6 右侧详情面板

```vue
<!-- LineageDetailPanel.vue -->
<template>
  <el-drawer v-model="visible" :title="title" size="400px" direction="rtl">
    <!-- 节点详情 -->
    <div v-if="type === 'node'">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="表名">{{ node.id }}</el-descriptions-item>
        <el-descriptions-item label="完整名">{{ node.fullTableName }}</el-descriptions-item>
        <el-descriptions-item label="Schema">{{ node.schemaName }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="tagType">{{ node.nodeType }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider>上下游关系</el-divider>
      <el-button size="small" @click="traceUpstream">追溯上游</el-button>
      <el-button size="small" @click="traceDownstream">追溯下游</el-button>
    </div>

    <!-- 边详情 -->
    <div v-else-if="type === 'edge'">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="源表">{{ edge.source }}</el-descriptions-item>
        <el-descriptions-item label="目标表">{{ edge.target }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ edge.operation }}</el-descriptions-item>
        <el-descriptions-item label="SQL 块序号">{{ edge.sqlBlockSeq }}</el-descriptions-item>
      </el-descriptions>

      <el-divider>JOIN / WHERE 详情</el-divider>
      <el-collapse>
        <el-collapse-item
          v-for="(f, i) in edge.filters"
          :key="i"
          :title="`${f.filterType}: ${f.filterClause}`"
        >
          <div>
            <el-tag size="small" :type="f.filterType === 'JOIN' ? 'success' : 'warning'">
              {{ f.filterType }}
            </el-tag>
            <pre style="margin:8px 0;background:#f5f7fa;padding:8px">{{ f.filterClause }}</pre>
            <el-tag v-for="field in f.fields" :key="field" size="small" style="margin:2px">
              {{ field }}
            </el-tag>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </el-drawer>
</template>
```

### 6.7 状态管理

不需要 Pinia/Vuex，组件内 `ref` 即可：

```js
const graph = ref(null)
const selectedNode = ref(null)
const selectedEdge = ref(null)
const panelVisible = ref(false)
const hideTemp = ref(false)

async function loadLineage(procName) {
  const { data } = await getLineage(procName)
  graph.value.setData(transformToG6(data))
  graph.value.render()
}

watch(hideTemp, (v) => {
  graph.value.hideItem(item => item.model.nodeType === 'TEMP' && v)
})
```

### 6.8 数据转换：API → G6

```js
function transformToG6(vo) {
  return {
    nodes: vo.nodes.map(n => ({ id: n.id, data: { ...n } })),
    edges: vo.edges.map((e, i) => ({
      id: `edge-${i}`,
      source: e.source,
      target: e.target,
      data: { ...e }
    }))
  }
}
```

### 6.9 包依赖新增

```json
{
  "dependencies": {
    "@antv/g6": "^5.0.0"
  }
}
```

### 6.10 不做的事

- 不做画布布局切换（dagre 一种即可）
- 不做节点搜索（节点数有限，肉眼可见）
- 不做编辑能力（血缘只读）
- 不做实时刷新（手动重新解析后才更新）

---

## 7. 错误处理

### 7.1 解析层

| 错误场景 | 处理 | 影响 |
|---|---|---|
| SQL 块无法识别类型 | 跳过该块，记录 warn 日志 | 不产出派生关系，但不阻断后续块 |
| FROM/JOIN 后表名提取失败 | 跳过该 source，记录 warn 日志 | 该 source 缺失，其他 source 正常 |
| 括号配对失败（子查询未闭合） | 放弃子查询解析，按整块扁平提取 | 退化为不解析子查询，不报错 |
| JOIN/WHERE 子句提取失败 | 跳过该 filter，edge 正常入库 | filter 缺失，不影响派生关系 |
| 字段列表提取失败 | `fields_involved` 存 null | filter 原文仍可用 |
| 递归深度超 5 层 | 停止递归，记录 warn 日志 | 深层子查询丢失，但解析完成 |
| schema 变量未定义 | 完整名存原文 `{UNKNOWN}.table` | 不阻断，便于人工排查 |
| SQL 语法异常 | 整块降级为扁平依赖 | 保留 proc_table_dependency 兼容数据 |

**核心原则**：解析器**永不抛出中断异常**，单个 SQL 块的失败不影响其他块。所有失败记录 warn 日志。

### 7.2 数据层

| 场景 | 处理 |
|---|---|
| 入库事务失败 | 整个 proc 的血缘回滚，但 `proc_info` 和 `proc_table_dependency` 不受影响（独立事务） |
| 删除 proc 时级联删除血缘失败 | 整个删除事务回滚，返回错误给用户 |
| 递归 CTE 检测到环 | depth 限制兜底，正常返回截断结果，附加 `truncated=true` 标记 |

### 7.3 API 层

| HTTP 状态 | 触发条件 | 响应体 |
|---|---|---|
| 400 | maxDepth > 10 或参数格式错误 | `{"code":400,"message":"..."}` |
| 404 | procName 不存在 | `{"code":404,"message":"存储过程不存在"}` |
| 500 | 未捕获异常 | `{"code":500,"message":"服务器内部错误"}` |
| 200 | 正常或「无数据」 | `{"code":0,"data":{...}}` |

### 7.4 前端层

| 场景 | 处理 |
|---|---|
| API 返回 404 | 显示「存储过程不存在」+ 返回按钮 |
| API 返回空 nodes | 显示空状态图：「未解析到血缘关系，请重新解析」 |
| API 超时 | ElMessage 错误提示 + 重试按钮 |
| G6 渲染失败 | 降级为「依赖表列表」展示（复用 ProcDetailView 表格） |
| 节点过多（>50） | 提示「节点较多，建议收起临时表查看」 |

---

## 8. 测试策略

### 8.1 解析器单元测试（重点）

**测试文件**：`src/test/java/com/hb/extractor/parser/LineageExtractorTest.java`

| 测试用例 | 验证点 |
|---|---|
| `testCreateTempTable` | CREATE TEMP TABLE 派生 a→VT_x, b→VT_x |
| `testInsertSelect` | INSERT INTO 派生 a→target, b→target |
| `testMergeInto` | MERGE INTO 派生 src→target |
| `testSubqueryExtraction` | 子查询内的 b 被提取为 source |
| `testNestedSubquery` | 多层嵌套子查询递归到 5 层 |
| `testJoinClauseExtraction` | JOIN 子句抽取，fields 含 a.id, b.id |
| `testWhereClauseExtraction` | WHERE 子句按 AND 拆分，按字段前缀归属 |
| `testMultiTableJoin` | 多表 JOIN，每条 JOIN 独立记录 |
| `testUpdateTargetExcluded` | 更新目标表出现在 FROM 时不产生自环 |
| `testUnrecognizableBlock` | 注释块返回空列表，不抛异常 |
| `testMalformedSql` | 括号未闭合降级处理，不抛异常 |
| `testRealScript` | 用 `dwa_c_agt_deposit_acct_inf.py` 真实脚本，提取 7+ 条派生关系 |

**重点**：`testRealScript` 是回归测试，用真实脚本验证完整血缘链路。

### 8.2 服务层集成测试

**测试文件**：`LineageServiceTest.java`

| 测试用例 | 验证点 |
|---|---|
| `testGetLineageGraph` | 节点包含 SOURCE/TEMP/TARGET 三种类型，edges 不为空 |
| `testRecursiveUpstream` | 返回所有上游表，depth 不超过 5 |
| `testRecursiveDownstream` | 对称测试下游追溯 |
| `testCycleDetection` | 构造 A→B→A 的环，不死循环，truncated=true |

### 8.3 Controller 层测试

**测试文件**：`LineageControllerTest.java`（MockMvc）

| 测试用例 | 验证点 |
|---|---|
| `testGetLineageNotFound` | procName 不存在返回 404 |
| `testGetLineageEmpty` | proc 存在但无血缘数据返回 200 + 空 nodes |
| `testMaxDepthValidation` | maxDepth=11 返回 400 |

### 8.4 前端测试

**手动验证清单**：

- [ ] 上传 `dwa_c_agt_deposit_acct_inf.py` 后，点击「查看血缘图」能渲染完整图
- [ ] 图中包含 7+ 个源表节点（覆盖子查询内的表）
- [ ] 临时表节点显示为黄色
- [ ] 目标表节点显示为红色
- [ ] 点击节点显示右侧详情面板
- [ ] 点击边显示 JOIN/WHERE 原文
- [ ] 双击节点跳转到该表的上游血缘图
- [ ] 「收起临时表」按钮工作正常
- [ ] 「导出 PNG」能下载图片
- [ ] 浏览器控制台无报错

### 8.5 测试数据

复用现有 `jiaoben/` 目录：
- 7 个 `.py` 脚本作为集成测试输入
- 重点用 `dwa_c_agt_deposit_acct_inf.py`（已分析，包含 7 处子查询）

### 8.6 验证命令

```bash
# 后端测试
mvn test

# 前端构建
cd frontend && npm run build

# 一键启动验证
startup.bat
```

### 8.7 不做的事

- 不做性能测试（当前规模无需）
- 不做 E2E 自动化测试（手动验证即可）
- 不做前端单元测试（Vue 组件逻辑简单）
- 不做覆盖率硬性指标（重点测试解析器和递归查询）

---

## 9. 范围与非目标

### 9.1 本次范围内

- 新增 `table_lineage` 和 `table_lineage_filter` 两张表
- 新增 `LineageExtractor` 解析器（含子查询递归）
- 新增 `LineageController` 和 `LineageService`
- 新增前端 `LineageView` 页面（AntV G6 交互式图）
- 修改 `ProcExtractServiceImpl` 双写血缘数据
- 修改 `DELETE /api/proc/{procName}` 级联删除血缘

### 9.2 非目标

- 不重构现有 `proc_table_dependency` 表和解析流程
- 不引入 Neo4j 或其他图数据库
- 不解析 CTE（WITH 子句）和 UNION 分支
- 不做 SQL 完整 AST 解析
- 不做血缘修改 API（血缘只读）
- 不做实时刷新（手动重新解析后才更新）
- 不做节点搜索、画布布局切换等高级交互

---

## 10. 实施顺序建议

1. **数据层**：新增两张表 DDL，更新 `init.sql`
2. **解析器**：实现 `LineageExtractor`，编写单元测试（含真实脚本回归）
3. **集成**：`SqlBlockExtractor.extractBlocks` + `PyProcParser` 双写
4. **服务层**：`LineageService` 实现递归 CTE 查询
5. **API 层**：`LineageController` + 错误处理
6. **前端**：新增 `LineageView` + G6 集成 + 详情面板
7. **端到端验证**：用真实脚本走完解析→查询→渲染全流程
