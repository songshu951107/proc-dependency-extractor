package com.hb.extractor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.nl2sql.Nl2SqlResult;
import com.hb.extractor.service.Nl2SqlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

/**
 * NL2SQL 智能查数 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/nl2sql")
public class Nl2SqlController {

    @Autowired
    private Nl2SqlService nl2SqlService;
    @Autowired
    private Nl2SqlProperties props;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成 SQL
     * POST /api/nl2sql/generate  body: {"question": "..."}
     */
    @PostMapping("/generate")
    public Result<Nl2SqlResult> generate(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return Result.error("question 不能为空");
        }
        return Result.success(nl2SqlService.generate(question));
    }

    /**
     * 题集跑批评估
     * POST /api/nl2sql/eval
     * 输出：总题数 / 选表正确率 / 语法通过率 / 整体正确率 / 明细
     */
    @PostMapping("/eval")
    public Result<Map<String, Object>> eval() throws Exception {
        File file = new File(props.getEvalFile());
        if (!file.exists()) {
            return Result.error("题集文件不存在: " + props.getEvalFile());
        }
        JsonNode questions = objectMapper.readTree(file).get("questions");
        int total = 0, tableCorrect = 0, syntaxPass = 0, overall = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (JsonNode q : questions) {
            total++;
            String question = q.get("question").asText();
            Set<String> expected = new HashSet<>();
            q.get("expected_tables").forEach(t -> expected.add(t.asText().toLowerCase()));
            Nl2SqlResult r = nl2SqlService.generate(question);

            Set<String> used = new HashSet<>();
            if (r.getTablesUsed() != null) r.getTablesUsed().forEach(t -> used.add(t.toLowerCase()));
            boolean tablesOk = !used.isEmpty() && expected.containsAll(used) && used.containsAll(expected);
            boolean syntaxOk = "VALIDATED".equals(r.getValidationStatus());
            if (tablesOk) tableCorrect++;
            if (syntaxOk) syntaxPass++;
            if (tablesOk && syntaxOk) overall++;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("question", question);
            d.put("expectedTables", expected);
            d.put("usedTables", used);
            d.put("tablesCorrect", tablesOk);
            d.put("syntaxValid", syntaxOk);
            d.put("status", r.getValidationStatus());
            d.put("sql", r.getSql());
            d.put("error", r.getErrorMessage());
            details.add(d);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("total", total);
        report.put("tableAccuracy", total == 0 ? 0 : (double) tableCorrect / total);
        report.put("syntaxPassRate", total == 0 ? 0 : (double) syntaxPass / total);
        report.put("overallAccuracy", total == 0 ? 0 : (double) overall / total);
        report.put("details", details);
        return Result.success(report);
    }
}