
import { run, queryAll, insertOrIgnore, update, queryOne } from "./ADB";
import store from "../store"
// 2026-10-12（遗留 #7）：SQL 构造抽到纯核心，本文件退回纯 IO 接线。
import {
    buildUpdateNoReadCount, buildSelectUserSessionList, buildSelectSessionByContactId,
    buildReadAll, buildSessionWhere, buildSessionUpdateInfo,
    buildSessionAttrUpdate, buildUpdateSessionInfo, buildSimpleSessionUpdate,
    buildSessionPreviewOnly, SESSION_USER_ATTR_WHITELIST
} from "./dbModelCore.mjs"


//新增
const addChatSession = (sessionInfo) => {
    sessionInfo.userId = store.getUserId();
    insertOrIgnore("chat_session_user", sessionInfo);
}

//更新会话信息
const updateChatSession = (sessionInfo) => {
    const paramData = buildSessionWhere(store.getUserId(), sessionInfo.contactId);
    const updateInfo = buildSessionUpdateInfo(sessionInfo);
    return update("chat_session_user", updateInfo, paramData);
}

//批量保存会话
// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve() })`。
// executor 内 await 抛出的异常不会被 Promise 捕获（会逃逸成 unhandled rejection），
// 故改为 async 函数直写。
//
// 注：原实现的 catch 分支**吞掉异常后仍 resolve()**，即「初始化失败」与「成功」
// 对调用方表现完全相同。本次**保留该语义**（改掉会让会话初始化失败从静默
// 变成阻断主流程，属行为变更），仅补一条 warn 使其不再完全无声。
const saveOrUpdateChatSessionBatch4Init = async (chatSessionList) => {
    try {
        for (let i = 0; i < chatSessionList.length; i++) {
            const sessionInfo = chatSessionList[i]
            sessionInfo.status = 1;
            let sessionData = await selectUserSessionByContactId(sessionInfo.contactId);
            if (sessionData) {
                await updateChatSession(sessionInfo);
            } else {
                await addChatSession(sessionInfo);
            }
        }
    } catch (error) {
        console.warn('[db] saveOrUpdateChatSessionBatch4Init 失败（已忽略，维持既有语义）', error);
    }
}

//更新未读数
const updateNoReadCount = ({ contactId, noReadCount }) => {
    const q = buildUpdateNoReadCount(store.getUserId(), contactId, noReadCount);
    return run(q.sql, q.params);
}

//查询用户的会话列表
const selectUserSessionList = () => {
    const q = buildSelectUserSessionList(store.getUserId());
    return queryAll(q.sql, q.params);
}
//根据联系人查询会话
const selectUserSessionByContactId = (concatId) => {
    const q = buildSelectSessionByContactId(store.getUserId(), concatId);
    return queryOne(q.sql, q.params);
}

//阅读会话所有消息
const readAll = (contactId) => {
    const q = buildReadAll(store.getUserId(), contactId);
    return run(q.sql, q.params);
}

//根据 sessionId 更新会话元数据（用于跨端 SYNC_SESSION 同步）
const updateSessionBySessionId = (updateInfo, sessionId) => {
    const q = buildSessionAttrUpdate(store.getUserId(), sessionId, updateInfo);
    // ⚠ 无可更新字段时**直接返回**、不拼 SQL —— 拼出 `set  where ...` 是语法错
    if (!q.hasClause) {
        return Promise.resolve();
    }
    return run(q.sql, q.params);
}


//收到消息新增或者更新会话
// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve() })`，
// executor 内 await 抛出的异常不会进 .catch()，改为 async 直写。
const saveOrUpdate4Message = async (currentSessionId, sessionInfo) => {
    let sessionData = await selectUserSessionByContactId(sessionInfo.contactId);
    if (sessionData) {
        await updateSessionInfo4Message(currentSessionId, sessionInfo);
    } else {
        sessionInfo.noReadCount = 1;
        await addChatSession(sessionInfo);
    }
}
//收到消息更新会话
const updateSessionInfo4Message = async (currentSessionId, sessionInfo) => {
    const q = buildUpdateSessionInfo(store.getUserId(), currentSessionId, sessionInfo);
    return run(q.sql, q.params);
}

//删除会话
const delChatSession = (contactId) => {
    const paramData = {
        userId: store.getUserId(),
        contactId
    }
    const sessionInfo = {
        status: 0
    }
    return update("chat_session_user", sessionInfo, paramData);
}
//置顶会话
const topChatSession = (contactId, topType) => {
    const paramData = {
        userId: store.getUserId(),
        contactId
    }
    const sessionInfo = {
        topType
    }
    return update("chat_session_user", sessionInfo, paramData);
}

//更新群名称
const updateGroupName = (contactId, groupName) => {
    const paramData = buildSessionWhere(store.getUserId(), contactId);
    const sessionInfo = buildSimpleSessionUpdate('contactName', groupName);
    return update("chat_session_user", sessionInfo, paramData);
}

const updateStatus = (contactId) => {
    const paramData = buildSessionWhere(store.getUserId(), contactId);
    const sessionInfo = buildSimpleSessionUpdate('status', 1);
    return update("chat_session_user", sessionInfo, paramData);
}

/**
 * 会话用户级属性同步（置顶 / 免打扰 / 草稿）：服务端是真源，本地只是缓存，
 * 收到跨端同步帧后直接落本地库，保证多端表现一致。
 */
const updateSessionAttr = (contactId, attrName, attrValue) => {
    const allowKeys = {};
    for (const k of SESSION_USER_ATTR_WHITELIST) allowKeys[k] = 1;
    if (!allowKeys[attrName]) {
        return Promise.resolve();
    }
    //同步帧可能不带值（null / undefined，如对端未设置该项），落库会拼出空 set 子句，直接跳过
    if (attrValue === null || attrValue === undefined) {
        return Promise.resolve();
    }
    const paramData = {
        userId: store.getUserId(),
        contactId
    }
    const sessionInfo = {};
    sessionInfo[attrName] = attrValue;
    return update("chat_session_user", sessionInfo, paramData);
}

//管理端删除帧（20）条件预览占位：仅改写 last_message，
//不刷 last_receive_time（防会话重排）、不计 no_read_count（防未读脏增）——ADR-004
const updateSessionPreviewOnly = (contactId, lastMessage) => {
    const q = buildSessionPreviewOnly(store.getUserId(), contactId, lastMessage);
    return run(q.sql, q.params);
}


export {
    updateSessionInfo4Message,
    saveOrUpdate4Message,
    saveOrUpdateChatSessionBatch4Init,
    selectUserSessionList,
    selectUserSessionByContactId,
    updateNoReadCount,
    readAll,
    updateSessionBySessionId,
    delChatSession,
    updateGroupName,
    topChatSession,
    updateStatus,
    updateSessionAttr,
    updateSessionPreviewOnly
}