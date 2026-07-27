package com.hb.extractor.parser.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 解析结果 DTO
 */
@Data
public class ProcMetadata {

    /** 存储过程名(文件名去.py) */
    private String procName;

    /** 文件名 */
    private String procFileName;

    /** 完整路径 */
    private String filePath;

    /** 功能说明 */
    private String procDesc;

    /** 更新对象原始字符串(如CDM.C_AGT_DEPOSIT_ACCT_INF) */
    private String updateTarget;

    /** 更新对象的schema变量名(如CDM) */
    private String updateTargetSchema;

    /** 更新对象的表名(如C_AGT_DEPOSIT_ACCT_INF) */
    private String updateTargetTable;

    /** schema变量映射: key=变量名(CDM), value=来源(etl.dwa_db_cdm) */
    private java.util.Map<String, String> schemaVarMap = new java.util.LinkedHashMap<>();

    /** 依赖表集合(去重,不含临时表,不含更新目标表) */
    private Set<TableRef> dependencies = new LinkedHashSet<>();

    /** 临时表集合(去重) */
    private Set<TableRef> tempTables = new LinkedHashSet<>();

    /**
     * 从 SQL 推断出的更新目标表集合(INSERT INTO/DELETE FROM/MERGE INTO/UPDATE 的目标表)
     * key=大写完整表名(SCHEMA.TABLE), value=TableRef
     * 用于无头部注释时识别更新目标,避免写入型操作的目标表被误计入依赖表
     */
    private java.util.Map<String, TableRef> inferredUpdateTargets = new java.util.LinkedHashMap<>();

    /** 提取时间 */
    private LocalDateTime extractTime;

    /**
     * 表引用记录
     */
    @Data
    public static class TableRef {
        /** schema变量名(CDM/FDM/ODM/PID等) */
        private String schemaName;
        /** schema来源(如etl.dwa_db_cdm) */
        private String schemaVarSrc;
        /** 表名 */
        private String tableName;
        /** 完整表名(schema.table) */
        private String fullTableName;
        /** 操作类型 */
        private String operationType;
        /** 是否临时表 */
        private boolean tempTable;

        public TableRef() {
        }

        public TableRef(String schemaName, String tableName, String operationType) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.operationType = operationType;
            this.fullTableName = (schemaName == null || schemaName.isEmpty())
                    ? tableName : schemaName + "." + tableName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TableRef tableRef)) return false;
            String s1 = schemaName == null ? "" : schemaName.toUpperCase();
            String s2 = tableRef.schemaName == null ? "" : tableRef.schemaName.toUpperCase();
            String t1 = tableName == null ? "" : tableName.toUpperCase();
            String t2 = tableRef.tableName == null ? "" : tableRef.tableName.toUpperCase();
            return s1.equals(s2) && t1.equals(t2);
        }

        @Override
        public int hashCode() {
            String s = schemaName == null ? "" : schemaName.toUpperCase();
            String t = tableName == null ? "" : tableName.toUpperCase();
            return (s + "." + t).hashCode();
        }
    }
}
