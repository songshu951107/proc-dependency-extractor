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
    /** 文件名主干黑名单前缀（不区分大小写）：F/O 层自身加工脚本不上架、不转换 */
    private final List<String> blacklist;
    /** 网页触发转换所需：Python 可执行文件、引擎目录、单脚本超时秒数 */
    private final String python;
    private final Path engineDir;
    private final long convertTimeoutSeconds;
    private final ObjectMapper om = new ObjectMapper();

    public TransformServiceImpl(
            @Value("${transform.base-dir:./transforms}") String baseDir,
            @Value("${transform.name-blacklist:dwa_f,dwa_o}") String blacklist,
            @Value("${transform.python:python}") String python,
            @Value("${transform.engine-dir:.}") String engineDir,
            @Value("${transform.convert-timeout-seconds:120}") long convertTimeoutSeconds) {
        this.baseDir = Path.of(baseDir);
        this.blacklist = Arrays.stream(blacklist.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toLowerCase).toList();
        this.python = python;
        this.engineDir = Path.of(engineDir);
        this.convertTimeoutSeconds = convertTimeoutSeconds;
    }

    /** 黑名单前缀匹配（按去 .py 的文件名主干，不区分大小写） */
    private boolean blacklisted(String fileName) {
        String stem = fileName.endsWith(".py")
                ? fileName.substring(0, fileName.length() - 3) : fileName;
        String low = stem.toLowerCase();
        return blacklist.stream().anyMatch(low::startsWith);
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
                    if (blacklisted(p.getFileName().toString())) continue;
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
                    if (blacklisted(stem + ".py")) continue;
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
        if (blacklisted(name)) {
            throw new BusinessException("F/O 层自身加工脚本（"
                    + String.join("/", blacklist) + " 开头）无需转换，拒绝上传");
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

    @Override
    public void convert(String name) {
        if (!NAME_RE.matcher(name).matches() || !name.endsWith(".py")) {
            throw new BusinessException("非法脚本名: " + name);
        }
        if (blacklisted(name)) {
            throw new BusinessException("F/O 层自身加工脚本（"
                    + String.join("/", blacklist) + " 开头）无需转换");
        }
        Path src = inputDir().resolve(name);
        if (!Files.exists(src)) throw new BusinessException("input 中不存在该脚本: " + name);
        Path batch = engineDir.resolve("transform_batch.py");
        Path config = engineDir.resolve("transform_config.json");
        if (!Files.exists(batch)) {
            throw new BusinessException("转换引擎不存在: " + batch.toAbsolutePath()
                    + "（需部署 transform_batch.py / transform_table_mapping.py / transform_config.json）");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    python, batch.toAbsolutePath().toString(),
                    "--input", inputDir().toAbsolutePath().toString(),
                    "--output", outputDir().toAbsolutePath().toString(),
                    "--config", config.toAbsolutePath().toString(),
                    "--only", name.replace(".py", ""))
                    .redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process proc = pb.start();
            // 先读完输出再等待，避免管道缓冲区写满导致死锁
            String out;
            try (var is = proc.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(convertTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new BusinessException("转换超时（>" + convertTimeoutSeconds + "s）");
            }
            if (proc.exitValue() != 0) {
                throw new BusinessException("转换失败: " + tail(out));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("转换执行失败: " + e.getMessage());
        }
    }

    /** 取子进程输出末尾作为错误信息（截 400 字符，压平换行） */
    private static String tail(String out) {
        if (out == null || out.isBlank()) return "无输出";
        String s = out.strip().replaceAll("\\s+", " ");
        return s.length() > 400 ? "..." + s.substring(s.length() - 400) : s;
    }

    @Override
    public void delete(String name) {
        if (!NAME_RE.matcher(name).matches() || !name.endsWith(".py")) {
            throw new BusinessException("非法脚本名: " + name);
        }
        try {
            Files.deleteIfExists(inputDir().resolve(name));
            Path out = safe(name.replace(".py", ""));
            if (Files.isDirectory(out)) {
                try (var walk = Files.walk(out)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception e) {
            throw new BusinessException("删除失败: " + e.getMessage());
        }
    }
}
