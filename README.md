# EasyChat

> 实时聊天系统全栈项目：Spring Boot + Netty(WebSocket) + MySQL + Redis 后端，Electron + Vue 3 桌面客户端。
> 目标形态为**功能对标微信桌面版**的 IM（不含视频号/小程序/支付/公众号等生态，不做多端形态）。

## 1. 技术栈

| 层 | 选型 | 版本 |
|----|------|------|
| 后端框架 | Spring Boot | 2.6.1 |
| 后端语言 | Java | 17 |
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
└─ easychat-migration-*.sql # 增量迁移脚本（由 Flyway 自动执行，见 §4.2）
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

# 2) 存量库升级：声明该库的真实版本号，其余交给 Flyway
SPRING_FLYWAY_BASELINE_VERSION=9 mvn spring-boot:run
#   声明 9 → Flyway 会自动执行 010~012，并从此往后自行记账
```

> **迁移由 Flyway 自动执行（2026-10-04 起）**：应用启动时检测并执行未应用的迁移，
> 用 `flyway_schema_history` 表记账。**不再需要人工逐个执行 SQL 文件。**
>
> **两条互斥的路，切勿混用**：`easychat.sql` 是**最新快照**，`easychat-migration-*.sql` 是**增量**。
> 在已导入快照的库上再跑 `001` 会直接报 `Error 1060 Duplicate column name`（实测）。
> 所以：全新环境只导快照；存量环境只走迁移，**不要再导快照**。
>
> **存量库首次纳管需要一次性人工声明**：`SPRING_FLYWAY_BASELINE_VERSION=N`
> （N = 该库真实跑到的编号）。Flyway 能往后记账，**不能回溯历史** ——
> 「这个库跑到第几号」仍需人知道一次。若你不知道 N，先跑
> `node scripts/verify/verify_schema_drift.mjs` 看结构与基线的差异。
>
> 结构若已与基线一致（全新库 / 刚导完快照），**什么都不用声明**，默认 12 即可。
>
> 改了 `easychat.sql` 基线**必须**同批产出 `easychat-migration-<NNN>-*.sql`
> ——只改基线是最隐蔽的故障源。门禁：`node scripts/verify/verify_schema_drift.mjs`。
>
> migration-003 是**有意留空**的占位说明文件（原 message-read-status 随已读回执下线被删除），不要复用该编号。
>
> ⚠️ **幂等性现状**：001 / 002 / 006 / 007 / 009 / 011 六份**不是幂等的**
> （MySQL 5.7 无 `ADD COLUMN IF NOT EXISTS`，且它们未加存在性守卫），
> 补齐前不要手工重跑这六份。详见 AGENTS §6.4-3。
>
> ⚠️ 迁移脚本不得含 `DELIMITER` / `CREATE PROCEDURE` —— 那是 mysql 客户端专有语法，
> Flyway 的解析器过不了。门禁：`node scripts/verify/verify_migration_flyway.mjs`。

### 4.3 后端

```bash
cd easychat-java
mvn spring-boot:run
# 或打包
mvn package -DskipTests
java -jar target/easychat-1.0.jar
```

依赖 **JDK 17** + MySQL(`3306/easychat`) + Redis(`6379`) 已就绪。

> ⚠️ JDK 必须为 17（与 `pom.xml` 的 `java.version`、CI 的 temurin 17、`Dockerfile` 的 temurin-17 三方一致）。
> 用 JDK 8 会编译失败；用其他版本则「本地全绿不代表 CI 全绿」——这正是 2026-10-03 之前长期存在的漂移。
> 后端运行需要给 JVM 加 `--add-opens java.base/java.lang,java.util,java.math=ALL-UNNAMED`（Redisson FST 在 JDK 17 强封装下需要），
> 该参数已配在 `pom.xml` 的 `spring-boot-maven-plugin.jvmArguments`；容器镜像则配在 `Dockerfile` 的 `JAVA_OPTS`。
> `mvn test` / `mvn package` 不经该插件，**本地跑测试时无需手动加**。

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
node scripts/check-api-contract.mjs --strict      # 前后端路由漂移
node scripts/check-ipc-registration.mjs --strict  # IPC 通道漏注册
node scripts/check-openspec-hygiene.mjs            # 四件套/归档闭环
node scripts/verify/verify_no_hardcoded_secret.mjs # 运行时配置分层与硬编码凭据
node scripts/verify/verify_mapper_params.mjs      # Mapper @Param 与 XML 占位符一致性
node scripts/verify/verify_password_handoff.mjs   # 密码明文交接红线
node scripts/verify/verify_virtual_core.mjs       # 虚拟列表核心算法
node scripts/verify/verify_call_core.mjs          # 通话核心逻辑
node scripts/verify/verify_ws_frame_parity.mjs    # WS 帧号两端对账
node scripts/verify/verify_file_type_content_type.mjs  # 文件类型与 content-type 一致性
node scripts/verify/verify_password_session.mjs   # 改密后会话失效 + 验证码投递契约
node scripts/verify/verify_schema_drift.mjs       # 基线 ⇄ 活库 表结构漂移（需 MySQL）
```

> 末条 `verify_schema_drift.mjs` 是**唯一需要活库**的门禁：连不上库即失败（fail-closed）。
> 连接参数可用 `SCHEMA_DB_HOST` / `SCHEMA_DB_PORT` / `SCHEMA_DB_USER` / `SCHEMA_DB_PASSWORD` /
> `SCHEMA_DB_NAME` 覆盖；离线场景显式加 `--no-live`。

以上全部为纯静态检查，无需启动服务。

Git hooks：`node scripts/setup-git-hooks.mjs`

| Hook | 触发 | 执行 |
|------|------|------|
| `commit-msg` | 提交时 | 格式 `type(scope): 描述`、type/scope 枚举、描述含中文、禁 body |
| `pre-commit` | 提交时 | 暂存区黑名单（`target/`、`dist/`、`node_modules/`、`*.log` 等） |
| `pre-push` | 推送时 | 契约 → IPC → 规格卫生 → 配置凭据 → WS 帧对账 → 文件 MIME → 密码会话 → 表结构漂移，任一失败即阻断 |

CI 流水线见 `.github/workflows/ci.yml`：`main` 推送与 PR 自动跑 4 个 job（后端 `mvn test` + `package`、前端 `build`、静态门禁、表结构对账）。

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

- **JDK 必须 17**：`pom.xml` / CI / Dockerfile 已三方对齐（2026-10-03 由 1.8 升级）。用别的版本编译出的产物与 CI 不等价。
- **ffmpeg 二进制不入库**（见 4.1），缺失是静默失败。
- **后端不可多实例**：`CallService` 的通话房间注册表与 `ChannelContextUtils.USER_CONTEXT_MAP` 都是进程内 `ConcurrentHashMap`，`docker compose up --scale backend=2` 会让通话建立失败（消息广播走 RTopic 不受影响）。详见 `docs/system-facts.md` §1.1。
- **TURN 配置**：`application.properties` 的 `easychat.turn.*` 目前是公共测试服务器（`guest/guess`），生产必须自建 coturn 并轮换凭据；留空则仅 STUN，对称 NAT 不通。
- **WS 客户端在主进程**：渲染进程不直连 WS，统一由 `src/main/wsClient.js` 维护；新增 IPC 通道必须在 `ipc.js` 定义**且**在 `index.js` 调一次，漏注册构建无感、功能静默失效（已有门禁拦截）。
- **本地 SQLite 字段是 snake_case**（`message_id` / `send_user_nick_name`），渲染层是 camelCase，主进程读库勿混用。

## 8. License

[MIT](./LICENSE)
