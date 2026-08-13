package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Transform 输入字段实体
 * 一个 FieldMapping 可对应多个 TransformInput（多源表达式拆分结果）
 */
@Data
@TableName("table_transform_input")
public class TableTransformInput {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fieldMappingId;
    private Long lineageId;
    private String procName;
    private String targetField;
    private String sourceTableShort;
    private String sourceField;
    private String sourceAlias;
    private String role;
    private Integer inputOrder;
    private LocalDateTime extractTime;
}
