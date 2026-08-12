package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.ImpactAnalysisRequest;
import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.entity.ReportCellBinding;
import com.hb.extractor.entity.ReportInfo;
import com.hb.extractor.mapper.ReportCellBindingMapper;
import com.hb.extractor.mapper.ReportInfoMapper;
import com.hb.extractor.service.ImpactAnalysisService;
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
    @Autowired
    private ReportCellBindingMapper reportCellBindingMapper;
    @Autowired
    private ReportInfoMapper reportInfoMapper;
    @Autowired
    private ImpactAnalysisService impactAnalysisService;

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
    public ReportImpactVO findReportsByHeaderText(String headerText) {
        ReportImpactVO vo = new ReportImpactVO();
        if (headerText == null || headerText.trim().isEmpty()) {
            return vo;
        }

        // 模糊匹配表头文本
        List<ReportCellBinding> bindings = reportCellBindingMapper.selectList(
                new LambdaQueryWrapper<ReportCellBinding>()
                        .like(ReportCellBinding::getHeaderText, headerText.trim()));

        if (bindings.isEmpty()) {
            return vo;
        }

        // 收集涉及的报表文件（去重，保持顺序）
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (ReportCellBinding b : bindings) {
            files.add(b.getReportFile());
        }

        // 一次性查出报表信息
        List<ReportInfo> reportInfos = reportInfoMapper.selectList(
                new LambdaQueryWrapper<ReportInfo>().in(ReportInfo::getFile, files));
        Map<String, ReportInfo> infoMap = new HashMap<>();
        for (ReportInfo ri : reportInfos) {
            infoMap.put(ri.getFile(), ri);
        }

        // 构造报表清单（按文件去重）
        for (String file : files) {
            ReportInfo ri = infoMap.get(file);
            ReportImpactVO.ReportRef ref = new ReportImpactVO.ReportRef();
            ref.setFile(file);
            ref.setName(ri != null ? ri.getName() : file);
            vo.getReports().add(ref);
        }

        // 构造绑定明细
        for (ReportCellBinding b : bindings) {
            ReportImpactVO.BindingDetail bd = new ReportImpactVO.BindingDetail();
            bd.setReportFile(b.getReportFile());
            bd.setDatasetName(b.getDatasetName());
            bd.setCellPos(b.getCellPos());
            bd.setColumnName(b.getColumnName());
            bd.setHeaderText(b.getHeaderText());
            bd.setSelectAlias(b.getSelectAlias());
            bd.setSourceTable(b.getSourceTable());
            bd.setSourceField(b.getSourceField());
            bd.setSourceExpr(b.getSourceExpr());
            vo.getBindings().add(bd);
        }
        return vo;
    }

    @Override
    public List<String> getFields(String schema, String table) {
        String tableName = (schema + "." + table).toLowerCase();
        String cypher = "MATCH (t:Table {name: $name})<-[:QUERIES]-(d:Dataset)-[ref:REFERENCES]->(c:Column) " +
                "RETURN DISTINCT c.name AS fieldName ORDER BY fieldName";
        Collection<Map<String, Object>> result = neo4jClient.query(cypher)
                .bind(tableName).to("name")
                .fetch().all();
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> row : result) {
            Object name = row.get("fieldName");
            if (name != null) fields.add(name.toString());
        }
        return fields;
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

    @Override
    public List<String> getReportHeaders(String reportFile) {
        if (reportFile == null || reportFile.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // 查询该报表所有非空的 header_text，去重并保持顺序
        List<ReportCellBinding> bindings = reportCellBindingMapper.selectList(
                new LambdaQueryWrapper<ReportCellBinding>()
                        .eq(ReportCellBinding::getReportFile, reportFile.trim())
                        .isNotNull(ReportCellBinding::getHeaderText)
                        .ne(ReportCellBinding::getHeaderText, "")
                        .orderByAsc(ReportCellBinding::getHeaderText));
        LinkedHashSet<String> headers = new LinkedHashSet<>();
        for (ReportCellBinding b : bindings) {
            if (b.getHeaderText() != null && !b.getHeaderText().trim().isEmpty()) {
                headers.add(b.getHeaderText().trim());
            }
        }
        return new ArrayList<>(headers);
    }

    @Override
    public ImpactAnalysisVO traceReportLineage(String reportFile, String headerText) {
        // 1. 查 report_cell_binding 拿 source_table + source_field
        if (reportFile == null || reportFile.trim().isEmpty()
                || headerText == null || headerText.trim().isEmpty()) {
            throw new IllegalArgumentException("报表文件和表头列不能为空");
        }
        ReportCellBinding binding = reportCellBindingMapper.selectOne(
                new LambdaQueryWrapper<ReportCellBinding>()
                        .eq(ReportCellBinding::getReportFile, reportFile.trim())
                        .eq(ReportCellBinding::getHeaderText, headerText.trim())
                        .last("LIMIT 1"));
        if (binding == null) {
            throw new IllegalStateException("未找到报表 [" + reportFile + "] 中表头列 [" + headerText + "] 的绑定记录");
        }
        String sourceTable = binding.getSourceTable();
        String sourceField = binding.getSourceField();
        if (sourceTable == null || sourceTable.trim().isEmpty()
                || sourceField == null || sourceField.trim().isEmpty()) {
            throw new IllegalStateException("表头列 [" + headerText + "] 未配置字段来源");
        }

        // 2. 从 source_table（格式 schema.table）解析 schema 和 tableShortName
        String[] parts = sourceTable.toLowerCase().split("\\.");
        if (parts.length < 2) {
            throw new IllegalStateException("源表格式无法识别: " + sourceTable);
        }
        String schema = parts[0];
        String tableShortName = parts[1];

        // 3. 构造 ImpactAnalysisRequest（UPSTREAM 字段级分析）
        ImpactAnalysisRequest request = new ImpactAnalysisRequest();
        request.setSchema(schema);
        request.setTableShortName(tableShortName);
        request.setFieldName(sourceField);
        request.setDirection("UPSTREAM");
        request.setMaxDepth(5);
        request.setAnalyzeReportImpact(false);

        // 4. 调用 ImpactAnalysisService.analyze 返回 ImpactAnalysisVO
        return impactAnalysisService.analyze(request);
    }
}
