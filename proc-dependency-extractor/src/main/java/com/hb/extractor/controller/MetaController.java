package com.hb.extractor.controller;

import com.hb.extractor.dto.Result;
import com.hb.extractor.entity.TableColumnMeta;
import com.hb.extractor.service.TableColumnMetaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物理表字段元数据 Controller
 *
 * 提供从 xlsx 导入字段元数据 + 查询接口。
 * 导入后 DdlParser 会优先从数据库读取，替代旧的 _ddl.TXT 文件方案。
 */
@Slf4j
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Autowired
    private TableColumnMetaService tableColumnMetaService;

    @Value("${extractor.ddl.excel-path:d:/HB/work_zuoye/jiaoben/columns0818.xlsx}")
    private String defaultExcelPath;

    /**
     * 从 xlsx 导入物理表字段元数据（幂等：导入前清空旧数据）
     * POST /api/meta/columns/import?path=xxx
     * 不传 path 则使用配置中的默认路径
     */
    @PostMapping("/columns/import")
    public Result<TableColumnMetaService.ImportResult> importColumns(
            @RequestParam(value = "path", required = false) String path) {
        String xlsxPath = (path == null || path.isBlank()) ? defaultExcelPath : path;
        try {
            log.info("开始导入物理表字段元数据: {}", xlsxPath);
            TableColumnMetaService.ImportResult r = tableColumnMetaService.importFromExcel(xlsxPath);
            return Result.success(r);
        } catch (Exception e) {
            log.error("导入物理表字段元数据失败: {}", xlsxPath, e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 按表短名查询字段列表
     * GET /api/meta/columns?table=c_agt_deposit_acct_inf
     */
    @GetMapping("/columns")
    public Result<List<TableColumnMeta>> getColumns(@RequestParam("table") String tableShort) {
        return Result.success(tableColumnMetaService.getColumns(tableShort));
    }

    /**
     * 按表短名查询生成的 CREATE TABLE 语句
     * GET /api/meta/ddl?table=c_agt_deposit_acct_inf
     */
    @GetMapping("/ddl")
    public Result<Map<String, String>> getDdl(@RequestParam("table") String tableShort) {
        Map<String, String> data = new HashMap<>();
        data.put("table", tableShort);
        data.put("ddl", tableColumnMetaService.getDdlText(tableShort));
        return Result.success(data);
    }

    /**
     * 元数据统计
     * GET /api/meta/stats
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(tableColumnMetaService.stats());
    }
}
