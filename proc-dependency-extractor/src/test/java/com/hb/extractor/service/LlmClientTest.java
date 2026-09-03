package com.hb.extractor.service;

import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.dto.nl2sql.JoinHint;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {

    private final LlmClient client = new LlmClient(new Nl2SqlProperties());

    private CandidateTable table(String name, String... fieldComments) {
        CandidateTable t = new CandidateTable();
        t.setFullTableName(name);
        t.setSchemaName(name.split("\\.")[0]);
        t.setFields(new java.util.ArrayList<>());
        for (int i = 0; i < fieldComments.length; i += 2) {
            CandidateTable.FieldBrief f = new CandidateTable.FieldBrief();
            f.setColumnName(fieldComments[i]);
            f.setColumnType("decimal(16,2)");
            f.setColumnComment(fieldComments[i + 1]);
            t.getFields().add(f);
        }
        return t;
    }

    @Test
    void 系统提示包含GBase方言约束() {
        String sys = client.buildSystemPrompt();
        assertTrue(sys.contains("GBase 8a"));
        assertTrue(sys.contains("DATE'"));
        assertTrue(sys.contains("EXCEPT"));
    }

    @Test
    void 用户提示包含候选表字段与JOIN提示() {
        JoinHint hint = new JoinHint();
        hint.setTableA("a.t1"); hint.setTableB("b.t2");
        hint.setRelation("COMMON_COLUMN"); hint.setJoinFields(List.of("cust_id"));
        String user = client.buildUserPrompt(
                List.of(table("a.t1", "cust_id", "客户号", "balance", "账户余额")),
                List.of(hint), "查客户余额");
        assertTrue(user.contains("a.t1"));
        assertTrue(user.contains("cust_id"));
        assertTrue(user.contains("客户号"));
        assertTrue(user.contains("COMMON_COLUMN"));
    }

    @Test
    void 解析LLM返回的JSON内容() {
        String content = "```json\n{\"sql\":\"SELECT 1\",\"tables_used\":[\"a.t1\"],\"join_notes\":[],\"assumptions\":[\"假设日期为昨天\"]}\n```";
        JsonNode node = client.extractJson(content);
        assertEquals("SELECT 1", node.get("sql").asText());
        assertEquals("a.t1", node.get("tables_used").get(0).asText());
        assertEquals("假设日期为昨天", node.get("assumptions").get(0).asText());
    }

    @Test
    void 无JSON包裹的纯文本报错() {
        assertThrows(RuntimeException.class, () -> client.extractJson("我不会写SQL"));
    }
}