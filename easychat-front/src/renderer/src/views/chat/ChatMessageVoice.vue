<template>
  <div class="voice-message" @click="togglePlay">
    <div class="voice-content">
      <div class="voice-icon" :class="{ playing: isPlaying }">
        <i class="iconfont icon-voice"></i>
      </div>
      <div class="voice-duration">{{ duration }}s</div>
    </div>
    <!-- 未播放红点：对标微信，仅对端发给我且我未播放时显示 -->
    <span v-if="unplayed" class="voice-unplayed-dot"></span>
    <audio
      ref="audioRef"
      :src="audioSrc"
      @ended="onEnded"
      @error="onError"
    ></audio>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getCurrentInstance } from 'vue'
const { proxy } = getCurrentInstance()
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'
import { useUserInfoStore } from '@/stores/UserInfoStore'
const globalInfoStore = useGlobalInfoStore()
const userInfoStore = useUserInfoStore()

const props = defineProps({
  data: {
    type: Object,
    default: () => ({})
  }
})

const audioRef = ref(null)
const isPlaying = ref(false)
// 未播放红点：仅对端发给我的语音、且我尚未播放过时显示。
// 状态由后端 chat_message_voice_read 表承载（per-receiver，ADR-001），
// 故跨重启/换设备一致 —— 而非用本地 isRead 标记（那会丢）。
const unplayed = ref(false)

const duration = computed(() => {
  return props.data.duration || 0
})

// 是否为「对方发给我的」语音：左侧气泡（接收方视角）才有未播放红点。
// 自己发的在右侧，无需红点。
const isReceived = computed(() => {
  return props.data && props.data.sendUserId && props.data.sendUserId !== userInfoStore.getInfo().userId
})

const audioSrc = computed(() => {
  // 语音文件路径。
  // 2026-10-03：原从 props.data.filePath 读取 —— 但 chat_message 表**没有 file_path 列**，
  // 该值也是发送方的本地路径（对端无意义）。改走既有下载端点，与 ShowLocalImage 同范式。
  // 接收方下载**对方**发来的语音：downloadFile 的 contact_id 守卫要求
  // request.userId == message.contactId，单聊里 contactId 是对方 id，本机器满足；
  // 不满足的场景（如群聊）已在 QA「未运行项」登记，需接收方是该群成员才放行。
  if (!props.data.messageId) return ''
  // 主进程 express 服务器提供 /file 端点（与 ShowLocalImage 同源）：
  // 本地缓存 miss 时回源 /chat/downloadFile，命中直接读本地。
  // 直接打 5050 的 /chat/downloadFile 是错的——那是后端 HTTP 接口而非
  // 本地文件服务器，且 require POST，不能直接作 <audio src>。
  const localFileServerPort = globalInfoStore.getInfo('localServerPort')
  return `http://localhost:${localFileServerPort}/file?fileId=${props.data.messageId}&partType=chat&fileType=3&showCover=false&${new Date().getTime()}`
})

const togglePlay = () => {
  if (!audioRef.value) return
  if (isPlaying.value) {
    audioRef.value.pause()
    isPlaying.value = false
  } else {
    audioRef.value.play()
    isPlaying.value = true
    // 播放即标记已读：乐观清除红点 + 失败恢复（防止「界面显示已读、实际未记录」不一致）
    if (unplayed.value) {
      markPlayed()
    }
  }
}

// 调后端标记已读，并乐观清除红点；失败恢复红点
const markPlayed = async () => {
  if (!props.data.messageId) return
  unplayed.value = false
  try {
    const res = await proxy.Request({
      url: proxy.Api.markVoiceRead,
      params: { messageId: props.data.messageId },
      showLoading: false
    })
    if (!res) {
      unplayed.value = true
    }
  } catch (e) {
    unplayed.value = true
  }
}

// 挂载后拉一次「该消息我是否已播放」：已播放则不亮红点
const fetchReadState = async () => {
  if (!isReceived.value || !props.data.messageId) return
  try {
    const res = await proxy.Request({
      url: proxy.Api.loadVoiceRead,
      params: { messageIdList: String(props.data.messageId) },
      showLoading: false
    })
    // 已在已读列表里 → 不亮红点；不在 → 亮红点
    unplayed.value = !(res && res.data && res.data.length > 0)
  } catch (e) {
    unplayed.value = true
  }
}

onMounted(() => {
  fetchReadState()
})

const onEnded = () => {
  isPlaying.value = false
}

const onError = () => {
  isPlaying.value = false
  proxy.Message.error('语音播放失败')
}

onUnmounted(() => {
  if (audioRef.value) {
    audioRef.value.pause()
  }
})
</script>

<style lang="scss" scoped>
.voice-message {
  display: flex;
  align-items: center;
  padding: 10px;
  cursor: pointer;
  user-select: none;

  &:hover {
    opacity: 0.8;
  }

  .voice-content {
    display: flex;
    align-items: center;
    gap: 8px;

    .voice-icon {
      width: 32px;
      height: 32px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #666;

      i {
        font-size: 20px;
      }

      &.playing {
        color: #07c160;
        animation: voice-pulse 1s ease-in-out infinite;
      }
    }

    .voice-duration {
      font-size: 14px;
      color: #666;
    }
  }

  // 未播放红点：与气泡同行，小红点
  .voice-unplayed-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #f56c6c;
    margin-left: 6px;
    flex-shrink: 0;
  }
}

@keyframes voice-pulse {
  0%, 100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.1);
  }
}
</style>
