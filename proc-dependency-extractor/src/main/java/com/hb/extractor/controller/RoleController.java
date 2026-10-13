package com.hb.extractor.controller;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.dto.MenuVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.RoleMenuRequest;
import com.hb.extractor.entity.SysRole;
import com.hb.extractor.service.MenuService;
import com.hb.extractor.service.RoleService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RequireAdmin
@RestController
@RequestMapping("/api/roles")
public class RoleController {
    private final RoleService roleService;
    private final MenuService menuService;
    public RoleController(RoleService roleService, MenuService menuService) {
        this.roleService = roleService; this.menuService = menuService;
    }

    @GetMapping public Result<List<SysRole>> list() { return Result.success(roleService.listAll()); }
    @PostMapping public Result<Void> create(@RequestBody SysRole role) { roleService.create(role); return Result.success(); }
    @PutMapping public Result<Void> update(@RequestBody SysRole role) { roleService.update(role); return Result.success(); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id) { roleService.delete(id); return Result.success(); }

    @GetMapping("/{id}/menus")
    public Result<List<Long>> roleMenus(@PathVariable Long id) {
        return Result.success(menuService.menusOfRole(id).stream()
                .flatMap(m -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(m.getId()),
                        m.getChildren().stream().map(MenuVO::getId)))
                .collect(java.util.stream.Collectors.toList()));
    }

    @PutMapping("/{id}/menus")
    public Result<Void> assignMenus(@PathVariable Long id, @RequestBody RoleMenuRequest req) {
        menuService.assignMenus(id, req.getMenuIds()); return Result.success();
    }
}