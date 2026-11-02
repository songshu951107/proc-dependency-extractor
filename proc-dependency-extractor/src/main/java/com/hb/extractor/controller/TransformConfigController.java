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
     * 批量导入预览：上传 F/O 两侧 DDL 与 T 层加工脚本（fScript/oScript 必填，DDL 可选），
     * 返回推导的 column_mapping 与未匹配清单，不落库。
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importPreview(
            @RequestParam("fScriptFile") MultipartFile fScriptFile,
            @RequestParam("oScriptFile") MultipartFile oScriptFile,
            @RequestParam(value = "fDdlFile", required = false) MultipartFile fDdlFile,
            @RequestParam(value = "oDdlFile", required = false) MultipartFile oDdlFile,
            @RequestParam("oldTable") String oldTable,
            @RequestParam("newTable") String newTable,
            @RequestParam(value = "oldDb", required = false) String oldDb,
            @RequestParam(value = "newDb", required = false) String newDb) throws Exception {

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("oldTable", oldTable);
        req.put("newTable", newTable);
        req.put("oldDb", oldDb);
        req.put("newDb", newDb);
        req.put("fScript", text(fScriptFile, "F 表加工脚本"));
        req.put("oScript", text(oScriptFile, "O 表加工脚本"));
        if (fDdlFile != null && !fDdlFile.isEmpty()) {
            req.put("fDdl", text(fDdlFile, "F 表 DDL"));
        }
        if (oDdlFile != null && !oDdlFile.isEmpty()) {
            req.put("oDdl", text(oDdlFile, "O 表 DDL"));
        }
        return Result.success(configService.importPreview(req));
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

    private String text(MultipartFile file, String label) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(label + "为空");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length > 10L * 1024 * 1024) {
            throw new IllegalArgumentException(label + "超过 10MB 上限");
        }
        return TransformConfigServiceImpl.decode(bytes);   // UTF-8 优先，失败回退 GBK
    }
}
