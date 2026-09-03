package com.hb.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.dto.nl2sql.JoinHint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 客户端：OpenAI 兼容 chat/completions + prompt 组装。
 * prompt 组装与响应解析为纯函数（可单测）；HTTP 调用经 #chat。
 */
@Slf4j
@Service
public class LlmClient {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private Nl2SqlProperties props;

    public LlmClient() {}

    public LlmClient(Nl2SqlProperties props) {
        this.props = props;
    }

    /** 调 LLM 返回原始文本内容 */
    public String chat(String systemPrompt, String userPrompt) {
        RestClient restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", props.getTemperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("response_format", Map.of("type", "json_object"));
        Map<?, ?> resp = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + props.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (resp == null) throw new RuntimeException("LLM 返回空响应");
        List<?> choices = (List<?>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("LLM 返回无 choices");
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        return String.valueOf(message.get("content"));
    }

    /** 系统 prompt：GBase 8a 方言约束（沉淀自项目 SqlPlaceholderReplacer 经验） */
    public String buildSystemPrompt() {
        return """
                你是 GBase 8a 数据库的 SQL 专家。根据用户问题和候选表结构生成查询 SQL。

                方言约束（必须遵守）：
                1. 目标方言是 GBase 8a（兼容大部分 MySQL 语法），不要使用 GBase 不支持的语法。
                2. 日期字面量写 DATE'YYYY-MM-DD' 形式（无空格），不要用 STR_TO_DATE。
                3. 集合操作仅支持 UNION [ALL] / EXCEPT [ALL] / INTERSECT [ALL]。
                4. 分页用 LIMIT n OFFSET m。
                5. 只允许使用候选表清单中列出的表和字段，禁止猜测不存在的字段。
                6. schema 层级提示：cdm/fdm/odm 为明细层，dwa/pid 为汇总层（优先选汇总层减少数据量，除非问题明确要明细）。

                输出严格的 JSON 对象，格式：
                {"sql": "生成的SQL", "tables_used": ["用到的表"], "join_notes": ["JOIN说明"], "assumptions": ["你做的假设"]}
                """;
    }

    /** 用户 prompt：候选 schema + JOIN 提示 + 问句 */
    public String buildUserPrompt(List<CandidateTable> candidates, List<JoinHint> joinHints, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选表结构（JSON）：\n");
        List<Map<String, Object>> schema = new ArrayList<>();
        for (CandidateTable t : candidates) {
            Map<String, Object> m = new HashMap<>();
            m.put("table", t.getFullTableName());
            m.put("schema", t.getSchemaName());
            List<Map<String, String>> fields = new ArrayList<>();
            for (CandidateTable.FieldBrief f : t.getFields()) {
                Map<String, String> fm = new HashMap<>();
                fm.put("name", f.getColumnName());
                fm.put("type", f.getColumnType());
                fm.put("comment", f.getColumnComment());
                fields.add(fm);
            }
            m.put("fields", fields);
            schema.add(m);
        }
        sb.append(toJson(schema)).append("\n\n");
        if (joinHints != null && !joinHints.isEmpty()) {
            sb.append("表间血缘关联提示（来自血缘分析，可信）：\n");
            for (JoinHint h : joinHints) {
                if ("COMMON_COLUMN".equals(h.getRelation())) {
                    sb.append(String.format("- %s 与 %s 存在共同字段 %s（关系：%s），可作为 JOIN 条件%n",
                            h.getTableA(), h.getTableB(), h.getJoinFields(), h.getRelation()));
                } else {
                    sb.append(String.format("- %s 与 %s 曾在同一存储过程 %s 中共同出现（血缘关联）%n",
                            h.getTableA(), h.getTableB(), h.getProcName()));
                }
            }
            sb.append("\n");
        }
        sb.append("用户问题：").append(question);
        return sb.toString();
    }

    /** 从 LLM 回复中提取 JSON（兼容 ```json 代码块包裹） */
    public JsonNode extractJson(String content) {
        String json = content;
        Matcher m = JSON_BLOCK.matcher(content);
        if (m.find()) json = m.group(1).trim();
        json = json.trim();
        if (!json.startsWith("{")) {
            throw new RuntimeException("LLM 返回内容不是 JSON：" + content.substring(0, Math.min(100, content.length())));
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("LLM 返回 JSON 解析失败：" + e.getMessage());
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}