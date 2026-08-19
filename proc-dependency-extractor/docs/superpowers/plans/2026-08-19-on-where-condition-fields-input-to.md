# ON/WHERE 条件字段 INPUT_TO 关系增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 JOIN ON 和 WHERE 条件字段创建 INPUT_TO 关系（role=join_condition/where_condition），打通字段级下游影响分析的断裂链路。

**架构：** 解析阶段（FieldMappingExtractor）提取 ON/WHERE 条件字段，利用 aliasMap + DDL 字段匹配确定表归属，为每个 FieldMapping 创建 TransformInput 记录。Neo4j 同步时 MERGE 按 role 去重。影响分析 DIRECT 类型为条件字段补全节点并创建 DERIVED_FROM 边。

**技术栈：** Java 17, Spring Boot 3, MyBatis-Plus, JsqlParser 4.9, Neo4j, JUnit 5

**关联规格：** `docs/superpowers/specs/2026-08-19-on-where-condition-fields-input-to-design.md`

---

## 文件结构

| 文件 | 职责 | 操作 |
|------|------|------|
| `src/main/java/com/hb/extractor/parser/JsqlSqlAnalyzer.java` | SQL AST 解析工具 | 修改：新增 `extractConditionFields` |
| `src/test/java/com/hb/extractor/parser/JsqlSqlAnalyzerTest.java` | JsqlSqlAnalyzer 单元测试 | 创建 |
| `src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java` | 字段映射提取器 | 修改：条件字段处理 + `matchFieldToTable` |
| `src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java` | 字段映射单元测试 | 修改：新增条件字段测试 |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | Neo4j 同步服务 | 修改：MERGE 加 role |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 影响分析服务 | 修改：DIRECT 类型条件字段处理 |

---

## 任务 1：JsqlSqlAnalyzer 新增 extractConditionFields

**文件：**
- 修改：`src/main/java/com/hb/extractor/parser/JsqlSqlAnalyzer.java`（行 191-220 附近，extractAliasMap 方法之后）
- 创建：`src/test/java/com/hb/extractor/parser/JsqlSqlAnalyzerTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `src/test/java/com/hb/extractor/parser/JsqlSqlAnalyzerTest.java`：

```java
package com.hb.extractor.parser;

import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsqlSqlAnalyzerTest {

    private JsqlSqlAnalyzer createAnalyzer() throws Exception {
        JsqlSqlAnalyzer analyzer = new JsqlSqlAnalyzer();
        // 反射注入 SqlPlaceholderReplacer
        var replacer = new SqlPlaceholderReplacer();
        var field = JsqlSqlAnalyzer.class.getDeclaredField("placeholderReplacer");
        field.setAccessible(true);
        field.set(analyzer, replacer);
        return analyzer;
    }

    @Test
    void testExtractConditionFields_JoinOn() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a, t2.b FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt);
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.insert.Insert);
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = insert.getSelect();
        assertTrue(select.getSelectBody() instanceof PlainSelect);
        var plainSelect = (PlainSelect) select.getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertFalse(conditionFields.isEmpty(), "应提取到 ON 条件字段");
        // ON t1.a = t2.b → 两个条件字段
        boolean hasT1A = conditionFields.stream().anyMatch(r -> "T1".equals(r.alias) && "a".equals(r.field));
        boolean hasT2B = conditionFields.stream().anyMatch(r -> "T2".equals(r.alias) && "b".equals(r.field));
        assertTrue(hasT1A, "应包含 T1.a");
        assertTrue(hasT2B, "应包含 T2.b");
        // role 应为 join_condition
        assertTrue(conditionFields.stream().allMatch(r -> "join_condition".equals(r.role)),
            "ON 条件字段 role 应为 join_condition");
    }

    @Test
    void testExtractConditionFields_Where() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 WHERE t1.a > 100";
        Statement stmt = analyzer.parse(sql);
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var plainSelect = (PlainSelect) insert.getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertFalse(conditionFields.isEmpty());
        boolean hasT1A = conditionFields.stream().anyMatch(r -> "T1".equals(r.alias) && "a".equals(r.field));
        assertTrue(hasT1A);
        assertTrue(conditionFields.stream().allMatch(r -> "where_condition".equals(r.role)));
    }

    @Test
    void testExtractConditionFields_Mixed() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t1.a > 100";
        Statement stmt = analyzer.parse(sql);
        var plainSelect = (PlainSelect) ((net.sf.jsqlparser.statement.insert.Insert) stmt).getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        long joinCount = conditionFields.stream().filter(r -> "join_condition".equals(r.role)).count();
        long whereCount = conditionFields.stream().filter(r -> "where_condition".equals(r.role)).count();
        assertTrue(joinCount >= 2, "ON 条件字段至少 2 个");
        assertTrue(whereCount >= 1, "WHERE 条件字段至少 1 个");
    }

    @Test
    void testExtractConditionFields_None() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1";
        Statement stmt = analyzer.parse(sql);
        var plainSelect = (PlainSelect) ((net.sf.jsqlparser.statement.insert.Insert) stmt).getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertTrue(conditionFields.isEmpty(), "无 JOIN/WHERE 时应返回空列表");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`D:\HB\maven\bin\mvn.cmd test -Dtest=JsqlSqlAnalyzerTest -pl . -q`
预期：FAIL，报错 `method extractConditionFields(PlainSelect)` not found

- [ ] **步骤 3：实现 extractConditionFields 方法**

在 `JsqlSqlAnalyzer.java` 的 `extractAliasMap` 方法之后（约行 220 附近）新增：

```java
/**
 * 从 PlainSelect 提取 ON/WHERE 条件中的字段引用。
 * 复用 collectColumnRefs，role 设为 join_condition / where_condition。
 * @param select PlainSelect（INSERT SELECT / CREATE TEMP AS SELECT 的 SELECT 子句）
 * @return 条件字段列表（alias 大写, field 小写, role=join_condition/where_condition）
 */
public List<ColumnRef> extractConditionFields(PlainSelect select) {
    List<ColumnRef> result = new ArrayList<>();
    if (select == null) return result;
    // JOIN ON 条件
    if (select.getJoins() != null) {
        for (Join join : select.getJoins()) {
            if (join.getOnExpressions() != null) {
                for (Expression onExpr : join.getOnExpressions()) {
                    collectColumnRefs(onExpr, "join_condition", result);
                }
            } else if (join.getOnExpression() != null) {
                collectColumnRefs(join.getOnExpression(), "join_condition", result);
            }
        }
    }
    // WHERE 条件
    if (select.getWhere() != null) {
        collectColumnRefs(select.getWhere(), "where_condition", result);
    }
    return result;
}
```

确保文件顶部已 import `net.sf.jsqlparser.statement.select.Join` 和 `net.sf.jsqlparser.expression.Expression`（如未 import 则添加）。

- [ ] **步骤 4：运行测试验证通过**

运行：`D:\HB\maven\bin\mvn.cmd test -Dtest=JsqlSqlAnalyzerTest -q`
预期：PASS（4 个测试全通过）

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/JsqlSqlAnalyzer.java src/test/java/com/hb/extractor/parser/JsqlSqlAnalyzerTest.java
git commit -m "feat: add extractConditionFields to JsqlSqlAnalyzer for ON/WHERE condition fields"
```

---

## 任务 2：FieldMappingExtractor 条件字段处理 + DDL 匹配

**文件：**
- 修改：`src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java`
- 修改：`src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `FieldMappingExtractorTest.java` 末尾新增测试方法：

```java
@Test
void testConditionFields_JoinOnAddedToInputs() throws Exception {
    FieldMappingExtractor extractor = createExtractor();
    String sql = "INSERT INTO cdm.target_tbl SELECT t1.a, t1.b FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b";
    java.util.Map<String, String> aliasMap = new java.util.HashMap<>();
    aliasMap.put("T1", "tab1");
    aliasMap.put("T2", "tab2");

    List<com.hb.extractor.parser.dto.FieldMapping> result = extractor.extract(
        sql, "target_tbl", aliasMap, java.util.Arrays.asList("a", "b"));

    // 应有 2 个 FieldMapping (a, b)
    assertEquals(2, result.size());
    // 每个 FieldMapping 应有条件字段的 TransformInput
    for (com.hb.extractor.parser.dto.FieldMapping fm : result) {
        boolean hasJoinCondition = fm.getInputs().stream()
            .anyMatch(ti -> "join_condition".equals(ti.getRole()));
        assertTrue(hasJoinCondition, "FieldMapping " + fm.getTargetField() + " 应包含 join_condition 输入");
    }
    // 验证 T2.b 条件字段的 sourceTableShort 正确解析
    boolean hasT2B = result.get(0).getInputs().stream()
        .anyMatch(ti -> "T2".equals(ti.getSourceAlias())
                     && "b".equals(ti.getSourceField())
                     && "tab2".equals(ti.getSourceTableShort())
                     && "join_condition".equals(ti.getRole()));
    assertTrue(hasT2B, "应有 T2.b 的 join_condition 输入, sourceTableShort=tab2");
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`D:\HB\maven\bin\mvn.cmd test -Dtest=FieldMappingExtractorTest#testConditionFields_JoinOnAddedToInputs -q`
预期：FAIL，`hasJoinCondition` 为 false（条件字段未被添加）

- [ ] **步骤 3：实现条件字段处理逻辑**

在 `FieldMappingExtractor.java` 中：

**3a. 注入 TableColumnMetaMapper**（类字段区域，约行 55 附近）：

```java
@Autowired
private com.hb.extractor.mapper.TableColumnMetaMapper tableColumnMetaMapper;
```

**3b. 在 extract 方法末尾**（matchByDdlFields/matchBySelectOnly 生成 result 之后，return result 之前）新增：

```java
// 【新增】为每个 FieldMapping 添加 ON/WHERE 条件字段的 TransformInput
if (select != null && !result.isEmpty()) {
    List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(select);
    if (!conditionFields.isEmpty()) {
        // 预建 DDL 缓存（多表无 alias 场景）
        java.util.Map<String, java.util.Set<String>> ddlCache = null;
        for (FieldMapping fm : result) {
            int inputOrder = fm.getInputs().size() + 1;
            java.util.Map<String, TransformInput> dedup = new java.util.LinkedHashMap<>();
            for (TransformInput ti : fm.getInputs()) {
                String key = (ti.getSourceAlias() != null ? ti.getSourceAlias() + "." : "")
                           + ti.getSourceField() + "/" + ti.getRole();
                dedup.put(key, ti);
            }
            for (JsqlSqlAnalyzer.ColumnRef ref : conditionFields) {
                if (ref.field == null) continue;
                if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
                if (ref.field.matches("\\d+")) continue;
                String key = (ref.alias != null ? ref.alias + "." : "")
                           + ref.field + "/" + ref.role;
                if (dedup.containsKey(key)) continue;
                TransformInput ti = new TransformInput();
                ti.setSourceAlias(ref.alias);
                ti.setSourceField(ref.field);
                ti.setRole(ref.role);
                ti.setInputOrder(inputOrder++);
                // sourceTableShort 赋值
                if (ref.alias != null && aliasMap.containsKey(ref.alias)) {
                    ti.setSourceTableShort(aliasMap.get(ref.alias));
                } else if (aliasMap.size() == 1) {
                    String onlyTable = aliasMap.values().iterator().next();
                    if (onlyTable != null) {
                        ti.setSourceTableShort(onlyTable);
                    }
                } else if (aliasMap.size() > 1) {
                    // 多表无 alias: DDL 字段匹配
                    if (ddlCache == null) {
                        ddlCache = buildDdlCache(aliasMap.values());
                    }
                    String matched = matchFieldToTable(ref.field, ddlCache);
                    if (matched != null) {
                        ti.setSourceTableShort(matched);
                    }
                }
                fm.getInputs().add(ti);
                dedup.put(key, ti);
            }
        }
    }
}
```

**3c. 新增 `buildDdlCache` 和 `matchFieldToTable` 方法**（类末尾）：

```java
/**
 * 批量查询 aliasMap 中所有表的字段列表,构建缓存。
 */
private java.util.Map<String, java.util.Set<String>> buildDdlCache(java.util.Collection<String> tableShorts) {
    java.util.Map<String, java.util.Set<String>> cache = new java.util.HashMap<>();
    if (tableShorts == null) return cache;
    for (String tableShort : tableShorts) {
        if (tableShort == null) continue;
        java.util.Set<String> columns = new java.util.HashSet<>();
        var records = tableColumnMetaMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.hb.extractor.entity.TableColumnMeta>()
                .eq(com.hb.extractor.entity.TableColumnMeta::getTableName, tableShort));
        for (var r : records) {
            if (r.getColumnName() != null) {
                columns.add(r.getColumnName().toUpperCase());
            }
        }
        cache.put(tableShort, columns);
    }
    return cache;
}

/**
 * 多表场景下通过 DDL 字段列表匹配字段归属。
 * @return 唯一匹配的 table_short;多重匹配或无匹配返回 null
 */
private String matchFieldToTable(String field, java.util.Map<String, java.util.Set<String>> ddlCache) {
    if (field == null || ddlCache == null || ddlCache.isEmpty()) return null;
    String fieldUpper = field.toUpperCase();
    String matched = null;
    int matchCount = 0;
    for (java.util.Map.Entry<String, java.util.Set<String>> entry : ddlCache.entrySet()) {
        if (entry.getValue().contains(fieldUpper)) {
            matched = entry.getKey();
            matchCount++;
        }
    }
    return matchCount == 1 ? matched : null;
}
```

注意：`FieldMapping` 类需有 `getInputs()` 返回 `List<TransformInput>`（如已有则不变）。`SQL_KEYWORDS` 常量已有（行 53 附近）。

- [ ] **步骤 4：运行测试验证通过**

运行：`D:\HB\maven\bin\mvn.cmd test -Dtest=FieldMappingExtractorTest#testConditionFields_JoinOnAddedToInputs -q`
预期：PASS

- [ ] **步骤 5：运行全部 FieldMappingExtractor 测试确认无回归**

运行：`D:\HB\maven\bin\mvn.cmd test -Dtest=FieldMappingExtractorTest -q`
预期：所有测试通过

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java
git commit -m "feat: add ON/WHERE condition fields to TransformInput with DDL matching"
```

---

## 任务 3：Neo4jSyncServiceImpl MERGE 加 role 去重

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`（行 514-525）

- [ ] **步骤 1：修改 createInputToRelationship 方法**

将行 521-524 的 Cypher 从：

```java
String cypher = "MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId}) " +
        "MERGE (c)-[r:INPUT_TO]->(t) " +
        "SET r.role = $role" +
        (sourceAlias != null ? ", r.source_alias = $sourceAlias" : "");
```

改为：

```java
String cypher = "MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId}) " +
        "MERGE (c)-[r:INPUT_TO {role: $role}]->(t) " +
        (sourceAlias != null ? "SET r.source_alias = $sourceAlias" : "");
```

- [ ] **步骤 2：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -Dmaven.test.skip=true -q`
预期：编译成功

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java
git commit -m "fix: MERGE INPUT_TO with role to avoid overwriting different roles"
```

---

## 任务 4：ImpactAnalysisServiceImpl DIRECT 类型条件字段处理

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`（行 692-716）

- [ ] **步骤 1：修改 DIRECT 类型处理逻辑**

将行 692-716 的 `if ("DIRECT".equals(logicType))` 块替换为：

```java
if ("DIRECT".equals(logicType)) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> directInputs = (List<Map<String, Object>>) row.get("inputs");
    if (directInputs != null && outputColKey != null) {
        ensureFieldNode(outputColKey, nodeIds, nodes);
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
    }
    continue;
}
```

- [ ] **步骤 2：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -Dmaven.test.skip=true -q`
预期：编译成功

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java
git commit -m "feat: DIRECT type includes condition fields in DERIVED_FROM edges"
```

---

## 任务 5：端到端验证

**文件：** 无修改（验证任务）

- [ ] **步骤 1：停止现有服务**

运行：
```powershell
$p = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess; if ($p) { Stop-Process -Id $p -Force }
```

- [ ] **步骤 2：编译并启动服务**

运行：
```powershell
D:\HB\maven\bin\mvn.cmd spring-boot:run "-Dmaven.test.skip=true" "-Dspring-boot.run.jvmArguments=-Xmx1024m"
```
等待 Tomcat 启动在 8080 端口。

- [ ] **步骤 3：重新解析脚本**

运行：
```powershell
$body = @{path='D:\HB\work_zuoye\jiaoben\dwa_c_agt\dwa_c_agt\dwa_c_agt_deposit_acct_profit.py'} | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/proc/extract' -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 60
```
预期：返回 200，解析成功。

- [ ] **步骤 4：验证 MySQL 条件字段记录**

运行：
```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -proot proc_extractor -N -e "SELECT ti.role, ti.source_table_short, ti.source_field, ti.source_alias FROM table_transform_input ti JOIN table_field_mapping fm ON ti.field_mapping_id = fm.id JOIN table_lineage tl ON fm.lineage_id = tl.id WHERE tl.proc_name = 'dwa_c_agt_deposit_acct_profit' AND tl.sql_block_seq = 13 AND ti.role IN ('join_condition', 'where_condition');"
```
预期：至少一行 `join_condition | vt_c_agt_deposit_acct_profit_06 | acct_no | T2`

- [ ] **步骤 5：验证影响分析链路打通**

从 `vt_c_agt_deposit_acct_profit_06.acct_no` 出发分析下游：
```powershell
$body = @{schema='CDM';tableShortName='vt_c_agt_deposit_acct_profit_06';fieldName='acct_no';direction='DOWNSTREAM';maxDepth=5} | ConvertTo-Json
$r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/impact/analyze' -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 30
$vo = $r.Content | ConvertFrom-Json
$vo.data.nodes | Where-Object { $_.id -like '*c_agt_deposit_acct_profit*acct_no' -and $_.id -notlike '*vt*' } | ForEach-Object { "REACHED: $($_.id)" }
```
预期：输出 `REACHED: cdm.c_agt_deposit_acct_profit.acct_no`（链路打通）

- [ ] **步骤 6：验证从 FDM.acct_no 出发的完整链路**

```powershell
$body = @{schema='FDM';tableShortName='f_ac_ctds_ldr_acct';fieldName='acct_no';direction='DOWNSTREAM';maxDepth=10} | ConvertTo-Json
$r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/api/impact/analyze' -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 30
$vo = $r.Content | ConvertFrom-Json
$vo.data.nodes | Where-Object { $_.nodeLevel -eq 'FIELD' } | Sort-Object depth | ForEach-Object { "depth=$($_.depth) | $($_.id)" }
```
预期：depth=3 出现 `cdm.c_agt_deposit_acct_profit.acct_no`（目标物理表）

- [ ] **步骤 7：回归验证**

检查没有平级的非下游节点：
```powershell
$vo.data.nodes | Where-Object { $_.nodeLevel -eq 'FIELD' -and $_.depth -eq $null } | ForEach-Object { "WARNING depth=null: $($_.id)" }
```
预期：无输出（所有节点都有明确 depth）

- [ ] **步骤 8：Commit 验证结果**

无需 commit（验证任务不产生代码变更）。如发现 bug，回到对应任务修复。
