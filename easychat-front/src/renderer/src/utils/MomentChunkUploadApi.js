import Request from './Request'
import Api from './Api'
import ChunkUpload from './ChunkUpload'

/**
 * 朋友圈媒体分片上传API封装
 */
class MomentChunkUploadApi {
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
      url: Api.uploadMomentMediaChunk,
      params: formData,
      showLoading: false,
      showError: false
    })
  }

  /**
   * 合并分片
   */
  async mergeChunks(fileId, momentId, fileName, totalChunks, mediaType) {
    const formData = new FormData()
    formData.append('fileId', fileId)
    formData.append('momentId', momentId)
    formData.append('fileName', fileName)
    formData.append('totalChunks', totalChunks)
    formData.append('mediaType', mediaType)

    return Request({
      url: Api.mergeMomentMediaChunks,
      params: formData,
      showLoading: false
    })
  }

  /**
   * 检查已上传的分片
   */
  async checkChunks(fileId, totalChunks) {
    return Request({
      url: Api.checkMomentMediaChunks,
      params: {
        fileId,
        totalChunks
      },
      showLoading: false,
      showError: false
    })
  }

  /**
   * 上传朋友圈媒体文件（使用分片上传）
   */
  async uploadMedia(file, momentId, mediaType, options = {}) {
    const {
      onProgress,
      onChunkSuccess,
      onComplete,
      onError
    } = options

    return this.chunkUploader.upload(file, momentId, {
      onProgress,
      onChunkSuccess,
      onComplete: async (fileId) => {
        // 合并完成后，调用 onComplete
        if (onComplete) {
          onComplete(fileId)
        }
      },
      onError,
      uploadChunkApi: this.uploadChunk.bind(this),
      mergeChunksApi: async (fileId, momentId, fileName, totalChunks) => {
        return this.mergeChunks(fileId, momentId, fileName, totalChunks, mediaType)
      },
      checkChunksApi: this.checkChunks.bind(this)
    })
  }
}

export default new MomentChunkUploadApi()
