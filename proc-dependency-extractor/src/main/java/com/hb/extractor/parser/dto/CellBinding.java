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
    /** 报表表头文本(商户号) - 同列上方最近的文本单元格 */
    private String headerText;
}
