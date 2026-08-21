# 条件影响双通道重构（值血缘 / 行血缘分离）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 ON/WHERE 条件字段从值血缘（Transform INPUT_TO）中剥离，建立独立行血缘通道（Column -[:FILTERS]-> Table），消除影响分析假阳性、上游溯源噪音、图边暴涨、虚假共祖四个缺陷，同时保留行影响作为正式分析结果。

**架构：** 解析期由 FieldMappingExtractor 独立提取条件字段（`extractConditionFields`），经 `LineageEdge.conditionFields` 落库 MySQL 新表 `table_block_condition`；Neo4j 同步为 `FILTERS` 边（条件列→目标表，每块每字段 1 条）。影响分析查询期双通道展开：值通道走 INPUT_TO/OUTPUT_TO/DERIVED_FROM，行通道从起点列沿 FILTERS 1 跳到表、查询期展开表内全部列为行影响（ROW_IMPACT），再沿值通道继续传播；前端标签聚合为 `值影响` / `行过滤·JOIN` / `行过滤·WHERE`。

**技术栈：** Java 17 / Spring Boot 3 / MyBatis-Plus / JsqlParser 4.9 / Neo4j（spring-data-neo4j Neo4jClient）/ Vue 3 + Element Plus + G6

**关联设计文档：** `docs/superpowers/specs/2026-08-21-condition-dual-channel-lineage-design.md`（已批准）

**重要环境说明：**
- 工作区有大量**未提交**的历史变更（与本计划无关）。每个任务的 Commit 步骤只 `git add` 该任务明确列出的文件，禁止 `git add -A`。
- `LineageExtractorTest` 是 `@SpringBootTest`，运行需本地 MySQL（application.yml 已配置）可用。
- 前端无单测框架，验证方式为 `npm run build` + 任务 8 的浏览器集成验证。
- 行尾注意：所有 `.bat`/`.ps1` 不改动；新建文件均为 UTF-8。

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `sql/init.sql` | 修改 | 追加 `table_block_condition` 建表 DDL |
| `sql/migration/2026-08-21-table_block_condition.sql` | 创建 | 已有环境的独立迁移片段 |
| `src/main/java/com/hb/extractor/parser/dto/ConditionField.java` | 创建 | 条件字段 DTO（行血缘） |
| `src/main/java/com/hb/extractor/parser/dto/LineageEdge.java` | 修改 | 新增 `conditionFields` 字段 |
| `src/main/java/com/hb/extractor/entity/TableBlockCondition.java` | 创建 | 新表实体 |
| `src/main/java/com/hb/extractor/mapper/TableBlockConditionMapper.java` | 创建 | 新表 Mapper |
| `src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java` | 修改 | 删除条件 TransformInput 附加循环；新增 `extractConditionFields` |
| `src/main/java/com/hb/extractor/parser/LineageExtractor.java` | 修改 | 条件字段挂到块内对应源表边 |
| `src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java` | 修改 | `table_block_condition` 先删后建落库 |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | 修改 | FILTERS 先删后建 + MERGE |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | 修改 | EdgeVO 扩展 impactKind/clause；新增 RowFilterVO + rowFilters |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 修改 | 双通道遍历 + 上游条件分区查询 |
| `frontend/src/components/impact/impactListUtils.js` | 修改 | 双通道标签聚合（移除角色跳变） |
| `frontend/src/components/impact/ImpactResultTabs.vue` | 修改 | 标签样式判断 |
| `frontend/src/components/impact/ImpactStatsDetailDialog.vue` | 修改 | 标签样式判断 |
| `frontend/src/components/impact/ImpactPathDialog.vue` | 修改 | TABLE 节点渲染 + ROW_IMPACT 边隐藏 |
| `frontend/src/components/impact/ImpactEdgeDetail.vue` | 修改 | role 中文显示 + 颜色判断 bug 修复 + FILTERS 详情 |
| `frontend/src/views/ImpactAnalysis.vue` | 修改 | 上游模式"关联/过滤条件"分区 |
| `src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java` | 修改 | 重写条件输入测试 + 新增 extractConditionFields 测试 |
| `src/test/java/com/hb/extractor/parser/LineageExtractorTest.java` | 修改 | 条件字段挂边测试 |

---

### 任务 1：ConditionField DTO + table_block_condition 表/实体/Mapper + LineageEdge 扩展

**文件：**
- 创建：`sql/migration/2026-08-21-table_block_condition.sql`
- 创建：`src/main/java/com/hb/extractor/parser/dto/ConditionField.java`
- 创建：`src/main/java/com/hb/extractor/entity/TableBlockCondition.java`
- 创建：`src/main/java/com/hb/extractor/mapper/TableBlockConditionMapper.java`
- 修改：`sql/init.sql`（在 `table_transform_input` 建表段之后追加）
- 修改：`src/main/java/com/hb/extractor/parser/dto/LineageEdge.java`

- [ ] **步骤 1：init.sql 追加 DDL**

先 Read `sql/init.sql` 的 `table_transform_input` 建表段（约 L141 起）确认风格（反引号/ENGINE 注释段），在其后追加（风格与既有表一致）：

```sql
-- ----------------------------
-- SQL块条件字段归属（行血缘同步源，2026-08-21 双通道重构）
-- 每块每 (source_alias, source_field, role) 一行；source_table_short 为 null 表示未归属（保留行、跳过 Neo4j 同步）
-- ----------------------------
DROP TABLE IF EXISTS `table_block_condition`;
CREATE TABLE `table_block_condition` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `lineage_id` bigint NOT NULL COMMENT '关联 table_lineage.id',
  `proc_name` varchar(128) NOT NULL COMMENT '存储过程名',
  `sql_block_seq` int NOT NULL COMMENT 'SQL块序号',
  `source_table_short` varchar(128) DEFAULT NULL COMMENT '别名解析后的源表短名，null=未归属(跳过同步)',
  `source_alias` varchar(64) DEFAULT NULL COMMENT '条件字段别名',
  `source_field` varchar(128) NOT NULL COMMENT '条件字段名',
  `role` varchar(32) NOT NULL COMMENT 'join_condition / where_condition',
  `clause` varchar(512) DEFAULT NULL COMMENT '条件原文(截断)',
  `extract_time` datetime DEFAULT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_cond` (`lineage_id`,`source_alias`,`source_field`,`role`),
  KEY `idx_proc` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SQL块条件字段归属(行血缘同步源)';
```

注意：MySQL 唯一键含 NULL 列不去重，未归属（source_alias=null）行的去重由解析器内存 dedup 保证（任务 2），无需数据库层处理。

- [ ] **步骤 2：创建独立迁移片段**

创建 `sql/migration/2026-08-21-table_block_condition.sql`，内容 = 步骤 1 的 `DROP TABLE IF EXISTS` + `CREATE TABLE` 段（供已有环境单独执行）。

- [ ] **步骤 3：创建 ConditionField DTO**

`src/main/java/com/hb/extractor/parser/dto/ConditionField.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 块 ON/WHERE 条件字段（行血缘，独立于 FieldMapping 值输入）
 */
@Data
public class ConditionField {
    /** 归属解析后的源表短名；null=未归属（落库保留、Neo4j 同步跳过） */
    private String sourceTableShort;
    /** 条件字段别名（大写；可为 null） */
    private String sourceAlias;
    /** 条件字段名（小写） */
    private String sourceField;
    /** join_condition / where_condition */
    private String role;
    /** 条件原文（同键多条件分号拼接，截断 512 字符，仅展示用） */
    private String clause;
}
```

- [ ] **步骤 4：创建实体与 Mapper**

`src/main/java/com/hb/extractor/entity/TableBlockCondition.java`（模式照抄 `TableLineageFilter.java`）：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL块条件字段归属实体(行血缘同步源)
 */
@Data
@TableName("table_block_condition")
public class TableBlockCondition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 table_lineage.id */
    private Long lineageId;

    /** 存储过程名(冗余) */
    private String procName;

    /** SQL块序号 */
    private Integer sqlBlockSeq;

    /** 别名解析后的源表短名，null=未归属(跳过同步) */
    private String sourceTableShort;

    /** 条件字段别名 */
    private String sourceAlias;

    /** 条件字段名 */
    private String sourceField;

    /** join_condition / where_condition */
    private String role;

    /** 条件原文(截断) */
    private String clause;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
```

`src/main/java/com/hb/extractor/mapper/TableBlockConditionMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableBlockCondition;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL块条件字段归属 Mapper
 */
@Mapper
public interface TableBlockConditionMapper extends BaseMapper<BlockConditionTable> {
}
```

**笔误修正：** 上面接口泛型应为 `TableBlockCondition`：

```java
public interface TableBlockConditionMapper extends BaseMapper<TableBlockCondition> {
}
```

- [ ] **步骤 5：LineageEdge 新增 conditionFields**

`LineageEdge.java` 在 `mainTable` 字段后追加：

```java
    /** 本块 ON/WHERE 条件字段（行血缘；按 sourceTableShort 归属到本边，未归属挂块内第一条边） */
    private java.util.List<ConditionField> conditionFields = new java.util.ArrayList<>();
```

- [ ] **步骤 6：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 7：Commit**

```bash
git add sql/init.sql sql/migration/2026-08-21-table_block_condition.sql src/main/java/com/hb/extractor/parser/dto/ConditionField.java src/main/java/com/hb/extractor/parser/dto/LineageEdge.java src/main/java/com/hb/extractor/entity/TableBlockCondition.java src/main/java/com/hb/extractor/mapper/TableBlockConditionMapper.java
git commit -m "feat(lineage): table_block_condition table + ConditionField DTO for row-lineage channel"
```

---

### 任务 2：FieldMappingExtractor 条件字段独立提取（TDD）

**文件：**
- 修改：`src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java`
- 修改：`src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java`

- [ ] **步骤 1：改写失败测试（值输入不再含条件字段）**

将现有测试 `testConditionFields_JoinOnAddedToInputs`（约 L415-L441）整体替换为：

```java
    @Test
    void testConditionFields_ExcludedFromValueInputs() {
        // 双通道重构后：ON/WHERE 条件字段不再附加到 FieldMapping.inputs（行血缘独立通道）
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a, t1.b FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t2.balance > 0";
        java.util.Map<String, String> aliasMap = new java.util.HashMap<>();
        aliasMap.put("T1", "tab1");
        aliasMap.put("T2", "tab2");

        List<FieldMapping> result = extractor.extract(
            sql, "target_tbl", aliasMap, java.util.Arrays.asList("a", "b"));

        assertEquals(2, result.size());
        for (FieldMapping fm : result) {
            // 值输入仅 SELECT 表达式自身字段，无任何 join_condition/where_condition
            assertTrue(fm.getInputs().stream().noneMatch(ti ->
                            "join_condition".equals(ti.getRole()) || "where_condition".equals(ti.getRole())),
                    "FieldMapping " + fm.getTargetField() + " 的值输入不应包含条件字段");
            assertEquals(1, fm.getInputs().size(), "t1.a / t1.b 各只有 1 个值输入");
        }
    }
```

- [ ] **步骤 2：新增 extractConditionFields 测试**

在同一测试类的"ON/WHERE 条件字段"分节追加（需要 `import com.hb.extractor.parser.dto.ConditionField;` 与 `import static org.mockito.ArgumentMatchers.any;`、`import static org.mockito.Mockito.when;`、`import org.mockito.Mockito;`）：

```java
    @Test
    void testExtractConditionFields_JoinAndWhereWithAttribution() throws Exception {
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t2.balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        // t1.a / t2.b 为 join_condition，t2.balance 为 where_condition
        assertEquals(3, cfs.size());
        ConditionField t2b = cfs.stream()
                .filter(c -> "b".equals(c.getSourceField()) && "join_condition".equals(c.getRole()))
                .findFirst().orElseThrow();
        assertEquals("T2", t2b.getSourceAlias());
        assertEquals("tab2", t2b.getSourceTableShort());
        assertNotNull(t2b.getClause(), "join 条件应携带条件原文");

        ConditionField balance = cfs.stream()
                .filter(c -> "balance".equals(c.getSourceField()))
                .findFirst().orElseThrow();
        assertEquals("where_condition", balance.getRole());
        assertEquals("tab2", balance.getSourceTableShort());
        assertTrue(balance.getClause().replace(" ", "").toUpperCase().contains("BALANCE>0"));
    }

    @Test
    void testExtractConditionFields_SingleTableNoAliasFallback() throws Exception {
        String sql = "INSERT INTO tgt SELECT a FROM tab1 WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        assertEquals(1, cfs.size());
        assertEquals("where_condition", cfs.get(0).getRole());
        assertEquals("balance", cfs.get(0).getSourceField());
        // 无 alias 单表兜底：归属唯一源表
        assertEquals("tab1", cfs.get(0).getSourceTableShort());
    }

    @Test
    void testExtractConditionFields_MultiTableNoAlias_DdlUniqueMatch() throws Exception {
        // 多表无 alias：按 table_column_meta DDL 字段唯一匹配归属
        com.hb.extractor.mapper.TableColumnMetaMapper metaMapper =
                Mockito.mock(com.hb.extractor.mapper.TableColumnMetaMapper.class);
        when(metaMapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> w = inv.getArgument(0);
            String tableName = String.valueOf(w.getParamNameValuePairs().values().iterator().next());
            if ("tab1".equals(tableName)) {
                com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
                m.setTableName("tab1"); m.setColumnName("K");
                return java.util.List.of(m);
            }
            if ("tab2".equals(tableName)) {
                com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
                m.setTableName("tab2"); m.setColumnName("BALANCE");
                return java.util.List.of(m);
            }
            return java.util.Collections.emptyList();
        });
        setField(extractor, "tableColumnMetaMapper", metaMapper);

        String sql = "INSERT INTO tgt SELECT a FROM tab1 JOIN tab2 ON k = k WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        // balance 仅 tab2 有 → 唯一匹配；k 仅 tab1 有 → 唯一匹配
        ConditionField balance = cfs.stream().filter(c -> "balance".equals(c.getSourceField())).findFirst().orElseThrow();
        assertEquals("tab2", balance.getSourceTableShort());
        ConditionField k = cfs.stream().filter(c -> "k".equals(c.getSourceField())).findFirst().orElseThrow();
        assertEquals("tab1", k.getSourceTableShort());
    }

    @Test
    void testExtractConditionFields_MultiMatchUnowned() throws Exception {
        // DDL 多重匹配：sourceTableShort=null（落库保留、同步跳过）
        com.hb.extractor.mapper.TableColumnMetaMapper metaMapper =
                Mockito.mock(com.hb.extractor.mapper.TableColumnMetaMapper.class);
        when(metaMapper.selectList(any())).thenAnswer(inv -> {
            com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
            m.setTableName("both"); m.setColumnName("BALANCE");
            return java.util.List.of(m);
        });
        setField(extractor, "tableColumnMetaMapper", metaMapper);

        String sql = "INSERT INTO tgt SELECT a FROM tab1 JOIN tab2 ON k = k WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);
        ConditionField balance = cfs.stream().filter(c -> "balance".equals(c.getSourceField())).findFirst().orElseThrow();
        assertNull(balance.getSourceTableShort(), "DDL 多重匹配应不归属");
    }
```

- [ ] **步骤 3：运行测试验证失败**

运行：`mvn test -Dtest=FieldMappingExtractorTest -q`
预期：FAIL —— `testConditionFields_ExcludedFromValueInputs` 报"值输入不应包含条件字段"（旧逻辑仍附加条件输入）；`extractConditionFields` 方法不存在编译失败

- [ ] **步骤 4：实现——删除条件输入附加循环**

`FieldMappingExtractor.java` 删除 `extract()` 中整段"【新增】为每个 FieldMapping 添加 ON/WHERE 条件字段的 TransformInput"（当前 L167-L216，从注释行 `// 【新增】为每个 FieldMapping 添加 ON/WHERE 条件字段的 TransformInput` 到 `return result;` 之前的整段 `if (select != null && !result.isEmpty()) { ... }` 块）。

- [ ] **步骤 5：实现——新增 extractConditionFields**

在 `extract()` 方法之后新增（复用现有 `SQL_KEYWORDS`、`buildDdlCache`、`matchFieldToTable`，不改 JsqlSqlAnalyzer）：

```java
    /**
     * 提取 SQL 块的 ON/WHERE 条件字段（行血缘，独立于 FieldMapping 值输入）。
     * 归属解析策略与值输入一致：alias 命中 > 单表兜底 > DDL 字段唯一匹配；
     * 归属失败（多重/无匹配）sourceTableShort=null：落库保留、Neo4j 同步跳过。
     * key=(alias, field, role) 去重，同键多条件 clause 分号拼接、截断 512 字符。
     */
    public List<ConditionField> extractConditionFields(PlainSelect select,
                                                       Map<String, String> aliasMap) {
        List<ConditionField> result = new ArrayList<>();
        if (select == null || aliasMap == null || aliasMap.isEmpty()) return result;

        // 复用 FilterInfo（携带 clause 原文），JOIN → join_condition / WHERE → where_condition
        List<JsqlSqlAnalyzer.FilterInfo> filters = new ArrayList<>();
        filters.addAll(analyzer.extractJoinFilters(select));
        filters.addAll(analyzer.extractWhereFilters(select));
        if (filters.isEmpty()) return result;

        java.util.Map<String, java.util.Set<String>> ddlCache = null;
        java.util.Map<String, ConditionField> dedup = new java.util.LinkedHashMap<>();
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            if (fi == null || fi.fields == null) continue;
            String role = "JOIN".equalsIgnoreCase(fi.filterType) ? "join_condition" : "where_condition";
            for (JsqlSqlAnalyzer.ColumnRef ref : fi.fields) {
                if (ref.field == null) continue;
                if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
                if (ref.field.matches("\\d+")) continue;
                String key = (ref.alias != null ? ref.alias + "." : "") + ref.field + "/" + role;
                ConditionField cf = dedup.get(key);
                if (cf == null) {
                    cf = new ConditionField();
                    cf.setSourceAlias(ref.alias);
                    cf.setSourceField(ref.field);
                    cf.setRole(role);
                    dedup.put(key, cf);
                }
                if (fi.clause != null && !fi.clause.isEmpty()) {
                    cf.setClause(cf.getClause() == null ? fi.clause : cf.getClause() + ";" + fi.clause);
                }
            }
        }
        for (ConditionField cf : dedup.values()) {
            if (cf.getClause() != null && cf.getClause().length() > 512) {
                cf.setClause(cf.getClause().substring(0, 512));
            }
            if (cf.getSourceAlias() != null && aliasMap.containsKey(cf.getSourceAlias())) {
                cf.setSourceTableShort(aliasMap.get(cf.getSourceAlias()));
            } else if (aliasMap.size() == 1) {
                String onlyTable = aliasMap.values().iterator().next();
                if (onlyTable != null) cf.setSourceTableShort(onlyTable);
            } else if (aliasMap.size() > 1) {
                if (ddlCache == null) ddlCache = buildDdlCache(aliasMap.values());
                cf.setSourceTableShort(matchFieldToTable(cf.getSourceField(), ddlCache));
            }
            result.add(cf);
        }
        return result;
    }
```

同时补充 import：`import com.hb.extractor.parser.dto.ConditionField;`

- [ ] **步骤 6：运行测试验证通过**

运行：`mvn test -Dtest=FieldMappingExtractorTest -q`
预期：PASS（全部用例，含原有用例无回归）

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java
git commit -m "refactor(parser): extract condition fields standalone, stop attaching them to FieldMapping inputs"
```

---

### 任务 3：LineageExtractor 条件字段挂边（TDD）

**文件：**
- 修改：`src/test/java/com/hb/extractor/parser/LineageExtractorTest.java`
- 修改：`src/main/java/com/hb/extractor/parser/LineageExtractor.java`

- [ ] **步骤 1：编写失败测试**

在 `LineageExtractorTest` 追加（需要 `import com.hb.extractor.parser.dto.ConditionField;`）：

```java
    @Test
    void testConditionFieldsAttachedToEdges() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "JOIN {FDM}.F_B b ON a.cust_id = b.id WHERE b.balance > 0";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        // b.id(join) / b.balance(where) 归属 f_b 边；a.cust_id(join) 归属 f_a 边
        LineageEdge fBEdge = result.getEdges().stream()
                .filter(e -> "f_b".equals(e.getSourceTableShort())).findFirst().orElse(null);
        assertNotNull(fBEdge, "应有 f_b 源表边");
        assertNotNull(fBEdge.getConditionFields());
        assertTrue(fBEdge.getConditionFields().stream().anyMatch(cf ->
                        "id".equals(cf.getSourceField()) && "join_condition".equals(cf.getRole())),
                "b.id 应为 f_b 边的 join_condition");
        assertTrue(fBEdge.getConditionFields().stream().anyMatch(cf ->
                        "balance".equals(cf.getSourceField()) && "where_condition".equals(cf.getRole())),
                "b.balance 应为 f_b 边的 where_condition");

        LineageEdge fAEdge = result.getEdges().stream()
                .filter(e -> "f_a".equals(e.getSourceTableShort())).findFirst().orElse(null);
        assertNotNull(fAEdge, "应有 f_a 源表边");
        assertTrue(fAEdge.getConditionFields().stream().anyMatch(cf ->
                        "cust_id".equals(cf.getSourceField()) && "join_condition".equals(cf.getRole())),
                "a.cust_id 应为 f_a 边的 join_condition");
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=LineageExtractorTest#testConditionFieldsAttachedToEdges -q`（需本地 MySQL 可用）
预期：FAIL —— `getConditionFields()` 为空列表

- [ ] **步骤 3：实现**

`LineageExtractor.parseBlock()` 中，在"字段映射"段（`if (("INSERT SELECT".equals(operation) || "CREATE TEMP".equals(operation)) && sourceSelect != null) { ... }`）之后追加：

```java
        // 条件字段（行血缘）：独立于字段映射提取，归属到本块对应源表的 edge
        if (("INSERT SELECT".equals(operation) || "CREATE TEMP".equals(operation)) && sourceSelect != null
                && result.getEdges().size() > edgeIndexBase) {
            List<ConditionField> condFields =
                    fieldMappingExtractor.extractConditionFields(sourceSelect, aliasMap);
            for (ConditionField cf : condFields) {
                LineageEdge owner = null;
                // 优先归属 sourceTableShort 命中的边
                if (cf.getSourceTableShort() != null) {
                    for (int i = edgeIndexBase; i < result.getEdges().size(); i++) {
                        LineageEdge e = result.getEdges().get(i);
                        if (cf.getSourceTableShort().equals(e.getSourceTableShort())) {
                            owner = e;
                            break;
                        }
                    }
                }
                // 未命中（含未归属）：挂到本块第一条边，保证 clause 仍可落库查询
                if (owner == null) owner = result.getEdges().get(edgeIndexBase);
                owner.getConditionFields().add(cf);
            }
        }
```

补充 import：`import com.hb.extractor.parser.dto.ConditionField;`

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -Dtest=LineageExtractorTest -q`
预期：PASS（全部用例）

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/LineageExtractor.java src/test/java/com/hb/extractor/parser/LineageExtractorTest.java
git commit -m "feat(parser): attach block condition fields to lineage edges for row-lineage persistence"
```

---

### 任务 4：ProcExtractServiceImpl 落库 table_block_condition

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java`

- [ ] **步骤 1：注入 Mapper + 级联删除**

新增 import：

```java
import com.hb.extractor.entity.TableBlockCondition;
import com.hb.extractor.mapper.TableBlockConditionMapper;
import com.hb.extractor.parser.dto.ConditionField;
```

在 `tableTransformInputMapper` 字段旁新增：

```java
    @Autowired
    private TableBlockConditionMapper tableBlockConditionMapper;
```

`deleteProc()`（约 L313 `tableTransformInputMapper.delete(...)` 之后）与 `saveMetadata()` 的"先删旧血缘"段（约 L427-428 同位置）各追加：

```java
        tableBlockConditionMapper.delete(new LambdaQueryWrapper<TableBlockCondition>()
                .eq(TableBlockCondition::getProcName, procName));
```

- [ ] **步骤 2：插入条件字段行**

`saveMetadata()` 中"插入 edges"循环结束后（`lineageRecords` 与 `lineageResult.getEdges()` 同序同长），"插入 filters"段之前追加：

```java
            // 插入 SQL 块条件字段（行血缘）：行数=条件字段数，与目标列数无关（消除 N×M）
            for (int i = 0; i < lineageResult.getEdges().size(); i++) {
                LineageEdge edge = lineageResult.getEdges().get(i);
                if (edge.getConditionFields() == null || edge.getConditionFields().isEmpty()) continue;
                for (ConditionField cf : edge.getConditionFields()) {
                    TableBlockCondition record = new TableBlockCondition();
                    record.setLineageId(lineageRecords.get(i).getId());
                    record.setProcName(procName);
                    record.setSqlBlockSeq(edge.getSqlBlockSeq());
                    record.setSourceTableShort(cf.getSourceTableShort());
                    record.setSourceAlias(cf.getSourceAlias());
                    record.setSourceField(cf.getSourceField());
                    record.setRole(cf.getRole());
                    record.setClause(cf.getClause());
                    record.setExtractTime(now);
                    tableBlockConditionMapper.insert(record);
                }
            }
```

说明：唯一键冲突不可能发生——解析端已按 `(alias, field, role)` dedup，且同块每源表仅一条边；无需 try-catch 回退。

- [ ] **步骤 3：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS

（落库正确性在任务 8 重解析后用 SQL 计数验证，服务层为 Spring 事务方法，不建 DB 单测。）

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java
git commit -m "feat(extract): persist table_block_condition rows with lineage delete-rebuild"
```

---

### 任务 5：Neo4jSyncServiceImpl FILTERS 边同步

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`

- [ ] **步骤 1：注入 Mapper + 先删后建**

新增 import：

```java
import com.hb.extractor.entity.TableBlockCondition;
import com.hb.extractor.mapper.TableBlockConditionMapper;
```

在类字段区新增：

```java
    @Autowired
    private TableBlockConditionMapper tableBlockConditionMapper;
```

`deleteProcData()` 中第 2 步（删除 MAPS_TO）之后追加：

```java
            // 2.5 删除该 proc 的 FILTERS 关系（行血缘，重建）
            neo4jClient.query("MATCH ()-[r:FILTERS {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
```

- [ ] **步骤 2：syncProc 写入 FILTERS**

`syncProc()` 中"DDL 补全字段"循环（`for (String fullName : involvedTables) { ... ddlParser.parse ... }`）之后、`updateSourceTableTypes()` 之前追加：

```java
            // 写入 FILTERS 关系（行血缘）：条件列 → 目标表（每块每字段 1 条，M 而非 N×M）
            List<TableBlockCondition> blockConditions = tableBlockConditionMapper.selectList(
                    new LambdaQueryWrapper<TableBlockCondition>()
                            .eq(TableBlockCondition::getProcName, procName)
                            .isNotNull(TableBlockCondition::getSourceTableShort));
            for (TableBlockCondition bc : blockConditions) {
                TableLineage edge = findEdgeById(edges, bc.getLineageId());
                if (edge == null) continue;
                String sourceTableFull = findTableFullName(edges, bc.getSourceTableShort());
                if (sourceTableFull == null) {
                    log.warn("FILTERS 跳过: 源表 {} 不在本 proc 血缘边中 proc={} field={}",
                            bc.getSourceTableShort(), procName, bc.getSourceField());
                    continue;
                }
                String sourceColKey = toColKey(sourceTableFull, bc.getSourceField());
                mergeColumnNode(sourceColKey, bc.getSourceField(), sourceTableFull);
                mergeBelongsRelationship(sourceColKey, sourceTableFull);
                createFiltersRelationship(sourceColKey, edge.getTargetTable(), bc.getRole(),
                        procName, bc.getSqlBlockSeq(), bc.getClause(), bc.getSourceAlias());
            }
```

- [ ] **步骤 3：新增 createFiltersRelationship 私有方法**

放在 `createInputToRelationship`（约 L517）旁：

```java
    /**
     * 创建 FILTERS 关系: Column -[:FILTERS {proc_name, sql_block_seq, role}]-> Table
     * MERGE 键 = (源列, 目标表, proc_name, sql_block_seq, role)，同键重建不重复
     */
    private void createFiltersRelationship(String sourceColKey, String targetTableName,
                                           String role, String procName,
                                           Integer sqlBlockSeq, String clause, String sourceAlias) {
        Map<String, Object> params = new HashMap<>();
        params.put("colKey", sourceColKey);
        params.put("tableName", targetTableName);
        params.put("role", role);
        params.put("procName", procName);
        params.put("seq", sqlBlockSeq);
        params.put("clause", clause);
        params.put("sourceAlias", sourceAlias);
        neo4jClient.query(
                        "MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName}) " +
                        "MERGE (c)-[r:FILTERS {proc_name: $procName, sql_block_seq: $seq, role: $role}]->(t) " +
                        "SET r.clause = $clause, r.source_alias = $sourceAlias")
                .bindAll(params).run();
    }
```

说明：旧条件 INPUT_TO 无需单独清理——`deleteProcData` 对 Transform 节点 DETACH DELETE，条件 INPUT_TO 挂在 Transform 上随之消失；全量重解析后图中自然无条件 INPUT_TO（任务 8 验证）。

- [ ] **步骤 4：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java
git commit -m "feat(neo4j): sync FILTERS row-lineage edges from table_block_condition"
```

---

### 任务 6：影响分析双通道后端

**文件：**
- 修改：`src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`

- [ ] **步骤 1：VO 扩展**

`ImpactAnalysisVO.java`：

1) 顶层字段（`reportImpacts` 之后）：

```java
    /** 上游模式：目标列所在表的行血缘条件分区（该表行集受哪些条件控制） */
    private List<RowFilterVO> rowFilters;
```

2) `EdgeVO` 追加两个字段（`expression` 之后）：

```java
        /** 影响通道: VALUE(值派生) / ROW(行过滤) */
        private String impactKind;
        /** FILTERS 边的条件原文 */
        private String clause;
```

3) 新增内部类（`ConditionClause` 类旁）：

```java
    @Data
    public static class RowFilterVO {
        /** 条件字段 col_key（schema.table.field） */
        private String fieldColKey;
        /** 条件字段名 */
        private String fieldName;
        /** join_condition / where_condition */
        private String role;
        /** 条件原文 */
        private String clause;
        /** 所属存储过程 */
        private String procName;
        /** SQL块序号 */
        private Integer sqlBlockSeq;
    }
```

- [ ] **步骤 2：移除 DIRECT 分支的条件字段特判**

`ImpactAnalysisServiceImpl.analyzeFieldLevel()` 中 DIRECT 处理的内层输入循环（当前约 L761-787），将：

```java
                            for (Map<String, Object> input : directInputs) {
                                String colKey = (String) input.get("colKey");
                                if (colKey == null) continue;
                                String role = (String) input.get("role");
                                boolean isCondition = "join_condition".equals(role) || "where_condition".equals(role);

                                if (!nodeIds.contains(colKey)) {
                                    if (isCondition) {
                                        // 条件字段: 补全节点, 创建 DERIVED_FROM 边
                                        ensureFieldNode(colKey, nodeIds, nodes);
                                    } else {
                                        // SELECT 列辅助字段: 跳过
                                        continue;
                                    }
                                }

                                ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                                edge.setSource(colKey);
                                edge.setTarget(outputColKey);
                                edge.setEdgeType("DERIVED_FROM");
                                edge.setSourceAlias((String) input.get("alias"));
                                edge.setProcName(procName);
                                if (isCondition) {
                                    edge.setRole(role);
                                }
                                edges.add(edge);
                            }
```

替换为（图中已无条件 INPUT_TO，条件特判为死代码）：

```java
                            for (Map<String, Object> input : directInputs) {
                                String colKey = (String) input.get("colKey");
                                if (colKey == null) continue;
                                // SELECT 列辅助字段（不在路径上）: 跳过
                                if (!nodeIds.contains(colKey)) continue;

                                ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                                edge.setSource(colKey);
                                edge.setTarget(outputColKey);
                                edge.setEdgeType("DERIVED_FROM");
                                edge.setSourceAlias((String) input.get("alias"));
                                edge.setProcName(procName);
                                edge.setImpactKind("VALUE");
                                edges.add(edge);
                            }
```

同时给非 DIRECT 分支的三处边构造补 `edge.setImpactKind("VALUE");`（INPUT_TO 边、OUTPUT_TO 边各一处）。

- [ ] **步骤 3：新增行影响通道展开方法**

在 `analyzeFieldLevel()` 之后新增：

```java
    /**
     * 行影响通道（下游）：起点列 --FILTERS--> 受影响表(1跳) → 表内所有列打行影响标签(2跳, 查询期展开)
     * 展开列作为 layer=2 种子返回，由值通道 BFS 继续向下游传播（标签保持行影响，前端聚合）。
     * 不二次经过 FILTERS：仅展开起点列的直接 FILTERS 边；起点自身所在表不重复展开。
     */
    private Map<Integer, Set<String>> expandRowImpactChannel(String startColKey, int maxDepth,
                                                             Set<String> nodeIds,
                                                             List<ImpactAnalysisVO.NodeVO> nodes,
                                                             List<ImpactAnalysisVO.EdgeVO> edges) {
        Map<Integer, Set<String>> seeds = new HashMap<>();
        if (maxDepth < 2) return seeds;
        String startTable = extractTableFromId(startColKey);
        Collection<Map<String, Object>> filterRows;
        try {
            filterRows = neo4jClient.query(
                            "MATCH (c:Column {col_key: $ck})-[f:FILTERS]->(t:Table) " +
                            "RETURN t.name AS tableName, t.short_name AS shortName, " +
                            "  f.role AS role, f.clause AS clause, f.proc_name AS procName, " +
                            "  f.sql_block_seq AS seq")
                    .bind(startColKey).to("ck").fetch().all();
        } catch (Exception e) {
            log.error("行影响通道 FILTERS 查询失败: {}", e.getMessage());
            return seeds;
        }
        Set<String> nextLayerCols = new LinkedHashSet<>();
        for (Map<String, Object> fr : filterRows) {
            String tableName = (String) fr.get("tableName");
            if (tableName == null || tableName.equals(startTable)) continue;
            String role = (String) fr.get("role");
            String procName = (String) fr.get("procName");
            // 表节点（depth=1）
            if (nodeIds.add(tableName)) {
                ImpactAnalysisVO.NodeVO tNode = new ImpactAnalysisVO.NodeVO();
                tNode.setId(tableName);
                Object shortName = fr.get("shortName");
                tNode.setLabel(shortName != null ? shortName.toString() : tableName);
                tNode.setNodeLevel("TABLE");
                tNode.setLayer(extractLayerFromId(tableName));
                tNode.setTableFullName(tableName);
                tNode.setDepth(1);
                nodes.add(tNode);
            }
            // FILTERS 边（起点列 → 表）
            ImpactAnalysisVO.EdgeVO fEdge = new ImpactAnalysisVO.EdgeVO();
            fEdge.setSource(startColKey);
            fEdge.setTarget(tableName);
            fEdge.setEdgeType("FILTERS");
            fEdge.setRole(role);
            fEdge.setClause((String) fr.get("clause"));
            fEdge.setProcName(procName);
            fEdge.setSqlBlockSeq(fr.get("seq") != null ? ((Number) fr.get("seq")).intValue() : null);
            fEdge.setImpactKind("ROW");
            edges.add(fEdge);
            // 表内所有列：行影响展开（depth=2）+ ROW_IMPACT 边（表→列，不落库、仅查询期）
            Collection<Map<String, Object>> cols;
            try {
                cols = neo4jClient.query(
                                "MATCH (col:Column)-[:BELONGS_TO]->(t:Table {name: $tn}) " +
                                "RETURN col.col_key AS colKey, col.name AS name, col.table_full_name AS tableFullName")
                        .bind(tableName).to("tn").fetch().all();
            } catch (Exception e) {
                log.warn("行影响列展开失败 table={}: {}", tableName, e.getMessage());
                continue;
            }
            for (Map<String, Object> col : cols) {
                String colKey = (String) col.get("colKey");
                if (colKey == null || colKey.equals(startColKey)) continue;
                if (nodeIds.add(colKey)) {
                    nodes.add(buildFieldNode(col, colKey, 2));
                }
                ImpactAnalysisVO.EdgeVO rEdge = new ImpactAnalysisVO.EdgeVO();
                rEdge.setSource(tableName);
                rEdge.setTarget(colKey);
                rEdge.setEdgeType("ROW_IMPACT");
                rEdge.setRole(role);
                rEdge.setProcName(procName);
                rEdge.setImpactKind("ROW");
                edges.add(rEdge);
                nextLayerCols.add(colKey);
            }
        }
        if (!nextLayerCols.isEmpty()) seeds.put(2, nextLayerCols);
        return seeds;
    }
```

- [ ] **步骤 4：BFS 合并行通道种子**

`analyzeFieldLevel()` 中：

1) `for (String dir : dirs) {` 之前插入：

```java
            // 行影响通道（仅下游方向）：FILTERS 1 跳 + 表内列展开，展开列并入值通道 BFS
            Map<Integer, Set<String>> rowSeeds = expandRowImpactChannel(fieldFullName, depth, nodeIds, nodes, edges);
```

2) 层循环开头（`for (int layer = 1; layer <= depth; layer++) {` 内、原 `if (frontier.isEmpty()) break;` 之前）插入：

```java
                    Set<String> seedCols = downstream ? rowSeeds.get(layer) : null;
                    if (seedCols != null && !seedCols.isEmpty()) frontier.addAll(seedCols);
```

- [ ] **步骤 5：上游条件分区查询**

`analyze()` 的字段级分支改为：

```java
        if (isFieldLevel) {
            String fieldFullName = tableFullName + "." + request.getFieldName().toLowerCase();
            analyzeFieldLevel(fieldFullName, direction, depth, nodes, edges);
            // 上游/双向：附加条件分区（目标列所在表行集受哪些条件控制）
            if (!"DOWNSTREAM".equals(direction)) {
                vo.setRowFilters(queryRowFilters(fieldFullName));
            }
        } else {
```

新增私有方法（放在 `analyzeFieldLevel` 附近）：

```java
    /** 上游条件分区：目标列所在表的全部反向 FILTERS 边 */
    private List<ImpactAnalysisVO.RowFilterVO> queryRowFilters(String fieldColKey) {
        String table = extractTableFromId(fieldColKey);
        List<ImpactAnalysisVO.RowFilterVO> result = new ArrayList<>();
        try {
            Collection<Map<String, Object>> rows = neo4jClient.query(
                            "MATCH (c:Column)-[f:FILTERS]->(t:Table {name: $tn}) " +
                            "RETURN c.col_key AS colKey, c.name AS name, f.role AS role, f.clause AS clause, " +
                            "  f.proc_name AS procName, f.sql_block_seq AS seq " +
                            "ORDER BY f.proc_name, c.col_key")
                    .bind(table).to("tn").fetch().all();
            for (Map<String, Object> r : rows) {
                ImpactAnalysisVO.RowFilterVO rf = new ImpactAnalysisVO.RowFilterVO();
                rf.setFieldColKey((String) r.get("colKey"));
                rf.setFieldName((String) r.get("name"));
                rf.setRole((String) r.get("role"));
                rf.setClause((String) r.get("clause"));
                rf.setProcName((String) r.get("procName"));
                rf.setSqlBlockSeq(r.get("seq") != null ? ((Number) r.get("seq")).intValue() : null);
                result.add(rf);
            }
        } catch (Exception e) {
            log.error("rowFilters 查询失败: {}", e.getMessage());
        }
        return result;
    }
```

说明：`removeDanglingAuxNodes` 无需改动——TABLE 节点（depth=1）与行影响列（depth=2）均有深度；报表列溯源（ReportImpactServiceImpl 复用 UPSTREAM 分析）自动受益，无需改动。

- [ ] **步骤 6：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS（行为验证在任务 8 集成场景执行；本服务依赖 Neo4jClient，不建脱离图库的单测）

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java
git commit -m "feat(impact): dual-channel traversal with FILTERS row-impact expansion and upstream row-filters"
```

---

### 任务 7：前端双通道展示

**文件：**
- 修改：`frontend/src/components/impact/impactListUtils.js`
- 修改：`frontend/src/components/impact/ImpactResultTabs.vue`
- 修改：`frontend/src/components/impact/ImpactStatsDetailDialog.vue`
- 修改：`frontend/src/components/impact/ImpactPathDialog.vue`
- 修改：`frontend/src/components/impact/ImpactEdgeDetail.vue`
- 修改：`frontend/src/views/ImpactAnalysis.vue`

- [ ] **步骤 1：impactListUtils.js 标签体系与双通道传播**

1) 头部常量替换：

```js
const REASON_ORDER = ['值影响', '行过滤·JOIN', '行过滤·WHERE']
const FILTERS_ROLE_TO_REASON = {
  join_condition: '行过滤·JOIN',
  where_condition: '行过滤·WHERE'
}
const VALUE_REASON = '值影响'
```

删除旧 `ROLE_TO_REASON`。

2) `buildFieldImpactRows` 中 `const reasonOf = (e) => ROLE_TO_REASON[e.role] || '直接来源'` 替换为：

```js
  const reasonOf = (e) => FILTERS_ROLE_TO_REASON[e.role] || VALUE_REASON
```

3) 初始标签段（起点出边）在现有 `if (e.edgeType === 'INPUT_TO') {...} else {...}` 之前增加 FILTERS 分支，整体改为：

```js
  // 初始标签：起点出边（FILTERS 行通道 + 值通道）
  for (const e of (outEdges.get(startFieldId) || [])) {
    if (e.edgeType === 'FILTERS') {
      const reason = FILTERS_ROLE_TO_REASON[e.role] || '行过滤·WHERE'
      for (const re of (outEdges.get(e.target) || [])) {
        if (re.edgeType === 'ROW_IMPACT') push(re.target, reason)
      }
    } else if (e.edgeType === 'INPUT_TO') {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'TRANSFORM') continue
      for (const out of (transformOutputs.get(e.target) || [])) push(out, reasonOf(e))
    } else {
      const t = nodeMap.get(e.target)
      if (!t || t.nodeLevel !== 'FIELD') continue
      push(e.target, reasonOf(e))
    }
  }
```

4) 继承传播循环同样增加 FILTERS 分支（值边继承当前标签，不再有角色跳变——INPUT_TO 的 role 恒为 value）：

```js
  // 继承传播：FILTERS 行通道按角色打标；值边继承当前标签
  while (queue.length) {
    const [cur, reason] = queue.shift()
    for (const e of (outEdges.get(cur) || [])) {
      if (e.edgeType === 'FILTERS') {
        const rowReason = FILTERS_ROLE_TO_REASON[e.role] || reason
        for (const re of (outEdges.get(e.target) || [])) {
          if (re.edgeType === 'ROW_IMPACT') push(re.target, rowReason)
        }
      } else if (e.edgeType === 'INPUT_TO') {
        for (const out of (transformOutputs.get(e.target) || [])) push(out, reason)
      } else if (e.edgeType === 'DERIVED_FROM' || !e.edgeType) {
        const t = nodeMap.get(e.target)
        if (t && t.nodeLevel === 'FIELD') push(e.target, reason)
      }
    }
  }
```

5) 行生成的到达边判断（两处：主行段与临时补充段）由

```js
    if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO') continue
```

改为：

```js
    if (e.edgeType !== 'DERIVED_FROM' && e.edgeType !== 'OUTPUT_TO' && e.edgeType !== 'ROW_IMPACT') continue
```

（ROW_IMPACT 边的 `procName` 由后端取自 FILTERS 边，`target` 为字段节点，天然满足行 key 与 temp 过滤逻辑。）

6) `buildFieldSourceRows` 中两处 `ROLE_TO_REASON[e.role] || '直接来源'` 与一处 `ROLE_TO_REASON[e.role] || reason` 替换为 `VALUE_REASON` / `reason`（图中已无条件边，溯源值来源天然纯净；行内 reasons 恒为 ['值影响']）。函数头注释中"标签规则与下游一致——边带角色时跳变"一句删除。

- [ ] **步骤 2：标签样式判断**

`ImpactResultTabs.vue` L26 与 `ImpactStatsDetailDialog.vue` L33 的：

```vue
:type="r === '直接来源' ? 'success' : 'warning'"
```

均改为：

```vue
:type="r === '值影响' ? 'success' : 'warning'"
```

（Excel 导出在 ImpactResultTabs L83 使用 `r.reasons.join('+')`，标签自动更新，无需改动。）

- [ ] **步骤 3：ImpactPathDialog.vue 支持行通道渲染**

`toG6Data()` 修改：

1) 边列表过滤掉 ROW_IMPACT（表→列不逐列画边，列清单区用标签表达）：

```js
  const edges = sg.edges.filter(e => e.edgeType !== 'ROW_IMPACT').map((e, i) => {
```

2) 节点尺寸与配色对 TABLE 节点生效——`nodes` 映射中 `size` 行改为：

```js
    size: n.nodeLevel === 'TRANSFORM' ? 26 : [150, 36],
```

（保持不变即可，TABLE 走默认 rect；）在 `nodeColor(n)` 函数内为 `n.nodeLevel === 'TABLE'` 增加返回色（先读该函数再改，若无 nodeLevel 判断则在最前加）：

```js
  if (n.nodeLevel === 'TABLE') return '#b37feb'
```

FILTERS 边的橙色虚线 + ON/WHERE 标签：现有 `isCond` 判断（`e.role === 'join_condition' || e.role === 'where_condition'`）对 FILTERS 边直接生效，无需改动；图例文案 L28 改为：

```vue
        <span class="legend-item"><span class="line-dashed">- - -</span> 行影响(FILTERS: ON/WHERE)</span>
```

- [ ] **步骤 4：ImpactEdgeDetail.vue 修复 role 显示**

角色描述项（当前 L9-L11）替换为：

```vue
      <el-descriptions-item label="角色" v-if="edge.role">
        <el-tag :type="edge.role === 'join_condition' || edge.role === 'where_condition' ? 'warning' : 'success'" size="small">
          {{ edge.role === 'join_condition' ? 'ON 条件' : edge.role === 'where_condition' ? 'WHERE 条件' : '取值' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="条件原文" v-if="edge.clause">{{ edge.clause }}</el-descriptions-item>
```

（修复点：旧代码判断 `edge.role === 'condition'` 永假。）

- [ ] **步骤 5：ImpactAnalysis.vue 上游条件分区**

在"报表影响"分区（`<div class="propagation-section" v-if="result?.reportImpacts?.length">`）之前插入：

```vue
    <div class="propagation-section" v-if="isUpstream && result?.rowFilters?.length">
      <div class="section-header">
        <span class="section-title">关联 / 过滤条件（行血缘）</span>
        <span class="report-tip">目标表行集受以下条件控制；条件字段不参与列值计算</span>
      </div>
      <el-table :data="result.rowFilters" border size="small" style="width: 100%">
        <el-table-column prop="fieldName" label="条件字段" min-width="140" />
        <el-table-column label="角色" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'join_condition' ? 'warning' : 'danger'" size="small">
              {{ row.role === 'join_condition' ? 'ON 条件' : 'WHERE 条件' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clause" label="条件原文" min-width="260" show-overflow-tooltip />
        <el-table-column prop="procName" label="存储过程" min-width="160" show-overflow-tooltip />
        <el-table-column prop="sqlBlockSeq" label="SQL块" width="80" align="center" />
      </el-table>
    </div>
```

（复用既有 `propagation-section` / `section-header` / `section-title` / `report-tip` 样式。）

- [ ] **步骤 6：构建验证**

运行：`cd frontend; npm run build`
预期：构建成功无错误。随后确认产物落位（读 `frontend/vite.config.js` 的 `outDir`；若未指向 `src/main/resources/static`，将 `dist/` 内容复制过去，与现有 `src/main/resources/static/` 产物保持一致）。

- [ ] **步骤 7：Commit**

```bash
git add frontend/src/components/impact/impactListUtils.js frontend/src/components/impact/ImpactResultTabs.vue frontend/src/components/impact/ImpactStatsDetailDialog.vue frontend/src/components/impact/ImpactPathDialog.vue frontend/src/components/impact/ImpactEdgeDetail.vue frontend/src/views/ImpactAnalysis.vue
git commit -m "feat(frontend): dual-channel impact labels, row-filter upstream section and FILTERS graph rendering"
```

---

### 任务 8：迁移与集成验证

**文件：** 无代码修改（执行迁移与验证；发现问题回上游任务修复）

- [ ] **步骤 1：执行 MySQL 迁移**

用 application.yml 的连接信息对业务库执行 `sql/migration/2026-08-21-table_block_condition.sql`。

验证：

```sql
SHOW CREATE TABLE table_block_condition;
```

- [ ] **步骤 2：构建部署**

后端：`mvn package -DskipTests -q`，用现有 `startup.bat` 重启（勿改行尾）。
前端：确认任务 7 产物已在 `src/main/resources/static/`。

- [ ] **步骤 3：全量重解析（300 proc，断点续传）**

运行：`powershell -ExecutionPolicy Bypass -File .\reparse_all.ps1`
预期：300 个 proc 解析成功（299/300 量级，个别解析失败可跳过），每个 proc 解析后自动触发 Neo4j syncProc。

- [ ] **步骤 4：数据验证（MySQL + Neo4j）**

MySQL：

```sql
-- 条件字段行数 = 条件字段数 × 引用块数（量级远小于旧 N×M 的 table_transform_input 条件行）
SELECT COUNT(*) FROM table_block_condition;
SELECT COUNT(*) FROM table_block_condition WHERE source_table_short IS NULL;  -- 未归属行应少量存在
```

Neo4j（浏览器或 cypher-shell）：

```cypher
// 旧条件 INPUT_TO 应为 0（Transform DETACH DELETE 已清）
MATCH ()-[r:INPUT_TO]->() WHERE r.role IN ['join_condition','where_condition'] RETURN count(r);
// FILTERS 边量级 ≈ 条件字段 × 引用块数，且 > 0
MATCH ()-[r:FILTERS]->() RETURN count(r);
// 抽样：边属性完整（role/clause/proc_name/sql_block_seq）
MATCH (c:Column)-[r:FILTERS]->(t:Table) RETURN c.col_key, t.name, r.role, r.clause, r.proc_name LIMIT 10;
```

预期：第一条 = 0；第二条 > 0。

- [ ] **步骤 5：功能验证（页面/API，对照设计 §12.2）**

| # | 操作 | 期望 |
|---|------|------|
| 1 | 从某 WHERE 条件字段（如 `b.balance`）做下游字段级分析 | 其过滤的目标表全部列 reasons 含 `行过滤·WHERE` 且不含 `值影响`（除非该列同时被值派生）；链路图中该表节点经橙色虚线 FILTERS 边连接 |
| 2 | 目标列（如 `cust_id`）上游溯源 | 值来源清单不含条件字段；页面出现"关联 / 过滤条件（行血缘）"分区且数据正确 |
| 3 | 纯值链路字段（不涉及任何条件）下游分析 | 结果与重构前一致（回归：行数、层级、标签为 `值影响`） |
| 4 | Excel 导出 | "影响原因"列出现 `值影响` / `行过滤·JOIN` / `行过滤·WHERE` |
| 5 | 链路弹窗 | FILTERS 边橙色虚线指向表节点；行影响列不逐列画边；点击 FILTERS 边能看到条件原文 |

发现问题：解析/落库问题回任务 2-4，同步问题回任务 5，遍历问题回任务 6，展示问题回任务 7。

- [ ] **步骤 6：收尾 Commit（如有回改）+ 更新设计文档状态**

将设计文档头部 `状态：已批准，待实现` 改为 `状态：已实现（YYYY-MM-DD）`：

```bash
git add docs/superpowers/specs/2026-08-21-condition-dual-channel-lineage-design.md docs/superpowers/plans/2026-08-21-condition-dual-channel-lineage.md
git commit -m "docs: mark condition dual-channel lineage design implemented"
```

---

## 自检记录（编写时已核对）

1. **规格覆盖度：** 设计 §3(任务5) §4(任务1/4) §5(任务2/3) §6(任务5) §7(任务6) §8(任务6 步骤5+任务7 步骤5) §9(任务7) §10(任务8) §11 边界（起点表跳过/未归属保留/MERGE 单边/clause 拼接截断——内嵌于任务2/5/6 代码）§12(任务2/3 单测+任务8 集成) 均有对应任务。
2. **占位符扫描：** 无"待定/TODO/类似任务N"；所有代码步骤含完整代码。
3. **类型一致性：** `ConditionField` 五字段在任务1/2/3/4 间一致；`extractConditionFields(PlainSelect, Map)` 签名任务2定义、任务3调用一致；`createFiltersRelationship` 7 参签名任务5内自洽；`EdgeVO.impactKind/clause`、`RowFilterVO`、`rowFilters` 任务6定义、任务7使用一致；前端 `FILTERS`/`ROW_IMPACT`/`值影响`/`行过滤·JOIN`/`行过滤·WHERE` 字符串前后端一致。
