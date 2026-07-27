<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-size: 16px; font-weight: bold;">导入存储过程脚本</span>
          <el-button type="primary" plain @click="goList">
            <el-icon><View /></el-icon>&nbsp;查看列表
          </el-button>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <!-- 方式1: 文件上传 -->
        <el-tab-pane label="上传 PY 文件" name="upload">
          <el-upload
            ref="uploadRef"
            drag
            multiple
            accept=".py"
            :auto-upload="false"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            :file-list="fileList"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">
              将 .py 文件拖到此处,或<em>点击选择</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                仅支持 .py 格式的存储过程脚本文件,可同时上传多个
              </div>
            </template>
          </el-upload>

          <div style="margin-top: 20px; text-align: center;">
            <el-button
              type="primary"
              :loading="uploading"
              :disabled="fileList.length === 0"
              @click="submitUpload"
            >
              <el-icon><Upload /></el-icon>
              开始解析并入库({{ fileList.length }} 个文件)
            </el-button>
            <el-button @click="clearFiles">清空</el-button>
          </div>
        </el-tab-pane>

        <!-- 方式2: 目录路径 -->
        <el-tab-pane label="按目录路径解析" name="dir">
          <el-form @submit.prevent>
            <el-form-item label="脚本目录">
              <el-input
                v-model="dirPath"
                placeholder="例如: d:/HB/work_zuoye/jiaoben"
                clearable
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="dirLoading"
                :disabled="!dirPath"
                @click="submitDir"
              >
                <el-icon><FolderOpened /></el-icon>
                开始解析
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            title="将扫描指定目录(含子目录)下所有 .py 文件进行解析"
            type="info"
            :closable="false"
          />
        </el-tab-pane>

        <!-- 方式3: 重新解析全部 -->
        <el-tab-pane label="重新解析全部" name="rebuild">
          <el-alert
            title="将根据已入库的存储过程记录,按其文件路径重新解析并覆盖入库"
            type="warning"
            :closable="false"
            show-icon
          />
          <div style="margin-top: 20px;">
            <el-button
              type="warning"
              :loading="rebuildLoading"
              @click="submitRebuild"
            >
              <el-icon><Refresh /></el-icon>
              重新解析全部
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 结果展示 -->
      <el-dialog v-model="resultVisible" title="解析结果" width="600px">
        <el-result
          :icon="resultIcon"
          :title="resultTitle"
          :sub-title="resultSubTitle"
        >
          <template #extra>
            <div v-if="result && result.failures && result.failures.length" style="text-align: left;">
              <el-divider content-position="left">失败详情</el-divider>
              <ul>
                <li v-for="(f, i) in result.failures" :key="i" style="color: #f56c6c;">{{ f }}</li>
              </ul>
            </div>
            <el-button type="primary" @click="resultVisible = false; goList()">查看列表</el-button>
          </template>
        </el-result>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Upload, UploadFilled, View, FolderOpened, Refresh
} from '@element-plus/icons-vue'
import { uploadFiles, extractByDir, reExtractAll } from '../api/proc'

const router = useRouter()
const activeTab = ref('upload')

// 上传相关
const uploadRef = ref()
const fileList = ref([])
const uploading = ref(false)

// 目录相关
const dirPath = ref('')
const dirLoading = ref(false)

// 重新解析
const rebuildLoading = ref(false)

// 结果对话框
const resultVisible = ref(false)
const result = ref(null)
const resultIcon = ref('success')
const resultTitle = ref('')
const resultSubTitle = ref('')

function handleFileChange(file, files) {
  // 仅保留 .py 文件
  fileList.value = files.filter((f) => f.name.endsWith('.py'))
}

function handleFileRemove(file, files) {
  fileList.value = files.filter((f) => f.name.endsWith('.py'))
}

function clearFiles() {
  fileList.value = []
  uploadRef.value && uploadRef.value.clearFiles()
}

async function submitUpload() {
  if (fileList.value.length === 0) {
    ElMessage.warning('请先选择 .py 文件')
    return
  }
  uploading.value = true
  try {
    const rawFiles = fileList.value.map((f) => f.raw)
    const res = await uploadFiles(rawFiles)
    showResult(res.data)
  } catch (e) {
    // 拦截器已处理提示
  } finally {
    uploading.value = false
  }
}

async function submitDir() {
  dirLoading.value = true
  try {
    const res = await extractByDir(dirPath.value)
    showResult(res.data)
  } catch (e) {
    // 拦截器已处理提示
  } finally {
    dirLoading.value = false
  }
}

async function submitRebuild() {
  rebuildLoading.value = true
  try {
    const res = await reExtractAll()
    showResult(res.data)
  } catch (e) {
    // 拦截器已处理提示
  } finally {
    rebuildLoading.value = false
  }
}

function showResult(data) {
  result.value = data
  if (!data) {
    resultIcon.value = 'error'
    resultTitle.value = '无结果'
    resultSubTitle.value = ''
  } else if (data.failCount > 0 && data.successCount === 0) {
    resultIcon.value = 'error'
    resultTitle.value = '解析失败'
    resultSubTitle.value = `共 ${data.totalCount} 个,失败 ${data.failCount} 个`
  } else if (data.failCount > 0) {
    resultIcon.value = 'warning'
    resultTitle.value = '部分成功'
    resultSubTitle.value = `共 ${data.totalCount} 个,成功 ${data.successCount} 个,失败 ${data.failCount} 个`
  } else {
    resultIcon.value = 'success'
    resultTitle.value = '解析成功'
    resultSubTitle.value = `共解析 ${data.successCount} 个存储过程`
  }
  resultVisible.value = true
}

function goList() {
  router.push('/procs')
}
</script>

<style scoped>
.el-upload-dragger {
  width: 100%;
}
</style>
