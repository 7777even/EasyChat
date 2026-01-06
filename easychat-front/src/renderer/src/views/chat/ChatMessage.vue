<template>
  <div 
    class="message-content-my" 
    v-if="data.sendUserId == userInfoStore.getInfo().userId"
    @contextmenu.stop="onContextMenu($event)"
  >
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
        <div class="content" v-html="data.messageContent" v-else-if="data.messageType != 5"></div>
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
    </div>
    <Avatar :width="35" :userId="userInfoStore.getInfo().userId"> </Avatar>
  </div>
  <div 
    class="message-content-other" 
    v-else
    @contextmenu.stop="onContextMenu($event)"
  >
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
        <div class="content" v-html="data.messageContent" v-else-if="data.messageType != 5"></div>
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
  }
})

const emit = defineEmits(['showMediaDetail', 'recallMessage'])

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

// 右键菜单
const onContextMenu = (e) => {
  // 只有自己的消息且不是撤回消息才能撤回
  const isMyMessage = props.data.sendUserId == userInfoStore.getInfo().userId
  const isRecalled = props.data.messageType == 14
  const isNormalMessage = props.data.messageType == 2 || props.data.messageType == 5
  
  if (!isMyMessage || isRecalled || !isNormalMessage) {
    return
  }
  
  // 检查是否在2分钟内
  const currentTime = Date.now()
  const sendTime = props.data.sendTime
  const timeDiff = currentTime - sendTime
  const canRecall = timeDiff <= 120000 // 2分钟 = 120000毫秒
  
  if (!canRecall) {
    return
  }
  
  ContextMenu.showContextMenu({
    x: e.x,
    y: e.y,
    items: [
      {
        label: '撤回',
        onClick: () => {
          emit('recallMessage', props.data.messageId)
        }
      }
    ]
  })
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
