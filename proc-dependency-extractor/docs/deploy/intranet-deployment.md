# 工程迁移到内网部署指南

> 适用于：内网 MySQL 已有物理表字段元数据（columns0818.xlsx 内容，即 `table_ddl_info` / `table_column_meta`）、内网需新建 Neo4j 的场景。
>
> 前提结论：**代码层面无需改动**（报表目录已可配置、界面动态显示已就位）。剩余工作是「构建产物 + 内网安装 MySQL/Neo4j + 连接配置 + 脚本落位 + 首次解析生成血缘」。

---

## 一、环境与前提

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 内网需安装，用于 `java -jar` |
| MySQL | 8.x | 内网已有表结构，无需导入 |
| Neo4j | Community（建议 5.x） | **内网需新装** |
| 前端构建 | — | 内网不需要 Node，已打进 jar |

> ⚠️ 内网通常无法访问公共 Maven 仓库和 `api.deepseek.com`。所以 **jar 必须在能联网的机器（本机）构建**，再拷入内网。

---

## 二、构建产物（在外网机/本机，联网）

```bash
cd proc-dependency-extractor
mvn clean package -DskipTests
```

产出：`target/proc-dependency-extractor.jar`（内置前端 `static/`、MySQL 驱动、Neo4j 驱动）。

拷入内网以下内容：

```
target/proc-dependency-extractor.jar
src/main/resources/application.yml     # 仅参考，真实配置用 §五 外部化
```

---

## 三、内网 MySQL 检查（已有表结构，跳过导入）

1. 确认库存在且**库名与连接串一致**（默认 `proc_extractor`）。库名不同则在 §五 连接 URL 的 `/` 后改。
2. **用以下完整表清单核对内网库**（除字段元数据外，应用还依赖血缘/报表/任务等表，缺任一张启动或查询即报错）：
   ```
   字段元数据:  table_ddl_info,   table_column_meta
   血缘:        table_lineage,    table_lineage_filter, sql_block_info, table_block_condition,
               table_field_mapping, table_transform_input
   存储过程:    proc_info,        proc_table_dependency, proc_parse_fail
   报表:        report_info,      report_dataset,   report_cell_binding
   任务/同步:    neo4j_sync_task,  neo4j_sync_log,   rebuild_task
   ```
   ```sql
   -- 一键核对缺失表（结果只列出当前没有的表）
   SELECT t.TABLE_NAME FROM (
     SELECT 'table_ddl_info' t UNION SELECT 'table_column_meta' UNION SELECT 'table_lineage'
     UNION SELECT 'table_lineage_filter' UNION SELECT 'sql_block_info' UNION SELECT 'table_block_condition'
     UNION SELECT 'table_field_mapping' UNION SELECT 'table_transform_input' UNION SELECT 'proc_info'
     UNION SELECT 'proc_table_dependency' UNION SELECT 'proc_parse_fail' UNION SELECT 'report_info'
     UNION SELECT 'report_dataset' UNION SELECT 'report_cell_binding' UNION SELECT 'neo4j_sync_task'
     UNION SELECT 'neo4j_sync_log' UNION SELECT 'rebuild_task'
   ) t LEFT JOIN information_schema.tables s
     ON s.table_schema='proc_extractor' AND s.table_name=t.t
   WHERE s.table_name IS NULL;
   ```
   - **校验命令**：`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='proc_extractor';` 应为 **17**。
   - **DDL 来源**：仓库已提供全量建表脚本 **`docs/deploy/schema.sql`**（17 张表，仅结构、幂等 DROP+CREATE，内网空库直接 `mysql -u<user> -p proc_extractor < schema.sql` 建表即可），其中的 `neo4j_sync_task`/`neo4j_sync_log`/`proc_parse_fail`/`rebuild_task` 与 `src/main/resources/db/` 下的单表 DDL 一致。
3. 建应用账号（或确认 root 密码）供连接，需权限：
   ```sql
   GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
   ON proc_extractor.* TO 'pde'@'内网服务IP' IDENTIFIED BY '强密码';
   ```
4. **确认 MySQL 允许应用主机远端连接**（若不一定在同一台机）：
   - 改 `my.cnf` 的 `bind-address` 为 `0.0.0.0`（或 MySQL 所在内网 IP），重启 MySQL；
   - 上一步账号 `@'内网服务IP'` 的 host 需覆盖应用机地址。
5. 确认字符集 `utf8mb4`、时区（连接串已含 `serverTimezone=Asia/Shanghai`）。

> ⚠️ **不要**再调 `POST /api/meta/columns/import` —— 它会**清空 `table_column_meta` + `table_ddl_info` 再全量重导**，而内网已有这些表。

---

## 四、内网安装 Neo4j Community（详细步骤）

> Neo4j Community 安装**不需要手动建约束** —— 应用启动时 `Neo4jConstraintInitializer` 会自动幂等创建本节无需处理。

### 4.1 获取安装包（离线）

内网无外网时，在**能联网的机器**下载 Neo4j Community 的 Linux tarball（选 5.x 稳定版，如 `neo4j-community-5.25.x-unix.tar.gz`），拷入内网；或走内网镜像源安装。

```
neo4j-community-5.25.x-unix.tar.gz     # 需一并确认：JDK 是否自带（见 4.3）
```

> Neo4j 发行包通常自带一个捆绑的 JVM，一般无需单独安装 Java。若你拿到的包不含 JRE、启动时报缺 Java，则内网需装 JDK 17，并在 4.4 的 `conf/neo4j.conf` 或启动脚本里设置 `JAVA_HOME`。

### 4.2 解压与目录结构

```bash
# 以 Linux 为例，采用 /opt 作为安装根
tar -xzf neo4j-community-5.25.x-unix.tar.gz -C /opt
# 得到 /opt/neo4j-community-5.25.x，软链方便管理
ln -s /opt/neo4j-community-5.25.x /opt/neo4j
cd /opt/neo4j

# 目录说明
#   bin/    启动命令（neo4j、neo4j-admin）
#   conf/   配置文件（neo4j.conf）
#   data/   数据与日志（databases/neo4j 为默认数据库，需持久化/备份）
#   plugins/ 扩展
```

### 4.3 版本与 Java 校验（可选）

```bash
bin/neo4j console --version || true     # 打印版本
# 若报缺少 Java，安装 JDK17 并在启动前设置：
export JAVA_HOME=/path/to/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
```

### 4.4 修改 `conf/neo4j.conf`（关键：允许内网其他机器连接 + 内存）

Core：默认只监听 localhost。**应用跑在另一台内网机器时，必须放开监听地址**，否则连不上 bolt/7687。

```ini
# 1) 监听几乎所有网卡，允许内网其他主机连接（关键项）
server.default_listen_address=0.0.0.0

# 2) 端口（默认即可，若要改才写）
server.bolt.listen_address=:7687
server.http.listen_address=:7474

# 3) 内存（按内网机器规格调整；Community 版 heap 有上限）
server.memory.heap.initial_size=512m
server.memory.heap.max_size=1G
server.memory.pagecache.size=512m
```

> 若应用与 Neo4j 就在**同一台机**且用 `bolt://localhost:7687`，可不改 `server.default_listen_address`。

### 4.5 设置初始密码（首次启动前执行）

Neo4j **默认账号是 `neo4j`**。Community 5.x 用以下命令直接设置密码（账号密码与 §五 内外网映射保持一致）：

```bash
cd /opt/neo4j
# 5.x 语法
bin/neo4j-admin dbms set-initial-password '强密码'
# 若你拿到的还是 4.4 老包，则是：bin/neo4j-admin set-initial-password '强密码'
```

> 必须在数据库**首次启动前**执行；repeat 也可用于重置（5.x 会提示）。

### 4.6 启动与开机自启

```bash
cd /opt/neo4j
bin/neo4j start        # 后台常驻
bin/neo4j status       # 查看状态
bin/neo4j console      # 前台运行（调试用，Ctrl+C 停止）
# 停止：bin/neo4j stop
```

要开机自启，写一个 systemd unit（内容示例）：

```ini
# /etc/systemd/system/neo4j.service
[Unit]
Description=Neo4j Community
After=network.target

[Service]
Type=forking
WorkingDirectory=/opt/neo4j
ExecStart=/opt/neo4j/bin/neo4j start
ExecStop=/opt/neo4j/bin/neo4j stop
ExecReload=/opt/neo4j/bin/neo4j restart
LimitNOFILE=40000
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now neo4j
```

### 4.7 验证连通

```bash
# HTTP 页面（浏览器可访问 7474 登录，账号 neo4j/强密码）
curl http://127.0.0.1:7474
# 端口监听
ss -lntp | grep -E '7474|7687'
# 应用侧测 Bolt（应用机器上）：若 7687 可连即成功
```

### 4.8 应用连接映射

对应 §五 环境变量，账号/密码必须与 4.5 一致：

```
Spring 侧               Neo4j 侧
SPRING_NEO4J_URI                 bolt://内网Neo4jIP:7687
SPRING_NEO4J_AUTHENTICATION_USERNAME  neo4j（默认账号）
SPRING_NEO4J_AUTHENTICATION_PASSWORD  4.5 设置的强密码
```

### 4.9 防火墙/安全

```bash
# 开放做bolt与http端口（内网其他机器连）
firewall-cmd --add-port=7687/tcp --permanent
firewall-cmd --add-port=7474/tcp --permanent
# 若前端/API 在另一台内网机器的应用需要访问本应用 8080，同样放行
firewall-cmd --add-port=8080/tcp --permanent
firewall-cmd --reload
# 或在云/安全组放行 7687、7474、8080
```

> 生产建议：对外只放开应用访问 Neo4j 的 7687；7474 只在内网调试时开放。默认 `dbms.security.auth_enabled=true`，保持开启。

### 4.10 数据持久化与迁移（可选）

- 数据默认在 `/opt/neo4j/data/databases`，备份/迁移整目录即可（先 `bin/neo4j stop`）。
- 若从本机迁移 Neo4j 数据：`bin/neo4j-admin database dump neo4j` → 拷 `.dump` 到内网 → `bin/neo4j-admin database load neo4j --from=xxx.dump`。
- 本项目首选「内网重新解析生成」而非 dump 迁移（见 §八），dump 仅作备份手段。

---

## 五、应用连接配置（外部化，不改代码）

**方式 A：环境变量（推荐）**

```bash
# MySQL
export SPRING_DATASOURCE_URL='jdbc:mysql://内网MySQLIP:3306/proc_extractor?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
export SPRING_DATASOURCE_USERNAME=pde
export SPRING_DATASOURCE_PASSWORD='xxx'

# Neo4j
export SPRING_NEO4J_URI='bolt://内网Neo4jIP:7687'
export SPRING_NEO4J_AUTHENTICATION_USERNAME=neo4j
export SPRING_NEO4J_AUTHENTICATION_PASSWORD='xxx'

# 脚本/报表目录
export EXTRACTOR_REPORT_DIR='/data/pde/reports'
export EXTRACTOR_DDL_DIR='/data/pde/jiaoben'
export EXTRACTOR_DDL_EXCEL_PATH='/data/pde/jiaoben/columns0818.xlsx'

# 端口（可选，默认 8080）
export SERVER_PORT=8080

# AI 问数密钥（可选，仅启用 nl2sql 时；不设则调用报“未配置密钥”，见 §十一）
export NL2SQL_API_KEY='真实key'
export NL2SQL_BASE_URL='内网LLM网关地址或 http默认 https://api.deepseek.com/v1'
```

**方式 B：外部配置 yml**，在 `/data/pde/conf/application.yml` 只写需覆盖项，启动加参数：

```bash
java -jar /data/pde/proc-dependency-extractor.jar \
  --spring.config.additional-location=file:/data/pde/conf/application.yml
```

---

## 六、脚本/文件落位

```
/data/pde/procs/20260915/     # 各日期目录（存储过程 .py）
/data/pde/procs/20260916/
/data/pde/reports/            # 报表 .cpt/.frm/.xml（若启用报表影响）
```

对应 §五 的 `EXTRACTOR_REPORT_DIR` 与「按目录解析」填的路径。

---

## 七、启动服务

```bash
java -version   # 确认 17
java -jar /data/pde/proc-dependency-extractor.jar
# 生产建议 systemd / nohup 后台运行
```

启动日志出现「Tomcat started on port 8080」即成功。应用能正常启动（Neo4j 约束初始化的连接失败仅 `warn`，不阻止启动）。

> 📦 仓库 `docs/deploy/` 已提供可直接使用的产物：建表脚本 `schema.sql`、环境变量模板 `.env.example`、Linux 启动脚本 `start.sh`、systemd 服务 `pde.service`。推荐方式：`cp .env.example .env` 改真实值 → `systemctl enable --now pde` 或 `./start.sh start`。

> ⚠️ **nl2sql（AI 问数）内网默认不可用**：它调用外网 `api.deepseek.com`。内网隔离时此功能访问报错属预期。如需启用：内网部署 LLM 并改 `nl2sql.base-url` / `api-key`，或在网络策略开放该域名。

---

## 八、首次初始化与血缘生成

1. 确认字段元数据已在（§三），无需导入。
2. **生成存储过程血缘 + Neo4j 图**（内网当前没有这些数据）：
   - 前端 **UploadView →「按目录路径解析」** 填某个日期目录根（如 `/data/pde/procs/20260915`）；或直调 `POST /api/proc/extract/batch` `{"path":"/data/pde/procs/20260915"}`
   - 解析会**同时写 MySQL 血缘并同步 Neo4j**，无需单独导 Neo4j。
3. （可选）报表：前端批量解析填 `EXTRACTOR_REPORT_DIR` 目录。
4. 日常使用：
   - 换新日期 →「按目录解析」填新日期目录（覆盖同名 proc）
   - 内容微调 →「重新解析全部」（仅重刷当前入库那份，不扫新日期）

---

## 九、访问与验证清单

| 检查 | 方式 |
|---|---|
| 前端 | 浏览器 `http://内网IP:8080` |
| 后端就绪 | `GET http://内网IP:8080/api/impact/schemas` 返回 schema 列表 |
| 血缘查询 | 影响分析页选 schema + 表，应有下游结果（验证 Neo4j 生效） |
| 批量影响 | `/schema-impact` 页选 schema 跑一遍 |
| 查看脚本 | proc 详情「查看脚本」能读到内网 .py（验证 `file_path` 配套） |

---

## 十、内网注意事项汇总

1. **构建在外网、运行在内网** —— jar 必须在联网机打。
2. **nl2sql 依赖外网** —— 内网隔离时不可用，需另行处理或接受缺省。
3. **`file_path` 配套** —— 首次务必用「按目录解析」在内网生成，让 `proc_info.file_path` 指向内网真实路径。
4. **Neo4j 首次约束自动建** —— 只需确账号密码可连。
5. **字段元数据已有** —— 别跑 `import`（会清空重导）。

---

## 十一、上线前：密钥清理与报表路径要求（必读，具体操作）

### 11.1 移除代码库中的明文密钥（在本机/联网机执行，部署前）

**要求**：代码库（含后续提交）不再保存任何真实密钥，一律走环境变量注入。

**步骤：**
1. 编辑 `src/main/resources/application.yml` 的 `nl2sql:` 段，将 api-key 的默认值去掉：
   ```yaml
   nl2sql:
     # 密钥一律走环境变量注入，代码库不保存明文（值缺失时调用方会明确报"未配置密钥"）
     api-key: ${NL2SQL_API_KEY:}
     base-url: ${NL2SQL_BASE_URL:https://api.deepseek.com/v1}
   ```
   > 值留空而非写一个假 key：留空时调用会明确报"未配置密钥"，便于定位；写假 key 只能换来 401，且同样进库。
2. 全仓扫描确认无其它明文密钥：
   ```bash
   # 在 git 仓库根执行
   grep -rn --include="*.yml" --include="*.yaml" --include="*.properties" --include="*.json" -E "sk-[A-Za-z0-9]{20,}" src | head -20
   ```
   无输出为通过。若命中，一并改成 `${环境变量}` 形式。
3. Commit 本次改动。**注意**：若 git 历史里已含真实 key，`grep` 只查工作区看不到历史——共享内网代码库前要么用 `git filter-repo` 清历史、要么轮换该 key；最低要求是**从本次提交起不再带 key**。
4. 本地/内网都通过环境变量注入（见 §五 末尾）：
   ```bash
   export NL2SQL_API_KEY='真实key'
   export NL2SQL_BASE_URL='内网LLM网关地址或省略保持默认'
   ```

### 11.2 报表 `report_info.file_path` 路径要求

**要求**：报表清单「文件所在路径」列应指向内网真实路径；报表血缘不依赖该字段，仅影响展示与重新解析定位。

**步骤：**
1. 内网首次部署完成后，对报表目录执行一次「重新解析全部」或「按目录解析」(填 `EXTRACTOR_REPORT_DIR` 对应目录)。`parseAndSave` 会把该报表的 `file_path` 重写为服务器路径。
2. 校验路径是否已指向服务器：
   ```sql
   SELECT file, file_path FROM report_info WHERE file_path LIKE 'd:%' OR file_path LIKE '%:\\%';
   -- 期望结果：无记录或已由「重新解析」覆盖为内网路径
   ```
   正常情况下直接看完整清单即可：
   ```sql
   SELECT file, file_path FROM report_info LIMIT 20;
   ```
3. 若内网承接旧库拷贝（`report_info` 里 file_path 是本机盘符 `d:/...`），**不必手工改库**：在服务器上重新解析一次该报表即覆盖；在重新解析前，报表清单的路径列会显示旧路径（仅展示层，不影响血缘/数据集/字段映射）。

### 11.3 nl2sql（AI 问数）内网处理

- **不启用（默认）**：内网不设 `NL2SQL_API_KEY`、不部署 LLM。此时调用该功能返回错误属**预期行为**——部署验收按"接口可达、业务报未配置密钥"判定，不算缺陷。
- **启用**：内网部署 LLM 网关，设置环境变量 `NL2SQL_API_KEY`、`NL2SQL_BASE_URL`（见 §五）后重启即可切换，无需改代码。
- **完全隐藏入口**：当前前端一直渲染入口；如需内网彻底不显示问数入口，属后续迭代需求（需前端改动），本版本不包含。

### 11.4 上线验收快速清单

| 项 | 验收命令 / 行为 |
|---|---|
| 后端就绪 | `GET http://内网IP:8080/api/impact/schemas` 返回列表 |
| MySQL 连通 | 影响分析页能查 schema/表 |
| Neo4j 生效 | 血缘查询返回下游结果 |
| 报表清单路径 | `SELECT file_path FROM report_info` 指向服务器路径 |
| 旧库 path 覆盖 | 重新解析某报表后其 file_path 变为内网路径 |
| 密钥 | `grep -rn "sk-" src` 无结果；仅经 `NL2SQL_API_KEY` 注入 |
| nl2sql | 不加密钥时调用报"未配置密钥"（预期） |