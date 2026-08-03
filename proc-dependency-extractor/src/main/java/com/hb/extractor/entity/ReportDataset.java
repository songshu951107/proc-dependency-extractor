package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表数据集实体
 */
@Data
@TableName("report_dataset")
public class ReportDataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属报表文件 */
    private String reportFile;

    /** 数据集名(ds1) */
    private String datasetName;

    /** 数据源连接名 */
    private String connection;

    /** SQL语句 */
    private String sqlText;

    /** 涉及表数 */
    private Integer tableCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
