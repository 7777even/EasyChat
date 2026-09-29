// 通话信令帧 → 状态转移纯函数（无 DOM / 无 WebRTC / 无 Pinia 依赖）
// 由 useCallStore.handleFrame 调用并应用结果；scripts/verify/verify_call_core.mjs 直接 import 断言。
// 帧 messageType 与后端 Constants.WS_CALL_* 对齐：
//   -10 invite  -11 accept  -12 reject  -13 signal  -14 hangup  -15 cancel  -16 busy  -17 join
//
// 约定：返回 { patch, effects }
//   - patch：需要写入 store state 的键值（调用方 Object.assign）
//   - effects：需要执行的副作用描述，type 取值：
//       offer          → 向 peerId 发起 offer（full-mesh 新成员）
//       answer         → 应答 peerId 的 offer
//       remoteAnswer   → 把 sdp 交给 peerId 的 RTCPeerConnection
//       remoteCandidate→ 把 candidate 交给 peerId 的 RTCPeerConnection
//       endCall        → 本地结束通话并给出 reason

export const FRAME_INVITE = -10
export const FRAME_ACCEPT = -11
export const FRAME_REJECT = -12
export const FRAME_SIGNAL = -13
export const FRAME_HANGUP = -14
export const FRAME_CANCEL = -15
export const FRAME_BUSY = -16
export const FRAME_JOIN = -17

/**
 * 计算一帧通话信令带来的状态转移与副作用。
 * @param {{status: string}} state  仅需当前 status（calling/ringing 判定）
 * @param {{messageType: number}} frame 后端下发的信令帧
 * @param {string|number} selfId 当前用户 userId
 * @returns {{patch: object, effects: Array<{type: string}>}}
 */
export function reduceCallFrame(state, frame, selfId) {
  const patch = {}
  const effects = []
  const t = frame.messageType

  if (t === FRAME_INVITE) {
    // 被叫侧收到来电提醒
    patch.incoming = {
      callId: frame.callId,
      fromUserId: frame.fromUserId,
      callType: frame.callType,
      mediaType: frame.mediaType,
      iceServers: frame.iceServers
    }
    patch.callId = frame.callId
    patch.callType = frame.callType
    patch.mediaType = frame.mediaType
    patch.groupId = frame.groupId
    patch.iceServers = frame.iceServers || []
    patch.status = 'ringing'
  } else if (t === FRAME_ACCEPT || t === FRAME_JOIN) {
    // 后端把房间成员广播给所有成员
    patch.callId = frame.callId
    if (frame.iceServers) patch.iceServers = frame.iceServers
    if (frame.members) patch.members = frame.members
    const newMemberId = frame.newMemberId
    if (newMemberId === selfId) {
      // 我是新加入者：向房间内其他每位成员发 offer（full-mesh）
      patch.status = 'connected'
      for (const uid of frame.members || []) {
        if (uid !== selfId) effects.push({ type: 'offer', peerId: uid })
      }
    } else if (state.status === 'calling' || state.status === 'ringing') {
      // 他人加入：发起方/已接听方此前停留在 calling/ringing，
      // 此时已知有人接听，转为通话中并等待对方发来的 offer（经 CALL_SIGNAL 到达）
      patch.status = 'connected'
    }
  } else if (t === FRAME_SIGNAL) {
    // offer / answer / ICE candidate
    const peerId = frame.fromUserId
    if (frame.signalType === 'offer') {
      effects.push({ type: 'answer', peerId, sdp: frame.sdp })
    } else if (frame.signalType === 'answer') {
      effects.push({ type: 'remoteAnswer', peerId, sdp: frame.sdp })
    } else if (frame.signalType === 'candidate') {
      effects.push({ type: 'remoteCandidate', peerId, candidate: frame.candidate })
    }
  } else if (t === FRAME_HANGUP) {
    effects.push({ type: 'endCall', reason: '对方已挂断' })
  } else if (t === FRAME_CANCEL) {
    effects.push({ type: 'endCall', reason: '对方已取消' })
  } else if (t === FRAME_BUSY) {
    effects.push({ type: 'endCall', reason: '对方忙线' })
  } else if (t === FRAME_REJECT) {
    effects.push({ type: 'endCall', reason: '对方已拒绝' })
  }

  return { patch, effects }
}
