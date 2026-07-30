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

        // 判断 nodeId 是 Table 还是 Field（Field 的 full_name 含 2 个点：schema.table.FIELD）
        boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2;

        String relType = isFieldLevel ? "MAPS_TO" : "DERIVES";
        String nodeLabel = isFieldLevel ? "Field" : "Table";
        String arrow = "UPSTREAM".equals(direction)
                ? "<-[:" + relType + "*1.." + depth + "]-"
                : "-[:" + relType + "*1.." + depth + "]->";
        String cypher = "MATCH path = (src:" + nodeLabel + " {full_name: $nodeId})" + arrow + "(downstream:" + nodeLabel + ") " +
                "RETURN path";
        executeAndCollect(cypher, request.getNodeId(), "nodeId", direction, depth, nodes, edges, nodeIds, isFieldLevel);

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
        try {
            neo4jClient.query(cypher)
                    .bind(source).to("source")
                    .bind(target).to("target")
                    .fetchAs(org.neo4j.driver.Record.class)
                    .mappedBy((type, record) -> record)
                    .all()
                    .forEach(record -> {
                        // 简化处理：实际 path 解析需要 org.neo4j.driver.types.Path
                        // 此方法为预留，当前返回空列表
                    });
        } catch (Exception e) {
            log.error("查询路径失败: {}", e.getMessage());
        }
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
        // path 查询 cypher（forEach 为空实现，备用方案 collectNodesAndEdges 为主逻辑）
        String arrow = "UPSTREAM".equals(direction)
                ? "<-[:DERIVES*1.." + depth + "]-"
                : "-[:DERIVES*1.." + depth + "]->";
        String cypher = "MATCH path = (src:Table {full_name: $tableFullName})" + arrow + "(downstream:Table) " +
                "RETURN path";
        executeAndCollect(cypher, tableFullName, "tableFullName", direction, depth, nodes, edges, nodeIds, false);
    }

    private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                    ImpactAnalysisRequest request,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges,
                                    Set<String> nodeIds) {
        String arrow = "UPSTREAM".equals(direction)
                ? "<-[:MAPS_TO*1.." + depth + "]-"
                : "-[:MAPS_TO*1.." + depth + "]->";
        String cypher = "MATCH path = (src:Field {full_name: $fieldFullName})" + arrow + "(downstream:Field) " +
                "RETURN path";
        executeAndCollect(cypher, fieldFullName, "fieldFullName", direction, depth, nodes, edges, nodeIds, true);
    }

    private void executeAndCollect(String cypher, String bindValue, String bindKey,
                                    String direction, int depth,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges,
                                    Set<String> nodeIds, boolean isFieldLevel) {
        try {
            neo4jClient.query(cypher)
                    .bind(bindValue).to(bindKey)
                    .fetchAs(org.neo4j.driver.Record.class)
                    .mappedBy((type, record) -> record)
                    .all()
                    .forEach(record -> {
                        // path 查询结果解析较复杂，使用备用方案
                    });
        } catch (Exception e) {
            log.error("Cypher 查询失败: {}", e.getMessage());
        }

        // 备用方案：分两步查（先查节点，再查边）
        if (nodes.isEmpty()) {
            collectNodesAndEdges(bindValue, bindKey, isFieldLevel, direction, depth, nodes, edges, nodeIds);
        }
    }

    /**
     * 备用方案：分步查询节点和边（根据 direction 构造箭头方向，depth 控制深度）
     */
    private void collectNodesAndEdges(String startId, String startKey, boolean isFieldLevel,
                                       String direction, int depth,
                                       List<ImpactAnalysisVO.NodeVO> nodes,
                                       List<ImpactAnalysisVO.EdgeVO> edges,
                                       Set<String> nodeIds) {
        String nodeLabel = isFieldLevel ? "Field" : "Table";
        String relType = isFieldLevel ? "MAPS_TO" : "DERIVES";

        // 根据方向决定要查询的方向列表：BOTH 时先下游再上游，合并去重
        List<String> dirs = new ArrayList<>();
        if ("BOTH".equals(direction)) {
            dirs.add("DOWNSTREAM");
            dirs.add("UPSTREAM");
        } else {
            dirs.add(direction);
        }

        for (String dir : dirs) {
            // DOWNSTREAM: -[:REL*1..depth]-> ; UPSTREAM: <-[:REL*1..depth]-
            String pathArrow = "DOWNSTREAM".equals(dir)
                    ? String.format("-[:%s*1..%d]->", relType, depth)
                    : String.format("<-[:%s*1..%d]-", relType, depth);

            // 查节点
            String cypher = String.format(
                    "MATCH (src:%s {full_name: $startId})%s(downstream:%s) " +
                    "RETURN DISTINCT downstream.full_name AS id, downstream.short_name AS label, " +
                    "downstream.type AS type, downstream.field AS field, downstream.table_full_name AS tableFullName",
                    nodeLabel, pathArrow, nodeLabel);

            try {
                neo4jClient.query(cypher)
                        .bind(startId).to("startId")
                        .fetchAs(org.neo4j.driver.Record.class)
                        .mappedBy((type, record) -> record)
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

            // 查边：DERIVES/MAPS_TO 关系本身始终是 source-[r]->target 方向
            // DOWNSTREAM: 找到指向 downstream 的边；UPSTREAM: 找到从 upstream 出发的边
            String edgeCypher;
            if ("DOWNSTREAM".equals(dir)) {
                edgeCypher = String.format(
                        "MATCH (src:%s {full_name: $startId})%s(downstream:%s) " +
                        "WITH DISTINCT downstream " +
                        "MATCH (src2)-[r:%s]->(downstream) " +
                        "WHERE src2.full_name IS NOT NULL AND downstream.full_name IS NOT NULL " +
                        "RETURN src2.full_name AS source, downstream.full_name AS target, " +
                        "r.operation AS operation, r.sql_block_seq AS seq, r.main_table AS mainTable, " +
                        "r.join_conditions AS joins, r.where_conditions AS wheres, " +
                        "r.source_expr AS sourceExpr, r.source_alias AS sourceAlias, " +
                        "r.proc_name AS procName",
                        nodeLabel, pathArrow, nodeLabel, relType);
            } else {
                edgeCypher = String.format(
                        "MATCH (src:%s {full_name: $startId})%s(upstream:%s) " +
                        "WITH DISTINCT upstream " +
                        "MATCH (upstream)-[r:%s]->(dst) " +
                        "WHERE upstream.full_name IS NOT NULL AND dst.full_name IS NOT NULL " +
                        "RETURN upstream.full_name AS source, dst.full_name AS target, " +
                        "r.operation AS operation, r.sql_block_seq AS seq, r.main_table AS mainTable, " +
                        "r.join_conditions AS joins, r.where_conditions AS wheres, " +
                        "r.source_expr AS sourceExpr, r.source_alias AS sourceAlias, " +
                        "r.proc_name AS procName",
                        nodeLabel, pathArrow, nodeLabel, relType);
            }

            try {
                neo4jClient.query(edgeCypher)
                        .bind(startId).to("startId")
                        .fetchAs(org.neo4j.driver.Record.class)
                        .mappedBy((type, record) -> record)
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
                            edge.setProcName(record.get("procName").isNull() ? null : record.get("procName").asString());
                            if (isFieldLevel) {
                                edge.setSourceExpr(record.get("sourceExpr").isNull() ? null : record.get("sourceExpr").asString());
                                edge.setSourceAlias(record.get("sourceAlias").isNull() ? null : record.get("sourceAlias").asString());
                            }
                            edges.add(edge);
                        });
            } catch (Exception e) {
                log.error("查询边失败: {}", e.getMessage());
            }
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
                .map(ImpactAnalysisVO.EdgeVO::getProcName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        stats.setAffectedProcCount(procs.size());
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
