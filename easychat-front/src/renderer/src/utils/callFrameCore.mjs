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
    // 新来电必须清掉上一通残留的本地错误提示（如 ICE 协商失败）。
    //   startCall / accept 两条路径已各自清过，但**来电**不经它们 ——
    //   不清的话，上一通「当前网络环境不支持通话」会挂在这次来电界面上。
    patch.errorMsg = ''
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

// ── ICE 连接失败兜底（2026-10-13 新增，turn-deploy-and-key-mgmt C3）────
//
// 背景：`openspec/specs/voice-call/spec.md` 的 `Scenario: 连接失败兜底` 早已写明
//   「前端检测 RTCPeerConnection 失败，提示『当前网络/设备不支持，请改用语音消息』，
//     并清理本地资源」—— 但该 Scenario **从未实现**（实测渲染层无任何
//     iceconnectionstate 处理）。后果不是「少个提示」，而是：对称 NAT 且未配 TURN 时
//     **接通后无声**，不报错、不提示、服务端无日志。
//
// 为什么判据取 `iceconnectionstate === 'failed'` 而不加固定超时：
//   固定超时（如 10s 未连通即判失败）会**误杀**慢网络；
//   `failed` 是 WebRTC 的标准终态，语义精确。
//   代价：「disconnected 长时间不恢复」这一中间态不会被提示 —— 已登记为已知边界。

/** WebRTC 的 ICE 终态字面量（集中定义，避免各处硬编码字符串） */
export const ICE_STATE_FAILED = 'failed'

/** 面向用户的中文提示。**单一来源**：状态机与 UI 都读它，避免两处各写一份文案。 */
export const ICE_PROMPT = '当前网络环境不支持通话，请改用语音消息'

/** 已进入即不可再被 ICE 事件改写的状态（终态） */
const TERMINAL_STATUSES = ['ended', 'rejected', 'canceled', 'busy', 'icefailed']

/**
 * 把 `RTCPeerConnection.iceconnectionstate` 的变化折算成 store 转移。
 *
 * 纯函数：无 DOM / 无 WebRTC 实例，判定完全由入参决定，便于门禁与变异检验。
 *
 * @param {{status: string}|null} state 当前通话状态
 * @param {string|undefined} iceState `pc.iceconnectionstate`
 * @returns {{patch: object, effects: Array<{type: string, reason?: string}>}}
 */
export function reduceIceConnectionState (state, iceState) {
  const patch = {}
  const effects = []

  if (iceState !== ICE_STATE_FAILED) return { patch, effects }

  // 终态不可逆：通话已结束时再收到 failed 事件**不得**改写状态。
  //   否则用户挂断后残留的 PC 事件会把界面从「已结束」拉回「连接失败」。
  const status = state && state.status
  if (TERMINAL_STATUSES.includes(status)) return { patch, effects }

  // idle 表示当前根本没有通话，此时收到 failed 属异常噪声 —— 不凭空把状态拉进通话。
  if (status === 'idle') return { patch, effects }

  patch.status = 'icefailed'
  effects.push({ type: 'endCall', reason: ICE_PROMPT })
  return { patch, effects }
}
