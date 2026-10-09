<template>
  <ContentPanel>
    <div class="user-info">
      <UserBaseInfo :userInfo="userInfo"></UserBaseInfo>
      <div class="more-op">
        <el-dropdown placement="bottom-end" trigger="click">
          <span class="el-dropdown-link">
            <div class="iconfont icon-more"></div>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="nudge">拍一拍</el-dropdown-item>
              <el-dropdown-item @click="addContact2BlackList">加入黑名单</el-dropdown-item>
              <el-dropdown-item @click="delContact">删除联系人</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
    <div class="part-item">
      <div class="part-title">个性签名</div>
      <div class="part-content">{{ userInfo.personalSignature || '-' }}</div>
    </div>
    <!-- 好友状态 -->
    <div class="part-item" v-if="userStatus">
      <div class="part-title">状态</div>
      <div class="part-content">
        <div class="status-content">
          <img v-if="userStatus.imageUrl" :src="userStatus.imageUrl" class="status-image" />
          <span>{{ userStatus.content }}</span>
        </div>
      </div>
    </div>
    <!-- 设置自己的状态 -->
    <div class="part-item">
      <div class="part-title">我的状态</div>
      <div class="part-content">
        <el-input
          v-model="myStatusContent"
          size="small"
          placeholder="设置状态，24小时后过期"
          maxlength="500"
          show-word-limit
          style="max-width: 260px"
          @change="saveMyStatus"
        ></el-input>
        <el-button size="small" @click="clearMyStatus" v-if="myStatusContent">清除</el-button>
      </div>
    </div>
    <!-- 好友备注：优先于昵称展示在会话与通讯录 -->
    <div class="part-item">
      <div class="part-title">备注</div>
      <div class="part-content">
        <el-input
          v-model="remark"
          size="small"
          placeholder="设置备注名"
          maxlength="20"
          show-word-limit
          style="max-width: 260px"
          @change="saveRemark"
        ></el-input>
      </div>
    </div>
    <!-- 好友分组 -->
    <div class="part-item">
      <div class="part-title">分组</div>
      <div class="part-content">
        <el-input
          v-model="groupName"
          size="small"
          placeholder="设置分组，如：同事"
          maxlength="20"
          show-word-limit
          style="max-width: 260px"
          @change="saveGroup"
        ></el-input>
      </div>
    </div>
    <div class="send-message" @click="sendMessage">
      <div class="iconfont icon-chat2"></div>
      <div class="text">发消息</div>
    </div>
  </ContentPanel>
</template>

<script setup>
import {getCurrentInstance, ref, watch, nextTick} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useContactStateStore} from '@/stores/ContactStateStore'
import {useUserInfoStore} from '@/stores/UserInfoStore'

const {proxy} = getCurrentInstance()
const route = useRoute()
const router = useRouter()

const contactStateStore = useContactStateStore()
const userInfoStore = useUserInfoStore()

const userInfo = ref({})
const remark = ref('')
const groupName = ref('')

const loadUserDetail = async (contactId) => {
  let result = await proxy.Request({
    url: proxy.Api.getContactUserInfo,
    params: {
      contactId: contactId
    }
  })
  if (!result) {
    return
  }
  userInfo.value = result.data
  remark.value = result.data.remark || ''
  groupName.value = result.data.groupName || ''
  // 加载好友状态
  loadUserStatus(contactId)
}

//设置好友备注名
const saveRemark = async () => {
  const result = await proxy.Request({
    url: proxy.Api.setContactRemark,
    params: {
      contactId: userInfo.value.userId,
      remark: remark.value
    },
    showLoading: false
  })
  if (!result) {
    return
  }
  proxy.Message.success('备注已保存')
  //通知通讯录刷新展示名（先置空再赋值，保证连续两次修改都能触发 watch）
  contactStateStore.setContactReload(null)
  nextTick(() => {
    contactStateStore.setContactReload('USER')
  })
}

//设置好友分组
const saveGroup = async () => {
  const result = await proxy.Request({
    url: proxy.Api.setContactGroup,
    params: {
      contactId: userInfo.value.userId,
      groupName: groupName.value
    },
    showLoading: false
  })
  if (!result) {
    return
  }
  proxy.Message.success('分组已保存')
  contactStateStore.setContactReload(null)
  nextTick(() => {
    contactStateStore.setContactReload('USER')
  })
}

//加入黑名单
const addContact2BlackList = () => {
  proxy.Confirm({
    message: '确定要将用户加入黑名单？',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.addContact2BlackList,
        params: {
          contactId: userInfo.value.userId
        }
      })
      if (!result) {
        return
      }
      //刷新我的群组列表
      delContactData(userInfo.value.userId)
      //此前黑名单只能加不能解，误点即永久无法退出；现已有管理页，给出去路提示
      proxy.Message.success('已加入黑名单，可在「设置 → 黑名单」中解除')
    }
  })
}
//删除用户
const delContact = () => {
  proxy.Confirm({
    message: '确定要删除联系人？',
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.delContact,
        params: {
          contactId: userInfo.value.userId
        }
      })
      if (!result) {
        return
      }
      delContactData(userInfo.value.userId)
    }
  })
}

const delContactData = (contactId) => {
  //刷新我的群组列表
  contactStateStore.setContactReload('REMOVE_USER')
  contactStateStore.delContact(contactId)
}

//发送消息
const sendMessage = () => {
  router.push({
    path: '/chat',
    query: {chatId: userInfo.value.userId, timestamp: new Date().getTime()}
  })
}

//拍一拍
const nudge = async () => {
  const result = await proxy.Request({
    url: proxy.Api.nudge,
    params: {
      contactId: userInfo.value.userId
    },
    showLoading: false
  })
  if (!result) {
    return
  }
  proxy.Message.success('已发送拍一拍')
}

// ===== 状态 =====
const userStatus = ref(null)
const myStatusContent = ref('')

// 加载好友状态
const loadUserStatus = async (userId) => {
  const result = await proxy.Request({
    url: proxy.Api.getStatus,
    params: {
      userId: userId
    },
    showLoading: false,
    showError: false
  })
  if (result && result.data) {
    userStatus.value = result.data
  } else {
    userStatus.value = null
  }
}

// 加载我的状态
const loadMyStatus = async () => {
  const result = await proxy.Request({
    url: proxy.Api.getStatus,
    params: {
      userId: userInfoStore.getInfo().userId
    },
    showLoading: false,
    showError: false
  })
  if (result && result.data) {
    myStatusContent.value = result.data.content || ''
  }
}

// 设置我的状态
const saveMyStatus = async () => {
  if (!myStatusContent.value) {
    return
  }
  const result = await proxy.Request({
    url: proxy.Api.setStatus,
    params: {
      content: myStatusContent.value
    },
    showLoading: false
  })
  if (!result) {
    return
  }
  proxy.Message.success('状态已设置')
}

// 清除我的状态
const clearMyStatus = async () => {
  const result = await proxy.Request({
    url: proxy.Api.clearStatus,
    showLoading: false
  })
  if (!result) {
    return
  }
  myStatusContent.value = ''
  proxy.Message.success('状态已清除')
}

// 更新会话列表（必须置于全部 const 声明之后：immediate 会同步调用 loadUserDetail/loadMyStatus，
// 而二者是 const —— 放在其声明前会触发 TDZ ReferenceError，进页即崩）
watch(
  () => route.query.contactId,
  (newVal, oldVal) => {
    if (newVal) {
      loadUserDetail(newVal)
      loadMyStatus()
    }
  },
  {immediate: true, deep: true}
)
</script>

<style lang="scss" scoped>
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

.send-message {
  width: 80px;
  margin: 0px auto;
  text-align: center;
  margin-top: 20px;
  color: #7d8cac;
  padding: 5px;

  .icon-chat2 {
    font-size: 23px;
  }

  .text {
    font-size: 12px;
    margin-top: 5px;
  }

  &:hover {
    background: #e9e9e9;
    cursor: pointer;
  }
}

.status-content {
  display: flex;
  align-items: center;
  gap: 8px;

  .status-image {
    width: 40px;
    height: 40px;
    border-radius: 4px;
    object-fit: cover;
  }
}
</style>
