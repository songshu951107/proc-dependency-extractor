package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 单元格绑定（XML解析输出）
 */
@Data
public class CellBinding {
    /** 单元格位置(A3) */
    private String cellPos;
    /** 数据集名(ds1) */
    private String dsName;
    /** 列名(STAT_DT) */
    private String columnName;
}
