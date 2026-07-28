package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 块信息实体
 */
@Data
@TableName("sql_block_info")
public class SqlBlockInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储过程名 */
    private String procName;

    /** SQL 块序号 */
    private Integer blockSeq;

    /** INSERT_SELECT / CREATE_TEMP / OTHER */
    private String blockType;

    /** 目标表短名 */
    private String targetTable;

    /** 主表(FROM 后第一个表)短名 */
    private String mainTable;

    /** 脚本中起始行号 */
    private Integer startLine;

    /** 脚本中结束行号 */
    private Integer endLine;

    /** SQL 块原文 */
    private String sqlContent;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
