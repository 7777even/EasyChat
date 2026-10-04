#!/usr/bin/env node
/**
 * verify_call_store_core.mjs —— 通话 store 编排逻辑的纯核心校验
 *
 * 背景（docs/system-facts.md §14 遗留 #7）：
 *   既有 verify_call_core.mjs 只覆盖 callFrameCore.mjs（**帧 → 状态转移**），
 *   而「store 拿到转移后怎么落地」这段（结束态重置、1800ms 自动复位守卫、
 *   出站报文构造）此前是黑箱——它与 Pinia / WebRTC / window.ipcRenderer 混在
 *   useCallStore.js 里，在 node 中根本无法 import。
 *
 *   本脚本 import 抽离出的纯核心 callStoreCore.mjs 做断言。
 *   抽离过程**修正了两个既有缺陷**（均由本门禁锁定，否则下次重构会改回去）：
 *     ① 陈旧定时器清掉新通话的结束态
 *     ② reason 为空时 endReason 残留上一通通话的旧原因
 *
 * 用法：node scripts/verify/verify_call_store_core.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { readFileSync } from 'node:fs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

// ⚠️ Windows 上必须转成 file:// URL 才能被 ESM loader 加载。
//   直接 import 绝对路径会抛 ERR_UNSUPPORTED_ESM_URL_SCHEME（Received protocol 'd:'），
//   门禁**根本没跑起来**就退出 —— 而此时若已有变异脚本，会把每次非 0 退出
//   都记成「捕获」，得到彻底的假通过（2026-10-04 实际踩中）。
const imp = async (rel) => import(pathToFileURL(join(ROOT, rel)).href)

const {
  INITIAL_CALL_EPOCH,
  endCallResetPatch,
  shouldAutoResetToIdle,
  buildSignalFrame,
  buildInviteFrame,
  buildAcceptFrame,
  buildRejectFrame,
  buildHangupFrame,
  buildBusyFrame
} = await imp('easychat-front/src/renderer/src/utils/callStoreCore.mjs')

let pass = 0
let fail = 0
function check (name, cond, detail = '') {
  if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
    fail++
    console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
  }
}

console.log('===== 通话 store 编排逻辑核心校验 =====\n')

// ── 1. 结束态重置补丁 ────────────────────────────────────────
console.log('=== 1. 结束通话的状态重置 ===')
const p = endCallResetPatch('对方已挂断')
check('清空 remoteStreams', JSON.stringify(p.remoteStreams) === '{}')
check('清空 localStream', p.localStream === null)
check('清空 incoming', p.incoming === null)
check('清空 members', Array.isArray(p.members) && p.members.length === 0)
check('复位静音标志', p.isMuted === false)
check('复位摄像头标志', p.isCameraOff === false)
check('status 置为 ended', p.status === 'ended')
check('写入结束原因', p.endReason === '对方已挂断')

// 缺陷②：reason 为空时必须清空，不能保留上一通通话的旧原因
const emptyReason = endCallResetPatch('')
check('【缺陷②】空 reason 时 endReason 被清空而非保留旧值',
  emptyReason.endReason === '',
  `实际得到 ${JSON.stringify(emptyReason.endReason)}；旧实现仅在 reason 非空时写入，` +
  '导致一通新通话因权限失败结束时显示上一通通话的原因')

// undefined 也必须清空（ensureLocalStream 失败路径传的是 ''，但防御性覆盖）
check('【缺陷②】reason 传 undefined 时同样清空',
  endCallResetPatch(undefined).endReason === '')

// 补丁必须自包含：漏字段会让通话残留上一次的状态
const EXPECTED_KEYS = [
  'remoteStreams', 'localStream', 'incoming', 'members',
  'isMuted', 'isCameraOff', 'endReason', 'status'
]
const missing = EXPECTED_KEYS.filter((k) => !(k in p))
check('补丁含全部重置字段（无遗漏，遗漏即状态残留）', missing.length === 0,
  `缺少：${missing.join(', ')}`)

// 不得越界清掉不该清的字段：members/iceServers 之外的通话上下文
check('补丁不误清 callType / mediaType',
  !('callType' in p) && !('mediaType' in p),
  '这两项是「下次通话的默认参数」语义，不应每次结束都重置')

// ── 2. 1800ms 自动复位守卫 ────────────────────────────────────
console.log('\n=== 2. 自动复位守卫（1800ms）===')
check('同一通通话且处于 ended → 允许复位',
  shouldAutoResetToIdle({ status: 'ended', epochAtSchedule: 1, currentEpoch: 1 }) === true)

// 缺陷①：陈旧定时器
//   t=0 A 结束起定时器A → t=500 B 开始(epoch 2) → t=600 B 结束起定时器B
//   → t=1800 定时器A 到期，此时 status 仍是 'ended'（属于 B），
//     旧实现只看 status 会误判为「A 的收尾」，把 B 的结束态提前抹掉
check('【缺陷①】定时器所属通话已被新通话取代 → 不得复位',
  shouldAutoResetToIdle({ status: 'ended', epochAtSchedule: 1, currentEpoch: 2 }) === false,
  '这是本次修复的核心缺陷：旧实现只判 status===\'ended\'，无通话身份校验')

check('已进入新通话（非 ended）→ 不得复位',
  shouldAutoResetToIdle({ status: 'connected', epochAtSchedule: 1, currentEpoch: 1 }) === false)
check('已进入新通话（calling）→ 不得复位',
  shouldAutoResetToIdle({ status: 'calling', epochAtSchedule: 2, currentEpoch: 2 }) === false)
check('已进入新通话（ringing）→ 不得复位',
  shouldAutoResetToIdle({ status: 'ringing', epochAtSchedule: 2, currentEpoch: 2 }) === false)
check('空入参不得抛异常（防御）',
  shouldAutoResetToIdle(null) === false && shouldAutoResetToIdle(undefined) === false)
check('缺字段不得抛异常（防御）',
  shouldAutoResetToIdle({}) === false)

// 身份代号必须是单调递增的初值起点，可被 store 自增
check('初始通话代号为 0（store 可直接自增）', INITIAL_CALL_EPOCH === 0)

// ── 3. 出站信令帧报文构造 ────────────────────────────────────
console.log('\n=== 3. 出站帧报文（与后端 Constants.WS_CALL_* 对齐）===')
const sig = buildSignalFrame({ callId: 'C1', peerId: 'U2', signalType: 'offer', sdp: '<sdp/>' })
check('signal 帧 messageType = -13', sig.messageType === -13)
check('signal 帧带 callId', sig.callId === 'C1')
check('signal 帧 toUserId 为对端', sig.toUserId === 'U2')
check('signal 帧带 signalType', sig.signalType === 'offer')
check('signal 帧带 sdp', sig.sdp === '<sdp/>')
check('signal 帧不带无意义的 candidate 键', !('candidate' in sig))

const sigCand = buildSignalFrame({ callId: 'C1', peerId: 'U2', signalType: 'candidate', candidate: { candidate: 'x' } })
check('signal 帧带 candidate', !!sigCand.candidate)

const sigEmpty = buildSignalFrame({ callId: 'C1', peerId: 'U2', signalType: 'offer' })
check('payload 为空时不写入 sdp/candidate 键',
  !('sdp' in sigEmpty) && !('candidate' in sigEmpty),
  '写成 undefined 也会被 JSON 序列化发出，白占带宽且可能让后端解析出 undefined')

// invite
const inv1 = buildInviteFrame({ callId: 'C1', callType: 1, mediaType: 2, toUserId: 'U2', groupId: null })
check('invite 帧 messageType = -10', inv1.messageType === -10)
check('invite 帧带 callType / mediaType', inv1.callType === 1 && inv1.mediaType === 2)
check('单聊 invite 带 toUserId', inv1.toUserId === 'U2')
const inv2 = buildInviteFrame({ callId: 'C2', callType: 2, mediaType: 2, toUserId: null, groupId: 'G1' })
check('群呼 invite 带 groupId 且 toUserId 为空', inv2.groupId === 'G1' && inv2.toUserId === null)

// 其余单键帧
check('accept 帧 messageType = -11', buildAcceptFrame('C1').messageType === -11)
check('reject 帧 messageType = -12', buildRejectFrame('C1').messageType === -12)
check('hangup 帧 messageType = -14', buildHangupFrame('C1').messageType === -14)
check('busy 帧 messageType = -16', buildBusyFrame('C1').messageType === -16)

// callId 为空时不发帧：旧实现是 `if (this.callId) sendFrame(...)`，
// 抽离后必须保留该守卫，否则会发出 callId=null 的无意义帧
check('callId 为空时 reject 帧返回 null（不发）', buildRejectFrame(null) === null)
check('callId 为空时 hangup 帧返回 null（不发）', buildHangupFrame(null) === null)
check('callId 为空时 busy 帧返回 null（不发）', buildBusyFrame(null) === null)

// ── 4. 与后端帧号的一致性（防止纯核心里写死了错的号）─────────
console.log('\n=== 4. 帧号与后端对齐 ===')
const frameCore = await imp('easychat-front/src/renderer/src/utils/callFrameCore.mjs')
check('signal -13 与 callFrameCore.FRAME_SIGNAL 一致',
  buildSignalFrame({ callId: 'x', peerId: 'y', signalType: 'offer' }).messageType === frameCore.FRAME_SIGNAL)
check('invite -10 与 callFrameCore.FRAME_INVITE 一致',
  buildInviteFrame({ callId: 'x', callType: 1, mediaType: 2, toUserId: 'y', groupId: null }).messageType === frameCore.FRAME_INVITE)
check('accept -11 与 callFrameCore.FRAME_ACCEPT 一致',
  buildAcceptFrame('x').messageType === frameCore.FRAME_ACCEPT)
check('reject -12 与 callFrameCore.FRAME_REJECT 一致',
  buildRejectFrame('x').messageType === frameCore.FRAME_REJECT)
check('hangup -14 与 callFrameCore.FRAME_HANGUP 一致',
  buildHangupFrame('x').messageType === frameCore.FRAME_HANGUP)
check('busy -16 与 callFrameCore.FRAME_BUSY 一致',
  buildBusyFrame('x').messageType === frameCore.FRAME_BUSY)

// ── 5. store 确实用上了纯核心（防止抽离后各写一份）──────────
console.log('\n=== 5. store 确实复用纯核心 ===')
const storeSrc = readFileSync(
  join(ROOT, 'easychat-front/src/renderer/src/stores/useCallStore.js'), 'utf8')
check('useCallStore import 了 callStoreCore', /from ['"]@\/utils\/callStoreCore['"]/.test(storeSrc))
check('useCallStore 用 endCallResetPatch 而非自行重置字段',
  /Object\.assign\(this, endCallResetPatch\(/.test(storeSrc))
check('useCallStore 用 shouldAutoResetToIdle 判定复位',
  /shouldAutoResetToIdle\(\{/.test(storeSrc))
check('useCallStore 捕获了定时器所属通话身份',
  /const epochAtSchedule = this\.callEpoch/.test(storeSrc))
check('useCallStore 用 buildSignalFrame 构造信令帧',
  /buildSignalFrame\(/.test(storeSrc))
check('useCallStore 用 buildInviteFrame 构造邀请帧',
  /buildInviteFrame\(/.test(storeSrc))
// 防回归：store 内不得再出现裸的 1800 守卫
check('useCallStore 不再内联裸的 `status === \'ended\'` 定时器守卫',
  !/setTimeout\(\(\)\s*=>\s*\{\s*if\s*\(\s*this\.status\s*===\s*['"]ended['"]/.test(storeSrc),
  '内联守卫即意味着又绕过了纯核心，缺陷①会复活')
// 帧号不得在 store 里裸写
check('useCallStore 不再裸写 messageType 字面量（统一走纯核心构造）',
  !/messageType:\s*-?\d+/.test(storeSrc))

// ── 汇总 ────────────────────────────────────────────────────
console.log(`\n===== 结论：${pass}/${pass + fail} 通过 =====`)
if (fail) {
  console.log(`失败 ${fail} 项`)
  process.exit(1)
}
process.exit(0)