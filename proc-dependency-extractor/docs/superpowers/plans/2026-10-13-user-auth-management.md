# 用户与权限模块 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为影响分析工具新增登录、用户管理、角色、密码重置、菜单-角色关联、使用分析六项能力，鉴权采用自定义拦截器 + JWT + BCrypt。

**架构：** 前端所有请求走统一 axios 实例并注入 JWT；后端用 `AuthInterceptor` 拦截 `/api/**`（白名单放行），`UserContext`(ThreadLocal) 承载当前用户，`@RequireAdmin` 注解保护管理员接口。菜单存库（`sys_menu`）+ 角色关联（`sys_role_menu`），前端按 `/api/auth/me` 返回动态渲染。

**技术栈：** Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.7 / MySQL 8 / jjwt 0.11.5 / spring-security-crypto / POI 5.2.5；前端 Vue3 + Element Plus + vue-router(hash) + axios + ECharts。

**规格来源：** `docs/superpowers/specs/2026-10-13-user-auth-management-design.md`

---

## 文件结构

### 后端（新增，包根 `com.hb.extractor`）

| 文件 | 职责 |
|---|---|
| `sql/migration/V2026_10_13__user_auth.sql` | 6 张表建表 + 初始化数据 |
| `config/PasswordConfig.java` | 暴露 `BCryptPasswordEncoder` Bean |
| `config/WebMvcConfig.java`（改） | 注册 `AuthInterceptor` + 白名单 |
| `interceptor/AuthInterceptor.java` | token 校验 / UserContext / @RequireAdmin |
| `util/JwtUtil.java` | 签发、解析、校验 token |
| `annotation/RequireAdmin.java` | 管理员接口标记 |
| `context/UserContext.java` | ThreadLocal 当前用户 |
| `exception/BusinessException.java` | 业务异常（带 code） |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` 统一异常转 `Result` |
| `entity/SysUser.java` `SysRole.java` `SysMenu.java` `SysRoleMenu.java` `SysLoginLog.java` `SysPageLog.java` | 6 实体 |
| `mapper/SysUserMapper.java` … `SysPageLogMapper.java` | 6 Mapper（`AnalyticsMapper` 承载统计 SQL） |
| `service/AuthService.java` `UserService.java` `RoleService.java` `MenuService.java` `AnalyticsService.java` | 接口 |
| `service/impl/*Impl.java` | 实现 |
| `controller/AuthController.java` `UserController.java` `RoleController.java` `MenuController.java` `LogController.java` `AnalyticsController.java` | REST |
| `dto/LoginRequest.java` `LoginResponse.java` `ChangePasswordRequest.java` `UserSaveRequest.java` `ImportResultDTO.java` `MenuVO.java` `RoleMenuRequest.java` `CurrentUserVO.java` | 出入参 |

### 前端（`frontend/src`）

| 文件 | 职责 |
|---|---|
| `api/request.js`（改） | 注入 token、处理 401/403 |
| `api/auth.js` `user.js` `role.js` `menu.js` `analytics.js` | 接口封装 |
| `utils/auth.js` | token / 用户信息 localStorage 读写 |
| `views/LoginView.vue` | 登录页（定稿设计） |
| `views/UserManageView.vue` | 用户管理 |
| `views/RoleManageView.vue` | 角色 + 菜单分配 |
| `views/AnalyticsView.vue` | 使用分析看板 |
| `components/ChangePasswordDialog.vue` | 改密弹窗（支持强制模式） |
| `router/index.js`（改） | 登录路由 + 全局前置守卫 |
| `App.vue`（改） | 动态菜单 + 右上角用户菜单 |

### 测试

| 文件 | 覆盖 |
|---|---|
| `src/test/java/com/hb/extractor/util/JwtUtilTest.java` | 签发/解析/过期/篡改 |
| `src/test/java/com/hb/extractor/service/impl/UserServiceImplTest.java` | 导入去重与跳过 |
| `src/test/java/com/hb/extractor/controller/AuthControllerTest.java` | MockMvc：登录/401/403/白名单 |

---

## 任务 1：数据库脚本与依赖

**文件：**
- 创建：`sql/migration/V2026_10_13__user_auth.sql`
- 修改：`pom.xml`、`src/main/resources/application.yml`

- [ ] **步骤 1：编写建表与初始化 SQL**

`sql/migration/V2026_10_13__user_auth.sql`：

```sql
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
```

> 注：上面的 BCrypt 串由任务 4 的 `BCryptPasswordEncoderTest` 生成并回填，确保与 `1234.Com` 匹配。

- [ ] **步骤 2：新增 Maven 依赖**

在 `pom.xml` 的 `<dependencies>` 中加入：

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **步骤 3：配置 JWT 参数**

在 `application.yml` 末尾追加：

```yaml
jwt:
  # HMAC-SHA256 密钥，长度需 >= 32 字节；生产用环境变量 JWT_SECRET 覆盖
  secret: ${JWT_SECRET:hb-extractor-2026-secret-key-please-change-me-32}
  expire-hours: 12
```

- [ ] **步骤 4：执行 SQL 并验证**

运行：`mysql -uroot -proot proc_extractor < sql/migration/V2026_10_13__user_auth.sql`
预期：无报错；`SELECT COUNT(*) FROM sys_menu;` 返回 12；`SELECT COUNT(*) FROM sys_role_menu WHERE role_id=2;` 返回 8。

- [ ] **步骤 5：Commit**

```bash
git add sql/migration/V2026_10_13__user_auth.sql pom.xml src/main/resources/application.yml
git commit -m "feat: 新增用户权限表结构与 JWT/BCrypt 依赖"
```

---

## 任务 2：实体与 Mapper

**文件：**
- 创建：`src/main/java/com/hb/extractor/entity/SysUser.java`（及其余 5 个实体）、`src/main/java/com/hb/extractor/mapper/SysUserMapper.java`（及其余 5 个）

- [ ] **步骤 1：编写实体**

`entity/SysUser.java`：

```java
package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String realName;
    private Long roleId;
    private String dept;
    private String email;
    private String phone;
    private Integer status;
    private Integer mustChangePwd;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

同法创建 `SysRole`（id, roleCode, roleName, description, createTime）、`SysMenu`（id, menuCode, menuName, path, icon, parentId, sortOrder, type）、`SysRoleMenu`（id, roleId, menuId）、`SysLoginLog`（id, userId, username, loginTime, ip, userAgent, success, failReason）、`SysPageLog`（id, userId, pagePath, pageName, visitTime），均带 `@Data @TableName("表名")` 与 `@TableId(type = IdType.AUTO)`。

- [ ] **步骤 2：编写 Mapper**

`mapper/SysUserMapper.java`：

```java
package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

其余 5 个同构（`extends BaseMapper<T>` + `@Mapper`）。另建 `mapper/AnalyticsMapper.java`：

```java
package com.hb.extractor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=1 AND DATE(login_time)=CURDATE()")
    long todayLoginCount();

    @Select("SELECT COUNT(DISTINCT user_id) FROM sys_login_log WHERE success=1 AND DATE(login_time)=CURDATE()")
    long todayActiveUsers();

    @Select("SELECT COUNT(*) FROM sys_page_log WHERE DATE(visit_time)=CURDATE()")
    long todayPageViews();

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=0 AND DATE(login_time)=CURDATE()")
    long todayLoginFail();

    @Select("SELECT DATE(login_time) d, COUNT(*) c FROM sys_login_log " +
            "WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL 29 DAY) " +
            "GROUP BY DATE(login_time) ORDER BY d")
    List<Map<String, Object>> loginTrend30d();

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL 29 DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> pageTop();

    @Select("SELECT u.username, u.real_name realName, " +
            "(SELECT COUNT(*) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1) loginCount, " +
            "(SELECT MAX(login_time) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1) lastLogin, " +
            "(SELECT COUNT(*) FROM sys_page_log p WHERE p.user_id=u.id) pageViews " +
            "FROM sys_user u ORDER BY loginCount DESC")
    List<Map<String, Object>> userActivity();
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/hb/extractor/entity src/main/java/com/hb/extractor/mapper
git commit -m "feat: 新增用户权限实体与 Mapper"
```

---

## 任务 3：JwtUtil（TDD）

**文件：**
- 创建：`src/main/java/com/hb/extractor/util/JwtUtil.java`
- 测试：`src/test/java/com/hb/extractor/util/JwtUtilTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package com.hb.extractor.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private final JwtUtil jwtUtil =
        new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);

    @Test
    void generateAndParse() {
        String token = jwtUtil.generate(7L, "zhangsan", "USER");
        Claims claims = jwtUtil.parse(token);
        assertEquals("zhangsan", claims.getSubject());
        assertEquals(7L, ((Number) claims.get("uid")).longValue());
        assertEquals("USER", claims.get("role"));
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtUtil.generate(1L, "admin", "ADMIN");
        assertThrows(RuntimeException.class, () -> jwtUtil.parse(token + "x"));
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil shortLived = new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);
        String token = shortLived.generateWithExpiry(1L, "admin", "ADMIN", System.currentTimeMillis() - 1000);
        assertThrows(RuntimeException.class, () -> jwtUtil.parse(token));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=JwtUtilTest test`
预期：编译失败 / FAIL —— `JwtUtil` 不存在。

- [ ] **步骤 3：编写实现**

`util/JwtUtil.java`：

```java
package com.hb.extractor.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    private final Key key;
    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours:12}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId, String username, String roleCode) {
        return generateWithExpiry(userId, username, roleCode, System.currentTimeMillis() + expireMillis);
    }

    public String generateWithExpiry(Long userId, String username, String roleCode, long expireAtMillis) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(username)
                .claim("uid", userId)
                .claim("role", roleCode)
                .setIssuedAt(now)
                .setExpiration(new Date(expireAtMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token).getBody();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=JwtUtilTest test`
预期：PASS（3 个用例）。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/util/JwtUtil.java src/test/java/com/hb/extractor/util/JwtUtilTest.java
git commit -m "feat: 新增 JwtUtil 及单元测试"
```

---

## 任务 4：PasswordConfig 与密码工具

**文件：**
- 创建：`src/main/java/com/hb/extractor/config/PasswordConfig.java`
- 测试：`src/test/java/com/hb/extractor/config/PasswordConfigTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package com.hb.extractor.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordConfigTest {

    private final BCryptPasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void defaultPasswordHashMatches() {
        assertTrue(encoder.matches("1234.Com", encoder.encode("1234.Com")));
    }

    @Test
    void wrongPasswordRejected() {
        assertFalse(encoder.matches("wrong", encoder.encode("1234.Com")));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=PasswordConfigTest test`
预期：编译失败 —— `PasswordConfig` 不存在。

- [ ] **步骤 3：编写实现**

`config/PasswordConfig.java`：

```java
package com.hb.extractor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=PasswordConfigTest test`
预期：PASS。

- [ ] **步骤 5：生成 admin 初始密码哈希并回填 SQL**

新增临时测试方法打印 `new BCryptPasswordEncoder().encode("1234.Com")`，复制输出串替换任务 1 SQL 中 `admin` 的 `password` 值（若任务 1 中的占位串已能匹配则跳过），再删除临时方法并重新执行该 SQL。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/config/PasswordConfig.java src/test/java/com/hb/extractor/config/PasswordConfigTest.java sql/migration/V2026_10_13__user_auth.sql
git commit -m "feat: 新增 BCrypt 密码编码器并回填管理员密码"
```

---

## 任务 5：UserContext、@RequireAdmin、AuthInterceptor、注册

**文件：**
- 创建：`context/UserContext.java`、`annotation/RequireAdmin.java`、`interceptor/AuthInterceptor.java`
- 修改：`config/WebMvcConfig.java`
- 测试：`src/test/java/com/hb/extractor/interceptor/AuthInterceptorTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package com.hb.extractor.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {

    private final JwtUtil jwtUtil = new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, new ObjectMapper());

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void noTokenReturns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, new HandlerMethod(this, Object.class.getMethod("toString")));
        assertFalse(ok);
        assertEquals(401, resp.getStatus());
    }

    @Test
    void validTokenFillsContext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        req.addHeader("Authorization", "Bearer " + jwtUtil.generate(5L, "zhangsan", "USER"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, new HandlerMethod(this, Object.class.getMethod("toString")));
        assertTrue(ok);
        assertEquals("zhangsan", UserContext.get().getUsername());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=AuthInterceptorTest test`
预期：编译失败 —— 相关类不存在。

- [ ] **步骤 3：编写 `UserContext`**

`context/UserContext.java`：

```java
package com.hb.extractor.context;

import lombok.Data;

public class UserContext {

    @Data
    public static class CurrentUser {
        private Long userId;
        private String username;
        private String roleCode;
    }

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) { HOLDER.set(user); }
    public static CurrentUser get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }
    public static boolean isAdmin() {
        CurrentUser u = HOLDER.get();
        return u != null && "ADMIN".equals(u.getRoleCode());
    }
}
```

- [ ] **步骤 4：编写 `@RequireAdmin`**

`annotation/RequireAdmin.java`：

```java
package com.hb.extractor.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
```

- [ ] **步骤 5：编写 `AuthInterceptor`**

`interceptor/AuthInterceptor.java`：

```java
package com.hb.extractor.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.Result;
import com.hb.extractor.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return write(response, 401, "未登录或登录已过期");
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(7));
            UserContext.CurrentUser user = new UserContext.CurrentUser();
            user.setUserId(((Number) claims.get("uid")).longValue());
            user.setUsername(claims.getSubject());
            user.setRoleCode((String) claims.get("role"));
            UserContext.set(user);
        } catch (Exception e) {
            return write(response, 401, "未登录或登录已过期");
        }
        if (handler instanceof HandlerMethod hm) {
            boolean needAdmin = hm.hasMethodAnnotation(RequireAdmin.class)
                    || hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
            if (needAdmin && !UserContext.isAdmin()) {
                return write(response, 403, "无权限");
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean write(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        response.getWriter().flush();
        return false;
    }
}
```

> 说明：HTTP 状态保持 200，业务码放在 `Result.code`，与现有前端 `request.js` 的判定逻辑一致。

- [ ] **步骤 6：在 `WebMvcConfig` 注册拦截器**

在 `WebMvcConfig` 类中注入并用 `addInterceptors` 注册（保留原有 CORS 与 viewController）：

```java
    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/error");
    }
```

- [ ] **步骤 7：运行测试验证通过**

运行：`mvn -q -Dtest=AuthInterceptorTest test`
预期：PASS（2 个用例）。

- [ ] **步骤 8：Commit**

```bash
git add src/main/java/com/hb/extractor/context src/main/java/com/hb/extractor/annotation src/main/java/com/hb/extractor/interceptor src/main/java/com/hb/extractor/config/WebMvcConfig.java src/test/java/com/hb/extractor/interceptor
git commit -m "feat: 新增鉴权拦截器与 UserContext 并注册"
```

---

## 任务 6：全局异常处理

**文件：**
- 创建：`exception/BusinessException.java`、`exception/GlobalExceptionHandler.java`

- [ ] **步骤 1：编写 `BusinessException`**

```java
package com.hb.extractor.exception;

public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) { super(message); this.code = code; }
    public BusinessException(String message) { this(400, message); }
    public int getCode() { return code; }
}
```

- [ ] **步骤 2：编写 `GlobalExceptionHandler`**

```java
package com.hb.extractor.exception;

import com.hb.extractor.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.error(500, "服务器异常");
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/hb/extractor/exception
git commit -m "feat: 新增全局异常处理"
```

---

## 任务 7：登录 / 当前用户 / 改密 / 登出

**文件：**
- 创建：`dto/LoginRequest.java` `dto/LoginResponse.java` `dto/ChangePasswordRequest.java` `dto/CurrentUserVO.java` `dto/MenuVO.java` `service/AuthService.java` `service/MenuService.java` `service/impl/AuthServiceImpl.java` `controller/AuthController.java`

- [ ] **步骤 1：编写 DTO**

```java
// dto/LoginRequest.java
package com.hb.extractor.dto;
import lombok.Data;
@Data public class LoginRequest { private String username; private String password; }
```

```java
// dto/LoginResponse.java
package com.hb.extractor.dto;
import lombok.AllArgsConstructor; import lombok.Data;
@Data @AllArgsConstructor
public class LoginResponse { private String token; private boolean mustChangePwd; private CurrentUserVO user; }
```

```java
// dto/ChangePasswordRequest.java
package com.hb.extractor.dto;
import lombok.Data;
@Data public class ChangePasswordRequest { private String oldPassword; private String newPassword; }
```

```java
// dto/CurrentUserVO.java
package com.hb.extractor.dto;
import lombok.Data; import java.util.List;
@Data public class CurrentUserVO {
    private Long id; private String username; private String realName; private String roleCode;
    private boolean mustChangePwd; private List<MenuVO> menus;
}
```

```java
// dto/MenuVO.java（任务 10 复用）
package com.hb.extractor.dto;
import lombok.Data; import java.util.ArrayList; import java.util.List;
@Data public class MenuVO {
    private Long id; private String menuCode; private String menuName; private String path;
    private String icon; private Long parentId; private Integer sortOrder; private String type;
    private List<MenuVO> children = new ArrayList<>();
}
```

- [ ] **步骤 2：编写 `AuthService` 接口**

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.ChangePasswordRequest;
import com.hb.extractor.dto.CurrentUserVO;
import com.hb.extractor.dto.LoginRequest;
import com.hb.extractor.dto.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request, String ip, String userAgent);
    CurrentUserVO currentUser(Long userId);
    void changePassword(Long userId, ChangePasswordRequest request);
}
```

`service/MenuService.java`（本任务先声明接口，任务 10 提供实现）：

```java
package com.hb.extractor.service;

import com.hb.extractor.dto.MenuVO;

import java.util.List;

public interface MenuService {
    List<MenuVO> menusOfRole(Long roleId);
    List<MenuVO> menuTree();
    String roleCodeOf(Long roleId);
    void assignMenus(Long roleId, List<Long> menuIds);
}
```

- [ ] **步骤 3：编写 `AuthServiceImpl`**

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.SysLoginLog;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysLoginLogMapper;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.service.AuthService;
import com.hb.extractor.service.MenuService;
import com.hb.extractor.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Pattern PWD_RULE =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final SysUserMapper userMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final MenuService menuService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            writeLog(user, request.getUsername(), ip, userAgent, false, "用户名或密码错误");
            throw new BusinessException(400, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            writeLog(user, user.getUsername(), ip, userAgent, false, "账号已禁用");
            throw new BusinessException(400, "账号已禁用，请联系管理员");
        }
        writeLog(user, user.getUsername(), ip, userAgent, true, null);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String roleCode = menuService.roleCodeOf(user.getRoleId());
        String token = jwtUtil.generate(user.getId(), user.getUsername(), roleCode);
        CurrentUserVO vo = toVO(user, roleCode, user.getMustChangePwd() != null && user.getMustChangePwd() == 1);
        return new LoginResponse(token, vo.isMustChangePwd(), vo);
    }

    @Override
    public CurrentUserVO currentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(401, "用户不存在");
        String roleCode = menuService.roleCodeOf(user.getRoleId());
        return toVO(user, roleCode, user.getMustChangePwd() != null && user.getMustChangePwd() == 1);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(401, "用户不存在");
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        if (request.getNewPassword() == null || !PWD_RULE.matcher(request.getNewPassword()).matches()) {
            throw new BusinessException(400, "新密码须≥8位且含大小写字母和数字");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePwd(0);
        userMapper.updateById(user);
    }

    private CurrentUserVO toVO(SysUser user, String roleCode, boolean mustChangePwd) {
        CurrentUserVO vo = new CurrentUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRoleCode(roleCode);
        vo.setMustChangePwd(mustChangePwd);
        vo.setMenus(menuService.menusOfRole(user.getRoleId()));
        return vo;
    }

    private void writeLog(SysUser user, String username, String ip, String userAgent,
                          boolean success, String failReason) {
        SysLoginLog log = new SysLoginLog();
        log.setUserId(user == null ? null : user.getId());
        log.setUsername(username);
        log.setIp(ip);
        log.setUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 255)));
        log.setSuccess(success ? 1 : 0);
        log.setFailReason(failReason);
        loginLogMapper.insert(log);
    }
}
```

- [ ] **步骤 4：编写 `AuthController`**

```java
package com.hb.extractor.controller;

import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.*;
import com.hb.extractor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        return Result.success(authService.login(request, clientIp(http), http.getHeader("User-Agent")));
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.currentUser(UserContext.get().getUserId()));
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(UserContext.get().getUserId(), request);
        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout() { return Result.success(); }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}
```

> `MenuService` 接口已在步骤 2 声明；`AuthServiceImpl` 依赖其 `roleCodeOf(Long)` 与 `menusOfRole(Long)`。实现类在任务 10 提供——`mvn compile` 仅编译不启动容器，故本步骤可 BUILD SUCCESS；运行期缺实现类的报错会在任务 10 补全解决。

- [ ] **步骤 5：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/dto src/main/java/com/hb/extractor/service/AuthService.java src/main/java/com/hb/extractor/service/impl/AuthServiceImpl.java src/main/java/com/hb/extractor/controller/AuthController.java
git commit -m "feat: 新增登录/当前用户/改密/登出接口"
```

---

## 任务 8：用户管理 CRUD + 重置密码

**文件：**
- 创建：`dto/UserSaveRequest.java` `service/UserService.java` `service/impl/UserServiceImpl.java` `controller/UserController.java`

- [ ] **步骤 1：编写 `UserSaveRequest`**

```java
package com.hb.extractor.dto;
import lombok.Data;
@Data public class UserSaveRequest {
    private Long id; private String username; private String realName; private Long roleId;
    private String dept; private String email; private String phone; private Integer status;
}
```

- [ ] **步骤 2：编写 `UserService` 接口**

```java
package com.hb.extractor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UserService {
    IPage<SysUser> page(int pageNum, int pageSize, String keyword);
    void create(UserSaveRequest request);
    void update(UserSaveRequest request);
    void delete(Long id);
    void resetPassword(Long id);
    ImportResultDTO importExcel(MultipartFile file);
    List<SysUser> listAll();
}
```

- [ ] **步骤 3：编写 `UserServiceImpl`**

```java
package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    public static final String DEFAULT_PASSWORD = "1234.Com";

    private final SysUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public IPage<SysUser> page(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }
        qw.orderByDesc(SysUser::getCreateTime);
        return userMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    @Override
    public void create(UserSaveRequest req) {
        checkDuplicate(req.getUsername(), null);
        SysUser user = new SysUser();
        apply(user, req);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setMustChangePwd(1);
        userMapper.insert(user);
    }

    @Override
    public void update(UserSaveRequest req) {
        SysUser user = userMapper.selectById(req.getId());
        if (user == null) throw new BusinessException(400, "用户不存在");
        checkDuplicate(req.getUsername(), req.getId());
        apply(user, req);
        userMapper.updateById(user);
    }

    @Override
    public void delete(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user != null && "admin".equals(user.getUsername())) {
            throw new BusinessException(400, "内置管理员账号不可删除");
        }
        userMapper.deleteById(id);
    }

    @Override
    public void resetPassword(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(400, "用户不存在");
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setMustChangePwd(1);
        userMapper.updateById(user);
    }

    @Override
    public List<SysUser> listAll() {
        return userMapper.selectList(null);
    }

    private void apply(SysUser user, UserSaveRequest req) {
        user.setUsername(req.getUsername());
        user.setRealName(req.getRealName());
        user.setRoleId(req.getRoleId());
        user.setDept(req.getDept());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus() == null ? 1 : req.getStatus());
    }

    private void checkDuplicate(String username, Long excludeId) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username);
        if (excludeId != null) qw.ne(SysUser::getId, excludeId);
        if (userMapper.selectCount(qw) > 0) {
            throw new BusinessException(409, "用户名已存在");
        }
    }
}
```

> 分页需要 MyBatis-Plus 分页插件；若项目未配置，任务 8 步骤 4 中添加 `MybatisPlusInterceptor` Bean。

- [ ] **步骤 4：确认分页插件**

检查项目是否已注册 `MybatisPlusInterceptor`（`PaginationInnerInterceptor`）。若没有，新增 `config/MybatisPlusConfig.java`：

```java
package com.hb.extractor.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

- [ ] **步骤 5：编写 `UserController`**

```java
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
```

> 分页列表返回体不含密码：`SysUser.password` 用 `@JsonIgnore` 或在 `SysUser` 上加 `@TableField(select = false)` 的更严格方案；本计划采用在 `SysUser.password` 字段加 `@com.fasterxml.jackson.annotation.JsonIgnore`。

- [ ] **步骤 6：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/UserSaveRequest.java src/main/java/com/hb/extractor/service/UserService.java src/main/java/com/hb/extractor/service/impl/UserServiceImpl.java src/main/java/com/hb/extractor/controller/UserController.java src/main/java/com/hb/extractor/config/MybatisPlusConfig.java src/main/java/com/hb/extractor/entity/SysUser.java
git commit -m "feat: 新增用户管理 CRUD 与重置密码"
```

---

## 任务 9：Excel 导入（TDD）

**文件：**
- 创建：`dto/ImportResultDTO.java`
- 修改：`service/impl/UserServiceImpl.java`（实现 `importExcel`）
- 测试：`src/test/java/com/hb/extractor/service/impl/UserImportTest.java`

- [ ] **步骤 1：编写 `ImportResultDTO`**

```java
package com.hb.extractor.dto;
import lombok.Data; import java.util.ArrayList; import java.util.List;
@Data public class ImportResultDTO {
    private int successCount;
    private int skipCount;
    private List<String> skipReasons = new ArrayList<>();
}
```

- [ ] **步骤 2：编写失败的测试**

```java
package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.mapper.SysUserMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserImportTest {

    @Autowired UserServiceImpl userService;

    @Test
    void skipsDuplicateUsername() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            String[] cols = {"用户名","姓名","角色","部门","邮箱","手机","状态"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            // 第一行：与内置 admin 重复 -> 跳过
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("admin");
            r1.createCell(1).setCellValue("重复");
            r1.createCell(2).setCellValue("普通用户");
            // 第二行：新用户 -> 成功
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("newuser01");
            r2.createCell(1).setCellValue("新用户");
            r2.createCell(2).setCellValue("普通用户");
            wb.write(bos);
        }
        MockMultipartFile file = new MockMultipartFile("file", "u.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());

        ImportResultDTO result = userService.importExcel(file);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkipCount());
        assertTrue(result.getSkipReasons().get(0).contains("admin"));
    }
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`mvn -q -Dtest=UserImportTest test`
预期：编译失败 / FAIL —— `importExcel` 未实现（返回 null）。

- [ ] **步骤 4：实现 `importExcel`**

在 `UserServiceImpl` 中补充（`SysRole` 用于角色名→roleId 映射）：

```java
    @org.springframework.beans.factory.annotation.Autowired
    private com.hb.extractor.mapper.SysRoleMapper roleMapper;

    @Override
    public ImportResultDTO importExcel(org.springframework.web.multipart.MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            throw new BusinessException(400, "文件格式错误，仅支持 .xlsx/.xls");
        }
        ImportResultDTO result = new ImportResultDTO();
        java.util.Map<String, Long> roleMap = new java.util.HashMap<>();
        roleMapper.selectList(null).forEach(r -> roleMap.put(r.getRoleName(), r.getId()));
        roleMap.put("管理员", 1L);
        roleMap.put("普通用户", 2L);

        try (org.apache.poi.ss.usermodel.Workbook wb =
                 org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                throw new BusinessException(400, "Excel 内容为空");
            }
            String header0 = cell(sheet.getRow(0), 0);
            if (header0 == null || !header0.contains("用户名")) {
                throw new BusinessException(400, "缺少必填列「用户名」");
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row == null) continue;
                String username = cell(row, 0);
                if (username == null || username.isBlank()) continue;
                if (userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                        .LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
                    result.setSkipCount(result.getSkipCount() + 1);
                    result.getSkipReasons().add("第" + (i + 1) + "行 " + username + "：用户名已存在");
                    continue;
                }
                try {
                    SysUser user = new SysUser();
                    user.setUsername(username);
                    user.setRealName(cell(row, 1));
                    String roleName = cell(row, 2);
                    user.setRoleId(roleMap.getOrDefault(roleName, 2L));
                    user.setDept(cell(row, 3));
                    user.setEmail(cell(row, 4));
                    user.setPhone(cell(row, 5));
                    user.setStatus("禁用".equals(cell(row, 6)) ? 0 : 1);
                    user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
                    user.setMustChangePwd(1);
                    userMapper.insert(user);
                    result.setSuccessCount(result.getSuccessCount() + 1);
                } catch (Exception ex) {
                    result.setSkipCount(result.getSkipCount() + 1);
                    result.getSkipReasons().add("第" + (i + 1) + "行 " + username + "：" + ex.getMessage());
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel 解析失败：" + e.getMessage());
        }
        return result;
    }

    private String cell(org.apache.poi.ss.usermodel.Row row, int idx) {
        org.apache.poi.ss.usermodel.Cell c = row.getCell(idx);
        if (c == null) return null;
        c.setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        String v = c.getStringCellValue();
        return v == null ? null : v.trim();
    }
```

> 说明：文件后缀非 .xlsx/.xls、空文件、缺少「用户名」表头 → 整体抛 `BusinessException(400)`；行级问题（用户名重复、字段异常）→ 跳过并在 `skipReasons` 记录原因。`BusinessException` 需在 `catch (Exception)` 之前单独放行，避免被包装成「Excel 解析失败」。

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -Dtest=UserImportTest test`
预期：PASS（成功 1 / 跳过 1，原因含 admin）。

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/ImportResultDTO.java src/main/java/com/hb/extractor/service/impl/UserServiceImpl.java src/test/java/com/hb/extractor/service/impl/UserImportTest.java
git commit -m "feat: 用户 Excel 导入（跳过重复行并回显原因）"
```

---

## 任务 10：角色、菜单与角色菜单分配

**文件：**
- 创建：`dto/RoleMenuRequest.java` `service/impl/MenuServiceImpl.java` `service/RoleService.java` `service/impl/RoleServiceImpl.java` `controller/MenuController.java` `controller/RoleController.java`
- 复用（任务 7 已建）：`dto/MenuVO.java`、`service/MenuService.java`（接口）

- [ ] **步骤 1：编写 `RoleMenuRequest`**

```java
// dto/RoleMenuRequest.java
package com.hb.extractor.dto;
import lombok.Data; import java.util.List;
@Data public class RoleMenuRequest { private List<Long> menuIds; }
```

> `MenuVO` 与 `MenuService` 接口已在任务 7 创建（`MenuService` 含 `menusOfRole` / `menuTree` / `roleCodeOf` / `assignMenus` 四个方法），本任务只补实现类。

- [ ] **步骤 2：编写 `MenuServiceImpl`**

```java
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
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds == null) return;
        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }
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
```

- [ ] **步骤 3：编写 `RoleService` / `RoleServiceImpl`**

```java
// service/RoleService.java
package com.hb.extractor.service;
import com.hb.extractor.entity.SysRole;
import java.util.List;
public interface RoleService {
    List<SysRole> listAll();
    void create(SysRole role);
    void update(SysRole role);
    void delete(Long id);
}
```

```java
// service/impl/RoleServiceImpl.java
package com.hb.extractor.service.impl;
import com.hb.extractor.entity.SysRole;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysRoleMapper;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;

    @Override public List<SysRole> listAll() { return roleMapper.selectList(null); }

    @Override public void create(SysRole role) { roleMapper.insert(role); }

    @Override public void update(SysRole role) { roleMapper.updateById(role); }

    @Override public void delete(Long id) {
        if ("ADMIN".equals(roleMapper.selectById(id).getRoleCode())) {
            throw new BusinessException(400, "内置管理员角色不可删除");
        }
        long used = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleId, id));
        if (used > 0) throw new BusinessException(400, "该角色下仍有用户，无法删除");
        roleMapper.deleteById(id);
    }
}
```

- [ ] **步骤 4：编写 Controller**

```java
// controller/MenuController.java
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
```

```java
// controller/RoleController.java
package com.hb.extractor.controller;
import com.hb.extractor.annotation.RequireAdmin;
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
```

> `RoleController.roleMenus` 需要 import `com.hb.extractor.dto.MenuVO`。

- [ ] **步骤 5：编写 `MenuServiceTest`（角色菜单树）**

测试文件：`src/test/java/com/hb/extractor/service/impl/MenuServiceTest.java`

```java
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
```

运行：`mvn -q -Dtest=MenuServiceTest test`
预期：PASS（2 个用例）。

- [ ] **步骤 6：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS（此时任务 7 的 `AuthServiceImpl` 也已可装配）。

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/RoleMenuRequest.java src/main/java/com/hb/extractor/service src/main/java/com/hb/extractor/controller src/test/java/com/hb/extractor/service/impl/MenuServiceTest.java
git commit -m "feat: 新增角色、菜单与角色菜单分配接口"
```

---

## 任务 11：使用分析统计与页面埋点

**文件：**
- 创建：`service/AnalyticsService.java` `service/impl/AnalyticsServiceImpl.java` `controller/AnalyticsController.java` `controller/LogController.java` `dto/PageViewRequest.java`

- [ ] **步骤 1：编写 `PageViewRequest`**

```java
package com.hb.extractor.dto;
import lombok.Data;
@Data public class PageViewRequest { private String pagePath; private String pageName; }
```

- [ ] **步骤 2：编写 `AnalyticsService` 与实现**

```java
// service/AnalyticsService.java
package com.hb.extractor.service;
import java.util.List; import java.util.Map;
public interface AnalyticsService {
    Map<String, Object> overview();
    List<Map<String, Object>> loginTrend();
    List<Map<String, Object>> pageTop();
    List<Map<String, Object>> userActivity();
}
```

```java
// service/impl/AnalyticsServiceImpl.java
package com.hb.extractor.service.impl;
import com.hb.extractor.mapper.AnalyticsMapper;
import com.hb.extractor.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {
    private final AnalyticsMapper mapper;

    @Override public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todayLoginCount", mapper.todayLoginCount());
        m.put("todayActiveUsers", mapper.todayActiveUsers());
        m.put("todayPageViews", mapper.todayPageViews());
        m.put("todayLoginFail", mapper.todayLoginFail());
        return m;
    }
    @Override public List<Map<String, Object>> loginTrend() { return mapper.loginTrend30d(); }
    @Override public List<Map<String, Object>> pageTop() { return mapper.pageTop(); }
    @Override public List<Map<String, Object>> userActivity() { return mapper.userActivity(); }
}
```

- [ ] **步骤 3：编写 `AnalyticsController` 与 `LogController`**

```java
// controller/AnalyticsController.java
package com.hb.extractor.controller;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RequireAdmin
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/overview") public Result<Map<String, Object>> overview() { return Result.success(service.overview()); }
    @GetMapping("/login-trend") public Result<List<Map<String, Object>>> loginTrend() { return Result.success(service.loginTrend()); }
    @GetMapping("/page-top") public Result<List<Map<String, Object>>> pageTop() { return Result.success(service.pageTop()); }
    @GetMapping("/user-activity") public Result<List<Map<String, Object>>> userActivity() { return Result.success(service.userActivity()); }
}
```

```java
// controller/LogController.java
package com.hb.extractor.controller;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.PageViewRequest;
import com.hb.extractor.dto.Result;
import com.hb.extractor.entity.SysPageLog;
import com.hb.extractor.mapper.SysPageLogMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final SysPageLogMapper pageLogMapper;
    public LogController(SysPageLogMapper pageLogMapper) { this.pageLogMapper = pageLogMapper; }

    @PostMapping("/page-view")
    public Result<Void> pageView(@RequestBody PageViewRequest req) {
        SysPageLog log = new SysPageLog();
        log.setUserId(UserContext.get().getUserId());
        log.setPagePath(req.getPagePath());
        log.setPageName(req.getPageName());
        pageLogMapper.insert(log);
        return Result.success();
    }
}
```

- [ ] **步骤 4：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/service/AnalyticsService.java src/main/java/com/hb/extractor/service/impl/AnalyticsServiceImpl.java src/main/java/com/hb/extractor/controller/AnalyticsController.java src/main/java/com/hb/extractor/controller/LogController.java src/main/java/com/hb/extractor/dto/PageViewRequest.java
git commit -m "feat: 新增使用分析统计与页面埋点接口"
```

---

## 任务 12：MockMvc 集成测试（鉴权行为）

**文件：**
- 测试：`src/test/java/com/hb/extractor/controller/AuthControllerTest.java`

- [ ] **步骤 1：编写测试**

```java
package com.hb.extractor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void loginSuccessReturnsToken() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin"); req.setPassword("1234.Com");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").exists());
    }

    @Test
    void loginFailReturns400() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin"); req.setPassword("wrong");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noTokenHitsProtectedApi() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(jsonPath("$.code").value(401));
    }
}
```

- [ ] **步骤 2：运行测试验证通过**

运行：`mvn -q -Dtest=AuthControllerTest test`
预期：PASS（3 个用例）。若 `noTokenHitsProtectedApi` 失败，检查 `WebMvcConfig` 是否已注册拦截器。

- [ ] **步骤 3：Commit**

```bash
git add src/test/java/com/hb/extractor/controller/AuthControllerTest.java
git commit -m "test: 新增登录与鉴权 MockMvc 集成测试"
```

---

## 任务 13：前端 axios 与 auth 工具

**文件：**
- 创建：`frontend/src/utils/auth.js`、`frontend/src/api/auth.js`
- 修改：`frontend/src/api/request.js`

- [ ] **步骤 1：编写 `utils/auth.js`**

```javascript
const TOKEN_KEY = 'hb_token'
const USER_KEY = 'hb_user'

export function getToken() { return localStorage.getItem(TOKEN_KEY) }
export function setToken(token) { localStorage.setItem(TOKEN_KEY, token) }
export function getUser() {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : null
}
export function setUser(user) { localStorage.setItem(USER_KEY, JSON.stringify(user)) }
export function clearAuth() { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY) }
```

- [ ] **步骤 2：改造 `api/request.js`（注入 token + 处理 401/403）**

```javascript
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '../utils/auth'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 60000 })

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code && res.code !== 200) {
      if (res.code === 401) {
        clearAuth()
        if (router.currentRoute.value.path !== '/login') router.replace('/login')
        ElMessage.error('登录已过期，请重新登录')
      } else if (res.code === 403) {
        ElMessage.error('无权限')
      } else {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request
```

- [ ] **步骤 3：编写 `api/auth.js`**

```javascript
import request from './request'

export const login = (data) => request.post('/auth/login', data)
export const getCurrentUser = () => request.get('/auth/me')
export const changePassword = (data) => request.post('/auth/change-password', data)
export const logout = () => request.post('/auth/logout')
```

- [ ] **步骤 4：Commit**

```bash
git add frontend/src/utils/auth.js frontend/src/api/auth.js frontend/src/api/request.js
git commit -m "feat: 前端注入 token 并处理 401/403"
```

---

## 任务 14：登录页 LoginView.vue

**文件：**
- 创建：`frontend/src/views/LoginView.vue`、`frontend/src/components/ChangePasswordDialog.vue`

- [ ] **步骤 1：编写 `ChangePasswordDialog.vue`**

```vue
<template>
  <el-dialog :model-value="modelValue" :title="force ? '首次登录请修改密码' : '修改密码'"
             width="420px" :close-on-click-modal="!force" :show-close="!force"
             @close="onClose">
    <el-form :model="form" label-width="80px">
      <el-form-item label="原密码">
        <el-input v-model="form.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="form.newPassword" type="password" show-password
                  placeholder="≥8位且含大小写字母和数字" />
      </el-form-item>
      <el-form-item label="确认">
        <el-input v-model="form.confirm" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="submit" type="primary">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '../api/auth'

const props = defineProps({ modelValue: Boolean, force: Boolean })
const emit = defineEmits(['update:modelValue', 'success'])
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function onClose() { if (!props.force) emit('update:modelValue', false) }

async function submit() {
  if (form.newPassword !== form.confirm) return ElMessage.warning('两次密码不一致')
  if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/.test(form.newPassword)) {
    return ElMessage.warning('新密码须≥8位且含大小写字母和数字')
  }
  await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
  ElMessage.success('修改成功')
  emit('success')
  emit('update:modelValue', false)
}
</script>
```

- [ ] **步骤 2：编写 `LoginView.vue`（定稿设计：A 布局 + B 配色，左 66% / 右 34%）**

```vue
<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="blob1"></div>
        <div class="blob2"></div>
        <div class="brand-inner">
          <div class="logo">◈</div>
          <div class="brand-title">影响分析工具</div>
          <div class="brand-sub">存储过程依赖抽取与影响分析平台</div>
        </div>
        <div class="copyright">© 2026 HB Data</div>
      </div>
      <div class="form-area">
        <div class="hint">请登录你的账号</div>
        <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" class="field" />
        <el-input v-model="form.password" type="password" placeholder="请输入密码"
                  :prefix-icon="Lock" show-password class="field" @keyup.enter="submit" />
        <div class="err">{{ error }}</div>
        <el-button class="login-btn" @click="submit">登 录</el-button>
        <div class="forgot">忘记密码请联系管理员</div>
      </div>
    </div>
    <ChangePasswordDialog v-model="showChangePwd" :force="forceChange" @success="onChanged" />
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { setToken, setUser } from '../utils/auth'
import ChangePasswordDialog from '../components/ChangePasswordDialog.vue'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const error = ref('')
const showChangePwd = ref(false)
const forceChange = ref(false)

async function submit() {
  error.value = ''
  try {
    const res = await login({ username: form.username, password: form.password })
    setToken(res.data.token)
    setUser(res.data.user)
    if (res.data.mustChangePwd) {
      forceChange.value = true
      showChangePwd.value = true
    } else {
      router.replace('/')
    }
  } catch (e) {
    error.value = e.message || '登录失败'
  }
}

function onChanged() {
  forceChange.value = false
  router.replace('/')
}
</script>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: #eef1f6; }
.login-card { width: 760px; height: 400px; display: flex; border-radius: 16px; overflow: hidden;
  box-shadow: 0 12px 44px rgba(15,32,39,.28); }
.brand { width: 66%; position: relative; background: linear-gradient(150deg,#0f2027,#203a43,#2c5364);
  color: #fff; padding: 34px; display: flex; flex-direction: column; justify-content: space-between; }
.blob1 { position: absolute; width: 170px; height: 170px; border-radius: 50%;
  background: rgba(79,172,254,.4); filter: blur(52px); top: -30px; left: -20px; }
.blob2 { position: absolute; width: 150px; height: 150px; border-radius: 50%;
  background: rgba(123,92,255,.45); filter: blur(48px); bottom: -24px; right: -16px; }
.brand-inner { position: relative; }
.logo { width: 42px; height: 42px; border-radius: 11px; background: rgba(255,255,255,.16);
  border: 1px solid rgba(255,255,255,.22); display: flex; align-items: center; justify-content: center; font-size: 21px; }
.brand-title { margin-top: 22px; font-size: 22px; font-weight: 700; }
.brand-sub { margin-top: 12px; font-size: 13px; opacity: .82; line-height: 1.8; max-width: 280px; }
.copyright { position: relative; font-size: 11.5px; opacity: .6; }
.form-area { width: 34%; padding: 34px 26px; display: flex; flex-direction: column; justify-content: center; }
.hint { font-size: 13px; color: #9aa4b2; margin-bottom: 20px; }
.field { margin-bottom: 14px; }
.err { color: #f56c6c; font-size: 12px; height: 16px; }
.login-btn { width: 100%; border: none; color: #fff;
  background: linear-gradient(90deg,#4facfe,#7b5cff); }
.forgot { margin-top: 14px; text-align: center; font-size: 12px; color: #9aa4b2; }
</style>
```

- [ ] **步骤 3：手工验证**

运行：`cd frontend; npm run dev`，打开 `http://localhost:5173/#/login`
预期：左右分栏登录页；用 `admin / 1234.Com` 登录成功跳首页。

- [ ] **步骤 4：Commit**

```bash
git add frontend/src/views/LoginView.vue frontend/src/components/ChangePasswordDialog.vue
git commit -m "feat: 新增登录页与改密弹窗"
```

---

## 任务 15：路由守卫与动态菜单

**文件：**
- 修改：`frontend/src/router/index.js`、`frontend/src/App.vue`

- [ ] **步骤 1：改造 `router/index.js`（新增 /login + 守卫）**

在 routes 数组最前加入：

```javascript
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
```

文件末尾（`export default router` 之前）加入守卫：

```javascript
import { getToken, getUser, setUser } from '../utils/auth'
import { getCurrentUser } from '../api/auth'

router.beforeEach(async (to, from, next) => {
  if (to.meta.public) return next()
  if (!getToken()) return next('/login')
  if (!getUser()) {
    try {
      const res = await getCurrentUser()
      setUser(res.data)
    } catch (e) {
      return next('/login')
    }
  }
  const user = getUser()
  const allowed = (user?.menus || []).some(m =>
    m.path === to.path || (m.children || []).some(c => c.path === to.path))
  if (!allowed && to.path !== '/') return next('/')
  next()
})
```

> 登录页与业务页面共用 App.vue 布局需处理：见步骤 2 的 `isLogin` 判断。

- [ ] **步骤 2：改造 `App.vue`（动态菜单 + 右上角用户菜单 + 埋点）**

```vue
<template>
  <router-view v-if="isLogin" />
  <el-container v-else style="height: 100vh">
    <el-header style="background:#409eff;color:#fff;display:flex;align-items:center;justify-content:space-between">
      <div style="display:flex;align-items:center">
        <el-icon style="margin-right:8px"><Connection /></el-icon>
        <span style="font-size:18px;font-weight:bold">影响分析工具</span>
      </div>
      <el-dropdown @command="onCommand">
        <span style="color:#fff;cursor:pointer">{{ user?.realName || user?.username }} ▾</span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="pwd">修改密码</el-dropdown-item>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-container>
      <el-aside width="200px" style="background:#304156">
        <el-menu :default-active="$route.path" router background-color="#304156"
                 text-color="#bfcbd9" active-text-color="#409eff">
          <template v-for="m in user?.menus || []" :key="m.id">
            <el-sub-menu v-if="m.children && m.children.length" :index="String(m.id)">
              <template #title><span>{{ m.menuName }}</span></template>
              <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path">{{ c.menuName }}</el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="m.path">{{ m.menuName }}</el-menu-item>
          </template>
        </el-menu>
      </el-aside>
      <el-main><router-view /></el-main>
    </el-container>
    <ChangePasswordDialog v-model="showPwd" @success="showPwd=false" />
  </el-container>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Connection } from '@element-plus/icons-vue'
import { getUser, clearAuth } from './utils/auth'
import request from './api/request'
import ChangePasswordDialog from './components/ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()
const user = computed(() => getUser())
const isLogin = computed(() => route.path === '/login')
const showPwd = ref(false)

watch(() => route.path, (path) => {
  if (!isLogin.value) {
    request.post('/logs/page-view', { pagePath: path, pageName: route.meta.title || path })
      .catch(() => {})
  }
})

function onCommand(cmd) {
  if (cmd === 'pwd') showPwd.value = true
  if (cmd === 'logout') { clearAuth(); router.replace('/login') }
}
</script>
```

> 删除原有的硬编码 `el-menu-item` 与图标 import。

- [ ] **步骤 3：手工验证**

运行：`cd frontend; npm run dev`
预期：登录后左侧只显示该角色菜单；普通用户看不到「系统管理」；点击退出回到登录页；刷新后菜单仍按角色渲染。

- [ ] **步骤 4：Commit**

```bash
git add frontend/src/router/index.js frontend/src/App.vue
git commit -m "feat: 前端路由守卫与动态菜单"
```

---

## 任务 16：用户管理页 UserManageView.vue

**文件：**
- 创建：`frontend/src/api/user.js`、`frontend/src/api/role.js`、`frontend/src/views/UserManageView.vue`

- [ ] **步骤 1：编写 `api/user.js` 与 `api/role.js`**

```javascript
// api/user.js
import request from './request'
export const pageUsers = (params) => request.get('/users', { params })
export const createUser = (data) => request.post('/users', data)
export const updateUser = (data) => request.put('/users', data)
export const deleteUser = (id) => request.delete(`/users/${id}`)
export const resetPassword = (id) => request.post(`/users/${id}/reset-password`)
export const importUsers = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/users/import', fd)
}
```

```javascript
// api/role.js
import request from './request'
export const listRoles = () => request.get('/roles')
export const menuTree = () => request.get('/menus/tree')
export const roleMenus = (id) => request.get(`/roles/${id}/menus`)
export const assignRoleMenus = (id, menuIds) => request.put(`/roles/${id}/menus`, { menuIds })
export const createRole = (data) => request.post('/roles', data)
export const deleteRole = (id) => request.delete(`/roles/${id}`)
```

- [ ] **步骤 2：编写 `UserManageView.vue`**

```vue
<template>
  <div>
    <div style="display:flex;gap:8px;margin-bottom:12px">
      <el-button type="primary" @click="openDialog()">新增用户</el-button>
      <el-upload :show-file-list="false" :before-upload="onImport" accept=".xlsx,.xls">
        <el-button type="success">Excel 导入</el-button>
      </el-upload>
      <el-input v-model="keyword" placeholder="按用户名/姓名搜索" style="width:220px" />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" border>
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="realName" label="姓名" />
      <el-table-column label="角色">
        <template #default="{ row }">{{ roleName(row.roleId) }}</template>
      </el-table-column>
      <el-table-column prop="dept" label="部门" />
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="warning" @click="onReset(row)">重置密码</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:12px" layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="pageNum" @current-change="onPage" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="70px">
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleId">
            <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门"><el-input v-model="form.dept" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入结果" width="480px">
      <p>成功 {{ importResult.successCount }} 条，跳过 {{ importResult.skipCount }} 条</p>
      <ul><li v-for="(r, i) in importResult.skipReasons" :key="i">{{ r }}</li></ul>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageUsers, createUser, updateUser, deleteUser, resetPassword, importUsers } from '../api/user'
import { listRoles } from '../api/role'

const rows = ref([]); const total = ref(0); const pageNum = ref(1); const pageSize = 10
const keyword = ref(''); const roles = ref([])
const dialogVisible = ref(false); const importVisible = ref(false)
const importResult = reactive({ successCount: 0, skipCount: 0, skipReasons: [] })
const form = reactive({ id: null, username: '', realName: '', roleId: null, dept: '', email: '', phone: '', status: 1 })

const roleName = (id) => roles.value.find(r => r.id === id)?.roleName || '-'

async function load() {
  const res = await pageUsers({ pageNum: pageNum.value, pageSize, keyword: keyword.value })
  rows.value = res.data.records; total.value = Number(res.data.total)
}
function onPage(p) { pageNum.value = p; load() }

function openDialog(row) {
  Object.assign(form, row ? { ...row } : { id: null, username: '', realName: '', roleId: roles.value[0]?.id, dept: '', email: '', phone: '', status: 1 })
  dialogVisible.value = true
}
async function save() {
  form.id ? await updateUser({ ...form }) : await createUser({ ...form })
  ElMessage.success('保存成功'); dialogVisible.value = false; load()
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除用户 ${row.username}？`, '提示', { type: 'warning' })
  await deleteUser(row.id); ElMessage.success('已删除'); load()
}
async function onReset(row) {
  await ElMessageBox.confirm(`将 ${row.username} 的密码重置为默认密码？`, '提示', { type: 'warning' })
  await resetPassword(row.id); ElMessage.success('已重置'); load()
}
async function onImport(file) {
  const res = await importUsers(file)
  Object.assign(importResult, res.data)
  importVisible.value = true; load()
  return false
}

onMounted(async () => { roles.value = (await listRoles()).data; load() })
</script>
```

- [ ] **步骤 3：手工验证**

运行：`cd frontend; npm run dev`
预期：以 admin 登录后新增用户、重置密码、Excel 导入均正常；导入弹窗回显成功/跳过明细。

- [ ] **步骤 4：Commit**

```bash
git add frontend/src/api/user.js frontend/src/api/role.js frontend/src/views/UserManageView.vue
git commit -m "feat: 新增用户管理页面"
```

---

## 任务 17：角色管理页 RoleManageView.vue

**文件：**
- 创建：`frontend/src/views/RoleManageView.vue`

- [ ] **步骤 1：编写页面**

```vue
<template>
  <div style="display:flex;gap:16px">
    <div style="width:200px;border-right:1px solid #eee;padding-right:12px">
      <div v-for="r in roles" :key="r.id" @click="select(r)"
           :style="{ padding:'8px', borderRadius:'6px', cursor:'pointer',
                     background: current?.id===r.id ? '#ecf5ff' : 'transparent' }">
        {{ r.roleName }}
      </div>
      <el-button style="width:100%;margin-top:10px" @click="addRole">新增角色</el-button>
    </div>
    <div style="flex:1">
      <div style="font-size:13px;color:#666;margin-bottom:8px">勾选该角色可见的菜单/页面</div>
      <el-tree ref="treeRef" :data="tree" show-checkbox node-key="id"
               :props="{ label:'menuName', children:'children' }" default-expand-all />
      <el-button type="primary" style="margin-top:12px" @click="save">保存</el-button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listRoles, menuTree, roleMenus, assignRoleMenus, createRole } from '../api/role'

const roles = ref([]); const tree = ref([]); const current = ref(null); const treeRef = ref()

async function load() {
  roles.value = (await listRoles()).data
  tree.value = (await menuTree()).data
  if (roles.value.length) select(roles.value[0])
}
async function select(role) {
  current.value = role
  const ids = (await roleMenus(role.id)).data
  treeRef.value?.setCheckedKeys(ids)
}
async function save() {
  if (!current.value) return
  const menuIds = treeRef.value.getCheckedKeys().concat(treeRef.value.getHalfCheckedKeys())
  await assignRoleMenus(current.value.id, menuIds)
  ElMessage.success('保存成功')
}
async function addRole() {
  const { value } = await ElMessageBox.prompt('角色名称', '新增角色', { inputPattern: /\S+/, inputErrorMessage: '不能为空' })
  await createRole({ roleName: value, roleCode: 'ROLE_' + Date.now() })
  ElMessage.success('已新增'); load()
}
onMounted(load)
</script>
```

- [ ] **步骤 2：手工验证**

运行：`cd frontend; npm run dev`
预期：以 admin 登录进入「角色管理」，切换角色显示对应勾选，保存后重新选择保持。

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/views/RoleManageView.vue
git commit -m "feat: 新增角色管理与菜单分配页面"
```

---

## 任务 18：使用分析看板 AnalyticsView.vue

**文件：**
- 创建：`frontend/src/api/analytics.js`、`frontend/src/views/AnalyticsView.vue`

- [ ] **步骤 1：编写 `api/analytics.js`**

```javascript
import request from './request'
export const overview = () => request.get('/analytics/overview')
export const loginTrend = () => request.get('/analytics/login-trend')
export const pageTop = () => request.get('/analytics/page-top')
export const userActivity = () => request.get('/analytics/user-activity')
```

- [ ] **步骤 2：安装 ECharts 并编写看板**

运行：`cd frontend; npm i echarts`

```vue
<template>
  <div>
    <div style="display:flex;gap:12px;margin-bottom:16px">
      <el-card v-for="c in cards" :key="c.label" style="flex:1;text-align:center">
        <div style="font-size:22px;font-weight:bold">{{ c.value }}</div>
        <div style="font-size:12px;color:#666">{{ c.label }}</div>
      </el-card>
    </div>
    <el-card style="margin-bottom:16px"><div ref="trendRef" style="height:280px"></div></el-card>
    <div style="display:flex;gap:12px">
      <el-card style="flex:1"><div ref="pageRef" style="height:280px"></div></el-card>
      <el-card style="flex:1">
        <el-table :data="activity" border height="280">
          <el-table-column prop="username" label="用户名" />
          <el-table-column prop="realName" label="姓名" />
          <el-table-column prop="loginCount" label="登录次数" />
          <el-table-column prop="lastLogin" label="最近登录" />
          <el-table-column prop="pageViews" label="页面访问" />
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import * as echarts from 'echarts'
import { overview, loginTrend, pageTop, userActivity } from '../api/analytics'

const ov = ref({}); const activity = ref([])
const trendRef = ref(); const pageRef = ref()
const cards = computed(() => [
  { label: '今日登录次数', value: ov.value.todayLoginCount ?? 0 },
  { label: '今日活跃用户', value: ov.value.todayActiveUsers ?? 0 },
  { label: '今日页面访问', value: ov.value.todayPageViews ?? 0 },
  { label: '今日登录失败', value: ov.value.todayLoginFail ?? 0 }
])

onMounted(async () => {
  ov.value = (await overview()).data
  activity.value = (await userActivity()).data
  const trend = (await loginTrend()).data
  echarts.init(trendRef.value).setOption({
    tooltip: { trigger: 'axis' }, xAxis: { type: 'category', data: trend.map(t => t.d) },
    yAxis: { type: 'value' }, series: [{ type: 'line', smooth: true, data: trend.map(t => t.c) }]
  })
  const top = (await pageTop()).data
  echarts.init(pageRef.value).setOption({
    tooltip: { trigger: 'axis' }, xAxis: { type: 'category', data: top.map(t => t.name) },
    yAxis: { type: 'value' }, series: [{ type: 'bar', data: top.map(t => t.c) }]
  })
})
</script>
```

- [ ] **步骤 3：手工验证**

运行：`cd frontend; npm run dev`
预期：以 admin 登录进入「使用分析」，KPI 卡、趋势折线、页面 Top 柱状、活跃明细均显示；多次切换页面后页面访问数增加。

- [ ] **步骤 4：Commit**

```bash
git add frontend/src/api/analytics.js frontend/src/views/AnalyticsView.vue frontend/package.json frontend/package-lock.json
git commit -m "feat: 新增使用分析看板"
```

---

## 任务 19：端到端验收与打包

**文件：**
- 无新增；整体验证

- [ ] **步骤 1：后端全量测试**

运行：`mvn -q test`
预期：全部 PASS（含 JwtUtilTest / PasswordConfigTest / AuthInterceptorTest / UserImportTest / MenuServiceTest / AuthControllerTest）。

- [ ] **步骤 2：前端构建**

运行：`cd frontend; npm run build`
预期：构建成功，产物输出到后端 static 目录（按现有 vite 配置）。

- [ ] **步骤 3：手工端到端走查清单**

- 匿名访问 `/#/users` → 跳登录页；
- `admin / 1234.Com` 登录 → 首页，左侧含「系统管理」；
- 新增普通用户 → 用其登录（默认密码）→ 强制改密弹窗（不可关闭）→ 改密后进首页，左侧无「系统管理」；
- 普通用户直接访问 `/#/users` → 跳首页并提示；
- 重置某用户密码 → 该用户下次登录回到默认密码 + 强制改密；
- Excel 导入含重复用户名 → 回显成功/跳过明细；
- 切换若干页面 → 使用分析看板访问数增加；
- 退出登录 → 清 token 回登录页。

- [ ] **步骤 4：Commit（若有配置微调）**

```bash
git add -A
git commit -m "chore: 用户权限模块端到端验收通过"
```

---

## 规格覆盖度对照

| 规格需求 | 对应任务 |
|---|---|
| 登录页 | 14（+13 axios、15 守卫） |
| 用户管理（手动 + Excel） | 8、9、16 |
| 角色（管理员/普通用户） | 10、17 |
| 密码重置 | 4、8、14 |
| 菜单-角色关联 | 10、17 |
| 使用情况分析 | 2、11、18 |
| 鉴权（拦截器/JWT/BCrypt/注解） | 3、4、5、6 |
| 数据模型 | 1、2 |
| 错误处理（错误码/全局异常/登录失败日志） | 6、7 |
| 测试策略 | 3、4、5、9、10、12、19 |
| 动态菜单 + 路由守卫 + 401/403 | 13、15 |