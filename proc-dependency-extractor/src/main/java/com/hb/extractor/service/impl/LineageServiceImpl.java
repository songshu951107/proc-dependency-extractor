package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.dto.SqlBlockVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.SqlBlockInfo;
import com.hb.extractor.entity.TableFieldMapping;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.entity.TableLineageFilter;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.SqlBlockInfoMapper;
import com.hb.extractor.mapper.TableFieldMappingMapper;
import com.hb.extractor.mapper.TableLineageFilterMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LineageServiceImpl implements LineageService {

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private TableLineageMapper tableLineageMapper;

    @Autowired
    private TableLineageFilterMapper tableLineageFilterMapper;

    @Autowired
    private TableFieldMappingMapper tableFieldMappingMapper;

    @Autowired
    private SqlBlockInfoMapper sqlBlockInfoMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public LineageGraphVO getLineageGraph(String procName) {
        ProcInfo procInfo = procInfoMapper.selectOne(
                new LambdaQueryWrapper<ProcInfo>().eq(ProcInfo::getProcName, procName));
        if (procInfo == null) {
            return null;
        }

        List<TableLineage> edges = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .eq(TableLineage::getProcName, procName)
                        .orderByAsc(TableLineage::getSqlBlockSeq));

        List<TableLineageFilter> filters = tableLineageFilterMapper.selectList(
                new LambdaQueryWrapper<TableLineageFilter>()
                        .eq(TableLineageFilter::getProcName, procName));

        List<TableFieldMapping> fieldMappings = tableFieldMappingMapper.selectList(
                new LambdaQueryWrapper<TableFieldMapping>()
                        .eq(TableFieldMapping::getProcName, procName));

        return buildGraphVO(procName, procInfo.getUpdateTarget(), edges, filters, fieldMappings, false);
    }

    @Override
    public LineageGraphVO traceUpstream(String tableName, Integer maxDepth) {
        int depth = maxDepth == null ? 5 : Math.min(maxDepth, 10);
        List<Map<String, Object>> records = tableLineageMapper.findUpstream(tableName, depth);

        List<TableLineage> edges = convertRecords(records);
        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

        List<TableLineageFilter> filters = edgeIds.isEmpty() ? Collections.emptyList() :
                tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>()
                                .in(TableLineageFilter::getLineageId, edgeIds));

        List<TableFieldMapping> fieldMappings = edgeIds.isEmpty() ? Collections.emptyList() :
                tableFieldMappingMapper.selectList(
                        new LambdaQueryWrapper<TableFieldMapping>()
                                .in(TableFieldMapping::getLineageId, edgeIds));

        // 保守判断：存在 depth=maxDepth 的记录时标记 truncated。
        // 这可能产生误报（叶子节点恰好到达 maxDepth），但不会漏报。
        // 精确判断需要额外查询该 source 是否仍有上游，后续迭代优化。
        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, fieldMappings, truncated);
    }

    @Override
    public LineageGraphVO traceDownstream(String tableName, Integer maxDepth) {
        int depth = maxDepth == null ? 5 : Math.min(maxDepth, 10);
        List<Map<String, Object>> records = tableLineageMapper.findDownstream(tableName, depth);

        List<TableLineage> edges = convertRecords(records);
        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());

        List<TableLineageFilter> filters = edgeIds.isEmpty() ? Collections.emptyList() :
                tableLineageFilterMapper.selectList(
                        new LambdaQueryWrapper<TableLineageFilter>()
                                .in(TableLineageFilter::getLineageId, edgeIds));

        List<TableFieldMapping> fieldMappings = edgeIds.isEmpty() ? Collections.emptyList() :
                tableFieldMappingMapper.selectList(
                        new LambdaQueryWrapper<TableFieldMapping>()
                                .in(TableFieldMapping::getLineageId, edgeIds));

        // 保守判断：存在 depth=maxDepth 的记录时标记 truncated（同 traceUpstream）
        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, fieldMappings, truncated);
    }

    @Override
    public List<TableLineageFilter> getFilters(String procName) {
        return tableLineageFilterMapper.selectList(
                new LambdaQueryWrapper<TableLineageFilter>()
                        .eq(TableLineageFilter::getProcName, procName));
    }

    @Override
    public FieldMappingVO getFieldMapping(String procName, String tableName, boolean recursive) {
        FieldMappingVO vo = new FieldMappingVO();
        vo.setTargetTable(tableName);

        String targetTableType = determineTableType(procName, tableName);
        vo.setTargetTableType(targetTableType);

        List<TableLineage> edges = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .eq(TableLineage::getProcName, procName)
                        .eq(TableLineage::getTargetTableShort, tableName)
                        .orderByAsc(TableLineage::getSqlBlockSeq));
        if (edges.isEmpty()) {
            vo.setFields(Collections.emptyList());
            vo.setUnmappedFields(Collections.emptyList());
            return vo;
        }

        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());
        List<TableFieldMapping> mappings = tableFieldMappingMapper.selectList(
                new LambdaQueryWrapper<TableFieldMapping>()
                        .in(TableFieldMapping::getLineageId, edgeIds)
                        .orderByAsc(TableFieldMapping::getFieldOrder));

        // 按 targetField 聚合：同一目标字段可能来自多个源表（多个 INSERT 块），
        // 应合并为一个 FieldItem，多源作为 directMappings 列表，避免前端出现重复行
        // 同时建立 edgeId → sqlBlockSeq 映射，让前端可按 SQL 块筛选
        Map<Long, Integer> edgeIdToBlockSeq = new HashMap<>();
        for (TableLineage edge : edges) {
            edgeIdToBlockSeq.put(edge.getId(), edge.getSqlBlockSeq());
        }

        List<FieldMappingVO.FieldItem> fieldItems = new ArrayList<>();
        Map<String, FieldMappingVO.FieldItem> itemByField = new LinkedHashMap<>();
        for (TableFieldMapping fm : mappings) {
            String key = fm.getTargetField() == null ? "" : fm.getTargetField();
            FieldMappingVO.FieldItem item = itemByField.get(key);
            if (item == null) {
                item = new FieldMappingVO.FieldItem();
                item.setTargetField(fm.getTargetField());
                item.setFieldOrder(fm.getFieldOrder());
                item.setDirectMappings(new ArrayList<>());
                fieldItems.add(item);
                itemByField.put(key, item);
            }

            FieldMappingVO.DirectMapping dm = new FieldMappingVO.DirectMapping();
            dm.setSourceTable(fm.getSourceTableShort());
            dm.setSourceTableType(determineTableType(procName, fm.getSourceTableShort()));
            dm.setSourceExpr(fm.getSourceExpr());
            dm.setSourceAlias(fm.getSourceAlias());
            dm.setSqlBlockSeq(edgeIdToBlockSeq.get(fm.getLineageId()));
            item.getDirectMappings().add(dm);

            // 递归追溯临时表来源：取第一个临时表源进行追溯（避免多次追溯覆盖）
            // 注意：传入临时表后应用 sourceExpr 中提取的字段名（如 DECODE(T7.M_AVG,...) → m_avg），
            // 而非目标字段名（如 kh_mavg），因为临时表中存储的是 sourceExpr 引用的字段名
            if (recursive && fm.getSourceTableShort() != null
                    && isTempTableName(fm.getSourceTableShort())
                    && item.getUltimateSource() == null) {
                String traceField = extractFieldFromExpr(fm.getSourceExpr());
                if (traceField == null) {
                    traceField = fm.getTargetField();
                }
                item.setUltimateSource(traceUltimateSource(procName, fm.getSourceTableShort(),
                        traceField, new LinkedHashSet<>()));
            }
        }
        vo.setFields(fieldItems);
        vo.setUnmappedFields(new ArrayList<>());
        return vo;
    }

    /**
     * 判定表类型：TEMP / SOURCE / TARGET
     */
    private String determineTableType(String procName, String tableShort) {
        if (tableShort == null) return null;
        if (isTempTableName(tableShort)) return "TEMP";
        List<SqlBlockInfo> blocks = sqlBlockInfoMapper.selectList(
                new LambdaQueryWrapper<SqlBlockInfo>()
                        .eq(SqlBlockInfo::getProcName, procName)
                        .eq(SqlBlockInfo::getTargetTable, tableShort));
        if (!blocks.isEmpty()) {
            return "TARGET";
        }
        return "SOURCE";
    }

    /**
     * 递归追溯临时表字段的最终来源
     *
     * 注意：target_field 在 DB 中可能为大小写混合（DDL 原始大小写）或小写（无 DDL 时），
     * tempField 可能来自 extractFieldFromExpr（小写）或上层 target_field（混合大小写），
     * 因此这里查询全部映射后用 equalsIgnoreCase 过滤，避免大小写不匹配导致溯源失败。
     */
    private FieldMappingVO.UltimateSource traceUltimateSource(String procName, String tempTable,
                                                                String tempField, Set<String> visited) {
        if (tempField == null) return null;
        // 统一转为大写作为 visited 的 key，避免大小写差异导致重复访问或漏访问
        String key = tempTable + "." + tempField.toUpperCase();
        if (visited.contains(key)) {
            return null;
        }
        visited.add(key);

        List<TableLineage> edges = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .eq(TableLineage::getProcName, procName)
                        .eq(TableLineage::getTargetTableShort, tempTable));
        if (edges.isEmpty()) return null;

        List<Long> edgeIds = edges.stream().map(TableLineage::getId).collect(Collectors.toList());
        // 查询全部映射后在 Java 侧用 equalsIgnoreCase 过滤，兼容 M_AVG / m_avg 等大小写差异
        List<TableFieldMapping> allMappings = tableFieldMappingMapper.selectList(
                new LambdaQueryWrapper<TableFieldMapping>()
                        .in(TableFieldMapping::getLineageId, edgeIds));
        List<TableFieldMapping> mappings = allMappings.stream()
                .filter(m -> m.getTargetField() != null
                        && m.getTargetField().equalsIgnoreCase(tempField))
                .collect(Collectors.toList());
        if (mappings.isEmpty()) return null;

        TableFieldMapping fm = mappings.get(0);
        String sourceTable = fm.getSourceTableShort();
        if (sourceTable == null) return null;

        FieldMappingVO.UltimateSource result = new FieldMappingVO.UltimateSource();
        List<FieldMappingVO.PathNode> path = new ArrayList<>();
        FieldMappingVO.PathNode n1 = new FieldMappingVO.PathNode();
        n1.setTable(tempTable);
        n1.setField(tempField);
        path.add(n1);

        if (isTempTableName(sourceTable)) {
            FieldMappingVO.UltimateSource deeper = traceUltimateSource(procName, sourceTable,
                    extractFieldFromExpr(fm.getSourceExpr()), visited);
            if (deeper != null) {
                result.setSourceTable(deeper.getSourceTable());
                result.setSourceTableType(deeper.getSourceTableType());
                result.setSourceField(deeper.getSourceField());
                path.addAll(deeper.getPath());
            } else {
                result.setSourceTable(sourceTable);
                result.setSourceTableType("TEMP");
                result.setSourceField(extractFieldFromExpr(fm.getSourceExpr()));
                path.add(buildPathNode(sourceTable, extractFieldFromExpr(fm.getSourceExpr())));
            }
        } else {
            result.setSourceTable(sourceTable);
            result.setSourceTableType("SOURCE");
            result.setSourceField(extractFieldFromExpr(fm.getSourceExpr()));
            path.add(buildPathNode(sourceTable, extractFieldFromExpr(fm.getSourceExpr())));
        }
        result.setPath(path);
        return result;
    }

    private FieldMappingVO.PathNode buildPathNode(String table, String field) {
        FieldMappingVO.PathNode n = new FieldMappingVO.PathNode();
        n.setTable(table);
        n.setField(field);
        return n;
    }

    /**
     * 从表达式中提取代表性字段名（用于溯源）
     *
     * 字段提取优先级（避免 CASE WHEN 条件字段干扰）：
     * 1. CASE WHEN ... THEN alias.field ... END → 取 THEN 后的 field（实际取值字段）
     * 2. 取最后一个 alias.field 的 field 部分
     * 3. 无 alias.field → 返回表达式小写
     *
     * 示例：
     *   "SUM(CASE WHEN A.appl_dt ... THEN LR_DISTR.dist_amt_thmonth END)" → "dist_amt_thmonth"
     *   "NVL(a.x, b.y)" → "y"
     *   "COUNT(*)" → "count(*)"
     */
    private String extractFieldFromExpr(String expr) {
        if (expr == null) return null;
        // 1. CASE WHEN ... THEN alias.field 模式：取 THEN 后的 field
        java.util.regex.Matcher thenM = java.util.regex.Pattern.compile(
            "\\bTHEN\\s+(\\w+)\\.(\\w+)", Pattern.CASE_INSENSITIVE).matcher(expr);
        if (thenM.find()) {
            return thenM.group(2).toLowerCase();
        }
        // 2. 非 CASE 表达式：取最后一个 alias.field 的 field 部分
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)\\.(\\w+)").matcher(expr);
        String lastField = null;
        while (m.find()) {
            lastField = m.group(2);
        }
        if (lastField != null) {
            return lastField.toLowerCase();
        }
        // 3. 无 alias.field
        return expr.toLowerCase();
    }

    @Override
    public List<SqlBlockVO> getSqlBlocks(String procName) {
        List<SqlBlockInfo> blocks = sqlBlockInfoMapper.selectList(
                new LambdaQueryWrapper<SqlBlockInfo>()
                        .eq(SqlBlockInfo::getProcName, procName)
                        .orderByAsc(SqlBlockInfo::getBlockSeq));
        if (blocks.isEmpty()) return Collections.emptyList();

        List<TableLineageFilter> allFilters = tableLineageFilterMapper.selectList(
                new LambdaQueryWrapper<TableLineageFilter>()
                        .eq(TableLineageFilter::getProcName, procName));

        List<TableLineage> allEdges = tableLineageMapper.selectList(
                new LambdaQueryWrapper<TableLineage>()
                        .eq(TableLineage::getProcName, procName)
                        .orderByAsc(TableLineage::getSqlBlockSeq));

        // 先按原逻辑构建所有块的 VO
        List<SqlBlockVO> rawResult = new ArrayList<>();
        for (SqlBlockInfo block : blocks) {
            rawResult.add(buildSqlBlockVO(procName, block, allEdges, allFilters));
        }

        return mergeBlocksByTargetTable(rawResult);
    }

    /**
     * 构建单个 SQL 块的 VO（不含 createDdl 信息）
     */
    private SqlBlockVO buildSqlBlockVO(String procName, SqlBlockInfo block,
                                        List<TableLineage> allEdges,
                                        List<TableLineageFilter> allFilters) {
        SqlBlockVO vo = new SqlBlockVO();
        vo.setBlockSeq(block.getBlockSeq());
        vo.setBlockType(block.getBlockType());
        vo.setTargetTable(block.getTargetTable());
        vo.setTargetTableType(determineTableType(procName, block.getTargetTable()));
        vo.setMainTable(block.getMainTable());
        vo.setMainTableType(determineTableType(procName, block.getMainTable()));
        vo.setStartLine(block.getStartLine());
        vo.setEndLine(block.getEndLine());
        vo.setSqlContent(block.getSqlContent());

        List<TableLineage> blockEdges = allEdges.stream()
                .filter(e -> Integer.valueOf(block.getBlockSeq()).equals(e.getSqlBlockSeq()))
                .collect(Collectors.toList());
        List<Long> blockEdgeIds = blockEdges.stream().map(TableLineage::getId).collect(Collectors.toList());
        List<TableLineageFilter> blockFilters = allFilters.stream()
                .filter(f -> blockEdgeIds.contains(f.getLineageId()))
                .collect(Collectors.toList());

        // JOIN 表信息直接从 sqlContent 解析，因为 table_lineage_filter 只存了 filterClause，
        // 没有单独存储 tableName/joinType/alias，且 filterClause 末尾会误带 JOIN 前缀关键字（如 LEFT）
        List<SqlBlockVO.JoinTable> joinTables = parseJoinTablesFromSql(block.getSqlContent());
        vo.setJoinTables(joinTables);

        // WHERE 条件仍从 filters 遍历（已按字段拆分，更精确）
        StringBuilder whereSb = new StringBuilder();
        for (TableLineageFilter f : blockFilters) {
            if ("WHERE".equals(f.getFilterType())) {
                if (whereSb.length() > 0) whereSb.append(" AND ");
                whereSb.append(f.getFilterClause());
            }
        }
        vo.setWhereConditions(whereSb.length() > 0 ? whereSb.toString() : null);

        List<LineageGraphVO.FilterDetail> filterDetails = new ArrayList<>();
        for (TableLineageFilter f : blockFilters) {
            LineageGraphVO.FilterDetail fd = new LineageGraphVO.FilterDetail();
            fd.setFilterType(f.getFilterType());
            fd.setFilterClause(f.getFilterClause());
            fd.setFields(parseFields(f.getFieldsInvolved()));
            filterDetails.add(fd);
        }
        vo.setFilters(filterDetails);
        return vo;
    }

    /**
     * 按 targetTable 合并 SQL 块：
     * 同一 targetTable 同时存在 CREATE_TEMP 和 INSERT_SELECT 块时，
     * 把 CREATE_TEMP 块的建表语句（含字段定义）合并到 INSERT_SELECT 块的 createDdl 字段，
     * 并从结果中移除 CREATE_TEMP 块，避免子 tab 重复且保留字段定义不丢失。
     * targetTable 为 null 的块（如 DELETE、DROP）不参与合并，直接保留。
     */
    private List<SqlBlockVO> mergeBlocksByTargetTable(List<SqlBlockVO> rawResult) {
        // 找出每个 targetTable 是否存在 INSERT_SELECT 块
        Set<String> targetTablesWithInsert = rawResult.stream()
                .filter(v -> v.getTargetTable() != null && "INSERT_SELECT".equals(v.getBlockType()))
                .map(SqlBlockVO::getTargetTable)
                .collect(Collectors.toSet());

        // 构建 CREATE_TEMP 块的 targetTable -> VO 映射（取第一个，用于合并到 INSERT_SELECT 块）
        Map<String, SqlBlockVO> createTempByTarget = new LinkedHashMap<>();
        for (SqlBlockVO vo : rawResult) {
            if (vo.getTargetTable() != null && "CREATE_TEMP".equals(vo.getBlockType())
                    && !createTempByTarget.containsKey(vo.getTargetTable())) {
                createTempByTarget.put(vo.getTargetTable(), vo);
            }
        }

        List<SqlBlockVO> result = new ArrayList<>();
        for (SqlBlockVO vo : rawResult) {
            String targetTable = vo.getTargetTable();
            if (targetTable == null) {
                // targetTable 为 null 的块不参与合并，直接保留
                result.add(vo);
                continue;
            }
            if ("CREATE_TEMP".equals(vo.getBlockType()) && targetTablesWithInsert.contains(targetTable)) {
                // 该 CREATE_TEMP 块已被同 targetTable 的 INSERT_SELECT 块覆盖，跳过
                // 其建表语句会合并到对应的 INSERT_SELECT 块的 createDdl 字段
                continue;
            }
            if ("INSERT_SELECT".equals(vo.getBlockType()) && createTempByTarget.containsKey(targetTable)) {
                // 把同 targetTable 的 CREATE_TEMP 块的建表语句合并到此处
                SqlBlockVO createTemp = createTempByTarget.get(targetTable);
                vo.setCreateDdl(createTemp.getSqlContent());
                vo.setCreateBlockSeq(createTemp.getBlockSeq());
                vo.setCreateStartLine(createTemp.getStartLine());
                vo.setCreateEndLine(createTemp.getEndLine());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 从 SQL 块原文解析 JOIN 子句，提取表名、JOIN 类型、别名、ON 条件。
     * 支持 LEFT/RIGHT/INNER/OUTER/FULL/CROSS JOIN 和普通 JOIN。
     * 能正确处理子查询内嵌套的 JOIN。
     */
    private List<SqlBlockVO.JoinTable> parseJoinTablesFromSql(String sqlContent) {
        List<SqlBlockVO.JoinTable> joinTables = new ArrayList<>();
        if (sqlContent == null || sqlContent.isEmpty()) {
            return joinTables;
        }

        // 匹配: [LEFT|RIGHT|INNER|OUTER|FULL|CROSS] JOIN {schema}.table alias ON conditions
        // conditions 非贪婪匹配，直到下一个 JOIN 前缀关键字或 WHERE/GROUP/ORDER/LIMIT/;/$
        Pattern joinPattern = Pattern.compile(
                "(?im)\\b(?:(LEFT|RIGHT|INNER|OUTER|FULL|CROSS)\\s+)?JOIN\\s+" +
                        "(?:(?:\\{\\w+\\}|\\w+)\\.)?(\\w+)" +
                        "(?:\\s+(?:AS\\s+)?(\\w+))?\\s+ON\\s+" +
                        "(.+?)" +
                        "(?=\\s+(?:(?:LEFT|RIGHT|INNER|OUTER|FULL|CROSS)\\s+)?JOIN\\b|WHERE\\b|GROUP\\b|ORDER\\b|LIMIT\\b|;|$)"
        );

        Matcher m = joinPattern.matcher(sqlContent);
        while (m.find()) {
            SqlBlockVO.JoinTable jt = new SqlBlockVO.JoinTable();
            String joinType = m.group(1);
            jt.setJoinType(joinType != null ? joinType.toUpperCase() + " JOIN" : "INNER JOIN");
            jt.setTableName(m.group(2));
            jt.setAlias(m.group(3));
            jt.setConditions(m.group(4).trim());
            joinTables.add(jt);
        }
        return joinTables;
    }

    private LineageGraphVO buildGraphVO(String procName, String updateTarget,
                                          List<TableLineage> edges,
                                          List<TableLineageFilter> filters,
                                          List<TableFieldMapping> fieldMappings,
                                          boolean truncated) {
        LineageGraphVO vo = new LineageGraphVO();
        vo.setProcName(procName);
        vo.setUpdateTarget(updateTarget);
        vo.setTruncated(truncated);

        Set<String> sourceShorts = edges.stream()
                .map(TableLineage::getSourceTableShort)
                .collect(Collectors.toSet());
        Set<String> targetShorts = edges.stream()
                .map(TableLineage::getTargetTableShort)
                .collect(Collectors.toSet());

        Set<String> allShorts = new LinkedHashSet<>();
        allShorts.addAll(sourceShorts);
        allShorts.addAll(targetShorts);

        String updateTargetShort = extractShortName(updateTarget);

        List<LineageGraphVO.Node> nodes = new ArrayList<>();

        for (String shortName : allShorts) {
            LineageGraphVO.Node node = new LineageGraphVO.Node();
            node.setId(shortName);

            // 节点类型判定优先级：
            // 1. 匹配 updateTarget → TARGET
            // 2. 以 VT_/TMP_ 开头或 _TMP 后缀 → TEMP
            // 3. 作为边的 target 且 targetIsTemp=1 → TEMP
            // 4. 不在任何边的 target 中 → SOURCE（纯源表）
            // 5. 作为边的 target 且 targetIsTemp=0 → TARGET（真正的目标表）
            if (shortName.equals(updateTargetShort)) {
                node.setNodeType("TARGET");
            } else if (isTempTableName(shortName) || isTempInEdges(shortName, edges)) {
                node.setNodeType("TEMP");
            } else if (!targetShorts.contains(shortName)) {
                node.setNodeType("SOURCE");
            } else if (isTargetInEdges(shortName, edges)) {
                // 作为 target 且非临时表 → 真正的目标表
                node.setNodeType("TARGET");
            } else {
                node.setNodeType("TEMP");
            }

            TableLineage sample = edges.stream()
                    .filter(e -> shortName.equals(e.getSourceTableShort()) || shortName.equals(e.getTargetTableShort()))
                    .findFirst().orElse(null);
            if (sample != null) {
                if (shortName.equals(sample.getSourceTableShort())) {
                    node.setFullTableName(sample.getSourceTable());
                    node.setSchemaName(extractSchema(sample.getSourceTable()));
                } else {
                    node.setFullTableName(sample.getTargetTable());
                    node.setSchemaName(extractSchema(sample.getTargetTable()));
                }
            }

            nodes.add(node);
        }

        Map<Long, List<TableLineageFilter>> filterByLineageId = filters.stream()
                .collect(Collectors.groupingBy(TableLineageFilter::getLineageId));

        List<LineageGraphVO.Edge> edgeList = new ArrayList<>();
        for (TableLineage e : edges) {
            LineageGraphVO.Edge edge = new LineageGraphVO.Edge();
            edge.setSource(e.getSourceTableShort());
            edge.setTarget(e.getTargetTableShort());
            edge.setOperation(e.getOperation());
            edge.setSqlBlockSeq(e.getSqlBlockSeq());

            List<LineageGraphVO.FilterDetail> filterDetails = new ArrayList<>();
            List<TableLineageFilter> edgeFilters = filterByLineageId.getOrDefault(e.getId(), Collections.emptyList());
            for (TableLineageFilter f : edgeFilters) {
                LineageGraphVO.FilterDetail fd = new LineageGraphVO.FilterDetail();
                fd.setFilterType(f.getFilterType());
                fd.setFilterClause(f.getFilterClause());
                fd.setFields(parseFields(f.getFieldsInvolved()));
                filterDetails.add(fd);
            }
            edge.setFilters(filterDetails);
            edge.setMainTable(e.getMainTable() != null ? e.getMainTable() : null);

            List<LineageGraphVO.FieldMappingBrief> briefs = new ArrayList<>();
            for (TableFieldMapping fm : fieldMappings) {
                if (fm.getLineageId() != null && fm.getLineageId().equals(e.getId())) {
                    LineageGraphVO.FieldMappingBrief brief = new LineageGraphVO.FieldMappingBrief();
                    brief.setTargetField(fm.getTargetField());
                    brief.setSourceExpr(fm.getSourceExpr());
                    brief.setSourceTableShort(fm.getSourceTableShort());
                    briefs.add(brief);
                }
            }
            edge.setFieldMappings(briefs);
            edgeList.add(edge);
        }

        vo.setNodes(nodes);
        vo.setEdges(edgeList);
        return vo;
    }

    private boolean isTempInEdges(String shortName, List<TableLineage> edges) {
        return edges.stream().anyMatch(e ->
                shortName.equals(e.getTargetTableShort()) && Integer.valueOf(1).equals(e.getTargetIsTemp()));
    }

    /**
     * 统一临时表判断规则（不区分大小写）：
     * - VT_ 前缀
     * - TMP_ 前缀
     * - _TMP 后缀（含 _TMP1, _TMP2 等数字后缀）
     */
    private static boolean isTempTableName(String shortName) {
        if (shortName == null || shortName.isEmpty()) return false;
        String upper = shortName.toUpperCase();
        return upper.startsWith("VT_")
                || upper.startsWith("TMP_")
                || upper.matches(".*_TMP\\d*");
    }

    /**
     * 判断 shortName 是否作为某条边的 target 且 targetIsTemp=0（真正的目标表，非临时表）
     */
    private boolean isTargetInEdges(String shortName, List<TableLineage> edges) {
        return edges.stream().anyMatch(e ->
                shortName.equals(e.getTargetTableShort()) && Integer.valueOf(0).equals(e.getTargetIsTemp()));
    }

    private List<String> parseFields(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 fields JSON 失败: {}", json);
            return Collections.emptyList();
        }
    }

    private String extractShortName(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    private String extractSchema(String fullName) {
        if (fullName == null) return null;
        int idx = fullName.indexOf('.');
        return idx >= 0 ? fullName.substring(0, idx) : null;
    }

    @SuppressWarnings("unchecked")
    private List<TableLineage> convertRecords(List<Map<String, Object>> records) {
        List<TableLineage> list = new ArrayList<>();
        for (Map<String, Object> r : records) {
            TableLineage e = new TableLineage();
            e.setId(toLong(r.get("id")));
            e.setProcId(toLong(r.get("proc_id")));
            e.setProcName((String) r.get("proc_name"));
            e.setSourceTable((String) r.get("source_table"));
            e.setSourceTableShort((String) r.get("source_table_short"));
            e.setTargetTable((String) r.get("target_table"));
            e.setTargetTableShort((String) r.get("target_table_short"));
            Object tempVal = r.get("target_is_temp");
            e.setTargetIsTemp(tempVal instanceof Number ? ((Number) tempVal).intValue() : 0);
            e.setOperation((String) r.get("operation"));
            Object seqVal = r.get("sql_block_seq");
            e.setSqlBlockSeq(seqVal instanceof Number ? ((Number) seqVal).intValue() : 0);
            e.setMainTable((String) r.get("main_table"));
            list.add(e);
        }
        return list;
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }
}
