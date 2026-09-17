package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表信息实体
 */
@Data
@TableName("report_info")
public class ReportInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件名(唯一) */
    private String file;

    /** 报表名(去扩展名) */
    private String name;

    /** 类型: cpt/frm/xml */
    private String type;

    /** 描述 */
    private String description;

    /** 报表文件完整路径 */
    private String filePath;

    /** 解析时间 */
    private LocalDateTime parseTime;

    /** 数据集数 */
    private Integer datasetCount;

    /** 单元格绑定数 */
    private Integer cellCount;

    /** 解析状态: SUCCESS/PARTIAL/FAILED */
    private String parseStatus;

    /** 入库时间 */
    private LocalDateTime createTime;
}
