<template>
  <div>
    <div class="top-panel">
      <el-card>
        <el-form :model="searchForm" label-width="80px" label-position="right">
          <el-row>
            <el-col :span="5">
              <el-form-item label="类型">
                <el-select v-model="searchForm.callType" clearable placeholder="全部" style="width: 100%">
                  <el-option label="单聊" :value="1" />
                  <el-option label="群呼" :value="2" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="5">
              <el-form-item label="媒体">
                <el-select v-model="searchForm.mediaType" clearable placeholder="全部" style="width: 100%">
                  <el-option label="音频" :value="1" />
                  <el-option label="音视频" :value="2" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="5">
              <el-form-item label="状态">
                <el-select v-model="searchForm.status" clearable placeholder="全部" style="width: 100%">
                  <el-option label="已接" :value="1" />
                  <el-option label="未接" :value="2" />
                  <el-option label="拒接" :value="3" />
                  <el-option label="取消" :value="4" />
                  <el-option label="忙线" :value="5" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="记录时间">
                <el-date-picker
                  v-model="dateRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="3" :style="{ paddingLeft: '10px' }">
              <el-button type="success" @click="loadDataList()">查询</el-button>
              <el-button @click="resetSearch()">重置</el-button>
            </el-col>
          </el-row>
        </el-form>
      </el-card>
    </div>
    <el-card class="table-data-card">
      <Table
        :columns="columns"
        :fetch="loadDataList"
        :dataSource="tableData"
        :options="tableOptions"
      >
        <template #slotCallType="{ index, row }">
          <span>{{ callTypeText(row.callType) }}</span>
        </template>
        <template #slotMediaType="{ index, row }">
          <span>{{ mediaTypeText(row.mediaType) }}</span>
        </template>
        <template #slotCaller="{ index, row }">
          <span>{{ row.callerNickName || '—' }}</span>
        </template>
        <template #slotPeerOrGroup="{ index, row }">
          <span>{{ peerOrGroupText(row) }}</span>
        </template>
        <template #slotStatus="{ index, row }">
          <div>
            <span v-if="row.status == 1" style="color: #67c23a">已接</span>
            <span v-else-if="row.status == 2" style="color: #e6a23c">未接</span>
            <span v-else-if="row.status == 3" style="color: #f56c6c">拒接</span>
            <span v-else-if="row.status == 4" style="color: #909399">取消</span>
            <span v-else-if="row.status == 5" style="color: #e6a23c">忙线</span>
            <span v-else>-</span>
          </div>
        </template>
        <template #slotDuration="{ index, row }">
          <span>{{ formatDuration(row.durationMs) }}</span>
        </template>
        <template #slotCreateTime="{ index, row }">
          <span>{{ formatTime(row.createTime) }}</span>
        </template>
      </Table>
    </el-card>
  </div>
</template>

<script setup>
import Table from '@/components/Table.vue'
import { getCurrentInstance, ref, reactive } from 'vue'
const { proxy } = getCurrentInstance()

const tableData = ref({})
const tableOptions = {}
const dateRange = ref([])

const searchForm = reactive({
  callType: null,
  mediaType: null,
  status: null,
  startTime: null,
  endTime: null
})

const columns = [
  { label: 'ID', prop: 'id', width: 80 },
  { label: '类型', prop: 'callType', width: 90, scopedSlots: 'slotCallType' },
  { label: '媒体', prop: 'mediaType', width: 90, scopedSlots: 'slotMediaType' },
  { label: '发起人', prop: 'callerNickName', width: 130, scopedSlots: 'slotCaller' },
  { label: '对方/群名', prop: 'peerOrGroup', minWidth: 160, scopedSlots: 'slotPeerOrGroup' },
  { label: '状态', prop: 'status', width: 90, scopedSlots: 'slotStatus' },
  { label: '时长', prop: 'durationMs', width: 100, scopedSlots: 'slotDuration' },
  { label: '参与人数', prop: 'participantCount', width: 90 },
  { label: '记录时间', prop: 'createTime', width: 160, scopedSlots: 'slotCreateTime' }
]

const callTypeText = (t) => {
  if (t == 1) return '单聊'
  if (t == 2) return '群呼'
  return '-'
}
const mediaTypeText = (t) => {
  if (t == 1) return '音频'
  if (t == 2) return '音视频'
  return '-'
}
// 单聊显示对方昵称、群呼显示群名；已注销/已解散时昵称为 null 显示占位
const peerOrGroupText = (row) => {
  if (row.callType == 2) {
    return row.groupNickName || '—'
  }
  return row.peerNickName || '—'
}
// 时长为 null（任一端时间缺失）显示占位
const formatDuration = (ms) => {
  if (ms == null || isNaN(Number(ms)) || Number(ms) < 0) return '—'
  const total = Math.floor(Number(ms) / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const p = (n) => (n < 10 ? '0' + n : n)
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`
}
const formatTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(Number(ts))
  if (isNaN(d.getTime())) return '-'
  const p = (n) => (n < 10 ? '0' + n : n)
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const resetSearch = () => {
  searchForm.callType = null
  searchForm.mediaType = null
  searchForm.status = null
  searchForm.startTime = null
  searchForm.endTime = null
  dateRange.value = []
  loadDataList()
}

const loadDataList = async () => {
  // 处理日期范围 -> 毫秒（过滤 create_time，含边界）
  searchForm.startTime = null
  searchForm.endTime = null
  if (dateRange.value && dateRange.value.length === 2) {
    const s = new Date(dateRange.value[0] + ' 00:00:00')
    const e = new Date(dateRange.value[1] + ' 23:59:59')
    searchForm.startTime = s.getTime()
    searchForm.endTime = e.getTime()
  }
  const params = {
    pageNo: tableData.value.pageNo,
    pageSize: tableData.value.pageSize
  }
  Object.assign(params, searchForm)
  const result = await proxy.Request({
    url: proxy.Api.loadCallLog,
    params
  })
  if (!result) {
    return
  }
  Object.assign(tableData.value, result.data)
}
</script>

<style lang="scss" scoped>
</style>
