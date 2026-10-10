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
      <div class="part-item">
        <div class="part-title">在线状态</div>
        <div class="part-content">
          <el-radio-group v-model="onlineStatus" @change="onlineStatusChange">
            <el-radio :label="1">在线</el-radio>
            <el-radio :label="2">忙碌</el-radio>
            <el-radio :label="3">离线</el-radio>
          </el-radio-group>
        </div>
      </div>
      <div class="part-item">
        <div class="part-title">拍一拍后缀</div>
        <div class="part-content">
          <el-input
            v-model="nudgeSuffix"
            size="small"
            placeholder="设置拍一拍后缀，如：我的肩膀说你好"
            maxlength="30"
            show-word-limit
            style="max-width: 260px"
            @change="saveNudgeSuffix"
          ></el-input>
          <div class="tips">发送拍一拍时将显示「xx 拍了拍{后缀}」</div>
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

// 注：「加我的方式」原在���页（只读不可改，2②-A 曾改为可编辑），
// 已于 2026-10-02 随隐私设置统一页迁移至 /setting/privacy（openspec/specs/privacy-settings ADR-003）。

// ===== 新消息提醒开关：任务栏闪烁（openspec/changes/2026-09-24-desktop-notification C2） =====
// 默认开；挂载时经主进程 getSysSetting 读 user_setting.sysSetting.notifySwitch（缺失视为开）
const notifySwitch = ref(true)

// ===== 外观主题：浅色/深色（本地 user_setting.sysSetting.theme，缺失视为浅色）=====
// ⚠ themeBeforeSave 只在**保存成功回调**里前移，不能在 @change 里捕获：
//   Element Plus 的 changeEvent 先同步发 update:modelValue、再 nextTick 发 change
//   （element-plus/es/components/radio/src/radio-group2.mjs:32-34），
//   故 @change 执行时 theme.value 已是新值——在 @change 里存"原值"存到的是新值，
//   保存失败的回弹会静默失效（2026-10-10 由挂载测试抓出）。
const theme = ref('light')
const themeBeforeSave = ref('light')
const themeChange = (value) => {
  theme.value = value
  applyTheme(value)
  window.ipcRenderer.send('updateSysSetting', { theme: value })
}

const notifySwitchChange = (value) => {
  window.ipcRenderer.send('updateSysSetting', { notifySwitch: value })
}

// ===== 在线状态设置 =====
const onlineStatus = ref(1)

const onlineStatusChange = (value) => {
  // 发送状态变更帧给服务端
  window.api.sendUserStatusChange(value)
}

// ===== 拍一拍后缀设置 =====
// 同 themeBeforeSave：el-input 的 @change 在 blur 触发，此时 v-model 已是新值，
// 「原值前移」只能放在保存成功回调里。
const nudgeSuffix = ref('')
const nudgeSuffixBeforeSave = ref('')

const saveNudgeSuffix = () => {
  window.ipcRenderer.send('updateSysSetting', { nudgeSuffix: nudgeSuffix.value })
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
      nudgeSuffix.value = parsed.nudgeSuffix || ''
      // 回读到的持久化值即「已确认值」基线（否则首次保存失败会回弹到错误的初始值）
      themeBeforeSave.value = theme.value
      nudgeSuffixBeforeSave.value = nudgeSuffix.value
    } catch (error) {
      notifySwitch.value = true
      theme.value = 'light'
      applyTheme('light')
      nudgeSuffix.value = ''
      themeBeforeSave.value = 'light'
      nudgeSuffixBeforeSave.value = ''
    }
  })
  //保存失败回弹原值
  window.ipcRenderer.on('updateSysSettingCallback', (e, result) => {
    if (!result || result.status !== 1) {
      notifySwitch.value = !notifySwitch.value
      theme.value = themeBeforeSave.value
      applyTheme(theme.value)
      nudgeSuffix.value = nudgeSuffixBeforeSave.value
      proxy.$message ? proxy.$message.error('设置保存失败') : null
    } else {
      // 保存成功 → 「已确认值」前移到当前值（下次失败才回弹到本次结果）
      themeBeforeSave.value = theme.value
      nudgeSuffixBeforeSave.value = nudgeSuffix.value
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
