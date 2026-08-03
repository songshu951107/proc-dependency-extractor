package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 表引用（SQL解析输出）
 */
@Data
public class TableRef {
    /** schema名(大写，如 RPT) */
    private String schema;
    /** 表名(大写，如 A_CFIN_DEPT_ACCT_INFO) */
    private String table;
    /** SQL中的别名(如 a) */
    private String alias;

    /** 完整表名 schema.table（小写） */
    public String getFullName() {
        return (schema + "." + table).toLowerCase();
    }
}
