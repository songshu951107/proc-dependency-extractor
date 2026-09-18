package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 字段映射（SQL解析输出）
 */
@Data
public class ColumnMapping {
    /** select输出列名(如 STAT_DT / a2) */
    private String selectAlias;
    /** 源表达式(如 a.GL_NUM / trim(a.GL_NUM)) */
    private String sourceExpr;
    /** 源表全名(小写，如 rpt.a_cfin_dept_acct_info)，无法解析时为null */
    private String sourceTable;
    /** 源字段名(大写，如 GL_NUM)，无法解析时为null */
    private String sourceField;
    /** 报表表头-最小粒度列名(仅报表数据集下钻填充，其它场景为null) */
    private String reportHeader;
}
