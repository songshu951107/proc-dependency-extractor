package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * Transform 输入字段 DTO（解析期）
 * 一个 FieldMapping 可对应多个 TransformInput（多源表达式拆分结果）
 */
@Data
public class TransformInput {
    /** 输入字段来源表短名（推断自 aliasMap） */
    private String sourceTableShort;
    /** 输入字段名（alias.field 的 field 部分） */
    private String sourceField;
    /** 表达式中该字段的别名（如 T01） */
    private String sourceAlias;
    /** 输入角色: value=取值字段 / condition=条件字段 */
    private String role;
    /** 在表达式中出现顺序（从1开始，按出现位置） */
    private int inputOrder;
}
