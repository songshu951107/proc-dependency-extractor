# 报表列表 + 数据集详情下钻 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在报表影响分析页新增「报表列表」按钮，弹出分页+搜索的报表清单（序号/报表名/文件所在路径/解析时间）；点报表名下钻到 tab 布局弹窗，展示每数据集 SQL 详情、连接数据库、数据集信息、源表与字段映射。

**架构：** 后端给 `report_info` 加 `file_path` 列（解析时存路径、旧数据兜底拼接）；复用 `GET /api/report/list` 做清单分页+搜索；新增 `GET /api/report/{file}/datasets` 下钻接口，用现有 `ReportSqlParser.extractTables/extractColumns` 实时解析 sqlText 出源表与字段映射。前端新增两个弹窗组件并接入入口按钮。

**技术栈：** Spring Boot 3.2.5 (jakarta) / MyBatis-Plus / Vue3 + Element Plus / Apache POI（无关）/ Maven。

**规格参考：** `docs/superpowers/specs/2026-09-17-report-list-drilldown-design.md`

---

## 文件结构

- 修改 `src/main/java/com/hb/extractor/entity/ReportInfo.java`：加 `filePath` 字段。
- 修改 `src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java`：`parseAndSave` 存路径；`listReports` 路径兜底；新增 `getReportDatasetsDetail(String file)`。
- 修改 `src/main/java/com/hb/extractor/controller/ReportController.java`：新增 `GET /api/report/{file}/datasets`。
- 创建 `src/main/java/com/hb/extractor/dto/ReportDatasetDetailVO.java`：下钻返回数据结构。
- 修改 `src/test/java/com/hb/extractor/service/impl/ReportParseServiceImplTest.java`（新建）：测试下钻解析组装。
- 修改 `frontend/src/api/report.js`：新增下钻接口。
- 创建 `frontend/src/components/report/ReportListDialog.vue`：报表清单弹窗。
- 创建 `frontend/src/components/report/ReportDatasetDrilldownDialog.vue`：数据集下钻弹窗。
- 修改 `frontend/src/views/ReportImpactView.vue`：新增「报表列表」按钮与两个弹窗。

---

### 任务 1：后端存路径 + 清单路径兜底

**文件：**
- 修改：`src/main/java/com/hb/extractor/entity/ReportInfo.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java`

- [ ] **步骤 1：给 `ReportInfo` 加 `filePath` 字段**

在 `parseTime` 前加入：

```java
/** 报表文件完整路径 */
private String filePath;
```

- [ ] **步骤 2：`parseAndSave` 写入路径**

在 `reportInfo.setParseTime(LocalDateTime.now());` 前加入：

```java
reportInfo.setFilePath(path.toString());
```

- [ ] **步骤 3：`listReports` 旧数据路径兜底**

将 `listReports` 返回前对 `page.getRecords()` 遍历，`filePath` 为空则拼 `reportDir + "/" + file`：

```java
for (ReportInfo r : page.getRecords()) {
    if (r.getFilePath() == null || r.getFilePath().isBlank()) {
        r.setFilePath(reportDir + "/" + r.getFile());
    }
}
return page;
```

- [ ] **步骤 4：提交 DB 变更与构建验证**

SQL（在目标库执行）：`ALTER TABLE report_info ADD COLUMN file_path VARCHAR(512) NULL;`

运行：`mvn -q compile`（确认实体字段映射无编译错误）。预期：BUILD SUCCESS。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/hb/extractor/entity/ReportInfo.java src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java
git commit -m "feat: report_info 增加 file_path 存储与清单路径兜底"
```

---

### 任务 2：后端下钻接口

**文件：**
- 创建：`src/main/java/com/hb/extractor/dto/ReportDatasetDetailVO.java`
- 创建：`src/test/java/com/hb/extractor/service/impl/ReportParseServiceImplTest.java`
- 修改：`src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java`
- 修改：`src/main/java/com/hb/extractor/controller/ReportController.java`

- [ ] **步骤 1：创建 `ReportDatasetDetailVO`**

```java
package com.hb.extractor.dto;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import lombok.Data;
import java.util.List;

@Data
public class ReportDatasetDetailVO {
    private String datasetName;
    private String connection;
    private Integer tableCount;
    private String sqlText;
    private List<TableRef> sourceTables;
    private List<ColumnMapping> fieldMappings;
}
```

- [ ] **步骤 2：编写下钻组装方法的失败测试**

新建 `ReportParseServiceImplTest.java`（`@SpringBootTest`，自动注入 `ReportParseServiceImpl` 与 `reportSqlParser`），测试给定 SQL 组装出源表与字段映射：

```java
package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ReportDatasetDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportParseServiceImplTest {

    @Autowired
    private ReportParseServiceImpl service;

    @Test
    void testBuildDatasetDetail_mapsSourceTablesAndFields() {
        String sql = "SELECT a.CUST_NO, a.ACTUALARTIFICIALNO 合同文本号 FROM GMDATA.G_AG_ZWLOAN_HKMX a";
        ReportDatasetDetailVO vo = service.buildDatasetDetail("ds1", "fin_ods", sql);
        assertEquals("ds1", vo.getDatasetName());
        assertEquals("fin_ods", vo.getConnection());
        assertEquals(1, vo.getSourceTables().size());
        assertEquals("GMDATA.G_AG_ZWLOAN_HKMX", vo.getSourceTables().get(0).getFullName());
        assertTrue(vo.getFieldMappings().stream().anyMatch(m -> m.getSourceField().equals("ACTUALARTIFICIALNO")));
        assertEquals("合同文本号", vo.getFieldMappings().get(1).getSelectAlias());
    }
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`mvn -q test -Dtest=ReportParseServiceImplTest -pl .`
预期：编译失败，`buildDatasetDetail` 不存在。

- [ ] **步骤 4：实现 `buildDatasetDetail`**

在 `ReportParseServiceImpl` 加入：

```java
@Override
public ReportDatasetDetailVO buildDatasetDetail(String dsName, String connection, String sqlText) {
    ReportDatasetDetailVO vo = new ReportDatasetDetailVO();
    vo.setDatasetName(dsName);
    vo.setConnection(connection);
    vo.setSqlText(sqlText);
    vo.setSourceTables(reportSqlParser.extractTables(sqlText));
    vo.setFieldMappings(reportSqlParser.extractColumns(sqlText));
    vo.setTableCount(vo.getSourceTables().size());
    return vo;
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q test -Dtest=ReportParseServiceImplTest -pl .`
预期：PASS。

- [ ] **步骤 6：新增下钻 HTTP 端点**

在 `ReportController` 加入（`getReportDetail` 已存在）：

```java
@GetMapping("/{file}/datasets")
public Result<List<ReportDatasetDetailVO>> datasets(@PathVariable String file) {
    if (reportParseService.getReportDetail(file) == null) {
        return Result.error(404, "报表不存在");
    }
    return Result.success(reportParseService.getReportDatasets(file));
}
```

- [ ] **步骤 7：实现 `getReportDatasets`**

在 `ReportParseServiceImpl` 加入：

```java
@Override
public List<ReportDatasetDetailVO> getReportDatasets(String file) {
    List<ReportDataset> datasets = reportDatasetMapper.selectList(
            new LambdaQueryWrapper<ReportDataset>().eq(ReportDataset::getReportFile, file));
    List<ReportDatasetDetailVO> result = new ArrayList<>();
    for (ReportDataset ds : datasets) {
        result.add(buildDatasetDetail(ds.getDatasetName(), ds.getConnection(), ds.getSqlText()));
    }
    return result;
}
```

注意 URL 顺序：`/{file}` 与 `/{file}/datasets` 均存在时，Spring 按方法映射精确匹配，`datasets` 端点不影响 `detail`/`delete`。`ReportParseService` 接口需补两个方法签名：`buildDatasetDetail`、`getReportDatasets`。

- [ ] **步骤 8：回归测试**

运行：`mvn -q test`
预期：全部 PASS（含 `ReportSqlParserTest`）。

- [ ] **步骤 9：Commit**

```bash
git add src/main/java/com/hb/extractor/dto/ReportDatasetDetailVO.java src/main/java/com/hb/extractor/service/impl/ReportParseServiceImpl.java src/main/java/com/hb/extractor/service/ReportParseService.java src/main/java/com/hb/extractor/controller/ReportController.java src/test/java/com/hb/extractor/service/impl/ReportParseServiceImplTest.java
git commit -m "feat: 报表数据集下钻接口(源表+字段映射)"
```

---

### 任务 3：前端 API + 报表清单弹窗 + 入口按钮

**文件：**
- 修改：`frontend/src/api/report.js`
- 创建：`frontend/src/components/report/ReportListDialog.vue`
- 修改：`frontend/src/views/ReportImpactView.vue`

- [ ] **步骤 1：追加下钻 API**

在 `frontend/src/api/report.js` 加入（`getReportDetail` 之后）：

```js
export function getReportDatasets(file) {
  return request({ url: `/report/${encodeURIComponent(file)}/datasets`, method: 'get' })
}
```

- [ ] **步骤 2：创建 `ReportListDialog.vue`**

清单弹窗（分页 + 搜索 + 表格），点报表名下钻触发 `select-report` 事件：

```vue
<template>
  <el-dialog :model-value="modelValue" title="报表清单" width="720px" @update:model-value="v => emit('update:modelValue', v)">
    <el-input v-model="kw" placeholder="按报表名搜索" clearable style="margin-bottom: 12px"
              @input="reload()" />
    <el-table :data="rows" v-loading="loading" border size="small" max-height="420">
      <el-table-column label="序号" width="70" align="center">
        <template #default="{ $index }">{{ (page - 1) * size + $index + 1 }}</template>
      </el-table-column>
      <el-table-column label="报表名" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="emit('select-report', row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="文件所在路径" min-width="260" show-overflow-tooltip prop="filePath" />
      <el-table-column label="解析时间" width="170" prop="parseTime" />
    </el-table>
    <el-pagination background layout="total, prev, pager, next"
                   :total="total" :current-page="page" :page-size="size"
                   @current-change="p => { page = p; reload() }" style="margin-top: 12px" />
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getReportList } from '../../api/report'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue', 'select-report'])

const kw = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function reload() {
  loading.value = true
  try {
    const res = await getReportList({ pageNum: page.value, pageSize: size.value, name: kw.value || undefined })
    const d = res.data || {}
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally { loading.value = false }
}

watch(() => props.modelValue, v => { if (v) { page.value = 1; reload() } })
watch(kw, () => { page.value = 1 })
</script>
```

- [ ] **步骤 3：入口按钮与清单弹窗接入**

在 `ReportImpactView.vue`：
1. 模板中，在「报表解析」按钮前加「报表列表」按钮，并加两个弹窗：

```vue
<el-button type="info" plain class="parse-entry-btn" style="right: 104px" @click="reportListDialogVisible = true">
  报表列表
</el-button>

<ReportListDialog v-model="reportListDialogVisible"
                  @select-report="openDrilldown" />
<ReportDatasetDrilldownDialog
                  :model-value="drilldownVisible"
                  :report="drilldownReport"
                  @update:model-value="drilldownVisible = $event" />
```

2. script 中引入组件与状态：

```js
import ReportListDialog from '../components/report/ReportListDialog.vue'
import ReportDatasetDrilldownDialog from '../components/report/ReportDatasetDrilldownDialog.vue'

const reportListDialogVisible = ref(false)
const drilldownVisible = ref(false)
const drilldownReport = ref(null)
function openDrilldown(report) {
  drilldownReport.value = report
  drilldownVisible.value = true
}
```

（`ReportListDialog`、`ReportDatasetDrilldownDialog` 需注册进当前组件的组件树。）

- [ ] **步骤 4：手工验证清单弹窗**

启动前后端，点「报表列表」：分页与报表名搜索生效、路径列显示（旧数据为拼接路径）、有数据。
预期：正常。

- [ ] **步骤 5：Commit**

```bash
git add frontend/src/api/report.js frontend/src/components/report/ReportListDialog.vue frontend/src/views/ReportImpactView.vue
git commit -m "feat: 报表清单弹窗(分页+搜索)与入口按钮"
```

---

### 任务 4：数据集下钻弹窗

**文件：**
- 创建：`frontend/src/components/report/ReportDatasetDrilldownDialog.vue`
- 修改：`frontend/src/components/report/ReportListDialog.vue`（不改，清单已触发 `select-report`）
- 修改：`frontend/src/views/ReportImpactView.vue`（已在任务3接入）

- [ ] **步骤 1：创建 `ReportDatasetDrilldownDialog.vue`**

tab 布局（左侧数据集列表、右侧详情），打开时按 `report.file` 拉取下钻数据：

```vue
<template>
  <el-dialog :model-value="modelValue" :title="'数据集详情 · ' + (report?.name || '')"
             width="860px" @update:model-value="v => emit('update:modelValue', v)">
    <el-row :gutter="12" style="height: 480px">
      <el-col :span="6" style="border-right: 1px solid #e8e8e8; padding-right: 8px; overflow-y: auto">
        <div
          v-for="(ds, i) in detailList" :key="i"
          style="cursor:pointer; padding: 8px 10px; border-radius: 4px;"
          :style="active === i ? { background: '#f0f2ff', color: '#4B3FE3' } : {}"
          @click="active = i">
          <div style="font-weight:500">{{ ds.datasetName }}</div>
          <div style="font-size:12px; color:#999">{{ ds.connection }}</div>
        </div>
      </el-col>
      <el-col :span="18">
        <div v-if="!current">
          <el-empty description="该报表无可展示数据集" />
        </div>
        <template v-else>
          <h4 style="margin:4px 0 8px">数据集信息</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="数据集名">{{ current.datasetName }}</el-descriptions-item>
            <el-descriptions-item label="连接数据库">{{ current.connection }}</el-descriptions-item>
            <el-descriptions-item label="涉及表数">{{ current.tableCount }}</el-descriptions-item>
          </el-descriptions>

          <h4 style="margin:12px 0 8px">源表</h4>
          <el-tag v-for="(t, i) in current.sourceTables" :key="i" size="small" style="margin:0 6px 6px 0">
            {{ t.fullName }} {{ t.alias ? '(' + t.alias + ')' : '' }}
          </el-tag>

          <h4 style="margin:12px 0 8px">字段映射</h4>
          <el-table :data="current.fieldMappings" border size="small" max-height="120">
            <el-table-column prop="selectAlias" label="别名" />
            <el-table-column label="源表 | 源字段">
              <template #default="{ row }">{{ row.sourceTable }}.{{ row.sourceField }}</template>
            </el-table-column>
            <el-table-column prop="sourceExpr" label="源表达式" show-overflow-tooltip />
          </el-table>

          <h4 style="margin:12px 0 8px">SQL 详情</h4>
          <div style="display:flex; justify-content:flex-end; margin-bottom:6px">
            <el-button size="small" @click="copySql">复制</el-button>
          </div>
          <pre style="margin:0; padding:12px; background:#f5f7fa; border:1px solid #e8e8e8;
               border-radius:4px; font-size:12px; max-height:180px; overflow:auto; white-space:pre-wrap; word-break:break-all">{{ current.sqlText }}</pre>
        </template>
      </el-col>
    </el-row>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReportDatasets } from '../../api/report'

const props = defineProps({ modelValue: { type: Boolean, default: false }, report: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])

const detailList = ref([])
const active = ref(0)
const current = computed(() => detailList.value && active.value < detailList.value.length
  ? detailList.value[active.value] : null)

watch(() => props.modelValue, async v => {
  if (!v || !props.report) return
  try {
    const res = await getReportDatasets(props.report.file)
    detailList.value = res.data || []
    active.value = 0
  } catch (e) { ElMessage.error('加载数据集详情失败') }
})

async function copySql() {
  if (current.value?.sqlText) {
    try { await navigator.clipboard.writeText(current.value.sqlText); ElMessage.success('已复制') }
    catch { ElMessage.error('复制失败') }
  }
}
</script>
```

- [ ] **步骤 2：手工验证下钻弹窗**

清单弹窗点某一报表名 → 打开下钻弹窗；左侧数据集切换；右侧展示连接数据库/表数/源表/字段映射/SQL 可滚动可复制；无数据集报表显示空态。
预期：正常。

- [ ] **步骤 3：Commit**

```bash
git add frontend/src/components/report/ReportDatasetDrilldownDialog.vue
git commit -m "feat: 数据集下钻弹窗(tab布局 + 源表/字段映射/SQL)"
```

---

## 自检

- **规格覆盖度：** 路径存储(任务1) / 清单分页+搜索+路径兜底(任务1) / 下钻接口含连接、表数、SQL、源表、字段映射(任务2) / 入口按钮 + 清单弹窗(任务3) / 下钻 tab 弹窗 + 空态 + 复制(任务4)。全覆盖。
- **占位符：** 无 TODO、"适当错误处理"类占位；每步带实际代码。
- **类型一致性：** 全链路同名 `buildDatasetDetail` / `getReportDatasets` / `getReportDatasets(file)`；VO 字段与前端引用 `datasetName/connection/tableCount/sqlText/sourceTables/fullName/alias/fieldMappings/selectAlias/sourceTable/sourceField/sourceExpr` 前后一致。