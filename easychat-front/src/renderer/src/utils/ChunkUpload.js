import SparkMD5 from 'spark-md5'

/**
 * 分片上传工具类
 */
class ChunkUpload {
  constructor(options = {}) {
    this.chunkSize = options.chunkSize || 5 * 1024 * 1024 // 默认5MB
    this.maxConcurrent = options.maxConcurrent || 3 // 最大并发数
    this.retryTimes = options.retryTimes || 3 // 重试次数
  }

  /**
   * 计算文件MD5作为唯一标识
   */
  async calculateFileMD5(file) {
    return new Promise((resolve, reject) => {
      const spark = new SparkMD5.ArrayBuffer()
      const fileReader = new FileReader()
      const chunks = Math.ceil(file.size / this.chunkSize)
      let currentChunk = 0

      fileReader.onload = (e) => {
        spark.append(e.target.result)
        currentChunk++

        if (currentChunk < chunks) {
          loadNext()
        } else {
          resolve(spark.end())
        }
      }

      fileReader.onerror = () => {
        reject(new Error('文件读取失败'))
      }

      const loadNext = () => {
        const start = currentChunk * this.chunkSize
        const end = Math.min(start + this.chunkSize, file.size)
        fileReader.readAsArrayBuffer(file.slice(start, end))
      }

      loadNext()
    })
  }

  /**
   * 分片上传文件
   */
  async upload(file, messageId, options = {}) {
    const {
      onProgress,
      onChunkSuccess,
      onComplete,
      onError,
      uploadChunkApi,
      mergeChunksApi,
      checkChunksApi,
      cover
    } = options

    try {
      // 计算文件唯一标识
      const fileId = await this.calculateFileMD5(file)
      const totalChunks = Math.ceil(file.size / this.chunkSize)

      // 检查已上传的分片（断点续传）
      let uploadedChunks = []
      if (checkChunksApi) {
        try {
          const result = await checkChunksApi(fileId, totalChunks)
          uploadedChunks = result.data || []
        } catch (e) {
          console.warn('检查已上传分片失败，将重新上传', e)
        }
      }

      // 准备待上传的分片
      const chunksToUpload = []
      for (let i = 0; i < totalChunks; i++) {
        if (!uploadedChunks.includes(i)) {
          chunksToUpload.push(i)
        }
      }

      console.log(`文件分片上传开始: fileId=${fileId}, 总分片=${totalChunks}, 待上传=${chunksToUpload.length}`)

      // 上传进度统计
      let uploadedCount = uploadedChunks.length
      const updateProgress = () => {
        const progress = Math.floor((uploadedCount / totalChunks) * 100)
        onProgress && onProgress(progress, uploadedCount, totalChunks)
      }

      // 初始进度
      updateProgress()

      // 并发上传分片
      await this.uploadChunksConcurrently(
        file,
        fileId,
        chunksToUpload,
        totalChunks,
        uploadChunkApi,
        () => {
          uploadedCount++
          updateProgress()
          onChunkSuccess && onChunkSuccess(uploadedCount, totalChunks)
        }
      )

      // 合并分片
      console.log('所有分片上传完成，开始合并...')
      await mergeChunksApi(fileId, messageId, file.name, totalChunks, cover)

      console.log('文件上传完成')
      onComplete && onComplete(fileId)

      return { success: true, fileId }
    } catch (error) {
      console.error('文件上传失败:', error)
      onError && onError(error)
      throw error
    }
  }

  /**
   * 并发上传分片
   */
  async uploadChunksConcurrently(file, fileId, chunkIndexes, totalChunks, uploadChunkApi, onChunkSuccess) {
    const queue = [...chunkIndexes]
    const executing = []

    const uploadChunk = async (chunkIndex) => {
      const start = chunkIndex * this.chunkSize
      const end = Math.min(start + this.chunkSize, file.size)
      const chunkBlob = file.slice(start, end)

      // 重试机制
      for (let retry = 0; retry <= this.retryTimes; retry++) {
        try {
          await uploadChunkApi(fileId, chunkIndex, totalChunks, chunkBlob)
          onChunkSuccess()
          return
        } catch (error) {
          if (retry === this.retryTimes) {
            throw new Error(`分片${chunkIndex}上传失败: ${error.message}`)
          }
          console.warn(`分片${chunkIndex}上传失败，重试${retry + 1}/${this.retryTimes}`)
          await this.sleep(1000 * (retry + 1))
        }
      }
    }

    while (queue.length > 0 || executing.length > 0) {
      // 控制并发数
      while (executing.length < this.maxConcurrent && queue.length > 0) {
        const chunkIndex = queue.shift()
        const promise = uploadChunk(chunkIndex).then(() => {
          executing.splice(executing.indexOf(promise), 1)
        })
        executing.push(promise)
      }

      if (executing.length > 0) {
        await Promise.race(executing)
      }
    }
  }

  /**
   * 延迟函数
   */
  sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }
}

export default ChunkUpload
