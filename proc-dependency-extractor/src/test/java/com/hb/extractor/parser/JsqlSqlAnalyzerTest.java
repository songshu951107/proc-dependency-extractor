package com.hb.extractor.parser;

import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
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

    // ===== 修复 A: 无别名表的隐式 alias 登记 =====

    private PlainSelect parseInsertSelect(JsqlSqlAnalyzer analyzer, String sql) {
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "SQL 应解析成功");
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = insert.getSelect();
        assertNotNull(select);
        assertTrue(select.getSelectBody() instanceof PlainSelect, "SELECT 体应为 PlainSelect");
        return (PlainSelect) select.getSelectBody();
    }

    @Test
    void testExtractAliasMap_ImplicitTableAlias() throws Exception {
        // 修复 A: FROM vt_x 无别名 → 表名自身即隐式引用前缀 VT_X → vt_x
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT a FROM vt_x");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(1, aliasMap.size(), "无别名单表应登记隐式 alias");
        assertEquals("vt_x", aliasMap.get("VT_X"));
    }

    @Test
    void testExtractAliasMap_ExplicitAliasUnchanged() throws Exception {
        // 显式别名行为不变: 只有 T → vt_x，不额外登记 VT_X
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT t.a FROM vt_x t");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(1, aliasMap.size());
        assertEquals("vt_x", aliasMap.get("T"));
        assertFalse(aliasMap.containsKey("VT_X"), "显式别名时表名不应再作为隐式前缀登记");
    }

    @Test
    void testExtractAliasMap_TwoTablesWithoutAlias() throws Exception {
        // 多表无别名: 各自登记隐式 alias，size=2（兜底歧义行为不变）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT x.a, y.b FROM vt_x JOIN vt_y ON x.id = y.id");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(2, aliasMap.size());
        assertEquals("vt_x", aliasMap.get("VT_X"));
        assertEquals("vt_y", aliasMap.get("VT_Y"));
    }

    // ===== 修复 C: JOIN 链中连续空行导致 JsqlParser 误判语句结束 =====

    @Test
    void testParse_JoinChainWithConsecutiveBlankLines() throws Exception {
        // dwa_pid_a_d_dp_deposit_acct_inf b10 结构: JOIN 链中间存在 2 个以上连续空行
        // （原文 T9 与 T11 之间留有 4 个空行），JsqlParser 4.9 会在此误判语句已结束，
        // 报 "Encountered unexpected token: LEFT ... Was expecting: <EOF>"
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl (acct_no) "
                + "SELECT t1.a FROM tab1 t1 "
                + "LEFT JOIN tab2 t2 ON t1.a = t2.a "
                + "LEFT JOIN tab3 t3 ON t1.a = t3.a\n"
                + "\n\n\n\n"
                + "LEFT JOIN tab4 t4 ON t1.a = t4.a "
                + "WHERE t1.data_dt = DATE '{ETL_TX_DT}'";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "JOIN 链中的连续空行不应导致解析失败（块被跳过会使字段级血缘断裂）");
    }

    @Test
    void testParse_SingleBlankLineUnaffected() throws Exception {
        // 单个空行原本就能解析，折叠后行为保持不变
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl (acct_no) "
                + "SELECT t1.a FROM tab1 t1 "
                + "LEFT JOIN tab2 t2 ON t1.a = t2.a\n\n"
                + "LEFT JOIN tab3 t3 ON t1.a = t3.a";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt);
    }

    // ===== 修复 D: 表名后紧跟日期分区占位符导致 JsqlParser 解析失败 =====

    @Test
    void testParse_TableNameWithBareDatePartitionSuffix() throws Exception {
        // dwa_pid_a_d_dp_deposit_org_dtl b3/b5 结构: FROM/JOIN 引用 VT_.._01_{ETL_TX_DT}
        // 预处理第 3 步把裸占位符替换为 '__ph_ETL_TX_DT__' 后，表名后跟字符串字面量，
        // JsqlParser 报 "Encountered unexpected token: '__ph_ETL_TX_DT__' <S_CHAR_LITERAL>"，
        // b3/b5 字段级血缘整体缺失 → 影响链路断在临时表
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO pid.a_d_dp_deposit_org_dtl (acct_no, bal) "
                + "SELECT p1.acct_no, p1.bal FROM PID.VT_A_D_DP_DEPOSIT_ORG_DTL_01_{ETL_TX_DT} p1 "
                + "WHERE p1.data_dt = DATE '{ETL_TX_DT}'";
        PlainSelect select = parseInsertSelect(analyzer, sql);
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals("vt_a_d_dp_deposit_org_dtl_01_", aliasMap.get("P1"),
                "表名末尾的日期分区占位符应剥离，与切块器 target 规范一致");
    }

    @Test
    void testParse_TableNameWithQuotedDatePartitionSuffix() throws Exception {
        // 防御: 表名后紧跟带引号占位符 'XXX_{PH}' 同样应剥离（部分脚本用引号拼分区名）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO pid.t (a) SELECT t1.a FROM CDM.VT_X_01_'{ETL_TX_DT}' t1";
        PlainSelect select = parseInsertSelect(analyzer, sql);
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals("vt_x_01_", aliasMap.get("T1"));
    }

    @Test
    void testParse_ValuePlaceholderAfterIdentifierUnaffected() throws Exception {
        // 值位置的引号占位符（引号前是空格/等号，非标识符字符）不受剥离影响，仍按
        // 字符串字面量替换；NVL(t.x, {ETL_TX_DT}) 这类括号前导的裸占位符同理
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.t (a, b) "
                + "SELECT t1.a, NVL(t1.b, {ETL_TX_DT}) FROM tab1 t1 "
                + "WHERE t1.dt = DATE '{ETL_TX_DT}'";
        PlainSelect select = parseInsertSelect(analyzer, sql);
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals("tab1", aliasMap.get("T1"), "值位置占位符不应触发表名后缀剥离");
    }

    // ===== 修复 B: UNION ALL 子查询源表提取 =====

    @Test
    void testExtractTablesRecursively_UnionAllSubquery() throws Exception {
        // dwa_c_agt_ldr_acct_intst block 4 结构: FROM (SELECT...UNION ALL SELECT...) TAA
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO {CDM}.C_AGT_LDR_ACCT_INTST " +
                "SELECT TAA.acct_no, SUM(day_intst) AS day_intst " +
                "FROM (SELECT TLX.acct_no, day_intst AS day_intst " +
                "      FROM {CDM}.VT_C_AGT_LDR_ACCT_INTST_01 TLX " +
                "      UNION ALL " +
                "      SELECT TLD.acct_no, TLD.month_intst AS day_intst " +
                "      FROM {CDM}.C_AGT_LDR_ACCT_INTST TLD WHERE data_dt = DATE '{I_LAST_DT}') TAA " +
                "GROUP BY TAA.acct_no";
        PlainSelect select = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.TableRefInfo> tables = analyzer.extractTablesRecursively(select, 0);
        assertTrue(tables.stream().anyMatch(t -> "vt_c_agt_ldr_acct_intst_01".equals(t.tableName)),
                "应提取 UNION 分支 1 的 VT_C_AGT_LDR_ACCT_INTST_01, 实际: " + tables);
        assertTrue(tables.stream().anyMatch(t -> "c_agt_ldr_acct_intst".equals(t.tableName)),
                "应提取 UNION 分支 2 的 C_AGT_LDR_ACCT_INTST, 实际: " + tables);
        assertTrue(tables.stream().anyMatch(t -> t.isSubquery && "TAA".equals(t.alias)),
                "子查询 alias TAA 应保留占位记录");
    }

    // ===== P7: GBase 方言 CTAS/建表语法兼容 =====

    @Test
    void testParse_CreateTableMissingAs_BareSelect() throws Exception {
        // dwa_c_agt_deposit_acct_intst 结构: CREATE TABLE name 后直接 SELECT（缺 AS 关键字）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "create  temporary table {PID}.a_d_x_01\n"
                + "  select t1.data_dt, t1.agt_num from {PID}.f_src t1 where t1.dt = date'{ETL_TX_DT}'";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "缺 AS 的 CTAS 应解析成功");
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.create.table.CreateTable,
                "应解析为 CreateTable, 实际: " + stmt.getClass().getSimpleName());
        var ct = (net.sf.jsqlparser.statement.create.table.CreateTable) stmt;
        assertEquals("a_d_x_01", ct.getTable().getName().toLowerCase());
        assertNotNull(ct.getSelect(), "CTAS 的 SELECT 子句应被识别");
    }

    @Test
    void testParse_CreateTableMissingAs_ParenSelect() throws Exception {
        // DWA_PID_A_D_LN_LOAN_ACCT_INF 结构: CREATE TABLE name 后直接 (SELECT ...)
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "CREATE TABLE {PID}.VT_A_D_X_01\n\n(\nSELECT F1.CUST_NO AS CUST_ID\n"
                + "FROM {PID}.F_CUST F1\nWHERE F1.DEL_FLAG <> 'D'\n)";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "缺 AS 的括号 CTAS 应解析成功");
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.create.table.CreateTable);
        var ct = (net.sf.jsqlparser.statement.create.table.CreateTable) stmt;
        assertEquals("VT_A_D_X_01", ct.getTable().getName());
        assertNotNull(ct.getSelect(), "括号 CTAS 的 SELECT 子句应被识别");
    }

    @Test
    void testParse_CreateTableInlineDdl_GBaseTablespaceOption() throws Exception {
        // dwa_pid_a_g_dp_deposit_report 结构: 内联 DDL 后带 GBase 私有表选项
        // ) ENGINE=EXPRESS DEFAULT CHARSET=utf8 TABLESPACE='sys_tablespace' COMMENT='...'
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "CREATE TABLE pid.vt_a_g_dp_rpt_02_{ETL_TX_DT} (\n"
                + "  \"DATA_DT\" date NOT NULL COMMENT '数据日期',\n"
                + "  \"BAL\" decimal(26,6) DEFAULT NULL COMMENT '余额'\n"
                + ") ENGINE=EXPRESS DEFAULT CHARSET=utf8 TABLESPACE='sys_tablespace' COMMENT='临时表'";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "带 TABLESPACE 选项的建表应解析成功");
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.create.table.CreateTable);
        var ct = (net.sf.jsqlparser.statement.create.table.CreateTable) stmt;
        assertEquals(2, ct.getColumnDefinitions().size(), "应解析出 2 个内联列定义");
        assertEquals("\"DATA_DT\"", ct.getColumnDefinitions().get(0).getColumnName(),
                "JSqlParser 保留双引号列名原文（上层 LineageExtractor 归一化剥离）");
    }

    @Test
    void testParse_ExceptAllSetOperation() throws Exception {
        // dwa_c_agt_loan_rcpt_subj_rela_h 结构: SELECT ... EXCEPT ALL SELECT ...
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO pid.tgt SELECT a FROM pid.x WHERE dt = date'{ETL_TX_DT}' "
                + "EXCEPT ALL SELECT a FROM pid.y";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "EXCEPT ALL 集合运算应解析成功");
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.insert.Insert);
    }

    // ===== 修复: 派生表子查询内 JOIN/WHERE 条件递归提取 =====

    private static JsqlSqlAnalyzer.ColumnRef findRef(List<JsqlSqlAnalyzer.FilterInfo> filters,
                                                     String alias, String field) {
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            for (JsqlSqlAnalyzer.ColumnRef r : fi.fields) {
                if (r.alias != null && r.alias.equalsIgnoreCase(alias)
                        && r.field != null && r.field.equalsIgnoreCase(field)) {
                    return r;
                }
            }
        }
        return null;
    }

    @Test
    void testExtractFiltersRecursively_DerivedSubqueryJoinWhere() throws Exception {
        // 块7真实形状: 所有 JOIN/WHERE 都在派生表子查询内
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cntd.vt_x_01 SELECT t1.a FROM ("
                + "SELECT T1.acct_no, T1.dt FROM a_d_dp_deposit_org_dtl_query T1 "
                + "LEFT JOIN vt_a_g_dp_deposit_prod_struc_sum_00 T2 ON T1.intal_acct_no_no = T2.acct_no "
                + "WHERE T1.dt = DATE '2020-01-01') x";
        PlainSelect body = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.FilterInfo> filters = analyzer.extractFiltersRecursively(body);

        assertFalse(filters.isEmpty(), "应提取到派生表内的 JOIN/WHERE 条件");
        // inner JOIN: T2.acct_no 必须在字段集合中，且作用域含 T2→vt_a_g_dp_deposit_prod_struc_sum_00
        JsqlSqlAnalyzer.ColumnRef t2 = findRef(filters, "T2", "acct_no");
        assertNotNull(t2, "派生表内 JOIN 的 T2.acct_no 应被提取");
        JsqlSqlAnalyzer.FilterInfo joinInfo = filters.stream()
                .filter(f -> f.fields.contains(t2)).findFirst().orElse(null);
        assertNotNull(joinInfo);
        assertEquals("JOIN", joinInfo.filterType);
        assertNotNull(joinInfo.scopeAliasMap, "条件应携带作用域局部别名映射");
        assertEquals("vt_a_g_dp_deposit_prod_struc_sum_00", joinInfo.scopeAliasMap.get("T2"));
        assertEquals("a_d_dp_deposit_org_dtl_query", joinInfo.scopeAliasMap.get("T1"));
        // inner WHERE: T1.dt 归属内层真实表
        JsqlSqlAnalyzer.ColumnRef t1dt = findRef(filters, "T1", "dt");
        assertNotNull(t1dt, "派生表内 WHERE 的 T1.dt 应被提取");
        JsqlSqlAnalyzer.FilterInfo whereInfo = filters.stream()
                .filter(f -> f.fields.contains(t1dt)).findFirst().orElse(null);
        assertNotNull(whereInfo);
        assertEquals("WHERE", whereInfo.filterType);
    }

    @Test
    void testExtractFiltersRecursively_OuterScopeNotPollutedByInner() throws Exception {
        // 外层 WHERE 引用派生表别名 x，不能被内层同名 T1 污染（内层 T1→tab1 不得泄漏到外层）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cntd.vt_x SELECT x.acct_no FROM ("
                + "SELECT T1.acct_no FROM tab1 T1 WHERE T1.dt > DATE '2020-01-01') x "
                + "WHERE x.acct_no = 'C'";
        PlainSelect body = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.FilterInfo> filters = analyzer.extractFiltersRecursively(body);

        JsqlSqlAnalyzer.ColumnRef outerX = findRef(filters, "x", "acct_no");
        assertNotNull(outerX, "外层 WHERE 的 x.acct_no 应被提取");
        JsqlSqlAnalyzer.FilterInfo outerInfo = filters.stream()
                .filter(f -> f.fields.contains(outerX)).findFirst().orElse(null);
        assertNotNull(outerInfo);
        assertEquals("WHERE", outerInfo.filterType);
        Map<String, String> outerScope = outerInfo.scopeAliasMap;
        // 外层映射：派生表别名 x 占位 null（不允许解析到物理表），且内层 T1→tab1 不得泄漏
        assertTrue(outerScope.containsKey("X"), "外层作用域应含派生表别名 X");
        assertNull(outerScope.get("X"), "派生表别名应为 null 占位，防止错误归属到任何物理表");
        assertFalse(outerScope.containsKey("T1"), "内层别名 T1 不得泄漏到外层作用域");
        // 内层 JOIN/WHERE 独立封闭: 内层 filter 的 scopeAliasMap 应含 T1→tab1
        boolean innerHasT1 = filters.stream().anyMatch(f -> f.scopeAliasMap != null
                && "tab1".equals(f.scopeAliasMap.get("T1")));
        assertTrue(innerHasT1, "内层条件作用域应含 T1→tab1");
    }

    @Test
    void testExtractFiltersRecursively_UnionAllBranches() throws Exception {
        // UNION ALL 各分支条件都应被收集，且各分支作用域封闭
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cntd.t SELECT u.a FROM ("
                + "SELECT a FROM vt_x t1 WHERE t1.x = 1 "
                + "UNION ALL "
                + "SELECT b FROM vt_y t2 WHERE t2.y = 2) u";
        PlainSelect body = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.FilterInfo> filters = analyzer.extractFiltersRecursively(body);

        assertTrue(filters.size() >= 2, "UNION 两个分支的 WHERE 条件都应被收集");
        // 分支1: t1.x 归属 vt_x
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            for (JsqlSqlAnalyzer.ColumnRef r : fi.fields) {
                if (r.alias != null && r.alias.equalsIgnoreCase("t1") && "x".equalsIgnoreCase(r.field)) {
                    assertEquals("vt_x", fi.scopeAliasMap.get("T1"));
                    return; // 找到即通过
                }
            }
        }
        fail("UNION 分支1 的 t1.x 条件缺失");
    }

    @Test
    void testExtractFiltersRecursively_FlatEquivalentToOldPair() throws Exception {
        // 平铺 SQL: 递归入口结果应与 旧 join+where 两方法拼接等价（回归护桩）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 "
                + "LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t1.x > 100";
        PlainSelect body = parseInsertSelect(analyzer, sql);

        List<JsqlSqlAnalyzer.FilterInfo> rec = analyzer.extractFiltersRecursively(body);
        List<JsqlSqlAnalyzer.FilterInfo> old = new java.util.ArrayList<>();
        old.addAll(analyzer.extractJoinFilters(body));
        old.addAll(analyzer.extractWhereFilters(body));

        assertEquals(old.size(), rec.size(), "平铺 SQL 两者条件数量应一致");
        // 字段集等价
        java.util.Set<String> recKeys = new java.util.HashSet<>();
        java.util.Set<String> oldKeys = new java.util.HashSet<>();
        for (JsqlSqlAnalyzer.FilterInfo f : rec) for (JsqlSqlAnalyzer.ColumnRef r : f.fields)
            recKeys.add((r.alias != null ? r.alias : "") + "." + r.field);
        for (JsqlSqlAnalyzer.FilterInfo f : old) for (JsqlSqlAnalyzer.ColumnRef r : f.fields)
            oldKeys.add((r.alias != null ? r.alias : "") + "." + r.field);
        assertEquals(oldKeys, recKeys, "平铺 SQL 递归与旧方法字段集应等价");
    }

    @Test
    void testExtractFiltersRecursively_DeepNestingNoStackOverflow() throws Exception {
        // 6 层嵌套派生表: 深度截断不爆栈，正常返回
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        StringBuilder sb = new StringBuilder("SELECT a FROM ");
        for (int i = 0; i < 6; i++) sb.append("(SELECT a FROM ");
        sb.append("vt_t0 T WHERE T.b > 0");
        for (int i = 0; i < 6; i++) sb.append(") s").append(i);
        String sql = "INSERT INTO cntd.t " + sb;
        PlainSelect body = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.FilterInfo> filters = analyzer.extractFiltersRecursively(body);
        assertNotNull(filters, "深嵌套不应抛异常，最多截断");
        // 仍应收集到 6 层内最深层 vs 截断处的条件之一（有收集即可，重点是未抛栈溢出）
    }
}
