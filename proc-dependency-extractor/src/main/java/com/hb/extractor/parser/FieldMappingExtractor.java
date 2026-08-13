package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.TransformInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字段映射提取器
 *
 * 从 INSERT INTO ... SELECT 或 CREATE TEMP ... SELECT 语句中
 * 提取目标表字段与源表字段的映射关系。
 *
 * target_field 确定：
 * - 有 DDL 字段列表 → 按位置对应
 * - 无 DDL 且列有 AS alias → 取 alias
 * - 无 DDL 且列是 T01.acct_no → 取 acct_no
 * - 无 DDL 且列是表达式（无别名）→ expr_{order}
 */
@Slf4j
@Component
public class FieldMappingExtractor {

    /** 提取 SELECT 到 FROM 之间的文本 */
    private static final Pattern SELECT_FROM_PATTERN = Pattern.compile(
            "SELECT\\s+(.+?)\\s+FROM\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 提取 AS alias */
    private static final Pattern AS_ALIAS_PATTERN = Pattern.compile(
            "\\bAS\\s+(\\w+)\\s*$", Pattern.CASE_INSENSITIVE);

    /** 提取 alias.field 模式 */
    private static final Pattern ALIAS_FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    /**
     * 从 SQL 块中提取字段映射
     *
     * 有 DDL 时：result 按 DDL 字段顺序排列，每个 DDL 字段匹配对应 SELECT 列
     *   匹配优先级：AS alias 等于 DDL 字段名 > alias.field 的 field 部分 > 按位置取剩余未用列
     * 无 DDL 时（临时表）：result 按 SELECT 列原顺序，target_field 从 AS alias / alias.field 推断
     *
     * @param sqlBlock SQL 块原文
     * @param targetTableShort 目标表短名
     * @param aliasMap 别名 → 表短名映射
     * @param ddlFields DDL 字段列表（可为 null，临时表无 DDL）
     * @return 字段映射列表
     */
    public List<FieldMapping> extract(String sqlBlock, String targetTableShort,
                                       Map<String, String> aliasMap, List<String> ddlFields) {
        // 预计算唯一源表（排除目标表自身），用于无别名前缀字段的源表推断
        String singleSourceTable = computeSingleSourceTable(aliasMap, targetTableShort);
        List<FieldMapping> mappings = new ArrayList<>();
        if (sqlBlock == null || targetTableShort == null) {
            return mappings;
        }

        // 定位 SELECT ... FROM 之间的文本
        Matcher m = SELECT_FROM_PATTERN.matcher(sqlBlock);
        if (!m.find()) {
            log.debug("未匹配到 SELECT ... FROM: target={}", targetTableShort);
            return mappings;
        }
        String selectClause = m.group(1);

        // 按括号深度拆分列
        List<String> columns = splitSelectColumns(selectClause);
        if (columns.isEmpty()) {
            return mappings;
        }

        if (ddlFields != null && !ddlFields.isEmpty()) {
            // 有 DDL：按 DDL 顺序匹配 SELECT 列
            List<Boolean> used = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                used.add(false);
            }
            for (int di = 0; di < ddlFields.size(); di++) {
                String ddlField = ddlFields.get(di);
                String matchedExpr = null;
                int matchedIdx = -1;

                // 1. 优先匹配 AS alias == ddlField
                for (int ci = 0; ci < columns.size(); ci++) {
                    if (used.get(ci)) {
                        continue;
                    }
                    String expr = columns.get(ci).trim();
                    Matcher asMatcher = AS_ALIAS_PATTERN.matcher(expr);
                    if (asMatcher.find() && asMatcher.group(1).equalsIgnoreCase(ddlField)) {
                        matchedExpr = expr;
                        matchedIdx = ci;
                        break;
                    }
                }

                // 2. 匹配 alias.field 中 field == ddlField
                if (matchedIdx == -1) {
                    for (int ci = 0; ci < columns.size(); ci++) {
                        if (used.get(ci)) {
                            continue;
                        }
                        String expr = columns.get(ci).trim();
                        Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(expr);
                        if (afMatcher.find() && afMatcher.group(2).equalsIgnoreCase(ddlField)) {
                            matchedExpr = expr;
                            matchedIdx = ci;
                            break;
                        }
                    }
                }

                // 3. 按位置取剩余未使用列中的第一个
                if (matchedIdx == -1) {
                    for (int ci = 0; ci < columns.size(); ci++) {
                        if (!used.get(ci)) {
                            matchedExpr = columns.get(ci).trim();
                            matchedIdx = ci;
                            break;
                        }
                    }
                }

                // 4. DDL 多于 SELECT 列，跳过该 DDL 字段
                if (matchedIdx == -1) {
                    continue;
                }

                used.set(matchedIdx, true);

                FieldMapping fm = new FieldMapping();
                fm.setFieldOrder(matchedIdx + 1);
                fm.setSourceExpr(matchedExpr);
                fm.setTargetField(ddlField);
                extractAliasAndTable(matchedExpr, aliasMap, fm, singleSourceTable);
                populateInputsAndLogicType(fm, aliasMap, singleSourceTable);
                mappings.add(fm);
            }
        } else {
            // 无 DDL（临时表）：按 SELECT 列原顺序，target_field 从 AS alias / alias.field 推断
            for (int i = 0; i < columns.size(); i++) {
                String expr = columns.get(i).trim();
                int order = i + 1;

                FieldMapping fm = new FieldMapping();
                fm.setFieldOrder(order);
                fm.setSourceExpr(expr);

                String targetField = determineTargetField(expr, order, ddlFields, i);
                fm.setTargetField(targetField);

                extractAliasAndTable(expr, aliasMap, fm, singleSourceTable);
                populateInputsAndLogicType(fm, aliasMap, singleSourceTable);

                mappings.add(fm);
            }
        }

        return mappings;
    }

    /** CASE WHEN 整体匹配 */
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "(?i)CASE\\s+(.+?)\\s+END", Pattern.DOTALL);
    /** WHEN...THEN 段匹配 */
    private static final Pattern WHEN_THEN_PATTERN = Pattern.compile(
            "(?i)WHEN\\s+(.+?)\\s+THEN\\s+(.+?)(?=\\s+(?:WHEN|ELSE|END)\\b)", Pattern.DOTALL);
    /** ELSE 段匹配 */
    private static final Pattern ELSE_PATTERN = Pattern.compile(
            "(?i)ELSE\\s+(.+?)(?=\\s+END\\b)", Pattern.DOTALL);

    /**
     * 对表达式进行 logic_type 分类
     * 优先级: LITERAL > CASE > 算术运算(顶层) > 聚合函数 > NVL > CONCAT > FUNCTION_OTHER > DIRECT
     * 注意: 顶层运算符优先于内层聚合（SUM(x) + y → ADDITION；SUM(x + y) → SUM）
     *
     * @param expr SELECT 列表达式原文
     * @return logic_type 枚举值
     */
    public String classifyLogicType(String expr) {
        if (expr == null || expr.isBlank()) return "DIRECT";
        String t = stripAsAlias(expr.trim());

        // 1. LITERAL: 字面量
        if (t.matches("^'[^']*'$")
                || t.matches("^\\d+(\\.\\d+)?$")
                || t.matches("(?i)^DATE\\s*'[^']*'$")
                || t.matches("(?i)^TIMESTAMP\\s*'[^']*'$")
                || t.matches("^\\{\\w+\\}$")) {
            return "LITERAL";
        }

        // 2. CASE
        if (t.matches("(?i)^CASE\\b.*END$")) return "CASE";

        // 3. 算术运算符（顶层深度 0，按 +、-、*、/ 顺序取首个）
        //    顶层运算符优先于内层聚合：SUM(x) + y → ADDITION
        char firstOp = findFirstTopLevelOperator(t);
        if (firstOp == '+') return "ADDITION";
        if (firstOp == '-') return "SUBTRACTION";
        if (firstOp == '*') return "MULTIPLICATION";
        if (firstOp == '/') return "DIVISION";

        // 4. 聚合函数（无顶层运算符时，如 SUM(x)、SUM(x + y)）
        String upper = t.toUpperCase();
        if (upper.startsWith("SUM(")) return "SUM";
        if (upper.startsWith("COUNT(")) return "COUNT";
        if (upper.startsWith("AVG(")) return "AVG";
        if (upper.startsWith("MAX(")) return "MAX";
        if (upper.startsWith("MIN(")) return "MIN";

        // 5. NVL / COALESCE
        if (upper.startsWith("NVL(") || upper.startsWith("COALESCE(")) return "NVL";

        // 6. CONCAT: || 或 CONCAT(...)
        if (t.contains("||") || upper.startsWith("CONCAT(")) return "CONCAT";

        // 7. FUNCTION_OTHER: 其他函数调用
        if (t.matches("(?i)^\\w+\\s*\\(.*")) return "FUNCTION_OTHER";

        // 8. DIRECT: 默认
        return "DIRECT";
    }

    /**
     * 去除表达式末尾的 "AS alias" 后缀，返回核心表达式
     * 用于 classifyLogicType / extractInputs，避免 AS alias 干扰分类
     */
    private String stripAsAlias(String expr) {
        if (expr == null) return null;
        String t = expr.trim();
        Matcher m = AS_ALIAS_PATTERN.matcher(t);
        if (m.find()) {
            return t.substring(0, m.start()).trim();
        }
        return t;
    }

    /**
     * 在括号深度 0 处扫描首个算术运算符，返回该运算符字符；无则返回 '\0'
     */
    private char findFirstTopLevelOperator(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && "+-*/".indexOf(c) >= 0) {
                if (i == 0) continue;
                char prev = expr.charAt(i - 1);
                if (!"+-*/(".contains(String.valueOf(prev))) {
                    return c;
                }
            }
        }
        return '\0';
    }

    /**
     * 从表达式中拆分出所有输入字段
     *
     * 算法:
     * 1. CASE WHEN 表达式: WHEN...THEN 之间的字段 → role=condition; THEN/ELSE 后的字段 → role=value
     * 2. 普通表达式: 提取所有 alias.field, 全部 role=value
     * 3. 去重: 同一 (sourceTableShort, sourceField) 多次出现 → 保留首次, input_order 取首次位置
     *    但不同 role 不去重
     *
     * @param expr SELECT 列表达式原文
     * @param aliasMap 别名 → 表短名映射
     * @param singleSourceTable 预计算的唯一源表（无别名前缀时使用），可为 null
     * @return TransformInput 列表，按 input_order 排序
     */
    public List<TransformInput> extractInputs(String expr, Map<String, String> aliasMap,
                                               String singleSourceTable) {
        List<TransformInput> inputs = new ArrayList<>();
        if (expr == null || expr.isBlank() || aliasMap == null || aliasMap.isEmpty()) {
            return inputs;
        }
        String t = stripAsAlias(expr);

        try {
            // 判断是否为 CASE WHEN 表达式
            Matcher caseM = CASE_PATTERN.matcher(t);
            if (caseM.matches()) {
                // CASE WHEN: 分别提取 condition 和 value 字段
                int order = 1;
                Set<String> seen = new HashSet<>();

                // WHEN...THEN 段: WHEN 后是 condition, THEN 后是 value
                Matcher whenThenM = WHEN_THEN_PATTERN.matcher(t);
                while (whenThenM.find()) {
                    String conditionPart = whenThenM.group(1);
                    String valuePart = whenThenM.group(2);
                    // 提取 condition 字段
                    order = addInputsFromSegment(conditionPart, aliasMap, singleSourceTable,
                            "condition", inputs, seen, order);
                    // 提取 value 字段
                    order = addInputsFromSegment(valuePart, aliasMap, singleSourceTable,
                            "value", inputs, seen, order);
                }

                // ELSE 段: value
                Matcher elseM = ELSE_PATTERN.matcher(t);
                if (elseM.find()) {
                    String elsePart = elseM.group(1);
                    order = addInputsFromSegment(elsePart, aliasMap, singleSourceTable,
                            "value", inputs, seen, order);
                }
            } else {
                // 普通表达式: 提取所有 alias.field, 全部 role=value
                Set<String> seen = new HashSet<>();
                addInputsFromSegment(t, aliasMap, singleSourceTable, "value", inputs, seen, 1);
            }
        } catch (Exception e) {
            log.warn("表达式拆分异常: expr={}, error={}", expr, e.getMessage());
            return new ArrayList<>();
        }

        return inputs;
    }

    /**
     * 从表达式片段中提取 alias.field 字段并添加到 inputs 列表
     * 去重: 同一 (sourceTableShort, sourceField) + role 组合只保留首次
     *
     * @return 下一个 input_order 值
     */
    private int addInputsFromSegment(String segment, Map<String, String> aliasMap,
                                      String singleSourceTable, String role,
                                      List<TransformInput> inputs, Set<String> seen, int startOrder) {
        int order = startOrder;
        Matcher m = ALIAS_FIELD_PATTERN.matcher(segment);
        while (m.find()) {
            String alias = m.group(1);
            String field = m.group(2);
            String tableShort = aliasMap.get(alias.toUpperCase());

            // 去重键: tableShort + field + role
            String dedupKey = (tableShort != null ? tableShort : "null") + "|" + field + "|" + role;
            if (seen.contains(dedupKey)) continue;
            seen.add(dedupKey);

            TransformInput ti = new TransformInput();
            ti.setSourceAlias(alias);
            ti.setSourceField(field);
            ti.setSourceTableShort(tableShort);
            ti.setRole(role);
            ti.setInputOrder(order++);
            inputs.add(ti);
        }

        // 处理无 alias 前缀的字段（如 acct_no），用 singleSourceTable 推断
        if (singleSourceTable != null) {
            // 提取独立字段名（不含点号的标识符）
            // (?!\s*\() 负向前瞻：跳过函数调用名（如 SUM、NVL 后跟括号），避免函数名被误当作字段
            Pattern standaloneField = Pattern.compile("(?<![\\w.])\\b(\\w+)\\b(?![\\w.])(?!\\s*\\()");
            Matcher sfm = standaloneField.matcher(segment);
            while (sfm.find()) {
                String field = sfm.group(1);
                // 跳过 SQL 关键字
                if (field.toUpperCase().matches("(CASE|WHEN|THEN|ELSE|END|AND|OR|NOT|NULL|IS|IN|BETWEEN|LIKE)")) {
                    continue;
                }
                // 跳过纯数字
                if (field.matches("\\d+")) continue;

                String dedupKey = singleSourceTable + "|" + field + "|" + role;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);

                TransformInput ti = new TransformInput();
                ti.setSourceAlias(null);
                ti.setSourceField(field);
                ti.setSourceTableShort(singleSourceTable);
                ti.setRole(role);
                ti.setInputOrder(order++);
                inputs.add(ti);
            }
        }

        return order;
    }

    /**
     * 为 FieldMapping 填充 inputs 和 logicType
     * 在 extract() 构造每个 FieldMapping 后调用
     *
     * @param fm 待填充的 FieldMapping
     * @param aliasMap 别名 → 表短名映射
     * @param singleSourceTable 预计算的唯一源表，可为 null
     */
    private void populateInputsAndLogicType(FieldMapping fm, Map<String, String> aliasMap,
                                             String singleSourceTable) {
        if (fm == null || fm.getSourceExpr() == null) {
            if (fm != null) {
                fm.setLogicType("DIRECT");
                fm.setInputs(new ArrayList<>());
            }
            return;
        }
        fm.setLogicType(classifyLogicType(fm.getSourceExpr()));
        fm.setInputs(extractInputs(fm.getSourceExpr(), aliasMap, singleSourceTable));
    }

    /**
     * 按括号深度拆分 SELECT 列表
     */
    private List<String> splitSelectColumns(String selectClause) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
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

    /**
     * 确定 target_field
     */
    private String determineTargetField(String expr, int order, List<String> ddlFields, int index) {
        if (ddlFields != null && !ddlFields.isEmpty()) {
            // 有 DDL，按位置取
            if (index < ddlFields.size()) {
                return ddlFields.get(index);
            }
            // SELECT 列多于 DDL，用 expr_order 占位
            return "expr_" + order;
        }
        // 无 DDL，从 AS alias 推断
        Matcher asMatcher = AS_ALIAS_PATTERN.matcher(expr.trim());
        if (asMatcher.find()) {
            return asMatcher.group(1).toLowerCase();
        }
        // 无 AS，尝试从 alias.field 取 field
        Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(expr);
        if (afMatcher.find()) {
            return afMatcher.group(2).toLowerCase();
        }
        // 表达式无别名，用 expr_order 占位
        return "expr_" + order;
    }

    /**
     * 从表达式中提取 alias 和 source_table_short
     * aliasMap 的 key 统一为大写，这里查找时也转大写以兼容大小写
     *
     * 当表达式无 alias 前缀（如 cust_no 而非 t1.cust_no）时，
     * 若 singleSourceTable 非空（FROM 子句仅有唯一源表），则自动关联到该源表。
     *
     * 字段提取优先级（避免 CASE WHEN 条件字段干扰）：
     * 1. CASE WHEN ... THEN alias.field ... END → 取 THEN 后的 alias.field（实际取值字段）
     * 2. 取最后一个 alias.field（SUM(t1.amt) → t1.amt；NVL(a.x, b.y) → b.y）
     * 3. 无 alias.field → 使用 singleSourceTable 推断
     *
     * @param expr SELECT 列表达式
     * @param aliasMap 别名 → 表短名映射
     * @param fm 待填充的 FieldMapping
     * @param singleSourceTable 预计算的唯一源表（无别名前缀时使用），可为 null
     */
    private void extractAliasAndTable(String expr, Map<String, String> aliasMap, FieldMapping fm,
                                       String singleSourceTable) {
        if (aliasMap == null || aliasMap.isEmpty()) {
            return;
        }
        String primaryAliasField = extractPrimaryAliasField(expr);
        if (primaryAliasField != null) {
            Matcher m = ALIAS_FIELD_PATTERN.matcher(primaryAliasField);
            if (m.find()) {
                String alias = m.group(1);
                // aliasMap 的 key 已在 LineageExtractor.extractAliasMap 中转为大写，
                // 这里查找时统一转大写，避免 t3/T3 大小写不匹配
                String tableShort = aliasMap.get(alias.toUpperCase());
                if (tableShort != null) {
                    fm.setSourceAlias(alias);
                    fm.setSourceTableShort(tableShort);
                    return;
                }
            }
        }
        // 无 alias.field 或 aliasMap 未命中：使用单源表推断
        if (singleSourceTable != null) {
            fm.setSourceTableShort(singleSourceTable);
        }
    }

    /**
     * 从 SELECT 列表达式中提取代表性的 alias.field（实际取值字段）
     *
     * 优先级：
     * 1. CASE WHEN ... THEN alias.field ... END → 返回 THEN 后首个 alias.field
     *    （避免误取条件中的 A.appl_dt，应取 THEN 后的 LR_DISTR.dist_amt_thmonth）
     * 2. 取最后一个 alias.field（SUM(t1.amt) → t1.amt；NVL(a.x, b.y) → b.y）
     *
     * @param expr SELECT 列表达式
     * @return 含 alias.field 的子串，未找到返回 null
     */
    private String extractPrimaryAliasField(String expr) {
        if (expr == null) return null;
        // 1. CASE WHEN ... THEN alias.field 模式：取 THEN 后首个 alias.field
        Matcher thenM = Pattern.compile("\\bTHEN\\s+(\\w+\\.\\w+)", Pattern.CASE_INSENSITIVE).matcher(expr);
        if (thenM.find()) {
            return thenM.group(1);
        }
        // 2. 非 CASE 表达式：取最后一个 alias.field（避免 SUM(CASE WHEN...) 中误取条件字段）
        Matcher m = ALIAS_FIELD_PATTERN.matcher(expr);
        String last = null;
        while (m.find()) {
            last = m.group(0);
        }
        return last;
    }

    /**
     * 计算 aliasMap 中的唯一源表（排除目标表自身）
     * 当 FROM/JOIN 仅涉及一张表时返回该表名，否则返回 null
     */
    private String computeSingleSourceTable(Map<String, String> aliasMap, String targetTableShort) {
        if (aliasMap == null || aliasMap.isEmpty()) {
            return null;
        }
        Set<String> uniqueTables = new HashSet<>();
        for (String table : aliasMap.values()) {
            if (targetTableShort != null && targetTableShort.equalsIgnoreCase(table)) {
                continue; // 排除目标表自身
            }
            uniqueTables.add(table);
        }
        if (uniqueTables.size() == 1) {
            return uniqueTables.iterator().next();
        }
        return null;
    }
}
