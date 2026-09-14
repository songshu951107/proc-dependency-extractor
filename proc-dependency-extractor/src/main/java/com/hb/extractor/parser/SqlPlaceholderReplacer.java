package com.hb.extractor.parser;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 占位符预处理器
 *
 * Python 存储过程脚本中 SQL 用 {CDM}.table 这种 format 占位符引用 Python 变量，
 * JsqlParser 无法识别 {XXX} 这种非标准语法，需在解析前还原为合法 SQL：
 *
 * 1. {CDM}.table  →  CDM.table      （schema 占位符去花括号，schema 名=变量名，与落库格式一致）
 * 2. {I_MB_DT}    →  '__ph_I_MB_DT__'  （独立占位符替换为字符串字面量，避免破坏 SELECT 表达式结构）
 * 3. CREATE TEMP TABLE → CREATE TEMPORARY TABLE  （GBase 方言适配）
 *
 * 解析后再从 AST 读出的 schemaName 就是变量名（CDM），与原正则方案的 buildFullName 行为一致。
 */
@Component
public class SqlPlaceholderReplacer {

    /** 形如 {CDM}.table 的 schema 占位符，去花括号 */
    private static final Pattern SCHEMA_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{(\\w+)\\}\\.");

    /**
     * 形如 '{XXX}' 的占位符（前后已被单引号包裹，例如 DATE '{ETL_TX_DT}'）。
     * 这种场景只替换占位符内部为 __ph_XXX__，保留原单引号，
     * 否则若再追加单引号会变成 ''__ph_XXX__''（空字符串字面量 + 标识符），
     * JsqlParser 会把 __ph_XXX__ 当成非法标识符报错。
     */
    private static final Pattern QUOTED_PLACEHOLDER_PATTERN =
            Pattern.compile("'\\{(\\w+)\\}'");

    /** 形如 {I_MB_DT} 的独立占位符，替换为字符串字面量 */
    private static final Pattern STANDALONE_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{(\\w+)\\}");

    /** CREATE TEMP TABLE（GBase 简写）→ CREATE TEMPORARY TABLE */
    private static final Pattern CREATE_TEMP_PATTERN =
            Pattern.compile("CREATE\\s+TEMP\\s+TABLE", Pattern.CASE_INSENSITIVE);

    /**
     * 连续空行（2 个及以上换行，中间可夹空白）折叠为单个换行。
     * JsqlParser 4.9 在 JOIN 链中遇到 2 个以上连续空行会误判语句已结束
     * （报 "Encountered unexpected token: LEFT ... Was expecting: <EOF>"），
     * 导致整个 SQL 块被跳过、字段级血缘断裂。
     * 空白对 SQL 语义无影响，折叠仅作用于送入解析器的文本，不改动落库原文。
     */
    private static final Pattern MULTI_NEWLINE_PATTERN =
            Pattern.compile("(?:\\r?\\n[ \\t]*){2,}");

    /**
     * 表名末尾紧跟的日期分区占位符后缀（裸 {PH} 或带引号 '{PH}'）。
     * 部分脚本按日期分区建表/引用：VT_X_01_{ETL_TX_DT}、VT_X_01_'{ETL_TX_DT}'。
     * 若不剥离，第 3 步会把裸占位符替换为 '__ph_PH__' 字符串字面量，
     * 表名后跟字符串字面量导致 JsqlParser 解析失败（报 S_CHAR_LITERAL），
     * 整个 INSERT 块字段级血缘缺失，影响链路断在临时表。
     * 仅匹配"标识符字符（\w）后紧跟"的形态；值位置的 DATE '{PH}'（引号前是空格）
     * 与 NVL(x, {PH})（前面是括号/等号/空格）不受影响。
     * 剥离结果与切块器 target 规范一致（target 记录为 VT_X_01_）。
     */
    private static final Pattern TABLE_SUFFIX_QUOTED_PLACEHOLDER_PATTERN =
            Pattern.compile("(\\w+)'\\{\\w+\\}'");

    private static final Pattern TABLE_SUFFIX_BARE_PLACEHOLDER_PATTERN =
            Pattern.compile("(\\w)\\{\\w+\\}");

    /**
     * CTAS 缺 AS 关键字（GBase 方言）：CREATE [TEMPORARY] TABLE name 后直接跟
     * select 或 (select。JsqlParser 要求显式 AS，缺失时报
     * "Encountered unexpected token: SELECT"。
     * lookahead 限定后跟 select 关键字，内联 DDL 的 (col1 type,...) 不受影响。
     */
    private static final Pattern CTAS_MISSING_AS_PATTERN =
            Pattern.compile("((?:CREATE)(?:\\s+TEMPORARY)?\\s+TABLE\\s+\\S+\\s*)(?=[\\s]*(?:\\(\\s*)?select\\b)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * GBase 私有表选项 TABLESPACE='xxx'（可带 = 或空格分隔）。
     * JsqlParser 的 CREATE TABLE 选项列表不识别 TABLESPACE，
     * 整个建表语句解析失败导致内联列定义丢失。
     * 仅匹配带引号的选项值形态；无引号形态（TABLESPACE sys_tablespace）
     * 与列名 tablespace_flag 难以区分，暂不处理（实际样本均为带引号形态）。
     */
    private static final Pattern TABLESPACE_OPTION_PATTERN =
            Pattern.compile("TABLESPACE\\s*=?\\s*'[^']*'", Pattern.CASE_INSENSITIVE);

    /**
     * 集合运算 EXCEPT ALL → EXCEPT。JSqlParser 4.9 不支持 EXCEPT ALL，
     * 解析失败导致整个 INSERT 块字段级血缘缺失。
     * 两者仅去重行为差异，对表血缘/字段映射无影响。
     */
    private static final Pattern EXCEPT_ALL_PATTERN =
            Pattern.compile("\\bEXCEPT\\s+ALL\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 预处理 SQL 文本，使其能被 JsqlParser 解析。
     * 不修改原 SQL 语义，仅做语法兼容性替换。
     */
    public String preprocess(String sql) {
        if (sql == null || sql.isEmpty()) return sql;

        // 0. 连续空行折叠为单个换行（JsqlParser JOIN 链空行 bug 规避）
        sql = MULTI_NEWLINE_PATTERN.matcher(sql).replaceAll("\n");

        // 1. schema 占位符 {CDM}.table → CDM.table
        sql = SCHEMA_PLACEHOLDER_PATTERN.matcher(sql).replaceAll("$1.");

        // 1.5 表名末尾的日期分区占位符后缀剥离：VT_X_01_{PH} / VT_X_01_'{PH}' → VT_X_01_
        //    必须先于第 2/3 步执行（否则占位符已被替换为字符串字面量形态）
        //    例外：引号前紧贴的词是日期关键字（DATE'{PH}' / TIME'{PH}' / TIMESTAMP'{PH}'，引号前无空格）
        //    属于值位置日期字面量而非表名后缀，不剥离，交由第 2 步替换为 '__ph_PH__' 字面量。
        //    否则 DATE'{PH}' AS DATA_DT 会被剥成 DATE AS DATA_DT，被解析为名为 DATE 的列，
        //    内层别名列注册失败导致外层 T1.DATA_DT 穿透断链（复现 credit_card_acct_sum block 2）。
        java.util.regex.Matcher tsq = TABLE_SUFFIX_QUOTED_PLACEHOLDER_PATTERN.matcher(sql);
        StringBuilder tsqSb = new StringBuilder();
        while (tsq.find()) {
            String word = tsq.group(1).toUpperCase();
            boolean dateLiteral = word.equals("DATE") || word.equals("TIME") || word.equals("TIMESTAMP");
            tsq.appendReplacement(tsqSb,
                    java.util.regex.Matcher.quoteReplacement(dateLiteral ? tsq.group(0) : tsq.group(1)));
        }
        tsq.appendTail(tsqSb);
        sql = tsqSb.toString();
        sql = TABLE_SUFFIX_BARE_PLACEHOLDER_PATTERN.matcher(sql).replaceAll("$1");

        // 2. 已包裹单引号的占位符 '{XXX}' → '__ph_XXX__'
        //    只替换占位符内部，保留原单引号，避免产生 ''__ph_XXX__'' 错误结构
        sql = QUOTED_PLACEHOLDER_PATTERN.matcher(sql).replaceAll("'__ph_$1__'");

        // 3. 剩余独立占位符 {I_MB_DT} → '__ph_I_MB_DT__'
        //    用字符串字面量避免被 JsqlParser 当作标识符解析
        Matcher m = STANDALONE_PLACEHOLDER_PATTERN.matcher(sql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "'__ph_" + m.group(1) + "__'");
        }
        m.appendTail(sb);
        sql = sb.toString();

        // 4. CREATE TEMP TABLE → CREATE TEMPORARY TABLE
        sql = CREATE_TEMP_PATTERN.matcher(sql).replaceAll("CREATE TEMPORARY TABLE");

        // 5. CTAS 缺 AS 补齐：CREATE [TEMPORARY] TABLE name select/(select → 插入 AS
        //    必须在第 4 步之后（TEMPORARY 关键字已统一）
        sql = CTAS_MISSING_AS_PATTERN.matcher(sql).replaceAll("$1AS ");

        // 6. GBase 私有表选项 TABLESPACE='xxx' 剥离
        sql = TABLESPACE_OPTION_PATTERN.matcher(sql).replaceAll("");

        // 7. EXCEPT ALL → EXCEPT（JSqlParser 4.9 不支持 EXCEPT ALL）
        sql = EXCEPT_ALL_PATTERN.matcher(sql).replaceAll("EXCEPT");

        return sql;
    }

    /**
     * 判断字符串是否为预处理产生的占位符字面量（'__ph_XXX__'），
     * 用于 FieldMappingExtractor 把 LITERAL 类型的占位符还原回 {XXX} 形式落库。
     */
    public boolean isPlaceholderLiteral(String s) {
        return s != null && s.startsWith("__ph_") && s.endsWith("__");
    }

    /**
     * 从占位符字面量还原原始变量名。
     * 输入 "__ph_I_MB_DT__" 返回 "{I_MB_DT}"
     */
    public String restorePlaceholder(String literal) {
        if (literal == null || !isPlaceholderLiteral(literal)) return literal;
        String name = literal.substring(5, literal.length() - 2);
        return "{" + name + "}";
    }
}
