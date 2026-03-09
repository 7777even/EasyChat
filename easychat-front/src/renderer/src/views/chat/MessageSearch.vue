<template>
  <el-dialog
    :model-value="dialogConfig.show"
    :title="'搜索消息'"
    width="800px"
    :show-close="true"
    @close="dialogConfig.show = false"
    class="message-search-dialog"
  >
    <div class="search-container">
      <div class="search-filters">
        <el-input
          v-model="searchParams.keyword"
          placeholder="搜索消息内容"
          clearable
          @keyup.enter="search"
          class="search-input"
        >
          <template #prefix>
            <span class="iconfont icon-search"></span>
          </template>
        </el-input>

        <el-select
          v-model="searchParams.messageType"
          placeholder="消息类型"
          clearable
          class="filter-select"
        >
          <el-option label="全部" :value="null"></el-option>
          <el-option label="文字消息" :value="2"></el-option>
          <el-option label="文件消息" :value="5"></el-option>
        </el-select>

        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="x"
          class="date-picker"
        />

        <el-button type="primary" @click="search">搜索</el-button>
      </div>

      <div class="search-results" v-loading="loading">
        <div v-if="searchResults.length === 0 && !loading" class="no-results">
          <span class="iconfont icon-search"></span>
          <p>{{ searched ? '未找到相关消息' : '请输入搜索条件' }}</p>
        </div>

        <div v-else class="result-list">
          <div
            v-for="item in searchResults"
            :key="item.messageId"
            class="result-item"
            @click="jumpToMessage(item)"
          >
            <div class="message-info">
              <Avatar :userId="item.sendUserId" :width="40"></Avatar>
              <div class="message-content">
                <div class="sender-info">
                  <span class="sender-name">{{ item.sendUserNickName }}</span>
                  <span class="send-time">{{ formatTime(item.sendTime) }}</span>
                </div>
                <div class="message-text" v-html="highlightKeyword(item.messageContent)"></div>
              </div>
            </div>
          </div>
        </div>

        <div class="pagination" v-if="total > 0">
          <el-pagination
            background
            layout="prev, pager, next"
            :total="total"
            :page-size="20"
            v-model:current-page="searchParams.pageNo"
            @current-change="search"
          />
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from 'vue'
import Avatar from '@/components/Avatar.vue'

const { proxy } = getCurrentInstance()

const dialogConfig = reactive({
  show: false,
  sessionId: null
})

const searchParams = reactive({
  keyword: '',
  messageType: null,
  pageNo: 1
})

const dateRange = ref(null)
const searchResults = ref([])
const loading = ref(false)
const searched = ref(false)
const total = ref(0)

const show = (sessionId) => {
  dialogConfig.show = true
  dialogConfig.sessionId = sessionId
  searchParams.keyword = ''
  searchParams.messageType = null
  searchParams.pageNo = 1
  dateRange.value = null
  searchResults.value = []
  searched.value = false
  total.value = 0
}

const search = async () => {
  if (!searchParams.keyword && !searchParams.messageType && !dateRange.value) {
    proxy.Message.warning('请输入搜索条件')
    return
  }

  loading.value = true
  searched.value = true

  try {
    const params = {
      sessionId: dialogConfig.sessionId,
      keyword: searchParams.keyword,
      messageType: searchParams.messageType,
      pageNo: searchParams.pageNo
    }

    if (dateRange.value && dateRange.value.length === 2) {
      params.startTime = dateRange.value[0]
      params.endTime = dateRange.value[1]
    }

    const result = await proxy.Request({
      url: proxy.Api.searchMessage,
      params
    })

    if (result && result.data) {
      searchResults.value = result.data.list || []
      total.value = result.data.totalCount || 0
    }
  } catch (error) {
    console.error('搜索失败:', error)
    proxy.Message.error('搜索失败')
  } finally {
    loading.value = false
  }
}

const highlightKeyword = (text) => {
  if (!searchParams.keyword || !text) {
    return text
  }
  const regex = new RegExp(`(${searchParams.keyword})`, 'gi')
  return text.replace(regex, '<span class="highlight">$1</span>')
}

const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  const now = new Date()
  const diff = now - date

  if (diff < 86400000) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } else if (diff < 604800000) {
    const days = ['日', '一', '二', '三', '四', '五', '六']
    return `周${days[date.getDay()]} ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  } else {
    return date.toLocaleDateString('zh-CN') + ' ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
}

const emit = defineEmits(['jumpToMessage'])

const jumpToMessage = (message) => {
  emit('jumpToMessage', message.messageId)
  dialogConfig.show = false
}

defineExpose({ show })
</script>

<style lang="scss" scoped>
.message-search-dialog {
  .search-container {
    .search-filters {
      display: flex;
      gap: 10px;
      margin-bottom: 20px;

      .search-input {
        flex: 4;
        min-width: 0;
      }

      .filter-select {
        flex: 3;
        min-width: 100px;
      }

      .date-picker {
        flex: 3;
        min-width: 280px;
      }
    }

    .search-results {
      min-height: 400px;
      max-height: 500px;
      overflow-y: auto;

      .no-results {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        height: 400px;
        color: #999;

        .iconfont {
          font-size: 60px;
          margin-bottom: 10px;
        }

        p {
          font-size: 14px;
        }
      }

      .result-list {
        .result-item {
          padding: 15px;
          border-bottom: 1px solid #f0f0f0;
          cursor: pointer;
          transition: background-color 0.2s;

          &:hover {
            background-color: #f5f5f5;
          }

          .message-info {
            display: flex;
            gap: 10px;

            .message-content {
              flex: 1;

              .sender-info {
                display: flex;
                justify-content: space-between;
                margin-bottom: 5px;

                .sender-name {
                  font-weight: 500;
                  color: #333;
                }

                .send-time {
                  font-size: 12px;
                  color: #999;
                }
              }

              .message-text {
                color: #666;
                font-size: 14px;
                word-break: break-all;

                :deep(.highlight) {
                  background-color: #ffeb3b;
                  color: #333;
                  font-weight: 500;
                }
              }
            }
          }
        }
      }

      .pagination {
        display: flex;
        justify-content: center;
        padding: 20px 0;
      }
    }
  }
}
</style>
