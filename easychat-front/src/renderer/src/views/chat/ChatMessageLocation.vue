<template>
  <div class="location-bubble" @click="openDetail">
    <div class="location-icon">
      <span class="iconfont icon-top"></span>
    </div>
    <div class="location-info">
      <div class="location-name">{{ locationInfo.location || '位置' }}</div>
      <div class="location-coord" v-if="hasCoord">
        {{ locationInfo.latitude.toFixed(5) }}, {{ locationInfo.longitude.toFixed(5) }}
      </div>
    </div>
  </div>

  <el-dialog
    v-model="detailVisible"
    title="位置详情"
    width="400px"
    append-to-body
  >
    <div class="location-detail">
      <div class="detail-name">
        <span class="iconfont icon-top"></span>
        {{ locationInfo.location || '位置' }}
      </div>
      <div class="detail-row" v-if="hasCoord">
        <span class="label">经度：</span>{{ locationInfo.longitude }}
      </div>
      <div class="detail-row" v-if="hasCoord">
        <span class="label">纬度：</span>{{ locationInfo.latitude }}
      </div>
      <div class="detail-row" v-else>
        <span class="label">经纬度：</span>未提供
      </div>
    </div>
    <template #footer>
      <el-button @click="detailVisible = false">关闭</el-button>
      <el-button type="primary" @click="openInMap" :disabled="!hasCoord">在地图中打开</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, getCurrentInstance } from 'vue'
const { proxy } = getCurrentInstance()

const props = defineProps({
  data: {
    type: Object,
    default: () => ({})
  }
})

const detailVisible = ref(false)

/**
 * 位置信息存于 extraData（JSON 字符串）：
 * { "location": "北京市朝阳区", "latitude": 39.9042, "longitude": 116.4074 }
 * 兼容旧形态：extraData 直接是地址字符串
 */
const locationInfo = computed(() => {
  const extra = props.data.extraData
  if (!extra) return {}
  try {
    const parsed = JSON.parse(extra)
    if (typeof parsed === 'string') {
      return { location: parsed, latitude: null, longitude: null }
    }
    return {
      location: parsed.location || '',
      latitude: parsed.latitude ?? null,
      longitude: parsed.longitude ?? null
    }
  } catch (e) {
    return { location: extra, latitude: null, longitude: null }
  }
})

const hasCoord = computed(
  () => locationInfo.value.latitude != null && locationInfo.value.longitude != null
)

const openDetail = () => {
  detailVisible.value = true
}

// 用系统默认地图打开（高德 URI，无需 Key）
const openInMap = () => {
  const { latitude, longitude, location } = locationInfo.value
  if (latitude == null || longitude == null) {
    proxy.Message.warning('该位置没有经纬度信息')
    return
  }
  const url =
    `https://uri.amap.com/marker?position=${longitude},${latitude}` +
    `&name=${encodeURIComponent(location || '位置')}&src=easychat&coordinate=gaode&callnative=0`
  window.ipcRenderer.send('openUrl', { url })
  detailVisible.value = false
}
</script>

<style lang="scss" scoped>
.location-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
  max-width: 260px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: opacity 0.2s;
  &:hover {
    opacity: 0.85;
  }
}
.location-icon {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #e8f4ff;
  color: #1677ff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.location-info {
  min-width: 0;
}
.location-name {
  font-size: 14px;
  color: var(--ec-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.location-coord {
  font-size: 12px;
  color: #888888;
  margin-top: 2px;
}
.location-detail {
  padding: 4px 0;
}
.detail-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  color: var(--ec-text-primary);
  margin-bottom: 12px;
}
.detail-row {
  font-size: 13px;
  color: #666666;
  margin-bottom: 6px;
  .label {
    color: #999999;
  }
}
</style>
