<template>
  <ContentPanel :showTopBorder="true">
    <div class="blacklist-tip">
      已被你拉黑的用户不会出现在你的好友列表中，也无法给你发送消息。
      解除后对方可重新向你发起好友申请。
    </div>

    <div v-if="list.length > 0">
      <div class="blacklist-item" v-for="item in list" :key="item.contactId">
        <Avatar :width="42" :userId="item.contactId" :lastUpdateTime="item.lastUpdateTime"></Avatar>
        <div class="info">
          <div class="nick-name">{{ item.contactName || item.contactId }}</div>
          <div class="sub-time">拉黑于 {{ formatTime(item.lastUpdateTime) }}</div>
        </div>
        <div class="op">
          <el-button type="primary" size="small" plain :loading="removingId === item.contactId"
            @click="confirmRemove(item)">解除</el-button>
        </div>
      </div>
    </div>
    <div v-else class="no-data">黑名单为空</div>
  </ContentPanel>
</template>

<script setup>
/**
 * 黑名单管理（openspec/specs/privacy-settings C2 / C3）
 *
 * 修复前只能加黑、没有列表也没有解除，用户点错一次就永久无法退出。
 * 列表只含我拉黑的（status=BLACKLIST）；「被拉黑」不在此列表，也不可由我解除。
 */
import { ref, getCurrentInstance } from 'vue'
import moment from 'moment'

const { proxy } = getCurrentInstance()

const list = ref([])
const removingId = ref('')

const formatTime = (ts) => {
  if (!ts) return '-'
  return moment(Number(ts)).format('YYYY-MM-DD HH:mm')
}

const loadBlackList = async () => {
  let result = await proxy.Request({
    url: proxy.Api.loadBlackList
  })
  if (!result) {
    // 失败时保留旧列表，不清空，避免把已加载内容抹掉
    return
  }
  list.value = result.data || []
}
loadBlackList()

const confirmRemove = (item) => {
  // utils/Confirm.js 仅支持 {message, okfun, showCancelBtn, okText}，没有 cancelfun
  proxy.Confirm({
    message: `确定将「${item.contactName || item.contactId}」移出黑名单？解除后对方可重新申请添加你。`,
    okText: '解除',
    showCancelBtn: true,
    okfun: async () => {
      removingId.value = item.contactId
      let result
      try {
        result = await proxy.Request({
          url: proxy.Api.removeBlackList,
          params: { contactId: item.contactId },
          showLoading: true
        })
      } finally {
        removingId.value = ''
      }
      if (!result) {
        // 失败保留该行，让用户可重试
        return
      }
      list.value = list.value.filter((row) => row.contactId !== item.contactId)
      proxy.Message.success('已移出黑名单')
    }
  })
}
</script>

<style lang="scss" scoped>
.blacklist-tip {
  padding: 10px 12px;
  margin-bottom: 12px;
  border-radius: 4px;
  background: var(--ec-surface-raised);
  color: var(--ec-text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.blacklist-item {
  display: flex;
  align-items: center;
  padding: 10px 8px;
  border-bottom: 1px solid var(--ec-border);
  &:hover {
    background: var(--ec-hover);
  }

  .info {
    flex: 1;
    margin-left: 12px;
    overflow: hidden;
  }

  .nick-name {
    color: var(--ec-text-primary);
    font-size: 14px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sub-time {
    margin-top: 4px;
    color: var(--ec-text-secondary);
    font-size: 12px;
  }

  .op {
    flex-shrink: 0;
  }
}

.no-data {
  padding: 40px 0;
  text-align: center;
  color: var(--ec-text-secondary);
  font-size: 13px;
}
</style>
