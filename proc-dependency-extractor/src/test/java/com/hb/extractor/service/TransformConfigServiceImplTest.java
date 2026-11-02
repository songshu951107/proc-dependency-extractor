package com.hb.extractor.service;

import com.hb.extractor.service.impl.TransformConfigServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformConfigServiceImplTest {

    @TempDir
    Path tmp;

    private TransformConfigServiceImpl service() {
        return new TransformConfigServiceImpl(tmp.toString());
    }

    private static final String INIT = """
            {"tables":{"F_OLD":{"new_table":"O_NEW","old_db":"FDM","new_db":"ODM",
              "filter":"zip","column_mapping":{"a":"b"},
              "special_columns":{"del_flag":"del_ind"},
              "join_columns":{"core_cust_no":{"join_table":"{ODM}.O_CUST"}}}}}
            """;

    private void initConfig() throws Exception {
        Files.writeString(tmp.resolve("transform_config.json"), INIT);
    }

    @Test
    void loadAllReturnsTables() throws Exception {
        initConfig();
        Map<String, Object> tables = service().loadAll();
        assertTrue(tables.containsKey("F_OLD"));
        assertEquals("O_NEW", ((Map<?, ?>) tables.get("F_OLD")).get("new_table"));
    }

    @Test
    void saveUpsertAndBackup() throws Exception {
        initConfig();
        TransformConfigServiceImpl s = service();
        s.saveTable(Map.of("old_table", "F_NEW", "new_table", "O_N2",
                "old_db", "FDM", "new_db", "ODM",
                "column_mapping", Map.of("x", "y")));
        Map<String, Object> tables = s.loadAll();
        assertEquals(2, tables.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) tables.get("F_NEW");
        @SuppressWarnings("unchecked")
        Map<String, String> cm = (Map<String, String>) conf.get("column_mapping");
        assertEquals("y", cm.get("x"));
        // .bak 备份生成
        assertTrue(Files.exists(tmp.resolve("transform_config.json.bak")));
    }

    @Test
    void saveRejectsIllegalNames() {
        assertThrows(Exception.class, () -> service().saveTable(
                Map.of("old_table", "F; DROP", "new_table", "O_X")));
        assertThrows(Exception.class, () -> service().saveTable(
                Map.of("old_table", "F_X", "new_table", "")));
        assertThrows(Exception.class, () -> service().saveTable(
                Map.of("old_table", "F_X", "new_table", "O_X",
                        "column_mapping", Map.of("a; b", "c"))));
    }

    @Test
    void deleteRemovesEntry() throws Exception {
        initConfig();
        service().deleteTable("F_OLD");
        assertFalse(service().loadAll().containsKey("F_OLD"));
        assertThrows(Exception.class, () -> service().deleteTable("F_OLD"));
    }

    @Test
    void importPreviewDerivesMapping() {
        // 单入口多文件：顺序随意，后端按内容自动识别
        String fFile = """
                CREATE TABLE FDM.F_T (cust_no VARCHAR(32), org_type_cd VARCHAR(10), rmk VARCHAR(99));
                INSERT INTO {FDM}.F_T (cust_no, org_type_cd, rmk)
                SELECT T.cust_id, T.org_type, T.note
                  FROM {TDM}.T_CUST T;
                """;
        String oFile = """
                INSERT INTO {ODM}.O_T (cust_id, org_cust_type, remarks)
                SELECT T.cust_id, T.org_type, T.note
                  FROM {TDM}.T_CUST T;
                """;
        String noise = "SELECT 1;";
        Map<String, Object> preview = service().importPreview(
                List.of(Map.of("name", "o.sql", "content", oFile),
                        Map.of("name", "f.sql", "content", fFile),
                        Map.of("name", "x.sql", "content", noise)),
                "F_T", "O_T", "FDM", "ODM");
        assertEquals(3, ((Number) preview.get("matched_count")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, String> cm = (Map<String, String>) preview.get("column_mapping");
        assertEquals("cust_id", cm.get("cust_no"));
        assertEquals("org_cust_type", cm.get("org_type_cd"));
        assertEquals("remarks", cm.get("rmk"));
        @SuppressWarnings("unchecked")
        List<String> recognized = (List<String>) preview.get("recognized");
        assertTrue(recognized.get(1).contains("F 表加工脚本"));
        assertTrue(recognized.get(0).contains("O 表加工脚本"));
        assertTrue(recognized.get(2).contains("未识别"));
        // 预览不落盘
        assertFalse(Files.exists(tmp.resolve("transform_config.json")));
    }

    @Test
    void importPreviewRequiresScriptsAndTarget() {
        // 缺少 O 侧脚本 → 报错并列出识别结果
        assertThrows(Exception.class, () -> service().importPreview(
                List.of(Map.of("name", "f.sql",
                        "content", "INSERT INTO F_T (a) SELECT T.a FROM T T")),
                "F_T", "O_T", null, null));
        // 表名非法
        assertThrows(Exception.class, () -> service().importPreview(
                List.of(Map.of("name", "f.sql",
                                "content", "INSERT INTO F_T (a) SELECT T.a FROM T T"),
                        Map.of("name", "o.sql",
                                "content", "INSERT INTO O_T (a) SELECT T.a FROM T T")),
                "F_T", "O;T", null, null));
    }

    @Test
    void applyMergesAndPreservesJoinColumns() throws Exception {
        initConfig();
        service().applyTables(List.of(Map.of(
                "old_table", "F_OLD", "new_table", "O_NEW_V2",
                "old_db", "FDM", "new_db", "ODM",
                "column_mapping", Map.of("cust_no", "cust_id"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) service().loadAll().get("F_OLD");
        assertEquals("O_NEW_V2", conf.get("new_table"));
        // join_columns / special_columns / filter 保留
        assertTrue(conf.containsKey("join_columns"));
        assertTrue(conf.containsKey("special_columns"));
        assertEquals("zip", conf.get("filter"));
    }
}
