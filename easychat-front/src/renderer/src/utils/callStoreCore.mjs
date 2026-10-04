// 通话 store 的纯逻辑核心（无 Pinia / 无 WebRTC / 无 window 依赖）
//
// 为什么需要它（与 exportChatCore.mjs 同源动机）：
//   useCallStore.js 里混着**状态补丁计算**与**副作用执行**（WebRTC / window.ipcRenderer），
//   在 node 中无法 import → 编排逻辑完全不可测。
//   既有 verify_call_core.mjs 只覆盖 callFrameCore.mjs（帧 → 状态转移），
//   「store 收到转移后怎么落地」这段仍是黑箱。
//
// 本模块只放**纯计算**：
//   · 结束通话时的状态重置补丁
//   · 1800ms 自动复位守卫的判定
//   · 出站信令帧的报文构造
// 副作用（WebRTC.stopAll / sendFrame）仍留在 store 内，由本模块的结果驱动。
//
// ⚠ 本模块同时**修正了两个既有缺陷**（均由抽离过程暴露，改动已写入门禁）：
//   ① 陈旧定时器会清掉新通话的结束态（原守卫只看 status，无通话身份校验）
//   ② reason 为空时 endReason 保留上一次通话的旧值（导致新通话失败却显示旧原因）

/**
 * 通话身份代号：每次「开始一通新通话」自增。
 * 不用 callId 做身份标识，因为 callId 依赖外部生成（crypto.randomUUID / 后端下发），
 * 而 1800ms 守卫需要的是「本通话是否仍是当前通话」这一单调可靠的判据。
 */
export const INITIAL_CALL_EPOCH = 0

/**
 * 结束通话时需要写入 store 的状态补丁（纯计算）。
 *
 * ⚠ 与旧实现的差异：endReason **总是**被写入（旧实现仅在 reason 非空时写入，
 *   导致一通新通话因权限失败而以空 reason 结束时，会显示上一通通话的结束原因）。
 *
 * @param {string} reason 结束原因，空串表示「本地原因，无对外语义」
 * @returns {object} 待 Object.assign 到 store 的补丁
 */
export function endCallResetPatch (reason) {
  return {
    remoteStreams: {},
    localStream: null,
    incoming: null,
    members: [],
    isMuted: false,
    isCameraOff: false,
    endReason: reason || '',
    status: 'ended'
  }
}

/**
 * 1800ms 自动复位守卫：定时器到期时是否可以把状态从 ended 打回 idle。
 *
 * 旧实现只判断 `status === 'ended'`。这在「一通电话结束后 1800ms 内又来一通、
 * 且新通话也很快结束」时会出错：
 *   t=0    通话 A 结束 → 起定时器 A（1800ms）
 *   t=500  通话 B 开始
 *   t=600  通话 B 结束（同样 status='ended'）→ 起定时器 B（1800ms）
 *   t=1800 定时器 A 到期，看到 status 仍是 'ended' → 误判为「A 的收尾」，
 *          把 B 刚设置的 endReason 清空、B 的「结束态」被提前 600ms 抹掉
 * 修法：定时器捕获自己那通通话的 epoch，只有 epoch 未变（仍是同一通）才允许复位。
 *
 * @param {{status: string, epochAtSchedule: number, currentEpoch: number}} s
 * @returns {boolean} true 表示允许复位
 */
export function shouldAutoResetToIdle (s) {
  if (!s || s.status !== 'ended') return false
  return s.currentEpoch === s.epochAtSchedule
}

/**
 * 构造出站信令帧（messageType = -13 CALL_SIGNAL）。
 * 纯函数：只拼报文，不发送。
 *
 * @param {{callId: string, peerId: string, signalType: string, sdp?: string, candidate?: any}} p
 */
export function buildSignalFrame ({ callId, peerId, signalType, sdp, candidate }) {
  const msg = { messageType: -13, callId, toUserId: peerId, signalType }
  if (sdp) msg.sdp = sdp
  if (candidate) msg.candidate = candidate
  return msg
}

/** 构造发起通话帧（messageType = -10 CALL_INVITE）。单聊 toUserId 有值，群呼 groupId 有值。 */
export function buildInviteFrame ({ callId, callType, mediaType, toUserId, groupId }) {
  return { messageType: -10, callId, callType, mediaType, toUserId, groupId }
}

/** 构造接听帧（messageType = -11 CALL_ACCEPT）。 */
export function buildAcceptFrame (callId) {
  return { messageType: -11, callId }
}

/** 构造拒绝帧（messageType = -12 CALL_REJECT）。callId 为空时不发（与旧实现 `if (this.callId)` 一致）。 */
export function buildRejectFrame (callId) {
  return callId ? { messageType: -12, callId } : null
}

/** 构造挂断帧（messageType = -14 CALL_HANGUP）。 */
export function buildHangupFrame (callId) {
  return callId ? { messageType: -14, callId } : null
}

/** 构造忙线帧（messageType = -16 CALL_BUSY）。 */
export function buildBusyFrame (callId) {
  return callId ? { messageType: -16, callId } : null
}