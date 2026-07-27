package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 血缘条件详情实体(JOIN/WHERE)
 */
@Data
@TableName("table_lineage_filter")
public class TableLineageFilter {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联table_lineage.id */
    private Long lineageId;

    /** 存储过程名(冗余) */
    private String procName;

    /** 条件类型:JOIN/WHERE */
    private String filterType;

    /** 条件原文(如a.cust_id=b.id) */
    private String filterClause;

    /** 涉及字段JSON数组(如["a.cust_id","b.id"]) */
    private String fieldsInvolved;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
