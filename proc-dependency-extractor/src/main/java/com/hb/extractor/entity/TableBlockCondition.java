package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL块条件字段归属实体(行血缘同步源)
 */
@Data
@TableName("table_block_condition")
public class TableBlockCondition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 table_lineage.id */
    private Long lineageId;

    /** 存储过程名(冗余) */
    private String procName;

    /** SQL块序号 */
    private Integer sqlBlockSeq;

    /** 别名解析后的源表短名，null=未归属(跳过同步) */
    private String sourceTableShort;

    /** 条件字段别名 */
    private String sourceAlias;

    /** 条件字段名 */
    private String sourceField;

    /** join_condition / where_condition */
    private String role;

    /** 条件原文(截断) */
    private String clause;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
