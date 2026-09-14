package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物理表 DDL 信息（每张表一行）
 * 由 information_schema.columns 风格的 xlsx 导入生成
 */
@Data
@TableName("table_ddl_info")
public class TableDdlInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** schema 名（小写，如 cdm/fdm/odm） */
    private String schemaName;
    /** 表名（小写，不含 schema） */
    private String tableName;
    /** 完整表名 schema.table（小写） */
    private String fullTableName;
    /** 字段数 */
    private Integer columnCount;
    /** 字段名列表 JSON 数组（按 ORDINAL_POSITION 顺序） */
    private String fieldsJson;
    /** 生成的 CREATE TABLE 语句 */
    private String ddlText;
    /** 元数据来源文件 */
    private String sourceFile;
    private LocalDateTime parseTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
