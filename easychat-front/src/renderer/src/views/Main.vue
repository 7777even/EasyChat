<template>
  <div class="main">
    <div class="left-sider">
      <Avatar :userId="userInfoStore.getInfo().userId" :width="35" :showDetail="false"></Avatar>
      <div class="menu-list">
        <template v-for="item in menuList">
          <div
            :class="['tab-item iconfont', item.icon, item.path == currentMenu.path ? 'active' : '']"
            @click="changeMenu(item)"
            v-if="item.position == 'top'"
          >
            <template v-if="item.name == 'chat' || item.name == 'contact'">
              <Badge :count="messageCountStore.getCount(item.countKey)" :top="5" :left="15"></Badge>
            </template>
            <!-- 朋友圈未读通知红点 -->
            <template v-if="item.name == 'moment'">
              <Badge :count="momentUnread" :top="5" :left="15"></Badge>
            </template>
          </div>
        </template>
      </div>
      <div class="menu-list menu-buttom">
        <template v-for="item in menuList">
          <div
            :class="['tab-item iconfont', item.icon, item.path == currentMenu.path ? 'active' : '']"
            @click="changeMenu(item)"
            v-if="item.position == 'bottom'"
          ></div>
        </template>
      </div>
    </div>
    <div class="right-container">
      <router-view v-slot="{ Component }">
        <keep-alive include="chat">
          <component :is="Component" ref="componentRef" />
        </keep-alive>
      </router-view>
    </div>
    <Update></Update>
  </div>
  <WinOp></WinOp>
  <CallWindow></CallWindow>
</template>

<script setup>
import Update from './Update.vue'
import Badge from '@/components/Badge.vue'
import CallWindow from './chat/CallWindow.vue'
import { ref, reactive, getCurrentInstance, nextTick, onUnmounted, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
const { proxy } = getCurrentInstance()
const router = useRouter()
const route = useRoute()
import { useUserInfoStore } from '@/stores/UserInfoStore'
const userInfoStore = useUserInfoStore()

import { useSysSettingStore } from '@/stores/SysSettingStore'
const sysSettingStore = useSysSettingStore()

import { useMessageCountStore } from '@/stores/MessageCountStore'
const messageCountStore = useMessageCountStore()

import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'
const globalInfoStore = useGlobalInfoStore()

import { useAvatarInfoStore } from '@/stores/AvatarUpdateStore'
const avatarInfoStore = useAvatarInfoStore()

import { useCallStore } from '@/stores/useCallStore'

import { applyTheme } from '@/utils/theme'

const menuList = ref([
  {
    name: 'chat',
    icon: 'icon-chat',
    path: '/chat',
    countKey: 'chatCount',
    position: 'top'
  },
  {
    name: 'moment',
    icon: 'icon-more',
    path: '/moment',
    position: 'top'
  },
  {
    name: 'contact',
    icon: 'icon-user',
    path: '/contact',
    countKey: 'contactApplyCount',
    position: 'top'
  },
  {
    name: 'mysetting',
    icon: 'icon-more2',
    path: '/setting',
    position: 'bottom'
  }
])

const componentRef = ref(null)
const changeMenu = (item) => {
  currentMenu.value = item
  // 进入朋友圈即视为已读，消掉红点
  if (item.name == 'moment' && momentUnread.value > 0) {
    markMomentRead()
  }
  router.push(item.path)
}

// ===== 朋友圈通知红点 =====
const momentUnread = ref(0)

const loadMomentUnread = async () => {
  try {
    const result = await proxy.Request({
      url: proxy.Api.momentUnreadCount,
      showLoading: false,
      showError: false
    })
    if (result && result.data != null) {
      momentUnread.value = result.data
    }
  } catch (e) {
    // 静默失败：红点非核心链路
  }
}

const markMomentRead = async () => {
  momentUnread.value = 0
  try {
    await proxy.Request({
      url: proxy.Api.momentMarkAllRead,
      showLoading: false,
      showError: false
    })
  } catch (e) {
    // 忽略
  }
}

const currentMenu = ref(menuList.value[0])
const menuSelect = (path) => {
  currentMenu.value = menuList.value.find((item) => {
    return path.includes(item.path)
  })
}

//获取登录信息
const getLoginInfo = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getUserInfo
  })
  if (!result) {
    return
  }
  userInfoStore.setInfo(result.data)
  //登录后强制回源本人头像一次，修复重新登录后展示本地旧缓存/兜底图的问题
  avatarInfoStore.setFoceReload(result.data.userId, true)
  window.ipcRenderer.send('getLocalStore', result.data.userId + 'localServerPort')
}

//获取系统设置信息
const getSysSetting = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getSysSetting,
    method: 'GET'
  })
  if (!result) {
    return
  }
  sysSettingStore.setSetting(result.data)
}
onMounted(() => {
  window.ipcRenderer.on('getLocalStoreCallback', (e, serverPort) => {
    globalInfoStore.setInfo('localServerPort', serverPort)
  })

  getLoginInfo()

  getSysSetting()

  // 应用本地持久化的主题（浅色/深色），读取 user_setting.sysSetting.theme
  window.ipcRenderer.send('getSysSetting')
  window.ipcRenderer.on('getSysSettingCallback', (e, sysSetting) => {
    if (!sysSetting) {
      return
    }
    try {
      const parsed = JSON.parse(sysSetting)
      applyTheme(parsed.theme === 'dark' ? 'dark' : 'light')
    } catch (err) {
      applyTheme('light')
    }
  })

  loadMomentUnread()

  // 朋友圈新通知：实时点亮红点
  window.ipcRenderer.on('momentNotify', (e, message) => {
    const extend = message.extendData || {}
    if (extend.unreadCount != null) {
      momentUnread.value = extend.unreadCount
    } else {
      momentUnread.value = momentUnread.value + 1
    }
  })

  // 未读数变化（已读/清空同步）
  window.ipcRenderer.on('momentUnread', (e, data) => {
    if (data && data.unreadCount != null) {
      momentUnread.value = data.unreadCount
    }
  })

  window.ipcRenderer.on('reLogin', (e, info) => {
    router.push('/login')
  })

  //重新加载头像：强制回源下载完成后置回 false，避免 forceGet 常驻导致每次渲染都回源
  window.ipcRenderer.on('reloadAvatar', (e, fileId) => {
    avatarInfoStore.setFoceReload(fileId, false)
  })

  // 通话信令帧：主进程经 WebSocket 收到后转发到此，由 useCallStore 统一分发
  window.ipcRenderer.on('callMessage', (e, frame) => {
    if (frame && frame.messageType != null) {
      useCallStore().handleFrame(frame)
    }
  })
})

onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('getLocalStoreCallback')
  window.ipcRenderer.removeAllListeners('reLogin')
  window.ipcRenderer.removeAllListeners('reloadAvatar')
  window.ipcRenderer.removeAllListeners('momentNotify')
  window.ipcRenderer.removeAllListeners('momentUnread')
  window.ipcRenderer.removeAllListeners('getSysSettingCallback')
  window.ipcRenderer.removeAllListeners('callMessage')
})

watch(
  () => route.path,
  (newVal, oldVal) => {
    if (newVal) {
      menuSelect(newVal)
    }
  },
  { immediate: true, deep: true }
)
</script>

<style lang="scss" scoped>
.main {
  background: var(--ec-bg);
  display: flex;
  border-radius: 0px 3px 3px 0px;
  overflow: hidden;
  .left-sider {
    width: 55px;
    background: var(--ec-sider-bg);
    text-align: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding-top: 35px;
    border: 1px solid var(--ec-sider-bg);
    border-right: none;
    padding-bottom: 10px;
    .menu-list {
      width: 100%;
      flex: 1;
      .tab-item {
        color: #d3d3d3;
        font-size: 20px;
        height: 40px;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-top: 10px;
        cursor: pointer;
        font-size: 22px;
        position: relative;
      }
      .active {
        color: #07c160;
      }
    }
    .menu-buttom {
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
    }
  }
  .right-container {
    flex: 1;
    overflow: hidden;
    border: 1px solid var(--ec-border);
    border-left: none;
  }
}

.popover-user-panel {
  padding: 10px;
  .popover-user {
    display: flex;
    border-bottom: 1px solid #ddd;
    padding-bottom: 20px;
  }
  .send-message {
    margin-top: 10px;
    text-align: center;
    padding: 20px 0px 0px 0px;
  }
}
</style>
