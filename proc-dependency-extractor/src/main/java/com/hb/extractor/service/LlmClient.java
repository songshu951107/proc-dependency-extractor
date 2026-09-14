package com.hb.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.dto.nl2sql.JoinHint;
import com.hb.extractor.nl2sql.ValueDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 数据日期（分区跑批日期）字段候选名单：与 SchemaLinkingServiceImpl.DATE_PARTITION_FIELDS 保持一致 */
    public static final Set<String> DATE_PARTITION_FIELDS = Set.of(
            "etl_tx_dt", "data_dt", "data_date", "stat_dt", "gather_dt");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private Nl2SqlProperties props;

    public LlmClient() {}

    public LlmClient(Nl2SqlProperties props) {
        this.props = props;
    }

    /** 调 LLM 返回原始文本内容 */
    public String chat(String systemPrompt, String userPrompt) {
        String apiKey = props.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 LLM API Key：请在启动时设置环境变量 NL2SQL_API_KEY=sk-...，或在 application.yml 的 nl2sql.api-key 配置");
        }
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException("未配置 LLM Base URL：请在 application.yml 的 nl2sql.base-url 配置");
        }
        HttpClient httpClient = HttpClient.newHttpClient()
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
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
                7. 数据日期（重要，必须遵守）：候选表若标注了 date_partition 字段（分区的跑批日期字段，如 etl_tx_dt/data_dt），生成的 SQL 必须用该字段做分区过滤，避免全表扫。若用户问题未给出具体日期，则取该表最新数据日期：WHERE <date_col> = (SELECT MAX(<date_col>) FROM <t>)，并在 assumptions 里注明"按该表最近一个数据日期查询"。若用户给了明确日期（含 N 月/N 日），用对应分区，不要取 MAX。
                8. 枚举码值（重要，必须遵守）：当 SQL 需要按某个枚举字段（如五级分类、状态码）过滤时，只允许使用"码值字典"里给出的 code（保留 code 本身，不要转换成别的值）或对应的业务 label 语义。码值字典里没有的枚举字段，不要猜测具体码值，改为在 assumptions 里说明"该字段枚举码值待人工确认（字典未收录）"。

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
            List<String> dateParts = new ArrayList<>();
            for (CandidateTable.FieldBrief f : t.getFields()) {
                Map<String, String> fm = new HashMap<>();
                fm.put("name", f.getColumnName());
                fm.put("type", f.getColumnType());
                fm.put("comment", f.getColumnComment());
                if (f.getColumnName() != null && DATE_PARTITION_FIELDS.contains(f.getColumnName().toLowerCase())) {
                    dateParts.add(f.getColumnName());
                }
                fields.add(fm);
            }
            m.put("fields", fields);
            m.put("date_partition", dateParts.isEmpty() ? null : dateParts);
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

        // 业务语义提示：问题含业务专有词时，明确告知表名含关键词的候选表即该业务主体表，引导优先选对表
        if (question != null && candidates != null) {
            String q = question.toLowerCase();
            for (Map.Entry<String, List<String>> b : ValueDictionary.businessTerms().entrySet()) {
                if (!q.contains(b.getKey().toLowerCase())) continue;
                for (CandidateTable t : candidates) {
                    if (t.getFullTableName() == null) continue;
                    String tn = t.getFullTableName().toLowerCase();
                    if (b.getValue().stream().anyMatch(kw -> !kw.isEmpty() && tn.contains(kw.toLowerCase()))) {
                        sb.append(String.format("\n业务语义提示：问题含业务词\"%s\"，候选表 %s 表名含 %s，属于\"%s\"业务主体表，应优先作为%s数据来源。",
                                b.getKey(), t.getFullTableName(),
                                b.getValue().stream().filter(kw -> tn.contains(kw.toLowerCase())).findFirst().orElse(""),
                                b.getKey(), b.getKey()));
                    }
                }
            }
        }

        // 码值字典：供 LLM 查询枚举字段的合法取值，避免臆造码值
        Map<String, String> dict = ValueDictionary.valueDict();
        if (dict != null && !dict.isEmpty()) {
            sb.append("\n\n码值字典（可信，字段语义名 -> 合法码值映射 code=含义）：\n");
            for (Map.Entry<String, String> e : dict.entrySet()) {
                sb.append("- ").append(e.getKey()).append(" : ").append(e.getValue()).append("\n");
            }
        }
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