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

## 1.1 运行时配置分层

| 文件 | 角色 | 敏感项策略 |
|------|------|-----------|
| `easychat-java/src/main/resources/application.properties` | **公共基线**，跨环境一致；含 `spring.profiles.active=dev` | 全部敏感键为 `${ENV_VAR:默认值}` 占位符，**无裸值**；不含 `easychat.turn.*`（环境专属） |
| `…/application-dev.properties` | 本地开发 / 本机双实例联调 | 含公共 TURN 凭据（`guest/guess`）+ 本地 DB 口令 + `test@qq.com` |
| `…/application-prod.properties` | 生产模板（入库） | **DB 密码无默认可用值**（`${SPRING_DATASOURCE_PASSWORD:}`，未注入即启动失败，属预期）；TURN 三项留空 → 降级纯 STUN |
| 仓库根 `.env.example` | 凭据模板，入库 | 仅占位值 |
| 仓库根 `.env` | 真实凭据载体，**不入库**（`.gitignore`） | — |

- 优先级：`application.properties` → `application-${profile}.properties` → 系统环境变量
- **Spring Boot 2.6 不自动读 `.env`**（自动导入是 3.0+ 的 `spring.config.import`），须用 `docker compose --env-file .env` 或 shell `set -a; . ./.env; set +a` 注入
- 生产启用：`export SPRING_PROFILES_ACTIVE=prod`
- 容器化：`easychat-java/Dockerfile`（多阶段，非 root）+ 根 `docker-compose.yml`（MySQL 8 + Redis 7 + 后端，首启动自动导入 `easychat.sql`）
- 门禁：`node scripts/verify/verify_no_hardcoded_secret.mjs`（19 项断言，已入 CI）
- 已知冗余：`easychat.project-folder` 为**死键**（全仓无人读 `EasyChatProperties#getProjectFolder`），落盘目录实际由 `project.folder` 经 `AppConfig#getProjectFolder` 提供；`EasyChatProperties` 的 Java 字段默认值仍在，待清理
- 容器部署注意：`CallService.rooms` / `USER_CONTEXT_MAP` 为进程内 `ConcurrentHashMap`，**后端多实例部署时通话房间路由会失效**（单实例约束，未解决）

## 2. 后端依赖版本（`pom.xml`）

| 依赖 | 版本 |
|------|------|
| Spring Boot | `2.6.1` |
| Java | `17`（2026-10-03 由 1.8 升级；与 CI temurin 17、Dockerfile temurin-17 三方对齐） |
| MyBatis (spring-boot-starter) | `1.3.2` |
| MySQL Connector | `8.0.23` |
| Netty (netty-all) | `4.1.50.Final` |
| Redisson | `3.12.3` |
| fastjson | `1.2.66` |
| logback | `1.2.10` |
| easy-captcha | `1.6.2` |

> 已知技术债（2026-10-03 已解决）：`spring-boot-maven-plugin` 曾显式锁 `2.2.6.RELEASE`，与 parent `2.6.1` 倒挂（产物 `Spring-Boot-Version: 2.2.6.RELEASE`，即 loader/repackage 2.2.6 去跑 2.6.1 的类）。现已删除显式 `<version>`，由 parent 统一管理，实测产物为 `Spring-Boot-Version: 2.6.1` / `Build-Jdk-Spec: 17`。
>
> 同批解决的另一处：`maven.compiler.source/target` 曾显式写死 `1.8`，**覆盖了 parent 的 `java.version`**——即「改 `java.version` 毫无作用」的假象。现两者同为 17。

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
| 密码加密 | **BCrypt**（`$2a$`，列宽 60）；客户端一律发明文，服务端承担哈希（2026-10-02 起；MD5 双验证保留，登录成功自动升级） |
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
| 管理员删除帧 | 类型 `20 ADMIN_DELETE`：管理端处置删除后推墓碑（`messageContent`/`lastMessage`=「该消息已被管理员删除」，`sendUserId`=原发送者，不改写 DB 原文）；单聊接收方帧经联系人转换 `contactId`=原发送者，发送方副本 `contactId`=会话对方且全部离线时入其离线缓冲（补推时 `sendUserId`=收件人则跳过转换）；群帧 `contactId` 保持 groupId（补推时 `contactType=1` 跳过转换），未收在线帧的群成员由删除动作逐成员入离线缓冲；客户端不计未读、不闪通知、不放提示音（通知白名单天然排除 20），右键菜单隐藏 |

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
| `Tables.js` | 表结构 DDL；`chat_session_user` 含 `top_type`/`no_disturb`/`draft` 三列（后两列系 2026-09-29 补：此前缺失导致草稿与免打扰本地写入被列映射过滤丢弃、重启不恢复），存量库由 `alter_tables` 启动时自动 ALTER |
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
| 修改密码 | `POST /userInfo/updatePassword` 必传 `oldPassword` + `password`；旧密码校验为 **BCrypt + MD5 双验证**（`isBCrypt` 分支，不是只比 MD5），且新旧不得相同。**2026-10-03 起成功后吊销该用户全部端 Redis Token（`cleanUserTokenByUserId`）并推 `FORCE_OFF_LINE(7)` 强制下线**，用户须重新登录；校验失败路径不吊销 |
| 找回密码 | `POST /account/sendEmailCode`（`type=1`，60 秒防重发，10 分钟有效）+ `POST /account/resetPassword`（`email`/`code`/`newPassword`）。**2026-10-03 起验证码经 SMTP 真实投递**（`MailService`）；未配置 `spring.mail.host` 时 **fail-closed 返回 `CODE_1002` 拒绝发送**，不再写日志。成功后同样吊销全部端 Token + 推 7 帧；验证码错误/过期/邮箱未注册三条早退路径不吊销 |
| 邮件服务配置 | `spring.mail.host/port/username/password/from`（dev 与 prod 均留空，由 `SPRING_MAIL_*` 注入，`.env.example` 有模板）。**任一项缺失即视为未配置 → fail-closed**。本地要联调找回密码须自行填入真实 SMTP，否则该功能显式不可用（预期行为，非故障） |
| 未登录端点限流 | `@GlobalInterceptor(checkRateLimit = true)` 共 4 个端点（`/account/login`、`/register`、`/sendEmailCode`、`/resetPassword`，全部 `checkLogin = false`）。限流键：token 非空 → `rate_limit:{token}`；**token 为空 → `rate_limit:ip:{ip}`**（ip 取 `X-Forwarded-For` 首段，回退 `getRemoteAddr()`）。阈值 60 次/60 秒，超限 `CODE_1001`。**2026-10-03 修复**：原实现在 `token == null` 时直接 `return`，而登录页无 token 头（`Request.js` 取 `localStorage.token` 为 null、axios 丢弃 null header），导致这 4 个端点限流**从未生效** |
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
| 消息删除位 | `chat_message.delete_flag BIGINT NOT NULL DEFAULT 0`（0=存活，非0=删除时间戳 ms；migration-009 已执行，基线 `easychat.sql` 已同步）。`ChatMessageMapper.xml query_condition` **无条件** `AND delete_flag=0`（`loadHistoryMessage`/`searchMessage`/`globalSearch`/同步补推全路径过滤，无 HTTP 可绕过开关）；管理端证据读取走 `selectByMessageId` PK 读不过滤（举报详情/列表摘要保留原文）；已删消息 `recallMessage`/`downloadFile`/`locateMessage` 返回 `CODE_2201`（HTTP 400）；被删消息为会话最新时 `chat_session.last_message` 置「该消息已被管理员删除」占位（不动 `last_receive_time`、不计未读），非最新不动；幂等：已删再处置不改写、不重推。前端 `wsClient.js case 20` 墓碑化本地行（缺行补插），`Chat.vue` 同步预览，`ChatMessage.vue` 墓碑渲染 |
| 聊天记录导出 | 会话右键菜单「导出聊天记录（TXT / CSV）」→ IPC `exportChatRecord` → 主进程 `src/main/exportChat.js` 读本地 SQLite 全量 → `dialog.showSaveDialog` → 落盘。**只导本地已持久化消息，不拉云端**——此为有意识的设计边界（local-only）：`exportChat.js` 仅读本地 SQLite，云端全量漫游导出归入后续独立的「备份/迁移」专项，不在本导出能力范围内；CSV 带 UTF-8 BOM 且对 `=+-@` 开头值前置单引号防 Excel 公式注入 |
| 深色模式 | 账号设置「外观主题」浅色/深色单选 → IPC `updateSysSetting` 合入 `user_setting.sysSetting.theme`（主进程键白名单已含 `theme`，不覆盖既有键）；`<html>.dark` 激活 Element Plus 暗色 css-vars（`element-plus/theme-chalk/dark/css-vars.css`）+ 自定义外壳 `--ec-*` 变量（`base.scss` 定义，`Layout`/`ContentPanel`/`Main`/`Setting`/`Chat` 引用）。仅本地持久化，不依赖后端；启动时由 `Main.vue` 读本地设置应用 |
| 群文件 | 群聊会话头部「群文件」图标 → `GroupFile.vue` 面板。上传走既有分片 `/upload/uploadChunk`+`/upload/checkChunks`，合并落盘 `file/group/<fileId>.<ext>`（新增 `Constants.FILE_FOLDER_GROUP`）并写 `group_file`（`status=1`，`file_type` 0图片/1视频/2文件）；列表 `POST /group/file/list`（分页、`create_time desc`、含上传人昵称）；删除 `POST /group/file/delete`（上传者本人或群主/管理员可删，逻辑删除 `status=0`）。**所有接口前置 `groupInfoService.checkGroupRole(MEMBER)`**（非成员抛 `CODE_2304`）。预览/下载复用本地文件服务 `getLocalFilePath` 的 `group` 分支 + 后端 `ChatController.downloadFile` 的 `partType=group` 分支，零新增下载路由 |
| 敏感词过滤 | 发送链路（聊天消息 / 朋友圈发布 / 评论）注入 `SensitiveWordService.filter`：命中 `level=3` 抛 `CODE_2701` 拒绝写入（不入库不推送）；命中 `level1/2` 替换为 `***` 后继续；词库取自 `sensitive_word` 表 `status=1 AND delete_flag=0`，`@PostConstruct` 启动加载到内存，管理端每次写变更后自动 `reload()` 热更新；空词库无副作用 |
| 敏感词库管理 | 管理端 `/admin/sensitiveWord/*` 五接口（均 `checkAdmin=true`）：`POST loadWord`（keyword/level/status 分页，仅存活行）、`POST saveWord`（新增/编辑，重复 `CODE_2704`、编辑对象不存在 `CODE_2705`）、`POST deleteWord`（逻辑删除 `delete_flag`=时间戳 ms，不存在 `CODE_2705`）、`POST importWords`（multipart，`.txt` 统一级别 / `.csv` 三列 `word,level,status`，按扩展名识别，≤5000 行/≤2MB 否则 `CODE_1001`，返回 `{success,skipped,failed}` 三态，重复行计 skipped 不入库）、`GET exportWords`（`text/csv` 文件流，UTF-8 BOM + `=+-@` 开头值前置 `'` 防公式注入；导入侧对 `'`+公式头剥离该引号保证往返等价）。**写操作（保存/删除/导入有新增行）后自动调 `reload()`，新词即刻生效**。`sensitive_word` 新增 `delete_flag BIGINT`（0=存活，非0=删除 ms）+ 唯一索引 `uk_word_flag(word,delete_flag)`（已删行不占唯一位 → 删后可重导/重增，旧 `uk_word` 已移除，migration-007）。前端 `views/admin/SensitiveWord.vue` + 菜单「敏感词管理」 |
| 语音/视频通话 | 经现有 Netty WS（5051）信令中继（**不碰媒体**），媒体走 WebRTC P2P **full-mesh** 直连；新增帧类型 `CALL_INVITE`/`CALL_ACCEPT`/`CALL_REJECT`/`CALL_SIGNAL`/`CALL_HANGUP`/`CALL_CANCEL`/`CALL_BUSY`/`CALL_JOIN`。服务端 `CallRoomRegistry`（内存，线程安全）维护房间（callId→发起方/类型/参与者/接听状态/开始时间）用于路由与 `call_log` 落库。单聊须好友、群呼须同群（越权 `CODE_2401`/`CODE_2302`）。TURN 配置 `easychat.turn.*` 由服务端随信令引导帧下发 `iceServers`（前端源码不含凭据）；缺省仅 STUN 可降级。通话以任一方式结束（接听后挂断/未接/拒接/取消/忙线）落 `call_log`（migration-008：`caller_id`/`call_type`(1单聊2群)/`peer_id`或`group_id`/`media_type`(1音频2音视频)/`start_time`/`end_time`/`status`(1已接2未接3拒接4取消5忙线)/`participant_count`/`create_time`）。群呼 full-mesh 参与者封顶 `easychat.call.max-participants`（默认 6），超出在 invite 阶段拒绝。前端 `utils/WebRTC.js`（getUserMedia(audio+video)+多 RTCPeerConnection 网格）+ `views/chat/CallWindow.vue` + `useCallStore`，单聊 `Chat.vue` 与群 `GroupChat.vue` 头部「音视频通话」按钮，复用现有 WS 连接。**（2026-10-02 复核：原「前端未实现」标注已于 2026-09-26 归档时更正，`WebRTC.js`/`CallWindow.vue`/`useCallStore` 均已存在，原标注为过期残留）**；真实 WebRTC 媒体链路（摄像头/麦克风、对称 NAT 走 TURN）需本机双实例手动验证，沙箱无 GUI 无法覆盖。
| 内容举报 | `POST /report/moment`（`momentId`/`commentId` + `reason` + `description`）、`POST /report/chat`（`messageId` + `reason` + `description`），均 `@GlobalInterceptor`；落 `moment_report`/`message_report`（`status=0` 待处理，仅落库）；同人同对象 `status=0` 幂等（已存在直接返回成功）；对象不存在返回 `CODE_2501`（动态/评论）/`CODE_2201`（消息）。`reason` 语义：0色情 / 1暴力 / 2诈骗 / 3侵权 / 4其他。前端入口：聊天消息右键「举报」、朋友圈他人动态下拉「举报」、他人评论「举报」→ 通用 `ReportDialog.vue`（理由单选 + 选填说明） |
| 举报处理与审计 | 管理端闭环：`POST /admin/report/loadReport`（跨 `moment_report`/`message_report` 的统一分页列表，按 reportType/status/reason/时间过滤，UNION 读视图 `ReportReadMapper`）、`POST /admin/report/getReportDetail`（含被举报内容全文 + 举报人/发布者信息）、`POST /admin/report/dealReport`（置 status 1已处理/2已驳回 + 记 handleUserId/handleTime/handleNote/handleAction；handleAction=1 软删动态/评论 `status=0`，聊天消息执行 `delete_flag` 逻辑删除 + 事务后（afterCommit）推 20 帧同步（幂等：已删跳过不重推，handleNote 同时进报告行与审计日志）；handleAction=2 调 `userInfoService.updateUserStatus(0, publisherId)` 封禁发布者；末了写 `report_audit_log`）、`POST /admin/report/loadAuditLog`（审计日志分页）；四接口均 `@GlobalInterceptor(checkAdmin=true)`，非管理员返回 `CODE_1003`（**原记 `CODE_404` 有误，该码已从枚举删除**）；不存在/已处置记录返回 `CODE_2702`。前端：`views/admin/ReportList.vue`（列表 + 详情弹窗含审计时间线 + 处置弹窗），`Admin.vue` 菜单「举报管理」。`moment_report`/`message_report` 已补齐 `handle_*` 字段，新增 `report_audit_log` 表（migration-006） |
| 输入/在线状态 | 帧类型 `TYPING_STATUS(21)` / `ONLINE_STATUS(22)` / `USER_STATUS_CHANGE(23)` / `ONLINE_STATUS_HIDDEN(27)`（`MessageTypeEnum`）。链路：渲染层 `MessageSend.vue`（输入防抖 3s）→ preload `sendTypingStatus` → **主进程 `ipc.js` 注册 `onSendTypingStatus`** → `wsClient.js` → WS 5051 → `HandlerWebSocket.handleTypingStatus` 中继；对方 `Chat.vue` 监听 `typingStatus` 显示提示。状态变更经 `UserInfo.vue` → `sendUserStatusChange` → Redis `easychat:ws:user:status:{userId}`。**易踩**：主进程通道须在 `index.js` 调一次，漏注册即静默失效（2026-10-02 已补，门禁 `check-ipc-registration.mjs --strict` 拦截） |
| **WS 帧协议对账** | 帧号 0–27 连续无空洞。**落库白名单**（`ChatMessageServiceImpl` 的 `ArraysUtil.contains(new Integer[]{...})`，仅 `CHAT(2)/GROUP_CREATE(3)/ADD_FRIEND(1)/MEDIA_CHAT(5)`）决定消息是否写 `chat_message` 表；这些帧客户端**必须**有 `case`，否则历史漫游也拿不到。`ADD_FRIEND_SELF(13)` 属服务端内部帧（`applyContactConvert` 投递前转成 `ADD_FRIEND(1)`），客户端**不得**有 case；`USER_STATUS_CHANGE(23)` 是渲染→服务端请求帧。**已知未接通**：`LOCATION(25)` 位置消息、`VOICE(24)` 语音消息——两层都断（不在落库白名单 + `wsClient.js` 无 case + `Chat.vue:136` 分发条件未含，`ChatMessageVoice.vue` 为死组件），DB 中 `message_type` 仅 1/2，0 条 24/25 记录 → 从未被真实使用。守卫：`node scripts/verify/verify_ws_frame_parity.mjs`（已接 pre-push + CI），新增帧必须在门禁的 `MUST_HANDLE`/`INTERNAL_FRAMES`/`KNOWN_GAP` 三者之一显式声明意图，否则阻断 |
| 收藏消息 | 后端 `POST /favorite/add`（`messageId`/`content`/`filePath`，重复返「该消息已收藏」）、`POST /favorite/cancel`（`favoriteId`）、`GET /favorite/list`（**全量不分页**）；落 `favorite` 表（migration-010 已在存量库补建，`uk_user_message` 唯一）。前端：消息右键「收藏」（文本/媒体/位置消息均可，撤回与管理员删除态不显示）→ `Chat.vue` 调接口；列表页 `views/setting/Favorite.vue`，设置页「我的收藏」入口 + 路由 `/setting/favorite` |
| 位置消息 | 消息类型 `LOCATION(25)`；信息存 `extra_data` = `{location, latitude, longitude}`（ADR-001，避免新增字段）。前端：工具栏「位置」→ 弹窗（地址输入 + 可选 `navigator.geolocation` 获取当前位置）→ `MessageSend.vue` 发 `messageType=25`；展示 `ChatMessageLocation.vue`（气泡 → 详情弹窗 → `openUrl` IPC 用 `shell.openExternal` 打开高德 URI）。**不引地图 SDK**（2026-10-02 人工确认） |
| 群二维码 / 群邀请 | 后端 `POST /group/qrCode/generate`、`POST /group/qrCode/join`（`qrCodeToken`）、`POST /group/invite/generate`、`POST /group/invite/join`（`inviteToken`）；generate 均需群主/管理员。Redis 双向映射：正向 `easychat:group:qrcode:{groupId}` / `easychat:group:invite:{groupId}` → token，**反查** `easychat:group:qrcode:token:{token}` / `easychat:group:invite:token:{token}` → groupId，同 TTL 7 天（**反查索引是 join 可用的前提**，原为三处 `return null` 桩）。前端：`GroupDetail.vue` 底部两个按钮（仅群主/管理员可见）+ 两个弹窗，QR 图由 `qrcode` 包生成 dataURL |
| 表情包 | `GET /emoji/list`（当前用户全部表情，不分页）、`POST /emoji/upload`（multipart `file`，≤5MB，仅 `Constants.IMAGE_SUFFIX_LIST`）、`POST /emoji/delete`（`emojiId`）；落 `emoji` 表（migration-010 补建）。文件落 `projectFolder + file/emoji/`。**注意**：鉴权取用户须走 `ABaseController.getTokenUserInfo(request)`，读 `request.getAttribute("userInfo")` 恒为 null（拦截器不写该 attribute） |
| **位置消息(25) / 语音消息(24)** | `openspec/specs/location-and-voice-message`。**2026-10-03 才首次接通**——此前两帧虽在 `MessageTypeEnum` 里但**五层全断**（发送白名单 / 落库白名单 / 上传白名单 / wsClient case / `Chat.vue` 分发），`chat_message.message_type` 仅有 `1 2`、**0 条 24/25** 记录，即从未被真正使用过。现状：`POST /chat/sendMessage` 放行 24/25（24 须 `file_name`/`duration∈[1,60]`/`fileType=3`；25 须 `extra_data` 是含 `location` 的合法 JSON ≤2000 字符，否则 `1001`）；落库白名单含 24/25；`allowedFileTypes` 默认值追加 `webm/m4a/wav/ogg`（+ 原有的 `mp3` 共 5 类，`Constants.AUDIO_SUFFIX_LIST` 为真源，不含 `.amr`）；客户端 `wsClient.js case 24/25` + `Chat.vue` 分发 + `ChatMessage.vue` 挂 `ChatMessageVoice.vue`（此前是死组件）；`sendVoiceMessage` 已改为「先发消息拿 messageId → 再 uploadFile」（此前只发消息不上传 = 空壳）；搜索默认范围与撤回白名单均含 24/25。**⚠ 六层**：本地文件服务器 `file.js` 的 `FILE_TYPE_CONTENT_TYPE` 此前缺 `fileType=3` → content-type 成 `undefinedwebm` → 浏览器不解码、`<audio>` 静默无声；已补 `"3":"audio/"`，并把 `"2"` 修正为带 `/` 的前缀。守卫 `verify_file_type_content_type.mjs` |
| **语音未播放红点** | 状态在**旁挂表 `chat_message_voice_read(message_id, user_id)`**（migration-012，幂等：CREATE TABLE IF NOT EXISTS，**已实跑两次验证**；含 `is_read`/`read_time`/`create_time` + `idx_user_read`）。**不给 `chat_message` 加列**——「谁播了」是 per-receiver 状态，加列会让 A 播放污染 B 看到的行（ADR-001）。接口：`POST /chat/markVoiceRead`（仅接收方；发送者本人也拒，否则红点永远消失）、`POST /chat/loadVoiceRead`（`messageIdList` 逗号分隔，上限 200 → `1001`，强制按 `userId` 过滤）。**不新增 WS 帧**——红点是私有状态，对方不关心（ADR-002）。`markVoiceRead` 走 `insertOrUpdate`（接收方可能从未打开过会话，行不存在时纯 UPDATE 影响 0 行）；单测 `ChatMessageServiceImplTest` 覆盖单聊/群聊分支与越权 |
| **隐私设置（统一页）** | 四区块同页：`views/setting/Privacy.vue` 路由 `/setting/privacy`。① 加我的方式 `join_type`（0直接加入/1需验证）② 朋友圈可见范围 `user_info.moment_visibility`（0公开/1仅好友/2仅自己/3白名单/4黑名单，语义与既有 `moment.visibility` 对齐）+ `moment_visible_list`/`moment_invisible_list`（JSON 数组，**仅作发布默认值**，`canView` 不参与，改设置不追溯历史动态）③ 在线状态可见性 `online_status_visible`（1展示/0隐藏，关则不广播 + 推 27 帧抹除，重开立即广播）④ 黑名单（`/contact/loadBlackList`、`/contact/removeBlackList`）。**旧路由 `/setting/userInfo`、`/setting/blacklist` 保留 `redirect`**，勿删。名单选人用通用组件 `components/ContactPicker.vue`（`views/chat/UserSelect.vue` 是群成员专用、有提交副作用，**不可复用**） |

## 13. 前端主进程约定（易踩）

| 项 | 值 |
|----|-----|
| IPC 通道注册 | `src/main/ipc.js` 里 `const onXxx = () => {...}` 定义并加进 `export {}` 后，**必须在 `src/main/index.js` 的 import 列表 + 启动初始化中调用一次**才会生效。项目不做自动扫描，漏注册时构建完全无感、功能静默失效 |
| 注册遗漏门禁 | `node scripts/check-ipc-registration.mjs --strict`：对拍 ipc.js 导出与 index.js 调用，缺失即 exit 1 |
| 全局工具能力边界 | `utils/Confirm.js` 仅支持 `{message, okfun, showCancelBtn, okText}`，**没有** `cancelfun` / `cancelText`；`utils/Message.js` 仅 `success` / `error` / `warning`。调用前先读实现，勿臆造参数 |
| 本地 SQLite 字段 | `chat_message` 等表为 **snake_case**（`message_id`、`send_user_nick_name`、`file_type`）。渲染层对象是 camelCase，主进程读库取值勿混用 |
| 文件类型枚举 | `file_type` / `File_TYPE`：0 图片、1 视频、2 文件 |

---

## 14. 已知遗留（未修的已确认缺陷，按批次推进）

> 登记纪律：每条须有「缺陷描述 + 为何本批不做 + 落点」。修复后**必须删行**，
> 避免本表腐烂成摆设（参照 `verify_ws_frame_parity.mjs` 的 `KNOWN_GAP` 过期即阻断做法）。

| # | 缺陷 | 为何未修 | 落点 |
|---|------|----------|------|
| 1 | `email_verify_code` 无失败次数字段，6 位码 10 分钟有效**理论上可暴力** | **2026-10-03 人工决策：不实现失败次数限制。** 现存残余风险已被三重约束压到可接受区间：① IP 限流 60 次/分（本批新加，单 IP 10 分钟内最多约 600 次尝试，相对 10^6 空间成功率约 0.06%）；② 60 秒防重发使换码无效（换码不增加尝试次数）；③ 验证码 10 分钟过期。若后续风险评估变化（改短有效期 / 降低限流阈值 / 面向公网开放注册），需重新评估本项 | 已决策不做——**改动此决策须经人工确认** |
| 2 | `operation_log.ip_address` **恒为 null**：6 处 `recordLog` 调用（`UserInfoServiceImpl:270/275/280/420/504`、`ChatMessageServiceImpl:673`）全传 `null`，登录失败/强制下线这类最需 IP 溯源的事件没有 IP | Service 不得感知 `HttpServletRequest`，需 AOP 取 `RequestContextHolder`（横切面改造） | 独立 Change |
| 3 | `@所有人` 权限**仅在客户端生效**：Java 侧无 role 校验，普通成员手工构造 `extraData.atAll` 即可冒用群主/管理员身份 | 涉权限语义（L4） | 独立 Change，`saveMessage` 校验发送者 `role ∈ {0,1}` |
| 4 | ~~无 `verify_schema_drift.mjs`~~ → **2026-10-03 已实现**：`scripts/verify/verify_schema_drift.mjs` 比对基线与活库 `information_schema`，接 pre-push + CI 独立 job（MySQL 8 service），fail-closed；钉死 `user_info.password` 需 `varchar(≥60)` 硬不变量；含迁移编号连续性检查。**残余**：`docker-compose.yml` 仍只在 MySQL **首次启动**导入 `easychat.sql`，存量卷不会重放迁移 → 「新环境按 001~012 顺序执行」目前仍靠人记 | compose 中加迁移执行步骤属部署配置基线变更 | 独立 Change（L4） |
| 5 | ~~本地 JDK 与 CI/Docker 不一致~~ → **2026-10-03 已解决**：`java.version` / `maven.compiler.source` / `maven.compiler.target` / CI / Dockerfile 全部升到 **17**，本地与 CI 不再漂移。连带修掉「`mvn spring-boot:run` 在 JDK 8 上直接启动失败」（`--add-opens` 现为 JDK 17 必需项，Dockerfile 的 `JAVA_OPTS` 同步补齐） | — | 已闭环 |
| 6 | ~~`spring-boot-maven-plugin` 版本倒挂~~ → **2026-10-03 已解决**：删除显式 `<version>`，由 parent 管理，实测产物 `Spring-Boot-Version: 2.6.1` | — | 已闭环 |
| 7 | 前端零自动化测试：`package.json` 无 `test` script、无 vitest，渲染层/主进程质量全靠静态门禁 + 本机 GUI 手验 | 结构性缺口，需引入测试框架 | 独立 Change |
| 8 | `GlobalInterceptor` 注解**矩阵未纳入测试**：`checkLogin`/`checkAdmin`/`checkRateLimit` 的组合散落各 Controller，`GlobalOperationAspectTest` 只覆盖了若干代表端点 | 低危但易漂移 | 在 `GlobalOperationAspectTest` 补一条「遍历全仓注解输出矩阵」用例 |
| 9 | 「新环境按 001~012 顺序执行迁移」无自动化：`docker-compose.yml` 只在 MySQL **首次启动**导入 `easychat.sql`，存量数据卷永不重放迁移，也没有版本表记录「这个库跑到第几号了」 | 加迁移执行步骤 / 版本表属 DB 结构与部署配置变更（L4） | 独立 Change；`verify_schema_drift.mjs` 已能发现漂移，但不能执行迁移 |

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
> | 2026-09-26 | 聊天记录导出现有两种数据源：本地 SQLite（原 local-only）+ **云端全量漫游**（渲染层用 `loadHistoryMessage` 按 `lastMessageId` 游标翻页取全，主进程 `exportChat.js` 归一化 camelCase→snake_case 后复用同一套 TXT/CSV 格式化落盘）。右键会话新增「导出云端全量（TXT/CSV）」。原记「云端导出归入后续专项」已作废 | 遗留项清理 |
> | 2026-09-26 | 敏感词种子 migration-005 已对 easychat 库执行（9 条 level2/3 示例词），内容治理正式生效；3 个回归脚本归位 `scripts/smoke/` | 遗留项清理 |
> | 2026-09-26 | 迁移脚本核对：migration-003 编号缺口系**有意保留**——原 `message-read-status` 随已读回执特性下线被删除（提交 400a054），新增 `easychat-migration-003-retired.sql` 纯注释占位说明，避免复用撞号；migration-007（`delete_flag` 列 + `uk_word_flag` 唯一索引）**已对 easychat 库执行**，基线 `easychat.sql` 已同步 | 遗留项清理 |
> | 2026-09-26 | 深色模式 v2 收尾：朋友圈/聊天深层视图的硬编码浅色背景（`#f7f7f7`/`#fafafa`/`#ededed`/`#fff` 等）与浅色描边（`#f0f0f0`/`#e8e8e8`）统一换为 `--ec-surface-*`/`--ec-divider*`/`--ec-card-bg` 变量，深色下不再出现浅色块；强调色与白字有意保留 | 遗留项清理 |
> | 2026-09-26 | 语音/视频通话**已全栈落地**：后端 Netty WS 信令中继（`CALL_*` 帧 -10~-17）+ `CallRoomRegistry` + TURN 配置随信令下发 + `call_log` 表（migration-008）；前端 `utils/WebRTC.js`（full-mesh P2P）+ `stores/useCallStore.js`（状态机）+ `views/chat/CallWindow.vue` + `Main.vue` 常驻监听 + `Chat.vue` 入口按钮；主进程 `sendCallFrame` 通道已注册。openspec 已闭环（归档 `openspec/archive/2026-09-26-voice-call`、spec 落 `specs/voice-call`、QA/Retro 已写）。**真实 WebRTC 媒体链路（摄像头/麦克风、对称 NAT 走 TURN）需本机双实例手动验证，沙箱无 GUI 无法覆盖**。原记「仅后端半截、前端缺失」已于本次更正 | openspec 2026-09-26-voice-call |
> | 2026-09-26 | 聊天记录备份升级为**跨会话全量**：设置页新增「数据备份」（`/setting/dataBackup`，`DataBackup.vue`）支持 TXT/CSV 一键备份全部会话；`utils/cloudBackup.js` 串行逐会话用 `loadHistoryMessage` 游标翻页取全（pageSize=100、失败会话跳过、总量软上限 200000）；主进程 `exportChat.js` 新增 `exportChatBackup()`（TXT 按会话分段 / CSV 增「会话」列）+ 新 IPC 通道 `exportChatBackup`（已注册）。**后端零改动**，复用既有接口与归属校验。活体冒烟 12/12 PASS（2 会话/31 条），GUI 交互留手动 | openspec 2026-09-26-chat-backup-export |
> | 2026-09-27 | 深色模式漏网补齐：上一轮只清了 `chat/` 与 `moment/`，本轮把 `contact/`（Search/Contact/ContactSearchResult/GroupDetail）、`admin/`（Admin/ReportList）、`Login`、`Update`、`show/ShowMedia`、`setting/FileManage`、`components/AvatarUpload` 共 11 个文件 19 处硬编码浅色背景/描边换成 `--ec-*` 变量。至此全仓该类硬编码浅色**仅剩 base.scss 变量定义本身**；强调色与白字（`color:#fff` 24 处）有意保留 | 遗留项清理 |
> | 2026-09-27 | 消息列表改为**自研不定高虚拟滚动**：新增 `views/chat/MessageVirtualList.vue`（ResizeObserver 实测每条高度 + 偏移表 + 二分定位），算法抽到 `utils/virtualListCore.mjs`（纯函数，15/15 算法验证通过）；`Chat.vue` 不再全量 `v-for` 渲染 `messageList`。因不可见项无真实 DOM，4 处 `scrollIntoView`/`getElementById` 定位已改为按 index 定位（`scrollToMessage`），原 `#message-panel` 滚动监听移除（改由组件 `@scroll`/`@load-more`）。**滚动手感（上翻是否跳动、图片加载后是否抖）需本机实测确认，沙箱无 GUI 无法覆盖** | 遗留项清理 |
> | 2026-09-29 | 敏感词走查两项遗留缺陷修复：① `ADB.js#update()` 空 set/空 where 短路返回 0（原先拼出 `update t set  where` 报 SQLITE_ERROR 并弹原生框阻塞主进程），`updateSessionAttr` 空值不落库；② 发送失败仅 2301/2302 联系人类错误弹「重新申请」，其余业务码（含 2701 敏感词）改「我知道了」，并订正旧码 `902`→`2301` | 敏感词走查发现 #1/#3 |
> | 2026-09-29 | **本地 SQLite `chat_session_user` 补列（前端 L4，人工已确认）**：新增 `no_disturb` 与 `draft` 两列（`add_tables` 建新库 + `alter_tables` 迁存量库）。此前两列缺失，`saveSessionDraft`/`setSessionNoDisturb` 与服务端 `-7` 同步帧的写入均被列映射静默过滤，导致**重启后草稿不恢复、免打扰状态丢失**（通知抑制与菜单状态读 `undefined`）。迁移验证 5/5 PASS（列存在 + 读写往返 + null 守卫不回归） | 人工确认 L4 关卡 |
> | 2026-09-29 | **管理端消息删除位（L4 四件套过人工关卡）**：`chat_message.delete_flag BIGINT`（migration-009 已执行 + 基线同步）+ 用户侧查询无条件过滤 + 管理端证据 PK 读保留；新增 WS 帧 `20 ADMIN_DELETE`（单聊双方/群成员在线直推，离线入缓冲，补推转换规则①sendUserId=收件人跳过、②contactType=1 群帧跳过）；已删消息 recall/download/locate 返回 2201；`dealReport handleAction=1` 接真实删除（afterCommit 推帧 + 幂等守卫）；前端 `wsClient case 20`/`Chat.vue`/`ChatMessage.vue` 墓碑渲染 + `ReportList` 警示改删除语义。冒烟 43/43 PASS + contract/hygiene 双绿。**双实例渲染侧（在线收帧/补推不出脏会话/右键隐藏/不闪）待用户本机验证** | openspec 2026-09-29-admin-message-delete |
> | 2026-10-02 | **登录必然失败修复（L4）**：BCrypt 改造（2026-09-30）只改了服务端，前端登录仍发 `md5(明文)`、注册发明文，口径分裂导致登录 100% 失败。`Login.vue` 去掉客户端哈希，四条链路统一发明文；**根因还有 DDL 只改基线没写迁移**——存量库 `user_info.password` 实为 `varchar(32)`，BCrypt 写不进去导致升级抛 `Data too long`、登录返回 500，补 `easychat-migration-010-password-and-im-tables.sql`（password 32→60 + 补建 `emoji`/`favorite`/`user_status`/`operation_log` 四张存量库缺失表）并已执行。另修：`index.js` 补注册 `onSendTypingStatus`/`onSendUserStatusChange`（原缺失致「正在输入/状态变更」静默失效）与 `getWindow` 未导入（稍后处理通知点击报错）。单测 120 例、守卫 `verify_password_handoff.mjs` 21/21、活体登录/改密往返全通 | openspec 2026-10-01-password-handoff-unify |
> | 2026-10-02 | **两处「表建好仍 500」缺陷修复（L2）**：`/userStatus/set` 是 `UserStatusMapper.xml` 的 `insert` 用裸属性 `#{userId}` 而 `BaseMapper.insert` 带 `@Param("bean")` → `BindingException`；`/emoji/list` 是 `EmojiController` 读 `request.getAttribute("userInfo")`（拦截器只校验、从不写入该 attribute）→ 恒 null → NPE，改为继承 `ABaseController` 用 `getTokenUserInfo(request)`。两者此前都被「表不存在」的 500 掩盖，migration-010 建表后才浮出。配套新增 `scripts/verify/verify_mapper_params.mjs`（Mapper `@Param` 与 XML 占位符一致性审计，全仓 27 个通过）；复测五个端点全 200 | 遗留项清理 |
> | 2026-10-03 | **位置消息(LOCATION 25) 与语音消息(VOICE 24) 首次接通 + 语音未播放红点（L4，四件套 `openspec/archive/2026-10-03-location-and-voice-message`）** —— 起因是 `verify_ws_frame_parity.mjs` 首次运行时报出这两个帧「既不落库也无 case」；追查后用只读探针 `probe_location_voice.py`（9/9）**实测取证**：两者五层全断（① `ChatController` 发送白名单只有 {2,5} → 直接 `CODE_1001` ② 落库白名单只有 {1,2,3,5} ③ `allowedFileTypes` 完全不含音频后缀、`.webm` 必被 `CODE_2604` ④ `wsClient.js` 无 case → 对端实时收不到（不崩不报错）⑤ `Chat.vue:136` 分发条件不含 → 即使历史拉到也不渲染，`ChatMessageVoice.vue` 是**从未被 import 的死组件**），且 **DB 里 `message_type` 只有 `1 2`、0 条 24/25 —— 即这两个功能从未被真正使用过**，这解释了为何既有 spec 验收与常规冒烟全都没发现。修复：发送/落库/搜索/撤回四处白名单加 24/25 + 6 条参数守卫（`duration∈[1,60]` 防伪造、`fileName` 非空、`fileType=3`、`extra_data` 须含 `location` 的合法 JSON ≤2000 字符，否则 `1001`）；`allowedFileTypes` 追加 `webm/m4a/wav/ogg`（连原 `mp3` 共 5 类，**刻意不含 `.amr`**，避免为兼容微信导出引入转码依赖，ADR-003）；前端 `wsClient case 24/25` + `Chat.vue` 分发 + 启用死组件 + `sendVoiceMessage` 改为「先发消息拿 messageId 再 uploadFile」（此前只发消息不上传 = 空壳）。**消息本体零 DDL**（复用既有 `duration`/`file_name`/`extra_data`）。**语音未播放红点**（用户追加，超出最小范围）：状态在**旁挂表 `chat_message_voice_read`**（migration-012，幂等双跑验证）——**不给 `chat_message` 加列**，因「谁播了」是 per-receiver 状态、加列会让 A 播放污染 B 看到的行（ADR-001）；新增 `markVoiceRead`/`loadVoiceRead` 两端点，**不新增 WS 帧**（红点是私有状态，对方不关心，ADR-002）；`markVoiceRead` 走 upsert 且**发送者本人也拒绝标记**（否则红点永远消失）。**⚠ 接线时暴露的第六层**：`file.js` 的 `FILE_TYPE_CONTENT_TYPE` 缺 `fileType=3` → content-type 拼成 `undefinedwebm` → 浏览器不解码、`<audio>` 静默无声；已补 `"3":"audio/"` 并把 `"2"` 修正为带 `/` 的前缀，另建门禁 `verify_file_type_content_type.mjs`。**过程中我自己写的变异脚本被抓出两次漏判**：① 锚点未覆盖新增的 24/25 → 报 FAIL（纪律生效）② 锚点只覆盖部分片段、残留 3 个枚举项 → 门禁仍 exit=0 → 报 MISSED —— 两次都靠「逐条看输出而非只看汇总」才发现。另补 `isVoiceReadReceiver` 群聊分支单测 3 例（上一轮 retro 记为立即项）。单测 200/200（新增 14），冒烟 `smoke_location_voice.py` 26/26，变异 9/9，**遗留 8 项需本机 GUI 验证（未谎报为通过）** | openspec 2026-10-03-location-and-voice-message |
> | 2026-10-02 | **朋友圈可见范围（用户级）+ 在线状态可见性 + 隐私设置统一页（L4，四件套 `openspec/archive/2026-10-02-privacy-moment-and-status`）** —— `user_info` 新增 4 列（`migration-011`，幂等：information_schema+PREPARE，**已在存量库实跑两次验证**）：`moment_visibility`（0公开 1仅好友 2仅自己 3白名单 4黑名单，语义与既有 `moment.visibility` 对齐）/ `moment_visible_list` / `moment_invisible_list`（JSON 数组字符串）/ `online_status_visible`（1展示 0隐藏）。**默认值刻意选「与现状一致」→ 存量用户行为不变**（0 与 PublishMoment 既有默认一致、1 与既有无条件广播一致），无需通知重新设置。新增 `POST /userInfo/updateMomentPrivacy`（空白名单/非好友入名单/非法 JSON/超长 60000 字符 → `1001`）与 `POST /userInfo/updateOnlineStatusVisible`；`UserInfoVO` 补 4 字段经 `BeanUtils.copyProperties` 自动带出。**新增 WS 帧 `ONLINE_STATUS_HIDDEN(27)`**：关闭时立即向在线好友推帧抹除其界面已有状态点（`broadcastOnlineStatus` 开头判 `online_status_visible` 为 0 则不广播），重开则立即广播当前状态；用新帧而非扩展 22 的 `extendData`（后者是裸 Integer，改对象会破坏旧客户端）。**用户级设置仅作发布默认值，`MomentServiceImpl#canView` 判定逻辑一行未改**（ADR-001，改设置不追溯历史动态，与微信一致）；名单解析抽出 `utils/IdListTools` 作为唯一真源，`parseList` 改为委托（逐字搬移，行为不变）。前端：统一「隐私」页 `views/setting/Privacy.vue`（四区块：加我的方式/朋友圈可见范围/在线状态可见性/黑名单，ADR-003 合并自 `/setting/userInfo` 与 `/setting/blacklist`，两旧路由保留 `redirect`）+ 新增通用纯选人组件 `components/ContactPicker.vue`（原 `UserSelect.vue` 是群成员专用、有提交副作用，**不可复用**已核实）+ `PublishMoment.vue` 可见范围补齐 5 项并继承用户级默认 + `wsClient.js` case 27 与 `Contact.vue` 抹除状态点。**过程中被活体冒烟抓出一个我自己漏的缺陷**：只改 PO/VO 未改 `UserInfoMapper.xml`，`updateByUserId` 的 SET 生成空串导致 `UPDATE user_info  where user_id=?` 语法错（全部正常路径 1002）——单测 mock 掉 Mapper 抓不到，只有真跑才现形。另有 PO 字段 Java 初始值导致「改一项串列重置另一项」，已移除（默认值只由 DDL 承担）。单测 173/173（新增 17），冒烟 `smoke_privacy.py` 51/51 | openspec 2026-10-02-privacy-moment-and-status |
> | 2026-10-02 | **加我方式可配置 + 黑名单可查可解（L3，四件套 `openspec/archive/2026-10-02-join-type-and-blacklist`）** —— 修两处「只能进不能出」的半成品设置：① `user_info.join_type` 此前 `applyAdd` 会读、`UserInfoVO` 会出、`UserInfo.vue` 会显示，但**无任何写入路径**（`UserUpdateDTO` 不含该字段），现新增 `POST /userInfo/updateJoinType`（只写 join_type 一列，Service 直读 DB 故保存即对新申请生效）；② 黑名单此前只能加不能列不能解（`UserDetail.vue` 点「加入黑名单」后**永久无法退出**），现新增 `POST /contact/loadBlackList`（只查 `status=BLACKLIST(4)`，「被拉黑」(5) 不入列、群组不入列、含昵称、按 last_update_time 倒序）与 `POST /contact/removeBlackList`（守卫 status 必须为 4 否则 `2401`；删双向行且反向仅当 `status=BLACKLIST_BE(5)` 才删，避免单方解除他人拉黑；双向清 Redis 缓存）。前端：`UserInfo.vue` 朋友权限改可编辑单选（乐观更新+失败回滚）、新增 `views/setting/Blacklist.vue` + 菜单与路由 `/setting/blacklist`、`UserDetail.vue` 加黑后提示解除入口。**顺带修复一个既有缺陷**（活体冒烟发现）：`removeUserContact` 拉黑走 `updateByUserIdAndContactId`，对「搜索到的陌生人」（无 user_contact 行）是 no-op → **拉黑静默失效**；已改为 `insertOrUpdate` upsert（`DEL` 分支保持 update 不变，并有回归护栏 `removeUserContact_delStillUsesUpdate`）。单测 156/156（新增 16），冒烟 `smoke_blacklist.py` 39/39。**2②-B 待办**：朋友圈可见范围 + 在线状态可见性（需 `user_info` 新增列 → L4），见 `openspec/specs/privacy-settings/spec.md` | openspec 2026-10-02-join-type-and-blacklist |
> | 2026-10-02 | **群入群审批闭环（L4，四件套 `openspec/archive/2026-10-02-group-join-approval`）** —— 修复**能真实绕过权限的洞**：`GroupQrCodeServiceImpl#joinByQrCode` / `GroupInviteServiceImpl#joinByInvite` 原先直接 `addContact` 入群，**完全不读 `group_info.join_type`**，群主设的「需管理员同意」形同虚设。两条路径改为委托 `UserContactApplyService#applyAdd(contactType=GROUP)` 复用既有审批链路；两个 join 端点出参 `Result<Void>`→`Result<Integer>`（joinType 0=已直接加入 1=已提交申请待审批）；`dealWithApply` 审批人按 contactType 分流（GROUP 走 `checkGroupRole(ADMIN)`，群主 role=0 天然满足，成员 2305 / 非成员 2304；**USER 仍强等 receive_user_id 未被放宽**）；`UserContactApplyQuery` 新增 `currentUserId` + `UserContactApplyMapper.xml#query_condition` 加审批可见性条件（`receive_user_id=我 OR (contact_type=1 AND contact_id IN 我 role∈(0,1) 的群)`），`loadApply` 与 `ChannelContextUtils` WS 申请红点**两处调用点同源**；同时移除 `queryContactInfo` 两个 LEFT JOIN 中冗余的 `a.receive_user_id` 守卫（两侧均 PK 1:1 无扇出，保留会让管理员分支恒不命中致 contactName 全 null）。前端新增 `components/GroupJoinDialog.vue`（二维码/邀请两个 tab）+ `Contact.vue` 搜索框旁入口 + `ContactApply.vue` 徽标改「入群申请」——此前两个 join 端点**零前端调用方**。活体冒烟 `scripts/smoke/smoke_group_join_approval.py` 48/48 PASS，单测 140/140（新增 20）。**新发现技术债**：`npm run lint` / `eslint .` **改动前即已失效**（报 No files matching，显式 glob 有 311 errors / 20537 warnings 存量），批次 1 加的 CI lint 步骤会恒红已移除 | openspec 2026-10-02-group-join-approval |
> | 2026-10-02 | **工程化骨架 + 运行时配置外置（L4）**：① 批次 1 骨架（L2）—— 新增 `README.md`、`LICENSE`(MIT)、`docker-compose.yml`、`easychat-java/Dockerfile`(多阶段/非 root)、`.github/workflows/ci.yml`(3 job：后端 test+package、前端 lint+build、8 个门禁)，并移除前端 3 个零引用死依赖 `fluent-ffmpeg`/`js-md5`/`less`（`ffmpeg` **仍在用**，`src/main/file.js` 裸调 `assets/ffmpeg.exe` 做视频封面与头像裁剪，二进制不入库 → 新克隆必缺且静默失败，已写入 README §4.1）。② 配置外置（L4，四件套 `openspec/archive/2026-10-02-config-externalization`）—— `application.properties` 拆为公共基线 + `application-dev` + `application-prod` 三段，敏感项全部 `${ENV_VAR:默认值}`，TURN 公共凭据移入 dev、prod 留空降级纯 STUN，DB 密码 prod 无默认可用值；新增 `.env.example`(入库) / `.env`(gitignore) 与门禁 `verify_no_hardcoded_secret.mjs`(19/19，已入 CI)。实跑验证：dev 正常起(5050/5051)且 admin 鉴权无回归、prod 无注入时按设计启动失败(`using password: NO`)、prod 注入口令后正常起。**新发现待办**：`easychat.project-folder` 是死键；`CallService.rooms` 内存单例导致后端多实例部署时通话路由失效 | openspec 2026-10-02-config-externalization + L2 骨架 |
> | 2026-10-03 | **密码变更后会话失效 + 邮箱验证码真实投递（L4，四件套 `openspec/archive/2026-10-03-password-session-and-mail`）** —— 起因是一次只读盘点，发现三处认证面缺陷构成可利用的账号接管链，且**全部无自动化覆盖**。① **改密 / 找回密码后旧 Token 仍有效**：`updatePassword` 与 `resetPasswordByEmail` 只写库 + 记 `operation_log`，既不删 Redis token 也不推 `FORCE_OFF_LINE`，而 `RedisComponet#cleanUserTokenByUserId`（能清多端全部 token）早已实现却**全仓零调用**，Token TTL 2 天 → 账号被盗后受害者改密码，攻击者照常在线；② **验证码只写日志**：全仓 `JavaMailSender`/`spring.mail` **零命中**，`sendEmailCode` 直接 `logger.info("…code={}")`，而前端 `Login.vue` 已暴露完整找回密码流程 → 用户永远收不到码，且**任何有日志读权限者可重置任意账号（含 admin）密码**；③ **未登录端点限流形同虚设**：`GlobalOperationAspect#checkRateLimit` 在 `token == null` 时直接 `return`，登录页无 token 头 → `login`/`register`/`sendEmailCode`/`resetPassword` 四个 `checkLogin=false` 端点的 `checkRateLimit=true` **从未生效**，唯一防护只剩图形验证码。修复：① 两条改密路径在**全部校验与写库之后**吊销全部端 Token + 复用既有 `forceOffLine` 推 7 帧（ADR-001：会话失效不进事务，避免「密码没改但 Token 被清」）；② 新增 `MailService`/`MailServiceImpl` + **`spring-boot-starter-mail`**（§8 L4 新增生产依赖，**不写版本号**由 parent `2.6.1` 管理），未配 SMTP 时 **fail-closed 抛 `CODE_1002`** 而非打日志（ADR-002），邮件主题为固定文案 + 验证码强制纯数字（防邮件头注入），任何失败路径的异常消息均不含验证码；③ 限流键由「无 token 直接 return」改为 `rate_limit:ip:{ip}` 降级（ADR-003，**刻意不用全体匿名共用一个空前缀键**，那等于把限流变成 DoS 放大器），新增 `resolveClientIp` 取 `X-Forwarded-For` 首段回退 `getRemoteAddr()`，阈值提为常量。**零接口契约变更、零表结构变更**（`check-api-contract --strict` 保持 0 漂移；不新增 `try_count` 列，ADR-005）。**纠正本文档两处事实失真**：上表原记「修改密码…服务端校验旧密码 MD5 匹配」实为 BCrypt+MD5 双验证，且原记「成功后关闭 WS 强制重登」**该行为此前从未实现**；原记「未配置邮件服务时验证码写日志」已作废。门禁：新增 `scripts/verify/verify_password_session.mjs`（35 项，接 pre-push + CI）+ 配套 `mutation_password_session.cjs`（**沙箱副本**实现，12/12 变异全被捕获且沙箱基线与真实基线一致）+ `verify_no_hardcoded_secret.mjs` 扩至 25 项（补 prod 邮件五键占位符且无默认可用值）；新增 `GlobalOperationAspectTest` 13 例（**全仓首次覆盖 L4 鉴权切面**）、`MailServiceTest` 13 例。**TDD 红灯 13 例中 5 红 + `UserInfoServiceImplTest` 56 例中 3 红**，并对「失败路径不吊销」这条守卫用例专门做变异证明其**非恒绿**（把它挪到方法开头即转红）。全量单测 232/232（基线 200）。**遗留 4 项已登记**（验证码 `try_count` 失败次数限制 / `operation_log.ip_address` 恒 null / `@所有人` 服务端鉴权 / `verify_schema_drift.mjs`） | openspec 2026-10-03-password-session-and-mail |
> | 2026-10-02 | **5 个滞留 Change 全部闭环，`openspec/changes/` 清空**：① `typing-online-status` 补 spec + 归档；② `password-bcrypt` 取消「批量迁移接口」任务（登录自动升级已覆盖）并归档；③ `favorite` 补前端（右键收藏 + `Favorite.vue` 列表页 + 设置页入口 + 路由）；④ `location-share` 补前端（工具栏位置弹窗 + `ChatMessageLocation.vue`，**不引地图 SDK**）；⑤ `group-qrcode-invite` 补前端（`GroupDetail.vue` 两弹窗 + `qrcode` 依赖）**并修后端三处 `getGroupIdByToken` 桩**（原 join 永远失败，现活体验证扫码/邀请加入均成功）。`check-api-contract.mjs` 孤路由 **7 → 0** | 遗留项清理 |
