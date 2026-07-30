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
            "CREATE CONSTRAINT table_full_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.full_name IS UNIQUE",
            "CREATE CONSTRAINT field_full_name_unique IF NOT EXISTS FOR (f:Field) REQUIRE f.full_name IS UNIQUE",
            "CREATE CONSTRAINT proc_name_unique IF NOT EXISTS FOR (p:Proc) REQUIRE p.name IS UNIQUE",
            "CREATE CONSTRAINT sqlblock_unique IF NOT EXISTS FOR (b:SqlBlock) REQUIRE (b.proc_name, b.seq) IS UNIQUE"
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
