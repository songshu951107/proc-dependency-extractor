-- 全量重新解析任务进度表（持久化到 DB，供页面常驻展示，刷新不丢）
CREATE TABLE IF NOT EXISTS rebuild_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id      VARCHAR(64)  NOT NULL                COMMENT '任务ID（唯一）',
    status       VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED',
    total        INT          NOT NULL DEFAULT 0      COMMENT '任务总量',
    done         INT          NOT NULL DEFAULT 0      COMMENT '已完成数',
    success      INT          NOT NULL DEFAULT 0      COMMENT '成功数',
    fail         INT          NOT NULL DEFAULT 0      COMMENT '失败数',
    current_proc VARCHAR(255) DEFAULT NULL            COMMENT '当前处理的存储过程',
    failures     MEDIUMTEXT   DEFAULT NULL            COMMENT '失败明细(JSON数组字符串)',
    error        VARCHAR(1024) DEFAULT NULL           COMMENT '任务级错误',
    start_time   DATETIME     DEFAULT NULL            COMMENT '开始时间',
    end_time     DATETIME     DEFAULT NULL            COMMENT '结束时间',
    create_time  DATETIME     DEFAULT NULL            COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量重新解析任务进度表';