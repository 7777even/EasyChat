<template>
  <div class="admin-window">
    <div class="title drag">管理员</div>
    <div class="body-content">
      <div class="left-side">
        <div
          :class="['menu-item', (item.routeName == route.name || route.path.startsWith(item.path)) ? 'active' : '']"
          @click="menuJump(item)"
          v-for="item in menuList"
        >
          <div :class="['iconfont', item.icon]" :style="{ background: item.iconBgColor }"></div>
          <div class="text">{{ item.name }}</div>
        </div>
      </div>
      <div class="right-content">
        <router-view v-slot="{ Component }" v-if="tokenReady">
          <component :is="Component" ref="componentRef" />
        </router-view>
      </div>
    </div>
  </div>
  <WinOp :showSetTop="false"></WinOp>
</template>

<script setup>
import { ref, reactive, getCurrentInstance, nextTick, onMounted, onUnmounted } from 'vue'
const { proxy } = getCurrentInstance()
import { useRouter, useRoute } from 'vue-router'
const router = useRouter()
const route = useRoute()

import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'
const globalInfoStore = useGlobalInfoStore()

const tokenReady = ref(false)

const menuList = ref([
  {
    name: '用户管理',
    routeName: '用户管理',
    icon: 'icon-user',
    path: '/admin/userList',
    iconBgColor: '#fa9d3b'
  },
  {
    name: '靓号管理',
    routeName: '靓号管理',
    icon: 'icon-beauty-beauty',
    path: '/admin/beautyAccount',
    iconBgColor: '#fe90b3'
  },
  {
    name: '群组管理',
    routeName: '群组管理',
    icon: 'icon-group',
    path: '/admin/groupList',
    iconBgColor: '#1485ee'
  },
  {
    name: '系统设置',
    routeName: '系统设置',
    icon: 'icon-setting',
    path: '/admin/sysSetting',
    iconBgColor: '#fa5151'
  },
  {
    name: '版本管理',
    routeName: '版本管理',
    icon: 'icon-refresh',
    path: '/admin/update',
    iconBgColor: '#07c160'
  }
])

const menuJump = (item) => {
  // 如果当前路由已经是目标路由，则不进行导航
  if (route.name === item.routeName || route.path === item.path) {
    return
  }
  router.push({ name: item.routeName }).catch(err => {
    // 如果使用名称路由失败（如 NavigationDuplicated），回退到路径路由
    if (err.name !== 'NavigationDuplicated') {
      router.push(item.path).catch(() => {
        // 忽略重复导航错误
      })
    }
  })
}

onMounted(() => {
  // 先检查 localStorage 中是否已有 token（窗口已存在的情况）
  const existingToken = localStorage.getItem('token')
  if (existingToken) {
    tokenReady.value = true
  }
  
  if (window.ipcRenderer) {
    window.ipcRenderer.on('pageInitData', (e, data) => {
      if (data.token) {
        localStorage.setItem('token', data.token)
        tokenReady.value = true
      }
      if (data.localServerPort) {
        globalInfoStore.setInfo('localServerPort', data.localServerPort)
      }
    })
  }
})
onUnmounted(() => {
  if (window.ipcRenderer) {
    window.ipcRenderer.removeAllListeners('pageInitData')
  }
})
</script>

<style lang="scss" scoped>
.admin-window {
  padding: 0px;
  border: 1px solid #ddd;
  background: #fff;
  position: relative;
  overflow: hidden;
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  min-width: 600px;
  min-height: 400px;
  .title {
    height: 40px;
    line-height: 40px;
    border-bottom: 1px solid #ddd;
    text-align: center;
    font-weight: bold;
    flex-shrink: 0;
  }
  .body-content {
    flex: 1;
    display: flex;
    overflow: hidden;
    min-height: 0;
    .left-side {
      width: 200px;
      min-width: 150px;
      border-right: 1px solid #ddd;
      background: #e6e5e5;
      overflow-y: auto;
      overflow-x: hidden;
      flex-shrink: 0;
      .menu-item {
        display: flex;
        align-items: center;
        padding: 10px 10px;
        position: relative;
        min-height: 55px;
        &:hover {
          cursor: pointer;
          background: #d6d6d7;
        }
        .iconfont {
          width: 35px;
          height: 35px;
          min-width: 35px;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 20px;
          color: #fff;
        }
        .text {
          flex: 1;
          color: #000000;
          margin-left: 10px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
      .active {
        background: #c4c4c4;
        &:hover {
          background: #c4c4c4;
        }
      }
    }
    .right-content {
      flex: 1;
      padding: 10px;
      overflow: auto;
      min-width: 0;
    }
  }
}
</style>
