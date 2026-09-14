-- SQL 块解析失败清单表
CREATE TABLE IF NOT EXISTS proc_parse_fail (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    proc_name   VARCHAR(255) NOT NULL                COMMENT '存储过程名',
    block_seq   INT          NOT NULL DEFAULT 0      COMMENT 'SQL 块序号',
    block_type  VARCHAR(32)  DEFAULT NULL            COMMENT '块类型 CREATE_TEMP/INSERT_SELECT/OTHER',
    fail_reason TEXT             DEFAULT NULL           COMMENT '失败原因(中文可读+原始)',
    sql_preview MEDIUMTEXT       DEFAULT NULL           COMMENT 'SQL内容(完整,详情弹窗展示)',
    resolved    TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已处理 0未处理 1已处理',
    created_at  DATETIME     DEFAULT NULL            COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_proc_fail (proc_name, resolved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 块解析失败清单';