package com.hb.extractor.service;

import com.hb.extractor.dto.LineageGraphVO;

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
}
