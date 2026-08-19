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
            // 子查询：递归提取内部第一个 PlainSelect 的表
            PlainSelect inner = extractInnerSelect(fromItem);
            if (inner != null) {
                List<TableRefInfo> innerTables = extractTablesRecursively(inner, depth + 1);
                // 子查询本身可能也有 alias，但内部表更重要
                result.addAll(innerTables);
                // 子查询 alias 也记录（用于子查询内字段归属）
                TableRefInfo subAliasInfo = new TableRefInfo();
                subAliasInfo.isSubquery = true;
                Alias a = fromItem.getAlias();
                subAliasInfo.alias = a != null ? a.getName().toUpperCase() : null;
                result.add(subAliasInfo);
            }
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

    private PlainSelect extractInnerSelect(FromItem fromItem) {
        Select innerSelect = null;
        if (fromItem instanceof ParenthesedSelect) {
            // 4.9: SubSelect 改名 ParenthesedSelect，getSelectBody() 改为 getSelect()
            innerSelect = ((ParenthesedSelect) fromItem).getSelect();
        }
        if (innerSelect instanceof PlainSelect) return (PlainSelect) innerSelect;
        return null;
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
        if (a != null && a.getName() != null) {
            String alias = a.getName().toUpperCase();
            if (fromItem instanceof Table) {
                Table t = (Table) fromItem;
                if (t.getName() != null) {
                    aliasMap.put(alias, t.getName().toLowerCase());
                }
            } else if (fromItem instanceof ParenthesedSelect) {
                // 子查询 alias 暂不映射到表（无法确定具体表）
                // 但保留 alias 占位，避免字段归属到错误表
                aliasMap.put(alias, null);
            }
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
     * 提取 JOIN ON 条件列表
     */
    public List<FilterInfo> extractJoinFilters(PlainSelect select) {
        List<FilterInfo> result = new ArrayList<>();
        if (select == null || select.getJoins() == null) return result;
        for (Join join : select.getJoins()) {
            if (join.getOnExpressions() != null) {
                for (Expression on : join.getOnExpressions()) {
                    collectFilterClauses(on, "JOIN", result);
                }
            } else if (join.getOnExpression() != null) {
                // JsqlParser 4.6 兼容旧 API
                collectFilterClauses(join.getOnExpression(), "JOIN", result);
            }
        }
        return result;
    }

    /**
     * 提取 WHERE 条件列表（按 AND/OR 拆分）
     */
    public List<FilterInfo> extractWhereFilters(PlainSelect select) {
        if (select == null) return new ArrayList<>();
        Expression where = select.getWhere();
        if (where == null) return new ArrayList<>();
        List<FilterInfo> result = new ArrayList<>();
        collectFilterClauses(where, "WHERE", result);
        return result;
    }

    /**
     * 递归拆分 AND/OR 表达式为子句列表
     */
    private void collectFilterClauses(Expression expr, String filterType, List<FilterInfo> result) {
        if (expr instanceof AndExpression) {
            AndExpression and = (AndExpression) expr;
            collectFilterClauses(and.getLeftExpression(), filterType, result);
            collectFilterClauses(and.getRightExpression(), filterType, result);
        } else if (expr instanceof OrExpression) {
            OrExpression or = (OrExpression) expr;
            collectFilterClauses(or.getLeftExpression(), filterType, result);
            collectFilterClauses(or.getRightExpression(), filterType, result);
        } else {
            // 叶子条件
            FilterInfo info = new FilterInfo();
            info.filterType = filterType;
            info.clause = expr.toString();
            info.fields = new ArrayList<>();
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
