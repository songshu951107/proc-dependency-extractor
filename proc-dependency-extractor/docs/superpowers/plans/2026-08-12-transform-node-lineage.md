# Transform 节点血缘模型实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 引入 Transform 节点替代 MAPS_TO 关系，将字段级血缘从"一对一映射"升级为"多源计算转换"模型，支持 `A.C + B.D → T.x` 这类复杂链路的完整可视化与影响分析。

**架构：** 解析器层拆分 SELECT 表达式为多个 TransformInput（含 role），入库到新增的 `table_transform_input` 表；同步层用 Transform 节点 + INPUT_TO/OUTPUT_TO 关系替代 MAPS_TO；查询层 Cypher 从 `MAPS_TO*1..N` 改为 `(INPUT_TO|OUTPUT_TO)*1..2N` 变长路径；前端新增 TRANSFORM 节点类型渲染。

**技术栈：** Spring Boot 3.2.5 + MyBatis-Plus 3.5.7 + Spring Data Neo4j + JDK 17 + Vue 3 + Element Plus + Vue Flow + dagre

**参考规格：** `docs/superpowers/specs/2026-08-12-transform-node-lineage-design.md`

**全局约束（所有任务必须遵守）：**
- Transform.id = `md5(proc_name + "|" + target_col_key)`，全小写 32 位 hex
- Column.col_key 统一小写：`schema.table.field` 全小写
- 所有 Neo4j 节点和关系写入必须用 MERGE（幂等）
- MySQL 表名下划线命名，Java 实体驼峰命名，MyBatis-Plus 配置 map-underscore-to-camel-case: true
- Maven 路径：`D:\HB\maven\bin\mvn.cmd`（系统默认 mvn 3.0.5 不兼容 Spring Boot 3.2.5）
- 前端构建后需重启后端才能生效（Spring Boot 缓存 classpath 资源）
- 前端构建命令：`cd frontend && npm run build`
- logic_type 枚举 15 类：DIRECT / LITERAL / ADDITION / SUBTRACTION / MULTIPLICATION / DIVISION / CASE / SUM / COUNT / AVG / MAX / MIN / NVL / CONCAT / FUNCTION_OTHER
- INPUT_TO.role 仅 `value` / `condition` 两类
- 字面量虚拟 Column 节点 col_key = `__literal__.{transformId}`，无 BELONGS_TO 关系
- 深度语义：`max-depth` 仍为字段跳数，Cypher 跳数 = `max-depth * 2`

---

## 文件结构

### 后端新增文件

| 文件路径 | 职责 |
|---------|------|
| `src/main/java/com/hb/extractor/parser/dto/TransformInput.java` | Transform 输入字段 DTO（解析期） |
| `src/main/java/com/hb/extractor/entity/TableTransformInput.java` | Transform 输入字段实体（MySQL） |
| `src/main/java/com/hb/extractor/mapper/TableTransformInputMapper.java` | Transform 输入字段 Mapper |

### 后端修改文件

| 文件路径 | 职责 | 规格章节 |
|---------|------|---------|
| `sql/init.sql` | 新增 table_transform_input 表 + ALTER table_field_mapping 加 logic_type 列 | §3.3 |
| `src/main/java/com/hb/extractor/parser/dto/FieldMapping.java` | 新增 inputs / logicType 字段 | §4.1 |
| `src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java` | 新增 extractInputs / classifyLogicType / populateInputsAndLogicType | §4.2 |
| `src/main/java/com/hb/extractor/entity/TableFieldMapping.java` | 新增 logicType 字段 | §5.7 |
| `src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java` | saveMetadata + deleteProc 新增 inputs 入库 | §4.3 |
| `src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java` | 追加 Transform 唯一约束 | §3.2 |
| `src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java` | 删除 MAPS_TO 逻辑，新增 Transform 节点 + INPUT_TO/OUTPUT_TO | §5 |
| `src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java` | NodeVO + EdgeVO 新增字段 | §6.7 |
| `src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java` | analyzeFieldLevel + expand + buildStats + buildImpactedByDepth 改造 | §6 |

### 前端修改文件

| 文件路径 | 职责 | 规格章节 |
|---------|------|---------|
| `frontend/src/components/impact/ImpactNode.vue` | 新增 TRANSFORM 节点渲染 + logic_type 着色 | §7.2 |
| `frontend/src/components/impact/ImpactFlowGraph.vue` | 节点类型分流 + 边样式区分 + 字段透传 | §7.3 |
| `frontend/src/components/impact/ImpactNodeDetail.vue` | 新增 TRANSFORM 详情面板 + 输入字段列表 | §7.4 |
| `frontend/src/components/impact/ImpactEdgeDetail.vue` | 新增 edgeType/role/expression 展示 | §7.5 |
| `frontend/src/views/ImpactAnalysis.vue` | 字段级跳过 buildWithoutTemp + 筛选适配 | §7.6 |

### 测试文件

| 文件路径 | 职责 |
|---------|------|
| `src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java` | 扩展：classifyLogicType + extractInputs 单元测试 |
| `src/test/java/com/hb/extractor/service/Neo4jSyncServiceTest.java` | 扩展：Transform 节点同步测试 |
| `src/test/java/com/hb/extractor/service/ImpactAnalysisServiceTest.java` | 扩展：字段级查询 Transform 节点测试 |

---

## 任务依赖图

```
任务1 (DDL+实体+Mapper)
  ↓
任务2 (DTO: TransformInput + FieldMapping扩展)
  ↓
任务3 (解析器: classifyLogicType)  ←─ TDD
  ↓
任务4 (解析器: extractInputs)      ←─ TDD
  ↓
任务5 (解析器: populateInputsAndLogicType集成)
  ↓
任务6 (入库: ProcExtractServiceImpl)
  ↓
任务7 (VO扩展 + Neo4j约束)
  ↓
任务8 (同步层: Transform节点方法 + syncProc主流程)
  ↓
任务9 (同步层: deleteProcData + 删除旧方法)
  ↓
任务10 (查询层: analyzeFieldLevel + buildFieldArrow)
  ↓
任务11 (查询层: expand + buildImpactedByDepth + buildStats)
  ↓
任务12 (前端: ImpactNode + ImpactFlowGraph)
  ↓
任务13 (前端: ImpactNodeDetail + ImpactEdgeDetail)
  ↓
任务14 (前端: ImpactAnalysis.vue)
  ↓
任务15 (端到端验证)
```

---

## 任务 1：DDL + 实体 + Mapper（数据层基础）

**文件：**
- 修改：`sql/init.sql`（追加 table_transform_input 建表 + ALTER 语句）
- 创建：`src/main/java/com/hb/extractor/entity/TableTransformInput.java`
- 创建：`src/main/java/com/hb/extractor/mapper/TableTransformInputMapper.java`
- 修改：`src/main/java/com/hb/extractor/entity/TableFieldMapping.java`（新增 logicType 字段）

- [ ] **步骤 1：在 `sql/init.sql` 末尾追加 DDL**

在 `sql/init.sql` 文件末尾追加：

```sql
-- =============================================
-- Transform 模型扩展（v2）
-- =============================================

-- 1. table_field_mapping 新增 logic_type 列
ALTER TABLE table_field_mapping
ADD COLUMN logic_type VARCHAR(30) DEFAULT NULL COMMENT 'Transform logic_type(DIRECT/LITERAL/ADDITION/...)' AFTER source_table_short;

-- 2. Transform 输入字段表
DROP TABLE IF EXISTS `table_transform_input`;
CREATE TABLE `table_transform_input` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `field_mapping_id`    BIGINT       NOT NULL COMMENT '关联 table_field_mapping.id',
    `lineage_id`          BIGINT       NOT NULL COMMENT '关联 table_lineage.id(冗余,便于直接查询)',
    `proc_name`           VARCHAR(200) NOT NULL COMMENT '存储过程名(冗余)',
    `target_field`        VARCHAR(200) NOT NULL COMMENT '目标字段名(冗余)',
    `source_table_short`  VARCHAR(200)          DEFAULT NULL COMMENT '输入字段来源表短名',
    `source_field`        VARCHAR(200)          DEFAULT NULL COMMENT '输入字段名',
    `source_alias`        VARCHAR(100)          DEFAULT NULL COMMENT '表达式中该字段别名',
    `role`                VARCHAR(20)  NOT NULL DEFAULT 'value' COMMENT '输入角色:value/condition',
    `input_order`         INT          NOT NULL COMMENT '在表达式中出现顺序(从1开始)',
    `extract_time`        DATETIME     NOT NULL COMMENT '提取时间',
    PRIMARY KEY (`id`),
    KEY `idx_field_mapping_id` (`field_mapping_id`),
    KEY `idx_lineage_id` (`lineage_id`),
    KEY `idx_proc_name` (`proc_name`),
    KEY `idx_source_table` (`source_table_short`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transform 输入字段';
```

- [ ] **步骤 2：执行 DDL**

运行：
```bash
mysql -u root -p proc_dependency < d:\HB\work_zuoye\proc-dependency-extractor\sql\init.sql
```
预期：无报错，`table_transform_input` 表创建成功，`table_field_mapping` 新增 `logic_type` 列。

验证：
```sql
DESCRIBE table_field_mapping;  -- 应包含 logic_type 列
SHOW TABLES LIKE 'table_transform_input';  -- 应返回 1 行
```

- [ ] **步骤 3：创建 `TableTransformInput` 实体**

创建文件 `src/main/java/com/hb/extractor/entity/TableTransformInput.java`：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Transform 输入字段实体
 * 一个 FieldMapping 可对应多个 TransformInput（多源表达式拆分结果）
 */
@Data
@TableName("table_transform_input")
public class TableTransformInput {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fieldMappingId;
    private Long lineageId;
    private String procName;
    private String targetField;
    private String sourceTableShort;
    private String sourceField;
    private String sourceAlias;
    private String role;
    private Integer inputOrder;
    private LocalDateTime extractTime;
}
```

- [ ] **步骤 4：创建 `TableTransformInputMapper`**

创建文件 `src/main/java/com/hb/extractor/mapper/TableTransformInputMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableTransformInput;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TableTransformInputMapper extends BaseMapper<TableTransformInput> {
}
```

- [ ] **步骤 5：修改 `TableFieldMapping` 实体新增 logicType 字段**

在 `src/main/java/com/hb/extractor/entity/TableFieldMapping.java` 中，在 `sourceTableShort` 字段后新增：

```java
    /** Transform logic_type(解析期填充,同步期写入 Transform 节点) */
    private String logicType;
```

- [ ] **步骤 6：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS，无编译错误。

- [ ] **步骤 7：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add sql/init.sql src/main/java/com/hb/extractor/entity/TableTransformInput.java src/main/java/com/hb/extractor/mapper/TableTransformInputMapper.java src/main/java/com/hb/extractor/entity/TableFieldMapping.java
git commit -m "feat: 新增 table_transform_input 表 + TableTransformInput 实体 + Mapper"
```

---

## 任务 2：DTO 层（TransformInput + FieldMapping 扩展）

**文件：**
- 创建：`src/main/java/com/hb/extractor/parser/dto/TransformInput.java`
- 修改：`src/main/java/com/hb/extractor/parser/dto/FieldMapping.java`

- [ ] **步骤 1：创建 `TransformInput` DTO**

创建文件 `src/main/java/com/hb/extractor/parser/dto/TransformInput.java`：

```java
package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * Transform 输入字段 DTO（解析期）
 * 一个 FieldMapping 可对应多个 TransformInput（多源表达式拆分结果）
 */
@Data
public class TransformInput {
    /** 输入字段来源表短名（推断自 aliasMap） */
    private String sourceTableShort;
    /** 输入字段名（alias.field 的 field 部分） */
    private String sourceField;
    /** 表达式中该字段的别名（如 T01） */
    private String sourceAlias;
    /** 输入角色: value=取值字段 / condition=条件字段 */
    private String role;
    /** 在表达式中出现顺序（从1开始，按出现位置） */
    private int inputOrder;
}
```

- [ ] **步骤 2：修改 `FieldMapping` DTO 新增 inputs + logicType**

在 `src/main/java/com/hb/extractor/parser/dto/FieldMapping.java` 中新增字段：

```java
    /** Transform 输入字段列表（拆分 sourceExpr 得到） */
    private List<TransformInput> inputs = new ArrayList<>();

    /** logic_type 分类（解析期填充，同步期写入 Transform 节点） */
    private String logicType;
```

确保文件顶部 import 包含：
```java
import java.util.ArrayList;
import java.util.List;
```

- [ ] **步骤 3：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/dto/TransformInput.java src/main/java/com/hb/extractor/parser/dto/FieldMapping.java
git commit -m "feat: 新增 TransformInput DTO + FieldMapping 扩展 inputs/logicType"
```

---

## 任务 3：解析器 - classifyLogicType（TDD）

**文件：**
- 修改：`src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java`
- 测试：`src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `FieldMappingExtractorTest.java` 中新增测试类内部类和方法：

```java
    @Test
    void testClassifyLogicType_Literal() {
        assertEquals("LITERAL", extractor.classifyLogicType("'N'"));
        assertEquals("LITERAL", extractor.classifyLogicType("123"));
        assertEquals("LITERAL", extractor.classifyLogicType("DATE '{ETL_TX_DT}'"));
        assertEquals("LITERAL", extractor.classifyLogicType("{ETL_TX_DT}"));
    }

    @Test
    void testClassifyLogicType_Case() {
        assertEquals("CASE", extractor.classifyLogicType("CASE WHEN T01.flag='1' THEN B.amt ELSE C.amt END"));
    }

    @Test
    void testClassifyLogicType_Aggregates() {
        assertEquals("SUM", extractor.classifyLogicType("SUM(T01.amt)"));
        assertEquals("COUNT", extractor.classifyLogicType("COUNT(DISTINCT T01.acct_no)"));
        assertEquals("AVG", extractor.classifyLogicType("AVG(T01.amt)"));
        assertEquals("MAX", extractor.classifyLogicType("MAX(T01.amt)"));
        assertEquals("MIN", extractor.classifyLogicType("MIN(T01.amt)"));
    }

    @Test
    void testClassifyLogicType_Nvl() {
        assertEquals("NVL", extractor.classifyLogicType("NVL(T01.a, T02.b)"));
        assertEquals("NVL", extractor.classifyLogicType("COALESCE(T01.a, T02.b, T03.c)"));
    }

    @Test
    void testClassifyLogicType_Concat() {
        assertEquals("CONCAT", extractor.classifyLogicType("T01.prefix || T01.code"));
        assertEquals("CONCAT", extractor.classifyLogicType("CONCAT(T01.prefix, T01.code)"));
    }

    @Test
    void testClassifyLogicType_Arithmetic() {
        assertEquals("ADDITION", extractor.classifyLogicType("T01.amt + T02.fee"));
        assertEquals("SUBTRACTION", extractor.classifyLogicType("T01.amt - T02.fee"));
        assertEquals("MULTIPLICATION", extractor.classifyLogicType("T01.rate * T01.amt"));
        assertEquals("DIVISION", extractor.classifyLogicType("T01.amt / T01.days"));
    }

    @Test
    void testClassifyLogicType_MixedArithmetic() {
        // 混合运算取首个运算符
        assertEquals("ADDITION", extractor.classifyLogicType("T01.amt + T02.fee - T03.tax"));
        assertEquals("SUBTRACTION", extractor.classifyLogicType("T01.amt - T02.fee + T03.tax"));
    }

    @Test
    void testClassifyLogicType_Priority() {
        // 混合运算:外层运算符优先于内层聚合
        assertEquals("ADDITION", extractor.classifyLogicType("SUM(T01.amt) + T02.fee"));
        // 括号内运算符不影响外层分类
        assertEquals("SUM", extractor.classifyLogicType("SUM(T01.amt + T02.fee)"));
    }

    @Test
    void testClassifyLogicType_FunctionOther() {
        assertEquals("FUNCTION_OTHER", extractor.classifyLogicType("SUBSTR(T01.code, 1, 4)"));
    }

    @Test
    void testClassifyLogicType_Direct() {
        assertEquals("DIRECT", extractor.classifyLogicType("T01.acct_no"));
        assertEquals("DIRECT", extractor.classifyLogicType("acct_no"));
    }

    @Test
    void testClassifyLogicType_Null() {
        assertEquals("DIRECT", extractor.classifyLogicType(null));
        assertEquals("DIRECT", extractor.classifyLogicType(""));
        assertEquals("DIRECT", extractor.classifyLogicType("  "));
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest#testClassifyLogicType_Literal`
预期：FAIL，报错 "method classifyLogicType not found" 或编译错误。

- [ ] **步骤 3：实现 `classifyLogicType` 方法**

在 `FieldMappingExtractor.java` 中新增方法（在 `extract` 方法之后）：

```java
    /**
     * 对表达式进行 logic_type 分类
     * 优先级: LITERAL > CASE > 聚合函数 > NVL > CONCAT > 算术运算 > FUNCTION_OTHER > DIRECT
     *
     * @param expr SELECT 列表达式原文
     * @return logic_type 枚举值
     */
    public String classifyLogicType(String expr) {
        if (expr == null || expr.isBlank()) return "DIRECT";
        String t = expr.trim();

        // 1. LITERAL: 字面量
        if (t.matches("^'[^']*'$")
                || t.matches("^\\d+(\\.\\d+)?$")
                || t.matches("(?i)^DATE\\s*'[^']*'$")
                || t.matches("(?i)^TIMESTAMP\\s*'[^']*'$")
                || t.matches("^\\{\\w+\\}$")) {
            return "LITERAL";
        }

        // 2. CASE
        if (t.matches("(?i)^CASE\\b.*END$")) return "CASE";

        // 3. 聚合函数
        String upper = t.toUpperCase();
        if (upper.startsWith("SUM(")) return "SUM";
        if (upper.startsWith("COUNT(")) return "COUNT";
        if (upper.startsWith("AVG(")) return "AVG";
        if (upper.startsWith("MAX(")) return "MAX";
        if (upper.startsWith("MIN(")) return "MIN";

        // 4. NVL / COALESCE
        if (upper.startsWith("NVL(") || upper.startsWith("COALESCE(")) return "NVL";

        // 5. CONCAT: || 或 CONCAT(...)
        if (t.contains("||") || upper.startsWith("CONCAT(")) return "CONCAT";

        // 6. 算术运算符（按 +、-、*、/ 顺序，取首个出现）
        int firstOp = findFirstTopLevelOperator(t);
        if (firstOp == '+') return "ADDITION";
        if (firstOp == '-') return "SUBTRACTION";
        if (firstOp == '*') return "MULTIPLICATION";
        if (firstOp == '/') return "DIVISION";

        // 7. FUNCTION_OTHER: 其他函数调用
        if (t.matches("(?i)^\\w+\\s*\\(.*")) return "FUNCTION_OTHER";

        // 8. DIRECT: 默认
        return "DIRECT";
    }

    /**
     * 在括号深度 0 处扫描首个算术运算符，返回该运算符字符；无则返回 '\0'
     */
    private char findFirstTopLevelOperator(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && "+-*/".indexOf(c) >= 0) {
                if (i == 0) continue;
                char prev = expr.charAt(i - 1);
                if (!"+-*/(".contains(String.valueOf(prev))) {
                    return c;
                }
            }
        }
        return '\0';
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest`
预期：所有 classifyLogicType 测试 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java
git commit -m "feat: 实现 classifyLogicType 表达式分类（15 类 logic_type）"
```

---

## 任务 4：解析器 - extractInputs（TDD）

**文件：**
- 修改：`src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java`
- 测试：`src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java`

- [ ] **步骤 1：编写失败的测试**

在 `FieldMappingExtractorTest.java` 中新增测试方法：

```java
    private Map<String, String> aliasMap3() {
        Map<String, String> m = new HashMap<>();
        m.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        m.put("T02", "F_AC_CRBS_CUST_INFO");
        m.put("T03", "F_AC_CRBS_ACCT_BAL");
        return m;
    }

    @Test
    void testExtractInputs_Direct() {
        List<TransformInput> inputs = extractor.extractInputs("T01.acct_no", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(0).getSourceTableShort());
        assertEquals("acct_no", inputs.get(0).getSourceField());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("value", inputs.get(0).getRole());
        assertEquals(1, inputs.get(0).getInputOrder());
    }

    @Test
    void testExtractInputs_Addition() {
        List<TransformInput> inputs = extractor.extractInputs("T01.amt + T02.fee", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("amt", inputs.get(0).getSourceField());
        assertEquals(1, inputs.get(0).getInputOrder());
        assertEquals("T02", inputs.get(1).getSourceAlias());
        assertEquals("fee", inputs.get(1).getSourceField());
        assertEquals(2, inputs.get(1).getInputOrder());
    }

    @Test
    void testExtractInputs_CaseWhen() {
        String expr = "CASE WHEN T01.flag='1' THEN T02.amt ELSE T03.amt END";
        List<TransformInput> inputs = extractor.extractInputs(expr, aliasMap3(), null);
        assertEquals(3, inputs.size());
        // flag 是条件
        assertEquals("condition", inputs.get(0).getRole());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("flag", inputs.get(0).getSourceField());
        // amt 是取值
        assertEquals("value", inputs.get(1).getRole());
        assertEquals("T02", inputs.get(1).getSourceAlias());
        assertEquals("value", inputs.get(2).getRole());
        assertEquals("T03", inputs.get(2).getSourceAlias());
    }

    @Test
    void testExtractInputs_CaseWhenMultiBranch() {
        String expr = "CASE WHEN T01.x>0 AND T01.y<10 THEN T02.z WHEN T01.x<0 THEN T03.z ELSE T02.z END";
        List<TransformInput> inputs = extractor.extractInputs(expr, aliasMap3(), null);
        // 去重: T01.x, T01.y, T02.z, T03.z (T02.z 重复只保留首次)
        assertEquals(4, inputs.size());
        assertEquals("condition", inputs.get(0).getRole());
        assertEquals("x", inputs.get(0).getSourceField());
        assertEquals("condition", inputs.get(1).getRole());
        assertEquals("y", inputs.get(1).getSourceField());
        assertEquals("value", inputs.get(2).getRole());
        assertEquals("z", inputs.get(2).getSourceField());
        assertEquals("value", inputs.get(3).getRole());
        assertEquals("z", inputs.get(3).getSourceField());
    }

    @Test
    void testExtractInputs_Literal() {
        List<TransformInput> inputs = extractor.extractInputs("'N'", aliasMap3(), null);
        assertTrue(inputs.isEmpty());
    }

    @Test
    void testExtractInputs_SchemaVar() {
        List<TransformInput> inputs = extractor.extractInputs("DATE '{ETL_TX_DT}'", aliasMap3(), null);
        assertTrue(inputs.isEmpty());
    }

    @Test
    void testExtractInputs_NoAliasSingleSource() {
        // 无 alias 前缀,单源表推断
        List<TransformInput> inputs = extractor.extractInputs("acct_no", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(0).getSourceTableShort());
        assertEquals("acct_no", inputs.get(0).getSourceField());
        assertNull(inputs.get(0).getSourceAlias());
    }

    @Test
    void testExtractInputs_AliasNotInMap() {
        // alias 未在 aliasMap 中,该输入 sourceTableShort=null
        List<TransformInput> inputs = extractor.extractInputs("T99.field1 + T01.field2", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertNull(inputs.get(0).getSourceTableShort());  // T99 未在 aliasMap
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(1).getSourceTableShort());
    }

    @Test
    void testExtractInputs_Sum() {
        List<TransformInput> inputs = extractor.extractInputs("SUM(T01.amt)", aliasMap3(), null);
        assertEquals(1, inputs.size());
        assertEquals("value", inputs.get(0).getRole());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("amt", inputs.get(0).getSourceField());
    }

    @Test
    void testExtractInputs_Nvl() {
        List<TransformInput> inputs = extractor.extractInputs("NVL(T01.a, T02.b)", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertEquals("a", inputs.get(0).getSourceField());
        assertEquals("b", inputs.get(1).getSourceField());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest#testExtractInputs_Direct`
预期：FAIL，报错 "method extractInputs not found"。

- [ ] **步骤 3：实现 `extractInputs` 方法**

在 `FieldMappingExtractor.java` 中新增方法（在 `classifyLogicType` 之后）：

```java
    /** CASE WHEN 整体匹配 */
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "(?i)CASE\\s+(.+?)\\s+END", Pattern.DOTALL);
    /** WHEN...THEN 段匹配 */
    private static final Pattern WHEN_THEN_PATTERN = Pattern.compile(
            "(?i)WHEN\\s+(.+?)\\s+THEN\\s+(.+?)(?=\\s+(?:WHEN|ELSE|END)\\b)", Pattern.DOTALL);
    /** ELSE 段匹配 */
    private static final Pattern ELSE_PATTERN = Pattern.compile(
            "(?i)ELSE\\s+(.+?)(?=\\s+END\\b)", Pattern.DOTALL);

    /**
     * 从表达式中拆分出所有输入字段
     *
     * 算法:
     * 1. CASE WHEN 表达式: WHEN...THEN 之间的字段 → role=condition; THEN/ELSE 后的字段 → role=value
     * 2. 普通表达式: 提取所有 alias.field, 全部 role=value
     * 3. 去重: 同一 (sourceTableShort, sourceField) 多次出现 → 保留首次, input_order 取首次位置
     *    但不同 role 不去重
     *
     * @param expr SELECT 列表达式原文
     * @param aliasMap 别名 → 表短名映射
     * @param singleSourceTable 预计算的唯一源表（无别名前缀时使用），可为 null
     * @return TransformInput 列表，按 input_order 排序
     */
    public List<TransformInput> extractInputs(String expr, Map<String, String> aliasMap,
                                               String singleSourceTable) {
        List<TransformInput> inputs = new ArrayList<>();
        if (expr == null || expr.isBlank() || aliasMap == null || aliasMap.isEmpty()) {
            return inputs;
        }
        String t = expr.trim();

        try {
            // 判断是否为 CASE WHEN 表达式
            Matcher caseM = CASE_PATTERN.matcher(t);
            if (caseM.matches()) {
                // CASE WHEN: 分别提取 condition 和 value 字段
                int order = 1;
                Set<String> seen = new HashSet<>();

                // WHEN...THEN 段: WHEN 后是 condition, THEN 后是 value
                Matcher whenThenM = WHEN_THEN_PATTERN.matcher(t);
                while (whenThenM.find()) {
                    String conditionPart = whenThenM.group(1);
                    String valuePart = whenThenM.group(2);
                    // 提取 condition 字段
                    order = addInputsFromSegment(conditionPart, aliasMap, singleSourceTable,
                            "condition", inputs, seen, order);
                    // 提取 value 字段
                    order = addInputsFromSegment(valuePart, aliasMap, singleSourceTable,
                            "value", inputs, seen, order);
                }

                // ELSE 段: value
                Matcher elseM = ELSE_PATTERN.matcher(t);
                if (elseM.find()) {
                    String elsePart = elseM.group(1);
                    order = addInputsFromSegment(elsePart, aliasMap, singleSourceTable,
                            "value", inputs, seen, order);
                }
            } else {
                // 普通表达式: 提取所有 alias.field, 全部 role=value
                Set<String> seen = new HashSet<>();
                addInputsFromSegment(t, aliasMap, singleSourceTable, "value", inputs, seen, 1);
            }
        } catch (Exception e) {
            log.warn("表达式拆分异常: expr={}, error={}", expr, e.getMessage());
            return new ArrayList<>();
        }

        return inputs;
    }

    /**
     * 从表达式片段中提取 alias.field 字段并添加到 inputs 列表
     * 去重: 同一 (sourceTableShort, sourceField) + role 组合只保留首次
     *
     * @return 下一个 input_order 值
     */
    private int addInputsFromSegment(String segment, Map<String, String> aliasMap,
                                      String singleSourceTable, String role,
                                      List<TransformInput> inputs, Set<String> seen, int startOrder) {
        int order = startOrder;
        Matcher m = ALIAS_FIELD_PATTERN.matcher(segment);
        while (m.find()) {
            String alias = m.group(1);
            String field = m.group(2);
            String tableShort = aliasMap.get(alias.toUpperCase());

            // 去重键: tableShort + field + role
            String dedupKey = (tableShort != null ? tableShort : "null") + "|" + field + "|" + role;
            if (seen.contains(dedupKey)) continue;
            seen.add(dedupKey);

            TransformInput ti = new TransformInput();
            ti.setSourceAlias(alias);
            ti.setSourceField(field);
            ti.setSourceTableShort(tableShort);
            ti.setRole(role);
            ti.setInputOrder(order++);
            inputs.add(ti);
        }

        // 处理无 alias 前缀的字段（如 acct_no），用 singleSourceTable 推断
        if (singleSourceTable != null) {
            // 提取独立字段名（不含点号的标识符）
            Pattern standaloneField = Pattern.compile("(?<![\\w.])\\b(\\w+)\\b(?![\\w.])");
            Matcher sfm = standaloneField.matcher(segment);
            while (sfm.find()) {
                String field = sfm.group(1);
                // 跳过 SQL 关键字
                if (field.toUpperCase().matches("(CASE|WHEN|THEN|ELSE|END|AND|OR|NOT|NULL|IS|IN|BETWEEN|LIKE)")) {
                    continue;
                }
                // 跳过纯数字
                if (field.matches("\\d+")) continue;

                String dedupKey = singleSourceTable + "|" + field + "|" + role;
                if (seen.contains(dedupKey)) continue;
                seen.add(dedupKey);

                TransformInput ti = new TransformInput();
                ti.setSourceAlias(null);
                ti.setSourceField(field);
                ti.setSourceTableShort(singleSourceTable);
                ti.setRole(role);
                ti.setInputOrder(order++);
                inputs.add(ti);
            }
        }

        return order;
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest`
预期：所有 extractInputs 测试 PASS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java
git commit -m "feat: 实现 extractInputs 表达式拆分（CASE WHEN 区分 condition/value）"
```

---

## 任务 5：解析器 - populateInputsAndLogicType 集成

**文件：**
- 修改：`src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java`

- [ ] **步骤 1：新增 `populateInputsAndLogicType` 方法**

在 `FieldMappingExtractor.java` 的 `extractInputs` 方法之后新增：

```java
    /**
     * 为 FieldMapping 填充 inputs 和 logicType
     * 在 extract() 构造每个 FieldMapping 后调用
     *
     * @param fm 待填充的 FieldMapping
     * @param aliasMap 别名 → 表短名映射
     * @param singleSourceTable 预计算的唯一源表，可为 null
     */
    private void populateInputsAndLogicType(FieldMapping fm, Map<String, String> aliasMap,
                                             String singleSourceTable) {
        if (fm == null || fm.getSourceExpr() == null) {
            if (fm != null) {
                fm.setLogicType("DIRECT");
                fm.setInputs(new ArrayList<>());
            }
            return;
        }
        fm.setLogicType(classifyLogicType(fm.getSourceExpr()));
        fm.setInputs(extractInputs(fm.getSourceExpr(), aliasMap, singleSourceTable));
    }
```

- [ ] **步骤 2：在 `extract` 方法中调用 `populateInputsAndLogicType`**

在 `extract` 方法中，找到两处构造 FieldMapping 后的位置：

**位置 1**（有 DDL 分支，约 L137-L142）：
```java
                FieldMapping fm = new FieldMapping();
                fm.setFieldOrder(matchedIdx + 1);
                fm.setSourceExpr(matchedExpr);
                fm.setTargetField(ddlField);
                extractAliasAndTable(matchedExpr, aliasMap, fm, singleSourceTable);
                populateInputsAndLogicType(fm, aliasMap, singleSourceTable);  // 新增
                mappings.add(fm);
```

**位置 2**（无 DDL 分支，约 L150-L159）：
```java
                FieldMapping fm = new FieldMapping();
                fm.setFieldOrder(order);
                fm.setSourceExpr(expr);
                String targetField = determineTargetField(expr, order, ddlFields, i);
                fm.setTargetField(targetField);
                extractAliasAndTable(expr, aliasMap, fm, singleSourceTable);
                populateInputsAndLogicType(fm, aliasMap, singleSourceTable);  // 新增
                mappings.add(fm);
```

- [ ] **步骤 3：编写集成测试验证 inputs 填充**

在 `FieldMappingExtractorTest.java` 中新增：

```java
    @Test
    void testExtractPopulatesInputsAndLogicType() {
        String sql = "INSERT INTO C_AGT_DEPOSIT_ACCT_INF \n" +
                "SELECT T01.acct_no, T01.amt + T02.fee AS total_fee, 'N' AS flag \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01, F_AC_CRBS_CUST_INFO T02";
        Map<String, String> aMap = new HashMap<>();
        aMap.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        aMap.put("T02", "F_AC_CRBS_CUST_INFO");
        List<String> ddlFields = Arrays.asList("acct_no", "total_fee", "flag");
        List<FieldMapping> result = extractor.extract(sql, "C_AGT_DEPOSIT_ACCT_INF", aMap, ddlFields);

        // 第1列: DIRECT
        assertEquals("DIRECT", result.get(0).getLogicType());
        assertEquals(1, result.get(0).getInputs().size());

        // 第2列: ADDITION, 2个输入
        assertEquals("ADDITION", result.get(1).getLogicType());
        assertEquals(2, result.get(1).getInputs().size());
        assertEquals("T01", result.get(1).getInputs().get(0).getSourceAlias());
        assertEquals("T02", result.get(1).getInputs().get(1).getSourceAlias());

        // 第3列: LITERAL, 0个输入
        assertEquals("LITERAL", result.get(2).getLogicType());
        assertTrue(result.get(2).getInputs().isEmpty());
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest`
预期：所有测试 PASS，包括新测试和原有测试不回归。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/parser/FieldMappingExtractor.java src/test/java/com/hb/extractor/parser/FieldMappingExtractorTest.java
git commit -m "feat: 解析器集成 inputs/logicType 填充"
```

---

## 任务 6：入库改造 - ProcExtractServiceImpl

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java`

- [ ] **步骤 1：注入 `TableTransformInputMapper`**

在 `ProcExtractServiceImpl.java` 的依赖注入区域新增：

```java
    @Autowired
    private TableTransformInputMapper tableTransformInputMapper;
```

确保 import：
```java
import com.hb.extractor.entity.TableTransformInput;
import com.hb.extractor.mapper.TableTransformInputMapper;
import com.hb.extractor.parser.dto.TransformInput;
```

- [ ] **步骤 2：在 `deleteProc` 方法中新增删除 inputs 逻辑**

找到 `deleteProc` 方法中删除 `tableFieldMappingMapper` 的位置，在其后新增：

```java
        // 删除 Transform 输入字段
        tableTransformInputMapper.delete(new LambdaQueryWrapper<TableTransformInput>()
                .eq(TableTransformInput::getProcName, procName));
```

- [ ] **步骤 3：在 `saveMetadata` 方法中新增插入 inputs 逻辑**

找到 `saveMetadata` 方法中插入 `tableFieldMappingMapper.insert(record)` 的循环，在每次 insert 后新增插入 inputs：

```java
            tableFieldMappingMapper.insert(record);

            // 新增:插入 Transform 输入字段
            if (fm.getInputs() != null) {
                for (TransformInput ti : fm.getInputs()) {
                    TableTransformInput inputRecord = new TableTransformInput();
                    inputRecord.setFieldMappingId(record.getId());
                    inputRecord.setLineageId(record.getLineageId());
                    inputRecord.setProcName(procName);
                    inputRecord.setTargetField(fm.getTargetField());
                    inputRecord.setSourceTableShort(ti.getSourceTableShort());
                    inputRecord.setSourceField(ti.getSourceField());
                    inputRecord.setSourceAlias(ti.getSourceAlias());
                    inputRecord.setRole(ti.getRole());
                    inputRecord.setInputOrder(ti.getInputOrder());
                    inputRecord.setExtractTime(now);
                    tableTransformInputMapper.insert(inputRecord);
                }
            }
```

- [ ] **步骤 4：在 `saveMetadata` 方法的删旧数据区域新增删除 inputs**

找到 `saveMetadata` 方法中删除 `tableFieldMappingMapper.delete(...)` 的位置，在其后新增：

```java
        tableTransformInputMapper.delete(new LambdaQueryWrapper<TableTransformInput>()
                .eq(TableTransformInput::getProcName, procName));
```

- [ ] **步骤 5：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/ProcExtractServiceImpl.java
git commit -m "feat: ProcExtractServiceImpl 入库/删除 table_transform_input"
```

---

## 任务 7：VO 扩展 + Neo4j 约束

**文件：**
- 修改：`src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java`
- 修改：`src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java`

- [ ] **步骤 1：`ImpactAnalysisVO.NodeVO` 新增字段**

在 `ImpactAnalysisVO.java` 的 `NodeVO` 内部类中新增：

```java
        /** Transform 节点专用:表达式原文 */
        private String expression;
        /** Transform 节点专用:logic_type */
        private String logicType;
        /** Transform 节点专用:SQL 提示 */
        private String sqlHint;
        /** Transform 节点专用:所属存储过程名 */
        private String procName;
```

- [ ] **步骤 2：`ImpactAnalysisVO.EdgeVO` 新增字段**

在 `ImpactAnalysisVO.java` 的 `EdgeVO` 内部类中新增：

```java
        /** 边类型: INPUT_TO / OUTPUT_TO / READS */
        private String edgeType;
        /** INPUT_TO 的角色: value / condition */
        private String role;
        /** OUTPUT_TO 边的表达式(冗余自 Transform 节点) */
        private String expression;
```

- [ ] **步骤 3：`Neo4jConstraintInitializer` 追加 Transform 约束**

在 `Neo4jConstraintInitializer.java` 的 `constraints` 数组末尾追加一条：

```java
    private final String[] constraints = {
            "CREATE CONSTRAINT table_name_unique IF NOT EXISTS FOR (t:Table) REQUIRE t.name IS UNIQUE",
            "CREATE CONSTRAINT column_col_key_unique IF NOT EXISTS FOR (c:Column) REQUIRE c.col_key IS UNIQUE",
            "CREATE CONSTRAINT script_file_unique IF NOT EXISTS FOR (s:Script) REQUIRE s.file IS UNIQUE",
            // 报表相关约束（如有则保持不变）
            "CREATE CONSTRAINT report_file_unique IF NOT EXISTS FOR (r:Report) REQUIRE r.file IS UNIQUE",
            "CREATE CONSTRAINT dataset_key_unique IF NOT EXISTS FOR (d:Dataset) REQUIRE d.key IS UNIQUE",
            // 新增: Transform 节点唯一约束
            "CREATE CONSTRAINT transform_id_unique IF NOT EXISTS FOR (t:Transform) REQUIRE t.id IS UNIQUE"
    };
```

> 注意：实际修改时保留现有数组内容不变，仅在末尾追加最后一条。

- [ ] **步骤 4：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/ImpactAnalysisVO.java src/main/java/com/hb/extractor/config/Neo4jConstraintInitializer.java
git commit -m "feat: VO 扩展 Transform 字段 + Neo4j Transform 唯一约束"
```

---

## 任务 8：同步层 - Transform 节点方法 + syncProc 主流程

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`

- [ ] **步骤 1：注入 `TableTransformInputMapper` + `SqlBlockInfoMapper`**

在依赖注入区域新增：

```java
    @Autowired
    private TableTransformInputMapper tableTransformInputMapper;

    @Autowired
    private SqlBlockInfoMapper sqlBlockInfoMapper;
```

确保 import：
```java
import com.hb.extractor.entity.TableTransformInput;
import com.hb.extractor.mapper.TableTransformInputMapper;
import com.hb.extractor.mapper.SqlBlockInfoMapper;
```

- [ ] **步骤 2：新增 `generateTransformId` 方法**

在 `Neo4jSyncServiceImpl.java` 中新增：

```java
    /**
     * 生成 Transform 节点 id: md5(proc_name + "|" + target_col_key), 全小写
     */
    private String generateTransformId(String procName, String targetColKey) {
        String raw = procName + "|" + targetColKey.toLowerCase();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("MD5 计算失败,降级 hashCode: {}", e.getMessage());
            return Integer.toHexString(raw.hashCode());
        }
    }
```

- [ ] **步骤 3：新增 `mergeTransformNode` 方法**

```java
    /**
     * MERGE Transform 节点
     */
    private void mergeTransformNode(String id, String expression, String logicType,
                                     String sqlHint, String procName,
                                     String targetColKey, String scriptFile) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("expression", expression);
        params.put("logicType", logicType);
        params.put("sqlHint", sqlHint);
        params.put("procName", procName);
        params.put("targetColKey", targetColKey.toLowerCase());
        params.put("scriptFile", scriptFile);
        neo4jClient.query(
                "MERGE (t:Transform {id: $id}) " +
                "SET t.expression = $expression, t.logic_type = $logicType, " +
                "    t.sql_hint = $sqlHint, t.proc_name = $procName, " +
                "    t.target_col_key = $targetColKey, t.script_file = $scriptFile")
                .bindAll(params).run();
    }
```

- [ ] **步骤 4：新增 `createInputToRelationship` 方法**

```java
    /**
     * 创建 INPUT_TO 关系: Column -[:INPUT_TO {role, source_alias}]-> Transform
     */
    private void createInputToRelationship(String sourceColKey, String transformId,
                                            String role, String sourceAlias) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceColKey", sourceColKey.toLowerCase());
        params.put("transformId", transformId);
        params.put("role", role);
        params.put("sourceAlias", sourceAlias);
        String cypher = "MATCH (c:Column {col_key: $sourceColKey}), (t:Transform {id: $transformId}) " +
                "MERGE (c)-[r:INPUT_TO]->(t) " +
                "SET r.role = $role" +
                (sourceAlias != null ? ", r.source_alias = $sourceAlias" : "");
        neo4jClient.query(cypher).bindAll(params).run();
    }
```

- [ ] **步骤 5：新增 `createOutputToRelationship` 方法**

```java
    /**
     * 创建 OUTPUT_TO 关系: Transform -[:OUTPUT_TO]-> Column
     */
    private void createOutputToRelationship(String transformId, String targetColKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("transformId", transformId);
        params.put("targetColKey", targetColKey.toLowerCase());
        neo4jClient.query(
                "MATCH (t:Transform {id: $transformId}), (c:Column {col_key: $targetColKey}) " +
                "MERGE (t)-[:OUTPUT_TO]->(c)")
                .bindAll(params).run();
    }
```

- [ ] **步骤 6：新增 `mergeLiteralColumn` 方法**

```java
    /**
     * MERGE 虚拟字面量 Column 节点
     * col_key = "__literal__." + transformId, 无 BELONGS_TO 关系
     */
    private void mergeLiteralColumn(String colKey, String expr) {
        Map<String, Object> params = new HashMap<>();
        params.put("colKey", colKey.toLowerCase());
        params.put("name", expr);
        params.put("tableFullName", "__literal__");
        neo4jClient.query(
                "MERGE (c:Column {col_key: $colKey}) " +
                "SET c.name = $name, c.table_full_name = $tableFullName")
                .bindAll(params).run();
    }
```

- [ ] **步骤 7：新增 `generateSqlHint` 方法**

```java
    /**
     * 生成 sql_hint
     * 格式: block_seq={n}; via_temp={temp_chain}
     */
    private String generateSqlHint(TableLineage edge, List<TableLineage> allEdges) {
        StringBuilder sb = new StringBuilder();
        sb.append("block_seq=").append(edge.getSqlBlockSeq());

        String targetShort = extractShortName(edge.getTargetTable());
        if (isTempTableName(targetShort)) {
            String tempChain = findTempUpstreamChain(edge.getTargetTable(), allEdges);
            if (tempChain != null) {
                sb.append("; via_temp=").append(tempChain);
            }
        }
        return sb.toString();
    }

    /**
     * 反查临时表的上游真实表链路
     * 防环: visited 集合记录已访问表
     */
    private String findTempUpstreamChain(String tempTable, List<TableLineage> allEdges) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = tempTable;
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            chain.add(current);
            String next = null;
            for (TableLineage e : allEdges) {
                if (e.getTargetTable().equalsIgnoreCase(current)) {
                    String sourceShort = extractShortName(e.getSourceTable());
                    if (!isTempTableName(sourceShort)) {
                        chain.add(e.getSourceTable());
                        return String.join("←", chain);
                    }
                    next = e.getSourceTable();
                    break;
                }
            }
            current = next;
        }
        return chain.size() > 1 ? String.join("←", chain) : null;
    }
```

- [ ] **步骤 8：新增 `inferLogicTypeFromExpr` 降级方法**

```java
    /**
     * 降级:从 sourceExpr 推断 logic_type
     * 仅用于兼容旧数据(table_field_mapping 无 logic_type 列时的回退)
     */
    private String inferLogicTypeFromExpr(String expr) {
        if (expr == null || expr.isBlank()) return "DIRECT";
        String t = expr.trim();
        if (t.matches("^'[^']*'$") || t.matches("^\\d+(\\.\\d+)?$")) return "LITERAL";
        if (t.matches("(?i)^CASE\\b.*END$")) return "CASE";
        String upper = t.toUpperCase();
        if (upper.startsWith("SUM(")) return "SUM";
        if (upper.startsWith("COUNT(")) return "COUNT";
        if (upper.startsWith("AVG(")) return "AVG";
        if (upper.startsWith("MAX(")) return "MAX";
        if (upper.startsWith("MIN(")) return "MIN";
        if (upper.startsWith("NVL(") || upper.startsWith("COALESCE(")) return "NVL";
        if (t.contains("||") || upper.startsWith("CONCAT(")) return "CONCAT";
        if (t.contains("+")) return "ADDITION";
        if (t.contains("-")) return "SUBTRACTION";
        if (t.contains("*")) return "MULTIPLICATION";
        if (t.contains("/")) return "DIVISION";
        if (t.matches("(?i)^\\w+\\s*\\(.*")) return "FUNCTION_OTHER";
        return "DIRECT";
    }
```

- [ ] **步骤 9：改造 `syncProc` 主流程 - 数据加载**

找到 `syncProc` 方法中加载 `mappings` 的位置，新增加载 `inputsByMappingId`：

```java
        // 新增:加载 Transform 输入字段,按 field_mapping_id 分组
        Map<Long, List<TableTransformInput>> inputsByMappingId = new HashMap<>();
        if (!mappings.isEmpty()) {
            List<Long> mappingIds = mappings.stream().map(TableFieldMapping::getId).collect(Collectors.toList());
            List<TableTransformInput> inputs = tableTransformInputMapper.selectList(
                    new LambdaQueryWrapper<TableTransformInput>().in(TableTransformInput::getFieldMappingId, mappingIds));
            inputsByMappingId = inputs.stream().collect(Collectors.groupingBy(TableTransformInput::getFieldMappingId));
        }
```

- [ ] **步骤 10：改造 `syncProc` 主流程 - 字段血缘写入**

找到现有写入 Column + BELONGS_TO + MAPS_TO 的循环，替换为：

```java
        // 写入 Column 节点 + BELONGS_TO + Transform 节点 + INPUT_TO/OUTPUT_TO 关系
        for (TableFieldMapping fm : mappings) {
            TableLineage edge = findEdgeById(edges, fm.getLineageId());
            if (edge == null) continue;

            // 1. 目标 Column 节点 + BELONGS_TO（逻辑不变）
            String targetColKey = toColKey(edge.getTargetTable(), fm.getTargetField());
            mergeColumnNode(targetColKey, fm.getTargetField(), edge.getTargetTable());
            mergeBelongsRelationship(targetColKey, edge.getTargetTable());

            // 2. Transform 节点
            String transformId = generateTransformId(procName, targetColKey);
            String logicType = fm.getLogicType();
            if (logicType == null) {
                logicType = inferLogicTypeFromExpr(fm.getSourceExpr());
            }
            String sqlHint = generateSqlHint(edge, edges);
            mergeTransformNode(transformId, fm.getSourceExpr(), logicType, sqlHint,
                               procName, targetColKey, scriptFile);

            // 3. INPUT_TO 关系
            List<TableTransformInput> inputs = inputsByMappingId.getOrDefault(fm.getId(), Collections.emptyList());
            if (inputs.isEmpty()) {
                // 降级:旧数据无 inputs 但有 source_alias,构造 1 条 INPUT_TO
                if (fm.getSourceAlias() != null && fm.getSourceTableShort() != null) {
                    String sourceTableFull = findTableFullName(edges, fm.getSourceTableShort());
                    if (sourceTableFull != null) {
                        String sourceColKey = toColKey(sourceTableFull, extractFieldFromExpr(fm.getSourceExpr()));
                        mergeColumnNode(sourceColKey, extractFieldFromExpr(fm.getSourceExpr()), sourceTableFull);
                        mergeBelongsRelationship(sourceColKey, sourceTableFull);
                        createInputToRelationship(sourceColKey, transformId, "value", fm.getSourceAlias());
                        log.warn("降级:proc={} target={} 无 inputs,用 source_alias 降级为单源 DIRECT", procName, fm.getTargetField());
                    }
                } else {
                    // 字面量场景:创建虚拟 __literal__ Column 节点
                    String literalColKey = "__literal__." + transformId;
                    mergeLiteralColumn(literalColKey, fm.getSourceExpr());
                    createInputToRelationship(literalColKey, transformId, "value", null);
                }
            } else {
                for (TableTransformInput ti : inputs) {
                    if (ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty()) continue;
                    String sourceTableFull = findTableFullName(edges, ti.getSourceTableShort());
                    if (sourceTableFull == null) continue;
                    String sourceColKey = toColKey(sourceTableFull, ti.getSourceField());
                    mergeColumnNode(sourceColKey, ti.getSourceField(), sourceTableFull);
                    mergeBelongsRelationship(sourceColKey, sourceTableFull);
                    createInputToRelationship(sourceColKey, transformId, ti.getRole(), ti.getSourceAlias());
                }
            }

            // 4. OUTPUT_TO 关系
            createOutputToRelationship(transformId, targetColKey);
        }
```

> 注意：`extractFieldFromExpr` 是现有方法名，如不存在则用 `fm.getSourceExpr().split("\\.")[1]` 替代。需根据实际代码确认方法名。

- [ ] **步骤 11：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。如有编译错误，检查 import 和方法名。

- [ ] **步骤 12：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java
git commit -m "feat: 同步层新增 Transform 节点方法 + syncProc 主流程改造"
```

---

## 任务 9：同步层 - deleteProcData 改造 + 删除旧方法

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java`

- [ ] **步骤 1：改造 `deleteProcData` 方法**

找到 `deleteProcData` 方法，按以下顺序改造（保留 MAPS_TO 删除 + 新增 Transform 删除）：

```java
    @Override
    public void deleteProcData(String procName) {
        String scriptFile = procName + ".py";
        try {
            // 1. 删除该 proc 的 READS 关系（不变）
            neo4jClient.query("MATCH ()-[r:READS {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();

            // 2. 删除该 proc 的 MAPS_TO 关系（过渡期兼容旧数据）
            neo4jClient.query("MATCH ()-[r:MAPS_TO {proc_name: $procName}]->() DELETE r")
                    .bind(procName).to("procName").run();

            // 3. 删除该 proc 的 Transform 节点（DETACH DELETE 清除 INPUT_TO/OUTPUT_TO）
            neo4jClient.query("MATCH (t:Transform {proc_name: $procName}) DETACH DELETE t")
                    .bind(procName).to("procName").run();

            // 4. 删除 Script 节点及其关系（不变）
            neo4jClient.query("MATCH (s:Script {file: $scriptFile})-[r]->() DELETE r")
                    .bind(scriptFile).to("scriptFile").run();
            neo4jClient.query("MATCH (s:Script {file: $scriptFile}) DETACH DELETE s")
                    .bind(scriptFile).to("scriptFile").run();

            // 5. 清理孤儿节点（范围扩展含 Transform）
            neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column OR n:Transform) AND NOT (n)--() DELETE n").run();
        } catch (Exception e) {
            log.warn("删除 Neo4j 旧数据失败 proc={}: {}", procName, e.getMessage());
        }
    }
```

- [ ] **步骤 2：删除 `classifyTransformation` 方法**

找到 `classifyTransformation` 方法，整个删除（逻辑已迁移到 `FieldMappingExtractor.classifyLogicType`）。

- [ ] **步骤 3：删除 `createMapsToRelationship` 方法**

找到 `createMapsToRelationship` 方法，整个删除（被 Transform + INPUT_TO + OUTPUT_TO 替代）。

- [ ] **步骤 4：删除 `extractFieldName` 方法（如存在且不再使用）**

检查 `extractFieldName` 方法是否还有其他调用点。如无调用则删除。

- [ ] **步骤 5：改造孤儿节点清理**

找到 `syncProc` 方法末尾的孤儿清理 Cypher：

```java
        // 原: MATCH (n) WHERE (n:Table OR n:Column) AND NOT (n)--() DELETE n
        // 改为:
        neo4jClient.query("MATCH (n) WHERE (n:Table OR n:Column OR n:Transform) AND NOT (n)--() DELETE n").run();
```

- [ ] **步骤 6：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/Neo4jSyncServiceImpl.java
git commit -m "refactor: deleteProcData 保留 MAPS_TO 清理 + 删除旧方法 classifyTransformation/createMapsToRelationship"
```

---

## 任务 10：查询层 - analyzeFieldLevel + buildFieldArrow

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`

- [ ] **步骤 1：新增 `buildFieldArrow` 方法**

在 `ImpactAnalysisServiceImpl.java` 中新增（在现有 `buildArrow` 方法附近）：

```java
    /**
     * 构建字段级查询的箭头
     * 字段跳数 depth 对应关系跳数 depth*2
     * 注意: cypherMaxHops 通过字符串拼接注入 Cypher（Neo4j 不支持关系跳数参数化）
     */
    private String buildFieldArrow(String direction, int cypherMaxHops) {
        String rel = "[:INPUT_TO|OUTPUT_TO*1.." + cypherMaxHops + "]";
        if ("UPSTREAM".equals(direction)) return "<-" + rel + "-";
        if ("BOTH".equals(direction)) return "-" + rel + "-";
        return "-" + rel + "->";
    }
```

- [ ] **步骤 2：新增 `isTransformId` 方法**

```java
    /**
     * 判断 id 是否为 Transform 节点 id（32 位 md5 hex）
     */
    private boolean isTransformId(String id) {
        if (id == null || id.isEmpty()) return false;
        return id.matches("^[a-f0-9]{32}$") && !id.contains(".");
    }
```

- [ ] **步骤 3：新增 `extractTableFromId` 方法**

```java
    /**
     * 从节点 id 提取表全名(schema.table)
     * - 字段节点: schema.table.field → schema.table
     * - Transform 节点(md5 hash): 返回 null
     * - 虚拟字面量节点: 返回 null
     */
    private String extractTableFromId(String id) {
        if (id == null) return null;
        if (id.startsWith("__literal__")) return null;
        String[] parts = id.split("\\.");
        if (parts.length < 3) return null;
        return (parts[0] + "." + parts[1]).toLowerCase();
    }
```

- [ ] **步骤 4：重写 `analyzeFieldLevel` 方法**

找到现有 `analyzeFieldLevel` 方法，替换为：

```java
    private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges) {
        int cypherMaxHops = depth * 2;
        String arrow = buildFieldArrow(direction, cypherMaxHops);

        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.add(fieldFullName);

        // 添加起点 Column 节点
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(fieldFullName);
        String[] parts = fieldFullName.split("\\.");
        String label = parts.length >= 3 ? parts[2] : fieldFullName;
        startNode.setLabel(label);
        startNode.setNodeLevel("FIELD");
        startNode.setDepth(0);
        nodes.add(startNode);

        try {
            // 步骤 1:查询所有可达 Column 节点
            String nodeCypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                    "WHERE src <> tgt " +
                    "WITH tgt, min(length(path)) AS minRelLen " +
                    "RETURN tgt.col_key AS id, tgt.name AS label, " +
                    "       tgt.table_full_name AS tableFullName, " +
                    "       (minRelLen + 1) / 2 AS depth " +
                    "ORDER BY depth, tgt.col_key";

            Collection<ImpactAnalysisVO.NodeVO> colNodes = neo4jClient.query(nodeCypher)
                    .bind(fieldFullName).to("key")
                    .fetchAs(ImpactAnalysisVO.NodeVO.class)
                    .mappedBy((type, record) -> {
                        ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                        n.setId(record.get("id").asString());
                        n.setLabel(record.get("label").asString());
                        n.setNodeLevel("FIELD");
                        n.setTableFullName(record.get("tableFullName").isNull() ? null : record.get("tableFullName").asString());
                        n.setDepth((int) record.get("depth").asLong());
                        return n;
                    })
                    .all();
            for (ImpactAnalysisVO.NodeVO n : colNodes) {
                if (nodeIds.add(n.getId())) {
                    nodes.add(n);
                }
            }

            // 步骤 2:查询所有 Transform 节点 + 边
            String edgeCypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                    "WHERE src <> tgt " +
                    "UNWIND nodes(path) AS n " +
                    "WITH DISTINCT n " +
                    "WHERE n:Transform " +
                    "OPTIONAL MATCH (in:Column)-[rIn:INPUT_TO]->(n) " +
                    "OPTIONAL MATCH (n)-[rOut:OUTPUT_TO]->(out:Column) " +
                    "RETURN n.id AS transformId, n.expression AS expression, " +
                    "       n.logic_type AS logicType, n.sql_hint AS sqlHint, " +
                    "       n.proc_name AS procName, n.script_file AS scriptFile, " +
                    "       collect(DISTINCT {colKey: in.col_key, role: rIn.role, alias: rIn.source_alias}) AS inputs, " +
                    "       out.col_key AS outputColKey";

            Collection<Map<String, Object>> transformRows = neo4jClient.query(edgeCypher)
                    .bind(fieldFullName).to("key")
                    .fetch().all();

            for (Map<String, Object> row : transformRows) {
                String transformId = (String) row.get("transformId");

                // 添加 Transform 节点
                if (nodeIds.add(transformId)) {
                    ImpactAnalysisVO.NodeVO tNode = new ImpactAnalysisVO.NodeVO();
                    tNode.setId(transformId);
                    tNode.setLabel(transformId);
                    tNode.setNodeLevel("TRANSFORM");
                    tNode.setExpression((String) row.get("expression"));
                    tNode.setLogicType((String) row.get("logicType"));
                    tNode.setSqlHint((String) row.get("sqlHint"));
                    tNode.setProcName((String) row.get("procName"));
                    nodes.add(tNode);
                }

                // 添加 INPUT_TO 边
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> inputs = (List<Map<String, Object>>) row.get("inputs");
                if (inputs != null) {
                    for (Map<String, Object> input : inputs) {
                        String colKey = (String) input.get("colKey");
                        if (colKey == null) continue;
                        ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                        edge.setSource(colKey);
                        edge.setTarget(transformId);
                        edge.setEdgeType("INPUT_TO");
                        edge.setRole((String) input.get("role"));
                        edge.setSourceAlias((String) input.get("alias"));
                        edge.setProcName((String) row.get("procName"));
                        edges.add(edge);
                    }
                }

                // 添加 OUTPUT_TO 边
                String outputColKey = (String) row.get("outputColKey");
                if (outputColKey != null) {
                    ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                    edge.setSource(transformId);
                    edge.setTarget(outputColKey);
                    edge.setEdgeType("OUTPUT_TO");
                    edge.setExpression((String) row.get("expression"));
                    edge.setProcName((String) row.get("procName"));
                    edges.add(edge);
                }
            }
        } catch (Exception e) {
            log.error("字段级影响查询失败: {}", e.getMessage());
        }
    }
```

- [ ] **步骤 5：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java
git commit -m "feat: analyzeFieldLevel 改造为 Transform 双查询 + buildFieldArrow"
```

---

## 任务 11：查询层 - expand + buildImpactedByDepth + buildStats

**文件：**
- 修改：`src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java`

- [ ] **步骤 1：改造 `expand` 方法的 `isFieldLevel` 判断**

找到 `expand` 方法中 `isFieldLevel` 判断的位置，改为：

```java
        boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2
                || isTransformId(request.getNodeId());
```

- [ ] **步骤 2：改造 `expand` 方法的字段级分支**

找到 `expand` 方法中字段级分支的 Cypher，替换为：

```java
        if (isFieldLevel) {
            int cypherMaxHops = depth * 2;
            String arrow = buildFieldArrow(direction, cypherMaxHops);
            String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                    "RETURN DISTINCT tgt.col_key AS id, tgt.name AS label, " +
                    "       (min(length(path)) + 1) / 2 AS depth " +
                    "ORDER BY depth, tgt.col_key";
            // ... (mapping 不变,沿用现有 fetchAs 逻辑)
```

- [ ] **步骤 3：改造 `buildImpactedByDepthFromFieldNodes` 方法的表级 impactSource 推导**

找到 `buildImpactedByDepthFromFieldNodes` 方法中通过边 split 推导 `tableImpactSourceMap` 的循环，替换为：

```java
        // 表级 impactSource: 通过 Transform 节点配对 INPUT_TO + OUTPUT_TO 推导
        Map<String, String> tableImpactSourceMap = new LinkedHashMap<>();

        // 构造 transformId -> outputColKey 映射
        Map<String, String> transformOutputMap = new HashMap<>();
        for (ImpactAnalysisVO.EdgeVO e : edges) {
            if ("OUTPUT_TO".equals(e.getEdgeType())) {
                transformOutputMap.put(e.getSource(), e.getTarget());
            }
        }

        // 对每个 INPUT_TO 边, 配对同 Transform 的 OUTPUT_TO, 推导 表A → 表B
        for (ImpactAnalysisVO.EdgeVO e : edges) {
            if (!"INPUT_TO".equals(e.getEdgeType())) continue;
            String transformId = e.getTarget();
            String outputColKey = transformOutputMap.get(transformId);
            if (outputColKey == null) continue;
            String srcTable = extractTableFromId(e.getSource());
            String tgtTable = extractTableFromId(outputColKey);
            if (srcTable == null || tgtTable == null) continue;
            if (!tgtTable.equals(sourceTable) && !tableImpactSourceMap.containsKey(tgtTable)) {
                tableImpactSourceMap.put(tgtTable, srcTable);
            }
        }

        // 降级: 边无 edgeType 时(旧数据), 用原 split 逻辑
        if (tableImpactSourceMap.isEmpty()) {
            for (ImpactAnalysisVO.EdgeVO e : edges) {
                String[] srcParts = e.getSource().split("\\.");
                String[] tgtParts = e.getTarget().split("\\.");
                if (srcParts.length < 3 || tgtParts.length < 3) continue;
                String srcTable = (srcParts[0] + "." + srcParts[1]).toLowerCase();
                String tgtTable = (tgtParts[0] + "." + tgtParts[1]).toLowerCase();
                if (!tgtTable.equals(sourceTable) && !tableImpactSourceMap.containsKey(tgtTable)) {
                    tableImpactSourceMap.put(tgtTable, srcTable);
                }
            }
        }
```

- [ ] **步骤 4：改造 `buildStats` 方法的 `maxDepthReached`**

找到 `buildStats` 方法中 `maxDepthReached` 的计算，改为排除 TRANSFORM：

```java
        int actualMax = nodes.stream()
                .filter(n -> !"TRANSFORM".equals(n.getNodeLevel()))
                .filter(n -> !Boolean.TRUE.equals(n.getIsTemp()))
                .map(ImpactAnalysisVO.NodeVO::getDepth)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        stats.setMaxDepthReached(actualMax);
```

- [ ] **步骤 5：编译验证**

运行：`D:\HB\maven\bin\mvn.cmd compile -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml`
预期：BUILD SUCCESS。

- [ ] **步骤 6：运行所有后端测试**

运行：`D:\HB\maven\bin\mvn.cmd test -f d:\HB\work_zuoye\proc-dependency-extractor\pom.xml -Dtest=FieldMappingExtractorTest`
预期：所有测试 PASS。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/service/impl/ImpactAnalysisServiceImpl.java
git commit -m "feat: expand/buildImpactedByDepth/buildStats 改造支持 Transform 模型"
```

---

## 任务 12：前端 - ImpactNode + ImpactFlowGraph

**文件：**
- 修改：`frontend/src/components/impact/ImpactNode.vue`
- 修改：`frontend/src/components/impact/ImpactFlowGraph.vue`

- [ ] **步骤 1：`ImpactNode.vue` 新增 TRANSFORM 节点渲染**

在 `ImpactNode.vue` 的 `<template>` 中新增 TRANSFORM 分支（在现有模板前）：

```vue
<template>
  <!-- TRANSFORM 节点 -->
  <div v-if="data.nodeLevel === 'TRANSFORM'" class="impact-node transform-node"
       :style="{ borderLeftColor: logicColor }">
    <div class="layer-badge" :style="{ background: logicColor }">
      {{ logicBadge }}
    </div>
    <div class="node-body">
      <div class="node-label" :title="data.expression">{{ expressionShort }}</div>
      <div v-if="data.logicType" class="logic-tag">{{ data.logicType }}</div>
    </div>
  </div>
  <!-- 表/字段节点:原模板不变 -->
  <div v-else class="impact-node" :style="{ borderLeftColor: color }">
    <!-- ... 原有内容保持不变 ... -->
  </div>
</template>
```

在 `<script setup>` 中新增：

```js
const LOGIC_COLORS = {
  DIRECT: '#94a3b8', LITERAL: '#64748b', ADDITION: '#3b82f6',
  SUBTRACTION: '#06b6d4', MULTIPLICATION: '#8b5cf6', DIVISION: '#a855f7',
  CASE: '#f59e0b', SUM: '#10b981', COUNT: '#22c55e', AVG: '#16a34a',
  MAX: '#15803d', MIN: '#166534', NVL: '#ef4444', CONCAT: '#ec4899',
  FUNCTION_OTHER: '#6366f1'
}
const LOGIC_BADGES = {
  ADDITION: '+', SUBTRACTION: '-', MULTIPLICATION: '×', DIVISION: '÷',
  SUM: 'Σ', COUNT: '#', AVG: 'Ā', MAX: '↑', MIN: '↓',
  CASE: '?', NVL: 'N', CONCAT: '∥', DIRECT: '·', LITERAL: '"', FUNCTION_OTHER: 'ƒ'
}
const logicColor = computed(() => LOGIC_COLORS[props.data.logicType] || '#94a3b8')
const logicBadge = computed(() => LOGIC_BADGES[props.data.logicType] || 'T')
const expressionShort = computed(() => {
  const expr = props.data.expression || props.data.label || ''
  return expr.length > 30 ? expr.substring(0, 30) + '...' : expr
})
```

在 `<style scoped>` 中新增：

```css
.transform-node {
  border-left-style: solid;
  border-left-width: 6px;
  background: #fff;
}
.transform-node .node-label {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #475569;
}
.logic-tag {
  display: inline-block;
  margin-top: 2px;
  padding: 0 4px;
  background: #f1f5f9;
  color: #475569;
  border-radius: 2px;
  font-size: 10px;
  font-weight: 600;
}
```

- [ ] **步骤 2：`ImpactFlowGraph.vue` 新增 transformNode 类型**

在 `<VueFlow>` 标签内新增节点类型注册：

```vue
  <template #node-transformNode="props">
    <ImpactNode :id="props.id" :data="props.data" />
  </template>
```

- [ ] **步骤 3：`ImpactFlowGraph.vue` 节点类型分流**

找到 `nodes.value = g.nodes().map(...)` 的位置，新增 type 分流：

```js
    type: node.nodeLevel === 'TRANSFORM' ? 'transformNode' : 'impactNode',
```

- [ ] **步骤 4：`ImpactFlowGraph.vue` 边样式区分**

找到 `edges.value = g.edges().map(...)` 的位置，新增边样式区分：

```js
    const isInputTo = edgeData.edgeType === 'INPUT_TO'
    const isOutputTo = edgeData.edgeType === 'OUTPUT_TO'
    return {
      id: `${e.v}-${e.w}`,
      source: e.v,
      target: e.w,
      type: 'smoothstep',
      animated: filtering ? bothMatched : !isInputTo,
      classed: filtering ? (bothMatched ? 'edge-highlight' : 'edge-dim') : '',
      style: {
        stroke: filtering
          ? (bothMatched ? '#1890ff' : '#64748b')
          : (isInputTo ? '#94a3b8' : (isOutputTo ? '#3b82f6' : '#64748b')),
        strokeWidth: filtering && bothMatched ? 2 : 1.5,
        opacity: filtering ? (bothMatched ? 1 : 0.15) : 1,
        strokeDasharray: isInputTo ? '5,5' : 'none'
      }
    }
```

- [ ] **步骤 5：`ImpactFlowGraph.vue` 字段透传**

找到 `expandNode` 和 `loadInitial` 中的 nodeMap/edgeMap 赋值，新增字段透传：

nodeMap 新增：
```js
      nodeLevel: n.nodeLevel,
      expression: n.expression,
      logicType: n.logicType,
      sqlHint: n.sqlHint,
      procName: n.procName
```

edgeMap 新增：
```js
      edgeType: e.edgeType,
      role: e.role,
      expression: e.expression,
      sourceAlias: e.sourceAlias,
      procName: e.procName
```

- [ ] **步骤 6：构建验证**

运行：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```
预期：构建成功，无编译错误。

- [ ] **步骤 7：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ImpactNode.vue frontend/src/components/impact/ImpactFlowGraph.vue
git commit -m "feat: 前端 ImpactNode 新增 TRANSFORM 渲染 + ImpactFlowGraph 节点/边类型分流"
```

---

## 任务 13：前端 - ImpactNodeDetail + ImpactEdgeDetail

**文件：**
- 修改：`frontend/src/components/impact/ImpactNodeDetail.vue`
- 修改：`frontend/src/components/impact/ImpactEdgeDetail.vue`

- [ ] **步骤 1：`ImpactNodeDetail.vue` 新增 TRANSFORM 详情面板**

在模板中新增 TRANSFORM 分支（在现有详情前）：

```vue
<template>
  <div class="node-detail">
    <!-- TRANSFORM 节点详情 -->
    <template v-if="node.nodeLevel === 'TRANSFORM'">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="类型">
          <el-tag type="warning" size="small">TRANSFORM</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="logic_type">
          <el-tag :color="logicColor" size="small">{{ node.logicType || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="表达式">
          <code class="expression-code">{{ node.expression }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="SQL 提示" v-if="node.sqlHint">
          <code class="hint-code">{{ node.sqlHint }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="存储过程" v-if="node.procName">
          {{ node.procName }}
        </el-descriptions-item>
        <el-descriptions-item label="目标字段" v-if="node.id">
          {{ node.id }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="inputFields.length" style="margin-top: 10px">
        <div style="font-weight: bold; margin-bottom: 5px">输入字段</div>
        <div v-for="(inp, i) in inputFields" :key="i" class="input-item">
          <el-tag :type="inp.role === 'condition' ? 'warning' : 'success'" size="small">
            {{ inp.role }}
          </el-tag>
          <code>{{ inp.colKey }}</code>
          <span v-if="inp.alias" style="color: #999; margin-left: 6px">({{ inp.alias }})</span>
        </div>
      </div>
    </template>

    <!-- 表/字段节点详情:原模板不变 -->
    <template v-else>
      <!-- ... 原有内容保持不变 ... -->
    </template>
  </div>
</template>
```

在 `<script setup>` 中新增：

```js
const props = defineProps({
  node: Object,
  edges: { type: Array, default: () => [] }
})

const inputFields = computed(() => {
  if (!props.node || props.node.nodeLevel !== 'TRANSFORM') return []
  return props.edges
    .filter(e => e.target === props.node.id && e.edgeType === 'INPUT_TO')
    .map(e => ({
      colKey: e.source,
      role: e.role || 'value',
      alias: e.sourceAlias
    }))
})

const LOGIC_COLORS = {
  DIRECT: '#94a3b8', LITERAL: '#64748b', ADDITION: '#3b82f6',
  SUBTRACTION: '#06b6d4', MULTIPLICATION: '#8b5cf6', DIVISION: '#a855f7',
  CASE: '#f59e0b', SUM: '#10b981', COUNT: '#22c55e', AVG: '#16a34a',
  MAX: '#15803d', MIN: '#166534', NVL: '#ef4444', CONCAT: '#ec4899',
  FUNCTION_OTHER: '#6366f1'
}
const logicColor = computed(() => LOGIC_COLORS[props.node?.logicType] || '#94a3b8')
```

新增样式：

```css
.expression-code {
  display: block;
  padding: 6px 8px;
  background: #f5f5f5;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.hint-code {
  display: block;
  padding: 4px 8px;
  background: #fffbe6;
  border-radius: 2px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #d48806;
}
.input-item {
  padding: 4px 8px;
  margin-bottom: 4px;
  background: #f5f5f5;
  border-radius: 2px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
```

- [ ] **步骤 2：`ImpactEdgeDetail.vue` 新增 edgeType/role/expression 展示**

在现有 `<el-descriptions>` 中新增条目：

```vue
      <el-descriptions-item label="边类型" v-if="edge.edgeType">
        <el-tag :type="edgeTypeTag(edge.edgeType)" size="small">{{ edge.edgeType }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="角色" v-if="edge.role">
        <el-tag :type="edge.role === 'condition' ? 'warning' : 'success'" size="small">
          {{ edge.role }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="表达式" v-if="edge.expression">
        <code class="expression-code">{{ edge.expression }}</code>
      </el-descriptions-item>
```

在 `<script setup>` 中新增：

```js
const edgeTypeTag = (type) => {
  if (type === 'INPUT_TO') return 'warning'
  if (type === 'OUTPUT_TO') return 'primary'
  if (type === 'READS') return 'info'
  return ''
}
```

- [ ] **步骤 3：构建验证**

运行：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor\frontend
npm run build
```
预期：构建成功。

- [ ] **步骤 4：Commit**

```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
git add frontend/src/components/impact/ImpactNodeDetail.vue frontend/src/components/impact/ImpactEdgeDetail.vue
git commit -m "feat: 前端 ImpactNodeDetail 新增 TRANSFORM 面板 + ImpactEdgeDetail 新增边类型展示"
```

---

## 任务 14：前端 - ImpactAnalysis.vue

**文件：**
- 修改：`frontend/src/views/ImpactAnalysis.vue`

- [ ] **步骤 1：`handleAnalyze` 字段级模式跳过 `buildWithoutTemp`**

找到 `handleAnalyze` 方法，新增字段级模式分支：

```js
const handleAnalyze = async (request) => {
  // ... 原有 request 构造逻辑不变 ...

  const res = await analyze(request)
  result.value = res.data

  // 新增:判断是否字段级
  const isFieldLevel = request.analysisType === 'FIELD'

  if (isFieldLevel) {
    // 字段级:直接使用后端返回的 nodes/edges(含 Transform)
    allNodesByDepth.value = res.data?.nodes || []
    allEdgesList.value = res.data?.edges || []
    visibleNodeIds.value = allNodesByDepth.value.map(n => n.id)
  } else {
    // 表级:沿用 buildWithoutTemp 逻辑(不变)
    const { nodes, edges } = buildWithoutTemp(sourceTable, sourceLayer, impactedByDepth)
    allNodesByDepth.value = nodes
    allEdgesList.value = edges
    visibleNodeIds.value = nodes
      .filter(n => n.depth <= DEFAULT_VISIBLE_DEPTH)
      .map(n => n.id)
  }
  updateVisibleGraph()
}
```

> 注意：`request.analysisType` 需根据实际字段名调整。检查现有代码中 `isFieldLevel` 的判断方式。

- [ ] **步骤 2：`availableSchemas` 跳过 TRANSFORM 节点**

找到 `availableSchemas` 计算属性，新增 TRANSFORM 跳过：

```js
const availableSchemas = computed(() => {
  const set = new Set()
  allNodesByDepth.value.forEach(n => {
    if (n.nodeLevel === 'TRANSFORM') return  // 新增
    if (n.layer && n.layer !== 'UNKNOWN') set.add(n.layer)
  })
  return Array.from(set)
})
```

- [ ] **步骤 3：`matchedNodeIds` TRANSFORM 节点关联保留**

找到 `matchedNodeIds` 计算属性，新增 TRANSFORM 关联判断：

```js
  allNodesByDepth.value.forEach(n => {
    if (n.nodeLevel === 'TRANSFORM') {
      // Transform 命中条件: 其任一 INPUT_TO 源字段或 OUTPUT_TO 目标字段命中
      const relatedEdges = allEdgesList.value.filter(
        e => (e.source === n.id || e.target === n.id)
      )
      const related = relatedEdges.some(e => {
        const otherEnd = e.source === n.id ? e.target : e.source
        return matched.has(otherEnd)
      })
      if (related) matched.add(n.id)
      return
    }
    // 原逻辑: 字段/表节点
    // ... 保持不变 ...
  })
```

- [ ] **步骤 4：`ImpactNodeDetail` 传入 `edges` prop**

找到 `<ImpactNodeDetail>` 组件引用，新增 `:edges` 传参：

```vue
<ImpactNodeDetail
  v-if="selectedNode"
  :node="selectedNode"
  :edges="allEdgesList"
  @expand="handleExpand"
  @collapse="handleCollapse"
/>
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
git add frontend/src/views/ImpactAnalysis.vue
git commit -m "feat: ImpactAnalysis.vue 字段级跳过 buildWithoutTemp + 筛选适配 Transform"
```

---

## 任务 15：端到端验证

**文件：** 无新文件，使用现有接口

- [ ] **步骤 1：启动后端**

运行：
```bash
cd d:\HB\work_zuoye\proc-dependency-extractor
D:\HB\maven\bin\mvn.cmd spring-boot:run
```
预期：应用启动成功，Neo4j 约束自动创建。

- [ ] **步骤 2：执行 DDL（如尚未执行）**

确认 `table_transform_input` 表和 `table_field_mapping.logic_type` 列已存在：

```sql
DESCRIBE table_field_mapping;       -- 应含 logic_type 列
SHOW TABLES LIKE 'table_transform_input';  -- 应返回 1 行
```

- [ ] **步骤 3：重新解析所有存储过程**

```bash
curl -X POST http://localhost:8080/api/proc/re-extract-all
```
预期：返回成功，`table_transform_input` 表有数据。

验证：
```sql
SELECT count(*) FROM table_transform_input;  -- 应 > 0
SELECT count(*) FROM table_field_mapping WHERE logic_type IS NOT NULL;  -- 应 > 0
```

- [ ] **步骤 4：全量同步 Neo4j**

```bash
curl -X POST http://localhost:8080/api/impact/resync
```
预期：返回成功，Neo4j 中 Transform 节点已创建，MAPS_TO 关系已清除。

验证 Cypher（在 Neo4j 浏览器 http://localhost:7474 执行）：
```cypher
MATCH (t:Transform) RETURN count(t)  -- 应 > 0
MATCH ()-[r:MAPS_TO]->() RETURN count(r)  -- 应 = 0
MATCH ()-[r:INPUT_TO]->() RETURN count(r)  -- 应 > 0
MATCH ()-[r:OUTPUT_TO]->() RETURN count(r)  -- 应 > 0
```

- [ ] **步骤 5：验证字段级影响分析**

```bash
curl -X POST http://localhost:8080/api/impact/analyze \
  -H "Content-Type: application/json" \
  -d '{"analysisType":"FIELD","fieldFullName":"cdm.c_agt_deposit_acct_inf.acct_no","direction":"UPSTREAM","maxDepth":5}'
```
预期：返回 VO 含 `nodeLevel: "TRANSFORM"` 节点和 `edgeType: "INPUT_TO"/"OUTPUT_TO"` 边。

- [ ] **步骤 6：前端页面验证**

访问 http://localhost:8080，进入影响分析页面：
1. 选择字段级分析，输入字段名，点击分析
2. 验证图谱渲染 Column + Transform + Column 链路
3. 验证 Transform 节点按 logic_type 着色
4. 验证 INPUT_TO 边虚线，OUTPUT_TO 边实线
5. 点击 Transform 节点验证详情面板（表达式 + 输入字段列表）
6. 点击 INPUT_TO / OUTPUT_TO 边验证详情
7. 验证表级分析不受影响

- [ ] **步骤 7：验证报表列溯源**

访问报表影响分析页面，执行字段溯源，验证图谱含 Transform 节点。

- [ ] **步骤 8：回归验证**

1. 表级影响分析：结果与改造前一致
2. 存储过程列表/详情：不受影响
3. 血缘图查询：不受影响

- [ ] **步骤 9：Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 端到端验证修复"
```

---

## 计划自检

### 1. 规格覆盖度

| 规格章节 | 对应任务 | 覆盖状态 |
|---------|---------|---------|
| §3.1 图模型 - Transform 节点 | 任务 8 | ✓ |
| §3.1 图模型 - INPUT_TO/OUTPUT_TO 关系 | 任务 8 | ✓ |
| §3.2 Neo4j 约束 | 任务 7 | ✓ |
| §3.3 MySQL - table_transform_input 表 | 任务 1 | ✓ |
| §3.3 MySQL - table_field_mapping.logic_type | 任务 1 | ✓ |
| §3.5 logic_type 枚举 15 类 | 任务 3 | ✓ |
| §4.1 TransformInput DTO | 任务 2 | ✓ |
| §4.1 FieldMapping 扩展 | 任务 2 | ✓ |
| §4.2 extractInputs 拆分算法 | 任务 4 | ✓ |
| §4.2 classifyLogicType 分类 | 任务 3 | ✓ |
| §4.2 populateInputsAndLogicType 集成 | 任务 5 | ✓ |
| §4.3 入库改造 | 任务 6 | ✓ |
| §5.2 deleteProcData 改造 | 任务 9 | ✓ |
| §5.3 syncProc 主流程 | 任务 8 | ✓ |
| §5.4 Transform 节点方法 | 任务 8 | ✓ |
| §5.5 删除旧方法 | 任务 9 | ✓ |
| §5.6 保留复用方法 | 任务 8（复用 findTableFullName） | ✓ |
| §6.3 analyzeFieldLevel 双查询 | 任务 10 | ✓ |
| §6.4 expand isTransformId | 任务 11 | ✓ |
| §6.5 buildImpactedByDepth 配对推导 | 任务 11 | ✓ |
| §6.6 buildStats 排除 TRANSFORM | 任务 11 | ✓ |
| §6.7 VO 扩展 | 任务 7 | ✓ |
| §7.2 ImpactNode TRANSFORM 渲染 | 任务 12 | ✓ |
| §7.3 ImpactFlowGraph 节点/边分流 | 任务 12 | ✓ |
| §7.4 ImpactNodeDetail TRANSFORM 面板 | 任务 13 | ✓ |
| §7.5 ImpactEdgeDetail 边类型展示 | 任务 13 | ✓ |
| §7.6 ImpactAnalysis 字段级适配 | 任务 14 | ✓ |
| §9 数据迁移路径 | 任务 15 | ✓ |

### 2. 类型一致性检查

| 类型/方法 | 定义任务 | 使用任务 | 一致性 |
|----------|---------|---------|--------|
| `TransformInput` DTO | 任务 2 | 任务 4, 5, 6 | ✓ |
| `TableTransformInput` 实体 | 任务 1 | 任务 6, 8 | ✓ |
| `TableTransformInputMapper` | 任务 1 | 任务 6, 8 | ✓ |
| `FieldMapping.inputs` | 任务 2 | 任务 5, 6 | ✓ |
| `FieldMapping.logicType` | 任务 2 | 任务 5, 8 | ✓ |
| `classifyLogicType()` | 任务 3 | 任务 5 | ✓ |
| `extractInputs()` | 任务 4 | 任务 5 | ✓ |
| `populateInputsAndLogicType()` | 任务 5 | 任务 5（extract 内调用） | ✓ |
| `generateTransformId()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `mergeTransformNode()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `createInputToRelationship()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `createOutputToRelationship()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `mergeLiteralColumn()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `generateSqlHint()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `inferLogicTypeFromExpr()` | 任务 8 | 任务 8（syncProc 内调用） | ✓ |
| `buildFieldArrow()` | 任务 10 | 任务 10, 11 | ✓ |
| `isTransformId()` | 任务 10 | 任务 11 | ✓ |
| `extractTableFromId()` | 任务 10 | 任务 11 | ✓ |
| `NodeVO.expression/logicType/sqlHint/procName` | 任务 7 | 任务 10, 12, 13 | ✓ |
| `EdgeVO.edgeType/role/expression` | 任务 7 | 任务 10, 12, 13 | ✓ |

### 3. 占位符扫描

- 无"TODO"/"待定"/"后续实现"
- 无"添加适当的错误处理"（每个方法已有明确 try-catch 或降级逻辑）
- 无"类似任务 N"（每个任务代码独立完整）
- 无未定义类型引用
