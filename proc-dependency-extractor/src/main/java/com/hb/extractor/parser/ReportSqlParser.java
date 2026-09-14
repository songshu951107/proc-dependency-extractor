package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表 SQL 解析器
 * 解析纯 SELECT 语句，提取表引用和字段映射
 */
@Slf4j
@Component
public class ReportSqlParser {

    /** ${if(...)} 帆软动态参数 */
    private static final Pattern FR_PARAM_PATTERN = Pattern.compile("\\$\\{[^}]*\\}");

    /** FROM/JOIN 后的表引用: [schema.]table [alias] */
    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
            "(?i)\\b(FROM|JOIN)\\s+(?:(\\w+)\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?");

    /** 单词 token（用于裸列提取） */
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b(\\w+)\\b");

    /** AS 别名: expr AS alias 或 expr AS 'alias' / "alias"（帆软报表常用单引号汉字别名，如 round(bal/10000,2) as '账户余额'） */
    private static final Pattern AS_ALIAS_PATTERN = Pattern.compile(
            "(?i)\\bAS\\s+(\\w+|'[^']*'|\"[^\"]*\")\\s*$");

    /** alias.field 模式 */
    private static final Pattern ALIAS_FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    /** 末尾空格别名: expr alias 或 expr 'alias'（Oracle 风格无 AS + 引号别名） */
    private static final Pattern SPACE_ALIAS_PATTERN = Pattern.compile(
            "\\s+(\\w+|'[^']*'|\"[^\"]*\")\\s*$");

    /**
     * 预处理 SQL：去除帆软参数和注释。
     * 注释剥离必须感知单引号字符串状态——否则字符串字面量里的 "--"（如
     * REPLACE(CUST_NAME,'--河北银行保证金','')）会被误判为行注释，
     * 连带吃掉右括号导致后续括号深度错乱、列拆分失败。
     */
    public String preprocess(String sql) {
        if (sql == null) return "";
        String result = FR_PARAM_PATTERN.matcher(sql).replaceAll("__PARAM__");
        return stripComments(result);
    }

    /** 感知字符串的注释剥离：引号内的 -- 和 /* 不视为注释 */
    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\'') {
                    // '' 转义仍处于字符串内，否则字符串结束
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        sb.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                sb.append(c);
                continue;
            }
            // 行注释 --（引号外）
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                if (nl == -1) break;
                sb.append(' ');
                i = nl - 1; // 保留换行，下一轮从 \n 继续
                continue;
            }
            // 块注释（引号外）
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end == -1) break;
                sb.append(' ');
                i = end + 1;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 提取 SQL 中的表引用（FROM/JOIN [schema.]table [alias]）
     */
    public List<TableRef> extractTables(String rawSql) {
        List<TableRef> tables = new ArrayList<>();
        String sql = preprocess(rawSql);

        Matcher m = FROM_JOIN_PATTERN.matcher(sql);
        while (m.find()) {
            String schema = m.group(2);
            String table = m.group(3);
            String alias = m.group(4);
            // alias 误匹配关键字（如 from t where）时置空
            if (alias != null && isTableAliasKeyword(alias)) {
                alias = null;
            }
            TableRef ref = new TableRef();
            ref.setSchema(schema != null ? schema.toUpperCase() : null);
            ref.setTable(table.toUpperCase());
            ref.setAlias(alias != null ? alias.toLowerCase() : null);
            tables.add(ref);
        }
        return tables;
    }

    /** FROM/JOIN 后可能紧跟的关键字，不应被误识别为表别名 */
    private boolean isTableAliasKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("WHERE") || upper.equals("ON") || upper.equals("AND") || upper.equals("OR")
                || upper.equals("GROUP") || upper.equals("ORDER") || upper.equals("HAVING")
                || upper.equals("JOIN") || upper.equals("LEFT") || upper.equals("RIGHT")
                || upper.equals("INNER") || upper.equals("OUTER") || upper.equals("FULL")
                || upper.equals("UNION") || upper.equals("LIMIT") || upper.equals("AS")
                || upper.equals("WITH") || upper.equals("SET");
    }

    /**
     * 提取 SELECT 列的字段映射
     */
    public List<ColumnMapping> extractColumns(String rawSql) {
        List<ColumnMapping> mappings = new ArrayList<>();
        String sql = preprocess(rawSql);

        // 定位 SELECT ... FROM
        int selectIdx = indexOfKeyword(sql, "SELECT");
        int fromIdx = indexOfKeyword(sql, "FROM");
        if (selectIdx == -1 || fromIdx == -1 || fromIdx <= selectIdx) {
            return mappings;
        }
        String selectClause = sql.substring(selectIdx + 6, fromIdx).trim();

        // 构建别名映射
        List<TableRef> tableRefs = extractTables(rawSql);
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        for (TableRef ref : tableRefs) {
            if (ref.getAlias() != null) {
                aliasToTable.put(ref.getAlias(), ref.getFullName());
            }
        }
        // 无 alias.field 限定字段的兜底源表：取 FROM 主表（多表 JOIN 时未限定列按惯例归属主表，
        // 如 acct_no/rate/bal 均来自 FROM GMDATA.G_AG_DP_ACCT t，仅 t1.brch_spl_nm 显式限定）
        String defaultTable = tableRefs.isEmpty() ? null : tableRefs.get(0).getFullName();

        // 按括号深度拆分列
        List<String> columns = splitSelectColumns(selectClause);

        for (int i = 0; i < columns.size(); i++) {
            String raw = columns.get(i).trim();
            if (raw.isEmpty()) continue;
            ColumnMapping mapping = parseColumn(raw, i, aliasToTable, defaultTable);
            mappings.add(mapping);
        }

        return mappings;
    }

    private int indexOfKeyword(String sql, String keyword) {
        Pattern p = Pattern.compile("(?i)\\b" + keyword + "\\b");
        Matcher m = p.matcher(sql);
        return m.find() ? m.start() : -1;
    }

    /**
     * 按括号深度拆分 SELECT 列（不拆分函数参数内的逗号）
     */
    private List<String> splitSelectColumns(String selectClause) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            if (c == '\'' && !inString) {
                inString = true;
                current.append(c);
            } else if (c == '\'' && inString) {
                inString = false;
                current.append(c);
            } else if (!inString && c == '(') {
                depth++;
                current.append(c);
            } else if (!inString && c == ')') {
                depth--;
                current.append(c);
            } else if (!inString && c == ',' && depth == 0) {
                columns.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            columns.add(current.toString());
        }
        return columns;
    }

    private ColumnMapping parseColumn(String raw, int index, Map<String, String> aliasToTable, String defaultTable) {
        ColumnMapping mapping = new ColumnMapping();
        String expr = raw.trim();

        // 尝试 AS 别名
        Matcher asMatcher = AS_ALIAS_PATTERN.matcher(expr);
        String alias = null;
        String sourceExpr = expr;
        if (asMatcher.find()) {
            alias = stripAliasQuotes(asMatcher.group(1));
            sourceExpr = expr.substring(0, asMatcher.start()).trim();
        } else {
            // 尝试末尾空格别名（如 nvl(a.X,0) CURR_BAL）
            Matcher spaceMatcher = SPACE_ALIAS_PATTERN.matcher(expr);
            if (spaceMatcher.find()) {
                String candidate = stripAliasQuotes(spaceMatcher.group(1));
                // 排除关键字和函数名
                if (!isKeyword(candidate) && !expr.endsWith("(" + candidate + ")")) {
                    alias = candidate;
                    sourceExpr = expr.substring(0, spaceMatcher.start()).trim();
                }
            }
        }

        // 无别名时，从 alias.field 推断；若为纯标识符则别名取自身
        if (alias == null) {
            Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(expr);
            if (afMatcher.find()) {
                alias = afMatcher.group(2);
                sourceExpr = expr;
            } else if (expr.matches("\\w+")) {
                alias = expr;
                sourceExpr = expr;
            } else {
                alias = "expr_" + (index + 1);
                sourceExpr = expr;
            }
        }

        mapping.setSelectAlias(alias);
        mapping.setSourceExpr(sourceExpr);

        // 提取源表和源字段
        Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(sourceExpr);
        if (afMatcher.find()) {
            String aliasName = afMatcher.group(1).toLowerCase();
            String fieldName = afMatcher.group(2).toUpperCase();
            mapping.setSourceField(fieldName);
            String table = aliasToTable.get(aliasName);
            if (table != null) {
                mapping.setSourceTable(table);
            }
        } else {
            // 无 alias.field 限定：提取表达式中的首个裸列（跳过函数名/关键字/数字字面量），
            // 归属到 FROM 主表。如 round(bal/10000,2) → 字段 BAL；rate → 字段 RATE
            String fieldName = extractFirstColumn(sourceExpr);
            if (fieldName != null) {
                mapping.setSourceField(fieldName);
                if (defaultTable != null) {
                    mapping.setSourceTable(defaultTable);
                }
            }
        }

        return mapping;
    }

    /** 从表达式中提取第一个裸列名：跳过函数名（后跟 '('）、关键字、数字字面量 */
    private String extractFirstColumn(String expr) {
        Matcher m = WORD_PATTERN.matcher(expr);
        while (m.find()) {
            String tok = m.group(1);
            if (Character.isDigit(tok.charAt(0))) continue;
            if (isKeyword(tok)) continue;
            int after = m.end();
            while (after < expr.length() && Character.isWhitespace(expr.charAt(after))) after++;
            if (after < expr.length() && expr.charAt(after) == '(') continue;
            return tok.toUpperCase();
        }
        return null;
    }

    /** 剥离别名的引号（'账户余额' → 账户余额）；无引号原样返回 */
    private String stripAliasQuotes(String alias) {
        if (alias == null) return null;
        if (alias.length() >= 2
                && ((alias.startsWith("'") && alias.endsWith("'"))
                 || (alias.startsWith("\"") && alias.endsWith("\"")))) {
            return alias.substring(1, alias.length() - 1);
        }
        return alias;
    }

    private boolean isKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("FROM") || upper.equals("WHERE") || upper.equals("AS")
                || upper.equals("AND") || upper.equals("OR") || upper.equals("CASE")
                || upper.equals("WHEN") || upper.equals("THEN") || upper.equals("ELSE")
                || upper.equals("END") || upper.equals("NULL") || upper.equals("NOT");
    }
}
