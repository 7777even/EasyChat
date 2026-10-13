const os = require('os');
import { queryOne, run, insertOrIgnore, update, queryAll } from "./ADB";
import { startLocalServer } from "../file"
import store from "../store"
// 2026-10-12（遗留 #7）：SQL 构造抽到纯核心，本文件退回纯 IO 接线。
import {
    buildContactNoReadCount, buildSelectMaxServerPort, buildSelectSettingByUserId,
    buildSelectSettingInfo, buildSelectLocalEmails, nextServerPort
} from "./dbModelCore.mjs"
const userDir = os.homedir();
// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve() })`。
// executor 内 await 抛出的异常不会被 Promise 捕获，改为 async 直写。
const updateContactNoReadCount = async ({ userId, noReadCount }) => {
    const q = buildContactNoReadCount(noReadCount, userId);
    await run(q.sql, q.params);
}

const addUserSetting = async (userId, email) => {
    const maxQ = buildSelectMaxServerPort();
    let { serverPort } = await queryOne(maxQ.sql, maxQ.params);
    serverPort = nextServerPort(serverPort);
    const sysSetting = {
        localFileFolder: userDir + "\\.easychat\\fileStorage\\"
    }
    const byIdQ = buildSelectSettingByUserId(userId);
    const userInfo = await queryOne(byIdQ.sql, byIdQ.params);

    let resultServerPort = null;
    let localFileFolder = sysSetting.localFileFolder + userId;
    if (userInfo) {
        await update("user_setting", { "email": email }, { "userId": userId });
        resultServerPort = userInfo.serverPort;
        localFileFolder = JSON.parse(userInfo.sysSetting).localFileFolder + userId;
    } else {
        await insertOrIgnore("user_setting", {
            userId: userId,
            email: email,
            sysSetting: JSON.stringify(sysSetting),
            contactNoRead: 0,
            serverPort: serverPort
        });
        resultServerPort = serverPort;
    }
    //启动本地服务
    startLocalServer(resultServerPort);
    store.setUserData("localServerPort", resultServerPort);
    store.setUserData("localFileFolder", localFileFolder);
}

const selectSettingInfo = (userId) => {
    const q = buildSelectSettingInfo(userId);
    return queryOne(q.sql, q.params);
}

const updateSysSetting = (sysSetting) => {
    const data = {
        sysSetting
    }
    const paramData = {
        userId: store.getUserId()
    }
    return update("user_setting", data, paramData);
}

const loadLocalUser = async () => {
    const q = buildSelectLocalEmails();
    return queryAll(q.sql, q.params);
}


export {
    updateContactNoReadCount,
    selectSettingInfo,
    addUserSetting,
    updateSysSetting,
    loadLocalUser
}