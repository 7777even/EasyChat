# EasyChat

> 实时聊天系统全栈项目：Spring Boot + Netty(WebSocket) + MySQL + Redis 后端，Electron + Vue 3 桌面客户端。
> 目标形态为**功能对标微信桌面版**的 IM（不含视频号/小程序/支付/公众号等生态，不做多端形态）。

## 1. 技术栈

| 层 | 选型 | 版本 |
|----|------|------|
| 后端框架 | Spring Boot | 2.6.1 |
| 后端语言 | Java | 1.8 |
| 持久层 | MyBatis（XML 映射） | 1.3.2 |
| 数据库 | MySQL | 8.0.23 |
| 缓存/会话 | Redis + Redisson | 3.12.3 |
| 长连接 | Netty（自研 WebSocket） | 4.1.50.Final |
| JSON | fastjson | 1.2.66 |
| 密码 | BCrypt（`spring-security-crypto`） | 随 Boot |
| 前端框架 | Vue 3 `<script setup>` | 3.3.4 |
| 桌面壳 | Electron + electron-vite | 25.6.0 / 1.0.27 |
| UI 库 | Element Plus | 2.4.3 |
| 状态管理 | Pinia | 2.1.7 |
| 本地缓存 | SQLite（`sqlite3`，主进程） | 5.1.6 |
| 打包 | electron-builder | 24.6.3 |

## 2. 端口与目录约定

| 组件 | 端口/路径 | 说明 |
|------|-----------|------|
| 后端 HTTP | `5050` | context-path = `/api` |
| WebSocket | `5051` | Netty 自研，心跳 + 消息分发 |
| 前端 dev server | `5000` | 代理 `/api` → `localhost:5050` |
| 后端运行目录 | `application.properties` 的 `project.folder` | 文件落盘根目录，**改这个不是改代码** |
| 前端本地数据 | Electron `app.getPath('userData')` | SQLite + 配置文件 |

## 3. 仓库结构

```
EasyChat/
├─ easychat-java/          # 后端（Spring Boot）
├─ easychat-front/         # 桌面客户端（Electron）
│  ├─ src/main/            #   主进程：WS 客户端、IPC 注册中心、本地 SQLite、文件服务
│  ├─ src/preload/         #   预加载桥接（contextBridge 白名单暴露）
│  └─ src/renderer/src/    #   渲染进程（Vue 3）
├─ openspec/               # L3/L4 变更规格（四件套 + archive + specs）
├─ engineering/            # 过程记录（plans / qa / retro）
├─ docs/system-facts.md    # 系统事实基线（人维护，改动须回写）
├─ scripts/                # 门禁 + 冒烟 + 校验脚本
├─ templates/              # 文档模板
├─ easychat.sql            # 数据库最新基线
└─ easychat-migration-*.sql # 存量库迁移脚本（幂等，按编号顺序）
```

## 4. 快速开始

### 4.1 准备 ffmpeg 二进制（**必做，否则视频封面/头像裁剪静默失败**）

`easychat-front/src/main/file.js` 用 `child_process` 裸调 ffmpeg/ffprobe 生成视频封面与头像缩略图，二进制不入库（体积大），需自行下载放到：

```
easychat-front/assets/ffmpeg.exe
easychat-front/assets/ffprobe.exe
```

从 [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases) 下载 `ffmpeg-master-latest-win64-gpl.zip`，把 `bin/` 下两个 exe 拷进去即可。缺失时 `file.js` 的 exec 会失败，表现为**视频消息无封面、头像取不到**，不抛错。

### 4.2 数据库

```bash
# 1) 建库并导入最新基线（首次）
mysql -uroot -p -e "CREATE DATABASE easychat DEFAULT CHARSET utf8mb4;"
mysql -uroot -p easychat < easychat.sql

# 2) 存量库升级：按编号顺序执行未跑过的迁移脚本
mysql -uroot -p easychat < easychat-migration-001-group-management.sql
# ...直到 010
```

> **迁移脚本是幂等的**（建表用 `IF NOT EXISTS`、列宽放宽向后兼容），重复执行安全。
> 改了 `easychat.sql` 基线**必须**同批产出 `easychat-migration-<NNN>-*.sql` 并在目标库执行——只改基线是最隐蔽的故障源。
> migration-003 是**有意留空**的占位说明文件（原 message-read-status 随已读回执下线被删除），不要复用该编号。

### 4.3 后端

```bash
cd easychat-java
mvn spring-boot:run
# 或打包
mvn package -DskipTests
java -jar target/easychat-1.0.jar
```

依赖 MySQL(`3306/easychat`) + Redis(`6379`) 已就绪。

### 4.5 运行时配置（三段 profile + .env）

配置分层与敏感项策略：

| 文件 | 角色 |
|------|------|
| `application.properties` | 公共基线，跨环境一致；`spring.profiles.active=dev`；敏感项一律 `${ENV_VAR:默认值}` 占位，**无裸值** |
| `application-dev.properties` | 本地开发值（含公共 TURN 联调凭据、DB 口令、测试管理员邮箱） |
| `application-prod.properties` | 生产模板（入库）；**DB 密码无默认可用值**，TURN 留空降级纯 STUN |
| `.env.example` | 凭据模板（入库，仅占位值） |
| `.env` | 真实凭据载体，**不入库** |

```bash
# 本地开发：零配置，默认 dev profile
mvn spring-boot:run

# 生产 / 容器
cp .env.example .env      # 填入真实值
docker compose --env-file .env up -d     # .env 中需含 SPRING_PROFILES_ACTIVE=prod
```

> ⚠️ **Spring Boot 2.6 不会自动读 `.env`**（自动导入是 3.0+ 的 `spring.config.import` 特性）。用 `docker compose --env-file .env` 或 `set -a; . ./.env; set +a` 注入。
> 加载优先级：`application.properties` → `application-${profile}.properties` → 系统环境变量。
> 纪律由门禁 `node scripts/verify/verify_no_hardcoded_secret.mjs` 强制（已入 CI）。

### 4.4 前端

```bash
cd easychat-front
npm install
npm run dev        # 开发（electron-vite dev，HMR）
npm run build:win  # Windows 打包（NSIS）
```

首次启动需在客户端内配置 API / WS 域名（Electron Store 的 `devApiDomain` / `devWsDomain`）。

## 5. 验证命令矩阵

改什么 → 至少跑什么：

| 改动范围 | 必跑 |
|----------|------|
| 文档 / 注释 | 检查格式与一致性 |
| Controller / Service / DTO | `mvn compile` |
| Entity / Mapper / SQL | `mvn compile` + 启动验证 |
| pom / 依赖 / 构建配置 | `mvn package -DskipTests` |
| 对外接口增删改 | 接口联冒烟 |

自动化门禁（CI 与本地同款）：

```bash
node scripts/check-api-contract.mjs --strict   # 前后端路由漂移
node scripts/check-ipc-registration.mjs --strict # IPC 通道漏注册
node scripts/check-openspec-hygiene.mjs         # 四件套/归档闭环
node scripts/verify/verify_mapper_params.mjs   # Mapper @Param 与 XML 占位符一致性
node scripts/verify/verify_password_handoff.mjs # 密码明文交接红线
node scripts/verify/verify_no_hardcoded_secret.mjs # 运行时配置分层与硬编码凭据
```

Git hooks：`node scripts/setup-git-hooks.mjs`（commit-msg 格式、pre-commit 黑名单、pre-push 规格卫生）。
CI 流水线见 `.github/workflows/ci.yml`，`main` 推送与 PR 自动执行上述全部门禁。

## 6. 协作规范

本仓库对 AI 助手与人类协作者有强制约定，改代码前必读：

| 文件 | 内容 |
|------|------|
| `AGENTS.md` | 分级工作流（L0–L4）、接口契约、错误码分段、安全红线、OpenSpec 闭环 |
| `easychat-front/AGENTS.md` | 前端三层红线（主进程 / preload / 渲染） |
| `docs/system-facts.md` | 系统事实基线，改动导致事实变化必须回写 |
| `CONTRIBUTING.md` | 提交规范 |

改动分级决定流程重量：**L1/L2** 直接改 + 最小验证；**L3/L4**（改业务能力、契约、权限语义、数据库结构、WebSocket 协议、生产配置、依赖升级）必须先走 `openspec/changes/<name>/` 四件套并取得人工确认。

提交信息单行，格式 `type(scope): 描述`，scope 枚举见 `AGENTS.md` §6.3。

## 7. 已知环境依赖与坑

- **ffmpeg 二进制不入库**（见 4.1），缺失是静默失败。
- **后端不可多实例**：`CallService` 的通话房间注册表与 `ChannelContextUtils.USER_CONTEXT_MAP` 都是进程内 `ConcurrentHashMap`，`docker compose up --scale backend=2` 会让通话建立失败（消息广播走 RTopic 不受影响）。详见 `docs/system-facts.md` §1.1。
- **TURN 配置**：`application.properties` 的 `easychat.turn.*` 目前是公共测试服务器（`guest/guess`），生产必须自建 coturn 并轮换凭据；留空则仅 STUN，对称 NAT 不通。
- **WS 客户端在主进程**：渲染进程不直连 WS，统一由 `src/main/wsClient.js` 维护；新增 IPC 通道必须在 `ipc.js` 定义**且**在 `index.js` 调一次，漏注册构建无感、功能静默失效（已有门禁拦截）。
- **本地 SQLite 字段是 snake_case**（`message_id` / `send_user_nick_name`），渲染层是 camelCase，主进程读库勿混用。

## 8. License

[MIT](./LICENSE)
