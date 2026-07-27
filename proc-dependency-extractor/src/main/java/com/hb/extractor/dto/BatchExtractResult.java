package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量提取结果
 */
@Data
public class BatchExtractResult {

    /** 总文件数 */
    private int totalCount;

    /** 成功数 */
    private int successCount;

    /** 失败数 */
    private int failCount;

    /** 失败的文件及原因 */
    private List<String> failures;

    /** 成功的存储过程名 */
    private List<String> successProcNames;
}
