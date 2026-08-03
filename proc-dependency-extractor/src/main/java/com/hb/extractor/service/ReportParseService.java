package com.hb.extractor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

    /** 删除报表 */
    boolean deleteReport(String file);

    /** 分页查询报表列表 */
    IPage<ReportInfo> listReports(int pageNum, int pageSize, String name);

    /** 获取报表详情（含数据集和单元格绑定） */
    ReportInfo getReportDetail(String file);
}
