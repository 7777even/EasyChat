<template>
  <div class="call-integration">
    <!-- 通话窗口 -->
    <CallWindow
      v-if="callManager.currentCall.value"
      :visible="!!callManager.currentCall.value"
      :call-id="callManager.currentCall.value.callId"
      :call-type="callManager.currentCall.value.callType"
      :is-group="callManager.currentCall.value.isGroup"
      :is-incoming="callManager.currentCall.value.isIncoming"
      :call-name="callManager.currentCall.value.callName"
      :to-id="callManager.currentCall.value.toId"
      :members="callManager.currentCall.value.members"
      @accept="handleAccept"
      @reject="handleReject"
      @hangup="handleHangup"
      ref="callWindowRef"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import CallWindow from '../../components/CallWindow.vue'
import callManager from '../../utils/CallManager'
import request from '../../utils/Request'
import { useUserInfoStore } from '@/stores/UserInfoStore'

const callWindowRef = ref(null)
const userInfoStore = useUserInfoStore()

// 发送信令到服务器
const sendSignalToServer = async (signalData) => {
  try {
    // 添加当前用户信息
    const userInfo = userInfoStore.getInfo()
    const payload = {
      ...signalData,
      fromUserId: userInfo.userId,
      fromUserNickName: userInfo.nickName
    }
    
    await request({
      url: '/webrtc/signal',
      method: 'post',
      params: payload,
      dataType: 'json',  // 重要：指定为 JSON 格式
      showLoading: false
    })
  } catch (error) {
    console.error('发送信令失败:', error)
  }
}

// 处理接听
const handleAccept = () => {
  if (callWindowRef.value) {
    callManager.acceptCall(callWindowRef.value.webrtcManager)
  }
}

// 处理拒绝
const handleReject = () => {
  callManager.rejectCall()
}

// 处理挂断
const handleHangup = () => {
  callManager.hangupCall()
}

// 处理接收到的 WebRTC 信令
const handleWebRTCSignal = (message) => {
  const signalData = message.extendData

  if (signalData.signalType === 'call') {
    // 收到来电
    callManager.receiveCall(signalData)
  } else {
    // 处理其他信令
    callManager.handleSignal(signalData)
  }
}

// 监听 WebSocket 消息
const messageListener = (_event, message) => {
  if (message.messageType === 19) {
    // WebRTC 信令消息
    handleWebRTCSignal(message)
  }
}

onMounted(() => {
  // 设置发送信令回调
  callManager.setSendSignalCallback(sendSignalToServer)

  // 监听消息
  window.ipcRenderer.on('reciveMessage', messageListener)
})

onUnmounted(() => {
  window.ipcRenderer.removeListener('reciveMessage', messageListener)
})

// 暴露方法供外部调用
defineExpose({
  startCall: (callInfo) => {
    return callManager.startCall(callInfo)
  }
})
</script>

<style scoped lang="scss">
.call-integration {
  // 样式可以根据需要添加
}
</style>
