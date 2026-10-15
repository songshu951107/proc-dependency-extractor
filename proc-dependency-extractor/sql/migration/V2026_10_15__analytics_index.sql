-- 使用分析页性能修复：sys_login_log 缺 user_id 索引，
-- 导致 userActivity 按用户聚合（GROUP BY user_id / 相关子查询）无法走索引，需全表扫描。
-- sys_page_log 建表时已有 idx_user_id，无需重复添加。
-- 说明：本文件为一次性增量迁移，执行一遍即可（重复执行会因索引已存在而报错）。
ALTER TABLE sys_login_log ADD KEY idx_user_id (user_id);
