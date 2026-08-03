package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ReportMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CptXmlParserTest {

    @Autowired
    private CptXmlParser cptXmlParser;

    private static final String CPT_FILE = "d:/HB/work_zuoye/reports/公司类存款明细表.cpt";

    @Test
    void testExtractDatasets() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return; // 测试环境无文件时跳过
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        assertEquals("公司类存款明细表", metadata.getName());
        assertEquals("cpt", metadata.getType());
        assertEquals(3, metadata.getDatasets().size());

        // 验证 ds1
        var ds1 = metadata.getDatasets().get(0);
        assertEquals("ds1", ds1.getName());
        assertEquals("139.0.10.11_rpt(gbase8A)", ds1.getConnection());
        assertNotNull(ds1.getSql());
        assertTrue(ds1.getSql().contains("RPT.A_CFIN_DEPT_ACCT_INFO"));
    }

    @Test
    void testExtractCellBindings() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return;
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        assertFalse(metadata.getCellBindings().isEmpty());

        // 验证第一个数据列绑定（c=0 r=2 → A3, ds1, ORD_II_ID）
        var firstBinding = metadata.getCellBindings().get(0);
        assertEquals("A3", firstBinding.getCellPos());
        assertEquals("ds1", firstBinding.getDsName());
        assertEquals("ORD_II_ID", firstBinding.getColumnName());
    }

    @Test
    void testConnectionName() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return;
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        // ds2 和 ds3 应使用 pdbfine_visual 连接
        var ds2 = metadata.getDatasets().get(1);
        assertEquals("ds2", ds2.getName());
        assertEquals("pdbfine_visual", ds2.getConnection());
    }
}
