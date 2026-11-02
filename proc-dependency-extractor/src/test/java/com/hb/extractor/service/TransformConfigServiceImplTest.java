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
    @SuppressWarnings("unchecked")
    void importPreviewAutoDerivesMapping() {
        // 单入口多文件：顺序随意，后端按内容自动识别 F/O 层并配对，无需传表名
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
        Map<String, Object> out = service().importPreviewAuto(
                List.of(Map.of("name", "o.sql", "content", oFile),
                        Map.of("name", "f.sql", "content", fFile),
                        Map.of("name", "x.sql", "content", noise)));
        List<Map<String, Object>> previews = (List<Map<String, Object>>) out.get("previews");
        assertEquals(1, previews.size());
        Map<String, Object> preview = previews.get(0);
        assertEquals("F_T", preview.get("old_table"));
        assertEquals("O_T", preview.get("new_table"));
        // 库名从 INSERT INTO {FDM}/{ODM} 的 schema 段自动提取
        assertEquals("FDM", preview.get("old_db"));
        assertEquals("ODM", preview.get("new_db"));
        assertEquals(3, ((Number) preview.get("matched_count")).intValue());
        Map<String, String> cm = (Map<String, String>) preview.get("column_mapping");
        assertEquals("cust_id", cm.get("cust_no"));
        assertEquals("org_cust_type", cm.get("org_type_cd"));
        assertEquals("remarks", cm.get("rmk"));
        List<String> recognized = (List<String>) out.get("recognized");
        assertTrue(recognized.get(1).contains("F 表加工脚本"));
        assertTrue(recognized.get(0).contains("O 表加工脚本"));
        assertTrue(recognized.get(2).contains("未识别"));
        // 预览不落盘
        assertFalse(Files.exists(tmp.resolve("transform_config.json")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importPreviewAutoPairsMultipleTables() {
        // 两对 F/O 脚本乱序上传：按 T 层源列交集自动配对（F_A→O_A、F_B→O_B）
        String f1 = "INSERT INTO {FDM}.F_A (c1, c2) SELECT T.a, T.b FROM {TDM}.T1 T";
        String f2 = "INSERT INTO {FDM}.F_B (c1) SELECT T.x FROM {TDM}.T2 T";
        String o1 = "INSERT INTO {ODM}.O_B (c1) SELECT T.x FROM {TDM}.T2 T";
        String o2 = "INSERT INTO {ODM}.O_A (c1, c2) SELECT T.a, T.b FROM {TDM}.T1 T";
        Map<String, Object> out = service().importPreviewAuto(List.of(
                Map.of("name", "o1.sql", "content", o1),
                Map.of("name", "f1.sql", "content", f1),
                Map.of("name", "f2.sql", "content", f2),
                Map.of("name", "o2.sql", "content", o2)));
        List<Map<String, Object>> previews = (List<Map<String, Object>>) out.get("previews");
        assertEquals(2, previews.size());
        Map<String, String> pairByOld = new java.util.LinkedHashMap<>();
        Map<String, String> dbByOld = new java.util.LinkedHashMap<>();
        for (Map<String, Object> p : previews) {
            pairByOld.put((String) p.get("old_table"), (String) p.get("new_table"));
            dbByOld.put((String) p.get("old_table"), p.get("old_db") + ">" + p.get("new_db"));
        }
        assertEquals("O_A", pairByOld.get("F_A"));
        assertEquals("O_B", pairByOld.get("F_B"));
        assertEquals("FDM>ODM", dbByOld.get("F_A"));
        assertEquals(0, ((List<String>) out.get("warnings")).size());
    }

    @Test
    void importPreviewAutoRequiresBothSides() {
        // 缺少 O 侧脚本 → 报错并列出识别结果
        assertThrows(Exception.class, () -> service().importPreviewAuto(
                List.of(Map.of("name", "f.sql",
                        "content", "INSERT INTO F_T (a) SELECT T.a FROM T T"))));
        // 空文件列表 → 报错
        assertThrows(Exception.class, () -> service().importPreviewAuto(List.of()));
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
