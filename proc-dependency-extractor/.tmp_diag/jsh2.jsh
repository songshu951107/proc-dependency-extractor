import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
net.sf.jsqlparser.statement.Statement s = CCJSqlParserUtil.parse("SELECT DATE'2024-01-01' AS DATA_DT FROM t");
var ps = (net.sf.jsqlparser.statement.select.PlainSelect)((net.sf.jsqlparser.statement.select.Select)s).getSelectBody();
var it = ps.getSelectItems().get(0);
System.out.println("exprClass=" + it.getExpression().getClass().getName());
System.out.println("expr=" + it.getExpression());
