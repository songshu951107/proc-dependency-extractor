package com.hb.extractor.service;

import com.fasterxml.jackson.databind.node.TextNode;
import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.dto.nl2sql.Nl2SqlResult;
import com.hb.extractor.service.impl.Nl2SqlServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Nl2SqlServiceTest {

    private SchemaLinkingService linking;
    private JoinPathService joinPath;
    private LlmClient llm;
    private Nl2SqlServiceImpl service;

    @BeforeEach
    void setUp() {
        linking = mock(SchemaLinkingService.class);
        joinPath = mock(JoinPathService.class);
        // 用 spy：保留 buildSystemPrompt/buildUserPrompt/extractJson 的真实逻辑，
        // 只 stub chat()（mock 版这些方法返回 null 会走不通校验分支）
        llm = spy(new LlmClient(new Nl2SqlProperties()));
        when(joinPath.findJoinPaths(anyList())).thenReturn(List.of());
        service = new Nl2SqlServiceImpl(linking, joinPath, llm, new Nl2SqlProperties());
    }

    /** 构造一个非空候选表，避免走"未匹配到任何表"的 ERROR 分支，才能测到 LLM/校验/重试逻辑 */
    private CandidateTable makeTable() {
        CandidateTable t = new CandidateTable();
        t.setFullTableName("fdm.f_ev_ftps_pub_cust_inf");
        t.setSchemaName("fdm");
        t.setFields(List.of());
        return t;
    }

    private String llmJson(String sql) {
        return "{\"sql\": " + TextNode.valueOf(sql).toString() +
               ", \"tables_used\": [], \"join_notes\": [], \"assumptions\": []}";
    }

    private void llmReturns(String sql) {
        // 用 doReturn 而非 when()：spy 上 when(x.chat(...)) 会先真实执行 chat()（引起真实网络调用）
        doReturn(llmJson(sql)).when(llm).chat(anyString(), anyString());
    }

    @Test
    void 生成合法SQL返回VALIDATED() {
        when(linking.link(anyString())).thenReturn(List.of(makeTable()));
        llmReturns("SELECT cust_id FROM fdm.f_ev_ftps_pub_cust_inf LIMIT 10");
        Nl2SqlResult r = service.generate("查客户号");
        assertEquals("VALIDATED", r.getValidationStatus());
        assertNotNull(r.getSql());
    }

    @Test
    void 语法错误时自动重试并在成功后返回() {
        when(linking.link(anyString())).thenReturn(List.of(makeTable()));
        doReturn(llmJson("SELECT FROM WHERE"),
                 llmJson("SELECT cust_id FROM fdm.f_ev_ftps_pub_cust_inf"))
                .when(llm).chat(anyString(), anyString());
        Nl2SqlResult r = service.generate("查客户号");
        assertEquals("VALIDATED", r.getValidationStatus());
        verify(llm, times(2)).chat(anyString(), anyString());
    }

    @Test
    void 重试耗尽返回FAILED() {
        when(linking.link(anyString())).thenReturn(List.of(makeTable()));
        doReturn(llmJson("SELECT FROM WHERE")).when(llm).chat(anyString(), anyString());
        Nl2SqlResult r = service.generate("查客户号");
        assertEquals("FAILED", r.getValidationStatus());
        // 首次 + 重试 2 次 = 3 次调用
        verify(llm, times(3)).chat(anyString(), anyString());
    }

    @Test
    void 召回为空时返回ERROR提示() {
        when(linking.link(anyString())).thenReturn(List.of());
        Nl2SqlResult r = service.generate("查客户号");
        assertEquals("ERROR", r.getValidationStatus());
        assertTrue(r.getErrorMessage().contains("未匹配到任何表"));
        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void LLM调用异常返回ERROR() {
        when(linking.link(anyString())).thenReturn(List.of(makeTable()));
        doThrow(new RuntimeException("网络超时")).when(llm).chat(anyString(), anyString());
        Nl2SqlResult r = service.generate("查客户号");
        assertEquals("ERROR", r.getValidationStatus());
        assertTrue(r.getErrorMessage().contains("网络超时"));
    }
}