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

## 6. 数据库约束

| 项 | 值 |
|----|-----|
| 数据源 | MySQL `easychat` |
| 连接池 | HikariCP（min-idle 5 / max-pool 10 / conn-timeout 30s） |
| 文件上传上限 | `multipart max-file-size / max-request-size`：15MB |
| 图片白名单 | `.jpeg/.jpg/.png/.gif/.bmp/.webp` |
| 视频白名单 | `.mp4/.avi/.rmvb/.mkv/.mov` |
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

---

> **变更日志（事实变化时必须在此追加一行）**
>
> | 日期 | 变更说明 | 来源 |
> |------|----------|------|
> | 2026-09-22 | 初始建立，对齐 AGENTS.md 与现有代码实测 | 第三轮规范建设 |
