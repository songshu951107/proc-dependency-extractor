CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password VARCHAR(100) NOT NULL,
  real_name VARCHAR(64),
  role_id BIGINT NOT NULL,
  dept VARCHAR(64),
  email VARCHAR(128),
  phone VARCHAR(32),
  status TINYINT NOT NULL DEFAULT 1,
  must_change_pwd TINYINT NOT NULL DEFAULT 0,
  last_login_time DATETIME NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code VARCHAR(32) NOT NULL,
  role_name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_code VARCHAR(64) NOT NULL,
  menu_name VARCHAR(64) NOT NULL,
  path VARCHAR(128),
  icon VARCHAR(64),
  parent_id BIGINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  type VARCHAR(16) NOT NULL DEFAULT 'MENU',
  UNIQUE KEY uk_menu_code (menu_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  UNIQUE KEY uk_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_login_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  username VARCHAR(64),
  login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  ip VARCHAR(64),
  user_agent VARCHAR(255),
  success TINYINT NOT NULL DEFAULT 1,
  fail_reason VARCHAR(255),
  KEY idx_login_time (login_time),
  KEY idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_page_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  page_path VARCHAR(128),
  page_name VARCHAR(64),
  visit_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_visit_time (visit_time),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 角色
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
  (1, 'ADMIN', '管理员', '系统管理员，拥有全部权限'),
  (2, 'USER',  '普通用户', '业务用户，仅可访问业务页面')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

-- 菜单（parent_id=0 为顶级）
INSERT INTO sys_menu (id, menu_code, menu_name, path, icon, parent_id, sort_order, type) VALUES
  (1, 'upload',         '导入脚本',     '/',              'Upload',     0, 10, 'MENU'),
  (2, 'treasure',       '开发宝藏',     NULL,             'Collection', 0, 20, 'GROUP'),
  (3, 'dict',           '数据字典',     '/dict',          'Notebook',   2, 21, 'MENU'),
  (4, 'procs',          '存储过程',     '/procs',         'List',       2, 22, 'MENU'),
  (5, 'reportlib',      '报表库',       '/reportlib',     'Document',   2, 23, 'MENU'),
  (6, 'impact',         '影响分析',     '/impact',        'Aim',        0, 30, 'MENU'),
  (7, 'report-impact',  '报表影响分析', '/report-impact', 'Document',   0, 40, 'MENU'),
  (8, 'schema-impact',  '集市批量影响', '/schema-impact', 'Aim',        0, 50, 'MENU'),
  (9, 'system',         '系统管理',     NULL,             'Setting',    0, 90, 'GROUP'),
  (10,'users',          '用户管理',     '/users',         'User',       9, 91, 'MENU'),
  (11,'roles',          '角色管理',     '/roles',         'Avatar',     9, 92, 'MENU'),
  (12,'analytics',      '使用分析',     '/analytics',     'DataLine',   9, 93, 'MENU')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

-- ADMIN 全部菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu;
-- USER 业务菜单（1-8，排除系统管理 9-12）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT 2, id FROM sys_menu WHERE id BETWEEN 1 AND 8;

-- 默认管理员账号 admin / 1234.Com（BCrypt）
INSERT INTO sys_user (username, password, real_name, role_id, status, must_change_pwd) VALUES
  ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa', '系统管理员', 1, 1, 0)
ON DUPLICATE KEY UPDATE username = VALUES(username);