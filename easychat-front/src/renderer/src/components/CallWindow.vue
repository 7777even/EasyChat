<template>
  <div class="call-window" v-if="visible">
    <div class="call-container">
      <!-- 通话头部 -->
      <div class="call-header">
        <div class="call-info">
          <span class="call-name">{{ callName }}</span>
          <span class="call-status">{{ callStatusText }}</span>
        </div>
        <div class="call-time" v-if="callDuration > 0">{{ formatDuration(callDuration) }}</div>
      </div>

      <!-- 视频区域 -->
      <div class="video-container" v-if="callType === 1">
        <!-- 远程视频 -->
        <div class="remote-videos">
          <video
            v-for="(stream, userId) in remoteStreams"
            :key="userId"
            :ref="(el) => setRemoteVideo(userId, el)"
            autoplay
            playsinline
            class="remote-video"
          ></video>
          <div v-if="Object.keys(remoteStreams).length === 0" class="waiting-video">
            <div class="avatar-large">{{ callName.charAt(0) }}</div>
          </div>
        </div>
        <!-- 本地视频 -->
        <video
          ref="localVideo"
          autoplay
          playsinline
          muted
          class="local-video"
        ></video>
      </div>

      <!-- 语音通话界面 -->
      <div class="audio-container" v-else>
        <div class="avatar-large">{{ callName.charAt(0) }}</div>
      </div>

      <!-- 控制按钮 -->
      <div class="call-controls">
        <button
          class="control-btn"
          :class="{ active: !audioEnabled }"
          @click="toggleAudio"
          title="麦克风"
        >
          <i :class="audioEnabled ? 'iconfont icon-microphone' : 'iconfont icon-microphone-off'"></i>
        </button>
        <button
          v-if="callType === 1"
          class="control-btn"
          :class="{ active: !videoEnabled }"
          @click="toggleVideo"
          title="摄像头"
        >
          <i :class="videoEnabled ? 'iconfont icon-video' : 'iconfont icon-video-off'"></i>
        </button>
        <button
          class="control-btn hangup"
          @click="hangup"
          title="挂断"
        >
          <i class="iconfont icon-phone-hangup"></i>
        </button>
        <button
          v-if="isIncoming && callStatus === 'calling'"
          class="control-btn accept"
          @click="accept"
          title="接听"
        >
          <i class="iconfont icon-phone"></i>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onUnmounted, watch } from 'vue'
import WebRTCManager from '../utils/WebRTC'

const props = defineProps({
  visible: Boolean,
  callId: String,
  callType: Number, // 0-语音 1-视频
  isGroup: Boolean,
  isIncoming: Boolean, // 是否是来电
  callName: String,
  toId: String,
  members: Array
})

const emit = defineEmits(['close', 'accept', 'reject', 'hangup'])

const webrtcManager = new WebRTCManager()
const localVideo = ref(null)
const remoteStreams = ref({})
const audioEnabled = ref(true)
const videoEnabled = ref(true)
const callStatus = ref('calling') // calling, connected, ended
const callDuration = ref(0)
let durationTimer = null

const callStatusText = computed(() => {
  if (callStatus.value === 'calling') {
    return props.isIncoming ? '邀请你通话...' : '等待对方接听...'
  } else if (callStatus.value === 'connected') {
    return '通话中'
  }
  return ''
})

// 设置远程视频元素
const setRemoteVideo = (userId, el) => {
  if (el && remoteStreams.value[userId]) {
    el.srcObject = remoteStreams.value[userId]
  }
}

// 初始化本地流
const initLocalStream = async () => {
  try {
    const stream = await webrtcManager.initLocalStream(props.callType === 1)
    if (localVideo.value) {
      localVideo.value.srcObject = stream
    }
  } catch (error) {
    console.error('初始化本地流失败:', error)
  }
}

// 监听远程流
webrtcManager.onRemoteStream((userId, stream) => {
  remoteStreams.value[userId] = stream
})

webrtcManager.onRemoteStreamRemoved((userId) => {
  delete remoteStreams.value[userId]
})

// 切换音频
const toggleAudio = () => {
  audioEnabled.value = !audioEnabled.value
  webrtcManager.toggleAudio(audioEnabled.value)
}

// 切换视频
const toggleVideo = () => {
  videoEnabled.value = !videoEnabled.value
  webrtcManager.toggleVideo(videoEnabled.value)
}

// 接听
const accept = () => {
  callStatus.value = 'connected'
  startDurationTimer()
  emit('accept')
}

// 挂断
const hangup = () => {
  callStatus.value = 'ended'
  stopDurationTimer()
  webrtcManager.close()
  emit('hangup')
}

// 格式化通话时长
const formatDuration = (seconds) => {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) {
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  }
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

// 开始计时
const startDurationTimer = () => {
  durationTimer = setInterval(() => {
    callDuration.value++
  }, 1000)
}

// 停止计时
const stopDurationTimer = () => {
  if (durationTimer) {
    clearInterval(durationTimer)
    durationTimer = null
  }
}

// 监听可见性变化
watch(() => props.visible, (newVal) => {
  if (newVal) {
    initLocalStream()
  } else {
    webrtcManager.close()
    stopDurationTimer()
  }
})

onUnmounted(() => {
  webrtcManager.close()
  stopDurationTimer()
})

// 暴露方法给父组件
defineExpose({
  webrtcManager,
  setCallStatus: (status) => {
    callStatus.value = status
    if (status === 'connected') {
      startDurationTimer()
    }
  }
})
</script>

<style scoped lang="scss">
.call-window {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.9);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.call-container {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}

.call-header {
  position: absolute;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  text-align: center;
  color: white;
  z-index: 10;
}

.call-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.call-name {
  font-size: 24px;
  font-weight: bold;
}

.call-status {
  font-size: 14px;
  opacity: 0.8;
}

.call-time {
  margin-top: 10px;
  font-size: 16px;
}

.video-container {
  flex: 1;
  position: relative;
  background: #000;
}

.remote-videos {
  width: 100%;
  height: 100%;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 10px;
}

.remote-video {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.waiting-video {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}

.local-video {
  position: absolute;
  bottom: 100px;
  right: 20px;
  width: 200px;
  height: 150px;
  object-fit: cover;
  border-radius: 8px;
  border: 2px solid rgba(255, 255, 255, 0.3);
}

.audio-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-large {
  width: 150px;
  height: 150px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 60px;
  color: white;
  font-weight: bold;
}

.call-controls {
  position: absolute;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 20px;
  z-index: 10;
}

.control-btn {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  font-size: 24px;
  cursor: pointer;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: rgba(255, 255, 255, 0.3);
  }

  &.active {
    background: rgba(255, 59, 48, 0.8);
  }

  &.hangup {
    background: rgba(255, 59, 48, 0.9);

    &:hover {
      background: rgba(255, 59, 48, 1);
    }
  }

  &.accept {
    background: rgba(52, 199, 89, 0.9);

    &:hover {
      background: rgba(52, 199, 89, 1);
    }
  }
}
</style>
