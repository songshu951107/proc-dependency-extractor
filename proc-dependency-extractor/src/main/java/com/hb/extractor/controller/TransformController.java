package com.hb.extractor.controller;

import com.hb.extractor.dto.Result;
import com.hb.extractor.service.TransformService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 脚本转换可视化接口（只读展示 + 上传，规格 §6） */
@Slf4j
@RestController
@RequestMapping("/api/transform")
@RequiredArgsConstructor
public class TransformController {

    private final TransformService transformService;

    @GetMapping("/scripts")
    public Result<List<?>> scripts() {
        return Result.success(transformService.listScripts());
    }

    @GetMapping("/scripts/{name}/report")
    public Result<Map<String, Object>> report(@PathVariable String name) {
        return Result.success(transformService.getReport(name));
    }

    @GetMapping("/scripts/{name}/file")
    public Result<String> file(@PathVariable String name,
                               @RequestParam(defaultValue = "source") String ver) {
        return Result.success(transformService.readFile(name, ver));
    }

    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        Path saved = transformService.upload(file);
        boolean replaced = transformService.listScripts().stream()
                .anyMatch(v -> v.getName().equals(saved.getFileName().toString())
                        && !"PENDING".equals(v.getStatus()));
        return Result.success(replaced
                ? "已替换 " + saved.getFileName() + "，可点击「重新转换」"
                : "已上传 " + saved.getFileName() + "，可点击「开始转换」");
    }

    @PostMapping("/scripts/{name}/convert")
    public Result<String> convert(@PathVariable String name) {
        transformService.convert(name);
        return Result.success(convertMessage(name));
    }

    /** 转换结果提示：带出转换点数；0 处时明示「脚本未引用映射表」避免误判为未生效 */
    private String convertMessage(String name) {
        try {
            Map<String, Object> r = transformService.getReport(name);
            Map<String, Object> sum = (Map<String, Object>) r.get("summary");
            int total = sum == null ? 0 : ((Number) sum.getOrDefault("total", 0)).intValue();
            Map<String, Object> cfg = (Map<String, Object>) r.get("config");
            if (total == 0) {
                return "转换完成 " + name + "：0 处修改——脚本未引用映射中的 F 表（"
                        + (cfg == null ? "见 transform_config.json"
                        : cfg.get("old_db") + "." + cfg.get("old_table"))
                        + "），转换结果与原文一致";
            }
            return "转换完成 " + name + "：" + total + " 处转换";
        } catch (Exception e) {
            return "转换完成 " + name;
        }
    }

    @DeleteMapping("/scripts/{name}")
    public Result<String> delete(@PathVariable String name) {
        transformService.delete(name);
        return Result.success("已删除 " + name);
    }

    @GetMapping("/scripts/{name}/download")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable String name, @RequestParam String type) {
        Path p = transformService.resolveDownload(name, type);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(p));
    }
}
