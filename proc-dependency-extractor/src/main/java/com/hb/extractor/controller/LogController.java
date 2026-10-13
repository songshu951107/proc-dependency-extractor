package com.hb.extractor.controller;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.PageViewRequest;
import com.hb.extractor.dto.Result;
import com.hb.extractor.entity.SysPageLog;
import com.hb.extractor.mapper.SysPageLogMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final SysPageLogMapper pageLogMapper;
    public LogController(SysPageLogMapper pageLogMapper) { this.pageLogMapper = pageLogMapper; }

    @PostMapping("/page-view")
    public Result<Void> pageView(@RequestBody PageViewRequest req) {
        SysPageLog log = new SysPageLog();
        log.setUserId(UserContext.get().getUserId());
        log.setPagePath(req.getPagePath());
        log.setPageName(req.getPageName());
        pageLogMapper.insert(log);
        return Result.success();
    }
}