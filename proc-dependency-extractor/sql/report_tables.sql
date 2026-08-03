-- ============================================
-- 报表影响分析 - 数据表
-- ============================================
USE proc_extractor;

-- --------------------------------------------
-- 表1：报表信息表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_info`;
CREATE TABLE `report_info` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file`          VARCHAR(255) NOT NULL COMMENT '文件名(唯一)',
    `name`          VARCHAR(255) NOT NULL COMMENT '报表名(去扩展名)',
    `type`          VARCHAR(20)  NOT NULL COMMENT '类型: cpt/frm/xml',
    `description`   VARCHAR(500)          DEFAULT NULL COMMENT '描述',
    `parse_time`    DATETIME     NOT NULL COMMENT '解析时间',
    `dataset_count` INT          NOT NULL DEFAULT 0  COMMENT '数据集数',
    `cell_count`    INT          NOT NULL DEFAULT 0  COMMENT '单元格绑定数',
    `parse_status`  VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态: SUCCESS/PARTIAL/FAILED',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file` (`file`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表信息表';

-- --------------------------------------------
-- 表2：报表数据集表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_dataset`;
CREATE TABLE `report_dataset` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `report_file` VARCHAR(255) NOT NULL COMMENT '所属报表文件',
    `dataset_name` VARCHAR(100) NOT NULL COMMENT '数据集名(ds1)',
    `connection`  VARCHAR(255)          DEFAULT NULL COMMENT '数据源连接名',
    `sql_text`    TEXT                   DEFAULT NULL COMMENT 'SQL语句',
    `table_count` INT          NOT NULL DEFAULT 0  COMMENT '涉及表数',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_dataset` (`report_file`, `dataset_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表数据集表';

-- --------------------------------------------
-- 表3：报表单元格绑定表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_cell_binding`;
CREATE TABLE `report_cell_binding` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `report_file`  VARCHAR(255) NOT NULL COMMENT '所属报表文件',
    `dataset_name` VARCHAR(100) NOT NULL COMMENT '数据集名',
    `cell_pos`     VARCHAR(20)  NOT NULL COMMENT '单元格位置(A3)',
    `column_name`  VARCHAR(100) NOT NULL COMMENT '数据集列名(STAT_DT)',
    `select_alias` VARCHAR(100)          DEFAULT NULL COMMENT 'SQL select别名',
    `source_table` VARCHAR(255)          DEFAULT NULL COMMENT '源表全名(小写)',
    `source_field` VARCHAR(100)          DEFAULT NULL COMMENT '源字段名',
    `source_expr`  TEXT                   DEFAULT NULL COMMENT '源表达式原文',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_report_dataset` (`report_file`, `dataset_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表单元格绑定表';
