# 使用分析页面丰富化设计

日期：2026-10-14
状态：待审查

## 功能目标

把现有「使用分析」页面（`/analytics`）从「4 个今日 KPI + 登录折线 + 页面 Top10 + 用户表格」丰富为**分区概览型**页面，覆盖系统健康、功能热度、用户活跃三类视角；支持**时间范围切换**（近 7/30/90 天）与**图表联动下钻**。不做部门/角色维度聚合，不做导出。

受众：系统管理员/运维、业务/产品团队、管理层汇报，综合覆盖。

## 关键技术决策

- 采用**方案 A：分区概览型** —— 页面分 4 个语义区，顶部一行时间范围切换，图表点击弹出右侧抽屉下钻。
- 所有统计接口统一新增 `days` 参数（`7 | 30 | 90`，默认 30），非法值夹取到最近合法档位。
- 前端单文件 `AnalyticsView.vue` 内组织；图表统一封装 `renderChart(el, option)`，补齐 `resize` 与 `dispose`（现有实现缺失）。
- 移除「登录失败」「失败率」相关指标；连同失败原因 Top5 与失败趋势线一并取消，原「登录质量区」整块移除。

## 数据来源

沿用现有三张表，无需新增表：

| 表               | 用到的字段                                                   |
| --------------- | -------------------------------------------------------- |
| `sys_login_log` | user\_id, login\_time, success                           |
| `sys_page_log`  | user\_id, page\_path, page\_name, visit\_time            |
| `sys_user`      | id, username, real\_name, last\_login\_time, create\_time |

**指标定义**

- 活跃用户 = 区间内有登录或有页面访问的**去重**用户数。
- DAU = 当天去重活跃用户数。
- 人均访问 = 区间页面访问量 ÷ 区间活跃用户数（活跃用户为 0 时记为 0）。
- 最近活跃 = `max(最近登录时间, 最近页面访问时间)`。
- 区间边界：`visit_time/login_time >= DATE_SUB(CURDATE(), INTERVAL (days-1) DAY)`。

## 后端接口与数据契约

统一沿用 `Result<T>` 包装。

| 接口                              | 参数            | 返回                                | 用途                |
| ------------------------------- | ------------- | --------------------------------- | ----------------- |
| `/api/analytics/overview`       | days          | 登录次数、活跃用户、页面访问、人均访问               | 概览区 4 KPI         |
| `/api/analytics/trend`          | days          | 每日 `{d, dau, loginSucc}`          | 活跃区折线 + 登录成功趋势    |
| `/api/analytics/hour-distribution` | days       | 24 个小时桶的「登录次数 + 页面访问次数」          | 活跃时段分布            |
| `/api/analytics/page-top`       | days          | `{name, c, pct}`，pct = 该页访问量 ÷ 全部页面访问量 | 功能热度排行            |
| `/api/analytics/page-trend`     | days, top     | Top3 页面每日访问量                      | 功能热度趋势            |
| `/api/analytics/user-activity`  | days, keyword | 用户 + 区间登录数/访问量/最近活跃               | 用户明细表             |
| `/api/analytics/drill`          | type, days/date/id | 明细行列表                        | 统一下钻（day/page/user） |

**改动**

- 原 `login-trend` 接口废弃，由 `trend` 取代；`overview` 与 `user-activity`、`page-top` 改造为带 `days` 参数版本。
- `AnalyticsMapper` 新增对应聚合 SQL；`AnalyticsService` / `AnalyticsController` 相应扩展。
- `page-path` / `page-name` 等来自 `sys_page_log`，无需前端额外埋点改造。

**错误处理**

- `days` 非法值统一夹取到 `{7,30,90}`。
- 查询结果为空返回空数组 / 零值，前端显示"暂无数据"，不抛错。

## 前端布局与图表

页面自上而下 5 个区块，`el-card` + `el-row/el-col` 布局。

**0. 顶部工具条**：页面标题 + 时间范围切换（`el-radio-group`：近7天 / 近30天 / 近90天，默认近30天）。切换即重载全部区块。

**1. 概览区**：4 个 KPI 卡片一行（登录次数、活跃用户、页面访问、人均访问），随区间变化。

**2. 活跃区**（一行两块）
- 左（约 2/3）：折线图，双线 =「DAU」+「登录次数」，x 轴日期。
- 右（约 1/3）：柱状图，24 小时活跃时段分布。

**3. 功能热度区**（一行两块）
- 左：横向条形图，页面访问排行，条形末端标注占比。
- 右：多线折线图，Top3 页面每日访问趋势。

**4. 用户明细区**
- 顶部：用户名搜索框（防抖）。
- 表格列：用户名 / 姓名 / 区间登录数 / 区间访问量 / 最近活跃；按区间访问量降序。
- 点行触发下钻。

**技术要点**

- 图表用 `echarts`；`onMounted` 初始化，`window.resize` 时 `resize`，`onUnmounted` 时 `dispose`。
- 数据加载加 `v-loading`；空数据显示 `el-empty`（"暂无数据"）。
- 默认近30天时与现有观感一致，仅内容更全。

## 下钻交互

统一接口 `/api/analytics/drill`：

- `type=day`（点折线/柱图某天）：返回该日活跃用户列表 + 当日页面访问 Top。
- `type=page`（点功能排行某条）：返回区间内访问过该页的用户列表（用户名、姓名、访问次数、最近访问）。
- `type=user`（点用户表格某行）：返回该用户区间内活动时间线（登录记录 + 页面访问记录）。

UI：统一用右侧 `el-drawer` 展示，标题显示当前维度，内部 `el-table` / `el-timeline`；空则显示"暂无明细"。

## 状态管理

单文件 `AnalyticsView.vue` 内：

- `days = ref(30)`；`watch(days, loadAll)` 触发全部区块重新拉取。
- `drill = ref({ visible, type, title, rows })` 控制抽屉。
- 各区块数据用独立 ref；整体 `loading` 控制 `v-loading`。
- 请求失败：`ElMessage.error` + 保留旧数据 / 显示空态；下钻为空：抽屉内空态。

## 不做（范围外）

- 部门 / 角色维度聚合。
- 数据导出。
- 实时推送 / 自动刷新。

## 测试与验证

- 后端：`AnalyticsServiceImpl` 轻量单测（`days` 夹取、字段映射）；真实数据 curl `/overview`、`/trend`、`/page-top` 确认非空。
- 前端：浏览器打开 `http://localhost:8080/?v=N`（绕缓存）逐项核对 —— KPI 与 SQL 结果一致、切换 7/30/90 天图表刷新、三种下钻均弹出正确明细。
- 构建部署：`frontend` 下 `npm run build` → 产物进 `src/main/resources/static` → 重启后端。
