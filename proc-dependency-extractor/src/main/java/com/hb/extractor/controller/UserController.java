package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RequireAdmin
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    public UserController(UserService userService) { this.userService = userService; }

    @GetMapping
    public Result<IPage<SysUser>> page(@RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String keyword) {
        return Result.success(userService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    public Result<Void> create(@RequestBody UserSaveRequest request) {
        userService.create(request); return Result.success();
    }

    @PutMapping
    public Result<Void> update(@RequestBody UserSaveRequest request) {
        userService.update(request); return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id); return Result.success();
    }

    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id); return Result.success();
    }

    @PostMapping("/import")
    public Result<ImportResultDTO> importExcel(@RequestParam("file") MultipartFile file) {
        return Result.success(userService.importExcel(file));
    }
}