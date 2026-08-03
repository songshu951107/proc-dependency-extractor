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
