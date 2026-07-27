package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hb.extractor.dto.BatchExtractResult;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.ProcTableDependency;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.ProcTableDependencyMapper;
import com.hb.extractor.parser.PyProcParser;
import com.hb.extractor.parser.dto.ProcMetadata;
import com.hb.extractor.service.ProcExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 存储过程解析服务实现
 */
@Slf4j
@Service
public class ProcExtractServiceImpl implements ProcExtractService {

    @Autowired
    private PyProcParser parser;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private ProcTableDependencyMapper procTableDependencyMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcInfo extractAndSave(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + filePath);
        }
        if (!filePath.endsWith(".py")) {
            throw new IllegalArgumentException("仅支持 .py 文件: " + filePath);
        }

        try {
            ProcMetadata metadata = parser.parse(path);
            return saveMetadata(metadata);
        } catch (IOException e) {
            throw new RuntimeException("解析文件失败: " + filePath + ", " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchExtractResult extractAndSaveBatch(String dirPath) {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在或不是有效目录: " + dirPath);
        }

        BatchExtractResult result = new BatchExtractResult();
        List<String> failures = new ArrayList<>();
        List<String> successProcNames = new ArrayList<>();
        int success = 0;
        int fail = 0;

        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> pyFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .collect(Collectors.toList());

            result.setTotalCount(pyFiles.size());

            for (Path pyFile : pyFiles) {
                try {
                    ProcMetadata metadata = parser.parse(pyFile);
                    saveMetadata(metadata);
                    success++;
                    successProcNames.add(metadata.getProcName());
                } catch (Exception e) {
                    fail++;
                    String msg = pyFile.getFileName() + ": " + e.getMessage();
                    failures.add(msg);
                    log.error("解析文件失败: {}", pyFile, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("遍历目录失败: " + dirPath + ", " + e.getMessage(), e);
        }

        result.setSuccessCount(success);
        result.setFailCount(fail);
        result.setFailures(failures);
        result.setSuccessProcNames(successProcNames);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchExtractResult extractAndSaveUploaded(List<MultipartFile> files) {
        BatchExtractResult result = new BatchExtractResult();
        List<String> failures = new ArrayList<>();
        List<String> successProcNames = new ArrayList<>();
        int success = 0;
        int fail = 0;

        // 过滤非 py 文件
        List<MultipartFile> pyFiles = files.stream()
                .filter(f -> f.getOriginalFilename() != null
                        && f.getOriginalFilename().endsWith(".py"))
                .collect(Collectors.toList());

        result.setTotalCount(pyFiles.size());

        if (pyFiles.isEmpty()) {
            result.setSuccessCount(0);
            result.setFailCount(0);
            result.setFailures(failures);
            result.setSuccessProcNames(successProcNames);
            return result;
        }

        // 保存到临时目录后解析
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("proc_upload_");
            for (MultipartFile file : pyFiles) {
                String fileName = file.getOriginalFilename();
                Path tempFile = tempDir.resolve(fileName);
                try {
                    file.transferTo(tempFile.toFile());
                    ProcMetadata metadata = parser.parse(tempFile);
                    saveMetadata(metadata);
                    success++;
                    successProcNames.add(metadata.getProcName());
                } catch (Exception e) {
                    fail++;
                    failures.add(fileName + ": " + e.getMessage());
                    log.error("解析上传文件失败: {}", fileName, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("创建临时目录失败: " + e.getMessage(), e);
        } finally {
            // 清理临时文件
            if (tempDir != null) {
                try (Stream<Path> stream = Files.list(tempDir)) {
                    stream.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                    Files.deleteIfExists(tempDir);
                } catch (IOException ignored) {}
            }
        }

        result.setSuccessCount(success);
        result.setFailCount(fail);
        result.setFailures(failures);
        result.setSuccessProcNames(successProcNames);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchExtractResult reExtractAll() {
        // 查询所有已存在的存储过程文件路径
        List<ProcInfo> existingProcs = procInfoMapper.selectList(null);
        BatchExtractResult result = new BatchExtractResult();
        List<String> failures = new ArrayList<>();
        List<String> successProcNames = new ArrayList<>();
        int success = 0;
        int fail = 0;

        result.setTotalCount(existingProcs.size());

        for (ProcInfo proc : existingProcs) {
            Path path = Paths.get(proc.getFilePath());
            if (!Files.exists(path)) {
                fail++;
                failures.add(proc.getProcName() + ": 文件不存在 " + proc.getFilePath());
                continue;
            }
            try {
                ProcMetadata metadata = parser.parse(path);
                saveMetadata(metadata);
                success++;
                successProcNames.add(metadata.getProcName());
            } catch (Exception e) {
                fail++;
                failures.add(proc.getProcName() + ": " + e.getMessage());
                log.error("重新解析失败: {}", proc.getProcName(), e);
            }
        }

        result.setSuccessCount(success);
        result.setFailCount(fail);
        result.setFailures(failures);
        result.setSuccessProcNames(successProcNames);
        return result;
    }

    @Override
    public IPage<ProcInfo> listProcs(int pageNum, int pageSize, String procName) {
        Page<ProcInfo> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ProcInfo> wrapper = new LambdaQueryWrapper<>();
        if (procName != null && !procName.trim().isEmpty()) {
            wrapper.like(ProcInfo::getProcName, procName.trim());
        }
        wrapper.orderByDesc(ProcInfo::getCreateTime);
        return procInfoMapper.selectPage(page, wrapper);
    }

    @Override
    public List<ProcTableDependency> listDependencies(String procName) {
        ProcInfo procInfo = getProcByName(procName);
        if (procInfo == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<ProcTableDependency> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProcTableDependency::getProcId, procInfo.getId());
        wrapper.orderByAsc(ProcTableDependency::getIsTempTable)
               .orderByAsc(ProcTableDependency::getSchemaName)
               .orderByAsc(ProcTableDependency::getTableName);
        return procTableDependencyMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteProc(String procName) {
        ProcInfo procInfo = getProcByName(procName);
        if (procInfo == null) {
            return false;
        }
        // 先删依赖表,再删存储过程
        LambdaQueryWrapper<ProcTableDependency> depWrapper = new LambdaQueryWrapper<>();
        depWrapper.eq(ProcTableDependency::getProcId, procInfo.getId());
        procTableDependencyMapper.delete(depWrapper);
        procInfoMapper.deleteById(procInfo.getId());
        return true;
    }

    /**
     * 保存解析结果到数据库(先删后插)
     */
    private ProcInfo saveMetadata(ProcMetadata metadata) {
        // 先删除旧记录
        ProcInfo existing = getProcByName(metadata.getProcName());
        if (existing != null) {
            LambdaQueryWrapper<ProcTableDependency> depWrapper = new LambdaQueryWrapper<>();
            depWrapper.eq(ProcTableDependency::getProcId, existing.getId());
            procTableDependencyMapper.delete(depWrapper);
            procInfoMapper.deleteById(existing.getId());
        }

        // 插入 proc_info
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName(metadata.getProcName());
        procInfo.setProcFileName(metadata.getProcFileName());
        procInfo.setFilePath(metadata.getFilePath());
        procInfo.setProcDesc(metadata.getProcDesc());
        // update_target: 优先使用头部注释声明的值;若头部无注释,用 SQL 推断的更新目标回填
        String updateTarget = metadata.getUpdateTarget();
        if ((updateTarget == null || updateTarget.isEmpty())
                && !metadata.getInferredUpdateTargets().isEmpty()) {
            updateTarget = metadata.getInferredUpdateTargets().values().stream()
                    .map(ProcMetadata.TableRef::getFullTableName)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
        procInfo.setUpdateTarget(updateTarget);
        procInfo.setTableCount(metadata.getDependencies().size());
        procInfo.setTempTableCount(metadata.getTempTables().size());
        procInfo.setExtractTime(metadata.getExtractTime());
        procInfo.setCreateTime(LocalDateTime.now());
        procInfoMapper.insert(procInfo);

        // 插入 proc_table_dependency(依赖表 + 临时表)
        LocalDateTime now = LocalDateTime.now();
        List<ProcTableDependency> depList = new ArrayList<>();

        // 真实依赖表
        for (ProcMetadata.TableRef ref : metadata.getDependencies()) {
            ProcTableDependency dep = toDependency(procInfo, ref, 0, now);
            depList.add(dep);
        }

        // 临时表
        for (ProcMetadata.TableRef ref : metadata.getTempTables()) {
            ProcTableDependency dep = toDependency(procInfo, ref, 1, now);
            depList.add(dep);
        }

        // 批量插入
        for (ProcTableDependency dep : depList) {
            procTableDependencyMapper.insert(dep);
        }

        return procInfo;
    }

    /**
     * 转换 TableRef 为 ProcTableDependency
     */
    private ProcTableDependency toDependency(ProcInfo procInfo, ProcMetadata.TableRef ref, int isTempTable, LocalDateTime now) {
        ProcTableDependency dep = new ProcTableDependency();
        dep.setProcId(procInfo.getId());
        dep.setProcName(procInfo.getProcName());
        dep.setSchemaName(ref.getSchemaName());
        dep.setSchemaVarSrc(ref.getSchemaVarSrc());
        dep.setTableName(ref.getTableName());
        dep.setFullTableName(ref.getFullTableName());
        dep.setOperationType(ref.getOperationType());
        dep.setIsTempTable(isTempTable);
        dep.setExtractTime(now);
        return dep;
    }

    /**
     * 按名称查询存储过程
     */
    private ProcInfo getProcByName(String procName) {
        LambdaQueryWrapper<ProcInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProcInfo::getProcName, procName);
        return procInfoMapper.selectOne(wrapper);
    }
}
