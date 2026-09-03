package com.hb.extractor.dto.nl2sql;

import lombok.Data;
import java.util.List;

/** 血缘 JOIN 提示：两表间存在血缘关联路径 */
@Data
public class JoinHint {
    private String tableA;
    private String tableB;
    /** 关联方式：DIRECT_EDGE（直接血缘边）/ COMMON_UPSTREAM（1-2 跳共同上游） */
    private String relation;
    /** 关联字段（同一 Column 节点名） */
    private List<String> joinFields;
    /** 血缘依据 proc */
    private String procName;
}