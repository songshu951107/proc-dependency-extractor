import com.hb.extractor.parser.FieldMappingExtractor;
import com.hb.extractor.parser.JsqlSqlAnalyzer;
import com.hb.extractor.parser.SqlPlaceholderReplacer;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.TransformInput;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.PlainSelect;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** 诊断: 复刻 LineageExtractor.parseBlock 的字段映射管线, 统计断链 inputs (sourceTableShort==null) */
public class DiagMain {
    static void setField(Object target, String name, Object value) throws Exception {
        Field f = null; Class<?> c = target.getClass();
        while (c != null && f == null) {
            try { f = c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        if (f == null) throw new IllegalStateException("no field " + name);
        f.setAccessible(true); f.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
            com.hb.extractor.entity.TableColumnMeta.class);

        SqlPlaceholderReplacer replacer = new SqlPlaceholderReplacer();
        JsqlSqlAnalyzer analyzer = new JsqlSqlAnalyzer();
        setField(analyzer, "placeholderReplacer", replacer);
        FieldMappingExtractor extractor = new FieldMappingExtractor();
        setField(extractor, "analyzer", analyzer);
        setField(extractor, "placeholderReplacer", replacer);
        Object mapper = Proxy.newProxyInstance(DiagMain.class.getClassLoader(),
            new Class[]{com.hb.extractor.mapper.TableColumnMetaMapper.class},
            (p, m, a) -> Collections.emptyList());
        setField(extractor, "tableColumnMetaMapper", mapper);

        Set<String> focus = new HashSet<>();
        for (String s : new String[]{"dwa_pid_a_g_ln_new_pord_sum","dwa_pid_a_d_pi_new_gl_d_b",
                "dwa_pid_a_g_dp_deposit_report","dwa_pid_a_g_ln_loan_acct_prft_sum",
                "dwa_pid_a_d_ln_loan_org_dtl","dwa_A_D_FIN_GL_SUBJ_BAL_B",
                "dwa_pid_a_d_ln_loan_specil_item_setou_dtl","dwa_c_agt_loan_appl_inf",
                "dwa_pid_a_g_ln_pri_loan_prod_bal_inf","dwa_c_agt_credit_card_info"})
            focus.add(s.toLowerCase());

        List<String> lines = Files.readAllLines(Paths.get(args[0]));
        Pattern headPat = Pattern.compile("^\\S+\\|\\|\\|(\\d+)\\|\\|");
        LinkedHashMap<String, List<String[]>> procs = new LinkedHashMap<>();
        String curProc = null, curBlock = null; StringBuilder curSql = null;
        for (String raw : lines) {
            String line = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            Matcher m = headPat.matcher(line);
            if (m.find()) {
                if (curProc != null) procs.get(curProc).add(new String[]{curBlock, curSql.toString()});
                curProc = line.substring(0, line.indexOf("|||"));
                if (!procs.containsKey(curProc)) procs.put(curProc, new ArrayList<>());
                curBlock = m.group(1);
                int p1 = line.indexOf("|||");
                int p2 = line.indexOf("|||", p1 + 3);
                curSql = new StringBuilder(p2 >= 0 ? line.substring(p2 + 3) : "");
            } else if (curSql != null) {
                curSql.append("\n").append(line);
            }
        }
        if (curProc != null) procs.get(curProc).add(new String[]{curBlock, curSql.toString()});

        int grandTotal = 0;
        for (Map.Entry<String, List<String[]>> e : procs.entrySet()) {
            String proc = e.getKey();
            boolean focused = focus.contains(proc.toLowerCase());
            Map<String, List<String>> tempCache = new HashMap<>();
            if (focused) System.out.println("=== PROC " + proc + " ===");
            for (String[] rec : e.getValue()) {
                String block = rec[0];
                String sql = rec[1].replace("\\n", "\n").replace("\\t", "\t");
                Statement stmt = analyzer.parse(sql);
                if (stmt == null) {
                    if (focused) System.out.println("  [b" + block + "] PARSE FAILED (块被跳过, 无字段映射)");
                    continue;
                }
                String targetTable = null; PlainSelect sourceSelect = null;
                List<String> inlineDdl = null; boolean targetIsTemp = false; boolean isCt = false;
                if (stmt instanceof CreateTable) {
                    isCt = true;
                    CreateTable ct = (CreateTable) stmt;
                    if (ct.getTable() != null) {
                        targetTable = ct.getTable().getName();
                        targetIsTemp = true;
                        if (ct.getColumnDefinitions() != null) {
                            inlineDdl = new ArrayList<>();
                            for (net.sf.jsqlparser.statement.create.table.ColumnDefinition cd : ct.getColumnDefinitions())
                                inlineDdl.add(cd.getColumnName().toLowerCase());
                        }
                        if (ct.getSelect() != null && ct.getSelect().getSelectBody() instanceof PlainSelect)
                            sourceSelect = (PlainSelect) ct.getSelect().getSelectBody();
                    }
                } else if (stmt instanceof Insert) {
                    Insert ins = (Insert) stmt;
                    if (ins.getTable() != null) {
                        targetTable = ins.getTable().getName();
                        targetIsTemp = JsqlSqlAnalyzer.isTempTableName(targetTable)
                                || tempCache.containsKey(targetTable.toLowerCase());
                        if (ins.getSelect() != null && ins.getSelect().getSelectBody() instanceof PlainSelect)
                            sourceSelect = (PlainSelect) ins.getSelect().getSelectBody();
                    }
                } else {
                    if (focused) System.out.println("  [b" + block + "] STMT=" + stmt.getClass().getSimpleName() + " (非INSERT/CREATE, 跳过)");
                    continue;
                }
                if (targetTable == null || sourceSelect == null) {
                    if (focused) System.out.println("  [b" + block + "] 无目标表或无SELECT: target=" + targetTable);
                    continue;
                }
                String tl = targetTable.toLowerCase();
                if (isCt && inlineDdl != null && !inlineDdl.isEmpty()) tempCache.put(tl, inlineDdl);
                Map<String, String> aliasMap = analyzer.extractAliasMap(sourceSelect);
                List<String> ddlFields = inlineDdl;
                if (ddlFields == null || ddlFields.isEmpty()) ddlFields = null;
                if (ddlFields == null && targetIsTemp) ddlFields = tempCache.get(tl);
                List<FieldMapping> mappings = extractor.extract(sql, tl, aliasMap, ddlFields, tempCache);
                if (isCt && targetIsTemp && !tempCache.containsKey(tl)) {
                    List<String> derived = new ArrayList<>();
                    for (FieldMapping fm : mappings) if (fm.getTargetField() != null) derived.add(fm.getTargetField());
                    if (!derived.isEmpty()) tempCache.put(tl, derived);
                }
                int broken = 0;
                StringBuilder detail = new StringBuilder();
                for (FieldMapping fm : mappings) {
                    for (TransformInput ti : fm.getInputs()) {
                        if (ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty()) {
                            broken++;
                            detail.append("    BROKEN target=").append(fm.getTargetField())
                                  .append(" <- ").append(ti.getSourceField())
                                  .append(" (alias=").append(ti.getSourceAlias())
                                  .append(", role=").append(ti.getRole()).append(")\n");
                        }
                    }
                }
                grandTotal += broken;
                if (focused) {
                    System.out.println("  [b" + block + "] target=" + tl + " mappings=" + mappings.size()
                        + " broken=" + broken + " aliasMap=" + aliasMap
                        + " ddl=" + (ddlFields == null ? "null" : ddlFields.size()));
                    System.out.print(detail);
                }
            }
        }
        System.out.println("GRAND TOTAL BROKEN = " + grandTotal);
    }
}
