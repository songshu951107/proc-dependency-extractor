package com.hb.extractor.dto.nl2sql;

import lombok.Data;
import java.util.List;

/** 召回候选表（schema linking 结果） */
@Data
public class CandidateTable {
    private String fullTableName;   // schema.table（小写）
    private String schemaName;
    /** 与问句相关的字段（已裁剪） */
    private List<FieldBrief> fields;
    /** 召回来源：COMMENT / COLUMN_NAME / TABLE_NAME / LINEAGE */
    private String source;
    /** 召回得分（注释命中 3 > 字段名 2 > 表名 1 > 血缘 2） */
    private double score;

    @Data
    public static class FieldBrief {
        private String columnName;
        private String columnType;
        private String columnComment;
    }
}