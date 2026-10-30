package com.hb.extractor.dto;

import lombok.Data;

/** 脚本转换列表行 VO（状态机见规格 §6） */
@Data
public class TransformScriptVO {
    private String name;          // 脚本名（含 .py）
    private String status;        // PENDING / CONVERTED / FAILED / STALE
    private Integer totalEdits;
    private Integer residueCount;
    private String pyCompile;     // PASS / FAIL / null(未转换)
    private String convertedAt;   // report.json converted_at
    private String errorMsg;      // 失败摘要
    private String oldTable;      // 映射快照（F 表）
    private String newTable;      // 映射快照（O 表）
}
