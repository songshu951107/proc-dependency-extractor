package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物理表字段元数据（每个字段一行）
 * 由 information_schema.columns 风格的 xlsx 导入生成
 */
@Data
@TableName("table_column_meta")
public class TableColumnMeta {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 table_ddl_info.id */
    private Long ddlInfoId;
    private String schemaName;
    private String tableName;
    private String fullTableName;
    private String columnName;
    /** 字段序号（从 1 开始） */
    private Integer ordinalPosition;
    private String columnDefault;
    /** YES / NO */
    private String isNullable;
    private String dataType;
    /** 完整列类型，如 varchar(64) */
    private String columnType;
    private Integer characterMaximumLength;
    private Integer numericPrecision;
    private Integer numericScale;
    private String characterSetName;
    private String collationName;
    /** PRI / UNI / MUL */
    private String columnKey;
    private String extra;
    private String columnComment;
    private LocalDateTime createTime;
}
