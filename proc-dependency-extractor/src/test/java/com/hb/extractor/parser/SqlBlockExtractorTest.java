package com.hb.extractor.parser;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlBlockExtractor 在 /*StepN*\/ 注释作为硬分隔符时，会把一条
 * INSERT..SELECT..UNION ALL..SELECT 语句拦腰切断，导致前半块以 "UNION ALL" 收尾、
 * 后半块另起。本用例验证修复后的切块器能将集合运算后半段合并回同一块。
 */
class SqlBlockExtractorTest {

    private final SqlBlockExtractor extractor = new SqlBlockExtractor();

    // 模拟源文件中 "INSERT..SELECT.. UNION ALL /*StepN*/ SELECT" 的真实形态：
    // 三引号包裹，中间用 /*StepN*/ 注释分隔，无任何分号。
    private static final String SAMPLE = "bsql = \"\"\"\n" +
            "INSERT INTO PID.A_G_LN_LOAN_ACCT_INF_SUM\n" +
            "SELECT t.PROD_NO, t.ACCTG_ORG\n" +
            "FROM TMP t\n" +
            "GROUP BY t.PROD_NO\n" +
            "\n\n" +
            "UNION ALL\n" +
            "/*Step6.2: 注贷款插入传统贷款*/\n" +
            "\n" +
            "SELECT t.PROD_NO\n" +
            "FROM TMP2 t\n" +
            "\"\"\"";

    @Test
    void union_all_after_step_marker_should_merge_into_single_block() {
        List<String> blocks = extractor.extractBlocks(SAMPLE);

        // 断言: 不再切出"以 UNION ALL 收尾"的残缺块
        assertFalse(blocks.isEmpty(), "应至少产出一个块");
        for (String b : blocks) {
            assertFalse(endWithSetOperator(b),
                    "不应存在以集合运算符收尾的残缺块: [" + b + "]");
        }

        // 断言: 整条 INSERT..UNION ALL..SELECT 合并为一个完整块
        assertTrue(blocks.size() >= 1);
        String merged = blocks.stream().reduce("", (a, b) -> a + b);
        assertTrue(merged.contains("UNION ALL"), "UNION ALL 应保留在合并后的块内");
        assertTrue(merged.contains("SELECT t.PROD_NO\nFROM TMP2") || merged.contains("SELECT t.PROD_NO FROM TMP2"),
                "UNION ALL 后的第二个 SELECT 应被合并进同一块");

        // 断言: 合并后的块能被 JsqlParser 完整解析（不再 EOF 中断）
        assertNotNull(assertParses(merged),
                "合并后的完整块应能被 JsqlParser 解析");
    }

    @Test
    void extractBlocksWithMeta_should_merge_union_across_step_marker() {
        List<?> metas = extractor.extractBlocksWithMeta(SAMPLE);

        boolean anyUnionTail = false;
        for (Object m : metas) {
            String content = ((com.hb.extractor.parser.dto.SqlBlockInfoDTO) m).getContent();
            if (endWithSetOperator(content)) {
                anyUnionTail = true;
                break;
            }
        }
        assertFalse(anyUnionTail, "extractBlocksWithMeta 不应产出以集合运算符收尾的残缺块");
    }

    // 分号出现在 COMMENT '...;...;...' 单引号字符串内时，不得被当作语句分隔符。
    // （GBase 临时表 DDL 的列 COMMENT 常含中文分号/顿号，切错会把 CREATE 拦腰切碎，
    //   且残块会以 "2"、"3" 之类注释内容作为首 token，导致 "unexpected token 3"）
    private static final String CREATE_DDL = "bsql = \"\"\"\n" +
            "CREATE TEMPORARY TABLE {PID}.VT_A_D_OTHR_DEMAND_MGMT_ANALY_01\n" +
            "(\n" +
            " DATA_DT DATE NOT NULL COMMENT '数据日期',\n" +
            " DATA_TYPE VARCHAR(8) DEFAULT NULL COMMENT '数据类型:1、业务中心;2、研发团队;3、系统承接',\n" +
            " TYPE_NAME VARCHAR(256) DEFAULT NULL COMMENT '类型名称'\n" +
            ") DISTRIBUTED BY ('TYPE_NAME') comment '加工临时表1取团队的数据'\n" +
            "\"\"\"";

    @Test
    void comment_semicolon_should_not_split_create_table() {
        List<String> blocks = extractor.extractBlocks(CREATE_DDL);

        assertFalse(blocks.isEmpty(), "应产出一个完整块");
        // 不允许出现以 "2"、"3" 等注释残片为首 token 的残块
        for (String b : blocks) {
            String t = b.trim();
            assertFalse(t.matches("^[0-9][、,]"), "不应存在从注释文本切出的残块: [" + t + "]");
        }
        // 整条 CREATE TEMPORARY TABLE 应保持为单个完整块
        assertEquals(1, blocks.size(), "CREATE TEMPORARY TABLE 应保持为单个块");
        String merged = blocks.get(0);
        assertTrue(merged.contains("COMMENT '数据类型:1、业务中心;2、研发团队;3、系统承接'"),
                "COMMENT 字符串内的分号应被原样保留");
        // 生产链路会先用 SqlPlaceholderReplacer 把 {PID}. 等占位符替换后再交给 JsqlParser；
        // 这里做同样替换后断言，完整 CREATE 块应能被 JsqlParser 解析
        String forParse = merged.replace("{PID}", "PID");
        assertNotNull(assertParses(forParse), "完整 CREATE 块应能被 JsqlParser 解析");
    }

    // Step 注释出现在单条 INSERT..SELECT 语句内部（列清单与 SELECT 之间、携带 -- 行内注释）
    // 时，不得被当作语句分隔符；整条 INSERT..SELECT 应以划线语句切为一个块。
    private static final String INSERT_WITH_INLINE_STEP = "bsql = \"\"\"\n" +
            "INSERT INTO {PID}.A_G_AG_PUB_INTER_REVE_SUM\n" +
            "      (  DATA_DT,-- 数据日期\n" +
            "         MKTG_ORG,-- 营销机构\n" +
            "         SUBJ_NO,-- 科目名称\n" +
            "         TXN_AMT-- 交易金额\n" +
            "      )  /*Step1:插入交易银行中收的数据*/\n" +
            "      SELECT A.DATA_DT,A.MKTG_ORG,A.SUBJ_NO,\n" +
            "             SUM(NVL(A.TXN_AMT,0)-NVL(B.TXN_AMT,0)) AS TXN_AMT\n" +
            "      FROM T1 A, T2 B\n" +
            "      WHERE A.DATA_DT = B.DATA_DT ;\n" +
            "\"\"\"";

    @Test
    void inline_step_marker_should_not_split_insert_select() {
        List<String> blocks = extractor.extractBlocks(INSERT_WITH_INLINE_STEP);

        assertFalse(blocks.isEmpty());
        // 取那条 INSERT..SELECT：不得被切开成 "INSERT INTO ()" 残块
        String merged = blocks.stream()
                .filter(b -> b.contains("INSERT INTO"))
                .reduce("", (a, b) -> a + b);
        assertFalse(merged.endsWith(")"), "INSERT..SELECT 不应以列清单括号收尾（被 Step 切断）");
        assertTrue(merged.contains("SELECT A.DATA_DT"), "Step 注释后的 SELECT 应留在同块内");
        // 只有一条合法的 INSERT..INSERT 且能被解析
        assertNotNull(assertParses(merged.replace("{PID}", "PID")),
                "完整的 INSERT..SELECT 块应能被 JsqlParser 解析");
    }

    // ---- helpers ----

    private static final Pattern SET_OP_END = Pattern.compile(
            "(?is)(unions?\\s+all|union|minus|intersect|except(?:\\s+all)?)\\s*$");

    private static boolean endWithSetOperator(String s) {
        return s != null && SET_OP_END.matcher(s.trim()).find();
    }

    private static net.sf.jsqlparser.statement.Statement assertParses(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            fail("解析失败: " + e.getMessage());
            return null;
        }
    }
}