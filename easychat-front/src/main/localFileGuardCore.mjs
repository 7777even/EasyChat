// 本地文件服务器的**纯守卫核心**（无 electron / fs / db 依赖）
//
// 抽离理由（与 exportChatCore.mjs、dbSqlCore.mjs、callFrameCore.mjs 同一手法）：
//   修复涉及 src/main/file.js，而该模块 import 了 electron / fs-extra / ./db/* / ./store，
//   在 node 里 import 它会拉起 Electron —— 导致「路径越界判定」与「文件令牌比对」
//   这两段**安全关键**的逻辑完全不可测。搬到本文件后可被
//   scripts/verify/verify_local_file_guard_core.mjs 直接 import 校验（零新依赖，已入 CI）。
//
// 修复的缺陷（docs/system-facts.md §14 遗留 #29，已用 PoC 实跑证实）：
//   ① getLocalFilePath 对 fileId 从未做过越界判定，partType 落到 else 兜底时
//      `localFolder + "/" + fileId` 是纯字符串拼接 → ../../ 可跳出用户数据目录；
//   ② GET /file 无任何鉴权，且响应固定 Access-Control-Allow-Origin: *；
//   ③ expressServer.listen(port, cb) 未传 host → 默认绑 0.0.0.0（实测）。
//
// ⚠️ 本文件只做「纯判定」，不碰 IO —— 拼路径与读盘仍留在 file.js。

import { resolve, sep } from 'node:path'
import { timingSafeEqual, randomBytes } from 'node:crypto'

/**
 * 判断 candidate 是否位于 root 目录**之内**。
 *
 * 判定边界（显式定义，不用「向上 N 个字符」这类经验窗口）：
 *   令 r = resolve(root)、c = resolve(candidate)，判定为真当且仅当
 *   `c !== r` 且 `c.startsWith(r + sep)`。
 *
 * 三个容易写错的点，均有对应用例：
 *   ① `c !== r`：root 自身是目录，不是可返回的文件；
 *   ② 必须拼 `sep` 再比：否则 root=`...\U001` 会把 `...\U001evil\x` 误判为「在内部」
 *      （这是 startsWith 类判定的经典漏洞，本仓历史上没有，但新写这段代码必须防住）；
 *   ③ 先 resolve：`localFolder` 结尾自带 `/`，而候选串是拼接产物（含 `\` 与 `/` 混排），
 *      不归一化则 `../` 根本不会被折叠，越界判定会静默失效。
 *
 * ⚠️ 已知局限（如实登记，不假装覆盖）：resolve 不解析符号链接，
 *    故若 root 内部存在指向外部的软链，仍可经该链接读到外部文件。
 *    本项目该目录由应用自建自写，不接受外部放置软链，故按可接受处理；
 *    若将来允许用户自选目录且目录内容不可控，须改用 fs.realpathSync 做实路径判定
 *    （注意目标文件在下载前可能尚不存在，需对父目录取 realpath）。
 *
 * @param {string} root      受限根目录（绝对路径）
 * @param {string} candidate 待判定路径（绝对路径或相对 root 拼接出的路径）
 * @returns {boolean} true 表示**安全**，位于 root 之内
 */
export const isInsideRoot = (root, candidate) => {
    if (typeof root !== 'string' || !root) return false
    if (typeof candidate !== 'string' || !candidate) return false
    const r = resolve(root)
    const c = resolve(candidate)
    if (c === r) return false
    return c.startsWith(r + sep)
}

/**
 * 同 isInsideRoot，但越界时抛错。
 * 路由层据此返回 400；调用方不应吞掉该异常（吞掉即等于把越界放行）。
 */
export const assertInsideRoot = (root, candidate) => {
    if (!isInsideRoot(root, candidate)) {
        throw new Error('路径越界：目标不在允许访问的目录内')
    }
    return resolve(candidate)
}

/**
 * 常量时间比对文件令牌。
 *
 * 为什么不用 `===`：令牌是唯一凭证，比较耗时随首个差异位置变化，
 * 攻击者理论上可据此逐字节爆破。代价只是一次 timingSafeEqual，没有理由省。
 *
 * 注意：期望值为空时**一律判否**。本地服务尚未启动（令牌未生成）时若放行，
 * 等于开一个「无令牌也能读」的窗口。
 */
export const tokensMatch = (expected, provided) => {
    if (typeof expected !== 'string' || !expected) return false
    if (typeof provided !== 'string' || !provided) return false
    const a = Buffer.from(expected, 'utf8')
    const b = Buffer.from(provided, 'utf8')
    if (a.length !== b.length) return false
    return timingSafeEqual(a, b)
}

/** 生成文件令牌：32 字节 CSPRNG，十六进制编码（64 字符） */
export const generateFileToken = () => randomBytes(32).toString('hex')
