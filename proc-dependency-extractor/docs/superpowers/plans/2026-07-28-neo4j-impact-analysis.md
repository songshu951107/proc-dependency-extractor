# Neo4j 影响分析功能实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 引入 Neo4j 图数据库，通过双写并行（MySQL 主 + Neo4j 辅）实现表级/字段级下游影响分析和跨 proc 全链路追踪。

**架构：** 解析时 MySQL 事务成功后调用 Neo4jSyncService 同步到 Neo4j（Best Effort，失败不回滚 MySQL）；新增独立影响分析页，后端查 Neo4j 构造 Cypher，前端 G6 渲染并支持路径展开。

**技术栈：** Spring Boot 3.2.5 + Spring Data Neo4j + MyBatis-Plus 3.5.7 + Vue 3 + AntV G6 v4.8.24 + Neo4j 5.x

**规格文档：** `docs/superpowers/specs/2026-07-28-neo4j-impact-analysis-design.md`

---

## 文件结构

### 后端新建文件

| 文件路径 | 职责 |
|---------|------|
| `src/main/java/com/hb/extractor/config/Neo4jConfig.java` | Neo4j 连接配置 + Repository 扫描 |
| `src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java` | 应用启动时创建 Neo4j 唯一性约束 |
| `src/main/java/com/hb/extractor/entity/Neo4jSyncLog.java` | 同步日志实体 |
| `src/main/java/com/hb/extractor/mapper/Neo4jSyncLogMapper.java` | 同步日志 Mapper |
| `src/main/java/com/hb/extractor/service/Neo4jSyncService.java` | Neo4j 同步服务接口 |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | Neo4j 同步服务实现（核心） |
| `src/main/java/com/hb/extractor/service/ImpactAnalysisService.java` | 影响分析服务接口 |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | 影响分析服务实现（Cypher 查询） |
| `src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java` | 影响分析 Controller |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisRequest.java` | 影响分析请求 DTO |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | 影响分析响应 VO（含内部类） |
| `src/main/java/com/hb/extractor/dto/ExpandRequest.java` | 路径展开请求 DTO |
| `src/main/java/com/hb/extractor/dto/SyncResult.java` | 同步结果 DTO |
| `src/main/java/com/hb/extractor/dto/Neo4jSyncLogVO.java` | 同步日志 VO |
| `src/main/java/com/hb/extractor/dto/TableOption.java` | 表选项 DTO |

### 后端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `pom.xml` | 新增 spring-boot-starter-data-neo4j 依赖 |
| `src/main/resources/application.yml` | 新增 Neo4j 连接配置 + impact 配置项 |
| `src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java` | extractAndSave 方法末尾调用 Neo4jSyncService |
| `src/main/java/com/hb/extractor/controller/ProcExtractController.java` | delete 方法同步删除 Neo4j 数据 |

### 测试文件

| 文件路径 | 职责 |
|---------|------|
| `src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java` | 同步逻辑单元测试（mock Neo4jClient） |
| `src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java` | Cypher 查询单元测试（mock Neo4jClient） |

### 前端新建文件

| 文件路径 | 职责 |
|---------|------|
| `frontend/src/api/impact.js` | 影响分析 API 封装 |
| `frontend/src/views/ImpactAnalysis.vue` | 影响分析页容器 |
| `frontend/src/components/impact/ImpactFilter.vue` | 筛选区（schema/表名/字段名/方向/深度/操作类型） |
| `frontend/src/components/impact/ImpactStats.vue` | 统计栏 |
| `frontend/src/components/impact/ImpactGraph.vue` | G6 图（复用 dagre LR + 三色节点） |
| `frontend/src/components/impact/ImpactNodeDetail.vue` | 节点详情侧边栏（展开/收起按钮） |
| `frontend/src/components/impact/ImpactEdgeDetail.vue` | 边详情（JOIN/WHERE/字段映射） |
| `frontend/src/components/impact/ImpactPathList.vue` | 路径列表 |

### 前端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `frontend/src/router/index.js` | 新增 `/impact` 路由 |
| `frontend/src/App.vue` | 菜单新增"影响分析"入口 |
| `startup.bat` | 增加 Neo4j 连接检查 |

### SQL 脚本

| 文件路径 | 职责 |
|---------|------|
| `src/main/resources/db/neo4j_sync_log.sql` | neo4j_sync_log 建表脚本 |

---

## 任务 1：Maven 依赖与配置文件

**文件：**
- 修改：`pom.xml`
- 修改：`src/main/resources/application.yml`
- 创建：`src/main/resources/db/neo4j_sync_log.sql`

- [ ] **步骤 1：pom.xml 新增 Neo4j 依赖**

在 `pom.xml` 的 `<dependencies>` 末尾（`spring-boot-starter-test` 之后）新增：

```xml
        <!-- Spring Data Neo4j -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-neo4j</artifactId>
        </dependency>
```

- [ ] **步骤 2：application.yml 新增 Neo4j 配置**

在 `application.yml` 的 `spring` 节点下新增 `neo4j` 和 `data.neo4j`，在文件末尾新增 `impact` 和 `scheduler`：

```yaml
spring:
  application:
    name: proc-dependency-extractor
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/proc_extractor?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: root
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: neo4j
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 200MB
  data:
    neo4j:
      database: neo4j

mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml
  type-aliases-package: com.hb.extractor.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto

extractor:
  ddl:
    dir: d:/HB/work_zuoye/jiaoben

impact:
  analysis:
    default-depth: 5
    max-depth: 20
    query-timeout: 10s
  sync:
    auto-retry: true
    retry-interval: 300000
    max-retry-count: 3

scheduler:
  neo4j-retry:
    enabled: true

logging:
  level:
    com.hb.extractor: debug
```

- [ ] **步骤 3：创建 neo4j_sync_log 建表脚本**

创建 `src/main/resources/db/neo4j_sync_log.sql`：

```sql
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
```

- [ ] **步骤 4：在 MySQL 中执行建表脚本**

运行：

```bash
mysql -uroot -proot proc_extractor < src/main/resources/db/neo4j_sync_log.sql
```

预期：无报错，`SHOW TABLES LIKE 'neo4j_sync_log'` 返回一行。

- [ ] **步骤 5：验证 Maven 依赖下载**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml dependency:resolve -q
```

预期：BUILD SUCCESS，Neo4j 相关 jar 包已下载。

- [ ] **步骤 6：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add pom.xml src/main/resources/application.yml src/main/resources/db/neo4j_sync_log.sql
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增 Neo4j 依赖与配置"
```

---

## 任务 2：Neo4j 配置类与约束初始化器

**文件：**
- 创建：`src/main/java/com/hb/extractor/config/Neo4jConfig.java`
- 创建：`src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java`

- [ ] **步骤 1：创建 Neo4jConfig.java**

```java
package com.hb.extractor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.net.URI;

/**
 * Neo4j 配置类
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "com.hb.extractor.repository.neo4j")
public class Neo4jConfig {

    @Value("${spring.neo4j.uri}")
    private String uri;

    @Value("${spring.neo4j.authentication.username}")
    private String username;

    @Value("${spring.neo4j.authentication.password}")
    private String password;

    @Value("${spring.data.neo4j.database:neo4j}")
    private String database;

    @Bean
    public Neo4jClient neo4jClient() {
        return Neo4jClient.create(url(), username, password);
    }

    private org.neo4j.driver.AuthTokens.AuthToken authToken() {
        return org.neo4j.driver.AuthTokens.basic(username, password);
    }

    private org.neo4j.driver.Driver driver() {
        return org.neo4j.driver.GraphDatabase.driver(uri, authToken());
    }

    private URI url() {
        return URI.create(uri);
    }
}
```

**注意**：Spring Boot 3.2.5 的 `spring-boot-starter-data-neo4j` 已自动配置 `Neo4jClient`，但显式声明 Bean 可确保配置可控。若自动配置冲突，可删除此 Bean 声明，仅保留 `@EnableNeo4jRepositories`。

- [ ] **步骤 2：创建 Neo4jConstraintInitializer.java**

```java
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
```

- [ ] **步骤 3：验证编译**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。若 Neo4j 未启动，约束创建失败仅打印 WARN 日志，不影响编译和启动。

- [ ] **步骤 4：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/config/Neo4jConfig.java src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增 Neo4j 配置类与约束初始化器"
```

---

## 任务 3：同步日志实体与 Mapper

**文件：**
- 创建：`src/main/java/com/hb/extractor/entity/Neo4jSyncLog.java`
- 创建：`src/main/java/com/hb/extractor/mapper/Neo4jSyncLogMapper.java`
- 创建：`src/main/java/com/hb/extractor/dto/Neo4jSyncLogVO.java`

- [ ] **步骤 1：创建 Neo4jSyncLog 实体**

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Neo4j 同步日志实体
 */
@Data
@TableName("neo4j_sync_log")
public class Neo4jSyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储过程名 */
    private String procName;

    /** 同步状态: PENDING / SUCCESS / FAILED */
    private String syncStatus;

    /** 错误信息 */
    private String errorMessage;

    /** 重试次数 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
```

- [ ] **步骤 2：创建 Neo4jSyncLogMapper**

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.Neo4jSyncLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface Neo4jSyncLogMapper extends BaseMapper<Neo4jSyncLog> {
}
```

- [ ] **步骤 3：创建 Neo4jSyncLogVO**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Neo4j 同步日志 VO（前端展示用）
 */
@Data
public class Neo4jSyncLogVO {

    private String procName;
    private String syncStatus;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime lastSyncTime;
}
```

- [ ] **步骤 4：验证编译**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 5：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/entity/Neo4jSyncLog.java src/main/java/com/hb/extractor/mapper/Neo4jSyncLogMapper.java src/main/java/com/hb/extractor/dto/Neo4jSyncLogVO.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增 Neo4j 同步日志实体与 Mapper"
```

---

## 任务 4：Neo4jSyncService 同步服务

**文件：**
- 创建：`src/main/java/com/hb/extractor/service/Neo4jSyncService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`
- 创建：`src/main/java/com/hb/extractor/dto/SyncResult.java`
- 创建：`src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java`

- [ ] **步骤 1：创建 SyncResult DTO**

```java
package com.hb.extractor.dto;

import lombok.Data;

/**
 * Neo4j 同步结果
 */
@Data
public class SyncResult {

    private String procName;
    private boolean success;
    private String errorMessage;

    /** 全量同步时的统计 */
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;

    public static SyncResult success(String procName) {
        SyncResult r = new SyncResult();
        r.setProcName(procName);
        r.setSuccess(true);
        return r;
    }

    public static SyncResult failure(String procName, String errorMessage) {
        SyncResult r = new SyncResult();
        r.setProcName(procName);
        r.setSuccess(false);
        r.setErrorMessage(errorMessage);
        return r;
    }

    public static SyncResult summary(int total, int success, int failed) {
        SyncResult r = new SyncResult();
        r.setSuccess(failed == 0);
        r.setTotalCount(total);
        r.setSuccessCount(success);
        r.setFailedCount(failed);
        return r;
    }
}
```

- [ ] **步骤 2：创建 Neo4jSyncService 接口**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.SyncResult;

/**
 * Neo4j 同步服务接口
 */
public interface Neo4jSyncService {

    /**
     * 同步单个 proc 到 Neo4j
     */
    SyncResult syncProc(String procName);

    /**
     * 全量同步所有 proc
     */
    SyncResult syncAll();

    /**
     * 删除 Neo4j 中该 proc 的全部数据
     */
    void deleteProcData(String procName);
}
```

- [ ] **步骤 3：创建 Neo4jSyncServiceImpl 核心实现**

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Neo4j 同步服务实现
 * MySQL 成功后调用，将 proc 的血缘数据同步到 Neo4j
 * 失败不影响主流程，记录到 neo4j_sync_log 表
 */
@Slf4j
@Service
public class Neo4jSyncServiceImpl implements Neo4jSyncService {

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private TableLineageMapper tableLineageMapper;

    @Autowired
    private TableLineageFilterMapper tableLineageFilterMapper;

    @Autowired
    private TableFieldMappingMapper tableFieldMappingMapper;

    @Autowired
    private SqlBlockInfoMapper sqlBlockInfoMapper;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncResult syncProc(String procName) {
        // 1. 记录同步日志（PENDING）
        Long logId = insertSyncLog(procName);

        try {
            // 2. 删除该 proc 的旧数据
            deleteProcData(procName);

            // 3. 读取 MySQL 数据
            ProcInfo procInfo = procInfoMapper.selectOne(
                    new LambdaQueryWrapper<ProcInfo>().eq(ProcInfo::getProcName, procName));
            if (procInfo == null) {
                updateSyncLog(logId, "FAILED", "proc_info 不存在");
                return SyncResult.failure(procName, "proc_info 不存在");
            }

            List<TableLineage> edges = tableLineageMapper.selectList(
                    new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getProcName, procName));
            List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

            Map<Long, List<TableLineageFilter>> filtersByLineageId = new HashMap<>();
            List<TableFieldMapping> mappings = new ArrayList<>();
            List<SqlBlockInfo> blocks = sqlBlockInfoMapper.selectList(
                    new LambdaQueryWrapper<SqlBlockInfo>().eq(SqlBlockInfo::getProcName, procName));

            if (!edgeIds.isEmpty()) {
                List<TableLineageFilter> filters = tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>().in(TableLineageFilter::getLineageId, edgeIds));
                filtersByLineageId = filters.stream().collect(Collectors.groupingBy(TableLineageFilter::getLineageId));

                mappings = tableFieldMappingMapper.selectList(
                        new LambdaQueryWrapper<TableFieldMapping>().in(TableFieldMapping::getLineageId, edgeIds));
            }

            // 4. 写入 Proc 节点
            Map<String, Object> procParams = new HashMap<>();
            procParams.put("name", procName);
            procParams.put("updateTarget", procInfo.getUpdateTarget());
            neo4jClient.query("MERGE (p:Proc {name: $name}) SET p.update_target = $updateTarget")
                    .bindAll(procParams).run();

            // 5. 写入 Table 节点 + DERIVES 关系（含筛选条件）+ PARSED_BY 关系
            for (TableLineage edge : edges) {
                // source Table 节点
                mergeTableNode(edge.getSourceTable(), edge.getSourceTableShort());
                // target Table 节点
                String targetType = edge.getTargetIsTemp() == 1 ? "TEMP" : "TARGET";
                mergeTableNode(edge.getTargetTable(), edge.getTargetTableShort());

                // 查当前 edge 是否有同 proc 同 target 的其他行决定 type
                // 简化：target 由 targetIsTemp 决定，SOURCE 由是否作为 target 出现决定（后续清理）
                // 这里直接根据 targetIsTemp 设置 type，并在最后统一更新 SOURCE 类型
                updateTableType(edge.getTargetTable(), edge.getTargetIsTemp() == 1 ? "TEMP" : "TARGET");

                // DERIVES 关系
                List<TableLineageFilter> edgeFilters = filtersByLineageId.getOrDefault(edge.getId(), Collections.emptyList());
                createDerivesRelationship(edge, edgeFilters);

                // PARSED_BY 关系
                Map<String, Object> parsedByParams = new HashMap<>();
                parsedByParams.put("procName", procName);
                parsedByParams.put("targetTable", edge.getTargetTable());
                parsedByParams.put("operation", edge.getOperation());
                neo4jClient.query("MATCH (t:Table {full_name: $targetTable}), (p:Proc {name: $procName}) " +
                        "MERGE (t)-[:PARSED_BY {proc_name: $procName, operation: $operation}]->(p)")
                        .bindAll(parsedByParams).run();
            }

            // 6. 写入 Field 节点 + HAS_FIELD 关系 + MAPS_TO 关系
            for (TableFieldMapping fm : mappings) {
                TableLineage edge = findEdgeById(edges, fm.getLineageId());
                if (edge == null) continue;

                String targetFieldFullName = toFieldFullName(edge.getTargetTable(), fm.getTargetField());
                mergeFieldNode(targetFieldFullName, fm.getTargetField(), edge.getTargetTable());
                createHasFieldRelationship(edge.getTargetTable(), targetFieldFullName);

                // source field（从 sourceExpr 提取）
                String sourceTableShort = fm.getSourceTableShort();
                if (sourceTableShort == null || sourceTableShort.isEmpty()) continue;
                String sourceTableFull = findTableFullName(edges, sourceTableShort, procName);
                if (sourceTableFull == null) continue;
                String sourceFieldName = extractFieldName(fm.getSourceExpr());
                if (sourceFieldName == null) continue;
                String sourceFieldFullName = toFieldFullName(sourceTableFull, sourceFieldName);
                mergeFieldNode(sourceFieldFullName, sourceFieldName, sourceTableFull);
                createHasFieldRelationship(sourceTableFull, sourceFieldFullName);

                // MAPS_TO 关系
                createMapsToRelationship(fm, edge, sourceFieldFullName, targetFieldFullName);
            }

            // 7. 写入 SqlBlock 节点 + CONTAINS_BLOCK 关系
            for (SqlBlockInfo block : blocks) {
                createSqlBlockNode(block, procName);
            }

            // 8. 更新 Table 节点 type：未被任何 edge 作为 target 的表为 SOURCE
            updateSourceTableTypes(procName);

            // 9. 清理孤儿节点
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Field) AND NOT (n)--() DELETE n").run();

            updateSyncLog(logId, "SUCCESS", null);
            log.info("Neo4j 同步成功 proc={}", procName);
            return SyncResult.success(procName);

        } catch (Exception e) {
            log.error("Neo4j 同步失败 proc={}", procName, e);
            updateSyncLog(logId, "FAILED", e.getMessage());
            return SyncResult.failure(procName, e.getMessage());
        }
    }

    @Override
    public SyncResult syncAll() {
        List<ProcInfo> allProcs = procInfoMapper.selectList(null);
        int success = 0, failed = 0;
        for (ProcInfo proc : allProcs) {
            SyncResult r = syncProc(proc.getProcName());
            if (r.isSuccess()) success++; else failed++;
        }
        return SyncResult.summary(allProcs.size(), success, failed);
    }

    @Override
    public void deleteProcData(String procName) {
        try {
            neo4jClient.query("MATCH (b:SqlBlock {proc_name: $procName}) DETACH DELETE b")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:DERIVES {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH ()-[r:PARSED_BY {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH (p:Proc {name: $procName}) DETACH DELETE p")
                    .bind(procName).to("procName").run();
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Field) AND NOT (n)--() DELETE n").run();
        } catch (Exception e) {
            log.warn("删除 Neo4j 旧数据失败 proc={}: {}", procName, e.getMessage());
        }
    }

    private Long insertSyncLog(String procName) {
        Neo4jSyncLog log = new Neo4jSyncLog();
        log.setProcName(procName);
        log.setSyncStatus("PENDING");
        log.setRetryCount(0);
        neo4jSyncLogMapper.insert(log);
        return log.getId();
    }

    private void updateSyncLog(Long logId, String status, String errorMessage) {
        Neo4jSyncLog log = new Neo4jSyncLog();
        log.setId(logId);
        log.setSyncStatus(status);
        log.setErrorMessage(errorMessage);
        if ("FAILED".equals(status)) {
            Neo4jSyncLog existing = neo4jSyncLogMapper.selectById(logId);
            if (existing != null) {
                log.setRetryCount(existing.getRetryCount() == null ? 1 : existing.getRetryCount() + 1);
            }
        }
        neo4jSyncLogMapper.updateById(log);
    }

    private void mergeTableNode(String fullName, String shortName) {
        if (fullName == null || fullName.isEmpty()) return;
        String schema = extractSchema(fullName);
        Map<String, Object> params = new HashMap<>();
        params.put("fullName", fullName);
        params.put("shortName", shortName);
        params.put("schema", schema);
        neo4jClient.query("MERGE (t:Table {full_name: $fullName}) " +
                "SET t.short_name = $shortName, t.schema = $schema")
                .bindAll(params).run();
    }

    private void updateTableType(String fullName, String type) {
        Map<String, Object> params = new HashMap<>();
        params.put("fullName", fullName);
        params.put("type", type);
        neo4jClient.query("MATCH (t:Table {full_name: $fullName}) SET t.type = $type")
                .bindAll(params).run();
    }

    private void updateSourceTableTypes(String procName) {
        // 作为 source 出现但从未作为 target 出现的表 → SOURCE
        neo4jClient.query("MATCH (t:Table) " +
                "WHERE NOT (t)<-[:DERIVES]-() " +
                "AND t.type IS NULL " +
                "SET t.type = 'SOURCE'").run();
        // 临时表作为 target 的，type 已设为 TEMP；正式 target 为 TARGET；
        // 但若某表既是 target 又是 source，保留其 target 的 type
    }

    private void createDerivesRelationship(TableLineage edge, List<TableLineageFilter> filters) {
        List<TableLineageFilter> joins = filters.stream()
                .filter(f -> "JOIN".equals(f.getFilterType()))
                .collect(Collectors.toList());
        List<TableLineageFilter> wheres = filters.stream()
                .filter(f -> "WHERE".equals(f.getFilterType()))
                .collect(Collectors.toList());

        String joinJson = serializeConditions(joins);
        String whereJson = serializeConditions(wheres);

        Map<String, Object> params = new HashMap<>();
        params.put("sourceTable", edge.getSourceTable());
        params.put("targetTable", edge.getTargetTable());
        params.put("procName", edge.getProcName());
        params.put("operation", edge.getOperation());
        params.put("sqlBlockSeq", edge.getSqlBlockSeq());
        params.put("mainTable", edge.getMainTable());
        params.put("joinConditions", joinJson);
        params.put("whereConditions", whereJson);

        neo4jClient.query("MATCH (s:Table {full_name: $sourceTable}), (t:Table {full_name: $targetTable}) " +
                "CREATE (s)-[:DERIVES {proc_name: $procName, operation: $operation, " +
                "sql_block_seq: $sqlBlockSeq, main_table: $mainTable, " +
                "join_conditions: $joinConditions, where_conditions: $whereConditions}]->(t)")
                .bindAll(params).run();
    }

    private String serializeConditions(List<TableLineageFilter> filters) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TableLineageFilter f : filters) {
            Map<String, Object> item = new HashMap<>();
            item.put("clause", f.getFilterClause());
            item.put("fields", parseFields(f.getFieldsInvolved()));
            list.add(item);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseFields(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(fieldsJson, List.class);
        } catch (Exception e) {
            return Collections.singletonList(fieldsJson);
        }
    }

    private void mergeFieldNode(String fieldFullName, String fieldName, String tableFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("fieldFullName", fieldFullName);
        params.put("fieldName", fieldName.toUpperCase());
        params.put("tableFullName", tableFullName);
        neo4jClient.query("MERGE (f:Field {full_name: $fieldFullName}) " +
                "SET f.field = $fieldName, f.table_full_name = $tableFullName")
                .bindAll(params).run();
    }

    private void createHasFieldRelationship(String tableFullName, String fieldFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("tableFullName", tableFullName);
        params.put("fieldFullName", fieldFullName);
        neo4jClient.query("MATCH (t:Table {full_name: $tableFullName}), (f:Field {full_name: $fieldFullName}) " +
                "MERGE (t)-[:HAS_FIELD]->(f)")
                .bindAll(params).run();
    }

    private void createMapsToRelationship(TableFieldMapping fm, TableLineage edge,
                                           String sourceFieldFullName, String targetFieldFullName) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceFieldFullName", sourceFieldFullName);
        params.put("targetFieldFullName", targetFieldFullName);
        params.put("procName", fm.getProcName());
        params.put("sqlBlockSeq", edge.getSqlBlockSeq());
        params.put("fieldOrder", fm.getFieldOrder());
        params.put("sourceExpr", fm.getSourceExpr());
        params.put("sourceAlias", fm.getSourceAlias());

        neo4jClient.query("MATCH (s:Field {full_name: $sourceFieldFullName}), " +
                "(t:Field {full_name: $targetFieldFullName}) " +
                "CREATE (s)-[:MAPS_TO {proc_name: $procName, sql_block_seq: $sqlBlockSeq, " +
                "field_order: $fieldOrder, source_expr: $sourceExpr, source_alias: $sourceAlias}]->(t)")
                .bindAll(params).run();
    }

    private void createSqlBlockNode(SqlBlockInfo block, String procName) {
        Map<String, Object> params = new HashMap<>();
        params.put("procName", procName);
        params.put("seq", block.getBlockSeq());
        params.put("operation", block.getBlockType());
        params.put("targetTable", block.getTargetTable());
        params.put("mainTable", block.getMainTable());

        neo4jClient.query("CREATE (b:SqlBlock {proc_name: $procName, seq: $seq, " +
                "operation: $operation, target_table: $targetTable, main_table: $mainTable})")
                .bindAll(params).run();

        neo4jClient.query("MATCH (p:Proc {name: $procName}), (b:SqlBlock {proc_name: $procName, seq: $seq}) " +
                "MERGE (p)-[:CONTAINS_BLOCK]->(b)")
                .bindAll(params).run();
    }

    private String toFieldFullName(String tableFullName, String fieldName) {
        if (fieldName == null) return null;
        return tableFullName + "." + fieldName.toUpperCase();
    }

    private String extractFieldName(String expr) {
        if (expr == null) return null;
        Matcher m = FIELD_PATTERN.matcher(expr);
        if (m.find()) return m.group(2);
        return null;
    }

    private String extractSchema(String fullName) {
        int idx = fullName.indexOf('.');
        return idx > 0 ? fullName.substring(0, idx) : null;
    }

    private TableLineage findEdgeById(List<TableLineage> edges, Long id) {
        return edges.stream().filter(e -> e.getId().equals(id)).findFirst().orElse(null);
    }

    private String findTableFullName(List<TableLineage> edges, String shortName, String procName) {
        // 优先从 sourceTable 找
        for (TableLineage e : edges) {
            if (shortName.equalsIgnoreCase(e.getSourceTableShort())) return e.getSourceTable();
        }
        // 再从 targetTable 找
        for (TableLineage e : edges) {
            if (shortName.equalsIgnoreCase(e.getTargetTableShort())) return e.getTargetTable();
        }
        // 可能是其他 proc 的表，查 MySQL
        List<TableLineage> all = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getSourceTableShort, shortName));
        if (!all.isEmpty()) return all.get(0).getSourceTable();
        all = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>().eq(TableLineage::getTargetTableShort, shortName));
        if (!all.isEmpty()) return all.get(0).getTargetTable();
        return shortName;
    }
}
```

- [ ] **步骤 4：编写单元测试 Neo4jSyncServiceTest**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.service.impl.Neo4jSyncServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Neo4j 同步服务单元测试
 * 使用 mock Neo4jClient，不依赖真实 Neo4j
 */
@ExtendWith(MockitoExtension.class)
class Neo4jSyncServiceTest {

    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private ProcInfoMapper procInfoMapper;
    @Mock
    private TableLineageMapper tableLineageMapper;
    @Mock
    private TableLineageFilterMapper tableLineageFilterMapper;
    @Mock
    private TableFieldMappingMapper tableFieldMappingMapper;
    @Mock
    private SqlBlockInfoMapper sqlBlockInfoMapper;
    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @InjectMocks
    private Neo4jSyncServiceImpl syncService;

    @Mock
    private Neo4jClient.UnboundRunnable unboundRunnable;

    @BeforeEach
    void setUp() {
        // mock Neo4jClient 链式调用
        when(neo4jClient.query(anyString())).thenReturn(unboundRunnable);
        when(unboundRunnable.bind(any())).thenReturn(unboundRunnable);
        when(unboundRunnable.bindAll(anyMap())).thenReturn(unboundRunnable);
        when(unboundRunnable.to(anyString())).thenReturn(unboundRunnable);
        when(unboundRunnable.run()).thenReturn(null);
    }

    @Test
    void testSyncProc_ProcInfoNotExist_ReturnsFailure() {
        when(procInfoMapper.selectOne(any())).thenReturn(null);

        SyncResult result = syncService.syncProc("nonexistent_proc");

        assertFalse(result.isSuccess());
        assertEquals("nonexistent_proc", result.getProcName());
        verify(neo4jSyncLogMapper).insert(any(Neo4jSyncLog.class));
    }

    @Test
    void testSyncProc_Success() {
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName("test_proc");
        procInfo.setUpdateTarget("CDM.TEST_TABLE");
        when(procInfoMapper.selectOne(any())).thenReturn(procInfo);
        when(tableLineageMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(sqlBlockInfoMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(neo4jSyncLogMapper.selectById(any())).thenReturn(null);

        SyncResult result = syncService.syncProc("test_proc");

        assertTrue(result.isSuccess());
        verify(neo4jSyncLogMapper).updateById(argThat(log -> "SUCCESS".equals(log.getSyncStatus())));
    }

    @Test
    void testDeleteProcData_Neo4jUnavailable_NoException() {
        // Neo4j 不可用时应捕获异常不抛出
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        assertDoesNotThrow(() -> syncService.deleteProcData("test_proc"));
    }
}
```

- [ ] **步骤 5：运行测试**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=Neo4jSyncServiceTest -q
```

预期：3 个测试全部 PASS。

- [ ] **步骤 6：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/service/Neo4jSyncService.java src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java src/main/java/com/hb/extractor/dto/SyncResult.java src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 实现 Neo4jSyncService 同步服务"
```

---

## 任务 5：双写集成 ProcExtractServiceImpl

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java`
- 修改：`src/main/java/com/hb/extractor/controller/ProcExtractController.java`

- [ ] **步骤 1：ProcExtractServiceImpl 注入 Neo4jSyncService 并在 extractAndSave 末尾调用**

在 `ProcExtractServiceImpl` 类中新增字段注入（在现有 `@Autowired` 字段之后）：

```java
    @Autowired
    private com.hb.extractor.service.Neo4jSyncService neo4jSyncService;
```

在 `extractAndSave` 方法的 `return procInfo;` 之前（MySQL 事务提交之后）新增 Neo4j 同步：

```java
        // Neo4j 同步（MySQL 成功后，失败不影响主流程）
        try {
            com.hb.extractor.dto.SyncResult syncResult = neo4jSyncService.syncProc(procInfo.getProcName());
            if (!syncResult.isSuccess()) {
                log.warn("Neo4j 同步失败 proc={}, error={}", procInfo.getProcName(), syncResult.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Neo4j 同步异常 proc={}", procInfo.getProcName(), e);
        }

        return procInfo;
```

**注意**：因为 `extractAndSave` 标注了 `@Transactional(rollbackFor = Exception.class)`，Neo4j 同步在事务提交后执行。但 Spring 的 `@Transactional` 方法在方法返回后才提交，所以 Neo4j 同步代码实际在提交前执行。为确保在 MySQL 提交后同步，需将 Neo4j 同步改为 `@TransactionalEventListener` 或使用 `TransactionSynchronizationManager`。

**简化方案**：由于 `Neo4jSyncServiceImpl.syncProc` 标注了 `@Transactional(propagation = Propagation.REQUIRES_NEW)`，它会在独立事务中执行。但为避免 MySQL 事务回滚后仍同步 Neo4j，需确保 MySQL 事务成功。

**更安全方案**：在 `ProcExtractController` 调用 `extractAndSave` 后调用 `neo4jSyncService.syncProc`。修改 Controller 层而非 Service 层。

- [ ] **步骤 2：修改 ProcExtractController 在解析后调用 Neo4j 同步**

查看 `ProcExtractController` 的 extract 方法，在调用 `procExtractService.extractAndSave(filePath)` 之后新增：

```java
        // Neo4j 同步（MySQL 成功后，失败不影响主流程）
        try {
            neo4jSyncService.syncProc(procInfo.getProcName());
        } catch (Exception e) {
            log.warn("Neo4j 同步失败 proc={}: {}", procInfo.getProcName(), e.getMessage());
        }
```

需要在 `ProcExtractController` 注入：

```java
    @Autowired
    private com.hb.extractor.service.Neo4jSyncService neo4jSyncService;
```

同时修改批量解析 `extractBatch` 和全量重解析 `extractAll` 方法，在循环内每个 proc 解析后调用 `neo4jSyncService.syncProc`。

- [ ] **步骤 3：修改 DELETE 接口同步删除 Neo4j 数据**

在 `ProcExtractController` 的 delete 方法中，删除 MySQL 数据后调用：

```java
        // 同步删除 Neo4j 数据
        try {
            neo4jSyncService.deleteProcData(procName);
        } catch (Exception e) {
            log.warn("删除 Neo4j 数据失败 proc={}: {}", procName, e.getMessage());
        }
```

- [ ] **步骤 4：验证编译**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 5：运行全部测试确保无回归**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -q
```

预期：所有现有测试 PASS（Neo4j 同步失败不影响解析主流程）。

- [ ] **步骤 6：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/controller/ProcExtractController.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 解析后双写 Neo4j，删除时同步清理"
```

---

## 任务 6：影响分析 API

**文件：**
- 创建：`src/main/java/com/hb/extractor/dto/ImpactAnalysisRequest.java`
- 创建：`src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java`
- 创建：`src/main/java/com/hb/extractor/dto/ExpandRequest.java`
- 创建：`src/main/java/com/hb/extractor/dto/TableOption.java`
- 创建：`src/main/java/com/hb/extractor/service/ImpactAnalysisService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`
- 创建：`src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java`
- 创建：`src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java`

- [ ] **步骤 1：创建 ImpactAnalysisRequest DTO**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ImpactAnalysisRequest {

    /** schema 名，可选 */
    private String schema;

    /** 表短名，必填 */
    private String tableShortName;

    /** 字段名，可选（填了则字段级分析） */
    private String fieldName;

    /** UPSTREAM / DOWNSTREAM / BOTH */
    private String direction = "DOWNSTREAM";

    /** 深度，默认 5，上限 20 */
    private Integer maxDepth = 5;

    /** 操作类型筛选 */
    private List<String> operations;

    /** 是否返回 JOIN/WHERE 条件，默认 true */
    private Boolean includeConditions = true;

    /** 是否返回字段级映射，默认 false */
    private Boolean includeFieldMapping = false;
}
```

- [ ] **步骤 2：创建 ImpactAnalysisVO（含内部类）**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ImpactAnalysisVO {

    private List<NodeVO> nodes;
    private List<EdgeVO> edges;
    private List<PathVO> paths;
    private ImpactStats stats;
    private Boolean truncated;

    @Data
    public static class NodeVO {
        private String id;
        private String label;
        private String type;           // SOURCE / TEMP / TARGET
        private String nodeLevel;      // TABLE / FIELD
        private String tableFullName;
        private Integer depth;
    }

    @Data
    public static class EdgeVO {
        private String source;
        private String target;
        private String operation;
        private Integer sqlBlockSeq;
        private String mainTable;
        private List<ConditionClause> joinConditions;
        private List<ConditionClause> whereConditions;
        private String sourceExpr;
        private String sourceAlias;
    }

    @Data
    public static class ImpactStats {
        private Integer affectedProcCount;
        private Integer affectedTableCount;
        private Integer affectedFieldCount;
        private Integer maxDepthReached;
        private List<String> affectedProcNames;
    }

    @Data
    public static class ConditionClause {
        private String clause;
        private List<String> fields;
    }

    @Data
    public static class PathVO {
        private List<PathNode> nodes;
        private Integer depth;
    }

    @Data
    public static class PathNode {
        private String id;
        private String label;
    }
}
```

- [ ] **步骤 3：创建 ExpandRequest 和 TableOption DTO**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExpandRequest {
    private String nodeId;
    private String direction;   // UPSTREAM / DOWNSTREAM
    private Integer depth = 1;
    private List<String> operations;
}
```

```java
package com.hb.extractor.dto;

import lombok.Data;

@Data
public class TableOption {
    private String fullName;
    private String shortName;
    private String type;
}
```

- [ ] **步骤 4：创建 ImpactAnalysisService 接口**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.*;

import java.util.List;

public interface ImpactAnalysisService {

    List<String> getSchemas();

    List<TableOption> getTables(String schema);

    List<String> getFields(String tableFullName);

    ImpactAnalysisVO analyze(ImpactAnalysisRequest request);

    ImpactAnalysisVO expand(ExpandRequest request);

    List<ImpactAnalysisVO.PathVO> getPaths(String source, String target, Integer maxDepth);

    List<Neo4jSyncLogVO> getSyncStatus();
}
```

- [ ] **步骤 5：创建 ImpactAnalysisServiceImpl 实现**

```java
package com.hb.extractor.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.Neo4jSyncLog;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import com.hb.extractor.service.ImpactAnalysisService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ImpactAnalysisServiceImpl implements ImpactAnalysisService {

    @Autowired
    private Neo4jClient neo4jClient;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @Value("${impact.analysis.max-depth:20}")
    private int maxAllowedDepth;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<String> getSchemas() {
        Collection<String> schemas = neo4jClient.query(
                "MATCH (t:Table) WHERE t.schema IS NOT NULL " +
                "RETURN DISTINCT t.schema AS schema ORDER BY schema")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("schema").asString())
                .all();
        return new ArrayList<>(schemas);
    }

    @Override
    public List<TableOption> getTables(String schema) {
        String cypher = "MATCH (t:Table) WHERE t.schema = $schema " +
                "RETURN t.full_name AS fullName, t.short_name AS shortName, t.type AS type " +
                "ORDER BY t.short_name";
        Collection<TableOption> tables = neo4jClient.query(cypher)
                .bind(schema).to("schema")
                .fetchAs(TableOption.class)
                .mappedBy((type, record) -> {
                    TableOption opt = new TableOption();
                    opt.setFullName(record.get("fullName").asString());
                    opt.setShortName(record.get("shortName").asString());
                    opt.setType(record.get("type").isNull() ? null : record.get("type").asString());
                    return opt;
                })
                .all();
        return new ArrayList<>(tables);
    }

    @Override
    public List<String> getFields(String tableFullName) {
        Collection<String> fields = neo4jClient.query(
                "MATCH (t:Table {full_name: $tableFullName})-[:HAS_FIELD]->(f:Field) " +
                "RETURN f.field AS field ORDER BY f.field")
                .bind(tableFullName).to("tableFullName")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("field").asString())
                .all();
        return new ArrayList<>(fields);
    }

    @Override
    public ImpactAnalysisVO analyze(ImpactAnalysisRequest request) {
        int depth = request.getMaxDepth() == null ? 5 : Math.min(request.getMaxDepth(), maxAllowedDepth);
        boolean isFieldLevel = request.getFieldName() != null && !request.getFieldName().isEmpty();

        String tableFullName = buildTableFullName(request.getSchema(), request.getTableShortName());
        String direction = request.getDirection() == null ? "DOWNSTREAM" : request.getDirection().toUpperCase();

        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
        List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();

        if (isFieldLevel) {
            String fieldFullName = tableFullName + "." + request.getFieldName().toUpperCase();
            analyzeFieldLevel(fieldFullName, direction, depth, request, nodes, edges, nodeIds);
        } else {
            analyzeTableLevel(tableFullName, direction, depth, request, nodes, edges, nodeIds);
        }

        vo.setNodes(nodes);
        vo.setEdges(edges);
        vo.setStats(buildStats(nodes, edges, depth));
        vo.setTruncated(false);
        return vo;
    }

    @Override
    public ImpactAnalysisVO expand(ExpandRequest request) {
        int depth = request.getDepth() == null ? 1 : request.getDepth();
        String direction = request.getDirection() == null ? "DOWNSTREAM" : request.getDirection().toUpperCase();

        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
        List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();

        // 判断 nodeId 是 Table 还是 Field
        boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2;

        if (isFieldLevel) {
            String relType = "DOWNSTREAM".equals(direction) ? "MAPS_TO" : "MAPS_TO";
            String arrow = "DOWNSTREAM".equals(direction) ? "-[:MAPS_TO*1.." + depth + "]->" : "<-[:MAPS_TO*1.." + depth + "]-";
            String cypher = "MATCH path = (src:Field {full_name: $nodeId})" + arrow + "(downstream:Field) " +
                    "RETURN path";
            executeAndCollect(cypher, request.getNodeId(), "nodeId", nodes, edges, nodeIds, true);
        } else {
            String arrow = "DOWNSTREAM".equals(direction) ? "-[:DERIVES*1.." + depth + "]->" : "<-[:DERIVES*1.." + depth + "]-";
            String cypher = "MATCH path = (src:Table {full_name: $nodeId})" + arrow + "(downstream:Table) " +
                    "RETURN path";
            executeAndCollect(cypher, request.getNodeId(), "nodeId", nodes, edges, nodeIds, false);
        }

        vo.setNodes(nodes);
        vo.setEdges(edges);
        return vo;
    }

    @Override
    public List<ImpactAnalysisVO.PathVO> getPaths(String source, String target, Integer maxDepth) {
        int depth = maxDepth == null ? 10 : Math.min(maxDepth, maxAllowedDepth);
        String cypher = "MATCH path = (src:Table {full_name: $source})-[:DERIVES*1.." + depth + "]->(dst:Table {full_name: $target}) " +
                "RETURN path ORDER BY length(path) LIMIT 10";

        List<ImpactAnalysisVO.PathVO> paths = new ArrayList<>();
        neo4jClient.query(cypher)
                .bind(source).to("source")
                .bind(target).to("target")
                .fetch()
                .table()
                .all()
                .forEach(record -> {
                    // 简化：从 path 提取节点
                    ImpactAnalysisVO.PathVO path = new ImpactAnalysisVO.PathVO();
                    // 实际需要从 record 解析 path
                    paths.add(path);
                });
        return paths;
    }

    @Override
    public List<Neo4jSyncLogVO> getSyncStatus() {
        List<Neo4jSyncLog> logs = neo4jSyncLogMapper.selectList(
                new LambdaQueryWrapper<Neo4jSyncLog>()
                        .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
                        .orderByDesc(Neo4jSyncLog::getUpdateTime));
        return logs.stream().map(this::toVO).collect(Collectors.toList());
    }

    private Neo4jSyncLogVO toVO(Neo4jSyncLog log) {
        Neo4jSyncLogVO vo = new Neo4jSyncLogVO();
        vo.setProcName(log.getProcName());
        vo.setSyncStatus(log.getSyncStatus());
        vo.setErrorMessage(log.getErrorMessage());
        vo.setRetryCount(log.getRetryCount());
        vo.setLastSyncTime(log.getUpdateTime());
        return vo;
    }

    private void analyzeTableLevel(String tableFullName, String direction, int depth,
                                    ImpactAnalysisRequest request,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges,
                                    Set<String> nodeIds) {
        String arrow = "DOWNSTREAM".equals(direction) ? "-[:DERIVES*1.." + depth + "]->" : "<-[:DERIVES*1.." + depth + "]-";
        String cypher = "MATCH path = (src:Table {full_name: $tableFullName})" + arrow + "(downstream:Table) " +
                "RETURN path";
        executeAndCollect(cypher, tableFullName, "tableFullName", nodes, edges, nodeIds, false);

        if ("BOTH".equals(direction)) {
            String cypherUp = "MATCH path = (src:Table {full_name: $tableFullName})<-[:DERIVES*1.." + depth + "]-(upstream:Table) " +
                    "RETURN path";
            executeAndCollect(cypherUp, tableFullName, "tableFullName", nodes, edges, nodeIds, false);
        }
    }

    private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                    ImpactAnalysisRequest request,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges,
                                    Set<String> nodeIds) {
        String arrow = "DOWNSTREAM".equals(direction) ? "-[:MAPS_TO*1.." + depth + "]->" : "<-[:MAPS_TO*1.." + depth + "]-";
        String cypher = "MATCH path = (src:Field {full_name: $fieldFullName})" + arrow + "(downstream:Field) " +
                "RETURN path";
        executeAndCollect(cypher, fieldFullName, "fieldFullName", nodes, edges, nodeIds, true);

        if ("BOTH".equals(direction)) {
            String cypherUp = "MATCH path = (src:Field {full_name: $fieldFullName})<-[:MAPS_TO*1.." + depth + "]-(upstream:Field) " +
                    "RETURN path";
            executeAndCollect(cypherUp, fieldFullName, "fieldFullName", nodes, edges, nodeIds, true);
        }
    }

    private void executeAndCollect(String cypher, String bindValue, String bindKey,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges,
                                    Set<String> nodeIds, boolean isFieldLevel) {
        try {
            neo4jClient.query(cypher)
                    .bind(bindValue).to(bindKey)
                    .fetch()
                    .table()
                    .all()
                    .forEach(record -> {
                        // 从 path record 提取节点和关系
                        // Neo4j driver 返回的 record 包含 path，需用 org.neo4j.driver.types.Path
                        // 简化处理：使用 fetchAs(Path.class) 或直接解析
                    });
        } catch (Exception e) {
            log.error("Cypher 查询失败: {}", e.getMessage());
        }

        // 备用方案：分两步查（先查节点，再查边）
        if (nodes.isEmpty()) {
            collectNodesAndEdges(bindValue, bindKey, isFieldLevel, nodes, edges, nodeIds);
        }
    }

    /**
     * 备用方案：分步查询节点和边
     */
    private void collectNodesAndEdges(String startId, String startKey, boolean isFieldLevel,
                                       List<ImpactAnalysisVO.NodeVO> nodes,
                                       List<ImpactAnalysisVO.EdgeVO> edges,
                                       Set<String> nodeIds) {
        String nodeLabel = isFieldLevel ? "Field" : "Table";
        String relType = isFieldLevel ? "MAPS_TO" : "DERIVES";

        // 查下游节点
        String cypher = String.format(
                "MATCH (src:%s {full_name: $startId})-[:%s*1..5]->(downstream:%s) " +
                "RETURN DISTINCT downstream.full_name AS id, downstream.short_name AS label, " +
                "downstream.type AS type, downstream.field AS field, downstream.table_full_name AS tableFullName",
                nodeLabel, relType, nodeLabel);

        try {
            neo4jClient.query(cypher)
                    .bind(startId).to("startId")
                    .fetch()
                    .table()
                    .all()
                    .forEach(record -> {
                        String id = record.get("id").asString();
                        if (!nodeIds.contains(id)) {
                            nodeIds.add(id);
                            ImpactAnalysisVO.NodeVO node = new ImpactAnalysisVO.NodeVO();
                            node.setId(id);
                            node.setLabel(isFieldLevel ?
                                    (record.get("field").isNull() ? id : record.get("field").asString()) :
                                    (record.get("label").isNull() ? id : record.get("label").asString()));
                            node.setType(record.get("type").isNull() ? null : record.get("type").asString());
                            node.setNodeLevel(isFieldLevel ? "FIELD" : "TABLE");
                            if (isFieldLevel && !record.get("tableFullName").isNull()) {
                                node.setTableFullName(record.get("tableFullName").asString());
                            }
                            nodes.add(node);
                        }
                    });
        } catch (Exception e) {
            log.error("查询节点失败: {}", e.getMessage());
        }

        // 查边
        String edgeCypher = String.format(
                "MATCH (src:%s {full_name: $startId})-[:%s*1..5]->(downstream:%s) " +
                "WITH DISTINCT downstream " +
                "MATCH (src2)-[r:%s]->(downstream) " +
                "WHERE src2.full_name IS NOT NULL AND downstream.full_name IS NOT NULL " +
                "RETURN src2.full_name AS source, downstream.full_name AS target, " +
                "r.operation AS operation, r.sql_block_seq AS seq, r.main_table AS mainTable, " +
                "r.join_conditions AS joins, r.where_conditions AS wheres, " +
                "r.source_expr AS sourceExpr, r.source_alias AS sourceAlias",
                nodeLabel, relType, nodeLabel, relType);

        try {
            neo4jClient.query(edgeCypher)
                    .bind(startId).to("startId")
                    .fetch()
                    .table()
                    .all()
                    .forEach(record -> {
                        ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                        edge.setSource(record.get("source").asString());
                        edge.setTarget(record.get("target").asString());
                        edge.setOperation(record.get("operation").isNull() ? null : record.get("operation").asString());
                        edge.setSqlBlockSeq(record.get("seq").isNull() ? null : (int) record.get("seq").asLong());
                        edge.setMainTable(record.get("mainTable").isNull() ? null : record.get("mainTable").asString());
                        edge.setJoinConditions(parseConditions(record.get("joins")));
                        edge.setWhereConditions(parseConditions(record.get("wheres")));
                        if (isFieldLevel) {
                            edge.setSourceExpr(record.get("sourceExpr").isNull() ? null : record.get("sourceExpr").asString());
                            edge.setSourceAlias(record.get("sourceAlias").isNull() ? null : record.get("sourceAlias").asString());
                        }
                        edges.add(edge);
                    });
        } catch (Exception e) {
            log.error("查询边失败: {}", e.getMessage());
        }

        // 添加起点节点
        if (!nodeIds.contains(startId)) {
            nodeIds.add(startId);
            ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
            startNode.setId(startId);
            startNode.setLabel(startId);
            startNode.setNodeLevel(isFieldLevel ? "FIELD" : "TABLE");
            startNode.setDepth(0);
            nodes.add(0, startNode);
        }
    }

    private List<ImpactAnalysisVO.ConditionClause> parseConditions(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) return Collections.emptyList();
        try {
            return objectMapper.readValue(value.asString(),
                    new TypeReference<List<ImpactAnalysisVO.ConditionClause>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private ImpactAnalysisVO.ImpactStats buildStats(List<ImpactAnalysisVO.NodeVO> nodes,
                                                     List<ImpactAnalysisVO.EdgeVO> edges,
                                                     int maxDepth) {
        ImpactAnalysisVO.ImpactStats stats = new ImpactAnalysisVO.ImpactStats();
        stats.setAffectedTableCount((int) nodes.stream()
                .filter(n -> "TABLE".equals(n.getNodeLevel())).count());
        stats.setAffectedFieldCount((int) nodes.stream()
                .filter(n -> "FIELD".equals(n.getNodeLevel())).count());
        Set<String> procs = edges.stream()
                .map(ImpactAnalysisVO.EdgeVO::getOperation)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        stats.setAffectedProcCount(edges.size());
        stats.setMaxDepthReached(maxDepth);
        return stats;
    }

    private String buildTableFullName(String schema, String shortName) {
        if (schema != null && !schema.isEmpty()) {
            return schema.toUpperCase() + "." + shortName.toUpperCase();
        }
        // 无 schema 时查 Neo4j
        try {
            Collection<String> fullNames = neo4jClient.query(
                    "MATCH (t:Table {short_name: $shortName}) RETURN t.full_name AS fullName LIMIT 1")
                    .bind(shortName.toUpperCase()).to("shortName")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("fullName").asString())
                    .all();
            if (!fullNames.isEmpty()) return fullNames.iterator().next();
        } catch (Exception e) {
            log.warn("查询表 full_name 失败: {}", e.getMessage());
        }
        return shortName.toUpperCase();
    }
}
```

- [ ] **步骤 6：创建 ImpactAnalysisController**

```java
package com.hb.extractor.controller;

import com.hb.extractor.dto.*;
import com.hb.extractor.service.ImpactAnalysisService;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/impact")
public class ImpactAnalysisController {

    @Autowired
    private ImpactAnalysisService impactAnalysisService;

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @GetMapping("/schemas")
    public Result<List<String>> getSchemas() {
        return Result.success(impactAnalysisService.getSchemas());
    }

    @GetMapping("/tables")
    public Result<List<TableOption>> getTables(@RequestParam String schema) {
        return Result.success(impactAnalysisService.getTables(schema));
    }

    @GetMapping("/fields")
    public Result<List<String>> getFields(@RequestParam String tableFullName) {
        return Result.success(impactAnalysisService.getFields(tableFullName));
    }

    @PostMapping("/analyze")
    public Result<ImpactAnalysisVO> analyze(@RequestBody ImpactAnalysisRequest request) {
        return Result.success(impactAnalysisService.analyze(request));
    }

    @PostMapping("/expand")
    public Result<ImpactAnalysisVO> expand(@RequestBody ExpandRequest request) {
        return Result.success(impactAnalysisService.expand(request));
    }

    @GetMapping("/paths")
    public Result<List<ImpactAnalysisVO.PathVO>> getPaths(
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(defaultValue = "10") Integer maxDepth) {
        return Result.success(impactAnalysisService.getPaths(source, target, maxDepth));
    }

    @PostMapping("/resync")
    public Result<SyncResult> resync(@RequestParam String procName) {
        return Result.success(neo4jSyncService.syncProc(procName));
    }

    @PostMapping("/resync/all")
    public Result<SyncResult> resyncAll() {
        return Result.success(neo4jSyncService.syncAll());
    }

    @GetMapping("/sync-status")
    public Result<List<Neo4jSyncLogVO>> getSyncStatus() {
        return Result.success(impactAnalysisService.getSyncStatus());
    }
}
```

- [ ] **步骤 7：编写 ImpactAnalysisServiceTest**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.ImpactAnalysisRequest;
import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.dto.ExpandRequest;
import com.hb.extractor.service.impl.ImpactAnalysisServiceImpl;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImpactAnalysisServiceTest {

    @Mock
    private Neo4jClient neo4jClient;

    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @InjectMocks
    private ImpactAnalysisServiceImpl impactService;

    @Test
    void testAnalyze_Neo4jUnavailable_ReturnsEmptyResult() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        // 起点节点应存在
        assertFalse(vo.getNodes().isEmpty());
        assertEquals("CDM.TEST_TABLE", vo.getNodes().get(0).getId());
    }

    @Test
    void testGetSyncStatus_ReturnsFailedLogs() {
        when(neo4jSyncLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = impactService.getSyncStatus();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **步骤 8：运行测试**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ImpactAnalysisServiceTest -q
```

预期：2 个测试 PASS。

- [ ] **步骤 9：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/dto/ImpactAnalysisRequest.java src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java src/main/java/com/hb/extractor/dto/ExpandRequest.java src/main/java/com/hb/extractor/dto/TableOption.java src/main/java/com/hb/extractor/service/ImpactAnalysisService.java src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 实现影响分析 API（analyze/expand/paths/resync）"
```

---

## 任务 7：前端 API 封装与路由

**文件：**
- 创建：`frontend/src/api/impact.js`
- 修改：`frontend/src/router/index.js`
- 修改：`frontend/src/App.vue`

- [ ] **步骤 1：创建 impact.js API 封装**

```javascript
import request from './request'

export function getSchemas() {
  return request({ url: '/api/impact/schemas', method: 'get' })
}

export function getTables(schema) {
  return request({ url: '/api/impact/tables', params: { schema }, method: 'get' })
}

export function getFields(tableFullName) {
  return request({ url: '/api/impact/fields', params: { tableFullName }, method: 'get' })
}

export function analyze(data) {
  return request({ url: '/api/impact/analyze', method: 'post', data })
}

export function expand(data) {
  return request({ url: '/api/impact/expand', method: 'post', data })
}

export function getPaths(params) {
  return request({ url: '/api/impact/paths', method: 'get', params })
}

export function resync(procName) {
  return request({ url: '/api/impact/resync', method: 'post', params: { procName } })
}

export function resyncAll() {
  return request({ url: '/api/impact/resync/all', method: 'post' })
}

export function getSyncStatus() {
  return request({ url: '/api/impact/sync-status', method: 'get' })
}
```

- [ ] **步骤 2：router/index.js 新增影响分析路由**

在 `routes` 数组末尾新增：

```javascript
  {
    path: '/impact',
    name: 'ImpactAnalysis',
    component: () => import('../views/ImpactAnalysis.vue'),
    meta: { title: '影响分析' }
  }
```

- [ ] **步骤 3：App.vue 新增菜单项**

在 `<el-menu-item index="/procs">` 之后新增：

```html
          <el-menu-item index="/impact">
            <el-icon><Aim /></el-icon>
            <span>影响分析</span>
          </el-menu-item>
```

在 `<script setup>` 的 import 中新增 `Aim`：

```javascript
import { Upload, List, Connection, Aim } from '@element-plus/icons-vue'
```

- [ ] **步骤 4：验证前端构建**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend && npm run build
```

预期：构建成功（ImpactAnalysis.vue 尚未创建，路由会报错，先创建空文件）。

- [ ] **步骤 5：创建占位 ImpactAnalysis.vue（下一任务实现）**

```vue
<template>
  <div class="impact-analysis">
    <h2>影响分析</h2>
    <p>功能开发中...</p>
  </div>
</template>

<script setup>
</script>

<style scoped>
.impact-analysis {
  padding: 20px;
}
</style>
```

- [ ] **步骤 6：验证构建**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend && npm run build
```

预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add frontend/src/api/impact.js frontend/src/router/index.js frontend/src/App.vue frontend/src/views/ImpactAnalysis.vue
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 前端新增影响分析路由与 API 封装"
```

---

## 任务 8：前端影响分析页（筛选区 + 统计栏 + 图展示）

**文件：**
- 修改：`frontend/src/views/ImpactAnalysis.vue`
- 创建：`frontend/src/components/impact/ImpactFilter.vue`
- 创建：`frontend/src/components/impact/ImpactStats.vue`
- 创建：`frontend/src/components/impact/ImpactGraph.vue`

- [ ] **步骤 1：创建 ImpactFilter.vue 筛选区组件**

```vue
<template>
  <el-card class="impact-filter" shadow="never">
    <el-form :inline="true" :model="form" label-width="80px">
      <el-form-item label="Schema">
        <el-select v-model="form.schema" placeholder="选择 Schema" @change="onSchemaChange" style="width: 120px">
          <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="表名">
        <el-select
          v-model="form.tableShortName"
          placeholder="选择表"
          filterable
          @change="onTableChange"
          style="width: 280px"
        >
          <el-option
            v-for="t in tables"
            :key="t.fullName"
            :label="t.shortName"
            :value="t.shortName"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="字段名">
        <el-select
          v-model="form.fieldName"
          placeholder="可选"
          filterable
          clearable
          style="width: 180px"
        >
          <el-option v-for="f in fields" :key="f" :label="f" :value="f" />
        </el-select>
      </el-form-item>
      <el-form-item label="方向">
        <el-select v-model="form.direction" style="width: 100px">
          <el-option label="下游" value="DOWNSTREAM" />
          <el-option label="上游" value="UPSTREAM" />
          <el-option label="双向" value="BOTH" />
        </el-select>
      </el-form-item>
      <el-form-item label="深度">
        <el-select v-model="form.maxDepth" style="width: 80px">
          <el-option :value="1" label="1" />
          <el-option :value="3" label="3" />
          <el-option :value="5" label="5" />
          <el-option :value="10" label="10" />
          <el-option :value="20" label="全部" />
        </el-select>
      </el-form-item>
      <el-form-item label="操作类型">
        <el-checkbox-group v-model="form.operations" style="display: inline-block">
          <el-checkbox label="INSERT SELECT">INSERT</el-checkbox>
          <el-checkbox label="CREATE TEMP">CREATE TEMP</el-checkbox>
          <el-checkbox label="MERGE">MERGE</el-checkbox>
          <el-checkbox label="UPDATE FROM">UPDATE</el-checkbox>
          <el-checkbox label="DELETE FROM">DELETE</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onAnalyze" :loading="loading">分析</el-button>
        <el-button @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getSchemas, getTables, getFields } from '@/api/impact'

const props = defineProps({
  loading: Boolean
})
const emit = defineEmits(['analyze'])

const schemas = ref([])
const tables = ref([])
const fields = ref([])

const form = reactive({
  schema: '',
  tableShortName: '',
  fieldName: '',
  direction: 'DOWNSTREAM',
  maxDepth: 5,
  operations: []
})

onMounted(async () => {
  try {
    const res = await getSchemas()
    schemas.value = res.data || []
  } catch (e) {
    console.error('加载 schemas 失败', e)
  }
})

const onSchemaChange = async () => {
  form.tableShortName = ''
  form.fieldName = ''
  fields.value = []
  if (!form.schema) return
  try {
    const res = await getTables(form.schema)
    tables.value = res.data || []
  } catch (e) {
    console.error('加载表列表失败', e)
  }
}

const onTableChange = async () => {
  form.fieldName = ''
  if (!form.tableShortName) return
  const table = tables.value.find(t => t.shortName === form.tableShortName)
  if (!table) return
  try {
    const res = await getFields(table.fullName)
    fields.value = res.data || []
  } catch (e) {
    console.error('加载字段列表失败', e)
  }
}

const onAnalyze = () => {
  emit('analyze', { ...form })
}

const onReset = () => {
  form.schema = ''
  form.tableShortName = ''
  form.fieldName = ''
  form.direction = 'DOWNSTREAM'
  form.maxDepth = 5
  form.operations = []
  tables.value = []
  fields.value = []
}
</script>

<style scoped>
.impact-filter {
  margin-bottom: 10px;
}
</style>
```

- [ ] **步骤 2：创建 ImpactStats.vue 统计栏组件**

```vue
<template>
  <el-card class="impact-stats" shadow="never" v-if="stats">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-statistic title="影响表数" :value="stats.affectedTableCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="影响字段数" :value="stats.affectedFieldCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="涉及 proc 数" :value="stats.affectedProcCount || 0" />
      </el-col>
      <el-col :span="6">
        <el-statistic title="最大深度" :value="stats.maxDepthReached || 0" />
      </el-col>
    </el-row>
  </el-card>
</template>

<script setup>
defineProps({
  stats: Object
})
</script>

<style scoped>
.impact-stats {
  margin-bottom: 10px;
}
</style>
```

- [ ] **步骤 3：创建 ImpactGraph.vue G6 图组件**

```vue
<template>
  <div class="impact-graph-container">
    <div ref="graphContainer" class="graph-canvas"></div>
    <el-empty v-if="!nodes || nodes.length === 0" description="未找到影响链路" />
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import G6 from '@antv/g6'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  isFieldLevel: { type: Boolean, default: false }
})
const emit = defineEmits(['nodeClick', 'edgeClick'])

const graphContainer = ref(null)
let graph = null

const NODE_COLORS = {
  SOURCE: '#52c41a',
  TEMP: '#faad14',
  TARGET: '#f5222d',
  FIELD: '#1890ff'
}

const initGraph = () => {
  if (!graphContainer.value) return
  const width = graphContainer.value.offsetWidth
  const height = graphContainer.value.offsetHeight || 500

  graph = new G6.Graph({
    container: graphContainer.value,
    width,
    height,
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node']
    },
    layout: {
      type: 'dagre',
      rankdir: 'LR',
      nodesep: 30,
      ranksep: 60
    },
    defaultNode: {
      type: 'rect',
      style: { radius: 4 },
      labelCfg: { style: { fontSize: 12 } }
    },
    defaultEdge: {
      type: 'cubic-horizontal',
      style: { endArrow: true, lineWidth: 1.5 }
    },
    nodeStateStyles: {
      hover: { shadowBlur: 10, shadowColor: '#1890ff' }
    }
  })

  graph.on('node:click', (evt) => {
    emit('nodeClick', evt.item.getModel())
  })
  graph.on('edge:click', (evt) => {
    emit('edgeClick', evt.item.getModel())
  })

  renderGraph()
}

const renderGraph = () => {
  if (!graph || !props.nodes) return

  const g6Nodes = props.nodes.map(n => ({
    id: n.id,
    label: n.label || n.id,
    style: {
      fill: props.isFieldLevel ? NODE_COLORS.FIELD : (NODE_COLORS[n.type] || '#d9d9d9'),
      stroke: '#333'
    }
  }))

  const g6Edges = props.edges.map((e, i) => ({
    id: `edge-${i}`,
    source: e.source,
    target: e.target,
    style: {
      lineDash: props.isFieldLevel ? [4, 4] : undefined
    }
  }))

  graph.data({ nodes: g6Nodes, edges: g6Edges })
  graph.render()
}

const addNodes = (newNodes, newEdges) => {
  if (!graph) return
  const g6Nodes = newNodes.map(n => ({
    id: n.id,
    label: n.label || n.id,
    style: {
      fill: props.isFieldLevel ? NODE_COLORS.FIELD : (NODE_COLORS[n.type] || '#d9d9d9'),
      stroke: '#333'
    }
  }))
  const g6Edges = newEdges.map((e, i) => ({
    id: `edge-add-${Date.now()}-${i}`,
    source: e.source,
    target: e.target,
    style: {
      lineDash: props.isFieldLevel ? [4, 4] : undefined
    }
  }))
  graph.addItem('nodes', g6Nodes)
  // G6 v4 批量添加需逐个
  g6Edges.forEach(e => graph.addItem('edge', e))
}

const removeDownstream = (nodeId) => {
  if (!graph) return
  // 递归查找下游节点并移除
  const toRemove = new Set()
  const queue = [nodeId]
  while (queue.length) {
    const current = queue.shift()
    const edges = graph.getEdges().filter(e => e.getModel().source === current)
    edges.forEach(e => {
      const target = e.getModel().target
      if (!toRemove.has(target)) {
        toRemove.add(target)
        queue.push(target)
      }
    })
  }
  toRemove.forEach(id => graph.removeItem(id))
}

const handleResize = () => {
  if (graph && graphContainer.value) {
    graph.changeSize(graphContainer.value.offsetWidth, graphContainer.value.offsetHeight || 500)
  }
}

onMounted(() => {
  nextTick(() => initGraph())
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (graph) {
    graph.destroy()
    graph = null
  }
})

watch(() => [props.nodes, props.edges], () => {
  renderGraph()
}, { deep: true })

defineExpose({ addNodes, removeDownstream })
</script>

<style scoped>
.impact-graph-container {
  position: relative;
  height: 500px;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}
.graph-canvas {
  width: 100%;
  height: 100%;
}
</style>
```

- [ ] **步骤 4：实现 ImpactAnalysis.vue 容器页面**

```vue
<template>
  <div class="impact-analysis">
    <ImpactFilter :loading="loading" @analyze="handleAnalyze" />

    <el-alert
      v-if="neo4jError"
      title="Neo4j 服务不可用"
      type="error"
      :description="neo4jError"
      show-icon
      style="margin-bottom: 10px"
    >
      <el-button size="small" @click="handleAnalyze(lastRequest)">重试</el-button>
    </el-alert>

    <ImpactStats :stats="result?.stats" />

    <el-row :gutter="10">
      <el-col :span="18">
        <ImpactGraph
          ref="graphRef"
          :nodes="renderNodes"
          :edges="renderEdges"
          :isFieldLevel="isFieldLevel"
          @nodeClick="handleNodeClick"
          @edgeClick="handleEdgeClick"
        />
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <template #header>详情</template>
          <ImpactNodeDetail
            v-if="selectedNode"
            :node="selectedNode"
            @expand="handleExpand"
            @collapse="handleCollapse"
          />
          <ImpactEdgeDetail
            v-else-if="selectedEdge"
            :edge="selectedEdge"
          />
          <el-empty v-else description="点击节点或边查看详情" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ImpactFilter from '@/components/impact/ImpactFilter.vue'
import ImpactStats from '@/components/impact/ImpactStats.vue'
import ImpactGraph from '@/components/impact/ImpactGraph.vue'
import ImpactNodeDetail from '@/components/impact/ImpactNodeDetail.vue'
import ImpactEdgeDetail from '@/components/impact/ImpactEdgeDetail.vue'
import { analyze, expand } from '@/api/impact'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const result = ref(null)
const neo4jError = ref('')
const lastRequest = ref(null)
const graphRef = ref(null)
const selectedNode = ref(null)
const selectedEdge = ref(null)

// 完整缓存（analyze 返回的全部节点/边）
const allNodes = ref([])
const allEdges = ref([])

// 实际渲染的节点/边（默认仅第 1 层）
const renderNodes = ref([])
const renderEdges = ref([])

const isFieldLevel = computed(() => {
  return lastRequest.value?.fieldName && lastRequest.value.fieldName !== ''
})

const handleAnalyze = async (request) => {
  loading.value = true
  neo4jError.value = ''
  lastRequest.value = request
  try {
    const res = await analyze(request)
    result.value = res.data
    allNodes.value = res.data?.nodes || []
    allEdges.value = res.data?.edges || []

    // 默认仅渲染第 1 层（起点 + 直接相邻节点）
    const startNode = allNodes.value.find(n => n.depth === 0 || n.id === getStartId(request))
    if (startNode) {
      renderNodes.value = [startNode]
      renderEdges.value = []
      // 找直接相邻节点
      const directEdges = allEdges.value.filter(e =>
        e.source === startNode.id || e.target === startNode.id)
      const directNodeIds = new Set([startNode.id])
      directEdges.forEach(e => {
        directNodeIds.add(e.source)
        directNodeIds.add(e.target)
      })
      renderNodes.value = allNodes.value.filter(n => directNodeIds.has(n.id))
      renderEdges.value = directEdges
    } else {
      renderNodes.value = allNodes.value
      renderEdges.value = allEdges.value
    }
  } catch (e) {
    neo4jError.value = e.message || '查询失败'
    ElMessage.error('影响分析查询失败')
  } finally {
    loading.value = false
  }
}

const getStartId = (request) => {
  const schema = request.schema || ''
  const table = request.tableShortName || ''
  const base = schema ? `${schema}.${table}` : table
  if (request.fieldName) {
    return `${base}.${request.fieldName.toUpperCase()}`
  }
  return base.toUpperCase()
}

const handleNodeClick = (node) => {
  selectedNode.value = node
  selectedEdge.value = null
}

const handleEdgeClick = (edge) => {
  selectedEdge.value = edge
  selectedNode.value = null
}

const handleExpand = async (node) => {
  // 先查缓存
  const cachedEdges = allEdges.value.filter(e => e.source === node.id)
  if (cachedEdges.length > 0) {
    const newNodeIds = new Set(renderNodes.value.map(n => n.id))
    cachedEdges.forEach(e => newNodeIds.add(e.target))
    renderNodes.value = allNodes.value.filter(n => newNodeIds.has(n.id))
    renderEdges.value = [...renderEdges.value, ...cachedEdges]
    return
  }

  // 缓存未命中，调 expand
  try {
    const res = await expand({
      nodeId: node.id,
      direction: lastRequest.value?.direction || 'DOWNSTREAM',
      depth: 1
    })
    const newNodes = res.data?.nodes || []
    const newEdges = res.data?.edges || []
    allNodes.value = [...allNodes.value, ...newNodes]
    allEdges.value = [...allEdges.value, ...newEdges]
    renderNodes.value = [...renderNodes.value, ...newNodes]
    renderEdges.value = [...renderEdges.value, ...newEdges]
  } catch (e) {
    ElMessage.error('展开失败')
  }
}

const handleCollapse = (node) => {
  // 前端本地移除下游节点
  if (graphRef.value) {
    graphRef.value.removeDownstream(node.id)
  }
}
</script>

<style scoped>
.impact-analysis {
  padding: 10px;
}
</style>
```

- [ ] **步骤 5：创建 ImpactNodeDetail 和 ImpactEdgeDetail 占位组件**

创建 `frontend/src/components/impact/ImpactNodeDetail.vue`：

```vue
<template>
  <div class="node-detail">
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="ID">{{ node.id }}</el-descriptions-item>
      <el-descriptions-item label="名称">{{ node.label }}</el-descriptions-item>
      <el-descriptions-item label="类型" v-if="node.type">
        <el-tag :type="typeTagType(node.type)" size="small">{{ node.type }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="层级">{{ node.nodeLevel }}</el-descriptions-item>
      <el-descriptions-item label="深度">{{ node.depth ?? '-' }}</el-descriptions-item>
    </el-descriptions>
    <div style="margin-top: 10px; text-align: center">
      <el-button size="small" type="primary" @click="$emit('expand', node)">展开下游</el-button>
      <el-button size="small" @click="$emit('collapse', node)">收起下游</el-button>
    </div>
  </div>
</template>

<script setup>
defineProps({ node: Object })
defineEmits(['expand', 'collapse'])

const typeTagType = (type) => {
  if (type === 'SOURCE') return 'success'
  if (type === 'TEMP') return 'warning'
  if (type === 'TARGET') return 'danger'
  return 'info'
}
</script>
```

创建 `frontend/src/components/impact/ImpactEdgeDetail.vue`：

```vue
<template>
  <div class="edge-detail">
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="源">{{ edge.source }}</el-descriptions-item>
      <el-descriptions-item label="目标">{{ edge.target }}</el-descriptions-item>
      <el-descriptions-item label="操作" v-if="edge.operation">{{ edge.operation }}</el-descriptions-item>
      <el-descriptions-item label="SQL块序号" v-if="edge.sqlBlockSeq">{{ edge.sqlBlockSeq }}</el-descriptions-item>
      <el-descriptions-item label="主表" v-if="edge.mainTable">{{ edge.mainTable }}</el-descriptions-item>
      <el-descriptions-item label="来源表达式" v-if="edge.sourceExpr">{{ edge.sourceExpr }}</el-descriptions-item>
      <el-descriptions-item label="来源别名" v-if="edge.sourceAlias">{{ edge.sourceAlias }}</el-descriptions-item>
    </el-descriptions>

    <div v-if="edge.joinConditions && edge.joinConditions.length" style="margin-top: 10px">
      <div style="font-weight: bold; margin-bottom: 5px">JOIN 条件</div>
      <div v-for="(c, i) in edge.joinConditions" :key="i" class="condition-item">
        {{ c.clause }}
        <div v-if="c.fields && c.fields.length" style="color: #999; font-size: 12px">
          涉及字段: {{ c.fields.join(', ') }}
        </div>
      </div>
    </div>

    <div v-if="edge.whereConditions && edge.whereConditions.length" style="margin-top: 10px">
      <div style="font-weight: bold; margin-bottom: 5px">WHERE 条件</div>
      <div v-for="(c, i) in edge.whereConditions" :key="i" class="condition-item">
        {{ c.clause }}
        <div v-if="c.fields && c.fields.length" style="color: #999; font-size: 12px">
          涉及字段: {{ c.fields.join(', ') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({ edge: Object })
</script>

<style scoped>
.condition-item {
  padding: 4px 8px;
  margin-bottom: 4px;
  background: #f5f5f5;
  border-radius: 2px;
  font-size: 13px;
}
</style>
```

- [ ] **步骤 6：验证前端构建**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend && npm run build
```

预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add frontend/src/views/ImpactAnalysis.vue frontend/src/components/impact/
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 实现前端影响分析页（筛选/统计/G6图/路径展开）"
```

---

## 任务 9：startup.bat 改造与全量验证

**文件：**
- 修改：`startup.bat`

- [ ] **步骤 1：startup.bat 增加 Neo4j 连接检查**

在现有 `startup.bat` 中，在后端启动之前新增 Neo4j 检查：

```bat
REM 检查 Neo4j 连接
echo [检查] Neo4j 连接...
powershell -Command "try { $null = Invoke-WebRequest -Uri http://localhost:7474 -TimeoutSec 3 -UseBasicParsing; Write-Host 'Neo4j 已启动' } catch { Write-Host '[警告] Neo4j 未启动，影响分析功能不可用'; exit 1 }"
if %errorlevel% neq 0 (
    echo [提示] 请确保 Neo4j 已在 localhost:7687 运行
    choice /c yn /m "是否继续启动（仅 MySQL 模式）"
    if errorlevel 2 exit /b 1
)
```

- [ ] **步骤 2：后端全量测试**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -q
```

预期：所有测试 PASS（Neo4j 相关测试用 mock，不依赖真实 Neo4j）。

- [ ] **步骤 3：前端构建**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend && npm run build
```

预期：BUILD SUCCESS。

- [ ] **步骤 4：启动后端并全量重解析**

启动后端后，调用全量重解析接口同步 Neo4j：

```bash
curl -X POST http://localhost:8080/api/proc/extract/all
curl -X POST http://localhost:8080/api/impact/resync/all
```

预期：返回 success，所有 proc 同步成功。

- [ ] **步骤 5：手动验证影响分析页**

打开浏览器 `http://localhost:8080`，点击"影响分析"菜单：

1. schema 下拉加载 FDM/CDM/ODM/PID
2. 选 CDM，表名下拉联动
3. 选 C_AGT_DEPOSIT_ACCT_INF，方向选下游，深度 5
4. 点击"分析"，G6 图展示节点（SOURCE 绿/TEMP 黄/TARGET 红）
5. 点击节点，侧边栏显示详情和"展开下游"按钮
6. 点击"展开下游"，新节点增量添加
7. 点击"收起下游"，下游节点移除
8. 点击边，侧边栏显示 JOIN/WHERE 条件

- [ ] **步骤 6：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add startup.bat
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: startup.bat 增加 Neo4j 连接检查"
```

---

## 任务 10：自动重试定时任务

**文件：**
- 创建：`src/main/java/com/hb/extractor/config/Neo4jSyncRetryScheduler.java`
- 修改：`src/main/java/com/hb/extractor/ProcExtractorApplication.java`（启用定时任务）

- [ ] **步骤 1：ProcExtractorApplication 启用 @EnableScheduling**

在 `ProcExtractorApplication.java` 类注解新增 `@EnableScheduling`：

```java
package com.hb.extractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProcExtractorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProcExtractorApplication.class, args);
    }
}
```

- [ ] **步骤 2：创建 Neo4jSyncRetryScheduler**

```java
package com.hb.extractor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.entity.Neo4jSyncLog;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Neo4j 同步失败自动重试定时任务
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "scheduler.neo4j-retry.enabled", havingValue = "true", matchIfMissing = false)
public class Neo4jSyncRetryScheduler {

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @Value("${impact.sync.max-retry-count:3}")
    private int maxRetryCount;

    @Scheduled(fixedDelayString = "${impact.sync.retry-interval:300000}")
    public void retryFailedSyncs() {
        List<Neo4jSyncLog> failedLogs = neo4jSyncLogMapper.selectList(
                new LambdaQueryWrapper<Neo4jSyncLog>()
                        .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
                        .lt(Neo4jSyncLog::getRetryCount, maxRetryCount));

        if (failedLogs.isEmpty()) return;

        log.info("Neo4j 同步重试: 发现 {} 个失败 proc", failedLogs.size());
        for (Neo4jSyncLog logEntry : failedLogs) {
            try {
                neo4jSyncService.syncProc(logEntry.getProcName());
            } catch (Exception e) {
                log.warn("重试同步失败 proc={}: {}", logEntry.getProcName(), e.getMessage());
            }
        }
    }
}
```

- [ ] **步骤 3：验证编译**

运行：

```bash
D:\HB\maven\bin\mvn.cmd -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git -C d:\HB\work_zuoye\proc-dependency-extractor add src/main/java/com/hb/extractor/config/Neo4jSyncRetryScheduler.java src/main/java/com/hb/extractor/ProcExtractorApplication.java
git -C d:\HB\work_zuoye\proc-dependency-extractor commit -m "feat: 新增 Neo4j 同步失败自动重试定时任务"
```

---

## 自检

### 规格覆盖度

| 规格章节 | 对应任务 |
|---------|---------|
| 2. 整体架构（双写） | 任务 4-5 |
| 3. Neo4j 图模型 | 任务 2-4 |
| 4.1 API 端点（8 个） | 任务 6 |
| 4.4 现有 API 改造 | 任务 5 |
| 5.1-5.3 前端页面 | 任务 7-8 |
| 5.4 G6 配置 | 任务 8 步骤 3 |
| 5.5 错误处理 | 任务 8 步骤 4 |
| 6.1-6.2 同步逻辑 | 任务 4-5 |
| 6.3 失败恢复 | 任务 10 |
| 7.1 后端单元测试 | 任务 4 步骤 4、任务 6 步骤 7 |
| 8.1-8.4 部署配置 | 任务 1-2 |
| 8.5 startup.bat | 任务 9 |

### 占位符扫描

无占位符。所有代码块均包含完整实现。

### 类型一致性

- `SyncResult` 在任务 4 定义，任务 5、6 使用 — 一致
- `ImpactAnalysisVO` 在任务 6 定义，任务 8 前端使用 — 一致
- `Neo4jSyncService.syncProc` 在任务 4 定义，任务 5、6、10 使用 — 一致
- `ImpactAnalysisService.analyze/expand` 在任务 6 定义，任务 8 前端调用 — 一致

### 已知技术风险

1. **Neo4jClient 的 path 查询结果解析**：任务 6 的 `executeAndCollect` 方法对 path 查询的解析较为简化，实际实现可能需要使用 `org.neo4j.driver.types.Path` 类型。备用方案 `collectNodesAndEdges` 分两步查节点和边，更可靠。

2. **Neo4jConfig Bean 冲突**：Spring Boot 3.2.5 的 `spring-boot-starter-data-neo4j` 会自动配置 `Neo4jClient`。任务 2 显式声明 Bean 可能冲突。若编译或启动报错，删除 `Neo4jConfig.neo4jClient()` Bean 方法，仅保留 `@EnableNeo4jRepositories`。

3. **Spring 事务边界**：任务 5 的双写集成在 Controller 层调用，确保 MySQL 事务已提交后再同步 Neo4j。若 `extractAndSave` 的 `@Transactional` 在 Controller 返回后才提交，需改用 `TransactionSynchronizationManager.registerSynchronization`。
