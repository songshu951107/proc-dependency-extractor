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
                "RETURN DISTINCT toUpper(t.schema_prefix) AS schema ORDER BY schema")
                .fetchAs(String.class)
                .mappedBy((type, record) -> record.get("schema").asString())
                .all();
        return new ArrayList<>(schemas);
    }

    @Override
    public List<TableOption> getTables(String schema) {
        // 过滤临时表：is_temp=true 的、VT_/TMP_ 前缀的、_TMP 后缀(含数字)的
        String cypher = "MATCH (t:Table) WHERE toUpper(t.schema_prefix) = toUpper($schema) " +
                "AND (t.is_temp IS NULL OR t.is_temp = false) " +
                "AND NOT toUpper(t.short_name) =~ 'VT_.*|TMP_.*|.*_TMP\\d*' " +
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
            // 最大深度不统计临时表：用 impactedByDepth 的 impactSource 关系 BFS 跳过临时表
            vo.getStats().setMaxDepthReached(calcNonTempMaxDepth(tableFullName, impactedByDepth));

            // 勾选"分析报表影响"时，沿 READS 链路追溯上下游表后查报表引用
            if (Boolean.TRUE.equals(request.getAnalyzeReportImpact())) {
                List<ImpactAnalysisVO.ReportImpact> reportImpacts =
                        buildReportImpacts(tableFullName, impactedByDepth);
                vo.setReportImpacts(reportImpacts);
                // 统计影响报表数量（按报表文件去重）
                Set<String> reportFiles = new HashSet<>();
                for (ImpactAnalysisVO.ReportImpact ri : reportImpacts) {
                    for (ImpactAnalysisVO.ReportRef ref : ri.getReports()) {
                        if (ref.getFile() != null) reportFiles.add(ref.getFile());
                    }
                }
                vo.getStats().setAffectedReportCount(reportFiles.size());
            }
        } else {
            // 字段级分析：从已查询的 FIELD 节点/边构建 impactedByDepth，让前端能渲染图谱
            Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> fieldImpactedByDepth =
                    buildImpactedByDepthFromFieldNodes(nodes, edges, tableFullName);
            vo.setImpactedByDepth(fieldImpactedByDepth);
            vo.setPropagationChains(buildPropagationChains(fieldImpactedByDepth));
            vo.getStats().setMaxDepthReached(calcNonTempMaxDepth(tableFullName, fieldImpactedByDepth));

            // 字段级也支持报表影响
            if (Boolean.TRUE.equals(request.getAnalyzeReportImpact())) {
                List<ImpactAnalysisVO.ReportImpact> reportImpacts =
                        buildReportImpacts(tableFullName, fieldImpactedByDepth);
                vo.setReportImpacts(reportImpacts);
                Set<String> reportFiles = new HashSet<>();
                for (ImpactAnalysisVO.ReportImpact ri : reportImpacts) {
                    for (ImpactAnalysisVO.ReportRef ref : ri.getReports()) {
                        if (ref.getFile() != null) reportFiles.add(ref.getFile());
                    }
                }
                vo.getStats().setAffectedReportCount(reportFiles.size());
            }
        }
        return vo;
    }

    /**
     * 字段级分析：从已查询的 FIELD/TRANSFORM 节点和 INPUT_TO/OUTPUT_TO 边构建表级 impactedByDepth。
     *
     * 字段节点 id 格式：schema.table.field
     * 实现步骤：
     * 1. 从字段节点提取所属表，按表聚合字段列表和最小深度
     * 2. 从边推导表级 impactSource（target 表 -> source 表）
     * 3. 查询每张表的元数据（layer/shortName/isTemp/scriptFile/columns）
     */
    private Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> buildImpactedByDepthFromFieldNodes(
            List<ImpactAnalysisVO.NodeVO> nodes,
            List<ImpactAnalysisVO.EdgeVO> edges,
            String sourceTable) {

        // 1. 表 -> 最小深度，表 -> 匹配字段列表
        Map<String, Integer> tableDepthMap = new LinkedHashMap<>();
        Map<String, List<String>> tableFieldsMap = new LinkedHashMap<>();
        for (ImpactAnalysisVO.NodeVO n : nodes) {
            if (!"FIELD".equals(n.getNodeLevel()) || n.getDepth() == null || n.getDepth() <= 0) continue;
            String[] parts = n.getId().split("\\.");
            if (parts.length < 3) continue;
            String tableFullName = (parts[0] + "." + parts[1]).toLowerCase();
            if (tableFullName.equals(sourceTable)) continue;
            tableDepthMap.merge(tableFullName, n.getDepth(), Math::min);
            tableFieldsMap.computeIfAbsent(tableFullName, k -> new ArrayList<>()).add(n.getLabel());
        }

        // 2. 表级 impactSource: 通过 Transform 节点配对 INPUT_TO + OUTPUT_TO 推导
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

        // 3. 组装 ImpactedTable，批量查询元数据
        Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : tableDepthMap.entrySet()) {
            String tableFullName = entry.getKey();
            int depth = entry.getValue();
            ImpactAnalysisVO.ImpactedTable t = new ImpactAnalysisVO.ImpactedTable();
            t.setName(tableFullName);
            t.setDepth(depth);
            t.setImpactSource(tableImpactSourceMap.get(tableFullName));
            t.setMatchedColumns(tableFieldsMap.get(tableFullName));
            fillTableMetadata(t);
            result.computeIfAbsent(depth, k -> new ArrayList<>()).add(t);
        }
        return result;
    }

    /**
     * 查询表元数据填充到 ImpactedTable（shortName/layer/temp/scriptFile/columns）
     */
    private void fillTableMetadata(ImpactAnalysisVO.ImpactedTable t) {
        try {
            Collection<Object[]> meta = neo4jClient.query(
                    "MATCH (tbl:Table {name: $name}) " +
                    "OPTIONAL MATCH (tbl)<-[:BELONGS_TO]-(c:Column) " +
                    "RETURN tbl.short_name AS shortName, tbl.layer AS layer, " +
                    "tbl.is_temp AS isTemp, tbl.script_file AS scriptFile, " +
                    "collect(c.name) AS columns")
                    .bind(t.getName()).to("name")
                    .fetchAs(Object[].class)
                    .mappedBy((type, record) -> new Object[]{
                            record.get("shortName").isNull() ? null : record.get("shortName").asString(),
                            record.get("layer").isNull() ? null : record.get("layer").asString(),
                            record.get("isTemp").isNull() ? false : record.get("isTemp").asBoolean(),
                            record.get("scriptFile").isNull() ? null : record.get("scriptFile").asString(),
                            record.get("columns").isNull() ? Collections.emptyList() :
                                    record.get("columns").asList(v -> v.asString())
                    })
                    .all();
            if (!meta.isEmpty()) {
                Object[] row = meta.iterator().next();
                t.setShortName((String) row[0]);
                t.setLayer((String) row[1]);
                t.setTemp((Boolean) row[2]);
                t.setScriptFile((String) row[3]);
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) row[4];
                t.setColumns(cols);
            }
        } catch (Exception e) {
            log.warn("查询表元数据失败 {}: {}", t.getName(), e.getMessage());
        }
    }

    /**
     * 计算非临时表的最大传播层级（跳过临时表，源表为 0）。
     * 与前端 buildWithoutTemp 逻辑一致：临时表被穿透，不增加层级。
     */
    private int calcNonTempMaxDepth(String sourceTable,
                                     Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth) {
        // 构建邻接表（含临时表）和临时表集合
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> tempTables = new HashSet<>();
        for (List<ImpactAnalysisVO.ImpactedTable> tables : impactedByDepth.values()) {
            for (ImpactAnalysisVO.ImpactedTable t : tables) {
                if (t.isTemp()) tempTables.add(t.getName());
                if (t.getImpactSource() != null) {
                    adjacency.computeIfAbsent(t.getImpactSource(), k -> new ArrayList<>())
                            .add(t.getName());
                }
            }
        }
        // BFS 跳过临时表
        Map<String, Integer> depthMap = new HashMap<>();
        depthMap.put(sourceTable, 0);
        Queue<String> queue = new LinkedList<>();
        queue.add(sourceTable);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depthMap.get(current);
            for (String neighbor : adjacency.getOrDefault(current, Collections.emptyList())) {
                if (!depthMap.containsKey(neighbor)) {
                    if (tempTables.contains(neighbor)) {
                        // 临时表：穿透，层级不变
                        depthMap.put(neighbor, currentDepth);
                    } else {
                        depthMap.put(neighbor, currentDepth + 1);
                    }
                    queue.add(neighbor);
                }
            }
        }
        // 取非临时表节点的最大层级
        int max = 0;
        for (Map.Entry<String, Integer> e : depthMap.entrySet()) {
            if (!tempTables.contains(e.getKey()) && e.getValue() > max) {
                max = e.getValue();
            }
        }
        return max;
    }

    /**
     * 构建报表影响：源表（depth=0）+ 各深度受影响表，查 Neo4j 中被哪些报表引用。
     * 仅保留有报表引用的表，按深度排序。
     */
    private List<ImpactAnalysisVO.ReportImpact> buildReportImpacts(
            String sourceTable,
            Map<Integer, List<ImpactAnalysisVO.ImpactedTable>> impactedByDepth) {
        // 汇总 表 -> 深度（源表深度 0；受影响表取其 depth）
        LinkedHashMap<String, Integer> tableToDepth = new LinkedHashMap<>();
        tableToDepth.put(sourceTable, 0);
        for (Map.Entry<Integer, List<ImpactAnalysisVO.ImpactedTable>> entry : impactedByDepth.entrySet()) {
            for (ImpactAnalysisVO.ImpactedTable t : entry.getValue()) {
                // 跳过临时表（与图表展示一致）
                if (t.isTemp()) continue;
                tableToDepth.putIfAbsent(t.getName(), t.getDepth());
            }
        }

        List<ImpactAnalysisVO.ReportImpact> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : tableToDepth.entrySet()) {
            String table = entry.getKey();
            Integer d = entry.getValue();
            List<ImpactAnalysisVO.ReportRef> refs = queryReportsForTable(table);
            if (refs.isEmpty()) continue;
            ImpactAnalysisVO.ReportImpact ri = new ImpactAnalysisVO.ReportImpact();
            ri.setTable(table);
            ri.setDepth(d);
            ri.setReports(refs);
            result.add(ri);
        }
        return result;
    }

    /** 查询 Neo4j 中引用了指定表的报表（Table<-[:QUERIES]-Dataset<-[:CONTAINS]-Report） */
    private List<ImpactAnalysisVO.ReportRef> queryReportsForTable(String tableName) {
        String cypher = "MATCH (t:Table {name: $name})<-[:QUERIES]-(d:Dataset)<-[:CONTAINS]-(r:Report) " +
                "RETURN r.file AS file, r.name AS name, d.name AS datasetName, d.connection AS connection";
        try {
            Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                    .bind(tableName).to("name")
                    .fetch().all();
            List<ImpactAnalysisVO.ReportRef> refs = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                ImpactAnalysisVO.ReportRef ref = new ImpactAnalysisVO.ReportRef();
                ref.setFile((String) row.get("file"));
                ref.setName((String) row.get("name"));
                ref.setDatasetName((String) row.get("datasetName"));
                ref.setConnection((String) row.get("connection"));
                refs.add(ref);
            }
            return refs;
        } catch (Exception e) {
            log.warn("查询表 {} 的报表引用失败: {}", tableName, e.getMessage());
            return Collections.emptyList();
        }
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
        // 深度计算与 buildImpactedByDepthFromCypher 保持一致：
        // 优先选择"最后一跳经过临时表"的路径，反映真实数据流。
        String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                "WHERE src <> downstream " +
                "WITH downstream, path, length(path) AS pathLen, " +
                "  nodes(path)[length(path)-1] AS predecessor " +
                "WITH downstream, " +
                "  CASE WHEN coalesce(predecessor.is_temp, false) THEN pathLen ELSE null END AS tempPredPathLen, " +
                "  pathLen, downstream.short_name AS label, downstream.layer AS layer, " +
                "  downstream.is_temp AS isTemp " +
                "RETURN downstream.name AS id, label, layer, isTemp, " +
                "  COALESCE(min(tempPredPathLen), min(pathLen)) AS depth " +
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
                        node.setIsTemp(record.get("isTemp").isNull() ? false : record.get("isTemp").asBoolean());
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
        if ("UPSTREAM".equals(direction)) return "<-" + rel + "-";
        if ("BOTH".equals(direction)) return "-" + rel + "-";
        return "-" + rel + "->";
    }

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

    /**
     * 判断 id 是否为 Transform 节点 id（32 位 md5 hex）
     */
    private boolean isTransformId(String id) {
        if (id == null || id.isEmpty()) return false;
        return id.matches("^[a-f0-9]{32}$") && !id.contains(".");
    }

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

    private String extractShortName(String fullName) {
        int idx = fullName.indexOf('.');
        return idx > 0 ? fullName.substring(idx + 1) : fullName;
    }

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
                if (transformId != null && nodeIds.add(transformId)) {
                    ImpactAnalysisVO.NodeVO tNode = new ImpactAnalysisVO.NodeVO();
                    tNode.setId(transformId);
                    tNode.setLabel(transformId);
                    tNode.setNodeLevel("TRANSFORM");
                    tNode.setExpression((String) row.get("expression"));
                    tNode.setLogicType((String) row.get("logicType"));
                    tNode.setSqlHint((String) row.get("sqlHint"));
                    tNode.setProcName((String) row.get("procName"));
                    tNode.setScriptFile((String) row.get("scriptFile"));
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
        // 深度计算：优先选择"最后一跳经过临时表"的路径（反映真实数据流：
        // 非临时表通常经临时表 JOIN 后才 INSERT 到目标表）。
        // 若存在前驱为临时表的路径，取该类路径中最短的；否则取最短路径。
        // scriptFile 从路径最后一条 READS 边的 proc_name 获取（生成该表的存储过程），
        // 而非节点属性 downstream.script_file（会被多次写入覆盖）。
        String cypher = "MATCH path = (src:Table {name: $name})" + arrow + "(downstream:Table) " +
                "WHERE src <> downstream " +
                "WITH downstream, path, length(path) AS pathLen, " +
                "  nodes(path)[length(path)-1] AS predecessor, " +
                "  relationships(path)[length(path)-1] AS lastRel " +
                "WITH downstream, " +
                "  CASE WHEN coalesce(predecessor.is_temp, false) THEN pathLen ELSE null END AS tempPredPathLen, " +
                "  pathLen, lastRel.proc_name AS procName " +
                "RETURN downstream.name AS name, downstream.short_name AS shortName, " +
                "downstream.layer AS layer, downstream.is_temp AS isTemp, " +
                "head(collect(DISTINCT procName)) AS scriptFile, " +
                "COALESCE(min(tempPredPathLen), min(pathLen)) AS depth " +
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
            // 从源表出发，找到路径上该表的直接前驱。
            // 优先选择前驱为临时表的路径（与深度计算逻辑一致），
            // 再按路径长度升序取最短的一条。
            String arrow = buildArrow(direction, "READS", 10);
            String cypher = "MATCH path = (src:Table {name: $sourceTable})" + arrow + "(tgt:Table {name: $targetName}) " +
                    "WITH path, nodes(path) AS ns, length(path) AS pathLen " +
                    "WITH ns, pathLen, ns[size(ns)-2] AS predecessor " +
                    "ORDER BY " +
                    "  CASE WHEN coalesce(predecessor.is_temp, false) THEN 0 ELSE 1 END, " +
                    "  pathLen " +
                    "LIMIT 1 " +
                    "RETURN predecessor.name AS source";
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

        boolean isFieldLevel = request.getNodeId().chars().filter(c -> c == '.').count() >= 2
                || isTransformId(request.getNodeId());

        if (isFieldLevel) {
            int cypherMaxHops = depth * 2;
            String arrow = buildFieldArrow(direction, cypherMaxHops);
            String cypher = "MATCH path = (src:Column {col_key: $key})" + arrow + "(tgt:Column) " +
                    "WHERE src <> tgt " +
                    "RETURN DISTINCT tgt.col_key AS id, tgt.name AS label, " +
                    "       (min(length(path)) + 1) / 2 AS depth ORDER BY depth, tgt.col_key";
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
        // 影响表数：仅统计下游受影响的非临时表（排除源表 depth=0 和临时表）
        stats.setAffectedTableCount((int) nodes.stream()
                .filter(n -> "TABLE".equals(n.getNodeLevel()))
                .filter(n -> n.getDepth() != null && n.getDepth() > 0)
                .filter(n -> !Boolean.TRUE.equals(n.getIsTemp()))
                .count());
        stats.setAffectedFieldCount((int) nodes.stream()
                .filter(n -> "FIELD".equals(n.getNodeLevel())).count());
        Set<String> procs = edges.stream()
                .map(ImpactAnalysisVO.EdgeVO::getProcName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        stats.setAffectedProcCount(procs.size());
        // 最大深度：取实际非临时表节点的最大 depth（排除临时表与 TRANSFORM，避免拉高深度）
        int actualMax = nodes.stream()
                .filter(n -> !"TRANSFORM".equals(n.getNodeLevel()))
                .filter(n -> !Boolean.TRUE.equals(n.getIsTemp()))
                .map(ImpactAnalysisVO.NodeVO::getDepth)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        stats.setMaxDepthReached(actualMax);
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
