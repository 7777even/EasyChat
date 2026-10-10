/**
 * 本地文件服务器 URL 的**唯一封装**（遗留 #29 加固）
 *
 * 背景：本地文件服务器自 403 起强制校验 `fileToken` 查询参数。此前渲染层有 7 处
 * 各自拼 `http://localhost:<port>/file?...`，若逐处手工追加令牌，**漏一处就是
 * 「图片/语音静默全裂」**——而这类失败不抛异常、只在界面上表现为空白，肉眼很难定位。
 * 故收敛到本文件：新接入点一律用 withFileToken()，门禁
 * `scripts/verify/verify_local_file_token_usage.mjs` 会扫出任何绕过封装的拼接。
 *
 * 令牌取自 globalInfoStore，与 localServerPort 同批由主进程下发
 * （见 ipc.js openWindow 与 Main.vue 的 getLocalStore 通路）。
 */

/**
 * 给已拼好的本地文件 URL 追加 fileToken。
 *
 * 令牌统一追加在**末尾**：既有测试断言的是 URL 前缀（如 `toContain('/file?fileId=F001')`），
 * 追加在末尾可保持其有效；同时也能让「令牌在最后一段」符合直觉。
 *
 * 端口或令牌尚未就绪时返回带空值的 URL —— 交由服务端返回 403，
 * 而不是在本地悄悄拼一个错的 URL，那会让失败原因更难排查。
 *
 * @param {string} url    已含端口与业务参数的完整 URL
 * @param {string} token  主进程下发的文件令牌
 */
export const withFileToken = (url, token) => {
  if (typeof url !== 'string' || !url) return url
  const sep = url.includes('?') ? '&' : '?'
  return `${url}${sep}fileToken=${encodeURIComponent(token ?? '')}`
}

/**
 * 一次性取到「端口 + 令牌」，供需要同时使用两者的调用点。
 * @param {{getInfo:(k:string)=>any}} globalInfoStore
 */
export const localFileServerInfo = (globalInfoStore) => ({
  port: globalInfoStore.getInfo('localServerPort'),
  token: globalInfoStore.getInfo('localFileServerToken')
})
