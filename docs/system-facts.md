# 系统事实基线（System Facts）

> **用途**：描述「系统现在是什么样」，是人维护、随代码演进的当前事实基线。**不是** AI 指令（约束写法的是 `AGENTS.md`）。AI 改动代码前应读本文确认现状；改动导致下列事实变化时**必须回来同步本文件**。
>
> 本文档与 `AGENTS.md` 双轨并行：AGENTS.md 规定「怎么改」，本文档记录「现在是什么」。

## 1. 环境与端口

| 组件 | 端口 | 说明 |
|------|------|------|
| 后端 HTTP | `5050` | context-path `/api` |
| WebSocket (Netty) | `5051` | 心跳由 HandlerHeartBeat 处理 |
| MySQL | `3306` | 库 `easychat`，用户名 `root` |
| Redis | `6379` | 索引 0 |

## 2. 后端依赖版本（`pom.xml`）

| 依赖 | 版本 |
|------|------|
| Spring Boot | `2.6.1` |
| Java | `1.8` |
| MyBatis (spring-boot-starter) | `1.3.2` |
| MySQL Connector | `8.0.23` |
| Netty (netty-all) | `4.1.50.Final` |
| Redisson | `3.12.3` |
| fastjson | `1.2.66` |
| logback | `1.2.10` |
| easy-captcha | `1.6.2` |

## 3. 前端依赖版本（`easychat-front/package.json`）

| 依赖 | 类型 | 版本 |
|------|------|------|
| Electron | devDep | `^25.6.0` |
| electron-vite | devDep | `^1.0.27` |
| electron-builder | devDep | `^24.6.3` |
| Vue 3 | devDep | `^3.3.4` |
| Vite | devDep | `^4.4.9` |
| Element Plus | dep | `^2.4.3` |
| Pinia | dep | `^2.1.7` |
| axios | dep | `^1.6.2` |
| vue-router | dep | `^4.2.5` |
| sqlite3 | dep | `5.1.6` |
| ws | dep | `^8.16.0` |
| moment | dep | `^2.30.1` |
| sass | dep | `^1.69.5` |

## 4. 鉴权与会话

| 项 | 值 |
|----|-----|
| 拦截器 | `GlobalInterceptor`（自定义注解 + AOP） |
| Token 缓存 | Redis 键 `easychat:ws:token:{userId}` |
| Token 过期 | 2 天（`REDIS_KEY_EXPIRES_DAY * 2`） |
| 心跳 Redis 键 | `easychat:ws:user:heartbeat` |
| 心跳 Redis TTL | 6 秒（`REDIS_KEY_EXPIRES_HEART_BEAT`） |
| 在线用户集合 | `easychat:ws:online:` |
| 用户联系人 | `easychat:ws:user:contact:{userId}` |
| 用户会话 | `easychat:ws:user:session:{userId}` |
| 密码加密 | MD5（后续建议 BCrypt） |
| 验证码键 | `easychat:checkcode:{key}` |
| 验证码 TTL | 1 分钟 |

## 5. WebSocket 协议

| 项 | 值 |
|----|-----|
| 端口 | `5051` |
| 框架 | Netty |
| 心跳发送 | 客户端 `onopen` 立即发 `"heart beat"` 字符串 |
| 服务端心跳检测 | `HandlerHeartBeat` 读空闲判断 |
| 消息广播 | Redisson `RTopic` channel `message.topic`，消息格式 `MessageSendDto` |
| 客户端 WS 库 | `ws` (Node.js) |
| 主进程维护 WS 句柄 | `wsClient.js`，连 `ws://<domain>:<5051>?token=<token>` |
| 最大重连次数 | 5 |
| 渲染进程感知 WS | 通过 preload 暴露 `window.bridge` 事件订阅 |
| 新消息提醒 | `src/main/notification.js` `flashOnNewMessage`：白名单消息（type 2 文本 / 5 媒体 / 4 好友申请 / 15-18 朋友圈 / 19 群公告）+ 主窗口失焦 + 开关开 → 触发；**最小化场景为主动交替闪烁循环**（600ms `flashFrame(false)/(true)`，获焦/关开关停），非最小化为单次 `flashFrame(true)` 系统静态红底；朋友圈类（15-18）**只弹 toast 不闪任务栏**；ACK/SYNC/心跳/撤回/系统帧不提醒 |
| 系统横幅 | 同模块 `showToast`：`Notification.isSupported()` 为真时弹 Windows 横幅，点击横幅 → 拉起窗口 + 推 `locateSession` 帧定位会话；不支持时静默降级为仅闪烁 |
| 提示音 | 主进程发 `playNotifySound` 帧，渲染层 `Chat.vue` 按提醒开关播放 |
| 免打扰抑制 | 会话本地 `no_disturb=1` 时不闪烁、不响铃（仅落库） |
| 朋友圈通知帧 | 类型 15 新动态 / 16 点赞 / 17 评论回复 / 18 @；主进程转发 `momentNotify` 给渲染层做红点；`-8` 帧携带 `extendData.unreadCount` 同步未读数 |
| 会话属性同步帧 | 类型 `-7`（SYNC_SESSION_USER），`extendData.action` ∈ `top`/`noDisturb`/`draft`，主进程回写本地 SQLite 并转发 `syncSessionUser` |
| 提醒开关 | `user_setting.sysSetting.notifySwitch` JSON 键（缺省 `true`），设置页账号设置 el-switch 经 `updateSysSetting` 通道读写 |

## 6. 数据库约束

| 项 | 值 |
|----|-----|
| 数据源 | MySQL `easychat` |
| 连接池 | HikariCP（min-idle 5 / max-pool 10 / conn-timeout 30s） |
| 文件上传上限 | `multipart max-file-size / max-request-size`：15MB；实际按系统设置分类限流（图片/视频/文件各自上限） |
| 图片白名单 | `.jpeg/.jpg/.png/.gif/.bmp/.webp` |
| 视频白名单 | `.mp4/.avi/.rmvb/.mkv/.mov` |
| 可执行文件黑名单 | `exe/bat/cmd/com/scr/pif/msi/dll/sys/jar/sh/ps1/vbs/vbe/js/jse/wsf/lnk`，命中即拒（错误码 2604） |
| 上传校验 | `ChatMessageServiceImpl.checkFileAllowed`：后缀非空 → 黑名单 → 类型白名单 → 分类大小；超限抛 2603，类型不支持抛 2604（旧实现为超限静默 return） |
| 好友备注/分组 | `user_contact.remark` / `user_contact.group_name`；`selectList` 的 `contactName` 取 `COALESCE(NULLIF(remark,''), nick_name)`，即备注优先 |
| 本地数据目录 | `<app.getPath('userData')>` |
| 本地 DB | sqlite3，表由 `src/main/db/Tables.js` 定义 |

## 7. 本地 SQLite（前端主进程缓存）

| Model | 职责 |
|-------|------|
| `ADB.js` | 连接管理 |
| `ChatMessageModel.js` | 消息本地持久化 + 分页 |
| `ChatSessionUserModel.js` | 会话-用户关联 + 未读计数 |
| `Tables.js` | 表结构 DDL |
| `UserSetting.js` | 用户本地配置读写 |

## 8. Redis Key 全表

| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `easychat:checkcode:{key}` | 验证码 | 60s |
| `easychat:ws:token:{userId}` | 用户 Token | 2 天 |
| `easychat:ws:token:userid` | Token → UserId 映射 | — |
| `easychat:ws:user:heartbeat` | 用户心跳 | 6s |
| `easychat:ws:online:{userId}` | 在线用户集合 | 心跳续期 |
| `easychat:ws:user:contact:{userId}` | 用户联系人列表 | — |
| `easychat:ws:user:session:{userId}` | 用户参与会话 | — |
| `easychat:syssetting:{key}` | 系统设置缓存 | — |

## 9. 前端配置项（Electron Store）

| Key | 用途 |
|-----|------|
| `devWsDomain` | 开发环境 WS 域名 |
| `prodWsDomain` | 生产环境 WS 域名 |
| `devApiDomain` | 开发环境 API baseURL |
| `prodApiDomain` | 生产环境 API baseURL |

## 10. 正则

| 名称 | 正则 | 用途 |
|------|------|------|
| 密码 | `^(?=.*\\d)(?=.*[a-zA-Z])[\\da-zA-Z~!@#$%^&*_]{8,18}$` | 必含数字 + 字母，8-18 位 |

## 11. 文件路径常量

| 常量子 | 值 |
|--------|-----|
| `FILE_FOLDER_FILE` | `/file/` |
| `FILE_FOLDER_TEMP` | `/temp/` |
| `FILE_FOLDER_IMAGE` | `images/` |
| `FILE_FOLDER_AVATAR_NAME` | `avatar/` |
| `APP_UPDATE_FOLDER` | `/app/` |

## 12. 接口契约与业务事实（2026-09 补齐）

| 项 | 值 |
|----|-----|
| 统一响应包络 | **仅** `Result<T>`（`code`/`message`/`data`，`code=0` 成功）。旧包络 `ResponseVO` 及 `ABaseController` 三个兼容方法已于 2026-09-26 删除，全仓零引用 |
| 错误码扩展 | `2603` 文件大小超限、`2604` 文件类型不支持（沿用 §3.1 的 2600-2699 文件域分段） |
| 登录策略 | 默认**允许多端同时在线**；配置 `easychat.login.single-device=true` 时新登录把旧设备挤下线（推 FORCE_OFF_LINE 并关闭其 WS），**不再是拒绝登录报错** |
| 修改密码 | `POST /userInfo/updatePassword` 必传 `oldPassword` + `password`；服务端校验旧密码 MD5 匹配，且新旧不得相同，成功后关闭 WS 强制重登 |
| 找回密码 | `POST /account/sendEmailCode`（`type=1`，60 秒防重发，10 分钟有效）+ `POST /account/resetPassword`（`email`/`code`/`newPassword`）；未配置邮件服务时验证码写日志 |
| 消息搜索 | `POST /chat/searchMessage` 强制校验会话归属（`chat_session_user` 存在记录），防会话 ID 推算越权 |
| 云端漫游 | `POST /chat/loadHistoryMessage`（按 `lastMessageId` 向前翻页）+ `POST /chat/locateMessage`（定位某条消息所在页，供搜索/@跳转） |
| 全局搜索 | `POST /chat/globalSearch`，`scope` ∈ `all`/`message`/`contact`/`group`，返回 `GlobalSearchResultVO`（messageList/contactList/groupList） |
| 会话属性 | `chat_session_user` 增列 `top_type`（置顶跨端）、`no_disturb`（免打扰）、`draft`（草稿跨端），服务端为真源，变更经 `-7` 帧同步各端 |
| 群禁言 | `GroupInfoServiceImpl.checkMuted()` 已在 `ChatMessageServiceImpl` 发送链路注入，被禁言成员发言直接抛业务异常 |
| 群公告 | `editNotice` 落一条 `GROUP_NOTICE`(19) 系统消息并向全体成员广播 WS 帧（旧实现只存不推） |
| 朋友圈通知 | 表 `moment_notify`；`MomentNotifyService` 提供未读数/列表/已读/清空；接口前缀 `/moment/notify/*` |
| 朋友圈个人主页 | `POST /moment/userMomentList`（`targetUserId`） |
| 评论删除 | `POST /moment/deleteComment`（`commentId`），动态发布者与评论者本人可删 |
| 消息扩展 | `chat_message.extra_data`（JSON：引用/转发）、`at_user_ids`（@ 提及）、`duration`（语音时长） |
| 聊天记录导出 | 会话右键菜单「导出聊天记录（TXT / CSV）」→ IPC `exportChatRecord` → 主进程 `src/main/exportChat.js` 读本地 SQLite 全量 → `dialog.showSaveDialog` → 落盘。**只导本地已持久化消息，不拉云端**；CSV 带 UTF-8 BOM 且对 `=+-@` 开头值前置单引号防 Excel 公式注入 |

## 13. 前端主进程约定（易踩）

| 项 | 值 |
|----|-----|
| IPC 通道注册 | `src/main/ipc.js` 里 `const onXxx = () => {...}` 定义并加进 `export {}` 后，**必须在 `src/main/index.js` 的 import 列表 + 启动初始化中调用一次**才会生效。项目不做自动扫描，漏注册时构建完全无感、功能静默失效 |
| 注册遗漏门禁 | `node scripts/check-ipc-registration.mjs --strict`：对拍 ipc.js 导出与 index.js 调用，缺失即 exit 1 |
| 全局工具能力边界 | `utils/Confirm.js` 仅支持 `{message, okfun, showCancelBtn, okText}`，**没有** `cancelfun` / `cancelText`；`utils/Message.js` 仅 `success` / `error` / `warning`。调用前先读实现，勿臆造参数 |
| 本地 SQLite 字段 | `chat_message` 等表为 **snake_case**（`message_id`、`send_user_nick_name`、`file_type`）。渲染层对象是 camelCase，主进程读库取值勿混用 |
| 文件类型枚举 | `file_type` / `File_TYPE`：0 图片、1 视频、2 文件 |

---

> **变更日志（事实变化时必须在此追加一行）**
>
> | 日期 | 变更说明 | 来源 |
> |------|----------|------|
> | 2026-09-22 | 初始建立，对齐 AGENTS.md 与现有代码实测 | 第三轮规范建设 |
> | 2026-09-24 | 新消息提醒收敛为白名单 + 失焦闪烁，去掉全帧无条件闪烁 | 桌面提醒变更 |
> | 2026-09-26 | 提醒扩展为「闪烁 + 系统横幅 + 提示音 + 点击定位」，新增朋友圈类帧与免打扰抑制 | IM 能力补齐 |
> | 2026-09-26 | 响应包络统一为 `Result<T>`，`ResponseVO` 及三个兼容方法删除 | 契约统一 |
> | 2026-09-26 | 上传新增可执行文件黑名单 + 类型白名单 + 分类限流（2603/2604）；旧静默 return 改为抛错 | 安全红线 §6.2-5 |
> | 2026-09-26 | 好友备注/分组成立，`contactName` 改为备注优先；新增按昵称/备注/分组搜索好友 | 核心体验补齐 |
> | 2026-09-26 | 登录策略改为默认多端在线（`single-device` 开关时挤下线而非拒绝） | 与 multi-device-sync 规格对齐 |
> | 2026-09-26 | 新增聊天记录导出（TXT/CSV，仅本地数据） | openspec 2026-09-26-chat-record-export |
> | 2026-09-26 | 修复 5 个 IPC 通道漏注册导致功能静默失效；新增 `check-ipc-registration.mjs` 门禁 | 同上的 QA 附带发现 |
