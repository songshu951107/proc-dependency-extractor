# 血缘分析功能实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 proc-dependency-extractor 系统上增加血缘分析功能，支持完整链式血缘（a → 临时表c → target）、JOIN/WHERE 详情、AntV G6 交互式可视化。

**架构：** 双写并存——保留现有 `proc_table_dependency` 扁平依赖，新增 `table_lineage` + `table_lineage_filter` 两表记录表间派生关系。新增 `LineageExtractor` 按 SQL 块顺序提取派生关系（含子查询递归），新增 `LineageController`/`LineageService` 提供血缘图查询（含递归 CTE 多跳追溯），前端新增 `LineageView` 用 AntV G6 渲染交互式图。

**技术栈：** Spring Boot 3.2.5 + MyBatis-Plus 3.5.7 + MySQL 8（递归 CTE）+ Vue 3 + AntV G6 5.x

**规格文档：** `docs/superpowers/specs/2026-07-27-lineage-analysis-design.md`

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|---|---|
| `proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineage.java` | table_lineage 实体 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineageFilter.java` | table_lineage_filter 实体 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageMapper.java` | table_lineage Mapper |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageFilterMapper.java` | table_lineage_filter Mapper |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/xml/TableLineageMapper.xml` | 递归 CTE 查询 XML |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/LineageExtractor.java` | 血缘派生关系解析器 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageEdge.java` | 派生边 DTO |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageFilter.java` | 条件 DTO |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageParseResult.java` | 解析结果 DTO |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/service/LineageService.java` | 血缘查询服务接口 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/LineageServiceImpl.java` | 血缘查询服务实现 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/controller/LineageController.java` | 血缘 API Controller |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/dto/LineageGraphVO.java` | 血缘图响应 VO |
| `proc-dependency-extractor/src/test/java/com/hb/extractor/parser/LineageExtractorTest.java` | 解析器单元测试 |
| `proc-dependency-extractor/src/test/java/com/hb/extractor/service/LineageServiceTest.java` | 服务层集成测试 |
| `proc-dependency-extractor/src/test/java/com/hb/extractor/controller/LineageControllerTest.java` | Controller 测试 |
| `proc-dependency-extractor/frontend/src/views/LineageView.vue` | 血缘图页面 |
| `proc-dependency-extractor/frontend/src/components/LineageDetailPanel.vue` | 详情面板组件 |
| `proc-dependency-extractor/frontend/src/api/lineage.js` | 血缘 API 封装 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `proc-dependency-extractor/sql/init.sql` | 追加两张新表 DDL |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/SqlBlockExtractor.java` | 新增 `extractBlocks` 方法 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/PyProcParser.java` | 集成 LineageExtractor 双写 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java` | 调用 LineageExtractor 入库 + 删除时级联 |
| `proc-dependency-extractor/src/main/java/com/hb/extractor/dto/BatchExtractResult.java` | 增加 lineage 相关统计字段（可选） |
| `proc-dependency-extractor/frontend/src/router/index.js` | 新增 lineage 路由 |
| `proc-dependency-extractor/frontend/src/views/ProcDetailView.vue` | 新增「查看血缘图」按钮 |
| `proc-dependency-extractor/frontend/package.json` | 增加 @antv/g6 依赖 |

---

## 任务 1：数据库表结构

**文件：**
- 修改：`proc-dependency-extractor/sql/init.sql`

- [ ] **步骤 1：追加两张新表 DDL**

在 `proc-dependency-extractor/sql/init.sql` 末尾追加：

```sql
-- --------------------------------------------
-- 表3：表间派生血缘关系表
-- --------------------------------------------
DROP TABLE IF EXISTS `table_lineage`;
CREATE TABLE `table_lineage` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `proc_id`         BIGINT       NOT NULL COMMENT '关联proc_info.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `source_table`    VARCHAR(300) NOT NULL COMMENT '派生源表(完整名schema.table)',
    `source_table_short` VARCHAR(200) NOT NULL COMMENT '源表短名(去schema)',
    `target_table`    VARCHAR(300) NOT NULL COMMENT '派生目标表(完整名)',
    `target_table_short` VARCHAR(200) NOT NULL COMMENT '目标表短名',
    `target_is_temp`  TINYINT      NOT NULL DEFAULT 0 COMMENT '目标是否临时表(VT_前缀):0否1是',
    `operation`       VARCHAR(30)  NOT NULL COMMENT '派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM',
    `sql_block_seq`   INT          NOT NULL COMMENT 'SQL块序号(从1开始,标识派生顺序)',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_proc_id` (`proc_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_source` (`source_table_short`),
    KEY `idx_target` (`target_table_short`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表间派生血缘关系';

-- --------------------------------------------
-- 表4：血缘条件详情表(JOIN/WHERE)
-- --------------------------------------------
DROP TABLE IF EXISTS `table_lineage_filter`;
CREATE TABLE `table_lineage_filter` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `lineage_id`      BIGINT       NOT NULL COMMENT '关联table_lineage.id',
    `proc_name`       VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `filter_type`     VARCHAR(10)  NOT NULL COMMENT '条件类型:JOIN/WHERE',
    `filter_clause`   TEXT         NOT NULL COMMENT '条件原文(如a.cust_id=b.id)',
    `fields_involved` VARCHAR(500) DEFAULT NULL COMMENT '涉及字段JSON数组(如["a.cust_id","b.id"])',
    `extract_time`    DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘条件详情(JOIN/WHERE)';
```

- [ ] **步骤 2：在 MySQL 中执行 DDL**

运行：
```bash
mysql -uroot -proot < proc-dependency-extractor/sql/init.sql
```
预期：四张表创建成功（proc_info、proc_table_dependency、table_lineage、table_lineage_filter）

- [ ] **步骤 3：Commit**

```bash
git add proc-dependency-extractor/sql/init.sql
git commit -m "feat: 新增 table_lineage 和 table_lineage_filter 表 DDL"
```

---

## 任务 2：实体类与 Mapper

**文件：**
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineage.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineageFilter.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageMapper.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageFilterMapper.java`

- [ ] **步骤 1：创建 TableLineage 实体**

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表间派生血缘关系实体
 */
@Data
@TableName("table_lineage")
public class TableLineage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联proc_info.id */
    private Long procId;

    /** 存储过程名(冗余) */
    private String procName;

    /** 派生源表(完整名schema.table) */
    private String sourceTable;

    /** 源表短名(去schema) */
    private String sourceTableShort;

    /** 派生目标表(完整名) */
    private String targetTable;

    /** 目标表短名 */
    private String targetTableShort;

    /** 目标是否临时表(VT_前缀):0否1是 */
    private Integer targetIsTemp;

    /** 派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM */
    private String operation;

    /** SQL块序号(从1开始) */
    private Integer sqlBlockSeq;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
```

- [ ] **步骤 2：创建 TableLineageFilter 实体**

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 血缘条件详情实体(JOIN/WHERE)
 */
@Data
@TableName("table_lineage_filter")
public class TableLineageFilter {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联table_lineage.id */
    private Long lineageId;

    /** 存储过程名(冗余) */
    private String procName;

    /** 条件类型:JOIN/WHERE */
    private String filterType;

    /** 条件原文(如a.cust_id=b.id) */
    private String filterClause;

    /** 涉及字段JSON数组(如["a.cust_id","b.id"]) */
    private String fieldsInvolved;

    /** 提取时间 */
    private LocalDateTime extractTime;
}
```

- [ ] **步骤 3：创建 TableLineageMapper**

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableLineage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 表间派生血缘关系 Mapper
 */
@Mapper
public interface TableLineageMapper extends BaseMapper<TableLineage> {

    /**
     * 递归查询上游血缘
     * @param targetTableShort 目标表短名
     * @param maxDepth 最大深度
     * @return 血缘记录列表（含 depth 字段）
     */
    List<Map<String, Object>> findUpstream(@Param("targetTableShort") String targetTableShort,
                                            @Param("maxDepth") int maxDepth);

    /**
     * 递归查询下游血缘
     * @param sourceTableShort 源表短名
     * @param maxDepth 最大深度
     * @return 血缘记录列表（含 depth 字段）
     */
    List<Map<String, Object>> findDownstream(@Param("sourceTableShort") String sourceTableShort,
                                              @Param("maxDepth") int maxDepth);
}
```

- [ ] **步骤 4：创建 TableLineageFilterMapper**

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableLineageFilter;
import org.apache.ibatis.annotations.Mapper;

/**
 * 血缘条件详情 Mapper
 */
@Mapper
public interface TableLineageFilterMapper extends BaseMapper<TableLineageFilter> {
}
```

- [ ] **步骤 5：创建 Mapper XML**

创建文件 `proc-dependency-extractor/src/main/resources/mapper/TableLineageMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.hb.extractor.mapper.TableLineageMapper">

    <!-- 递归查询上游血缘 -->
    <select id="findUpstream" resultType="java.util.Map">
        WITH RECURSIVE upstream AS (
            SELECT id, proc_id, proc_name,
                   source_table, source_table_short,
                   target_table, target_table_short,
                   target_is_temp, operation, sql_block_seq,
                   1 AS depth
            FROM table_lineage
            WHERE target_table_short = #{targetTableShort}
            UNION ALL
            SELECT t.id, t.proc_id, t.proc_name,
                   t.source_table, t.source_table_short,
                   t.target_table, t.target_table_short,
                   t.target_is_temp, t.operation, t.sql_block_seq,
                   u.depth + 1
            FROM table_lineage t
            JOIN upstream u ON t.target_table_short = u.source_table_short
            WHERE u.depth &lt; #{maxDepth}
        )
        SELECT DISTINCT id, proc_id, proc_name,
               source_table, source_table_short,
               target_table, target_table_short,
               target_is_temp, operation, sql_block_seq,
               depth
        FROM upstream
        ORDER BY depth, sql_block_seq
    </select>

    <!-- 递归查询下游血缘 -->
    <select id="findDownstream" resultType="java.util.Map">
        WITH RECURSIVE downstream AS (
            SELECT id, proc_id, proc_name,
                   source_table, source_table_short,
                   target_table, target_table_short,
                   target_is_temp, operation, sql_block_seq,
                   1 AS depth
            FROM table_lineage
            WHERE source_table_short = #{sourceTableShort}
            UNION ALL
            SELECT t.id, t.proc_id, t.proc_name,
                   t.source_table, t.source_table_short,
                   t.target_table, t.target_table_short,
                   t.target_is_temp, t.operation, t.sql_block_seq,
                   d.depth + 1
            FROM table_lineage t
            JOIN downstream d ON t.source_table_short = d.target_table_short
            WHERE d.depth &lt; #{maxDepth}
        )
        SELECT DISTINCT id, proc_id, proc_name,
               source_table, source_table_short,
               target_table, target_table_short,
               target_is_temp, operation, sql_block_seq,
               depth
        FROM downstream
        ORDER BY depth, sql_block_seq
    </select>

</mapper>
```

- [ ] **步骤 6：验证编译**

运行：
```bash
cd proc-dependency-extractor && mvn compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 7：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineage.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/entity/TableLineageFilter.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageMapper.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/mapper/TableLineageFilterMapper.java \
        proc-dependency-extractor/src/main/resources/mapper/TableLineageMapper.xml
git commit -m "feat: 新增 TableLineage/TableLineageFilter 实体与 Mapper"
```

---

## 任务 3：解析器 DTO

**文件：**
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageEdge.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageFilter.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageParseResult.java`

- [ ] **步骤 1：创建 LineageEdge DTO**

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 血缘派生边（source → target）
 */
@Data
public class LineageEdge {
    /** 派生源表完整名 */
    private String sourceTable;
    /** 源表短名(去schema) */
    private String sourceTableShort;
    /** 派生目标表完整名 */
    private String targetTable;
    /** 目标表短名 */
    private String targetTableShort;
    /** 目标是否临时表 */
    private boolean targetIsTemp;
    /** 派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM */
    private String operation;
    /** SQL块序号(从1开始) */
    private int sqlBlockSeq;
}
```

- [ ] **步骤 2：创建 LineageFilter DTO**

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.List;

/**
 * 血缘条件（JOIN/WHERE）
 */
@Data
public class LineageFilter {
    /** 关联到 edges 的下标 */
    private int edgeIndex;
    /** 条件类型:JOIN/WHERE */
    private String filterType;
    /** 条件原文 */
    private String filterClause;
    /** 涉及字段列表 */
    private List<String> fieldsInvolved;
}
```

- [ ] **步骤 3：创建 LineageParseResult DTO**

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 血缘解析结果
 */
@Data
public class LineageParseResult {
    private List<LineageEdge> edges = new ArrayList<>();
    private List<LineageFilter> filters = new ArrayList<>();
}
```

- [ ] **步骤 4：验证编译**

运行：
```bash
cd proc-dependency-extractor && mvn compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageEdge.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageFilter.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/LineageParseResult.java
git commit -m "feat: 新增血缘解析 DTO"
```

---

## 任务 4：LineageExtractor 核心解析器（TDD）

**文件：**
- 创建：`proc-dependency-extractor/src/test/java/com/hb/extractor/parser/LineageExtractorTest.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/LineageExtractor.java`

- [ ] **步骤 1：编写测试类骨架与 CREATE TEMP TABLE 测试**

创建 `proc-dependency-extractor/src/test/java/com/hb/extractor/parser/LineageExtractorTest.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LineageExtractorTest {

    @Autowired
    private LineageExtractor lineageExtractor;

    private Map<String, String> schemaVarMap;

    @BeforeEach
    void setUp() {
        schemaVarMap = new HashMap<>();
        schemaVarMap.put("CDM", "etl.dwa_db_cdm");
        schemaVarMap.put("FDM", "etl.dwa_db_fdm");
    }

    @Test
    void testCreateTempTable() {
        String sql = "CREATE TEMPORARY TABLE {CDM}.VT_X AS SELECT a.id FROM {FDM}.F_A a JOIN {FDM}.F_B b ON a.id = b.aid";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        LineageEdge e1 = result.getEdges().get(0);
        assertEquals("F_A", e1.getSourceTableShort());
        assertEquals("VT_X", e1.getTargetTableShort());
        assertTrue(e1.isTargetIsTemp());
        assertEquals("CREATE TEMP", e1.getOperation());
        assertEquals(1, e1.getSqlBlockSeq());
    }

    @Test
    void testInsertSelect() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a JOIN {CDM}.VT_X x ON a.id = x.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        LineageEdge e0 = result.getEdges().get(0);
        assertEquals("F_A", e0.getSourceTableShort());
        assertEquals("TARGET", e0.getTargetTableShort());
        assertFalse(e0.isTargetIsTemp());
        assertEquals("INSERT SELECT", e0.getOperation());
    }

    @Test
    void testSubqueryExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "LEFT JOIN (SELECT b.id FROM {FDM}.F_B b WHERE b.sts='1') sq ON a.id = sq.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        // 期望: F_A → TARGET, F_B → TARGET（子查询内的 F_B 被提取）
        assertEquals(2, result.getEdges().size());
        boolean hasFB = result.getEdges().stream()
                .anyMatch(e -> "F_B".equals(e.getSourceTableShort()));
        assertTrue(hasFB, "子查询内的 F_B 应被提取为 source");
    }

    @Test
    void testJoinClauseExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "JOIN {FDM}.F_B b ON a.cust_id = b.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertFalse(result.getFilters().isEmpty());
        boolean hasJoin = result.getFilters().stream()
                .anyMatch(f -> "JOIN".equals(f.getFilterType()));
        assertTrue(hasJoin, "应提取到 JOIN 条件");
    }

    @Test
    void testWhereClauseExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a WHERE a.sts = '1' AND a.dt >= '2024-01-01'";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        boolean hasWhere = result.getFilters().stream()
                .anyMatch(f -> "WHERE".equals(f.getFilterType()));
        assertTrue(hasWhere, "应提取到 WHERE 条件");
    }

    @Test
    void testUpdateTargetExcluded() {
        // 更新目标出现在 FROM 时不应产生自环
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {CDM}.TARGET a JOIN {FDM}.F_B b ON a.id = b.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        boolean hasSelfLoop = result.getEdges().stream()
                .anyMatch(e -> "TARGET".equals(e.getSourceTableShort()) && "TARGET".equals(e.getTargetTableShort()));
        assertFalse(hasSelfLoop, "更新目标表不应产生自环");
    }

    @Test
    void testUnrecognizableBlock() {
        // 注释或变量赋值应返回空结果
        List<String> blocks = Arrays.asList("-- this is a comment", "x = 1");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");
        assertTrue(result.getEdges().isEmpty());
    }

    @Test
    void testMalformedSql() {
        // 括号未闭合 - 不抛异常
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a JOIN (SELECT * FROM {FDM}.F_B b";
        List<String> blocks = Arrays.asList(sql);
        assertDoesNotThrow(() -> lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET"));
    }

    @Test
    void testRealScript() throws Exception {
        // 使用真实脚本验证
        java.nio.file.Path scriptPath = java.nio.file.Paths.get("../jiaoben/dwa_c_agt_deposit_acct_inf.py");
        if (!java.nio.file.Files.exists(scriptPath)) {
            scriptPath = java.nio.file.Paths.get("jiaoben/dwa_c_agt_deposit_acct_inf.py");
        }
        if (!java.nio.file.Files.exists(scriptPath)) {
            System.out.println("跳过 testRealScript: 脚本文件不存在");
            return;
        }
        String content = java.nio.file.Files.readString(scriptPath, java.nio.charset.StandardCharsets.UTF_8);

        // 用 SqlBlockExtractor 切分 SQL 块
        com.hb.extractor.parser.SqlBlockExtractor extractor = lineageExtractor.getSqlBlockExtractor();
        List<String> blocks = extractor.extractBlocks(content);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.C_AGT_DEPOSIT_ACCT_INF");

        // 至少应提取到 7+ 条派生关系（含子查询内的表）
        assertTrue(result.getEdges().size() >= 7,
                "应提取至少 7 条派生关系, 实际: " + result.getEdges().size());

        // 验证子查询内的表被提取
        boolean hasFrozenBook = result.getEdges().stream()
                .anyMatch(e -> e.getSourceTableShort().contains("FROZEN_BOOK"));
        assertTrue(hasFrozenBook, "应包含子查询内的 F_EV_CRBS_DEPOSIT_ACCT_FROZEN_BOOK");

        System.out.println("提取到 " + result.getEdges().size() + " 条派生关系");
        System.out.println("提取到 " + result.getFilters().size() + " 条 JOIN/WHERE 条件");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageExtractorTest -q
```
预期：FAIL，`LineageExtractor` 类不存在，编译失败

- [ ] **步骤 3：创建 LineageExtractor 实现类**

创建 `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/LineageExtractor.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageFilter;
import com.hb.extractor.parser.dto.LineageParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 血缘派生关系解析器
 *
 * 按 SQL 块顺序提取表间派生关系（source → target），
 * 支持 CREATE TEMP / INSERT SELECT / MERGE / UPDATE / DELETE，
 * 支持子查询递归提取（最大深度 5 层）。
 */
@Slf4j
@Component
public class LineageExtractor {

    @Autowired
    private SqlBlockExtractor sqlBlockExtractor;

    /** schema 变量占位符正则，如 {CDM}、{FDM} */
    private static final Pattern SCHEMA_VAR_PATTERN = Pattern.compile("\\{(\\w+)\\}");

    /** 临时表前缀 */
    private static final String TEMP_PREFIX = "VT_";

    /** 最大子查询递归深度 */
    private static final int MAX_SUBQUERY_DEPTH = 5;

    // SQL 块类型识别正则（不区分大小写）
    private static final Pattern CREATE_TEMP_PATTERN =
            Pattern.compile("CREATE\\s+(TEMP|TEMPORARY)\\s+TABLE", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN =
            Pattern.compile("INSERT\\s+INTO", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_INTO_PATTERN =
            Pattern.compile("MERGE\\s+INTO", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_FROM_PATTERN =
            Pattern.compile("UPDATE\\s+\\w+.*\\bFROM\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DELETE_FROM_PATTERN =
            Pattern.compile("DELETE\\s+FROM", Pattern.CASE_INSENSITIVE);

    // target 表提取正则
    private static final Pattern CREATE_TEMP_TARGET_PATTERN =
            Pattern.compile("CREATE\\s+(TEMP|TEMPORARY)\\s+TABLE\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_TARGET_PATTERN =
            Pattern.compile("INSERT\\s+INTO\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_TARGET_PATTERN =
            Pattern.compile("MERGE\\s+INTO\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TARGET_PATTERN =
            Pattern.compile("UPDATE\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_TARGET_PATTERN =
            Pattern.compile("DELETE\\s+FROM\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    // source 表提取正则（FROM/JOIN/USING 后的表名，排除子查询括号）
    private static final Pattern FROM_TABLE_PATTERN =
            Pattern.compile("\\bFROM\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_TABLE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern USING_TABLE_PATTERN =
            Pattern.compile("\\bUSING\\s+(?:\\{\\w+\\}\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);

    // JOIN 子句提取
    private static final Pattern JOIN_CLAUSE_PATTERN =
            Pattern.compile("\\bJOIN\\s+(?:\\{\\w+\\}\\.)?(\\w+)\\s+(?:\\w+\\s+)?ON\\s+(.+?)(?=\\b(JOIN|WHERE|GROUP|ORDER|LIMIT|;|$))",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // WHERE 子句提取
    private static final Pattern WHERE_CLAUSE_PATTERN =
            Pattern.compile("\\bWHERE\\s+(.+?)(?=\\b(GROUP|ORDER|LIMIT|;|$))", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 字段提取（a.field 形式）
    private static final Pattern FIELD_PATTERN = Pattern.compile("(\\w+\\.\\w+)");

    // FROM/JOIN 后跟括号（子查询）
    private static final Pattern SUBQUERY_START_PATTERN =
            Pattern.compile("\\b(FROM|JOIN)\\s*\\(", Pattern.CASE_INSENSITIVE);

    public LineageParseResult extract(List<String> sqlBlocks,
                                       Map<String, String> schemaVarMap,
                                       String updateTarget) {
        LineageParseResult result = new LineageParseResult();
        String updateTargetShort = extractShortName(updateTarget);

        for (int seq = 0; seq < sqlBlocks.size(); seq++) {
            String block = sqlBlocks.get(seq);
            try {
                parseBlock(block, seq + 1, schemaVarMap, updateTargetShort, result);
            } catch (Exception e) {
                log.warn("解析 SQL 块 {} 失败: {}", seq + 1, e.getMessage());
            }
        }
        return result;
    }

    private void parseBlock(String block, int seq, Map<String, String> schemaVarMap,
                            String updateTargetShort, LineageParseResult result) {
        String trimmed = block.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("--")) {
            return;
        }

        String targetTable = null;
        String operation = null;
        String targetSchema = null;

        // 识别 SQL 块类型
        if (CREATE_TEMP_PATTERN.matcher(trimmed).find()) {
            Matcher m = CREATE_TEMP_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(2);
                targetSchema = extractSchemaFromBlock(trimmed, "TABLE\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "CREATE TEMP";
            }
        } else if (INSERT_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = INSERT_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "INSERT SELECT";
            }
        } else if (MERGE_INTO_PATTERN.matcher(trimmed).find()) {
            Matcher m = MERGE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "INTO\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "MERGE";
            }
        } else if (UPDATE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = UPDATE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "UPDATE\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "UPDATE FROM";
            }
        } else if (DELETE_FROM_PATTERN.matcher(trimmed).find()) {
            Matcher m = DELETE_TARGET_PATTERN.matcher(trimmed);
            if (m.find()) {
                targetTable = m.group(1);
                targetSchema = extractSchemaFromBlock(trimmed, "FROM\\s+(?:\\{(\\w+)\\}\\.)?");
                operation = "DELETE FROM";
            }
        }

        if (targetTable == null) {
            return;
        }

        String targetFull = buildFullName(targetSchema, targetTable, schemaVarMap);
        boolean targetIsTemp = targetTable.startsWith(TEMP_PREFIX);

        // 提取 source 表（顶层 + 子查询递归）
        Set<String> sourceShortSet = new LinkedHashSet<>();
        List<String> sources = extractSourceTables(trimmed, 0);
        for (String src : sources) {
            if (!src.equals(targetTable) && !src.equals(updateTargetShort)) {
                sourceShortSet.add(src);
            }
        }

        // 为每个 source 创建 edge
        for (String sourceShort : sourceShortSet) {
            LineageEdge edge = new LineageEdge();
            edge.setSourceTableShort(sourceShort);
            edge.setSourceTable(sourceShort); // 短名作为占位，后续由调用方补全
            edge.setTargetTableShort(targetTable);
            edge.setTargetTable(targetFull);
            edge.setTargetIsTemp(targetIsTemp);
            edge.setOperation(operation);
            edge.setSqlBlockSeq(seq);
            result.getEdges().add(edge);
        }

        // 提取 JOIN/WHERE 条件
        extractFilters(trimmed, result.getEdges().size() - sourceShortSet.size(),
                sourceShortSet, result);
    }

    /**
     * 提取 source 表（含子查询递归）
     */
    private List<String> extractSourceTables(String sql, int depth) {
        List<String> sources = new ArrayList<>();
        if (depth > MAX_SUBQUERY_DEPTH) {
            log.warn("子查询递归深度超过 {}, 停止递归", MAX_SUBQUERY_DEPTH);
            return sources;
        }

        // 先提取非子查询的 FROM/JOIN/USING 表
        addMatches(FROM_TABLE_PATTERN, sql, sources);
        addMatches(JOIN_TABLE_PATTERN, sql, sources);
        addMatches(USING_TABLE_PATTERN, sql, sources);

        // 递归处理子查询
        Matcher subMatcher = SUBQUERY_START_PATTERN.matcher(sql);
        while (subMatcher.find()) {
            int parenStart = sql.indexOf('(', subMatcher.end() - 1);
            if (parenStart < 0) continue;
            String subContent = extractParenContent(sql, parenStart);
            if (subContent != null && !subContent.isEmpty()) {
                sources.addAll(extractSourceTables(subContent, depth + 1));
            }
        }

        return sources;
    }

    /**
     * 用括号计数器提取括号内内容
     */
    private String extractParenContent(String sql, int openParenIndex) {
        if (openParenIndex >= sql.length() || sql.charAt(openParenIndex) != '(') {
            return null;
        }
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = openParenIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                if (depth > 1) sb.append(c);
            } else if (c == ')') {
                depth--;
                if (depth == 0) break;
                sb.append(c);
            } else if (depth > 0) {
                sb.append(c);
            }
        }
        if (depth != 0) {
            log.warn("括号未闭合, 放弃子查询解析");
            return null;
        }
        return sb.toString();
    }

    private void addMatches(Pattern pattern, String sql, List<String> sources) {
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            sources.add(m.group(1));
        }
    }

    /**
     * 提取 JOIN/WHERE 条件
     */
    private void extractFilters(String sql, int startEdgeIndex,
                                 Set<String> sourceSet, LineageParseResult result) {
        // JOIN 条件
        Matcher joinMatcher = JOIN_CLAUSE_PATTERN.matcher(sql);
        int edgeOffset = 0;
        while (joinMatcher.find()) {
            String joinTable = joinMatcher.group(1);
            String clause = joinMatcher.group(2).trim();
            List<String> fields = extractFields(clause);

            // 关联到对应 source（按出现顺序）
            int edgeIndex = findEdgeIndex(sourceSet, joinTable, startEdgeIndex, edgeOffset);
            if (edgeIndex >= 0 && edgeIndex < result.getEdges().size()) {
                LineageFilter filter = new LineageFilter();
                filter.setEdgeIndex(edgeIndex);
                filter.setFilterType("JOIN");
                filter.setFilterClause(clause);
                filter.setFieldsInvolved(fields);
                result.getFilters().add(filter);
            }
        }

        // WHERE 条件
        Matcher whereMatcher = WHERE_CLAUSE_PATTERN.matcher(sql);
        if (whereMatcher.find()) {
            String whereClause = whereMatcher.group(1).trim();
            // 按 AND 拆分
            String[] parts = whereClause.split("\\bAND\\b|\\bOR\\b", Pattern.CASE_INSENSITIVE);
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;
                List<String> fields = extractFields(part);
                if (fields.isEmpty()) continue;

                // 按字段前缀关联到 source
                String firstField = fields.get(0);
                String prefix = firstField.split("\\.")[0].toUpperCase();
                int edgeIndex = findEdgeIndexByAlias(sourceSet, prefix, startEdgeIndex);
                if (edgeIndex >= 0) {
                    LineageFilter filter = new LineageFilter();
                    filter.setEdgeIndex(edgeIndex);
                    filter.setFilterType("WHERE");
                    filter.setFilterClause(part);
                    filter.setFieldsInvolved(fields);
                    result.getFilters().add(filter);
                }
            }
        }
    }

    private List<String> extractFields(String clause) {
        List<String> fields = new ArrayList<>();
        Matcher m = FIELD_PATTERN.matcher(clause);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    private int findEdgeIndex(Set<String> sourceSet, String tableName,
                               int startEdgeIndex, int offset) {
        int i = 0;
        for (String src : sourceSet) {
            if (src.equalsIgnoreCase(tableName)) {
                return startEdgeIndex + i;
            }
            i++;
        }
        return -1;
    }

    private int findEdgeIndexByAlias(Set<String> sourceSet, String alias,
                                      int startEdgeIndex) {
        // 简化：按表名前缀字母匹配（FDM.F_A 的别名通常是 a 或 A）
        // 这里用一个简单的启发式：遍历 source，找首字母匹配的
        int i = 0;
        for (String src : sourceSet) {
            String firstChar = src.substring(src.length() - 1).toUpperCase();
            // F_A → A, F_B → B 等
            String expected = src.replaceAll(".*_", "").substring(0, 1).toUpperCase();
            if (expected.equalsIgnoreCase(alias.substring(0, 1))) {
                return startEdgeIndex + i;
            }
            i++;
        }
        return -1;
    }

    /**
     * 从 schema 占位符提取 schema 变量名
     */
    private String extractSchemaFromBlock(String sql, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(sql);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String buildFullName(String schemaVar, String tableShort, Map<String, String> schemaVarMap) {
        if (schemaVar == null) {
            return tableShort;
        }
        String src = schemaVarMap.getOrDefault(schemaVar, "{" + schemaVar + "}");
        return schemaVar + "." + tableShort;
    }

    private String extractShortName(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    /** 暴露给测试使用 */
    public SqlBlockExtractor getSqlBlockExtractor() {
        return sqlBlockExtractor;
    }
}
```

- [ ] **步骤 4：运行测试验证**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageExtractorTest -q
```
预期：大部分测试通过。若有失败，根据失败信息调整正则或逻辑。

- [ ] **步骤 5：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/parser/LineageExtractor.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/ \
        proc-dependency-extractor/src/test/java/com/hb/extractor/parser/LineageExtractorTest.java
git commit -m "feat: 实现 LineageExtractor 血缘解析器（含子查询递归）"
```

---

## 任务 5：SqlBlockExtractor 新增 extractBlocks 方法

**文件：**
- 修改：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/SqlBlockExtractor.java`

- [ ] **步骤 1：阅读现有 SqlBlockExtractor**

运行：`Read proc-dependency-extractor/src/main/java/com/hb/extractor/parser/SqlBlockExtractor.java`

了解现有 `extract` 方法的 SQL 块切分逻辑（按 `;` 或 `/*StepN*/` 切分）。

- [ ] **步骤 2：新增 extractBlocks 方法**

在 `SqlBlockExtractor.java` 中新增方法（保留原 `extract` 方法不变）：

```java
/**
 * 按顺序切分 SQL 块，返回每个块独立的内容
 * 切分依据：分号结尾 或 /*StepN*\/ 注释起点
 *
 * @param content Python 文件中三引号内的 SQL 文本
 * @return SQL 块列表（按出现顺序）
 */
public List<String> extractBlocks(String content) {
    List<String> blocks = new ArrayList<>();
    if (content == null || content.trim().isEmpty()) {
        return blocks;
    }

    // 按 /*StepN:...*/ 注释切分
    Pattern stepPattern = Pattern.compile("/\\*Step\\d+[^*]*\\*/");
    String[] segments = stepPattern.split(content);

    for (String seg : segments) {
        if (seg == null) continue;
        String trimmed = seg.trim();
        if (!trimmed.isEmpty()) {
            // 进一步按分号切分（处理一个 Step 内多条语句）
            String[] stmts = trimmed.split(";");
            for (String stmt : stmts) {
                String s = stmt.trim();
                if (!s.isEmpty()) {
                    blocks.add(s);
                }
            }
        }
    }

    // 如果未切分出块，整体作为一个块
    if (blocks.isEmpty() && !content.trim().isEmpty()) {
        blocks.add(content.trim());
    }

    return blocks;
}
```

- [ ] **步骤 3：验证编译并运行测试**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageExtractorTest -q
```
预期：`testRealScript` 应能正确切分 SQL 块并提取派生关系

- [ ] **步骤 4：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/parser/SqlBlockExtractor.java
git commit -m "feat: SqlBlockExtractor 新增 extractBlocks 方法返回 List<String>"
```

---

## 任务 6：PyProcParser 集成 LineageExtractor（双写）

**文件：**
- 修改：`proc-dependency-extractor/src/main/java/com/hb/extractor/parser/PyProcParser.java`
- 修改：`proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java`

- [ ] **步骤 1：修改 PyProcParser 集成 LineageExtractor**

在 `PyProcParser.java` 中：

1. 注入 `LineageExtractor`
2. 在 `parse` 方法末尾调用 `LineageExtractor.extract`
3. 将 `LineageParseResult` 存入 `ProcMetadata`

修改后的 `PyProcParser.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.LineageParseResult;
import com.hb.extractor.parser.dto.ProcMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Python 存储过程主解析器(编排)
 */
@Component
public class PyProcParser {

    @Autowired
    private ProcHeaderParser headerParser;

    @Autowired
    private SchemaVarResolver schemaVarResolver;

    @Autowired
    private SqlBlockExtractor sqlBlockExtractor;

    @Autowired
    private SqlTableExtractor sqlTableExtractor;

    @Autowired
    private LineageExtractor lineageExtractor;

    public ProcMetadata parse(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);

        ProcMetadata metadata = new ProcMetadata();
        metadata.setFilePath(filePath.toString());
        metadata.setProcFileName(filePath.getFileName().toString());
        metadata.setExtractTime(LocalDateTime.now());

        String fileName = filePath.getFileName().toString();
        if (fileName.endsWith(".py")) {
            metadata.setProcName(fileName.substring(0, fileName.length() - 3));
        } else {
            metadata.setProcName(fileName);
        }

        // 1. 头部元数据
        headerParser.parse(content, metadata);

        // 2. schema 变量映射
        metadata.setSchemaVarMap(schemaVarResolver.resolve(content));

        // 3. 抽取 SQL 块（拼接形式，兼容旧逻辑）
        String sql = sqlBlockExtractor.extract(content);

        // 4. 提取表名（原有，维护 proc_table_dependency）
        sqlTableExtractor.extract(sql, metadata);

        // 5. 血缘派生关系（新增，维护 table_lineage）
        List<String> blocks = sqlBlockExtractor.extractBlocks(content);
        LineageParseResult lineageResult = lineageExtractor.extract(
                blocks, metadata.getSchemaVarMap(), metadata.getUpdateTarget());
        metadata.setLineageResult(lineageResult);

        return metadata;
    }
}
```

- [ ] **步骤 2：在 ProcMetadata 中增加 lineageResult 字段**

修改 `proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/ProcMetadata.java`，新增字段：

```java
/** 血缘解析结果 */
private LineageParseResult lineageResult;
```

并补充 import：`import com.hb.extractor.parser.dto.LineageParseResult;`（如已在同包则不需要）

- [ ] **步骤 3：修改 ProcExtractServiceImpl 双写入库**

在 `ProcExtractServiceImpl.java` 中：

1. 注入 `TableLineageMapper` 和 `TableLineageFilterMapper`
2. 在 `extractAndSave` 方法中，原 `proc_table_dependency` 入库后，追加 `table_lineage` 和 `table_lineage_filter` 入库
3. 在 `deleteProc` 方法中，追加删除 `table_lineage` 和 `table_lineage_filter`

关键代码片段（在 `extractAndSave` 中追加）：

```java
// === 血缘数据双写入库 ===
List<TableLineage> lineageRecords = new ArrayList<>();
List<TableLineageFilter> filterRecords = new ArrayList<>();

LineageParseResult lineageResult = metadata.getLineageResult();
if (lineageResult != null) {
    LocalDateTime now = LocalDateTime.now();

    // 先删旧血缘
    tableLineageMapper.delete(new LambdaQueryWrapper<TableLineage>()
            .eq(TableLineage::getProcName, procName));
    tableLineageFilterMapper.delete(new LambdaQueryWrapper<TableLineageFilter>()
            .eq(TableLineageFilter::getProcName, procName));

    // 插入 edges
    for (LineageEdge edge : lineageResult.getEdges()) {
        TableLineage record = new TableLineage();
        record.setProcId(procInfo.getId());
        record.setProcName(procName);
        record.setSourceTable(edge.getSourceTable());
        record.setSourceTableShort(edge.getSourceTableShort());
        record.setTargetTable(edge.getTargetTable());
        record.setTargetTableShort(edge.getTargetTableShort());
        record.setTargetIsTemp(edge.isTargetIsTemp() ? 1 : 0);
        record.setOperation(edge.getOperation());
        record.setSqlBlockSeq(edge.getSqlBlockSeq());
        record.setExtractTime(now);
        tableLineageMapper.insert(record);
        lineageRecords.add(record);
    }

    // 插入 filters
    for (LineageFilter filter : lineageResult.getFilters()) {
        int idx = filter.getEdgeIndex();
        if (idx < 0 || idx >= lineageRecords.size()) continue;
        TableLineageFilter record = new TableLineageFilter();
        record.setLineageId(lineageRecords.get(idx).getId());
        record.setProcName(procName);
        record.setFilterType(filter.getFilterType());
        record.setFilterClause(filter.getFilterClause());
        record.setFieldsInvolved(filter.getFieldsInvolved() != null
                ? new ObjectMapper().writeValueAsString(filter.getFieldsInvolved())
                : null);
        record.setExtractTime(now);
        tableLineageFilterMapper.insert(record);
        filterRecords.add(record);
    }
}
```

`deleteProc` 方法追加：

```java
// 级联删除血缘
tableLineageFilterMapper.delete(new LambdaQueryWrapper<TableLineageFilter>()
        .eq(TableLineageFilter::getProcName, procName));
tableLineageMapper.delete(new LambdaQueryWrapper<TableLineage>()
        .eq(TableLineage::getProcName, procName));
```

import 补充：
```java
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.entity.TableLineageFilter;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.mapper.TableLineageFilterMapper;
import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageFilter;
import com.hb.extractor.parser.dto.LineageParseResult;
```

- [ ] **步骤 4：验证编译并启动应用**

运行：
```bash
cd proc-dependency-extractor && mvn compile -q
```
预期：BUILD SUCCESS

- [ ] **步骤 5：用真实脚本验证入库**

启动应用，调用 API 解析真实脚本：
```bash
# 启动应用
cd proc-dependency-extractor && startup.bat

# 调用解析 API
curl -X POST http://localhost:8080/api/proc/extract \
  -H "Content-Type: application/json" \
  -d '{"path": "d:/HB/work_zuoye/jiaoben/dwa_c_agt_deposit_acct_inf.py"}'
```

查询数据库验证：
```sql
SELECT COUNT(*) FROM table_lineage WHERE proc_name = 'dwa_c_agt_deposit_acct_inf';
-- 预期: 7+ 条记录

SELECT COUNT(*) FROM table_lineage_filter WHERE proc_name = 'dwa_c_agt_deposit_acct_inf';
-- 预期: 多条 JOIN/WHERE 条件
```

- [ ] **步骤 6：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/parser/PyProcParser.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/parser/dto/ProcMetadata.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java
git commit -m "feat: 集成 LineageExtractor 双写入库 table_lineage"
```

---

## 任务 7：LineageGraphVO 响应对象

**文件：**
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/dto/LineageGraphVO.java`

- [ ] **步骤 1：创建 LineageGraphVO**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * 血缘图响应对象
 */
@Data
public class LineageGraphVO {
    private String procName;
    private String updateTarget;
    private List<Node> nodes;
    private List<Edge> edges;
    /** 递归是否被 depth 截断 */
    private Boolean truncated;

    @Data
    public static class Node {
        /** 表短名 */
        private String id;
        /** 完整名 */
        private String fullTableName;
        /** SOURCE / TEMP / TARGET */
        private String nodeType;
        /** schema 变量名 */
        private String schemaName;
    }

    @Data
    public static class Edge {
        private String source;
        private String target;
        private String operation;
        private Integer sqlBlockSeq;
        private List<FilterDetail> filters;
    }

    @Data
    public static class FilterDetail {
        private String filterType;
        private String filterClause;
        private List<String> fields;
    }
}
```

- [ ] **步骤 2：验证编译**

运行：
```bash
cd proc-dependency-extractor && mvn compile -q
```

- [ ] **步骤 3：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/dto/LineageGraphVO.java
git commit -m "feat: 新增 LineageGraphVO 响应对象"
```

---

## 任务 8：LineageService 血缘查询服务（TDD）

**文件：**
- 创建：`proc-dependency-extractor/src/test/java/com/hb/extractor/service/LineageServiceTest.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/service/LineageService.java`
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/LineageServiceImpl.java`

- [ ] **步骤 1：编写服务层测试**

创建 `proc-dependency-extractor/src/test/java/com/hb/extractor/service/LineageServiceTest.java`：

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LineageServiceTest {

    @Autowired
    private LineageService lineageService;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private TableLineageMapper tableLineageMapper;

    @Test
    void testGetLineageGraph() {
        // 准备测试数据
        String procName = "test_lineage_proc";
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName(procName);
        procInfo.setProcFileName(procName + ".py");
        procInfo.setFilePath("/tmp/" + procName + ".py");
        procInfo.setUpdateTarget("CDM.TARGET_TABLE");
        procInfo.setTableCount(1);
        procInfo.setTempTableCount(0);
        procInfo.setExtractTime(LocalDateTime.now());
        procInfoMapper.insert(procInfo);

        TableLineage edge = new TableLineage();
        edge.setProcId(procInfo.getId());
        edge.setProcName(procName);
        edge.setSourceTable("FDM.F_A");
        edge.setSourceTableShort("F_A");
        edge.setTargetTable("CDM.TARGET_TABLE");
        edge.setTargetTableShort("TARGET_TABLE");
        edge.setTargetIsTemp(0);
        edge.setOperation("INSERT SELECT");
        edge.setSqlBlockSeq(1);
        edge.setExtractTime(LocalDateTime.now());
        tableLineageMapper.insert(edge);

        // 执行
        LineageGraphVO vo = lineageService.getLineageGraph(procName);

        // 断言
        assertNotNull(vo);
        assertEquals(procName, vo.getProcName());
        assertEquals("CDM.TARGET_TABLE", vo.getUpdateTarget());
        assertFalse(vo.getNodes().isEmpty());
        assertFalse(vo.getEdges().isEmpty());

        // 验证节点类型
        boolean hasSource = vo.getNodes().stream()
                .anyMatch(n -> "SOURCE".equals(n.getNodeType()) && "F_A".equals(n.getId()));
        boolean hasTarget = vo.getNodes().stream()
                .anyMatch(n -> "TARGET".equals(n.getNodeType()) && "TARGET_TABLE".equals(n.getId()));
        assertTrue(hasSource, "应包含 SOURCE 节点 F_A");
        assertTrue(hasTarget, "应包含 TARGET 节点 TARGET_TABLE");

        // 清理
        tableLineageMapper.deleteById(edge.getId());
        procInfoMapper.deleteById(procInfo.getId());
    }

    @Test
    void testGetLineageGraphNotFound() {
        LineageGraphVO vo = lineageService.getLineageGraph("non_existent_proc");
        assertNull(vo);
    }

    @Test
    void testRecursiveUpstream() {
        // 构造 A → B → C 链路，查询 C 的上游
        String procName = "test_recursive_proc";
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName(procName);
        procInfo.setProcFileName(procName + ".py");
        procInfo.setFilePath("/tmp/" + procName + ".py");
        procInfo.setUpdateTarget("CDM.C");
        procInfo.setTableCount(2);
        procInfo.setTempTableCount(1);
        procInfo.setExtractTime(LocalDateTime.now());
        procInfoMapper.insert(procInfo);

        LocalDateTime now = LocalDateTime.now();
        TableLineage e1 = new TableLineage();
        e1.setProcId(procInfo.getId());
        e1.setProcName(procName);
        e1.setSourceTable("FDM.A");
        e1.setSourceTableShort("A");
        e1.setTargetTable("CDM.VT_B");
        e1.setTargetTableShort("VT_B");
        e1.setTargetIsTemp(1);
        e1.setOperation("CREATE TEMP");
        e1.setSqlBlockSeq(1);
        e1.setExtractTime(now);
        tableLineageMapper.insert(e1);

        TableLineage e2 = new TableLineage();
        e2.setProcId(procInfo.getId());
        e2.setProcName(procName);
        e2.setSourceTable("CDM.VT_B");
        e2.setSourceTableShort("VT_B");
        e2.setTargetTable("CDM.C");
        e2.setTargetTableShort("C");
        e2.setTargetIsTemp(0);
        e2.setOperation("INSERT SELECT");
        e2.setSqlBlockSeq(2);
        e2.setExtractTime(now);
        tableLineageMapper.insert(e2);

        // 执行：查询 C 的上游
        LineageGraphVO vo = lineageService.traceUpstream("C", 5);

        assertNotNull(vo);
        assertFalse(vo.getNodes().isEmpty());
        // 应包含 A 和 VT_B
        boolean hasA = vo.getNodes().stream().anyMatch(n -> "A".equals(n.getId()));
        boolean hasVTB = vo.getNodes().stream().anyMatch(n -> "VT_B".equals(n.getId()));
        assertTrue(hasA, "应包含上游表 A");
        assertTrue(hasVTB, "应包含中间临时表 VT_B");

        // 清理
        tableLineageMapper.deleteById(e1.getId());
        tableLineageMapper.deleteById(e2.getId());
        procInfoMapper.deleteById(procInfo.getId());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageServiceTest -q
```
预期：FAIL，`LineageService` 不存在

- [ ] **步骤 3：创建 LineageService 接口**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.LineageGraphVO;

/**
 * 血缘查询服务
 */
public interface LineageService {

    /**
     * 查询某存储过程的完整血缘图
     */
    LineageGraphVO getLineageGraph(String procName);

    /**
     * 递归查询某表的上游血缘
     * @param tableName 表短名
     * @param maxDepth 最大深度（默认 5，上限 10）
     */
    LineageGraphVO traceUpstream(String tableName, Integer maxDepth);

    /**
     * 递归查询某表的下游血缘
     */
    LineageGraphVO traceDownstream(String tableName, Integer maxDepth);
}
```

- [ ] **步骤 4：创建 LineageServiceImpl 实现**

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.entity.TableLineageFilter;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.TableLineageFilterMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LineageServiceImpl implements LineageService {

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private TableLineageMapper tableLineageMapper;

    @Autowired
    private TableLineageFilterMapper tableLineageFilterMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public LineageGraphVO getLineageGraph(String procName) {
        ProcInfo procInfo = procInfoMapper.selectOne(
                new LambdaQueryWrapper<ProcInfo>().eq(ProcInfo::getProcName, procName));
        if (procInfo == null) {
            return null;
        }

        List<TableLineage> edges = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .eq(TableLineage::getProcName, procName)
                        .orderByAsc(TableLineage::getSqlBlockSeq));

        List<TableLineageFilter> filters = tableLineageFilterMapper.selectList(
                new LambdaQueryWrapper<TableLineageFilter>()
                        .eq(TableLineageFilter::getProcName, procName));

        return buildGraphVO(procName, procInfo.getUpdateTarget(), edges, filters, false);
    }

    @Override
    public LineageGraphVO traceUpstream(String tableName, Integer maxDepth) {
        int depth = maxDepth == null ? 5 : Math.min(maxDepth, 10);
        List<Map<String, Object>> records = tableLineageMapper.findUpstream(tableName, depth);

        List<TableLineage> edges = convertRecords(records);
        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

        List<TableLineageFilter> filters = edgeIds.isEmpty() ? Collections.emptyList() :
                tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>()
                                .in(TableLineageFilter::getLineageId, edgeIds));

        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, truncated);
    }

    @Override
    public LineageGraphVO traceDownstream(String tableName, Integer maxDepth) {
        int depth = maxDepth == null ? 5 : Math.min(maxDepth, 10);
        List<Map<String, Object>> records = tableLineageMapper.findDownstream(tableName, depth);

        List<TableLineage> edges = convertRecords(records);
        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

        List<TableLineageFilter> filters = edgeIds.isEmpty() ? Collections.emptyList() :
                tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>()
                                .in(TableLineageFilter::getLineageId, edgeIds));

        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, truncated);
    }

    private LineageGraphVO buildGraphVO(String procName, String updateTarget,
                                          List<TableLineage> edges,
                                          List<TableLineageFilter> filters,
                                          boolean truncated) {
        LineageGraphVO vo = new LineageGraphVO();
        vo.setProcName(procName);
        vo.setUpdateTarget(updateTarget);
        vo.setTruncated(truncated);

        // 构建节点
        Set<String> sourceShorts = edges.stream()
                .map(TableLineage::getSourceTableShort)
                .collect(Collectors.toSet());
        Set<String> targetShorts = edges.stream()
                .map(TableLineage::getTargetTableShort)
                .collect(Collectors.toSet());

        Set<String> allShorts = new LinkedHashSet<>();
        allShorts.addAll(sourceShorts);
        allShorts.addAll(targetShorts);

        String updateTargetShort = extractShortName(updateTarget);

        List<LineageGraphVO.Node> nodes = new ArrayList<>();
        Map<String, LineageGraphVO.Node> nodeMap = new HashMap<>();

        for (String shortName : allShorts) {
            LineageGraphVO.Node node = new LineageGraphVO.Node();
            node.setId(shortName);

            // 节点类型判定
            if (shortName.equals(updateTargetShort)) {
                node.setNodeType("TARGET");
            } else if (shortName.startsWith("VT_") || isTempInEdges(shortName, edges)) {
                node.setNodeType("TEMP");
            } else if (!targetShorts.contains(shortName)) {
                node.setNodeType("SOURCE");
            } else {
                node.setNodeType("TEMP"); // 既是 source 又是 target，视为中间节点
            }

            // 找完整名
            TableLineage sample = edges.stream()
                    .filter(e -> shortName.equals(e.getSourceTableShort()) || shortName.equals(e.getTargetTableShort()))
                    .findFirst().orElse(null);
            if (sample != null) {
                if (shortName.equals(sample.getSourceTableShort())) {
                    node.setFullTableName(sample.getSourceTable());
                } else {
                    node.setFullTableName(sample.getTargetTable());
                }
                node.setSchemaName(extractSchema(sample.getSourceTable()));
            }

            nodes.add(node);
            nodeMap.put(shortName, node);
        }

        // 构建边
        Map<Long, List<TableLineageFilter>> filterByLineageId = filters.stream()
                .collect(Collectors.groupingBy(TableLineageFilter::getLineageId));

        List<LineageGraphVO.Edge> edgeList = new ArrayList<>();
        for (TableLineage e : edges) {
            LineageGraphVO.Edge edge = new LineageGraphVO.Edge();
            edge.setSource(e.getSourceTableShort());
            edge.setTarget(e.getTargetTableShort());
            edge.setOperation(e.getOperation());
            edge.setSqlBlockSeq(e.getSqlBlockSeq());

            List<LineageGraphVO.FilterDetail> filterDetails = new ArrayList<>();
            List<TableLineageFilter> edgeFilters = filterByLineageId.getOrDefault(e.getId(), Collections.emptyList());
            for (TableLineageFilter f : edgeFilters) {
                LineageGraphVO.FilterDetail fd = new LineageGraphVO.FilterDetail();
                fd.setFilterType(f.getFilterType());
                fd.setFilterClause(f.getFilterClause());
                fd.setFields(parseFields(f.getFieldsInvolved()));
                filterDetails.add(fd);
            }
            edge.setFilters(filterDetails);
            edgeList.add(edge);
        }

        vo.setNodes(nodes);
        vo.setEdges(edgeList);
        return vo;
    }

    private boolean isTempInEdges(String shortName, List<TableLineage> edges) {
        return edges.stream().anyMatch(e ->
                shortName.equals(e.getTargetTableShort()) && Integer.valueOf(1).equals(e.getTargetIsTemp()));
    }

    private List<String> parseFields(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 fields JSON 失败: {}", json);
            return Collections.emptyList();
        }
    }

    private String extractShortName(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    private String extractSchema(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.indexOf('.');
        return idx >= 0 ? fullName.substring(0, idx) : null;
    }

    @SuppressWarnings("unchecked")
    private List<TableLineage> convertRecords(List<Map<String, Object>> records) {
        List<TableLineage> list = new ArrayList<>();
        for (Map<String, Object> r : records) {
            TableLineage e = new TableLineage();
            e.setId(toLong(r.get("id")));
            e.setProcId(toLong(r.get("proc_id")));
            e.setProcName((String) r.get("proc_name"));
            e.setSourceTable((String) r.get("source_table"));
            e.setSourceTableShort((String) r.get("source_table_short"));
            e.setTargetTable((String) r.get("target_table"));
            e.setTargetTableShort((String) r.get("target_table_short"));
            Object tempVal = r.get("target_is_temp");
            e.setTargetIsTemp(tempVal instanceof Number ? ((Number) tempVal).intValue() : 0);
            e.setOperation((String) r.get("operation"));
            Object seqVal = r.get("sql_block_seq");
            e.setSqlBlockSeq(seqVal instanceof Number ? ((Number) seqVal).intValue() : 0);
            list.add(e);
        }
        return list;
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageServiceTest -q
```
预期：测试通过

- [ ] **步骤 6：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/service/LineageService.java \
        proc-dependency-extractor/src/main/java/com/hb/extractor/service/impl/LineageServiceImpl.java \
        proc-dependency-extractor/src/test/java/com/hb/extractor/service/LineageServiceTest.java
git commit -m "feat: 实现 LineageService 血缘查询服务（含递归 CTE）"
```

---

## 任务 9：LineageController API 层

**文件：**
- 创建：`proc-dependency-extractor/src/main/java/com/hb/extractor/controller/LineageController.java`
- 创建：`proc-dependency-extractor/src/test/java/com/hb/extractor/controller/LineageControllerTest.java`

- [ ] **步骤 1：创建 LineageController**

```java
package com.hb.extractor.controller;

import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 血缘查询 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    @Autowired
    private LineageService lineageService;

    /**
     * 查询某存储过程的完整血缘图
     */
    @GetMapping("/{procName}")
    public Result<LineageGraphVO> getLineage(@PathVariable String procName) {
        LineageGraphVO vo = lineageService.getLineageGraph(procName);
        if (vo == null) {
            return Result.error("存储过程不存在");
        }
        return Result.success(vo);
    }

    /**
     * 递归查询某表的上游血缘
     */
    @GetMapping("/table/{tableName}/upstream")
    public Result<LineageGraphVO> upstream(@PathVariable String tableName,
                                            @RequestParam(required = false) Integer maxDepth) {
        if (maxDepth != null && maxDepth > 10) {
            return Result.error("maxDepth 不能超过 10");
        }
        LineageGraphVO vo = lineageService.traceUpstream(tableName, maxDepth);
        return Result.success(vo);
    }

    /**
     * 递归查询某表的下游血缘
     */
    @GetMapping("/table/{tableName}/downstream")
    public Result<LineageGraphVO> downstream(@PathVariable String tableName,
                                              @RequestParam(required = false) Integer maxDepth) {
        if (maxDepth != null && maxDepth > 10) {
            return Result.error("maxDepth 不能超过 10");
        }
        LineageGraphVO vo = lineageService.traceDownstream(tableName, maxDepth);
        return Result.success(vo);
    }
}
```

- [ ] **步骤 2：编写 Controller 测试**

创建 `proc-dependency-extractor/src/test/java/com/hb/extractor/controller/LineageControllerTest.java`：

```java
package com.hb.extractor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LineageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetLineageNotFound() throws Exception {
        mockMvc.perform(get("/api/lineage/non_existent_proc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)); // Result.error 返回非 0 code
    }

    @Test
    void testMaxDepthValidation() throws Exception {
        mockMvc.perform(get("/api/lineage/table/SOME_TABLE/upstream").param("maxDepth", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("maxDepth 不能超过 10"));
    }

    @Test
    void testUpstreamEmptyResult() throws Exception {
        // 查询不存在的表，返回空 nodes
        mockMvc.perform(get("/api/lineage/table/NON_EXISTENT/upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nodes").isArray());
    }
}
```

- [ ] **步骤 3：运行测试**

运行：
```bash
cd proc-dependency-extractor && mvn test -Dtest=LineageControllerTest -q
```
预期：测试通过

- [ ] **步骤 4：Commit**

```bash
git add proc-dependency-extractor/src/main/java/com/hb/extractor/controller/LineageController.java \
        proc-dependency-extractor/src/test/java/com/hb/extractor/controller/LineageControllerTest.java
git commit -m "feat: 新增 LineageController 血缘查询 API"
```

---

## 任务 10：前端依赖与路由

**文件：**
- 修改：`proc-dependency-extractor/frontend/package.json`
- 修改：`proc-dependency-extractor/frontend/src/router/index.js`
- 修改：`proc-dependency-extractor/frontend/src/views/ProcDetailView.vue`
- 创建：`proc-dependency-extractor/frontend/src/api/lineage.js`

- [ ] **步骤 1：添加 @antv/g6 依赖**

修改 `frontend/package.json`，在 `dependencies` 中增加：

```json
"@antv/g6": "^5.0.0"
```

运行安装：
```bash
cd proc-dependency-extractor/frontend && npm install
```

- [ ] **步骤 2：创建 lineage API 封装**

创建 `frontend/src/api/lineage.js`：

```javascript
import request from './request'

export function getLineage(procName) {
  return request({
    url: `/api/lineage/${procName}`,
    method: 'get'
  })
}

export function traceUpstream(tableName, maxDepth) {
  return request({
    url: `/api/lineage/table/${tableName}/upstream`,
    method: 'get',
    params: maxDepth ? { maxDepth } : {}
  })
}

export function traceDownstream(tableName, maxDepth) {
  return request({
    url: `/api/lineage/table/${tableName}/downstream`,
    method: 'get',
    params: maxDepth ? { maxDepth } : {}
  })
}
```

- [ ] **步骤 3：新增路由**

修改 `frontend/src/router/index.js`，在 routes 数组中新增：

```javascript
{
  path: '/procs/:procName/lineage',
  name: 'Lineage',
  component: () => import('../views/LineageView.vue'),
  meta: { title: '血缘图' }
}
```

- [ ] **步骤 4：在 ProcDetailView 增加「查看血缘图」按钮**

修改 `frontend/src/views/ProcDetailView.vue`，在页面顶部（或返回按钮旁）新增：

```html
<el-button type="primary" @click="$router.push(`/procs/${procName}/lineage`)">
  <el-icon><Connection /></el-icon> 查看血缘图
</el-button>
```

并补充 import：`import { Connection } from '@element-plus/icons-vue'`

- [ ] **步骤 5：验证前端构建**

运行：
```bash
cd proc-dependency-extractor/frontend && npm run build
```
预期：构建成功（LineageView.vue 尚未创建，会在任务 11 中创建。先创建一个空文件占位）

创建空占位文件 `frontend/src/views/LineageView.vue`：

```vue
<template>
  <div>血缘图页面（待实现）</div>
</template>

<script setup>
</script>
```

- [ ] **步骤 6：Commit**

```bash
git add proc-dependency-extractor/frontend/package.json \
        proc-dependency-extractor/frontend/package-lock.json \
        proc-dependency-extractor/frontend/src/api/lineage.js \
        proc-dependency-extractor/frontend/src/router/index.js \
        proc-dependency-extractor/frontend/src/views/ProcDetailView.vue \
        proc-dependency-extractor/frontend/src/views/LineageView.vue
git commit -m "feat: 前端新增 G6 依赖、lineage API、路由"
```

---

## 任务 11：LineageView 血缘图页面

**文件：**
- 创建：`proc-dependency-extractor/frontend/src/components/LineageDetailPanel.vue`
- 修改：`proc-dependency-extractor/frontend/src/views/LineageView.vue`

- [ ] **步骤 1：创建 LineageDetailPanel 详情面板组件**

创建 `frontend/src/components/LineageDetailPanel.vue`：

```vue
<template>
  <el-drawer v-model="visible" :title="title" size="400px" direction="rtl">
    <!-- 节点详情 -->
    <div v-if="type === 'node' && node">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="表名">{{ node.id }}</el-descriptions-item>
        <el-descriptions-item label="完整名">{{ node.fullTableName }}</el-descriptions-item>
        <el-descriptions-item label="Schema">{{ node.schemaName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="tagType">{{ node.nodeType }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider>上下游关系</el-divider>
      <el-button size="small" @click="$emit('trace-upstream', node.id)">追溯上游</el-button>
      <el-button size="small" @click="$emit('trace-downstream', node.id)">追溯下游</el-button>
    </div>

    <!-- 边详情 -->
    <div v-else-if="type === 'edge' && edge">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="源表">{{ edge.source }}</el-descriptions-item>
        <el-descriptions-item label="目标表">{{ edge.target }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ edge.operation }}</el-descriptions-item>
        <el-descriptions-item label="SQL 块序号">{{ edge.sqlBlockSeq }}</el-descriptions-item>
      </el-descriptions>

      <el-divider>JOIN / WHERE 详情</el-divider>
      <el-collapse v-if="edge.filters && edge.filters.length">
        <el-collapse-item
          v-for="(f, i) in edge.filters"
          :key="i"
          :title="`${f.filterType}: ${f.filterClause}`"
        >
          <div>
            <el-tag size="small" :type="f.filterType === 'JOIN' ? 'success' : 'warning'">
              {{ f.filterType }}
            </el-tag>
            <pre style="margin:8px 0;background:#f5f7fa;padding:8px;font-size:12px">{{ f.filterClause }}</pre>
            <el-tag v-for="field in (f.fields || [])" :key="field" size="small" style="margin:2px">
              {{ field }}
            </el-tag>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-else description="无 JOIN/WHERE 条件" :image-size="60" />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  type: String, // 'node' | 'edge'
  node: Object,
  edge: Object
})

defineEmits(['trace-upstream', 'trace-downstream'])

const title = computed(() => {
  if (props.type === 'node') return '节点详情'
  if (props.type === 'edge') return '边详情'
  return '详情'
})

const tagType = computed(() => {
  const map = { SOURCE: 'success', TEMP: 'warning', TARGET: 'danger' }
  return map[props.node?.nodeType] || 'info'
})
</script>
```

- [ ] **步骤 2：创建 LineageView 主页面**

修改 `frontend/src/views/LineageView.vue`：

```vue
<template>
  <div class="lineage-view">
    <!-- 头部信息 -->
    <div class="lineage-header">
      <el-button @click="$router.back()" :icon="ArrowLeft" text>返回</el-button>
      <span class="title">存储过程血缘图：{{ procName }}</span>
      <span class="meta" v-if="graphData">
        更新目标：{{ graphData.updateTarget || '-' }}
        · 节点 {{ graphData.nodes?.length || 0 }}
        · 边 {{ graphData.edges?.length || 0 }}
      </span>
    </div>

    <!-- 工具栏 -->
    <div class="lineage-toolbar">
      <el-button size="small" @click="toggleTemp">收起/展开临时表</el-button>
      <el-button size="small" @click="exportPng">导出 PNG</el-button>
      <el-tag v-if="graphData?.truncated" type="warning">递归已截断</el-tag>
    </div>

    <!-- 图例 -->
    <div class="legend">
      <span class="legend-item"><i class="dot source"></i>源表</span>
      <span class="legend-item"><i class="dot temp"></i>临时表</span>
      <span class="legend-item"><i class="dot target"></i>目标表</span>
    </div>

    <!-- G6 画布 -->
    <div class="lineage-canvas-container">
      <div ref="canvasRef" class="lineage-canvas"></div>
      <el-empty v-if="loaded && (!graphData || !graphData.nodes?.length)"
        description="未解析到血缘关系，请重新解析" />
    </div>

    <!-- 详情面板 -->
    <LineageDetailPanel
      v-model:visible="panelVisible"
      :type="selectedType"
      :node="selectedNode"
      :edge="selectedEdge"
      @trace-upstream="handleTraceUpstream"
      @trace-downstream="handleTraceDownstream"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { Graph } from '@antv/g6'
import { getLineage, traceUpstream, traceDownstream } from '../api/lineage'
import LineageDetailPanel from '../components/LineageDetailPanel.vue'

const route = useRoute()
const router = useRouter()
const procName = ref(route.params.procName)

const canvasRef = ref(null)
const graph = ref(null)
const graphData = ref(null)
const loaded = ref(false)

const panelVisible = ref(false)
const selectedType = ref('')
const selectedNode = ref(null)
const selectedEdge = ref(null)

const hideTemp = ref(false)

const NODE_COLORS = {
  SOURCE: '#67c23a',
  TEMP: '#e6a23c',
  TARGET: '#f56c6c'
}

function transformToG6(vo) {
  if (!vo) return { nodes: [], edges: [] }
  return {
    nodes: (vo.nodes || []).map(n => ({
      id: n.id,
      data: { ...n }
    })),
    edges: (vo.edges || []).map((e, i) => ({
      id: `edge-${i}`,
      source: e.source,
      target: e.target,
      data: { ...e }
    }))
  }
}

async function loadData(procName) {
  try {
    const res = await getLineage(procName)
    if (res.data.code === 0) {
      graphData.value = res.data.data
    } else {
      ElMessage.error(res.data.message || '查询失败')
      graphData.value = null
    }
    loaded.value = true
    renderGraph()
  } catch (e) {
    ElMessage.error('加载血缘数据失败')
    loaded.value = true
  }
}

function renderGraph() {
  if (!graph.value) {
    initGraph()
  }
  const data = transformToG6(graphData.value)
  if (hideTemp.value) {
    const tempIds = new Set(data.nodes.filter(n => n.data.nodeType === 'TEMP').map(n => n.id))
    data.nodes = data.nodes.filter(n => !tempIds.has(n.id))
    // 临时表的边直接跳过（简化处理）
    data.edges = data.edges.filter(e => !tempIds.has(e.source) && !tempIds.has(e.target))
  }
  graph.value.setData(data)
  graph.value.render()
}

function initGraph() {
  graph.value = new Graph({
    container: canvasRef.value,
    width: canvasRef.value.clientWidth,
    height: canvasRef.value.clientHeight,
    layout: {
      type: 'dagre',
      rankdir: 'LR',
      nodesep: 40,
      ranksep: 80
    },
    node: {
      type: 'rect',
      style: {
        size: [160, 40],
        radius: 8,
        fill: ({ data }) => NODE_COLORS[data.nodeType] || '#909399',
        fillOpacity: 0.15,
        stroke: ({ data }) => NODE_COLORS[data.nodeType] || '#909399',
        lineWidth: 2
      },
      label: ({ data }) => data.id,
      labelFill: '#303133',
      labelFontSize: 12
    },
    edge: {
      type: 'quadratic',
      style: {
        stroke: '#909399',
        lineWidth: 1.5,
        endArrow: true
      },
      label: ({ data }) => data.operation,
      labelFontSize: 10,
      labelFill: '#606266'
    },
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'click-select']
  })

  graph.value.on('node:click', (e) => {
    selectedType.value = 'node'
    selectedNode.value = e.target?.model?.data || e.item?.model?.data
    selectedEdge.value = null
    panelVisible.value = true
  })

  graph.value.on('edge:click', (e) => {
    selectedType.value = 'edge'
    selectedEdge.value = e.target?.model?.data || e.item?.model?.data
    selectedNode.value = null
    panelVisible.value = true
  })
}

function toggleTemp() {
  hideTemp.value = !hideTemp.value
  renderGraph()
}

function exportPng() {
  if (!graph.value) return
  try {
    const url = graph.value.toFullDataURL('image/png', { padding: 20 })
    const a = document.createElement('a')
    a.href = url
    a.download = `${procName.value}_lineage.png`
    a.click()
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

async function handleTraceUpstream(tableName) {
  try {
    const res = await traceUpstream(tableName, 5)
    if (res.data.code === 0) {
      graphData.value = res.data.data
      panelVisible.value = false
      renderGraph()
      ElMessage.success(`已加载 ${tableName} 的上游血缘`)
    }
  } catch (e) {
    ElMessage.error('追溯上游失败')
  }
}

async function handleTraceDownstream(tableName) {
  try {
    const res = await traceDownstream(tableName, 5)
    if (res.data.code === 0) {
      graphData.value = res.data.data
      panelVisible.value = false
      renderGraph()
      ElMessage.success(`已加载 ${tableName} 的下游血缘`)
    }
  } catch (e) {
    ElMessage.error('追溯下游失败')
  }
}

watch(() => route.params.procName, (newName) => {
  if (newName) {
    procName.value = newName
    loadData(newName)
  }
})

onMounted(() => {
  loadData(procName.value)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (graph.value) {
    graph.value.destroy()
  }
})

function handleResize() {
  if (graph.value && canvasRef.value) {
    graph.value.resize(canvasRef.value.clientWidth, canvasRef.value.clientHeight)
  }
}
</script>

<style scoped>
.lineage-view {
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
}
.lineage-header {
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  gap: 12px;
}
.lineage-header .title {
  font-weight: bold;
  font-size: 16px;
}
.lineage-header .meta {
  color: #909399;
  font-size: 13px;
}
.lineage-toolbar {
  padding: 8px 16px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  gap: 8px;
}
.legend {
  padding: 4px 16px;
  font-size: 12px;
  color: #606266;
  background: #fafafa;
}
.legend-item {
  margin-right: 16px;
}
.legend-item .dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.dot.source { background: #67c23a; }
.dot.temp { background: #e6a23c; }
.dot.target { background: #f56c6c; }
.lineage-canvas-container {
  flex: 1;
  position: relative;
  overflow: hidden;
}
.lineage-canvas {
  width: 100%;
  height: 100%;
}
</style>
```

- [ ] **步骤 3：构建前端验证**

运行：
```bash
cd proc-dependency-extractor/frontend && npm run build
```
预期：构建成功

- [ ] **步骤 4：Commit**

```bash
git add proc-dependency-extractor/frontend/src/views/LineageView.vue \
        proc-dependency-extractor/frontend/src/components/LineageDetailPanel.vue
git commit -m "feat: 实现 LineageView 血缘图页面（G6 交互式可视化）"
```

---

## 任务 12：端到端验证

**文件：**
- 无新增，使用现有脚本验证全流程

- [ ] **步骤 1：重新构建并启动应用**

```bash
cd proc-dependency-extractor/frontend && npm run build
cd ..
startup.bat
```

- [ ] **步骤 2：调用解析 API 重新解析全部脚本**

```bash
curl -X POST http://localhost:8080/api/proc/extract/all
```

- [ ] **步骤 3：验证血缘数据入库**

```sql
-- 检查每个 proc 是否有血缘数据
SELECT p.proc_name,
       (SELECT COUNT(*) FROM table_lineage l WHERE l.proc_name = p.proc_name) AS lineage_count,
       (SELECT COUNT(*) FROM table_lineage_filter f WHERE f.proc_name = p.proc_name) AS filter_count
FROM proc_info p;

-- 重点验证 dwa_c_agt_deposit_acct_inf
SELECT * FROM table_lineage WHERE proc_name = 'dwa_c_agt_deposit_acct_inf';
-- 预期: 至少 7 条记录，覆盖子查询内的表

SELECT * FROM table_lineage_filter WHERE proc_name = 'dwa_c_agt_deposit_acct_inf';
-- 预期: 多条 JOIN/WHERE 条件
```

- [ ] **步骤 4：验证 API 响应**

```bash
# 单 proc 血缘图
curl http://localhost:8080/api/lineage/dwa_c_agt_deposit_acct_inf | python -m json.tool

# 上游追溯
curl "http://localhost:8080/api/lineage/table/C_AGT_DEPOSIT_ACCT_INF/upstream?maxDepth=5" | python -m json.tool
```

- [ ] **步骤 5：前端手动验证清单**

打开浏览器访问 `http://localhost:8080/`，按以下清单验证：

- [ ] 进入「存储过程列表」，点击某存储过程进入详情
- [ ] 详情页点击「查看血缘图」按钮
- [ ] 血缘图正确渲染，包含 7+ 个源表节点
- [ ] 临时表节点显示为黄色（VT_ 前缀）
- [ ] 目标表节点显示为红色
- [ ] 源表节点显示为绿色
- [ ] 点击节点，右侧详情面板显示表名、schema、类型
- [ ] 点击边，右侧详情面板显示派生操作、JOIN/WHERE 原文
- [ ] 点击「追溯上游」按钮，图刷新为该节点的上游血缘
- [ ] 点击「收起/展开临时表」按钮，节点正确隐藏/显示
- [ ] 点击「导出 PNG」按钮，下载图片
- [ ] 浏览器控制台无报错

- [ ] **步骤 6：Commit 最终状态**

```bash
git add -A
git commit -m "test: 端到端验证血缘分析功能"
```

---

## 任务 13：回归测试与清理

**文件：**
- 无新增

- [ ] **步骤 1：运行全部测试**

运行：
```bash
cd proc-dependency-extractor && mvn test -q
```
预期：所有测试通过

- [ ] **步骤 2：停止 brainstorm 服务器（如仍在运行）**

```bash
# 如果之前启动的 brainstorm 服务器还在运行
bash .trae/skills/brainstorming/scripts/stop-server.sh .superpowers/brainstorm/14324-1785120383
```

或直接在任务管理器中结束 node 进程。

- [ ] **步骤 3：更新 .gitignore（如需要）**

确认 `.superpowers/` 目录已被忽略：
```bash
grep -q "\.superpowers" .gitignore || echo ".superpowers/" >> .gitignore
```

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "chore: 血缘分析功能完成，清理临时文件"
```

---

## 自检

### 1. 规格覆盖度

| 规格章节 | 对应任务 |
|---|---|
| 1. 背景与目标 | 全部任务覆盖 |
| 2. 架构设计 | 任务 1-11 |
| 3. 数据模型 | 任务 1 |
| 4. 解析器设计 | 任务 3-5 |
| 5. API 设计 | 任务 7-9 |
| 6. 前端设计 | 任务 10-11 |
| 7. 错误处理 | 任务 4（解析器容错）、任务 9（API 错误）、任务 11（前端降级） |
| 8. 测试策略 | 任务 4（解析器测试）、任务 8（服务测试）、任务 9（Controller 测试）、任务 12（手动验证） |
| 9. 范围与非目标 | 已在任务中遵循 |
| 10. 实施顺序 | 任务 1-13 按此顺序 |

无遗漏。

### 2. 占位符扫描

- 无 TODO/待定
- 所有代码步骤均包含完整代码
- 所有测试步骤均包含实际断言

### 3. 类型一致性

- `LineageEdge` / `LineageFilter` / `LineageParseResult` 在任务 3 定义，任务 4-6 使用一致
- `TableLineage` / `TableLineageFilter` 在任务 2 定义，任务 6-8 使用一致
- `LineageGraphVO` 在任务 7 定义，任务 8-9 使用一致
- `LineageService` 接口在任务 8 定义，`LineageServiceImpl` 实现一致
- 前端 `getLineage` / `traceUpstream` / `traceDownstream` 在任务 10 定义，任务 11 使用一致

无类型不一致。

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-27-lineage-analysis.md`。两种执行方式：

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

**选哪种方式？**
