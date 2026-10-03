#!/usr/bin/env node
/**
 * verify_export_chat_core.mjs —— 聊天记录导出纯逻辑核心校验
 *
 * 背景：src/main/exportChat.js 原先 import 了 electron / fs / db / store，
 *   在 node 里 import 会直接失败 → 其内部那些函数**完全不可测**。
 *   其中 {@code csvCell} 的「以 = + - @ 开头前置单引号」是一条**安全控制**：
 *   聊天内容来自**对方**，载荷 `=cmd|'/c calc'!A1` 若原样进 CSV，
 *   受害者用 Excel 打开导出的聊天记录时就会执行。属可被联系人触发的真实攻击面，
 *   此前**零测试**。
 *
 *   2026-10-03 把纯计算抽离到 src/main/exportChatCore.mjs（与 utils/virtualListCore.mjs、
 *   utils/callFrameCore.mjs 同一手法），本脚本直接 import 该核心做断言，零新依赖。
 *
 * 退出码：0 全通过 / 1 有失败项
 */
import {
  csvCell,
  buildContent,
  buildTxt,
  buildCsv,
  buildBackupTxt,
  buildBackupCsv,
  safeFileName,
  buildDefaultPath,
  buildBackupDefaultPath,
  normalizeCloudRow,
  sortRowsBySendTime,
  contactTypeLabel,
  formatTime
} from '../../easychat-front/src/main/exportChatCore.mjs'

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

console.log('===== 聊天记录导出纯逻辑核心校验 =====\n')

// ── 1. CSV 公式注入防护（安全控制，最高优先级） ───────────────
console.log('=== 1. CSV 公式注入防护（安全） ===')
for (const ch of ['=', '+', '-', '@']) {
  const out = csvCell(`${ch}cmd|'/c calc'!A1`)
  check(`以 ${ch} 开头的内容被前置单引号中和`, out.startsWith(`"'${ch}`), out)
}
// 这些字符是 Excel 公式的四种触发前缀，缺一即留缺口
check('四种触发前缀 = + - @ 全部覆盖',
  ['=', '+', '-', '@'].every((c) => csvCell(c + 'x').startsWith(`"'${c}`)))
// ⚠ 必须覆盖 **空白前缀**：Excel 会忽略前导空白后再解释公式，
//    只判首字符的防护会被「␣=cmd|...」「⇥=cmd|...」绕过（OWASP CSV Injection）。
check('前导空格后仍是公式被中和', csvCell(' =1+1') === '"\' =1+1"', csvCell(' =1+1'))
check('前导 Tab 后仍是公式被中和', csvCell('\t=1+1') === '"\'\t=1+1"', csvCell('\t=1+1'))
check('多个前导空白同样被中和', csvCell('   =1+1').includes("'"), csvCell('   =1+1'))
check('仅含空白的单元格不加引号（无需中和不误伤）',
  !csvCell('   ').startsWith('"\' '), csvCell('   '))
// 不该被误伤：普通文本不应被加引号
check('普通文本不被误加单引号', csvCell('你好') === '"你好"', csvCell('你好'))
check('不以触发字符开头的普通文本原样', csvCell('a=b') === '"a=b"', csvCell('a=b'))
check('含触发字符但不在开头不加引号', csvCell('a=b') === '"a=b"' && csvCell('1+1=2') === '"1+1=2"')

// ── 2. CSV 基本转义 ────────────────────────────────────────
console.log('\n=== 2. CSV 转义 ===')
check('双引号翻倍', csvCell('a"b') === '"a""b"', csvCell('a"b'))
check('换行转空格（防行注入）', !csvCell('a\nb').includes('\n'), csvCell('a\nb'))
check('CRLF 同样转空格', !csvCell('a\r\nb').includes('\r'), csvCell('a\r\nb'))
check('null / undefined 转空串', csvCell(null) === '""' && csvCell(undefined) === '""')
check('数字正常转字符串', csvCell(123) === '"123"')

// ── 3. 内容降级（媒体消息文本化无意义） ─────────────────────
console.log('\n=== 3. 媒体消息降级展示 ===')
check('图片消息 → [图片]', buildContent({ message_type: 5, file_type: 0 }) === '[图片]')
check('视频消息 → [视频]', buildContent({ message_type: 5, file_type: 1 }) === '[视频]')
check('文件消息 → [文件] 文件名', buildContent({ message_type: 5, file_type: 2, file_name: 'a.pdf' }) === '[文件] a.pdf')
check('未知 file_type → [文件]', buildContent({ message_type: 5, file_type: 99 }) === '[文件]')
check('文本消息原样', buildContent({ message_type: 2, message_content: 'hi' }) === 'hi')
check('文本消息 null → 空串', buildContent({ message_type: 2, message_content: null }) === '')

// ── 4. 文件名净化（Windows 非法字符 / 路径分隔） ────────────
console.log('\n=== 4. 文件名净化 ===')
check('斜杠被替换（防目录穿越）', safeFileName('a/b') === 'a_b', safeFileName('a/b'))
check('反斜杠被替换', safeFileName('a\\b') === 'a_b', safeFileName('a\\b'))
check('Windows 非法字符集全部替换',
  safeFileName('a:b*c?d"e<f>g|h') === 'a_b_c_d_e_f_g_h', safeFileName('a:b*c?d"e<f>g|h'))
check('空名回落 chat', safeFileName('') === 'chat' && safeFileName(null) === 'chat')
check('超长名截断到 50', safeFileName('x'.repeat(200)).length === 50)
check('默认路径含会话名与 csv 后缀', buildDefaultPath('张三', 'csv').includes('张三') &&
  buildDefaultPath('张三', 'csv').endsWith('.csv'))
check('非 csv 回落 txt 后缀', buildDefaultPath('张三', 'txt').endsWith('.txt'))
check('备份默认名固定前缀', buildBackupDefaultPath('csv').startsWith('EasyChat-备份-'))

// ── 5. 云端行归一化（camelCase → snake_case） ───────────────
console.log('\n=== 5. 云端行归一化 ===')
const cloud = normalizeCloudRow({
  messageId: 1, messageType: 2, messageContent: 'c',
  sendUserId: 'U1', sendUserNickName: 'n', sendTime: 100,
  fileType: 0, fileName: 'f'
})
check('messageId → message_id', cloud.message_id === 1)
check('sendUserNickName → send_user_nick_name', cloud.send_user_nick_name === 'n')
check('fileType → file_type', cloud.file_type === 0)
check('无多余 camelCase 残留键',
  !Object.keys(cloud).some((k) => /[A-Z]/.test(k)), Object.keys(cloud).join(','))
check('键集合与本地行形状一致',
  ['message_id', 'message_type', 'message_content', 'send_user_id',
    'send_user_nick_name', 'send_time', 'file_type', 'file_name']
    .every((k) => k in cloud))

// ── 6. 排序（服务端 desc 分页返回 → 导出需升序） ────────────
console.log('\n=== 6. 按发送时间升序 ===')
const rows = [{ send_time: 300 }, { send_time: 100 }, { send_time: 200 }]
const sorted = sortRowsBySendTime(rows)
check('升序排列正确', sorted.map((r) => r.send_time).join(',') === '100,200,300',
  sorted.map((r) => r.send_time).join(','))
check('不修改入参数组（不产生副作用）',
  rows.map((r) => r.send_time).join(',') === '300,100,200')
check('字符串时间戳可排序（Number 强转）',
  sortRowsBySendTime([{ send_time: '200' }, { send_time: '100' }])
    .map((r) => r.send_time).join(',') === '100,200')
check('空数组不报错', sortRowsBySendTime([]).length === 0)

// ── 7. 整文组装 ────────────────────────────────────────────
console.log('\n=== 7. TXT / CSV 组装 ===')
const sample = [
  { message_id: 1, message_type: 2, message_content: '你好', send_user_id: 'U1', send_user_nick_name: '张三', send_time: 1700000000000 },
  { message_id: 2, message_type: 5, file_type: 2, file_name: 'a.pdf', send_user_id: 'U2', send_user_nick_name: '李四', send_time: 1700000001000 }
]
const txt = buildTxt(sample, '与张三的会话')
check('TXT 含会话名', txt.includes('与张三的会话'))
check('TXT 含消息条数', txt.includes('消息条数：2'))
check('TXT 含昵称与内容', txt.includes('张三: 你好'))
check('TXT 媒体消息降级为占位符', txt.includes('[文件] a.pdf'))

const csv = buildCsv(sample)
check('CSV 带 UTF-8 BOM', csv.charCodeAt(0) === 0xfeff, 'code=' + csv.charCodeAt(0))
check('CSV 含表头', csv.includes('消息ID') && csv.includes('发送人ID'))
check('CSV 行数 = 表头 + 消息数', csv.split('\r\n').length === 3, csv.split('\r\n').length)
check('CSV 用 CRLF 分行', csv.includes('\r\n'))

// ── 8. 跨会话备份 ──────────────────────────────────────────
console.log('\n=== 8. 跨会话备份组装 ===')
const groups = [
  { sessionId: 'G1', title: '群A', contactType: 1, rows: [sample[0]] },
  { sessionId: 'U1', title: '', contactType: 0, rows: [sample[1]] }
]
check('contactType 1 → 群聊', contactTypeLabel(1) === '群聊')
check('contactType 0 → 单聊', contactTypeLabel(0) === '单聊')
const bTxt = buildBackupTxt(groups, 2)
check('备份 TXT 含会话数与总条数', bTxt.includes('会话数：2') && bTxt.includes('消息条数：2'))
check('备份 TXT 空标题回落 sessionId', bTxt.includes('会话：U1'))
const bCsv = buildBackupCsv(groups)
check('备份 CSV 表头增加「会话」「会话类型」',
  bCsv.includes('会话') && bCsv.includes('会话类型'))
check('备份 CSV 含群聊标签', bCsv.includes('群聊') && bCsv.includes('单聊'))

// ── 9. 时间格式化边界 ──────────────────────────────────────
console.log('\n=== 9. 时间格式化 ===')
check('空值 → 空串', formatTime(null) === '' && formatTime(0) === '')
check('非法时间戳 → 空串（不抛 Invalid Date）', formatTime('abc') === '', formatTime('abc'))
check('合法时间戳产出 YYYY-MM-DD 形状', /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(formatTime(1700000000000)),
  formatTime(1700000000000))
check('字符串时间戳可格式化', formatTime('1700000000000').length === 19)

// ── 汇总 ────────────────────────────────────────────────────
const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach(([n]) => console.log('  - ' + n))
  process.exit(1)
}
process.exit(0)