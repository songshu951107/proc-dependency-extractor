-- ----------------------------
-- SQL块条件字段归属（行血缘同步源，2026-08-21 双通道重构）
-- 每块每 (source_alias, source_field, role) 一行；source_table_short 为 null 表示未归属（保留行、跳过 Neo4j 同步）
-- ----------------------------
DROP TABLE IF EXISTS `table_block_condition`;
CREATE TABLE `table_block_condition` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `lineage_id` bigint NOT NULL COMMENT '关联 table_lineage.id',
  `proc_name` varchar(128) NOT NULL COMMENT '存储过程名',
  `sql_block_seq` int NOT NULL COMMENT 'SQL块序号',
  `source_table_short` varchar(128) DEFAULT NULL COMMENT '别名解析后的源表短名，null=未归属(跳过同步)',
  `source_alias` varchar(64) DEFAULT NULL COMMENT '条件字段别名',
  `source_field` varchar(128) NOT NULL COMMENT '条件字段名',
  `role` varchar(32) NOT NULL COMMENT 'join_condition / where_condition',
  `clause` varchar(512) DEFAULT NULL COMMENT '条件原文(截断)',
  `extract_time` datetime DEFAULT NULL COMMENT '提取时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_cond` (`lineage_id`,`source_alias`,`source_field`,`role`),
  KEY `idx_proc` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SQL块条件字段归属(行血缘同步源)';
