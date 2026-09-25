import { dialog } from 'electron'
import fs from 'fs'
import { selectAllMessageList } from './db/ChatMessageModel'
import store from './store'

// ===== 聊天记录导出（openspec/changes/2026-09-26-chat-record-export） =====
// 数据源：本地 SQLite 已持久化的消息（只读）。云端全量漫游归入后续「备份迁移」专项。
// 职责：全量读库 → 组装文本 → 保存对话框选路径 → 落盘 → 回传结果。

/** 文件类型：0图片 1视频 2文件（与渲染层 Constants.File_TYPE 对齐） */
const FILE_TYPE_LABEL = { 0: '图片', 1: '视频', 2: '文件' }

const pad = (n) => (n < 10 ? '0' + n : '' + n)

const formatTime = (ts) => {
    if (!ts) return ''
    const d = new Date(Number(ts))
    if (isNaN(d.getTime())) return ''
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 展示用内容：媒体消息文本化无意义，降级为占位符 */
const buildContent = (row) => {
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
const buildTxt = (list, sessionTitle) => {
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
 */
const csvCell = (value) => {
    let v = value == null ? '' : String(value)
    v = v.replace(/\r?\n/g, ' ')
    if (/^[=+\-@]/.test(v)) {
        v = "'" + v
    }
    return '"' + v.replace(/"/g, '""') + '"'
}

/** CSV：UTF-8 BOM + 表头，Excel 直接打开不乱码 */
const buildCsv = (list) => {
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

const safeFileName = (name) => {
    // 去掉 Windows 文件名非法字符，避免保存失败
    return String(name || '').replace(/[\\/:*?"<>|]/g, '_').slice(0, 50) || 'chat'
}

const buildDefaultPath = (sessionTitle, format) => {
    const d = new Date()
    const date = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
    const ext = format === 'csv' ? 'csv' : 'txt'
    return `EasyChat-${safeFileName(sessionTitle)}-${date}.${ext}`
}

/**
 * 导出单个会话的聊天记录。
 * @param {{sessionId:string, contactName:string, format:'txt'|'csv'}} params
 * @returns {Promise<{success:boolean, canceled:boolean, path:string, count:number, error?:string}>}
 */
const exportChatRecord = async (params) => {
    const { sessionId, contactName, format } = params || {}
    if (!sessionId) {
        return { success: false, canceled: false, path: '', count: 0, error: '缺少会话ID' }
    }
    const useCsv = format === 'csv'
    try {
        const list = await selectAllMessageList({ sessionId })
        if (!list || list.length === 0) {
            return { success: false, canceled: false, path: '', count: 0, error: '该会话没有可导出的本地消息' }
        }
        const content = useCsv ? buildCsv(list) : buildTxt(list, contactName)
        const result = await dialog.showSaveDialog({
            title: '导出聊天记录',
            defaultPath: buildDefaultPath(contactName, format),
            filters: useCsv
                ? [{ name: 'CSV 表格', extensions: ['csv'] }]
                : [{ name: '文本文件', extensions: ['txt'] }]
        })
        // 用户取消：静默返回，不视为失败
        if (result.canceled || !result.filePath) {
            return { success: false, canceled: true, path: '', count: 0 }
        }
        fs.writeFileSync(result.filePath, content, { encoding: 'utf8' })
        console.log('聊天记录导出成功 path=' + result.filePath + ' count=' + list.length + ' userId=' + store.getUserId())
        return { success: true, canceled: false, path: result.filePath, count: list.length }
    } catch (e) {
        console.warn('聊天记录导出失败', e)
        return { success: false, canceled: false, path: '', count: 0, error: (e && e.message) || '导出失败' }
    }
}

export {
    exportChatRecord
}
