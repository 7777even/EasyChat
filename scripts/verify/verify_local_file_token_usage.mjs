#!/usr/bin/env node
/**
 * verify_local_file_token_usage.mjs —— 渲染层本地文件 URL 必须带 fileToken
 *
 * 背景（docs/system-facts.md §14 遗留 #29）：
 *   本地文件服务器自加固起对 `GET /file` 强制校验 `fileToken`（无令牌一律 403）。
 *   渲染层有 7 处拼该 URL。**漏加一处不会抛异常**，只表现为「图片/语音/视频静默全裂」，
 *   且在开发时极易被误判为样式或缓存问题 —— 这是本仓反复出现的失败形态。
 *   故用门禁把「必须经 withFileToken 封装」钉死。
 *
 * 检查项：
 *   ① 任何 `http://localhost:${...}/file` 形式的拼接，其所在语句必须被 withFileToken 包裹；
 *   ② 每个含该拼接的文件必须 import withFileToken；
 *   ③ withFileToken 的实现在 utils/localFileServer.js，不得被就地重写（防止有人
 *      在调用点内联一个「总是追加空 token」的假封装）。
 *   ④ 空转自检：必须扫到 ≥7 个命中点，否则报「扫描器可能失效」。
 *
 * 用法：node scripts/verify/verify_local_file_token_usage.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { readFileSync, readdirSync, statSync } from 'node:fs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const RENDERER = join(ROOT, 'easychat-front/src/renderer/src')

let pass = 0
let fail = 0
function check(name, cond, detail = '') {
    if (cond) { pass++; console.log(`   [PASS] ${name}`) } else {
        fail++; console.log(`   [FAIL] ${name}${detail ? ' | ' + detail : ''}`)
    }
}

function walk (dir, out = []) {
    for (const name of readdirSync(dir)) {
        if (name === '__tests__' || name === 'node_modules') continue
        const p = join(dir, name)
        if (statSync(p).isDirectory()) walk(p, out)
        else if (name.endsWith('.vue') || name.endsWith('.js')) out.push(p)
    }
    return out
}

// 命中形态：`http://localhost:${…}/file` —— 端口是插值，故必须写成 localhost:${ 才算命中。
// 刻意**不**匹配裸字符串 '/file?'，否则 utils/localFileServer.js 自身的注释与
// 门禁说明文字会把自己判成违规（AGENTS §2.1 第 7 条：先剥注释再匹配）。
const URL_RE = /https?:\/\/(?:localhost|127\.0\.0\.1):\$\{[^}]*\}\/file\?/g

const files = walk(RENDERER)
const hits = []
for (const f of files) {
    const text = readFileSync(f, 'utf8')
    // 剥掉行注释与块注释，避免「注释里引用旧写法」被误判。
    // ⚠ 行注释必须用负向后顾排除 `://` —— 否则 `http://localhost:` 里的 `//`
    //   被当成注释起点，整行连同 URL 一起被剥掉，扫描结果会是「0 命中」的空转通过。
    //   本脚本首次运行时正是踩中这一点，被下方「空转自检」当场拦下。
    const code = text
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(?<!:)\/\/.*$/gm, '')
    const m = code.match(URL_RE)
    if (m) hits.push({ file: f, count: m.length, code, text })
}

console.log(`== 扫描到 ${hits.length} 个文件、${hits.reduce((a, b) => a + b.count, 0)} 处本地文件 URL 拼接 ==`)

for (const h of hits) {
    const rel = h.file.slice(ROOT.length + 1).replace(/\\/g, '/')
    check(`${rel} 的本地文件 URL 经 withFileToken 封装`, h.code.includes('withFileToken('))
    check(`${rel} 已 import withFileToken`, /import\s*\{[^}]*withFileToken[^}]*\}\s*from\s*['"][^'"]*localFileServer['"]/.test(h.code))
}

console.log('== 封装本身未被就地绕过 ==')
const utilFile = join(RENDERER, 'utils/localFileServer.js')
const utilText = readFileSync(utilFile, 'utf8')
check('utils/localFileServer.js 存在且导出 withFileToken', /export const withFileToken\s*=/.test(utilText))
check('withFileToken 确实追加 fileToken 参数（而不是空实现）', /fileToken=\$\{encodeURIComponent/.test(utilText))
check('withFileToken 对非字符串入参原样返回（不做隐式拼接）', /typeof url\s*!==\s*'string'[\s\S]{0,80}return url/.test(utilText))

console.log('== 空转自检 ==')
const totalHits = hits.reduce((a, b) => a + b.count, 0)
// 2026-10-10 基线：ShowLocalImage / ChatMessageVoice / Moment / MomentDetail /
//                  UserMoment / GroupFile / ShowMedia 共 7 处
check(`命中点数量 ≥7（实际 ${totalHits}）`, totalHits >= 7,
    '扫描器可能失效（正则不匹配或文件被移走），此时上面的通过项不可信')

console.log('')
console.log(`结果：${pass} 通过 / ${fail} 失败`)
if (fail > 0) { console.log('[FAIL] 存在失败项'); process.exit(1) }
console.log('[PASS] 渲染层本地文件 URL 鉴权参数使用规范')
process.exit(0)