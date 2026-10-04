import { queryCount, queryOne, queryAll, insertOrReplace, update, run } from "./ADB";
import { updateNoReadCount } from "./ChatSessionUserModel"
import store from "../store"

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
    let sql = "select count(1) from chat_message where session_id = ? and user_id = ?";
    const totalCount = await queryCount(sql, [sessionId, store.getUserId()]);
    const { pageTotal, offset, limit } = getPageOffset(pageNo, totalCount);
    const params = [sessionId, store.getUserId()];
    sql = "select * from chat_message where session_id = ? and user_id = ?";
    if (maxMessageId) {
        sql = sql + "and message_id <=?";
        params.push(maxMessageId);
    }
    params.push(offset);
    params.push(limit);
    sql = sql + "order by message_id desc limit ?,?";
    const dataList = await queryAll(sql, params);
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
    const params = [sessionId, store.getUserId()];
    const sql = "select * from chat_message where session_id = ? and user_id = ? order by message_id asc";
    const dataList = await queryAll(sql, params);
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
    return queryOne("select message_id from chat_message where user_id = ? and message_id = ?", [store.getUserId(), messageId]);
}

//本地删除单条消息（多选删除 / 右键删除；服务端保留，仅本端不可见）
const delMessage = (messageId) => {
    const sql = "delete from chat_message where user_id = ? and message_id = ?";
    return run(sql, [store.getUserId(), messageId]);
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
    let sql = "select * from chat_message where message_id = ? and user_id =?";
    const params = [messageId, store.getUserId()];
    return queryOne(sql, params)
}

// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve(v) })`。
// executor 内 await 抛出的异常不会被 Promise 捕获，改为 async 直写。
const searchMessages = async (query) => {
    const { sessionId, keyword, sendUserId, messageType, startTime, endTime, pageNo } = query;

    let sql = "select count(1) from chat_message where session_id = ? and user_id = ?";
    const countParams = [sessionId, store.getUserId()];

    // 构建查询条件
    if (keyword) {
        sql += " and message_content like ?";
        countParams.push(`%${keyword}%`);
    }
    if (sendUserId) {
        sql += " and send_user_id = ?";
        countParams.push(sendUserId);
    }
    if (messageType) {
        sql += " and message_type = ?";
        countParams.push(messageType);
    }
    if (startTime) {
        sql += " and send_time >= ?";
        countParams.push(startTime);
    }
    if (endTime) {
        sql += " and send_time <= ?";
        countParams.push(endTime);
    }

    const totalCount = await queryCount(sql, countParams);
    const { pageTotal, offset, limit } = getPageOffset(pageNo, totalCount);

    // 构建查询语句
    let querySql = "select * from chat_message where session_id = ? and user_id = ?";
    const queryParams = [sessionId, store.getUserId()];

    if (keyword) {
        querySql += " and message_content like ?";
        queryParams.push(`%${keyword}%`);
    }
    if (sendUserId) {
        querySql += " and send_user_id = ?";
        queryParams.push(sendUserId);
    }
    if (messageType) {
        querySql += " and message_type = ?";
        queryParams.push(messageType);
    }
    if (startTime) {
        querySql += " and send_time >= ?";
        queryParams.push(startTime);
    }
    if (endTime) {
        querySql += " and send_time <= ?";
        queryParams.push(endTime);
    }

    queryParams.push(offset);
    queryParams.push(limit);
    querySql += " order by send_time desc limit ?,?";

    const dataList = await queryAll(querySql, queryParams);
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