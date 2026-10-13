import { run, queryAll, queryOne } from "./ADB";
// 2026-10-12（遗留 #7）：SQL 构造抽到纯核心，本文件退回纯 IO 接线。
import {
    buildInsertLaterHandle, buildSelectLaterHandleList, buildSelectLaterHandleById,
    buildDeleteLaterHandle, buildDeleteExpiredLaterHandle, buildSelectNeedRemindLaterHandle
} from "./dbModelCore.mjs"

/**
 * 稍后处理 Model 层
 */

// 添加稍后处理
const addLaterHandle = async (row) => {
    const q = buildInsertLaterHandle(row);
    await run(q.sql, q.params);
}

// 查询稍后处理列表
const selectLaterHandleList = async (userId) => {
    const q = buildSelectLaterHandleList(userId);
    return await queryAll(q.sql, q.params);
}

// 查询单条稍后处理
const selectLaterHandleById = async (id) => {
    const q = buildSelectLaterHandleById(id);
    return await queryOne(q.sql, q.params);
}

// 删除稍后处理
const deleteLaterHandle = async (id) => {
    const q = buildDeleteLaterHandle(id);
    await run(q.sql, q.params);
}

// 删除已过期稍后处理
const deleteExpiredLaterHandle = async (currentTime) => {
    const q = buildDeleteExpiredLaterHandle(currentTime);
    await run(q.sql, q.params);
}

// 查询需要提醒的稍后处理
const selectNeedRemindLaterHandle = async (currentTime) => {
    const q = buildSelectNeedRemindLaterHandle(currentTime);
    return await queryAll(q.sql, q.params);
}

export {
    addLaterHandle,
    selectLaterHandleList,
    selectLaterHandleById,
    deleteLaterHandle,
    deleteExpiredLaterHandle,
    selectNeedRemindLaterHandle
};
