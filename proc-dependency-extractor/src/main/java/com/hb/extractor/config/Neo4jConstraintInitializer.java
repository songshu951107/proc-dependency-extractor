package com.hb.extractor.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * Neo4j 约束初始化器
 * 应用启动时创建唯一性约束（幂等）
 */
@Slf4j
@Component
public class Neo4jConstraintInitializer {

    @Autowired
    private Neo4jClient neo4jClient;

    @PostConstruct
    public void init() {
        String[] constraints = {
            "CREATE CONSTRAINT table_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.name IS UNIQUE",
            "CREATE CONSTRAINT column_key_unique IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE",
            "CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE"
        };

        for (String cypher : constraints) {
            try {
                neo4jClient.query(cypher).run();
                log.info("Neo4j 约束创建成功: {}", cypher.substring(0, 50));
            } catch (Exception e) {
                log.warn("Neo4j 约束创建失败（可能已存在或 Neo4j 不可用）: {}", e.getMessage());
            }
        }
    }
}
