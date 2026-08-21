# 条件影响双通道重构设计（值血缘 / 行血缘分离）

- **作者**：AI 助手 + 用户协作
- **创建日期**：2026-08-21
- **状态**：已实现（2026-08-21）
- **关联项目**：proc-dependency-extractor
- **前置文档**：`2026-08-19-on-where-condition-fields-input-to-design.md`（本设计取代其中"条件字段挂 Transform INPUT_TO"的建模方式）
- **用户已确认的语义决策**：
  1. 下游影响分析中，行影响作为一类正式结果保留，与值影响区分类型展示
  2. 上游溯源时，条件字段以独立分区呈现，不与值来源混排

## 1. 背景与问题

### 1.1 现状建模

2026-08-19 的增强将 ON/WHERE 条件字段以 `role=join_condition/where_condition` 挂到 SQL 块内**每一个** Transform 节点的 INPUT_TO 边上（FieldMappingExtractor 为每个 FieldMapping 复制一份条件 TransformInput）。

### 1.2 四个已确认缺陷

| # | 问题 | 机理 |
|---|------|------|
| 1 | 影响分析假阳性 | 从 `b.balance`（WHERE 条件字段）做下游分析，报告其"影响"目标表所有列——但 `balance > 0` 只过滤行，不参与任何列的值计算 |
| 2 | 上游溯源噪音 | 查目标列 `cust_id` 的值来源时，条件字段与真正参与计算的源字段混排 |
| 3 | 图边暴涨 | N 个目标列 × M 个条件字段 = N×M 条 INPUT_TO 边（50 列 × 10 条件 = 500 条），拖慢同步、遍历与渲染 |
| 4 | 虚假共祖 | 同块所有 Transform 共享同一批条件输入，任何两列都被判强相关，未来"共同上游"分析会失真 |

### 1.3 根因

**行影响被建模成了 N 份"值影响"**。语义上存在两层正交的血缘：

| 语义层 | 含义 | 正确建模 |
|--------|------|---------|
| 值血缘 | 目标列的值由哪些源列**计算**而来（SELECT 表达式） | 列 → Transform → 列（现有值边） |
| 行血缘 | 条件字段决定目标表**产出哪些行**（ON/WHERE） | 条件列 → 目标表（每块每字段 1 条边） |

行集变化确实影响目标表所有列的**数据**（行影响是真实影响，需保留），但不改变任何列的**计算口径**（不是值派生）。fan-out 应发生在**查询/展示期**（"影响整表"），而非**建图期**（N×M 条边落库）。

## 2. 目标与非目标

### 2.1 目标

- 四个缺陷全部消除：行影响不再冒充值派生；溯源值来源纯净；边量 N×M → M；共祖分析只走值边天然正确
- 保留两类合法能力：条件字段的下游影响分析（标注为行影响）；原断链场景打通（临时表字段仅在后续块 ON/WHERE 中使用 → 仍能到达目标物理表字段）
- 值链路（INPUT_TO value / OUTPUT_TO / DERIVED_FROM / MAPS_TO / READS）分析结果与重构前完全一致

### 2.2 非目标

- 不处理 GROUP BY / HAVING 条件字段（维持原范围）
- 不递归子查询内部的条件（维持原范围）
- 不改表级影响分析（READS 表级血缘已覆盖表级语义，FILTERS 仅服务字段级双通道）
- 不合并/删除 `table_lineage_filter`（其职责为表级条件原文展示，与本设计的字段级归属存储互补）

## 3. 图模型变更

### 3.1 新增边

```cypher
MATCH (c:Column {col_key: $srcColKey}), (t:Table {name: $targetTable})
MERGE (c)-[r:FILTERS {proc_name: $procName, sql_block_seq: $seq, role: $role}]->(t)
SET r.clause = $clause, r.source_alias = $sourceAlias
```

- **MERGE 键**：`(源列, 目标表, proc_name, sql_block_seq, role)`——同一字段在同块同角色仅 1 条边
- **属性**：`role`（join_condition / where_condition）、`clause`（条件原文，多条件分号拼接，截断 512 字符，仅展示用）、`source_alias`（最后写入胜出，仅展示用）
- 目标表 = 该 SQL 块的 lineage edge 目标表（临时表或物理表）

### 3.2 删除的建模

- 不再创建 `role in (join_condition, where_condition)` 的 INPUT_TO 边
- `table_transform_input` 不再为条件字段产生行（恢复为只存值输入）

### 3.3 不变的建模

INPUT_TO(value)、OUTPUT_TO、DERIVED_FROM、MAPS_TO、READS、PRODUCES/CONSUMES、报表侧全部关系。

## 4. MySQL 变更

### 4.1 新表 `table_block_condition`

```sql
CREATE TABLE table_block_condition (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  lineage_id BIGINT NOT NULL COMMENT '关联 table_lineage.id',
  proc_name VARCHAR(128) NOT NULL,
  sql_block_seq INT NOT NULL,
  source_table_short VARCHAR(128) COMMENT '别名解析后的源表短名，null=未归属(跳过同步)',
  source_alias VARCHAR(64),
  source_field VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL COMMENT 'join_condition / where_condition',
  clause VARCHAR(512) COMMENT '条件原文(截断)',
  UNIQUE KEY uk_block_cond (lineage_id, source_alias, source_field, role),
  KEY idx_proc (proc_name)
) COMMENT 'SQL块条件字段归属(行血缘同步源)';
```

- 每块每 `(source_alias, source_field, role)` 一行（对比现状：每 FieldMapping 一份 = N 份）
- DDL 追加到 `sql/init.sql`，另提供独立迁移片段供已有环境执行

### 4.2 数据流

`LineageExtractor` 解析 → `LineageEdge.conditionFields`（内存 DTO）→ `ProcExtractServiceImpl` 落库 `table_block_condition`（随 lineage 先删后建）→ `Neo4jSyncServiceImpl` 读取建 FILTERS 边。

## 5. 解析器变更

### 5.1 FieldMappingExtractor

- **移除**："为每个 FieldMapping 添加 ON/WHERE 条件字段的 TransformInput"整段循环（现 L167-L216）
- **新增**：`extractConditionFields(select)` 结果独立返回，字段归属解析逻辑**全部复用**：
  - 有 alias 前缀 → `aliasMap.get(alias)`
  - 无 alias、单表 → 兜底唯一表
  - 无 alias、多表 → DDL 字段匹配（`table_column_meta`），唯一匹配才归属
- 归属失败（多重匹配/无匹配）跳过并 `log.warn`（proc、block、字段名），保持可观测
- SQL 关键字过滤、纯数字过滤、`(alias, field, role)` 去重逻辑照旧

### 5.2 LineageExtractor / DTO

- `LineageEdge` 新增 `List<ConditionField> conditionFields`（字段：sourceTableShort、sourceAlias、sourceField、role、clause）
- `FieldMapping.inputs` 不再含条件行；`matchByDdlFields/matchBySelectOnly` 等值映射逻辑零改动

## 6. Neo4j 同步变更（Neo4jSyncServiceImpl.syncProc）

- **先删后建范围扩大**：删除该 proc 的 `FILTERS {proc_name}` 边（重建）
- 从 `table_block_condition` 读取（`source_table_short` 非空才建边）；表名解析为全限定名后 MERGE
- INPUT_TO 创建逻辑恢复为仅值输入（条件行已不存在，天然满足）
- **旧条件 INPUT_TO 边无需单独清理**：syncProc 对 Transform 节点 DETACH DELETE，条件 INPUT_TO 挂在 Transform 上随之自动消失；全量重解析后图中不再有条件 INPUT_TO
- 同步失败记录 `neo4j_sync_log`、重试调度（Neo4jSyncRetryScheduler）机制不变

## 7. 影响分析变更（下游）

### 7.1 双通道遍历

```
值通道（现有）：起点列 --INPUT_TO(value)--> Transform --OUTPUT_TO--> 目标列 --DERIVED_FROM--> ...
行通道（新增）：起点列 --FILTERS--> 受影响表(1跳) → 表内所有列打"行影响"标签(查询期展开)
             → 这些列沿值通道规则继续向下游传播，标签保持行影响
```

- 行影响标签：`行过滤·JOIN` / `行过滤·WHERE`（由 FILTERS.role 决定）
- 深度：FILTERS 计 1 跳 + 后续值边跳数，整体受 `impact.analysis.max-depth` 限制
- 行通道到达的列继续传播时不再二次经过 FILTERS（同一表不重复展开；跨 proc 下游表经值链路自然衔接）

### 7.2 结果结构

- 受影响字段行新增影响方式标签：`直接来源/派生（值）`、`行过滤·JOIN`、`行过滤·WHERE`
- **同一列既有值影响又有行影响**（如 `b.balance` 同时出现在 SELECT 和 WHERE）：两通道独立传播，聚合为**一行多标签**（复用现有 (procName, 目标表, 目标字段) 单行多 reasons 模式）
- 原"最近角色跳变"规则随条件 INPUT_TO 一并移除：值边无 role，行影响标签来自 FILTERS 通道
- 层级分组（最长路径穿透临时表）、去重、统计、Excel 导出（Level 列）规则照旧；导出的"影响原因"列同步新标签
- `ImpactAnalysisVO` 边/行结构扩展 `impactKind`（VALUE / ROW）与标签字段

### 7.3 悬空节点清理

`removeDanglingAuxNodes` 适配：行通道展开的表/列节点为查询期构造，不落深度时不产生残留；校验逻辑保持"depth 非空且连通"原则不变。

## 8. 溯源变更（上游）

- **值来源分区**：上游遍历只走值边（图中已无条件 INPUT_TO，天然干净，无需 role 过滤）
- **条件分区**：目标列所在 Table 的反向 FILTERS 边 → 展示"该表行集受以下条件控制"（字段、role 中文、clause、proc_name、block_seq）
- 报表列溯源（ReportImpactServiceImpl.traceReportLineage 复用字段级 UPSTREAM 分析）自动受益：报表列值来源同样纯净，条件分区同样可用

## 9. 前端变更

| 位置 | 变更 |
|------|------|
| impactListUtils.js | 标签体系改为 `直接来源/派生（值）`、`行过滤·JOIN`、`行过滤·WHERE`；移除"最近角色跳变"BFS，改为双通道标签聚合 |
| ImpactAnalysis.vue 结果列表 | "影响原因"列：行过滤标签用描边 tag 样式区分值影响 |
| ImpactAnalysis.vue 上游模式 | 拆分"值来源"与"关联/过滤条件"两个区块 |
| ImpactPathDialog.vue | FILTERS 边画橙色虚线 + ON/WHERE 标签，**指向 Table 节点**；表→列的行影响继承不逐列画边，列清单区用标签表达 |
| ImpactEdgeDetail.vue | role 中文显示；修复 `role === 'condition'` 永假的颜色判断 bug（改为 join_condition/where_condition 判定） |
| Excel 导出 | 原因列同步新标签 |

## 10. 迁移方案

1. 执行 `table_block_condition` 建表 DDL
2. 部署新代码
3. 全量重解析 300 proc（`reparse_all.ps1`，断点续传）：重建 MySQL 数据 + syncProc 重建 Neo4j（旧条件 INPUT_TO 随 Transform DETACH DELETE 自动清除）
4. 抽查 Neo4j：确认无 `INPUT_TO.role in (join_condition, where_condition)` 残留；`FILTERS` 边数量级正确（≈ 条件字段数 × 引用块数）

**无需单独的数据迁移脚本**（旧 `table_transform_input` 条件行随重解析先删后建自然消失）；**回滚**＝回退代码 + 再次全量重解析。

## 11. 边界情况

| 场景 | 处理 |
|------|------|
| 同一字段在 SELECT 和 ON/WHERE 中 | 值通道 + 行通道独立传播；目标列聚合多标签 |
| ON 条件含字面量 | 不建 FILTERS（collectColumnRefs 只提取 Column） |
| WHERE 无 alias、多表且 DDL 多重匹配 | 跳过 + log.warn |
| 条件字段归属解析失败（source_table_short=null） | 不建 FILTERS 边，MySQL 行保留（clause 可查） |
| 子查询内部条件 | 不处理（维持原范围） |
| 同块同字段多条同类条件 | MERGE 单边，clause 分号拼接 |
| 行通道起点自身所在表被过滤（自引用） | 起点表不重复展开 |

## 12. 测试验证

### 12.1 单元测试

- 解析器：条件字段进入 `LineageEdge.conditionFields` 且**不进** `FieldMapping.inputs`；N 列场景 `table_block_condition` 仅 M 行（非 N×M）；DDL 匹配唯一/多重/无匹配三分支
- 同步：FILTERS MERGE 唯一性（同键重建不重复）；不再创建条件 INPUT_TO
- 影响分析：双通道标签正确；同列多标签聚合一行；深度计算含 FILTERS 跳
- 溯源：值来源不含条件字段；条件分区数据正确

### 12.2 集成验证（重解析后）

| # | 操作 | 期望 |
|---|------|------|
| 1 | 从 `b.balance`（WHERE 条件字段）下游分析 | 目标表所有列标 `行过滤·WHERE`，**无** `值派生` 标签 |
| 2 | 目标列 `cust_id` 上游溯源 | 值来源区不含 `b.balance`；条件分区可见其所在表的条件 |
| 3 | 从 FDM `acct_no` 下游分析（原 spec 场景） | depth 链路到达 `c_agt_deposit_acct_profit.acct_no`，标签含 `行过滤·JOIN`（经 ON 条件传播） |
| 4 | 纯值链路字段分析（不涉及条件） | 结果与重构前一致（回归） |
| 5 | Neo4j 抽查 | 无条件 INPUT_TO 残留；边量约等于"条件字段 × 引用块数" |

## 13. 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `sql/init.sql` | 新增 `table_block_condition` DDL |
| `parser/dto/ConditionField.java`（新增） | 条件字段 DTO |
| `parser/dto/LineageEdge.java` | 新增 `conditionFields` 字段 |
| `parser/FieldMappingExtractor.java` | 移除条件 TransformInput 附加循环；条件字段独立提取并解析归属（复用现有三种归属策略 + log.warn） |
| `parser/LineageExtractor.java` | 传递 conditionFields 到 edge |
| `entity/TableBlockCondition.java`（新增）+ `mapper/TableBlockConditionMapper.java`（新增） | 新表实体与 Mapper |
| `service/impl/ProcExtractServiceImpl.java` | 落库 `table_block_condition`（随 lineage 先删后建） |
| `service/impl/Neo4jSyncServiceImpl.java` | FILTERS 先删后建 + MERGE；INPUT_TO 仅值输入 |
| `service/impl/ImpactAnalysisServiceImpl.java` | 双通道遍历、标签聚合、深度计算、悬空清理适配 |
| `dto/ImpactAnalysisVO.java` | impactKind / 标签字段 |
| `frontend/src/components/impact/impactListUtils.js` | 双通道标签聚合 |
| `frontend/src/components/impact/ImpactEdgeDetail.vue` | role 中文 + 颜色判断修复 |
| `frontend/src/components/impact/ImpactPathDialog.vue` | FILTERS 指向 Table 节点虚线 |
| `frontend/src/views/ImpactAnalysis.vue` | 结果标签样式 + 上游双分区 |
