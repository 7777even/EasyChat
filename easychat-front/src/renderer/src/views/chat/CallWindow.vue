<template>
  <div v-if="callStore.status !== 'idle'" class="call-window-mask">
    <div class="call-window">
      <!-- 来电提醒（被叫侧） -->
      <div v-if="callStore.status === 'ringing'" class="call-incoming">
        <div class="call-avatar"></div>
        <div class="call-title">{{ callStore.callType === 2 ? '群通话邀请' : '好友通话邀请' }}</div>
        <div class="call-sub">{{ callStore.mediaType === 2 ? '音视频通话' : '语音通话' }}</div>
        <div class="call-actions">
          <el-button type="danger" circle @click="callStore.rejectCall()">拒绝</el-button>
          <el-button type="warning" circle @click="callStore.bus()">忙线</el-button>
          <el-button type="success" circle @click="callStore.acceptCall()">接听</el-button>
        </div>
      </div>

      <!-- 通话中 / 发起中 -->
      <div v-else class="call-active">
        <div class="call-header">
          <span>{{ callStore.callType === 2 ? '群通话' : '通话中' }}</span>
          <span v-if="callStore.endReason" class="call-end-reason">{{ callStore.endReason }}</span>
        </div>
        <div class="call-video-grid">
          <div class="video-cell">
            <video ref="localVideoRef" autoplay playsinline muted></video>
            <div class="video-tag">
              我{{ callStore.isMuted ? '·静音' : '' }}{{ callStore.isCameraOff ? '·关摄像头' : '' }}
            </div>
          </div>
          <div class="video-cell" v-for="(stream, uid) in callStore.remoteStreams" :key="uid">
            <video :ref="(el) => setRemoteRef(uid, el)" autoplay playsinline></video>
            <div class="video-tag">{{ uid }}</div>
          </div>
        </div>
        <div class="call-controls">
          <el-button :type="callStore.isMuted ? 'info' : 'primary'" circle @click="callStore.toggleMute()">
            {{ callStore.isMuted ? '取消静音' : '静音' }}
          </el-button>
          <el-button :type="callStore.isCameraOff ? 'info' : 'primary'" circle @click="callStore.toggleCamera()">
            {{ callStore.isCameraOff ? '开摄像头' : '关摄像头' }}
          </el-button>
          <el-button type="danger" circle @click="callStore.hangup()">挂断</el-button>
        </div>
        <div v-if="callStore.callType === 2" class="call-members">
          成员：{{ callStore.members.join('、') }}
        </div>
      </div>

      <div v-if="callStore.errorMsg" class="call-error">{{ callStore.errorMsg }}</div>
    </div>
  </div>
</template>

<script setup>
import { useCallStore } from '@/stores/useCallStore'
import { ref, watch, nextTick, onMounted } from 'vue'

const callStore = useCallStore()
const localVideoRef = ref(null)
const remoteRefs = {}

const setRemoteRef = (uid, el) => {
  if (el) remoteRefs[uid] = el
}

const bindLocal = () => {
  if (localVideoRef.value && callStore.localStream) {
    localVideoRef.value.srcObject = callStore.localStream
  }
}

const bindRemote = () => {
  for (const uid in remoteRefs) {
    const el = remoteRefs[uid]
    const stream = callStore.remoteStreams[uid]
    if (el && stream && el.srcObject !== stream) {
      el.srcObject = stream
    }
  }
}

watch(() => callStore.localStream, () => nextTick(bindLocal))
watch(() => callStore.remoteStreams, () => nextTick(bindRemote), { deep: true })
onMounted(() => {
  bindLocal()
  bindRemote()
})
</script>

<style scoped>
.call-window-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
}
.call-window {
  width: 440px;
  background: var(--ec-card-bg);
  border-radius: 10px;
  padding: 22px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.25);
}
.call-incoming {
  text-align: center;
}
.call-avatar {
  width: 56px;
  height: 56px;
  margin: 0 auto 10px;
  border-radius: 50%;
  background: #409eff;
}
.call-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}
.call-sub {
  color: #888;
  margin-bottom: 18px;
}
.call-actions {
  display: flex;
  justify-content: center;
  gap: 16px;
}
.call-header {
  display: flex;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 10px;
}
.call-end-reason {
  color: #e6a23c;
  font-weight: 400;
  font-size: 13px;
}
.call-video-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.video-cell {
  position: relative;
  width: 48%;
  aspect-ratio: 4 / 3;
  background: #000;
  border-radius: 6px;
  overflow: hidden;
}
.video-cell video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.video-tag {
  position: absolute;
  left: 6px;
  bottom: 6px;
  color: #fff;
  font-size: 12px;
  background: rgba(0, 0, 0, 0.4);
  padding: 1px 6px;
  border-radius: 4px;
}
.call-controls {
  display: flex;
  justify-content: center;
  gap: 12px;
}
.call-members {
  margin-top: 10px;
  font-size: 12px;
  color: #888;
  text-align: center;
  word-break: break-all;
}
.call-error {
  margin-top: 10px;
  color: #f56c6c;
  font-size: 13px;
  text-align: center;
}
</style>
