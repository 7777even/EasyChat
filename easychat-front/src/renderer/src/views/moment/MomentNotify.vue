<template>
  <Dialog
    :show="dialogConfig.show"
    :title="'朋友圈消息'"
    width="600px"
    :showCancel="false"
    @close="closeDialog"
  >
    <div class="notify-center">
      <!-- 类型 Tab：全部 / 点赞 / 评论回复 / @我 -->
      <div class="notify-tabs">
        <span
          v-for="tab in tabList"
          :key="tab.value"
          class="notify-tab"
          :class="{ active: activeType === tab.value }"
          @click="switchTab(tab.value)"
        >{{ tab.label }}</span>
        <div class="tab-actions">
          <el-button link size="small" @click="markAllRead">全部已读</el-button>
          <el-button link size="small" type="danger" @click="clearAll">清空</el-button>
        </div>
      </div>

      <el-scrollbar class="notify-list" ref="scrollbarRef">
        <div
          v-for="item in notifyList"
          :key="item.id"
          class="notify-item"
          :class="{ unread: item.readStatus === 0 }"
          @click="handleItemClick(item)"
        >
          <Avatar :userId="item.fromUserId" :width="40" :borderRadius="4" />
          <div class="notify-body">
            <div class="notify-name">{{ item.fromNickName || item.fromUserId }}</div>
            <div class="notify-content">
              <span class="notify-type">{{ typeLabel(item.type) }}</span>
              {{ item.content }}
            </div>
            <div class="notify-time">{{ formatTime(item.createTime) }}</div>
          </div>
          <span v-if="item.readStatus === 0" class="unread-dot"></span>
        </div>

        <div v-if="loading" class="tip">加载中...</div>
        <div v-else-if="notifyList.length === 0" class="tip empty">
          <i class="iconfont icon-empty"></i>
          <p>暂无消息</p>
        </div>
        <div v-else-if="noMore" class="tip">没有更多了</div>
      </el-scrollbar>
    </div>
  </Dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, nextTick } from 'vue'
import Avatar from '@/components/Avatar.vue'

const { proxy } = getCurrentInstance()
const emit = defineEmits(['locateMoment'])

const dialogConfig = reactive({
  show: false
})

// 通知类型 0新动态 1点赞 2评论 3回复 4@
const tabList = [
  { label: '全部', value: null },
  { label: '点赞', value: 1 },
  { label: '评论', value: 2 },
  { label: '@我', value: 4 }
]

const activeType = ref(null)
const notifyList = ref([])
const loading = ref(false)
const noMore = ref(false)
const pageNo = ref(1)
const pageSize = 20
const scrollbarRef = ref(null)

const show = () => {
  dialogConfig.show = true
  pageNo.value = 1
  noMore.value = false
  loadNotifyList(false)
}

const closeDialog = () => {
  dialogConfig.show = false
}

const switchTab = (type) => {
  activeType.value = type
  pageNo.value = 1
  noMore.value = false
  loadNotifyList(false)
}

const loadNotifyList = async (append = false) => {
  if (loading.value) return
  loading.value = true
  const result = await proxy.Request({
    url: proxy.Api.momentNotifyList,
    params: {
      pageNo: pageNo.value,
      pageSize: pageSize
    },
    showLoading: false,
    showError: false
  })
  loading.value = false
  if (!result) return

  const pageData = result.data || {}
  let list = pageData.list || []
  // 按 Tab 在前端过滤：后端只有全量列表接口，避免为每种类型各开一个端点
  if (activeType.value !== null) {
    list = list.filter((item) => item.type === activeType.value)
  }

  if (list.length < pageSize) {
    noMore.value = true
  }

  if (append) {
    notifyList.value = notifyList.value.concat(list)
  } else {
    notifyList.value = list
  }
}

const typeLabel = (type) => {
  switch (type) {
    case 0:
      return '新动态'
    case 1:
      return '赞了你'
    case 2:
      return '评论了你'
    case 3:
      return '回复了你'
    case 4:
      return '@了你'
    default:
      return ''
  }
}

const formatTime = (time) => {
  if (!time) return ''
  const diff = Date.now() - time
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)}分钟前`
  if (diff < day) return `${Math.floor(diff / hour)}小时前`
  if (diff < 2 * day) return '昨天'
  const date = new Date(time)
  const month = (date.getMonth() + 1 + '').padStart(2, '0')
  const dayStr = (date.getDate() + '').padStart(2, '0')
  return `${month}-${dayStr}`
}

// 点击通知：标记已读并跳转到对应动态
const handleItemClick = async (item) => {
  if (item.readStatus === 0) {
    item.readStatus = 1
    try {
      await proxy.Request({
        url: proxy.Api.momentMarkRead,
        params: { notifyId: item.id },
        showLoading: false,
        showError: false
      })
    } catch (e) {
      // 标记失败不影响跳转
    }
  }
  if (item.refId) {
    emit('locateMoment', item.refId)
    closeDialog()
  }
}

const markAllRead = async () => {
  const result = await proxy.Request({
    url: proxy.Api.momentMarkAllRead,
    showLoading: false,
    showError: false
  })
  if (!result) return
  notifyList.value.forEach((item) => {
    item.readStatus = 1
  })
}

const clearAll = () => {
  proxy.Confirm({
    message: '确定要清空全部朋友圈消息吗？',
    okfun: async () => {
      const result = await proxy.Request({
        url: proxy.Api.momentClearNotify,
        showLoading: false
      })
      if (!result) return
      notifyList.value = []
      proxy.Message.success('已清空')
    }
  })
}

const handleScroll = () => {
  const scrollbar = scrollbarRef.value
  if (!scrollbar || !scrollbar.wrapRef) return
  const { scrollTop, scrollHeight, clientHeight } = scrollbar.wrapRef
  if (scrollTop + clientHeight >= scrollHeight - 100 && !loading.value && !noMore.value) {
    pageNo.value++
    loadNotifyList(true)
  }
}

nextTick(() => {
  if (scrollbarRef.value && scrollbarRef.value.wrapRef) {
    scrollbarRef.value.wrapRef.addEventListener('scroll', handleScroll)
  }
})

defineExpose({ show })
</script>

<style lang="scss" scoped>
.notify-center {
  height: 520px;
  display: flex;
  flex-direction: column;
}

.notify-tabs {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--ec-divider-soft);
  padding-bottom: 8px;
  margin-bottom: 8px;

  .notify-tab {
    padding: 4px 12px;
    margin-right: 6px;
    font-size: 13px;
    color: #666;
    cursor: pointer;
    border-radius: 12px;
    transition: all 0.2s;

    &:hover {
      color: #07c160;
    }

    &.active {
      background: #07c160;
      color: #fff;
    }
  }

  .tab-actions {
    margin-left: auto;
  }
}

.notify-list {
  flex: 1;
}

.notify-item {
  display: flex;
  align-items: flex-start;
  padding: 10px 8px;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  transition: background 0.2s;

  &:hover {
    background: var(--ec-surface-soft);
  }

  &.unread {
    background: var(--ec-chip-green-bg);
  }

  .notify-body {
    margin-left: 10px;
    flex: 1;
    min-width: 0;

    .notify-name {
      font-size: 14px;
      font-weight: 600;
      color: #1a1a1a;
    }

    .notify-content {
      font-size: 13px;
      color: #666;
      margin-top: 2px;
      word-break: break-word;

      .notify-type {
        color: #576b95;
        margin-right: 4px;
      }
    }

    .notify-time {
      font-size: 12px;
      color: #999;
      margin-top: 4px;
    }
  }

  .unread-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #fa5151;
    margin-top: 6px;
    flex-shrink: 0;
  }
}

.tip {
  text-align: center;
  color: #999;
  padding: 20px 0;
  font-size: 13px;

  &.empty {
    padding: 60px 0;

    .iconfont {
      font-size: 50px;
      color: #ddd;
    }

    p {
      font-size: 14px;
    }
  }
}
</style>
