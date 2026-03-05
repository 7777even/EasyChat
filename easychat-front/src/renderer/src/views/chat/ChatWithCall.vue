<template>
  <div class="chat-with-call">
    <!-- 聊天头部 -->
    <div class="chat-header">
      <div class="contact-info">
        <span class="contact-name">{{ contactName }}</span>
      </div>
      <div class="call-buttons">
        <el-button 
          circle 
          @click="startCall(0)"
          title="语音通话"
        >
          <i class="iconfont icon-phone"></i>
        </el-button>
        <el-button 
          circle 
          @click="startCall(1)"
          title="视频通话"
        >
          <i class="iconfont icon-video"></i>
        </el-button>
      </div>
    </div>

    <!-- 聊天内容区域 -->
    <div class="chat-content">
      <!-- 你的聊天消息列表 -->
    </div>

    <!-- 通话集成组件 -->
    <CallIntegration ref="callIntegrationRef" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import CallIntegration from './CallIntegration.vue'
import callManager from '../../utils/CallManager'
import { useUserInfoStore } from '../../stores/UserInfoStore'

const props = defineProps({
  contactId: String,
  contactName: String,
  contactType: Number, // 0-单聊 1-群聊
  groupMembers: Array // 群组成员列表
})

const callIntegrationRef = ref(null)
const userInfoStore = useUserInfoStore()

// 设置当前用户ID
callManager.setCurrentUserId(userInfoStore.getInfo().userId)

// 发起通话
const startCall = (callType) => {
  if (!callIntegrationRef.value) {
    console.error('通话组件未初始化')
    return
  }

  const isGroup = props.contactType === 1

  callIntegrationRef.value.startCall({
    callType, // 0-语音 1-视频
    isGroup,
    callName: props.contactName,
    toId: props.contactId,
    members: isGroup ? props.groupMembers : []
  })
}
</script>

<style scoped lang="scss">
.chat-with-call {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px 20px;
  border-bottom: 1px solid #e0e0e0;
  background: #fff;
}

.contact-info {
  .contact-name {
    font-size: 16px;
    font-weight: 500;
  }
}

.call-buttons {
  display: flex;
  gap: 10px;

  .el-button {
    width: 40px;
    height: 40px;
    padding: 0;
    
    .iconfont {
      font-size: 20px;
    }
  }
}

.chat-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
</style>
