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
                "MATCH (t:Table) WHERE t.schema_prefix IS NOT NULL " +
                "RETURN DISTINCT t.schema_prefix AS schema ORDER BY schema")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("schema").asString())
                .all();
        return new ArrayList<>(schemas);
    }

    @Override
    public List<TableOption> getTables(String schema) {
        String cypher = "MATCH (t:Table) WHERE toUpper(t.schema_prefix) = toUpper($schema) " +
                "RETURN t.name AS fullName, t.short_name AS shortName, t.layer AS type " +
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
        String tableName = tableFullName.toLowerCase();
        Collection<String> fields = neo4jClient.query(
                "MATCH (t:Table {name: $tableName})<-[:BELONGS_TO]-(c:Column) " +
                "RETURN c.name AS field ORDER BY c.name")
                .bind(tableName).to("tableName")
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
        vo.setSourceTable(tableFullName);

        // 查询源表 layer（用于前端着色）
        String sourceLayer = queryTableLayer(tableFullName);
        vo.setSourceLayer(sourceLayer);

        List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
        List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();

        if (isFieldLevel) {
            String fieldFullName = tableFullName + "." + request.getFieldName().toLowerCase();
            analyzeFieldLevel(fieldFullName, direction, depth, nodes, edges);
        } else {
            analyzeTableLevel(tableFullName, direction, depth, nodes, edges);
        }

        vo.setNodes(nodes);
        vo.setEdges(edges);
        vo.setStats(buildStats(nodes, edges, depth));
        vo.setTruncated(false);

        if (!isFieldLevel) {
            Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth =
                    buildImpactedByDepthFromCypher(tableFullName, direction, depth);
            vo.setImpactedByDepth(impactedByDepth);
            vo.setPropagationChains(buildPropagationChains(impactedByDepth));
        } else {
            vo.setImpactedByDepth(new LinkedHashMap<>());
            vo.setPropagationChains(new ArrayList<>());
        }
        return vo;
    }

    private String queryTableLayer(String tableName) {
        try {
            Collection<String> layers = neo4jClient.query(
                    "MATCH (t:Table {name: $name}) RETURN t.layer AS layer LIMIT 1")
                    .bind(tableName).to("name")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("layer").isNull() ? null : record.get("layer").asString())
                    .all();
            return layers.isEmpty() ? null : layers.iterator().next();
        } catch (Exception e) {
            log.warn("查询表 layer 失败: {}", e.getMessage());
            return null;
        }
    }

    private void analyzeTableLevel(String tableFullName, String direction, int depth,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges) {
        String arrow = buildArrow(direction, "READS", depth);
        String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                "WHERE src <> downstream " +
                "RETURN DISTINCT downstream.name AS id, downstream.short_name AS label, " +
                "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
                "min(length(path)) AS depth " +
                "ORDER BY depth, downstream.name";

        try {
            Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                    .bind(tableFullName).to("name")
                    .fetchAs(ImpactAnalysisVO.NodeVO.class)
                    .mappedBy((type, record) -> {
                        ImpactAnalysisVO.NodeVO node = new ImpactAnalysisVO.NodeVO();
                        node.setId(record.get("id").asString());
                        node.setLabel(record.get("label").isNull() ? record.get("id").asString() : record.get("label").asString());
                        node.setNodeLevel("TABLE");
                        node.setDepth((int) record.get("depth").asLong());
                        return node;
                    })
                    .all();

            // 添加起点节点
            ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
            startNode.setId(tableFullName);
            startNode.setLabel(extractShortName(tableFullName));
            startNode.setNodeLevel("TABLE");
            startNode.setDepth(0);
            nodes.add(startNode);
            nodes.addAll(result);

            // 查询边
            collectEdges(tableFullName, direction, depth, "READS", "Table", edges, nodes);
        } catch (Exception e) {
            log.error("表级影响查询失败: {}", e.getMessage());
            // 降级：仅返回起点节点
            ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
            startNode.setId(tableFullName);
            startNode.setLabel(extractShortName(tableFullName));
            startNode.setNodeLevel("TABLE");
            startNode.setDepth(0);
            nodes.add(startNode);
        }
    }

    private String buildArrow(String direction, String relType, int depth) {
        String rel = "[:" + relType + "*1.." + depth + "]";
        if ("UPSTREAM".equals(direction)) return "<" + rel + "-";
        if ("BOTH".equals(direction)) return rel + "-";
        return "-" + rel + "->";
    }

    private String extractShortName(String fullName) {
        int idx = fullName.indexOf('.');
        return idx > 0 ? fullName.substring(idx + 1) : fullName;
    }

    private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges) {
        String arrow = buildArrow(direction, "MAPS_TO", depth);
        String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                "WITH path, relationships(path) AS rels, nodes(path) AS cols " +
                "UNWIND range(0, size(rels)-1) AS i " +
                "WITH cols[i] AS srcCol, rels[i] AS r, cols[i+1] AS tgtCol, i+1 AS depth " +
                "RETURN srcCol.col_key AS sourceId, srcCol.name AS sourceLabel, " +
                "tgtCol.col_key AS targetId, tgtCol.name AS targetLabel, " +
                "depth, r.transformation_type AS transType, r.proc_name AS procName, " +
                "r.source_expr AS sourceExpr, r.script_file AS scriptFile " +
                "ORDER BY depth, targetId";

        try {
            Collection<Object[]> result = neo4jClient.query(cypher)
                    .bind(fieldFullName).to("key")
                    .fetchAs(Object[].class)
                    .mappedBy((type, record) -> new Object[]{
                            record.get("sourceId").asString(),
                            record.get("sourceLabel").asString(),
                            record.get("targetId").asString(),
                            record.get("targetLabel").asString(),
                            (int) record.get("depth").asLong(),
                            record.get("transType").isNull() ? null : record.get("transType").asString(),
                            record.get("procName").isNull() ? null : record.get("procName").asString(),
                            record.get("sourceExpr").isNull() ? null : record.get("sourceExpr").asString(),
                            record.get("scriptFile").isNull() ? null : record.get("scriptFile").asString()
                    })
                    .all();

            Set<String> nodeIds = new LinkedHashSet<>();
            nodeIds.add(fieldFullName);

            // 添加起点节点
            ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
            startNode.setId(fieldFullName);
            startNode.setLabel(fieldFullName.split("\\.")[2]);
            startNode.setNodeLevel("FIELD");
            startNode.setDepth(0);
            nodes.add(startNode);

            for (Object[] row : result) {
                String sourceId = (String) row[0];
                String sourceLabel = (String) row[1];
                String targetId = (String) row[2];
                String targetLabel = (String) row[3];
                int d = (int) row[4];
                String transType = (String) row[5];
                String procName = (String) row[6];
                String sourceExpr = (String) row[7];

                if (!nodeIds.contains(sourceId)) {
                    nodeIds.add(sourceId);
                    ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                    n.setId(sourceId);
                    n.setLabel(sourceLabel);
                    n.setNodeLevel("FIELD");
                    n.setDepth(d - 1);
                    nodes.add(n);
                }
                if (!nodeIds.contains(targetId)) {
                    nodeIds.add(targetId);
                    ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                    n.setId(targetId);
                    n.setLabel(targetLabel);
                    n.setNodeLevel("FIELD");
                    n.setDepth(d);
                    nodes.add(n);
                }

                ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                edge.setSource(sourceId);
                edge.setTarget(targetId);
                edge.setProcName(procName);
                edge.setSourceExpr(sourceExpr);
                edges.add(edge);
            }
        } catch (Exception e) {
            log.error("字段级影响查询失败: {}", e.getMessage());
            ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
            startNode.setId(fieldFullName);
            startNode.setLabel(fieldFullName.split("\\.")[2]);
            startNode.setNodeLevel("FIELD");
            startNode.setDepth(0);
            nodes.add(startNode);
        }
    }

    private void collectEdges(String startId, String direction, int depth, String relType,
                               String nodeLabel, List<ImpactAnalysisVO.EdgeVO> edges,
                               List<ImpactAnalysisVO.NodeVO> nodes) {
        List<String> dirs = new ArrayList<>();
        if ("BOTH".equals(direction)) {
            dirs.add("DOWNSTREAM");
            dirs.add("UPSTREAM");
        } else {
            dirs.add(direction);
        }

        Set<String> nodeIds = new HashSet<>();
        for (ImpactAnalysisVO.NodeVO n : nodes) nodeIds.add(n.getId());

        for (String dir : dirs) {
            String edgeCypher;
            if ("DOWNSTREAM".equals(dir)) {
                edgeCypher = String.format(
                        "MATCH (src:%s {name: $startId})-[:%s*1..%d]->(downstream:%s) " +
                        "WITH DISTINCT downstream " +
                        "MATCH (src2)-[r:%s]->(downstream) " +
                        "RETURN src2.name AS source, downstream.name AS target, " +
                        "r.operation AS operation, r.proc_name AS procName, " +
                        "r.join_conditions AS joins, r.where_conditions AS wheres",
                        nodeLabel, relType, depth, nodeLabel, relType);
            } else {
                edgeCypher = String.format(
                        "MATCH (src:%s {name: $startId})<-[:%s*1..%d]-(upstream:%s) " +
                        "WITH DISTINCT upstream " +
                        "MATCH (upstream)-[r:%s]->(dst) " +
                        "RETURN upstream.name AS source, dst.name AS target, " +
                        "r.operation AS operation, r.proc_name AS procName, " +
                        "r.join_conditions AS joins, r.where_conditions AS wheres",
                        nodeLabel, relType, depth, nodeLabel, relType);
            }

            try {
                neo4jClient.query(edgeCypher)
                        .bind(startId).to("startId")
                        .fetchAs(org.neo4j.driver.Record.class)
                        .mappedBy((type, record) -> record)
                        .all()
                        .forEach(record -> {
                            String sourceId = record.get("source").asString();
                            String targetId = record.get("target").asString();

                            // 确保边引用的节点都存在
                            if (!nodeIds.contains(sourceId)) {
                                nodeIds.add(sourceId);
                                ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                                n.setId(sourceId);
                                n.setLabel(extractShortName(sourceId));
                                n.setNodeLevel("TABLE");
                                nodes.add(n);
                            }
                            if (!nodeIds.contains(targetId)) {
                                nodeIds.add(targetId);
                                ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                                n.setId(targetId);
                                n.setLabel(extractShortName(targetId));
                                n.setNodeLevel("TABLE");
                                nodes.add(n);
                            }

                            ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                            edge.setSource(sourceId);
                            edge.setTarget(targetId);
                            edge.setOperation(record.get("operation").isNull() ? null : record.get("operation").asString());
                            edge.setProcName(record.get("procName").isNull() ? null : record.get("procName").asString());
                            edges.add(edge);
                        });
            } catch (Exception e) {
                log.error("查询边失败: {}", e.getMessage());
            }
        }
    }

    private Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> buildImpactedByDepthFromCypher(
            String sourceTable, String direction, int maxDepth) {

        String arrow = buildArrow(direction, "READS", maxDepth);
        String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                "WHERE src <> downstream " +
                "RETURN downstream.name AS name, downstream.short_name AS shortName, " +
                "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
                "downstream.script_file AS scriptFile, " +
                "min(length(path)) AS depth " +
                "ORDER BY depth, downstream.name";

        Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> result = new LinkedHashMap<>();
        try {
            neo4jClient.query(cypher)
                    .bind(sourceTable).to("name")
                    .fetchAs(ImpactAnalysisVO.ImpactedTable.class)
                    .mappedBy((type, record) -> {
                        ImpactAnalysisVO.ImpactedTable t = new ImpactAnalysisVO.ImpactedTable();
                        t.setName(record.get("name").asString());
                        t.setShortName(record.get("shortName").isNull() ? record.get("name").asString() : record.get("shortName").asString());
                        t.setLayer(record.get("layer").isNull() ? null : record.get("layer").asString());
                        t.setTemp(record.get("isTemp").isNull() ? false : record.get("isTemp").asBoolean());
                        t.setDepth((int) record.get("depth").asLong());
                        t.setScriptFile(record.get("scriptFile").isNull() ? null : record.get("scriptFile").asString());
                        return t;
                    })
                    .all()
                    .forEach(t -> {
                        // 查询 impactSource（单跳）
                        t.setImpactSource(findImpactSource(sourceTable, t.getName(), direction));
                        // 查询表的字段
                        t.setColumns(getFields(t.getName()));
                        result.computeIfAbsent(t.getDepth(), k -> new ArrayList<>()).add(t);
                    });
        } catch (Exception e) {
            log.error("构建 impactedByDepth 失败: {}", e.getMessage());
        }
        return result;
    }

    private String findImpactSource(String sourceTable, String tableName, String direction) {
        try {
            // 从源表出发，找到路径上该表的直接前驱
            String cypher = "MATCH path = (src:Table {name: $sourceTable})-[:READS*1..10]->(tgt:Table {name: $targetName}) " +
                    "WITH path, nodes(path) AS ns " +
                    "WITH ns[size(ns)-2] AS predecessor " +
                    "RETURN predecessor.name AS source LIMIT 1";
            Collection<String> sources = neo4jClient.query(cypher)
                    .bind(sourceTable).to("sourceTable")
                    .bind(tableName).to("targetName")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("source").asString())
                    .all();
            return sources.isEmpty() ? null : sources.iterator().next();
        } catch (Exception e) {
            log.warn("查询 impactSource 失败 table={}: {}", tableName, e.getMessage());
            return null;
        }
    }

    private List<String> buildPropagationChains(
            Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth) {
        List<String> chains = new ArrayList<>();
        for (Map.Entry<Integer, List<ImpactAnalysisVO.ImpactedTable>> entry : impactedByDepth.entrySet()) {
            for (ImpactAnalysisVO.ImpactedTable t : entry.getValue()) {
                if (t.getImpactSource() != null) {
                    chains.add(t.getImpactSource() + " → " + t.getName());
                }
            }
        }
        return chains;
    }

    @Override
    public ImpactAnalysisVO expand(ExpandRequest request) {
        int depth = request.getDepth() == null ? 1 : request.getDepth();
        String direction = request.getDirection() == null ? "DOWNSTREAM" : request.getDirection().toUpperCase();

        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        List<ImpactAnalysisVO.NodeVO> nodes = new ArrayList<>();
        List<ImpactAnalysisVO.EdgeVO> edges = new ArrayList<>();

        boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2;

        if (isFieldLevel) {
            String arrow = buildArrow(direction, "MAPS_TO", depth);
            String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                    "RETURN DISTINCT tgt.col_key AS id, tgt.name AS label, " +
                    "min(length(path)) AS depth ORDER BY depth, tgt.col_key";
            try {
                Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                        .bind(request.getNodeId()).to("key")
                        .fetchAs(ImpactAnalysisVO.NodeVO.class)
                        .mappedBy((type, record) -> {
                            ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                            n.setId(record.get("id").asString());
                            n.setLabel(record.get("label").asString());
                            n.setNodeLevel("FIELD");
                            n.setDepth((int) record.get("depth").asLong());
                            return n;
                        })
                        .all();
                nodes.addAll(result);
            } catch (Exception e) {
                log.error("字段展开失败: {}", e.getMessage());
            }
        } else {
            String arrow = buildArrow(direction, "READS", depth);
            String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                    "RETURN DISTINCT downstream.name AS id, downstream.short_name AS label, " +
                    "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
                    "min(length(path)) AS depth ORDER BY depth, downstream.name";
            try {
                Collection<ImpactAnalysisVO.NodeVO> result = neo4jClient.query(cypher)
                        .bind(request.getNodeId()).to("name")
                        .fetchAs(ImpactAnalysisVO.NodeVO.class)
                        .mappedBy((type, record) -> {
                            ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                            n.setId(record.get("id").asString());
                            n.setLabel(record.get("label").isNull() ? record.get("id").asString() : record.get("label").asString());
                            n.setNodeLevel("TABLE");
                            n.setDepth((int) record.get("depth").asLong());
                            return n;
                        })
                        .all();
                nodes.addAll(result);

                // 同时查询边
                collectEdges(request.getNodeId(), direction, depth, "READS", "Table", edges, nodes);
            } catch (Exception e) {
                log.error("表展开失败: {}", e.getMessage());
            }
        }

        vo.setNodes(nodes);
        vo.setEdges(edges);
        return vo;
    }

    @Override
    public List<ImpactAnalysisVO.PathVO> getPaths(String source, String target, Integer maxDepth) {
        // 预留方法，当前返回空列表
        return new ArrayList<>();
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
            return (schema + "." + shortName).toLowerCase();
        }
        // 无 schema 时查 Neo4j
        try {
            Collection<String> fullNames = neo4jClient.query(
                    "MATCH (t:Table {short_name: $shortName}) RETURN t.name AS name LIMIT 1")
                    .bind(shortName.toUpperCase()).to("shortName")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("name").asString())
                    .all();
            if (!fullNames.isEmpty()) return fullNames.iterator().next();
        } catch (Exception e) {
            log.warn("查询表 name 失败: {}", e.getMessage());
        }
        return shortName.toLowerCase();
    }
}
