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
                ? "已替换 " + saved.getFileName() + "，需重新离线转换"
                : "已上传 " + saved.getFileName());
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
