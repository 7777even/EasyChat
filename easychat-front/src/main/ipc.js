import { shell, BrowserWindow, ipcMain, clipboard } from 'electron';
const NODE_ENV = process.env.NODE_ENV
import { join } from 'path'
import { is } from '@electron-toolkit/utils'
import { initWs, closeWs, registerPendingAck } from './wsClient';
import { initNotifySwitch, setNotifySwitch } from './notification';
import { exportChatRecord } from './exportChat';
import { selectMessageList, saveMessage, updateMessage, existsMessage, delMessage } from "./db/ChatMessageModel";
import { selectUserSessionList, updateSessionInfo4Message, readAll, delChatSession, topChatSession, updateStatus, updateSessionAttr } from "./db/ChatSessionUserModel";
import { addUserSetting, selectSettingInfo, updateContactNoReadCount, loadLocalUser, updateSysSetting } from "./db/UserSetting";
import {
    saveFile2Local, checkFile, createCover, saveAs, changeLocalFolder, openLocalFolder,
    downloadUpdate, closeLocalServer, saveClipBoardFile
} from "./file"

import { saveWindow, getWindow, delWindow, windowManage } from './windowProxy'

import store from "./store"
import icon from '../../resources/icon.png?asset'

const onLoginOrRegister = (callback) => {
    ipcMain.on("loginOrRegister", (e, isLogin) => {
        callback(isLogin);
    })
}

//登录成功
const onLoginSuccess = (callback) => {
    ipcMain.on("openChat", async (e, config) => {
        store.initUserId(config.userId);
        store.setUserData("token", config.token);
        addUserSetting(config.userId, config.email);
        //初始化新消息提醒开关内存缓存（读 user_setting.sysSetting.notifySwitch，缺省 true）
        initNotifySwitch();
        callback(config);
        initWs(config, e.sender);
    })
}

//设置本地存储
const onSetLocalStore = () => {
    ipcMain.on("setLocalStore", (e, { key, value }) => {
        store.setData(key, value);
    })
}
//获取本地存储
const onGetLocalStore = () => {
    ipcMain.on("getLocalStore", (e, key) => {
        e.sender.send("getLocalStoreCallback", store.getData(key))
    })
}


//重新登录
const onReLogin = (callback) => {
    ipcMain.on("reLogin", (e, data) => {
        for (let win in windowManage) {
            if (win !== "main") {
                windowManage[win].close();
            }
        }
        callback();
        e.sender.send("reLogin")
        closeWs();
        closeLocalServer();
    })
}


const winTitleOp = (callback) => {
    ipcMain.on("winTitleOp", (e, data) => {
        callback(e, data);
    });
}

const onLoadChatMessage = () => {
    ipcMain.on("loadChatMessage", async (e, data) => {
        const result = await selectMessageList(data)
        e.sender.send("loadChatMessage", result);
    });
}

const onLoadSessionData = () => {
    ipcMain.on("loadSessionData", async (e) => {
        console.log("开始查询session");
        const result = await selectUserSessionList()
        e.sender.send("loadSessionDataCallback", result);
    });
}

//设置选中的会话session
const onSetSessionSelect = () => {
    ipcMain.on("setSessionSelect", async (e, { contactId, sessionId }) => {
        console.log("设置选中的会话", sessionId);
        if (sessionId) {
            store.setUserData("currentSessionId", sessionId);
            readAll(contactId);
        } else {
            store.deleteUserData("currentSessionId");
        }
    });
}

//好友申请信息
const onLoadContactApply = () => {
    ipcMain.on("loadContactApply", async (e) => {
        const userId = store.getUserId();
        let result = await selectSettingInfo(userId);
        let contactNoRead = 0;
        if (result != null) {
            contactNoRead = result.contactNoRead;
        }
        e.sender.send("loadContactApplyCallback", contactNoRead);
    });
}

const onUpdateContactNoReadCount = () => {
    ipcMain.on("updateContactNoReadCount", async (e) => {
        await updateContactNoReadCount({ userId: store.getUserId() });
    });
}

//注册待 ACK 消息（由渲染层调用）
const onRegisterPendingAck = () => {
    ipcMain.on("registerPendingAck", (e, { clientId, messageObj }) => {
        registerPendingAck(clientId, messageObj);
    });
}

//保存本地消息
const onAddLocalMessage = () => {
    ipcMain.on("addLocalMessage", async (e, data) => {
        await saveMessage(data);
        //将文件保存到本地目录
        if (data.messageType == 5) {
            //保存本地文件
            await saveFile2Local(data.messageId, data.filePath, data.fileType);
            const updateInfo = {
                status: 1
            }
            //更新本地文件状态
            await updateMessage(updateInfo, { messageId: data.messageId });
        }
        //更新session信息
        data.lastReceiveTime = data.sendTime;
        updateSessionInfo4Message(store.getUserData("currentSessionId"), data);
        e.sender.send("addLocalCallback", { status: 1, messageId: data.messageId });
    });
}

//更新本地消息（用于撤回消息）
const onUpdateLocalMessage = () => {
    ipcMain.on("updateLocalMessage", async (e, data) => {
        await updateMessage(data, { messageId: data.messageId });
    });
}

//本地删除消息（多选删除 / 右键删除）
const onDelLocalMessage = () => {
    ipcMain.on("delLocalMessage", async (e, { messageId }) => {
        if (!messageId) {
            return;
        }
        await delMessage(messageId);
    });
}

//复制文本到系统剪贴板（消息右键「复制」）
const onCopyText = () => {
    ipcMain.on("copyText", (e, text) => {
        clipboard.writeText(text || '');
    })
}

//会话免打扰：本地缓存 + 服务端真源（渲染层负责调服务端接口）
const onSetSessionNoDisturb = () => {
    ipcMain.on("setSessionNoDisturb", (e, { contactId, noDisturb }) => {
        updateSessionAttr(contactId, 'noDisturb', noDisturb);
    })
}

//会话草稿：切会话/退出时保存，跨端同步
const onSaveSessionDraft = () => {
    ipcMain.on("saveSessionDraft", (e, { contactId, draft }) => {
        updateSessionAttr(contactId, 'draft', draft || '');
    })
}

//云端漫游回写：把服务端拉取的历史消息落本地 SQLite，下次进入直接从本地读
const onSaveOrUpdateMessage = () => {
    ipcMain.on("saveOrUpdateMessage", async (e, { message }) => {
        if (!message) {
            return;
        }
        const exists = await existsMessage(message.messageId);
        if (exists != null && exists.messageId != null) {
            await updateMessage(message, { messageId: message.messageId });
        } else {
            await saveMessage(message);
        }
    });
}

const onSaveAs = () => {
    ipcMain.on("saveAs", async (e, data) => {
        saveAs(data);
    });
}

//导出会话聊天记录（TXT / CSV），结果回传渲染层做提示
const onExportChatRecord = () => {
    ipcMain.on("exportChatRecord", async (e, data) => {
        const result = await exportChatRecord(data);
        e.sender.send("exportChatRecordCallback", result);
    });
}

//校验文件是否已经下载完成
checkFile();

//生成缩略图
const onCreateCover = () => {
    ipcMain.on("createCover", async (e, localFilePath) => {
        const stream = await createCover(localFilePath);
        e.sender.send("createCoverCallback", stream);
    });
}

//获取设置信息
const onGetSettingInfo = () => {
    ipcMain.on("getSysSetting", async (e) => {
        const userId = store.getUserId();
        let result = await selectSettingInfo(userId);
        let sysSetting = result.sysSetting;
        e.sender.send("getSysSettingCallback", sysSetting);
    });
}

//更新系统设置（整份 JSON 读-改-写 + 键白名单合入，按当前登录用户定位行；成功后刷新提醒开关内存缓存）
const onUpdateSysSetting = () => {
    ipcMain.on("updateSysSetting", async (e, patch) => {
        try {
            const userId = store.getUserId();
            const result = await selectSettingInfo(userId);
            let sysSetting = {};
            if (result && result.sysSetting) {
                try {
                    sysSetting = JSON.parse(result.sysSetting);
                } catch (parseError) {
                    sysSetting = {};
                }
            }
            //键白名单：仅允许已知键合入，防覆盖丢失既有键（localFileFolder / notifySwitch）
            if (patch && typeof patch === "object") {
                if ("notifySwitch" in patch) {
                    sysSetting.notifySwitch = Boolean(patch.notifySwitch);
                }
            }
            await updateSysSetting(JSON.stringify(sysSetting));
            //刷新主进程提醒开关内存缓存
            setNotifySwitch(sysSetting.notifySwitch);
            e.sender.send("updateSysSettingCallback", { status: 1, sysSetting: JSON.stringify(sysSetting) });
        } catch (error) {
            console.warn("更新系统设置失败", error);
            e.sender.send("updateSysSettingCallback", { status: 0 });
        }
    });
}

//更换目录
const onChangeLocalFolder = () => {
    ipcMain.on("changeLocalFolder", async (e) => {
        changeLocalFolder();
    });
}
//打开文件夹
const onOpenLocalFolder = () => {
    ipcMain.on("openLocalFolder", async (e) => {
        openLocalFolder();
    });
}

//下载更新
const onDownloadUpdate = () => {
    ipcMain.on("downloadUpdate", async (e, { id, fileName }) => {
        downloadUpdate(id, fileName);
    });
}
//打开链接
const onOpenUrl = () => {
    ipcMain.on("openUrl", async (e, { url }) => {
        shell.openExternal(url)
    });
}

//读取剪切板内容
const onSaveClipBoardFile = () => {
    ipcMain.on("saveClipBoardFile", async (e, file) => {
        const result = await saveClipBoardFile(file);
        console.log("result", result);
        e.sender.send("saveClipBoardFileCallback", result);
    });
}

const onOpenNewWindow = () => {
    ipcMain.on("newWindow", (e, config) => {
        openWindow(config);
    })
}

//查询所有用户
const onLoadLocalUser = () => {
    ipcMain.on("loadLocalUser", async (e) => {
        let userList = await loadLocalUser();
        e.sender.send("loadLocalUserCallback", userList);
    })
}

//删除会话
const onDelChatSession = () => {
    ipcMain.on("delChatSession", (e, contactId) => {
        delChatSession(contactId);
    })
}

//置顶回话
const onTopChatSession = () => {
    ipcMain.on("topChatSession", (e, { contactId, topType }) => {
        topChatSession(contactId, topType);
    })
}


//更新会话状态，重新获取会话
const onReloadChatSession = () => {
    ipcMain.on("reloadChatSession", async (e, { contactId }) => {
        await updateStatus(contactId);
        const chatSessionDataList = await selectUserSessionList();
        e.sender.send("reloadChatSessionCallback", { contactId, chatSessionDataList });
    })
}

const openWindow = ({ windowId, title = "EasyChat", path, width = 960, height = 720, data }) => {
    const localServerPort = store.getUserData("localServerPort");
    data.localServerPort = localServerPort;
    let newWindow = getWindow(windowId);
    if (!newWindow) {
        newWindow = new BrowserWindow({
            icon: icon,
            width: width,
            height: height,//380
            fullscreenable: false,
            fullscreen: false,
            maximizable: false,
            autoHideMenuBar: true,
            resizable: true,
            titleBarStyle: 'hidden',
            frame: true,
            transparent: true,
            hasShadow: false,
            webPreferences: {
                preload: join(__dirname, '../preload/index.js'),
                sandbox: false,
                contextIsolation: false
            }
        })
        //保存窗口
        saveWindow(windowId, newWindow);

        newWindow.setMinimumSize(600, 484);
        if (is.dev && process.env['ELECTRON_RENDERER_URL']) {
            // newWindow.loadURL(process.env['ELECTRON_RENDERER_URL'] + "#" + path)
            newWindow.loadURL(`${process.env['ELECTRON_RENDERER_URL']}/index.html#${path}`);
        } else {
            newWindow.loadFile(join(__dirname, `../renderer/index.html`), { hash: `${path}` });
        }
        //打开调试窗口
        if (NODE_ENV === 'development') {
            newWindow.webContents.openDevTools();
        }

        newWindow.on('ready-to-show', () => {
            console.log("设置title", title);
            newWindow.setTitle(title);
            newWindow.show()
        })

        newWindow.once('show', () => {
            setTimeout(() => {
                newWindow.webContents.send('pageInitData', data);
            }, 500);
        })
        newWindow.on('closed', () => {
            console.log("关闭窗口");
            delWindow(windowId);
        })
    } else {
        newWindow.show();
        newWindow.setSkipTaskbar(false)
        newWindow.webContents.send('pageInitData', data);
    }
}

export {
    onLoginSuccess,
    onSetLocalStore,
    onGetLocalStore,
    winTitleOp,
    onLoginOrRegister,
    onOpenNewWindow,
    openWindow,
    onReLogin,
    onLoadChatMessage,
    onLoadSessionData,
    onSetSessionSelect,
    onLoadContactApply,
    onUpdateContactNoReadCount,
    onAddLocalMessage,
    onUpdateLocalMessage,
    onSaveOrUpdateMessage,
    onDelLocalMessage,
    onCopyText,
    onSetSessionNoDisturb,
    onSaveSessionDraft,
    onCreateCover,
    onSaveAs,
    onExportChatRecord,
    onGetSettingInfo,
    onUpdateSysSetting,
    onChangeLocalFolder,
    onOpenLocalFolder,
    onDownloadUpdate,
    onOpenUrl,
    onSaveClipBoardFile,
    onLoadLocalUser,
    onDelChatSession,
    onTopChatSession,
    onReloadChatSession,
    onRegisterPendingAck
}
