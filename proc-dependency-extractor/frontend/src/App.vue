<template>
  <el-container style="height: 100vh">
    <el-header style="background: #409eff; color: #fff; display: flex; align-items: center;">
      <el-icon style="margin-right: 8px;"><Connection /></el-icon>
      <span style="font-size: 18px; font-weight: bold;">存储过程依赖表名提取系统</span>
    </el-header>
    <el-container>
      <el-aside width="200px" style="background: #304156;">
        <el-menu
          :default-active="$route.path"
          router
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409eff"
        >
          <el-menu-item index="/">
            <el-icon><Upload /></el-icon>
            <span>导入脚本</span>
          </el-menu-item>
          <el-menu-item index="/procs">
            <el-icon><List /></el-icon>
            <span>存储过程列表</span>
          </el-menu-item>
          <el-menu-item index="/impact">
            <el-icon><Aim /></el-icon>
            <span>影响分析</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { Upload, List, Connection, Aim, Document } from '@element-plus/icons-vue'
</script>

<style>
html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  font-family: "Microsoft YaHei", Arial, sans-serif;
}
.el-menu {
  border-right: none;
}
</style>
