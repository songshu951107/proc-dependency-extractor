package com.hb.extractor.service;

import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.entity.TableLineageFilter;

import java.util.List;

/**
 * 血缘查询服务
 */
public interface LineageService {

    /**
     * 查询某存储过程的完整血缘图
     */
    LineageGraphVO getLineageGraph(String procName);

    /**
     * 递归查询某表的上游血缘
     * @param tableName 表短名
     * @param maxDepth 最大深度（默认 5，上限 10）
     */
    LineageGraphVO traceUpstream(String tableName, Integer maxDepth);

    /**
     * 递归查询某表的下游血缘
     */
    LineageGraphVO traceDownstream(String tableName, Integer maxDepth);

    /**
     * 查询某存储过程的所有血缘条件（JOIN/WHERE）
     */
    List<TableLineageFilter> getFilters(String procName);

    /**
     * 查询字段映射（含递归追溯）
     *
     * @param procName  存储过程名
     * @param tableName 目标表短名
     * @param recursive 是否递归追溯临时表字段的最终来源
     */
    FieldMappingVO getFieldMapping(String procName, String tableName, boolean recursive);
}
