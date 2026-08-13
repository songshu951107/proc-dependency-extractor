package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段级血缘映射实体
 */
@Data
@TableName("table_field_mapping")
public class TableFieldMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 table_lineage.id */
    private Long lineageId;

    /** 存储过程名(冗余) */
    private String procName;

    /** 目标表字段名 */
    private String targetField;

    /** SELECT 列序号(从1开始) */
    private Integer fieldOrder;

    /** SELECT 表达式原文 */
    private String sourceExpr;

    /** 表达式主别名(如 T01) */
    private String sourceAlias;

    /** 推断的来源表短名 */
    private String sourceTableShort;

    /** Transform logic_type(解析期填充,同步期写入 Transform 节点) */
    private String logicType;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
