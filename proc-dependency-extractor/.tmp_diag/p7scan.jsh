import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
String all = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("D:/HB/work_zuoye/proc-dependency-extractor/.tmp_diag/p7blocks.txt")), java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF", "");
String[] parts = all.split("@@BLOCK ");
int ok = 0, fail = 0;
for (int i = 1; i < parts.length; i++) { String header = parts[i].substring(0, parts[i].indexOf("@@")).trim(); String sql = parts[i].substring(parts[i].indexOf("@@") + 3); String processed = sql.replaceAll("(?:\\r?\\n[ \\t]*){2,}", "\n").replaceAll("\\{(\\w+)\\}\\.", "$1.").replaceAll("(\\w)'\\{\\w+\\}'", "$1").replaceAll("(\\w)\\{\\w+\\}", "$1"); try { Statement st = CCJSqlParserUtil.parse(processed); if (st instanceof net.sf.jsqlparser.statement.create.table.CreateTable) { net.sf.jsqlparser.statement.create.table.CreateTable ct = (net.sf.jsqlparser.statement.create.table.CreateTable) st; ok++; System.out.println("OK [" + header + "] cols=" + (ct.getColumnDefinitions() == null ? -1 : ct.getColumnDefinitions().size()) + (ct.getSelect() != null ? " CTAS" : "")); } else { fail++; System.out.println("NOT-CREATE [" + header + "] -> " + st.getClass().getSimpleName()); } } catch (Exception e) { fail++; String msg = e.getMessage().split("\n")[0]; if (msg.length() > 140) msg = msg.substring(0, 140); System.out.println("FAIL [" + header + "] " + msg); } }
System.out.println("SUMMARY ok=" + ok + " fail=" + fail);
/exit
