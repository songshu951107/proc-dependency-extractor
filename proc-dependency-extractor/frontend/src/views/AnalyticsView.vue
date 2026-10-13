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