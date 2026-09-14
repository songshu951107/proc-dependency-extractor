# 血缘重同步与 Transform 节点覆盖问题说明

## 1. 背景：为什么需要重新同步

血缘数据是「脚本 → MySQL 解析数据 → Neo4j 图 → 影响分析」的流水线：

| 环节 | 动作 | 接口 |
|------|------|------|
| 脚本 → MySQL | 解析 | POST /api/proc/extract |
| MySQL → Neo4j | 同步 | POST /api/impact/resync* |

- 修改 SQL 解析逻辑（预处理、字段归属、临时表识别等）→ 需重解析刷新 MySQL，再 re-sync Neo4j。
- 修改同步/图模型逻辑（如本次 generateTransformId）→ 只需 re-sync Neo4j，无需重解析。

## 2. 本次问题与修复

### 现象
查询字段上游影响时，到中间某列后链路中断，查不到真正的物理源字段。

### 根因
同一 proc 内多个块（UNION 分支）写入同一目标列时，生成的 Transform 节点 id 为 md5(proc_name + "|" + target_col_key)，多块共享同一个 Transform 节点。MERGE 后处理顺序使最后一块覆盖节点的 expression / logic_type。典型结果：真实源（DIRECT）被常量块（LITERAL）覆盖 → 节点被判为 LITERAL → BFS 上游扩展被剪枝 → 链路中断。

### 修复
generateTransformId 加入 fieldMappingId 作为区分因子，让每个 FieldMapping 各自持有独立的 Transform 节点，避免不同块互相覆盖 logic_type。

涉及文件：
- Neo4jSyncServiceImpl.java：generateTransformId(procName, targetColKey, fieldMappingId) 及同步循环调用处。

## 3. 接口说明

| 接口 | 方法 | 用途 |
|------|------|------|
| /api/impact/resync?procName=xxx | POST | 单个 proc 重新同步 Neo4j |
| /api/impact/resync/all | POST | 全量重新同步 Neo4j |
| /api/impact/clear-neo4j | POST | 清空整个 Neo4j 图 |
| /api/impact/sync-status | GET | 查看同步状态 |

## 4. 手动执行

### 4.1 单个 proc
curl -X POST "http://localhost:8080/api/impact/resync?procName=dwa_pid_a_g_ln_new_pord_sum"

### 4.2 全量重同步
直接前台调用可能因客户端超时被中断，建议后台分离进程执行：

```powershell
Start-Process powershell -ArgumentList '-NoProfile','-Command',
  'try { $r = Invoke-RestMethod -Uri "http://localhost:8080/api/impact/resync/all" -Method Post -TimeoutSec 7200;
         $r | ConvertTo-Json -Depth 6 | Out-File "d:\HB\work_zuoye\proc-dependency-extractor\resync_all_result.json" }
   catch { $_ | Out-String | Out-File "d:\HB\work_zuoye\proc-dependency-extractor\resync_all_error.txt" }'
```

```bash
curl -X POST --max-time 7200 "http://localhost:8080/api/impact/resync/all" > resync_all_result.json
```

### 4.3 可选：先清空再全量重建
Invoke-RestMethod -Uri 'http://localhost:8080/api/impact/clear-neo4j' -Method Post
然后再执行 4.2 的全量重同步。

### 4.4 监控进度
Invoke-RestMethod -Uri 'http://localhost:8080/api/impact/sync-status' -Method Get

或查询 MySQL 日志表：
SELECT sync_status, COUNT(*) FROM neo4j_sync_log WHERE update_time >= NOW() - INTERVAL 30 MINUTE GROUP BY sync_status;

## 5. 验证结果
- 全量重同步：totalCount=299 / successCount=299 / failedCount=0
- 抽查 dwa_pid_a_g_money_flow_qty.org_no：DIRECT 节点已独立，携带真实源 pid.a_dm_zbpt_org.org_id，LITERAL 常量不再污染上游链路。

## 6. 注意事项
- 后端需用 JDK 17 启动，路径上的默认 java 是 JDK 11，会导致 UnsupportedClassVersionError。
- 修改代码后必须 mvn package 重新生成 jar 并重启应用，否则跑的是旧 jar，产出陈旧结果。
- neo4j_sync_log 为跨多次运行的累计表，统计本次进度需按 update_time 时间段过滤。