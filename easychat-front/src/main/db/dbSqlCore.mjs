// 本地 SQLite 访问层的纯 SQL 构造核心（无 sqlite3 / fs / os 依赖）
//
// 为什么需要它（docs/system-facts.md §14 遗留 #7 第三段）：
//   db/ADB.js 在**模块加载时**就 `require('sqlite3')` 并调用 `init()` 建表，
//   node 中根本无法 import → 本地缓存层的 SQL 拼装与列映射逻辑**完全不可测**。
//   而这一层恰恰是「静默失效」的高发区：列名拼错 / where 条件漏项
//   都不会抛异常，只表现为「数据没写进去」或「写到了不该写的行」。
//
// 本模块只放纯计算，ADB.js 负责执行。
//
// ⚠ 本模块同时修正了两个既有缺陷（均由 scripts/verify/verify_local_db_core.mjs 锁定）：
//   ① where 条件用 `if (paramData[item])` 过滤 → **假值条件被静默丢弃**，
//      拼出的 WHERE 少一个条件 → 更新命中范围扩大（改的是别人的行）
//   ② 字段不在列映射里时被静默丢弃（`columnsMap[item] != undefined`），
//      调用方无从得知「这次写入根本没生效」

/**
 * snake_case → camelCase（与 ADB.js 原有实现逐字一致）。
 */
export function toCamelCase (str) {
  return String(str).replace(/_([a-z])/g, (match, p1) =>
    String.fromCharCode(p1.charCodeAt(0) - 32))
}

/**
 * 把一行 DB 记录（snake_case 列名）转成业务对象（camelCase 键）。
 * null / undefined / 非对象输入统一返回 null。
 */
export function convertDbObj2BizObj (data) {
  if (!data) return null
  const bizData = {}
  for (const item in data) {
    bizData[toCamelCase(item)] = data[item]
  }
  return bizData
}

/**
 * 批量转换多行。
 */
export function convertDbRows2BizObjs (rows) {
  if (!Array.isArray(rows)) return []
  return rows.map((r) => convertDbObj2BizObj(r))
}

/**
 * 判定「该字段是否应参与写库」。
 * null 视为**有效值**（显式写 null），undefined 视为未提供。
 */
function isWritable (value) {
  return value !== undefined
}

/**
 * 构造 INSERT 语句。
 *
 * @param {string} sqlPrefix 如 'insert or ignore into'
 * @param {string} tableName
 * @param {object} data 业务对象（camelCase 键）
 * @param {object} columnsMap camelCase → 真实列名
 * @returns {{sql: string, params: Array, dropped: Array<string>}}
 *   dropped 列出「传了但未落库」的字段名——旧实现静默丢弃，
 *   调用方无从察觉「这次写入根本没生效」。
 */
export function buildInsertSql (sqlPrefix, tableName, data, columnsMap) {
  const dbColumns = []
  const params = []
  const dropped = []
  const map = columnsMap || {}
  for (const item in data) {
    if (!isWritable(data[item])) continue
    if (map[item] === undefined) {
      dropped.push(item)
      continue
    }
    dbColumns.push(map[item])
    params.push(data[item])
  }
  const preper = '?'.repeat(dbColumns.length).split('').join(',')
  const sql = `${sqlPrefix} ${tableName}(${dbColumns.join(',')})values(${preper})`
  return { sql, params, dropped }
}

/**
 * 构造 UPDATE 语句。
 *
 * ⚠ 缺陷①修复：where 条件原先用 `if (paramData[item])` 过滤，
 *   假值（'' / 0 / false）会被**静默丢弃** → 拼出的 WHERE 少条件 → 更新命中范围扩大。
 *   例如 userId 有值而 contactId 为 '' 时，原本只应命中一条的更新
 *   会变成「该用户的所有会话都被改写」。故改为只过滤 undefined / null。
 *
 * ⚠ 无可更新列或无 where 条件时返回 skipped（不产生 SQL）。
 *   空 set 会拼出 `update t  where ...` 触发 SQLITE_ERROR 并弹原生框阻塞主进程；
 *   空 where 会退化成**全表更新**。两者都必须短路。
 *
 * @returns {{sql: string|null, params: Array, skipped: boolean,
 *            reason: string, dropped: Array<string>}}
 */
export function buildUpdateSql (tableName, data, paramData, columnsMap) {
  const map = columnsMap || {}
  const setClauses = []
  const params = []
  const dropped = []
  for (const item in data) {
    if (!isWritable(data[item])) continue
    if (map[item] === undefined) {
      dropped.push(item)
      continue
    }
    setClauses.push(`${map[item]} = ?`)
    params.push(data[item])
  }

  const whereClauses = []
  for (const item in paramData) {
    const v = paramData ? paramData[item] : undefined
    if (v === undefined || v === null) continue
    if (map[item] === undefined) {
      dropped.push(item)
      continue
    }
    whereClauses.push(`${map[item]} = ?`)
    params.push(v)
  }

  if (setClauses.length === 0) {
    return { sql: null, params, skipped: true, reason: 'set 子句为空（无可更新列）', dropped }
  }
  if (whereClauses.length === 0) {
    return { sql: null, params, skipped: true, reason: 'where 条件为空（会退化成全表更新）', dropped }
  }

  const sql = `update ${tableName} set ${setClauses.join(',')} where ${whereClauses.join(' and ')}`
  return { sql, params, skipped: false, reason: '', dropped }
}

/**
 * 从 PRAGMA table_info 结果构建 camelCase → 真实列名 的映射。
 * @param {Array<{name: string}>} columns
 */
export function buildColumnsMap (columns) {
  const map = {}
  for (const col of columns || []) {
    if (!col || col.name === undefined || col.name === null) continue
    map[toCamelCase(col.name)] = col.name
  }
  return map
}

/**
 * 建表 / 改表脚本的可执行性判定（对应 ADB.js#createTable 的 alter 分支）。
 *
 * ⚠ 缺陷②修复：`alter_tables` 里每条都声明了 field，
 *   但判定存在性用的是 `pragma table_info(...)`。若 pragma 结果为空
 *   （表尚未建好 / pragma 语法不被接受），`some(...)` 为 false →
 *   **对每一张表都执行 ALTER**，而 sqlite 的 `add column` 不幂等，
 *   第二次启动即报「duplicate column name」。
 *   原实现在 createTable 循环内直接跑 SQL、异常无人接 → 主进程启动日志里
 *   只有一句 console.error，而**草稿/免打扰两列已存在的老用户**
 *   会遇到启动噪音、功能却正常（掩盖了真实错误）。
 *   故改为：先收集「需要执行的 alter 清单」，再统一执行，
 *   且调用方应据 skipped 数量决定是否告警。
 *
 * @param {Array<{tableName: string, field: string}>} alterTables
 * @param {(sql: string) => Promise<Array>} queryAll 执行 PRAGMA 查询
 * @returns {Promise<Array<{tableName: string, field: string, sql: string}>>} 待执行的 alter
 */
export async function selectMissingAlters (alterTables, queryAll) {
  const pending = []
  for (const item of alterTables || []) {
    if (!item || !item.tableName || !item.field || !item.sql) continue
    const fieldList = await queryAll(`pragma table_info(${item.tableName})`)
    const exists = Array.isArray(fieldList) && fieldList.some((row) => row && row.name === item.field)
    if (!exists) pending.push({ tableName: item.tableName, field: item.field, sql: item.sql })
  }
  return pending
}