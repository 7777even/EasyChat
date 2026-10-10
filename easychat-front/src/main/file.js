
const fs = require('fs');
const fse = require('fs-extra');
const NODE_ENV = process.env.NODE_ENV
const path = require('path')
const { app, ipcMain, shell } = require('electron')
const { exec } = require("child_process");
const FormData = require('form-data'); // 引入FormData模块（用于构建表单数据）
const axios = require('axios'); // 引入axios库
import store from "./store"
const moment = require('moment')
moment.locale('zh-cn', {});
const { dialog } = require('electron')
//express服务器
const express = require('express');
const expressServer = express();

import { selectByMessageId } from './db/ChatMessageModel'
import { selectSettingInfo, updateSysSetting } from './db/UserSetting'
import { isInsideRoot, assertInsideRoot, tokensMatch, generateFileToken } from './localFileGuardCore.mjs'

import { getWindow } from "./windowProxy";

//缩略图后缀
const cover_image_suffix = "_cover.png";
const image_suffix = ".png";

const ffprobePath = "/assets/ffprobe.exe";
const ffmpegPath = "/assets/ffmpeg.exe"

const mkdirs = (dir) => {
    if (!fs.existsSync(dir)) { // 如果目录不存在则进行创建
        const parentDir = path.dirname(dir); // 获取上级目录
        if (parentDir !== dir) { // 确保当前目录不为根目录
            mkdirs(parentDir); // 递归调用自身创建上级目录
        }
        fs.mkdirSync(dir); // 创建当前目录
    }
}

const getResourcesPath = () => {
    let resourcesPath = app.getAppPath();
    if (NODE_ENV !== 'development') {
        resourcesPath = path.dirname(app.getPath('exe')) + "/resources";
    }
    return resourcesPath;

}

const getFFprobePath = () => {
    return path.join(getResourcesPath(), ffprobePath);
}

const getFFmegPath = () => {
    return path.join(getResourcesPath(), ffmpegPath);
}

// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve() })`。
// 该写法下 executor 内 await 抛出的异常不会被 Promise 捕获，会逃逸成
// unhandled rejection，调用方 .catch() 收不到 —— 转码失败时静默。
const saveFile2Local = async (messageId, filePath, fileType) => {
    let ffmpegPath = getFFmegPath();
    let savePath = await getLocalFilePath("chat", false, messageId);
    let coverPath = null;
    fs.copyFileSync(filePath, savePath);
    //生成缩略图l
    if (fileType != 2) {
        //判断视频格式
        let command = `${getFFprobePath()} -v error -select_streams v:0 -show_entries stream=codec_name "${filePath}"`
        let result = await execCommand(command);
        result = result.replaceAll("\r\n", "");
        result = result.substring(result.indexOf("=") + 1);
        let codec = result.substring(0, result.indexOf("["));
        if ("hevc" === codec) {
            command = `${ffmpegPath}  -y -i "${filePath}" -c:v libx264 -crf 20 "${savePath}"`;
            await execCommand(command);
        }
        //生成缩略图
        coverPath = savePath + cover_image_suffix;
        command = `${ffmpegPath} -i "${savePath}" -y -vframes 1 -vf "scale=min(170\\,iw*min(170/iw\\,170/ih)):min(170\\,ih*min(170/iw\\,170/ih))" "${coverPath}"`
        await execCommand(command);
    }
    //上传文件
    // 2026-10-04：补 await。原实现未 await 就紧接着 resolve()，即函数在
    // 上传完成前就返回，上传失败也不会传到调用方 —— 与 ChatMessageModel
    // #saveMessageBatch 同类的提前返回问题。
    await uploadFile(messageId, savePath, coverPath);
}

const uploadFile = (messageId, savePath, coverPath) => {
    // 创建FormData对象，并添加图片文件到其中
    const formData = new FormData();
    formData.append("messageId", messageId);
    formData.append('file', fs.createReadStream(savePath));
    //封面存在就上传封面
    if (coverPath) {
        formData.append('cover', fs.createReadStream(coverPath));
    }
    // 设置POST请求参数
    const url = `${getDomain()}/api/chat/uploadFile`;
    const token = store.getUserData("token");
    const config = { headers: { 'Content-Type': 'multipart/form-data', "token": token } };
    // 发送POST请求
    axios.post(url, formData, config)
        .then((response) => {

        })
        .catch((error) => {
            console.error('文件上传成功失败', error);
        });
}

// 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve(v) })`。
// executor 内 await 之后若抛错（此处最可能是 fs.readFileSync 的 ENOENT ——
// ffmpeg 未生成预期文件），异常不会被 Promise 捕获，会逃逸成 unhandled
// rejection，调用方 await 不到错误。改为 async 直写。
const createCover = async (filePath) => {
    let ffmpegPath = getFFmegPath();
    let avatarPath = await getLocalFilePath("avatar", false, store.getUserId() + "_temp");
    let command = `${ffmpegPath} -i "${filePath}" "${avatarPath}" -y`
    await execCommand(command);

    let coverPath = await getLocalFilePath("avatar", false, store.getUserId() + "_temp_cover");
    command = `${ffmpegPath} -i "${filePath}" -y -vframes 1 -vf "scale=min(60\\,iw*min(60/iw\\,60/ih)):min(60\\,ih*min(60/iw\\,60/ih))" "${coverPath}"`
    await execCommand(command);

    return {
        avatarStream: fs.readFileSync(avatarPath),
        coverStream: fs.readFileSync(coverPath),
    };
}

const execCommand = (command) => {
    return new Promise((resolve, reject) => {
        exec(command, (error, stdout, stderr) => {
            console.log("ffmpeg命令:", command);
            if (error) {
                console.error('执行命令失败', error);
            }
            console.log("ffmpeg命令:", command, stdout);
            resolve(stdout);
        });
    })
}

const getDomain = () => {
    return NODE_ENV !== 'development' ? store.getData("prodDomain") : store.getData("devDomain")
}

//校验本地图片缓存是否为有效图片（魔数 + PNG IHDR），损坏缓存触发回源重新下载
const isValidImageCache = (filePath) => {
    try {
        if (!fs.existsSync(filePath)) {
            return false;
        }
        if (fs.statSync(filePath).size < 16) {
            return false;
        }
        const fd = fs.openSync(filePath, 'r');
        const buf = Buffer.alloc(16);
        fs.readSync(fd, buf, 0, 16, 0);
        fs.closeSync(fd);
        //PNG：校验签名 + 第一个块必须是 IHDR
        if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) {
            return buf.toString('ascii', 12, 16) === 'IHDR';
        }
        //JPEG
        if (buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) {
            return true;
        }
        //GIF
        if (buf.toString('ascii', 0, 3) === 'GIF') {
            return true;
        }
        //BMP
        if (buf[0] === 0x42 && buf[1] === 0x4D) {
            return true;
        }
        //WEBP
        if (buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') {
            return true;
        }
        return false;
    } catch (e) {
        console.error('校验本地图片缓存失败', filePath, e);
        return false;
    }
}


//express 本地服务器
// 注意：这里存的是 **MIME 前缀**，file.js 会拼上文件后缀
// （contentType = FILE_TYPE_CONTENT_TYPE[fileType] + fileSuffix），
// 所以每项必须以 "/" 结尾。2026-10-03 修正了原来 "2": "application/octet-stream"
// 这个不带 "/" 的值——它拼出的是 "application/octet-streamzip"，
// 不是合法 MIME，浏览器会拒绝按正确类型处理（对普通文件影响有限，
// 但一旦需要精确 content-type 就会失效）。
const FILE_TYPE_CONTENT_TYPE = {
    "0": "image/",
    "1": "video/",
    "2": "application/octet-stream/",
    // 语音消息（messageType=24）的 fileType=3。
    // 2026-10-03 新增：此前此处无 3 → contentType 拼成 "undefinedwebm"，
    // 浏览器无法按音频解码 → <audio> 播不出声音，且不抛任何错。
    "3": "audio/"
}


const checkFile = () => {
    ipcMain.on("checkFile", async (e, fileId) => {
        const localPath = await getLocalFilePath("chat", false, fileId);
        if (!fs.existsSync(localPath)) {
            await downloadFile(fileId, false, localPath, "chat");
        }
        e.sender.send("checkFileCallback", { fileId, status: 1 });
    });
}
let server = null;
// 2026-10-10（遗留 #29）：本服务此前**无鉴权**且响应固定 ACAO: *，PoC 实测任意网站
//   可经路径穿越读取用户磁盘任意文件。token 为唯一凭证，进程内持有以便路由比对。
let currentFileToken = null;

/** 取当前文件令牌（渲染层拼 URL 时经 IPC 透出，见 ipc.js openWindow） */
const getFileToken = () => currentFileToken;

/**
 * 取得文件令牌：已持久化则复用，否则生成 32 字节 CSPRNG。
 * 持久化而非每次启动重生成，是为了让登录后读取端口的渲染层与主进程拿到同一个值
 * （渲染层靠 port 判断「本地服务已就绪」，token 必须随之一起就绪）。
 */
const ensureFileToken = () => {
    let token = store.getUserData("localFileServerToken");
    if (typeof token !== "string" || !/^[0-9a-f]{64}$/.test(token)) {
        token = generateFileToken();
        store.setUserData("localFileServerToken", token);
    }
    return token;
};

const startLocalServer = (serverPort) => {
    // 令牌须在早退分支之前准备：同端口复用时进程内变量也要有值，
    // 否则复用分支启动的实例会「永不鉴权」
    currentFileToken = ensureFileToken();
    //渲染层重载后再次登录会重复调用：同端口服务仍在监听则直接复用，避免 EADDRINUSE 打崩主进程
    if (server) {
        if (server.listening && server.address() && server.address().port === serverPort) {
            console.log('本地服务已在运行 http://127.0.0.1:' + serverPort);
            return;
        }
        if (server.listening) {
            server.close();
        }
        server = null;
    }
    // ⚠ 2026-10-10：此前 `listen(serverPort, cb)` **未传 host**，Node 默认绑 0.0.0.0
    //   —— 即同网段任意主机可直接直连读取（连 CORS 都不用绕）。
    //   这是本机服务，必须只绑回环。日志里那句 http://127.0.0.1: 只是打印字符串，与绑定无关。
    server = expressServer.listen(serverPort, '127.0.0.1', () => {
        console.log('本地服务在 http://127.0.0.1:' + serverPort + "开启");
    })
    //端口被外部占用等异常仅告警，不再抛出未捕获异常
    server.on('error', (err) => {
        console.warn('本地服务启动失败', err.message);
        server = null;
    });
}

const closeLocalServer = () => {
    if (server && server.listening) {
        server.close();
    }
    server = null;
}

const getLocalFilePath = async (partType, showCover, fileId) => {
    // 2026-10-04：原为 `new Promise(async (resolve, reject) => { ... resolve(p) })`。
    // executor 内 await 抛出的异常不会被 Promise 捕获（此处可能是
    // selectByMessageId 拿到 undefined 后的属性访问），会逃逸成 unhandled
    // rejection 而调用方 await 不到。去掉包裹直接 async 直写。
    let localFolder = store.getUserData("localFileFolder");
    let localPath = null;
    if (partType == "avatar") { //头像
        localFolder = localFolder + "/avatar/"
        if (!fs.existsSync(localFolder)) {
            mkdirs(localFolder);
        }
        localPath = localFolder + fileId + image_suffix;
    } else if (partType == "chat") {
        let messageInfo = await selectByMessageId(fileId);
        const month = moment(Number.parseInt(messageInfo.sendTime)).format('YYYYMM');
        localFolder = localFolder + "/" + month;
        if (!fs.existsSync(localFolder)) {
            mkdirs(localFolder);
        }
        let fileSuffix = messageInfo.fileName;
        fileSuffix = fileSuffix.substring(fileSuffix.lastIndexOf("."));
        localPath = localFolder + "/" + fileId + fileSuffix;
    } else if (partType == "moment") { //朋友圈
        localFolder = localFolder + "/moment/"
        if (!fs.existsSync(localFolder)) {
            mkdirs(localFolder);
        }
        // 检查 fileId 是否已经包含扩展名
        if (fileId.includes('.')) {
            localPath = localFolder + fileId;
        } else {
            localPath = localFolder + fileId + image_suffix;
        }
    } else if (partType == "tmp") {
        localFolder = localFolder + "/tmp/"
        if (!fs.existsSync(localFolder)) {
            mkdirs(localFolder);
        }
        localPath = localFolder + "/" + fileId
    } else if (partType == "group") { //群文件
        localFolder = localFolder + "/group/"
        if (!fs.existsSync(localFolder)) {
            mkdirs(localFolder);
        }
        localPath = localFolder + fileId
    } else {
        localPath = localFolder + "/" + fileId
    }
    if (showCover) {
        localPath = localPath + cover_image_suffix;
    }
    // 2026-10-10（遗留 #29）：**所有**分支在此统一收口，含 avatar/chat/moment/tmp/group。
    // 此前只补 else 兜底是不够的 —— 五个已知分支同样把 fileId 拼进路径
    // （如 moment 分支 fileId 自带扩展名、chat 分支的 fileSuffix 取自库中 file_name），
    // 任何一条被构造出 ../ 即重新打开穿越口，故必须在这里一次性拦住。
    // ⚠ 断言放在 mkdirs 之后是安全的：mkdirs 建的都是分支目录（avatar/chat/moment…），
    //   不含 fileId，故越界的 fileId 不会在断言前就建出越界目录。
    return assertInsideRoot(localFolder, localPath);
};

expressServer.get('/file', async (req, res) => {
    let { partType, fileType, fileId, showCover, forceGet } = req.query;
    // 2026-10-10（遗留 #29）：**令牌校验必须在最前**，早于任何参数解析与读盘。
    // 放在后面等于「先看有没有文件、再问有没有权限」，且会给未授权方泄露
    // 「该 fileId 是否存在」的探测能力。
    if (!tokensMatch(currentFileToken, req.query.fileToken)) {
        res.status(403).send('forbidden');
        return;
    }
    //console.log("getFile", partType, fileType, fileId, showCover, forceGet);
    if (!partType || !fileId) {
        res.send("请求参数错误");
        return;
    }
    // 正确处理字符串 "false" 和 "true"
    showCover = showCover === 'true' || showCover === true;
    let localPath;
    try {
        localPath = await getLocalFilePath(partType, showCover, fileId);
    } catch (e) {
        // getLocalFilePath 内含越界断言；越界即 400，绝不落到「下载兜底图片」分支
        console.warn('非法文件路径请求:', e.message);
        res.status(400).send("非法文件路径");
        return;
    }
    //头像缓存命中前校验有效性：本地文件损坏时强制回源重新下载，避免坏图/旧图被永久命中
    const avatarCacheBroken = partType == "avatar" && fs.existsSync(localPath) && !isValidImageCache(localPath);
    if (!fs.existsSync(localPath) || forceGet == "true" || avatarCacheBroken) {
        if (forceGet == "true" && partType == "avatar") {
            await downloadFile(fileId, true, localPath + cover_image_suffix, partType);
        }
        await downloadFile(fileId, showCover, localPath, partType);
    }

    //console.log("获取图片", new Date().getTime(), fileId, forceGet, partType);
    if (forceGet == "true" && partType == "avatar") {
        getWindow("main").webContents.send("reloadAvatar", fileId);
    }

    const fileSuffix = localPath.substring(localPath.lastIndexOf(".") + 1);
    //图片直接返回
    let contentType = FILE_TYPE_CONTENT_TYPE[fileType] + fileSuffix;
    // 2026-10-10（遗留 #29）：此处原为固定 `*`。现在**保留** —— 因为令牌已构成唯一凭证
    // （无令牌一律 403），任意站点即便能发请求也拿不到任何文件；而渲染层生产环境以
    // file:// 加载、origin 为 null，开发环境端口又是随机的，逐个枚举 origin 得不偿失。
    // 若日后改用其它取文件方式（如 main 进程代读），此处应改为回显受控 origin。
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader('Content-Type', contentType);
    //头像，缩略图，文件
    if (showCover || fileType != "1") {
        fs.createReadStream(localPath).pipe(res);
        return;
    }
    //视频文件 需要能够拖动需要通过range来获取文件流
    let stat = fs.statSync(localPath);
    let fileSize = stat.size;
    let range = req.headers.range;
    if (range) {
        //有range头才使用206状态码
        let parts = range.replace(/bytes=/, "").split("-");
        let start = parseInt(parts[0], 10);
        let end = parts[1] ? parseInt(parts[1], 10) : start + 999999;

        // end 在最后取值为 fileSize - 1 
        end = end > fileSize - 1 ? fileSize - 1 : end;

        let chunksize = (end - start) + 1;
        let stream = fs.createReadStream(localPath, {
            start,
            end
        });
        let head = {
            'Content-Range': `bytes ${start}-${end}/${fileSize}`,
            'Accept-Ranges': 'bytes',
            'Content-Length': chunksize,
            'Content-Type': 'video/mp4',
        };
        res.writeHead(206, head);
        stream.pipe(res);
    } else {
        let head = {
            'Content-Length': fileSize,
            'Content-Type': 'video/mp4',
        };
        res.writeHead(200, head);
        fs.createReadStream(localPath).pipe(res);
    }
})
//同一文件的并发下载去重：forceGet 突发（侧栏+气泡+会话列表同时渲染）只回源一次
const pendingDownloads = new Map();

//先写临时文件再原子 rename，读方要么读到旧完整文件、要么读到新完整文件，不会读到半截
const writeAtomically = (savePath, dataStream) => {
    return new Promise((resolve, reject) => {
        const tmpPath = savePath + ".downloading";
        const stream = fs.createWriteStream(tmpPath);
        dataStream.pipe(stream);
        stream.on('finish', () => {
            stream.close();
            try {
                fs.renameSync(tmpPath, savePath);
                resolve();
            } catch (renameErr) {
                reject(renameErr);
            }
        });
        stream.on('error', (err) => {
            console.error('写入文件失败', savePath, err);
            try { fs.unlinkSync(tmpPath); } catch (e) { /* 忽略清理失败 */ }
            reject(err);
        });
    });
};

const downloadFile = (fileId, showCover, savePath, partType) => {
    // 确保 showCover 是布尔值
    showCover = Boolean(showCover);
    //并发去重：同一 savePath 已有在途下载时直接复用其 Promise
    if (pendingDownloads.has(savePath)) {
        return pendingDownloads.get(savePath);
    }
    let url = `${getDomain()}/api/chat/downloadFile`;
    const token = store.getUserData("token");
    const promise = new Promise((resolve, reject) => {
        // validateStatus 允许 4xx/5xx 也进入响应处理，避免 README 400 JSON 响应被当作网络错误
        const config = {
            responseType: 'stream',
            headers: { 'Content-Type': 'multipart/form-data', "token": token },
            validateStatus: () => true
        };
        // 发送POST请求
        axios.post(url, {
            fileId,
            showCover,
            partType
        }, config).then(async (response) => {
            const folder = savePath.substring(0, savePath.lastIndexOf("/"));
            mkdirs(folder);
            if (response.headers["content-type"] && response.headers["content-type"].includes("application/json")) {
                // 后端返回了 JSON（文件不存在等业务错误），使用兜底图片
                let resourcesPath = path.join(app.getAppPath(), '/');
                if (NODE_ENV !== 'development') {
                    resourcesPath = path.join(path.dirname(app.getPath('exe')), '/resources/');
                }
                const defaultImage = partType == "avatar" ? "assets/user.png" : "assets/404.png";
                await writeAtomically(savePath, fs.createReadStream(resourcesPath + defaultImage));
            } else {
                await writeAtomically(savePath, response.data);
            }
            resolve();
        }).catch(async (err) => {
            console.error('下载文件失败', url, fileId, err);
            // 网络异常等无法拿到响应时，也写入兜底图片，避免后续读取本地文件报错
            try {
                const folder = savePath.substring(0, savePath.lastIndexOf("/"));
                mkdirs(folder);
                let resourcesPath = path.join(app.getAppPath(), '/');
                if (NODE_ENV !== 'development') {
                    resourcesPath = path.join(path.dirname(app.getPath('exe')), '/resources/');
                }
                const defaultImage = partType == "avatar" ? "assets/user.png" : "assets/404.png";
                fs.copyFileSync(resourcesPath + defaultImage, savePath);
            } catch (copyErr) {
                console.error('写入兜底图片失败', savePath, copyErr);
            }
            resolve();
        });
    });
    pendingDownloads.set(savePath, promise);
    promise.finally(() => pendingDownloads.delete(savePath));
    return promise;
}

const saveAs = async ({ partType, fileId, fileType }) => {
    let fileName = "";
    if (partType == "avatar") {
        fileName = fileId + image_suffix;
    } else if (partType == "chat") {
        let messageInfo = await selectByMessageId(fileId);
        fileName = messageInfo.fileName;
    }
    const localPath = await getLocalFilePath(partType, false, fileId);
    //文件类型，没有预览，所以保存的时候判断本地是否有，没有就下载
    if (fileType == 2) {
        if (!fs.existsSync(localPath)) {
            await downloadFile(fileId, false, localPath, partType);
        }
    }
    const options = {
        title: '保存文件', // 对话框标题
        defaultPath: fileName
    }
    // 显示保存文件的对话框
    let result = await dialog.showSaveDialog(options);
    if (result.canceled || result.filePath == '') {
        return;
    }
    const filePath = result.filePath;
    fs.copyFileSync(localPath, filePath);
}

const changeLocalFolder = async () => {
    let settingInfo = await selectSettingInfo(store.getUserId());
    const sysSetting = JSON.parse(settingInfo.sysSetting);
    let localFileFolder = sysSetting.localFileFolder
    const options = {
        properties: ['openDirectory'],
        defaultPath: localFileFolder
    }
    // 显示保存文件的对话框
    let result = await dialog.showOpenDialog(options);
    if (result.canceled) {
        return;
    }
    //拷贝目录
    if (localFileFolder !== result.filePaths[0]) {
        const userId = store.getUserId();
        getWindow("main").webContents.send("copyingCallback");
        await fse.copy(localFileFolder + "/" + userId, result.filePaths[0] + "/" + userId)
    }
    sysSetting.localFileFolder = result.filePaths[0] + "\\";
    const sysSettingJson = JSON.stringify(sysSetting)
    await updateSysSetting(sysSettingJson);
    //更新路径
    store.setUserData("localFileFolder", sysSetting.localFileFolder + store.getUserId())

    getWindow("main").webContents.send("getSysSettingCallback", sysSettingJson);
}

const openLocalFolder = async () => {
    let settingInfo = await selectSettingInfo(store.getUserId());
    const sysSetting = JSON.parse(settingInfo.sysSetting);
    const localFileFolder = sysSetting.localFileFolder;

    if (!fs.existsSync(localFileFolder)) {
        mkdirs(localFileFolder);
    }

    shell.openPath('file:///' + localFileFolder);
}

const downloadUpdate = async (id, fileName) => {
    let url = `${store.getData("domain")}/api/update/download`;
    const token = store.getUserData("token");
    const config = {
        responseType: 'stream',
        headers: { 'Content-Type': 'multipart/form-data', "token": token }, onDownloadProgress(progress) {
            const loaded = progress.loaded
            getWindow("main").webContents.send("updateDownloadCallback", loaded);
        }
    }
    // 发送POST请求
    const response = await axios.post(url, { id }, config);
    const localFile = await getLocalFilePath(null, false, fileName);
    const stream = fs.createWriteStream(localFile);
    response.data.pipe(stream);
    stream.on('finish', async () => {
        stream.close();
        //开始安装
        const command = `${localFile}`
        execCommand(command);
    });
}

//保存剪切板内容
const saveClipBoardFile = async (file) => {
    const fileSuffix = file.name.substring(file.name.lastIndexOf("."));
    const filePath = await getLocalFilePath("tmp", false, "tmp" + fileSuffix);
    let byteArray = file.byteArray;
    const buffer = Buffer.from(byteArray);
    fs.writeFileSync(filePath, buffer);
    return {
        size: byteArray.length,
        name: file.name,
        path: filePath
    };
}

//文件管理
export {
    saveClipBoardFile,
    saveFile2Local,
    startLocalServer,
    closeLocalServer,
    getFileToken,
    checkFile,
    createCover,
    saveAs,
    changeLocalFolder,
    openLocalFolder,
    downloadUpdate
}