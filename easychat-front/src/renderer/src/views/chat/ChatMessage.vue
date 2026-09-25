<template>
  <div 
    class="message-content-my" 
    v-if="data.sendUserId == userInfoStore.getInfo().userId"
    @contextmenu.stop="onContextMenu($event)"
  >
    <div class="select-check" v-if="multiSelectMode" @click.stop="toggleSelect">
      <el-checkbox :model-value="selected"></el-checkbox>
    </div>
    <div :class="['content-panel', data.messageType == 5 ? 'content-panel-media' : '', data.messageType == 14 ? 'recalled-message' : '']">
      <div class="sending" v-if="data.status == 0">
        <el-skeleton :animated="true">
          <template #template>
            <el-skeleton-item class="skeleton-item" variant="image" />
          </template>
        </el-skeleton>
      </div>
      <template v-else>
        <div class="content recalled-content" v-if="data.messageType == 14">
          <span class="recall-text">{{ getRecallText() }}</span>
        </div>
        <template v-else>
          <div class="quote-block" v-if="quoteInfo">
            <span class="quote-name">{{ quoteInfo.quoteNickName || '消息' }}</span>
            <span class="quote-content">{{ quoteInfo.quoteContent }}</span>
          </div>
          <div class="content" v-html="data.messageContent" v-if="data.messageType != 5"></div>
          <div class="content" v-else>
            <template v-if="data.fileType == 0">
              <ChatMessageImage :data="data" @click="showDetail"></ChatMessageImage>
            </template>
            <template v-if="data.fileType == 1">
              <ChatMessageVideo :data="data" @click="showDetail"></ChatMessageVideo>
            </template>
            <template v-if="data.fileType == 2">
              <ChatMessageFile :data="data" @click="showDetail"></ChatMessageFile>
            </template>
          </div>
        </template>
      </template>
    </div>
    <Avatar :width="35" :userId="userInfoStore.getInfo().userId"> </Avatar>
  </div>
  <div 
    class="message-content-other" 
    v-else
    @contextmenu.stop="onContextMenu($event)"
  >
    <div class="select-check" v-if="multiSelectMode" @click.stop="toggleSelect">
      <el-checkbox :model-value="selected"></el-checkbox>
    </div>
    <div class="user-avatar">
      <Avatar :width="35" :userId="data.sendUserId"></Avatar>
    </div>
    <div
      :class="[
        'content-panel',
        data.contactType == 1 ? 'group-content' : '',
        data.messageType == 5 ? 'content-panel-media' : '',
        data.messageType == 14 ? 'recalled-message' : ''
      ]"
    >
      <div class="nick-name" v-if="data.contactType == 1 && data.messageType != 14">
        {{ data.sendUserNickName }}
      </div>
      <div class="sending" v-if="data.status == 0">
        <el-skeleton :animated="true">
          <template #template>
            <el-skeleton-item class="skeleton-item" variant="image" />
          </template>
        </el-skeleton>
      </div>
      <template v-else>
        <div class="content recalled-content" v-if="data.messageType == 14">
          <span class="recall-text">{{ getRecallText() }}</span>
        </div>
        <template v-else>
          <div class="quote-block" v-if="quoteInfo">
            <span class="quote-name">{{ quoteInfo.quoteNickName || '消息' }}</span>
            <span class="quote-content">{{ quoteInfo.quoteContent }}</span>
          </div>
          <div class="content" v-html="data.messageContent" v-if="data.messageType != 5"></div>
          <div class="content" v-else>
            <template v-if="data.fileType == 0">
              <ChatMessageImage :data="data" @click="showDetail"></ChatMessageImage>
            </template>
            <template v-if="data.fileType == 1">
              <ChatMessageVideo :data="data" @click="showDetail"></ChatMessageVideo>
            </template>
            <template v-if="data.fileType == 2">
              <ChatMessageFile :data="data" @click="showDetail"></ChatMessageFile>
            </template>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import ChatMessageVideo from './ChatMessageVideo.vue'
import ChatMessageImage from './ChatMessageImage.vue'
import ChatMessageFile from './ChatMessageFile.vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import '@imengyu/vue3-context-menu/lib/vue3-context-menu.css'
import { ref, reactive, getCurrentInstance, nextTick, computed } from 'vue'
const { proxy } = getCurrentInstance()

import { useUserInfoStore } from '@/stores/UserInfoStore'
const userInfoStore = useUserInfoStore()

const props = defineProps({
  data: {
    type: Object,
    default: {}
  },
  currentChatSession: {
    type: Object,
    default: {}
  },
  //多选模式：显示勾选框
  multiSelectMode: {
    type: Boolean,
    default: false
  },
  //是否被勾选
  selected: {
    type: Boolean,
    default: false
  }
})

//多选勾选切换
const toggleSelect = () => {
  emit('toggleSelect', props.data.messageId)
}

const emit = defineEmits([
  'showMediaDetail',
  'recallMessage',
  'quoteMessage',
  'forwardMessage',
  'multiSelect',
  'toggleSelect',
  'deleteMessage'
])

/**
 * 消息扩展数据（引用回复 / 转发来源），兼容对象与 JSON 字符串两种形态
 */
const parseExtraData = () => {
  const extra = props.data.extraData
  if (!extra) return null
  if (typeof extra === 'object') return extra
  try {
    return JSON.parse(extra)
  } catch (e) {
    return null
  }
}

const quoteInfo = computed(() => {
  const extra = parseExtraData()
  if (!extra || !extra.quoteContent) return null
  return extra
})

const showDetail = () => {
  if (props.data.stauts == 0) {
    return
  }
  emit('showMediaDetail', props.data.messageId)
}

// 获取撤回提示文本
const getRecallText = () => {
  // 群聊中显示撤回者信息
  if (props.data.contactType == 1 && props.data.sendUserNickName) {
    return `${props.data.sendUserNickName} 撤回了一条消息`
  }
  return '该消息已撤回'
}

// 右键菜单：撤回 / 复制 / 引用回复 / 转发 / 多选 / 删除
const onContextMenu = (e) => {
  const isMyMessage = props.data.sendUserId == userInfoStore.getInfo().userId
  const isRecalled = props.data.messageType == 14
  const isNormalMessage = props.data.messageType == 2 || props.data.messageType == 5

  if (isRecalled || !isNormalMessage) {
    return
  }

  const items = []

  // 复制：文本消息复制正文，媒体消息复制文件名
  items.push({
    label: '复制',
    onClick: () => {
      const text =
        props.data.messageType == 5
          ? props.data.fileName || ''
          : (props.data.messageContent || '').replace(/<[^>]+>/g, '')
      copyText(text)
    }
  })

  items.push({
    label: '引用回复',
    onClick: () => {
      emit('quoteMessage', props.data)
    }
  })

  items.push({
    label: '转发',
    onClick: () => {
      emit('forwardMessage', props.data)
    }
  })

  items.push({
    label: '多选',
    onClick: () => {
      emit('multiSelect', props.data)
    }
  })

  // 撤回：仅自己的消息且 2 分钟内
  if (isMyMessage) {
    const timeDiff = Date.now() - props.data.sendTime
    if (timeDiff <= 120000) {
      items.push({
        label: '撤回',
        onClick: () => {
          emit('recallMessage', props.data.messageId)
        }
      })
    }
  }

  items.push({
    label: '删除',
    onClick: () => {
      emit('deleteMessage', props.data)
    }
  })

  ContextMenu.showContextMenu({
    x: e.x,
    y: e.y,
    items
  })
}

// 复制文本：优先用 Electron 剪贴板，降级到浏览器 API
const copyText = async (text) => {
  if (!text) return
  try {
    if (window.ipcRenderer) {
      window.ipcRenderer.send('copyText', text)
      return
    }
  } catch (e) {
    // 降级
  }
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    console.warn('复制失败', e)
  }
}
</script>

<style lang="scss" scoped>
.sending {
  width: 170px;
  height: 170px;
  overflow: hidden;
  float: right;
  margin-right: 5px;
  border-radius: 5px;
  .skeleton-item {
    width: 170px;
    height: 170px;
  }
}

.content {
  display: inline-block;
  padding: 8px;
  color: #474747;
  border-radius: 5px;
  text-align: left;
  font-size: 14px;
  :deep(.emoji) {
    font-size: 20px;
  }
}

.content-panel {
  flex: 1;
  position: relative;
  &::after {
    content: '';
    position: absolute;
    display: block;
    width: 10px;
    height: 10px;
    background: #95ec69;
    transform: rotate(45deg);
    border-radius: 2px;
    top: 13px;
  }
}

.content-panel-media {
  .content {
    border-radius: 5px;
    background: none !important;
    overflow: hidden;
    padding: 0px;
  }
  &::after {
    display: none;
  }
}

.message-content-my {
  display: flex;
  .content-panel {
    margin-right: 10px;
    text-align: right;
    padding-left: 32%;
    .content {
      background: #95ec69;
    }
    &::after {
      right: -4px;
    }
  }
}

.message-content-other {
  display: flex;
  padding-right: 32%;
  .user-avatar {
    margin-right: 10px;
    width: 35px;
    height: 35px;
  }
  .content-panel {
    flex: 1;
    position: relative;
    text-align: left;
    .nick-name {
      font-size: 12px;
      color: #b2b2b2;
    }
    .content {
      background: #fff;
    }
    .sending {
      float: left;
    }
    &::after {
      left: -4px;
      background: #fff;
    }
  }
  .content-panel-media {
    justify-content: flex-start;
  }
}
.group-content {
  margin-top: -6px;
  .content {
    margin-top: 6px;
  }
  &::after {
    left: -4px;
    top: 35px;
    background: #fff;
  }
}

// 引用回复块
.quote-block {
  display: inline-block;
  max-width: 100%;
  margin-bottom: 4px;
  padding: 4px 8px;
  border-left: 3px solid #b6b6b6;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 3px;
  text-align: left;
  font-size: 12px;
  color: #6b6b6b;
  .quote-name {
    display: block;
    font-weight: 500;
    margin-bottom: 2px;
  }
  .quote-content {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 260px;
  }
}

.recalled-message {
  .recalled-content {
    background: #e5e5e5 !important;
    color: #999;
    font-style: italic;
    padding: 8px 12px;
    .recall-text {
      font-size: 12px;
    }
  }
  &::after {
    display: none;
  }
}
</style>
