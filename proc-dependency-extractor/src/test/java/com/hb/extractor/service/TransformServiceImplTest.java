package com.hb.extractor.service;

import com.hb.extractor.dto.TransformScriptVO;
import com.hb.extractor.service.impl.TransformServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransformServiceImplTest {

    @TempDir
    Path tmp;

    private TransformServiceImpl service() {
        return new TransformServiceImpl(tmp.toString(), "dwa_f,dwa_o");
    }

    private void putInput(String name) throws Exception {
        Files.createDirectories(tmp.resolve("input"));
        Files.writeString(tmp.resolve("input").resolve(name),
                "#更新对象 X\nbsql=\"\"\"SELECT 1\"\"\"\n");
    }

    private void putOutput(String dirName, String reportJson) throws Exception {
        Path d = tmp.resolve("output").resolve(dirName);
        Files.createDirectories(d);
        Files.writeString(d.resolve("source.py"), "orig\n");
        Files.writeString(d.resolve("converted.py"), "conv\n");
        Files.writeString(d.resolve("report.json"), reportJson);
    }

    private static final String OK_REPORT = """
            {"script":"a.py","py_compile":"PASS",
             "config":{"old_db":"FDM","old_table":"F_T","new_db":"ODM","new_table":"O_T"},
             "summary":{"total":3,"residues":0},
             "edits":[],"residues":[]}""";

    @Test
    void pending_whenOnlyInput() throws Exception {
        putInput("a.py");
        List<TransformScriptVO> list = service().listScripts();
        assertEquals(1, list.size());
        assertEquals("PENDING", list.get(0).getStatus());
    }

    @Test
    void converted_whenReportOk() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        TransformScriptVO vo = service().listScripts().get(0);
        assertEquals("CONVERTED", vo.getStatus());
        assertEquals(3, vo.getTotalEdits());
        assertEquals(0, vo.getResidueCount());
    }

    @Test
    void failed_whenCompileFail() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT.replace("\"PASS\"", "\"FAIL\"")
                .replace("}],", "}],\"error\":\"syntax\""));
        assertEquals("FAILED", service().listScripts().get(0).getStatus());
    }

    @Test
    void stale_whenInputReplaced() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        // 确保输入 mtime 严格晚于 source.py
        Path input = tmp.resolve("input").resolve("a.py");
        input.toFile().setLastModified(
                tmp.resolve("output/a/source.py").toFile().lastModified() + 60_000);
        assertEquals("STALE", service().listScripts().get(0).getStatus());
    }

    @Test
    void rejectsIllegalName() {
        assertThrows(Exception.class, () -> service().getReport("../etc"));
        assertThrows(Exception.class, () -> service().readFile("a b", "source"));
    }

    @Test
    void readFileSourceAndConverted() throws Exception {
        putInput("a.py");
        putOutput("a", OK_REPORT);
        assertEquals("orig\n", service().readFile("a", "source"));
        assertEquals("conv\n", service().readFile("a", "converted"));
    }

    @Test
    void blacklistFilteredFromList() throws Exception {
        // F 层自身加工脚本：input 与 output 都不上架（页面上看不到）
        putInput("a.py");
        putInput("dwa_f_pt_ecif_pub_cust_inf.py");
        putOutput("dwa_f_pt_ecif_pub_cust_inf", OK_REPORT);
        List<TransformScriptVO> list = service().listScripts();
        assertEquals(1, list.size());
        assertEquals("a.py", list.get(0).getName());
    }

    @Test
    void uploadRejectsBlacklistedName() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "DWA_O_DEMO.py", "text/plain", "#x\n".getBytes());
        assertThrows(Exception.class, () -> service().upload(file));
    }
}
