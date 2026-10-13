# 用户与权限模块设计

日期：2026-10-13
状态：待审查

## 功能目标

为「影响分析工具」新增用户与权限体系，包含六项能力：①登录页 ②用户管理（手动新增 + Excel 导入）③角色（管理员 / 普通用户）④密码重置 ⑤菜单-角色关联 ⑥用户使用情况分析。权限粒度为**页面级**（角色 ↔ 菜单/页面映射，页面内功能不再细分）。

## 关键技术决策

- **鉴权实现 = 方案一**：自定义 `HandlerInterceptor` 拦截 `/api/**`（白名单放行）+ JWT（jjwt，前端存 localStorage，请求头 `Authorization: Bearer <token>`）+ BCrypt（仅引 `spring-security-crypto`）+ 自定义注解校验管理员接口。**不引入完整 Spring Security**。
- **前端所有接口统一走同一个 axios 实例**（`api/request.js`），由请求拦截器统一注入 token，业务接口无需单独改造。
- 现有业务接口原本匿名可访问；启用鉴权后白名单**仅放行登录接口**，其余接口一律要求携带有效 token。

## 数据模型（新增 6 张表，不改动现有表）

| 表 | 用途 | 关键字段 |
|---|---|---|
| `sys_user` | 用户账号 | id, username(唯一), password(BCrypt), real_name, role_id, dept, email, phone, status(1启用/0禁用), must_change_pwd, last_login_time, create_time, update_time |
| `sys_role` | 角色 | id, role_code(ADMIN/USER), role_name, description, create_time |
| `sys_menu` | 菜单/页面（存库可配置） | id, menu_code, menu_name, path(前端路由), icon, parent_id, sort_order, type(菜单/分组) |
| `sys_role_menu` | 角色↔菜单关联（页面级权限） | id, role_id, menu_id |
| `sys_login_log` | 登录日志 | id, user_id, username, login_time, ip, user_agent, success, fail_reason |
| `sys_page_log` | 页面访问埋点 | id, user_id, page_path, page_name, visit_time |

**初始化数据**：角色 `ADMIN`(管理员)、`USER`(普通用户)；管理员账号 `admin` / `Abc@123456`（BCrypt 入库，首次登录建议改密）；菜单初始 = 现有 7 个页面（导入脚本 / 数据字典 / 存储过程 / 报表库 / 影响分析 / 报表影响分析 / 集市批量影响）；ADMIN 拥有全部菜单，USER 拥有业务菜单（不含用户管理 / 角色管理 / 使用分析）。

## 后端

### 拦截与鉴权

拦截器 `AuthInterceptor` 处理 `/api/**`：
1. 命中白名单 → 放行；
2. 取 `Authorization: Bearer <token>`；缺失 / 非法 / 过期 → 返回 `401 Result`；
3. 解析出 userId / username / roleCode 写入 `UserContext`（ThreadLocal）；
4. 目标方法或类带 `@RequireAdmin` 且 roleCode ≠ ADMIN → 返回 `403 Result`；
5. 放行；`afterCompletion` 清理 ThreadLocal。

**白名单**：`/api/auth/login`、`/error`，以及非 `/api/**` 的静态资源与 SPA 转发路径（`/`、`/index.html`、`/assets/**`、`/favicon.ico`）。

**JWT 配置**（`application.yml`）：`jwt.secret`（HMAC 密钥）、`jwt.expire-hours`（默认 12 小时）。token 过期后返回 401，前端跳登录页重新登录。

### 新增 / 改动文件

| 层 | 组件 | 职责 |
|---|---|---|
| config | `WebMvcConfig`(改) | 注册 AuthInterceptor，配置路径与白名单 |
| config | `PasswordConfig`(新) | 暴露 `BCryptPasswordEncoder` Bean |
| interceptor | `AuthInterceptor` | token 校验、UserContext 注入、@RequireAdmin 校验 |
| util | `JwtUtil` | 签发/解析/校验 token（secret 与有效期读配置） |
| annotation | `@RequireAdmin` | 标注仅管理员可访问的方法/类 |
| context | `UserContext` | ThreadLocal 保存当前登录用户 |
| exception | `GlobalExceptionHandler`(新) | `@RestControllerAdvice`，统一把异常转为 `Result` |
| entity | 6 个实体 | SysUser / SysRole / SysMenu / SysRoleMenu / SysLoginLog / SysPageLog |
| mapper | 6 个 Mapper | BaseMapper；Analytics 用自定义统计 SQL |
| service | Auth / User / Role / Menu / Analytics Service | 接口 + impl |
| controller | Auth / User / Role / Menu / Analytics Controller | REST，统一返回 `Result<T>` |
| dto | LoginRequest/Response、ChangePwdRequest、UserDTO、ImportResultDTO 等 | 入参/出参 |
| pom | jjwt-api / jjwt-impl / jjwt-jackson、spring-security-crypto | 新增依赖 |

### REST 接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/auth/login` | 登录，返回 token + 用户信息 + 菜单，写登录日志 | 匿名 |
| POST | `/api/auth/logout` | 登出（前端清 token） | 登录 |
| GET | `/api/auth/me` | 当前用户信息 + 菜单 | 登录 |
| POST | `/api/auth/change-password` | 自助改密，清除强制改密标记 | 登录 |
| GET/POST/PUT/DELETE | `/api/users …` | 用户 CRUD、分页查询 | 管理员 |
| POST | `/api/users/import` | Excel 导入 | 管理员 |
| POST | `/api/users/{id}/reset-password` | 重置为默认密码并标记强制改密 | 管理员 |
| GET/POST/PUT/DELETE | `/api/roles …` | 角色 CRUD | 管理员 |
| PUT | `/api/roles/{id}/menus` | 保存角色↔菜单分配 | 管理员 |
| GET | `/api/menus/tree` | 完整菜单树（配置用） | 管理员 |
| GET | `/api/menus/mine` | 当前用户可见菜单 | 登录 |
| POST | `/api/logs/page-view` | 前端上报页面访问埋点 | 登录 |
| GET | `/api/analytics/*` | 使用分析统计 | 管理员 |

### 密码规则

- 默认密码统一为 `Abc@123456`。
- 管理员「重置密码」→ 置为默认密码并标记 `must_change_pwd=1`。
- 用户自助改密：新密码须 **≥8 位且含大写字母、小写字母、数字**；改密成功后清除强制改密标记。
- 登录返回 `mustChangePwd=true` 时，前端强制弹出不可关闭的改密对话框。

### Excel 导入

- 模板列：用户名 / 姓名 / 角色 / 部门 / 邮箱 / 手机 / 状态。
- 重复用户名行**跳过**，其余照常导入；结束后回显「成功 N / 跳过 M + 逐行原因」。
- 导入用户初始密码 = 默认密码，并标记强制改密。
- 文件格式错误、空文件、缺少必填列 → 整体报错；行级问题 → 跳过并记录原因。

## 前端

### 登录页（定稿设计）

登录页为居中卡片（`max-width:760px`，高 400px），左右分栏：左侧品牌区 66%、右侧表单区 34%。

- **左侧品牌区**：深色斜向渐变 `linear-gradient(150deg,#0f2027,#203a43,#2c5364)`，叠加蓝色 `#4facfe` / 紫色 `#7b5cff` 模糊光斑；半透明圆角 Logo 块 + 标题「影响分析工具」+ 一行平台能力副标题 + 版权。
- **右侧表单区**：**不含「欢迎回来」大标题**，仅一行浅色引导小字「请登录你的账号」；用户名/密码输入框（前置图标、聚焦蓝紫高亮）+ 整宽登录按钮（`linear-gradient(90deg,#4facfe,#7b5cff)`）；按钮下方为错误提示行，再下方为浅色静态提示「忘记密码请联系管理员」（**不做自助找回密码流程**）。表单随 1/3 列宽自然收窄（约 260px）。
- 回车可提交；登录成功跳首页；返回 `mustChangePwd=true` → 强制改密弹窗（沿用同配色）。

### 页面与路由

新增页面：`/login` 登录页、`/users` 用户管理、`/roles` 角色管理、`/analytics` 使用分析看板；新增改密对话框组件。

- **用户管理**：顶部「新增 / Excel 导入 / 下载模板 / 搜索」；表格列 = 用户名 / 姓名 / 角色 / 部门 / 状态 / 操作（编辑 · 重置密码 · 删除）；新增/编辑用对话框表单；导入后回显成功与跳过明细。
- **角色管理**：左角色列表 + 右菜单勾选树，勾选结果写 `sys_role_menu`；管理员默认全选，「系统管理」分组对普通用户不可见。
- **使用分析看板**（ECharts）：4 个 KPI 卡（今日登录次数 / 今日活跃用户 / 今日页面访问 / 今日登录失败）+ 近 30 天登录趋势折线 + 页面访问量 Top 柱状 + 用户活跃明细表；支持按日期范围筛选。

### 导航与守卫

- `App.vue` 侧边菜单由硬编码改为按 `/api/auth/me` 返回菜单动态渲染；新增「系统管理」分组（仅管理员：用户管理 / 角色管理 / 使用分析）；右上角增加「当前用户名 ▾」下拉（修改密码 / 退出登录）。
- `router.beforeEach`：目标为 `/login` → 放行；无 token → 重定向 `/login`；有 token 未拉取用户信息 → 拉 `/api/auth/me` 缓存菜单 → 放行；目标路由不在该角色菜单内 → **跳转首页并提示「无权限」**。
- axios 请求拦截器：注入 `Authorization: Bearer <token>`。
- axios 响应拦截器：`401` → 清 token 跳登录页；`403` → 提示「无权限」；`mustChangePwd` → 强制改密弹窗。
- 埋点：`router.afterEach` 上报 `POST /api/logs/page-view`。

## 错误处理

统一错误码（沿用 `Result<T>`）：

| code | 场景 | 前端处理 |
|---|---|---|
| 200 | 成功 | 正常 |
| 400 | 参数错误（必填缺失、格式非法） | 提示具体信息 |
| 401 | 未登录 / token 缺失或过期 | 清 token → 跳登录页 |
| 403 | 无权限 | 提示「无权限」 |
| 409 | 冲突（用户名已存在） | 表单内提示 |
| 500 | 服务器异常 | 通用错误提示 |

- 新增 `GlobalExceptionHandler`（现无全局异常处理），统一转换异常为 `Result`。
- 登录失败统一提示「用户名或密码错误」（不区分账号不存在 / 密码错误，防枚举），并写 `sys_login_log`（`success=false` + `fail_reason`）。
- 账号被禁用（`status=0`）→ 提示「账号已禁用，请联系管理员」，写登录日志。

## 测试

- 后端单元测试：`JwtUtil`（签发 / 解析 / 过期 / 篡改）、BCrypt 校验、`UserService` 导入去重与跳过逻辑、`MenuService` 角色菜单树。
- 后端 MockMvc 集成测试（参照现有 `LineageControllerTest`）：登录成功 / 失败、无 token 得 401、普通用户访问管理接口得 403、白名单放行。
- 前端手工验收（项目当前无前端测试框架，不为此新增 Vitest）：登录、token 过期跳转、动态菜单显隐、导入回显、强制改密、未授权路由跳转提示。

## 涉及文件

- 后端：`pom.xml`、`application.yml`（jwt 配置）、`config/WebMvcConfig.java`（改）、新增 config / interceptor / util / annotation / context / exception / entity / mapper / service / controller / dto 若干。
- 前端：新增 `views/LoginView.vue`、`views/UserManageView.vue`、`views/RoleManageView.vue`、`views/AnalyticsView.vue`、改密对话框组件、`api/auth.js` / `api/user.js` / `api/role.js` / `api/menu.js` / `api/analytics.js`；改动 `App.vue`、`router/index.js`、`api/request.js`。
- SQL：新增建表与初始化脚本（user / role / menu / role_menu / login_log / page_log）。