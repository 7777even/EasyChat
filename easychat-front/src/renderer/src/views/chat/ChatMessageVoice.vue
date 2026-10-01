<template>
  <div class="voice-message" @click="togglePlay">
    <div class="voice-content">
      <div class="voice-icon" :class="{ playing: isPlaying }">
        <i class="iconfont icon-voice"></i>
      </div>
      <div class="voice-duration">{{ duration }}s</div>
    </div>
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

const props = defineProps({
  data: {
    type: Object,
    default: () => ({})
  }
})

const audioRef = ref(null)
const isPlaying = ref(false)

const duration = computed(() => {
  return props.data.duration || 0
})

const audioSrc = computed(() => {
  // 语音文件路径
  return props.data.filePath || ''
})

const togglePlay = () => {
  if (!audioRef.value) return
  if (isPlaying.value) {
    audioRef.value.pause()
    isPlaying.value = false
  } else {
    audioRef.value.play()
    isPlaying.value = true
  }
}

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
