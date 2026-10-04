// 语音/视频通话状态机与信令协调（Pinia）
// 状态：idle -> calling(发起中) / ringing(来电) -> connected -> ended -> idle
// 信令帧 messageType 常量（与后端 Constants.WS_CALL_* 对齐，-10~-17）：
//   -10 invite  -11 accept  -12 reject  -13 signal  -14 hangup  -15 cancel  -16 busy  -17 join
import { defineStore } from 'pinia'
import * as WebRTC from '@/utils/WebRTC'
import { reduceCallFrame } from '@/utils/callFrameCore'
// 纯逻辑已抽离到 callStoreCore.mjs（node 中可测），本 store 只保留副作用编排。
// 抽离过程修正了两个既有缺陷：① 1800ms 陈旧定时器会清掉新通话的结束态
// ② reason 为空时 endReason 会残留上一通通话的旧原因。见 callStoreCore.mjs 顶部说明。
import {
  INITIAL_CALL_EPOCH,
  buildAcceptFrame,
  buildBusyFrame,
  buildHangupFrame,
  buildInviteFrame,
  buildRejectFrame,
  buildSignalFrame,
  endCallResetPatch,
  shouldAutoResetToIdle
} from '@/utils/callStoreCore'
import { useUserInfoStore } from '@/stores/UserInfoStore'

export const useCallStore = defineStore('call', {
  state: () => ({
    status: 'idle', // idle | calling | ringing | connected | ended
    callEpoch: INITIAL_CALL_EPOCH, // 通话身份代号，每次开始新通话自增；用于 1800ms 复位守卫区分「同一通」
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
      this.sendFrame(buildSignalFrame({
        callId: this.callId,
        peerId,
        signalType,
        sdp: payload && payload.sdp,
        candidate: payload && payload.candidate
      }))
    },
    // 发起通话（单聊 contactType=0 / 群聊 contactType=1）
    async startCall({ contactId, contactType, mediaType = 2 }) {
      if (this.status !== 'idle') return
      this.callEpoch += 1 // 新通话身份：使上一通遗留的 1800ms 定时器失效
      this.callType = contactType === 1 ? 2 : 1
      this.groupId = contactType === 1 ? contactId : null
      const toUserId = contactType === 0 ? contactId : null
      this.callId = crypto.randomUUID()
      this.mediaType = mediaType
      this.errorMsg = ''
      this.status = 'calling'
      const ok = await this.ensureLocalStream()
      if (!ok) { this.endCallLocal(''); return }
      this.sendFrame(buildInviteFrame({
        callId: this.callId,
        callType: this.callType,
        mediaType: this.mediaType,
        toUserId,
        groupId: this.groupId
      }))
    },
    // 接听来电
    async acceptCall() {
      if (!this.incoming) return
      const inv = this.incoming
      this.callEpoch += 1 // 接听也算进入一通新通话：作废上一通遗留的复位定时器
      this.callId = inv.callId
      this.callType = inv.callType
      this.mediaType = inv.mediaType
      this.groupId = inv.groupId
      this.iceServers = inv.iceServers || []
      this.errorMsg = ''
      const ok = await this.ensureLocalStream()
      if (!ok) { this.rejectCall(); return }
      this.members = [this.selfId(), inv.fromUserId]
      this.status = 'connected'
      this.incoming = null
      this.sendFrame(buildAcceptFrame(this.callId))
    },
    rejectCall() {
      const f = buildRejectFrame(this.callId)
      if (f) this.sendFrame(f)
      this.endCallLocal('已拒绝')
    },
    busy() {
      const f = buildBusyFrame(this.callId)
      if (f) this.sendFrame(f)
      this.endCallLocal('对方忙线')
    },
    hangup() {
      const f = buildHangupFrame(this.callId)
      if (f) this.sendFrame(f)
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
    // 分发后端下发的通话帧（状态转移在 utils/callFrameCore.mjs 纯函数中，本方法只执行副作用）
    async handleFrame(frame) {
      const { patch, effects } = reduceCallFrame({ status: this.status }, frame, this.selfId())
      if (Object.keys(patch).length) Object.assign(this, patch)
      for (const fx of effects) {
        if (fx.type === 'offer') {
          this.ensureLocalThenOffer(fx.peerId)
        } else if (fx.type === 'answer') {
          this.ensureLocalThenAnswer(fx.peerId, fx.sdp)
        } else if (fx.type === 'remoteAnswer') {
          WebRTC.handleRemoteAnswer(fx.peerId, fx.sdp)
        } else if (fx.type === 'remoteCandidate') {
          WebRTC.handleRemoteCandidate(fx.peerId, fx.candidate)
        } else if (fx.type === 'endCall') {
          this.endCallLocal(fx.reason)
        }
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
      Object.assign(this, endCallResetPatch(reason))
      // 捕获当前通话身份：若 1800ms 内开始了新通话（callEpoch 自增），
      // 本定时器不得把新通话的状态打回 idle。
      // 旧实现只判 `status === 'ended'`，在「A 结束后 1800ms 内 B 也结束」时
      // A 的定时器会误抹掉 B 的结束态。见 callStoreCore.mjs#shouldAutoResetToIdle。
      const epochAtSchedule = this.callEpoch
      setTimeout(() => {
        if (!shouldAutoResetToIdle({
          status: this.status,
          epochAtSchedule,
          currentEpoch: this.callEpoch
        })) return
        this.status = 'idle'
        this.endReason = ''
        this.callId = null
        this.groupId = null
      }, 1800)
    }
  }
})
