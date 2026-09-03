package com.hb.extractor.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.dto.nl2sql.JoinHint;
import com.hb.extractor.dto.nl2sql.Nl2SqlResult;
import com.hb.extractor.service.JoinPathService;
import com.hb.extractor.service.LlmClient;
import com.hb.extractor.service.Nl2SqlService;
import com.hb.extractor.service.SchemaLinkingService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
public class Nl2SqlServiceImpl implements Nl2SqlService {

    @Autowired
    private SchemaLinkingService schemaLinkingService;
    @Autowired
    private JoinPathService joinPathService;
    @Autowired
    private LlmClient llmClient;
    @Autowired
    private Nl2SqlProperties props;

    public Nl2SqlServiceImpl() {}

    public Nl2SqlServiceImpl(SchemaLinkingService linking, JoinPathService joinPath,
                             LlmClient llm, Nl2SqlProperties props) {
        this.schemaLinkingService = linking;
        this.joinPathService = joinPath;
        this.llmClient = llm;
        this.props = props;
    }

    @Override
    public Nl2SqlResult generate(String question) {
        Nl2SqlResult result = new Nl2SqlResult();
        result.setQuestion(question);

        // ① 召回
        List<CandidateTable> candidates = schemaLinkingService.link(question);
        result.setCandidates(candidates);
        if (candidates.isEmpty()) {
            result.setValidationStatus("ERROR");
            result.setErrorMessage("未匹配到任何表：请换更贴近字段注释/表名的措辞重试");
            return result;
        }

        // ② JOIN 提示
        List<JoinHint> hints = joinPathService.findJoinPaths(
                candidates.stream().map(CandidateTable::getFullTableName).collect(Collectors.toList()));
        result.setLineageEvidence(hints);

        // ③④ 生成 + 校验重试
        String systemPrompt = llmClient.buildSystemPrompt();
        String userPrompt = llmClient.buildUserPrompt(candidates, hints, question);
        String lastError = null;
        String lastSql = null;
        boolean llmErrored = false;
        for (int attempt = 0; attempt <= props.getMaxRetries(); attempt++) {
            try {
                String prompt = (lastError == null) ? userPrompt
                        : userPrompt + "\n\n上一次生成的 SQL 语法校验失败：" + lastSql + "\n报错：" + lastError + "\n请修正。";
                String content = llmClient.chat(systemPrompt, prompt);
                JsonNode node = llmClient.extractJson(content);
                String sql = node.path("sql").asText();
                String err = validate(sql);
                if (err == null) {
                    result.setSql(sql);
                    result.setTablesUsed(toStringList(node.path("tables_used")));
                    result.setJoinNotes(toStringList(node.path("join_notes")));
                    result.setAssumptions(toStringList(node.path("assumptions")));
                    result.setValidationStatus("VALIDATED");
                    return result;
                }
                lastError = err;
                lastSql = sql;
            } catch (Exception e) {
                log.warn("NL2SQL 第 {} 次生成失败: {}", attempt + 1, e.getMessage());
                lastError = e.getMessage();
                llmErrored = true;
            }
        }
        result.setValidationStatus(llmErrored ? "ERROR" : "FAILED");
        result.setErrorMessage(lastError != null ? lastError : "生成失败");
        return result;
    }

    /** JsqlParser 语法校验；通过返回 null，失败返回错误信息 */
    String validate(String sql) {
        if (sql == null || sql.isBlank()) return "SQL 为空";
        try {
            CCJSqlParserUtil.parse(sql);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toList());
    }
}