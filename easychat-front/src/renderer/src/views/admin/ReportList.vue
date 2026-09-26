<template>
  <div>
    <div class="top-panel">
      <el-card>
        <el-form :model="searchForm" label-width="80px" label-position="right">
          <el-row>
            <el-col :span="5">
              <el-form-item label="类型">
                <el-select v-model="searchForm.reportType" clearable placeholder="全部" style="width: 100%">
                  <el-option label="朋友圈动态" :value="1" />
                  <el-option label="评论" :value="2" />
                  <el-option label="聊天消息" :value="3" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="5">
              <el-form-item label="状态">
                <el-select v-model="searchForm.status" clearable placeholder="全部" style="width: 100%">
                  <el-option label="待处理" :value="0" />
                  <el-option label="已处理" :value="1" />
                  <el-option label="已驳回" :value="2" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="9">
              <el-form-item label="举报时间">
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
            <el-col :span="4" :style="{ paddingLeft: '10px' }">
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
        <template #slotReportType="{ index, row }">
          <span>{{ reportTypeText(row.reportType) }}</span>
        </template>
        <template #slotReason="{ index, row }">
          <span>{{ reasonText(row.reason) }}</span>
        </template>
        <template #slotStatus="{ index, row }">
          <div>
            <span v-if="row.status == 0" style="color: #e6a23c">待处理</span>
            <span v-else-if="row.status == 1" style="color: #67c23a">已处理</span>
            <span v-else style="color: #909399">已驳回</span>
          </div>
        </template>
        <template #slotCreateTime="{ index, row }">
          <span>{{ formatTime(row.createTime) }}</span>
        </template>
        <template #slotOperation="{ index, row }">
          <div class="row-op-panel">
            <a href="javascript:void(0)" @click="showDetail(row)">查看</a>
            <a href="javascript:void(0)" @click="openDeal(row)" v-if="row.status == 0">处理</a>
            <span v-else style="color: #c0c4cc">已处置</span>
          </div>
        </template>
      </Table>
    </el-card>

    <!-- 举报详情 + 审计时间线 -->
    <el-dialog title="举报详情" v-model="detailVisible" width="640px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="举报类型">{{ reportTypeText(detailData.reportType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ detailData.status == 0 ? '待处理' : detailData.status == 1 ? '已处理' : '已驳回' }}
        </el-descriptions-item>
        <el-descriptions-item label="举报人">
          {{ detailData.reportUserName || detailData.reportUserId }}
        </el-descriptions-item>
        <el-descriptions-item label="发布者">
          {{ detailData.publisherNickName || detailData.publisherId || '未知' }}
        </el-descriptions-item>
        <el-descriptions-item label="举报理由">{{ reasonText(detailData.reason) }}</el-descriptions-item>
        <el-descriptions-item label="举报说明">{{ detailData.description || '无' }}</el-descriptions-item>
        <el-descriptions-item label="被举报内容">
          <div class="content-box">{{ detailData.content || '(内容为空或已删除)' }}</div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="audit-title">处置审计轨迹</div>
      <el-timeline v-if="auditList.length">
        <el-timeline-item
          v-for="(item, i) in auditList"
          :key="i"
          :timestamp="formatTime(item.createTime)"
          placement="top"
        >
          <div>
            <el-tag size="small" :type="item.action == 1 ? 'success' : 'info'">
              {{ item.action == 1 ? '已处理' : '已驳回' }}
            </el-tag>
            <span class="audit-action">动作：{{ handleActionText(item.handleAction) }}</span>
          </div>
          <div class="audit-meta">处理人：{{ item.adminId }}</div>
          <div class="audit-note" v-if="item.handleNote">备注：{{ item.handleNote }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无处置记录" :image-size="60" />
    </el-dialog>

    <!-- 处置弹窗 -->
    <el-dialog title="处置举报" v-model="dealVisible" width="520px">
      <el-form :model="dealForm" label-width="90px">
        <el-form-item label="处置结论">
          <el-radio-group v-model="dealForm.status">
            <el-radio :label="1">已处理</el-radio>
            <el-radio :label="2">已驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处置动作">
          <el-select v-model="dealForm.handleAction" style="width: 100%">
            <el-option label="仅记录处理" :value="0" />
            <el-option label="删除被举报内容" :value="1" />
            <el-option label="封禁发布者" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input
            v-model="dealForm.handleNote"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="可选：填写处置说明"
          />
        </el-form-item>
        <el-alert
          v-if="dealForm.handleAction == 1 && dealForm.reportType == 3"
          type="warning"
          :closable="false"
          title="聊天消息无删除状态位，选择「删除内容」将仅记录、不物理删除"
        />
      </el-form>
      <template #footer>
        <el-button @click="dealVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDeal">确认处置</el-button>
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
const dateRange = ref([])

const searchForm = reactive({
  reportType: null,
  status: null,
  startTime: null,
  endTime: null
})

const detailVisible = ref(false)
const detailData = ref(null)
const auditList = ref([])

const dealVisible = ref(false)
const dealForm = reactive({
  id: null,
  reportType: null,
  status: 1,
  handleAction: 0,
  handleNote: ''
})

const columns = [
  { label: '类型', prop: 'reportType', width: 110, scopedSlots: 'slotReportType' },
  { label: '被举报内容(摘要)', prop: 'contentExcerpt', minWidth: 220 },
  { label: '举报人', prop: 'reportUserName', width: 130 },
  { label: '理由', prop: 'reason', width: 90, scopedSlots: 'slotReason' },
  { label: '状态', prop: 'status', width: 90, scopedSlots: 'slotStatus' },
  { label: '举报时间', prop: 'createTime', width: 160, scopedSlots: 'slotCreateTime' },
  { label: '操作', prop: 'operation', width: 130, scopedSlots: 'slotOperation' }
]

const reportTypeText = (t) => {
  if (t == 1) return '朋友圈动态'
  if (t == 2) return '评论'
  if (t == 3) return '聊天消息'
  return '-'
}
const reasonText = (r) => {
  return ['色情', '暴力', '诈骗', '侵权', '其他'][r] || '-'
}
const handleActionText = (a) => {
  return ['仅记录处理', '删除被举报内容', '封禁发布者'][a] || '-'
}
const formatTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(Number(ts))
  if (isNaN(d.getTime())) return '-'
  const p = (n) => (n < 10 ? '0' + n : n)
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const resetSearch = () => {
  searchForm.reportType = null
  searchForm.status = null
  searchForm.startTime = null
  searchForm.endTime = null
  dateRange.value = []
  loadDataList()
}

const loadDataList = async () => {
  // 处理日期范围 -> 毫秒
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
    url: proxy.Api.loadReport,
    params
  })
  if (!result) {
    return
  }
  Object.assign(tableData.value, result.data)
}

const showDetail = async (row) => {
  const result = await proxy.Request({
    url: proxy.Api.getReportDetail,
    params: { id: row.id, reportType: row.reportType }
  })
  if (!result) {
    return
  }
  detailData.value = result.data
  // 拉取该举报的处置审计轨迹
  const audit = await proxy.Request({
    url: proxy.Api.loadReportAudit,
    params: { reportId: row.id, reportType: row.reportType, pageNo: 1, pageSize: 50 }
  })
  auditList.value = audit && audit.data ? audit.data.list : []
  detailVisible.value = true
}

const openDeal = (row) => {
  dealForm.id = row.id
  dealForm.reportType = row.reportType
  dealForm.status = 1
  dealForm.handleAction = 0
  dealForm.handleNote = ''
  dealVisible.value = true
}

const submitDeal = () => {
  proxy.Confirm({
    message: '确认要处置该举报吗？',
    okfun: async () => {
      const result = await proxy.Request({
        url: proxy.Api.dealReport,
        params: {
          id: dealForm.id,
          reportType: dealForm.reportType,
          status: dealForm.status,
          handleAction: dealForm.handleAction,
          handleNote: dealForm.handleNote
        }
      })
      if (!result) {
        return
      }
      proxy.Message.success('处置成功')
      dealVisible.value = false
      loadDataList()
    }
  })
}
</script>

<style lang="scss" scoped>
.row-op-panel {
  a {
    margin-right: 8px;
    color: var(--link-color, #409eff);
  }
}
.content-box {
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f7f7f7;
  padding: 8px;
  border-radius: 4px;
}
.audit-title {
  margin: 16px 0 8px;
  font-weight: 600;
}
.audit-action {
  margin-left: 8px;
}
.audit-meta {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
.audit-note {
  margin-top: 2px;
}
</style>
