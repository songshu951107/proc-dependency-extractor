package com.hb.extractor.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.TransformScriptVO;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.service.TransformService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TransformServiceImpl implements TransformService {

    /** 名称白名单：字母数字下划线点横线，不以点开头，防路径穿越（规格 §6） */
    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]*");
    private static final long MAX_UPLOAD = 10L * 1024 * 1024;

    private final Path baseDir;
    private final ObjectMapper om = new ObjectMapper();

    public TransformServiceImpl(
            @Value("${transform.base-dir:./transforms}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    private Path inputDir() { return baseDir.resolve("input"); }
    private Path outputDir() { return baseDir.resolve("output"); }

    /** 校验名称白名单并解析 output/<脚本名>/... 路径，防路径穿越（规格 §6） */
    private Path safe(String name, String... more) {
        if (!NAME_RE.matcher(name).matches()) {
            throw new BusinessException("非法脚本名: " + name);
        }
        Path p = outputDir().resolve(name);
        for (String m : more) p = p.resolve(m);
        return p;
    }

    @Override
    public List<TransformScriptVO> listScripts() {
        Map<String, TransformScriptVO> map = new TreeMap<>();
        if (Files.isDirectory(inputDir())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(inputDir(), "*.py")) {
                for (Path p : ds) {
                    TransformScriptVO vo = new TransformScriptVO();
                    vo.setName(p.getFileName().toString());
                    vo.setStatus("PENDING");
                    map.put(vo.getName(), vo);
                }
            } catch (Exception e) {
                throw new BusinessException("扫描 input 目录失败: " + e.getMessage());
            }
        }
        if (Files.isDirectory(outputDir())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outputDir())) {
                for (Path d : ds) {
                    if (!Files.isDirectory(d)) continue;
                    String stem = d.getFileName().toString();
                    Path report = d.resolve("report.json");
                    TransformScriptVO vo = map.computeIfAbsent(stem + ".py", n -> {
                        TransformScriptVO v = new TransformScriptVO();
                        v.setName(n);
                        return v;
                    });
                    if (!Files.exists(report)) {
                        vo.setStatus("FAILED");
                        vo.setErrorMsg("output 缺少 report.json");
                        continue;
                    }
                    try {
                        Map<String, Object> r = om.readValue(report.toFile(), Map.class);
                        vo.setPyCompile((String) r.get("py_compile"));
                        vo.setErrorMsg((String) r.get("error"));
                        vo.setConvertedAt((String) r.get("converted_at"));
                        Map<String, Object> cfg = (Map<String, Object>) r.get("config");
                        if (cfg != null) {
                            vo.setOldTable(cfg.get("old_db") + "." + cfg.get("old_table"));
                            vo.setNewTable(cfg.get("new_db") + "." + cfg.get("new_table"));
                        }
                        Map<String, Object> sum = (Map<String, Object>) r.get("summary");
                        vo.setTotalEdits(sum == null ? 0 : ((Number) sum.getOrDefault("total", 0)).intValue());
                        vo.setResidueCount(sum == null ? 0 : ((Number) sum.getOrDefault("residues", 0)).intValue());
                        boolean bad = "FAIL".equals(vo.getPyCompile())
                                || (vo.getErrorMsg() != null && !vo.getErrorMsg().isBlank());
                        vo.setStatus(bad ? "FAILED" : "CONVERTED");
                        // 源被替换：input mtime 晚于转换时的 source.py 快照
                        Path input = inputDir().resolve(stem + ".py");
                        Path snapshot = d.resolve("source.py");
                        if (Files.exists(input) && Files.exists(snapshot)
                                && input.toFile().lastModified() > snapshot.toFile().lastModified()) {
                            vo.setStatus("STALE");
                        }
                    } catch (Exception e) {
                        vo.setStatus("FAILED");
                        vo.setErrorMsg("report.json 解析失败: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                throw new BusinessException("扫描 output 目录失败: " + e.getMessage());
            }
        }
        return new ArrayList<>(map.values());
    }

    @Override
    public Map<String, Object> getReport(String name) {
        Path p = safe(name.replace(".py", ""), "report.json");
        if (!Files.exists(p)) throw new BusinessException("该脚本尚未转换");
        try { return om.readValue(p.toFile(), Map.class); }
        catch (Exception e) { throw new BusinessException("report.json 解析失败"); }
    }

    @Override
    public String readFile(String name, String ver) {
        String stem = name.replace(".py", "");
        Path p = switch (ver) {
            case "source" -> safe(stem, "source.py");
            case "converted" -> safe(stem, "converted.py");
            default -> throw new BusinessException("ver 仅支持 source|converted");
        };
        if (!Files.exists(p)) throw new BusinessException("文件不存在: " + ver);
        try { return Files.readString(p); }
        catch (Exception e) { throw new BusinessException("读取失败"); }
    }

    @Override
    public Path upload(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.endsWith(".py") || !NAME_RE.matcher(name).matches()) {
            throw new BusinessException("仅支持上传 .py 脚本，且文件名限字母数字下划线点横线");
        }
        try {
            if (file.getSize() > MAX_UPLOAD) throw new BusinessException("文件超过 10MB 上限");
            Files.createDirectories(inputDir());
            Path dst = inputDir().resolve(name);
            file.transferTo(dst.toAbsolutePath());
            return dst;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("保存上传文件失败: " + e.getMessage()); }
    }

    @Override
    public Path resolveDownload(String name, String type) {
        String stem = name.replace(".py", "");
        Path p = switch (type) {
            case "converted" -> safe(stem, "converted.py");
            case "md" -> safe(stem, "report.md");
            case "html" -> safe(stem, "diff.html");
            default -> throw new BusinessException("type 仅支持 converted|md|html");
        };
        if (!Files.exists(p)) throw new BusinessException("产物不存在: " + type);
        return p;
    }
}
