# NL2SQL 智能查数设计文档

- 日期：2026-09-03
- 状态：待审查
- 关联：proc-dependency-extractor（MySQL 血缘真相源 + Neo4j 图谱 + Vue 前端）

## 1. 背景与目标

项目已沉淀三块 NL2SQL 关键资产：

| 资产 | 作用 |
|------|------|
| `table_column_meta`（字段级明细 + 中文注释） | 表/字段语义描述（schema linking 素材） |
| Neo4j 血缘图（Transform 表达式、INPUT_TO/OUTPUT_TO/BELONGS_TO） | 字段间真实关联/加工关系 |
| `table_lineage` / 报表绑定 | 表与业务对应关系 |

**目标**：为开发/数据人员提供"智能查数"功能——中文业务问句 → GBase 8a 查询 SQL（生成 + 语法校验），血缘图谱辅助选表与 JOIN 提示。

**非目标（一期不做）**：
- SQL 真实执行闭环（二期预留接口）
- 向量库 / embedding 召回
- 多轮对话记忆（一期问句独立）
- 微调

## 2. 需求约束（已确认）

| 项 | 决定 |
|----|------|
| 用户 | 开发/数据人员即席查数 |
| 提问语言 | 中文业务词汇（如"账户余额"） |
| 查询范围 | 全部物理表（cdm/fdm/odm/dwa/pid，数百张） |
| LLM | 公网 API（OpenAI 兼容格式，DeepSeek/通义等） |
| 目标方言 | GBase 8a |
| 产物 | 生成 SQL + 语法校验，预留执行闭环 |
| 入口 | Vue 前端新增"智能查数"页面 |
| 验收 | 题集基线 + 持续试用迭代 |

## 3. 方案选型

采用 **方案一：RAG 检索 + 单次生成**（三方案对比结论）：

- 方案一（混合召回 + 单次生成）：架构简单，血缘图用在刀刃上，题集可回归 —— **选定**
- 方案二（Agent 多步选表）：准确率上限高但 token ×3~5、延迟高 —— 二期演进
- 方案三（向量库/微调）：工程量大，当前性价比低 —— 召回成瓶颈时再评估

## 4. 架构

```
用户问句（中文）
   ▼
Nl2SqlService.generate(question)
   ├─① SchemaLinkingService（选表召回）
   │    ├─ MySQL：table_column_meta 注释/字段名/表名 模糊匹配
   │    └─ Neo4j：疑似字段名沿 BELONGS_TO 反查宿主表
   │         → 候选表 ≤10 张 + 每表相关字段
   ├─② JoinPathService（JOIN 路径提示）
   │    └─ Neo4j：候选表两两间血缘边 / 1-2 跳共同上游
   ├─③ LlmClient → prompt(GBase 方言约束 + 候选 schema + JOIN 提示 + 问句)
   │         → 候选 SQL（JSON：sql/tables_used/join_notes/assumptions）
   └─④ JsqlParser 语法校验 → 失败带报错重试 ≤2 次
   ▼
响应：SQL + 引用表字段 + 血缘依据 + 校验状态
```

## 5. 组件与接口

| 组件 | 位置（新建） | 职责 |
|------|------|------|
| `Nl2SqlController` | `controller/Nl2SqlController.java` | `POST /api/nl2sql/generate`（入参 `{question}`）、`POST /api/nl2sql/eval`（题集跑批） |
| `Nl2SqlService` + impl | `service/` | 编排召回/JOIN/LLM/校验，返回结构化结果；预留 `execute` 扩展点 |
| `SchemaLinkingService` | `service/impl/SchemaLinkingServiceImpl.java` | 混合召回候选表+字段 |
| `JoinPathService` | `service/impl/JoinPathServiceImpl.java` | Neo4j 查表间血缘路径 |
| `LlmClient` | `service/LlmClient.java` | 公网 LLM HTTP 调用（OpenAI 兼容 chat/completions） |
| 前端页面 | `frontend/src/views/Nl2SqlView.vue` | 输入框 + 结果区 + 路由/菜单 |

配置（`application.yml`，key 不落库）：

```yaml
nl2sql:
  api-key: ${NL2SQL_API_KEY:}
  base-url: https://api.deepseek.com/v1
  model: deepseek-chat
  temperature: 0
  max-candidate-tables: 10
  max-fields-per-table: 15
  max-retries: 2
```

## 6. 召回设计（核心环节）

两路并行，结果合并去重：

**路 1：元数据关键词匹配（主路）**
- 问句提取 2-gram（中文）+ 英文/数字 token
- 匹配 `table_column_meta.column_comment`（LIKE）、`column_name`（忽略大小写）、`table_name`、`table_ddl_info` 表注释
- 权重排序：注释全文命中 > 字段名命中 > 表名命中

**路 2：血缘反查（辅路）**
- 问句中英文 token 视为疑似字段名，Neo4j `MATCH (c:Column {name: $n})-[:BELONGS_TO]->(t:Table)` 反查宿主表

**裁剪策略**：
- 候选表 ≤10 张；每表字段 ≤15 个（按与问句相关性排序），DDL 片段仅含 `字段名 类型 注释`
- 每表附 schema 归属，帮助 LLM 分层选表（明细层 vs 汇总层）

## 7. Prompt 设计

系统 prompt 三段：
1. **方言约束**：GBase 8a；写入已知方言坑（`EXCEPT ALL`、`DATE'...'` 字面量、连续空格 CREATE 语法等，来源项目 SqlPlaceholderReplacer 经验）
2. **候选 schema**：裁剪后的表/字段/注释/JOIN 提示（结构化 JSON）
3. **任务指令**：只允许使用候选表字段；输出 JSON `{sql, tables_used, join_notes, assumptions}`

`assumptions` 用于前端展示"AI 做的假设"；`JoinPathService` 路径信息随响应返回，前端在 SQL 下方展示"表 A—表 B 关联依据：proc xxx 血缘边"。

## 8. 校验与重试

- `JsqlParser.parse` 语法校验；GBase 特有语法沿用 `SqlPlaceholderReplacer` 预处理思路
- 校验失败：报错 + 原 SQL 回传 LLM 修正，最多重试 2 次
- 响应标注"未经执行验证，请人工确认"
- 校验通过 ≠ 逻辑正确

## 9. 测试与验收

- **单测**：召回（问句 → 断言候选表集）、JOIN 路径、prompt 组装、校验重试（mock LLM）
- **题集**：`docs/nl2sql/questions.json`，30 题（单表 10 / 两表 JOIN 10 / 三表以上或聚合 10），每题含问句、标准 SQL、应选表清单
- **跑批接口**：`POST /api/nl2sql/eval` 输出报表：选表正确率 / JOIN 正确率 / 语法通过率 / 整体正确率
- 题集同时作为后续试用迭代的回归集

## 10. 二期预留

- 执行闭环（连 GBase 真实执行 + 结果反馈修正）—— `Nl2SqlService` 留 `execute` 扩展点
- 多轮对话追问
- 向量召回（若题集暴露召回瓶颈）
