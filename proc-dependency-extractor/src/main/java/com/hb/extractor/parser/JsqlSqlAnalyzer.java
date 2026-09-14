package com.hb.extractor.parser;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.merge.MergeInsert;
import net.sf.jsqlparser.statement.merge.MergeUpdate;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JsqlParser AST 分析器
 *
 * 封装 JsqlParser 的 AST 访问逻辑，提供统一的结构化数据提取接口：
 * - 表引用（schema/table/alias，递归子查询）
 * - SELECT 列（表达式 + alias + alias.field）
 * - JOIN ON / WHERE 条件（按 AND/OR 拆分）
 * - 别名→表映射
 * - logicType 分类（保留"顶层运算符优先于内层聚合"语义）
 * - 表达式中字段引用（CASE WHEN 区分 value/condition）
 *
 * 三个解析器（SqlTableExtractor / LineageExtractor / FieldMappingExtractor）
 * 调用本类获取结构化数据，再构造各自 DTO。
 */
@Slf4j
@Component
public class JsqlSqlAnalyzer {

    @Autowired
    private SqlPlaceholderReplacer placeholderReplacer;

    /** 临时表前缀 */
    public static final String TEMP_PREFIX = "VT_";
    public static final String TMP_PREFIX = "TMP_";
    public static final String TMP_SUFFIX = "_TMP";

    /** ==================== 数据载体类 ==================== **/

    /** 表引用信息（FROM/JOIN 后的表或子查询） */
    public static class TableRefInfo {
        public String schemaName;    // schema 变量名（CDM）或 null
        public String tableName;     // 表名（小写）
        public String alias;        // 别名（大写，无别名则=null）
        public boolean isSubquery;   // 是否子查询
        public String fullTableName; // schema.table（schema 为 null 时=table）
    }

    /** SELECT 列信息 */
    public static class SelectItemInfo {
        public Expression expression;  // 原始 AST 表达式
        public String alias;          // AS alias（大写，无则=null）
        public String exprText;       // 表达式原文
        public int order;            // 列序号（从 1 开始）
    }

    /** 条件信息（JOIN ON / WHERE 子句） */
    public static class FilterInfo {
        public String filterType;      // JOIN / WHERE
        public String clause;          // 子句原文
        public List<ColumnRef> fields; // 子句中的字段引用
        /**
         * 条件所在作用域的局部别名映射（alias→tableShort，子查询别名为 null 占位）。
         * 内层子查询条件用内层映射解析、外层条件用外层映射——作用域封闭，防止跨层错误归属。
         * 顶层条件经 extractJoinFilters/extractWhereFilters 生成时为 null（调用方用全局 aliasMap 兜底）。
         */
        public Map<String, String> scopeAliasMap;
    }

    /** 字段引用 */
    public static class ColumnRef {
        public String alias;   // 别名（大写）
        public String field;   // 字段名（小写）
        public String role;    // value / condition
    }

    /** ==================== 解析入口 ==================== **/

    /**
     * 预处理 + JsqlParser 解析 SQL 文本
     * @return Statement 对象；解析失败返回 null（调用方应跳过该块）
     */
    public Statement parse(String sql) {
        if (sql == null || sql.trim().isEmpty()) return null;
        String processed = placeholderReplacer.preprocess(sql);
        try {
            return CCJSqlParserUtil.parse(processed);
        } catch (Exception e) {
            log.warn("JsqlParser 解析失败（块将跳过）: {} | preprocess 后 SQL 预览(前200字符): {}", e.getMessage(),
                    processed.length() > 200 ? processed.substring(0, 200) + "..." : processed);
            return null;
        }
    }

    /**
     * 解析判定结果：statement 与 failureReason 二选一非空（成功时 failureReason 为 null）。
     */
    public static class ParseOutcome {
        public final Statement statement;
        public final String failureReason;
        public ParseOutcome(Statement statement, String failureReason) {
            this.statement = statement;
            this.failureReason = failureReason;
        }
    }

    /**
     * 预处理 + 解析，失败时携带具体原因，供失败清单落库。
     */
    public ParseOutcome parseVerbose(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return new ParseOutcome(null, "SQL 块为空");
        }
        String processed = placeholderReplacer.preprocess(sql);
        try {
            return new ParseOutcome(CCJSqlParserUtil.parse(processed), null);
        } catch (Exception e) {
            log.warn("JsqlParser 解析失败（块将跳过）: {} | preprocess 后 SQL 预览(前200字符): {}", e.getMessage(),
                    processed.length() > 200 ? processed.substring(0, 200) + "..." : processed);
            return new ParseOutcome(null, e.getMessage());
        }
    }

    /** ==================== 表引用提取 ==================== **/

    /**
     * 从 PlainSelect 递归提取所有表引用（FROM + JOINs，含子查询内部表）。
     * 子查询递归最大深度 5。
     */
    public List<TableRefInfo> extractTablesRecursively(PlainSelect select, int depth) {
        List<TableRefInfo> result = new ArrayList<>();
        if (select == null || depth > 5) return result;
        collectFromItem(select.getFromItem(), result, depth);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectFromItem(join.getRightItem(), result, depth);
            }
        }
        return result;
    }

    private void collectFromItem(FromItem fromItem, List<TableRefInfo> result, int depth) {
        if (fromItem == null) return;
        if (fromItem instanceof Table) {
            TableRefInfo info = tableRefInfo((Table) fromItem);
            result.add(info);
        } else if (fromItem instanceof ParenthesedSelect) {
            // 子查询：递归收集内部所有分支的表（支持 PlainSelect / SetOperationList(UNION) / 嵌套括号）
            Select innerSelect = ((ParenthesedSelect) fromItem).getSelect();
            collectSelectTables(innerSelect, result, depth + 1);
            // 子查询 alias 也记录（用于子查询内字段归属）
            TableRefInfo subAliasInfo = new TableRefInfo();
            subAliasInfo.isSubquery = true;
            Alias a = fromItem.getAlias();
            subAliasInfo.alias = a != null ? a.getName().toUpperCase() : null;
            result.add(subAliasInfo);
        }
    }

    /**
     * 递归收集 Select（PlainSelect / SetOperationList / ParenthesedSelect）内部所有分支的表引用。
     * UNION ALL 子查询（SetOperationList）的每个分支都提取，深度沿用 ≤5。
     */
    private void collectSelectTables(Select sel, List<TableRefInfo> result, int depth) {
        if (sel == null || depth > 5) return;
        if (sel instanceof PlainSelect) {
            result.addAll(extractTablesRecursively((PlainSelect) sel, depth));
        } else if (sel instanceof SetOperationList) {
            for (Select s : ((SetOperationList) sel).getSelects()) {
                collectSelectTables(s, result, depth + 1);
            }
        } else if (sel instanceof ParenthesedSelect) {
            collectSelectTables(((ParenthesedSelect) sel).getSelect(), result, depth + 1);
        }
    }

    private TableRefInfo tableRefInfo(Table table) {
        TableRefInfo info = new TableRefInfo();
        info.schemaName = table.getSchemaName();  // 已经过预处理，{CDM} → CDM
        info.tableName = table.getName() != null ? table.getName().toLowerCase() : null;
        if (info.schemaName != null) {
            info.fullTableName = info.schemaName + "." + info.tableName;
        } else {
            info.fullTableName = info.tableName;
        }
        Alias a = table.getAlias();
        info.alias = a != null ? a.getName().toUpperCase() : null;
        info.isSubquery = false;
        return info;
    }

    /** ==================== 别名映射 ==================== **/

    /**
     * 提取 alias → tableShort 映射（key 大写）
     */
    public Map<String, String> extractAliasMap(PlainSelect select) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        if (select == null) return aliasMap;
        collectAlias(select.getFromItem(), aliasMap);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectAlias(join.getRightItem(), aliasMap);
            }
        }
        return aliasMap;
    }

    private void collectAlias(FromItem fromItem, Map<String, String> aliasMap) {
        if (fromItem == null) return;
        Alias a = fromItem.getAlias();
        String alias = (a != null && a.getName() != null) ? a.getName().toUpperCase() : null;
        if (fromItem instanceof Table) {
            Table t = (Table) fromItem;
            if (t.getName() != null) {
                String tableShort = t.getName().toLowerCase();
                if (alias != null) {
                    aliasMap.put(alias, tableShort);
                } else {
                    // 无显式别名: 表名自身即隐式引用前缀（FROM t1 ≡ FROM t1 t1，SQL 标准语义）
                    aliasMap.put(tableShort.toUpperCase(), tableShort);
                }
            }
        } else if (fromItem instanceof ParenthesedSelect && alias != null) {
            // 子查询 alias 暂不映射到表（无法确定具体表），保留占位避免字段归属到错误表
            aliasMap.put(alias, null);
        }
    }

    /** ==================== ON/WHERE 条件字段提取 ==================== **/

    /**
     * 从 PlainSelect 提取 ON/WHERE 条件中的字段引用。
     * 复用 collectColumnRefs，role 设为 join_condition / where_condition。
     * @param select PlainSelect（INSERT SELECT / CREATE TEMP AS SELECT 的 SELECT 子句）
     * @return 条件字段列表（alias 大写, field 小写, role=join_condition/where_condition）
     */
    public List<ColumnRef> extractConditionFields(PlainSelect select) {
        List<ColumnRef> result = new ArrayList<>();
        if (select == null) return result;
        // JOIN ON 条件
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression onExpr : join.getOnExpressions()) {
                        collectColumnRefs(onExpr, "join_condition", result);
                    }
                } else if (join.getOnExpression() != null) {
                    collectColumnRefs(join.getOnExpression(), "join_condition", result);
                }
            }
        }
        // WHERE 条件
        if (select.getWhere() != null) {
            collectColumnRefs(select.getWhere(), "where_condition", result);
        }
        return result;
    }

    /** ==================== SELECT 列提取 ==================== **/

    /**
     * 提取 PlainSelect 的 SELECT 列（不含 SELECT * 通配符）
     */
    public List<SelectItemInfo> extractSelectItems(PlainSelect select) {
        List<SelectItemInfo> result = new ArrayList<>();
        if (select == null || select.getSelectItems() == null) return result;
        int order = 1;
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression expr = item.getExpression();
            // 4.9: AllColumns（SELECT *）是 Expression，由 SelectItem.getExpression() 返回
            if (expr instanceof AllColumns) {
                // SELECT * 跳过（无法对应具体字段）
                order++;
                continue;
            }
            if (expr == null) {
                order++;
                continue;
            }
            SelectItemInfo info = new SelectItemInfo();
            info.expression = expr;
            info.exprText = expr.toString();
            info.order = order;
            Alias a = item.getAlias();
            info.alias = a != null ? a.getName().toUpperCase() : null;
            result.add(info);
            order++;
        }
        return result;
    }

    /** ==================== JOIN/WHERE 条件提取 ==================== **/

    /**
     * 单一递归入口：提取顶层及所有派生表子查询内部的 JOIN/WHERE 条件。
     * 每个作用域各自绑定局部别名映射（scopeAliasMap），深度 ≤5（与 extractTablesRecursively 一致）。
     * <p>注意：不能复用 extractJoinFilters/extractWhereFilters 各自递归——两者被调用方成对调用，
     * 各自递归会让子查询条件被收集两次。故统一走本入口一次遍历。</p>
     */
    public List<FilterInfo> extractFiltersRecursively(PlainSelect select) {
        List<FilterInfo> result = new ArrayList<>();
        collectScopeFilters(select, result, 0);
        return result;
    }

    /** 收集单个 PlainSelect 作用域的条件，并下钻其 FROM/JOIN 中的子查询（深度 ≤5） */
    private void collectScopeFilters(PlainSelect select, List<FilterInfo> result, int depth) {
        if (select == null || depth > 5) return;
        // 本作用域局部别名映射（非递归，复用 extractAliasMap；子查询别名映射为 null 占位）
        Map<String, String> localAlias = extractAliasMap(select);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        collectFilterClauses(on, "JOIN", result, localAlias);
                    }
                } else if (join.getOnExpression() != null) {
                    // JsqlParser 4.6 兼容旧 API
                    collectFilterClauses(join.getOnExpression(), "JOIN", result, localAlias);
                }
            }
        }
        if (select.getWhere() != null) {
            collectFilterClauses(select.getWhere(), "WHERE", result, localAlias);
        }
        // 下钻：FROM 主项 + 每个 JOIN 右侧的子查询
        collectSubqueryScopeFilters(select.getFromItem(), result, depth);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectSubqueryScopeFilters(join.getRightItem(), result, depth);
            }
        }
    }

    /** FROM item 为派生表子查询时递归 */
    private void collectSubqueryScopeFilters(FromItem item, List<FilterInfo> result, int depth) {
        if (!(item instanceof ParenthesedSelect)) return;
        Select inner = ((ParenthesedSelect) item).getSelect();
        collectSelectScopeFilters(inner, result, depth + 1);
    }

    /** 递归收集 Select（PlainSelect / SetOperationList(UNION) / 嵌套括号）内部各作用域条件，深度 ≤5 */
    private void collectSelectScopeFilters(Select sel, List<FilterInfo> result, int depth) {
        if (sel == null || depth > 5) return;
        if (sel instanceof PlainSelect) {
            collectScopeFilters((PlainSelect) sel, result, depth);
        } else if (sel instanceof SetOperationList) {
            for (Select s : ((SetOperationList) sel).getSelects()) {
                collectSelectScopeFilters(s, result, depth + 1);
            }
        } else if (sel instanceof ParenthesedSelect) {
            collectSelectScopeFilters(((ParenthesedSelect) sel).getSelect(), result, depth + 1);
        }
    }

    /**
     * 提取 JOIN ON 条件列表（仅顶层，含下钻能力以外的兼容保留）
     */
    public List<FilterInfo> extractJoinFilters(PlainSelect select) {
        List<FilterInfo> result = new ArrayList<>();
        if (select == null || select.getJoins() == null) return result;
        for (Join join : select.getJoins()) {
            if (join.getOnExpressions() != null) {
                for (Expression on : join.getOnExpressions()) {
                    collectFilterClauses(on, "JOIN", result, null);
                }
            } else if (join.getOnExpression() != null) {
                // JsqlParser 4.6 兼容旧 API
                collectFilterClauses(join.getOnExpression(), "JOIN", result, null);
            }
        }
        return result;
    }

    /**
     * 提取 WHERE 条件列表（按 AND/OR 拆分，仅顶层）
     */
    public List<FilterInfo> extractWhereFilters(PlainSelect select) {
        if (select == null) return new ArrayList<>();
        Expression where = select.getWhere();
        if (where == null) return new ArrayList<>();
        List<FilterInfo> result = new ArrayList<>();
        collectFilterClauses(where, "WHERE", result, null);
        return result;
    }

    /**
     * 递归拆分 AND/OR 表达式为子句列表；scopeAliasMap 绑定条件所在作用域（顶层调用传 null）
     */
    private void collectFilterClauses(Expression expr, String filterType, List<FilterInfo> result,
                                      Map<String, String> scopeAliasMap) {
        if (expr instanceof AndExpression) {
            AndExpression and = (AndExpression) expr;
            collectFilterClauses(and.getLeftExpression(), filterType, result, scopeAliasMap);
            collectFilterClauses(and.getRightExpression(), filterType, result, scopeAliasMap);
        } else if (expr instanceof OrExpression) {
            OrExpression or = (OrExpression) expr;
            collectFilterClauses(or.getLeftExpression(), filterType, result, scopeAliasMap);
            collectFilterClauses(or.getRightExpression(), filterType, result, scopeAliasMap);
        } else {
            // 叶子条件
            FilterInfo info = new FilterInfo();
            info.filterType = filterType;
            info.clause = expr.toString();
            info.fields = new ArrayList<>();
            info.scopeAliasMap = scopeAliasMap;
            collectColumnRefs(expr, "condition", info.fields);
            result.add(info);
        }
    }

    /** ==================== logicType 分类 ==================== **/

    /**
     * 表达式 logicType 分类（保留"顶层运算符优先于内层聚合"语义）。
     * 优先级：LITERAL > CASE > 算术运算 > 聚合 > NVL/COALESCE > CONCAT > FUNCTION_OTHER > DIRECT
     */
    public String classifyLogicType(Expression expr) {
        if (expr == null) return "DIRECT";

        // 1. LITERAL: 字符串字面量（含占位符还原后）、数字、日期字面量
        if (expr instanceof StringValue) {
            StringValue sv = (StringValue) expr;
            if (placeholderReplacer.isPlaceholderLiteral(sv.getValue())) {
                return "LITERAL";  // {I_MB_DT} 等占位符
            }
            return "LITERAL";
        }
        if (expr instanceof net.sf.jsqlparser.expression.LongValue
                || expr instanceof net.sf.jsqlparser.expression.DoubleValue
                || expr instanceof net.sf.jsqlparser.expression.DateValue
                || expr instanceof net.sf.jsqlparser.expression.TimestampValue
                || expr instanceof net.sf.jsqlparser.expression.DateTimeLiteralExpression) {
            // 4.9: DATE 'xxx' / TIME 'xxx' / TIMESTAMP 'xxx' 解析为 DateTimeLiteralExpression
            return "LITERAL";
        }

        // 2. CASE
        if (expr instanceof CaseExpression) return "CASE";

        // 3. 算术运算（顶层）— 优先于内层聚合
        if (expr instanceof Addition) return "ADDITION";
        if (expr instanceof Subtraction) return "SUBTRACTION";
        if (expr instanceof Multiplication) return "MULTIPLICATION";
        if (expr instanceof Division) return "DIVISION";

        // 4. 聚合函数
        if (expr instanceof Function) {
            Function f = (Function) expr;
            String name = f.getName() == null ? "" : f.getName().toUpperCase();
            if (name.equals("SUM") || name.equals("COUNT") || name.equals("AVG")
                    || name.equals("MAX") || name.equals("MIN")) {
                return name;  // SUM/COUNT/AVG/MAX/MIN
            }
            if (name.equals("NVL") || name.equals("COALESCE")) {
                return name.equals("NVL") ? "NVL" : "COALESCE";
            }
            if (name.equals("CONCAT")) {
                return "CONCAT";
            }
            return "FUNCTION_OTHER";
        }

        // 5. CONCAT 运算符 ||
        if (expr instanceof Concat) return "CONCAT";

        // 6. 默认 DIRECT
        return "DIRECT";
    }

    /** ==================== 表达式字段提取 ==================== **/

    /**
     * 从表达式递归提取所有字段引用（Column 对象）
     * @param role value/condition
     */
    public void collectColumnRefs(Expression expr, String role, List<ColumnRef> result) {
        if (expr == null) return;

        if (expr instanceof Column) {
            Column col = (Column) expr;
            ColumnRef ref = new ColumnRef();
            if (col.getTable() != null && col.getTable().getName() != null) {
                ref.alias = col.getTable().getName().toUpperCase();
            }
            ref.field = col.getColumnName() != null ? col.getColumnName().toLowerCase() : null;
            ref.role = role;
            result.add(ref);
            return;
        }

        if (expr instanceof CaseExpression) {
            CaseExpression caseExpr = (CaseExpression) expr;
            // WHEN 条件字段 → condition
            if (caseExpr.getWhenClauses() != null) {
                for (WhenClause wc : caseExpr.getWhenClauses()) {
                    collectColumnRefs(wc.getWhenExpression(), "condition", result);
                    collectColumnRefs(wc.getThenExpression(), "value", result);
                }
            }
            // ELSE 字段 → value
            if (caseExpr.getElseExpression() != null) {
                collectColumnRefs(caseExpr.getElseExpression(), "value", result);
            }
            return;
        }

        if (expr instanceof Function) {
            Function f = (Function) expr;
            if (f.getParameters() != null && f.getParameters().getExpressions() != null) {
                for (Expression arg : f.getParameters().getExpressions()) {
                    collectColumnRefs(arg, role, result);
                }
            }
            return;
        }

        // MySQL GROUP_CONCAT: 4.9 中解析为 MySQLGroupConcat（不继承 Function），需单独遍历其参数
        if (expr instanceof net.sf.jsqlparser.expression.MySQLGroupConcat) {
            net.sf.jsqlparser.expression.MySQLGroupConcat gc =
                    (net.sf.jsqlparser.expression.MySQLGroupConcat) expr;
            if (gc.getExpressionList() != null && gc.getExpressionList().getExpressions() != null) {
                for (Expression arg : gc.getExpressionList().getExpressions()) {
                    collectColumnRefs(arg, role, result);
                }
            }
            return;
        }

        // 二元运算（算术、比较、AND/OR）
        if (expr instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
            AndExpression and = (AndExpression) expr;
            collectColumnRefs(and.getLeftExpression(), role, result);
            collectColumnRefs(and.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof OrExpression) {
            OrExpression or = (OrExpression) expr;
            collectColumnRefs(or.getLeftExpression(), role, result);
            collectColumnRefs(or.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof ComparisonOperator) {
            ComparisonOperator cmp = (ComparisonOperator) expr;
            collectColumnRefs(cmp.getLeftExpression(), role, result);
            collectColumnRefs(cmp.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof InExpression) {
            // IN (...): 左侧字段 + 右侧列表（右侧通常为字面量, 保险起见同样遍历）
            // 缺失时 CASE WHEN x IN (...) 的条件字段会被静默丢弃, 导致子查询穿透索引缺列
            InExpression in = (InExpression) expr;
            collectColumnRefs(in.getLeftExpression(), role, result);
            Expression right = in.getRightExpression();
            if (right instanceof ExpressionList) {
                for (Expression e : ((ExpressionList<?>) right)) {
                    collectColumnRefs(e, role, result);
                }
            } else {
                collectColumnRefs(right, role, result);
            }
            return;
        }
        if (expr instanceof Addition) {
            Addition add = (Addition) expr;
            collectColumnRefs(add.getLeftExpression(), role, result);
            collectColumnRefs(add.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof Subtraction) {
            Subtraction sub = (Subtraction) expr;
            collectColumnRefs(sub.getLeftExpression(), role, result);
            collectColumnRefs(sub.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof Multiplication) {
            Multiplication mul = (Multiplication) expr;
            collectColumnRefs(mul.getLeftExpression(), role, result);
            collectColumnRefs(mul.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof Division) {
            Division div = (Division) expr;
            collectColumnRefs(div.getLeftExpression(), role, result);
            collectColumnRefs(div.getRightExpression(), role, result);
            return;
        }
        if (expr instanceof Concat) {
            Concat c = (Concat) expr;
            collectColumnRefs(c.getLeftExpression(), role, result);
            collectColumnRefs(c.getRightExpression(), role, result);
            return;
        }

        // Parenthesis
        if (expr instanceof net.sf.jsqlparser.expression.Parenthesis) {
            collectColumnRefs(((net.sf.jsqlparser.expression.Parenthesis) expr).getExpression(), role, result);
            return;
        }
    }

    /** ==================== 临时表判定 ==================== **/

    /**
     * 临时表命名规则判定：VT_ / TMP_ 前缀，_TMP 后缀
     */
    public static boolean isTempTableName(String tableName) {
        if (tableName == null) return false;
        String upper = tableName.toUpperCase();
        return upper.startsWith(TEMP_PREFIX)
                || upper.startsWith(TMP_PREFIX)
                || upper.endsWith(TMP_SUFFIX);
    }
}
