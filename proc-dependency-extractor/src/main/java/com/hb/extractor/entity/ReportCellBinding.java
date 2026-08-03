package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表单元格绑定实体
 */
@Data
@TableName("report_cell_binding")
public class ReportCellBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属报表文件 */
    private String reportFile;

    /** 数据集名 */
    private String datasetName;

    /** 单元格位置(A3) */
    private String cellPos;

    /** 数据集列名(STAT_DT) */
    private String columnName;

    /** SQL select别名 */
    private String selectAlias;

    /** 源表全名(小写) */
    private String sourceTable;

    /** 源字段名 */
    private String sourceField;

    /** 源表达式原文 */
    private String sourceExpr;

    /** 创建时间 */
    private LocalDateTime createTime;
}
