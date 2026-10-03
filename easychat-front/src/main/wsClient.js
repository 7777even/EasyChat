import WebSocket from 'ws'
const NODE_ENV = process.env.NODE_ENV
import { saveMessage, saveMessageBatch, updateMessage, existsMessage } from "./db/ChatMessageModel"
import {
    saveOrUpdateChatSessionBatch4Init, saveOrUpdate4Message,
    updateGroupName, delChatSession, selectUserSessionByContactId,
    updateSessionBySessionId, topChatSession, updateSessionAttr,
    updateSessionPreviewOnly
} from "./db/ChatSessionUserModel"
import { updateContactNoReadCount } from "./db/UserSetting"
import { flashOnNewMessage } from "./notification"
import { getWindow } from "./windowProxy";
import store from "./store"
//ws相关
let ws = null
//重连次数
let maxReConnectTimes = null;

// ===== 消息可靠性协议状态 =====
// 每个 sessionId 当前已收到的最大 seq（用于 SYNC 补推）
const lastSeqMap = new Map();
// 待确认消息：key = clientId, value = { messageId, timer, messageObj }
const pendingMap = new Map();
// ACK 超时 30 秒
const ACK_TIMEOUT = 30000;
//避免onclose onerror都重连相当于加个锁
let lockReconnect = false;

// ===== 消息可靠性协议辅助方法（模块顶层，供 IPC 调用） =====
const sendHeartbeat = () => {
    if (ws != null && ws.readyState === 1) {
        const hb = JSON.stringify({ messageType: -4 });
        ws.send(hb);
    }
}

const sendSyncFrame = () => {
    if (ws != null && ws.readyState === 1 && lastSeqMap.size > 0) {
        const sync = {};
        for (const [sid, seq] of lastSeqMap.entries()) {
            sync[sid] = seq;
        }
        ws.send(JSON.stringify({ messageType: -2, extendData: { sync } }));
        console.log('SYNC 补推请求, sessions=' + lastSeqMap.size);
    }
}

const sendCallFrame = (frame) => {
    if (ws != null && ws.readyState === 1) {
        ws.send(JSON.stringify(frame));
    }
}

// ===== 输入状态与在线状态 =====
// 输入状态防抖计时器
let typingTimer = null;
const TYPING_DEBOUNCE_MS = 3000;

/**
 * 发送正在输入状态帧（防抖 3 秒）
 * @param {string} contactId 对方用户 ID
 * @param {string} sessionId 会话 ID
 * @param {boolean} typing 是否正在输入
 */
const sendTypingStatus = (contactId, sessionId, typing) => {
    if (ws == null || ws.readyState !== 1) {
        return;
    }
    // 清除之前的防抖计时器
    if (typingTimer) {
        clearTimeout(typingTimer);
    }
    // 如果正在输入，立即发送；如果停止输入，延迟 3 秒发送（避免频繁发送）
    const sendFn = () => {
        const frame = {
            messageType: 21, // TYPING_STATUS
            contactId: contactId,
            typing: typing,
            sessionId: sessionId
        };
        ws.send(JSON.stringify(frame));
        console.log('发送输入状态帧: contactId=' + contactId + ', typing=' + typing);
    };
    if (typing) {
        sendFn();
    } else {
        typingTimer = setTimeout(sendFn, TYPING_DEBOUNCE_MS);
    }
}

/**
 * 发送用户状态变更帧
 * @param {number} status 状态值（1=在线 2=忙碌 3=离线）
 */
const sendUserStatusChange = (status) => {
    if (ws == null || ws.readyState !== 1) {
        return;
    }
    const frame = {
        messageType: 23, // USER_STATUS_CHANGE
        status: status
    };
    ws.send(JSON.stringify(frame));
    console.log('发送用户状态变更帧: status=' + status);
}

const registerPendingAck = (clientId, messageObj) => {
    if (pendingMap.has(clientId)) {
        clearTimeout(pendingMap.get(clientId).timer);
    }
    const timer = setTimeout(() => {
        pendingMap.delete(clientId);
        console.warn('ACK 超时, clientId=' + clientId);
        sender.send('addLocalCallback', { clientId, status: 0, timeout: true });
    }, ACK_TIMEOUT);
    pendingMap.set(clientId, { timer, messageObj });
}

//wsUrl
let wsUrl = null;
let sender = null;
let needReconnect = null;

const initWs = (config, _sender) => {
    wsUrl = `${NODE_ENV !== 'development' ? store.getData("prodWsDomain") : store.getData("devWsDomain")}?token=${config.token}`;
    sender = _sender;
    // 供 notification.js 在 toast 点击时回推渲染层（定位会话）
    global.__easychatSender = _sender;
    needReconnect = true;
    maxReConnectTimes = 20;
    createWs();
}

const closeWs = () => {
    needReconnect = false;
    ws.close();
}

const createWs = () => {
    if (wsUrl == null) {
        return
    }
    ws = new WebSocket(wsUrl)
    ws.onopen = function (params) {
        console.log('客户端连接成功')
        sendHeartbeat()
        maxReConnectTimes = 20
        // 连接后立即发送 SYNC 帧，请求补推本地离线期间错过的消息
        sendSyncFrame()
    }

    // 从服务器接受到信息时的回调函数
    ws.onmessage = async function (e) {
        let mainWindow = getWindow("main");
        console.log('收到服务器消息', e.data)
        const message = JSON.parse(e.data);
        const leaveGroupUserId = message.extendData;
        const messageType = message.messageType;
        switch (messageType) {
            case 0://ws链接成功
                //保存会话信息
                await saveOrUpdateChatSessionBatch4Init(message.extendData.chatSessionList);
                //保存聊天消息
                await saveMessageBatch(message.extendData.chatMessageList);
                //保存好友通知消息数
                await updateContactNoReadCount({ userId: store.getUserId(), noReadCount: message.extendData.applyCount });
                //发送消息
                sender.send("reciveMessage", { messageType: message.messageType });
                break;
            case 4://好友申请
                await updateContactNoReadCount({ userId: store.getUserId(), noReadCount: 1 });
                sender.send("reciveMessage", { messageType: message.messageType });
                //新消息任务栏闪烁（抑制规则见 notification.js）
                flashOnNewMessage(message, mainWindow);
                break;
            case 6://文件上传完成
                updateMessage({ status: message.status }, { messageId: message.messageId });
                sender.send("reciveMessage", message);
                break;
            case 10://修改群昵称
                updateGroupName(message.contactId, message.extendData);
                sender.send("reciveMessage", message);
                break;
            case 7://强制下线
                sender.send("reciveMessage", message);
                closeWs();
                break;
            case 1: //添加好友成功
            case 3://群创建成功
            case 9://好友加入群组
            case -1: { // ACK：服务端确认已接收消息（更新本地 pending 状态为已发送）
                const ack = message;
                const ackClientId = ack.clientId;
                const ackMessageId = ack.messageId;
                const ackSeq = ack.seq;
                if (ackClientId && pendingMap.has(ackClientId)) {
                    const pending = pendingMap.get(ackClientId);
                    clearTimeout(pending.timer);
                    pendingMap.delete(ackClientId);
                    // 更新本地消息状态：messageId + status
                    if (ackMessageId != null) {
                        updateMessage(
                            { messageId: ackMessageId, status: 1, extendData: null },
                            { messageId: ackMessageId }
                        );
                        sender.send('addLocalCallback', { messageId: ackMessageId, status: 1 });
                    }
                    console.log('ACK 收到, clientId=' + ackClientId + ', seq=' + ackSeq);
                }
                // ACK 不需要渲染器展示，仅作状态更新
                break;
            }
            case -6: { // SYNC_SESSION：跨端会话同步——更新本地会话元数据并通知渲染层
                const sessionData = message.extendData;
                if (sessionData && sessionData.sessionId) {
                    // 更新本地 SQLite 会话信息
                    const dbSessionUpdate = {
                        lastMessage: sessionData.lastMessage || '',
                        lastReceiveTime: sessionData.lastReceiveTime || Date.now(),
                        contactType: sessionData.contactType
                    };
                    await updateSessionBySessionId(dbSessionUpdate, sessionData.sessionId);

                    // 通知渲染层同步刷新会话列表
                    sender.send('syncSession', sessionData);
                    console.log('SYNC_SESSION 收到, sessionId=' + sessionData.sessionId);
                }
                break;
            }
            case 15://朋友圈新动态
            case 16://朋友圈点赞
            case 17://朋友圈评论/回复
            case 18://朋友圈@提醒
                // 朋友圈通知：不进会话，只推给渲染层做红点 / toast / 通知中心
                sender.send("momentNotify", message);
                flashOnNewMessage(message, mainWindow);
                break;
            case -7: { // SYNC_SESSION_USER：会话属性跨端同步（置顶/免打扰/草稿）
                const syncData = message.extendData || {};
                const attrMap = { top: 'topType', noDisturb: 'noDisturb', draft: 'draft' };
                const attrName = attrMap[syncData.action];
                if (syncData.contactId && attrName) {
                    await updateSessionAttr(syncData.contactId, attrName, syncData.value);
                    sender.send('syncSessionUser', syncData);
                    console.log('SYNC_SESSION_USER 收到, action=' + syncData.action);
                }
                break;
            }
            case -8: { // 朋友圈未读通知数变化：实时点亮/消除朋友圈红点
                sender.send("momentUnread", message.extendData || {});
                break;
            }
            case -10: // CALL_INVITE
            case -11: // CALL_ACCEPT
            case -12: // CALL_REJECT
            case -13: // CALL_SIGNAL
            case -14: // CALL_HANGUP
            case -15: // CALL_CANCEL
            case -16: // CALL_BUSY
            case -17: { // CALL_JOIN
                // 通话信令帧：整帧转发渲染进程（不落库、不触发普通消息逻辑，媒体 P2P 不经服务器）
                sender.send("callMessage", message);
                break;
            }
            case 2://聊条消息
            case 5://图片，视频消息
            // 2026-10-03 接通位置(25)/语音(24)：与文本/媒体同属「真实消息」路径，
            // 走同一套「落本地 SQLite + reciveMessage 推渲染层」逻辑。
            // 此前缺 case → 对端实时收不到（静默失效），已被
            // scripts/verify/verify_ws_frame_parity.mjs 抓出。
            case 24://语音消息
            case 25://位置消息
            case 8://解散群聊
            case 11://退出群聊
            case 12://提出群聊
            case 14://撤回消息
            case 19://群公告更新
            case 26://拍一拍
                //如果是群聊消息，那么这个群里的所有人都会收到聊天消息，发送人和接收人是同一个人不做处理
                if (message.sendUserId === store.getUserId() && message.contactType == 1 && messageType != 14) {
                    break;
                }
                //防御：单聊消息的 contactId 异常为“当前用户自己”（历史离线补推脏数据导致），
                //按发送方修正，避免再次以 contact_id=自己 新建脏会话、误用自己头像
                if (message.contactType == 0 && message.sendUserId
                    && String(message.contactId) === String(store.getUserId())
                    && String(message.sendUserId) !== String(store.getUserId())) {
                    message.contactId = message.sendUserId;
                    if (!message.contactName) {
                        message.contactName = message.sendUserNickName;
                    }
                }
                // 维护 lastSeq（有 seq 的消息）
                if (message.seq != null && message.sessionId) {
                    const cur = lastSeqMap.get(message.sessionId) || 0;
                    if (message.seq > cur) {
                        lastSeqMap.set(message.sessionId, message.seq);
                    }
                }
                //收到ws消息更新会话信息
                const sessionInfo = {};
                if (message.extendData && typeof message.extendData === "object") {
                    Object.assign(sessionInfo, message.extendData);
                } else {
                    Object.assign(sessionInfo, message);
                    //单聊更新联系人名称
                    if (message.contactType == 0 && messageType != 1) {
                        sessionInfo.contactName = message.sendUserNickName;
                    }
                    sessionInfo.lastReceiveTime = message.sendTime;
                }
                //11退出群聊 12移除群聊 减少成员数量
                if (messageType == 9 || messageType == 12 || messageType == 11) {
                    sessionInfo.memberCount = message.memberCount;
                }
                console.log("sessionInfo", sessionInfo);
                await saveOrUpdate4Message(store.getUserData("currentSessionId"), sessionInfo);
                //撤回消息：本地已有则更新；本地缺行（离线期间被撤回、SYNC补推首达撤回帧）则补插入历史，避免撤回消息丢失
                if (messageType == 14) {
                    const recallInfo = { 
                        messageType: 14, 
                        messageContent: message.messageContent || '该消息已撤回',
                        status: 1  // 确保消息状态为已发送
                    };
                    const exists = await existsMessage(message.messageId);
                    if (exists != null && exists.messageId != null) {
                        await updateMessage(recallInfo, { messageId: message.messageId });
                    } else {
                        await saveMessage(Object.assign({}, message, recallInfo));
                    }
                } else {
                    //写入本地消息
                    await saveMessage(message);
                }
                //查询本地session 单聊联系人就是发送人，群聊联系人就是群号
                const dbSessionInfo = await selectUserSessionByContactId(message.contactId);
                message.extendData = dbSessionInfo;
                //退出群聊，当前用户不收到消息
                if (messageType == 11 && leaveGroupUserId == store.getUserId()) {
                    break;
                }
                sender.send("reciveMessage", message);
                //新消息提醒：置于自身回声跳过分支之后（sendUserId==自己 已在上方 break），
                //类型白名单与其余抑制规则在 notification.js 内判定
                //免打扰会话：本地 SQLite 的 no_disturb=1 时不闪不响
                const notifySession = await selectUserSessionByContactId(message.contactId);
                if (!notifySession || notifySession.noDisturb != 1) {
                    flashOnNewMessage(message, mainWindow);
                    // 提示音：由渲染层按提醒开关播放（主进程不便发声）
                    sender.send("playNotifySound", {
                        messageType,
                        contactId: message.contactId,
                        contactType: message.contactType
                    });
                }
                break;
            case 20: {
                // 管理端删除消息（墓碑）：本地行墓碑化 + 条件预览占位；
                // 不计未读、不闪通知、不放提示音（ADR-002/003/004）
                // 防御：单聊副本 contactId 异常为「当前用户自己」时按发送方修正（与 14 帧同款）
                if (message.contactType == 0 && message.sendUserId
                    && String(message.contactId) === String(store.getUserId())
                    && String(message.sendUserId) !== String(store.getUserId())) {
                    message.contactId = message.sendUserId;
                    if (!message.contactName) {
                        message.contactName = message.sendUserNickName;
                    }
                }
                // 条件预览：仅帧带 lastMessage 才改写占位（不刷 last_receive_time、不计未读，ADR-004）
                if (message.lastMessage) {
                    await updateSessionPreviewOnly(message.contactId, message.lastMessage);
                }
                // 本地行墓碑化：已有则更新、缺行（离线错过在线帧）则补插（仿 14 帧）
                const tombstoneInfo = {
                    messageType: 20,
                    messageContent: message.messageContent || "该消息已被管理员删除",
                    status: 1
                };
                const exists20 = await existsMessage(message.messageId);
                if (exists20 != null && exists20.messageId != null) {
                    await updateMessage(tombstoneInfo, { messageId: message.messageId });
                } else {
                    await saveMessage(Object.assign({}, message, tombstoneInfo));
                }
                // 转发渲染进程（extendData=本地会话行，供 Chat.vue 会话内墓碑渲染）
                const dbSession20 = await selectUserSessionByContactId(message.contactId);
                message.extendData = dbSession20;
                sender.send("reciveMessage", message);
                break;
            }
            case 21: { // TYPING_STATUS：正在输入状态帧
                // 转发给渲染进程，由 Chat.vue 显示"正在输入..."提示
                sender.send("typingStatus", message);
                break;
            }
            case 22: { // ONLINE_STATUS：在线状态变更帧
                // 转发给渲染进程，由 Contact.vue 显示好友在线状态
                sender.send("onlineStatus", message);
                break;
            }
            case 27: { // ONLINE_STATUS_HIDDEN：对方关闭了「展示在线状态」
                // 抹除该联系人已显示的在线状态点，否则好友会看到对方「卡在在线」直到其掉线
                // （openspec/specs/privacy-settings）
                sender.send("onlineStatusHidden", message);
                break;
            }
            default:
                // 未知帧类型必须显式忽略：服务端后续新增帧号时，
                // 旧客户端不能因未匹配 case 而静默中断后续帧处理
                break;
        }
    }

    // 连接关闭后的回调函数存储数据
    ws.onclose = function (evt) {
        console.log('关闭客户端连接准备重连')
        reconnect('onclose')
    }

    // 连接失败后的回调函数
    ws.onerror = function (evt) {
        console.log('连接失败了准备重连')
        reconnect('onerror')
    }

    // （辅助方法已移至模块顶层）

    const reconnect = (type) => {
        if (!needReconnect) {
            console.log("链接断开无须重连");
            return;
        }
        if (ws != null) {
            ws.close()
        }
        if (lockReconnect) {
            return;
        }
        console.log(type + "准备重连");
        lockReconnect = true;
        if (maxReConnectTimes > 0) {
            console.log('准备重连，剩余重连次数' + maxReConnectTimes, new Date().getTime())
            maxReConnectTimes--
            // 进行重连
            setTimeout(function () {
                createWs()
                lockReconnect = false;
            }, 5000)
        } else {
            // 重连次数耗尽也不放弃：重置配额继续周期重试，确保后端重启等场景下能自动恢复
            console.log('重连次数已耗尽，5 秒后继续重试');
            maxReConnectTimes = 20;
            setTimeout(function () {
                lockReconnect = false;
                createWs()
            }, 5000)
        }
    }

    // 发送心跳（改用 JSON 格式，服务器识别为 -4 心跳类型）
    setInterval(() => {
        if (ws != null && ws.readyState == 1) {
            sendHeartbeat()
        }
    }, 1000 * 5);
}

export {
    initWs,
    closeWs,
    registerPendingAck,
    sendSyncFrame,
    sendCallFrame,
    sendTypingStatus,
    sendUserStatusChange
}