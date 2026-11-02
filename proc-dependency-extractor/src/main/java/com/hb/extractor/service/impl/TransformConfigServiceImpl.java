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
    public Map<String, Object> importPreview(Map<String, Object> req) {
        String oldTable = str(req.get("oldTable"));
        String newTable = str(req.get("newTable"));
        if (oldTable == null || newTable == null
                || !TABLE_KEY_RE.matcher(oldTable).matches()
                || !TABLE_KEY_RE.matcher(newTable).matches()) {
            throw new BusinessException("oldTable / newTable 必填且限字母数字下划线$");
        }
        String fScript = str(req.get("fScript"));
        String oScript = str(req.get("oScript"));
        if (fScript == null || fScript.isBlank() || oScript == null || oScript.isBlank()) {
            throw new BusinessException("F 表与 O 表的 T 层加工脚本均为必填");
        }
        List<String> fDdlCols = TableMappingDeriver.parseDdlColumns(str(req.get("fDdl")));
        List<String> oDdlCols = TableMappingDeriver.parseDdlColumns(str(req.get("oDdl")));

        TableMappingDeriver.ScriptMapping fSide =
                TableMappingDeriver.parseScript(fScript, oldTable, fDdlCols);
        TableMappingDeriver.ScriptMapping oSide =
                TableMappingDeriver.parseScript(oScript, newTable, oDdlCols);
        if (fSide.cols.isEmpty()) {
            throw new BusinessException("F 加工脚本中未找到 INSERT INTO " + oldTable + " 的 SELECT");
        }
        if (oSide.cols.isEmpty()) {
            throw new BusinessException("O 加工脚本中未找到 INSERT INTO " + newTable + " 的 SELECT");
        }
        TableMappingDeriver.DeriveResult r = TableMappingDeriver.derive(fSide, oSide);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("old_table", oldTable);
        out.put("new_table", newTable);
        String oldDb = str(req.get("oldDb"));
        String newDb = str(req.get("newDb"));
        if (oldDb != null) out.put("old_db", oldDb);
        if (newDb != null) out.put("new_db", newDb);
        out.put("column_mapping", r.columnMapping);
        out.put("matched_count", r.matchedCount);
        out.put("unmatched_f", r.unmatchedF);
        out.put("unmatched_o", r.unmatchedO);
        return out;
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
