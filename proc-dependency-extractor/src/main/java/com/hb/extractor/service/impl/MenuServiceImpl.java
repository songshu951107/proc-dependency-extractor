package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.dto.MenuVO;
import com.hb.extractor.entity.SysMenu;
import com.hb.extractor.entity.SysRole;
import com.hb.extractor.entity.SysRoleMenu;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysMenuMapper;
import com.hb.extractor.mapper.SysRoleMapper;
import com.hb.extractor.mapper.SysRoleMenuMapper;
import com.hb.extractor.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public List<MenuVO> menusOfRole(Long roleId) {
        List<Long> menuIds = roleMenuMapper.selectList(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
        return buildTree(menuIds);
    }

    @Override
    public List<MenuVO> menuTree() {
        List<SysMenu> all = menuMapper.selectList(null);
        List<Long> ids = all.stream().map(SysMenu::getId).collect(Collectors.toList());
        return buildTree(ids);
    }

    @Override
    public String roleCodeOf(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw new BusinessException(400, "角色不存在");
        return role.getRoleCode();
    }

    @Override
    @Transactional
    public void assignMenus(Long roleId, List<Long> menuIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) throw new BusinessException(400, "角色不存在");
        // 「系统管理」分组（menu_code=system 及其子树）为管理员专属，禁止分配给非 ADMIN 角色
        if (!"ADMIN".equals(role.getRoleCode()) && menuIds != null) {
            Set<Long> adminOnly = adminOnlyMenuIds();
            for (Long menuId : menuIds) {
                if (adminOnly.contains(menuId)) {
                    throw new BusinessException(403, "「系统管理」菜单仅可分配给管理员角色");
                }
            }
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds == null) return;
        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }
    }

    /** 「系统管理」分组（menu_code=system）及其所有后代的菜单 id */
    private Set<Long> adminOnlyMenuIds() {
        List<SysMenu> all = menuMapper.selectList(null);
        Set<Long> ids = new HashSet<>();
        Long rootId = all.stream().filter(m -> "system".equals(m.getMenuCode()))
                .map(SysMenu::getId).findFirst().orElse(null);
        if (rootId == null) return ids;
        ids.add(rootId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SysMenu m : all) {
                if (m.getParentId() != null && ids.contains(m.getParentId()) && ids.add(m.getId())) changed = true;
            }
        }
        return ids;
    }

    private List<MenuVO> buildTree(List<Long> includeIds) {
        List<SysMenu> all = menuMapper.selectList(null);
        Map<Long, MenuVO> voMap = new LinkedHashMap<>();
        for (SysMenu m : all) {
            if (!includeIds.contains(m.getId())) continue;
            MenuVO vo = new MenuVO();
            vo.setId(m.getId()); vo.setMenuCode(m.getMenuCode()); vo.setMenuName(m.getMenuName());
            vo.setPath(m.getPath()); vo.setIcon(m.getIcon()); vo.setParentId(m.getParentId());
            vo.setSortOrder(m.getSortOrder()); vo.setType(m.getType());
            voMap.put(m.getId(), vo);
        }
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : voMap.values()) {
            if (vo.getParentId() != null && vo.getParentId() != 0 && voMap.containsKey(vo.getParentId())) {
                voMap.get(vo.getParentId()).getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        Comparator<MenuVO> cmp = Comparator.comparing(MenuVO::getSortOrder, Comparator.nullsLast(Integer::compareTo));
        roots.sort(cmp);
        voMap.values().forEach(v -> v.getChildren().sort(cmp));
        return roots;
    }
}