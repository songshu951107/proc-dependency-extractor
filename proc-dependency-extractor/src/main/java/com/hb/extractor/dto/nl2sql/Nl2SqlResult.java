package com.hb.extractor.dto.nl2sql;

import lombok.Data;
import java.util.List;

/** NL2SQL 生成结果 */
@Data
public class Nl2SqlResult {
    private String question;
    private String sql;
    /** LLM 声明使用的表 */
    private List<String> tablesUsed;
    private List<String> joinNotes;
    /** LLM 声明的假设（前端展示） */
    private List<String> assumptions;
    /** 血缘依据（JoinPathService 产出） */
    private List<JoinHint> lineageEvidence;
    /** 召回候选表（前端展示，帮助用户判断） */
    private List<CandidateTable> candidates;
    /** VALIDATED（语法通过）/ FAILED（重试后仍失败）/ ERROR（LLM 调用异常） */
    private String validationStatus;
    private String errorMessage;
}