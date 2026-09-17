package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.dto.ReportReParseProgressVO;
import com.hb.extractor.dto.ReportDatasetDetailVO;
import com.hb.extractor.parser.CptXmlParser;
import com.hb.extractor.parser.ReportSqlParser;
import com.hb.extractor.parser.dto.*;
import com.hb.extractor.service.ReportParseService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 报表脚本目录（配置项 extractor.report.dir，可用环境变量 EXTRACTOR_REPORT_DIR 覆盖） */
    @Value("${extractor.report.dir:d:/HB/work_zuoye/reports}")
    private String reportDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 报表全量重解析异步任务（单个任务串行执行，避免并发重写报表数据） */
    private final ExecutorService reParseExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, ReParseTask> reParseTasks = new ConcurrentHashMap<>();

    /** 已完成任务保留时长（毫秒），避免长期占用内存 */
    private static final long TASK_TTL_MILLIS = 30 * 60 * 1000L;

    /** 报表全量重解析任务内部状态（volatile 保证跨线程可见） */
    private static class ReParseTask {
        final String taskId;
        volatile String status = "RUNNING";
        volatile int total;
        volatile int done;
        volatile int success;
        volatile int fail;
        volatile String current;
        volatile String error;
        final long startTime = System.currentTimeMillis();

        ReParseTask(String taskId) {
            this.taskId = taskId;
        }
    }

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
            reportInfo.setFilePath(path.toString());
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
                    rcb.setHeaderText(cb.getHeaderText());
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
            // 覆盖所有 SELECT 字段（含无单元格绑定的），确保影响分析可查全部使用字段
            List<ReportCellBinding> dsBindings = bindingsByDs.getOrDefault(ds.getName(), Collections.emptyList());
            // 按 selectAlias 聚合单元格位置
            Map<String, List<String>> cellsByAlias = new LinkedHashMap<>();
            for (ReportCellBinding rcb : dsBindings) {
                String aliasKey = rcb.getSelectAlias() != null ? rcb.getSelectAlias() : rcb.getColumnName();
                cellsByAlias.computeIfAbsent(aliasKey, k -> new ArrayList<>()).add(rcb.getCellPos());
            }

            List<ColumnMapping> columnMappings = reportSqlParser.extractColumns(ds.getSql());
            for (ColumnMapping cm : columnMappings) {
                if (cm.getSourceTable() == null || cm.getSourceField() == null) continue;
                String colKey = (cm.getSourceTable() + "." + cm.getSourceField()).toLowerCase();
                String aliasKey = cm.getSelectAlias();

                // MERGE Column 节点（复用现有）
                Map<String, Object> colParams = new HashMap<>();
                colParams.put("colKey", colKey);
                colParams.put("name", cm.getSourceField());
                colParams.put("tableFullName", cm.getSourceTable());
                neo4jClient.query("MERGE (c:Column {col_key: $colKey}) " +
                        "SET c.name = $name, c.table_full_name = $tableFullName")
                        .bindAll(colParams).run();

                // BELONGS_TO（复用现有）
                Map<String, Object> belongParams = new HashMap<>();
                belongParams.put("colKey", colKey);
                belongParams.put("tableName", cm.getSourceTable());
                neo4jClient.query("MATCH (c:Column {col_key: $colKey}), (t:Table {name: $tableName}) " +
                        "MERGE (c)-[:BELONGS_TO]->(t)").bindAll(belongParams).run();

                // REFERENCES 关系（cell_refs 从单元格绑定聚合，无绑定时为空数组）
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
                refParams.put("sourceExpr", cm.getSourceExpr());
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
        // Files.walk 递归遍历子目录，适配多层文件夹存放报表文件
        for (Path p : collectReportFiles(dirPath)) {
            try {
                results.add(parseAndSave(p.toString()));
            } catch (Exception e) {
                log.error("解析报表失败 file={}: {}", p, e.getMessage());
            }
        }
        return results;
    }

    /** 递归收集目录下所有报表文件（.cpt / .frm / .xml） */
    private List<Path> collectReportFiles(String dirPath) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(Paths.get(dirPath))) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".cpt") || name.endsWith(".frm") || name.endsWith(".xml");
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + e.getMessage(), e);
        }
        return files;
    }

    @Override
    public String startReParseAll() {
        purgeExpiredTasks();
        ReParseTask task = new ReParseTask(UUID.randomUUID().toString().replace("-", ""));
        reParseTasks.put(task.taskId, task);
        reParseExecutor.submit(() -> {
            try {
                List<Path> files = collectReportFiles(reportDir);
                task.total = files.size();
                for (Path p : files) {
                    task.current = p.getFileName().toString();
                    try {
                        parseAndSave(p.toString());
                        task.success++;
                    } catch (Exception e) {
                        task.fail++;
                        log.error("报表全量重解析失败 file={}: {}", p, e.getMessage());
                    } finally {
                        task.done++;
                    }
                }
                task.status = "DONE";
                log.info("报表全量重解析完成：total={}, success={}, fail={}", task.total, task.success, task.fail);
            } catch (Exception e) {
                task.status = "FAILED";
                task.error = e.getMessage();
                log.error("报表全量重解析异常", e);
            }
        });
        return task.taskId;
    }

    @Override
    public ReportReParseProgressVO getReParseProgress(String taskId) {
        ReParseTask t = reParseTasks.get(taskId);
        if (t == null) return null;
        ReportReParseProgressVO vo = new ReportReParseProgressVO();
        vo.setTaskId(t.taskId);
        vo.setStatus(t.status);
        vo.setTotal(t.total);
        vo.setDone(t.done);
        vo.setSuccess(t.success);
        vo.setFail(t.fail);
        vo.setCurrent(t.current);
        vo.setError(t.error);
        vo.setCreateTime(new java.util.Date(t.startTime));
        vo.setPercent(t.total == 0 ? 0 : (int) (t.done * 100L / t.total));
        return vo;
    }

    /** 清理已完成且超过保留时长的旧任务，防止 map 无限增长 */
    private void purgeExpiredTasks() {
        long now = System.currentTimeMillis();
        reParseTasks.entrySet().removeIf(e ->
                !"RUNNING".equals(e.getValue().status) && (now - e.getValue().startTime) > TASK_TTL_MILLIS);
    }

    @PreDestroy
    public void shutdownReParseExecutor() {
        reParseExecutor.shutdown();
    }

    @Override
    public List<ReportInfo> reParseAll() {
        List<ReportInfo> existing = reportInfoMapper.selectList(null);
        List<ReportInfo> results = new ArrayList<>();
        // 假设报表文件在配置的报表目录（extractor.report.dir）
        return parseBatch(reportDir);
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
        IPage<ReportInfo> result = reportInfoMapper.selectPage(page, wrapper);
        // 旧数据 file_path 兜底：解析时已存路径，历史数据为 null 则用报表目录拼接
        for (ReportInfo r : result.getRecords()) {
            if (r.getFilePath() == null || r.getFilePath().isBlank()) {
                r.setFilePath(reportDir + "/" + r.getFile());
            }
        }
        return result;
    }

    @Override
    public ReportInfo getReportDetail(String file) {
        return reportInfoMapper.selectOne(new LambdaQueryWrapper<ReportInfo>()
                .eq(ReportInfo::getFile, file));
    }

    @Override
    public ReportDatasetDetailVO buildDatasetDetail(String dsName, String connection, String sqlText) {
        ReportDatasetDetailVO vo = new ReportDatasetDetailVO();
        vo.setDatasetName(dsName);
        vo.setConnection(connection);
        vo.setSqlText(sqlText);
        vo.setSourceTables(reportSqlParser.extractTables(sqlText));
        vo.setFieldMappings(reportSqlParser.extractColumns(sqlText));
        vo.setTableCount(vo.getSourceTables().size());
        return vo;
    }

    @Override
    public List<ReportDatasetDetailVO> getReportDatasets(String file) {
        List<ReportDataset> datasets = reportDatasetMapper.selectList(
                new LambdaQueryWrapper<ReportDataset>().eq(ReportDataset::getReportFile, file));
        List<ReportDatasetDetailVO> result = new ArrayList<>();
        for (ReportDataset ds : datasets) {
            result.add(buildDatasetDetail(ds.getDatasetName(), ds.getConnection(), ds.getSqlText()));
        }
        return result;
    }
}
