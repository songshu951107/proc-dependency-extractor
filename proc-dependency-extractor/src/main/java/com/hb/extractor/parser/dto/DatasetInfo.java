package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 数据集信息（XML解析输出）
 */
@Data
public class DatasetInfo {
    /** 数据集名(ds1) */
    private String name;
    /** 数据源连接名 */
    private String connection;
    /** SQL查询语句 */
    private String sql;
}
