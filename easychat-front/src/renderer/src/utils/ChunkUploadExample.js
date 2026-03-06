/**
 * 分片上传使用示例
 * 
 * 这个文件展示了如何在 Vue 组件中使用分片上传功能
 */

import ChunkUploadApi from './ChunkUploadApi'

/**
 * 示例1: 基本使用 - 上传文件并显示进度
 */
export async function basicUploadExample(file, messageId, cover) {
  try {
    const result = await ChunkUploadApi.uploadFile(file, messageId, cover, {
      // 上传进度回调
      onProgress: (progress, uploadedCount, totalChunks) => {
        console.log(`上传进度: ${progress}%, 已上传: ${uploadedCount}/${totalChunks}`)
      },
      
      // 单个分片上传成功回调
      onChunkSuccess: (uploadedCount, totalChunks) => {
        console.log(`分片上传成功: ${uploadedCount}/${totalChunks}`)
      },
      
      // 上传完成回调
      onComplete: (fileId) => {
        console.log('文件上传完成, fileId:', fileId)
      },
      
      // 上传失败回调
      onError: (error) => {
        console.error('文件上传失败:', error)
      }
    })
    
    return result
  } catch (error) {
    console.error('上传异常:', error)
    throw error
  }
}

/**
 * 示例2: 在 Vue 组件中使用（带进度条）
 */
export const vueComponentExample = {
  data() {
    return {
      uploadProgress: 0,
      uploadedChunks: 0,
      totalChunks: 0,
      isUploading: false
    }
  },
  
  methods: {
    async uploadFileWithProgress(file, messageId, cover) {
      this.isUploading = true
      this.uploadProgress = 0
      
      try {
        await ChunkUploadApi.uploadFile(file, messageId, cover, {
          onProgress: (progress, uploadedCount, totalChunks) => {
            this.uploadProgress = progress
            this.uploadedChunks = uploadedCount
            this.totalChunks = totalChunks
          },
          
          onComplete: (fileId) => {
            this.$message.success('文件上传成功')
            this.isUploading = false
          },
          
          onError: (error) => {
            this.$message.error('文件上传失败: ' + error.message)
            this.isUploading = false
          }
        })
      } catch (error) {
        this.isUploading = false
      }
    }
  }
}

/**
 * 示例3: 批量上传多个文件
 */
export async function batchUploadExample(files, messageIds, covers) {
  const uploadPromises = files.map((file, index) => {
    return ChunkUploadApi.uploadFile(file, messageIds[index], covers[index], {
      onProgress: (progress) => {
        console.log(`文件${index + 1}上传进度: ${progress}%`)
      }
    })
  })
  
  try {
    const results = await Promise.all(uploadPromises)
    console.log('所有文件上传完成:', results)
    return results
  } catch (error) {
    console.error('批量上传失败:', error)
    throw error
  }
}

/**
 * 示例4: 在 MessageSend.vue 中集成
 * 
 * 替换原有的 uploadFileDo 函数
 */
export const messageSendIntegration = `
// 在 MessageSend.vue 的 <script setup> 中添加:

import ChunkUploadApi from '@/utils/ChunkUploadApi'

// 上传进度状态
const uploadProgress = ref({})

// 修改 uploadFileDo 函数
const uploadFileDo = async (file) => {
  const fileType = getFileTypeByName(file.name)
  
  // 先发送消息创建记录
  const messageObj = {
    messageContent: '[' + getFileType(fileType) + ']',
    messageType: 5,
    fileSize: file.size,
    fileName: file.name,
    filePath: file.path,
    fileType: fileType
  }
  
  // 发送消息并获取 messageId
  const result = await sendMessageDo(messageObj, false)
  const messageId = result.messageId
  
  // 初始化上传进度
  uploadProgress.value[messageId] = 0
  
  try {
    // 使用分片上传
    await ChunkUploadApi.uploadFile(file, messageId, null, {
      onProgress: (progress) => {
        uploadProgress.value[messageId] = progress
        console.log(\`文件 \${file.name} 上传进度: \${progress}%\`)
      },
      
      onComplete: (fileId) => {
        console.log('文件上传完成:', file.name)
        delete uploadProgress.value[messageId]
        // 通知更新消息状态
        window.ipcRenderer.send('updateMessageStatus', {
          messageId,
          status: 'completed'
        })
      },
      
      onError: (error) => {
        proxy.Message.error('文件上传失败: ' + error.message)
        delete uploadProgress.value[messageId]
      }
    })
  } catch (error) {
    console.error('文件上传异常:', error)
  }
}

// 在模板中显示上传进度
<template>
  <div v-if="Object.keys(uploadProgress).length > 0" class="upload-progress-panel">
    <div v-for="(progress, messageId) in uploadProgress" :key="messageId" class="progress-item">
      <el-progress :percentage="progress" />
    </div>
  </div>
</template>
`

/**
 * 示例5: 配置自定义参数
 */
export function customConfigExample() {
  // 创建自定义配置的上传器
  const customUploader = new ChunkUpload({
    chunkSize: 10 * 1024 * 1024, // 10MB 分片
    maxConcurrent: 5,             // 最大并发5个
    retryTimes: 5                 // 重试5次
  })
  
  return customUploader
}
`
