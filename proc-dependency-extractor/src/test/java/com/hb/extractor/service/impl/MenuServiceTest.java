package com.hb.extractor.service.impl;

import com.hb.extractor.dto.MenuVO;
import com.hb.extractor.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MenuServiceTest {

    @Autowired MenuService menuService;

    @Test
    void adminHasSystemGroup() {
        List<MenuVO> menus = menuService.menusOfRole(1L);
        assertTrue(menus.stream().anyMatch(m -> "系统管理".equals(m.getMenuName())));
    }

    @Test
    void userHasNoSystemGroup() {
        List<MenuVO> menus = menuService.menusOfRole(2L);
        assertFalse(menus.stream().anyMatch(m -> "系统管理".equals(m.getMenuName())));
        // 普通用户仍可见「开发宝藏」分组及其子菜单
        MenuVO treasure = menus.stream().filter(m -> "开发宝藏".equals(m.getMenuName()))
                .findFirst().orElseThrow();
        assertFalse(treasure.getChildren().isEmpty());
    }
}