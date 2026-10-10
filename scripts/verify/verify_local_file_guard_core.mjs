#!/usr/bin/env node
/**
 * verify_local_file_guard_core.mjs —— 本地文件服务器守卫核心校验
 *
 * 背景（docs/system-facts.md §14 遗留 #29，已 PoC 实跑证实）：
 *   easychat-front/src/main/file.js 的 `GET /file` 上三个缺陷叠加：
 *     ① 零鉴权（expressServer 无任何 use() 中间件）；
 *     ② 路径穿越（getLocalFilePath 的 else 兜底是 `localFolder + "/" + fileId` 纯拼接，
 *        fileId 从未经过 path.join/normalize/startsWith，路由只校验非空）；
 *     ③ listen(port, cb) 未传 host → 实测绑定 0.0.0.0。
 *   叠加 file.js:329 固定的 `Access-Control-Allow-Origin: *`，
 *   PoC 实测 `partType=x&fileId=../../poc/token.txt` → HTTP 200 + ACAO:* + 文件明文原样返回。
 *
 *   file.js import electron，在 node 中无法 import → 守卫逻辑抽到
 *   src/main/localFileGuardCore.mjs，本脚本直接 import 断言。
 *
 * 本脚本除常规断言外，另含三道**防空转**措施（AGENTS §2.1 第 10 / 14 条）：
 *   ① 「必须有断言判否」自检 —— 若被测函数恒返回 true 或恒返回 false，
 *      上面两条方向的用例会互相矛盾并报错，汇总行也会打上 FAIL；
 *   ② 逐条打印 [PASS]/[FAIL]，不靠「N/N」汇总判读（4/4 与「3 捕获 1 漏网」在汇总上不可区分）；
 *   ③ 显式统计正例数与反例数，两者都必须 > 0，否则报「该断言空转通过」。
 *
 * 用法：node scripts/verify/verify_local_file_guard_core.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve, join, sep } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

// ⚠️ Windows 上必须转 file:// URL，否则 ESM loader 抛 ERR_UNSUPPORTED_ESM_URL_SCHEME，
//    门禁整体崩溃（而只判 exit != 0 的变异脚本会把这误记成「全部捕获」，2026-10-04 踩中）
const imp = async (rel) => import(pathToFileURL(join(ROOT, rel)).href)

const { isInsideRoot, assertInsideRoot, tokensMatch, generateFileToken } =
    await imp('easychat-front/src/main/localFileGuardCore.mjs')

let pass = 0
let fail = 0
const expectTrue = []
const expectFalse = []

function check(name, cond, detail = '') {
    if (cond) {
        pass++
        console.log(`   [PASS] ${name}`)
    } else {
        fail++
        console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
    }
}

// 根目录用 resolve 归一，避免 Windows 上末尾 '/' 与 '\' 混排影响判读。
// 这里刻意**不带**尾部斜杠——生产里 localFolder 是带尾斜杠的，
// isInsideRoot 内部会 resolve，两种写法都必须能过（见「根目录带尾斜杠」一组）。
const ROOT_DIR = resolve(sep + 'home' + sep + 'victim' + sep + '.easychat' + sep + 'fileStorage' + sep + 'U001')

console.log('== 1. 合法路径必须放行（正例）==')
const legit = [
    ['根下直接文件', ROOT_DIR + sep + '9001.png'],
    ['二级子目录（chat 按月分目录）', ROOT_DIR + sep + 'chat' + sep + '202401' + sep + '9001.webm'],
    ['avatar 分支', ROOT_DIR + sep + 'avatar' + sep + 'U002.png'],
    ['文件名前带点', ROOT_DIR + sep + 'tmp' + sep + '.hidden.txt'],
    ['文件名含 Unicode', ROOT_DIR + sep + 'moment' + sep + '图片1.png'],
]
for (const [name, p] of legit) {
    const r = isInsideRoot(ROOT_DIR, p)
    if (r) expectTrue.push(name)
    check(`放行：${name}`, r === true, `实际=${r} 路径=${p}`)
}

console.log('== 2. 越界路径必须拦截（反例）==')
const escapes = [
    ['单层上跳到 .easychat', ROOT_DIR + sep + '..' + sep + 'poc' + sep + 'token.txt'],
    ['双层上跳到用户家目录', ROOT_DIR + sep + '..' + sep + '..' + sep + '.ssh' + sep + 'id_rsa'],
    ['POSIX 斜杠写法（本核心用 sep 归一，须同样拦住）', ROOT_DIR + '/../../../etc/passwd'],
    ['上跳后重新向下（伪装成合法子路径）', ROOT_DIR + sep + 'chat' + sep + '..' + sep + '..' + sep + '..' + sep + 'x'],
    ['绝对路径直接指向根外', resolve(sep + 'etc' + sep + 'passwd')],
    ['根目录自身', ROOT_DIR],
    ['兄弟目录前缀相同（startsWith 陷阱）', ROOT_DIR + 'evil' + sep + 'x.png'],
    ['纯上跳无文件名', ROOT_DIR + sep + '..'],
]
for (const [name, p] of escapes) {
    const r = isInsideRoot(ROOT_DIR, p)
    if (!r) expectFalse.push(name)
    check(`拦截：${name}`, r === false, `实际=${r} 路径=${p}`)
}

console.log('== 3. 根目录带尾斜杠时行为一致 ==')
check('带尾斜杠的根可放行根下文件', isInsideRoot(ROOT_DIR + sep, ROOT_DIR + sep + '9001.png') === true)
check('带尾斜杠的根仍拦截上跳', isInsideRoot(ROOT_DIR + sep, ROOT_DIR + sep + '..' + sep + 'x') === false)
check('带尾斜杠时兄弟目录仍被拦截',
    isInsideRoot(ROOT_DIR + sep, ROOT_DIR + 'evil' + sep + 'x.png') === false)

console.log('== 4. 非法入参必须判否（不得抛异常）==')
const badInputs = [
    ['root 为空串', '', ROOT_DIR + sep + 'a.png'],
    ['root 为 null', null, ROOT_DIR + sep + 'a.png'],
    ['candidate 为空串', ROOT_DIR, ''],
    ['candidate 为 undefined', ROOT_DIR, undefined],
    ['candidate 为数字', ROOT_DIR, 12345],
    ['root 为数字', 999, ROOT_DIR + sep + 'a.png'],
]
for (const [name, root, cand] of badInputs) {
    let res
    let threw = false
    try { res = isInsideRoot(root, cand) } catch (e) { threw = true }
    if (!threw && res === false) expectFalse.push(name)
    check(`判否不抛：${name}`, threw === false && res === false, `threw=${threw} res=${res}`)
}

console.log('== 5. assertInsideRoot：越界必须抛错 ==')
let threwOk = false
try { assertInsideRoot(ROOT_DIR, ROOT_DIR + sep + '..' + sep + 'x') } catch (e) { threwOk = true }
check('assertInsideRoot 越界抛错', threwOk === true)
if (threwOk) expectFalse.push('assertInsideRoot 抛错')
check('assertInsideRoot 合法路径不抛且返回归一化绝对路径',
    (() => { try { return assertInsideRoot(ROOT_DIR, ROOT_DIR + sep + 'a.png') === resolve(ROOT_DIR + sep + 'a.png') } catch (e) { return false } })())

console.log('== 6. 文件令牌比对 ==')
const TOKEN = generateFileToken()
check('令牌为 64 位十六进制', /^[0-9a-f]{64}$/.test(TOKEN), `实际=${TOKEN}`)
check('两次生成互不相同', generateFileToken() !== generateFileToken())
check('相同令牌判定为真', tokensMatch(TOKEN, TOKEN) === true)
if (tokensMatch(TOKEN, TOKEN)) expectTrue.push('相同令牌')
const tokenNegatives = [
    ['不同令牌', generateFileToken()],
    ['少一位', TOKEN.slice(0, -1)],
    ['多一位', TOKEN + 'a'],
    ['大小写翻转', TOKEN.toUpperCase()],
    ['空串', ''],
    ['undefined', undefined],
    ['null', null],
    ['前缀相同但后缀不同', TOKEN.slice(0, 32) + generateFileToken().slice(32)],
]
for (const [name, provided] of tokenNegatives) {
    const r = tokensMatch(TOKEN, provided)
    if (!r) expectFalse.push('令牌：' + name)
    check(`拒绝：${name}`, r === false, `实际=${r}`)
}
check('**期望令牌为空时一律拒绝**（服务未启动不得开无令牌窗口）',
    tokensMatch('', TOKEN) === false && tokensMatch(null, TOKEN) === false && tokensMatch(undefined, TOKEN) === false)
if (tokensMatch('', TOKEN) === false) expectFalse.push('空期望令牌')

console.log('== 7. 空转自检（AGENTS §2.1 第 10/14 条）==')
check(`正例断言已生效（≥5 条，实际 ${expectTrue.length}）`, expectTrue.length >= 5)
check(`反例断言已生效（≥10 条，实际 ${expectFalse.length}）`, expectFalse.length >= 10,
    '若为 0，说明被测函数可能恒返回同一值，本轮结果不可信')
// 交叉验证：把同一组输入喂给两次相反的期望，若仍全过则说明断言根本没在看结果
check('交叉验证：合法路径与越界路径在同一实现下判定相反',
    legit.every(([, p]) => isInsideRoot(ROOT_DIR, p) === true) &&
    escapes.every(([, p]) => isInsideRoot(ROOT_DIR, p) === false))

console.log('')
console.log(`结果：${pass} 通过 / ${fail} 失败（正例 ${expectTrue.length} 条、反例 ${expectFalse.length} 条均已生效）`)
if (fail > 0) {
    console.log('[FAIL] 存在失败项')
    process.exit(1)
}
console.log('[PASS] 本地文件服务器守卫核心全部通过')
process.exit(0)