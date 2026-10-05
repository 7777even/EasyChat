/**
 * 群聊 @ 提及判定核心（纯函数）
 *
 * 抽离自 `MessageSend.vue`（2026-10-04，openspec `2026-10-04-at-mention-pure-core`）。
 *
 * <p><b>为何要抽</b>：原先这些判定与 DOM / store / `proxy.Request` 深度缠绕，
 * 在 node 中无法 import，故**零自动化覆盖**；而这段逻辑恰是本项目缺陷密度最高的形态
 * ——「条件漏一项 / 判定与实际不符 → 静默失效」。抽离后门禁与 vitest 均可直接验证。
 *
 * <p><b>边界</b>：本模块**只放判定，不放副作用**。
 * 不 import `electron` / `window` / `axios` / store —— 这正是能被 node 直接 import 的前提。
 * 以下留在组件内（有副作用，不可抽）：`nextTick` 光标复位、`proxy.Message.warning`、
 * `proxy.Request` 拉成员、`closeAtPopover`、`watch` 重置。
 */

/** @所有人 的正文文本。写入 `extraData.atAll` 的判定也依赖它。 */
export const AT_ALL_TEXT = '@所有人'

/** 群主 */
export const AT_ALL_ROLE = 0

/** 管理员 */
export const AT_ADMIN_ROLE = 1

/** 仅群聊出现 @ 按钮 */
export const GROUP_CONTACT_TYPE = 1

/**
 * @ 正文中匹配用户 ID 的正则。
 *
 * ⚠️ **本轮刻意不动**：格式 `@U<字母数字>` 是**跨端契约** ——
 *   服务端 `ExtraDataTools.isAtAll` 与 `MessageSendDto.atUserIds` 消费同一形态，
 *   收紧需前后端协同 + 存量数据评估。
 *   已知误命中：`@Ubuntu` 会被提取为 `buntu`。已登记为独立 Change，**不做假覆盖**。
 *
 *   抽离前该正则被**复制了两份**（`buildExtraData` 与 `buildAtUserIds`），
 *   且两者去重口径已分叉。现只此一份。
 */
const AT_USER_ID_PATTERN = /@(U[A-Za-z0-9]+)/g

/**
 * 本人群角色是否允许 @所有人。
 *
 * @param {number|null|undefined} role 0 群主 / 1 管理员 / 2 成员
 * @returns {boolean}
 */
export function canAtAll (role) {
  return role === AT_ALL_ROLE || role === AT_ADMIN_ROLE
}

/**
 * 角色文案。
 *
 * @param {number|null|undefined} role
 * @returns {string} 未知值兜底为「成员」
 */
export function roleText (role) {
  return { 0: '群主', 1: '管理员', 2: '成员' }[role] || '成员'
}

/**
 * 按关键词过滤群成员。
 *
 * ⚠️ **当前仅匹配 `contactName`**（= 抽离前的实际行为）。
 *   面板「显示」用的是 `contactName || userId`，故未设昵称者以 userId 呈现却搜不到。
 *   该不一致由 openspec 的「群成员搜索与显示口径一致」Requirement 覆盖，
 *   在 T3 阶段一并修复并加变异用例。
 *
 * @param {Array|null|undefined} list
 * @param {string|null|undefined} keyword
 * @returns {Array}
 */
export function filterMembers (list, keyword) {
  const kw = (keyword || '').trim().toLowerCase()
  if (!kw) {
    return list || []
  }
  return (list || []).filter((item) => (item.contactName || '').toLowerCase().includes(kw))
}

/**
 * 在光标区间处插入文本（**纯字符串操作**，不含光标复位等 DOM 副作用）。
 *
 * <p>抽离前 `insertAtText` 把 DOM 读取、字符串拼接、`nextTick` 光标复位写在一起，
 * 字符串那部分因此完全不可测。拆开后：拼接进本函数，`nextTick` 留在组件。
 *
 * <p>`start > end` 是防御性归一化：DOM 本身保证 `selectionStart <= selectionEnd`。
 * 不归一化的话 `slice(0,3) + text + slice(1)` 会把选区内的文本**复制一份**。
 *
 * @param {string} content 当前正文
 * @param {string} text 待插入文本
 * @param {number} start 选区起
 * @param {number} end 选区止
 * @returns {{content: string, cursor: number}} 新正文与插入后的光标位置
 */
export function spliceAtText (content, text, start, end) {
  const src = content || ''
  const len = src.length
  const clamp = (n) => Math.max(0, Math.min(Number.isFinite(n) ? n : len, len))
  const from = clamp(Math.min(start, end))
  const to = clamp(Math.max(start, end))
  return {
    content: src.slice(0, from) + text + src.slice(to),
    cursor: from + text.length
  }
}

/**
 * 从正文提取被 @ 的用户 ID 集合（**去重**，返回数组）。
 *
 * @param {string|null|undefined} messageContent
 * @returns {string[]}
 */
export function extractAtUserIds (messageContent) {
  if (!messageContent) {
    return []
  }
  const matched = messageContent.match(AT_USER_ID_PATTERN)
  if (!matched || matched.length === 0) {
    return []
  }
  return Array.from(new Set(matched.map((item) => item.substring(1))))
}

/**
 * 构造 `MessageSendDto.atUserIds` 字段值（去重后逗号连接）。
 *
 * @param {string|null|undefined} messageContent
 * @returns {string|null} 无匹配时返回 null
 */
export function buildAtUserIdsField (messageContent) {
  const ids = extractAtUserIds(messageContent)
  return ids.length > 0 ? ids.join(',') : null
}

/**
 * 构造消息的 `extraData` JSON 串。
 *
 * 三者共存：引用信息、@ 提及、@所有人。按需合并，避免引用消息丢失 @ 标记。
 *
 * ⚠️ **两处已知缺陷，本函数当前保持抽离前的行为**（T3 阶段修复）：
 *   ① `extraData.atUserIds` **不去重**（而 `buildAtUserIdsField` 去重）——两处口径已分叉；
 *   ② `atAll` 的写入条件**不叠加角色权限**（`atAllEnabled || 正文含 @所有人`），
 *      故普通成员手工键入 `@所有人` 也会产出 `atAll: true`，
 *      随后被服务端 `checkGroupRole` 以 `CODE_2305` 拒绝 → **整条消息发送失败**。
 *
 * @param {object} opts
 * @param {object|null} [opts.quoteInfo] 引用信息
 * @param {number} opts.contactType 1 群聊 / 0 单聊
 * @param {string} [opts.messageContent]
 * @param {boolean} [opts.atAllEnabled] 面板是否勾选了「@所有人」
 * @param {number} [opts.role] 本人群角色（T3 起参与 atAll 判定）
 * @returns {string|null} 无任何附加信息时返回 null
 */
export function buildExtraData ({
  quoteInfo,
  contactType,
  messageContent,
  atAllEnabled,
  role
} = {}) {
  const extra = {}

  if (quoteInfo) {
    extra.quoteId = quoteInfo.messageId
    extra.quoteContent = quoteInfo.quoteContent
    extra.quoteNickName = quoteInfo.quoteNickName || ''
  }

  // 群 @ 提及：正文里形如 "@Uxxxx" 的用户 ID
  if (contactType === GROUP_CONTACT_TYPE && messageContent) {
    const matched = messageContent.match(AT_USER_ID_PATTERN)
    if (matched && matched.length > 0) {
      extra.atUserIds = matched.map((item) => item.substring(1))
    }
    // @所有人：以面板勾选标记为准，正文出现 @所有人 也认（兼容草稿恢复后重发）
    if (atAllEnabled || messageContent.indexOf(AT_ALL_TEXT) >= 0) {
      extra.atAll = true
    }
  }

  return Object.keys(extra).length > 0 ? JSON.stringify(extra) : null
}
