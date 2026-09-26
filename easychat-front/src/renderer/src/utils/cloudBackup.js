import request from '@/utils/Request'
import Api from '@/utils/Api'

// ===== 云端漫游取数（跨会话备份复用；单会话导出也从此取，避免两份翻页逻辑） =====

/** 后端 loadHistoryMessage 的 pageSize 硬上限（>100 会被服务端降为 20） */
const PAGE_SIZE = 100
/** 单会话翻页保护：异常情况下避免无限循环 */
const MAX_PAGES = 5000
/** 跨会话备份总量软上限：超出即停止并提示，避免内存/文件过大 */
export const MAX_TOTAL_MESSAGES = 200000

/**
 * 拉取单个会话的云端全量历史。
 * 服务端按 messageId desc 返回，lastMessageId 是「严格小于」游标，故每页取最小 id 往更早翻，直到返回空。
 * @param {string} sessionId
 * @returns {Promise<Array>} 服务端消息对象数组（camelCase），已按 messageId 去重
 */
const pullCloudHistory = async (sessionId) => {
    const all = []
    const seen = new Set()
    let lastMessageId = null
    for (let page = 0; page < MAX_PAGES; page++) {
        const result = await request({
            url: Api.loadHistoryMessage,
            showLoading: false,
            showError: false,
            params: { sessionId, lastMessageId, pageSize: PAGE_SIZE }
        })
        const list = (result && result.data && result.data.list) || []
        if (list.length == 0) {
            break
        }
        let added = 0
        list.forEach((item) => {
            if (!seen.has(item.messageId)) {
                seen.add(item.messageId)
                all.push(item)
                added++
            }
        })
        const pageIds = list.map((item) => Number(item.messageId)).filter((id) => !isNaN(id))
        if (pageIds.length == 0) {
            break
        }
        const nextCursor = Math.min(...pageIds)
        // 无新增或游标未往前推进即停止，避免死循环
        if (added == 0) {
            break
        }
        if (lastMessageId != null && nextCursor >= lastMessageId) {
            break
        }
        lastMessageId = nextCursor
    }
    return all
}

/**
 * 串行遍历会话列表，拉取每个会话的云端全量并聚合。
 * 单会话失败只跳过并计入 failedSessions，不中断整体。
 * @param {{sessions: Array, onProgress?: Function}} params
 * @returns {Promise<{groups: Array, total: number, failedSessions: string[], truncated: boolean}>}
 */
const backupAllSessions = async (params) => {
    const { sessions, onProgress } = params || {}
    const list = Array.isArray(sessions) ? sessions : []
    const groups = []
    const failedSessions = []
    let total = 0
    let truncated = false

    for (let i = 0; i < list.length; i++) {
        const s = list[i]
        const title = s.contactName || s.sessionId || ''
        if (onProgress) {
            onProgress({ current: i + 1, total: list.length, title })
        }
        try {
            const messages = await pullCloudHistory(s.sessionId)
            if (messages.length == 0) {
                continue
            }
            if (total + messages.length > MAX_TOTAL_MESSAGES) {
                const room = MAX_TOTAL_MESSAGES - total
                if (room > 0) {
                    groups.push({
                        sessionId: s.sessionId,
                        title,
                        contactType: s.contactType,
                        messages: messages.slice(0, room)
                    })
                    total += room
                }
                truncated = true
                break
            }
            groups.push({ sessionId: s.sessionId, title, contactType: s.contactType, messages })
            total += messages.length
        } catch (e) {
            console.warn('备份会话失败', title, e)
            failedSessions.push(title)
        }
    }
    return { groups, total, failedSessions, truncated }
}

export {
    pullCloudHistory,
    backupAllSessions
}
