package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.TableDdlInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.ReportImpactService;
import com.hb.extractor.service.SchemaBatchImpactService;
import jakarta.annotation.PreDestroy;
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

    static class Task {
        final String taskId;
        final String schema;
        volatile String status = "running";
        volatile int total;
        volatile int done;
        volatile int failed;
        volatile String error;
        volatile long createTime = System.currentTimeMillis();
        volatile SchemaImpactSummaryVO result;
        Task(String taskId, String schema) {
            this.taskId = taskId;
            this.schema = schema;
        }
        String getTaskId() {
            return taskId;
        }
    }

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Task> latestBySchema = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final long TASK_TTL_MILLIS = 30L * 60 * 1000;

    @Autowired
    private TableDdlInfoMapper ddlInfoMapper;
    @Autowired
    private TableLineageMapper tableLineageMapper;
    @Autowired
    private ReportImpactService reportImpactService;

    @Override
    public String start(String schema) {
        sweep();
        Task task = createTaskAndRegister(schema);
        executor.submit(() -> run(task));
        return task.getTaskId();
    }

    private Task createTaskAndRegister(String schema) {
        Task old = latestBySchema.get(schema.toLowerCase());
        if (old != null && !"done".equals(old.status) && !"failed".equals(old.status)) {
            old.status = "failed";
            old.error = "已被新任务替换";
        }
        Task task = new Task(UUID.randomUUID().toString().replace("-", ""), schema);
        tasks.put(task.taskId, task);
        latestBySchema.put(schema.toLowerCase(), task);
        return task;
    }

    /** 清理过期且已终结的任务；最新任务(含 being-replaced)保留，避免 latestBySchema 悬空。 */
    void sweep() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Task> e : tasks.entrySet()) {
            Task t = e.getValue();
            boolean terminal = "done".equals(t.status) || "failed".equals(t.status);
            if (terminal && now - t.createTime > TASK_TTL_MILLIS) {
                toRemove.add(e.getKey());
            }
        }
        for (String id : toRemove) {
            Task t = tasks.remove(id);
            if (t != null && latestBySchema.get(t.schema.toLowerCase()) == t) {
                tasks.put(id, t); // 最新任务(含 being-replaced)保留，避免 latestBySchema 悬空
            }
        }
    }

    /** 包内可见供单测：注册任务。 */
    Task registerTaskForTest(String schema) {
        return createTaskAndRegister(schema);
    }

    /** 包内可见供单测：将任务标记为 done。 */
    void markTaskDone(Task t) {
        t.status = "done";
    }

    /** 包内可见供单测：老化任务 createTime。 */
    void ageTask(Task t, long millis) {
        t.createTime = System.currentTimeMillis() - millis;
    }

    /** 包内可见供单测：当前任务数。 */
    int taskCount() {
        return tasks.size();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
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