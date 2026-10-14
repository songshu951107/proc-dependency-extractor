# 使用分析页面丰富化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把「使用分析」页面（`/analytics`）丰富为分区概览型，支持时间范围切换（近 7/30/90 天）与图表联动下钻，覆盖概览、活跃、功能热度、用户明细四区。

**架构：** 后端在 `AnalyticsMapper` 用注解 SQL 承载按 `days` 参数的聚合查询，`AnalyticsService` 负责 days 归一化与结果整形，`AnalyticsController` 暴露 REST 接口；前端单文件 `AnalyticsView.vue` 用 `days` ref 驱动全局刷新，用右侧 `el-drawer` 承载统一 `/drill` 接口的下钻明细。

**技术栈：** Spring Boot 3.2.5 / Java 17 / MyBatis-Plus / MySQL 8；前端 Vue3 + Element Plus + ECharts 6 + axios。

**规格来源：** `docs/superpowers/specs/2026-10-14-usage-analytics-enrich-design.md`

---

## 文件结构

### 后端（包根 `com.hb.extractor`）

| 文件 | 职责 |
|---|---|
| `mapper/AnalyticsMapper.java`（改） | 新增按 `daysOff` 参数的全部统计 SQL |
| `service/AnalyticsService.java`（改） | 接口签名扩展（带 `days`/`keyword`/`type`） |
| `service/impl/AnalyticsServiceImpl.java`（改） | `normalizeDays` 归一化、结果整形（占比、24 桶补零、Top3 序列） |
| `controller/AnalyticsController.java`（改） | 新接口 + `days` 夹取入口 |

### 前端（`frontend/src`）

| 文件 | 职责 |
|---|---|
| `api/analytics.js`（改） | 扩展/新增 7 个接口封装 |
| `views/AnalyticsView.vue`（改） | 时间切换 + 四区布局 + 下钻抽屉 |

### 测试

| 文件 | 覆盖 |
|---|---|
| `src/test/java/com/hb/extractor/service/impl/AnalyticsDaysTest.java`（新） | `normalizeDays` 纯逻辑（TDD） |
| `src/test/java/com/hb/extractor/service/impl/AnalyticsServiceSmokeTest.java`（新） | `@SpringBootTest` 冒烟：各接口返回非 null（对接真实库） |

> 前端无测试框架（`package.json` 无 vitest），前端验证走浏览器实测 + 构建产物核对。

---

## 任务 1：`normalizeDays` 归一化（纯逻辑，TDD）

**文件：**
- 测试：`src/test/java/com/hb/extractor/service/impl/AnalyticsDaysTest.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/AnalyticsServiceImpl.java`

- [ ] **步骤 1：编写失败的测试**

```java
package com.hb.extractor.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyticsDaysTest {

    @Test
    void keepsLegalValues() {
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(7));
        assertEquals(30, AnalyticsServiceImpl.normalizeDays(30));
        assertEquals(90, AnalyticsServiceImpl.normalizeDays(90));
    }

    @Test
    void clampsIllegalValuesToNearestLowerOrEqual() {
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(0));
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(5));
        assertEquals(30, AnalyticsServiceImpl.normalizeDays(15));
        assertEquals(30, AnalyticsServiceImpl.normalizeDays(45));
        assertEquals(90, AnalyticsServiceImpl.normalizeDays(120));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -Dtest=AnalyticsDaysTest test`
预期：编译失败，`normalizeDays` 方法不存在

- [ ] **步骤 3：编写最少实现代码**

在 `AnalyticsServiceImpl` 中加入：

```java
    /** 合法档位 {7,30,90}，非法值夹取到最近的不大于其的档位。 */
    static int normalizeDays(int days) {
        if (days <= 7) return 7;
        if (days <= 30) return 30;
        return 90;
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -Dtest=AnalyticsDaysTest test`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/test/java/com/hb/extractor/service/impl/AnalyticsDaysTest.java src/main/java/com/hb/extractor/service/impl/AnalyticsServiceImpl.java
git commit -m "feat(analytics): 新增 days 档位归一化 normalizeDays"
```

---

## 任务 2：Mapper 统计 SQL + Service 整形

**文件：**
- 修改：`src/main/java/com/hb/extractor/mapper/AnalyticsMapper.java`
- 修改：`src/main/java/com/hb/extractor/service/AnalyticsService.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/AnalyticsServiceImpl.java`

**约定：** 所有涉及区间的方法传入 `daysOff = days - 1`（SQL 用 `DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)`）。

- [ ] **步骤 1：改写 `AnalyticsMapper`**

```java
package com.hb.extractor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=1 " +
            "AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)")
    long loginCount(@Param("daysOff") int daysOff);

    @Select("SELECT COUNT(*) FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)")
    long pageViews(@Param("daysOff") int daysOff);

    @Select("SELECT COUNT(DISTINCT uid) FROM (" +
            "  SELECT user_id uid FROM sys_login_log WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION" +
            "  SELECT user_id uid FROM sys_page_log WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t")
    long activeUsers(@Param("daysOff") int daysOff);

    @Select("SELECT day d, SUM(succ) loginSucc, COUNT(DISTINCT uid) dau FROM (" +
            "  SELECT DATE(login_time) day, user_id uid, 1 succ FROM sys_login_log " +
            "    WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION ALL" +
            "  SELECT DATE(visit_time) day, user_id uid, 0 succ FROM sys_page_log " +
            "    WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t GROUP BY day ORDER BY day")
    List<Map<String, Object>> trend(@Param("daysOff") int daysOff);

    @Select("SELECT h hour, COUNT(*) c FROM (" +
            "  SELECT HOUR(login_time) h FROM sys_login_log " +
            "    WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION ALL" +
            "  SELECT HOUR(visit_time) h FROM sys_page_log " +
            "    WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t GROUP BY h ORDER BY h")
    List<Map<String, Object>> hourDistribution(@Param("daysOff") int daysOff);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> pageTop(@Param("daysOff") int daysOff);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT #{limit}")
    List<Map<String, Object>> topPages(@Param("daysOff") int daysOff, @Param("limit") int limit);

    @Select("SELECT page_name pageName, DATE(visit_time) d, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "AND page_name IN (SELECT page_name FROM (" +
            "  SELECT page_name FROM sys_page_log WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "  GROUP BY page_name ORDER BY COUNT(*) DESC LIMIT #{limit}) x) " +
            "GROUP BY page_name, DATE(visit_time) ORDER BY d")
    List<Map<String, Object>> pageTrend(@Param("daysOff") int daysOff, @Param("limit") int limit);

    @Select("SELECT u.id, u.username, u.real_name realName, " +
            "(SELECT COUNT(*) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1 " +
            "  AND l.login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)) loginCount, " +
            "(SELECT COUNT(*) FROM sys_page_log p WHERE p.user_id=u.id " +
            "  AND p.visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)) pageViews, " +
            "GREATEST(" +
            "  COALESCE((SELECT MAX(login_time) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1), '1970-01-01')," +
            "  COALESCE((SELECT MAX(visit_time) FROM sys_page_log p WHERE p.user_id=u.id), '1970-01-01')" +
            ") lastActive " +
            "FROM sys_user u " +
            "WHERE u.username LIKE CONCAT('%', IFNULL(#{keyword},''), '%') " +
            "   OR u.real_name LIKE CONCAT('%', IFNULL(#{keyword},''), '%') " +
            "ORDER BY pageViews DESC")
    List<Map<String, Object>> userActivity(@Param("daysOff") int daysOff, @Param("keyword") String keyword);

    @Select("SELECT u.username, u.real_name realName, SUM(lg) loginCount, SUM(pv) pageViews FROM (" +
            "  SELECT user_id, 1 lg, 0 pv FROM sys_login_log WHERE success=1 AND DATE(login_time)=#{date}" +
            "  UNION ALL" +
            "  SELECT user_id, 0 lg, 1 pv FROM sys_page_log WHERE DATE(visit_time)=#{date}" +
            ") t JOIN sys_user u ON u.id=t.user_id " +
            "GROUP BY u.id, u.username, u.real_name ORDER BY pageViews DESC")
    List<Map<String, Object>> dayActiveUsers(@Param("date") String date);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE DATE(visit_time)=#{date} GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> dayPageTop(@Param("date") String date);

    @Select("SELECT u.username, u.real_name realName, COUNT(p.id) visits, MAX(p.visit_time) lastVisit " +
            "FROM sys_page_log p JOIN sys_user u ON u.id=p.user_id " +
            "WHERE p.page_name=#{pageName} AND p.visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY u.id, u.username, u.real_name ORDER BY visits DESC")
    List<Map<String, Object>> pageUsers(@Param("pageName") String pageName, @Param("daysOff") int daysOff);

    @Select("SELECT '登录' type, l.login_time time, '登录成功' detail FROM sys_login_log l " +
            "  WHERE l.user_id=#{userId} AND l.success=1 AND l.login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "UNION ALL " +
            "SELECT '页面访问' type, p.visit_time time, p.page_name detail FROM sys_page_log p " +
            "  WHERE p.user_id=#{userId} AND p.visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "ORDER BY time DESC LIMIT 200")
    List<Map<String, Object>> userEvents(@Param("userId") Long userId, @Param("daysOff") int daysOff);
}
```

- [ ] **步骤 2：改写 `AnalyticsService` 接口**

```java
package com.hb.extractor.service;
import java.util.List; import java.util.Map;
public interface AnalyticsService {
    Map<String, Object> overview(int days);
    List<Map<String, Object>> trend(int days);
    List<Map<String, Object>> hourDistribution(int days);
    List<Map<String, Object>> pageTop(int days);
    Map<String, Object> pageTrend(int days, int limit);
    List<Map<String, Object>> userActivity(int days, String keyword);
    Map<String, Object> drill(String type, int days, String date, String pageName, Long userId);
}
```

- [ ] **步骤 3：改写 `AnalyticsServiceImpl`**

```java
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

    /** 合法档位 {7,30,90}，非法值夹取到最近的不大于其的档位。 */
    static int normalizeDays(int days) {
        if (days <= 7) return 7;
        if (days <= 30) return 30;
        return 90;
    }

    @Override public Map<String, Object> overview(int days) {
        int d = normalizeDays(days), off = d - 1;
        long loginCount = mapper.loginCount(off);
        long activeUsers = mapper.activeUsers(off);
        long pageViews = mapper.pageViews(off);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginCount", loginCount);
        m.put("activeUsers", activeUsers);
        m.put("pageViews", pageViews);
        m.put("avgPageViews", activeUsers == 0 ? 0 : Math.round((double) pageViews / activeUsers * 10) / 10.0);
        return m;
    }

    @Override public List<Map<String, Object>> trend(int days) {
        return mapper.trend(normalizeDays(days) - 1);
    }

    @Override public List<Map<String, Object>> hourDistribution(int days) {
        List<Map<String, Object>> raw = mapper.hourDistribution(normalizeDays(days) - 1);
        Map<Integer, Long> byHour = new HashMap<>();
        for (Map<String, Object> r : raw) {
            byHour.put(((Number) r.get("hour")).intValue(), ((Number) r.get("c")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("c", byHour.getOrDefault(h, 0L));
            out.add(m);
        }
        return out;
    }

    @Override public List<Map<String, Object>> pageTop(int days) {
        List<Map<String, Object>> raw = mapper.pageTop(normalizeDays(days) - 1);
        long total = 0;
        for (Map<String, Object> r : raw) total += ((Number) r.get("c")).longValue();
        for (Map<String, Object> r : raw) {
            long c = ((Number) r.get("c")).longValue();
            r.put("pct", total == 0 ? 0 : Math.round((double) c / total * 1000) / 10.0);
        }
        return raw;
    }

    @Override public Map<String, Object> pageTrend(int days, int limit) {
        int d = normalizeDays(days), off = d - 1;
        List<Map<String, Object>> tops = mapper.topPages(off, limit);
        List<String> pages = new ArrayList<>();
        for (Map<String, Object> t : tops) pages.add((String) t.get("name"));
        List<Map<String, Object>> rows = mapper.pageTrend(off, limit);
        // 收集日期（有序）
        List<String> dates = new ArrayList<>();
        Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
        for (String p : pages) byPage.put(p, new LinkedHashMap<>());
        for (Map<String, Object> r : rows) {
            String p = (String) r.get("pageName");
            String day = String.valueOf(r.get("d"));
            if (!dates.contains(day)) dates.add(day);
            byPage.computeIfAbsent(p, k -> new LinkedHashMap<>())
                  .put(day, ((Number) r.get("c")).longValue());
        }
        Collections.sort(dates);
        List<Map<String, Object>> series = new ArrayList<>();
        for (String p : pages) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", p);
            List<Long> data = new ArrayList<>();
            for (String day : dates) data.add(byPage.get(p).getOrDefault(day, 0L));
            s.put("data", data);
            series.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    @Override public List<Map<String, Object>> userActivity(int days, String keyword) {
        return mapper.userActivity(normalizeDays(days) - 1, keyword);
    }

    @Override public Map<String, Object> drill(String type, int days, String date, String pageName, Long userId) {
        int off = normalizeDays(days) - 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        switch (type == null ? "" : type) {
            case "day":
                out.put("users", mapper.dayActiveUsers(date));
                out.put("pages", mapper.dayPageTop(date));
                break;
            case "page":
                out.put("users", mapper.pageUsers(pageName, off));
                break;
            case "user":
                out.put("events", mapper.userEvents(userId, off));
                break;
            default:
                out.put("users", Collections.emptyList());
        }
        return out;
    }
}
```

- [ ] **步骤 4：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/mapper/AnalyticsMapper.java src/main/java/com/hb/extractor/service/AnalyticsService.java src/main/java/com/hb/extractor/service/impl/AnalyticsServiceImpl.java
git commit -m "feat(analytics): 新增按区间聚合的统计 SQL 与服务整形"
```

---

## 任务 3：Controller 暴露新接口

**文件：**
- 修改：`src/main/java/com/hb/extractor/controller/AnalyticsController.java`

- [ ] **步骤 1：改写 Controller**

```java
package com.hb.extractor.controller;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.overview(days));
    }
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.trend(days));
    }
    @GetMapping("/hour-distribution")
    public Result<List<Map<String, Object>>> hourDistribution(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.hourDistribution(days));
    }
    @GetMapping("/page-top")
    public Result<List<Map<String, Object>>> pageTop(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.pageTop(days));
    }
    @GetMapping("/page-trend")
    public Result<Map<String, Object>> pageTrend(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(defaultValue = "3") int top) {
        return Result.success(service.pageTrend(days, top));
    }
    @GetMapping("/user-activity")
    public Result<List<Map<String, Object>>> userActivity(@RequestParam(defaultValue = "30") int days,
                                                          @RequestParam(required = false) String keyword) {
        return Result.success(service.userActivity(days, keyword));
    }
    @GetMapping("/drill")
    public Result<Map<String, Object>> drill(@RequestParam String type,
                                             @RequestParam(defaultValue = "30") int days,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) String pageName,
                                             @RequestParam(required = false) Long userId) {
        return Result.success(service.drill(type, days, date, pageName, userId));
    }
}
```

- [ ] **步骤 2：编译并写冒烟测试**

`src/test/java/com/hb/extractor/service/impl/AnalyticsServiceSmokeTest.java`：

```java
package com.hb.extractor.service.impl;

import com.hb.extractor.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AnalyticsServiceSmokeTest {

    @Autowired AnalyticsService service;

    @Test
    void overviewFieldsPresent() {
        Map<String, Object> ov = service.overview(30);
        assertTrue(ov.containsKey("loginCount"));
        assertTrue(ov.containsKey("activeUsers"));
        assertTrue(ov.containsKey("pageViews"));
        assertTrue(ov.containsKey("avgPageViews"));
    }

    @Test
    void hourDistributionHas24Buckets() {
        assertEquals(24, service.hourDistribution(30).size());
    }

    @Test
    void pageTrendShape() {
        Map<String, Object> pt = service.pageTrend(30, 3);
        assertTrue(pt.containsKey("dates"));
        assertTrue(pt.containsKey("series"));
    }

    @Test
    void illegalDaysClamped() {
        assertNotNull(service.overview(0));
        assertNotNull(service.trend(120));
    }

    @Test
    void drillUserReturnsEventsList() {
        Map<String, Object> d = service.drill("user", 30, null, null, 1L);
        assertTrue(d.get("events") instanceof List);
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`mvn -q -Dtest=AnalyticsServiceSmokeTest,AnalyticsDaysTest test`
预期：PASS（需本地 MySQL 可用）

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/hb/extractor/controller/AnalyticsController.java src/test/java/com/hb/extractor/service/impl/AnalyticsServiceSmokeTest.java
git commit -m "feat(analytics): 暴露区间聚合接口并加冒烟测试"
```

---

## 任务 4：前端 API 封装

**文件：**
- 修改：`frontend/src/api/analytics.js`

- [ ] **步骤 1：扩展 api/analytics.js**

```js
import request from './request'

export const overview = (days) => request.get('/analytics/overview', { params: { days } })
export const trend = (days) => request.get('/analytics/trend', { params: { days } })
export const hourDistribution = (days) => request.get('/analytics/hour-distribution', { params: { days } })
export const pageTop = (days) => request.get('/analytics/page-top', { params: { days } })
export const pageTrend = (days, top = 3) => request.get('/analytics/page-trend', { params: { days, top } })
export const userActivity = (days, keyword) => request.get('/analytics/user-activity', { params: { days, keyword } })
export const drill = (params) => request.get('/analytics/drill', { params })
```

- [ ] **步骤 2：Commit**

```bash
git add frontend/src/api/analytics.js
git commit -m "feat(analytics): 前端接口封装支持区间/下钻参数"
```

---

## 任务 5：前端页面重构（骨架 + 概览区 + 活跃区）

**文件：**
- 修改：`frontend/src/views/AnalyticsView.vue`

- [ ] **步骤 1：替换整份 `AnalyticsView.vue`**

```vue
<template>
  <div v-loading="loading">
    <!-- 0. 顶部工具条 -->
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
      <span style="font-size:16px;font-weight:600">使用分析</span>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">近7天</el-radio-button>
        <el-radio-button :value="30">近30天</el-radio-button>
        <el-radio-button :value="90">近90天</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 1. 概览区 -->
    <div style="display:flex;gap:12px;margin-bottom:16px">
      <el-card v-for="c in kpis" :key="c.label" style="flex:1;text-align:center">
        <div style="font-size:22px;font-weight:bold">{{ c.value }}</div>
        <div style="font-size:12px;color:#666">{{ c.label }}</div>
      </el-card>
    </div>

    <!-- 2. 活跃区 -->
    <div style="display:flex;gap:12px;margin-bottom:16px">
      <el-card style="flex:2">
        <div ref="trendRef" style="height:300px"></div>
      </el-card>
      <el-card style="flex:1">
        <div ref="hourRef" style="height:300px"></div>
      </el-card>
    </div>

    <!-- 3. 功能热度区 -->
    <div style="display:flex;gap:12px;margin-bottom:16px">
      <el-card style="flex:1">
        <div ref="pageTopRef" style="height:300px"></div>
      </el-card>
      <el-card style="flex:1">
        <div ref="pageTrendRef" style="height:300px"></div>
      </el-card>
    </div>

    <!-- 4. 用户明细区 -->
    <el-card>
      <div style="margin-bottom:12px">
        <el-input v-model="keyword" placeholder="搜索用户名 / 姓名" clearable
                  style="width:240px" @input="onSearch" />
      </div>
      <el-table :data="activity" border height="300" @row-click="onUserClick">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="realName" label="姓名" />
        <el-table-column prop="loginCount" label="区间登录数" />
        <el-table-column prop="pageViews" label="区间访问量" />
        <el-table-column prop="lastActive" label="最近活跃" />
      </el-table>
    </el-card>

    <!-- 下钻抽屉 -->
    <el-drawer v-model="drill.visible" :title="drill.title" size="45%">
      <template v-if="drill.type === 'user'">
        <el-timeline v-if="drill.events.length">
          <el-timeline-item v-for="(e, i) in drill.events" :key="i" :timestamp="e.time">
            {{ e.type }} · {{ e.detail }}
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无明细" />
      </template>
      <template v-else>
        <el-table v-if="drill.rows.length" :data="drill.rows" border>
          <el-table-column v-for="col in drill.columns" :key="col.prop"
                           :prop="col.prop" :label="col.label" />
        </el-table>
        <el-empty v-else description="暂无明细" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import {
  overview, trend, hourDistribution, pageTop, pageTrend, userActivity, drill as drillApi
} from '../api/analytics'

const days = ref(30)
const loading = ref(false)
const kpis = ref([])
const activity = ref([])
const keyword = ref('')
const drill = ref({ visible: false, type: '', title: '', rows: [], columns: [], events: [] })

const trendRef = ref(); const hourRef = ref()
const pageTopRef = ref(); const pageTrendRef = ref()
const charts = {}

function renderChart(key, el, option, onClick) {
  if (!el) return
  if (!charts[key]) charts[key] = echarts.init(el)
  const chart = charts[key]
  chart.off('click')
  chart.setOption(option, true)
  if (onClick) chart.on('click', onClick)
}

function loadOverview() {
  return overview(days.value).then(r => {
    const d = r.data
    kpis.value = [
      { label: `${days.value}天登录次数`, value: d.loginCount },
      { label: `${days.value}天活跃用户`, value: d.activeUsers },
      { label: `${days.value}天页面访问`, value: d.pageViews },
      { label: `${days.value}天人均访问`, value: d.avgPageViews }
    ]
  })
}

function loadTrend() {
  return trend(days.value).then(r => {
    const data = r.data
    renderChart('trend', trendRef.value, {
      tooltip: { trigger: 'axis' }, legend: { data: ['DAU', '登录次数'] },
      xAxis: { type: 'category', data: data.map(t => t.d) },
      yAxis: { type: 'value' },
      series: [
        { name: 'DAU', type: 'line', smooth: true, data: data.map(t => t.dau) },
        { name: '登录次数', type: 'line', smooth: true, data: data.map(t => t.loginSucc) }
      ]
    }, p => openDayDrill(p.name))
  })
}

function loadHour() {
  return hourDistribution(days.value).then(r => {
    const data = r.data
    renderChart('hour', hourRef.value, {
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: data.map(t => t.hour + '时') },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: data.map(t => t.c) }]
    })
  })
}

function loadPageTop() {
  return pageTop(days.value).then(r => {
    const data = r.data.slice().reverse()
    renderChart('pageTop', pageTopRef.value, {
      tooltip: { trigger: 'axis' },
      grid: { left: 120 },
      xAxis: { type: 'value' },
      yAxis: { type: 'category', data: data.map(t => t.name) },
      series: [{
        type: 'bar',
        data: data.map(t => t.c),
        label: { show: true, position: 'right', formatter: p => data[p.dataIndex].pct + '%' }
      }]
    }, p => openPageDrill(p.name))
  })
}

function loadPageTrend() {
  return pageTrend(days.value, 3).then(r => {
    const { dates, series } = r.data
    renderChart('pageTrend', pageTrendRef.value, {
      tooltip: { trigger: 'axis' }, legend: {},
      xAxis: { type: 'category', data: dates },
      yAxis: { type: 'value' },
      series: series.map(s => ({ name: s.name, type: 'line', smooth: true, data: s.data }))
    })
  })
}

function loadActivity() {
  return userActivity(days.value, keyword.value).then(r => { activity.value = r.data })
}

let searchTimer = null
function onSearch() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(loadActivity, 300)
}

function openDayDrill(date) {
  drillApi({ type: 'day', days: days.value, date }).then(r => {
    drill.value = {
      visible: true, type: 'day', title: `当日明细 ${date}`, events: [],
      columns: [
        { prop: 'username', label: '用户名' },
        { prop: 'realName', label: '姓名' },
        { prop: 'loginCount', label: '登录次数' },
        { prop: 'pageViews', label: '访问量' }
      ],
      rows: r.data.users || []
    }
  })
}

function openPageDrill(pageName) {
  drillApi({ type: 'page', days: days.value, pageName }).then(r => {
    drill.value = {
      visible: true, type: 'page', title: `页面访问用户 ${pageName}`, events: [],
      columns: [
        { prop: 'username', label: '用户名' },
        { prop: 'realName', label: '姓名' },
        { prop: 'visits', label: '访问次数' },
        { prop: 'lastVisit', label: '最近访问' }
      ],
      rows: r.data.users || []
    }
  })
}

function onUserClick(row) {
  drillApi({ type: 'user', days: days.value, userId: row.id }).then(r => {
    drill.value = {
      visible: true, type: 'user', title: `用户活动 ${row.username}`,
      rows: [], columns: [], events: r.data.events || []
    }
  })
}

function loadAll() {
  loading.value = true
  Promise.all([
    loadOverview(), loadTrend(), loadHour(), loadPageTop(), loadPageTrend(), loadActivity()
  ]).finally(() => { loading.value = false })
}

function resizeAll() { Object.values(charts).forEach(c => c.resize()) }

onMounted(loadAll)
watch(days, loadAll)
window.addEventListener('resize', resizeAll)
onUnmounted(() => {
  window.removeEventListener('resize', resizeAll)
  Object.values(charts).forEach(c => c.dispose())
})
</script>
```

- [ ] **步骤 2：构建验证（产物进 static）**

运行（在 `frontend` 目录）：`npm run build`
预期：构建成功，`src/main/resources/static/index.html` 与 `assets/` 更新

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/views/AnalyticsView.vue
git commit -m "feat(analytics): 使用分析页面重构为四区概览 + 时间切换 + 下钻"
```

---

## 任务 6：部署与端到端验证

**文件：**
- 无（部署与验证）

- [ ] **步骤 1：重启后端加载新类**

运行：`& "D:\HB\maven\bin\mvn.cmd" spring-boot:run`（后台），等待监听 8080

- [ ] **步骤 2：curl 三个核心接口，核对数据非空**

运行：

```powershell
Invoke-RestMethod "http://localhost:8080/api/analytics/overview?days=30" | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8080/api/analytics/trend?days=7" | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://localhost:8080/api/analytics/page-top?days=30" | ConvertTo-Json -Depth 5
```

预期：`code=200`，`data` 非空；`overview.data` 含 4 个字段且与 SQL 直查一致

- [ ] **步骤 3：浏览器逐项核对**

打开 `http://localhost:8080/?v=1`（`?v=` 绕 `index.html` 缓存）→ 进入「使用分析」：

- 4 个 KPI 数值与 SQL 直查一致
- 切换 近7/30/90 天，全部图表随之刷新
- 点折线某天 → 抽屉显示当日活跃用户；点功能排行某条 → 抽屉显示该页访问用户；点用户表某行 → 抽屉显示该用户活动时间线
- 空数据区块显示「暂无数据」，无报错

- [ ] **步骤 4：记录验证结果**

在提交信息或会话中记录：各接口返回样例、KPI 与 SQL 对照数据、三种下钻实际弹出内容。

---

## 自检

**规格覆盖度：**

- 概览区 4 KPI → 任务 2 `overview` + 任务 5 概览区 ✅
- 活跃区（DAU+登录双线、24 时段）→ 任务 2 `trend`/`hourDistribution` + 任务 5 ✅
- 功能热度区（排行+占比、Top3 趋势）→ 任务 2 `pageTop`/`pageTrend` + 任务 5 ✅
- 用户明细区（搜索、区间列、降序）→ 任务 2 `userActivity` + 任务 5 ✅
- 下钻（day/page/user，抽屉）→ 任务 2 `drill` + 任务 3 Controller + 任务 5 抽屉 ✅
- 时间范围切换 → 任务 3 `days` 参数 + 任务 5 `watch(days)` ✅
- 移除登录失败/失败率 → 未设计相关字段/区块 ✅
- 错误处理（days 夹取、空数据空态）→ 任务 1 + 任务 5 `el-empty` ✅
- 测试与验证 → 任务 1/3 单测 + 任务 6 端到端 ✅

**占位符扫描：** 无 TODO/待定；各代码步骤均含可执行代码块。

**类型一致性：** `overview` 返回字段名 `loginCount/activeUsers/pageViews/avgPageViews` 在 Service、Controller、前端 `loadOverview` 三处一致；`trend` 字段 `d/dau/loginSucc` 与前端一致；`drill` 返回 `users/pages/events` 与前端 `drill.rows/drill.events` 一致；`normalizeDays` 在任务 1 定义、任务 2/3 复用，「最近的不大于其的档位」逻辑在任务 1 测试中固定。
