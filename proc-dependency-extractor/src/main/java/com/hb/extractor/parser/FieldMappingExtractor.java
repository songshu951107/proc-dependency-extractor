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
import net.sf.jsqlparser.statement.select.AllTableColumns;
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
        return extract(sqlBlock, targetTableShort, aliasMap, ddlFields, null);
    }

    /**
     * 提取字段映射（带临时表列定义）。
     *
     * @param tempColumnDefs 临时表列定义（key=表短名小写, value=CREATE TEMP 内联列定义），
     *                       用于多表 JOIN 裸列唯一匹配归属；可为 null
     */
    public List<FieldMapping> extract(String sqlBlock,
                                      String targetTableShort,
                                      Map<String, String> aliasMap,
                                      List<String> ddlFields,
                                      Map<String, List<String>> tempColumnDefs) {
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
            if (sel != null) {
                // P4: INSERT (SELECT ...) 括号形式同样解开
                select = unwrapPlainSelect(sel);
            }
        } else if (stmt instanceof CreateTable) {
            CreateTable ct = (CreateTable) stmt;
            // CREATE TABLE AS SELECT（含 AS (SELECT ...) 括号形式, P4）
            select = unwrapPlainSelect(ct.getSelect());
        }

        if (select == null) return result;

        // P3: SELECT 项 A.* 前缀为子查询别名时, 展开为子查询具体列（A.col1, A.col2 ...）,
        // 复用既有列级匹配与穿透链路; 物理表别名前缀或不可枚举（内层仅 __star__）时保持原行为。
        // 注意 AllTableColumns extends AllColumns, 必须在 hasWildcard 早退检查之前展开。
        expandSubqueryAliasStarItems(select, tempColumnDefs);

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

        // 裸列候选缓存（表短名→列名大写集合）: 多表 JOIN 裸列唯一匹配归属用, 惰性构建。
        // 物理表列来自 table_column_meta, 临时表列来自 CREATE TEMP 内联定义（tempColumnDefs）。
        // 仅在真正遇到多表裸列时才查库构建（memoized supplier, 匿名类闭包捕获 tempColumnDefs）。
        final java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef =
                new java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>>() {
                    private java.util.Map<String, java.util.Set<String>> cache;
                    @Override
                    public java.util.Map<String, java.util.Set<String>> get() {
                        if (cache == null) cache = buildBareColumnCache(aliasMap, tempColumnDefs);
                        return cache;
                    }
                };

        // 决定 targetField 来源：优先 INSERT 的目标列，其次 DDL 字段列表
        if (targetColumns != null && !targetColumns.isEmpty()) {
            // INSERT 指定了目标列：按位置对应
            matchByTargetColumns(exprInfos, targetColumns, aliasMap, bareColumnCacheRef, result);
        } else if (ddlFields != null && !ddlFields.isEmpty()) {
            // 有 DDL 字段列表：按三级优先匹配
            matchByDdlFields(exprInfos, ddlFields, aliasMap, bareColumnCacheRef, result);
        } else {
            // 无 DDL：按 SELECT 列原顺序，targetField 从 AS alias / alias.field 推断
            matchBySelectOnly(exprInfos, aliasMap, bareColumnCacheRef, result);
        }

        // 子查询字段穿透解析: TAA.field / 裸字段 → 沿 UNION 各分支 SELECT 项解析到底层表字段
        applySubqueryColumnPassthrough(select, result, tempColumnDefs);

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
        // 递归下钻派生表子查询，覆盖子查询内 JOIN/WHERE 条件
        List<JsqlSqlAnalyzer.FilterInfo> filters = analyzer.extractFiltersRecursively(select);
        if (filters.isEmpty()) return result;

        // 缓存：作用域表集合 → DDL 候选列缓存（多表 JOIN 裸列唯一匹配用）
        java.util.Map<String, java.util.Map<String, java.util.Set<String>>> ddlCacheByScope =
                new java.util.HashMap<>();
        java.util.Map<String, ConditionField> dedup = new java.util.LinkedHashMap<>();
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            if (fi == null || fi.fields == null) continue;
            String role = "JOIN".equalsIgnoreCase(fi.filterType) ? "join_condition" : "where_condition";
            // 作用域别名映射：条件所在作用域优先，顶层（scopeAliasMap 为 null）用传入 aliasMap 兜底
            java.util.Map<String, String> scopeMap =
                    fi.scopeAliasMap != null ? fi.scopeAliasMap : aliasMap;
            if (scopeMap == null || scopeMap.isEmpty()) continue;
            for (JsqlSqlAnalyzer.ColumnRef ref : fi.fields) {
                if (ref.field == null) continue;
                if (SQL_KEYWORDS.contains(ref.field.toUpperCase())) continue;
                if (ref.field.matches("\\d+")) continue;
                // 按作用域解析来源表短名（alias 命中 → 单表兜底 → DDL 字段唯一匹配）
                String resolved = resolveScopeTable(ref, scopeMap, ddlCacheByScope);
                // 去重键须与 DB 唯一约束 uk_block_cond(lineage_id,source_alias,source_field,role) 对齐：
                // 只用 alias+field+role。若把 resolved 表加入键，同一 alias+field+role 跨作用域解析到不同表
                // 会拆成多行，在相同 lineage_id 下触发唯一键冲突（uk 不含 source_table_short）。
                String key = (ref.alias != null ? ref.alias : "") + "|" + ref.field + "|" + role;
                ConditionField cf = dedup.get(key);
                if (cf == null) {
                    cf = new ConditionField();
                    cf.setSourceAlias(ref.alias);
                    cf.setSourceField(ref.field);
                    cf.setRole(role);
                    if (resolved != null) cf.setSourceTableShort(resolved);
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
            result.add(cf);
        }
        return result;
    }

    /**
     * 按条件所在作用域的别名映射解析来源表短名。
     * 优先级：alias 命中（含 null 占位，防错误归属，返回 null）→ 单表兜底 → 多表 DDL 字段唯一匹配。
     * ddlCacheByScope 按键（作用域表短名排序串）记忆化，避免同作用域重复查库。
     */
    private String resolveScopeTable(JsqlSqlAnalyzer.ColumnRef ref,
                                     java.util.Map<String, String> scopeMap,
                                     java.util.Map<String, java.util.Map<String, java.util.Set<String>>> ddlCacheByScope) {
        if (ref.alias != null && scopeMap.containsKey(ref.alias)) {
            return scopeMap.get(ref.alias);   // 可能为 null（子查询别名占位）→ 防错误归属
        }
        if (scopeMap.size() == 1) {
            String onlyTable = scopeMap.values().iterator().next();
            return onlyTable;
        }
        if (scopeMap.size() > 1) {
            String scopeKey = String.join(",",
                    scopeMap.values().stream().filter(java.util.Objects::nonNull).sorted().toArray(String[]::new));
            java.util.Map<String, java.util.Set<String>> ddlCache = ddlCacheByScope.get(scopeKey);
            if (ddlCache == null) {
                ddlCache = buildDdlCache(scopeMap.values());
                ddlCacheByScope.put(scopeKey, ddlCache);
            }
            return matchFieldToTable(ref.field, ddlCache);
        }
        return null;
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
                // alias.field 形式（直接是 Column 对象）; 裸列同样记录 colField
                // （无 DDL 时 targetField 推断 alias > colField > expr_N, 裸列丢失列名会退化成 expr_N,
                //  污染 CTAS 派生列缓存; P4 CTAS 括号形式块常见裸列 SELECT）
                if (expr instanceof Column) {
                    Column col = (Column) expr;
                    if (col.getTable() != null && col.getTable().getName() != null) {
                        info.colAlias = col.getTable().getName().toUpperCase();
                    }
                    if (col.getColumnName() != null) {
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
                                       Map<String, String> aliasMap,
                                       java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef,
                                       List<FieldMapping> result) {
        int n = Math.min(exprInfos.size(), targetColumns.size());
        for (int i = 0; i < n; i++) {
            SelectExprInfo info = exprInfos.get(i);
            FieldMapping fm = buildFieldMapping(info, targetColumns.get(i), aliasMap, bareColumnCacheRef);
            result.add(fm);
        }
    }

    /** 有 DDL 字段列表：三级优先匹配（AS alias > alias.field > 位置） */
    private void matchByDdlFields(List<SelectExprInfo> exprInfos, List<String> ddlFields,
                                   Map<String, String> aliasMap,
                                   java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef,
                                   List<FieldMapping> result) {
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
                result.add(buildFieldMapping(match, ddlField, aliasMap, bareColumnCacheRef));
            }
            // DDL 多于 SELECT 列则跳过
        }
    }

    /** 无 DDL：按 SELECT 列原顺序 */
    private void matchBySelectOnly(List<SelectExprInfo> exprInfos,
                                    Map<String, String> aliasMap,
                                    java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef,
                                    List<FieldMapping> result) {
        for (SelectExprInfo info : exprInfos) {
            String targetField = determineTargetField(info);
            result.add(buildFieldMapping(info, targetField, aliasMap, bareColumnCacheRef));
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
                                            Map<String, String> aliasMap,
                                            java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef) {
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
        fm.getInputs().addAll(buildInputs(info.expression, aliasMap, null, bareColumnCacheRef));

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
     * @param bareColumnCacheRef 多表 JOIN 裸列唯一匹配缓存（可为 null 表示不做唯一匹配）
     */
    private List<TransformInput> buildInputs(Expression expr,
                                              Map<String, String> aliasMap,
                                              String singleSourceTable,
                                              java.util.function.Supplier<java.util.Map<String, java.util.Set<String>>> bareColumnCacheRef) {
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
            } else if (bareColumnCacheRef != null) {
                // 多表 JOIN 裸列: 按列名在候选表中唯一匹配归属（物理表用 DDL 元数据、临时表用 CREATE TEMP 列定义）。
                // 仅唯一命中才归属, 多表都有该列则保守置空（无法确定）。
                String matched = matchFieldToTable(ref.field, bareColumnCacheRef.get());
                if (matched != null) {
                    ti.setSourceTableShort(matched);
                    // 反填 alias 便于溯源（从 aliasMap 反查别名）
                    for (java.util.Map.Entry<String, String> en : aliasMap.entrySet()) {
                        if (matched.equals(en.getValue())) {
                            ti.setSourceAlias(en.getKey());
                            break;
                        }
                    }
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
        return buildInputs(expr, effectiveMap, singleSourceTable, null);
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
            // P5: 分区后缀表（基表+数字后缀/尾下划线, 如 a_d_fin_mkt_prft_loss_dtl_10、
            // a_d_ag_agent_pay_cus_inf_）元数据表通常只登记基表 → 原名查无记录时
            // 剥离分区后缀回退基表列（补录到原名 key, 归属表名保留原名）
            if (columns.isEmpty()) {
                String base = stripPartitionSuffix(tableShort);
                if (base != null && !base.equals(tableShort)) {
                    var baseRecords = tableColumnMetaMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.hb.extractor.entity.TableColumnMeta>()
                            .eq(com.hb.extractor.entity.TableColumnMeta::getTableName, base));
                    for (var r : baseRecords) {
                        if (r.getColumnName() != null) {
                            columns.add(r.getColumnName().toUpperCase());
                        }
                    }
                }
            }
            cache.put(tableShort, columns);
        }
        return cache;
    }

    /**
     * P5: 剥离分区表后缀。支持两种形态：
     * - 尾部数字段（_\d+，可多层, 如 a_xxx_10 / a_xxx_20240818）
     * - 尾部下划线（表名后缀占位符 {ETL_TX_DT} 剥离残留, 如 a_xxx_）
     * 剥离后过短（<3 字符）视为无效返回 null。
     */
    private String stripPartitionSuffix(String tableShort) {
        String s = tableShort;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d+)+$").matcher(s);
        if (m.find()) {
            s = s.substring(0, m.start());
        }
        while (s.endsWith("_")) {
            s = s.substring(0, s.length() - 1);
        }
        return (s.isEmpty() || s.length() < 3) ? null : s;
    }

    /**
     * 构建多表 JOIN 裸列唯一匹配的候选缓存（表短名→列名大写集合）。
     * 物理表列来自 table_column_meta（DB 元数据）；临时表列来自 CREATE TEMP 内联列定义（tempColumnDefs）。
     */
    private java.util.Map<String, java.util.Set<String>> buildBareColumnCache(
            Map<String, String> aliasMap, Map<String, List<String>> tempColumnDefs) {
        java.util.Map<String, java.util.Set<String>> cache = new java.util.HashMap<>();
        if (aliasMap == null || aliasMap.isEmpty()) return cache;
        // 物理表: 查 DB 元数据（临时表在元数据表中无记录, 查出为空集合）
        cache.putAll(buildDdlCache(aliasMap.values()));
        // 临时表: 用 CREATE TEMP 内联列定义补充/覆盖
        if (tempColumnDefs != null) {
            for (java.util.Map.Entry<String, List<String>> en : tempColumnDefs.entrySet()) {
                String tableShort = en.getKey();
                // 仅当该表在本块 FROM/JOIN 中被引用时才参与候选
                if (tableShort != null && aliasMap.containsValue(tableShort)) {
                    java.util.Set<String> columns = cache.computeIfAbsent(tableShort, k -> new java.util.HashSet<>());
                    if (en.getValue() != null) {
                        for (String col : en.getValue()) {
                            if (col != null) columns.add(col.toUpperCase());
                        }
                    }
                }
            }
        }
        // 剔除无列信息的表: 无法参与唯一匹配, 留着会让"其他表都没有该列"误判为唯一命中
        cache.values().removeIf(java.util.Set::isEmpty);
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

    /** 常量来源标记: 分支内纯字面量项的索引归属表名（无上游列, 穿透时丢弃 input） */
    private static final String CONSTANT_TABLE = "__constant__";

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
    private void applySubqueryColumnPassthrough(PlainSelect select, List<FieldMapping> result,
                                                Map<String, List<String>> tempColumnDefs) {
        if (select == null || result == null || result.isEmpty()) return;
        Map<String, Map<String, List<ResolvedRef>>> subqueryIndex = buildSubqueryColumnIndex(select, tempColumnDefs);
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
                } else if (ti.getSourceField() != null) {
                    // P9: 裸字段 + 多子查询 scope: 按列名在所有 scope 中唯一命中才归属（保守穿透）
                    // （如 NVL(ftpexpen*nvl(exchange_to_cny_rate,...)) 中 exchange_to_cny_rate 是
                    //   JOIN 派生表 a1 的聚合输出别名, 块内还有其他子查询 scope 时原逻辑直接放弃）
                    String fieldKey = ti.getSourceField().toLowerCase();
                    Map<String, List<ResolvedRef>> uniqueHit = null;
                    int hitCount = 0;
                    for (Map<String, List<ResolvedRef>> sc : subqueryIndex.values()) {
                        if (sc.containsKey(fieldKey)) {
                            uniqueHit = sc;
                            hitCount++;
                        }
                    }
                    if (hitCount == 1) scopeColumns = uniqueHit;
                }
                List<ResolvedRef> resolved = scopeColumns != null
                        ? scopeColumns.get(ti.getSourceField().toLowerCase()) : null;
                if ((resolved == null || resolved.isEmpty()) && scopeColumns != null) {
                    // 列名未直接命中: SELECT * 通配子查询单表来源 → 按列名穿透（外层 T3.PROM_ORG_NO → 单表.PROM_ORG_NO）
                    List<ResolvedRef> star = scopeColumns.get("__star__");
                    if (star != null && ti.getSourceField() != null) {
                        resolved = new ArrayList<>();
                        for (ResolvedRef rr : star) {
                            resolved.add(new ResolvedRef(rr.sourceTableShort, ti.getSourceField(), rr.sourceAlias));
                        }
                    }
                }
                if (resolved == null || resolved.isEmpty()) {
                    // 查不到: 保持原样
                    addPassthroughInput(dedup, null, ti.getSourceField(),
                            ti.getSourceAlias(), ti.getRole(), ti.getInputOrder());
                    continue;
                }
                // 过滤常量来源（分支内纯字面量项）: 无上游列, 丢弃 input 而非留断链
                java.util.List<ResolvedRef> realResolved = new ArrayList<>(resolved);
                realResolved.removeIf(rr -> CONSTANT_TABLE.equals(rr.sourceTableShort));
                if (realResolved.isEmpty()) {
                    changed = true;
                    continue;
                }
                changed = true;
                for (ResolvedRef rr : realResolved) {
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

    /**
     * P4: 解开 SELECT 的括号形式（AS (SELECT ...) / INSERT (SELECT ...)）,
     * 返回内层 PlainSelect; 非 PlainSelect 形式返回 null。
     */
    private PlainSelect unwrapPlainSelect(Select sel) {
        if (sel == null) return null;
        for (int i = 0; i < 5 && sel != null; i++) {
            Object body = sel.getSelectBody();
            if (body instanceof PlainSelect) return (PlainSelect) body;
            if (body instanceof ParenthesedSelect) {
                sel = ((ParenthesedSelect) body).getSelect();
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * P3: 展开 SELECT 项中的 `A.*`（A 为子查询别名）为子查询具体列项（A.col1, A.col2 ...）。
     *
     * 复现 `select A.* from (...) A` 形态断链：AllTableColumns 是通配表达式,
     * 无列引用可提取 → 每个目标字段无 inputs; 且 AllTableColumns extends AllColumns,
     * 会被 hasWildcard 早退分支吞掉。展开后复用既有列级匹配 + 子查询穿透链路。
     *
     * P8: 同时展开裸 `*`（SELECT * FROM (子查询) t）—— CTAS 常见形态,
     * 临时表列 = 内层子查询输出别名。限制: FROM 必须是单一子查询（无 JOIN）,
     * 否则无法唯一归属, 保持原 hasWildcard 行为。
     *
     * 不展开的情形（保持原行为）：
     * - 前缀别名是物理表（不在子查询索引中）
     * - 子查询列不可枚举（scope 仅含 __star__ 表级入口）
     */
    private void expandSubqueryAliasStarItems(PlainSelect select, Map<String, List<String>> tempColumnDefs) {
        List<SelectItem<?>> items = select.getSelectItems();
        if (items == null) return;
        boolean hasAliasStar = items.stream()
                .anyMatch(i -> i.getExpression() instanceof AllTableColumns);
        // P8: 裸 * 且 FROM 为单一子查询时也展开
        boolean hasBareStar = items.stream()
                .anyMatch(i -> i.getExpression() instanceof AllColumns
                        && !(i.getExpression() instanceof AllTableColumns))
                && select.getFromItem() instanceof ParenthesedSelect
                && (select.getJoins() == null || select.getJoins().isEmpty());
        if (!hasAliasStar && !hasBareStar) return;

        Map<String, Map<String, List<ResolvedRef>>> subqueryIndex =
                buildSubqueryColumnIndex(select, tempColumnDefs);
        if (subqueryIndex.isEmpty()) return;

        // P8: 裸 * 的目标 scope —— 主 FROM 子查询的别名（无别名用 "" 空 scope）
        String bareStarScope = null;
        if (hasBareStar) {
            Alias fromAlias = ((ParenthesedSelect) select.getFromItem()).getAlias();
            bareStarScope = (fromAlias != null && fromAlias.getName() != null)
                    ? fromAlias.getName().toUpperCase() : "";
        }

        List<SelectItem<?>> expanded = new ArrayList<>();
        boolean changed = false;
        for (SelectItem<?> item : items) {
            Expression expr = item.getExpression();
            if (expr instanceof AllTableColumns) {
                Table prefix = ((AllTableColumns) expr).getTable();
                String prefixName = prefix != null ? prefix.getName() : null;
                Map<String, List<ResolvedRef>> scopeColumns = prefixName != null
                        ? subqueryIndex.get(prefixName.toUpperCase()) : null;
                boolean enumerable = scopeColumns != null && scopeColumns.keySet().stream()
                        .anyMatch(k -> !"__star__".equals(k));
                if (!enumerable) {
                    expanded.add(item);
                    continue;
                }
                changed = true;
                // 列名 key 统一小写且保持子查询 SELECT 项顺序（LinkedHashMap 插入序）
                for (String colName : scopeColumns.keySet()) {
                    if ("__star__".equals(colName)) continue;
                    Column col = new Column(new Table(prefixName), colName);
                    expanded.add(new SelectItem<>(col));
                }
            } else if (expr instanceof AllColumns && hasBareStar) {
                // P8: 裸 * → 展开 FROM 子查询输出列（带子查询别名前缀, 便于统一走别名解析链路）
                Map<String, List<ResolvedRef>> scopeColumns = subqueryIndex.get(bareStarScope);
                boolean enumerable = scopeColumns != null && scopeColumns.keySet().stream()
                        .anyMatch(k -> !"__star__".equals(k));
                if (!enumerable) {
                    expanded.add(item);
                    continue;
                }
                String fromAliasName = "".equals(bareStarScope) ? null : bareStarScope;
                changed = true;
                for (String colName : scopeColumns.keySet()) {
                    if ("__star__".equals(colName)) continue;
                    Column col = (fromAliasName != null)
                            ? new Column(new Table(fromAliasName), colName)
                            : new Column(colName);
                    expanded.add(new SelectItem<>(col));
                }
            } else {
                expanded.add(item);
            }
        }
        if (changed) {
            select.setSelectItems(expanded);
        }
    }

    /** 构造子查询列索引: scope别名(大写) → 列名(小写) → 底层表字段解析列表 */
    private Map<String, Map<String, List<ResolvedRef>>> buildSubqueryColumnIndex(PlainSelect select,
                                                                                Map<String, List<String>> tempColumnDefs) {
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
            boolean isMain = (fi == select.getFromItem());
            // 主 FROM 无别名子查询: 用 "" scope 承接外层裸列引用（如 SELECT NVL(org_no,'') FROM (SELECT ...) ）
            // JOIN 右侧无别名子查询无法安全匹配裸列, 仍跳过
            if ((a == null || a.getName() == null) && !isMain) continue;
            String scope = (a != null && a.getName() != null) ? a.getName().toUpperCase() : "";
            Map<String, List<ResolvedRef>> scopeColumns =
                    index.computeIfAbsent(scope, k -> new LinkedHashMap<>());
            collectBranchColumns(((ParenthesedSelect) fi).getSelect(), scopeColumns, 0, tempColumnDefs);
        }
        return index;
    }

    /** 遍历子查询内部各分支（PlainSelect / SetOperationList / 嵌套括号，深度 ≤5）建立列索引 */
    private void collectBranchColumns(Select sel, Map<String, List<ResolvedRef>> scopeColumns, int depth,
                                      Map<String, List<String>> tempColumnDefs) {
        if (sel == null || depth > 5) return;
        if (sel instanceof PlainSelect) {
            indexBranchColumns((PlainSelect) sel, scopeColumns, 0, tempColumnDefs);
        } else if (sel instanceof SetOperationList) {
            for (Select s : ((SetOperationList) sel).getSelects()) {
                collectBranchColumns(s, scopeColumns, depth + 1, tempColumnDefs);
            }
        } else if (sel instanceof ParenthesedSelect) {
            collectBranchColumns(((ParenthesedSelect) sel).getSelect(), scopeColumns, depth + 1, tempColumnDefs);
        }
    }

    /** 单个分支: 遍历 SELECT 项，列名（AS 别名优先，其次裸列名）→ 项内字段引用解析到底层表 */
    private void indexBranchColumns(PlainSelect branch, Map<String, List<ResolvedRef>> scopeColumns,
                                    Map<String, List<String>> tempColumnDefs) {
        indexBranchColumns(branch, scopeColumns, 0, tempColumnDefs);
    }

    /**
     * 带深度的分支索引。depth 递增用于嵌套子查询二级穿透：
     * 分支 SELECT 引用子查询别名（如 t.fivclaid 中 t 是 FROM (子查询B) t）时，
     * 递归索引子查询B 的列，把 t.fivclaid 解析为子查询B 内 FIVCLAID 列的底层表字段。
     */
    private void indexBranchColumns(PlainSelect branch, Map<String, List<ResolvedRef>> scopeColumns, int depth,
                                    Map<String, List<String>> tempColumnDefs) {
        Map<String, String> branchAliasMap = analyzer.extractAliasMap(branch);

        // 嵌套子查询列索引: 子查询别名(大写) → 列名 → 底层表字段（二级穿透用）
        // "" key 用于主 FROM 子查询的裸列引用: SQL 允许不带子查询别名前缀裸引用主 FROM 子查询的列
        // （如 SELECT org_no FROM ( SELECT T01.org_no ... ) A——主 FROM 有别名 A 但引用是裸列）, 因此主 FROM 子查询
        // 无论有无别名都建 "" 入口; JOIN 右侧仅有别名子查询（无别名不建, 避免误归属）
        Map<String, Map<String, List<ResolvedRef>>> nestedIndex = new LinkedHashMap<>();
        if (depth < 5) {
            FromItem mainFrom = branch.getFromItem();
            if (mainFrom instanceof ParenthesedSelect) {
                Map<String, List<ResolvedRef>> nestedCols = new LinkedHashMap<>();
                collectBranchColumns(((ParenthesedSelect) mainFrom).getSelect(), nestedCols, depth + 1, tempColumnDefs);
                nestedIndex.put("", nestedCols);
                Alias mainAlias = mainFrom.getAlias();
                if (mainAlias != null && mainAlias.getName() != null) {
                    nestedIndex.put(mainAlias.getName().toUpperCase(), nestedCols);
                }
            }
            if (branch.getJoins() != null) {
                for (Join join : branch.getJoins()) {
                    FromItem right = join.getRightItem();
                    if (!(right instanceof ParenthesedSelect)) continue;
                    Alias a = right.getAlias();
                    if (a == null || a.getName() == null) continue;  // JOIN 右侧无别名不建裸列入口
                    Map<String, List<ResolvedRef>> nestedCols = new LinkedHashMap<>();
                    collectBranchColumns(((ParenthesedSelect) right).getSelect(), nestedCols, depth + 1, tempColumnDefs);
                    nestedIndex.put(a.getName().toUpperCase(), nestedCols);
                }
            }
        }

        // 分支裸列 DDL 唯一匹配缓存（惰性构建）: 多表分支裸列归属失败时按列名唯一匹配
        // （物理表元数据 + 临时表 CREATE TEMP 列定义），仅唯一命中时归属
        java.util.Map<String, java.util.Set<String>> branchBareCache = null;

        for (SelectItem<?> item : branch.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr == null) continue;
            // 别名通配 a.*: 按前缀别名解析来源表（多表 JOIN 也可）, 记 __star__ 入口（表级）
            if (expr instanceof AllTableColumns) {
                Table prefix = ((AllTableColumns) expr).getTable();
                String prefixName = prefix != null ? prefix.getName() : null;
                if (prefixName != null) {
                    String prefixKey = prefixName.toUpperCase();
                    String table = branchAliasMap.get(prefixKey);
                    if (table != null) {
                        scopeColumns.computeIfAbsent("__star__", k -> new ArrayList<>())
                                .add(new ResolvedRef(table, "*", null));
                    } else {
                        // P3: A.* 前缀是子查询别名 → 冒泡内层已索引列（含 __star__）到当前 scope,
                        // 使更外层引用（如 B.col）穿透时能命中具体列（复现中层 `select A.* from (...) A` 断链）
                        Map<String, List<ResolvedRef>> scopeIdx = nestedIndex.get(prefixKey);
                        if (scopeIdx != null) {
                            for (java.util.Map.Entry<String, List<ResolvedRef>> en : scopeIdx.entrySet()) {
                                List<ResolvedRef> target =
                                        scopeColumns.computeIfAbsent(en.getKey(), k -> new ArrayList<>());
                                for (ResolvedRef rr : en.getValue()) {
                                    addResolvedRefIfAbsent(target, rr);
                                }
                            }
                        }
                    }
                }
                continue;
            }
            // 裸 SELECT *: 三种形态
            if (expr instanceof AllColumns) {
                FromItem mainFrom = branch.getFromItem();
                if (mainFrom instanceof ParenthesedSelect && nestedIndex.containsKey("")) {
                    // 主 FROM 是子查询: 整体冒泡内层已索引列（含 __star__）到当前 scope
                    for (java.util.Map.Entry<String, List<ResolvedRef>> en : nestedIndex.get("").entrySet()) {
                        List<ResolvedRef> target = scopeColumns.computeIfAbsent(en.getKey(), k -> new ArrayList<>());
                        for (ResolvedRef rr : en.getValue()) {
                            addResolvedRefIfAbsent(target, rr);
                        }
                    }
                } else if (branchAliasMap.size() == 1) {
                    // 单表来源: 记 __star__（原行为）
                    String only = branchAliasMap.values().iterator().next();
                    if (only != null) {
                        scopeColumns.computeIfAbsent("__star__", k -> new ArrayList<>())
                                .add(new ResolvedRef(only, "*", null));
                    }
                }
                // 多物理表 + 裸 *: 列归属歧义, 保守跳过（防误归属）
                continue;
            }
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
            if (refs.isEmpty()) {
                // 纯常量项（字面量/日期/无列引用函数, 如 '0098' AS BRCH_NO / 0 AS TERM_END_BAL）:
                // 登记 __constant__ 标记, 外层穿透命中常量来源时丢弃 input（无上游列, 不构成断链）
                scopeColumns.computeIfAbsent(colName, k -> new ArrayList<>())
                        .add(new ResolvedRef(CONSTANT_TABLE, colName, null));
                continue;
            }
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

                List<ResolvedRef> list = scopeColumns.computeIfAbsent(colName, k -> new ArrayList<>());
                if (table != null) {
                    addResolvedRefIfAbsent(list, new ResolvedRef(table, ref.field, ref.alias));
                    continue;
                }
                // table == null 且带别名: 别名是子查询 → 二级穿透, 解析为子查询列的底层引用
                if (ref.alias != null && nestedIndex.containsKey(ref.alias)) {
                    Map<String, List<ResolvedRef>> scopeIdx = nestedIndex.get(ref.alias);
                    // 列名 key 统一小写: 引用侧(PROM_ORG_NO)与索引侧(prom_org_no)大小写对齐
                    List<ResolvedRef> inner = ref.field != null
                            ? scopeIdx.get(ref.field.toLowerCase()) : null;
                    if (inner == null) {
                        // 列名未直接命中: SELECT * 通配子查询 → 按列名穿透单表来源（如表为 VT/PID 表）
                        List<ResolvedRef> star = scopeIdx.get("__star__");
                        if (star != null) {
                            inner = new ArrayList<>();
                            for (ResolvedRef rr : star) {
                                inner.add(new ResolvedRef(rr.sourceTableShort, ref.field, ref.alias));
                            }
                        }
                    }
                    if (inner != null) {
                        for (ResolvedRef rr : inner) {
                            addResolvedRefIfAbsent(list, rr);
                        }
                    }
                }
                // table == null 且裸列: 主 FROM 无别名子查询 → 按列名穿透子查询列的底层引用
                else if (ref.alias == null && nestedIndex.containsKey("")) {
                    List<ResolvedRef> inner = nestedIndex.get("").get(ref.field.toLowerCase());
                    if (inner == null) {
                        // 列名未直接命中: 内层 a.* / 单表 * → 按列名穿透 __star__ 表级入口
                        List<ResolvedRef> star = nestedIndex.get("").get("__star__");
                        if (star != null && ref.field != null) {
                            inner = new ArrayList<>();
                            for (ResolvedRef rr : star) {
                                inner.add(new ResolvedRef(rr.sourceTableShort, ref.field, rr.sourceAlias));
                            }
                        }
                    }
                    if (inner != null) {
                        for (ResolvedRef rr : inner) {
                            addResolvedRefIfAbsent(list, rr);
                        }
                    }
                }
                // P10: 裸列未命中以上入口时, 在 JOIN 侧子查询 scope 中按列名唯一命中归属
                // （复现 new_pord_sum: 内层 sum(int_income) 裸列实为 JOIN 子查询 c 的聚合输出列,
                //   别名/单表/主FROM入口均不适用, 按列名在所有子查询 scope 中唯一命中时采纳其底层引用）
                if (table == null && ref.alias == null
                        && list.stream().noneMatch(r -> r.sourceTableShort != null
                                && !CONSTANT_TABLE.equals(r.sourceTableShort))) {
                    List<ResolvedRef> uniqueHit = null;
                    int hitScopes = 0;
                    for (java.util.Map.Entry<String, Map<String, List<ResolvedRef>>> en : nestedIndex.entrySet()) {
                        List<ResolvedRef> inner = en.getValue().get(ref.field.toLowerCase());
                        if (inner != null && !inner.isEmpty()) {
                            hitScopes++;
                            uniqueHit = inner;
                        }
                    }
                    if (hitScopes == 1 && uniqueHit != null) {
                        for (ResolvedRef rr : uniqueHit) {
                            addResolvedRefIfAbsent(list, rr);
                        }
                    }
                }
                // table == null 且裸列且多表分支: DDL 唯一匹配兜底
                // （如 sum(loan_bal) 中 loan_bal 裸列, 分支 FROM 主表+JOIN 子查询 → aliasMap>1 无单表兜底;
                //   按列名在候选表（物理表元数据+临时表列定义）中唯一命中时归属）
                if (table == null && ref.alias == null && branchAliasMap.size() > 1
                        && list.stream().noneMatch(r -> r.sourceTableShort != null
                                && !CONSTANT_TABLE.equals(r.sourceTableShort))) {
                    if (branchBareCache == null) {
                        branchBareCache = buildBareColumnCache(branchAliasMap, tempColumnDefs);
                    }
                    String matched = matchFieldToTable(ref.field, branchBareCache);
                    if (matched != null) {
                        addResolvedRefIfAbsent(list, new ResolvedRef(matched, ref.field, null));
                    }
                }
            }
        }
    }

    /** 收集分支的 FROM 项（主表 + JOIN 右侧） */
    private List<FromItem> collectBranchFromItems(PlainSelect branch) {
        List<FromItem> items = new ArrayList<>();
        if (branch.getFromItem() != null) items.add(branch.getFromItem());
        if (branch.getJoins() != null) {
            for (Join join : branch.getJoins()) {
                if (join.getRightItem() != null) items.add(join.getRightItem());
            }
        }
        return items;
    }

    /** 去重追加 ResolvedRef（table+field+alias 相同则跳过） */
    private void addResolvedRefIfAbsent(List<ResolvedRef> list, ResolvedRef ref) {
        for (ResolvedRef r : list) {
            if (r.sourceTableShort.equals(ref.sourceTableShort) && r.sourceField.equals(ref.sourceField)
                    && java.util.Objects.equals(r.sourceAlias, ref.sourceAlias)) {
                return;
            }
        }
        list.add(ref);
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
