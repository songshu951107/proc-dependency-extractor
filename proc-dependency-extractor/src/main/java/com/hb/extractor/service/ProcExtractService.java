package com.hb.extractor.service;

import com.hb.extractor.dto.BatchExtractResult;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.ProcTableDependency;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 存储过程解析服务接口
 */
public interface ProcExtractService {

    /**
     * 解析单个文件并入库
     *
     * @param filePath 文件路径
     * @return 存储过程信息
     */
    ProcInfo extractAndSave(String filePath);

    /**
     * 批量解析目录下所有 .py 文件并入库
     *
     * @param dirPath 目录路径
     * @return 批量结果
     */
    BatchExtractResult extractAndSaveBatch(String dirPath);

    /**
     * 解析上传的 py 文件并入库(支持单文件或多个文件)
     *
     * @param files 上传的文件列表
     * @return 批量结果
     */
    BatchExtractResult extractAndSaveUploaded(List<MultipartFile> files);

    /**
     * 重新解析全部已存在的存储过程(先删后插)
     *
     * @return 批量结果
     */
    BatchExtractResult reExtractAll();

    /**
     * 分页查询存储过程列表
     */
    IPage<ProcInfo> listProcs(int pageNum, int pageSize, String procName);

    /**
     * 查询某存储过程的依赖表
     */
    List<ProcTableDependency> listDependencies(String procName);

    /**
     * 删除存储过程及其依赖
     */
    boolean deleteProc(String procName);
}
