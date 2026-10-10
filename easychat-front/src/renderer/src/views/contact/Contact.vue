<template>
  <Layout>
    <template #left-content>
      <div class="drag-panel drag"></div>
      <div class="top-search">
        <el-input placeholder="搜索" v-model="searchKey" size="small" @keyup="search">
          <template #suffix>
            <span class="iconfont icon-search"></span>
          </template>
        </el-input>
        <el-tooltip content="通过群二维码或邀请链接加入群聊" placement="right">
          <div class="join-group-btn" @click="joinDialogShow = true">
            <span class="iconfont icon-add-group"></span>
          </div>
        </el-tooltip>
      </div>
      <div class="contact-list" v-show="!searchKey">
        <template v-for="item in partList">
          <div class="part-title">{{ item.partName }}</div>
          <div class="part-list">
            <div
              :class="['part-item', sub.path == route.path ? 'active' : '']"
              v-for="sub in item.children"
              @click="partJump(sub)"
            >
              <div :class="['iconfont', sub.icon]" :style="{ background: sub.iconBgColor }"></div>
              <div class="text">{{ sub.name }}</div>
              <Badge :count="messageCountStore.getCount(sub.countKey)" :top="3" :left="45"></Badge>
            </div>
            <!--联系人信息 群组，联系人-->
            <template v-for="contact in item.contactData">
              <div
                :class="[
                  'part-item',
                  contact[item.contactId] == route.query.contactId ? 'active' : ''
                ]"
                @click="contactDetail(contact, item)"
              >
                <div class="avatar-wrapper">
                  <Avatar :userId="contact[item.contactId]" :width="35" :contactType="contact.contactType"></Avatar>
                  <!-- 在线状态指示点 -->
                  <span
                    v-if="contact.contactType === 0 && onlineStatusMap[contact[item.contactId]]"
                    :class="['status-dot', `status-${onlineStatusMap[contact[item.contactId]]}`]"
                  ></span>
                </div>
                <div class="text">
                  {{ contact[item.contactName] }}
                </div>
              </div>
            </template>
            <template v-if="item.contactData && item.contactData.length == 0">
              <div class="no-data">{{ item.emptyMsg }}</div>
            </template>
          </div>
        </template>
      </div>
      <div class="search-list" v-show="searchKey">
        <ContactSearchResult
          @click="searchClickHandler(item)"
          :data="item"
          v-for="item in searchList"
        >
        </ContactSearchResult>
      </div>
    </template>
    <template #right-content>
      <div class="title-panel drag">{{ rightTitle }}</div>
      <router-view v-slot="{ Component }">
        <component :is="Component" ref="componentRef" />
      </router-view>
    </template>
  </Layout>
  <!-- 加入群聊（群二维码 / 邀请链接），openspec/specs/group-join-approval -->
  <GroupJoinDialog v-model="joinDialogShow" @joined="onGroupJoined" />
</template>
<script setup>
import ContactSearchResult from './ContactSearchResult.vue'
import GroupJoinDialog from '@/components/GroupJoinDialog.vue'
import {getCurrentInstance, ref, watch, onMounted, onUnmounted} from 'vue'
import {useContactStateStore} from '@/stores/ContactStateStore'
import {useMessageCountStore} from '@/stores/MessageCountStore'
import {useRoute, useRouter} from 'vue-router'

const { proxy } = getCurrentInstance()
const contactStateStore = useContactStateStore()

//加入群聊对话框
const joinDialogShow = ref(false)

const messageCountStore = useMessageCountStore()

const router = useRouter()
const route = useRoute()

const partList = ref([
  {
    partName: '新朋友',
    children: [
      {
        name: '搜好友',
        icon: 'icon-search',
        iconBgColor: '#fa9d3b',
        path: '/contact/search'
      },
      {
        name: '新的朋友',
        icon: 'icon-plane',
        iconBgColor: '#08bf61',
        path: '/contact/contactNotice',
        showTitle: true,
        countKey: 'contactApplyCount'
      }
    ]
  },
  {
    partName: '我的群聊',
    children: [
      {
        name: '新建群聊',
        icon: 'icon-add-group',
        iconBgColor: '#1485ee',
        path: '/contact/createGroup'
      }
    ],
    contactId: 'groupId',
    contactName: 'groupName',
    showTitle: true,
    contactData: [],
    contactPath: '/contact/groupDetail'
  },
  {
    partName: '我加入的群聊',
    contactId: 'contactId',
    contactName: 'contactName',
    showTitle: true,
    contactData: [],
    contactPath: '/contact/groupDetail',
    emptyMsg: '暂未加入群聊'
  },
  {
    partName: '我的好友',
    children: [],
    contactId: 'contactId',
    contactName: 'contactName',
    contactData: [],
    contactPath: '/contact/userDetail',
    emptyMsg: '暂无好友'
  }
])

const rightTitle = ref()
const partJump = (data) => {
  if (data.showTitle) {
    rightTitle.value = data.name
  } else {
    rightTitle.value = null
  }
  if (data.countKey) {
    messageCountStore.setCount(data.countKey, 0, true)
    //更新数据库中的值
    window.ipcRenderer.send('updateContactNoReadCount')
  }
  router.push(data.path)
}

const loadContact = async (contactType) => {
  let result = await proxy.Request({
    url: proxy.Api.loadContact,
    showLoading: false,
    params: {
      contactType
    }
  })
  if (!result) {
    return
  }
  if (contactType === 'GROUP') {
    partList.value[2].contactData = result.data
  } else if (contactType === 'USER') {
    partList.value[3].contactData = result.data
  }
}

loadContact('USER')
loadContact('GROUP')

const loadMyGroup = async () => {
  let result = await proxy.Request({
    url: proxy.Api.loadMyGroup
  })
  if (!result) {
    return
  }
   let groupList = result.data
  groupList = groupList.map((item) => {
    item.contactType = 1
    return item
  })
  partList.value[1].contactData = groupList
}
loadMyGroup()

//加入群聊结果回调（GroupJoinDialog 抛出）
// joinType=0 已直接入群 → 刷新群列表；joinType=1 仅提交申请，群列表不变
const onGroupJoined = ({ approved }) => {
  if (approved) {
    loadMyGroup()
    loadContact('GROUP')
  }
}

//联系人详情
const contactDetail = (contact, part) => {
  if (part.showTitle) {
    rightTitle.value = contact[part.contactName]
  } else {
    rightTitle.value = null
  }
  router.push({
    path: part.contactPath,
    query: {
      contactId: contact[part.contactId]
    }
  })
}

// ===== 在线状态 =====
const onlineStatusMap = ref({})

// 监听在线状态变更帧
const onOnlineStatus = () => {
  window.ipcRenderer.on('onlineStatus', (e, message) => {
    if (!message || !message.contactId) return
    const status = message.extendData
    // 更新在线状态映射
    onlineStatusMap.value = {
      ...onlineStatusMap.value,
      [message.contactId]: status
    }
  })
  // 在线状态已隐藏帧（messageType=27）：对方关闭了「展示在线状态」
  // 必须抹除已显示的状态点，否则会一直「卡在在线」直到对方掉线
  // openspec/specs/privacy-settings
  window.ipcRenderer.on('onlineStatusHidden', (e, message) => {
    if (!message || !message.contactId) return
    if (!(message.contactId in onlineStatusMap.value)) return
    const next = { ...onlineStatusMap.value }
    delete next[message.contactId]
    onlineStatusMap.value = next
  })
}

onMounted(() => {
  onOnlineStatus()
})

onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('onlineStatus')
})

//搜索
const searchKey = ref()
const searchList = ref([])
const search = () => {
  if (!searchKey.value) {
    return
  }
  searchList.value = []
  var regex = new RegExp('(' + searchKey.value + ')', 'gi')

  let allContactList = []

  partList.value.forEach((item) => {
    if (item.contactData) {
      allContactList = allContactList.concat(item.contactData)
    }
  })
  allContactList.forEach((item) => {
    let contactName = item.groupId ? item.groupName : item.contactName
    if (contactName.includes(searchKey.value)) {
      contactName = contactName.replace(regex, "<span class='highlight'>$1</span>")
      let newData = {
        contactName: contactName,
        contactId: item.groupId || item.contactId
      }
      searchList.value.push(newData)
    }
  })
}

const searchClickHandler = (data) => {
  searchKey.value = undefined
  router.push({ path: '/chat', query: { chatId: data.contactId, timestamp: new Date().getTime() } })
}

watch(
  () => contactStateStore.contactReload,
  (newVal, oldVal) => {
    if (!newVal) {
      return
    }
    switch (newVal) {
      case 'MY':
        loadMyGroup()
        break
      case 'USER':
      case 'GROUP':
        loadContact(newVal)
        break
      case 'DISSOLUTION_GROUP': //解散
        loadMyGroup()
        router.push('/contact/blank')
        rightTitle.value = null
        break
      case 'LEAVE_GROUP':
        loadContact('GROUP')
        router.push('/contact/blank')
        rightTitle.value = null
        break
      case 'REMOVE_USER':
        loadContact('USER')
        router.push('/contact/blank')
        rightTitle.value = null
        break
      default:
        break
    }
    //避免连续删除，状态为改变，不重新加载列表
    contactStateStore.setContactReload("");
  },
  { immediate: true, deep: true }
)
</script>

<style lang="scss" scoped>
.drag-panel {
  height: 25px;
  background: var(--ec-surface-soft);
}
.top-search {
  padding: 0px 10px 9px 10px;
  background: var(--ec-surface-soft);
  display: flex;
  align-items: center;
  .iconfont {
    font-size: 12px;
  }
  //加入群聊入口（群二维码 / 邀请链接）
  .join-group-btn {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    margin-left: 6px;
    border-radius: 4px;
    cursor: pointer;
    color: var(--ec-text-secondary);
    transition: background 0.2s, color 0.2s;

    &:hover {
      background: var(--ec-surface-raised);
      color: var(--ec-brand);
    }
  }
}
.contact-list {
  border-top: 1px solid #ddd;
  height: calc(100vh - 62px);
  overflow: hidden;
  &:hover {
    overflow: auto;
  }

  .part-title {
    color: #515151;
    padding-left: 10px;
    margin-top: 10px;
  }
  .part-list {
    border-bottom: 1px solid #d6d6d6;
    .part-item {
      display: flex;
      align-items: center;
      padding: 10px 10px;
      position: relative;
      &:hover {
        cursor: pointer;
        background: #d6d6d7;
      }
      .iconfont {
        width: 35px;
        height: 35px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 20px;
        color: #fff;
      }
      .avatar-wrapper {
        position: relative;
        width: 35px;
        height: 35px;
        flex-shrink: 0;

        .status-dot {
          position: absolute;
          bottom: 0;
          right: 0;
          width: 10px;
          height: 10px;
          border-radius: 50%;
          border: 2px solid #fff;

          &.status-1 {
            background-color: #07c160;
          }
          &.status-2 {
            background-color: #ff9800;
          }
          &.status-3 {
            background-color: #999;
          }
        }
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
    .no-data {
      text-align: center;
      font-size: 12px;
      color: #9d9d9d;
      line-height: 30px;
    }
    .active {
      background: #c4c4c4;
      &:hover {
        background: #c4c4c4;
      }
    }
  }
}
.title-panel {
  width: 100%;
  height: 60px;
  display: flex;
  align-items: center;
  padding-left: 10px;
  font-size: 18px;
  color: #000000;
}
</style>
