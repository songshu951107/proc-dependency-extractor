package com.hb.extractor.service;

import com.hb.extractor.dto.SchemaBatchProgressVO;
import com.hb.extractor.dto.SchemaImpactSummaryVO;

/**
 * 批量 schema 一层下游影响异步任务服务（内存任务仓库，不持久化）。
 */
public interface SchemaBatchImpactService {

    /** 发起任务：立即返回 taskId，异步执行。同一 schema 旧任务被替换。 */
    String start(String schema);

    /** 查询进度；taskId 不存在时返回 null。 */
    SchemaBatchProgressVO getProgress(String taskId);

    /**
     * 取最终结果。任务不存在/进行中/失败时抛 IllegalStateException
     * （进行中 / 失败信息见 e.getMessage()）。
     */
    SchemaImpactSummaryVO getResult(String taskId);
}