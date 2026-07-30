package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.FieldMapping;
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

                mappings.add(fm);
            }
        }

        return mappings;
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
