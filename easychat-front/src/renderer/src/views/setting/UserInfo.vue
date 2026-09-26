<template>
  <ContentPanel>
    <div class="show-info" v-if="showType == 0">
      <div class="user-info">
        <UserBaseInfo :userInfo="userInfo"></UserBaseInfo>
        <div class="more-op">
          <el-dropdown placement="bottom-end" trigger="click">
            <span class="el-dropdown-link">
              <div class="iconfont icon-more"></div>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="changePart(1)">修改个人信息</el-dropdown-item>
                <el-dropdown-item @click="changePart(2)">修改密码</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <div class="part-item">
        <div class="part-title">朋友权限</div>
        <div class="part-content">
          {{ userInfo.joinType == 0 ? '直接加入' : '加我为好友时需要验证' }}
        </div>
      </div>
      <div class="part-item">
        <div class="part-title">个性签名</div>
        <div class="part-content">{{ userInfo.personalSignature || '-' }}</div>
      </div>
      <div class="part-item">
        <div class="part-title">新消息通知</div>
        <div class="part-content notify-row">
          <el-switch v-model="notifySwitch" @change="notifySwitchChange" />
          <div class="tips">关闭后收到新消息将不再闪烁任务栏图标</div>
        </div>
      </div>
      <div class="part-item">
        <div class="part-title">外观主题</div>
        <div class="part-content">
          <el-radio-group v-model="theme" @change="themeChange">
            <el-radio label="light">浅色</el-radio>
            <el-radio label="dark">深色</el-radio>
          </el-radio-group>
        </div>
      </div>
      <div class="logout">
        <el-button @click="logout">退出登录</el-button>
      </div>
    </div>
    <div v-if="showType == 1">
      <UserInfoEdit @editBack="editBack" :data="userInfo"></UserInfoEdit>
    </div>
    <div v-if="showType == 2">
      <UserPassword @editBack="editBack"></UserPassword>
    </div>
  </ContentPanel>
</template>

<script setup>
import UserInfoEdit from './UserInfoEdit.vue'
import UserPassword from './UserInfoPassword.vue'
import { ref, reactive, getCurrentInstance, nextTick, watch, computed, onMounted, onUnmounted } from 'vue'
const { proxy } = getCurrentInstance()
import { useRoute } from 'vue-router'
const route = useRoute()
import { applyTheme } from '@/utils/theme'

const userInfo = ref({})

const getUserInfo = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getUserInfo
  })
  if (!result) {
    return
  }
  userInfo.value = result.data
}
getUserInfo()

const showType = ref(0)

// ===== 新消息提醒开关：任务栏闪烁（openspec/changes/2026-09-24-desktop-notification C2） =====
// 默认开；挂载时经主进程 getSysSetting 读 user_setting.sysSetting.notifySwitch（缺失视为开）
const notifySwitch = ref(true)

// ===== 外观主题：浅色/深色（本地 user_setting.sysSetting.theme，缺失视为浅色）=====
const theme = ref('light')
const themeBeforeSave = ref('light')
const themeChange = (value) => {
  themeBeforeSave.value = theme.value
  theme.value = value
  applyTheme(value)
  window.ipcRenderer.send('updateSysSetting', { theme: value })
}

const notifySwitchChange = (value) => {
  window.ipcRenderer.send('updateSysSetting', { notifySwitch: value })
}

onMounted(() => {
  window.ipcRenderer.send('getSysSetting')
  window.ipcRenderer.on('getSysSettingCallback', (e, sysSetting) => {
    if (!sysSetting) {
      return
    }
    try {
      const parsed = JSON.parse(sysSetting)
      notifySwitch.value = parsed.notifySwitch === undefined ? true : Boolean(parsed.notifySwitch)
      theme.value = parsed.theme === 'dark' ? 'dark' : 'light'
      applyTheme(theme.value)
    } catch (error) {
      notifySwitch.value = true
      theme.value = 'light'
      applyTheme('light')
    }
  })
  //保存失败回弹原值
  window.ipcRenderer.on('updateSysSettingCallback', (e, result) => {
    if (!result || result.status !== 1) {
      notifySwitch.value = !notifySwitch.value
      theme.value = themeBeforeSave.value
      applyTheme(theme.value)
      proxy.$message ? proxy.$message.error('设置保存失败') : null
    }
  })
})

onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('getSysSettingCallback')
  window.ipcRenderer.removeAllListeners('updateSysSettingCallback')
})

const changePart = (part) => {
  showType.value = part
}

const editBack = () => {
  showType.value = 0
  getUserInfo()
}

const logout = () => {
  proxy.Confirm({
    message: '确定要退出登录吗？',
    okfun: async () => {
      window.ipcRenderer.send('reLogin')
      let result = await proxy.Request({
        url: proxy.Api.logout
      })
      if (!result) {
        return
      }
    }
  })
}
</script>

<style lang="scss" scoped>
.show-info {
  .user-info {
    position: relative;
    .more-op {
      position: absolute;
      right: 0px;
      top: 20px;
      .icon-more {
        color: #9e9e9e;
        &:hover {
          background: #dddddd;
        }
      }
    }
  }
  .part-item {
    display: flex;
    border-bottom: 1px solid #eaeaea;
    padding: 20px 0px;
    .part-title {
      width: 60px;
      color: #9e9e9e;
    }
    .part-content {
      flex: 1;
      margin-left: 15px;
      color: #161616;
    }
  }

  .logout {
    text-align: center;
    margin-top: 20px;
  }
  .notify-row {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
    .tips {
      font-size: 12px;
      color: #888888;
    }
  }
}
</style>
