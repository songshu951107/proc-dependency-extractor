package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.List;

/**
 * 血缘条件（JOIN/WHERE）
 */
@Data
public class LineageFilter {
    /** 关联到 edges 的下标 */
    private int edgeIndex;
    /** 条件类型:JOIN/WHERE */
    private String filterType;
    /** 条件原文 */
    private String filterClause;
    /** 涉及字段列表 */
    private List<String> fieldsInvolved;
}
