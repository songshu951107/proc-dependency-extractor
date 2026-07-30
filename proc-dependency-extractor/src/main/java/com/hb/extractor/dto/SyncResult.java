package com.hb.extractor.dto;

import lombok.Data;

/**
 * Neo4j 同步结果
 */
@Data
public class SyncResult {

    private String procName;
    private boolean success;
    private String errorMessage;

    /** 全量同步时的统计 */
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;

    public static SyncResult success(String procName) {
        SyncResult r = new SyncResult();
        r.setProcName(procName);
        r.setSuccess(true);
        return r;
    }

    public static SyncResult failure(String procName, String errorMessage) {
        SyncResult r = new SyncResult();
        r.setProcName(procName);
        r.setSuccess(false);
        r.setErrorMessage(errorMessage);
        return r;
    }

    public static SyncResult summary(int total, int success, int failed) {
        SyncResult r = new SyncResult();
        r.setSuccess(failed == 0);
        r.setTotalCount(total);
        r.setSuccessCount(success);
        r.setFailedCount(failed);
        return r;
    }
}
