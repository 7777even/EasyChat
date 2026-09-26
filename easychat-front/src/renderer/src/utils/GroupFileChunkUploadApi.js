import Request from './Request'
import Api from './Api'
import ChunkUpload from './ChunkUpload'

/**
 * 群文件分片上传API封装（分片阶段复用 /upload/*，合并阶段指向 /group/file/upload）
 */
class GroupFileChunkUploadApi {
  constructor() {
    this.chunkUploader = new ChunkUpload({
      chunkSize: 5 * 1024 * 1024, // 5MB
      maxConcurrent: 3,
      retryTimes: 3
    })
  }

  /**
   * 上传单个分片（复用既有 /upload/uploadChunk）
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
   * 检查已上传的分片（复用既有 /upload/checkChunks）
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
   * 合并分片并入库（指向群文件专属合并接口）
   */
  async mergeChunks(fileId, groupId, fileName, totalChunks, fileType) {
    const formData = new FormData()
    formData.append('fileId', fileId)
    formData.append('groupId', groupId)
    formData.append('fileName', fileName)
    formData.append('totalChunks', totalChunks)
    formData.append('fileType', fileType)

    return Request({
      url: Api.groupFileUpload,
      params: formData,
      showLoading: false
    })
  }

  /**
   * 上传群文件（使用分片上传）
   */
  async uploadFile(file, groupId, fileType, options = {}) {
    const {
      onProgress,
      onChunkSuccess,
      onComplete,
      onError
    } = options

    return this.chunkUploader.upload(file, groupId, {
      onProgress,
      onChunkSuccess,
      onComplete,
      onError,
      uploadChunkApi: this.uploadChunk.bind(this),
      mergeChunksApi: async (fileId, gid, fileName, totalChunks, cover) => {
        return this.mergeChunks(fileId, gid, fileName, totalChunks, fileType)
      },
      checkChunksApi: this.checkChunks.bind(this)
    })
  }
}

export default new GroupFileChunkUploadApi()
