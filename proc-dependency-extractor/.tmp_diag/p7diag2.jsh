import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import com.hb.extractor.parser.SqlPlaceholderReplacer;
import java.util.regex.*;
SqlPlaceholderReplacer rep = new SqlPlaceholderReplacer();
String all = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("D:/HB/work_zuoye/proc-dependency-extractor/.tmp_diag/p7blocks.txt")), java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF", "");
String[] parts = all.split("@@BLOCK ");
for (int i = 1; i < parts.length; i++) { String header = parts[i].substring(0, parts[i].indexOf("@@")).trim(); String sql = parts[i].substring(parts[i].indexOf("@@") + 3); String processed = rep.preprocess(sql); try { Statement st = CCJSqlParserUtil.parse(processed); if (!(st instanceof net.sf.jsqlparser.statement.create.table.CreateTable)) { System.out.println("NOT-CREATE [" + header + "] -> " + st.getClass().getSimpleName()); } } catch (Exception e) { String m = e.getMessage(); Matcher lm = Pattern.compile("line (\\d+), column (\\d+)").matcher(m); int ln = lm.find() ? Integer.parseInt(lm.group(1)) : -1; String tok = ""; Matcher tm = Pattern.compile("unexpected token: \"([^\"]*)\"").matcher(m); if (tm.find()) tok = tm.group(1); String[] lines = processed.split("\n"); String lineTxt = (ln > 0 && ln <= lines.length) ? lines[ln - 1].trim() : "?"; if (lineTxt.length() > 90) lineTxt = lineTxt.substring(0, 90); String first = m.split("\n")[0]; System.out.println("FAIL [" + header + "] L" + ln + " tok=" + tok + " | " + lineTxt); } }
/exit
