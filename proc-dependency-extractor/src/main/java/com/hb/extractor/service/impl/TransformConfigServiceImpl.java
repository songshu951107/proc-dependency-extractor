package com.hb.extractor.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.parser.TableMappingDeriver;
import com.hb.extractor.service.TransformConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 映射配置服务实现：读写仓库根 transform_config.json（引擎 transform_table_mapping.py 的输入）。
 * 写回前备份 .bak；表名做白名单校验防注入/穿越。
 */
@Service
public class TransformConfigServiceImpl implements TransformConfigService {

    /** 表名/列名白名单：与脚本名同源的保守集合 */
    private static final Pattern IDENT_RE = Pattern.compile("[A-Za-z0-9_$]+");
    private static final Pattern TABLE_KEY_RE = Pattern.compile("[A-Za-z0-9_$]+");

    private final Path configFile;
    private final ObjectMapper om = new ObjectMapper();

    public TransformConfigServiceImpl(@Value("${transform.engine-dir:.}") String engineDir) {
        this.configFile = Path.of(engineDir).resolve("transform_config.json");
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> loadAll() {
        Map<String, Object> root = readRoot();
        Object tables = root.get("tables");
        return tables instanceof Map ? (Map<String, Object>) tables : new LinkedHashMap<>();
    }

    @Override
    public synchronized void saveTable(Map<String, Object> entry) {
        String oldTable = str(entry.get("old_table"));
        String newTable = str(entry.get("new_table"));
        if (oldTable == null || !TABLE_KEY_RE.matcher(oldTable).matches()) {
            throw new BusinessException("old_table 非法（限字母数字下划线$）: " + oldTable);
        }
        if (newTable == null || !TABLE_KEY_RE.matcher(newTable).matches()) {
            throw new BusinessException("new_table 非法（限字母数字下划线$）: " + newTable);
        }
        Map<String, Object> conf = new LinkedHashMap<>();
        copyIfPresent(entry, conf, "new_table", "old_db", "new_db", "filter",
                "column_mapping", "special_columns", "join_columns");
        Map<String, Object> cm = asMap(conf.get("column_mapping"));
        for (Map.Entry<String, Object> e : cm.entrySet()) {
            if (!IDENT_RE.matcher(e.getKey()).matches()
                    || !IDENT_RE.matcher(String.valueOf(e.getValue())).matches()) {
                throw new BusinessException("column_mapping 含非法列名: "
                        + e.getKey() + " -> " + e.getValue());
            }
        }
        Map<String, Object> root = readRoot();
        Map<String, Object> tables = asMap(root.computeIfAbsent("tables", k -> new LinkedHashMap<>()));
        tables.put(oldTable, conf);
        writeRoot(root);
    }

    @Override
    public synchronized void deleteTable(String oldTable) {
        if (oldTable == null || !TABLE_KEY_RE.matcher(oldTable).matches()) {
            throw new BusinessException("old_table 非法: " + oldTable);
        }
        Map<String, Object> root = readRoot();
        Map<String, Object> tables = asMap(root.get("tables"));
        if (tables.remove(oldTable) == null) {
            throw new BusinessException("表对不存在: " + oldTable);
        }
        writeRoot(root);
    }

    @Override
    public Map<String, Object> importPreviewAuto(List<Map<String, String>> files) {
        // 1. 按内容分类：脚本（含 INSERT）按目标表前缀 F_/O_ 归层；DDL 按表名归层
        List<Map<String, Object>> fScripts = new ArrayList<>();   // {table, schema, content}
        List<Map<String, Object>> oScripts = new ArrayList<>();
        Map<String, String> fDdls = new LinkedHashMap<>();        // 表名(大写) → DDL 文本
        Map<String, String> oDdls = new LinkedHashMap<>();
        List<String> recognized = new ArrayList<>();
        boolean hasAny = false;

        if (files != null) {
            for (Map<String, String> f : files) {
                String content = f.get("content");
                if (content == null || content.isBlank()) continue;
                String name = f.getOrDefault("name", "");
                hasAny = true;
                String insTarget = TableMappingDeriver.detectInsertTarget(content);
                if (insTarget != null) {
                    List<Map<String, Object>> side = insTarget.startsWith("F_") ? fScripts : oScripts;
                    side.add(Map.of("table", insTarget,
                            "schema", String.valueOf(TableMappingDeriver.detectInsertSchema(content)),
                            "content", content, "name", name));
                    recognized.add(name + " → " + (insTarget.startsWith("F_") ? "F" : "O")
                            + " 表加工脚本（INSERT " + insTarget + "）");
                    continue;
                }
                List<String> created = TableMappingDeriver.extractCreateTableNames(content);
                if (!created.isEmpty()) {
                    for (String table : created) {
                        if (table.startsWith("F_")) {
                            fDdls.putIfAbsent(table, content);
                        } else if (table.startsWith("O_")) {
                            oDdls.putIfAbsent(table, content);
                        }
                    }
                    recognized.add(name + " → 表结构 DDL（CREATE " + created + "）");
                } else {
                    recognized.add(name + " → 未识别（无 CREATE TABLE / INSERT INTO）");
                }
            }
        }
        if (!hasAny) {
            throw new BusinessException("未上传有效文件");
        }
        if (fScripts.isEmpty()) {
            throw new BusinessException("文件中未识别出 F 表加工脚本（INSERT INTO F_ 开头的目标表）；识别结果："
                    + String.join("；", recognized));
        }
        if (oScripts.isEmpty()) {
            throw new BusinessException("文件中未识别出 O 表加工脚本（INSERT INTO O_ 开头的目标表）；识别结果："
                    + String.join("；", recognized));
        }

        // 2. 解析脚本侧映射
        Map<String, TableMappingDeriver.ScriptMapping> fParsed = new LinkedHashMap<>();
        Map<String, TableMappingDeriver.ScriptMapping> oParsed = new LinkedHashMap<>();
        for (Map<String, Object> s : fScripts) {
            fParsed.put((String) s.get("table"),
                    TableMappingDeriver.parseScript((String) s.get("content"),
                            (String) s.get("table"),
                            TableMappingDeriver.parseDdlColumns(fDdls.get(s.get("table")), (String) s.get("table"))));
        }
        for (Map<String, Object> s : oScripts) {
            oParsed.put((String) s.get("table"),
                    TableMappingDeriver.parseScript((String) s.get("content"),
                            (String) s.get("table"),
                            TableMappingDeriver.parseDdlColumns(oDdls.get(s.get("table")), (String) s.get("table"))));
        }

        // 3. 配对：单对直配；多对按共同 T 层源列交集最大贪心配对
        List<String[]> pairs = new ArrayList<>();   // {fTable, oTable}
        List<String> warnings = new ArrayList<>();
        if (fParsed.size() == 1 && oParsed.size() == 1) {
            pairs.add(new String[]{fParsed.keySet().iterator().next(),
                    oParsed.keySet().iterator().next()});
        } else {
            Map<String, TableMappingDeriver.ScriptMapping> fRest = new LinkedHashMap<>(fParsed);
            Map<String, TableMappingDeriver.ScriptMapping> oRest = new LinkedHashMap<>(oParsed);
            while (!fRest.isEmpty() && !oRest.isEmpty()) {
                String bestF = null, bestO = null;
                int bestScore = 0;
                for (Map.Entry<String, TableMappingDeriver.ScriptMapping> fe : fRest.entrySet()) {
                    for (Map.Entry<String, TableMappingDeriver.ScriptMapping> oe : oRest.entrySet()) {
                        int score = TableMappingDeriver.derive(fe.getValue(), oe.getValue()).matchedCount;
                        if (score > bestScore) {
                            bestScore = score;
                            bestF = fe.getKey();
                            bestO = oe.getKey();
                        }
                    }
                }
                if (bestF == null) break;   // 无任何交集，剩余的放弃并告警
                pairs.add(new String[]{bestF, bestO});
                fRest.remove(bestF);
                oRest.remove(bestO);
            }
            for (String f : fRest.keySet()) {
                warnings.add("F 表 " + f + " 未找到有共同 T 层源列的 O 脚本，已跳过");
            }
            for (String o : oRest.keySet()) {
                warnings.add("O 表 " + o + " 未找到有共同 T 层源列的 F 脚本，已跳过");
            }
        }

        // 4. 逐对推导预览
        List<Map<String, Object>> previews = new ArrayList<>();
        for (String[] pair : pairs) {
            TableMappingDeriver.ScriptMapping fSide = fParsed.get(pair[0]);
            TableMappingDeriver.ScriptMapping oSide = oParsed.get(pair[1]);
            TableMappingDeriver.DeriveResult r = TableMappingDeriver.derive(fSide, oSide);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("old_table", pair[0]);
            entry.put("new_table", pair[1]);
            String oldDb = schemaOf(fScripts, pair[0], fDdls.get(pair[0]));
            String newDb = schemaOf(oScripts, pair[1], oDdls.get(pair[1]));
            if (oldDb != null) entry.put("old_db", oldDb);
            if (newDb != null) entry.put("new_db", newDb);
            entry.put("column_mapping", r.columnMapping);
            entry.put("matched_count", r.matchedCount);
            entry.put("unmatched_f", r.unmatchedF);
            entry.put("unmatched_o", r.unmatchedO);
            previews.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("previews", previews);
        out.put("recognized", recognized);
        out.put("warnings", warnings);
        return out;
    }

    /** 表对侧的库名：优先该侧脚本的 INSERT schema，回退 DDL 中 CREATE 的 schema */
    private static String schemaOf(List<Map<String, Object>> scripts, String table, String ddl) {
        for (Map<String, Object> s : scripts) {
            if (table.equals(s.get("table"))) {
                String schema = (String) s.get("schema");
                if (schema != null && !"null".equals(schema) && !schema.isEmpty()) return schema;
            }
        }
        if (ddl != null) {
            Matcher m = Pattern.compile(
                    "(?is)CREATE\\s+TABLE\\s+([A-Za-z0-9_$#{}]+)\\s*\\.\\s*[`\"\\[]?"
                            + Pattern.quote(table)).matcher(ddl);
            if (m.find()) {
                return m.group(1).replace("{", "").replace("}", "").toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    @Override
    public synchronized void applyTables(List<Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException("确认内容为空");
        }
        Map<String, Object> root = readRoot();
        Map<String, Object> tables = asMap(root.computeIfAbsent("tables", k -> new LinkedHashMap<>()));
        for (Map<String, Object> entry : entries) {
            String oldTable = str(entry.get("old_table"));
            String newTable = str(entry.get("new_table"));
            if (oldTable == null || !TABLE_KEY_RE.matcher(oldTable).matches()
                    || newTable == null || !TABLE_KEY_RE.matcher(newTable).matches()) {
                throw new BusinessException("表名非法: " + oldTable + " -> " + newTable);
            }
            Map<String, Object> conf = new LinkedHashMap<>();
            copyIfPresent(entry, conf, "new_table", "old_db", "new_db", "filter",
                    "column_mapping", "special_columns", "join_columns");
            // 已有表对：保留 join_columns / special_columns / filter（批量导入不覆盖补键规则）
            Object existing = tables.get(oldTable);
            if (existing instanceof Map) {
                Map<String, Object> ex = asMap(existing);
                for (String k : new String[]{"join_columns", "special_columns", "filter"}) {
                    if (!conf.containsKey(k) && ex.containsKey(k)) conf.put(k, ex.get(k));
                }
            }
            tables.put(oldTable, conf);
        }
        writeRoot(root);
    }

    // ------------------------------------------------------------------ IO

    private Map<String, Object> readRoot() {
        if (!Files.exists(configFile)) {
            return new LinkedHashMap<>();
        }
        try {
            return asMap(om.readValue(configFile.toFile(), Map.class));
        } catch (Exception e) {
            throw new BusinessException("transform_config.json 解析失败: " + e.getMessage());
        }
    }

    private void writeRoot(Map<String, Object> root) {
        try {
            if (Files.exists(configFile)) {
                Files.copy(configFile, configFile.resolveSibling("transform_config.json.bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            om.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), root);
        } catch (Exception e) {
            throw new BusinessException("transform_config.json 写入失败: " + e.getMessage());
        }
    }

    /** 上传文本解码：优先 UTF-8，失败回退 GBK（国内 ETL 脚本常见编码） */
    public static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to,
                                      String... keys) {
        for (String k : keys) {
            if (from.containsKey(k) && from.get(k) != null) to.put(k, from.get(k));
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
