package com.hb.extractor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.ReportReParseProgressVO;
import com.hb.extractor.dto.ReportDatasetDetailVO;
import com.hb.extractor.entity.ReportInfo;

import java.util.List;

/**
 * 报表解析服务接口
 */
public interface ReportParseService {

    /** 单文件解析 */
    ReportInfo parseAndSave(String filePath);

    /** 目录批量解析 */
    List<ReportInfo> parseBatch(String dirPath);

    /** 重新解析全部报表 */
    List<ReportInfo> reParseAll();

    /** 异步启动报表全量重新解析（写 MySQL + 同步 Neo4j），返回任务 id */
    String startReParseAll();

    /** 查询报表全量重新解析任务进度；任务不存在返回 null */
    ReportReParseProgressVO getReParseProgress(String taskId);

    /** 删除报表 */
    boolean deleteReport(String file);

    /** 分页查询报表列表 */
    IPage<ReportInfo> listReports(int pageNum, int pageSize, String name);

    /** 获取报表详情（含数据集和单元格绑定） */
    ReportInfo getReportDetail(String file);

    /** 组装单数据集下钻详情（源表 + 字段映射） */
    ReportDatasetDetailVO buildDatasetDetail(String dsName, String connection, String sqlText);

    /** 获取报表下所有数据集的下钻详情 */
    List<ReportDatasetDetailVO> getReportDatasets(String file);
}
