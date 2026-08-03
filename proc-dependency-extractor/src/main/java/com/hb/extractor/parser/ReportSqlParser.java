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

    /** 行注释 -- ... */
    private static final Pattern LINE_COMMENT_PATTERN = Pattern.compile("--[^\\n]*");

    /** 块注释 (C 风格 star-slash) */
    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** FROM/JOIN 后的表引用: schema.table alias */
    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
            "(?i)\\b(FROM|JOIN)\\s+(\\w+)\\.(\\w+)\\s+(?:AS\\s+)?(\\w+)?");

    /** AS 别名: expr AS alias */
    private static final Pattern AS_ALIAS_PATTERN = Pattern.compile(
            "(?i)\\bAS\\s+(\\w+)\\s*$");

    /** alias.field 模式 */
    private static final Pattern ALIAS_FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    /** 末尾空格别名: expr alias */
    private static final Pattern SPACE_ALIAS_PATTERN = Pattern.compile(
            "\\s+(\\w+)\\s*$");

    /**
     * 预处理 SQL：去除帆软参数和注释
     */
    public String preprocess(String sql) {
        if (sql == null) return "";
        String result = sql;
        result = FR_PARAM_PATTERN.matcher(result).replaceAll("__PARAM__");
        result = BLOCK_COMMENT_PATTERN.matcher(result).replaceAll(" ");
        result = LINE_COMMENT_PATTERN.matcher(result).replaceAll(" ");
        return result;
    }

    /**
     * 提取 SQL 中的表引用（FROM/JOIN schema.table alias）
     */
    public List<TableRef> extractTables(String rawSql) {
        List<TableRef> tables = new ArrayList<>();
        String sql = preprocess(rawSql);

        Matcher m = FROM_JOIN_PATTERN.matcher(sql);
        while (m.find()) {
            TableRef ref = new TableRef();
            ref.setSchema(m.group(2).toUpperCase());
            ref.setTable(m.group(3).toUpperCase());
            ref.setAlias(m.group(4) != null ? m.group(4).toLowerCase() : null);
            tables.add(ref);
        }
        return tables;
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
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        for (TableRef ref : extractTables(rawSql)) {
            if (ref.getAlias() != null) {
                aliasToTable.put(ref.getAlias(), ref.getFullName());
            }
        }

        // 按括号深度拆分列
        List<String> columns = splitSelectColumns(selectClause);

        for (int i = 0; i < columns.size(); i++) {
            String raw = columns.get(i).trim();
            if (raw.isEmpty()) continue;
            ColumnMapping mapping = parseColumn(raw, i, aliasToTable);
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

    private ColumnMapping parseColumn(String raw, int index, Map<String, String> aliasToTable) {
        ColumnMapping mapping = new ColumnMapping();
        String expr = raw.trim();

        // 尝试 AS 别名
        Matcher asMatcher = AS_ALIAS_PATTERN.matcher(expr);
        String alias = null;
        String sourceExpr = expr;
        if (asMatcher.find()) {
            alias = asMatcher.group(1);
            sourceExpr = expr.substring(0, asMatcher.start()).trim();
        } else {
            // 尝试末尾空格别名（如 nvl(a.X,0) CURR_BAL）
            Matcher spaceMatcher = SPACE_ALIAS_PATTERN.matcher(expr);
            if (spaceMatcher.find()) {
                String candidate = spaceMatcher.group(1);
                // 排除关键字和函数名
                if (!isKeyword(candidate) && !expr.endsWith("(" + candidate + ")")) {
                    alias = candidate;
                    sourceExpr = expr.substring(0, spaceMatcher.start()).trim();
                }
            }
        }

        // 无别名时，从 alias.field 推断
        if (alias == null) {
            Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(expr);
            if (afMatcher.find()) {
                alias = afMatcher.group(2);
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
        }

        return mapping;
    }

    private boolean isKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("FROM") || upper.equals("WHERE") || upper.equals("AS")
                || upper.equals("AND") || upper.equals("OR") || upper.equals("CASE")
                || upper.equals("WHEN") || upper.equals("THEN") || upper.equals("ELSE")
                || upper.equals("END") || upper.equals("NULL") || upper.equals("NOT");
    }
}
