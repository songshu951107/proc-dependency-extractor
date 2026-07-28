package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * 脚本内容 VO
 */
@Data
public class ScriptVO {
    private String procName;
    private String fileName;
    private String content;
    private List<SqlBlockRange> sqlBlocks;

    @Data
    public static class SqlBlockRange {
        private Integer seq;
        private Integer startLine;
        private Integer endLine;
    }
}
