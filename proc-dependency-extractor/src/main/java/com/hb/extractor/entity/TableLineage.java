package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表间派生血缘关系实体
 */
@Data
@TableName("table_lineage")
public class TableLineage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联proc_info.id */
    private Long procId;

    /** 存储过程名(冗余) */
    private String procName;

    /** 派生源表(完整名schema.table) */
    private String sourceTable;

    /** 源表短名(去schema) */
    private String sourceTableShort;

    /** 派生目标表(完整名) */
    private String targetTable;

    /** 目标表短名 */
    private String targetTableShort;

    /** 目标是否临时表(VT_前缀):0否1是 */
    private Integer targetIsTemp;

    /** 派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM */
    private String operation;

    /** SQL块序号(从1开始) */
    private Integer sqlBlockSeq;

    /** 主表短名(FROM 后第一个表) */
    private String mainTable;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
