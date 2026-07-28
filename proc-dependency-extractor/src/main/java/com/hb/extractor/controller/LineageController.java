package com.hb.extractor.controller;

import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.SqlBlockVO;
import com.hb.extractor.entity.TableLineageFilter;
import com.hb.extractor.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 血缘查询 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    @Autowired
    private LineageService lineageService;

    /**
     * 查询某存储过程的完整血缘图
     */
    @GetMapping("/{procName}")
    public Result<LineageGraphVO> getLineage(@PathVariable String procName) {
        LineageGraphVO vo = lineageService.getLineageGraph(procName);
        if (vo == null) {
            return Result.error(404, "存储过程不存在");
        }
        return Result.success(vo);
    }

    /**
     * 查询某存储过程的所有血缘条件（JOIN/WHERE）
     */
    @GetMapping("/{procName}/filter")
    public Result<List<TableLineageFilter>> getFilters(@PathVariable String procName) {
        List<TableLineageFilter> filters = lineageService.getFilters(procName);
        return Result.success(filters);
    }

    /**
     * 递归查询某表的上游血缘
     */
    @GetMapping("/table/{tableName}/upstream")
    public Result<LineageGraphVO> upstream(@PathVariable String tableName,
                                            @RequestParam(required = false) Integer maxDepth) {
        if (maxDepth != null && maxDepth > 10) {
            return Result.error(400, "maxDepth 不能超过 10");
        }
        LineageGraphVO vo = lineageService.traceUpstream(tableName, maxDepth);
        return Result.success(vo);
    }

    /**
     * 递归查询某表的下游血缘
     */
    @GetMapping("/table/{tableName}/downstream")
    public Result<LineageGraphVO> downstream(@PathVariable String tableName,
                                              @RequestParam(required = false) Integer maxDepth) {
        if (maxDepth != null && maxDepth > 10) {
            return Result.error(400, "maxDepth 不能超过 10");
        }
        LineageGraphVO vo = lineageService.traceDownstream(tableName, maxDepth);
        return Result.success(vo);
    }

    /**
     * 查询字段映射（含递归追溯）
     * GET /api/lineage/{procName}/field-mapping?tableName=X&recursive=true
     */
    @GetMapping("/{procName}/field-mapping")
    public Result<FieldMappingVO> fieldMapping(@PathVariable String procName,
                                                @RequestParam String tableName,
                                                @RequestParam(defaultValue = "false") boolean recursive) {
        FieldMappingVO vo = lineageService.getFieldMapping(procName, tableName, recursive);
        if (vo == null) {
            return Result.error(404, "存储过程不存在");
        }
        return Result.success(vo);
    }

    /**
     * 查询 SQL 块详情
     * GET /api/lineage/{procName}/sql-blocks
     */
    @GetMapping("/{procName}/sql-blocks")
    public Result<List<SqlBlockVO>> sqlBlocks(@PathVariable String procName) {
        List<SqlBlockVO> list = lineageService.getSqlBlocks(procName);
        return Result.success(list);
    }
}
