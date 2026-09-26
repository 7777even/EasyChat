<template>
  <div>
    <div class="top-panel">
      <el-card>
        <el-form :model="searchForm" label-width="80px" label-position="right">
          <el-row>
            <el-col :span="6">
              <el-form-item label="关键词">
                <el-input v-model="searchForm.keyword" clearable placeholder="词条模糊匹配" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="级别">
                <el-select v-model="searchForm.level" clearable placeholder="全部" style="width: 100%">
                  <el-option label="提醒" :value="1" />
                  <el-option label="替换" :value="2" />
                  <el-option label="禁止发送" :value="3" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="状态">
                <el-select v-model="searchForm.status" clearable placeholder="全部" style="width: 100%">
                  <el-option label="启用" :value="1" />
                  <el-option label="停用" :value="0" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6" :style="{ paddingLeft: '10px' }">
              <el-button type="success" @click="loadDataList()">查询</el-button>
              <el-button @click="resetSearch()">重置</el-button>
            </el-col>
          </el-row>
        </el-form>
      </el-card>
    </div>

    <el-card class="table-data-card">
      <div class="toolbar">
        <el-button type="primary" @click="openSave()">新增词条</el-button>
        <el-button @click="importVisible = true">批量导入</el-button>
        <el-button @click="exportWords">导出 CSV</el-button>
      </div>
      <Table
        :columns="columns"
        :fetch="loadDataList"
        :dataSource="tableData"
        :options="tableOptions"
      >
        <template #slotLevel="{ index, row }">
          <el-tag size="small" :type="levelTagType(row.level)">{{ levelText(row.level) }}</el-tag>
        </template>
        <template #slotStatus="{ index, row }">
          <div>
            <span v-if="row.status == 1" style="color: #67c23a">启用</span>
            <span v-else style="color: #909399">停用</span>
          </div>
        </template>
        <template #slotCreateTime="{ index, row }">
          <span>{{ formatTime(row.createTime) }}</span>
        </template>
        <template #slotOperation="{ index, row }">
          <div class="row-op-panel">
            <a href="javascript:void(0)" @click="openSave(row)">编辑</a>
            <a href="javascript:void(0)" @click="removeWord(row)">删除</a>
          </div>
        </template>
      </Table>
    </el-card>

    <!-- 新增/编辑词条 -->
    <el-dialog :title="saveForm.id ? '编辑词条' : '新增词条'" v-model="saveVisible" width="460px">
      <el-form :model="saveForm" label-width="80px">
        <el-form-item label="词条" required>
          <el-input
            v-model="saveForm.word"
            maxlength="50"
            show-word-limit
            placeholder="敏感词内容（最长 50 字）"
          />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="saveForm.level" style="width: 100%">
            <el-option label="提醒" :value="1" />
            <el-option label="替换" :value="2" />
            <el-option label="禁止发送" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="saveForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入 -->
    <el-dialog title="批量导入词条" v-model="importVisible" width="520px">
      <el-form :model="importForm" label-width="90px">
        <el-form-item label="文件">
          <input type="file" ref="fileInput" accept=".txt,.csv" @change="onFileChange" />
          <div class="import-tip">
            支持 .txt（一行一词，统一使用下方级别与状态）或 .csv（三列 word,level,status，可带表头）；
            最大 5000 行 / 2MB，重复词条自动跳过，坏行计入失败。
          </div>
        </el-form-item>
        <el-form-item label="统一级别">
          <el-select v-model="importForm.level" style="width: 100%" :disabled="importForm.isCsv">
            <el-option label="提醒" :value="1" />
            <el-option label="替换" :value="2" />
            <el-option label="禁止发送" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="统一状态">
          <el-radio-group v-model="importForm.status" :disabled="importForm.isCsv">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import Table from '@/components/Table.vue'
import { getCurrentInstance, ref, reactive } from 'vue'
const { proxy } = getCurrentInstance()

const tableData = ref({})
const tableOptions = {}

const searchForm = reactive({
  keyword: '',
  level: null,
  status: null
})

const saveVisible = ref(false)
const saveForm = reactive({
  id: null,
  word: '',
  level: 3,
  status: 1
})

const importVisible = ref(false)
const importFile = ref(null)
const fileInput = ref(null)
const importForm = reactive({
  level: 3,
  status: 1,
  isCsv: false
})

const columns = [
  { label: '词条', prop: 'word', minWidth: 220 },
  { label: '级别', prop: 'level', width: 120, scopedSlots: 'slotLevel' },
  { label: '状态', prop: 'status', width: 90, scopedSlots: 'slotStatus' },
  { label: '创建时间', prop: 'createTime', width: 160, scopedSlots: 'slotCreateTime' },
  { label: '操作', prop: 'operation', width: 130, scopedSlots: 'slotOperation' }
]

const levelText = (l) => {
  return { 1: '提醒', 2: '替换', 3: '禁止发送' }[l] || '-'
}
const levelTagType = (l) => {
  return { 1: 'info', 2: 'warning', 3: 'danger' }[l] || 'info'
}
const formatTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(Number(ts))
  if (isNaN(d.getTime())) return '-'
  const p = (n) => (n < 10 ? '0' + n : n)
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const resetSearch = () => {
  searchForm.keyword = ''
  searchForm.level = null
  searchForm.status = null
  loadDataList()
}

const loadDataList = async () => {
  const params = {
    pageNo: tableData.value.pageNo,
    pageSize: tableData.value.pageSize
  }
  Object.assign(params, searchForm)
  const result = await proxy.Request({
    url: proxy.Api.loadSensitiveWord,
    params
  })
  if (!result) {
    return
  }
  Object.assign(tableData.value, result.data)
}

const openSave = (row) => {
  saveForm.id = row ? row.id : null
  saveForm.word = row ? row.word : ''
  saveForm.level = row ? row.level : 3
  saveForm.status = row ? row.status : 1
  saveVisible.value = true
}

const submitSave = async () => {
  if (!saveForm.word || !saveForm.word.trim()) {
    proxy.Message.warning('请输入词条内容')
    return
  }
  const result = await proxy.Request({
    url: proxy.Api.saveSensitiveWord,
    params: {
      id: saveForm.id,
      word: saveForm.word.trim(),
      level: saveForm.level,
      status: saveForm.status
    }
  })
  if (!result) {
    return
  }
  proxy.Message.success(saveForm.id ? '词条已更新，过滤已即时生效' : '词条已新增，过滤已即时生效')
  saveVisible.value = false
  loadDataList()
}

const removeWord = (row) => {
  proxy.Confirm({
    message: `确认要删除词条「${row.word}」吗？删除后可重新导入同一词条。`,
    okfun: async () => {
      const result = await proxy.Request({
        url: proxy.Api.deleteSensitiveWord,
        params: { id: row.id }
      })
      if (!result) {
        return
      }
      proxy.Message.success('已删除，过滤已即时生效')
      loadDataList()
    }
  })
}

const onFileChange = (e) => {
  const file = e.target.files && e.target.files[0]
  importFile.value = file || null
  importForm.isCsv = file ? /\.csv$/i.test(file.name) : false
}

const submitImport = async () => {
  if (!importFile.value) {
    proxy.Message.warning('请选择要导入的文件')
    return
  }
  const formData = new FormData()
  formData.append('file', importFile.value)
  if (!importForm.isCsv) {
    formData.append('level', importForm.level)
    formData.append('status', importForm.status)
  }
  const result = await proxy.Request({
    url: proxy.Api.importSensitiveWord,
    params: formData
  })
  if (!result) {
    return
  }
  const r = result.data || {}
  proxy.Message.success(`导入完成：新增 ${r.success || 0}，跳过 ${r.skipped || 0}，失败 ${r.failed || 0}`)
  importVisible.value = false
  if (fileInput.value) {
    fileInput.value.value = ''
  }
  importFile.value = null
  loadDataList()
}

const exportWords = async () => {
  const blob = await proxy.Request({
    url: proxy.Api.exportSensitiveWord,
    method: 'GET',
    responseType: 'blob',
    showError: true
  })
  if (!blob) {
    return
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'sensitive-words.csv'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<style lang="scss" scoped>
.toolbar {
  margin-bottom: 12px;
}
.row-op-panel {
  a {
    margin-right: 8px;
    color: var(--link-color, #409eff);
  }
}
.import-tip {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
