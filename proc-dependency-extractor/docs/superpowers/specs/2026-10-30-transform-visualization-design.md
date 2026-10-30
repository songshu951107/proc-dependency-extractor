# 脚本转换可视化（Transform Visualization）设计文档

- 日期：2026-10-30
- 状态：已获用户批准的设计（待实现）
- 关联工具链：`mapping_extractor.py` / `transform_table_mapping.py` / `transform_config.json`

## 1. 背景与目标

transform-script 工具链已实现 F 层表 → O 层表的下游脚本自动转换（词法纪律 + 语句级别名作用域 + JOIN 注入），当前产物为转换后脚本 + markdown 报告 + difflib 行级 diff（txt/html）。痛点：

- difflib 行级 diff 看不出**转换点语义**（表引用替换 / 列改名 / JOIN 注入）、无导航、长 SQL 行展示错乱；
- 转换报告 md 与原文割裂，逐处验收（25+ 处/脚本）效率低；
- 后续约 150 个下游脚本批量转换，需要更好的验收与管理载体。

目标：将转换操作集成进现有血缘系统（Spring Boot + Vue3），提供**转换点为中心的对比视图**与**脚本级管理列表**，并支持网页上传脚本替换。

## 2. 关键决策记录

| 决策点 | 结论 | 理由 |
|--------|------|------|
| 载体 | 方案 B：集成现有血缘系统 | 与脚本管理打通、可做批量管理台；用户明确选择 |
| 转换执行 | Python 离线转换，网页只读展示 | 引擎已回归验证零风险；内网服务器无需装 Python；避免 Java 重写 600 行引擎 |
| 对比视图 | 转换点为中心 + 全文定位 | 正是逐处验收所需；不引 Monaco，控制 jar 体积 |
| 页面挂载 | 独立菜单「脚本转换」 | 转换脚本未入 proc_info，无详情页挂载点；适配批量管理 |
| 映射来源 | 加工脚本对推导 + 人工确认关口 | 映射跟「表对」绑定，不跟单个脚本绑定；配置按表对并存 |
| 追加需求 | 支持网页上传脚本（同名替换） | 替代手工拷贝文件到服务器 |

## 3. 总体架构与数据流

```
网页上传 .py ──► {base}/transforms/input/           （同名覆盖 = 替换，需重跑离线转换）
                     │
                     ▼  离线（一条命令）：python transform_batch.py --input <dir> --output <dir> --config <json>
              {base}/transforms/output/<脚本名>/
                     ├── source.py        # 转换前快照（保证替换后 diff 与原文一致）
                     ├── converted.py     # 转换后脚本
                     ├── report.json      # 机读报告（前端唯一数据源，新增）
                     ├── report.md        # 人类可读报告（沿用现有）
                     └── diff.html        # difflib 产物（沿用现有，供下载）
                     ▼
后端 /api/transform/*   只读扫描目录（无新 MySQL 表，不碰 Neo4j）
                     ▼
前端「脚本转换」菜单：列表页（TransformListView）+ 对比详情页（TransformDetailView）
```

职责边界：Python 引擎零逻辑改动（只加批量包装 + JSON 产出）；后端纯文件服务；前端纯渲染。

## 4. 目录与产物约定

- 根目录：`transform.base-dir`（application.yml 配置，默认 `./transforms`）
- `input/`：待转换脚本（网页上传或手工放置）
- `output/<脚本名去 .py>/`：转换产物，由离线批处理写入
- 映射配置：沿用现有 `transform_config.json` 单文件多表对结构（`tables` 键即旧表名，新增表对时追加条目即可，多表对并存）；当前唯一表对 `FDM.F_PT_ECIF_PUB_CUST_INF → ODM.O_ECIF_M_CI_ORG` 已生成并经人工确认（84 列：rename 80 + need_join 1 + constant 3）

## 5. Python 侧改动

### 5.1 transform_table_mapping.py（小改）

- 产出 `report.json`：`{script, converted_at, config:{old_db, old_table, new_db, new_table}, summary:{table_ref, rename, join_inject, total, residues}, edits:[{type, line, new_line, old, new}], residues:[{type, line, content}], py_compile:"PASS|FAIL", error?}`
- **行号语义（前端定位契约，必须严格遵守）**：
  - `edits[].line` = 转换前原文**全文行号**（现有报告是三引号块内行号，属已知问题，JSON 中修正）；
  - `edits[].new_line` = 转换后**全文行号**（JOIN 注入会移行，替换类不改行）；
  - `residues[].line` = 转换后**全文行号**（残留检查本就在转换后文本上执行）
- 失败时也产出 report.json（py_compile FAIL / 异常摘要写入 error 字段），保证前端能展示失败态

### 5.2 transform_batch.py（新增包装器）

- 遍历 input/ 下全部 `.py`，逐个调用现有单脚本转换，产物落 output/<脚本名>/
- 汇总打印：成功/失败/残留数；支持 `--config` 指定表对配置（默认 `transform_config.json`）

## 6. 后端设计（Spring Boot 新模块）

`TransformController`，路径前缀 `/api/transform`：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload` | MultipartFile 存 input/；限 `.py` 与大小上限；同名覆盖并返回「已替换，需重新离线转换」 |
| GET | `/scripts` | 列表：合并 input/ 与 output/ 视图（脚本名、状态、转换点数、残留数、py_compile、转换时间） |
| GET | `/scripts/{name}/report` | 返回 report.json 内容 |
| GET | `/scripts/{name}/file?ver=source\|converted` | 返回脚本全文文本（前端做行定位） |
| GET | `/scripts/{name}/download?type=converted\|md\|html` | 产物下载（Resource 流） |

- Service：目录扫描，请求时刷新（不缓存，~150 脚本规模无性能压力），无需入库
- 状态机：`待转换`（仅 input）/ `已转换`（output 完整）/ `失败`（report.json error 或 py_compile FAIL）；input 文件 mtime 晚于 output → 显示「源已更新，待重转」
- 安全：`{name}` 白名单 `[A-Za-z0-9_.-]+` 防路径穿越；上传文件名同样校验
- 登录/权限沿用现有体系

## 7. 前端设计（Vue3 + Element Plus）

### 7.1 列表页 TransformListView.vue

- el-table：脚本名 / 状态徽标（待转换灰、已转换绿、失败红、残留黄、源已更新待重转橙）/ 转换点数 / 残留数 / py_compile / 转换时间 / 操作（查看对比、下载 converted、下载 diff.html）
- 顶部 el-upload（`.py` 校验）；同名时确认框：「已存在同名脚本，替换后需重新离线转换」

### 7.2 详情页 TransformDetailView.vue（路由 /transform/:name）

- 顶部概要条：`{FDM}.F_X → {ODM}.O_Y` 映射快照（report.json 带出）、转换点统计、残留与 py_compile 告警条
- 左侧转换点列表：类型徽标三色（table_ref / rename / join_inject）、行号、`old → new`、按类型筛选（el-radio-group）；点击 → 右侧滚动定位 + 行高亮
- 右侧全文视图：Tab「转换前 / 转换后」；行号列 + 转换点行高亮（蓝）+ 残留行警示（黄）；轻量渲染（行 div + 等宽字体，不引 Monaco/重型编辑器）
- 定位规则：转换前视图用 `edit.line`，转换后视图用 `edit.new_line`；残留行高亮在**转换后视图**，用 `residues[].line`

## 8. 错误处理与边界

| 场景 | 行为 |
|------|------|
| 仅 input 无 output | 状态「待转换」，详情页禁用对比 |
| py_compile FAIL / Python 异常 | report.json 写 error；列表红色；详情展示错误摘要 |
| 残留 > 0 | 列表黄色徽标 + 详情顶部告警条（如恒等改名类误报的人工排查入口） |
| 路径穿越 | name 白名单校验，拒绝 400 |
| 非法上传 | 非 `.py` / 超限 / 文件名非法 → 400 + 提示 |
| output 目录被手工删除 | 状态回落「待转换」 |

## 9. 测试策略

- **Python**：transform_batch 对 tmp1030 两个真实脚本端到端跑；断言 report.json 字段完整性、全文行号一致性（重点：join_inject 的 new_line）、对已转换输出重跑的幂等性
- **后端**：目录扫描与列表合并逻辑单测（临时目录 + JUnit）；上传校验单测
- **前端**：筛选 / 定位 / 上传替换 / Tab 切换手动走查；tmp1030 两脚本作为首批种子数据（转换点 25 + 3、残留 0）
- **验收标准**：不读代码的情况下，仅靠详情页即可逐处确认 25+3 处转换点正确性

## 10. 部署影响

- 前端 `npm run build` 进 `src/main/resources/static` → 重新打 jar → 内网部署（现有流程）
- 服务器无需 Python；离线转换在现有运维环节执行（本地或跳板机跑 transform_batch.py）
- application.yml 新增 `transform.base-dir` 一项

## 11. 非目标（YAGNI）

- 不做网页端触发转换（需服务器 Python 环境，暂不引入）
- 不做映射配置的网页端编辑/确认（人工关口仍在离线环节，报告 md 确认）
- 不入库 MySQL（目录扫描足够）；不动 Neo4j
- 不做 Monaco 行级全文 diff（difflib diff.html 下载兜底）
