<template>
  <ContentPanel>
    <div class="group-info-item">
      <div class="group-title">群封面：</div>
      <div class="group-value">
        <Avatar :userId="groupInfo.groupId" :contactType="1"></Avatar>
      </div>
      <el-dropdown placement="bottom-end" trigger="click">
        <span class="el-dropdown-link">
          <div class="iconfont icon-more"></div>
        </span>
        <template #dropdown>
          <el-dropdown-menu v-if="groupInfo.groupOwnerId == userInfoStore.getInfo().userId">
            <el-dropdown-item @click="eidtGroupInfo">修改群信息</el-dropdown-item>
            <el-dropdown-item @click="showMemberList">群成员管理</el-dropdown-item>
            <el-dropdown-item @click="toggleEditNotice">{{ editingNotice ? '取消编辑' : '编辑公告' }}</el-dropdown-item>
            <el-dropdown-item @click="dissolutionGroup" divided>解散该群</el-dropdown-item>
          </el-dropdown-menu>
          <el-dropdown-menu v-else-if="isAdmin">
            <el-dropdown-item @click="showMemberList">群成员管理</el-dropdown-item>
            <el-dropdown-item @click="toggleEditNotice">{{ editingNotice ? '取消编辑' : '编辑公告' }}</el-dropdown-item>
            <el-dropdown-item @click="leaveGroup" divided>退出该群</el-dropdown-item>
          </el-dropdown-menu>
          <el-dropdown-menu v-else>
            <el-dropdown-item @click="leaveGroup">退出该群</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <div class="group-info-item">
      <div class="group-title">群ID：</div>
      <div class="group-value">{{ groupInfo.groupId }}</div>
    </div>
    <div class="group-info-item">
      <div class="group-title">群名称：</div>
      <div class="group-value">{{ groupInfo.groupName }}</div>
    </div>
    <div class="group-info-item">
      <div class="group-title">群主：</div>
      <div class="group-value">{{ groupInfo.groupOwnerNickName || groupInfo.groupOwnerId }}</div>
    </div>
    <div class="group-info-item">
      <div class="group-title">群成员：</div>
      <div class="group-value">{{ groupInfo.memberCount }}</div>
    </div>
    <div class="group-info-item">
      <div class="group-title">我的角色：</div>
      <div class="group-value">{{ roleText }}</div>
    </div>
    <div class="group-info-item">
      <div class="group-title">加入权限：</div>
      <div class="group-value">
        {{ groupInfo.joinType == 0 ? '直接加入' : '管理员同意后加入' }}
      </div>
    </div>
    <div class="group-info-item notice">
      <div class="group-title">公告：</div>
      <div class="group-value" v-if="!editingNotice">{{ groupInfo.groupNotice || '-' }}</div>
      <div class="group-value" v-else style="flex:1">
        <el-input
          v-model="noticeEditing"
          type="textarea"
          rows="4"
          maxlength="300"
          show-word-limit
          resize="none"
          placeholder="请输入群公告"
        ></el-input>
        <div style="margin-top:8px; text-align:right">
          <el-button type="primary" size="small" @click="submitNotice">保存</el-button>
        </div>
      </div>
    </div>
    <div class="group-info-item">
      <div class="group-title"></div>
      <div class="group-value">
        <el-button type="primary" @click="sendMessage">发送群消息</el-button>
      </div>
    </div>
  </ContentPanel>
  <GroupEditDialog ref="groupEditDialogRef" @reloadGroupInfo="getGroupInfo"></GroupEditDialog>

  <!-- 群成员管理 -->
  <Dialog
    :show="memberDialog.show"
    :title="`群成员管理(${memberDialog.total})`"
    :buttons="[]"
    width="560px"
    :showCancel="false"
    @close="memberDialog.show = false"
  >
    <div class="member-list">
      <div v-for="item in memberDialog.list" :key="item.userId" class="member-item">
        <Avatar :userId="item.userId" :contactType="0" :size="36" showName></Avatar>
        <span class="member-name">{{ item.contactName || item.userId }}</span>
        <el-tag size="small" :type="memberRoleType(item.role)">{{ memberRoleText(item.role) }}</el-tag>
        <span v-if="item.muteEndTime" class="muted-tag">禁言中</span>
        <span class="member-op" v-if="canMangeMember(item)">
          <el-button link size="small" @click="onSetAdmin(item)">{{ item.role == 1 ? '取消管理员' : '设为管理员' }}</el-button>
          <el-button link size="small" @click="onMuteMember(item)">{{ item.muteEndTime ? '解除禁言' : '禁言' }}</el-button>
          <el-button link size="small" v-if="groupInfo.groupOwnerId == userInfoStore.getInfo().userId" @click="onTransferOwner(item)">转让群主</el-button>
        </span>
      </div>
      <div v-if="memberDialog.list.length == 0" class="empty">暂无成员</div>
    </div>
  </Dialog>

  <!-- 禁言确认 -->
  <Dialog
    :show="muteDialog.show"
    :title="muteDialog.title"
    :buttons="muteDialog.buttons"
    width="380px"
    @close="muteDialog.show = false"
  >
    <el-form label-width="80px">
      <el-form-item label="禁言时长">
        <el-input-number v-model="muteDialog.minutes" :min="0" :max="43200" controls-position="right" style="width:100%"></el-input-number>
        <div class="hint">单位：分钟（0 表示解除禁言）</div>
      </el-form-item>
    </el-form>
  </Dialog>
</template>

<script setup>
import GroupEditDialog from './GroupEditDialog.vue'
import Dialog from '@/components/Dialog.vue'
import Avatar from '@/components/Avatar.vue'
import { ref, reactive, getCurrentInstance, watch, computed } from 'vue'
const { proxy } = getCurrentInstance()
import { useRoute, useRouter } from 'vue-router'
const route = useRoute()
const router = useRouter()
import { useUserInfoStore } from '@/stores/UserInfoStore'
const userInfoStore = useUserInfoStore()
import { useContactStateStore } from '@/stores/ContactStateStore'
const contactStateStore = useContactStateStore()

const groupInfo = ref({})
const groupId = ref()

// 我的群角色（来自后端 user_contact.role：0 群主 / 1 管理员 / 2 成员）
const myRole = ref(2)
const isAdmin = computed(() => myRole.value === 0 || myRole.value === 1)
const roleText = computed(() => {
  return { 0: '群主', 1: '管理员', 2: '成员' }[myRole.value] || '成员'
})

const memberRoleText = (role) => ({ 0: '群主', 1: '管理员', 2: '成员' }[role] ?? '成员')
const memberRoleType = (role) => ({ 0: 'danger', 1: 'warning', 2: '' }[role] ?? '')

const getGroupInfo = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getGroupInfo,
    params: {
      groupId: groupId.value
    }
  })
  if (!result) {
    return
  }
  groupInfo.value = result.data
}

// 加载“我的角色”：复用 getGroupInfo4Chat 接口（已返回 userId 与 nickname），再匹配当前位置
const loadMyRole = async () => {
  let result = await proxy.Request({
    url: proxy.Api.getGroupInfo4Chat,
    params: { groupId: groupId.value }
  })
  if (!result) return
  const contacts = result.data?.userContactList || []
  const me = userInfoStore.getInfo().userId
  const mine = contacts.find((c) => c.userId === me)
  if (mine && mine.role != null) {
    myRole.value = Number(mine.role)
  } else {
    // 不在成员列表中则默认最高（兼容群主不在 user_contact 的极端情况）
    myRole.value = groupInfo.value.groupOwnerId === me ? 0 : 2
  }
}

//修改群组信息
const groupEditDialogRef = ref()
const eidtGroupInfo = () => {
  groupEditDialogRef.value.show(groupInfo.value)
}

//解散群组
const dissolutionGroup = () => {
  proxy.Confirm({
    message: '确定要删除群组?删除后将无法恢复!',
    okfun: async () => {
      contactStateStore.setContactReload(null)
      let result = await proxy.Request({
        url: proxy.Api.dissolutionGroup,
        params: {
          groupId: groupInfo.value.groupId
        }
      })
      if (!result) {
        return
      }
      proxy.Message.success('解散成功')
      //刷新我的群组列表
      contactStateStore.setContactReload('DISSOLUTION_GROUP')
    }
  })
}
//退出群组
const leaveGroup = () => {
  proxy.Confirm({
    message: '确定要退出群组?',
    okfun: async () => {
      contactStateStore.setContactReload(null)
      let result = await proxy.Request({
        url: proxy.Api.leaveGroup,
        params: {
          groupId: groupInfo.value.groupId
        }
      })
      if (!result) {
        return
      }
      proxy.Message.success('退出成功')
      contactStateStore.setContactReload('LEAVE_GROUP')
    }
  })
}

//发送消息
const sendMessage = () => {
  router.push({
    path: '/chat',
    query: { chatId: groupInfo.value.groupId, timestamp: new Date().getTime() }
  })
}

/* === 群公告 === */
const editingNotice = ref(false)
const noticeEditing = ref('')
const toggleEditNotice = () => {
  if (!isAdmin.value) {
    proxy.Message.warning('无权编辑公告')
    return
  }
  if (!editingNotice.value) {
    noticeEditing.value = groupInfo.value.groupNotice || ''
  }
  editingNotice.value = !editingNotice.value
}
const submitNotice = async () => {
  if (!isAdmin.value) return
  let result = await proxy.Request({
    url: proxy.Api.editNotice,
    params: { groupId: groupInfo.value.groupId, notice: noticeEditing.value }
  })
  if (!result) return
  proxy.Message.success('公告已更新')
  editingNotice.value = false
  getGroupInfo()
}

/* === 群成员管理 === */
const memberDialog = reactive({ show: false, list: [], total: 0 })
const showMemberList = async () => {
  memberDialog.show = true
  let result = await proxy.Request({
    url: proxy.Api.memberList,
    params: { groupId: groupInfo.value.groupId }
  })
  if (!result) return
  memberDialog.list = result.data?.list || []
  memberDialog.total = result.data?.total || 0
}

// 群主可管理：管理员/成员；管理员可管理：成员（不可操作群主、其他管理员）
const canMangeMember = (item) => {
  const my = myRole.value
  if (item.userId == userInfoStore.getInfo().userId) return false
  if (item.role === 0) return false // 群主不可被管理
  if (my === 0) return true // 群主可管理除自己/群主以外的成员
  if (my === 1 && item.role === 2) return true // 管理员只可管理成员
  return false
}

/* === 设置/取消管理员 === */
const onSetAdmin = (item) => {
  if (!canMangeMember(item)) {
    proxy.Message.warning('无权操作')
    return
  }
  const targetRole = item.role === 1 ? 0 : 1 // 0 表示取消管理员，1 设为管理员
  proxy.Confirm({
    message: targetRole === 1 ? `确定将【${item.contactName || item.userId}】设为管理员？` : `确定取消【${item.contactName || item.userId}】的管理员身份？`,
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.setAdmin,
        params: { groupId: groupInfo.value.groupId, userId: item.userId, role: targetRole }
      })
      if (!result) return
      proxy.Message.success('操作成功')
      showMemberList()
      loadMyRole()
    }
  })
}

/* === 禁言 === */
const muteDialog = reactive({
  show: false,
  title: '禁言',
  minutes: 30,
  target: null,
  buttons: [
    { type: 'primary', text: '确定', click: () => submitMute() },
    { type: '', text: '取消', click: () => (muteDialog.show = false) }
  ]
})
const onMuteMember = (item) => {
  if (!canMangeMember(item)) {
    proxy.Message.warning('无权操作')
    return
  }
  muteDialog.target = item
  muteDialog.title = item.muteEndTime ? '解除禁言' : '禁言'
  muteDialog.minutes = item.muteEndTime ? 0 : 30
  muteDialog.show = true
}
const submitMute = async () => {
  const item = muteDialog.target
  if (!item) return
  let result = await proxy.Request({
    url: proxy.Api.muteMember,
    params: { groupId: groupInfo.value.groupId, userId: item.userId, minutes: muteDialog.minutes }
  })
  if (!result) return
  proxy.Message.success('操作成功')
  muteDialog.show = false
  showMemberList()
}

/* === 转让群主 === */
const onTransferOwner = (item) => {
  if (myRole.value !== 0) {
    proxy.Message.warning('仅群主可操作')
    return
  }
  proxy.Confirm({
    message: `确定将群主转让给【${item.contactName || item.userId}】？转让后您将变为普通成员。`,
    okfun: async () => {
      let result = await proxy.Request({
        url: proxy.Api.transferOwner,
        params: { groupId: groupInfo.value.groupId, newOwnerUserId: item.userId }
      })
      if (!result) return
      proxy.Message.success('转让成功')
      memberDialog.show = false
      loadMyRole()
      getGroupInfo()
    }
  })
}

watch(
  () => route.query.contactId,
  (newVal) => {
    if (newVal) {
      groupId.value = newVal
      getGroupInfo()
      loadMyRole()
    }
  },
  { immediate: true, deep: true }
)
</script>

<style lang="scss" scoped>
.group-info-item {
  display: flex;
  margin: 15px 0px;
  align-items: center;
  .group-title {
    width: 100px;
    text-align: right;
  }
  .group-value {
    flex: 1;
  }
}
.notice {
  align-items: flex-start;
}
.member-list {
  max-height: 380px;
  overflow-y: auto;
  .member-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 0;
    border-bottom: 1px solid #f0f0f0;
    .member-name {
      flex: 1;
      font-size: 14px;
    }
    .muted-tag {
      color: #f56c6c;
      font-size: 12px;
    }
    .member-op {
      display: flex;
      gap: 4px;
    }
  }
  .empty {
    text-align: center;
    color: #999;
    padding: 20px 0;
  }
}
.hint {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
</style>
