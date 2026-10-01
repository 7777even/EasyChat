<template>
  <div class="send-panel">
    <div class="toolbar">
      <el-popover
        :visible="showEmojiPopover"
        trigger="click"
        placement="top"
        :teleported="false"
        @show="openPopover"
        @hide="closePopover"
        :popper-style="{
          padding: '0px 10px 10px 10px',
          width: '490px'
        }"
      >
        <template #default>
          <el-tabs v-model="activeEmoji" @click.stop>
            <el-tab-pane :label="emoji.name" :name="emoji.name" v-for="emoji in emojiList">
              <div class="emoji-list">
                <div class="emoji-item" v-for="item in emoji.emojiList" @click="sendEmoji(item)">
                  {{ item }}
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
        <template #reference>
          <div class="iconfont icon-emoji" @click="showEmojiPopoverHandler"></div>
        </template>
      </el-popover>
      <el-upload
        ref="uploadRef"
        name="file"
        :show-file-list="false"
        :multiple="true"
        :limit="fileLimit"
        :http-request="uploadFile"
        :on-exceed="uploadExceed"
      >
        <div class="iconfont icon-folder"></div>
      </el-upload>
      <!-- 群聊 @ 提及（仅群聊可见；「@所有人」仅群主/管理员可见） -->
      <el-popover
        v-if="isGroupChat"
        :visible="showAtPopover"
        trigger="click"
        placement="top-start"
        :teleported="false"
        :popper-style="{
          padding: '0px',
          width: '240px'
        }"
        @show="openAtPopoverHandler"
        @hide="closeAtPopover"
      >
        <template #default>
          <div class="at-panel" @click.stop>
            <div class="at-item at-item-all" v-if="canAtAll" @click="selectAtAll">
              <div class="at-name">@所有人</div>
              <div class="at-desc">提醒群内全体成员</div>
            </div>
            <div class="at-search">
              <el-input v-model="atKeyword" placeholder="搜索群成员" size="small" clearable />
            </div>
            <div class="at-list">
              <div
                class="at-item"
                v-for="item in filteredAtMemberList"
                :key="item.userId"
                @click="selectAtMember(item)"
              >
                <div class="at-name">{{ item.contactName || item.userId }}</div>
                <div class="at-role" v-if="item.role != null && item.role != 2">{{ roleText(item.role) }}</div>
              </div>
              <div class="at-empty" v-if="atLoading">加载中...</div>
              <div class="at-empty" v-else-if="filteredAtMemberList.length == 0">无匹配群成员</div>
            </div>
          </div>
        </template>
        <template #reference>
          <span class="at-icon" @click="showAtPopoverHandler" title="@"></span>
        </template>
      </el-popover>
      <div class="iconfont icon-search" @click="showSearchDialog" title="搜索消息"></div>
    </div>
    <div class="quote-panel" v-if="quoteInfo">
      <div class="quote-text">
        <span class="quote-name">{{ quoteInfo.quoteNickName || '引用' }}</span>
        <span class="quote-content">{{ quoteInfo.quoteContent }}</span>
      </div>
      <span class="iconfont icon-close quote-close" @click="clearQuote"></span>
    </div>
    <div class="input-area" @drop="dropHandler" @dragover="dragOverHandler">
      <el-input
        ref="inputRef"
        :rows="5"
        v-model="msgContent"
        type="textarea"
        resize="none"
        maxlength="500"
        show-word-limit
        spellcheck="false"
        input-style="background:var(--ec-input-bg);border:none;"
        @keydown.enter="sendMessage"
        @paste="pasteFile"
      />
    </div>
    <div class="send-btn-panel">
      <el-popover
        trigger="click"
        :visible="showSendMsgPopover"
        :hide-after="1500"
        placement="top-end"
        :teleported="false"
        @show="openPopover"
        @hide="closePopover"
        :popper-style="{
          padding: '5px',
          'min-width': '0px',
          width: '120px'
        }"
      >
        <template #default><span class="empty-msg">不能发送空白信息</span></template>
        <template #reference>
          <span class="send-btn" @click="sendMessage">发送(S)</span>
        </template>
      </el-popover>
    </div>

    <!-- 上传进度显示 -->
    <div v-if="Object.keys(uploadProgress).length > 0" class="upload-progress-panel">
      <div class="progress-header">
        <span>文件上传中</span>
        <span class="file-count">{{ Object.keys(uploadProgress).length }} 个文件</span>
      </div>
      <div v-for="(item, messageId) in uploadProgress" :key="messageId" class="progress-item">
        <div class="file-name">{{ item.fileName }}</div>
        <el-progress 
          :percentage="item.progress" 
          :status="item.status === 'success' ? 'success' : item.status === 'error' ? 'exception' : ''"
        />
        <div class="progress-text">{{ item.uploadedChunks }}/{{ item.totalChunks }} 分片</div>
      </div>
    </div>

    <!--添加好友-->
    <SearchAdd ref="searchAddRef"></SearchAdd>
  </div>
</template>

<script setup>
import SearchAdd from '@/views/contact/SearchAdd.vue'
import {getFileType} from '@/utils/Constants.js'
import {computed, getCurrentInstance, nextTick, onMounted, onUnmounted, ref, watch} from 'vue'
import emojiList from '@/utils/Emoji.js'
import {useUserInfoStore} from '@/stores/UserInfoStore'
import {useSysSettingStore} from '@/stores/SysSettingStore'
import ChunkUploadApi from '@/utils/ChunkUploadApi'

const {proxy} = getCurrentInstance()
const userInfoStore = useUserInfoStore()

const sysSettingStore = useSysSettingStore()

const props = defineProps({
  currentChatSession: {
    type: Object,
    default: {}
  }
})

// 上传进度状态
const uploadProgress = ref({})

const cleanMessage = () => {
  msgContent.value = ''
  clearQuote()
  atAllEnabled.value = false
}

// ===== 引用回复 =====
const quoteInfo = ref(null)
const setQuote = (quote) => {
  quoteInfo.value = quote
}
const clearQuote = () => {
  quoteInfo.value = null
}

defineExpose({
  cleanMessage,
  setQuote,
  clearQuote
})

//发送消息（需先于下方草稿 watch 声明，否则 setup 阶段 TDZ 报错导致整页白屏）
const msgContent = ref('')

// ===== 群聊 @ 提及 =====
const AT_ALL_TEXT = '@所有人'
const AT_ALL_ROLE = 0 // 群主
const AT_ADMIN_ROLE = 1 // 管理员
// 仅群聊出现 @ 按钮
const isGroupChat = computed(() => props.currentChatSession.contactType == 1)
// 本人群角色（0 群主 / 1 管理员 / 2 成员），来自 getGroupInfo4Chat 的 userContactList
const myGroupRole = ref(2)
// 仅群主/管理员可 @所有人（普通成员面板不渲染该选项）
const canAtAll = computed(
  () => myGroupRole.value === AT_ALL_ROLE || myGroupRole.value === AT_ADMIN_ROLE
)
const roleText = (role) => ({ 0: '群主', 1: '管理员', 2: '成员' })[role] || '成员'

const showAtPopover = ref(false)
const atKeyword = ref('')
const atMemberList = ref([])
const atLoading = ref(false)
// 输入框组件实例：@ 文本需插入到光标位置而非文末
const inputRef = ref()
// 本条消息是否携带 @所有人 标记（写入 extraData.atAll，发送后清空）
const atAllEnabled = ref(false)

// 按关键词过滤群成员
const filteredAtMemberList = computed(() => {
  const keyword = (atKeyword.value || '').trim().toLowerCase()
  if (!keyword) {
    return atMemberList.value
  }
  return atMemberList.value.filter((item) => {
    return (item.contactName || '').toLowerCase().includes(keyword)
  })
})

// 打开 @ 面板时按需拉取群成员 + 我的角色（同一群只拉一次，切换会话后重置）
const loadAtMemberList = async () => {
  const groupId = props.currentChatSession.contactId
  if (!groupId || atLoading.value || atMemberList.value.length > 0) {
    return
  }
  atLoading.value = true
  try {
    const result = await proxy.Request({
      url: proxy.Api.getGroupInfo4Chat,
      params: {groupId},
      showLoading: false,
      showError: false,
      errorCallback: (response) => {
        proxy.Confirm({message: response.message || response.info, showCancelBtn: false})
      }
    })
    if (!result) {
      return
    }
    atMemberList.value = result.data.userContactList || []
    const me = userInfoStore.getInfo().userId
    const mine = atMemberList.value.find((item) => item.userId === me)
    if (mine && mine.role != null) {
      myGroupRole.value = Number(mine.role)
    }
  } finally {
    atLoading.value = false
  }
}

const showAtPopoverHandler = () => {
  showAtPopover.value = true
  loadAtMemberList()
}
const openAtPopoverHandler = () => {
  loadAtMemberList()
}
const closeAtPopover = () => {
  showAtPopover.value = false
  atKeyword.value = ''
}

// 在光标处插入 @ 文本（沿用既有 @Ux 格式，服务端 atUserIds 解析依赖此形态）
const insertAtText = (text) => {
  const textarea = inputRef.value && inputRef.value.textarea
  if (!textarea) {
    msgContent.value = msgContent.value + text
    return
  }
  const start = textarea.selectionStart ?? msgContent.value.length
  const end = textarea.selectionEnd ?? start
  msgContent.value =
    msgContent.value.slice(0, start) + text + msgContent.value.slice(end)
  // 插入后把光标移到文本之后
  nextTick(() => {
    const cursor = start + text.length
    textarea.focus()
    textarea.setSelectionRange(cursor, cursor)
  })
}

const selectAtMember = (member) => {
  insertAtText(`@${member.userId} `)
  closeAtPopover()
}

const selectAtAll = () => {
  // 双重保护：非群主/管理员不允许 @所有人
  if (!canAtAll.value) {
    proxy.Message.warning('仅群主或管理员可以@所有人')
    return
  }
  if (msgContent.value.indexOf(AT_ALL_TEXT) < 0) {
    insertAtText(`${AT_ALL_TEXT} `)
  }
  atAllEnabled.value = true
  closeAtPopover()
}

// 切换会话时重置 @ 面板缓存（成员列表与角色按群隔离）
watch(
  () => props.currentChatSession.contactId,
  () => {
    atMemberList.value = []
    myGroupRole.value = 2
    atAllEnabled.value = false
    showAtPopover.value = false
    atKeyword.value = ''
  }
)

// ===== 会话草稿：切会话时保存 / 恢复（跨端同步，服务端真源） =====
let draftContactId = null
let draftTimer = null
watch(
  () => props.currentChatSession.contactId,
  (newContactId, oldContactId) => {
    // 保存上一个会话的草稿
    if (oldContactId && draftContactId === oldContactId) {
      saveDraft(oldContactId, msgContent.value)
    }
    draftContactId = newContactId || null
    // 恢复新会话草稿
    msgContent.value = (newContactId && props.currentChatSession.draft) || ''
    clearQuote()
  },
  {immediate: true}
)

// 输入防抖保存草稿
watch(msgContent, (val) => {
  if (!draftContactId) return
  if (draftTimer) {
    clearTimeout(draftTimer)
  }
  draftTimer = setTimeout(() => {
    saveDraft(draftContactId, val)
  }, 800)
})

const saveDraft = (contactId, draft) => {
  window.ipcRenderer.send('saveSessionDraft', {contactId, draft: draft || ''})
  proxy.Request({
    url: proxy.Api.saveSessionDraft,
    showLoading: false,
    showError: false,
    params: {contactId, draft: draft || ''}
  }).catch(() => {})
}

const activeEmoji = ref('笑脸')

const emit = defineEmits(['sendMessage4Local', 'showSearch'])
const sendMessage = async (e) => {
  //shift +enter 换行  enter 发送
  if (e.shiftKey && e.keyCode === 13) {
    return
  }
  e.preventDefault()
  const messageContent = msgContent.value ? msgContent.value.replace(/\s*$/g, '') : ''
  if (messageContent == '') {
    showSendMsgPopover.value = true
    return
  }
  sendMessageDo({messageContent, messageType: 2}, true)
}

/**
 * 组装消息扩展数据：引用回复 / 群 @ 提及 / @所有人
 * 均落在 extraData（JSON 字符串），@ 另落 atUserIds（服务端用于红点提醒）。
 * 三者可共存，按需合并，避免引用消息丢失 @ 标记。
 */
const buildExtraData = (messageContent) => {
  const extra = {}
  if (quoteInfo.value) {
    extra.quoteId = quoteInfo.value.messageId
    extra.quoteContent = quoteInfo.value.quoteContent
    extra.quoteNickName = quoteInfo.value.quoteNickName || ''
  }
  // 群 @ 提及：正文里形如 "@Uxxxx" 的用户 ID
  if (props.currentChatSession.contactType == 1 && messageContent) {
    const matched = messageContent.match(/@(U[A-Za-z0-9]+)/g)
    if (matched && matched.length > 0) {
      extra.atUserIds = matched.map((item) => item.substring(1))
    }
    // @所有人：以面板勾选标记为准，正文出现 @所有人 也认（兼容草稿恢复后重发）
    if (atAllEnabled.value || messageContent.indexOf(AT_ALL_TEXT) >= 0) {
      extra.atAll = true
    }
  }
  return Object.keys(extra).length > 0 ? JSON.stringify(extra) : null
}

const buildAtUserIds = (messageContent) => {
  if (props.currentChatSession.contactType != 1 || !messageContent) {
    return null
  }
  const matched = messageContent.match(/@(U[A-Za-z0-9]+)/g)
  if (!matched || matched.length == 0) {
    return null
  }
  return Array.from(new Set(matched.map((item) => item.substring(1)))).join(',')
}

//添加好友
const searchAddRef = ref()
const addContact = (contactId, code) => {
  searchAddRef.value.show({
    contactId,
    //902 是已下线的旧码，现码：2301 非好友（USER）/ 2302 不在群（GROUP）
    contactType: code == 2301 ? 'USER' : 'GROUP'
  })
}

//发送消息
const sendMessageDo = async (
  messageObj = {
    messageContent,
    messageType,
    localFilePath,
    fileSize,
    fileName,
    filePath,
    fileType
  },
  cleanMsgContent,
  skipSizeCheck = false  // 新增参数：是否跳过文件大小检查
) => {
  // 只有在不跳过检查时才进行文件大小验证
  if (!skipSizeCheck && !checkFileSize(messageObj.fileType, messageObj.fileSize, messageObj.fileName)) {
    return
  }

  if (messageObj.fileSize == 0) {
    proxy.Confirm({
      message: `"${messageObj.fileName}"是一个空文件无法发送，请重新选择`,
      showCancelBtn: false
    })
    return
  }
  messageObj.sessionId = props.currentChatSession.sessionId
  messageObj.sendUserId = userInfoStore.getInfo().userId

  // ===== 消息可靠性：生成客户端唯一 ID，用于去重 + ACK 匹配 =====
  // 仅对需要可靠投递的消息类型生成 clientId（排除群创建系统消息）
  const needReliable = [2, 5].includes(messageObj.messageType)
  if (needReliable && !messageObj.clientId) {
    messageObj.clientId = crypto.randomUUID()
  }

  //请求服务器发送消息
  let result = await proxy.Request({
    url: proxy.Api.sendMessage,
    showLoading: false,
    params: {
      messageContent: messageObj.messageContent,
      contactId: props.currentChatSession.contactId,
      messageType: messageObj.messageType,
      fileSize: messageObj.fileSize,
      fileName: messageObj.fileName,
      fileType: messageObj.fileType,
      clientId: messageObj.clientId || null,
      extraData: messageObj.extraData || buildExtraData(messageObj.messageContent),
      atUserIds: messageObj.atUserIds || buildAtUserIds(messageObj.messageContent)
    },
    showError: false,
    errorCallback: (responseData) => {
      //仅联系人类失败（2301 非好友 / 2302 不在群）才走「重新申请」加好友流程；
      //其余业务拦截（如 2701 敏感词、文件类校验）只做提示，不触发申请动作
      const code = responseData.code
      const isContactApply = code == 2301 || code == 2302
      proxy.Confirm({
        message: responseData.message || responseData.info,
        okfun: isContactApply ? () => addContact(props.currentChatSession.contactId, code) : undefined,
        okText: isContactApply ? '重新申请' : '我知道了',
        showCancelBtn: isContactApply
      })
    }
  })
  if (!result) {
    return
  }
  //更新本地消息
  if (cleanMsgContent) {
    msgContent.value = ''
  }
  // 引用随消息一起落库展示
  const extraData = messageObj.extraData || buildExtraData(messageObj.messageContent)
  if (extraData) {
    messageObj.extraData = extraData
  }
  clearQuote()
  // @所有人标记随消息一次性消费，发送后清空，避免下一条消息误带
  atAllEnabled.value = false
  Object.assign(messageObj, result.data)
  //更新列表
  emit('sendMessage4Local', messageObj)
  //保存消息到本地
  window.ipcRenderer.send('addLocalMessage', messageObj)

  //注册待 ACK 消息
  if (messageObj.clientId) {
    window.ipcRenderer.send('registerPendingAck', {
      clientId: messageObj.clientId,
      messageObj
    })
  }

  // 返回 messageId 用于文件上传
  return result.data
}

//表情相关
const sendEmoji = (emoji) => {
  msgContent.value = msgContent.value + emoji
  showEmojiPopover.value = false
}

const showEmojiPopoverHandler = () => {
  showEmojiPopover.value = true
}

const showSendMsgPopover = ref(false)
const showEmojiPopover = ref(false)

const hidePopover = () => {
  showSendMsgPopover.value = false
  showEmojiPopover.value = false
}
const openPopover = () => {
  document.addEventListener('click', hidePopover, false)
}
const closePopover = () => {
  document.removeEventListener('click', hidePopover, false)
}

//校验文件大小
const checkFileSize = (fileType, fileSize, fileName) => {
  const SIZE_MB = 1024 * 1024
  const settingArray = Object.values(sysSettingStore.getSetting())
  //图片
  if (fileSize > settingArray[fileType] * SIZE_MB) {
    proxy.Confirm({
      message: `文件${fileName}超过大小${settingArray[fileType]}MB限制`,
      showCancelBtn: false
    })
    return false
  }
  return true
}

//发送文件
const fileLimit = 10
const checkFileLimit = (files) => {
  if (files.length > fileLimit) {
    proxy.Confirm({
      message: `一次最多可以上传10个文件`,
      showCancelBtn: false
    })
    return
  }
  return true
}
//拖入文件
const dragOverHandler = (event) => {
  event.preventDefault()
}
const dropHandler = (event) => {
  event.preventDefault()
  const files = event.dataTransfer.files
  if (!checkFileLimit(files)) {
    return
  }
  for (let i = 0; i < files.length; i++) {
    uploadFileDo(files[i])
  }
}

//文件个数超过指定值
const uploadExceed = (files) => {
  checkFileLimit(files)
}
//上传文件
const uploadRef = ref()
const uploadFile = (file) => {
  uploadFileDo(file.file)
  uploadRef.value.clearFiles()
}

const getFileTypeByName = (fileName) => {
  const fileSuffix = fileName.substr(fileName.lastIndexOf('.') + 1)
  return getFileType(fileSuffix)
}

// 使用分片上传文件
const uploadFileDo = async (file) => {
  const fileType = getFileTypeByName(file.name)
  
  // 判断文件大小，决定上传方式
  const USE_CHUNK_SIZE = 5 * 1024 * 1024 // 5MB阈值
  const useChunkUpload = file.size >= USE_CHUNK_SIZE
  
  // 先发送消息创建记录
  // 对于大文件（使用分片上传），跳过文件大小检查
  const result = await sendMessageDo(
    {
      messageContent: '[' + getFileType(fileType) + ']',
      messageType: 5,
      fileSize: file.size,
      fileName: file.name,
      filePath: file.path,
      fileType: fileType
    },
    false,
    useChunkUpload  // 大文件跳过大小检查
  )
  
  if (!result || !result.messageId) {
    return
  }
  
  const messageId = result.messageId
  
  if (useChunkUpload) {
    // 大文件使用分片上传
    console.log(`文件 ${file.name} 大于5MB (${(file.size / 1024 / 1024).toFixed(2)}MB)，使用分片上传`)
    uploadFileChunk(file, messageId)
  } else {
    // 小文件使用普通上传
    console.log(`文件 ${file.name} 小于5MB (${(file.size / 1024 / 1024).toFixed(2)}MB)，使用普通上传`)
    uploadFileNormal(file, messageId)
  }
}

// 普通上传（原有逻辑）
const uploadFileNormal = async (file, messageId) => {
  const formData = new FormData()
  formData.append('messageId', messageId)
  formData.append('file', file)
  
  try {
    const result = await proxy.Request({
      url: proxy.Api.uploadFile,
      params: formData,
      showLoading: false
    })
    
    if (result && result.code === 200) {
      console.log('文件上传成功:', file.name)
    }
  } catch (error) {
    console.error('文件上传失败:', error)
    proxy.Message.error('文件上传失败')
  }
}

// 分片上传
const uploadFileChunk = async (file, messageId) => {
  // 初始化上传进度
  uploadProgress.value[messageId] = {
    fileName: file.name,
    progress: 0,
    uploadedChunks: 0,
    totalChunks: 0,
    status: 'uploading'
  }
  
  try {
    await ChunkUploadApi.uploadFile(file, messageId, null, {
      onProgress: (progress, uploadedCount, totalChunks) => {
        if (uploadProgress.value[messageId]) {
          uploadProgress.value[messageId].progress = progress
          uploadProgress.value[messageId].uploadedChunks = uploadedCount
          uploadProgress.value[messageId].totalChunks = totalChunks
        }
      },
      
      onChunkSuccess: (uploadedCount, totalChunks) => {
        console.log(`文件 ${file.name} 分片上传: ${uploadedCount}/${totalChunks}`)
      },
      
      onComplete: (fileId) => {
        console.log('文件上传完成:', file.name)
        if (uploadProgress.value[messageId]) {
          uploadProgress.value[messageId].status = 'success'
        }
        
        // 2秒后移除进度显示
        setTimeout(() => {
          delete uploadProgress.value[messageId]
        }, 2000)
        
        // 通知更新消息状态
        window.ipcRenderer.send('updateMessageStatus', {
          messageId,
          status: 'completed'
        })
      },
      
      onError: (error) => {
        console.error('文件上传失败:', error)
        if (uploadProgress.value[messageId]) {
          uploadProgress.value[messageId].status = 'error'
        }
        proxy.Message.error('文件上传失败: ' + error.message)
        
        // 3秒后移除进度显示
        setTimeout(() => {
          delete uploadProgress.value[messageId]
        }, 3000)
      }
    })
  } catch (error) {
    console.error('文件上传异常:', error)
    delete uploadProgress.value[messageId]
  }
}

//截图粘贴上传文件
const pasteFile = async (event) => {
  let items = event.clipboardData && event.clipboardData.items
  const fileData = {}
  for (const item of items) {
    if (item.kind != 'file') {
      break
    }
    const file = await item.getAsFile()
    if (file.path != '') {
      uploadFileDo(file)
    } else {
      const imageFile = new File([file], 'temp.jpg')
      let fileReader = new FileReader()
      fileReader.onloadend = function () {
        // 读取完成后获得结果
        const byteArray = new Uint8Array(this.result)
        fileData.byteArray = byteArray
        fileData.name = imageFile.name
        window.ipcRenderer.send('saveClipBoardFile', fileData)
      }
      fileReader.readAsArrayBuffer(imageFile)
    }
  }
}

onMounted(() => {
  window.ipcRenderer.on('saveClipBoardFileCallback', (e, file) => {
    const fileType = 0
    sendMessageDo(
      {
        messageContent: '[' + getFileType(fileType) + ']',
        messageType: 5,
        fileSize: file.size,
        fileName: file.name,
        filePath: file.path,
        fileType: fileType
      },
      false
    )
  })
})

// 搜索消息
const showSearchDialog = () => {
  emit('showSearch')
}

onUnmounted(() => {
  window.ipcRenderer.removeAllListeners('saveClipBoardFileCallback')
})
</script>

<style lang="scss" scoped>
.emoji-list {
  .emoji-item {
    float: left;
    font-size: 23px;
    padding: 2px;
    text-align: center;
    border-radius: 3px;
    margin-left: 10px;
    margin-top: 5px;
    cursor: pointer;

    &:hover {
      background: #ddd;
    }
  }
}

.send-panel {
  height: 200px;
  border-top: 1px solid #ddd;

  .toolbar {
    height: 40px;
    display: flex;
    align-items: center;
    padding-left: 10px;

    .iconfont {
      color: #494949;
      font-size: 20px;
      margin-left: 10px;
      cursor: pointer;
    }

    :deep(.el-tabs__header) {
      margin-bottom: 0px;
    }
  }

  .input-area {
    padding: 0px 10px;
    outline: none;
    width: 100%;
    height: 115px;
    overflow: auto;
    word-wrap: break-word;
    word-break: break-all;

    :deep(.el-textarea__inner) {
      box-shadow: none;
    }

    :deep(.el-input__count) {
      background: none;
      right: 12px;
    }
  }

  .send-btn-panel {
    text-align: right;
    padding-top: 10px;
    margin-right: 22px;

    .send-btn {
      cursor: pointer;
      color: #07c160;
      background: #e9e9e9;
      border-radius: 5px;
      padding: 8px 25px;

      &:hover {
        background: #d2d2d2;
      }
    }

    .empty-msg {
      font-size: 13px;
    }
  }
}

// ===== @ 提及选择面板 =====
.at-panel {
  font-size: 13px;
  color: var(--ec-body-text);

  .at-search {
    padding: 8px;
    border-bottom: 1px solid var(--ec-divider);
  }

  .at-list {
    max-height: 220px;
    overflow-y: auto;
    padding: 4px 0;
  }

  .at-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 7px 12px;
    cursor: pointer;

    &:hover {
      background: var(--ec-surface-soft);
    }

    &.at-item-all {
      border-bottom: 1px solid var(--ec-divider);
      color: var(--ec-at-all);
      font-weight: 600;
    }
  }

  .at-name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .at-desc {
    font-size: 11px;
    color: #b2b2b2;
    margin-left: 8px;
  }

  .at-role {
    flex-shrink: 0;
    margin-left: 8px;
    padding: 0 5px;
    font-size: 11px;
    border-radius: 3px;
    background: var(--ec-chip-green-bg);
    color: #07c160;
  }

  .at-empty {
    padding: 12px;
    text-align: center;
    color: #b2b2b2;
  }
}

.upload-progress-panel {
  position: fixed;
  bottom: 220px;
  right: 20px;
  width: 320px;
  max-height: 400px;
  overflow-y: auto;
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
  padding: 15px;
  z-index: 1000;

  .progress-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 15px;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--ec-divider);

    span {
      font-size: 14px;
      font-weight: 500;
      color: #333;
    }

    .file-count {
      font-size: 12px;
      color: #999;
      font-weight: normal;
    }
  }

  .progress-item {
    margin-bottom: 15px;

    &:last-child {
      margin-bottom: 0;
    }

    .file-name {
      font-size: 13px;
      color: #333;
      margin-bottom: 8px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .progress-text {
      font-size: 12px;
      color: #999;
      margin-top: 5px;
      text-align: right;
    }
  }
}
</style>
