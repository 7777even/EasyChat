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
    </div>
    <div class="input-area" @drop="dropHandler" @dragover="dragOverHandler">
      <el-input
        :rows="5"
        v-model="msgContent"
        type="textarea"
        resize="none"
        maxlength="500"
        show-word-limit
        spellcheck="false"
        input-style="background:#f5f5f5;border:none;"
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
import {getCurrentInstance, onMounted, onUnmounted, ref} from 'vue'
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
}
defineExpose({
  cleanMessage
})

const activeEmoji = ref('笑脸')

//发送消息
const msgContent = ref('')

const emit = defineEmits(['sendMessage4Local'])
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

//添加好友
const searchAddRef = ref()
const addContact = (contactId, code) => {
  searchAddRef.value.show({
    contactId,
    contactType: code == 902 ? 'USER' : 'GROUP'
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
      fileType: messageObj.fileType
    },
    showError: false,
    errorCallback: (responseData) => {
      proxy.Confirm({
        message: responseData.info,
        okfun: () => {
          addContact(props.currentChatSession.contactId, responseData.code)
        },
        okText: '重新申请'
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
  Object.assign(messageObj, result.data)
  //更新列表
  emit('sendMessage4Local', messageObj)
  //保存消息到本地
  window.ipcRenderer.send('addLocalMessage', messageObj)
  
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
    border-bottom: 1px solid #e8e8e8;

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
