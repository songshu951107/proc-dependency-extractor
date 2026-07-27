package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageFilter;
import com.hb.extractor.parser.dto.LineageParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 血缘派生关系解析器
 *
 * 按 SQL 块顺序提取表间派生关系（source → target），
 * 支持 CREATE TEMP / INSERT SELECT / MERGE / UPDATE / DELETE，
 * 支持子查询递归提取（最大深度 5 层）。
 */
@Slf4j
@Component
public class LineageExtractor {

    @Autowired
    private SqlBlockExtractor sqlBlockExtractor;

    private static final Pattern SCHEMA_VAR_PATTERN = Pattern.compile("\\{(\\w+)\\}");
    private static final String TEMP_PREFIX = "VT_";
    private static final int MAX_SUBQUERY_DEPTH = 5;

    private static final Pattern CREATE_TEMP_PATTERN =
            Pattern.compile("CREATE\\s+(TEMP|TEMPORARY)\\s+TABLE", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN =
            Pattern.compile("INSERT\\s+INTO", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_INTO_PATTERN =
            Pattern.compile("MERGE\\s+INTO", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_FROM_PATTERN =
            Pattern.compile("UPDATE\\s+\\w+.*\\bFROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DELETE_FROM_PATTERN =
            Pattern.compile("DELETE\\s+FROM", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TEMP_TARGET_PATTERN =
            Pattern.compile("CREATE\\s+(TEMP|TEMPORARY)\\s+TABLE\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_TARGET_PATTERN =
            Pattern.compile("INSERT\\s+INTO\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_TARGET_PATTERN =
            Pattern.compile("MERGE\\s+INTO\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TARGET_PATTERN =
            Pattern.compile("UPDATE\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_TARGET_PATTERN =
            Pattern.compile("DELETE\\s+FROM\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM_TABLE_PATTERN =
            Pattern.compile("\\bFROM\\s+(?:\\{(\\w+)\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_TABLE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:\\{(\\w+)\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USING_TABLE_PATTERN =
            Pattern.compile("\\bUSING\\s+(?:\\{(\\w+)\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern JOIN_CLAUSE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:\\{\\w+\\}\\.)?(\\w+)\\s+(?:\\w+\\s+)?ON\\s+(.+?)(?=\\s+(?:JOIN|WHERE|GROUP|ORDER|LIMIT)\\b|;|$)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 提取 FROM/JOIN 后的表名和别名，用于 WHERE 字段归属判定 */
    private static final Pattern ALIAS_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:\\{\\w+\\}\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?",
            Pattern.CASE_INSENSITIVE);

    /** SQL 关键字集合，用于过滤别名误匹配 */
    private static final Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
            "ON", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
            "GROUP", "ORDER", "LIMIT", "UNION", "AND", "OR", "HAVING", "SELECT",
            "FROM", "INTO", "VALUES", "SET", "BY", "DISTINCT", "ALL", "AS"));

    private static final Pattern WHERE_CLAUSE_PATTERN =
            Pattern.compile("\\bWHERE\\s+(.+?)(?=\\s+(?:GROUP|ORDER|LIMIT)\\b|;|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern FIELD_PATTERN = Pattern.compile("(\\w+\\.\\w+)");

    private static final Pattern SUBQUERY_START_PATTERN =
            Pattern.compile("\\b(FROM|JOIN)\\s*\\(", Pattern.CASE_INSENSITIVE);

    public LineageParseResult extract(List<String> sqlBlocks,
                                       Map<String, String> schemaVarMap,
                                       String updateTarget) {
        LineageParseResult result = new LineageParseResult();
        String updateTargetShort = extractShortName(updateTarget);

        for (int seq = 0; seq < sqlBlocks.size(); seq++) {
            String block = sqlBlocks.get(seq);
            try {
                parseBlock(block, seq + 1, schemaVarMap, updateTargetShort, result);
            } catch (Exception e) {
                log.warn("解析 SQL 块 {} 失败: {}", seq + 1, e.getMessage());
            }
        }
        return result;
    }

    private void parseBlock(String block, int seq, Map<String, String> schemaVarMap,
                            String updateTargetShort, LineageParseResult result) {
        String trimmed = block.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("--")) {
            return;
        }

        String targetTable = null;
        String operation = null;
        String targetSchema = null;

        if (CREATE_TEMP_PATTERN.matcher(trimmed).find()) {
            Matcher m = CREATE_TEMP_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(2);
                targetSchema = extractSchemaFromBlock(trimmed, "TABLE\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "CREATE TEMP";
            }
        } else if (INSERT_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = INSERT_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "INSERT SELECT";
            }
        } else if (MERGE_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = MERGE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "MERGE";
            }
        } else if (UPDATE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = UPDATE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "UPDATE\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "UPDATE FROM";
            }
        } else if (DELETE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = DELETE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "FROM\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "DELETE FROM";
            }
        }

        if (targetTable == null) {
            return;
        }

        String targetFull = buildFullName(targetSchema, targetTable, schemaVarMap);
        boolean targetIsTemp = targetTable.startsWith(TEMP_PREFIX);

        // 提取 source 表及其 schema 变量，构建完整名
        Map<String, String> sourceSchemaMap = new LinkedHashMap<>();
        List<String[]> sources = extractSourceTables(trimmed, 0);
        for (String[] src : sources) {
            String shortName = src[0];
            String schemaVar = src[1];
            if (!shortName.equals(targetTable) && !shortName.equals(updateTargetShort)) {
                sourceSchemaMap.putIfAbsent(shortName, schemaVar);
            }
        }

        Set<String> sourceShortSet = sourceSchemaMap.keySet();

        int startEdgeIndex = result.getEdges().size();
        for (String sourceShort : sourceShortSet) {
            String sourceSchema = sourceSchemaMap.get(sourceShort);
            String sourceFull = buildFullName(sourceSchema, sourceShort, schemaVarMap);
            LineageEdge edge = new LineageEdge();
            edge.setSourceTableShort(sourceShort);
            edge.setSourceTable(sourceFull);
            edge.setTargetTableShort(targetTable);
            edge.setTargetTable(targetFull);
            edge.setTargetIsTemp(targetIsTemp);
            edge.setOperation(operation);
            edge.setSqlBlockSeq(seq);
            result.getEdges().add(edge);
        }

        // 构建 alias -> tableShort 映射，用于 WHERE 字段归属判定
        Map<String, String> aliasMap = extractAliasMap(trimmed);
        extractFilters(trimmed, startEdgeIndex, sourceShortSet, aliasMap, result);
    }

    private List<String[]> extractSourceTables(String sql, int depth) {
        List<String[]> sources = new ArrayList<>();
        if (depth > MAX_SUBQUERY_DEPTH) {
            log.warn("子查询递归深度超过 {}, 停止递归", MAX_SUBQUERY_DEPTH);
            return sources;
        }

        addMatches(FROM_TABLE_PATTERN, sql, sources);
        addMatches(JOIN_TABLE_PATTERN, sql, sources);
        addMatches(USING_TABLE_PATTERN, sql, sources);

        Matcher subMatcher = SUBQUERY_START_PATTERN.matcher(sql);
        while (subMatcher.find()) {
            int parenStart = sql.indexOf('(', subMatcher.end() - 1);
            if (parenStart < 0) continue;
            String subContent = extractParenContent(sql, parenStart);
            if (subContent != null && !subContent.isEmpty()) {
                sources.addAll(extractSourceTables(subContent, depth + 1));
            }
        }

        return sources;
    }

    private String extractParenContent(String sql, int openParenIndex) {
        if (openParenIndex >= sql.length() || sql.charAt(openParenIndex) != '(') {
            return null;
        }
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = openParenIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                if (depth > 1) sb.append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) break;
                sb.append(c);
            } else if (depth > 0) {
                sb.append(c);
            }
        }
        if (depth != 0) {
            log.warn("括号未闭合, 放弃子查询解析");
            return null;
        }
        return sb.toString();
    }

    private void addMatches(Pattern pattern, String sql, List<String[]> sources) {
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            String schemaVar = m.group(1);
            String tableName = m.group(2);
            sources.add(new String[]{tableName, schemaVar});
        }
    }

    private void extractFilters(String sql, int startEdgeIndex,
                                 Set<String> sourceSet, Map<String, String> aliasMap,
                                 LineageParseResult result) {
        Matcher joinMatcher = JOIN_CLAUSE_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            String joinTable = joinMatcher.group(1);
            String clause = joinMatcher.group(2).trim();
            List<String> fields = extractFields(clause);

            int edgeIndex = findEdgeIndex(sourceSet, joinTable, startEdgeIndex, 0);
            if (edgeIndex >= 0 && edgeIndex < result.getEdges().size()) {
                LineageFilter filter = new LineageFilter();
                filter.setEdgeIndex(edgeIndex);
                filter.setFilterType("JOIN");
                filter.setFilterClause(clause);
                filter.setFieldsInvolved(fields);
                result.getFilters().add(filter);
            }
        }

        Matcher whereMatcher = WHERE_CLAUSE_PATTERN.matcher(sql);
        if (whereMatcher.find()) {
            String whereClause = whereMatcher.group(1).trim();
            String[] parts = whereClause.split("\\bAND\\b|\\bOR\\b", Pattern.CASE_INSENSITIVE);
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;
                List<String> fields = extractFields(part);
                if (fields.isEmpty()) continue;

                String firstField = fields.get(0);
                String prefix = firstField.split("\\.")[0].toUpperCase();
                int edgeIndex = findEdgeIndexByAlias(aliasMap, sourceSet, prefix, startEdgeIndex);
                if (edgeIndex >= 0) {
                    LineageFilter filter = new LineageFilter();
                    filter.setEdgeIndex(edgeIndex);
                    filter.setFilterType("WHERE");
                    filter.setFilterClause(part);
                    filter.setFieldsInvolved(fields);
                    result.getFilters().add(filter);
                }
            }
        }
    }

    /**
     * 从 SQL 块中提取 alias -> tableShort 映射。
     * 扫描 FROM/JOIN {schema}.table alias 模式，排除 SQL 关键字误匹配。
     * 若表未显式指定别名，则将表名本身作为别名存入映射。
     */
    private Map<String, String> extractAliasMap(String sql) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        Matcher m = ALIAS_PATTERN.matcher(sql);
        while (m.find()) {
            String tableShort = m.group(1);
            String alias = m.group(2);
            if (alias != null && !SQL_KEYWORDS.contains(alias.toUpperCase())) {
                aliasMap.put(alias.toUpperCase(), tableShort);
            }
            aliasMap.putIfAbsent(tableShort.toUpperCase(), tableShort);
        }
        return aliasMap;
    }

    private List<String> extractFields(String clause) {
        List<String> fields = new ArrayList<>();
        Matcher m = FIELD_PATTERN.matcher(clause);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    private int findEdgeIndex(Set<String> sourceSet, String tableName,
                               int startEdgeIndex, int offset) {
        int i = 0;
        for (String src : sourceSet) {
            if (src.equalsIgnoreCase(tableName)) {
                return startEdgeIndex + i;
            }
            i++;
        }
        return -1;
    }

    /**
     * 通过 alias -> tableShort 映射查找对应的 edge 下标。
     * 若 alias 不在映射中（如子查询别名），返回 -1，跳过该 filter 而非误归属。
     */
    private int findEdgeIndexByAlias(Map<String, String> aliasMap, Set<String> sourceSet,
                                      String alias, int startEdgeIndex) {
        String tableShort = aliasMap.get(alias.toUpperCase());
        if (tableShort == null) {
            return -1;
        }
        return findEdgeIndex(sourceSet, tableShort, startEdgeIndex, 0);
    }

    private String extractSchemaFromBlock(String sql, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String buildFullName(String schemaVar, String tableShort, Map<String, String> schemaVarMap) {
        if (schemaVar == null) {
            return tableShort;
        }
        return schemaVar + "." + tableShort;
    }

    private String extractShortName(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    public SqlBlockExtractor getSqlBlockExtractor() {
        return sqlBlockExtractor;
    }
}
