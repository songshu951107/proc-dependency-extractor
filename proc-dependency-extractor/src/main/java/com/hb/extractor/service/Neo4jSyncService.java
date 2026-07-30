package com.hb.extractor.service;

import com.hb.extractor.dto.SyncResult;

/**
 * Neo4j 同步服务接口
 */
public interface Neo4jSyncService {

    /**
     * 同步单个 proc 到 Neo4j
     */
    SyncResult syncProc(String procName);

    /**
     * 全量同步所有 proc
     */
    SyncResult syncAll();

    /**
     * 删除 Neo4j 中该 proc 的全部数据
     */
    void deleteProcData(String procName);
}
