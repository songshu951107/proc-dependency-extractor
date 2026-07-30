package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.FieldMapping;
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

    @Autowired
    private DdlParser ddlParser;

    @Autowired
    private FieldMappingExtractor fieldMappingExtractor;

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

    // target 正则：schema 前缀用非捕获组，支持 {var}.table 和 schema.table 两种形式
    private static final Pattern CREATE_TEMP_TARGET_PATTERN =
            Pattern.compile("CREATE\\s+(TEMP|TEMPORARY)\\s+TABLE\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_TARGET_PATTERN =
            Pattern.compile("INSERT\\s+INTO\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_TARGET_PATTERN =
            Pattern.compile("MERGE\\s+INTO\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TARGET_PATTERN =
            Pattern.compile("UPDATE\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_TARGET_PATTERN =
            Pattern.compile("DELETE\\s+FROM\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    // source 正则：schema 前缀用捕获组，group(1)={var} 形式，group(2)=schema 名形式，group(3)=表名
    private static final Pattern FROM_TABLE_PATTERN =
            Pattern.compile("\\bFROM\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_TABLE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USING_TABLE_PATTERN =
            Pattern.compile("\\bUSING\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern JOIN_CLAUSE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)\\s+(?:\\w+\\s+)?ON\\s+(.+?)(?=\\s+(?:JOIN|WHERE|GROUP|ORDER|LIMIT)\\b|;|$)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 提取 FROM/JOIN 后的表名和别名，用于 WHERE 字段归属判定 */
    private static final Pattern ALIAS_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?",
            Pattern.CASE_INSENSITIVE);

    /** 匹配子查询别名：\) alias 后跟 ON/WHERE/JOIN/逗号 等关键字或结尾 */
    private static final Pattern SUBQUERY_ALIAS_PATTERN = Pattern.compile(
            "\\)\\s*(?:AS\\s+)?(\\w+)\\s+(?:ON|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|FULL|CROSS|GROUP|ORDER|LIMIT|UNION|,)",
            Pattern.CASE_INSENSITIVE);

    /** 从子查询内部提取第一个 FROM 后的表名（用于子查询别名的表名溯源） */
    private static final Pattern SUBQUERY_INNER_TABLE_PATTERN = Pattern.compile(
            "\\bFROM\\s+(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)",
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
        // 缓存 CREATE TEMP 语句中的 DDL 字段列表，供后续 INSERT INTO 同名临时表时使用
        Map<String, List<String>> tempTableDdlFields = new HashMap<>();

        for (int seq = 0; seq < sqlBlocks.size(); seq++) {
            String block = sqlBlocks.get(seq);
            try {
                parseBlock(block, seq + 1, schemaVarMap, updateTargetShort, result, tempTableDdlFields);
            } catch (Exception e) {
                log.warn("解析 SQL 块 {} 失败: {}", seq + 1, e.getMessage());
            }
        }
        return result;
    }

    private void parseBlock(String block, int seq, Map<String, String> schemaVarMap,
                            String updateTargetShort, LineageParseResult result,
                            Map<String, List<String>> tempTableDdlFields) {
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
                targetSchema = extractSchemaFromBlock(trimmed, "TABLE\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?");
                operation = "CREATE TEMP";
            }
        } else if (INSERT_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = INSERT_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?");
                operation = "INSERT SELECT";
            }
        } else if (MERGE_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = MERGE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?");
                operation = "MERGE";
            }
        } else if (UPDATE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = UPDATE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "UPDATE\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?");
                operation = "UPDATE FROM";
            }
        } else if (DELETE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = DELETE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "FROM\\s+(?:(?:\\{(\\w+)\\}|(\\w+))\\.)?");
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

        // 提取主表（FROM 后第一个表）
        String mainTable = extractMainTable(trimmed);
        // 回填到本块产生的所有 edge
        for (int i = startEdgeIndex; i < result.getEdges().size(); i++) {
            result.getEdges().get(i).setMainTable(mainTable);
        }

        // 提取字段映射（仅 INSERT SELECT / CREATE TEMP）
        if ("INSERT SELECT".equals(operation) || "CREATE TEMP".equals(operation)) {
            try {
                // CREATE TEMP 块：从内联 DDL 提取字段名并缓存，供后续 INSERT INTO 同名临时表使用
                if ("CREATE TEMP".equals(operation) && targetIsTemp) {
                    List<String> inlineFields = extractDdlFieldsFromCreateTemp(trimmed);
                    if (!inlineFields.isEmpty()) {
                        tempTableDdlFields.put(targetTable, inlineFields);
                    }
                }

                List<String> ddlFields = ddlParser.parse(targetTable);
                // DDL 文件不存在时，尝试使用 CREATE TEMP 缓存的内联 DDL 字段
                if ((ddlFields == null || ddlFields.isEmpty())
                        && tempTableDdlFields.containsKey(targetTable)) {
                    ddlFields = tempTableDdlFields.get(targetTable);
                }
                List<FieldMapping> mappings = fieldMappingExtractor.extract(
                        trimmed, targetTable, aliasMap, ddlFields);
                for (FieldMapping fm : mappings) {
                    int edgeIndex = findMainEdgeIndex(result.getEdges(), mainTable, startEdgeIndex);
                    fm.setEdgeIndex(edgeIndex >= 0 ? edgeIndex : startEdgeIndex);
                    result.getFieldMappings().add(fm);
                }
            } catch (Exception e) {
                log.warn("SQL 块 {} 字段映射提取失败: {}", seq, e.getMessage());
            }
        }
    }

    /**
     * 从 CREATE TEMPORARY TABLE 语句的内联 DDL 中提取字段名列表
     *
     * 示例输入：
     *   CREATE TEMPORARY TABLE {PID}.VT_xxx ( col1 varchar(100), col2 DECIMAL(18,2) ) DISTRIBUTED BY(...)
     *
     * 提取结果：[col1, col2]（保留原始大小写，与 DDL 文件解析行为一致）
     * 注意：DECIMAL(18,2) 内部的逗号不应导致拆分，需按括号深度处理
     */
    private List<String> extractDdlFieldsFromCreateTemp(String sql) {
        List<String> fields = new ArrayList<>();
        Matcher m = CREATE_TEMP_TARGET_PATTERN.matcher(sql);
        if (!m.find()) return fields;

        // 定位表名后的第一个 '('
        int parenStart = sql.indexOf('(', m.end());
        if (parenStart < 0) return fields;

        // 找到匹配的 ')'
        int depth = 0;
        int parenEnd = -1;
        for (int i = parenStart; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    parenEnd = i;
                    break;
                }
            }
        }
        if (parenEnd < 0) return fields;

        String fieldsText = sql.substring(parenStart + 1, parenEnd);

        // 按括号深度 0 处的逗号拆分字段定义
        List<String> fieldDefs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        depth = 0;
        for (int i = 0; i < fieldsText.length(); i++) {
            char c = fieldsText.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                fieldDefs.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            fieldDefs.add(current.toString());
        }

        // 从每个字段定义中提取首个单词作为字段名（跳过约束关键字）
        Set<String> constraintKeywords = new HashSet<>(Arrays.asList(
                "PRIMARY", "KEY", "CONSTRAINT", "FOREIGN", "UNIQUE", "INDEX", "CHECK"));
        for (String def : fieldDefs) {
            String trimmedDef = def.trim();
            if (trimmedDef.isEmpty()) continue;
            String[] parts = trimmedDef.split("\\s+");
            if (parts.length > 0) {
                String firstWord = parts[0].toUpperCase();
                if (constraintKeywords.contains(firstWord)) continue;
                // 去除可能的引号
                String fieldName = parts[0].replace("\"", "");
                fields.add(fieldName.toLowerCase());
            }
        }
        return fields;
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

    /**
     * 从指定 ')' 位置反向查找匹配的 '('，正确跳过嵌套的括号对。
     * 用于子查询别名的内部文本提取，避免 lastIndexOf('(') 误匹配到函数调用内部的 '('。
     *
     * @param sql 原始 SQL 文本
     * @param closeParenIndex ')' 的位置
     * @return 匹配的 '(' 位置，未找到返回 -1
     */
    private int findMatchingOpenParen(String sql, int closeParenIndex) {
        if (closeParenIndex < 0 || closeParenIndex >= sql.length() || sql.charAt(closeParenIndex) != ')') {
            return -1;
        }
        int depth = 1;
        for (int i = closeParenIndex - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
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
            // group(1) = {var} 形式的 schema 变量，group(2) = schema 名形式，group(3) = 表名
            String schemaVar = m.group(1) != null ? m.group(1) : m.group(2);
            String tableName = m.group(3);
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

        // 补充子查询别名：\) alias ON/WHERE/JOIN/...
        // 子查询的"表名"取子查询内部第一个 FROM 后的表名，使字段映射能关联到实际源表
        Matcher subM = SUBQUERY_ALIAS_PATTERN.matcher(sql);
        while (subM.find()) {
            String alias = subM.group(1);
            if (SQL_KEYWORDS.contains(alias.toUpperCase())) {
                continue;
            }
            if (aliasMap.containsKey(alias.toUpperCase())) {
                continue; // 已有映射，不覆盖
            }
            // 从子查询内部提取第一个 FROM 后的表名
            int endIdx = subM.start(); // ')' 的位置
            // 反向查找匹配的 '('，正确处理子查询内部的嵌套括号（如 sum(nvl(...))、SUBSTR(...) 等）
            int matchingOpen = findMatchingOpenParen(sql, endIdx);
            String innerText = matchingOpen >= 0
                    ? sql.substring(matchingOpen + 1, endIdx)
                    : sql.substring(0, endIdx);
            Matcher innerM = SUBQUERY_INNER_TABLE_PATTERN.matcher(innerText);
            if (innerM.find()) {
                String innerTable = innerM.group(1);
                // 排除子查询内部的子查询别名（以 T1/T2 等开头但不是实际表名）
                if (!SQL_KEYWORDS.contains(innerTable.toUpperCase())) {
                    aliasMap.put(alias.toUpperCase(), innerTable);
                }
            }
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
            // group(1) = {var} 形式，group(2) = schema 名形式
            return m.group(1) != null ? m.group(1) : m.group(2);
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

    /**
     * 提取 FROM 后的主表短名
     */
    private String extractMainTable(String sql) {
        Matcher m = FROM_TABLE_PATTERN.matcher(sql);
        if (m.find()) {
            return m.group(3);
        }
        return null;
    }

    /**
     * 在 startEdgeIndex 之后的 edges 中查找主表对应的 edge 下标
     */
    private int findMainEdgeIndex(java.util.List<LineageEdge> edges, String mainTable, int startEdgeIndex) {
        if (mainTable == null) return -1;
        for (int i = startEdgeIndex; i < edges.size(); i++) {
            if (mainTable.equals(edges.get(i).getSourceTableShort())) {
                return i;
            }
        }
        return -1;
    }

    public SqlBlockExtractor getSqlBlockExtractor() {
        return sqlBlockExtractor;
    }
}
