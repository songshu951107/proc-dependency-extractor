package com.hb.extractor.service;

import com.hb.extractor.dto.nl2sql.CandidateTable;
import java.util.List;

public interface SchemaLinkingService {
    /** 中文问句 → 候选表（含相关字段），上限 maxCandidateTables */
    List<CandidateTable> link(String question);
}