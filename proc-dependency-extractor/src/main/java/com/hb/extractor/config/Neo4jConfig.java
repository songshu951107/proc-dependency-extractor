package com.hb.extractor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

/**
 * Neo4j 配置类
 * Spring Boot 3.2.5 的 spring-boot-starter-data-neo4j 会自动配置 Neo4jClient，
 * 此处仅需启用 Repository 扫描
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "com.hb.extractor.repository.neo4j")
public class Neo4jConfig {
}
