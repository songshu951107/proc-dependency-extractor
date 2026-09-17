package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ReportDatasetDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportParseServiceImplTest {

    @Autowired
    private ReportParseServiceImpl service;

    @Test
    void testBuildDatasetDetail_mapsSourceTablesAndFields() {
        String sql = "SELECT a.CUST_NO, a.ACTUALARTIFICIALNO 合同文本号 FROM GMDATA.G_AG_ZWLOAN_HKMX a";
        ReportDatasetDetailVO vo = service.buildDatasetDetail("ds1", "fin_ods", sql);
        assertEquals("ds1", vo.getDatasetName());
        assertEquals("fin_ods", vo.getConnection());
        assertEquals(1, vo.getTableCount());
        assertEquals(1, vo.getSourceTables().size());
        // getFullName 返回全小写
        assertEquals("gmdata.g_ag_zwloan_hkmx", vo.getSourceTables().get(0).getFullName());
        assertTrue(vo.getFieldMappings().stream().anyMatch(m -> m.getSourceField().equals("ACTUALARTIFICIALNO")));
        assertEquals("合同文本号", vo.getFieldMappings().get(1).getSelectAlias());
    }
}