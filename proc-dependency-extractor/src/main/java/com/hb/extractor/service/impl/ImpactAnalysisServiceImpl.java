package com.hb.extractor.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.Neo4jSyncLog;
import com.hb.extractor.entity.TableDdlInfo;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import com.hb.extractor.mapper.TableColumnMetaMapper;
import com.hb.extractor.mapper.TableDdlInfoMapper;
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

    @Autowired
    private TableDdlInfoMapper ddlInfoMapper;

    @Autowired
    private TableColumnMetaMapper columnMetaMapper;

    @Value("${impact.analysis.max-depth:20}")
    private int maxAllowedDepth;

    /** 字段级分析 BFS 安全阀：nodes 总数超过该值提前终止扩展（防可达闭包爆炸） */
    private static final int MAX_FIELD_GRAPH_NODES = 100_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<String> getSchemas() {
        // 主数据源：MySQL table_ddl_info（已导入的全部物理表元数据）
        // 保证下拉框能显示所有已导入的 schema，而不只是 Neo4j 中已建图的少量 schema。
        Set<String> schemaSet = new TreeSet<>();
        try {
            List<String> mysqlSchemas = ddlInfoMapper.selectDistinctSchemas();
            if (mysqlSchemas != null) {
                for (String s : mysqlSchemas) {
                    if (s != null && !s.isEmpty()) schemaSet.add(s.toUpperCase());
                }
            }
        } catch (Exception e) {
            log.warn("从 MySQL 查询 schemas 失败: {}", e.getMessage());
        }
        // 合并 Neo4j 中的 schema（临时表等可能只存在于 Neo4j）
        try {
            Collection<String> neoSchemas = neo4jClient.query(
                    "MATCH (t:Table) WHERE t.schema_prefix IS NOT NULL " +
                    "RETURN DISTINCT toUpper(t.schema_prefix) AS schema ORDER BY schema")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("schema").asString())
                    .all();
            schemaSet.addAll(neoSchemas);
        } catch (Exception e) {
            log.warn("合并 Neo4j schemas 失败: {}", e.getMessage());
        }
        return new ArrayList<>(schemaSet);
    }

    @Override
    public List<TableOption> getTables(String schema) {
        // 主数据源：MySQL table_ddl_info（全部已导入的物理表）
        List<TableOption> result = new ArrayList<>();
        if (schema != null && !schema.isEmpty()) {
            try {
                List<TableDdlInfo> rows = ddlInfoMapper.selectBySchema(schema.toLowerCase());
                if (rows != null) {
                    for (TableDdlInfo r : rows) {
                        // 过滤临时表命名（与原 Neo4j 逻辑保持一致）
                        // 过滤 bak 备份表（如 bak2、xxx_bak、xxx_bak2），columns0818.xlsx 导入时未过滤
                        String shortName = r.getTableName();
                        if (shortName == null) continue;
                        String upper = shortName.toUpperCase();
                        if (upper.matches("VT_.*|TMP_.*|.*_TMP\\d*")) continue;
                        if (upper.matches("BAK\\d*|.*_BAK|.*_BAK\\d+")) continue;
                        TableOption opt = new TableOption();
                        opt.setFullName(r.getFullTableName());
                        opt.setShortName(shortName);
                        opt.setType(null);
                        result.add(opt);
                    }
                }
            } catch (Exception e) {
                log.warn("从 MySQL 查询表列表失败 schema={}: {}", schema, e.getMessage());
            }
        }
        // Neo4j fallback：补齐 MySQL 中没有但 Neo4j 已建图的表（如某些临时表）
        try {
            String cypher = "MATCH (t:Table) WHERE toUpper(t.schema_prefix) = toUpper($schema) " +
                    "AND (t.is_temp IS NULL OR t.is_temp = false) " +
                    "AND NOT toUpper(t.short_name) =~ 'VT_.*|TMP_.*|.*_TMP\\d*|BAK\\d*|.*_BAK|.*_BAK\\d+' " +
                    "RETURN t.name AS fullName, t.short_name AS shortName, t.layer AS type " +
                    "ORDER BY t.short_name";
            Collection<TableOption> neoTables = neo4jClient.query(cypher)
                    .bind(schema == null ? "" : schema).to("schema")
                    .fetchAs(TableOption.class)
                    .mappedBy((type, record) -> {
                        TableOption opt = new TableOption();
                        opt.setFullName(record.get("fullName").asString());
                        opt.setShortName(record.get("shortName").asString());
                        opt.setType(record.get("type").isNull() ? null : record.get("type").asString());
                        return opt;
                    })
                    .all();
            // 合并去重（按 fullName）
            Set<String> existing = new HashSet<>();
            for (TableOption t : result) existing.add(t.getFullName());
            for (TableOption t : neoTables) {
                if (t.getFullName() != null && existing.add(t.getFullName())) {
                    result.add(t);
                }
            }
        } catch (Exception e) {
            log.warn("合并 Neo4j 表列表失败 schema={}: {}", schema, e.getMessage());
        }
        // 按短名排序
        result.sort(Comparator.comparing(TableOption::getShortName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    @Override
    public List<String> getFields(String tableFullName) {
        if (tableFullName == null || tableFullName.isEmpty()) return new ArrayList<>();
        // 主数据源：MySQL table_column_meta（覆盖全部已导入字段）
        try {
            List<String> fields = columnMetaMapper.selectFieldNamesByFullTable(tableFullName.toLowerCase());
            if (fields != null && !fields.isEmpty()) {
                return fields;
            }
        } catch (Exception e) {
            log.warn("从 MySQL 查询字段失败 table={}: {}", tableFullName, e.getMessage());
        }
        // Neo4j fallback：MySQL 未导入该表时，从已建图的 Column 节点读取
        try {
            String tableName = tableFullName.toLowerCase();
            Collection<String> fields = neo4jClient.query(
                    "MATCH (t:Table {name: $tableName})<-[:BELONGS_TO]-(c:Column) " +
                    "RETURN c.name AS field ORDER BY c.name")
                    .bind(tableName).to("tableName")
                    .fetchAs(String.class)
                    .mappedBy((type, record) -> record.get("field").asString())
                    .all();
            return new ArrayList<>(fields);
        } catch (Exception e) {
            log.warn("从 Neo4j 查询字段失败 table={}: {}", tableFullName, e.getMessage());
            return new ArrayList<>();
        }
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
            // 上游/双向：附加条件分区（目标列所在表行集受哪些条件控制）
            if (!"DOWNSTREAM".equals(direction)) {
                vo.setRowFilters(queryRowFilters(fieldFullName));
            }
        } else {
            analyzeTableLevel(tableFullName, direction, depth, nodes, edges);
        }

        // 删除不与任何有 depth 节点连通的 depth=null 辅助节点（ensureFieldNode 补全的
        // 非路径 FieldMapping 条件字段等），在组装 VO 与统计前清理（表级分析节点均有
        // depth 或为 TABLE 级，此调用为 no-op）
        removeDanglingAuxNodes(nodes, edges);

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

        // DIRECT 类型的 DERIVED_FROM 边: 直接推导 表A → 表B
        for (ImpactAnalysisVO.EdgeVO e : edges) {
            if (!"DERIVED_FROM".equals(e.getEdgeType())) continue;
            String srcTable = extractTableFromId(e.getSource());
            String tgtTable = extractTableFromId(e.getTarget());
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
        // 构建双向邻接表（含临时表）和临时表集合
        // 双向：字段级 UPSTREAM 中 impactSource 是上游表（更深），name 是下游表（更浅）；
        //       表级分析中 impactSource 是下游表（更浅），name 是上游表（更深）。
        //       双向邻接表确保 BFS 从 sourceTable 出发能到达所有节点。
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> tempTables = new HashSet<>();
        for (List<ImpactAnalysisVO.ImpactedTable> tables : impactedByDepth.values()) {
            for (ImpactAnalysisVO.ImpactedTable t : tables) {
                if (t.isTemp()) tempTables.add(t.getName());
                if (t.getImpactSource() != null) {
                    adjacency.computeIfAbsent(t.getImpactSource(), k -> new ArrayList<>())
                            .add(t.getName());
                    adjacency.computeIfAbsent(t.getName(), k -> new ArrayList<>())
                            .add(t.getImpactSource());
                }
            }
        }
        // 如果 sourceTable 不在邻接表中（字段级分析排除了 sourceTable），
        // 将 depth=1 的表与 sourceTable 连接，确保 BFS 能从 sourceTable 出发
        if (!adjacency.containsKey(sourceTable)) {
            for (ImpactAnalysisVO.ImpactedTable t : impactedByDepth.getOrDefault(1, Collections.emptyList())) {
                adjacency.computeIfAbsent(sourceTable, k -> new ArrayList<>()).add(t.getName());
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
                        node.setLayer(record.get("layer").isNull() ? extractLayerFromId(node.getId()) : record.get("layer").asString());
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
            startNode.setLayer(extractLayerFromId(tableFullName));
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
            startNode.setLayer(extractLayerFromId(tableFullName));
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

    /** 从节点 id（格式 schema.table.field 或 schema.table）解析数据层级，大写返回 */
    private String extractLayerFromId(String id) {
        if (id == null || id.isEmpty()) return null;
        int idx = id.indexOf('.');
        if (idx <= 0) return null;
        return id.substring(0, idx).toUpperCase();
    }

    /**
     * 字段级影响分析：Java 迭代 BFS 逐层扩展（替代 Cypher 变长路径枚举，避免组合爆炸）。
     *
     * 图模型: (:Column)-[:INPUT_TO {role, source_alias}]->(:Transform)-[:OUTPUT_TO]->(:Column)
     * 字段跳 = 2 关系跳；depth 参数 = 最大字段跳数（等价于旧实现 *1..depth*2 的关系跳上限）。
     *
     * 每层一次扩展查询（frontier 为上一层 Column col_key 列表，走 col_key 唯一索引）：
     * - DOWNSTREAM: frontier Column -INPUT_TO-> Transform，下一层 = Transform 的输出字段
     * - UPSTREAM:   Transform -OUTPUT_TO-> frontier Column，下一层 = Transform 的输入字段（上游字段）
     * - BOTH = 两方向各跑一遍（先 DOWNSTREAM 后 UPSTREAM），visited 跨方向共享去重。
     *
     * 语义与旧 path 枚举实现等价：
     * - 可达 Column 集合与 depth（BFS 层号 = 最短字段跳数，同 min(length(path))/2）
     * - Transform 行在 BFS 期间收集、BFS 结束后统一处理，保证 DIRECT 分支的
     *   nodeIds.contains 判断与旧实现"先查全部可达节点、再查 Transform 边"看到相同的完整可达集
     * - 无输入或无输出的 Transform 不可能出现在任何路径上（路径要求中间 Transform 既有入边又有出边），跳过
     */
    private void analyzeFieldLevel(String fieldFullName, String direction, int depth,
                                    List<ImpactAnalysisVO.NodeVO> nodes,
                                    List<ImpactAnalysisVO.EdgeVO> edges) {
        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.add(fieldFullName);

        // 添加起点 Column 节点
        ImpactAnalysisVO.NodeVO startNode = new ImpactAnalysisVO.NodeVO();
        startNode.setId(fieldFullName);
        String[] parts = fieldFullName.split("\\.");
        String label = parts.length >= 3 ? parts[2] : fieldFullName;
        startNode.setLabel(label);
        startNode.setNodeLevel("FIELD");
        startNode.setLayer(extractLayerFromId(fieldFullName));
        startNode.setDepth(0);
        nodes.add(startNode);

        try {
            // 每层一次扩展查询：返回与 frontier 相邻的 Transform 及其全量输入/输出
            String expandDownstream = "UNWIND $frontier AS ck " +
                    "MATCH (c:Column {col_key: ck})-[:INPUT_TO]->(t:Transform) " +
                    "OPTIONAL MATCH (in:Column)-[rIn:INPUT_TO]->(t) " +
                    "OPTIONAL MATCH (t)-[:OUTPUT_TO]->(out:Column) " +
                    "OPTIONAL MATCH (in)-[:BELONGS_TO]->(tblIn:Table) " +
                    "OPTIONAL MATCH (out)-[:BELONGS_TO]->(tblOut:Table) " +
                    "RETURN t.id AS transformId, t.expression AS expression, " +
                    "  t.logic_type AS logicType, t.sql_hint AS sqlHint, " +
                    "  t.proc_name AS procName, t.script_file AS scriptFile, " +
                    "  collect(DISTINCT {colKey: in.col_key, role: rIn.role, alias: rIn.source_alias, " +
                    "    name: in.name, tableFullName: in.table_full_name, temp: tblIn.is_temp}) AS inputs, " +
                    "  collect(DISTINCT {colKey: out.col_key, name: out.name, " +
                    "    tableFullName: out.table_full_name, temp: tblOut.is_temp}) AS outputs";
            String expandUpstream = "UNWIND $frontier AS ck " +
                    "MATCH (c:Column {col_key: ck})<-[:OUTPUT_TO]-(t:Transform) " +
                    "OPTIONAL MATCH (in:Column)-[rIn:INPUT_TO]->(t) " +
                    "OPTIONAL MATCH (t)-[:OUTPUT_TO]->(out:Column) " +
                    "OPTIONAL MATCH (in)-[:BELONGS_TO]->(tblIn:Table) " +
                    "OPTIONAL MATCH (out)-[:BELONGS_TO]->(tblOut:Table) " +
                    "RETURN t.id AS transformId, t.expression AS expression, " +
                    "  t.logic_type AS logicType, t.sql_hint AS sqlHint, " +
                    "  t.proc_name AS procName, t.script_file AS scriptFile, " +
                    "  collect(DISTINCT {colKey: in.col_key, role: rIn.role, alias: rIn.source_alias, " +
                    "    name: in.name, tableFullName: in.table_full_name, temp: tblIn.is_temp}) AS inputs, " +
                    "  collect(DISTINCT {colKey: out.col_key, name: out.name, " +
                    "    tableFullName: out.table_full_name, temp: tblOut.is_temp}) AS outputs";

            List<String> dirs = new ArrayList<>();
            if ("UPSTREAM".equals(direction)) {
                dirs.add("UPSTREAM");
            } else if ("BOTH".equals(direction)) {
                dirs.add("DOWNSTREAM");
                dirs.add("UPSTREAM");
            } else {
                dirs.add("DOWNSTREAM");
            }

            // Transform 行先收集、BFS 结束后统一处理（见方法 javadoc）
            Set<String> seenTransforms = new HashSet<>();
            List<Map<String, Object>> transformRows = new ArrayList<>();

            // 行影响通道（仅下游方向，设计 §8：上游只走值边）：FILTERS 1 跳 + 表内列展开，展开列并入值通道 BFS
            Map<Integer, Set<String>> rowSeeds = dirs.contains("DOWNSTREAM")
                    ? expandRowImpactChannel(fieldFullName, depth, nodeIds, nodes, edges)
                    : Collections.emptyMap();

            for (String dir : dirs) {
                boolean downstream = "DOWNSTREAM".equals(dir);
                String cypher = downstream ? expandDownstream : expandUpstream;
                Set<String> frontier = new LinkedHashSet<>();
                frontier.add(fieldFullName);
                for (int layer = 1; layer <= depth; layer++) {
                    Set<String> seedCols = downstream ? rowSeeds.get(layer) : null;
                    if (seedCols != null && !seedCols.isEmpty()) frontier.addAll(seedCols);
                    if (frontier.isEmpty()) break;
                    // 安全阀：防真闭包爆炸把 API 打死
                    if (nodes.size() > MAX_FIELD_GRAPH_NODES) {
                        log.warn("字段级分析节点数超过安全上限 {}，提前终止扩展 field={} direction={}",
                                MAX_FIELD_GRAPH_NODES, fieldFullName, dir);
                        break;
                    }
                    Collection<Map<String, Object>> rows = neo4jClient.query(cypher)
                            .bind(new ArrayList<>(frontier)).to("frontier")
                            .fetch().all();
                    Set<String> nextFrontier = new LinkedHashSet<>();
                    for (Map<String, Object> row : rows) {
                        String transformId = (String) row.get("transformId");
                        if (transformId == null) continue;
                        // 字面量转换剪枝：表达式为常量（如 NVL(x,0) 的 0 / INSERT 常量列），
                        // 其输出列值恒定、不受任何上游影响，且会引入 __literal__ 虚拟列噪音节点，
                        // 不入 transformRows、其输出列不入 frontier（行通道的 ROW_IMPACT 不受影响）
                        if ("LITERAL".equals(row.get("logicType"))) continue;
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> rowInputs = (List<Map<String, Object>>) row.get("inputs");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> rowOutputs = (List<Map<String, Object>>) row.get("outputs");
                        // 路径枚举语义：Transform 既有输入又有输出才会出现在任何路径上
                        if (rowInputs == null || rowInputs.isEmpty()
                                || rowOutputs == null || rowOutputs.isEmpty()) {
                            continue;
                        }
                        if (seenTransforms.add(transformId)) {
                            transformRows.add(row);
                        }
                        // 下一层 frontier：DOWNSTREAM 取输出字段，UPSTREAM 取输入字段（上游字段）
                        List<Map<String, Object>> nextCols = downstream ? rowOutputs : rowInputs;
                        for (Map<String, Object> col : nextCols) {
                            String colKey = (String) col.get("colKey");
                            if (colKey == null || !nodeIds.add(colKey)) continue;
                            nodes.add(buildFieldNode(col, colKey, layer));
                            nextFrontier.add(colKey);
                        }
                    }
                    frontier = nextFrontier;
                }
            }

            // 统一处理 Transform 节点 + 边（此时 nodeIds 已含全部可达字段）
            for (Map<String, Object> row : transformRows) {
                String transformId = (String) row.get("transformId");
                String logicType = (String) row.get("logicType");
                String procName = (String) row.get("procName");

                // DIRECT 类型: 无计算转换, 不创建 Transform 节点,
                // 直接合并 INPUT_TO + OUTPUT_TO 为 源Column → 目标Column 的 DERIVED_FROM 边
                if ("DIRECT".equals(logicType)) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> directInputs = (List<Map<String, Object>>) row.get("inputs");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> directOutputs = (List<Map<String, Object>>) row.get("outputs");
                    if (directInputs != null && directOutputs != null) {
                        // 旧实现按 (Transform, output) 逐行处理；此处对每个 output 重复 DIRECT 处理
                        for (Map<String, Object> output : directOutputs) {
                            String outputColKey = (String) output.get("colKey");
                            if (outputColKey == null) continue;
                            ensureFieldNode(outputColKey, nodeIds, nodes);
                            for (Map<String, Object> input : directInputs) {
                                String colKey = (String) input.get("colKey");
                                if (colKey == null) continue;
                                // SELECT 列辅助字段（不在路径上）: 跳过（条件字段已走行血缘 FILTERS 通道）
                                if (!nodeIds.contains(colKey)) continue;

                                ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                                edge.setSource(colKey);
                                edge.setTarget(outputColKey);
                                edge.setEdgeType("DERIVED_FROM");
                                edge.setSourceAlias((String) input.get("alias"));
                                edge.setProcName(procName);
                                edge.setImpactKind("VALUE");
                                edges.add(edge);
                            }
                        }
                    }
                    continue;
                }

                // 非 DIRECT 类型: 创建 Transform 节点 + INPUT_TO/OUTPUT_TO 边
                if (transformId != null && nodeIds.add(transformId)) {
                    ImpactAnalysisVO.NodeVO tNode = new ImpactAnalysisVO.NodeVO();
                    tNode.setId(transformId);
                    String expression = (String) row.get("expression");
                    // label 用截断的 expression，避免显示无意义的 md5 hash
                    String tLabel = expression != null
                            ? expression.replaceAll("\\s+", " ").trim()
                            : transformId.substring(0, 8);
                    tNode.setLabel(tLabel.length() > 30 ? tLabel.substring(0, 30) + "..." : tLabel);
                    tNode.setNodeLevel("TRANSFORM");
                    tNode.setExpression(expression);
                    tNode.setLogicType(logicType);
                    tNode.setSqlHint((String) row.get("sqlHint"));
                    tNode.setProcName(procName);
                    tNode.setScriptFile((String) row.get("scriptFile"));
                    nodes.add(tNode);
                }

                // 添加 INPUT_TO 边（输入字段可能是主路径外的辅助输入，需补全为 FIELD 节点，
                // 否则前端按"两端节点可见"过滤边时会丢弃这些输入边）
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> inputs = (List<Map<String, Object>>) row.get("inputs");
                if (inputs != null) {
                    for (Map<String, Object> input : inputs) {
                        String colKey = (String) input.get("colKey");
                        if (colKey == null) continue;
                        ensureFieldNode(colKey, nodeIds, nodes);
                        ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                        edge.setSource(colKey);
                        edge.setTarget(transformId);
                        edge.setEdgeType("INPUT_TO");
                        edge.setRole((String) input.get("role"));
                        edge.setSourceAlias((String) input.get("alias"));
                        edge.setProcName(procName);
                        edge.setImpactKind("VALUE");
                        edges.add(edge);
                    }
                }

                // 添加 OUTPUT_TO 边（Transform 可有多个输出，逐个建边）
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> outputs = (List<Map<String, Object>>) row.get("outputs");
                if (outputs != null) {
                    for (Map<String, Object> output : outputs) {
                        String outputColKey = (String) output.get("colKey");
                        if (outputColKey == null) continue;
                        ensureFieldNode(outputColKey, nodeIds, nodes);
                        ImpactAnalysisVO.EdgeVO edge = new ImpactAnalysisVO.EdgeVO();
                        edge.setSource(transformId);
                        edge.setTarget(outputColKey);
                        edge.setEdgeType("OUTPUT_TO");
                        edge.setExpression((String) row.get("expression"));
                        edge.setProcName(procName);
                        edge.setImpactKind("VALUE");
                        edges.add(edge);
                    }
                }
            }
        } catch (Exception e) {
            log.error("字段级影响查询失败: {}", e.getMessage());
        }
    }

    /**
     * 行影响通道（下游）：起点列 --FILTERS--> 受影响表(1跳) → 表内所有列打行影响标签(2跳, 查询期展开)
     * 展开列作为 layer=2 种子返回，由值通道 BFS 继续向下游传播（标签保持行影响，前端聚合）。
     * 不二次经过 FILTERS：仅展开起点列的直接 FILTERS 边；起点自身所在表不重复展开。
     */
    private Map<Integer, Set<String>> expandRowImpactChannel(String startColKey, int maxDepth,
                                                             Set<String> nodeIds,
                                                             List<ImpactAnalysisVO.NodeVO> nodes,
                                                             List<ImpactAnalysisVO.EdgeVO> edges) {
        Map<Integer, Set<String>> seeds = new HashMap<>();
        if (maxDepth < 2) return seeds;
        String startTable = extractTableFromId(startColKey);
        Collection<Map<String, Object>> filterRows;
        try {
            filterRows = neo4jClient.query(
                            "MATCH (c:Column {col_key: $ck})-[f:FILTERS]->(t:Table) " +
                            "RETURN t.name AS tableName, t.short_name AS shortName, " +
                            "  f.role AS role, f.clause AS clause, f.proc_name AS procName, " +
                            "  f.sql_block_seq AS seq")
                    .bind(startColKey).to("ck").fetch().all();
        } catch (Exception e) {
            log.error("行影响通道 FILTERS 查询失败: {}", e.getMessage());
            return seeds;
        }
        Set<String> nextLayerCols = new LinkedHashSet<>();
        // 按表分组：同表多条 FILTERS 边（多 proc/多块 INSERT 过滤同一表）只查一次列
        Map<String, List<Map<String, Object>>> filtersByTable = new LinkedHashMap<>();
        for (Map<String, Object> fr : filterRows) {
            String tableName = (String) fr.get("tableName");
            if (tableName == null || tableName.equals(startTable)) continue;
            filtersByTable.computeIfAbsent(tableName, k -> new ArrayList<>()).add(fr);
        }
        for (Map.Entry<String, List<Map<String, Object>>> tableEntry : filtersByTable.entrySet()) {
            String tableName = tableEntry.getKey();
            List<Map<String, Object>> tableFilters = tableEntry.getValue();
            // 表节点（depth=1）
            if (nodeIds.add(tableName)) {
                ImpactAnalysisVO.NodeVO tNode = new ImpactAnalysisVO.NodeVO();
                tNode.setId(tableName);
                Object shortName = tableFilters.get(0).get("shortName");
                tNode.setLabel(shortName != null ? shortName.toString() : tableName);
                tNode.setNodeLevel("TABLE");
                tNode.setLayer(extractLayerFromId(tableName));
                tNode.setTableFullName(tableName);
                tNode.setDepth(1);
                nodes.add(tNode);
            }
            // FILTERS 边（起点列 → 表）：每条各保留（携带各自 proc/block 信息）
            for (Map<String, Object> fr : tableFilters) {
                ImpactAnalysisVO.EdgeVO fEdge = new ImpactAnalysisVO.EdgeVO();
                fEdge.setSource(startColKey);
                fEdge.setTarget(tableName);
                fEdge.setEdgeType("FILTERS");
                fEdge.setRole((String) fr.get("role"));
                fEdge.setClause((String) fr.get("clause"));
                fEdge.setProcName((String) fr.get("procName"));
                fEdge.setSqlBlockSeq(fr.get("seq") != null ? ((Number) fr.get("seq")).intValue() : null);
                fEdge.setImpactKind("ROW");
                edges.add(fEdge);
            }
            // 表内所有列：行影响展开（depth=2），列查询每表一次
            Collection<Map<String, Object>> cols;
            try {
                cols = neo4jClient.query(
                                "MATCH (col:Column)-[:BELONGS_TO]->(t:Table {name: $tn}) " +
                                "RETURN col.col_key AS colKey, col.name AS name, " +
                                "  col.table_full_name AS tableFullName, t.is_temp AS temp")
                        .bind(tableName).to("tn").fetch().all();
            } catch (Exception e) {
                log.warn("行影响列展开失败 table={}: {}", tableName, e.getMessage());
                continue;
            }
            // ROW_IMPACT 边按 (proc, role) 组去重：同组重复（同 proc 多块）只发一组；
            // 跨组（不同 proc / WHERE+JOIN 双角色）各发一组，保留 proc 归属（前端行清单 key=procName|field）
            Set<String> emittedGroups = new HashSet<>();
            for (Map<String, Object> fr : tableFilters) {
                String groupKey = fr.get("procName") + "|" + fr.get("role");
                if (!emittedGroups.add(groupKey)) continue;
                String role = (String) fr.get("role");
                String procName = (String) fr.get("procName");
                for (Map<String, Object> col : cols) {
                    String colKey = (String) col.get("colKey");
                    if (colKey == null || colKey.equals(startColKey)) continue;
                    if (nodeIds.add(colKey)) {
                        nodes.add(buildFieldNode(col, colKey, 2));
                    }
                    ImpactAnalysisVO.EdgeVO rEdge = new ImpactAnalysisVO.EdgeVO();
                    rEdge.setSource(tableName);
                    rEdge.setTarget(colKey);
                    rEdge.setEdgeType("ROW_IMPACT");
                    rEdge.setRole(role);
                    rEdge.setProcName(procName);
                    rEdge.setImpactKind("ROW");
                    edges.add(rEdge);
                    nextLayerCols.add(colKey);
                }
            }
        }
        if (!nextLayerCols.isEmpty()) seeds.put(2, nextLayerCols);
        return seeds;
    }

    /** 上游条件分区：目标列所在表的全部反向 FILTERS 边 */
    private List<ImpactAnalysisVO.RowFilterVO> queryRowFilters(String fieldColKey) {
        String table = extractTableFromId(fieldColKey);
        List<ImpactAnalysisVO.RowFilterVO> result = new ArrayList<>();
        try {
            Collection<Map<String, Object>> rows = neo4jClient.query(
                            "MATCH (c:Column)-[f:FILTERS]->(t:Table {name: $tn}) " +
                            "RETURN c.col_key AS colKey, c.name AS name, f.role AS role, f.clause AS clause, " +
                            "  f.proc_name AS procName, f.sql_block_seq AS seq " +
                            "ORDER BY f.proc_name, c.col_key")
                    .bind(table).to("tn").fetch().all();
            for (Map<String, Object> r : rows) {
                ImpactAnalysisVO.RowFilterVO rf = new ImpactAnalysisVO.RowFilterVO();
                rf.setFieldColKey((String) r.get("colKey"));
                rf.setFieldName((String) r.get("name"));
                rf.setRole((String) r.get("role"));
                rf.setClause((String) r.get("clause"));
                rf.setProcName((String) r.get("procName"));
                rf.setSqlBlockSeq(r.get("seq") != null ? ((Number) r.get("seq")).intValue() : null);
                result.add(rf);
            }
        } catch (Exception e) {
            log.error("rowFilters 查询失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * BFS 层发现的字段节点构建（与旧可达节点查询的字段一致：
     * label=Column.name, tableFullName=Column.table_full_name, depth=BFS 层号）
     */
    private ImpactAnalysisVO.NodeVO buildFieldNode(Map<String, Object> col, String colKey, int layer) {
        ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
        n.setId(colKey);
        String[] p = colKey.split("\\.");
        String name = (String) col.get("name");
        n.setLabel(name != null ? name : (p.length >= 3 ? p[2] : colKey));
        // 临时表标记来自所属 Table 节点（权威：MySQL target_is_temp 同步），前端据此判定临时表
        n.setIsTemp(Boolean.TRUE.equals(col.get("temp")));
        n.setNodeLevel("FIELD");
        n.setLayer(extractLayerFromId(colKey));
        String tableFullName = (String) col.get("tableFullName");
        n.setTableFullName(tableFullName != null ? tableFullName
                : (p.length >= 2 ? (p[0] + "." + p[1]).toLowerCase() : null));
        n.setDepth(layer);
        return n;
    }

    /**
     * 补全字段节点：Transform 的输入/输出字段可能不在主路径上（辅助输入），
     * 若尚未存在于 nodeIds 则创建一个 FIELD 节点加入 nodes，确保前端按可见节点过滤边时不丢失输入边。
     * col_key 格式: schema.table.field（小写）
     */
    private void ensureFieldNode(String colKey, Set<String> nodeIds,
                                  List<ImpactAnalysisVO.NodeVO> nodes) {
        if (colKey == null || !nodeIds.add(colKey)) return;
        ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
        n.setId(colKey);
        String[] p = colKey.split("\\.");
        String fieldName = p.length >= 1 ? p[p.length - 1] : colKey;
        n.setLabel(fieldName.toUpperCase());
        n.setNodeLevel("FIELD");
        n.setLayer(extractLayerFromId(colKey));
        n.setTableFullName(p.length >= 2 ? (p[0] + "." + p[1]).toLowerCase() : null);
        n.setDepth(null);
        nodes.add(n);
    }

    /**
     * 清理悬空辅助节点：删除 depth=null 且不与任何有 depth 节点连通的 FIELD/TRANSFORM 节点。
     * 这些节点来自非路径 FieldMapping 的条件字段补全（ensureFieldNode），不在影响范围内。
     * 迭代保留直至不动点：与保留节点有边相连的 depth=null 节点必须保留
     * （如 CASE Transform 的输入字段、路径上条件字段的解释性输入），其邻居也可能因此保留。
     * 注意：TABLE 级节点即使 depth=null 也直接保留（表级视图依赖表节点）。
     */
    private void removeDanglingAuxNodes(List<ImpactAnalysisVO.NodeVO> nodes,
                                        List<ImpactAnalysisVO.EdgeVO> edges) {
        // 1. 初始保留集：有 depth 的节点 + 非 FIELD/TRANSFORM 层级节点
        Set<String> keep = nodes.stream()
                .filter(n -> n.getDepth() != null
                        || (!"FIELD".equals(n.getNodeLevel()) && !"TRANSFORM".equals(n.getNodeLevel())))
                .map(ImpactAnalysisVO.NodeVO::getId)
                .collect(Collectors.toSet());
        // 2. 迭代：depth=null 的 FIELD/TRANSFORM 节点若有一条边连到保留集则入保留集
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ImpactAnalysisVO.NodeVO n : nodes) {
                if (keep.contains(n.getId())) continue;
                boolean touchesKept = edges.stream().anyMatch(e ->
                        (n.getId().equals(e.getSource()) && keep.contains(e.getTarget()))
                        || (n.getId().equals(e.getTarget()) && keep.contains(e.getSource())));
                if (touchesKept) {
                    keep.add(n.getId());
                    changed = true;
                }
            }
        }
        // 3. 删除未保留的节点 + 任一端未保留的边
        nodes.removeIf(n -> !keep.contains(n.getId()));
        edges.removeIf(e -> !keep.contains(e.getSource()) || !keep.contains(e.getTarget()));
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
                                n.setLayer(extractLayerFromId(sourceId));
                                nodes.add(n);
                            }
                            if (!nodeIds.contains(targetId)) {
                                nodeIds.add(targetId);
                                ImpactAnalysisVO.NodeVO n = new ImpactAnalysisVO.NodeVO();
                                n.setId(targetId);
                                n.setLabel(extractShortName(targetId));
                                n.setNodeLevel("TABLE");
                                n.setLayer(extractLayerFromId(targetId));
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
                    chains.add(t.getImpactSource() + " -> " + t.getName());
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
                            n.setLayer(extractLayerFromId(n.getId()));
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
                            n.setLayer(record.get("layer").isNull() ? extractLayerFromId(n.getId()) : record.get("layer").asString());
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
