#!/usr/bin/env node
/**
 * 通话状态机纯逻辑验证（无 DOM / 无 WebRTC，直接 import 真代码）
 *
 * 覆盖 engineering/retro/2026-09-26-voice-call.md 改进方案 #1 的场景：
 *   - A 发起 → B 接听 → A 收到 JOIN 且 newMemberId != A → calling 转 connected
 *     （历史缺陷：该分支漏判导致 UI 停在「呼叫中」，静态门禁抓不到）
 *   - C 中途加入 → A/B 收到 JOIN 不重复转态、C 收到 JOIN 对每个成员发 offer
 *   - 各结束帧给出正确 endReason，信令帧路由到正确副作用
 *
 * 用法：node scripts/verify/verify_call_core.mjs
 */
import {
  reduceCallFrame,
  FRAME_INVITE,
  FRAME_ACCEPT,
  FRAME_REJECT,
  FRAME_SIGNAL,
  FRAME_HANGUP,
  FRAME_CANCEL,
  FRAME_BUSY,
  FRAME_JOIN
} from '../../easychat-front/src/renderer/src/utils/callFrameCore.mjs'

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

const A = 'userA'
const B = 'userB'
const C = 'userC'
const CALL = 'call-1'
const ICE = [{ urls: 'stun:stun.l.google.com:19302' }]
const run = (status, frame, selfId) => reduceCallFrame({ status }, frame, selfId)

console.log('===== 通话状态机纯逻辑验证 =====\n')

// 1. 来电：被叫收到 INVITE → ringing + incoming 落齐
console.log('=== 1. INVITE（-10）===')
const inv = run('idle', {
  messageType: FRAME_INVITE,
  callId: CALL,
  fromUserId: A,
  callType: 1,
  mediaType: 2,
  groupId: null,
  iceServers: ICE
}, B)
check('status → ringing', inv.patch.status === 'ringing')
check('incoming.fromUserId = 发起方', inv.patch.incoming && inv.patch.incoming.fromUserId === A)
check('incoming.callId = 帧 callId', inv.patch.incoming && inv.patch.incoming.callId === CALL)
check('iceServers 落入 state', Array.isArray(inv.patch.iceServers) && inv.patch.iceServers.length === 1)
check('无副作用', inv.effects.length === 0)
const invNoIce = run('idle', { messageType: FRAME_INVITE, iceServers: undefined }, B)
check('iceServers 缺省 → 空数组（不落 undefined）', Array.isArray(invNoIce.patch.iceServers) && invNoIce.patch.iceServers.length === 0)

// 2. 核心回归：A 发起（calling）→ B 接听 → A 收到 JOIN(newMemberId=B) → connected
console.log('\n=== 2. 发起方收到他人 JOIN（历史缺陷回归）===')
const joinToA = run('calling', {
  messageType: FRAME_JOIN,
  callId: CALL,
  newMemberId: B,
  members: [A, B],
  iceServers: ICE
}, A)
check('calling → connected（缺陷场景）', joinToA.patch.status === 'connected', `got ${joinToA.patch.status}`)
check('members 更新为 [A,B]', Array.isArray(joinToA.patch.members) && joinToA.patch.members.join(',') === `${A},${B}`)
check('非新成员不发 offer（等对方 offer）', joinToA.effects.length === 0)
const joinRing = run('ringing', {
  messageType: FRAME_JOIN,
  callId: CALL,
  newMemberId: B,
  members: [A, B]
}, A)
check('ringing → connected', joinRing.patch.status === 'connected')
const joinConnected = run('connected', {
  messageType: FRAME_JOIN,
  callId: CALL,
  newMemberId: C,
  members: [A, B, C]
}, A)
check('已 connected 不产生重复转态', joinConnected.patch.status === undefined)
const joinEnded = run('ended', {
  messageType: FRAME_JOIN,
  callId: CALL,
  newMemberId: B,
  members: [A, B]
}, A)
check('ended 不被 JOIN 拉回 connected', joinEnded.patch.status === undefined)

// 3. 新成员视角：C 收到 JOIN → connected + 对每个成员发 offer
console.log('\n=== 3. 新成员收到 JOIN（full-mesh offer）===')
const joinToC = run('connected', {
  messageType: FRAME_JOIN,
  callId: CALL,
  newMemberId: C,
  members: [A, B, C]
}, C)
check('status → connected', joinToC.patch.status === 'connected')
check('对 A、B 各发一个 offer', joinToC.effects.length === 2 && joinToC.effects.every((e) => e.type === 'offer'))
check('offer 目标 = 除自己外的成员', joinToC.effects.map((e) => e.peerId).sort().join(',') === `${A},${B}`)
const joinAlone = run('calling', { messageType: FRAME_ACCEPT, callId: CALL, newMemberId: C, members: [C] }, C)
check('房内仅自己 → 0 个 offer', joinAlone.effects.length === 0)
check('ACCEPT(-11) 与 JOIN(-17) 同路径', joinAlone.patch.status === 'connected')

// 4. 信令路由
console.log('\n=== 4. SIGNAL（-13）副作用路由 ===')
const sigOffer = run('connected', { messageType: FRAME_SIGNAL, fromUserId: A, signalType: 'offer', sdp: 'v=0 offer' }, B)
check('offer → answer 副作用', sigOffer.effects.length === 1 && sigOffer.effects[0].type === 'answer' && sigOffer.effects[0].peerId === A && sigOffer.effects[0].sdp === 'v=0 offer')
const sigAnswer = run('connected', { messageType: FRAME_SIGNAL, fromUserId: A, signalType: 'answer', sdp: 'v=0 answer' }, B)
check('answer → remoteAnswer', sigAnswer.effects.length === 1 && sigAnswer.effects[0].type === 'remoteAnswer')
const sigCand = run('connected', { messageType: FRAME_SIGNAL, fromUserId: A, signalType: 'candidate', candidate: { candidate: 'cand' } }, B)
check('candidate → remoteCandidate', sigCand.effects.length === 1 && sigCand.effects[0].type === 'remoteCandidate')
check('信令不改 state', sigOffer.patch.status === undefined && Object.keys(sigOffer.patch).length === 0)
const sigUnknown = run('connected', { messageType: FRAME_SIGNAL, fromUserId: A, signalType: 'weird' }, B)
check('未知 signalType → 无副作用', sigUnknown.effects.length === 0)

// 5. 结束帧 → endReason
console.log('\n=== 5. 结束帧（-12/-14/-15/-16）===')
const ends = [
  [FRAME_HANGUP, '对方已挂断'],
  [FRAME_CANCEL, '对方已取消'],
  [FRAME_BUSY, '对方忙线'],
  [FRAME_REJECT, '对方已拒绝']
]
for (const [ft, reason] of ends) {
  const r = run('connected', { messageType: ft, callId: CALL }, B)
  check(`帧 ${ft} → endCall「${reason}」`, r.effects.length === 1 && r.effects[0].type === 'endCall' && r.effects[0].reason === reason)
}

// 6. 兜底：未知帧不崩、不改状态
console.log('\n=== 6. 兜底 ===')
const unknown = run('connected', { messageType: -99 }, B)
check('未知 messageType → 空 patch 空 effects', Object.keys(unknown.patch).length === 0 && unknown.effects.length === 0)
const nullMembers = run('calling', { messageType: FRAME_JOIN, callId: CALL, newMemberId: C, members: undefined }, C)
check('members 缺省不崩且发 0 个 offer', nullMembers.effects.length === 0 && nullMembers.patch.status === 'connected')

const passed = results.filter(([, ok]) => ok).length
console.log(`\n===== 结果: ${passed}/${results.length} PASS =====`)
if (passed !== results.length) {
  console.log('失败项:')
  results.filter(([, ok]) => !ok).forEach(([n]) => console.log('   - ' + n))
  process.exit(1)
}
