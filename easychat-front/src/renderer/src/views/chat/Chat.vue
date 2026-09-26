<template>
  <Layout>
    <template #left-content>
      <div class="drag-panel drag"></div>
      <div class="top-search">
        <el-input
          placeholder="搜索（回车全局搜索）"
          v-model="searchKey"
          size="small"
          @keyup="search"
          @keyup.enter="openGlobalSearch"
        >
          <template #suffix>
            <span class="iconfont icon-search global-search-icon" @click="openGlobalSearch"></span>
          </template>
        </el-input>
      </div>
      <div class="chat-session-list" v-show="!searchKey">
        <template v-for="data in chatSessionList">
          <ChatSession
            @click="chatSessionClickHandler(data)"
            :data="data"
            :currentSession="data.contactId == currentChatSession.contactId"
            @contextmenu.stop="onContextMenu(data, $event)"
          ></ChatSession>
        </template>
      </div>
      <div class="search-list" v-show="searchKey">
        <SearchResult
          @click="searchClickHandler(item)"
          :data="item"
          v-for="item in searchList"
        ></SearchResult>
      </div>
    </template>
    <template #right-content>
      <div class="title-panel drag" v-if="Object.keys(currentChatSession).length > 0">
        <div class="title">
          <span>{{ currentChatSession.contactName }}</span>
          <span v-if="currentChatSession.contactType == 1"
          >({{ currentChatSession.memberCount }})</span
          >
        </div>
        <div class="title-actions">
          <span
            v-if="currentChatSession.contactType == 1"
            class="iconfont icon-folder no-drag"
            title="群文件"
            @click="showGroupFile"
          ></span>
          <span
            v-if="currentChatSession.contactType == 1"
            class="iconfont icon-more no-drag"
            @click="showGroupDetail"
          ></span>
        </div>
      </div>

      <div class="chat-panel" v-show="Object.keys(currentChatSession).length > 0">
        <div class="multi-select-bar" v-if="multiSelect.mode">
          <span class="multi-tip">已选择 {{ multiSelect.ids.length }} 条</span>
          <el-button size="small" type="primary" @click="forwardSelected">转发</el-button>
          <el-button size="small" type="danger" @click="deleteSelected">删除</el-button>
          <el-button size="small" @click="exitMultiSelect">退出多选</el-button>
        </div>
        <div class="message-panel" id="message-panel">
          <div
            class="message-item"
            v-for="(data, index) in messageList"
            :id="'message' + data.messageId"
          >
            <template
              v-if="
                index > 1 &&
                data.sendTime - messageList[index - 1].sendTime >= 300000 &&
                (data.messageType == 2 || data.messageType == 5)
              "
            >
              <ChatMessageTime :data="data"></ChatMessageTime>
            </template>
            <template
              v-if="
                data.messageType == 3 ||
                data.messageType == 1 ||
                data.messageType == 9 ||
                data.messageType == 8 ||
                data.messageType == 11 ||
                data.messageType == 12
              "
            >
              <ChatMessageSys :data="data"></ChatMessageSys>
            </template>
            <template
              v-if="data.messageType == 1 || data.messageType == 2 || data.messageType == 5 || data.messageType == 14"
            >
              <ChatMessage
                :data="data"
                :currentChatSession="currentChatSession"
                :multiSelectMode="multiSelect.mode"
                :selected="multiSelect.ids.includes(data.messageId)"
                @showMediaDetail="showMediaDetailHandler"
                @recallMessage="recallMessageHandler"
                @quoteMessage="quoteMessageHandler"
                @forwardMessage="forwardMessageHandler"
                @multiSelect="enterMultiSelect"
                @toggleSelect="toggleMessageSelect"
                @deleteMessage="deleteMessageHandler"
                @reportMessage="reportMessageHandler"
              ></ChatMessage>
            </template>
          </div>
        </div>
        <MessageSend
          ref="messageSendRef"
          :currentChatSession="currentChatSession"
          @sendMessage4Local="sendMessage4LocalHandler"
          @showSearch="showMessageSearch"
        >
        </MessageSend>
      </div>
      <div class="chat-blank" v-show="Object.keys(currentChatSession).length == 0">
        <Blank></Blank>
      </div>
    </template>
  </Layout>
  <ChatGroupDetail
    ref="chatGroupDetailRef"
    @delChatSessionCallback="delChatSession"
  ></ChatGroupDetail>
  <GroupFile ref="groupFileRef"></GroupFile>
  <MessageSearch ref="messageSearchRef" @jumpToMessage="jumpToMessage"></MessageSearch>
  <ForwardSelect ref="forwardSelectRef"></ForwardSelect>
  <ReportDialog v-model="reportVisible" type="message" :messageId="reportTargetId"></ReportDialog>
  <GlobalSearch
    ref="globalSearchRef"
    @openSession="openSessionFromSearch"
    @jumpMessage="jumpMessageFromSearch"
  ></GlobalSearch>
</template>
<script>
export default {
  name: 'chat'
}
</script>
<script setup>
import ContextMenu from '@imengyu/vue3-context-menu'
import '@imengyu/vue3-context-menu/lib/vue3-context-menu.css'

import SearchResult from './SearchResult.vue'
import MessageSearch from './MessageSearch.vue'
import ChatGroupDetail from './ChatGroupDetail.vue'
import GroupFile from './GroupFile.vue'
import {getFileType} from '@/utils/Constants.js'
import Blank from '@/components/Blank.vue'
import ChatSession from './ChatSession.vue'
import ChatMessage from './ChatMessage.vue'
import ChatMessageTime from './ChatMessageTime.vue'
import ChatMessageSys from './ChatMessageSys.vue'
import MessageSend from './MessageSend.vue'
import ForwardSelect from './ForwardSelect.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import GlobalSearch from './GlobalSearch.vue'
import {ref, reactive, getCurrentInstance, nextTick, onMounted, onActivated, watch, onUnmounted} from 'vue'
import {useRoute} from 'vue-router'

import {useUserInfoStore} from '@/stores/UserInfoStore'
import {useMessageCountStore} from '@/stores/MessageCountStore'
import {useContactStateStore} from '@/stores/ContactStateStore'
import {useSysSettingStore} from '@/stores/SysSettingStore'

const route = useRoute()

const {proxy} = getCurrentInstance()

const userInfoStore = useUserInfoStore()
//消息数
const messageCountStore = useMessageCountStore()
//系统设置（含新消息提醒开关 notifySwitch）
const sysSettingStore = useSysSettingStore()

//全局提醒开关：缺省视为开启
const notifySwitchOn = () => {
  const setting = sysSettingStore.getSetting() || {}
  return setting.notifySwitch === undefined ? true : Boolean(setting.notifySwitch)
}

const contactStateStore = useContactStateStore()

//会话列表
const chatSessionList = ref([])
//加载会话信息
const loadChatSession = () => {
  window.ipcRenderer.send('loadSessionData')
}

//会话排序
const sortChatSessionList = (dataList) => {
  dataList.sort((a, b) => {
    const topTypeResult = b['topType'] - a['topType']
    if (topTypeResult == 0) {
      return b['lastReceiveTime'] - a['lastReceiveTime']
    }
    return topTypeResult
  })
}

const delChatSessionList = (contactId) => {
  setTimeout(() => {
    chatSessionList.value = chatSessionList.value.filter((item) => {
      return item.contactId !== contactId
    })
  }, 100)
}

//当前会话
const currentChatSession = ref({})
const messageCountInfo = {
  totalPage: 0,
  pageNo: 0,
  maxMessageId: null,
  noData: false
}
// 云端漫游：本地历史翻完后继续从服务端拉，避免新设备只能看到 3 天内的消息
const remoteHistory = reactive({
  noData: false,
  lastMessageId: null
})

//消息列表
const messageList = ref([])
//是否自动滚动到底部
let distanceBottom = 0
const messageSendRef = ref()
//是否正在加载消息
const loadingMessage = ref(false)

const chatSessionClickHandler = (item) => {
  distanceBottom = 0
  currentChatSession.value = Object.assign({}, item)
  messageCountStore.setCount('chatCount', -item.noReadCount, false)
  item.noReadCount = 0
  //加载本地数据库中的消息
  messageList.value = []
  loadingMessage.value = false
  messageCountInfo.pageNo = 0
  messageCountInfo.totalPage = 1
  messageCountInfo.maxMessageId = null
  messageCountInfo.noData = false
  remoteHistory.noData = false
  remoteHistory.lastMessageId = null
  loadChatMessage()
  //设置session
  setSessionSelect({contactId: item.contactId, sessionId: item.sessionId})
  //清空输入框中的消息
  messageSendRef.value.cleanMessage()
}

const setSessionSelect = ({contactId, sessionId}) => {
  window.ipcRenderer.send('setSessionSelect', {
    contactId,
    sessionId
  })
}

const loadChatMessage = () => {
  if (loadingMessage.value) {
    return
  }
  // 本地历史已翻完 → 转云端漫游，从服务端继续往更早拉
  if (messageCountInfo.noData) {
    loadRemoteHistoryMessage()
    return
  }
  messageCountInfo.pageNo++
  //正在加载
  loadingMessage.value = true
  window.ipcRenderer.send('loadChatMessage', {
    sessionId: currentChatSession.value.sessionId,
    pageNo: messageCountInfo.pageNo,
    maxMessageId: messageCountInfo.maxMessageId
  })
}

/**
 * 云端消息漫游：本地 SQLite 没有更早消息时，按会话从服务端分页拉取历史。
 * 解决「新设备只拉 3 天、历史翻页只读本地」的问题。
 */
const loadRemoteHistoryMessage = async () => {
  if (loadingMessage.value || remoteHistory.noData || !currentChatSession.value.sessionId) {
    return
  }
  loadingMessage.value = true
  try {
    const result = await proxy.Request({
      url: proxy.Api.loadHistoryMessage,
      showLoading: false,
      showError: false,
      params: {
        sessionId: currentChatSession.value.sessionId,
        lastMessageId: remoteHistory.lastMessageId,
        pageSize: 20
      }
    })
    const pageData = result && result.data
    const list = pageData && pageData.list ? pageData.list : []
    if (list.length == 0) {
      remoteHistory.noData = true
      return
    }
    // 服务端按 message_id desc 返回，翻转为升序后拼到列表头部
    list.sort((a, b) => a.messageId - b.messageId)
    remoteHistory.lastMessageId = list[0].messageId
    // 本地已有则跳过，避免与本地数据重复
    const existsIds = new Set(messageList.value.map((item) => item.messageId))
    const appendList = list.filter((item) => !existsIds.has(item.messageId))
    if (appendList.length == 0) {
      return
    }
    const scrollAnchorId = messageList.value.length > 0 ? messageList.value[0].messageId : null
    messageList.value = appendList.concat(messageList.value)
    // 同时回写本地 SQLite，下次进入直接从本地读
    appendList.forEach((item) => {
      window.ipcRenderer.send('saveOrUpdateMessage', {message: item})
    })
    nextTick(() => {
      if (scrollAnchorId != null) {
        const anchor = document.querySelector('#message' + scrollAnchorId)
        if (anchor) {
          anchor.scrollIntoView()
        }
      }
    })
  } catch (e) {
    console.warn('云端漫游拉取失败', e)
  } finally {
    loadingMessage.value = false
  }
}

const onReciveMessage = () => {
  //监听聊天消息
  window.ipcRenderer.on('reciveMessage', (e, message) => {
    console.log('收到消息', message)
    if (message.messageType == 1) {
      contactStateStore.setContactReload(message.contactType == 0 ? 'USER' : 'GROUP')
      loadChatSession()
    }
    if (message.messageType == 0) {
      if (chatSessionList.value.length == 0) {
        loadChatSession()
      }
      return
    }
    //查询好友申请信息
    if (message.messageType == 4) {
      loadContactApply()
      return
    }
    //强制下线
    if (message.messageType == 7) {
      proxy.Confirm({
        message: `你已经被管理员强制下线`,
        okfun: () => {
          setTimeout(() => {
            window.ipcRenderer.send('reLogin')
          }, 200)
        },
        showCancelBtn: false
      })
      return
    }
    //更新群昵称
    if (message.messageType == 10) {
      let curSession = chatSessionList.value.find((item) => {
        return item.contactId == message.contactId
      })
      curSession.contactName = message.extendData
      return
    }
    //如果是 文件上传完成的消息，更新文件即可
    if (message.messageType == 6) {
      const localMessage = messageList.value.find((item) => {
        if (item.messageId == message.messageId) {
          return item
        }
      })
      if (localMessage != null) {
        localMessage.status = 1
      }
      return
    }
    //如果是撤回消息，更新消息显示
    if (message.messageType == 14) {
      const localMessage = messageList.value.find((item) => {
        if (item.messageId == message.messageId) {
          return item
        }
      })
      if (localMessage != null) {
        localMessage.messageType = 14
        localMessage.messageContent = message.messageContent || '该消息已撤回'
        localMessage.status = 1  // 确保消息状态为已发送，避免显示加载中
        // 如果是群聊，更新撤回者信息
        if (message.contactType == 1) {
          localMessage.sendUserNickName = message.sendUserNickName
        }
      }
      // 更新本地数据库
      window.ipcRenderer.send('updateLocalMessage', {
        messageId: message.messageId,
        messageType: 14,
        messageContent: message.messageContent || '该消息已撤回'
      })
      return
    }
    //添加好友、创建群、加入群
    //刷新我的联系人列表，如果当前页面正在联系人列表页面，加入群组申请通过后需要刷新列表
    if (message.messageType == 9 && message.extendData.userId == userInfoStore.getInfo().userId) {
      contactStateStore.setContactReload('GROUP')
    }

    //查询当前消息对应的会话
    let curSession = chatSessionList.value.find((item) => {
      return item.sessionId == message.sessionId
    })
    if (curSession == null) {
      chatSessionList.value.push(message.extendData)
      curSession = message.extendData
    } else {
      Object.assign(curSession, message.extendData)
    }
    //会话列表重新排序
    sortChatSessionList(chatSessionList.value)
    //如果没有选中当前消息的会话，就显示消息数量 不增加消息
    if (message.sessionId !== currentChatSession.value.sessionId) {
      messageCountStore.setCount('chatCount', 1, false)
    } else {
      //选中了当前消息的会话更新会话信息，添加消息
      Object.assign(currentChatSession.value, message.extendData)
      messageList.value.push(message)
      gotoBottom()
    }
  })
}
//监听加载会话列表
const onLoadSessionData = () => {
  window.ipcRenderer.on('loadSessionDataCallback', (e, data) => {
    //设置未读数
    let noReadCount = 0
    data.forEach((element) => {
      noReadCount = noReadCount + element.noReadCount
    })
    messageCountStore.setCount('chatCount', noReadCount, true)
    //排序
    sortChatSessionList(data)
    chatSessionList.value = data
  })
}

//获取会话信息
const onLoadChatMessage = () => {
  //监听查询本地数据库消息列表
  window.ipcRenderer.on('loadChatMessage', (e, {dataList, pageTotal, pageNo}) => {
    if (pageNo == pageTotal) {
      messageCountInfo.noData = true
    }

    loadingMessage.value = false
    dataList.sort((a, b) => {
      return a.messageId - b.messageId
    })

    const lastMessage = messageList.value[0]

    messageList.value = dataList.concat(messageList.value)
    messageCountInfo.pageTotal = pageTotal
    messageCountInfo.pageNo = pageNo

    if (pageNo == 1) {
      messageCountInfo.maxMessageId =
        dataList.length > 0 ? dataList[dataList.length - 1].messageId : null
    }
    if (pageNo == 1) {
      gotoBottom()
    } else {
      nextTick(() => {
        document.querySelector('#message' + lastMessage.messageId).scrollIntoView()
      })
    }
  })
}

//本地消息写入完成回调
const onAddLocalMessage = () => {
  window.ipcRenderer.on('addLocalCallback', (e, {messageId, status}) => {
    const findMessage = messageList.value.find((item) => {
      if (item.messageId == messageId) {
        return item
      }
    })
    //本地有消息，说明是自己发送的，更新消息
    if (findMessage != null) {
      findMessage.status = status
    }
  })
}

// 监听跨端会话同步（SYNC_SESSION），更新本地会话列表
const onSyncSession = () => {
  window.ipcRenderer.on('syncSession', (e, sessionData) => {
    if (!sessionData || !sessionData.sessionId) return
    const session = chatSessionList.value.find((s) => s.sessionId === sessionData.sessionId)
    if (session) {
      if (sessionData.lastMessage !== undefined) {
        session.lastMessage = sessionData.lastMessage
      }
      if (sessionData.lastReceiveTime !== undefined) {
        session.lastReceiveTime = sessionData.lastReceiveTime
      }
      // 重新排序会话列表
      sortChatSessionList(chatSessionList.value)
    }
  })
}

// 监听会话属性跨端同步（置顶 / 免打扰 / 草稿）
const onSyncSessionUser = () => {
  window.ipcRenderer.on('syncSessionUser', (e, syncData) => {
    if (!syncData || !syncData.contactId) return
    const session = chatSessionList.value.find((s) => s.contactId === syncData.contactId)
    if (!session) return
    if (syncData.action === 'top') {
      session.topType = syncData.value
      sortChatSessionList(chatSessionList.value)
    } else if (syncData.action === 'noDisturb') {
      session.noDisturb = syncData.value
    } else if (syncData.action === 'draft') {
      session.draft = syncData.value
    }
  })
}

/* ==================== 引用回复 / 转发 / 多选 ==================== */

//多选模式：勾选后可批量转发或批量删除
const multiSelect = reactive({
  mode: false,
  ids: []
})

//引用回复：把待引用消息交给 MessageSend 展示在输入框上方
const quoteMessage = ref(null)
const quoteMessageHandler = (message) => {
  quoteMessage.value = {
    messageId: message.messageId,
    quoteNickName: message.sendUserNickName || '',
    quoteContent:
      message.messageType == 5 ? (message.fileName || '[文件]') : (message.messageContent || '')
  }
  messageSendRef.value &&
    messageSendRef.value.setQuote &&
    messageSendRef.value.setQuote(quoteMessage.value)
}

//清除引用
const clearQuote = () => {
  quoteMessage.value = null
}

//转发：弹出会话选择，逐条转发到选中的会话
const forwardSelectRef = ref()
const forwardMessageHandler = (message) => {
  if (forwardSelectRef.value) {
    forwardSelectRef.value.show(async (selectedList) => {
      await doForward([message], selectedList)
    })
  }
}

const doForward = async (messages, contactList) => {
  for (const contact of contactList) {
    for (const message of messages) {
      const extraData = JSON.stringify({
        forwardFrom: message.sendUserId,
        forwardNickName: message.sendUserNickName || ''
      })
      const content =
        message.messageType == 5 ? (message.fileName || '[文件]') : (message.messageContent || '')
      try {
        await proxy.Request({
          url: proxy.Api.sendMessage,
          showLoading: false,
          showError: false,
          params: {
            contactId: contact.contactId,
            messageContent: content,
            messageType: message.messageType,
            extraData
          }
        })
      } catch (e) {
        console.warn('转发失败', e)
      }
    }
  }
}

//进入多选模式
const enterMultiSelect = (message) => {
  multiSelect.mode = true
  multiSelect.ids = [message.messageId]
}

//勾选 / 取消勾选
const toggleMessageSelect = (messageId) => {
  const index = multiSelect.ids.indexOf(messageId)
  if (index == -1) {
    multiSelect.ids.push(messageId)
  } else {
    multiSelect.ids.splice(index, 1)
  }
}

//退出多选模式
const exitMultiSelect = () => {
  multiSelect.mode = false
  multiSelect.ids = []
}

//多选：批量转发
const forwardSelected = () => {
  const selectedMessages = messageList.value.filter((item) => multiSelect.ids.includes(item.messageId))
  if (selectedMessages.length == 0) {
    return
  }
  forwardSelectRef.value &&
    forwardSelectRef.value.show(async (selectedList) => {
      await doForward(selectedMessages, selectedList)
      exitMultiSelect()
    })
}

//多选：批量删除（仅本地删除，服务端保留）
const deleteSelected = () => {
  if (multiSelect.ids.length == 0) {
    return
  }
  proxy.Confirm({
    message: `确定要删除选中的 ${multiSelect.ids.length} 条消息吗？`,
    okfun: () => {
      messageList.value = messageList.value.filter(
        (item) => !multiSelect.ids.includes(item.messageId)
      )
      multiSelect.ids.forEach((messageId) => {
        window.ipcRenderer.send('delLocalMessage', {messageId})
      })
      exitMultiSelect()
    }
  })
}

//单条删除（仅本地）
const deleteMessageHandler = (message) => {
  messageList.value = messageList.value.filter((item) => item.messageId !== message.messageId)
  window.ipcRenderer.send('delLocalMessage', {messageId: message.messageId})
}

// 举报聊天消息：打开举报弹窗
const reportVisible = ref(false)
const reportTargetId = ref(null)
const reportMessageHandler = (messageId) => {
  reportTargetId.value = messageId
  reportVisible.value = true
}

/**
 * 新消息提示音：Web Audio 合成短促双音，无需引入音频资源文件。
 * 免打扰会话与全局提醒开关关闭时不发声。
 */
let audioContext = null
const playNotifySound = () => {
  try {
    if (!notifySwitchOn()) return
    if (!audioContext) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext
      if (!AudioCtx) return
      audioContext = new AudioCtx()
    }
    if (audioContext.state === 'suspended') {
      audioContext.resume()
    }
    const now = audioContext.currentTime
    ;[880, 1175].forEach((freq, index) => {
      const oscillator = audioContext.createOscillator()
      const gain = audioContext.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = freq
      const start = now + index * 0.12
      gain.gain.setValueAtTime(0.0001, start)
      gain.gain.exponentialRampToValueAtTime(0.25, start + 0.02)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.16)
      oscillator.connect(gain)
      gain.connect(audioContext.destination)
      oscillator.start(start)
      oscillator.stop(start + 0.2)
    })
  } catch (e) {
    console.warn('播放提示音失败', e)
  }
}

const onPlayNotifySound = () => {
  window.ipcRenderer.on('playNotifySound', (e, {messageType, contactId}) => {
    // 免打扰会话不响铃
    if (contactId) {
      const session = chatSessionList.value.find((item) => item.contactId === contactId)
      if (session && session.noDisturb == 1) {
        return
      }
    }
    playNotifySound()
  })
}

/**
 * 点击系统横幅定位：切到对应会话（toast 点击 → 主进程 → 渲染层）
 */
const onLocateSession = () => {
  window.ipcRenderer.on('locateSession', (e, {contactId}) => {
    if (!contactId) return
    const session = chatSessionList.value.find((s) => s.contactId === contactId)
    if (session) {
      chatSessionClickHandler(session)
      return
    }
    // 本地会话列表尚未加载时兜底：重载后再次定位
    loadChatSession()
    setTimeout(() => {
      const retry = chatSessionList.value.find((s) => s.contactId === contactId)
      if (retry) {
        chatSessionClickHandler(retry)
      }
    }, 500)
  })
}

//发送本地消息
const sendMessage4LocalHandler = (messageObj) => {
  messageList.value.push(messageObj)

  const chatSession = chatSessionList.value.find((item) => {
    return item.sessionId == messageObj.sessionId
  })
  if (chatSession) {
    chatSession.lastMessage = messageObj.lastMessage
    chatSession.lastReceiveTime = messageObj.sendTime
  }
  sortChatSessionList(chatSessionList.value)
  gotoBottom()
}

const gotoBottom = () => {
  nextTick(() => {
    //距离底部距离超过200就不自动滚动到底部
    if (distanceBottom > 200) {
      return
    }
    const feedItems = document.querySelectorAll('.message-item')
    if (feedItems.length > 0) {
      setTimeout(() => {
        feedItems[feedItems.length - 1].scrollIntoView()
      }, 100)
    }
  })
}

//获取好友申请信息
const loadContactApply = () => {
  window.ipcRenderer.send('loadContactApply')
}

const onLoadContactApply = () => {
  window.ipcRenderer.on('loadContactApplyCallback', (e, contactNoRead) => {
    messageCountStore.setCount('contactApplyCount', contactNoRead, true)
  })
}

//重新加载会话
const onReloadChatSession = () => {
  window.ipcRenderer.on('reloadChatSessionCallback', (e, {contactId, chatSessionDataList}) => {
    //重新排序
    sortChatSessionList(chatSessionDataList)
    //设置会话
    chatSessionList.value = chatSessionDataList
    //选中会话发送消息
    sendMessage(contactId)
  })
}

//监听主进程消息
onMounted(() => {
  onReciveMessage()

  onLoadChatMessage()

  loadChatSession()

  onLoadSessionData()

  loadContactApply()

  onLoadContactApply()

  onAddLocalMessage()

  // 监听跨端会话同步
  onSyncSession()

  // 监听会话属性跨端同步（置顶/免打扰/草稿）
  onSyncSessionUser()

  // 监听系统横幅点击定位
  onLocateSession()

  // 监听新消息提示音
  onPlayNotifySound()

  // 监听聊天记录导出结果
  onExportChatRecordCallback()

  //重新加载已删除的会话
  onReloadChatSession()

  nextTick(() => {
    const messagePanel = document.querySelector('#message-panel')
    messagePanel.addEventListener('scroll', (e) => {
      const scrollTop = e.target.scrollTop
      //计算距离底部的距离
      distanceBottom = e.target.scrollHeight - e.target.clientHeight - scrollTop
      //滚动到顶部，开始分页查询
      if (scrollTop == 0 && messageList.value.length > 0) {
        loadChatMessage()
      }
    })
  })
  //设置选中session为空
  setSessionSelect({})
})

onActivated(() => {
  loadChatSession()
})

onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('loadSessionDataCallback')
  window.ipcRenderer.removeAllListeners('reciveMessage')
  window.ipcRenderer.removeAllListeners('loadChatMessage')
  window.ipcRenderer.removeAllListeners('loadContactApply')
  window.ipcRenderer.removeAllListeners('addLocalCallback')
  window.ipcRenderer.removeAllListeners('syncSession')
  window.ipcRenderer.removeAllListeners('reloadChatSessionCallback')
  window.ipcRenderer.removeAllListeners('exportChatRecordCallback')
})

/**
 * 置顶 / 取消置顶：服务端是真源，本地 SQLite 只是缓存。
 * 先更新本地以便即时反馈，再调服务端持久化并广播到其他设备。
 */
const setTop = async (data) => {
  const topType = data.topType == 0 ? 1 : 0
  data.topType = topType
  sortChatSessionList(chatSessionList.value)
  window.ipcRenderer.send('topChatSession', {contactId: data.contactId, topType})
  try {
    await proxy.Request({
      url: proxy.Api.setSessionTop,
      showLoading: false,
      showError: false,
      params: {contactId: data.contactId, topType}
    })
  } catch (e) {
    console.warn('置顶同步服务端失败', e)
  }
}

/**
 * 会话免打扰：开启后该会话的新消息不闪烁、不响铃
 */
const setNoDisturb = async (data) => {
  const noDisturb = data.noDisturb == 1 ? 0 : 1
  data.noDisturb = noDisturb
  window.ipcRenderer.send('setSessionNoDisturb', {contactId: data.contactId, noDisturb})
  try {
    await proxy.Request({
      url: proxy.Api.setSessionNoDisturb,
      showLoading: false,
      showError: false,
      params: {contactId: data.contactId, noDisturb}
    })
  } catch (e) {
    console.warn('免打扰同步服务端失败', e)
  }
}

//删除会话
const delChatSession = (contactId) => {
  delChatSessionList(contactId)
  setSessionSelect({})
  currentChatSession.value = {}
  window.ipcRenderer.send('delChatSession', contactId)
}

//右键
const onContextMenu = (data, e) => {
  ContextMenu.showContextMenu({
    x: e.x,
    y: e.y,
    items: [
      {
        label: data.topType == 0 ? '置顶' : '取消置顶',
        onClick: () => {
          setTop(data)
        }
      },
      {
        label: data.noDisturb == 1 ? '取消免打扰' : '消息免打扰',
        onClick: () => {
          setNoDisturb(data)
        }
      },
      {
        label: '导出聊天记录（TXT）',
        onClick: () => {
          doExportChat(data, 'txt')
        }
      },
      {
        label: '导出聊天记录（CSV）',
        onClick: () => {
          doExportChat(data, 'csv')
        }
      },
      {
        label: '删除聊天',
        onClick: () => {
          proxy.Confirm({
            message: `确定要删除聊天【${data.contactName}】吗？`,
            okfun: () => {
              delChatSession(data.contactId, true)
            }
          })
        }
      }
    ]
  })
}

// ===== 聊天记录导出：下发主进程落盘，回调提示结果 =====
const doExportChat = (session, format) => {
  if (!session || !session.sessionId) {
    proxy.Message.warning('请先选择要导出的会话')
    return
  }
  window.ipcRenderer.send('exportChatRecord', {
    sessionId: session.sessionId,
    contactName: session.contactName,
    format
  })
}

const onExportChatRecordCallback = () => {
  window.ipcRenderer.on('exportChatRecordCallback', (e, result) => {
    if (!result) {
      return
    }
    // 用户主动取消保存：静默返回，不打扰
    if (result.canceled) {
      return
    }
    if (result.success) {
      proxy.Message.success(`已导出 ${result.count} 条消息：${result.path}`)
      return
    }
    proxy.Message.warning(result.error || '导出失败')
  })
}
//查看媒体详情
const showMediaDetailHandler = (messageId) => {
  let showFileList = messageList.value.filter((item) => {
    return item.messageType == 5
  })
  showFileList = showFileList.map((item) => {
    return {
      partType: 'chat',
      fileId: item.messageId,
      fileType: item.fileType,
      fileName: item.fileName,
      fileSize: item.fileSize,
      forceGet: false
    }
  })
  window.ipcRenderer.send('newWindow', {
    windowId: 'media',
    title: '图片查看',
    path: `/showMedia`,
    data: {
      currentFileId: messageId,
      fileList: showFileList
    }
  })
}

//群详情
const chatGroupDetailRef = ref()
const showGroupDetail = () => {
  chatGroupDetailRef.value.show(currentChatSession.value.contactId)
}

//群文件
const groupFileRef = ref()
const showGroupFile = () => {
  groupFileRef.value.show(currentChatSession.value.contactId)
}

//消息搜索
const messageSearchRef = ref()
const showMessageSearch = () => {
  if (currentChatSession.value.sessionId) {
    messageSearchRef.value.show(currentChatSession.value.sessionId)
  }
}

// ===== 全局搜索：跨会话消息 + 联系人 + 群组 =====
const globalSearchRef = ref()
const openGlobalSearch = () => {
  globalSearchRef.value.show(searchKey.value)
}

// 搜索结果点联系人/群：切到对应会话，本地无会话时兜底重载
const openSessionFromSearch = ({contactId, contactType}) => {
  if (!contactId) {
    return
  }
  const session = chatSessionList.value.find((item) => item.contactId === contactId)
  if (session) {
    chatSessionClickHandler(session)
    return
  }
  window.ipcRenderer.send('reloadChatSession', {contactId, contactType})
  setTimeout(() => {
    const retry = chatSessionList.value.find((item) => item.contactId === contactId)
    if (retry) {
      chatSessionClickHandler(retry)
    }
  }, 500)
}

// 搜索结果点聊天记录：先切会话，等消息列表就绪后再定位到该条
const jumpMessageFromSearch = ({contactId, contactType, messageId}) => {
  if (!contactId || !messageId) {
    return
  }
  const currentId = currentChatSession.value.contactId
  if (currentId === contactId) {
    jumpToMessage(messageId)
    return
  }
  openSessionFromSearch({contactId, contactType})
  setTimeout(() => {
    jumpToMessage(messageId)
  }, 600)
}

/**
 * 跳转到指定消息并高亮。
 * 命中老消息（本地未渲染）时不再静默失败：先按 messageId 从服务端定位一页补齐，再滚动。
 */
const jumpToMessage = async (messageId) => {
  const scrollToTarget = () => {
    nextTick(() => {
      const messageElement = document.getElementById('message' + messageId)
      if (messageElement) {
        messageElement.scrollIntoView({behavior: 'smooth', block: 'center'})
        messageElement.classList.add('highlight-message')
        setTimeout(() => {
          messageElement.classList.remove('highlight-message')
        }, 2000)
      }
    })
  }
  const exists = messageList.value.some((item) => item.messageId == messageId)
  if (exists) {
    scrollToTarget()
    return
  }
  if (!currentChatSession.value.sessionId) {
    return
  }
  try {
    const result = await proxy.Request({
      url: proxy.Api.locateMessage,
      showLoading: false,
      showError: false,
      params: {messageId, pageSize: 20}
    })
    const list = result && result.data && result.data.list ? result.data.list : []
    if (list.length == 0) {
      return
    }
    list.sort((a, b) => a.messageId - b.messageId)
    const existsIds = new Set(messageList.value.map((item) => item.messageId))
    const appendList = list.filter((item) => !existsIds.has(item.messageId))
    if (appendList.length > 0) {
      messageList.value = messageList.value.concat(appendList)
      appendList.forEach((item) => {
        window.ipcRenderer.send('saveOrUpdateMessage', {message: item})
      })
    }
    scrollToTarget()
  } catch (e) {
    console.warn('定位消息失败', e)
  }
}

//发送消息
const sendMessage = (contactId) => {
  let curSession = chatSessionList.value.find((item) => {
    return item.contactId == contactId
  })
  //没有找到会话记录可能是已经删除
  if (!curSession) {
    //修改会话状态
    window.ipcRenderer.send('reloadChatSession', {contactId})
    return
  } else {
    chatSessionClickHandler(curSession)
  }
}

//发送消息监听 发送消息给联系人
watch(
  () => route.query.timestamp,
  (newVal, oldVal) => {
    if (newVal && route.query.chatId) {
      sendMessage(route.query.chatId)
    }
  },
  {immediate: true, deep: true}
)

//搜索
const searchKey = ref()
const searchList = ref([])
const search = () => {
  if (!searchKey.value) {
    return
  }
  searchList.value = []
  var regex = new RegExp('(' + searchKey.value + ')', 'gi')
  chatSessionList.value.forEach((item) => {
    if (item.contactName.includes(searchKey.value) || item.lastMessage.includes(searchKey.value)) {
      let newData = Object.assign({}, item)
      newData.searchContactName = newData.contactName.replace(
        regex,
        "<span class='highlight'>$1</span>"
      )
      newData.searchLastMessage = newData.lastMessage.replace(
        regex,
        "<span class='highlight'>$1</span>"
      )
      searchList.value.push(newData)
    }
  })
}

const searchClickHandler = (data) => {
  searchKey.value = undefined
  chatSessionClickHandler(data)
}

// 撤回消息
const recallMessageHandler = async (messageId) => {
  const result = await proxy.Request({
    url: proxy.Api.recallMessage,
    showLoading: false,
    params: {
      messageId: messageId
    },
    showError: true
  })
  if (result) {
    // 更新本地消息列表
    const message = messageList.value.find((item) => item.messageId == messageId)
    if (message) {
      message.messageType = 14
      message.messageContent = '该消息已撤回'
    }
    // 更新本地数据库
    window.ipcRenderer.send('updateLocalMessage', {
      messageId: messageId,
      messageType: 14,
      messageContent: '该消息已撤回'
    })
  }
}
</script>

<style lang="scss" scoped>
.drag-panel {
  height: 25px;
  background: var(--ec-title-bg);
}

.top-search {
  padding: 0px 10px 9px 10px;
  background: var(--ec-title-bg);
  display: flex;
  align-items: center;

  .iconfont {
    font-size: 12px;
  }

  .global-search-icon {
    cursor: pointer;

    &:hover {
      color: #07c160;
    }
  }
}

.chat-session-list {
  height: calc(100vh - 62px);
  overflow: hidden;
  border-top: 1px solid var(--ec-border);

  &:hover {
    overflow: auto;
  }
}

.search-list {
  height: calc(100vh - 62px);
  background: var(--ec-title-bg);
  overflow: hidden;

  &:hover {
    overflow: auto;
  }
}

.title-panel {
  display: flex;
  align-items: center;

  .title {
    height: 60px;
    line-height: 60px;
    padding-left: 10px;
    font-size: 18px;
    color: var(--ec-title-text);
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .title-actions {
    display: flex;
    align-items: center;
    gap: 15px;
    padding-right: 10px;

    .iconfont {
      font-size: 20px;
      cursor: pointer;
      transition: color 0.2s;

      &:hover {
        color: #07c160;
      }
    }
  }
}

.icon-more {
  position: absolute;
  z-index: 1;
  top: 30px;
  right: 3px;
  width: 20px;
  font-size: 20px;
  margin-right: 5px;
  cursor: pointer;
}

.chat-panel {
  border-top: 1px solid var(--ec-border);
  background: var(--ec-chat-bg);

  .message-panel {
    padding: 10px 30px 0px 30px;
    height: calc(100vh - 200px - 62px);
    overflow-y: auto;

    .message-item {
      margin-bottom: 15px;
      text-align: center;

      &.highlight-message {
        animation: highlight 2s ease;
      }
    }
  }
}

@keyframes highlight {
  0%, 100% {
    background-color: transparent;
  }
  50% {
    background-color: rgba(7, 193, 96, 0.2);
  }
}
</style>
