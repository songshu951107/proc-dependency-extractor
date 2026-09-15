# 批量 Schema 一层下游影响（表级）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增独立页面 `/schema-impact`，批量查询某个 schema 下所有物理表的一层（depth=1）直连下游影响，以「汇总矩阵 + 数字列下钻弹窗 + 导出 Excel」呈现，采用异步任务 + 前端进度轮询。

**架构：** 后端新增内存任务仓库式异步服务 `SchemaBatchImpactService`（复用 `TableDdlInfoMapper.selectBySchema` 枚举起点表、`TableLineageMapper` 直连下游查询、`ReportImpactService.findReportsByTable` 报表关联），控制器新增 `POST/GET .../schema/{schema}/task...` 四接口，POI 导出 4 sheet Excel。前端新增 `SchemaImpactView.vue` + 路由 + 侧边栏菜单，`setInterval` 2 秒轮询进度。现有单表影响分析完全保留、不改动。

**技术栈：** Spring Boot / MyBatis-Plus / Apache POI / Vue3 + Element Plus + Vue Router / Vite。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/com/hb/extractor/dto/SchemaBatchProgressVO.java` | 创建 | 进度响应 VO |
| `src/main/java/com/hb/extractor/dto/SchemaImpactSummaryVO.java` | 创建 | 汇总矩阵结果 VO |
| `src/main/java/com/hb/extractor/dto/SchemaImpactRowVO.java` | 创建 | 每起点表一行的明细 VO |
| `src/main/java/com/hb/extractor/dto/AffectedTableVO.java` | 创建 | 受影响表（含到达 proc）VO |
| `src/main/java/com/hb/extractor/service/SchemaBatchImpactService.java` | 创建 | 异步任务服务接口 |
| `src/main/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImpl.java` | 创建 | 异步任务服务实现（核心） |
| `src/main/java/com/hb/extractor/service/impl/SchemaImpactExcelExporter.java` | 创建 | POI 4-sheet 导出 |
| `src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java` | 修改 | 新增 4 个批量接口 |
| `src/test/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImplTest.java` | 创建 | 核心聚合逻辑单测 |
| `frontend/src/api/impact.js` | 修改 | 新增 4 个批量 API |
| `frontend/src/views/SchemaImpactView.vue` | 创建 | 批量分析页面 |
| `frontend/src/router/index.js` | 修改 | 注册 `/schema-impact` 路由 |
| `frontend/src/App.vue` | 修改 | 侧边栏加菜单项 |

---

### 任务 1：后端 DTO

**文件：**
- 创建：`src/main/java/com/hb/extractor/dto/SchemaBatchProgressVO.java`
- 创建：`src/main/java/com/hb/extractor/dto/SchemaImpactSummaryVO.java`
- 创建：`src/main/java/com/hb/extractor/dto/SchemaImpactRowVO.java`
- 创建：`src/main/java/com/hb/extractor/dto/AffectedTableVO.java`

- [ ] **步骤 1：创建 `SchemaBatchProgressVO.java`**

```java
package com.hb.extractor.dto;

import lombok.Data;

/**
 * 批量 schema 下游影响 - 异步任务进度 VO
 */
@Data
public class SchemaBatchProgressVO {
    private String taskId;
    private String schema;
    /** running / done / failed */
    private String status;
    /** 起点表总数 */
    private int total;
    /** 已处理表数 */
    private int done;
    /** 失败表数 */
    private int failed;
    /** 完成百分比 0-100 */
    private int percent;
    /** 任务级失败原因 */
    private String error;
    private long createTime;
}
```

- [ ] **步骤 2：创建 `AffectedTableVO.java`**

```java
package com.hb.extractor.dto;

import lombok.Data;

/**
 * 受影响物理表明细（含到达它的存储过程）
 */
@Data
public class AffectedTableVO {
    private String name;
    private String procName;
}
```

- [ ] **步骤 3：创建 `SchemaImpactRowVO.java`**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量 schema 下游影响 - 每起点表一行的明细
 */
@Data
public class SchemaImpactRowVO {
    /** 起点表全名 schema.table */
    private String table;
    private Integer affectedTableCount;
    private Integer affectedProcCount;
    private Integer affectedReportCount;
    /** 单表失败原因 */
    private String error;
    private List<AffectedTableVO> affectedTables = new ArrayList<>();
    private List<String> affectedProcs = new ArrayList<>();
    private List<ReportImpactVO.ReportRef> affectedReports = new ArrayList<>();
}
```

- [ ] **步骤 4：创建 `SchemaImpactSummaryVO.java`**

```java
package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量 schema 下游影响 - 汇总矩阵结果
 */
@Data
public class SchemaImpactSummaryVO {
    private String schema;
    /** 起点表总数 */
    private int totalTables;
    /** 失败起点表数 */
    private int failedCount;
    private List<SchemaImpactRowVO> rows = new ArrayList<>();
    /** 全局受影响报表（按 file 去重，供导出 Sheet4） */
    private List<ReportImpactVO.ReportRef> affectedReports = new ArrayList<>();
}
```

- [ ] **步骤 5：编译验证**

运行：`mvn -q -f pom.xml compile`
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/SchemaBatchProgressVO.java src/main/java/com/hb/extractor/dto/SchemaImpactSummaryVO.java src/main/java/com/hb/extractor/dto/SchemaImpactRowVO.java src/main/java/com/hb/extractor/dto/AffectedTableVO.java
git commit -m "feat: add schema batch impact DTOs"
```

---

### 任务 2：SchemaBatchImpactService（核心逻辑 + 单测）

**文件：**
- 创建：`src/main/java/com/hb/extractor/service/SchemaBatchImpactService.java`
- 创建：`src/main/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImpl.java`
- 测试：`src/test/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImplTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `src/test/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImplTest.java`：

```java
package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.SchemaImpactRowVO;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.ReportImpactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaBatchImpactServiceImplTest {

    @Mock private TableDdlInfoMapper ddlInfoMapper;
    @Mock private TableLineageMapper tableLineageMapper;
    @Mock private ReportImpactService reportImpactService;
    @InjectMocks private SchemaBatchImpactServiceImpl service;

    private TableLineage lineage(String target, String proc) {
        TableLineage tl = new TableLineage();
        tl.setTargetTable(target);
        tl.setProcName(proc);
        tl.setTargetIsTemp(0);
        return tl;
    }

    @Test
    void buildRow_dedupeTables_procs_andReports() {
        when(tableLineageMapper.selectList(any())).thenReturn(Arrays.asList(
                lineage("cdm.c_agt_ldr_acct_intst", "p1"),
                lineage("cdm.c_agt_ldr_acct_intst", "p2"),
                lineage("pid.a_d_fin_mkt_prft_loss_dtl", "p1")));

        ReportImpactVO.ReportRef r1 = new ReportImpactVO.ReportRef();
        r1.setFile("rpt_a.xlsx"); r1.setName("A");
        ReportImpactVO.ReportRef r2 = new ReportImpactVO.ReportRef();
        r2.setFile("rpt_a.xlsx"); r2.setName("A");
        ReportImpactVO.ReportRef r3 = new ReportImpactVO.ReportRef();
        r3.setFile("rpt_b.xlsx"); r3.setName("B");
        ReportImpactVO voCdm = new ReportImpactVO();
        voCdm.getReports().add(r1);
        ReportImpactVO voPid = new ReportImpactVO();
        voPid.getReports().add(r2);
        voPid.getReports().add(r3);
        when(reportImpactService.findReportsByTable("cdm", "c_agt_ldr_acct_intst")).thenReturn(voCdm);
        when(reportImpactService.findReportsByTable("pid", "a_d_fin_mkt_prft_loss_dtl")).thenReturn(voPid);

        SchemaImpactRowVO row = service.buildRow("fdm.f_ac_ctds_ldr_acct");

        // 两张物理表去重；两个 proc 去重；rpt_a.xlsx 跨表去重只算一次
        assertEquals(2, row.getAffectedTableCount());
        assertEquals(2, row.getAffectedProcs().size());
        assertEquals(2, row.getAffectedReportCount());
        assertEquals(2, row.getAffectedTables().size());
        assertEquals(2, row.getAffectedReports().size());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -f pom.xml test -Dtest=SchemaBatchImpactServiceImplTest`
预期：编译失败，报 `SchemaBatchImpactServiceImpl` 不存在。

- [ ] **步骤 3：创建服务接口 `SchemaBatchImpactService.java`**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.SchemaBatchProgressVO;
import com.hb.extractor.dto.SchemaImpactSummaryVO;

/**
 * 批量 schema 一层下游影响异步任务服务（内存任务仓库，不持久化）。
 */
public interface SchemaBatchImpactService {

    /** 发起任务：立即返回 taskId，异步执行。同一 schema 旧任务被替换。 */
    String start(String schema);

    /** 查询进度；taskId 不存在时返回 null。 */
    SchemaBatchProgressVO getProgress(String taskId);

    /**
     * 取最终结果。任务不存在/进行中/失败时抛 IllegalStateException
     * （进行中 / 失败信息见 e.getMessage()）。
     */
    SchemaImpactSummaryVO getResult(String taskId);
}
```

- [ ] **步骤 4：创建实现 `SchemaBatchImpactServiceImpl.java`**

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.TableDdlInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.ReportImpactService;
import com.hb.extractor.service.SchemaBatchImpactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 批量 schema 一层下游影响实现。
 * - 起点表：table_ddl_info by schema（剔除 V_/TMP_/BAK 命名，与单表页 getTables 一致）
 * - 直连下游：table_lineage where LOWER(source_table)=LOWER(?) and target_is_temp=0（双端 LOWER 兜底）
 * - 报表：对每个受影响物理表调 ReportImpactService.findReportsByTable，按 file 去重
 */
@Slf4j
@Service
public class SchemaBatchImpactServiceImpl implements SchemaBatchImpactService {

    private static class Task {
        final String taskId;
        final String schema;
        volatile String status = "running";
        volatile int total;
        volatile int done;
        volatile int failed;
        volatile String error;
        final long createTime = System.currentTimeMillis();
        volatile SchemaImpactSummaryVO result;
        Task(String taskId, String schema) {
            this.taskId = taskId;
            this.schema = schema;
        }
    }

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Task> latestBySchema = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Autowired
    private TableDdlInfoMapper ddlInfoMapper;
    @Autowired
    private TableLineageMapper tableLineageMapper;
    @Autowired
    private ReportImpactService reportImpactService;

    @Override
    public String start(String schema) {
        Task old = latestBySchema.get(schema.toLowerCase());
        if (old != null && !"done".equals(old.status) && !"failed".equals(old.status)) {
            old.status = "failed";
            old.error = "已被新任务替换";
        }
        Task task = new Task(UUID.randomUUID().toString().replace("-", ""), schema);
        tasks.put(task.taskId, task);
        latestBySchema.put(schema.toLowerCase(), task);
        executor.submit(() -> run(task));
        return task.taskId;
    }

    private void run(Task task) {
        try {
            List<TableDdlInfo> tables = ddlInfoMapper.selectBySchema(task.schema.toLowerCase());
            List<TableDdlInfo> startTables = tables.stream()
                    .filter(t -> {
                        if (t.getFullTableName() == null || t.getTableName() == null) return false;
                        String u = t.getTableName().toUpperCase();
                        if (u.matches("VT_.*|TMP_.*|.*_TMP\\d*")) return false;
                        if (u.matches("BAK\\d*|.*_BAK|.*_BAK\\d+")) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
            task.total = startTables.size();
            SchemaImpactSummaryVO summary = new SchemaImpactSummaryVO();
            summary.setSchema(task.schema);
            LinkedHashMap<String, ReportImpactVO.ReportRef> globalReports = new LinkedHashMap<>();
            for (TableDdlInfo t : startTables) {
                try {
                    SchemaImpactRowVO row = buildRow(t.getFullTableName());
                    for (ReportImpactVO.ReportRef r : row.getAffectedReports()) {
                        if (r.getFile() != null) globalReports.putIfAbsent(r.getFile(), r);
                    }
                    summary.getRows().add(row);
                } catch (Exception e) {
                    task.failed++;
                    SchemaImpactRowVO row = new SchemaImpactRowVO();
                    row.setTable(t.getFullTableName());
                    row.setError(e.getMessage());
                    summary.getRows().add(row);
                    log.error("schema 批量影响分析单表失败 table={}: {}", t.getFullTableName(), e.getMessage());
                } finally {
                    task.done++;
                }
            }
            summary.setTotalTables(task.total);
            summary.setFailedCount(task.failed);
            summary.setAffectedReports(new ArrayList<>(globalReports.values()));
            task.result = summary;
            task.status = "done";
        } catch (Exception e) {
            log.error("schema 批量影响分析任务异常 schema={}", task.schema, e);
            task.status = "failed";
            task.error = e.getMessage();
        }
    }

    /** 包内可见供单测：计算单张起点表的一层直连下游汇总与明细。 */
    SchemaImpactRowVO buildRow(String sourceFull) {
        List<TableLineage> lineage = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .apply("LOWER(source_table) = LOWER({0})", sourceFull)
                        .eq(TableLineage::getTargetIsTemp, 0));
        SchemaImpactRowVO row = new SchemaImpactRowVO();
        row.setTable(sourceFull);
        LinkedHashMap<String, String> targetProc = new LinkedHashMap<>();
        LinkedHashSet<String> procs = new LinkedHashSet<>();
        for (TableLineage tl : lineage) {
            if (tl.getTargetTable() == null) continue;
            targetProc.putIfAbsent(tl.getTargetTable(), tl.getProcName());
            if (tl.getProcName() != null) procs.add(tl.getProcName());
        }
        row.setAffectedTableCount(targetProc.size());
        row.setAffectedProcs(new ArrayList<>(procs));
        row.setAffectedProcCount(procs.size());

        LinkedHashMap<String, ReportImpactVO.ReportRef> reportMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : targetProc.entrySet()) {
            try {
                String[] pt = e.getKey().toLowerCase().split("\\.");
                if (pt.length < 2) continue;
                ReportImpactVO vo = reportImpactService.findReportsByTable(pt[0], pt[1]);
                for (ReportImpactVO.ReportRef r : vo.getReports()) {
                    if (r.getFile() == null || r.getFile().isEmpty()) continue;
                    reportMap.putIfAbsent(r.getFile(), r);
                }
            } catch (Exception ex) {
                log.warn("受影响报表查询失败 table={}: {}", e.getKey(), ex.getMessage());
            }
        }
        row.setAffectedReportCount(reportMap.size());
        row.setAffectedReports(new ArrayList<>(reportMap.values()));

        List<AffectedTableVO> aff = new ArrayList<>();
        for (Map.Entry<String, String> e : targetProc.entrySet()) {
            AffectedTableVO a = new AffectedTableVO();
            a.setName(e.getKey());
            a.setProcName(e.getValue());
            aff.add(a);
        }
        row.setAffectedTables(aff);
        return row;
    }

    @Override
    public SchemaBatchProgressVO getProgress(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return null;
        SchemaBatchProgressVO p = new SchemaBatchProgressVO();
        p.setTaskId(task.taskId);
        p.setSchema(task.schema);
        p.setStatus(task.status);
        p.setTotal(task.total);
        p.setDone(task.done);
        p.setFailed(task.failed);
        p.setError(task.error);
        p.setCreateTime(task.createTime);
        p.setPercent(task.total == 0 ? 0 : (int) (task.done * 100L / task.total));
        return p;
    }

    @Override
    public SchemaImpactSummaryVO getResult(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) throw new IllegalStateException("任务不存在或已清除");
        if ("running".equals(task.status)) throw new IllegalStateException("任务进行中");
        if ("failed".equals(task.status)) throw new IllegalStateException(task.error);
        return task.result;
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -f pom.xml test -Dtest=SchemaBatchImpactServiceImplTest`
预期：PASS（2 表、2 proc、2 报表去重断言全部通过）

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/service/SchemaBatchImpactService.java src/main/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImpl.java src/test/java/com/hb/extractor/service/impl/SchemaBatchImpactServiceImplTest.java
git commit -m "feat: async schema batch downstream impact service"
```

---

### 任务 3：控制器接口

**文件：**
- 修改：`src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java`

- [ ] **步骤 1：新增依赖注入**

在 `ImpactAnalysisController` 顶部（现有 `@Autowired Neo4jSyncLogMapper` 之后）新增注入与 import：

```java
import com.hb.extractor.dto.SchemaBatchProgressVO;
import com.hb.extractor.dto.SchemaImpactSummaryVO;
import com.hb.extractor.service.SchemaBatchImpactService;
import com.hb.extractor.service.impl.SchemaImpactExcelExporter;

// 成员区新增：
    @Autowired
    private SchemaBatchImpactService schemaBatchImpactService;

    @Autowired
    private SchemaImpactExcelExporter schemaImpactExcelExporter;
```

- [ ] **步骤 2：新增批量接口方法（追加在 `getSyncStatus` 之后）**

```java
    /** 发起 schema 批量一层下游影响分析（异步，立即返回 taskId）。 */
    @PostMapping("/schema/{schema}/task")
    public Result<String> startSchemaBatchTask(@PathVariable String schema) {
        return Result.success(schemaBatchImpactService.start(schema));
    }

    /** 查询 schema 批量任务进度。 */
    @GetMapping("/schema/{schema}/task/{taskId}/progress")
    public Result<SchemaBatchProgressVO> schemaBatchProgress(@PathVariable String schema,
                                                             @PathVariable String taskId) {
        SchemaBatchProgressVO p = schemaBatchImpactService.getProgress(taskId);
        if (p == null) return Result.error(404, "任务不存在或已清除");
        return Result.success(p);
    }

    /** 取 schema 批量任务最终结果。进行中返回 409。 */
    @GetMapping("/schema/{schema}/task/{taskId}/result")
    public Result<SchemaImpactSummaryVO> schemaBatchResult(@PathVariable String schema,
                                                           @PathVariable String taskId) {
        try {
            return Result.success(schemaBatchImpactService.getResult(taskId));
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    /** 导出 schema 批量结果 Excel（4 sheet）。任务未完成返回 409。 */
    @GetMapping("/schema/{schema}/task/{taskId}/export")
    public void schemaBatchExport(@PathVariable String schema,
                                  @PathVariable String taskId,
                                  HttpServletResponse response) throws IOException {
        SchemaImpactSummaryVO summary;
        try {
            summary = schemaBatchImpactService.getResult(taskId);
        } catch (IllegalStateException e) {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":409,\"message\":\""
                    + e.getMessage().replace("\"", "'") + "\"}");
            return;
        }
        String filename = "schema_impact_" + schema + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        schemaImpactExcelExporter.export(summary, response.getOutputStream());
    }
```

- [ ] **步骤 3：补充 import**

确保文件顶部已 import `javax.servlet.http.HttpServletResponse` 与 `java.io.IOException`（如缺失则补上）。

- [ ] **步骤 4：编译验证**（注意：因任务 4 尚未创建 Excel exporter，此处仅验证 schema-task 接口前缀能解析）

运行：`mvn -q -f pom.xml compile`
预期：可能因 `SchemaImpactExcelExporter` 未创建而失败 —— 属预期，先与任务 4 合并后再整体编译。

（说明：`schemaBatchExport` 依赖任务 4 的 `SchemaImpactExcelExporter`，请先完成任务 4 后再回到本任务执行 **步骤 4 编译验证与步骤 5 Commit**。）

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java
git commit -m "feat: schema batch impact controller endpoints"
```

---

### 任务 4：Excel 导出器

**文件：**
- 创建：`src/main/java/com/hb/extractor/service/impl/SchemaImpactExcelExporter.java`

- [ ] **步骤 1：实现导出器**

```java
package com.hb.extractor.service.impl;

import com.hb.extractor.dto.AffectedTableVO;
import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.SchemaImpactRowVO;
import com.hb.extractor.dto.SchemaImpactSummaryVO;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 批量 schema 下游影响结果导出为 4-sheet Excel。
 * Sheet1 汇总矩阵 / Sheet2 受影响表明细 / Sheet3 受影响存储过程明细 / Sheet4 受影响报表明细。
 */
@Component
public class SchemaImpactExcelExporter {

    public void export(SchemaImpactSummaryVO summary, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet s1 = wb.createSheet("汇总矩阵");
            writeHeader(s1, new String[]{"表名", "被影响表数", "受影响存储过程数", "涉及报表数"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                Row r = s1.createRow(s1.getLastRowNum() + 1);
                r.createCell(0).setCellValue(row.getTable());
                r.createCell(1).setCellValue(orZero(row.getAffectedTableCount()));
                r.createCell(2).setCellValue(orZero(row.getAffectedProcCount()));
                r.createCell(3).setCellValue(orZero(row.getAffectedReportCount()));
            }

            XSSFSheet s2 = wb.createSheet("受影响表明细");
            writeHeader(s2, new String[]{"起点表", "受影响表", "到达的proc"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (AffectedTableVO a : row.getAffectedTables()) {
                    Row r = s2.createRow(s2.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(a.getName());
                    r.createCell(2).setCellValue(a.getProcName() == null ? "" : a.getProcName());
                }
            }

            XSSFSheet s3 = wb.createSheet("受影响存储过程明细");
            writeHeader(s3, new String[]{"起点表", "受影响存储过程"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (String p : row.getAffectedProcs()) {
                    Row r = s3.createRow(s3.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(p);
                }
            }

            XSSFSheet s4 = wb.createSheet("受影响报表明细");
            writeHeader(s4, new String[]{"起点表", "报表文件", "报表名称", "数据集", "连接"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (ReportImpactVO.ReportRef ref : row.getAffectedReports()) {
                    Row r = s4.createRow(s4.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(ref.getFile());
                    r.createCell(2).setCellValue(ref.getName() == null ? "" : ref.getName());
                    r.createCell(3).setCellValue(ref.getDatasetName() == null ? "" : ref.getDatasetName());
                    r.createCell(4).setCellValue(ref.getConnection() == null ? "" : ref.getConnection());
                }
            }

            wb.write(out);
        }
    }

    private int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private void writeHeader(XSSFSheet sheet, String[] head) {
        Row r = sheet.createRow(0);
        for (int i = 0; i < head.length; i++) r.createCell(i).setCellValue(head[i]);
    }
}
```

- [ ] **步骤 2：整体编译验证（回到任务 3 的步骤 4）**

运行：`mvn -q -f pom.xml compile`
预期：BUILD SUCCESS（控制器 + 导出器 + DTO + 服务全部编译通过）

- [ ] **步骤 3：Commit（一并提交任务 3 控制器改动）**

```bash
git add src/main/java/com/hb/extractor/service/impl/SchemaImpactExcelExporter.java src/main/java/com/hb/extractor/controller/ImpactAnalysisController.java
git commit -m "feat: schema batch impact excel export + controller"
```

---

### 任务 5：前端 API 封装

**文件：**
- 修改：`frontend/src/api/impact.js`

- [ ] **步骤 1：新增批量 API（追加在 `getSyncStatus` 之后）**

```js
// 批量 schema 下游影响（异步任务 + 进度轮询）
export function startSchemaBatchTask(schema) {
  return request({ url: `/impact/schema/${schema}/task`, method: 'post' })
}
export function getSchemaBatchProgress(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/progress`, method: 'get' })
}
export function getSchemaBatchResult(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/result`, method: 'get' })
}
// responseType blob：拦截器对 blob 不做 code 校验，正常返回 Blob
export function exportSchemaBatch(schema, taskId) {
  return request({ url: `/impact/schema/${schema}/task/${taskId}/export`, method: 'get', responseType: 'blob' })
}
```

- [ ] **步骤 2：构建验证**

运行：`npm run build --prefix frontend`
预期：构建成功，无语法错误（新函数仅声明，不影响编译）

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/api/impact.js
git commit -m "feat: frontend api for schema batch impact"
```

---

### 任务 6：SchemaImpactView 页面 + 路由 + 菜单

**文件：**
- 创建：`frontend/src/views/SchemaImpactView.vue`
- 修改：`frontend/src/router/index.js`
- 修改：`frontend/src/App.vue`

- [ ] **步骤 1：创建 `SchemaImpactView.vue`**

```vue
<template>
  <div style="padding: 16px;">
    <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
      <el-select v-model="schema" placeholder="选择 Schema" filterable style="width: 200px;"
                 :disabled="running" @change="onSchemaChange">
        <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button type="primary" :loading="running" :disabled="!schema || running" @click="startAnalysis">
        批量分析
      </el-button>
      <el-button :disabled="!result || running" @click="doExport">导出 Excel</el-button>
      <el-progress v-if="task" :percentage="percent()" :status="taskStatus()"
                   :stroke-width="12" style="width: 240px;" />
    </div>

    <el-empty v-if="!result && !running" description="请选择 Schema 并点击「批量分析」开始" />

    <el-table v-if="result" :data="result.rows" border height="560">
      <el-table-column prop="table" label="表名" min-width="280" show-overflow-tooltip />
      <el-table-column label="被影响表数" width="120" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedTableCount" @click="openDetail(row, 'tables')">
            {{ row.affectedTableCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="受影响存储过程数" width="160" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedProcCount" @click="openDetail(row, 'procs')">
            {{ row.affectedProcCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="涉及报表数" width="120" align="center">
        <template #default="{ row }">
          <el-link type="primary" :disabled="!row.affectedReportCount" @click="openDetail(row, 'reports')">
            {{ row.affectedReportCount }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="140">
        <template #default="{ row }">
          <span v-if="row.error" style="color: #f56c6c;">失败: {{ row.error }}</span>
          <span v-else style="color: #67c23a;">OK</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailVisible" :title="detailTitle()" width="760px" top="8vh">
      <el-table :data="detailRows" border max-height="460">
        <template v-if="detailKind === 'tables'">
          <el-table-column prop="name" label="受影响表" min-width="280" />
          <el-table-column prop="procName" label="到达的存储过程" min-width="240" />
        </template>
        <template v-else-if="detailKind === 'procs'">
          <el-table-column prop="name" label="受影响存储过程" min-width="360" />
        </template>
        <template v-else-if="detailKind === 'reports'">
          <el-table-column prop="file" label="报表文件" min-width="220" />
          <el-table-column prop="name" label="报表名称" min-width="160" />
          <el-table-column prop="datasetName" label="数据集" min-width="160" />
          <el-table-column prop="connection" label="连接" min-width="140" />
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getSchemas,
  startSchemaBatchTask,
  getSchemaBatchProgress,
  getSchemaBatchResult,
  exportSchemaBatch
} from '@/api/impact'

const schemas = ref([])
const schema = ref('')
const running = ref(false)
const task = ref(null)
const result = ref(null)
const detailVisible = ref(false)
const detailKind = ref('tables')
const detailRows = ref([])
let pollTimer = null

function percent() {
  return task.value ? task.value.percent : 0
}
function taskStatus() {
  if (!task.value) return 'success'
  return task.value.status === 'failed' ? 'exception' : 'success'
}
function detailTitle() {
  const t = detailKind.value === 'tables' ? '被影响表明细'
    : detailKind.value === 'procs' ? '受影响存储过程明细' : '涉及报表明细'
  return t
}

async function loadSchemas() {
  const res = await getSchemas()
  schemas.value = res.data || []
}
loadSchemas()

function onSchemaChange() {
  stopPoll()
  result.value = null
  task.value = null
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  running.value = false
}

async function startAnalysis() {
  stopPoll()
  result.value = null
  running.value = true
  try {
    const res = await startSchemaBatchTask(schema.value)
    const taskId = res.data
    task.value = { taskId, status: 'running', total: 0, done: 0, failed: 0, percent: 0 }
    poll(taskId)
  } catch (e) {
    stopPoll()
  }
}

function poll(taskId) {
  pollTimer = setInterval(async () => {
    try {
      const res = await getSchemaBatchProgress(schema.value, taskId)
      const t = res.data
      if (!t) {
        stopPoll()
        return
      }
      task.value = t
      if (t.status === 'done') {
        stopPoll()
        await loadResult(taskId)
      } else if (t.status === 'failed') {
        stopPoll()
        ElMessage.error(t.error || '分析失败')
      }
    } catch (e) {
      // 单次失败交给下次
    }
  }, 2000)
}

async function loadResult(taskId) {
  const res = await getSchemaBatchResult(schema.value, taskId)
  result.value = res.data
}

function openDetail(row, kind) {
  detailKind.value = kind
  detailVisible.value = true
  if (kind === 'tables') {
    detailRows.value = row.affectedTables || []
  } else if (kind === 'procs') {
    detailRows.value = (row.affectedProcs || []).map((p) => ({ name: p }))
  } else {
    detailRows.value = row.affectedReports || []
  }
}

async function doExport() {
  if (!task.value || !result.value) {
    ElMessage.warning('请先完成分析')
    return
  }
  try {
    const blob = await exportSchemaBatch(schema.value, task.value.taskId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `schema_impact_${schema.value}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    // 拦截器已提示
  }
}

onBeforeUnmount(stopPoll)
</script>
```

- [ ] **步骤 2：注册路由（`frontend/src/router/index.js`，在 `/report-impact` 之后追加）**

```js
  {
    path: '/schema-impact',
    name: 'SchemaImpact',
    component: () => import('../views/SchemaImpactView.vue'),
    meta: { title: '集市批量影响' }
  },
```

- [ ] **步骤 3：侧边栏加菜单（`frontend/src/App.vue`，在两个 el-menu-item 之间加）**

```html
          <el-menu-item index="/schema-impact">
            <el-icon><Aim /></el-icon>
            <span>集市批量影响</span>
          </el-menu-item>
```

（`Aim` 图标已 import，无需改 import 语句；若想区分可改成已有图标，此处复用 Aim 即可。）

- [ ] **步骤 4：构建验证**

运行：`npm run build --prefix frontend`
预期：构建成功

- [ ] **步骤 5：Commit**

```bash
git add frontend/src/views/SchemaImpactView.vue frontend/src/router/index.js frontend/src/App.vue
git commit -m "feat: schema batch impact page, route and menu"
```

---

### 任务 7：端到端验证

**文件：** 无（仅验证）

- [ ] **步骤 1：后端全量测试 + 编译**

运行：`mvn -q -f pom.xml test`
预期：BUILD SUCCESS（含新增 `SchemaBatchImpactServiceImplTest`，既有测试通过）

- [ ] **步骤 2：前端生产构建**

运行：`npm run build --prefix frontend`
预期：构建成功

- [ ] **步骤 3：手工功能验证清单**

启动后端与前端（`npm run dev --prefix frontend`），打开 `/schema-impact`：
1. Schema 下拉能加载 schema 列表。
2. 选 schema 点「批量分析」→ 按钮 loading、进度条显示百分比 → 停轮询后渲染汇总矩阵。
3. 点「被影响表数」数字 → 弹窗只显示受影响表 + 到达 proc 列表。
4. 点「受影响存储过程数」→ 弹窗只显示存储过程列表。
5. 点「涉及报表数」→ 弹窗只显示报表（文件/名称/数据集/连接）。
6. 点「导出 Excel」→ 下载 4-sheet 文件，Sheet 数据与矩阵一致。
7. 切 schema 重发 → 新任务替换旧任务，无残留轮询。
8. 回归：`/impact` 单表影响分析（Neo4j analyze）不受影响。

- [ ] **步骤 4：Commit（如有遗留改动）**

```bash
git add -A
git commit -m "chore: verify schema batch impact end-to-end"
```

---

## 自检

**1. 规格覆盖度：**
- 4.1 异步接口（task/progress/result/export）→ 任务 3；状态机/内存任务仓库 → 任务 2。
- 4.2 处理流程（枚举起点表 → 一层直连 → 报表去重 → 聚合）→ 任务 2 `run`/`buildRow`。
- 4.3 返回 VO → 任务 1 DTO。
- 4.4 导出 4 sheet → 任务 4。
- 5.1 页面布局（工具栏 + 汇总矩阵 + 数字列下钻弹窗）→ 任务 6。
- 5.2 异步进度轮询（2s setInterval、done/failed/running 分支、切 schema 重置）→ 任务 6 `poll`。
- 5.4 导出按钮 → 任务 6 `doExport`。
- 5.5 独立入口 + 保留单表分析 → 任务 6 新增路由/菜单；未改动单表分析代码。
- 一致性（LOWER、target_is_temp=0、filter temp/bak、报表去重）→ 任务 2。

**2. 占位符扫描：** 无 TODO/待定；所有类/方法/属性均已在本计划定义，类型一致（`AffectedTableVO.name/procName`、`SchemaImpactRowVO.*`、`SchemaBatchProgressVO.*`、`ReportImpactVO.ReportRef.file/name/datasetName/connection` 均匹配 Excel 导出与前端字段）。

**3. 类型一致性：** `buildRow`（包内可见）签名、`getResult` 抛 `IllegalStateException`、前端字段映射（`affectedTables/affectedProcs/affectedReports`）前后一致。