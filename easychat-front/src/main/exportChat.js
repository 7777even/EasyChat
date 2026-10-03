import { dialog } from 'electron'
import fs from 'fs'
import { selectAllMessageList } from './db/ChatMessageModel'
import store from './store'
import {
    buildTxt,
    buildCsv,
    buildDefaultPath,
    normalizeCloudRow,
    sortRowsBySendTime,
    buildBackupDefaultPath,
    buildBackupTxt,
    buildBackupCsv
} from './exportChatCore.mjs'

// ===== 聊天记录导出（openspec/changes/2026-09-26-chat-record-export） =====
// 数据源二选一：
//   1) 本地 SQLite 已持久化的消息（只读）；
//   2) 云端漫游全量：渲染进程用 loadHistoryMessage 翻页拉全后传入 list（camelCase），
//      此处归一化为本地行形状（snake_case），复用同一套格式化与落盘逻辑。
// 职责：取数 → 归一化 → 组装文本 → 保存对话框选路径 → 落盘 → 回传结果。
//
// 2026-10-03：纯计算部分已抽离到 ./exportChatCore.mjs —— 本模块 import 了
//   electron / fs / db / store，在 node 中无法 import，故原先那些模块私有函数
//   **完全不可测**（含 CSV 公式注入防护这条安全控制）。抽离后由
//   scripts/verify/verify_export_chat_core.mjs 直接 import 校验（零新依赖，已入 CI）。
//   本文件现在只保留 IO 与弹窗编排。

/**
 * 导出单个会话的聊天记录。
 * 传入 list 时走云端漫游全量（渲染层已翻页取全），否则读本地 SQLite。
 * @param {{sessionId:string, contactName:string, format:'txt'|'csv', list?:Array}} params
 * @returns {Promise<{success:boolean, canceled:boolean, path:string, count:number, error?:string}>}
 */
const exportChatRecord = async (params) => {
    const { sessionId, contactName, format, list: cloudList } = params || {}
    if (!sessionId) {
        return { success: false, canceled: false, path: '', count: 0, error: '缺少会话ID' }
    }
    const useCsv = format === 'csv'
    try {
        let list
        if (Array.isArray(cloudList) && cloudList.length > 0) {
            // 云端全量：归一化 + 按发送时间升序（服务端是 messageId desc 分页返回）
            list = sortRowsBySendTime(cloudList.map(normalizeCloudRow))
        } else {
            list = await selectAllMessageList({ sessionId })
        }
        if (!list || list.length === 0) {
            return { success: false, canceled: false, path: '', count: 0, error: '该会话没有可导出的消息' }
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

/**
 * 跨会话全量备份：把渲染层已取全的多个会话消息导出为单个文件。
 * @param {{format:'txt'|'csv', groups:Array<{sessionId:string, title:string, contactType:number, messages:Array}>}} params
 * @returns {Promise<{success:boolean, canceled:boolean, path:string, count:number, sessionCount:number, error?:string}>}
 */
const exportChatBackup = async (params) => {
    const { format, groups } = params || {}
    const useCsv = format === 'csv'
    const list = Array.isArray(groups) ? groups : []
    try {
        // 归一化 + 每个会话内按时间升序；丢弃无消息的会话（如已清空历史）
        const normalized = list
            .map((g) => ({
                sessionId: g.sessionId,
                title: g.title,
                contactType: g.contactType,
                rows: sortRowsBySendTime(
                    (Array.isArray(g.messages) ? g.messages : []).map(normalizeCloudRow)
                )
            }))
            .filter((g) => g.rows.length > 0)

        if (normalized.length === 0) {
            return { success: false, canceled: false, path: '', count: 0, sessionCount: 0, error: '没有可备份的消息' }
        }
        const totalCount = normalized.reduce((sum, g) => sum + g.rows.length, 0)
        const content = useCsv ? buildBackupCsv(normalized) : buildBackupTxt(normalized, totalCount)
        const result = await dialog.showSaveDialog({
            title: '备份全部会话聊天记录',
            defaultPath: buildBackupDefaultPath(format),
            filters: useCsv
                ? [{ name: 'CSV 表格', extensions: ['csv'] }]
                : [{ name: '文本文件', extensions: ['txt'] }]
        })
        if (result.canceled || !result.filePath) {
            return { success: false, canceled: true, path: '', count: 0, sessionCount: 0 }
        }
        fs.writeFileSync(result.filePath, content, { encoding: 'utf8' })
        console.log('跨会话备份成功 path=' + result.filePath + ' count=' + totalCount +
            ' sessions=' + normalized.length + ' userId=' + store.getUserId())
        return {
            success: true,
            canceled: false,
            path: result.filePath,
            count: totalCount,
            sessionCount: normalized.length
        }
    } catch (e) {
        console.warn('跨会话备份失败', e)
        return { success: false, canceled: false, path: '', count: 0, sessionCount: 0, error: (e && e.message) || '备份失败' }
    }
}

export {
    exportChatRecord,
    exportChatBackup
}
