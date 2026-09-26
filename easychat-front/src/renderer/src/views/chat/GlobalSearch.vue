<template>
  <Dialog
    :show="dialogConfig.show"
    title="全局搜索"
    width="680px"
    :showCancel="false"
    @close="closeDialog"
  >
    <div class="global-search">
      <!-- 搜索框 + 范围 -->
      <div class="search-bar">
        <el-input
          v-model="keyword"
          size="large"
          placeholder="搜索聊天记录、联系人、群组"
          clearable
          @keyup.enter="doSearch"
        >
          <template #prefix>
            <span class="iconfont icon-search"></span>
          </template>
        </el-input>
        <el-button type="primary" size="large" :loading="loading" @click="doSearch">搜索</el-button>
      </div>

      <div class="scope-tabs">
        <span
          v-for="tab in scopeTabs"
          :key="tab.value"
          class="scope-tab"
          :class="{ active: scope === tab.value }"
          @click="switchScope(tab.value)"
        >{{ tab.label }}</span>
      </div>

      <el-scrollbar class="result-panel">
        <template v-if="searched">
          <!-- 联系人 -->
          <div v-if="contactList.length > 0" class="result-group">
            <div class="group-title">联系人</div>
            <div
              v-for="item in contactList"
              :key="'c' + item.contactId"
              class="result-item"
              @click="openContact(item)"
            >
              <Avatar :userId="item.contactId" :width="40" :borderRadius="4" />
              <div class="result-body">
                <div class="result-name" v-html="highlight(displayName(item))"></div>
                <div class="result-desc">联系人</div>
              </div>
            </div>
          </div>

          <!-- 群组 -->
          <div v-if="groupList.length > 0" class="result-group">
            <div class="group-title">群组</div>
            <div
              v-for="item in groupList"
              :key="'g' + item.contactId"
              class="result-item"
              @click="openContact(item)"
            >
              <Avatar :userId="item.contactId" :width="40" :borderRadius="4" />
              <div class="result-body">
                <div class="result-name" v-html="highlight(displayName(item))"></div>
                <div class="result-desc">群聊</div>
              </div>
            </div>
          </div>

          <!-- 聊天记录 -->
          <div v-if="messageList.length > 0" class="result-group">
            <div class="group-title">聊天记录</div>
            <div
              v-for="item in messageList"
              :key="'m' + item.messageId"
              class="result-item"
              @click="openMessage(item)"
            >
              <Avatar :userId="item.sendUserId" :width="40" :borderRadius="4" />
              <div class="result-body">
                <div class="result-name">{{ item.sendUserNickName || item.sendUserId }}</div>
                <div class="result-desc" v-html="highlight(messageDigest(item))"></div>
                <div class="result-time">{{ formatTime(item.sendTime) }}</div>
              </div>
            </div>
          </div>

          <div
            v-if="contactList.length === 0 && groupList.length === 0 && messageList.length === 0"
            class="empty-tip"
          >
            <i class="iconfont icon-empty"></i>
            <p>没有找到相关内容</p>
          </div>
        </template>

        <div v-else class="empty-tip">
          <p>输入关键词开始搜索</p>
        </div>
      </el-scrollbar>
    </div>
  </Dialog>
</template>

<script setup>
import { ref, reactive, getCurrentInstance } from 'vue'
import Avatar from '@/components/Avatar.vue'

const { proxy } = getCurrentInstance()
const emit = defineEmits(['openSession', 'jumpMessage'])

const dialogConfig = reactive({ show: false })
const keyword = ref('')
const scope = ref('all')
const loading = ref(false)
const searched = ref(false)

const contactList = ref([])
const groupList = ref([])
const messageList = ref([])

const scopeTabs = [
  { label: '全部', value: 'all' },
  { label: '聊天记录', value: 'message' },
  { label: '联系人', value: 'contact' },
  { label: '群组', value: 'group' }
]

const show = (initKeyword) => {
  dialogConfig.show = true
  if (initKeyword) {
    keyword.value = initKeyword
    doSearch()
  }
}

const closeDialog = () => {
  dialogConfig.show = false
}

const switchScope = (value) => {
  scope.value = value
  if (keyword.value) {
    doSearch()
  }
}

const doSearch = async () => {
  const key = (keyword.value || '').trim()
  if (!key) {
    proxy.Message.warning('请输入搜索关键词')
    return
  }
  loading.value = true
  const result = await proxy.Request({
    url: proxy.Api.globalSearch,
    params: { keyword: key, scope: scope.value },
    showLoading: false,
    showError: false
  })
  loading.value = false
  if (!result) {
    return
  }
  const data = result.data || {}
  contactList.value = data.contactList || []
  groupList.value = data.groupList || []
  messageList.value = data.messageList || []
  searched.value = true
}

// 优先展示备注名，其次群名 / 联系人昵称
const displayName = (item) => {
  return item.remark || item.contactName || item.groupName || item.contactId
}

const messageDigest = (item) => {
  if (item.messageType === 5) {
    return '[文件]' + (item.fileName || '')
  }
  return item.messageContent || ''
}

// 关键词高亮：先转义再替换，避免用户输入正则元字符导致渲染异常
const escapeHtml = (str) => {
  return (str || '').replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  })
}

const highlight = (text) => {
  const safe = escapeHtml(text)
  const key = (keyword.value || '').trim()
  if (!key) {
    return safe
  }
  const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(escapedKey, 'gi'), (match) => {
    return "<span class='highlight'>" + match + '</span>'
  })
}

const formatTime = (time) => {
  if (!time) return ''
  const date = new Date(time)
  const month = (date.getMonth() + 1 + '').padStart(2, '0')
  const day = (date.getDate() + '').padStart(2, '0')
  const hour = (date.getHours() + '').padStart(2, '0')
  const minute = (date.getMinutes() + '').padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

const openContact = (item) => {
  emit('openSession', { contactId: item.contactId, contactType: item.contactType })
  closeDialog()
}

// 命中聊天记录：先切到会话，再定位到该条消息
const openMessage = (item) => {
  emit('jumpMessage', {
    contactId: item.contactId,
    contactType: item.contactType,
    messageId: item.messageId
  })
  closeDialog()
}

defineExpose({ show })
</script>

<style lang="scss" scoped>
.global-search {
  height: 560px;
  display: flex;
  flex-direction: column;
}

.search-bar {
  display: flex;
  gap: 8px;
}

.scope-tabs {
  display: flex;
  gap: 6px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ec-divider-soft);
  margin-bottom: 8px;

  .scope-tab {
    padding: 4px 12px;
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
}

.result-panel {
  flex: 1;
}

.result-group {
  margin-bottom: 10px;

  .group-title {
    font-size: 12px;
    color: #999;
    padding: 6px 8px;
    background: var(--ec-surface-softer);
  }
}

.result-item {
  display: flex;
  align-items: flex-start;
  padding: 10px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.2s;

  &:hover {
    background: var(--ec-surface-soft);
  }

  .result-body {
    margin-left: 10px;
    flex: 1;
    min-width: 0;

    .result-name {
      font-size: 14px;
      color: #1a1a1a;
      font-weight: 600;
    }

    .result-desc {
      font-size: 13px;
      color: #666;
      margin-top: 2px;
      word-break: break-word;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .result-time {
      font-size: 12px;
      color: #999;
      margin-top: 2px;
    }
  }
}

.empty-tip {
  text-align: center;
  color: #999;
  padding: 60px 0;
  font-size: 14px;

  .iconfont {
    font-size: 50px;
    color: #ddd;
  }

  p {
    font-size: 14px;
  }
}
</style>
