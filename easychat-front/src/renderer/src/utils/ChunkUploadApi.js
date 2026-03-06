import Request from './Request'
import Api from './Api'
import ChunkUpload from './ChunkUpload'

/**
 * 分片上传API封装
 */
class ChunkUploadApi {
  constructor() {
    this.chunkUploader = new ChunkUpload({
      chunkSize: 5 * 1024 * 1024, // 5MB
      maxConcurrent: 3,
      retryTimes: 3
    })
  }

  /**
   * 上传单个分片
   */
  async uploadChunk(fileId, chunkIndex, totalChunks, chunkBlob) {
    const formData = new FormData()
    formData.append('fileId', fileId)
    formData.append('chunkIndex', chunkIndex)
    formData.append('totalChunks', totalChunks)
    formData.append('chunk', chunkBlob, `${fileId}_${chunkIndex}.chunk`)

    return Request({
      url: Api.uploadChunk,
      params: formData,
      showLoading: false,
      showError: false
    })
  }

  /**
   * 合并分片
   */
  async mergeChunks(fileId, messageId, fileName, totalChunks, cover) {
    const formData = new FormData()
    formData.append('fileId', fileId)
    formData.append('messageId', messageId)
    formData.append('fileName', fileName)
    formData.append('totalChunks', totalChunks)
    if (cover) {
      formData.append('cover', cover)
    }

    return Request({
      url: Api.mergeChunks,
      params: formData,
      showLoading: false
    })
  }

  /**
   * 检查已上传的分片
   */
  async checkChunks(fileId, totalChunks) {
    return Request({
      url: Api.checkChunks,
      params: {
        fileId,
        totalChunks
      },
      showLoading: false,
      showError: false
    })
  }

  /**
   * 上传文件（使用分片上传）
   */
  async uploadFile(file, messageId, cover, options = {}) {
    const {
      onProgress,
      onChunkSuccess,
      onComplete,
      onError
    } = options

    return this.chunkUploader.upload(file, messageId, {
      cover,
      onProgress,
      onChunkSuccess,
      onComplete,
      onError,
      uploadChunkApi: this.uploadChunk.bind(this),
      mergeChunksApi: this.mergeChunks.bind(this),
      checkChunksApi: this.checkChunks.bind(this)
    })
  }
}

export default new ChunkUploadApi()
