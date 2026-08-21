package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 块 ON/WHERE 条件字段（行血缘，独立于 FieldMapping 值输入）
 */
@Data
public class ConditionField {
    /** 归属解析后的源表短名；null=未归属（落库保留、Neo4j 同步跳过） */
    private String sourceTableShort;
    /** 条件字段别名（大写；可为 null） */
    private String sourceAlias;
    /** 条件字段名（小写） */
    private String sourceField;
    /** join_condition / where_condition */
    private String role;
    /** 条件原文（同键多条件分号拼接，截断 512 字符，仅展示用） */
    private String clause;
}
