-- ============================================
-- 物理表字段元数据 - 数据表
-- 数据来源：information_schema.columns 导出的 xlsx
-- ============================================
USE proc_extractor;

-- --------------------------------------------
-- 表1：物理表 DDL 信息表（每张表一行）
-- --------------------------------------------
DROP TABLE IF EXISTS `table_ddl_info`;
CREATE TABLE `table_ddl_info` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `schema_name`     VARCHAR(50)  NOT NULL COMMENT 'schema 名（小写，如 cdm/fdm/odm）',
    `table_name`      VARCHAR(200) NOT NULL COMMENT '表名（小写，不含 schema）',
    `full_table_name` VARCHAR(300) NOT NULL COMMENT '完整表名 schema.table（小写）',
    `column_count`    INT          NOT NULL DEFAULT 0 COMMENT '字段数',
    `fields_json`     MEDIUMTEXT            DEFAULT NULL COMMENT '字段名列表 JSON 数组（按 ORDINAL_POSITION 顺序）',
    `ddl_text`        MEDIUMTEXT            DEFAULT NULL COMMENT '生成的 CREATE TABLE 语句',
    `source_file`     VARCHAR(500)          DEFAULT NULL COMMENT '元数据来源文件',
    `parse_time`      DATETIME     NOT NULL COMMENT '解析时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_full_table` (`full_table_name`),
    KEY `idx_table_name` (`table_name`),
    KEY `idx_schema` (`schema_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物理表 DDL 信息';

-- --------------------------------------------
-- 表2：物理表字段元数据表（每个字段一行）
-- --------------------------------------------
DROP TABLE IF EXISTS `table_column_meta`;
CREATE TABLE `table_column_meta` (
    `id`                          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `ddl_info_id`                 BIGINT       NOT NULL COMMENT '关联 table_ddl_info.id',
    `schema_name`                 VARCHAR(50)  NOT NULL COMMENT 'schema 名（小写）',
    `table_name`                  VARCHAR(200) NOT NULL COMMENT '表名（小写）',
    `full_table_name`             VARCHAR(300) NOT NULL COMMENT '完整表名 schema.table（小写）',
    `column_name`                 VARCHAR(200) NOT NULL COMMENT '字段名（小写）',
    `ordinal_position`            INT          NOT NULL COMMENT '字段序号（从 1 开始）',
    `column_default`              TEXT                  DEFAULT NULL COMMENT '默认值',
    `is_nullable`                 VARCHAR(3)   NOT NULL DEFAULT 'NO' COMMENT '是否可空: YES/NO',
    `data_type`                   VARCHAR(50)           DEFAULT NULL COMMENT '数据类型（varchar/bigint）',
    `column_type`                 VARCHAR(200)          DEFAULT NULL COMMENT '完整列类型（varchar(64)）',
    `character_maximum_length`    INT                   DEFAULT NULL COMMENT '字符最大长度',
    `numeric_precision`           INT                   DEFAULT NULL COMMENT '数值精度',
    `numeric_scale`               INT                   DEFAULT NULL COMMENT '数值小数位',
    `character_set_name`          VARCHAR(50)           DEFAULT NULL COMMENT '字符集',
    `collation_name`              VARCHAR(50)           DEFAULT NULL COMMENT '排序规则',
    `column_key`                  VARCHAR(10)           DEFAULT NULL COMMENT '键类型: PRI/UNI/MUL',
    `extra`                       VARCHAR(100)          DEFAULT NULL COMMENT '额外信息（auto_increment）',
    `column_comment`              TEXT                  DEFAULT NULL COMMENT '字段注释',
    `create_time`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_full_table_col` (`full_table_name`, `column_name`),
    KEY `idx_ddl_info_id` (`ddl_info_id`),
    KEY `idx_table_name` (`table_name`),
    KEY `idx_full_table` (`full_table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物理表字段元数据';
