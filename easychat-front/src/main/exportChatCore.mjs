// 聊天记录导出的**纯逻辑核心**（无 electron / fs / db 依赖）
//
// 抽离理由（与 utils/virtualListCore.mjs、utils/callFrameCore.mjs 同一手法）：
//   原本这些函数是 src/main/exportChat.js 的模块私有实现，而该模块 import 了
//   electron / fs / ./db/ChatMessageModel / ./store —— 在 node 里 import 它会拉起 Electron，
//   导致**完全无法被测试**。把它们搬到本文件后：
//     ① 可被 scripts/verify/verify_export_chat_core.mjs 直接 import 校验（零新依赖，已入 CI）；
//     ② 后续若引入 vitest，本文件也可直接作为测试目标，无需再抽。
//
// 本文件只做「纯计算」，不做 IO 与弹窗——那些仍留在 exportChat.js。
//
// ⚠️ 搬移纪律：本文件内容从 exportChat.js **逐字搬移**，未改任何逻辑。
//    任何行为调整必须在单测/校验脚本能覆盖的前提下进行，否则视为未授权改动。

/** 文件类型：0图片 1视频 2文件（与渲染层 Constants.File_TYPE 对齐） */
export const FILE_TYPE_LABEL = { 0: '图片', 1: '视频', 2: '文件' }

const pad = (n) => (n < 10 ? '0' + n : '' + n)

export const formatTime = (ts) => {
    if (!ts) return ''
    const d = new Date(Number(ts))
    if (isNaN(d.getTime())) return ''
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 展示用内容：媒体消息文本化无意义，降级为占位符 */
export const buildContent = (row) => {
    const type = Number(row.message_type)
    if (type === 5) {
        const label = FILE_TYPE_LABEL[Number(row.file_type)] || '文件'
        if (Number(row.file_type) === 2) {
            return `[${label}] ${row.file_name || ''}`.trim()
        }
        return `[${label}]`
    }
    return row.message_content == null ? '' : String(row.message_content)
}

/** TXT：[时间] 昵称: 内容 */
export const buildTxt = (list, sessionTitle) => {
    const lines = []
    lines.push(`会话：${sessionTitle || ''}`)
    lines.push(`导出时间：${formatTime(Date.now())}`)
    lines.push(`消息条数：${list.length}`)
    lines.push('----------------------------------------')
    list.forEach((row) => {
        const name = row.send_user_nick_name || row.send_user_id || ''
        lines.push(`[${formatTime(row.send_time)}] ${name}: ${buildContent(row)}`)
    })
    return lines.join('\n')
}

/**
 * CSV 单元格转义：双引号翻倍、换行转空格；
 * 以 = + - @ 开头前置单引号，避免被 Excel 当公式执行。
 *
 * ⚠️ 这是一条**安全控制**，不是格式美化：
 *    聊天内容来自**对方**，「=cmd|'/c calc'!A1」这类公式注入载荷若原样写入 CSV，
 *    受害者用 Excel 打开导出的记录时就会执行。故该前缀 `'` 不可省略，
 *    也不可因「看着多余」而删除 —— 见 verify_export_chat_core.mjs 的对应断言。
 */
export const csvCell = (value) => {
    let v = value == null ? '' : String(value)
    v = v.replace(/\r?\n/g, ' ')
    // ⚠ 必须先剥掉前导空白（空格 / Tab / 其它空白）再判定触发字符。
    //   Excel 会**忽略单元格内容的前导空白**再把它当公式解释，
    //   所以聊天里发「␣=cmd|'/c calc'!A1」或「⇥=...」就能绕过只判首字符的防护。
    //   这是 CSV 注入的经典绕过手法（OWASP CSV Injection）。
    const probe = v.replace(/^[\s﻿ ]+/, '')
    if (/^[=+\-@]/.test(probe)) {
        // 前缀直接加在原始内容上（保留空白），Excel 才会把整格视作文本
        v = "'" + v
    }
    return '"' + v.replace(/"/g, '""') + '"'
}

/** CSV：UTF-8 BOM + 表头，Excel 直接打开不乱码 */
export const buildCsv = (list) => {
    const header = ['消息ID', '时间', '发送人ID', '昵称', '消息类型', '内容', '文件名']
    const rows = [header.map(csvCell).join(',')]
    list.forEach((row) => {
        rows.push([
            row.message_id,
            formatTime(row.send_time),
            row.send_user_id,
            row.send_user_nick_name,
            Number(row.message_type) === 5 ? (FILE_TYPE_LABEL[Number(row.file_type)] || '文件') : '文本',
            buildContent(row),
            row.file_name
        ].map(csvCell).join(','))
    })
    return '\uFEFF' + rows.join('\r\n')
}

export const safeFileName = (name) => {
    // 去掉 Windows 文件名非法字符，避免保存失败
    return String(name || '').replace(/[\\/:*?"<>|]/g, '_').slice(0, 50) || 'chat'
}

export const buildDefaultPath = (sessionTitle, format) => {
    const d = new Date()
    const date = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
    const ext = format === 'csv' ? 'csv' : 'txt'
    return `EasyChat-${safeFileName(sessionTitle)}-${date}.${ext}`
}

/**
 * 云端漫游行归一化：服务端下发 camelCase，本地 SQLite 行是 snake_case。
 * 统一成本地行形状后，buildTxt / buildCsv 可无差别复用。
 */
export const normalizeCloudRow = (row) => ({
    message_id: row.messageId,
    message_type: row.messageType,
    message_content: row.messageContent,
    send_user_id: row.sendUserId,
    send_user_nick_name: row.sendUserNickName,
    send_time: row.sendTime,
    file_type: row.fileType,
    file_name: row.fileName
})

/** 群聊 / 单聊标签（contactType：0 单聊 1 群聊） */
export const contactTypeLabel = (contactType) => (Number(contactType) === 1 ? '群聊' : '单聊')

/** 备份默认文件名：EasyChat-备份-YYYYMMDD.<ext> */
export const buildBackupDefaultPath = (format) => {
    const d = new Date()
    const date = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
    return `EasyChat-备份-${date}.${format === 'csv' ? 'csv' : 'txt'}`
}

/** 跨会话 TXT：文件头 + 每个会话一段分隔块 */
export const buildBackupTxt = (groups, totalCount) => {
    const lines = []
    lines.push(`EasyChat 聊天记录备份（跨会话全量）`)
    lines.push(`备份时间：${formatTime(Date.now())}`)
    lines.push(`会话数：${groups.length}`)
    lines.push(`消息条数：${totalCount}`)
    lines.push('========================================')
    groups.forEach((g) => {
        lines.push('')
        lines.push(`会话：${g.title || g.sessionId || ''}（${contactTypeLabel(g.contactType)}）`)
        lines.push(`消息数：${g.rows.length}`)
        lines.push('----------------------------------------')
        g.rows.forEach((row) => {
            const name = row.send_user_nick_name || row.send_user_id || ''
            lines.push(`[${formatTime(row.send_time)}] ${name}: ${buildContent(row)}`)
        })
    })
    return lines.join('\n')
}

/** 跨会话 CSV：在既有列基础上增加首列「会话」「会话类型」 */
export const buildBackupCsv = (groups) => {
    const header = ['会话', '会话类型', '消息ID', '时间', '发送人ID', '昵称', '消息类型', '内容', '文件名']
    const rows = [header.map(csvCell).join(',')]
    groups.forEach((g) => {
        g.rows.forEach((row) => {
            rows.push([
                g.title || g.sessionId || '',
                contactTypeLabel(g.contactType),
                row.message_id,
                formatTime(row.send_time),
                row.send_user_id,
                row.send_user_nick_name,
                Number(row.message_type) === 5 ? (FILE_TYPE_LABEL[Number(row.file_type)] || '文件') : '文本',
                buildContent(row),
                row.file_name
            ].map(csvCell).join(','))
        })
    })
    return '\uFEFF' + rows.join('\r\n')
}

/**
 * 云端全量排序：服务端是 messageId desc 分页返回，导出需按发送时间**升序**。
 * 抽成函数以便测试「时间戳为字符串 / null / 非法」时的排序稳定性。
 */
export const sortRowsBySendTime = (rows) =>
    rows.slice().sort((a, b) => Number(a.send_time) - Number(b.send_time))