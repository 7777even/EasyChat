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
| 通话信令帧 | 语音/视频通话复用同一条 WS 长连接（5051），帧类型 `CALL_INVITE`/`CALL_ACCEPT`/`CALL_REJECT`/`CALL_SIGNAL`/`CALL_HANGUP`/`CALL_CANCEL`/`CALL_BUSY`/`CALL_JOIN`；服务端仅按 `CallRoomRegistry` 中继，不解析媒体；`call_signal` 不落库、不触发普通消息逻辑 |

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
| 聊天记录导出 | 会话右键菜单「导出聊天记录（TXT / CSV）」→ IPC `exportChatRecord` → 主进程 `src/main/exportChat.js` 读本地 SQLite 全量 → `dialog.showSaveDialog` → 落盘。**只导本地已持久化消息，不拉云端**——此为有意识的设计边界（local-only）：`exportChat.js` 仅读本地 SQLite，云端全量漫游导出归入后续独立的「备份/迁移」专项，不在本导出能力范围内；CSV 带 UTF-8 BOM 且对 `=+-@` 开头值前置单引号防 Excel 公式注入 |
| 深色模式 | 账号设置「外观主题」浅色/深色单选 → IPC `updateSysSetting` 合入 `user_setting.sysSetting.theme`（主进程键白名单已含 `theme`，不覆盖既有键）；`<html>.dark` 激活 Element Plus 暗色 css-vars（`element-plus/theme-chalk/dark/css-vars.css`）+ 自定义外壳 `--ec-*` 变量（`base.scss` 定义，`Layout`/`ContentPanel`/`Main`/`Setting`/`Chat` 引用）。仅本地持久化，不依赖后端；启动时由 `Main.vue` 读本地设置应用 |
| 群文件 | 群聊会话头部「群文件」图标 → `GroupFile.vue` 面板。上传走既有分片 `/upload/uploadChunk`+`/upload/checkChunks`，合并落盘 `file/group/<fileId>.<ext>`（新增 `Constants.FILE_FOLDER_GROUP`）并写 `group_file`（`status=1`，`file_type` 0图片/1视频/2文件）；列表 `POST /group/file/list`（分页、`create_time desc`、含上传人昵称）；删除 `POST /group/file/delete`（上传者本人或群主/管理员可删，逻辑删除 `status=0`）。**所有接口前置 `groupInfoService.checkGroupRole(MEMBER)`**（非成员抛 `CODE_2304`）。预览/下载复用本地文件服务 `getLocalFilePath` 的 `group` 分支 + 后端 `ChatController.downloadFile` 的 `partType=group` 分支，零新增下载路由 |
| 敏感词过滤 | 发送链路（聊天消息 / 朋友圈发布 / 评论）注入 `SensitiveWordService.filter`：命中 `level=3` 抛 `CODE_2701` 拒绝写入（不入库不推送）；命中 `level1/2` 替换为 `***` 后继续；词库取自 `sensitive_word` 表 `status=1 AND delete_flag=0`，`@PostConstruct` 启动加载到内存，管理端每次写变更后自动 `reload()` 热更新；空词库无副作用 |
| 敏感词库管理 | 管理端 `/admin/sensitiveWord/*` 五接口（均 `checkAdmin=true`）：`POST loadWord`（keyword/level/status 分页，仅存活行）、`POST saveWord`（新增/编辑，重复 `CODE_2704`、编辑对象不存在 `CODE_2705`）、`POST deleteWord`（逻辑删除 `delete_flag`=时间戳 ms，不存在 `CODE_2705`）、`POST importWords`（multipart，`.txt` 统一级别 / `.csv` 三列 `word,level,status`，按扩展名识别，≤5000 行/≤2MB 否则 `CODE_1001`，返回 `{success,skipped,failed}` 三态，重复行计 skipped 不入库）、`GET exportWords`（`text/csv` 文件流，UTF-8 BOM + `=+-@` 开头值前置 `'` 防公式注入；导入侧对 `'`+公式头剥离该引号保证往返等价）。**写操作（保存/删除/导入有新增行）后自动调 `reload()`，新词即刻生效**。`sensitive_word` 新增 `delete_flag BIGINT`（0=存活，非0=删除 ms）+ 唯一索引 `uk_word_flag(word,delete_flag)`（已删行不占唯一位 → 删后可重导/重增，旧 `uk_word` 已移除，migration-007）。前端 `views/admin/SensitiveWord.vue` + 菜单「敏感词管理」 |
| 语音/视频通话 | 经现有 Netty WS（5051）信令中继（**不碰媒体**），媒体走 WebRTC P2P **full-mesh** 直连；新增帧类型 `CALL_INVITE`/`CALL_ACCEPT`/`CALL_REJECT`/`CALL_SIGNAL`/`CALL_HANGUP`/`CALL_CANCEL`/`CALL_BUSY`/`CALL_JOIN`。服务端 `CallRoomRegistry`（内存，线程安全）维护房间（callId→发起方/类型/参与者/接听状态/开始时间）用于路由与 `call_log` 落库。单聊须好友、群呼须同群（越权 `CODE_2401`/`CODE_2302`）。TURN 配置 `easychat.turn.*` 由服务端随信令引导帧下发 `iceServers`（前端源码不含凭据）；缺省仅 STUN 可降级。通话以任一方式结束（接听后挂断/未接/拒接/取消/忙线）落 `call_log`（migration-008：`caller_id`/`call_type`(1单聊2群)/`peer_id`或`group_id`/`media_type`(1音频2音视频)/`start_time`/`end_time`/`status`(1已接2未接3拒接4取消5忙线)/`participant_count`/`create_time`）。群呼 full-mesh 参与者封顶 `easychat.call.max-participants`（默认 6），超出在 invite 阶段拒绝。前端 `utils/WebRTC.js`（getUserMedia(audio+video)+多 RTCPeerConnection 网格）+ `views/chat/CallWindow.vue` + `useCallStore`，单聊 `Chat.vue` 与群 `GroupChat.vue` 头部「音视频通话」按钮，复用现有 WS 连接 | ⚠️ **现状失真（2026-09-26 更正）**：截至本日仅后端信令中继（`CallRoomRegistry`/`CALL_*` 帧）与 `call_log` 落库落地（且在工作区**未提交**），前端 `utils/WebRTC.js`/`CallWindow.vue`/`useCallStore`/入口按钮**均未实现**，特性当前不可用；openspec 未闭环（tasks 全未勾、未归档、缺 QA/Retro、specs 无 voice-call）。前端真正实现需另走 L4 四件套 + 人工确认关卡。
| 内容举报 | `POST /report/moment`（`momentId`/`commentId` + `reason` + `description`）、`POST /report/chat`（`messageId` + `reason` + `description`），均 `@GlobalInterceptor`；落 `moment_report`/`message_report`（`status=0` 待处理，仅落库）；同人同对象 `status=0` 幂等（已存在直接返回成功）；对象不存在返回 `CODE_2501`（动态/评论）/`CODE_2201`（消息）。`reason` 语义：0色情 / 1暴力 / 2诈骗 / 3侵权 / 4其他。前端入口：聊天消息右键「举报」、朋友圈他人动态下拉「举报」、他人评论「举报」→ 通用 `ReportDialog.vue`（理由单选 + 选填说明） |
| 举报处理与审计 | 管理端闭环：`POST /admin/report/loadReport`（跨 `moment_report`/`message_report` 的统一分页列表，按 reportType/status/reason/时间过滤，UNION 读视图 `ReportReadMapper`）、`POST /admin/report/getReportDetail`（含被举报内容全文 + 举报人/发布者信息）、`POST /admin/report/dealReport`（置 status 1已处理/2已驳回 + 记 handleUserId/handleTime/handleNote/handleAction；handleAction=1 软删动态/评论 `status=0`，聊天消息无删除位仅记录；handleAction=2 调 `userInfoService.updateUserStatus(0, publisherId)` 封禁发布者；末了写 `report_audit_log`）、`POST /admin/report/loadAuditLog`（审计日志分页）；四接口均 `@GlobalInterceptor(checkAdmin=true)`，非管理员返回 `CODE_404`；不存在/已处置记录返回 `CODE_2702`。前端：`views/admin/ReportList.vue`（列表 + 详情弹窗含审计时间线 + 处置弹窗），`Admin.vue` 菜单「举报管理」。`moment_report`/`message_report` 已补齐 `handle_*` 字段，新增 `report_audit_log` 表（migration-006） |

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
> | 2026-09-26 | 新增深色模式（浅色/深色切换，本地持久化 + Element Plus 暗色主题 + 自定义外壳 `--ec-*` 变量） | 四·新增能力（从简单项续做） |
> | 2026-09-26 | 新增群文件（上传/列表/删除/下载，复用分片与本地文件服务，全接口 `checkGroupRole(MEMBER)` 最小权限） | openspec 2026-09-26-group-file |
> | 2026-09-26 | 新增内容治理：敏感词实时过滤（level3 拦截 `CODE_2701` / level1-2 替换 `***`，词库 `sensitive_word` 内存加载）+ 举报（动态/评论/消息，幂等落 `moment_report`/`message_report`，仅落库不处理） | openspec 2026-09-26-content-moderation |
> | 2026-09-26 | 新增举报处理与管理端审计：管理端 `/admin/report/*`（checkAdmin）四接口（列表/详情/处置/审计日志），UNION 读视图 + `report_audit_log` 不可变审计；补齐举报表 `handle_*` 字段、新增审计表（migration-006）；前端 `ReportList.vue` + 菜单「举报管理」 | openspec 2026-09-26-report-admin |
> | 2026-09-26 | 新增敏感词库管理端：`/admin/sensitiveWord/*` 五接口（增改删/导入/导出，写后自动 `reload()` 热更），错误码 `2704`/`2705`；`sensitive_word` 加 `delete_flag BIGINT` + `uk_word_flag(word,delete_flag)`（旧 `uk_word` 移除），引擎加载范围改 `status=1 AND delete_flag=0`；前端 `SensitiveWord.vue` + 菜单「敏感词管理」 | openspec 2026-09-26-sensitive-word-admin |
> | 2026-09-26 | 群文件列表修复：`GroupFile` PO 补 `uploadUserNickName` 字段 + Mapper `resultMap` 补映射（原先子查询带出昵称但被静默丢弃）；列表现正确返回上传人昵称，活体冒烟 23/23 通过 | 遗留项清理 |
> | 2026-09-26 | 深色模式 v2：聊天气泡（接收白底/发送绿底）、消息输入区、朋友圈卡片主背景与正文色改用 `--ec-*` 变量（base.scss 新增 bubble/card/quote/recalled/input 系列），深色下不再刺眼；品牌强调色与次级灰有意保留 | 遗留项清理 |
> | 2026-09-26 | 契约门禁升级：扩展扫描 Electron 主进程 `src/main` 的 `/api/*` 调用，消除 `/chat/downloadFile`、`/update/download` 两个历史误报孤儿路由（现 0 孤儿 / 0 漂移） | 遗留项清理 |
> | 2026-09-26 | 聊天记录导出明确为 local-only 设计边界（仅读本地 SQLite），云端全量漫游导出归入后续独立「备份/迁移」专项，不再作为遗留缺口 | 遗留项清理 |
> | 2026-09-26 | 敏感词种子 migration-005 已对 easychat 库执行（9 条 level2/3 示例词），内容治理正式生效；3 个回归脚本归位 `scripts/smoke/` | 遗留项清理 |
> | 2026-09-26 | 语音/视频通话**仅后端半截落地（前端缺失，特性不可用）**：Netty WS 信令中继（`CALL_*` 帧）+ `CallRoomRegistry` + TURN 配置随信令下发 + `call_log` 表（migration-008）已在工作区实现但**尚未提交**；前端 `WebRTC.js`/`CallWindow.vue`/`useCallStore`/入口按钮均未实现。openspec 未闭环（tasks 全未勾、未归档、缺 QA/Retro、specs 无 voice-call）。原记「L4 已通过」与事实不符，已更正 | openspec 2026-09-26-voice-call |
