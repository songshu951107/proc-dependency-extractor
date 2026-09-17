package com.hb.extractor.dto;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import lombok.Data;

import java.util.List;

/**
 * 报表数据集下钻详情
 */
@Data
public class ReportDatasetDetailVO {
    /** 数据集名 */
    private String datasetName;
    /** 连接数据库 */
    private String connection;
    /** 涉及表数 */
    private Integer tableCount;
    /** SQL 原文 */
    private String sqlText;
    /** 源表列表 */
    private List<TableRef> sourceTables;
    /** 字段映射 */
    private List<ColumnMapping> fieldMappings;
}