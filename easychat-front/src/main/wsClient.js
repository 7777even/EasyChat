import WebSocket from 'ws'
const NODE_ENV = process.env.NODE_ENV
import { saveMessage, saveMessageBatch, updateMessage } from "./db/ChatMessageModel"
import {
    saveOrUpdateChatSessionBatch4Init, saveOrUpdate4Message,
    updateGroupName, delChatSession, selectUserSessionByContactId,
    updateSessionBySessionId
} from "./db/ChatSessionUserModel"
import { updateContactNoReadCount } from "./db/UserSetting"
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

const sendClientAck = (ackType, messageIds) => {
    if (ws != null && ws.readyState === 1 && messageIds && messageIds.length > 0) {
        ws.send(JSON.stringify({ messageType: -3, extendData: { ackType, messageIds } }));
    }
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
        //信息消息闪烁
        if (!mainWindow.isFocused()) {
            mainWindow.flashFrame(true);
        }
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
            case -5: { // ACK_NOTIFY：服务端通知发送方，对方已送达/已读
                // 通知渲染层更新消息的已读/送达状态
                // 注意：ackUserId 由服务端放在 contactId 字段中传递
                sender.send('ackNotify', {
                    messageId: message.messageId,
                    ackUserId: message.contactId,    // 执行 ack 的用户 ID
                    contactType: message.contactType,
                    ackType: message.extendData     // 2=已送达, 3=已读
                });
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
            case 2://聊条消息
            case 5://图片，视频消息
            case 8://解散群聊
            case 11://退出群聊
            case 12://提出群聊
            case 14://撤回消息
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
                //撤回消息更新本地消息，不新增消息
                if (messageType == 14) {
                    updateMessage({ 
                        messageType: 14, 
                        messageContent: message.messageContent || '该消息已撤回',
                        status: 1  // 确保消息状态为已发送
                    }, { messageId: message.messageId });
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
    sendClientAck
}