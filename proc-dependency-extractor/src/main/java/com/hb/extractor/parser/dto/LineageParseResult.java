package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 血缘解析结果
 */
@Data
public class LineageParseResult {
    private List<LineageEdge> edges = new ArrayList<>();
    private List<LineageFilter> filters = new ArrayList<>();
    /** 字段级映射 */
    private List<FieldMapping> fieldMappings = new ArrayList<>();
    /** SQL 块信息（含行号） */
    private List<SqlBlockInfoDTO> sqlBlocks = new ArrayList<>();
}
