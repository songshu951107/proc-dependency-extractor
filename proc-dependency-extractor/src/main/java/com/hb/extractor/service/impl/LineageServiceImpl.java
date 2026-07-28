package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.dto.LineageGraphVO;
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

        List<FieldMappingVO.FieldItem> fieldItems = new ArrayList<>();
        for (TableFieldMapping fm : mappings) {
            FieldMappingVO.FieldItem item = new FieldMappingVO.FieldItem();
            item.setTargetField(fm.getTargetField());
            item.setFieldOrder(fm.getFieldOrder());

            List<FieldMappingVO.DirectMapping> directs = new ArrayList<>();
            FieldMappingVO.DirectMapping dm = new FieldMappingVO.DirectMapping();
            dm.setSourceTable(fm.getSourceTableShort());
            dm.setSourceTableType(determineTableType(procName, fm.getSourceTableShort()));
            dm.setSourceExpr(fm.getSourceExpr());
            dm.setSourceAlias(fm.getSourceAlias());
            directs.add(dm);
            item.setDirectMappings(directs);

            if (recursive && fm.getSourceTableShort() != null
                    && fm.getSourceTableShort().startsWith("VT_")) {
                item.setUltimateSource(traceUltimateSource(procName, fm.getSourceTableShort(),
                        fm.getTargetField(), new LinkedHashSet<>()));
            }

            fieldItems.add(item);
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
        if (tableShort.startsWith("VT_")) return "TEMP";
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
     */
    private FieldMappingVO.UltimateSource traceUltimateSource(String procName, String tempTable,
                                                                String tempField, Set<String> visited) {
        String key = tempTable + "." + tempField;
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
        List<TableFieldMapping> mappings = tableFieldMappingMapper.selectList(
                new LambdaQueryWrapper<TableFieldMapping>()
                        .in(TableFieldMapping::getLineageId, edgeIds)
                        .eq(TableFieldMapping::getTargetField, tempField.toLowerCase()));
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

        if (sourceTable.startsWith("VT_")) {
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
     * 从 T01.acct_no 这样的表达式中提取字段名 acct_no
     */
    private String extractFieldFromExpr(String expr) {
        if (expr == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)\\.(\\w+)").matcher(expr);
        if (m.find()) return m.group(2).toLowerCase();
        return expr.toLowerCase();
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
            // 2. 以 VT_ 开头 → TEMP
            // 3. 作为边的 target 且 targetIsTemp=1 → TEMP
            // 4. 不在任何边的 target 中 → SOURCE（纯源表）
            // 5. 作为边的 target 且 targetIsTemp=0 → TARGET（真正的目标表）
            if (shortName.equals(updateTargetShort)) {
                node.setNodeType("TARGET");
            } else if (shortName.startsWith("VT_") || isTempInEdges(shortName, edges)) {
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
