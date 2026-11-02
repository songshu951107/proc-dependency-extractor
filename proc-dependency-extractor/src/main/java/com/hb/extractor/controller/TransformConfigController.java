package com.hb.extractor.controller;

import com.hb.extractor.dto.Result;
import com.hb.extractor.service.TransformConfigService;
import com.hb.extractor.service.impl.TransformConfigServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 映射配置管理接口：表对 CRUD、批量导入推导预览、确认落库（transform_config.json） */
@RestController
@RequestMapping("/api/transform/config")
@RequiredArgsConstructor
public class TransformConfigController {

    private final TransformConfigService configService;

    @GetMapping
    public Result<Map<String, Object>> list() {
        return Result.success(configService.loadAll());
    }

    /** 新增/编辑单个表对（old_table 为主键，column_mapping 可空 = 仅表名替换） */
    @PostMapping
    public Result<String> save(@RequestBody Map<String, Object> entry) {
        configService.saveTable(entry);
        return Result.success("已保存表对 " + entry.get("old_table"));
    }

    @DeleteMapping("/{oldTable}")
    public Result<String> delete(@PathVariable String oldTable) {
        configService.deleteTable(oldTable);
        return Result.success("已删除表对 " + oldTable);
    }

    /**
     * 批量导入预览：一次上传多个文件（顺序随意），后端按内容自动识别——
     * INSERT INTO 旧表 → F 加工脚本、INSERT INTO 新表 → O 加工脚本、
     * CREATE TABLE → 对应侧建表 DDL。返回推导的 column_mapping 与未匹配清单，不落库。
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importPreview(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("oldTable") String oldTable,
            @RequestParam("newTable") String newTable,
            @RequestParam(value = "oldDb", required = false) String oldDb,
            @RequestParam(value = "newDb", required = false) String newDb) throws Exception {

        List<Map<String, String>> fs = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            byte[] bytes = f.getBytes();
            if (bytes.length > 10L * 1024 * 1024) {
                throw new IllegalArgumentException(
                        "文件超过 10MB 上限: " + f.getOriginalFilename());
            }
            fs.add(Map.of("name", f.getOriginalFilename() == null ? "" : f.getOriginalFilename(),
                    "content", TransformConfigServiceImpl.decode(bytes)));   // UTF-8 优先，失败回退 GBK
        }
        return Result.success(configService.importPreview(fs, oldTable, newTable, oldDb, newDb));
    }

    /** 确认导入：把预览确认后的表对（可多对批量）合并写入 transform_config.json */
    @PostMapping("/apply")
    public Result<String> apply(@RequestBody List<Map<String, Object>> entries) {
        configService.applyTables(entries);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            names.add(String.valueOf(e.get("old_table")));
        }
        return Result.success("已导入表对: " + String.join(", ", names));
    }
}
