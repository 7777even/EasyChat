<template>
  <ContentPanel v-loading="loading" element-loading-text="加载中">
    <div class="favorite-page">
      <div class="favorite-tip">共 {{ favoriteList.length }} 条收藏</div>
      <el-empty v-if="!loading && favoriteList.length == 0" description="暂无收藏，右键消息可收藏" />
      <div class="favorite-list" v-else>
        <div class="favorite-item" v-for="item in favoriteList" :key="item.id">
          <div class="favorite-body">
            <div class="favorite-content">{{ item.content || '（无文本内容）' }}</div>
            <div class="favorite-meta">
              <span v-if="item.filePath" class="favorite-file">{{ item.filePath }}</span>
              <span class="favorite-time">{{ formatTime(item.createTime) }}</span>
            </div>
          </div>
          <el-button type="danger" plain size="small" @click="cancel(item)">取消收藏</el-button>
        </div>
      </div>
    </div>
  </ContentPanel>
</template>

<script setup>
import { ref, getCurrentInstance, onMounted } from 'vue'
const { proxy } = getCurrentInstance()

const loading = ref(false)
const favoriteList = ref([])

const formatTime = (ts) => {
  if (!ts) return ''
  const d = new Date(ts)
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const loadList = async () => {
  loading.value = true
  let result = await proxy.Request({
    url: proxy.Api.listFavorite,
    method: 'get',
    showLoading: false
  })
  loading.value = false
  if (!result) return
  favoriteList.value = result.data || []
}

const cancel = (item) => {
  proxy.Confirm({
    message: '确定取消收藏吗?',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.cancelFavorite,
        params: { favoriteId: item.id },
        showLoading: false
      })
      if (!result) return
      proxy.Message.success('已取消收藏')
      loadList()
    }
  })
}

onMounted(() => {
  loadList()
})
</script>

<style lang="scss" scoped>
.favorite-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 10px;
  box-sizing: border-box;
}
.favorite-tip {
  color: #888888;
  font-size: 13px;
  margin-bottom: 10px;
}
.favorite-list {
  flex: 1;
  overflow-y: auto;
}
.favorite-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: var(--ec-card-bg);
  border: 1px solid var(--ec-divider);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.favorite-body {
  flex: 1;
  min-width: 0;
}
.favorite-content {
  font-size: 14px;
  color: var(--ec-text-primary);
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.favorite-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: #888888;
}
.favorite-file {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}
</style>
