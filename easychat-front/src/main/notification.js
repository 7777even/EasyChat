import { selectSettingInfo } from './db/UserSetting'
import store from './store'

// ===== 新消息提醒：任务栏图标闪烁（openspec/changes/2026-09-24-desktop-notification） =====
// 需求修订(2026-09-24)：不做系统横幅/声音/点击跳转，对齐微信——失焦收新消息时任务栏图标闪烁。
// 职责：闪烁抑制规则 + flashFrame 调用。仅此模块发起闪烁，wsClient.js 只做调用接线。

// 仅这些消息类型触发闪烁：2 单聊/群聊文本、5 媒体消息、4 好友申请
// （ACK(-1)/SYNC(-2)/心跳(-4)/撤回(14)/系统帧 3·6·8·9·10·11·12 等一律不闪）
const NOTIFY_TYPE_WHITELIST = [2, 5, 4]
// 重连补推的过期消息时效阈值：超过 5 分钟不闪，防离线补推误闪
const STALE_MESSAGE_MS = 5 * 60 * 1000

// 提醒开关内存缓存（缺省 true = 开；登录后 initNotifySwitch 从 user_setting.sysSetting 读入）
let notifySwitch = true

// ===== 主动交替闪烁循环（实现修订 2，2026-09-24） =====
// 背景：Windows 对**单次** flashFrame 在最小化窗口上仅渲染静态高亮/不产生闪动动画，
// 无法满足「缩入最小化时图标闪烁」的微信对齐目标；
// 方案：600ms 周期交替 flashFrame(false)/(true)，强制产生亮/灭动画；窗口获焦或开关关闭即停止并清态。
const BLINK_INTERVAL_MS = 600
let blinkTimer = null
let blinkPhase = false
let blinkWindow = null

/**
 * 停止交替闪烁并清除残留高亮（幂等）。
 */
const stopBlink = () => {
    if (blinkTimer) {
        clearInterval(blinkTimer)
        blinkTimer = null
    }
    if (blinkWindow) {
        try {
            blinkWindow.flashFrame(false)
        } catch (e) {
            // 窗口可能已销毁，忽略
        }
    }
    blinkPhase = false
    blinkWindow = null
}

/**
 * 启动交替闪烁循环（幂等：已在闪且窗口未变则直接返回）。
 * 循环内自检停止条件：窗口获焦 / 开关关闭 / 窗口句柄失效。
 */
const startBlink = (mainWindow) => {
    if (!mainWindow) return
    if (blinkTimer && blinkWindow === mainWindow) return
    stopBlink()
    blinkWindow = mainWindow
    blinkPhase = false
    blinkTimer = setInterval(() => {
        try {
            if (!blinkWindow || blinkWindow.isFocused() || !notifySwitch) {
                stopBlink()
                return
            }
            blinkPhase = !blinkPhase
            blinkWindow.flashFrame(blinkPhase)
        } catch (e) {
            stopBlink()
        }
    }, BLINK_INTERVAL_MS)
}

/**
 * 登录后初始化开关缓存：读 user_setting.sysSetting.notifySwitch，缺失视为开（存量用户免迁移）。
 * 读取失败不阻断聊天，默认保持 true。
 */
const initNotifySwitch = async () => {
    notifySwitch = true
    try {
        const row = await selectSettingInfo(store.getUserId())
        if (row && row.sysSetting) {
            const sysSetting = JSON.parse(row.sysSetting)
            if (sysSetting && sysSetting.notifySwitch !== undefined) {
                notifySwitch = Boolean(sysSetting.notifySwitch)
            }
        }
    } catch (e) {
        console.warn('读取提醒开关失败，按开启处理', e)
    }
}

/**
 * 开关保存成功后刷新内存缓存（供 ipc.js updateSysSetting 通道调用）。
 */
const setNotifySwitch = (value) => {
    notifySwitch = Boolean(value)
    // 开关关闭立即终止闪烁循环
    if (!notifySwitch) {
        stopBlink()
    }
}

/**
 * 新消息任务栏闪烁主入口。
 * 抑制规则（按序判定，全部命中才闪）：
 *   1. 提醒开关关闭
 *   2. 主窗口聚焦（消息直接可见）
 *   3. 消息类型不在白名单 2/5/4（系统协议帧不闪，收敛既有全帧闪烁）
 *   4. 自身消息回声（多端同步副本，sendUserId = 当前用户）
 *   5. 过期消息（sendTime 距今 > 5 分钟，重连补推）
 * 闪烁本身（两态，实现修订 2）：最小化 → 600ms 交替闪烁循环（微信式图标闪动）；
 * 非最小化失焦 → 单次 flashFrame 系统静态红底高亮。均至窗口获焦停止，无正文外显、无声音。
 * @param message   WS 帧
 * @param mainWindow 主窗口句柄
 */
const flashOnNewMessage = (message, mainWindow) => {
    try {
        if (!message || !mainWindow) return
        // 规则 1：开关关闭
        if (!notifySwitch) return
        // 规则 2：窗口聚焦不打扰
        if (mainWindow.isFocused()) return
        // 规则 3：类型白名单
        if (!NOTIFY_TYPE_WHITELIST.includes(message.messageType)) return
        // 规则 4：自身回声（群聊自身消息在 wsClient 已 break，此处防御多端同步副本）
        if (message.sendUserId && String(message.sendUserId) === String(store.getUserId())) return
        // 规则 5：过期补推消息（sendTime 为毫秒时间戳；缺失时放行）
        if (message.sendTime && Date.now() - message.sendTime > STALE_MESSAGE_MS) return

        // 两态触发（实现修订 2）：
        // - 最小化：主动交替闪烁循环（单次 flashFrame 在最小化窗口上无闪动动画）
        // - 非最小化失焦：单次 flashFrame 系统静态红底高亮（人工验收确认可接受）
        if (mainWindow.isMinimized()) {
            startBlink(mainWindow)
        } else {
            mainWindow.flashFrame(true)
        }
    } catch (e) {
        // 闪烁失败不影响消息收发主链路
        console.warn('任务栏闪烁失败', e)
    }
}

export {
    initNotifySwitch,
    setNotifySwitch,
    flashOnNewMessage,
    stopBlink
}
