// 语音/视频通话状态机与信令协调（Pinia）
// 状态：idle -> calling(发起中) / ringing(来电) -> connected -> ended -> idle
// 信令帧 messageType 常量（与后端 Constants.WS_CALL_* 对齐，-10~-17）：
//   -10 invite  -11 accept  -12 reject  -13 signal  -14 hangup  -15 cancel  -16 busy  -17 join
import { defineStore } from 'pinia'
import * as WebRTC from '@/utils/WebRTC'
import { useUserInfoStore } from '@/stores/UserInfoStore'

export const useCallStore = defineStore('call', {
  state: () => ({
    status: 'idle', // idle | calling | ringing | connected | ended
    callId: null,
    callType: 1, // 1 单聊 2 群呼
    mediaType: 2, // 1 音频 2 音视频
    groupId: null,
    members: [], // 当前通话成员 userId 列表
    iceServers: [], // 后端随信令下发的 STUN/TURN
    remoteStreams: {}, // userId -> MediaStream（供浮窗视频网格渲染）
    localStream: null,
    incoming: null, // { callId, fromUserId, callType, mediaType, iceServers } 来电信息
    isMuted: false,
    isCameraOff: false,
    endReason: '', // 结束原因（对方挂断/拒绝/忙线等）
    errorMsg: '' // 本地错误（如设备权限被拒）
  }),
  actions: {
    sendFrame(msg) {
      if (window.ipcRenderer) window.ipcRenderer.send('sendCallFrame', msg)
    },
    selfId() {
      return useUserInfoStore().getInfo().userId
    },
    async ensureLocalStream() {
      try {
        this.localStream = await WebRTC.getLocalStream()
        return true
      } catch (e) {
        this.errorMsg = '无法获取摄像头/麦克风权限，请检查系统设置后重试'
        console.warn('getUserMedia 失败', e)
        return false
      }
    },
    onRemoteStream(peerId, stream) {
      this.remoteStreams[peerId] = stream
    },
    sendSignal(peerId, signalType, payload) {
      const msg = { messageType: -13, callId: this.callId, toUserId: peerId, signalType }
      if (payload && payload.sdp) msg.sdp = payload.sdp
      if (payload && payload.candidate) msg.candidate = payload.candidate
      this.sendFrame(msg)
    },
    // 发起通话（单聊 contactType=0 / 群聊 contactType=1）
    async startCall({ contactId, contactType, mediaType = 2 }) {
      if (this.status !== 'idle') return
      this.callType = contactType === 1 ? 2 : 1
      this.groupId = contactType === 1 ? contactId : null
      const toUserId = contactType === 0 ? contactId : null
      this.callId = crypto.randomUUID()
      this.mediaType = mediaType
      this.status = 'calling'
      const ok = await this.ensureLocalStream()
      if (!ok) { this.endCallLocal(''); return }
      this.sendFrame({
        messageType: -10,
        callId: this.callId,
        callType: this.callType,
        mediaType: this.mediaType,
        toUserId,
        groupId: this.groupId
      })
    },
    // 接听来电
    async acceptCall() {
      if (!this.incoming) return
      const inv = this.incoming
      this.callId = inv.callId
      this.callType = inv.callType
      this.mediaType = inv.mediaType
      this.groupId = inv.groupId
      this.iceServers = inv.iceServers || []
      const ok = await this.ensureLocalStream()
      if (!ok) { this.rejectCall(); return }
      this.members = [this.selfId(), inv.fromUserId]
      this.status = 'connected'
      this.incoming = null
      this.sendFrame({ messageType: -11, callId: this.callId })
    },
    rejectCall() {
      if (this.callId) this.sendFrame({ messageType: -12, callId: this.callId })
      this.endCallLocal('已拒绝')
    },
    busy() {
      if (this.callId) this.sendFrame({ messageType: -16, callId: this.callId })
      this.endCallLocal('对方忙线')
    },
    hangup() {
      if (this.callId) this.sendFrame({ messageType: -14, callId: this.callId })
      this.endCallLocal('已挂断')
    },
    async ensureLocalThenOffer(peerId) {
      try {
        await this.ensureLocalStream()
        const offer = await WebRTC.createOfferTo(peerId, this.iceServers, this.onRemoteStream, this.sendSignal)
        this.sendSignal(peerId, 'offer', { sdp: offer.sdp })
      } catch (e) {
        console.warn('发起 offer 失败', e)
      }
    },
    async ensureLocalThenAnswer(peerId, sdp) {
      try {
        await this.ensureLocalStream()
        const answer = await WebRTC.handleRemoteOffer(peerId, sdp, this.iceServers, this.onRemoteStream, this.sendSignal)
        this.sendSignal(peerId, 'answer', { sdp: answer.sdp })
      } catch (e) {
        console.warn('回 answer 失败', e)
      }
    },
    // 分发后端下发的通话帧
    async handleFrame(frame) {
      const t = frame.messageType
      const selfId = this.selfId()
      if (t === -10) {
        // CALL_INVITE：被叫侧收到来电提醒
        this.incoming = {
          callId: frame.callId,
          fromUserId: frame.fromUserId,
          callType: frame.callType,
          mediaType: frame.mediaType,
          iceServers: frame.iceServers
        }
        this.callId = frame.callId
        this.callType = frame.callType
        this.mediaType = frame.mediaType
        this.groupId = frame.groupId
        this.iceServers = frame.iceServers || []
        this.status = 'ringing'
      } else if (t === -11 || t === -17) {
        // CALL_ACCEPT / CALL_JOIN：后端把房间成员广播给所有成员
        this.callId = frame.callId
        if (frame.iceServers) this.iceServers = frame.iceServers
        if (frame.members) this.members = frame.members
        const newMemberId = frame.newMemberId
        if (newMemberId === selfId) {
          // 我是新加入者：向房间内其他每位成员发 offer（full-mesh）
          this.status = 'connected'
          for (const uid of (frame.members || [])) {
            if (uid !== selfId) this.ensureLocalThenOffer(uid)
          }
        } else {
          // 他人加入：发起方/已接听方此前停留在 calling/ringing，
          // 此时已知有人接听，转为通话中并等待对方发来的 offer（经 CALL_SIGNAL 到达）
          if (this.status === 'calling' || this.status === 'ringing') {
            this.status = 'connected'
          }
        }
      } else if (t === -13) {
        // CALL_SIGNAL：offer / answer / ICE candidate
        const peerId = frame.fromUserId
        if (frame.signalType === 'offer') {
          this.ensureLocalThenAnswer(peerId, frame.sdp)
        } else if (frame.signalType === 'answer') {
          WebRTC.handleRemoteAnswer(peerId, frame.sdp)
        } else if (frame.signalType === 'candidate') {
          WebRTC.handleRemoteCandidate(peerId, frame.candidate)
        }
      } else if (t === -14) {
        this.endCallLocal('对方已挂断')
      } else if (t === -15) {
        this.endCallLocal('对方已取消')
      } else if (t === -16) {
        this.endCallLocal('对方忙线')
      } else if (t === -12) {
        this.endCallLocal('对方已拒绝')
      }
    },
    toggleMute() {
      this.isMuted = !this.isMuted
      WebRTC.setMuted(this.isMuted)
    },
    toggleCamera() {
      this.isCameraOff = !this.isCameraOff
      WebRTC.setCameraOff(this.isCameraOff)
    },
    endCallLocal(reason) {
      WebRTC.stopAll()
      this.remoteStreams = {}
      this.localStream = null
      this.incoming = null
      this.members = []
      this.isMuted = false
      this.isCameraOff = false
      if (reason) this.endReason = reason
      this.status = 'ended'
      setTimeout(() => {
        if (this.status === 'ended') {
          this.status = 'idle'
          this.endReason = ''
        }
      }, 1800)
    }
  }
})
