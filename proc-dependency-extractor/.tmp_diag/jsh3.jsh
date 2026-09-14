import net.sf.jsqlparser.parser.CCJSqlParserUtil;
net.sf.jsqlparser.statement.Statement s = CCJSqlParserUtil.parse("SELECT DATE'__ph_ETL_TX_DT__' AS DATA_DT, t1.ORG_NO AS ORG_NO FROM CDM.f_p11_card t1 GROUP BY t1.ORG_NO");
var ps = (net.sf.jsqlparser.statement.select.PlainSelect)((net.sf.jsqlparser.statement.select.Select)s).getSelectBody();
System.out.println("item0=" + ps.getSelectItems().get(0).getExpression().getClass().getName());
/exit
