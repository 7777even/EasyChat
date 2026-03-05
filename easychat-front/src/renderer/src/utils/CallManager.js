/**
 * 通话管理器
 */
import { ref } from 'vue'

class CallManager {
  constructor() {
    this.currentCall = ref(null)
    this.webrtcManager = null
    this.sendSignalCallback = null
  }

  /**
   * 设置发送信令回调
   */
  setSendSignalCallback(callback) {
    this.sendSignalCallback = callback
  }

  /**
   * 发起通话
   */
  startCall(callInfo) {
    this.currentCall.value = {
      callId: this.generateCallId(),
      callType: callInfo.callType, // 0-语音 1-视频
      isGroup: callInfo.isGroup || false,
      isIncoming: false,
      callName: callInfo.callName,
      toId: callInfo.toId,
      members: callInfo.members || [],
      status: 'calling'
    }

    // 发送呼叫信令
    this.sendSignal({
      signalType: 'call',
      callId: this.currentCall.value.callId,
      toId: callInfo.toId,
      callType: callInfo.callType,
      isGroup: callInfo.isGroup,
      members: callInfo.members
    })

    return this.currentCall.value
  }

  /**
   * 接收来电
   */
  receiveCall(signalData) {
    this.currentCall.value = {
      callId: signalData.callId,
      callType: signalData.callType,
      isGroup: signalData.isGroup || false,
      isIncoming: true,
      callName: signalData.fromUserNickName,
      toId: signalData.fromUserId,
      members: signalData.members || [],
      status: 'calling'
    }
  }

  /**
   * 接听通话
   */
  acceptCall(webrtcManager) {
    if (!this.currentCall.value) return

    this.webrtcManager = webrtcManager
    this.currentCall.value.status = 'connected'

    // 发送接听信令
    this.sendSignal({
      signalType: 'accept',
      callId: this.currentCall.value.callId,
      toId: this.currentCall.value.toId,
      isGroup: this.currentCall.value.isGroup,
      members: this.currentCall.value.members
    })

    // 如果是群组通话，需要与所有成员建立连接
    if (this.currentCall.value.isGroup) {
      this.connectToGroupMembers()
    } else {
      // 一对一通话，创建 offer
      this.createOffer(this.currentCall.value.toId)
    }
  }

  /**
   * 拒绝通话
   */
  rejectCall() {
    if (!this.currentCall.value) return

    this.sendSignal({
      signalType: 'reject',
      callId: this.currentCall.value.callId,
      toId: this.currentCall.value.toId,
      isGroup: this.currentCall.value.isGroup,
      members: this.currentCall.value.members
    })

    this.endCall()
  }

  /**
   * 挂断通话
   */
  hangupCall() {
    if (!this.currentCall.value) return

    this.sendSignal({
      signalType: 'hangup',
      callId: this.currentCall.value.callId,
      toId: this.currentCall.value.toId,
      isGroup: this.currentCall.value.isGroup,
      members: this.currentCall.value.members
    })

    this.endCall()
  }

  /**
   * 结束通话
   */
  endCall() {
    if (this.webrtcManager) {
      this.webrtcManager.close()
      this.webrtcManager = null
    }
    this.currentCall.value = null
  }

  /**
   * 处理信令
   */
  async handleSignal(signalData) {
    if (!this.webrtcManager) return

    const { signalType, fromUserId, data } = signalData

    switch (signalType) {
      case 'offer':
        // 收到 offer，创建 answer
        const answer = await this.webrtcManager.createAnswer(
          fromUserId,
          data,
          (signal) => this.sendSignal({ ...signal, callId: this.currentCall.value.callId })
        )
        this.sendSignal({
          signalType: 'answer',
          callId: this.currentCall.value.callId,
          toId: fromUserId,
          data: answer
        })
        break

      case 'answer':
        // 收到 answer
        await this.webrtcManager.handleAnswer(fromUserId, data)
        break

      case 'ice-candidate':
        // 收到 ICE 候选
        await this.webrtcManager.addIceCandidate(fromUserId, data)
        break

      case 'accept':
        // 对方接听
        if (this.currentCall.value) {
          this.currentCall.value.status = 'connected'
        }
        break

      case 'reject':
        // 对方拒绝
        this.endCall()
        break

      case 'hangup':
        // 对方挂断
        this.endCall()
        break
    }
  }

  /**
   * 创建 Offer
   */
  async createOffer(userId) {
    if (!this.webrtcManager) return

    const offer = await this.webrtcManager.createOffer(
      userId,
      (signal) => this.sendSignal({ ...signal, callId: this.currentCall.value.callId })
    )

    this.sendSignal({
      signalType: 'offer',
      callId: this.currentCall.value.callId,
      toId: userId,
      data: offer
    })
  }

  /**
   * 连接到群组成员
   */
  connectToGroupMembers() {
    if (!this.currentCall.value || !this.currentCall.value.members) return

    this.currentCall.value.members.forEach((memberId) => {
      // 不连接自己
      if (memberId !== this.getCurrentUserId()) {
        this.createOffer(memberId)
      }
    })
  }

  /**
   * 发送信令
   */
  sendSignal(signalData) {
    if (this.sendSignalCallback) {
      // 过滤掉空的 members 数组，避免后端解析错误
      const cleanedData = { ...signalData }
      if (cleanedData.members && cleanedData.members.length === 0) {
        delete cleanedData.members
      }
      this.sendSignalCallback(cleanedData)
    }
  }

  /**
   * 生成通话ID
   */
  generateCallId() {
    return `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
  }

  /**
   * 获取当前用户ID（需要从外部设置）
   */
  getCurrentUserId() {
    // 这个方法需要从外部设置当前用户ID
    return this.currentUserId
  }

  /**
   * 设置当前用户ID
   */
  setCurrentUserId(userId) {
    this.currentUserId = userId
  }
}

// 单例模式
const callManager = new CallManager()

export default callManager
