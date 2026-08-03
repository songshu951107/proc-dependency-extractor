package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表元数据（XML解析输出）
 */
@Data
public class ReportMetadata {
    /** 报表名(去扩展名) */
    private String name;
    /** 文件类型: cpt/frm/xml */
    private String type;
    /** 数据集列表 */
    private List<DatasetInfo> datasets = new ArrayList<>();
    /** 单元格绑定列表 */
    private List<CellBinding> cellBindings = new ArrayList<>();
}
