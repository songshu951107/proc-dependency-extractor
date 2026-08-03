# 报表影响分析实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 解析帆软报表文件（.cpt/.frm/.xml）的数据集 SQL 和单元格绑定，建立"报表→数据集→表→字段"完整字段链路，支持按 schema/表名/字段名查询影响的报表及字段口径溯源。

**架构：** 后端新增 ReportSqlParser + CptXmlParser 解析器，ReportParseService 负责解析入库 + Neo4j 同步（新增 Report/Dataset 节点和 CONTAINS/QUERIES/REFERENCES 关系），ReportImpactService 提供 5 种 Cypher 查询。前端新增独立 /report-impact 页面，含筛选区 + 报表清单 + 字段映射表 + 口径/跨链图。

**技术栈：** Spring Boot 3.2.5 + MyBatis-Plus + Spring Data Neo4j + JDK DOM + Vue 3 + Element Plus + Vue Flow + dagre

**参考规格：** `docs/superpowers/specs/2026-07-31-report-impact-analysis-design.md`

**全局约束（所有任务必须遵守）：**
- Report 节点主键 `file`（报表文件名，如 `公司类存款明细表.cpt`）
- Dataset 节点主键 `key`（report_file + "#" + dataset_name，如 `公司类存款明细表.cpt#ds1`）
- Table/Column 节点复用现有，name/col_key 统一小写
- 所有节点和关系写入必须用 MERGE（幂等）
- QUERIES 关系用 `alias` 作为 MERGE 键：`MERGE (d)-[:QUERIES {alias: $alias}]->(t)`
- REFERENCES 关系用 `select_alias` 作为 MERGE 键：`MERGE (d)-[r:REFERENCES {select_alias: $selectAlias}]->(c)`
- Table.name 统一小写：写入前 `fullName.toLowerCase()`
- Column.col_key 统一小写：`schema.table.field` 全小写
- MySQL 表名下划线命名，Java 实体驼峰命名，MyBatis-Plus 配置 map-underscore-to-camel-case: true
- 解析器单文件失败不影响批量其他文件
- ${if(...)} 帆软动态参数当占位符跳过
- Maven 路径：`D:\HB\maven\bin\mvn.cmd`（系统默认 mvn 3.0.5 不兼容 Spring Boot 3.2.5）
- 前端构建后需重启后端才能生效（Spring Boot 缓存 classpath 资源）

---

## 文件结构

### 后端新增文件

| 文件路径 | 职责 |
|---------|------|
| `sql/report_tables.sql` | 报表相关 3 张 MySQL 表建表脚本 |
| `src/main/java/com/hb/extractor/entity/ReportInfo.java` | 报表信息实体 |
| `src/main/java/com/hb/extractor/entity/ReportDataset.java` | 报表数据集实体 |
| `src/main/java/com/hb/extractor/entity/ReportCellBinding.java` | 报表单元格绑定实体 |
| `src/main/java/com/hb/extractor/mapper/ReportInfoMapper.java` | 报表信息 Mapper |
| `src/main/java/com/hb/extractor/mapper/ReportDatasetMapper.java` | 报表数据集 Mapper |
| `src/main/java/com/hb/extractor/mapper/ReportCellBindingMapper.java` | 报表单元格绑定 Mapper |
| `src/main/java/com/hb/extractor/parser/dto/ReportMetadata.java` | XML 解析结果 DTO |
| `src/main/java/com/hb/extractor/parser/dto/DatasetInfo.java` | 数据集信息 DTO |
| `src/main/java/com/hb/extractor/parser/dto/CellBinding.java` | 单元格绑定 DTO |
| `src/main/java/com/hb/extractor/parser/dto/TableRef.java` | SQL 表引用 DTO |
| `src/main/java/com/hb/extractor/parser/dto/ColumnMapping.java` | SQL 字段映射 DTO |
| `src/main/java/com/hb/extractor/parser/CptXmlParser.java` | 帆软 XML 解析器 |
| `src/main/java/com/hb/extractor/parser/ReportSqlParser.java` | 报表 SQL 解析器 |
| `src/main/java/com/hb/extractor/service/ReportParseService.java` | 报表解析服务接口 |
| `src/main/java/com/hb/extractor/service/ReportImpactService.java` | 报表影响分析服务接口 |
| `src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java` | 报表解析服务实现 |
| `src/main/java/com/hb/extractor/service/impl/ReportImpactServiceImpl.java` | 报表影响分析服务实现 |
| `src/main/java/com/hb/extractor/controller/ReportController.java` | 报表管理 Controller |
| `src/main/java/com/hb/extractor/controller/ReportImpactController.java` | 报表影响分析 Controller |
| `src/main/java/com/hb/extractor/dto/ReportImpactVO.java` | 报表影响分析响应 VO |
| `src/test/java/com/hb/extractor/parser/ReportSqlParserTest.java` | SQL 解析器单元测试 |
| `src/test/java/com/hb/extractor/parser/CptXmlParserTest.java` | XML 解析器单元测试 |

### 后端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java` | 新增 Report/Dataset 唯一约束 |
| `sql/init.sql` | 追加报表 3 张表建表语句 |

### 前端新增文件

| 文件路径 | 职责 |
|---------|------|
| `frontend/src/api/report.js` | 报表 API |
| `frontend/src/views/ReportImpactView.vue` | 报表影响分析主视图 |
| `frontend/src/components/report/ReportImpactFilter.vue` | 筛选区组件 |
| `frontend/src/components/report/ReportList.vue` | 报表清单组件 |
| `frontend/src/components/report/FieldMappingTable.vue` | 字段映射表组件 |
| `frontend/src/components/report/FieldSourceGraph.vue` | 口径来源链路图 |
| `frontend/src/components/report/CrossImpactGraph.vue` | 跨链影响图 |

### 前端修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `frontend/src/router/index.js` | 新增 /report-impact 路由 |
| `frontend/src/App.vue` | 菜单新增"报表影响分析"入口 |

---

## 任务 1：MySQL 建表脚本 + 实体类 + Mapper

**文件：**
- 创建：`sql/report_tables.sql`
- 修改：`sql/init.sql`（追加报表表）
- 创建：`src/main/java/com/hb/extractor/entity/ReportInfo.java`
- 创建：`src/main/java/com/hb/extractor/entity/ReportDataset.java`
- 创建：`src/main/java/com/hb/extractor/entity/ReportCellBinding.java`
- 创建：`src/main/java/com/hb/extractor/mapper/ReportInfoMapper.java`
- 创建：`src/main/java/com/hb/extractor/mapper/ReportDatasetMapper.java`
- 创建：`src/main/java/com/hb/extractor/mapper/ReportCellBindingMapper.java`

**上下文：** 基础数据结构任务。参考现有 `proc_info` 表和 `ProcInfo.java` 实体模式。MySQL 表名下划线命名，Java 实体驼峰，MyBatis-Plus 配置 map-underscore-to-camel-case: true。

- [ ] **步骤 1：创建建表脚本**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\sql\report_tables.sql`：

```sql
-- ============================================
-- 报表影响分析 - 数据表
-- ============================================
USE proc_extractor;

-- --------------------------------------------
-- 表1：报表信息表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_info`;
CREATE TABLE `report_info` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file`          VARCHAR(255) NOT NULL COMMENT '文件名(唯一)',
    `name`          VARCHAR(255) NOT NULL COMMENT '报表名(去扩展名)',
    `type`          VARCHAR(20)  NOT NULL COMMENT '类型: cpt/frm/xml',
    `description`   VARCHAR(500)          DEFAULT NULL COMMENT '描述',
    `parse_time`    DATETIME     NOT NULL COMMENT '解析时间',
    `dataset_count` INT          NOT NULL DEFAULT 0  COMMENT '数据集数',
    `cell_count`    INT          NOT NULL DEFAULT 0  COMMENT '单元格绑定数',
    `parse_status`  VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS' COMMENT '解析状态: SUCCESS/PARTIAL/FAILED',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file` (`file`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表信息表';

-- --------------------------------------------
-- 表2：报表数据集表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_dataset`;
CREATE TABLE `report_dataset` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `report_file` VARCHAR(255) NOT NULL COMMENT '所属报表文件',
    `dataset_name` VARCHAR(100) NOT NULL COMMENT '数据集名(ds1)',
    `connection`  VARCHAR(255)          DEFAULT NULL COMMENT '数据源连接名',
    `sql_text`    TEXT                   DEFAULT NULL COMMENT 'SQL语句',
    `table_count` INT          NOT NULL DEFAULT 0  COMMENT '涉及表数',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_dataset` (`report_file`, `dataset_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表数据集表';

-- --------------------------------------------
-- 表3：报表单元格绑定表
-- --------------------------------------------
DROP TABLE IF EXISTS `report_cell_binding`;
CREATE TABLE `report_cell_binding` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `report_file`  VARCHAR(255) NOT NULL COMMENT '所属报表文件',
    `dataset_name` VARCHAR(100) NOT NULL COMMENT '数据集名',
    `cell_pos`     VARCHAR(20)  NOT NULL COMMENT '单元格位置(A3)',
    `column_name`  VARCHAR(100) NOT NULL COMMENT '数据集列名(STAT_DT)',
    `select_alias` VARCHAR(100)          DEFAULT NULL COMMENT 'SQL select别名',
    `source_table` VARCHAR(255)          DEFAULT NULL COMMENT '源表全名(小写)',
    `source_field` VARCHAR(100)          DEFAULT NULL COMMENT '源字段名',
    `source_expr`  TEXT                   DEFAULT NULL COMMENT '源表达式原文',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_report_dataset` (`report_file`, `dataset_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表单元格绑定表';
```

- [ ] **步骤 2：追加到 init.sql**

在 `d:\HB\work_zuoye\proc-dependency-extractor\sql\init.sql` 末尾追加一行：

```
-- 报表影响分析相关表（详见 report_tables.sql）
SOURCE report_tables.sql;
```

- [ ] **步骤 3：创建 ReportInfo 实体**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\entity\ReportInfo.java`：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表信息实体
 */
@Data
@TableName("report_info")
public class ReportInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件名(唯一) */
    private String file;

    /** 报表名(去扩展名) */
    private String name;

    /** 类型: cpt/frm/xml */
    private String type;

    /** 描述 */
    private String description;

    /** 解析时间 */
    private LocalDateTime parseTime;

    /** 数据集数 */
    private Integer datasetCount;

    /** 单元格绑定数 */
    private Integer cellCount;

    /** 解析状态: SUCCESS/PARTIAL/FAILED */
    private String parseStatus;

    /** 入库时间 */
    private LocalDateTime createTime;
}
```

- [ ] **步骤 4：创建 ReportDataset 实体**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\entity\ReportDataset.java`：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表数据集实体
 */
@Data
@TableName("report_dataset")
public class ReportDataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属报表文件 */
    private String reportFile;

    /** 数据集名(ds1) */
    private String datasetName;

    /** 数据源连接名 */
    private String connection;

    /** SQL语句 */
    private String sqlText;

    /** 涉及表数 */
    private Integer tableCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
```

- [ ] **步骤 5：创建 ReportCellBinding 实体**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\entity\ReportCellBinding.java`：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表单元格绑定实体
 */
@Data
@TableName("report_cell_binding")
public class ReportCellBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属报表文件 */
    private String reportFile;

    /** 数据集名 */
    private String datasetName;

    /** 单元格位置(A3) */
    private String cellPos;

    /** 数据集列名(STAT_DT) */
    private String columnName;

    /** SQL select别名 */
    private String selectAlias;

    /** 源表全名(小写) */
    private String sourceTable;

    /** 源字段名 */
    private String sourceField;

    /** 源表达式原文 */
    private String sourceExpr;

    /** 创建时间 */
    private LocalDateTime createTime;
}
```

- [ ] **步骤 6：创建 3 个 Mapper**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\mapper\ReportInfoMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.ReportInfo;

public interface ReportInfoMapper extends BaseMapper<ReportInfo> {
}
```

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\mapper\ReportDatasetMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.ReportDataset;

public interface ReportDatasetMapper extends BaseMapper<ReportDataset> {
}
```

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\mapper\ReportCellBindingMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.ReportCellBinding;

public interface ReportCellBindingMapper extends BaseMapper<ReportCellBinding> {
}
```

- [ ] **步骤 7：执行建表 SQL**

运行（在 MySQL 客户端执行 `sql/report_tables.sql` 内容，或用 mysql 命令行）：

```bash
mysql -uroot -proot proc_extractor < d:\HB\work_zuoye\proc-dependency-extractor\sql\report_tables.sql
```

预期：3 张表创建成功。验证：`SHOW TABLES LIKE 'report%';` 返回 3 行。

- [ ] **步骤 8：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 9：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add sql/report_tables.sql sql/init.sql src/main/java/com/hb/extractor/entity/Report*.java src/main/java/com/hb/extractor/mapper/Report*.java
git commit -m "feat: 报表影响分析-MySQL建表+实体+Mapper"
```

---

## 任务 2：解析器 DTO

**文件：**
- 创建：`src/main/java/com/hb/extractor/parser/dto/ReportMetadata.java`
- 创建：`src/main/java/com/hb/extractor/parser/dto/DatasetInfo.java`
- 创建：`src/main/java/com/hb/extractor/parser/dto/CellBinding.java`
- 创建：`src/main/java/com/hb/extractor/parser/dto/TableRef.java`
- 创建：`src/main/java/com/hb/extractor/parser/dto/ColumnMapping.java`

**上下文：** 这些 DTO 在 XML 解析器和 SQL 解析器之间传递数据。ReportMetadata 是 XML 解析的输出，含数据集列表和单元格绑定列表；TableRef 和 ColumnMapping 是 SQL 解析的输出。

- [ ] **步骤 1：创建 DatasetInfo DTO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\dto\DatasetInfo.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 数据集信息（XML解析输出）
 */
@Data
public class DatasetInfo {
    /** 数据集名(ds1) */
    private String name;
    /** 数据源连接名 */
    private String connection;
    /** SQL查询语句 */
    private String sql;
}
```

- [ ] **步骤 2：创建 CellBinding DTO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\dto\CellBinding.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 单元格绑定（XML解析输出）
 */
@Data
public class CellBinding {
    /** 单元格位置(A3) */
    private String cellPos;
    /** 数据集名(ds1) */
    private String dsName;
    /** 列名(STAT_DT) */
    private String columnName;
}
```

- [ ] **步骤 3：创建 ReportMetadata DTO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\dto\ReportMetadata.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表元数据（XML解析输出）
 */
@Data
public class ReportMetadata {
    /** 报表名(去扩展名) */
    private String name;
    /** 文件类型: cpt/frm/xml */
    private String type;
    /** 数据集列表 */
    private List<DatasetInfo> datasets = new ArrayList<>();
    /** 单元格绑定列表 */
    private List<CellBinding> cellBindings = new ArrayList<>();
}
```

- [ ] **步骤 4：创建 TableRef DTO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\dto\TableRef.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 表引用（SQL解析输出）
 */
@Data
public class TableRef {
    /** schema名(大写，如 RPT) */
    private String schema;
    /** 表名(大写，如 A_CFIN_DEPT_ACCT_INFO) */
    private String table;
    /** SQL中的别名(如 a) */
    private String alias;

    /** 完整表名 schema.table（小写） */
    public String getFullName() {
        return (schema + "." + table).toLowerCase();
    }
}
```

- [ ] **步骤 5：创建 ColumnMapping DTO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\dto\ColumnMapping.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 字段映射（SQL解析输出）
 */
@Data
public class ColumnMapping {
    /** select输出列名(如 STAT_DT / a2) */
    private String selectAlias;
    /** 源表达式(如 a.GL_NUM / trim(a.GL_NUM)) */
    private String sourceExpr;
    /** 源表全名(小写，如 rpt.a_cfin_dept_acct_info)，无法解析时为null */
    private String sourceTable;
    /** 源字段名(大写，如 GL_NUM)，无法解析时为null */
    private String sourceField;
}
```

- [ ] **步骤 6：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/parser/dto/ReportMetadata.java src/main/java/com/hb/extractor/parser/dto/DatasetInfo.java src/main/java/com/hb/extractor/parser/dto/CellBinding.java src/main/java/com/hb/extractor/parser/dto/TableRef.java src/main/java/com/hb/extractor/parser/dto/ColumnMapping.java
git commit -m "feat: 报表解析器DTO"
```

---

## 任务 3：CptXmlParser XML 解析器

**文件：**
- 创建：`src/main/java/com/hb/extractor/parser/CptXmlParser.java`
- 创建：`src/test/java/com/hb/extractor/parser/CptXmlParserTest.java`

**上下文：** 用 JDK DOM 解析帆软报表 XML。关键 XML 结构：
- `<TableDataMap><TableData name="ds1" class="com.fr.data.impl.DBTableData">` → 数据集
- `<Connection class="com.fr.data.impl.NameDatabaseConnection"><DatabaseName><![CDATA[139.0.10.11_rpt(gbase8A)]]></DatabaseName>` → 数据源连接名
- `<Query><![CDATA[select ...]]></Query>` → SQL
- `<CellElementList><C c="0" r="2">` → 单元格（c=列号 r=行号）
- `<O t="DSColumn"><Attributes dsName="ds1" columnName="ORD_II_ID"/>` → 数据列绑定

单元格位置转换：c=0→A, c=1→B...；r=2→3（行号+1，因为 r 是 0-based）。如 c=0 r=2 → A3。

- [ ] **步骤 1：编写失败的测试**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\test\java\com\hb\extractor\parser\CptXmlParserTest.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ReportMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CptXmlParserTest {

    @Autowired
    private CptXmlParser cptXmlParser;

    private static final String CPT_FILE = "d:/HB/work_zuoye/reports/公司类存款明细表.cpt";

    @Test
    void testExtractDatasets() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return; // 测试环境无文件时跳过
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        assertEquals("公司类存款明细表", metadata.getName());
        assertEquals("cpt", metadata.getType());
        assertEquals(3, metadata.getDatasets().size());

        // 验证 ds1
        var ds1 = metadata.getDatasets().get(0);
        assertEquals("ds1", ds1.getName());
        assertEquals("139.0.10.11_rpt(gbase8A)", ds1.getConnection());
        assertNotNull(ds1.getSql());
        assertTrue(ds1.getSql().contains("RPT.A_CFIN_DEPT_ACCT_INFO"));
    }

    @Test
    void testExtractCellBindings() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return;
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        assertFalse(metadata.getCellBindings().isEmpty());

        // 验证第一个数据列绑定（c=0 r=2 → A3, ds1, ORD_II_ID）
        var firstBinding = metadata.getCellBindings().get(0);
        assertEquals("A3", firstBinding.getCellPos());
        assertEquals("ds1", firstBinding.getDsName());
        assertEquals("ORD_II_ID", firstBinding.getColumnName());
    }

    @Test
    void testConnectionName() throws IOException {
        Path path = Paths.get(CPT_FILE);
        if (!Files.exists(path)) {
            return;
        }
        String xml = Files.readString(path);
        ReportMetadata metadata = cptXmlParser.parse(xml, "公司类存款明细表.cpt");

        // ds2 和 ds3 应使用 pdbfine_visual 连接
        var ds2 = metadata.getDatasets().get(1);
        assertEquals("ds2", ds2.getName());
        assertEquals("pdbfine_visual", ds2.getConnection());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=CptXmlParserTest -q
```

预期：编译失败（CptXmlParser 类不存在）。

- [ ] **步骤 3：实现 CptXmlParser**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\CptXmlParser.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.CellBinding;
import com.hb.extractor.parser.dto.DatasetInfo;
import com.hb.extractor.parser.dto.ReportMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * 帆软报表 XML 解析器
 * 解析 .cpt / .frm / .xml 文件，提取数据集和单元格绑定
 */
@Slf4j
@Component
public class CptXmlParser {

    private final DocumentBuilderFactory docFactory = createFactory();

    private DocumentBuilderFactory createFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        return f;
    }

    /**
     * 解析报表 XML
     * @param xml XML 文本
     * @param fileName 文件名（如 公司类存款明细表.cpt）
     */
    public ReportMetadata parse(String xml, String fileName) {
        ReportMetadata metadata = new ReportMetadata();
        metadata.setName(extractName(fileName));
        metadata.setType(extractType(fileName));

        try {
            DocumentBuilder builder = docFactory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            parseDatasets(doc, metadata);
            parseCellBindings(doc, metadata);

        } catch (Exception e) {
            log.error("解析报表 XML 失败 file={}: {}", fileName, e.getMessage());
            throw new RuntimeException("XML 解析失败: " + e.getMessage(), e);
        }

        return metadata;
    }

    private String extractName(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private String extractType(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(idx + 1).toLowerCase() : "unknown";
    }

    private void parseDatasets(Document doc, ReportMetadata metadata) {
        NodeList tableDataList = doc.getElementsByTagName("TableData");
        for (int i = 0; i < tableDataList.getLength(); i++) {
            Element tableData = (Element) tableDataList.item(i);
            DatasetInfo ds = new DatasetInfo();
            ds.setName(tableData.getAttribute("name"));

            // 提取数据源连接名
            NodeList connNodes = tableData.getElementsByTagName("Connection");
            if (connNodes.getLength() > 0) {
                Element conn = (Element) connNodes.item(0);
                NodeList dbNames = conn.getElementsByTagName("DatabaseName");
                if (dbNames.getLength() > 0) {
                    ds.setConnection(dbNames.item(0).getTextContent().trim());
                }
            }

            // 提取 SQL
            NodeList queryNodes = tableData.getElementsByTagName("Query");
            if (queryNodes.getLength() > 0) {
                ds.setSql(queryNodes.item(0).getTextContent().trim());
            }

            metadata.getDatasets().add(ds);
        }
    }

    private void parseCellBindings(Document doc, ReportMetadata metadata) {
        NodeList cellList = doc.getElementsByTagName("C");
        for (int i = 0; i < cellList.getLength(); i++) {
            Element cell = (Element) cellList.item(i);
            String cAttr = cell.getAttribute("c");
            String rAttr = cell.getAttribute("r");
            if (cAttr.isEmpty() || rAttr.isEmpty()) continue;

            // 查找 DSColumn 子节点
            Element oElement = findChildElement(cell, "O");
            if (oElement == null || !"DSColumn".equals(oElement.getAttribute("t"))) continue;

            Element attributes = findChildElement(oElement, "Attributes");
            if (attributes == null) continue;

            CellBinding binding = new CellBinding();
            binding.setCellPos(toCellPos(Integer.parseInt(cAttr), Integer.parseInt(rAttr)));
            binding.setDsName(attributes.getAttribute("dsName"));
            binding.setColumnName(attributes.getAttribute("columnName"));

            if (binding.getDsName() != null && !binding.getDsName().isEmpty()
                    && binding.getColumnName() != null && !binding.getColumnName().isEmpty()) {
                metadata.getCellBindings().add(binding);
            }
        }
    }

    private Element findChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** c=0, r=2 → A3（列号转字母，行号+1） */
    private String toCellPos(int col, int row) {
        StringBuilder sb = new StringBuilder();
        int n = col;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        sb.append(row + 1);
        return sb.toString();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=CptXmlParserTest -q
```

预期：3 个测试全部 PASS。

- [ ] **步骤 5：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/parser/CptXmlParser.java src/test/java/com/hb/extractor/parser/CptXmlParserTest.java
git commit -m "feat: CptXmlParser帆软报表XML解析器"
```

---

## 任务 4：ReportSqlParser SQL 解析器

**文件：**
- 创建：`src/main/java/com/hb/extractor/parser/ReportSqlParser.java`
- 创建：`src/test/java/com/hb/extractor/parser/ReportSqlParserTest.java`

**上下文：** 解析报表数据集的纯 SELECT SQL，输出 TableRef 列表和 ColumnMapping 列表。与现有 FieldMappingExtractor 的区别：无 INSERT INTO 目标表，select别名即映射目标。需处理：
- `${if(...)}` 帆软动态参数 → 替换为 `__PARAM__` 占位符
- `--` 行注释和 `/* */` 块注释 → 去除
- SELECT 列拆分（注意括号层级，不拆分函数参数内逗号）
- 列别名识别：`AS 别名` / `空格别名` / `alias.field` 直接取 field
- 源表字段提取：从 sourceExpr 中匹配 `alias.field`，查 alias→table 映射

- [ ] **步骤 1：编写失败的测试**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\test\java\com\hb\extractor\parser\ReportSqlParserTest.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportSqlParserTest {

    @Autowired
    private ReportSqlParser reportSqlParser;

    @Test
    void testExtractTables() {
        String sql = "select a.STAT_DT, a.CUST_ID from RPT.A_CFIN_DEPT_ACCT_INFO a " +
                "LEFT JOIN FDM.F_AC_CRBS_CUST_ACCT_NO b ON a.CUST_ACCT_NUM = b.CUST_ACCT_NO";
        List<TableRef> tables = reportSqlParser.extractTables(sql);

        assertEquals(2, tables.size());
        assertEquals("RPT", tables.get(0).getSchema());
        assertEquals("A_CFIN_DEPT_ACCT_INFO", tables.get(0).getTable());
        assertEquals("a", tables.get(0).getAlias());
        assertEquals("FDM", tables.get(1).getSchema());
        assertEquals("F_AC_CRBS_CUST_ACCT_NO", tables.get(1).getTable());
        assertEquals("b", tables.get(1).getAlias());
    }

    @Test
    void testSelectAliasAs() {
        String sql = "select trim(a.GL_NUM) as a2 from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("a2", mappings.get(0).getSelectAlias());
        assertEquals("GL_NUM", mappings.get(0).getSourceField());
        assertEquals("rpt.t", mappings.get(0).getSourceTable());
    }

    @Test
    void testSelectAliasSpace() {
        String sql = "select nvl(a.CURR_BAL,0) CURR_BAL from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("CURR_BAL", mappings.get(0).getSelectAlias());
        assertEquals("CURR_BAL", mappings.get(0).getSourceField());
    }

    @Test
    void testSelectAliasField() {
        String sql = "select a.STAT_DT from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
        assertEquals("a.STAT_DT", mappings.get(0).getSourceExpr());
        assertEquals("STAT_DT", mappings.get(0).getSourceField());
        assertEquals("rpt.t", mappings.get(0).getSourceTable());
    }

    @Test
    void testParamPlaceholder() {
        String sql = "select a.STAT_DT from RPT.T a where a.STAT_DT = date'${data_dt}' " +
                "${if(len(CUST_ID)==0,\"\",\"and a.CUST_ID in ('\"+replace(CUST_ID,\",\",\",'\")+\"')\")}";
        List<TableRef> tables = reportSqlParser.extractTables(sql);
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, tables.size());
        assertEquals(1, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
    }

    @Test
    void testCommentStripping() {
        String sql = "select a.STAT_DT -- 数据日期\n, a.CUST_ID /* 客户号 */ from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(2, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
        assertEquals("CUST_ID", mappings.get(1).getSelectAlias());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ReportSqlParserTest -q
```

预期：编译失败（ReportSqlParser 类不存在）。

- [ ] **步骤 3：实现 ReportSqlParser**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\parser\ReportSqlParser.java`：

```java
package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表 SQL 解析器
 * 解析纯 SELECT 语句，提取表引用和字段映射
 */
@Slf4j
@Component
public class ReportSqlParser {

    /** ${if(...)} 帆软动态参数 */
    private static final Pattern FR_PARAM_PATTERN = Pattern.compile("\\$\\{[^}]*\\}");

    /** 行注释 -- ... */
    private static final Pattern LINE_COMMENT_PATTERN = Pattern.compile("--[^\\n]*");

    /** 块注释 /* ... */ */
    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** FROM/JOIN 后的表引用: schema.table alias */
    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
            "(?i)\\b(FROM|JOIN)\\s+(\\w+)\\.(\\w+)\\s+(?:AS\\s+)?(\\w+)?");

    /** AS 别名: expr AS alias */
    private static final Pattern AS_ALIAS_PATTERN = Pattern.compile(
            "(?i)\\bAS\\s+(\\w+)\\s*$");

    /** alias.field 模式 */
    private static final Pattern ALIAS_FIELD_PATTERN = Pattern.compile("(\\w+)\\.(\\w+)");

    /** 末尾空格别名: expr alias */
    private static final Pattern SPACE_ALIAS_PATTERN = Pattern.compile(
            "\\s+(\\w+)\\s*$");

    /**
     * 预处理 SQL：去除帆软参数和注释
     */
    public String preprocess(String sql) {
        if (sql == null) return "";
        String result = sql;
        result = FR_PARAM_PATTERN.matcher(result).replaceAll("__PARAM__");
        result = BLOCK_COMMENT_PATTERN.matcher(result).replaceAll(" ");
        result = LINE_COMMENT_PATTERN.matcher(result).replaceAll(" ");
        return result;
    }

    /**
     * 提取 SQL 中的表引用（FROM/JOIN schema.table alias）
     */
    public List<TableRef> extractTables(String rawSql) {
        List<TableRef> tables = new ArrayList<>();
        String sql = preprocess(rawSql);

        Matcher m = FROM_JOIN_PATTERN.matcher(sql);
        while (m.find()) {
            TableRef ref = new TableRef();
            ref.setSchema(m.group(2).toUpperCase());
            ref.setTable(m.group(3).toUpperCase());
            ref.setAlias(m.group(4) != null ? m.group(4).toLowerCase() : null);
            tables.add(ref);
        }
        return tables;
    }

    /**
     * 提取 SELECT 列的字段映射
     */
    public List<ColumnMapping> extractColumns(String rawSql) {
        List<ColumnMapping> mappings = new ArrayList<>();
        String sql = preprocess(rawSql);

        // 定位 SELECT ... FROM
        int selectIdx = indexOfKeyword(sql, "SELECT");
        int fromIdx = indexOfKeyword(sql, "FROM");
        if (selectIdx == -1 || fromIdx == -1 || fromIdx <= selectIdx) {
            return mappings;
        }
        String selectClause = sql.substring(selectIdx + 6, fromIdx).trim();

        // 构建别名映射
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        for (TableRef ref : extractTables(rawSql)) {
            if (ref.getAlias() != null) {
                aliasToTable.put(ref.getAlias(), ref.getFullName());
            }
        }

        // 按括号深度拆分列
        List<String> columns = splitSelectColumns(selectClause);

        for (int i = 0; i < columns.size(); i++) {
            String raw = columns.get(i).trim();
            if (raw.isEmpty()) continue;
            ColumnMapping mapping = parseColumn(raw, i, aliasToTable);
            mappings.add(mapping);
        }

        return mappings;
    }

    private int indexOfKeyword(String sql, String keyword) {
        Pattern p = Pattern.compile("(?i)\\b" + keyword + "\\b");
        Matcher m = p.matcher(sql);
        return m.find() ? m.start() : -1;
    }

    /**
     * 按括号深度拆分 SELECT 列（不拆分函数参数内的逗号）
     */
    private List<String> splitSelectColumns(String selectClause) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            if (c == '\'' && !inString) {
                inString = true;
                current.append(c);
            } else if (c == '\'' && inString) {
                inString = false;
                current.append(c);
            } else if (!inString && c == '(') {
                depth++;
                current.append(c);
            } else if (!inString && c == ')') {
                depth--;
                current.append(c);
            } else if (!inString && c == ',' && depth == 0) {
                columns.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            columns.add(current.toString());
        }
        return columns;
    }

    private ColumnMapping parseColumn(String raw, int index, Map<String, String> aliasToTable) {
        ColumnMapping mapping = new ColumnMapping();
        String expr = raw.trim();

        // 尝试 AS 别名
        Matcher asMatcher = AS_ALIAS_PATTERN.matcher(expr);
        String alias = null;
        String sourceExpr = expr;
        if (asMatcher.find()) {
            alias = asMatcher.group(1);
            sourceExpr = expr.substring(0, asMatcher.start()).trim();
        } else {
            // 尝试末尾空格别名（如 nvl(a.X,0) CURR_BAL）
            Matcher spaceMatcher = SPACE_ALIAS_PATTERN.matcher(expr);
            if (spaceMatcher.find()) {
                String candidate = spaceMatcher.group(1);
                // 排除关键字和函数名
                if (!isKeyword(candidate) && !expr.endsWith("(" + candidate + ")")) {
                    alias = candidate;
                    sourceExpr = expr.substring(0, spaceMatcher.start()).trim();
                }
            }
        }

        // 无别名时，从 alias.field 推断
        if (alias == null) {
            Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(expr);
            if (afMatcher.find()) {
                alias = afMatcher.group(2);
                sourceExpr = expr;
            } else {
                alias = "expr_" + (index + 1);
                sourceExpr = expr;
            }
        }

        mapping.setSelectAlias(alias);
        mapping.setSourceExpr(sourceExpr);

        // 提取源表和源字段
        Matcher afMatcher = ALIAS_FIELD_PATTERN.matcher(sourceExpr);
        if (afMatcher.find()) {
            String aliasName = afMatcher.group(1).toLowerCase();
            String fieldName = afMatcher.group(2).toUpperCase();
            mapping.setSourceField(fieldName);
            String table = aliasToTable.get(aliasName);
            if (table != null) {
                mapping.setSourceTable(table);
            }
        }

        return mapping;
    }

    private boolean isKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("FROM") || upper.equals("WHERE") || upper.equals("AS")
                || upper.equals("AND") || upper.equals("OR") || upper.equals("CASE")
                || upper.equals("WHEN") || upper.equals("THEN") || upper.equals("ELSE")
                || upper.equals("END") || upper.equals("NULL") || upper.equals("NOT");
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml test -Dtest=ReportSqlParserTest -q
```

预期：6 个测试全部 PASS。

- [ ] **步骤 5：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/parser/ReportSqlParser.java src/test/java/com/hb/extractor/parser/ReportSqlParserTest.java
git commit -m "feat: ReportSqlParser报表SQL解析器"
```

---

## 任务 5：Neo4j 约束 + ReportParseService

**文件：**
- 修改：`src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java`
- 创建：`src/main/java/com/hb/extractor/service/ReportParseService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java`

**上下文：** Neo4jConstraintInitializer 新增 Report.file 和 Dataset.key 唯一约束。ReportParseService 负责解析报表文件 → MySQL 入库 + Neo4j 同步。

Neo4j 同步逻辑：
1. 删除旧数据：按 report_file 删除 Report 节点及其 CONTAINS 关系，删除 Dataset 节点（key 前缀匹配），删除孤立的 QUERIES/REFERENCES 关系
2. MERGE Report 节点
3. 对每个数据集：MERGE Dataset 节点 + CONTAINS 关系 + MERGE Table 节点（复用现有，按 getFullName 小写）+ QUERIES 关系 + MERGE Column 节点（复用现有）+ REFERENCES 关系
4. 单元格绑定关系：通过 dsName+columnName 关联 ColumnMapping，将 cell_refs 写入 REFERENCES 关系

- [ ] **步骤 1：更新 Neo4jConstraintInitializer**

修改 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\config\Neo4jConstraintInitializer.java`，在 `constraints` 数组中追加 2 条：

```java
String[] constraints = {
    "CREATE CONSTRAINT table_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.name IS UNIQUE",
    "CREATE CONSTRAINT column_key_unique IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE",
    "CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE",
    "CREATE CONSTRAINT report_file_unique IF NOT EXISTS FOR (r:Report) REQUIRE r.file IS UNIQUE",
    "CREATE CONSTRAINT dataset_key_unique IF NOT EXISTS FOR (d:Dataset) REQUIRE d.key IS UNIQUE"
};
```

- [ ] **步骤 2：创建 ReportParseService 接口**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\ReportParseService.java`：

```java
package com.hb.extractor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.entity.ReportInfo;

import java.util.List;

/**
 * 报表解析服务接口
 */
public interface ReportParseService {

    /** 单文件解析 */
    ReportInfo parseAndSave(String filePath);

    /** 目录批量解析 */
    List<ReportInfo> parseBatch(String dirPath);

    /** 重新解析全部报表 */
    List<ReportInfo> reParseAll();

    /** 删除报表 */
    boolean deleteReport(String file);

    /** 分页查询报表列表 */
    IPage<ReportInfo> listReports(int pageNum, int pageSize, String name);

    /** 获取报表详情（含数据集和单元格绑定） */
    ReportInfo getReportDetail(String file);
}
```

- [ ] **步骤 3：实现 ReportParseServiceImpl**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\impl\ReportParseServiceImpl.java`：

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.parser.CptXmlParser;
import com.hb.extractor.parser.ReportSqlParser;
import com.hb.extractor.parser.dto.*;
import com.hb.extractor.service.ReportParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ReportParseServiceImpl implements ReportParseService {

    @Autowired
    private CptXmlParser cptXmlParser;
    @Autowired
    private ReportSqlParser reportSqlParser;
    @Autowired
    private ReportInfoMapper reportInfoMapper;
    @Autowired
    private ReportDatasetMapper reportDatasetMapper;
    @Autowired
    private ReportCellBindingMapper reportCellBindingMapper;
    @Autowired
    private Neo4jClient neo4jClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportInfo parseAndSave(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        String fileName = path.getFileName().toString();
        String type = extractType(fileName);
        if (!Set.of("cpt", "frm", "xml").contains(type)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileName);
        }

        try {
            String xml = Files.readString(path);
            ReportMetadata metadata = cptXmlParser.parse(xml, fileName);
            String parseStatus = "SUCCESS";

            // 先删除旧数据
            deleteReportData(fileName);

            // 写 report_info
            ReportInfo reportInfo = new ReportInfo();
            reportInfo.setFile(fileName);
            reportInfo.setName(metadata.getName());
            reportInfo.setType(metadata.getType());
            reportInfo.setParseTime(LocalDateTime.now());
            reportInfo.setDatasetCount(metadata.getDatasets().size());
            reportInfo.setCellCount(metadata.getCellBindings().size());

            List<ReportCellBinding> allBindings = new ArrayList<>();

            // 解析每个数据集
            for (DatasetInfo ds : metadata.getDatasets()) {
                List<TableRef> tableRefs = new ArrayList<>();
                List<ColumnMapping> columnMappings = new ArrayList<>();
                try {
                    tableRefs = reportSqlParser.extractTables(ds.getSql());
                    columnMappings = reportSqlParser.extractColumns(ds.getSql());
                } catch (Exception e) {
                    log.warn("数据集 SQL 解析失败 report={} dataset={}: {}", fileName, ds.getName(), e.getMessage());
                    parseStatus = "PARTIAL";
                }

                // 写 report_dataset
                ReportDataset rd = new ReportDataset();
                rd.setReportFile(fileName);
                rd.setDatasetName(ds.getName());
                rd.setConnection(ds.getConnection());
                rd.setSqlText(ds.getSql());
                rd.setTableCount(tableRefs.size());
                rd.setCreateTime(LocalDateTime.now());
                reportDatasetMapper.insert(rd);

                // 合并单元格绑定与字段映射
                Map<String, ColumnMapping> mappingByAlias = new LinkedHashMap<>();
                for (ColumnMapping cm : columnMappings) {
                    mappingByAlias.put(cm.getSelectAlias().toUpperCase(), cm);
                }

                for (CellBinding cb : metadata.getCellBindings()) {
                    if (!cb.getDsName().equals(ds.getName())) continue;
                    ColumnMapping cm = mappingByAlias.get(cb.getColumnName().toUpperCase());

                    ReportCellBinding rcb = new ReportCellBinding();
                    rcb.setReportFile(fileName);
                    rcb.setDatasetName(ds.getName());
                    rcb.setCellPos(cb.getCellPos());
                    rcb.setColumnName(cb.getColumnName());
                    if (cm != null) {
                        rcb.setSelectAlias(cm.getSelectAlias());
                        rcb.setSourceTable(cm.getSourceTable());
                        rcb.setSourceField(cm.getSourceField());
                        rcb.setSourceExpr(cm.getSourceExpr());
                    }
                    rcb.setCreateTime(LocalDateTime.now());
                    reportCellBindingMapper.insert(rcb);
                    allBindings.add(rcb);
                }
            }

            reportInfo.setParseStatus(parseStatus);
            reportInfoMapper.insert(reportInfo);

            // Neo4j 同步（MySQL 已提交后）
            try {
                syncToNeo4j(fileName, metadata, allBindings);
            } catch (Exception e) {
                log.error("Neo4j 同步失败 report={}: {}", fileName, e.getMessage());
            }

            return reportInfo;

        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private void syncToNeo4j(String fileName, ReportMetadata metadata, List<ReportCellBinding> bindings) {
        // 删除旧 Neo4j 数据
        deleteNeo4jData(fileName);

        // MERGE Report 节点
        Map<String, Object> reportParams = new HashMap<>();
        reportParams.put("file", fileName);
        reportParams.put("name", metadata.getName());
        reportParams.put("type", metadata.getType());
        neo4jClient.query("MERGE (r:Report {file: $file}) " +
                "SET r.name = $name, r.type = $type").bindAll(reportParams).run();

        // 按数据集分组绑定
        Map<String, List<ReportCellBinding>> bindingsByDs = bindings.stream()
                .collect(Collectors.groupingBy(ReportCellBinding::getDatasetName));

        for (DatasetInfo ds : metadata.getDatasets()) {
            String datasetKey = fileName + "#" + ds.getName();

            // MERGE Dataset 节点
            Map<String, Object> dsParams = new HashMap<>();
            dsParams.put("key", datasetKey);
            dsParams.put("name", ds.getName());
            dsParams.put("connection", ds.getConnection());
            dsParams.put("sql", ds.getSql());
            neo4jClient.query("MERGE (d:Dataset {key: $key}) " +
                    "SET d.name = $name, d.connection = $connection, d.sql = $sql")
                    .bindAll(dsParams).run();

            // CONTAINS 关系
            Map<String, Object> containsParams = new HashMap<>();
            containsParams.put("file", fileName);
            containsParams.put("key", datasetKey);
            neo4jClient.query("MATCH (r:Report {file: $file}), (d:Dataset {key: $key}) " +
                    "MERGE (r)-[:CONTAINS]->(d)").bindAll(containsParams).run();

            // QUERIES 关系
            List<TableRef> tableRefs = reportSqlParser.extractTables(ds.getSql());
            for (TableRef ref : tableRefs) {
                String tableName = ref.getFullName();
                Map<String, Object> tableMergeParams = new HashMap<>();
                tableMergeParams.put("name", tableName);
                neo4jClient.query("MERGE (t:Table {name: $name})").bindAll(tableMergeParams).run();

                Map<String, Object> queriesParams = new HashMap<>();
                queriesParams.put("key", datasetKey);
                queriesParams.put("tableName", tableName);
                queriesParams.put("alias", ref.getAlias());
                neo4jClient.query("MATCH (d:Dataset {key: $key}), (t:Table {name: $tableName}) " +
                        "MERGE (d)-[:QUERIES {alias: $alias}]->(t)").bindAll(queriesParams).run();
            }

            // REFERENCES 关系 + cell_refs
            List<ReportCellBinding> dsBindings = bindingsByDs.getOrDefault(ds.getName(), Collections.emptyList());
            // 按 selectAlias 聚合单元格位置
            Map<String, List<String>> cellsByAlias = new LinkedHashMap<>();
            for (ReportCellBinding rcb : dsBindings) {
                String aliasKey = rcb.getSelectAlias() != null ? rcb.getSelectAlias() : rcb.getColumnName();
                cellsByAlias.computeIfAbsent(aliasKey, k -> new ArrayList<>()).add(rcb.getCellPos());
            }

            for (ReportCellBinding rcb : dsBindings) {
                if (rcb.getSourceTable() == null || rcb.getSourceField() == null) continue;
                String colKey = (rcb.getSourceTable() + "." + rcb.getSourceField()).toLowerCase();

                // MERGE Column 节点（复用现有）
                Map<String, Object> colParams = new HashMap<>();
                colParams.put("colKey", colKey);
                colParams.put("name", rcb.getSourceField());
                colParams.put("tableFullName", rcb.getSourceTable());
                neo4jClient.query("MERGE (c:Column {col_key: $colKey}) " +
                        "SET c.name = $name, c.table_full_name = $tableFullName")
                        .bindAll(colParams).run();

                // BELONGS_TO（复用现有）
                Map<String, Object> belongParams = new HashMap<>();
                belongParams.put("colKey", colKey);
                belongParams.put("tableName", rcb.getSourceTable());
                neo4jClient.query("MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName}) " +
                        "MERGE (c)-[:BELONGS_TO]->(t)").bindAll(belongParams).run();

                // REFERENCES 关系
                String aliasKey = rcb.getSelectAlias() != null ? rcb.getSelectAlias() : rcb.getColumnName();
                String cellRefsJson;
                try {
                    cellRefsJson = objectMapper.writeValueAsString(cellsByAlias.getOrDefault(aliasKey, Collections.emptyList()));
                } catch (Exception e) {
                    cellRefsJson = "[]";
                }

                Map<String, Object> refParams = new HashMap<>();
                refParams.put("key", datasetKey);
                refParams.put("colKey", colKey);
                refParams.put("selectAlias", aliasKey);
                refParams.put("sourceExpr", rcb.getSourceExpr());
                refParams.put("cellRefs", cellRefsJson);
                neo4jClient.query("MATCH (d:Dataset {key: $key}), (c:Column {col_key: $colKey}) " +
                        "MERGE (d)-[r:REFERENCES {select_alias: $selectAlias}]->(c) " +
                        "SET r.source_expr = $sourceExpr, r.cell_refs = $cellRefs")
                        .bindAll(refParams).run();
            }
        }

        log.info("Neo4j 同步成功 report={}", fileName);
    }

    private void deleteNeo4jData(String fileName) {
        try {
            // 删除 Report 及其 CONTAINS 关系
            neo4jClient.query("MATCH (r:Report {file: $file}) DETACH DELETE r")
                    .bind(fileName).to("file").run();
            // 删除该报表的 Dataset 节点（key 前缀匹配）
            neo4jClient.query("MATCH (d:Dataset) WHERE d.key STARTS WITH $prefix DETACH DELETE d")
                    .bind(fileName + "#").to("prefix").run();
            // 清理孤立 Table/Column
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n").run();
        } catch (Exception e) {
            log.warn("删除 Neo4j 旧数据失败 report={}: {}", fileName, e.getMessage());
        }
    }

    private void deleteReportData(String file) {
        reportCellBindingMapper.delete(new LambdaQueryWrapper<ReportCellBinding>()
                .eq(ReportCellBinding::getReportFile, file));
        reportDatasetMapper.delete(new LambdaQueryWrapper<ReportDataset>()
                .eq(ReportDataset::getReportFile, file));
        reportInfoMapper.delete(new LambdaQueryWrapper<ReportInfo>()
                .eq(ReportInfo::getFile, file));
    }

    private String extractType(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(idx + 1).toLowerCase() : "unknown";
    }

    @Override
    public List<ReportInfo> parseBatch(String dirPath) {
        List<ReportInfo> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(Paths.get(dirPath))) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".cpt") || name.endsWith(".frm") || name.endsWith(".xml");
                    })
                    .forEach(p -> {
                        try {
                            results.add(parseAndSave(p.toString()));
                        } catch (Exception e) {
                            log.error("解析报表失败 file={}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + e.getMessage(), e);
        }
        return results;
    }

    @Override
    public List<ReportInfo> reParseAll() {
        List<ReportInfo> existing = reportInfoMapper.selectList(null);
        List<ReportInfo> results = new ArrayList<>();
        // 假设报表文件在 reports 目录（从配置或固定路径获取）
        return parseBatch("d:/HB/work_zuoye/reports");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteReport(String file) {
        deleteReportData(file);
        deleteNeo4jData(file);
        return true;
    }

    @Override
    public IPage<ReportInfo> listReports(int pageNum, int pageSize, String name) {
        Page<ReportInfo> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ReportInfo> wrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isEmpty()) {
            wrapper.like(ReportInfo::getName, name);
        }
        wrapper.orderByDesc(ReportInfo::getParseTime);
        return reportInfoMapper.selectPage(page, wrapper);
    }

    @Override
    public ReportInfo getReportDetail(String file) {
        return reportInfoMapper.selectOne(new LambdaQueryWrapper<ReportInfo>()
                .eq(ReportInfo::getFile, file));
    }
}
```

- [ ] **步骤 4：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 5：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java src/main/java/com/hb/extractor/service/ReportParseService.java src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java
git commit -m "feat: Neo4j约束+ReportParseService解析入库+Neo4j同步"
```

---

## 任务 6：ReportController + ReportImpactService

**文件：**
- 创建：`src/main/java/com/hb/extractor/controller/ReportController.java`
- 创建：`src/main/java/com/hb/extractor/dto/ReportImpactVO.java`
- 创建：`src/main/java/com/hb/extractor/service/ReportImpactService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/ReportImpactServiceImpl.java`
- 创建：`src/main/java/com/hb/extractor/controller/ReportImpactController.java`

**上下文：** ReportController 提供报表解析管理 API。ReportImpactService 用 Cypher 实现 5 个查询场景。

- [ ] **步骤 1：创建 ReportController**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\controller\ReportController.java`：

```java
package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.Result;
import com.hb.extractor.entity.ReportCellBinding;
import com.hb.extractor.entity.ReportDataset;
import com.hb.extractor.entity.ReportInfo;
import com.hb.extractor.mapper.ReportCellBindingMapper;
import com.hb.extractor.mapper.ReportDatasetMapper;
import com.hb.extractor.service.ReportParseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportParseService reportParseService;
    @Autowired
    private ReportDatasetMapper reportDatasetMapper;
    @Autowired
    private ReportCellBindingMapper reportCellBindingMapper;

    @PostMapping("/parse")
    public Result<ReportInfo> parse(@RequestBody Map<String, String> body) {
        try {
            return Result.success(reportParseService.parseAndSave(body.get("path")));
        } catch (Exception e) {
            log.error("报表解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/parse/batch")
    public Result<List<ReportInfo>> parseBatch(@RequestBody Map<String, String> body) {
        try {
            return Result.success(reportParseService.parseBatch(body.get("path")));
        } catch (Exception e) {
            log.error("批量解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/parse/all")
    public Result<List<ReportInfo>> reParseAll() {
        try {
            return Result.success(reportParseService.reParseAll());
        } catch (Exception e) {
            log.error("全量重新解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<IPage<ReportInfo>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return Result.success(reportParseService.listReports(pageNum, pageSize, name));
    }

    @GetMapping("/{file}")
    public Result<Map<String, Object>> detail(@PathVariable String file) {
        ReportInfo info = reportParseService.getReportDetail(file);
        if (info == null) return Result.error(404, "报表不存在");
        List<ReportDataset> datasets = reportDatasetMapper.selectList(
                new LambdaQueryWrapper<ReportDataset>().eq(ReportDataset::getReportFile, file));
        List<ReportCellBinding> bindings = reportCellBindingMapper.selectList(
                new LambdaQueryWrapper<ReportCellBinding>().eq(ReportCellBinding::getReportFile, file));
        Map<String, Object> result = new HashMap<>();
        result.put("info", info);
        result.put("datasets", datasets);
        result.put("bindings", bindings);
        return Result.success(result);
    }

    @DeleteMapping("/{file}")
    public Result<Boolean> delete(@PathVariable String file) {
        return Result.success(reportParseService.deleteReport(file));
    }
}
```

- [ ] **步骤 2：创建 ReportImpactVO**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\dto\ReportImpactVO.java`：

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表影响分析响应 VO
 */
@Data
public class ReportImpactVO {

    /** 查询的表名（小写 schema.table） */
    private String table;
    /** 查询的字段名（大写） */
    private String field;
    /** 受影响的报表列表 */
    private List<ReportRef> reports = new ArrayList<>();
    /** 字段口径来源（场景3） */
    private List<FieldSource> fieldSources = new ArrayList<>();

    @Data
    public static class ReportRef {
        private String file;
        private String name;
        private String datasetName;
        private String connection;
        private String selectAlias;
        private String sourceExpr;
        private List<String> cellRefs = new ArrayList<>();
    }

    @Data
    public static class FieldSource {
        private String sourceTable;
        private String sourceField;
        private String sourceExpr;
        private String connection;
        private String datasetName;
    }
}
```

- [ ] **步骤 3：创建 ReportImpactService 接口**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\ReportImpactService.java`：

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.ReportImpactVO;

/**
 * 报表影响分析服务接口
 */
public interface ReportImpactService {

    /** 场景1：表级查询 */
    ReportImpactVO findReportsByTable(String schema, String table);

    /** 场景2：字段级查询 */
    ReportImpactVO findReportsByField(String schema, String table, String field);

    /** 场景3：口径查询 */
    ReportImpactVO traceFieldSource(String reportFile, String datasetName, String columnName);

    /** 场景5：跨链影响 */
    ReportImpactVO analyzeCrossImpact(String schema, String table, String field);
}
```

- [ ] **步骤 4：实现 ReportImpactServiceImpl**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\service\impl\ReportImpactServiceImpl.java`：

```java
package com.hb.extractor.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.service.ReportImpactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ReportImpactServiceImpl implements ReportImpactService {

    @Autowired
    private Neo4jClient neo4jClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ReportImpactVO findReportsByTable(String schema, String table) {
        String tableName = (schema + "." + table).toLowerCase();
        ReportImpactVO vo = new ReportImpactVO();
        vo.setTable(tableName);

        String cypher = "MATCH (t:Table {name: $name})<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report) " +
                "RETURN r.file AS file, r.name AS name, d.name AS datasetName, " +
                "d.connection AS connection";

        Collection<Map<String, Object>> result = neo4jClient.query(cypher)
                .bind(tableName).to("name")
                .fetch().all();

        for (Map<String, Object> row : result) {
            ReportImpactVO.ReportRef ref = new ReportImpactVO.ReportRef();
            ref.setFile((String) row.get("file"));
            ref.setName((String) row.get("name"));
            ref.setDatasetName((String) row.get("datasetName"));
            ref.setConnection((String) row.get("connection"));
            vo.getReports().add(ref);
        }
        return vo;
    }

    @Override
    public ReportImpactVO findReportsByField(String schema, String table, String field) {
        String tableName = (schema + "." + table).toLowerCase();
        String colKey = (tableName + "." + field).toLowerCase();
        ReportImpactVO vo = new ReportImpactVO();
        vo.setTable(tableName);
        vo.setField(field.toUpperCase());

        String cypher = "MATCH (c:Column {col_key: $key})<-[ref:REFERENCES]-(d:Dataset)<-[:CONTAINS]-(r:Report) " +
                "RETURN r.file AS file, r.name AS name, d.name AS datasetName, " +
                "d.connection AS connection, ref.select_alias AS selectAlias, " +
                "ref.source_expr AS sourceExpr, ref.cell_refs AS cellRefs";

        Collection<Map<String, Object>> result = neo4jClient.query(cypher)
                .bind(colKey).to("key")
                .fetch().all();

        for (Map<String, Object> row : result) {
            ReportImpactVO.ReportRef ref = new ReportImpactVO.ReportRef();
            ref.setFile((String) row.get("file"));
            ref.setName((String) row.get("name"));
            ref.setDatasetName((String) row.get("datasetName"));
            ref.setConnection((String) row.get("connection"));
            ref.setSelectAlias((String) row.get("selectAlias"));
            ref.setSourceExpr((String) row.get("sourceExpr"));
            ref.setCellRefs(parseCellRefs((String) row.get("cellRefs")));
            vo.getReports().add(ref);
        }
        return vo;
    }

    @Override
    public ReportImpactVO traceFieldSource(String reportFile, String datasetName, String columnName) {
        String datasetKey = reportFile + "#" + datasetName;
        ReportImpactVO vo = new ReportImpactVO();

        String cypher = "MATCH (d:Dataset {key: $key})-[ref:REFERENCES]->(c:Column)-[:BELONGS_TO]->(t:Table) " +
                "WHERE ref.select_alias = $col " +
                "RETURN t.name AS sourceTable, c.name AS sourceField, " +
                "ref.source_expr AS sourceExpr, d.connection AS connection, d.name AS datasetName";

        Collection<Map<String, Object>> result = neo4jClient.query(cypher)
                .bindAll(Map.of("key", datasetKey, "col", columnName))
                .fetch().all();

        for (Map<String, Object> row : result) {
            ReportImpactVO.FieldSource fs = new ReportImpactVO.FieldSource();
            fs.setSourceTable((String) row.get("sourceTable"));
            fs.setSourceField((String) row.get("sourceField"));
            fs.setSourceExpr((String) row.get("sourceExpr"));
            fs.setConnection((String) row.get("connection"));
            fs.setDatasetName((String) row.get("datasetName"));
            vo.getFieldSources().add(fs);
        }
        return vo;
    }

    @Override
    public ReportImpactVO analyzeCrossImpact(String schema, String table, String field) {
        String tableName = (schema + "." + table).toLowerCase();
        ReportImpactVO vo = new ReportImpactVO();
        vo.setTable(tableName);

        // 报表引用
        String cypher = "MATCH (t:Table {name: $name})<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report) " +
                "RETURN r.file AS file, r.name AS name, d.name AS datasetName, d.connection AS connection";
        Collection<Map<String, Object>> result = neo4jClient.query(cypher)
                .bind(tableName).to("name").fetch().all();
        for (Map<String, Object> row : result) {
            ReportImpactVO.ReportRef ref = new ReportImpactVO.ReportRef();
            ref.setFile((String) row.get("file"));
            ref.setName((String) row.get("name"));
            ref.setDatasetName((String) row.get("datasetName"));
            ref.setConnection((String) row.get("connection"));
            vo.getReports().add(ref);
        }

        // 若有 field，同时查字段级
        if (field != null && !field.isEmpty()) {
            vo.setField(field.toUpperCase());
            String colKey = (tableName + "." + field).toLowerCase();
            String fieldCypher = "MATCH (c:Column {col_key: $key})<-[ref:REFERENCES]-(d:Dataset)<-[:CONTAINS]-(r:Report) " +
                    "RETURN r.file AS file, ref.select_alias AS selectAlias, ref.source_expr AS sourceExpr, " +
                    "ref.cell_refs AS cellRefs, r.name AS name, d.name AS datasetName, d.connection AS connection";
            Collection<Map<String, Object>> fieldResult = neo4jClient.query(fieldCypher)
                    .bind(colKey).to("key").fetch().all();
            for (Map<String, Object> row : fieldResult) {
                ReportImpactVO.ReportRef ref = new ReportImpactVO.ReportRef();
                ref.setFile((String) row.get("file"));
                ref.setName((String) row.get("name"));
                ref.setDatasetName((String) row.get("datasetName"));
                ref.setConnection((String) row.get("connection"));
                ref.setSelectAlias((String) row.get("selectAlias"));
                ref.setSourceExpr((String) row.get("sourceExpr"));
                ref.setCellRefs(parseCellRefs((String) row.get("cellRefs")));
                vo.getReports().add(ref);
            }
        }
        return vo;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseCellRefs(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
```

- [ ] **步骤 5：创建 ReportImpactController**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\src\main\java\com\hb\extractor\controller\ReportImpactController.java`：

```java
package com.hb.extractor.controller;

import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.ReportImpactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/report-impact")
public class ReportImpactController {

    @Autowired
    private ReportImpactService reportImpactService;

    @GetMapping("/by-table")
    public Result<ReportImpactVO> byTable(@RequestParam String schema, @RequestParam String table) {
        return Result.success(reportImpactService.findReportsByTable(schema, table));
    }

    @GetMapping("/by-field")
    public Result<ReportImpactVO> byField(@RequestParam String schema, @RequestParam String table,
                                          @RequestParam String field) {
        return Result.success(reportImpactService.findReportsByField(schema, table, field));
    }

    @GetMapping("/field-source")
    public Result<ReportImpactVO> fieldSource(@RequestParam String reportFile,
                                              @RequestParam String datasetName,
                                              @RequestParam String columnName) {
        return Result.success(reportImpactService.traceFieldSource(reportFile, datasetName, columnName));
    }

    @GetMapping("/cross-impact")
    public Result<ReportImpactVO> crossImpact(@RequestParam String schema, @RequestParam String table,
                                              @RequestParam(required = false) String field) {
        return Result.success(reportImpactService.analyzeCrossImpact(schema, table, field));
    }
}
```

- [ ] **步骤 6：编译验证**

运行：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml compile -q
```

预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add src/main/java/com/hb/extractor/controller/ReportController.java src/main/java/com/hb/extractor/controller/ReportImpactController.java src/main/java/com/hb/extractor/dto/ReportImpactVO.java src/main/java/com/hb/extractor/service/ReportImpactService.java src/main/java/com/hb/extractor/service/impl/ReportImpactServiceImpl.java
git commit -m "feat: ReportController+ReportImpactService影响分析查询"
```

---

## 任务 7：前端 API + 路由 + 菜单

**文件：**
- 创建：`frontend/src/api/report.js`
- 修改：`frontend/src/router/index.js`
- 修改：`frontend/src/App.vue`

**上下文：** 新增报表 API 模块和路由入口。

- [ ] **步骤 1：创建 report.js API**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\api\report.js`：

```javascript
import request from './request'

export function parseReport(path) {
  return request({ url: '/report/parse', method: 'post', data: { path } })
}

export function parseReportBatch(path) {
  return request({ url: '/report/parse/batch', method: 'post', data: { path } })
}

export function reParseAllReports() {
  return request({ url: '/report/parse/all', method: 'post' })
}

export function getReportList(params) {
  return request({ url: '/report/list', method: 'get', params })
}

export function getReportDetail(file) {
  return request({ url: `/report/${encodeURIComponent(file)}`, method: 'get' })
}

export function deleteReport(file) {
  return request({ url: `/report/${encodeURIComponent(file)}`, method: 'delete' })
}

export function findReportsByTable(schema, table) {
  return request({ url: '/report-impact/by-table', method: 'get', params: { schema, table } })
}

export function findReportsByField(schema, table, field) {
  return request({ url: '/report-impact/by-field', method: 'get', params: { schema, table, field } })
}

export function traceFieldSource(reportFile, datasetName, columnName) {
  return request({ url: '/report-impact/field-source', method: 'get',
    params: { reportFile, datasetName, columnName } })
}

export function analyzeCrossImpact(schema, table, field) {
  return request({ url: '/report-impact/cross-impact', method: 'get',
    params: { schema, table, field } })
}
```

- [ ] **步骤 2：更新路由**

修改 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\router\index.js`，在 routes 数组的 `/impact` 路由后追加：

```javascript
  {
    path: '/report-impact',
    name: 'ReportImpact',
    component: () => import('../views/ReportImpactView.vue'),
    meta: { title: '报表影响分析' }
  }
```

- [ ] **步骤 3：更新 App.vue 菜单**

修改 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\App.vue`，在 `影响分析` 菜单项后追加：

```html
          <el-menu-item index="/report-impact">
            <el-icon><Document /></el-icon>
            <span>报表影响分析</span>
          </el-menu-item>
```

并在 script setup 的 import 中追加 `Document`：

```javascript
import { Upload, List, Connection, Aim, Document } from '@element-plus/icons-vue'
```

- [ ] **步骤 4：构建验证**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```

预期：构建成功（此时 ReportImpactView.vue 尚未创建，需先创建空文件占位）。

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\views\ReportImpactView.vue` 占位：

```vue
<template>
  <div class="report-impact-view">
    <h2>报表影响分析（开发中）</h2>
  </div>
</template>

<script setup>
</script>
```

重新构建验证通过。

- [ ] **步骤 5：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/api/report.js frontend/src/router/index.js frontend/src/App.vue frontend/src/views/ReportImpactView.vue
git commit -m "feat: 前端报表API+路由+菜单"
```

---

## 任务 8：前端报表影响分析页面

**文件：**
- 创建：`frontend/src/components/report/ReportImpactFilter.vue`
- 创建：`frontend/src/components/report/ReportList.vue`
- 创建：`frontend/src/components/report/FieldMappingTable.vue`
- 修改：`frontend/src/views/ReportImpactView.vue`

**上下文：** 报表影响分析主页面。筛选区支持 4 种查询类型（表级/字段级/口径/跨链），左侧报表清单，右侧字段映射表。口径查询在底部展示来源链路（简化为表格，不用 Vue Flow 图以降低复杂度）。

- [ ] **步骤 1：创建 ReportImpactFilter.vue**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\report\ReportImpactFilter.vue`：

```vue
<template>
  <div class="report-filter">
    <el-form :inline="true" :model="form" @submit.prevent>
      <el-form-item label="查询类型">
        <el-select v-model="form.queryType" style="width: 120px">
          <el-option label="表级" value="table" />
          <el-option label="字段级" value="field" />
          <el-option label="口径" value="trace" />
          <el-option label="跨链" value="cross" />
        </el-select>
      </el-form-item>

      <template v-if="form.queryType !== 'trace'">
        <el-form-item label="Schema">
          <el-select v-model="form.schema" placeholder="选择" style="width: 120px"
                     @change="loadTables">
            <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="表名">
          <el-select v-model="form.table" filterable placeholder="选择" style="width: 280px">
            <el-option v-for="t in tables" :key="t.fullName" :label="t.shortName" :value="t.shortName" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.queryType === 'field' || form.queryType === 'cross'" label="字段">
          <el-input v-model="form.field" placeholder="字段名" style="width: 160px" />
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="报表文件">
          <el-input v-model="form.reportFile" placeholder="如 公司类存款明细表.cpt" style="width: 240px" />
        </el-form-item>
        <el-form-item label="数据集">
          <el-input v-model="form.datasetName" placeholder="ds1" style="width: 100px" />
        </el-form-item>
        <el-form-item label="列名">
          <el-input v-model="form.columnName" placeholder="STAT_DT" style="width: 160px" />
        </el-form-item>
      </template>

      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleQuery">查询</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getSchemas, getTables } from '../../api/impact'

const props = defineProps({ loading: Boolean })
const emit = defineEmits(['query'])

const form = ref({
  queryType: 'table',
  schema: '',
  table: '',
  field: '',
  reportFile: '',
  datasetName: '',
  columnName: ''
})

const schemas = ref([])
const tables = ref([])

onMounted(async () => {
  try {
    const res = await getSchemas()
    schemas.value = res.data || []
  } catch (e) { /* ignore */ }
})

async function loadTables() {
  if (!form.value.schema) return
  try {
    const res = await getTables(form.value.schema)
    tables.value = res.data || []
  } catch (e) { /* ignore */ }
}

function handleQuery() {
  emit('query', { ...form.value })
}
</script>

<style scoped>
.report-filter { padding: 10px; background: #fff; border-radius: 4px; margin-bottom: 10px; }
</style>
```

- [ ] **步骤 2：创建 ReportList.vue**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\report\ReportList.vue`：

```vue
<template>
  <div class="report-list">
    <div v-if="!reports.length" class="empty">暂无报表数据</div>
    <el-card v-for="r in reports" :key="r.file" class="report-card"
             shadow="hover" @click="handleClick(r)">
      <div class="card-header">
        <span class="title">{{ r.name }}</span>
        <el-tag size="small" type="info">{{ r.datasetName }}</el-tag>
      </div>
      <div class="card-body">
        <div v-if="r.connection" class="row"><span class="label">数据源:</span> {{ r.connection }}</div>
        <div v-if="r.selectAlias" class="row"><span class="label">别名:</span> {{ r.selectAlias }}</div>
        <div v-if="r.sourceExpr" class="row"><span class="label">表达式:</span> {{ r.sourceExpr }}</div>
        <div v-if="r.cellRefs && r.cellRefs.length" class="row">
          <span class="label">单元格:</span>
          <el-tag v-for="c in r.cellRefs" :key="c" size="small" style="margin-right: 4px">{{ c }}</el-tag>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
defineProps({ reports: { type: Array, default: () => [] } })
const emit = defineEmits(['select'])

function handleClick(report) {
  emit('select', report)
}
</script>

<style scoped>
.report-list { max-height: 600px; overflow-y: auto; }
.report-card { margin-bottom: 8px; cursor: pointer; }
.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.card-header .title { font-weight: bold; }
.card-body .row { font-size: 12px; color: #666; margin: 2px 0; }
.card-body .label { color: #999; }
.empty { text-align: center; color: #999; padding: 40px; }
</style>
```

- [ ] **步骤 3：创建 FieldMappingTable.vue**

创建 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\components\report\FieldMappingTable.vue`：

```vue
<template>
  <div class="field-mapping">
    <el-empty v-if="!bindings.length" description="选择报表查看字段映射" />
    <el-table v-else :data="bindings" border size="small" height="600">
      <el-table-column prop="cellPos" label="单元格" width="80" />
      <el-table-column prop="datasetName" label="数据集" width="80" />
      <el-table-column prop="columnName" label="列名" width="120" />
      <el-table-column prop="selectAlias" label="select别名" width="120" />
      <el-table-column prop="sourceTable" label="源表" width="240" />
      <el-table-column prop="sourceField" label="源字段" width="120" />
      <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
defineProps({ bindings: { type: Array, default: () => [] } })
</script>

<style scoped>
.field-mapping { background: #fff; padding: 10px; border-radius: 4px; }
</style>
```

- [ ] **步骤 4：实现 ReportImpactView.vue**

修改 `d:\HB\work_zuoye\proc-dependency-extractor\frontend\src\views\ReportImpactView.vue`：

```vue
<template>
  <div class="report-impact-view">
    <ReportImpactFilter :loading="loading" @query="handleQuery" />

    <el-row :gutter="10">
      <el-col :span="8">
        <div class="section-title">报表清单</div>
        <ReportList :reports="reports" @select="handleSelectReport" />
      </el-col>
      <el-col :span="16">
        <div class="section-title">字段映射详情</div>
        <FieldMappingTable :bindings="currentBindings" />
      </el-col>
    </el-row>

    <div v-if="fieldSources.length" class="field-source-section">
      <div class="section-title">字段来源链路（口径查询）</div>
      <el-table :data="fieldSources" border size="small">
        <el-table-column prop="sourceTable" label="源表" />
        <el-table-column prop="sourceField" label="源字段" />
        <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
        <el-table-column prop="connection" label="数据库" />
        <el-table-column prop="datasetName" label="数据集" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import ReportImpactFilter from '../components/report/ReportImpactFilter.vue'
import ReportList from '../components/report/ReportList.vue'
import FieldMappingTable from '../components/report/FieldMappingTable.vue'
import {
  findReportsByTable, findReportsByField, traceFieldSource, analyzeCrossImpact,
  getReportDetail
} from '../api/report'

const loading = ref(false)
const reports = ref([])
const currentBindings = ref([])
const fieldSources = ref([])

async function handleQuery(form) {
  loading.value = true
  reports.value = []
  currentBindings.value = []
  fieldSources.value = []
  try {
    let res
    if (form.queryType === 'table') {
      res = await findReportsByTable(form.schema, form.table)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'field') {
      res = await findReportsByField(form.schema, form.table, form.field)
      reports.value = res.data?.reports || []
    } else if (form.queryType === 'trace') {
      res = await traceFieldSource(form.reportFile, form.datasetName, form.columnName)
      fieldSources.value = res.data?.fieldSources || []
    } else if (form.queryType === 'cross') {
      res = await analyzeCrossImpact(form.schema, form.table, form.field)
      reports.value = res.data?.reports || []
    }
  } catch (e) {
    ElMessage.error('查询失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

async function handleSelectReport(report) {
  try {
    const res = await getReportDetail(report.file)
    currentBindings.value = res.data?.bindings || []
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}
</script>

<style scoped>
.report-impact-view { padding: 10px; }
.section-title { font-weight: bold; margin-bottom: 8px; padding: 4px 8px; background: #f5f7fa; }
.field-source-section { margin-top: 15px; }
</style>
```

- [ ] **步骤 5：构建验证**

运行：

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```

预期：构建成功。

- [ ] **步骤 6：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/report/ frontend/src/views/ReportImpactView.vue
git commit -m "feat: 前端报表影响分析页面"
```

---

## 任务 9：端到端验证

**文件：** 无新增（验证任务）

**上下文：** 启动后端，解析 reports/ 下的报表文件，验证 API 和前端。

- [ ] **步骤 1：重启后端**

停止现有后端进程，启动新后端：

```bash
& 'D:\HB\maven\bin\mvn.cmd' -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml spring-boot:run
```

预期：应用启动成功，Neo4j 约束创建日志显示 5 条约束。

- [ ] **步骤 2：解析报表文件**

调用 API 解析 reports 目录：

```bash
curl.exe -X POST http://localhost:8080/api/report/parse/batch -H "Content-Type: application/json" -d '{\"path\":\"d:/HB/work_zuoye/reports\"}'
```

预期：返回 2 个 ReportInfo（公司类存款明细表.cpt 和 shoudan.xml）。

- [ ] **步骤 3：验证报表列表 API**

```bash
curl.exe http://localhost:8080/api/report/list
```

预期：返回 2 条记录。

- [ ] **步骤 4：验证表级影响查询**

```bash
curl.exe "http://localhost:8080/api/report-impact/by-table?schema=RPT&table=A_CFIN_DEPT_ACCT_INFO"
```

预期：返回 reports 数组含"公司类存款明细表.cpt"。

- [ ] **步骤 5：验证字段级影响查询**

```bash
curl.exe "http://localhost:8080/api/report-impact/by-field?schema=RPT&table=A_CFIN_DEPT_ACCT_INFO&field=STAT_DT"
```

预期：返回含 selectAlias=STAT_DT, cellRefs 含单元格位置。

- [ ] **步骤 6：验证口径查询**

```bash
curl.exe "http://localhost:8080/api/report-impact/field-source?reportFile=公司类存款明细表.cpt&datasetName=ds1&columnName=STAT_DT"
```

预期：返回 fieldSources 含 sourceTable=rpt.a_cfin_dept_acct_info, sourceField=STAT_DT。

- [ ] **步骤 7：验证前端**

打开浏览器访问 `http://localhost:8080/#/report-impact`：
1. 选择查询类型"表级"，schema=RPT，表名=A_CFIN_DEPT_ACCT_INFO，点查询 → 左侧显示报表卡片
2. 点击报表卡片 → 右侧显示字段映射表
3. 切换"字段级"，输入字段 STAT_DT → 卡片显示单元格位置
4. 切换"口径"，输入报表/数据集/列名 → 底部显示来源链路

- [ ] **步骤 8：验证 Neo4j 数据**

在 Neo4j Browser 执行：
```cypher
MATCH (r:Report)-[:CONTAINS]->(d:Dataset)-[:QUERIES]->(t:Table) RETURN r,d,t
```

预期：返回 2 个报表的数据集及其查询的表。

- [ ] **步骤 9：Commit 验证记录**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git commit --allow-empty -m "chore: 报表影响分析端到端验证完成"
```
