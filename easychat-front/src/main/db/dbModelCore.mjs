/**
 * 主进程本地 SQLite 层的**纯构造核心**（无 sqlite3 / 无 store / 无 IO）
 *
 * 抽离理由（docs/system-facts.md §14 遗留 #7 剩余缺口之二）：
 *   `ADB.js` 在**模块加载时** require('sqlite3') 并 init() 建表，
 *   故 ChatMessageModel / ChatSessionUserModel / UserSetting / LaterHandleModel
 *   在 node 中 import 即建表 —— **完全不可测**。
 *   而这一层恰是「静默失效」高发区：列名写错、where 条件漏项、参数顺序颠倒，
 *   **都不抛异常**，只表现为「数据没写进去」或「写到了别人的行」。
 *
 * 本文件只放**构造**（SQL 文本 + 参数数组 + 字段白名单），
 * **不放执行**：run / queryAll / update 等依赖 sqlite3，抽出也测不了，留在 Model。
 *
 * ⚠ 搬移纪律：本文件内容从各 Model **逐字搬移**，未改任何逻辑。
 *    任何行为调整必须先有能覆盖它的断言，否则视为未授权改动。
 */

/** 本地服务端口的起始值（与 UserSetting#addUserSetting 一致） */
export const FIRST_SERVER_PORT = 10240

// ───────────────────────── chat_message ─────────────────────────

/** 单条消息是否存在（撤回帧补落历史用） */
export function buildExistsMessage (userId, messageId) {
  return {
    sql: 'select message_id from chat_message where user_id = ? and message_id = ?',
    params: [userId, messageId]
  }
}

/** 本地删除单条消息（多选删除 / 右键删除；服务端保留，仅本端不可见） */
export function buildDelMessage (userId, messageId) {
  return {
    sql: 'delete from chat_message where user_id = ? and message_id = ?',
    params: [userId, messageId]
  }
}

/** 按 messageId 取单条（本地文件服务器解析落盘路径时用） */
export function buildSelectByMessageId (userId, messageId) {
  // ⚠ 原 SQL 是 `user_id =?`（等号后无空格），语义与 `= ?` 等价，此处**保持原样**
  //   以便「逐字搬移」可核对；改写不属于本抽离的范围。
  return {
    sql: 'select * from chat_message where message_id = ? and user_id =?',
    params: [messageId, userId]
  }
}

/**
 * 聊天窗口的分页倒序查询（selectMessageList）。
 *
 * ⚠ 原 SQL 里 `and message_id <=?` **前面没有空格**，拼在
 *   `... and user_id = ?` 之后会得到 `... and user_id = ?and message_id <=?`。
 *   SQLite 能解析，但此处**刻意原样保留**以便「逐字搬移」可逐字核对 ——
 *   加空格属改写，不属于本抽离的范围。
 */
export function buildMessagePage (userId, sessionId, maxMessageId, offset, limit) {
  let sql = 'select * from chat_message where session_id = ? and user_id = ?'
  const params = [sessionId, userId]
  if (maxMessageId) {
    sql = sql + 'and message_id <=?'
    params.push(maxMessageId)
  }
  params.push(offset)
  params.push(limit)
  sql = sql + 'order by message_id desc limit ?,?'
  return { sql, params }
}

/** count 语句（分页总数） */
export function buildMessagePageCount (userId, sessionId) {
  return {
    sql: 'select count(1) from chat_message where session_id = ? and user_id = ?',
    params: [sessionId, userId]
  }
}

/** 按会话全量读取消息（导出用，按 message_id **升序**） */
export function buildSelectAllMessageList (userId, sessionId) {
  // ⚠ 与 buildMessagePage 的差别有二：升序（不是 desc）、无 maxMessageId 条件、不分页
  return {
    sql: 'select * from chat_message where session_id = ? and user_id = ? order by message_id asc',
    params: [sessionId, userId]
  }
}

/**
 * 消息搜索的**公共条件块**。
 *
 * ⚠ 2026-10-12 抽离时发现：`searchMessages` 原先把这段条件构造**复制了两份**
 *   （一份给 count 查询、一份给分页查询，ChatMessageModel.js:123-142 与 151-170
 *   逐字相同）。两份若改一处漏一处，count 与实际结果会对不上，且**不抛异常**
 *   （表现为「页码总数与实际条数不符」）。本函数消除该重复，是抽离的附带收益。
 *
 * 条件为「真值才加」——注意这意味着 `noReadCount = 0`、`keyword = ''` 这类
 * **会被静默忽略**（不是缺陷，是既有语义；改它属行为变更，须单独决策）。
 */
export function buildSearchConditions (query) {
  const { keyword, sendUserId, messageType, startTime, endTime } = query || {}
  const clauses = []
  const params = []
  if (keyword) {
    clauses.push(' and message_content like ?')
    params.push(`%${keyword}%`)
  }
  if (sendUserId) {
    clauses.push(' and send_user_id = ?')
    params.push(sendUserId)
  }
  if (messageType) {
    clauses.push(' and message_type = ?')
    params.push(messageType)
  }
  if (startTime) {
    clauses.push(' and send_time >= ?')
    params.push(startTime)
  }
  if (endTime) {
    clauses.push(' and send_time <= ?')
    params.push(endTime)
  }
  return { clauses, params }
}

/** 消息搜索的 count 语句 */
export function buildSearchCount (userId, sessionId, query) {
  const { clauses, params } = buildSearchConditions(query)
  return {
    sql: 'select count(1) from chat_message where session_id = ? and user_id = ?' + clauses.join(''),
    params: [sessionId, userId, ...params]
  }
}

/**
 * 消息搜索的分页语句。
 *
 * ⚠ **offset 与 limit 的顺序不可颠倒**：SQL 是 `limit ?,?`，参数依次为 offset、limit。
 *   写反不会报错，只会「每页取到的条数与总页数对不上」——典型的静默失效。
 */
export function buildSearchPage (userId, sessionId, query, offset, limit) {
  const { clauses, params } = buildSearchConditions(query)
  return {
    sql: 'select * from chat_message where session_id = ? and user_id = ?' +
      clauses.join('') + ' order by send_time desc limit ?,?',
    params: [sessionId, userId, ...params, offset, limit]
  }
}

// ─────────────────────── chat_session_user ───────────────────────

/** 更新未读数（自增） */
export function buildUpdateNoReadCount (userId, contactId, noReadCount) {
  // ⚠ 参数顺序必须与 SET 子句、WHERE 子句依次对应：[增量, userId, contactId]
  //   顺序颠倒会把**别人的**会话未读数改掉，且不抛异常。
  return {
    sql: 'update chat_session_user set no_read_count = no_read_count+? where user_id=? and contact_id=?',
    params: [noReadCount, userId, contactId]
  }
}

/** 会话列表（仅 status = 1） */
export function buildSelectUserSessionList (userId) {
  // ⚠ 必须同时带 user_id 与 status：漏掉 status 会把已删除/隐藏的会话一并列出
  return {
    sql: 'select * from chat_session_user where user_id = ? and status = 1',
    params: [userId]
  }
}

/** 按 contactId 查单个会话 */
export function buildSelectSessionByContactId (userId, contactId) {
  return {
    sql: 'select * from chat_session_user where user_id = ? and contact_id = ?',
    params: [userId, contactId]
  }
}

/** 标记会话已读 */
export function buildReadAll (userId, contactId) {
  return {
    sql: 'update chat_session_user set no_read_count = 0 where user_id=? and contact_id=?',
    params: [userId, contactId]
  }
}

/** 会话更新的 WHERE 条件 */
export function buildSessionWhere (userId, contactId) {
  return { userId, contactId }
}

/**
 * 会话更新的 SET 部分：**必须剔除** userId / contactId。
 * 主键被改写会造成「一条会话凭空变成另一条」，且不抛异常。
 */
export function buildSessionUpdateInfo (sessionInfo) {
  const info = Object.assign({}, sessionInfo)
  info.contactId = null
  info.userId = null
  return info
}

/** 按 sessionId 更新会话元数据（跨端 SYNC_SESSION 同步帧用） */
export const SESSION_ATTR_FIELDS = ['lastMessage', 'lastReceiveTime', 'noReadCount']

/**
 * 动态 SET 子句构造。
 *
 * ⚠ 字段名 → 列名的映射是**硬编码白名单**，不是把 JS 驼峰名直接当列名用。
 *   直接拼字段名会产生 `lastMessage = ?` 这种不存在的列，SQLite 不报错、
 *   动态生成库甚至会**凭空建列**，表现是「同步帧写了但数据没进去」。
 */
export function buildSessionAttrSet (updateInfo) {
  const COL = { lastMessage: 'last_message', lastReceiveTime: 'last_receive_time', noReadCount: 'no_read_count' }
  const setClauses = []
  const params = []
  for (const field of SESSION_ATTR_FIELDS) {
    if (updateInfo && updateInfo[field] !== undefined) {
      setClauses.push(`${COL[field]} = ?`)
      params.push(updateInfo[field])
    }
  }
  return { setClauses, params }
}

/** 会话级属性同步（置顶 / 免打扰 / 草稿）的字段白名单 */
export const SESSION_USER_ATTR_WHITELIST = ['topType', 'noDisturb', 'draft']

/** 按 sessionId 更新会话元数据的完整语句 */
export function buildSessionAttrUpdate (userId, sessionId, updateInfo) {
  const { setClauses, params } = buildSessionAttrSet(updateInfo)
  let sql = 'update chat_session_user set '
  sql += setClauses.join(', ')
  sql += ' where user_id = ? and session_id = ?'
  params.push(userId, sessionId)
  return { sql, params, hasClause: setClauses.length > 0 }
}

/**
 * 收到消息时更新会话（updateSessionInfo4Message）。
 *
 * ⚠ 三处易错点：
 *  ① `sessionId !== currentSessionId` 时**自增**未读数 —— 即「用户正看着这个会话时不计未读」；
 *  ② `memberCount != null` 用**宽松比较**（0 也要写入），而 `contactName` 用真值判断
 *     （空串不写入）。两者口径不同是**既有语义**，此处原样保留；
 *  ③ WHERE 是 `contact_id`（不是 session_id）——与上面的按 sessionId 更新是两处不同查询。
 */
export function buildUpdateSessionInfo (userId, currentSessionId, sessionInfo) {
  const { sessionId, contactName, lastMessage, lastReceiveTime, contactId, memberCount } = sessionInfo
  const params = [lastMessage, lastReceiveTime]
  let sql = 'update chat_session_user set last_message=?,last_receive_time=?,status = 1'
  if (contactName) {
    sql = sql + ',contact_name = ?'
    params.push(contactName)
  }
  if (memberCount != null) {
    sql = sql + ',member_count =?'
    params.push(memberCount)
  }
  if (sessionId !== currentSessionId) {
    sql = sql + ',no_read_count = no_read_count + 1'
  }
  sql = sql + ' where user_id = ? and contact_id = ?'
  params.push(userId)
  params.push(contactId)
  return { sql, params }
}

/** 单字段更新（置顶 / 群名 / 状态 / 已删除标记）的 SET 片段 */
export function buildSimpleSessionUpdate (field, value) {
  return { [field]: value }
}

/**
 * 管理端删除帧（20）条件预览：仅改写 last_message。
 *
 * ⚠ 刻意**不**刷 last_receive_time（防会话重排）、**不**计 no_read_count（防未读脏增），
 *   见 MessageSend / ADR-004。三者中漏任何一个都会产生用户可见的错误，故单列断言。
 */
export function buildSessionPreviewOnly (userId, contactId, lastMessage) {
  return {
    sql: 'update chat_session_user set last_message = ? where user_id = ? and contact_id = ?',
    params: [lastMessage, userId, contactId]
  }
}

// ───────────────────────── UserSetting ─────────────────────────

/**
 * 会话未读数更新：两种语义。
 *
 * ⚠ 判定用**真值**（if (noReadCount)）：传 0 会走「置 0」分支，
 *   传 undefined 也走「置 0」分支。这是**既有语义**，原样保留
 *   （改成 != null 会改变 undefined 时的行为，属行为变更，须单独立项）。
 */
export function buildContactNoReadCount (noReadCount, userId) {
  if (noReadCount) {
    return {
      sql: 'update user_setting set contact_no_read = contact_no_read+? where user_id = ?',
      params: [noReadCount, userId]
    }
  }
  return {
    sql: 'update user_setting set contact_no_read = ? where user_id = ?',
    params: [0, userId]
  }
}

/** 取全表最大端口号。刻意**不**加 where user_id —— 原实现就是全表 max，
 *  故各用户端口互不相同（避免同机两用户撞端口导致 EADDRINUSE）。 */
export function buildSelectMaxServerPort () {
  return { sql: 'select max(server_port) server_port from user_setting', params: [] }
}

/** 按 userId 取该用户的设置行（判断新建还是已存在） */
export function buildSelectSettingByUserId (userId) {
  return { sql: 'select * from user_setting where user_id =?', params: [userId] }
}

/**
 * 取本机所有已登录用户的邮箱（启动时用于「上次登录账号」回填）。
 *
 * ⚠ 只取 `email is not null` —— SQLite 的 `is not null` 对空串同样成立，
 *   故历史脏数据里的空串邮箱仍会被返回。这是**既有语义**，原样保留。
 */
export function buildSelectLocalEmails () {
  return { sql: 'select email from user_setting where email is not null', params: [] }
}

/** 按 userId 取系统设置 */
export function buildSelectSettingInfo (userId) {
  return { sql: 'select  * from user_setting where user_id = ?', params: [userId] }
}

/**
 * 本地服务端口分配。
 *
 * ⚠ 原实现读 `select max(server_port) server_port from user_setting`（**全表**最大值，
 *   不按 userId 过滤），故**先到先得、各用户端口互不相同**——
 *   同一台机器上两个用户若撞端口，第二个的本地文件服务器会起不来（EADDRINUSE）。
 *   保持原语义，**刻意不改成按 userId 取 max**：那会让同一用户反复登录拿到不同端口，
 *   属行为变更。
 *
 * @param {number|null|undefined} currentMax 全表最大端口号；空表 / 无记录时为 null
 * @returns {number} 本次分配的端口
 */
export function nextServerPort (currentMax) {
  if (currentMax === null || currentMax === undefined) return FIRST_SERVER_PORT
  return Number(currentMax) + 1
}

// ───────────────────────── LaterHandle ─────────────────────────

export function buildSelectLaterHandleList (userId) {
  return { sql: 'SELECT * FROM later_handle WHERE user_id = ? ORDER BY create_time DESC', params: [userId] }
}

export function buildSelectLaterHandleById (id) {
  return { sql: 'SELECT * FROM later_handle WHERE id = ?', params: [id] }
}

export function buildDeleteLaterHandle (id) {
  return { sql: 'DELETE FROM later_handle WHERE id = ?', params: [id] }
}

export function buildDeleteExpiredLaterHandle (currentTime) {
  return { sql: 'DELETE FROM later_handle WHERE remind_time < ?', params: [currentTime] }
}

export function buildSelectNeedRemindLaterHandle (currentTime) {
  return { sql: 'SELECT * FROM later_handle WHERE remind_time <= ?', params: [currentTime] }
}

/**
 * 新增稍后处理。
 *
 * ⚠ 8 个占位符与 8 个列的**顺序**必须逐一对应；顺序错不会报错，
 *   只会把 messageId 写进 sessionId 列（跨列串值，且数据类型多为 varchar，
 *   SQLite 不会拦）。
 */
export function buildInsertLaterHandle (row) {
  return {
    sql: 'INSERT INTO later_handle (user_id, message_id, session_id, contact_id, ' +
      'contact_name, content, create_time, remind_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
    params: [
      row.userId, row.messageId, row.sessionId, row.contactId,
      row.contactName, row.content, row.createTime, row.remindTime
    ]
  }
}