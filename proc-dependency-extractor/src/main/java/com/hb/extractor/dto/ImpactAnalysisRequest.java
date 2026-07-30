package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ImpactAnalysisRequest {

    /** schema 名，可选 */
    private String schema;

    /** 表短名，必填 */
    private String tableShortName;

    /** 字段名，可选（填了则字段级分析） */
    private String fieldName;

    /** UPSTREAM / DOWNSTREAM / BOTH */
    private String direction = "DOWNSTREAM";

    /** 深度，默认 5，上限 20 */
    private Integer maxDepth = 5;

    /** 操作类型筛选 */
    private List<String> operations;

    /** 是否返回 JOIN/WHERE 条件，默认 true */
    private Boolean includeConditions = true;

    /** 是否返回字段级映射，默认 false */
    private Boolean includeFieldMapping = false;
}
