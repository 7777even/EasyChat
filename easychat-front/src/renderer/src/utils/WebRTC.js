/**
 * WebRTC 工具类
 */

// ICE 服务器配置
const ICE_SERVERS = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ]
}

class WebRTCManager {
  constructor() {
    this.peerConnections = new Map() // 存储多个对等连接
    this.localStream = null
    this.remoteStreams = new Map() // 存储多个远程流
    this.callId = null
    this.isGroup = false
    this.members = []
    this.onRemoteStreamCallback = null
    this.onRemoteStreamRemovedCallback = null
  }

  /**
   * 初始化本地媒体流
   */
  async initLocalStream(isVideo = true) {
    try {
      const constraints = {
        audio: true,
        video: isVideo ? { width: 1280, height: 720 } : false
      }
      this.localStream = await navigator.mediaDevices.getUserMedia(constraints)
      return this.localStream
    } catch (error) {
      console.error('获取本地媒体流失败:', error)
      throw error
    }
  }

  /**
   * 创建对等连接
   */
  createPeerConnection(userId, onSignal) {
    const pc = new RTCPeerConnection(ICE_SERVERS)

    // 添加本地流
    if (this.localStream) {
      this.localStream.getTracks().forEach((track) => {
        pc.addTrack(track, this.localStream)
      })
    }

    // 监听 ICE 候选
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        onSignal({
          signalType: 'ice-candidate',
          toId: userId,
          data: event.candidate
        })
      }
    }

    // 监听远程流
    pc.ontrack = (event) => {
      console.log('收到远程流:', userId)
      this.remoteStreams.set(userId, event.streams[0])
      if (this.onRemoteStreamCallback) {
        this.onRemoteStreamCallback(userId, event.streams[0])
      }
    }

    // 监听连接状态
    pc.onconnectionstatechange = () => {
      console.log(`连接状态 [${userId}]:`, pc.connectionState)
      if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
        this.removePeerConnection(userId)
      }
    }

    this.peerConnections.set(userId, pc)
    return pc
  }

  /**
   * 创建 Offer
   */
  async createOffer(userId, onSignal) {
    const pc = this.peerConnections.get(userId) || this.createPeerConnection(userId, onSignal)
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    return offer
  }

  /**
   * 创建 Answer
   */
  async createAnswer(userId, offer, onSignal) {
    const pc = this.peerConnections.get(userId) || this.createPeerConnection(userId, onSignal)
    await pc.setRemoteDescription(new RTCSessionDescription(offer))
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    return answer
  }

  /**
   * 处理 Answer
   */
  async handleAnswer(userId, answer) {
    const pc = this.peerConnections.get(userId)
    if (pc) {
      await pc.setRemoteDescription(new RTCSessionDescription(answer))
    }
  }

  /**
   * 添加 ICE 候选
   */
  async addIceCandidate(userId, candidate) {
    const pc = this.peerConnections.get(userId)
    if (pc) {
      await pc.addIceCandidate(new RTCIceCandidate(candidate))
    }
  }

  /**
   * 移除对等连接
   */
  removePeerConnection(userId) {
    const pc = this.peerConnections.get(userId)
    if (pc) {
      pc.close()
      this.peerConnections.delete(userId)
    }
    this.remoteStreams.delete(userId)
    if (this.onRemoteStreamRemovedCallback) {
      this.onRemoteStreamRemovedCallback(userId)
    }
  }

  /**
   * 切换音频
   */
  toggleAudio(enabled) {
    if (this.localStream) {
      this.localStream.getAudioTracks().forEach((track) => {
        track.enabled = enabled
      })
    }
  }

  /**
   * 切换视频
   */
  toggleVideo(enabled) {
    if (this.localStream) {
      this.localStream.getVideoTracks().forEach((track) => {
        track.enabled = enabled
      })
    }
  }

  /**
   * 关闭所有连接
   */
  close() {
    // 关闭所有对等连接
    this.peerConnections.forEach((pc) => {
      pc.close()
    })
    this.peerConnections.clear()

    // 停止本地流
    if (this.localStream) {
      this.localStream.getTracks().forEach((track) => {
        track.stop()
      })
      this.localStream = null
    }

    // 清空远程流
    this.remoteStreams.clear()
  }

  /**
   * 设置远程流回调
   */
  onRemoteStream(callback) {
    this.onRemoteStreamCallback = callback
  }

  /**
   * 设置远程流移除回调
   */
  onRemoteStreamRemoved(callback) {
    this.onRemoteStreamRemovedCallback = callback
  }
}

export default WebRTCManager
