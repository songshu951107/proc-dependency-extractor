package com.hb.extractor.controller;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.dto.MenuVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.MenuService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/menus")
public class MenuController {
    private final MenuService menuService;
    public MenuController(MenuService menuService) { this.menuService = menuService; }

    @RequireAdmin
    @GetMapping("/tree")
    public Result<List<MenuVO>> tree() { return Result.success(menuService.menuTree()); }
}