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
      <el-card style="flex:2;position:relative">
        <div ref="trendRef" style="height:300px"></div>
        <div v-if="!trendData.length" class="chart-empty">
          <el-empty description="暂无数据" :image-size="80" />
        </div>
      </el-card>
      <el-card style="flex:1;position:relative">
        <div ref="hourRef" style="height:300px"></div>
        <div v-if="!hourData.some(h => h.c > 0)" class="chart-empty">
          <el-empty description="暂无数据" :image-size="80" />
        </div>
      </el-card>
    </div>

    <!-- 3. 功能热度区 -->
    <div style="display:flex;gap:12px;margin-bottom:16px">
      <el-card style="flex:1;position:relative">
        <div ref="pageTopRef" style="height:300px"></div>
        <div v-if="!pageTopData.length" class="chart-empty">
          <el-empty description="暂无数据" :image-size="80" />
        </div>
      </el-card>
      <el-card style="flex:1;position:relative">
        <div ref="pageTrendRef" style="height:300px"></div>
        <div v-if="!pageTrendData.series.length" class="chart-empty">
          <el-empty description="暂无数据" :image-size="80" />
        </div>
      </el-card>
    </div>

    <!-- 4. 用户明细区 -->
    <el-card>
      <div style="margin-bottom:12px">
        <el-input v-model="keyword" placeholder="搜索用户名 / 姓名" clearable
                  style="width:240px" @input="onSearch" />
      </div>
      <el-table :data="activity" border height="300" empty-text="暂无数据" @row-click="onUserClick">
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
const trendData = ref([])
const hourData = ref([])
const pageTopData = ref([])
const pageTrendData = ref({ dates: [], series: [] })
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
  }).catch(() => {})
}

function loadTrend() {
  return trend(days.value).then(r => {
    const data = r.data
    trendData.value = data
    renderChart('trend', trendRef.value, {
      tooltip: { trigger: 'axis' }, legend: { data: ['DAU', '登录次数'] },
      xAxis: { type: 'category', data: data.map(t => t.d) },
      yAxis: { type: 'value' },
      series: [
        { name: 'DAU', type: 'line', smooth: true, data: data.map(t => t.dau) },
        { name: '登录次数', type: 'line', smooth: true, data: data.map(t => t.loginSucc) }
      ]
    }, p => openDayDrill(p.name))
  }).catch(() => {})
}

function loadHour() {
  return hourDistribution(days.value).then(r => {
    const data = r.data
    hourData.value = data
    renderChart('hour', hourRef.value, {
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: data.map(t => t.hour + '时') },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: data.map(t => t.c) }]
    })
  }).catch(() => {})
}

function loadPageTop() {
  return pageTop(days.value).then(r => {
    pageTopData.value = r.data
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
  }).catch(() => {})
}

function loadPageTrend() {
  return pageTrend(days.value, 3).then(r => {
    pageTrendData.value = r.data
    const { dates, series } = r.data
    renderChart('pageTrend', pageTrendRef.value, {
      tooltip: { trigger: 'axis' }, legend: {},
      xAxis: { type: 'category', data: dates },
      yAxis: { type: 'value' },
      series: series.map(s => ({ name: s.name, type: 'line', smooth: true, data: s.data }))
    })
  }).catch(() => {})
}

function loadActivity() {
  return userActivity(days.value, keyword.value).then(r => { activity.value = r.data }).catch(() => {})
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

<style scoped>
.chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
</style>
