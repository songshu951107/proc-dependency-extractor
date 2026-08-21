package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ConditionField;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.TransformInput;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.schema.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段映射提取器（基于 JsqlParser AST）
 *
 * 从 INSERT INTO ... SELECT / CREATE TEMP TABLE ... AS SELECT 提取字段级映射：
 * - 有 DDL 字段列表：按 AS alias > alias.field > 位置 三级优先匹配
 * - 无 DDL：按 SELECT 列原顺序，targetField 从 AS alias / alias.field / expr_{order} 推断
 * - 每条映射填充 inputs（CASE WHEN 区分 value/condition）和 logicType
 *
 * logicType 保留"顶层运算符优先于内层聚合"语义：
 *   SUM(x) + y → ADDITION（顶层 + 优先）
 *   SUM(x + y) → SUM（顶层聚合）
 *
 * 替代原正则方案，用 AST 精确识别表达式类型。
 */
@Slf4j
@Component
public class FieldMappingExtractor {

    @Autowired
    private JsqlSqlAnalyzer analyzer;

    @Autowired
    private SqlPlaceholderReplacer placeholderReplacer;

    @Autowired
    private com.hb.extractor.mapper.TableColumnMetaMapper tableColumnMetaMapper;

    /** SQL 关键字黑名单，过滤误识别为字段名的关键字 */
    private static final Set<String> SQL_KEYWORDS = new HashSet<>(java.util.Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION",
            "ALL", "DISTINCT", "AS", "ON", "AND", "OR", "NOT", "NULL", "CASE", "WHEN",
            "THEN", "ELSE", "END", "BY", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
            "CROSS", "USING", "WITH", "SET", "VALUES", "EXISTS", "BETWEEN", "LIKE",
            "ASC", "DESC", "JOIN", "INTO", "UPDATE", "DELETE", "INSERT", "MERGE",
            "TABLE", "TEMPORARY", "CREATE", "DROP", "ALTER", "TRUNCATE", "PRIMARY",
            "FOREIGN", "KEY", "INDEX", "UNIQUE", "CHECK", "DEFAULT", "CONSTRAINT",
            "COLUMN", "CAST", "CONVERT", "DATE", "TIME", "TIMESTAMP", "INTERVAL",
            "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND"
    ));

    /**
     * 提取字段映射
     *
     * @param sqlBlock         单个 SQL 块原文
     * @param targetTableShort 目标表短名
     * @param aliasMap         别名→表短名映射（key 大写）
     * @param ddlFields        DDL 字段列表（可为 null）
     * @return FieldMapping 列表
     */
    public List<FieldMapping> extract(String sqlBlock,
                                      String targetTableShort,
                                      Map<String, String> aliasMap,
                                      List<String> ddlFields) {
        List<FieldMapping> result = new ArrayList<>();
        if (sqlBlock == null || sqlBlock.trim().isEmpty()) return result;

        Statement stmt = analyzer.parse(sqlBlock);
        if (stmt == null) return result;

        // 仅处理 INSERT 和 CREATE TABLE AS SELECT
        PlainSelect select = null;
        List<String> targetColumns = null;

        if (stmt instanceof Insert) {
            Insert ins = (Insert) stmt;
            // INSERT 的目标列（可能为 null，表示按 SELECT 列顺序）
            if (ins.getColumns() != null) {
                targetColumns = new ArrayList<>();
                for (Column c : ins.getColumns()) {
                    targetColumns.add(c.getColumnName().toLowerCase());
                }
            }
            Select sel = ins.getSelect();
            if (sel != null && sel.getSelectBody() instanceof PlainSelect) {
                select = (PlainSelect) sel.getSelectBody();
            }
        } else if (stmt instanceof CreateTable) {
            CreateTable ct = (CreateTable) stmt;
            // CREATE TABLE AS SELECT
            Select sel = ct.getSelect();
            if (sel != null && sel.getSelectBody() instanceof PlainSelect) {
                select = (PlainSelect) sel.getSelectBody();
            }
        }

        if (select == null) return result;

        // 提取 SELECT 列
        List<SelectItem<?>> selectItems = select.getSelectItems();
        if (selectItems == null || selectItems.isEmpty()) return result;

        // 判断是否有 SELECT *（无法精确映射）
        // 4.9: AllColumns 是 Expression，通过 SelectItem.getExpression() 判定
        boolean hasWildcard = selectItems.stream()
                .anyMatch(i -> i.getExpression() instanceof AllColumns);
        if (hasWildcard) {
            log.debug("SELECT * 出现在块中, 无法精确映射字段, targetTable={}", targetTableShort);
            // 尝试用 DDL 字段列表填充 DIRECT 映射
            if (ddlFields != null && !ddlFields.isEmpty()) {
                for (int i = 0; i < ddlFields.size(); i++) {
                    FieldMapping fm = new FieldMapping();
                    fm.setFieldOrder(i + 1);
                    fm.setTargetField(ddlFields.get(i));
                    fm.setSourceExpr("*");
                    fm.setLogicType("DIRECT");
                    result.add(fm);
                }
            }
            return result;
        }

        // 收集 SelectItemInfo（保留原顺序）
        List<SelectExprInfo> exprInfos = collectSelectExprs(selectItems);

        // 决定 targetField 来源：优先 INSERT 的目标列，其次 DDL 字段列表
        if (targetColumns != null && !targetColumns.isEmpty()) {
            // INSERT 指定了目标列：按位置对应
            matchByTargetColumns(exprInfos, targetColumns, aliasMap, result);
        } else if (ddlFields != null && !ddlFields.isEmpty()) {
            // 有 DDL 字段列表：按三级优先匹配
            matchByDdlFields(exprInfos, ddlFields, aliasMap, result);
        } else {
            // 无 DDL：按 SELECT 列原顺序，targetField 从 AS alias / alias.field 推断
            matchBySelectOnly(exprInfos, aliasMap, result);
        }

        // 子查询字段穿透解析: TAA.field / 裸字段 → 沿 UNION 各分支 SELECT 项解析到底层表字段
        applySubqueryColumnPassthrough(select, result);

        return result;
    }

    /**
     * 提取 SQL 块的 ON/WHERE 条件字段（行血缘，独立于 FieldMapping 值输入）。
     * 归属解析策略与值输入一致：alias 命中 > 单表兜底 > DDL 字段唯一匹配；
     * 归属失败（多重/无匹配）sourceTableShort=null：落库保留、Neo4j 同步跳过。
     * key=(alias, field, role) 去重，同键多条件 clause 分号拼接、截断 512 字符。
     */
    public List<ConditionField> extractConditionFields(PlainSelect select,
                                                       Map<String, String> aliasMap) {
        List<ConditionField> result = new ArrayList<>();
        if (select == null || aliasMap == null || aliasMap.isEmpty()) return result;

        // 复用 FilterInfo（携带 clause 原文），JOIN → join_condition / WHERE → where_condition
        List<JsqlSqlAnalyzer.FilterInfo> filters = new ArrayList<>();
        filters.addAll(analyzer.extractJoinFilters(select));
        filters.addAll(analyzer.extractWhereFilters(select));
        if (filters.isEmpty()) return result;

        java.util.Map<String, java.util.Set<String>> ddlCache = null;
        java.util.Map<String, ConditionField> dedup = new java.util.LinkedHashMap<>();
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            if (fi == null || fi.fields == null) continue;
            String role = "JOIN".equalsIgnoreCase(fi.filterType) ? "join_condition" : "where_condition";
            for (JsqlSqlAnalyzer.ColumnRef ref : fi.fields) {
                if (ref.field == null) continue;
                if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
                if (ref.field.matches("\\d+")) continue;
                String key = (ref.alias != null ? ref.alias + "." : "") + ref.field + "/" + role;
                ConditionField cf = dedup.get(key);
                if (cf == null) {
                    cf = new ConditionField();
                    cf.setSourceAlias(ref.alias);
                    cf.setSourceField(ref.field);
                    cf.setRole(role);
                    dedup.put(key, cf);
                }
                if (fi.clause != null && !fi.clause.isEmpty()) {
                    cf.setClause(cf.getClause() == null ? fi.clause : cf.getClause() + ";" + fi.clause);
                }
            }
        }
        for (ConditionField cf : dedup.values()) {
            if (cf.getClause() != null && cf.getClause().length() > 512) {
                cf.setClause(cf.getClause().substring(0, 512));
            }
            if (cf.getSourceAlias() != null && aliasMap.containsKey(cf.getSourceAlias())) {
                cf.setSourceTableShort(aliasMap.get(cf.getSourceAlias()));
            } else if (aliasMap.size() == 1) {
                String onlyTable = aliasMap.values().iterator().next();
                if (onlyTable != null) cf.setSourceTableShort(onlyTable);
            } else if (aliasMap.size() > 1) {
                if (ddlCache == null) ddlCache = buildDdlCache(aliasMap.values());
                cf.setSourceTableShort(matchFieldToTable(cf.getSourceField(), ddlCache));
            }
            result.add(cf);
        }
        return result;
    }

    /** 收集 SELECT 列表达式信息 */
    private List<SelectExprInfo> collectSelectExprs(List<SelectItem<?>> items) {
        List<SelectExprInfo> result = new ArrayList<>();
        int order = 1;
        for (SelectItem<?> item : items) {
            // 4.9: SelectItem 直接承载 expression + alias（SelectExpressionItem 已合并入 SelectItem）
            Expression expr = item.getExpression();
            if (expr != null) {
                SelectExprInfo info = new SelectExprInfo();
                info.expression = expr;
                info.exprText = expr.toString();
                info.order = order;
                Alias a = item.getAlias();
                info.alias = a != null ? a.getName().toUpperCase() : null;
                // alias.field 形式（直接是 Column 对象）
                if (expr instanceof Column) {
                    Column col = (Column) expr;
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        info.colAlias = col.getTable().getName().toUpperCase();
                        info.colField = col.getColumnName().toLowerCase();
                    }
                }
                result.add(info);
            }
            order++;
        }
        return result;
    }

    /** INSERT 指定了目标列：按位置对应 */
    private void matchByTargetColumns(List<SelectExprInfo> exprInfos, List<String> targetColumns,
                                       Map<String, String> aliasMap, List<FieldMapping> result) {
        int n = Math.min(exprInfos.size(), targetColumns.size());
        for (int i = 0; i < n; i++) {
            SelectExprInfo info = exprInfos.get(i);
            FieldMapping fm = buildFieldMapping(info, targetColumns.get(i), aliasMap);
            result.add(fm);
        }
    }

    /** 有 DDL 字段列表：三级优先匹配（AS alias > alias.field > 位置） */
    private void matchByDdlFields(List<SelectExprInfo> exprInfos, List<String> ddlFields,
                                   Map<String, String> aliasMap, List<FieldMapping> result) {
        boolean[] used = new boolean[exprInfos.size()];  // 标记 SELECT 列是否已被用
        // 第一遍：AS alias 等于 DDL 字段名
        for (String ddlField : ddlFields) {
            SelectExprInfo match = null;
            // 优先级 1：AS alias 等于 DDL 字段
            for (int i = 0; i < exprInfos.size(); i++) {
                if (used[i]) continue;
                SelectExprInfo info = exprInfos.get(i);
                if (info.alias != null && info.alias.equalsIgnoreCase(ddlField)) {
                    match = info;
                    used[i] = true;
                    break;
                }
            }
            // 优先级 2：alias.field 中 field 等于 DDL 字段
            if (match == null) {
                for (int i = 0; i < exprInfos.size(); i++) {
                    if (used[i]) continue;
                    SelectExprInfo info = exprInfos.get(i);
                    if (info.colField != null && info.colField.equalsIgnoreCase(ddlField)) {
                        match = info;
                        used[i] = true;
                        break;
                    }
                }
            }
            // 优先级 3：位置取剩余未用列第一个
            if (match == null) {
                for (int i = 0; i < exprInfos.size(); i++) {
                    if (!used[i]) {
                        match = exprInfos.get(i);
                        used[i] = true;
                        break;
                    }
                }
            }
            if (match != null) {
                result.add(buildFieldMapping(match, ddlField, aliasMap));
            }
            // DDL 多于 SELECT 列则跳过
        }
    }

    /** 无 DDL：按 SELECT 列原顺序 */
    private void matchBySelectOnly(List<SelectExprInfo> exprInfos,
                                    Map<String, String> aliasMap, List<FieldMapping> result) {
        for (SelectExprInfo info : exprInfos) {
            String targetField = determineTargetField(info);
            result.add(buildFieldMapping(info, targetField, aliasMap));
        }
    }

    /** 无 DDL 时推断 targetField：AS alias > alias.field > expr_{order} */
    private String determineTargetField(SelectExprInfo info) {
        if (info.alias != null) return info.alias.toLowerCase();
        if (info.colField != null) return info.colField;
        return "expr_" + info.order;
    }

    /** 构造 FieldMapping，填充 sourceAlias/sourceTableShort/logicType/inputs */
    private FieldMapping buildFieldMapping(SelectExprInfo info, String targetField,
                                            Map<String, String> aliasMap) {
        FieldMapping fm = new FieldMapping();
        fm.setTargetField(targetField);
        fm.setFieldOrder(info.order);
        // sourceExpr：还原占位符字面量
        String expr = info.exprText;
        if (placeholderReplacer.isPlaceholderLiteral(stripQuotes(expr))) {
            expr = placeholderReplacer.restorePlaceholder(stripQuotes(expr));
        }
        fm.setSourceExpr(expr);

        // sourceAlias / sourceTableShort：优先 alias.field 的 alias，其次 AS alias（不可靠，仅作 hint）
        if (info.colAlias != null) {
            fm.setSourceAlias(info.colAlias);
            fm.setSourceTableShort(aliasMap.getOrDefault(info.colAlias, info.colAlias));
        } else if (info.alias != null) {
            fm.setSourceAlias(info.alias);
            // AS alias 不能确定来源表，保持空
        }

        // logicType
        fm.setLogicType(analyzer.classifyLogicType(info.expression));

        // inputs：从表达式递归提取字段引用
        fm.getInputs().addAll(buildInputs(info.expression, aliasMap, null));

        // 兜底：复杂表达式（CASE WHEN / 函数 / 算术）无顶层 alias.field，
        // 若 sourceTableShort 仍为空，从 inputs 第一个非空来源表推断
        if (fm.getSourceTableShort() == null) {
            for (TransformInput ti : fm.getInputs()) {
                if (ti.getSourceTableShort() != null) {
                    fm.setSourceTableShort(ti.getSourceTableShort());
                    if (fm.getSourceAlias() == null) {
                        fm.setSourceAlias(ti.getSourceAlias());
                    }
                    break;
                }
            }
        }

        return fm;
    }

    /**
     * 从表达式构造 TransformInput 列表（去重 + aliasMap/singleSourceTable 兜底）。
     *
     * @param expr              AST 表达式
     * @param aliasMap          别名→表短名映射（不可为 null）
     * @param singleSourceTable 单源表兜底（ref.alias 为 null 时使用；可为 null）
     */
    private List<TransformInput> buildInputs(Expression expr,
                                              Map<String, String> aliasMap,
                                              String singleSourceTable) {
        List<TransformInput> result = new ArrayList<>();
        if (expr == null) return result;

        List<JsqlSqlAnalyzer.ColumnRef> colRefs = new ArrayList<>();
        // CASE WHEN 表达式内部按 value/role 区分
        analyzer.collectColumnRefs(expr, "value", colRefs);

        // 去重并构造 TransformInput
        java.util.Map<String, TransformInput> dedup = new LinkedHashMap<>();
        int inputOrder = 1;
        for (JsqlSqlAnalyzer.ColumnRef ref : colRefs) {
            if (ref.field == null) continue;
            // 过滤 SQL 关键字
            if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
            // 过滤纯数字
            if (ref.field.matches("\\d+")) continue;
            String key = (ref.alias != null ? ref.alias + "." : "") + ref.field + "/" + ref.role;
            if (dedup.containsKey(key)) continue;
            TransformInput ti = new TransformInput();
            ti.setSourceAlias(ref.alias);
            ti.setSourceField(ref.field);
            ti.setRole(ref.role);
            ti.setInputOrder(inputOrder++);
            // 通过 aliasMap 推断来源表
            if (ref.alias != null && aliasMap.containsKey(ref.alias)) {
                ti.setSourceTableShort(aliasMap.get(ref.alias));
            } else if (singleSourceTable != null) {
                // 字符串入口的单源表兜底（无 alias 前缀字段）
                ti.setSourceTableShort(singleSourceTable);
            } else if (aliasMap.size() == 1) {
                // extract 流程的单源表兜底
                String onlyTable = aliasMap.values().iterator().next();
                if (onlyTable != null) {
                    ti.setSourceTableShort(onlyTable);
                }
            }
            dedup.put(key, ti);
        }
        result.addAll(dedup.values());
        return result;
    }

    /** 去掉字符串字面量的引号 */
    private String stripQuotes(String s) {
        if (s == null) return null;
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 字符串入口：解析表达式字符串为 AST，再分类逻辑类型。
     * 供单表达式分析场景使用（测试 / API 暴露）。
     * 解析失败返回 "DIRECT"。
     */
    public String classifyLogicType(String exprText) {
        if (exprText == null || exprText.trim().isEmpty()) return "DIRECT";
        Expression expr = parseExpression(exprText);
        return expr == null ? "DIRECT" : analyzer.classifyLogicType(expr);
    }

    /**
     * 字符串入口：解析表达式字符串为 AST，再提取 TransformInput 列表。
     * 供单表达式分析场景使用（测试 / API 暴露）。
     *
     * @param exprText          表达式原文（可含 {ETL_TX_DT} 占位符）
     * @param aliasMap         别名→表短名映射
     * @param singleSourceTable 单源表兜底（ref.alias 为 null 时使用；可为 null）
     */
    public List<TransformInput> extractInputs(String exprText,
                                               Map<String, String> aliasMap,
                                               String singleSourceTable) {
        if (exprText == null || exprText.trim().isEmpty()) return new ArrayList<>();
        Map<String, String> effectiveMap = aliasMap != null ? aliasMap : new HashMap<>();
        Expression expr = parseExpression(exprText);
        if (expr == null) return new ArrayList<>();
        return buildInputs(expr, effectiveMap, singleSourceTable);
    }

    /** 解析表达式字符串为 AST（先 preprocess 占位符） */
    private Expression parseExpression(String exprText) {
        String processed = placeholderReplacer.preprocess(exprText);
        try {
            return CCJSqlParserUtil.parseExpression(processed);
        } catch (Exception e) {
            log.debug("表达式解析失败: {} | 原文: {}", e.getMessage(), exprText);
            return null;
        }
    }

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

    /** ==================== 子查询字段穿透解析 ==================== **/

    /** 子查询列解析结果: 子查询 SELECT 项内字段引用解析到底层表后的归属 */
    private static class ResolvedRef {
        final String sourceTableShort;
        final String sourceField;
        final String sourceAlias;

        ResolvedRef(String sourceTableShort, String sourceField, String sourceAlias) {
            this.sourceTableShort = sourceTableShort;
            this.sourceField = sourceField;
            this.sourceAlias = sourceAlias;
        }
    }

    /**
     * 子查询字段穿透解析。
     *
     * 外层引用子查询别名（如 FROM (SELECT...UNION ALL...) TAA 后取 TAA.acct_no）
     * 或裸字段时，aliasMap 中 TAA → null，字段无法归属具体表。
     * 本方法预建"子查询 scope 别名 → 列名 → 底层表字段"索引，
     * 再把 FieldMapping.inputs 中无表归属的输入替换为沿 UNION 各分支
     * SELECT 项解析出的底层表字段（如 TAA.acct_no → vt_01.acct_no + c_agt.acct_no）。
     * 查不到的输入保持原样；已有表归属的输入不受影响。
     */
    private void applySubqueryColumnPassthrough(PlainSelect select, List<FieldMapping> result) {
        if (select == null || result == null || result.isEmpty()) return;
        Map<String, Map<String, List<ResolvedRef>>> subqueryIndex = buildSubqueryColumnIndex(select);
        if (subqueryIndex.isEmpty()) return;

        for (FieldMapping fm : result) {
            if (fm.getInputs() == null || fm.getInputs().isEmpty()) continue;
            List<TransformInput> oldInputs = fm.getInputs();
            boolean changed = false;
            // 新输入列表（按 table+field+role 去重）
            LinkedHashMap<String, TransformInput> dedup = new LinkedHashMap<>();
            int nextOrder = oldInputs.stream().mapToInt(TransformInput::getInputOrder).max().orElse(0);
            for (TransformInput ti : oldInputs) {
                if (ti.getSourceTableShort() != null) {
                    addPassthroughInput(dedup, ti.getSourceTableShort(), ti.getSourceField(),
                            ti.getSourceAlias(), ti.getRole(), ti.getInputOrder());
                    continue;
                }
                // 确定查询 scope: 有别名按别名（如 TAA）; 裸字段且 FROM 恰好只有一个子查询项时用唯一 scope
                Map<String, List<ResolvedRef>> scopeColumns = null;
                if (ti.getSourceAlias() != null) {
                    scopeColumns = subqueryIndex.get(ti.getSourceAlias());
                } else if (subqueryIndex.size() == 1) {
                    scopeColumns = subqueryIndex.values().iterator().next();
                }
                List<ResolvedRef> resolved = scopeColumns != null
                        ? scopeColumns.get(ti.getSourceField()) : null;
                if (resolved == null || resolved.isEmpty()) {
                    // 查不到: 保持原样
                    addPassthroughInput(dedup, null, ti.getSourceField(),
                            ti.getSourceAlias(), ti.getRole(), ti.getInputOrder());
                    continue;
                }
                changed = true;
                for (ResolvedRef rr : resolved) {
                    addPassthroughInput(dedup, rr.sourceTableShort, rr.sourceField,
                            rr.sourceAlias, ti.getRole(), ++nextOrder);
                }
            }
            if (changed) {
                List<TransformInput> newInputs = new ArrayList<>(dedup.values());
                fm.setInputs(newInputs);
            }
        }
    }

    /** 构造子查询列索引: scope别名(大写) → 列名(小写) → 底层表字段解析列表 */
    private Map<String, Map<String, List<ResolvedRef>>> buildSubqueryColumnIndex(PlainSelect select) {
        Map<String, Map<String, List<ResolvedRef>>> index = new LinkedHashMap<>();
        List<FromItem> fromItems = new ArrayList<>();
        if (select.getFromItem() != null) fromItems.add(select.getFromItem());
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getRightItem() != null) fromItems.add(join.getRightItem());
            }
        }
        for (FromItem fi : fromItems) {
            if (!(fi instanceof ParenthesedSelect)) continue;
            Alias a = fi.getAlias();
            if (a == null || a.getName() == null) continue;  // 无别名子查询无法被外层引用
            String scope = a.getName().toUpperCase();
            Map<String, List<ResolvedRef>> scopeColumns =
                    index.computeIfAbsent(scope, k -> new LinkedHashMap<>());
            collectBranchColumns(((ParenthesedSelect) fi).getSelect(), scopeColumns, 0);
        }
        return index;
    }

    /** 遍历子查询内部各分支（PlainSelect / SetOperationList / 嵌套括号，深度 ≤2）建立列索引 */
    private void collectBranchColumns(Select sel, Map<String, List<ResolvedRef>> scopeColumns, int depth) {
        if (sel == null || depth > 2) return;
        if (sel instanceof PlainSelect) {
            indexBranchColumns((PlainSelect) sel, scopeColumns);
        } else if (sel instanceof SetOperationList) {
            for (Select s : ((SetOperationList) sel).getSelects()) {
                collectBranchColumns(s, scopeColumns, depth + 1);
            }
        } else if (sel instanceof ParenthesedSelect) {
            collectBranchColumns(((ParenthesedSelect) sel).getSelect(), scopeColumns, depth + 1);
        }
    }

    /** 单个分支: 遍历 SELECT 项，列名（AS 别名优先，其次裸列名）→ 项内字段引用解析到底层表 */
    private void indexBranchColumns(PlainSelect branch, Map<String, List<ResolvedRef>> scopeColumns) {
        Map<String, String> branchAliasMap = analyzer.extractAliasMap(branch);
        for (SelectItem<?> item : branch.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr == null || expr instanceof AllColumns) continue;  // SELECT * 无法按列名索引
            String colName = null;
            Alias a = item.getAlias();
            if (a != null && a.getName() != null) {
                colName = a.getName().toLowerCase();
            } else if (expr instanceof Column && ((Column) expr).getColumnName() != null) {
                colName = ((Column) expr).getColumnName().toLowerCase();
            }
            if (colName == null) continue;

            List<JsqlSqlAnalyzer.ColumnRef> refs = new ArrayList<>();
            analyzer.collectColumnRefs(expr, "value", refs);
            for (JsqlSqlAnalyzer.ColumnRef ref : refs) {
                if (ref.field == null) continue;
                if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
                if (ref.field.matches("\\d+")) continue;
                // 归属表解析: 分支 aliasMap; 裸列用分支单表兜底
                String table = null;
                if (ref.alias != null && branchAliasMap.containsKey(ref.alias)) {
                    table = branchAliasMap.get(ref.alias);
                } else if (ref.alias == null && branchAliasMap.size() == 1) {
                    String only = branchAliasMap.values().iterator().next();
                    if (only != null) table = only;
                }
                if (table == null) continue;
                List<ResolvedRef> list = scopeColumns.computeIfAbsent(colName, k -> new ArrayList<>());
                boolean exists = false;
                for (ResolvedRef r : list) {
                    if (r.sourceTableShort.equals(table) && r.sourceField.equals(ref.field)
                            && java.util.Objects.equals(r.sourceAlias, ref.alias)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(new ResolvedRef(table, ref.field, ref.alias));
                }
            }
        }
    }

    /** 追加穿透输入（按 table+field+role 去重） */
    private void addPassthroughInput(LinkedHashMap<String, TransformInput> dedup,
                                     String tableShort, String field, String alias,
                                     String role, int inputOrder) {
        String key = tableShort + "." + field + "/" + role;
        if (dedup.containsKey(key)) return;
        TransformInput ti = new TransformInput();
        ti.setSourceTableShort(tableShort);
        ti.setSourceField(field);
        ti.setSourceAlias(alias);
        ti.setRole(role);
        ti.setInputOrder(inputOrder);
        dedup.put(key, ti);
    }

    /** 内部：SELECT 列表达式信息 */
    private static class SelectExprInfo {
        Expression expression;  // AST 表达式
        String exprText;        // 表达式原文
        int order;              // 列序号
        String alias;           // AS alias（大写）
        String colAlias;        // alias.field 中的 alias（大写）
        String colField;        // alias.field 中的 field（小写）
    }
}
