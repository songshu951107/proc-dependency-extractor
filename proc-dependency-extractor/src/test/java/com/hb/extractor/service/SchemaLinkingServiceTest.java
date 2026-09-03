package com.hb.extractor.service;

import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.entity.TableColumnMeta;
import com.hb.extractor.mapper.TableColumnMetaMapper;
import com.hb.extractor.service.impl.SchemaLinkingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchemaLinkingServiceTest {

    private TableColumnMetaMapper mapper;
    private SchemaLinkingServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(TableColumnMetaMapper.class);
        Nl2SqlProperties props = new Nl2SqlProperties();
        service = new SchemaLinkingServiceImpl(mapper, null, props);
        // null = Neo4jClient 未注入时跳过血缘反查（实现需判空）
    }

    private TableColumnMeta col(String fullTable, String schema, String name, String comment) {
        TableColumnMeta c = new TableColumnMeta();
        c.setFullTableName(fullTable);
        c.setSchemaName(schema);
        c.setTableName(fullTable.split("\\.")[1]);
        c.setColumnName(name);
        c.setColumnType("decimal(16,2)");
        c.setColumnComment(comment);
        return c;
    }

    @Test
    void 中文注释命中召回宿主表并按得分排序() {
        when(mapper.selectByCommentOrColumn(ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList()))
                .thenReturn(List.of(
                        col("fdm.f_ev_ftps_pub_cust_inf", "fdm", "acc_int_year", "账户年利息"),
                        col("cdm.c_com_exch_rate", "cdm", "dom_currcy_convt_price", "汇率")));
        List<CandidateTable> result = service.link("查一下账户年利息");
        assertEquals(1, result.size());
        assertEquals("fdm.f_ev_ftps_pub_cust_inf", result.get(0).getFullTableName());
        assertEquals("COMMENT", result.get(0).getSource());
    }

    @Test
    void 问句为空返回空列表() {
        assertTrue(service.link("").isEmpty());
        assertTrue(service.link(null).isEmpty());
    }

    @Test
    void 候选表数不超过上限() {
        when(mapper.selectByCommentOrColumn(ArgumentMatchers.<String>anyList(), ArgumentMatchers.<String>anyList()))
                .thenReturn(List.of(
                        col("a.t1", "a", "x", "账户年利息"),
                        col("a.t2", "a", "x", "账户年利息"),
                        col("a.t3", "a", "x", "账户年利息")));
        List<CandidateTable> result = service.link("账户年利息");
        assertTrue(result.size() <= new Nl2SqlProperties().getMaxCandidateTables());
    }
}