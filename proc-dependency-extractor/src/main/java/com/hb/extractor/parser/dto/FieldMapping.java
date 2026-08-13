package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段级映射 DTO（解析期）
 */
@Data
public class FieldMapping {
    /** 关联 edges 下标，入库时转 lineage_id */
    private int edgeIndex;
    /** 目标表字段名 */
    private String targetField;
    /** SELECT 列序号(从1开始) */
    private int fieldOrder;
    /** SELECT 表达式原文 */
    private String sourceExpr;
    /** 表达式主别名(如 T01) */
    private String sourceAlias;
    /** 推断的来源表短名 */
    private String sourceTableShort;

    /** Transform 输入字段列表（拆分 sourceExpr 得到） */
    private List<TransformInput> inputs = new ArrayList<>();

    /** logic_type 分类（解析期填充，同步期写入 Transform 节点） */
    private String logicType;
}
