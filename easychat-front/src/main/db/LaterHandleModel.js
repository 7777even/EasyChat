import { run, insert, queryAll, queryOne, del } from "./ADB";

/**
 * 稍后处理 Model 层
 */

// 添加稍后处理
const addLaterHandle = async ({ userId, messageId, sessionId, contactId, contactName, content, createTime, remindTime }) => {
    const sql = `INSERT INTO later_handle (user_id, message_id, session_id, contact_id, contact_name, content, create_time, remind_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`;
    await run(sql, [userId, messageId, sessionId, contactId, contactName, content, createTime, remindTime]);
}

// 查询稍后处理列表
const selectLaterHandleList = async (userId) => {
    const sql = `SELECT * FROM later_handle WHERE user_id = ? ORDER BY create_time DESC`;
    return await queryAll(sql, [userId]);
}

// 查询单条稍后处理
const selectLaterHandleById = async (id) => {
    const sql = `SELECT * FROM later_handle WHERE id = ?`;
    return await queryOne(sql, [id]);
}

// 删除稍后处理
const deleteLaterHandle = async (id) => {
    const sql = `DELETE FROM later_handle WHERE id = ?`;
    await run(sql, [id]);
}

// 删除已过期稍后处理
const deleteExpiredLaterHandle = async (currentTime) => {
    const sql = `DELETE FROM later_handle WHERE remind_time < ?`;
    await run(sql, [currentTime]);
}

// 查询需要提醒的稍后处理
const selectNeedRemindLaterHandle = async (currentTime) => {
    const sql = `SELECT * FROM later_handle WHERE remind_time <= ?`;
    return await queryAll(sql, [currentTime]);
}

export {
    addLaterHandle,
    selectLaterHandleList,
    selectLaterHandleById,
    deleteLaterHandle,
    deleteExpiredLaterHandle,
    selectNeedRemindLaterHandle
};
