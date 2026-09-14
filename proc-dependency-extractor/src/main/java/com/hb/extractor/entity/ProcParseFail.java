package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 块解析失败清单实体
 */
@Data
@TableName("proc_parse_fail")
public class ProcParseFail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储过程名 */
    private String procName;

    /** SQL 块序号 */
    private Integer blockSeq;

    /** 块类型 CREATE_TEMP / INSERT_SELECT / OTHER */
    private String blockType;

    /** 失败原因 */
    private String failReason;

    /** SQL 预览(截断) */
    private String sqlPreview;

    /** 是否已处理 0未处理 1已处理 */
    private Integer resolved;

    /** 记录时间 */
    private LocalDateTime createdAt;
}