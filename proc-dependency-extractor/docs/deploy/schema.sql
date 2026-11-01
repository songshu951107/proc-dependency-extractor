-- =====================================================================
-- proc-dependency-extractor 内网初始化建表脚本（仅结构，无数据）
-- 由开发库 proc_extractor 导出，覆盖全部 17 张业务表。
-- 用途：内网新建空库后执行本脚本建表，再按需导入数据。
-- 使用：mysql -u<user> -p proc_extractor < schema.sql
-- =====================================================================

USE proc_extractor;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `neo4j_sync_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `neo4j_sync_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `proc_name` varchar(200) COLLATE utf8mb4_general_ci NOT NULL,
  `sync_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL,
  `error_message` text COLLATE utf8mb4_general_ci,
  `retry_count` int DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`sync_status`),
  KEY `idx_proc` (`proc_name`)
) ENGINE=InnoDB AUTO_INCREMENT=8500 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Neo4j 同步日志';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `neo4j_sync_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `neo4j_sync_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING',
  `total` int NOT NULL DEFAULT '0',
  `done` int NOT NULL DEFAULT '0',
  `success` int NOT NULL DEFAULT '0',
  `fail` int NOT NULL DEFAULT '0',
  `current_proc` varchar(255) DEFAULT NULL,
  `error` varchar(1024) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Neo4j 全量同步任务进度表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `proc_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proc_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(文件名去.py)',
  `proc_file_name` varchar(200) NOT NULL COMMENT '文件名',
  `file_path` varchar(500) NOT NULL COMMENT '完整路径',
  `proc_desc` varchar(500) DEFAULT NULL COMMENT '功能说明',
  `update_target` varchar(200) DEFAULT NULL COMMENT '更新对象(头部#更新对象,如CDM.C_AGT_DEPOSIT_ACCT_INF)',
  `table_count` int NOT NULL DEFAULT '0' COMMENT '真实依赖表数量(去重,不含临时表,不含更新目标表)',
  `temp_table_count` int NOT NULL DEFAULT '0' COMMENT '临时表数量(VT_前缀)',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proc_name` (`proc_name`)
) ENGINE=InnoDB AUTO_INCREMENT=7469 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储过程信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `proc_parse_fail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proc_parse_fail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proc_name` varchar(255) NOT NULL COMMENT '存储过程名',
  `task_id` varchar(64) DEFAULT NULL COMMENT '解析任务ID(本次解析任务的taskId,同步/上传等无任务上下文时为空)',
  `block_seq` int NOT NULL DEFAULT '0' COMMENT 'SQL块序号',
  `block_type` varchar(32) DEFAULT NULL COMMENT '块类型',
  `fail_reason` text COMMENT '失败原因(中文可读+原始)',
  `sql_preview` mediumtext COMMENT 'SQL内容(完整,详情弹窗展示)',
  `resolved` tinyint NOT NULL DEFAULT '0' COMMENT '是否已处理',
  `created_at` datetime DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (`id`),
  KEY `idx_proc_fail` (`proc_name`,`resolved`),
  KEY `idx_parse_fail_task` (`task_id`)
) ENGINE=InnoDB AUTO_INCREMENT=40 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `proc_table_dependency`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `proc_table_dependency` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proc_id` bigint NOT NULL COMMENT '关联proc_info.id',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(冗余)',
  `schema_name` varchar(50) DEFAULT NULL COMMENT 'schema变量名(CDM/FDM/ODM/PID等)',
  `schema_var_src` varchar(100) DEFAULT NULL COMMENT 'schema来源(如etl.dwa_db_cdm)',
  `table_name` varchar(200) NOT NULL COMMENT '表名',
  `full_table_name` varchar(300) NOT NULL COMMENT '完整表名(schema.table)',
  `operation_type` varchar(30) DEFAULT NULL COMMENT '操作类型:FROM/JOIN/INSERT INTO/DELETE FROM/UPDATE/MERGE INTO',
  `is_temp_table` tinyint NOT NULL DEFAULT '0' COMMENT '是否临时表(VT_前缀):0否1是',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  KEY `idx_proc_id` (`proc_id`),
  KEY `idx_proc_name` (`proc_name`),
  KEY `idx_table_name` (`table_name`)
) ENGINE=InnoDB AUTO_INCREMENT=53998 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='存储过程依赖表关系表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `rebuild_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rebuild_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` varchar(64) NOT NULL COMMENT '任务ID',
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING',
  `total` int NOT NULL DEFAULT '0',
  `done` int NOT NULL DEFAULT '0',
  `success` int NOT NULL DEFAULT '0',
  `fail` int NOT NULL DEFAULT '0',
  `current_proc` varchar(255) DEFAULT NULL,
  `failures` mediumtext,
  `error` varchar(1024) DEFAULT NULL,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `report_cell_binding`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_cell_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_file` varchar(255) NOT NULL COMMENT '所属报表文件',
  `dataset_name` varchar(100) NOT NULL COMMENT '数据集名',
  `cell_pos` varchar(20) NOT NULL COMMENT '单元格位置(A3)',
  `column_name` varchar(100) NOT NULL COMMENT '数据集列名(STAT_DT)',
  `header_text` varchar(255) DEFAULT NULL COMMENT '报表表头文本',
  `select_alias` varchar(100) DEFAULT NULL COMMENT 'SQL select别名',
  `source_table` varchar(255) DEFAULT NULL COMMENT '源表全名(小写)',
  `source_field` varchar(100) DEFAULT NULL COMMENT '源字段名',
  `source_expr` text COMMENT '源表达式原文',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_report_dataset` (`report_file`,`dataset_name`)
) ENGINE=InnoDB AUTO_INCREMENT=180010 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表单元格绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `report_dataset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_dataset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_file` varchar(255) NOT NULL COMMENT '所属报表文件',
  `dataset_name` varchar(100) NOT NULL COMMENT '数据集名(ds1)',
  `connection` varchar(255) DEFAULT NULL COMMENT '数据源连接名',
  `sql_text` text COMMENT 'SQL语句',
  `table_count` int NOT NULL DEFAULT '0' COMMENT '涉及表数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_dataset` (`report_file`,`dataset_name`)
) ENGINE=InnoDB AUTO_INCREMENT=20920 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表数据集表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `report_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file` varchar(255) NOT NULL COMMENT '文件名(唯一)',
  `name` varchar(255) NOT NULL COMMENT '报表名(去扩展名)',
  `type` varchar(20) NOT NULL COMMENT '类型: cpt/frm/xml',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `parse_time` datetime NOT NULL COMMENT '解析时间',
  `dataset_count` int NOT NULL DEFAULT '0' COMMENT '数据集数',
  `cell_count` int NOT NULL DEFAULT '0' COMMENT '单元格绑定数',
  `parse_status` varchar(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态: SUCCESS/PARTIAL/FAILED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  `file_path` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file` (`file`)
) ENGINE=InnoDB AUTO_INCREMENT=8236 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报表信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sql_block_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sql_block_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名',
  `block_seq` int NOT NULL COMMENT 'SQL 块序号',
  `block_type` varchar(50) DEFAULT NULL COMMENT 'INSERT_SELECT / CREATE_TEMP / OTHER',
  `target_table` varchar(200) DEFAULT NULL COMMENT '目标表短名',
  `main_table` varchar(200) DEFAULT NULL COMMENT '主表(FROM 后第一个表)短名',
  `start_line` int DEFAULT NULL COMMENT '脚本中起始行号',
  `end_line` int DEFAULT NULL COMMENT '脚本中结束行号',
  `sql_content` mediumtext COMMENT 'SQL 块原文',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proc_block` (`proc_name`,`block_seq`),
  KEY `idx_proc_target` (`proc_name`,`target_table`)
) ENGINE=InnoDB AUTO_INCREMENT=62322 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SQL 块信息';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_block_condition`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_block_condition` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `lineage_id` bigint NOT NULL COMMENT '关联 table_lineage.id',
  `proc_name` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储过程名',
  `sql_block_seq` int NOT NULL COMMENT 'SQL块序号',
  `source_table_short` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '别名解析后的源表短名，null=未归属(跳过同步)',
  `source_alias` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '条件字段别名',
  `source_field` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '条件字段名',
  `role` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'join_condition / where_condition',
  `clause` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '条件原文(截断)',
  `extract_time` datetime DEFAULT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_cond` (`lineage_id`,`source_alias`,`source_field`,`role`),
  KEY `idx_proc` (`proc_name`)
) ENGINE=InnoDB AUTO_INCREMENT=167852 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SQL块条件字段归属(行血缘同步源)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_column_meta`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_column_meta` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ddl_info_id` bigint NOT NULL COMMENT '关联 table_ddl_info.id',
  `schema_name` varchar(50) NOT NULL COMMENT 'schema 名（小写）',
  `table_name` varchar(200) NOT NULL COMMENT '表名（小写）',
  `full_table_name` varchar(300) NOT NULL COMMENT '完整表名 schema.table（小写）',
  `column_name` varchar(200) NOT NULL COMMENT '字段名（小写）',
  `ordinal_position` int NOT NULL COMMENT '字段序号（从 1 开始）',
  `column_default` text COMMENT '默认值',
  `is_nullable` varchar(3) NOT NULL DEFAULT 'NO' COMMENT '是否可空: YES/NO',
  `data_type` varchar(50) DEFAULT NULL COMMENT '数据类型（varchar/bigint）',
  `column_type` varchar(200) DEFAULT NULL COMMENT '完整列类型（varchar(64)）',
  `character_maximum_length` int DEFAULT NULL COMMENT '字符最大长度',
  `numeric_precision` int DEFAULT NULL COMMENT '数值精度',
  `numeric_scale` int DEFAULT NULL COMMENT '数值小数位',
  `character_set_name` varchar(50) DEFAULT NULL COMMENT '字符集',
  `collation_name` varchar(50) DEFAULT NULL COMMENT '排序规则',
  `column_key` varchar(10) DEFAULT NULL COMMENT '键类型: PRI/UNI/MUL',
  `extra` varchar(100) DEFAULT NULL COMMENT '额外信息（auto_increment）',
  `column_comment` text COMMENT '字段注释',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_full_table_col` (`full_table_name`,`column_name`),
  KEY `idx_ddl_info_id` (`ddl_info_id`),
  KEY `idx_table_name` (`table_name`),
  KEY `idx_full_table` (`full_table_name`)
) ENGINE=InnoDB AUTO_INCREMENT=616141 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物理表字段元数据';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_ddl_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_ddl_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `schema_name` varchar(50) NOT NULL COMMENT 'schema 名（小写，如 cdm/fdm/odm）',
  `table_name` varchar(200) NOT NULL COMMENT '表名（小写，不含 schema）',
  `full_table_name` varchar(300) NOT NULL COMMENT '完整表名 schema.table（小写）',
  `column_count` int NOT NULL DEFAULT '0' COMMENT '字段数',
  `fields_json` mediumtext COMMENT '字段名列表 JSON 数组（按 ORDINAL_POSITION 顺序）',
  `ddl_text` mediumtext COMMENT '生成的 CREATE TABLE 语句',
  `source_file` varchar(500) DEFAULT NULL COMMENT '元数据来源文件',
  `parse_time` datetime NOT NULL COMMENT '解析时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_full_table` (`full_table_name`),
  KEY `idx_table_name` (`table_name`),
  KEY `idx_schema` (`schema_name`)
) ENGINE=InnoDB AUTO_INCREMENT=19568 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物理表 DDL 信息';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_field_mapping`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_field_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `lineage_id` bigint NOT NULL COMMENT '关联 table_lineage.id',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(冗余)',
  `target_field` varchar(200) NOT NULL COMMENT '目标表字段名',
  `field_order` int NOT NULL COMMENT 'SELECT 列序号(从1开始)',
  `source_expr` text COMMENT 'SELECT 表达式原文',
  `source_alias` varchar(100) DEFAULT NULL COMMENT '表达式主别名(如 T01)',
  `source_table_short` varchar(200) DEFAULT NULL COMMENT '推断的来源表短名',
  `logic_type` varchar(30) DEFAULT NULL COMMENT 'Transform logic_type(DIRECT/LITERAL/ADDITION/...)',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  KEY `idx_lineage_id` (`lineage_id`),
  KEY `idx_proc_name` (`proc_name`),
  KEY `idx_target_field` (`target_field`)
) ENGINE=InnoDB AUTO_INCREMENT=526678 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字段级血缘映射';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_lineage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_lineage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proc_id` bigint NOT NULL COMMENT '关联proc_info.id',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(冗余)',
  `source_table` varchar(300) NOT NULL COMMENT '派生源表',
  `source_table_short` varchar(200) NOT NULL COMMENT '源表短名',
  `target_table` varchar(300) NOT NULL COMMENT '派生目标表',
  `target_table_short` varchar(200) NOT NULL COMMENT '目标表短名',
  `target_is_temp` tinyint NOT NULL DEFAULT '0' COMMENT '目标是否临时表:0否1是',
  `operation` varchar(30) NOT NULL COMMENT '派生操作',
  `sql_block_seq` int NOT NULL COMMENT 'SQL块序号',
  `main_table` varchar(200) DEFAULT NULL COMMENT '主表短名(FROM后第一个表)',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  KEY `idx_proc_id` (`proc_id`),
  KEY `idx_proc_name` (`proc_name`),
  KEY `idx_source` (`source_table_short`),
  KEY `idx_target` (`target_table_short`)
) ENGINE=InnoDB AUTO_INCREMENT=107505 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表间派生血缘关系';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_lineage_filter`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_lineage_filter` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `lineage_id` bigint NOT NULL COMMENT '关联table_lineage.id',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(冗余)',
  `filter_type` varchar(10) NOT NULL COMMENT '条件类型:JOIN/WHERE',
  `filter_clause` text NOT NULL COMMENT '条件原文',
  `fields_involved` longtext,
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  KEY `idx_lineage_id` (`lineage_id`),
  KEY `idx_proc_name` (`proc_name`)
) ENGINE=InnoDB AUTO_INCREMENT=182861 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='血缘条件详情';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `table_transform_input`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `table_transform_input` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `field_mapping_id` bigint NOT NULL COMMENT '关联 table_field_mapping.id',
  `lineage_id` bigint NOT NULL COMMENT '关联 table_lineage.id(冗余,便于直接查询)',
  `proc_name` varchar(200) NOT NULL COMMENT '存储过程名(冗余)',
  `target_field` varchar(200) NOT NULL COMMENT '目标字段名(冗余)',
  `source_table_short` varchar(200) DEFAULT NULL COMMENT '输入字段来源表简称',
  `source_field` varchar(200) DEFAULT NULL COMMENT '输入字段名',
  `source_alias` varchar(100) DEFAULT NULL COMMENT '表达式中该字段别名',
  `role` varchar(20) NOT NULL DEFAULT 'value' COMMENT '输入角色:value/condition',
  `input_order` int NOT NULL COMMENT '在表达式中出现的顺序(从零开始)',
  `extract_time` datetime NOT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  KEY `idx_field_mapping_id` (`field_mapping_id`),
  KEY `idx_lineage_id` (`lineage_id`),
  KEY `idx_proc_name` (`proc_name`),
  KEY `idx_source_table` (`source_table_short`)
) ENGINE=InnoDB AUTO_INCREMENT=1366589 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Transform 输入字段';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- 脚本转换可视化菜单（字段名参照 sql/migration/V2026_10_13__user_auth.sql 中 sys_menu 既有行样式）
INSERT INTO sys_menu (id, menu_code, menu_name, path, icon, parent_id, sort_order, type) VALUES
  (13, 'transform', '脚本转换', '/transform', 'Refresh', 0, 70, 'MENU')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

