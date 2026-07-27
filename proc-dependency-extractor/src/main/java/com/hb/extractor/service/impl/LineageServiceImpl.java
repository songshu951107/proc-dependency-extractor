package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.entity.TableLineageFilter;
import com.hb.extractor.mapper.ProcInfoMapper;
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

        return buildGraphVO(procName, procInfo.getUpdateTarget(), edges, filters, false);
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

        // 保守判断：存在 depth=maxDepth 的记录时标记 truncated。
        // 这可能产生误报（叶子节点恰好到达 maxDepth），但不会漏报。
        // 精确判断需要额外查询该 source 是否仍有上游，后续迭代优化。
        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, truncated);
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

        // 保守判断：存在 depth=maxDepth 的记录时标记 truncated（同 traceUpstream）
        boolean truncated = records.stream().anyMatch(r -> Integer.valueOf(depth).equals(r.get("depth")));
        return buildGraphVO(null, null, edges, filters, truncated);
    }

    @Override
    public List<TableLineageFilter> getFilters(String procName) {
        return tableLineageFilterMapper.selectList(
                new LambdaQueryWrapper<TableLineageFilter>()
                        .eq(TableLineageFilter::getProcName, procName));
    }

    private LineageGraphVO buildGraphVO(String procName, String updateTarget,
                                          List<TableLineage> edges,
                                          List<TableLineageFilter> filters,
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

            if (shortName.equals(updateTargetShort)) {
                node.setNodeType("TARGET");
            } else if (shortName.startsWith("VT_") || isTempInEdges(shortName, edges)) {
                node.setNodeType("TEMP");
            } else if (!targetShorts.contains(shortName)) {
                node.setNodeType("SOURCE");
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
