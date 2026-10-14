#!/usr/bin/env node
/**
 * ICE 连接失败兜底：纯判定验证（2026-10-13，turn-deploy-and-key-mgmt C3 / T13）
 *
 * 背景：`openspec/specs/voice-call/spec.md` 的 `Scenario: 连接失败兜底` 写着
 * 「前端检测 RTCPeerConnection 失败，提示『当前网络/设备不支持，请改用语音消息』，
 *   并清理本地资源」—— **该 Scenario 自 2026-09-26 归档起从未实现**
 * （实测渲染层搜不到任何 iceconnectionstate 处理）。
 * 其后果不是「少个提示」，而是：对称 NAT 且未配 TURN 时，**接通后无声**，
 * 不报错、不提示、服务端无日志。
 *
 * 断言要点：
 *   - `failed` → 进入终态 `icefailed` 并触发 endCall（清理 + 提示）
 *   - **制衡用例**：`connected` / `completed` / `disconnected` **不得**判为失败
 *     （防「恒拒绝」假通过 —— 只断言 failed 会失败的话，判据写反或恒真都照样绿）
 *   - 通话已结束时再收到失败事件**不得**改写状态（终态不可逆）
 *   - 非通话态收到失败事件**不得**凭空把 idle 拉进通话
 *
 * 用法：node scripts/verify/verify_call_ice_fail.mjs
 */
import {
  reduceIceConnectionState,
  reduceCallFrame,
  FRAME_INVITE,
  ICE_STATE_FAILED,
  ICE_PROMPT
} from '../../easychat-front/src/renderer/src/utils/callFrameCore.mjs'

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

console.log('===== ICE 连接失败兜底验证 =====\n')

// ── 1. failed → 终态 + 触发清理/提示 ───────────────────────────────
console.log('=== 1. failed 判定 ===')
const failed = reduceIceConnectionState({ status: 'connected' }, ICE_STATE_FAILED)
check('iceconnectionstate=failed → status 置为 icefailed',
  failed.patch.status === 'icefailed', `实际 ${failed.patch.status}`)
check('failed → 产出 endCall 副作用（清理 peer 与本地流）',
  failed.effects.some((e) => e.type === 'endCall'))
check('endCall 携带面向用户的中文提示（不得是空串或英文）',
  failed.effects.some((e) => e.type === 'endCall' && typeof e.reason === 'string' &&
    e.reason.length > 0 && /[一-龥]/.test(e.reason)),
  '规格要求提示「当前网络/设备不支持，请改用语音消息」')
check('导出常量 ICE_PROMPT 与实际 reason 一致（避免两处各写一份文案）',
  failed.effects.some((e) => e.type === 'endCall' && e.reason === ICE_PROMPT))
check('ICE_PROMPT 本身是中文且非空',
  typeof ICE_PROMPT === 'string' && ICE_PROMPT.length > 0 && /[一-龥]/.test(ICE_PROMPT),
  ICE_PROMPT)

// ── 2. 制衡用例：正常状态**不得**判为失败 ─────────────────────────
console.log('\n=== 2. 制衡：正常 ICE 状态不得判失败（防恒拒绝）===')
for (const s of ['connected', 'completed', 'checking', 'new', 'disconnected']) {
  const r = reduceIceConnectionState({ status: 'connected' }, s)
  check(`iceconnectionstate=${s} → 不改状态、不产生 endCall`,
    !r.patch.status && !r.effects.some((e) => e.type === 'endCall'),
    `patch.status=${r.patch.status} effects=${r.effects.length}`)
}

// ── 3. 终态不可逆 ─────────────────────────────────────────────────
console.log('\n=== 3. 终态保护 ===')
for (const s of ['ended', 'rejected', 'canceled', 'busy', 'icefailed']) {
  const r = reduceIceConnectionState({ status: s }, ICE_STATE_FAILED)
  check(`已处于 ${s} 时收到 failed → 保持原状（终态不可逆）`,
    !r.patch.status && r.effects.length === 0,
    `patch.status=${r.patch.status} effects=${r.effects.length}`)
}

// ── 4. 非通话态不得凭空起通话 ─────────────────────────────────────
console.log('\n=== 4. 非通话态不得凭空起通话 ===')
for (const s of ['idle', 'calling', 'ringing']) {
  const r = reduceIceConnectionState({ status: s }, ICE_STATE_FAILED)
  if (s === 'idle') {
    check('idle 时收到 failed → 不得凭空把状态改成 icefailed',
      !r.patch.status, `实际 ${r.patch.status}`)
  } else {
    check(`${s} 时收到 failed → 判为失败（呼叫中失败同样要提示）`,
      r.patch.status === 'icefailed' && r.effects.some((e) => e.type === 'endCall'))
  }
}

// ── 5. 异常入参不得崩 ─────────────────────────────────────────────
console.log('\n=== 5. 异常入参 ===')
const bad = reduceIceConnectionState({ status: 'connected' }, undefined)
check('iceconnectionstate 为 undefined → 不判失败、不抛异常',
  !bad.patch.status && bad.effects.length === 0)
const nullState = reduceIceConnectionState(null, ICE_STATE_FAILED)
check('state 为 null → 不抛异常',
  nullState !== null && typeof nullState.patch === 'object')

// ── 6. 新来电清掉上一通残留的失败提示 ───────────────────────────────
console.log('\n=== 6. 残留提示不得带进新来电 ===')
const invite = reduceCallFrame({ status: 'idle' }, {
  messageType: FRAME_INVITE, callId: 'call-2', fromUserId: 'B', callType: 1, mediaType: 2
}, 'A')
check('INVITE → errorMsg 被清空（否则上一通的失败提示挂在本次来电界面）',
  invite.patch.errorMsg === '', `实际 ${JSON.stringify(invite.patch.errorMsg)}`)

// ── 汇总 ────────────────────────────────────────────────────────
const fail = results.filter(([, ok]) => !ok).length
console.log(`\n===== 结论：${results.length - fail}/${results.length} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
