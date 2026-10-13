package com.hb.extractor.service;

import com.hb.extractor.dto.MenuVO;

import java.util.List;

public interface MenuService {
    List<MenuVO> menusOfRole(Long roleId);
    List<MenuVO> menuTree();
    String roleCodeOf(Long roleId);
    void assignMenus(Long roleId, List<Long> menuIds);
}