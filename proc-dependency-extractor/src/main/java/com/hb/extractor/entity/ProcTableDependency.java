package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储过程依赖表关系实体
 */
@Data
@TableName("proc_table_dependency")
public class ProcTableDependency {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联proc_info.id */
    private Long procId;

    /** 存储过程名(冗余) */
    private String procName;

    /** schema变量名(CDM/FDM/ODM/PID等) */
    private String schemaName;

    /** schema来源(如etl.dwa_db_cdm) */
    private String schemaVarSrc;

    /** 表名 */
    private String tableName;

    /** 完整表名(schema.table) */
    private String fullTableName;

    /** 操作类型:FROM/JOIN/INSERT INTO/DELETE FROM/UPDATE/MERGE INTO */
    private String operationType;

    /** 是否临时表(VT_前缀):0否1是 */
    private Integer isTempTable;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
