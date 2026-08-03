-- ============================================
-- 存储过程依赖表名提取系统 - 数据库初始化脚本
-- ============================================

-- 数据库创建（按需调整库名）
CREATE DATABASE IF NOT EXISTS proc_extractor DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE proc_extractor;

-- --------------------------------------------
-- 表1：存储过程信息表
-- --------------------------------------------
DROP TABLE IF EXISTS `proc_info`;
CREATE TABLE `proc_info` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_name`        VARCHAR(200) NOT NULL COMMENT '存储过程名(文件名去.py)',
    `proc_file_name`   VARCHAR(200) NOT NULL COMMENT '文件名',
    `file_path`        VARCHAR(500) NOT NULL COMMENT '完整路径',
    `proc_desc`        VARCHAR(500)          DEFAULT NULL COMMENT '功能说明',
    `update_target`    VARCHAR(200)          DEFAULT NULL COMMENT '更新对象(头部#更新对象,如CDM.C_AGT_DEPOSIT_ACCT_INF)',
    `table_count`      INT          NOT NULL DEFAULT 0  COMMENT '真实依赖表数量(去重,不含临时表,不含更新目标表)',
    `temp_table_count` INT          NOT NULL DEFAULT 0  COMMENT '临时表数量(VT_前缀)',
    `extract_time`     DATETIME     NOT NULL COMMENT '提取时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proc_name` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储过程信息表';

-- --------------------------------------------
-- 表2：存储过程-依赖表关系表
-- --------------------------------------------
DROP TABLE IF EXISTS `proc_table_dependency`;
CREATE TABLE `proc_table_dependency` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_id`         BIGINT       NOT NULL COMMENT '关联proc_info.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `schema_name`     VARCHAR(50)           DEFAULT NULL COMMENT 'schema变量名(CDM/FDM/ODM/PID等)',
    `schema_var_src`  VARCHAR(100)          DEFAULT NULL COMMENT 'schema来源(如etl.dwa_db_cdm)',
    `table_name`      VARCHAR(200) NOT NULL COMMENT '表名',
    `full_table_name` VARCHAR(300) NOT NULL COMMENT '完整表名(schema.table)',
    `operation_type`  VARCHAR(30)           DEFAULT NULL COMMENT '操作类型:FROM/JOIN/INSERT INTO/DELETE FROM/UPDATE/MERGE INTO',
    `is_temp_table`   TINYINT      NOT NULL DEFAULT 0  COMMENT '是否临时表(VT_前缀):0否1是',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_proc_id` (`proc_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_table_name` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储过程依赖表关系表';

-- --------------------------------------------
-- 表3：表间派生血缘关系表
-- --------------------------------------------
DROP TABLE IF EXISTS `table_lineage`;
CREATE TABLE `table_lineage` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_id`         BIGINT       NOT NULL COMMENT '关联proc_info.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `source_table`    VARCHAR(300) NOT NULL COMMENT '派生源表(完整名schema.table)',
    `source_table_short` VARCHAR(200) NOT NULL COMMENT '源表短名(去schema)',
    `target_table`    VARCHAR(300) NOT NULL COMMENT '派生目标表(完整名)',
    `target_table_short` VARCHAR(200) NOT NULL COMMENT '目标表短名',
    `target_is_temp`  TINYINT      NOT NULL DEFAULT 0 COMMENT '目标是否临时表(VT_前缀):0否1是',
    `operation`       VARCHAR(30)  NOT NULL COMMENT '派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM',
    `sql_block_seq`   INT          NOT NULL COMMENT 'SQL块序号(从1开始,标识派生顺序)',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_proc_id` (`proc_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_source` (`source_table_short`),
    KEY `idx_target` (`target_table_short`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表间派生血缘关系';

-- --------------------------------------------
-- 表4：血缘条件详情表(JOIN/WHERE)
-- --------------------------------------------
DROP TABLE IF EXISTS `table_lineage_filter`;
CREATE TABLE `table_lineage_filter` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `lineage_id`      BIGINT       NOT NULL COMMENT '关联table_lineage.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `filter_type`     VARCHAR(10)  NOT NULL COMMENT '条件类型:JOIN/WHERE',
    `filter_clause`   TEXT         NOT NULL COMMENT '条件原文(如a.cust_id=b.id)',
    `fields_involved` VARCHAR(500) DEFAULT NULL COMMENT '涉及字段JSON数组(如["a.cust_id","b.id"])',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘条件详情(JOIN/WHERE)';

-- --------------------------------------------
-- 表5：字段级血缘映射表
-- --------------------------------------------
DROP TABLE IF EXISTS `table_field_mapping`;
CREATE TABLE `table_field_mapping` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `lineage_id`          BIGINT       NOT NULL COMMENT '关联 table_lineage.id',
    `proc_name`           VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `target_field`        VARCHAR(200) NOT NULL COMMENT '目标表字段名',
    `field_order`         INT          NOT NULL COMMENT 'SELECT 列序号(从1开始)',
    `source_expr`         TEXT                  DEFAULT NULL COMMENT 'SELECT 表达式原文',
    `source_alias`        VARCHAR(100)          DEFAULT NULL COMMENT '表达式主别名(如 T01)',
    `source_table_short`  VARCHAR(200)          DEFAULT NULL COMMENT '推断的来源表短名',
    `extract_time`        DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_target_field` (`target_field`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段级血缘映射';

-- --------------------------------------------
-- 表6：SQL 块信息表
-- --------------------------------------------
DROP TABLE IF EXISTS `sql_block_info`;
CREATE TABLE `sql_block_info` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_name`     VARCHAR(200) NOT NULL COMMENT '存储过程名',
    `block_seq`     INT          NOT NULL COMMENT 'SQL 块序号',
    `block_type`    VARCHAR(50)           DEFAULT NULL COMMENT 'INSERT_SELECT / CREATE_TEMP / OTHER',
    `target_table`  VARCHAR(200)          DEFAULT NULL COMMENT '目标表短名',
    `main_table`    VARCHAR(200)          DEFAULT NULL COMMENT '主表(FROM 后第一个表)短名',
    `start_line`    INT                   DEFAULT NULL COMMENT '脚本中起始行号',
    `end_line`      INT                   DEFAULT NULL COMMENT '脚本中结束行号',
    `sql_content`   MEDIUMTEXT            DEFAULT NULL COMMENT 'SQL 块原文',
    `extract_time`  DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proc_block` (`proc_name`, `block_seq`),
    KEY `idx_proc_target` (`proc_name`, `target_table`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 块信息';

-- 报表影响分析相关表（详见 report_tables.sql）
SOURCE report_tables.sql;
