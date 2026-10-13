import { queryCount, queryOne, queryAll, insertOrReplace, update, run } from "./ADB";
import { updateNoReadCount } from "./ChatSessionUserModel"
import store from "../store"
// 2026-10-12（遗留 #7）：SQL 构造抽到纯核心，ADB.js 是唯一的 sqlite3 加载点，
//   本文件退回纯 IO 接线。核心位置见 dbModelCore.mjs 顶部说明。
import {
    buildExistsMessage, buildDelMessage, buildSelectByMessageId,
    buildMessagePage, buildMessagePageCount, buildSelectAllMessageList,
    buildSearchCount, buildSearchPage
} from "./dbModelCore.mjs"

const getPageOffset = (pageNo = 1, totalCount) => {
    const pageSize = 20;
    const pageTotal = (totalCount % pageSize) == 0 ? totalCount / pageSize : Number.parseInt(totalCount / pageSize) + 1;
    //pageNo小于等于1就是1
    pageNo = pageNo <= 1 ? 1 : pageNo;
    //pageNo大于等于总数就是总数
    pageNo = pageNo >= pageTotal ? pageTotal : pageNo;
    return {
        pageTotal,
        offset: (pageNo - 1) * pageSize,
        limit: pageSize
    }
}

// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve(v) })`。
// executor 内 await 抛出的异常不会被 Promise 捕获（逃逸成 unhandled rejection，
// 调用方 .catch() 收不到），故改为 async 函数直写。
const selectMessageList = async (query) => {
    const { sessionId, pageNo, maxMessageId } = query;
    const userId = store.getUserId();
    const countQ = buildMessagePageCount(userId, sessionId);
    const totalCount = await queryCount(countQ.sql, countQ.params);
    const { pageTotal, offset, limit } = getPageOffset(pageNo, totalCount);
    const q = buildMessagePage(userId, sessionId, maxMessageId, offset, limit);
    const dataList = await queryAll(q.sql, q.params);
    return {
        dataList,
        pageTotal,
        pageNo
    }
}

/**
 * 导出用：按会话全量读取消息（不分页），按 message_id 升序。
 * 与 selectMessageList 的区别：后者是聊天窗口翻页用的分页倒序查询，不适合导出。
 */
const selectAllMessageList = async (query) => {
    const { sessionId } = query;
    const q = buildSelectAllMessageList(store.getUserId(), sessionId);
    const dataList = await queryAll(q.sql, q.params);
    return dataList || [];
}

const saveMessage = (data) => {
    data.userId = store.getUserId();
    return insertOrReplace("chat_message", data);
}


const updateMessage = (data, paramData) => {
    paramData.userId = store.getUserId();
    return update("chat_message", data, paramData);
}

//查询本地消息是否已存在（撤回帧补落历史：本地缺行时需插入而非仅更新）
const existsMessage = (messageId) => {
    const q = buildExistsMessage(store.getUserId(), messageId);
    return queryOne(q.sql, q.params);
}

//本地删除单条消息（多选删除 / 右键删除；服务端保留，仅本端不可见）
const delMessage = (messageId) => {
    const q = buildDelMessage(store.getUserId(), messageId);
    return run(q.sql, q.params);
}

// 2026-10-04 修正两处（原为 `new Promise(async (resolve, reject) => {` 包裹）：
//   ① executor 内 await 抛出的异常不会被 Promise 捕获，会逃逸成 unhandled
//      rejection，调用方的 .catch() 收不到 → 静默失败
//   ② `chatMessageList.forEach(async item => { await saveMessage(item) })`
//      用了 async 回调，而 **forEach 不等待异步回调** —— 紧随其后的 resolve()
//      会在所有插入完成之前就执行，即函数提前返回、数据仍在后台写入。
//      改为 for...of 顺序 await，语义与「批量保存」这个名字一致。
const saveMessageBatch = async (chatMessageList) => {
    //插入聊天数据
    const chatSessionCountMap = {};
    chatMessageList.forEach(item => {
        let contactId = item.contactType == 1 ? item.contactId : item.sendUserId;
        let noReadCount = chatSessionCountMap[contactId];
        if (!noReadCount) {
            chatSessionCountMap[contactId] = 1;
        } else {
            chatSessionCountMap[contactId] = noReadCount + 1;
        }
    });
    //跟新未读数
    for (let item in chatSessionCountMap) {
        await updateNoReadCount({ contactId: item, noReadCount: chatSessionCountMap[item] })
    }
    //批量插入（顺序 await，确保返回时数据确已落库）
    for (const item of chatMessageList) {
        await saveMessage(item);
    }
}


const selectByMessageId = (messageId) => {
    const q = buildSelectByMessageId(store.getUserId(), messageId);
    return queryOne(q.sql, q.params)
}

// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve(v) })`。
// executor 内 await 抛出的异常不会被 Promise 捕获，改为 async 直写。
const searchMessages = async (query) => {
    const { sessionId, pageNo } = query;
    const userId = store.getUserId();

    // ⚠ 2026-10-12：原先此处把条件构造**复制了两份**（count 一份、分页一份，逐字相同）。
    //   两份若改一处漏一处，count 与实际结果会对不上，且**不抛异常**（表现为
    //   「页码总数与实际条数不符」）。现统一走 buildSearchConditions，不再重复。
    const countQ = buildSearchCount(userId, sessionId, query);
    const totalCount = await queryCount(countQ.sql, countQ.params);
    const { pageTotal, offset, limit } = getPageOffset(pageNo, totalCount);

    const pageQ = buildSearchPage(userId, sessionId, query, offset, limit);
    const dataList = await queryAll(pageQ.sql, pageQ.params);
    return {
        dataList,
        pageTotal,
        pageNo,
        totalCount
    }
}

export {
    saveMessage,
    updateMessage,
    existsMessage,
    delMessage,
    selectMessageList,
    selectAllMessageList,
    saveMessageBatch,
    selectByMessageId,
    searchMessages
}