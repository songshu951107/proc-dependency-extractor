-- 脚本转换可视化菜单
-- 新增顶级菜单「脚本转换」（path=/transform），id=13 取既有最大 id+1，
-- sort_order=70 介于顶级 analytics(60) 与 system(90) 之间。
-- 幂等：menu_code 唯一键冲突时仅更新 menu_name。
INSERT INTO sys_menu (id, menu_code, menu_name, path, icon, parent_id, sort_order, type)
VALUES (13, 'transform', '脚本转换', '/transform', 'Refresh', 0, 70, 'MENU')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

-- 部署说明：菜单记录落库后，需在「角色管理」界面给目标角色勾选「脚本转换」菜单，
-- 否则普通用户看不到 / 访问不了该页面。
