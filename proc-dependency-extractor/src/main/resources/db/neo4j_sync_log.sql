CREATE TABLE IF NOT EXISTS neo4j_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    proc_name VARCHAR(200) NOT NULL,
    sync_status VARCHAR(20) NOT NULL,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (sync_status),
    INDEX idx_proc (proc_name)
) COMMENT='Neo4j 同步日志';
