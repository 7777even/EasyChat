# AGENTS.md — AI 编码必读（Electron 前端）

面向 AI 助手 / 自动化编码的前端专属约束入口。**动手前先按 `../AGENTS.md` §1 判定改动等级（L0–L4）；L2 及以上在生成或修改 IPC / preload / 渲染代码前，必须读完本文 §3 既有项目约束，再动手。**

本库是 **EasyChat Electron 桌面客户端**（Vue 3 + Vite + Electron），与后端库 `easychat-java/` 同仓库双目录结构。

## 0. 仓库结构

```
easychat-front/
├─ AGENTS.md                       ← 本文件（前端权威）
├─ src/
│  ├─ main/                        ← Electron 主进程（Node.js 运行时）
│  │  ├─ index.js                  ← 应用入口、窗口创建、app lifecycle
│  │  ├─ ipc.js                    ← ipcMain.handle 注册中心
│  │  ├─ file.js                   ← 本地文件操作封装
│  │  ├─ store.js                  ← Electron Store 持久化
│  │  ├─ wsClient.js               ← WebSocket 客户端连接后端
│  │  ├─ windowProxy.js            ← 窗口代理、跨窗口通信
│  │  └─ db/                       ← SQLite 本地缓存（sql.js / better-sqlite3）
│  │     ├─ ADB.js                 ← 数据库连接管理
│  │     ├─ ChatMessageModel.js   ← 消息本地持久化
│  │     ├─ ChatSessionUserModel.js← 会话用户关联
│  │     ├─ Tables.js              ← 表结构定义
│  │     └─ UserSetting.js         ← 用户本地设置
│  ├─ preload/                     ← 预加载脚本（桥接层）
│  │  └─ index.js                  ← contextBridge.exposeInMainWorld
│  └─ renderer/                    ← Vue 3 渲染进程
│     └─ src/
│        ├─ App.vue                ← 根组件
│        ├─ main.js                ← Vue 应用入口、插件注册
│        ├─ assets/                ← 样式 / 图标 / 图片
│        ├─ components/            ← 通用组件
│        ├─ router/                ← 前端路由
│        ├─ stores/                ← Pinia 状态管理
│        ├─ utils/                 ← 工具函数（request.js / 校验 / 上传）
│        └─ views/                 ← 页面视图（login / admin / chat / contact / moment / setting）
├─ package.json
├─ electron.vite.config.js         ← Electron-Vite 构建配置
└─ electron-builder.yml            ← Electron Builder 打包配置
```

## 1. 跨库入口（与后端对齐，本仓库为同仓双目录）

与后端 `easychat-java` 同属 EasyChat 仓库，**跨目录协作**时以下约束适用：

- **契约真源**：后端 `com.easychat.entity.vo.*` 为接口出参唯一真源，前端 `utils/Api.js` 调用后端接口时以后端 VO 结构为准。
- **系统事实基线**：后端端口 / WS 协议 / Token 会话机制见根 `../AGENTS.md` §6。
- **规则优先级**：根 `../AGENTS.md` 全局规则 > 本文件前端专项规则；本规则只能加严不可放宽。

## 2. 三类目录职责（与根 §7 对齐）

| 目录           | 回答的问题           | 特征                                 |
| -------------- | -------------------- | ------------------------------------ |
| `docs/`        | 系统**现在**是什么样 | 长期共识，跨版本有效                 |
| `openspec/`    | 系统**将要**怎么变   | 唯一业务规格来源（与后端共享）       |
| `engineering/` | 这次**做得怎么样**   | 短期过程记录：计划、QA、复盘         |

前端局部落点：
- `openspec/` 和 `engineering/` 位于仓库根（与后端共享，见 `../AGENTS.md` §7）。
- 前端**不单独另建** `openspec-fe/` 等平行体系。

## 3. 既有项目约束

### 3.1 技术栈概述

Electron + Vue 3 (`<script setup>`) + Element Plus + Vite + Pinia + sql.js（本地缓存）。

- **主进程**：Node.js 上下文，可使用 `fs` / `path` / `better-sqlite3` / `electron-store`。
- **预加载脚本**：`contextIsolation: true` 的沙盒桥接层，**唯一**向渲染进程暴露主进程能力的通道。
- **渲染进程**：Web 上下文，**无** Node.js API 访问权限，通过 `window.ipcRenderer` 通道与主进程通信。

### 3.2 三层红线（任何改动都必须满足）

#### 主进程（`src/main/`）红线

- `ipcMain.handle/channel` 必须在 `ipc.js` 集中注册，禁止在分散模块中注册。
- 主进程不得直接操作 DOM / 调用渲染进程 webContents 发消息，应通过 `windowProxy.js` 窗口代理统一调度。
- 本地文件操作 `file.js` 必须限制在用户数据目录 `app.getPath('userData')` 内，禁止任意路径读写。
- 本地数据库操作必须经由 `db/` 下 Model 层，禁止在主进程业务代码中直接写 SQL。

#### 预加载脚本（`src/preload/`）红线

- **必须**通过 `contextBridge.exposeInMainWorld` 白名单式暴露，**禁止**暴露 `ipcRenderer` 全对象。
- 暴露的每个方法需有明确用途注释（供渲染进程开发者参考）。
- 禁止在 preload 中写业务逻辑，只做「接收参数 → 校验类型 → invoke 主进程 → 透传结果」。
- **禁止**在 preload 中暴露 `require` / `process` / `Buffer` / `child_process` 等 Node.js 能力。

#### 渲染进程（`src/renderer/`）红线

- **禁止**在渲染进程源码中直接使用 `window.require` / `window.ipcRenderer`（应通过 preload 暴露的封装调用）。
- **禁止**在组件中直接使用 `axios`，必须经 `src/renderer/src/utils/request.js` 统一封装。
- 响应拦截器统一处理三大分支：成功（code=0）、登录过期（code=2001 → 弹登录窗）、其他错误（Toast 提示）。
- UI 渲染禁止使用后端 VO 对象透传渲染，必须经前端适配层转换成视图模型。

### 3.3 安全红线

1. **上下文隔离**：`contextIsolation: true` + `nodeIntegration: false` 为默认配置，**禁止**为便捷关闭。
2. **远程内容**：禁用 `enableRemoteModule`；如加载外部 URL，必须校验白名单并在 `webPreferences` 中显式启用。
3. **本地数据**：敏感数据（Token、会话信息）**禁止**以明文写入 `localStorage` / 未加密 JSON 文件；优先使用 Electron 内置 `safeStorage` 或编译加密。
4. **Node.js 依赖**：前端 `package.json` 依赖变更（含 Electron / Vite 版本）属 **L3**，需人工确认。
5. **打包配置**：`electron-builder.yml` 改动（含签名、文件包含、asar 解包）属 **L4**。

### 3.4 HTTP 请求层（`src/renderer/src/utils/request.js`）

```javascript
// 使用方式：所有组件必须通过 request 实例，禁止直接调用 axios
import request from '@/utils/request';

// 响应结构对齐后端 Result<T>
// { code: 0, message: 'success', data: T }
// 拦截器已处理 code=2001 → 登出
```

- `baseURL` 通过环境变量 `VITE_API_BASE` 控制（生产 / 开发）。
- 文件上传走 `ChunkUpload` / `ChunkUploadApi` 分片封装，不重复造上传逻辑。

### 3.5 WebSocket 链路

渲染进程**不直连**后端 WebSocket（`ws://host:5051`），统一由 `src/main/wsClient.js` 在主进程维护 WS 连接，通过进程间通信将收到的消息推送给渲染进程。

- WS 连接生命周期（重连、心跳、断开兜底）在主进程管理。
- 渲染进程只订阅 `window.bridge.onWSError` / `onWSMessage` 等事件，不持有 WS 句柄。

### 3.6 组件与视图

- 视图命名：PascalCase，位于 `src/renderer/src/views/<domain>/` 下。
- 通用组件命名：PascalCase，位于 `src/renderer/src/components/` 下。
- 样式：统一使用 Element Plus 组件与 SCSS，主题覆盖走 `assets/cust-elementplus.scss`。

### 3.7 本地数据库（`src/main/db/`）

| 文件                | 职责                                   | 红线                               |
| ------------------- | -------------------------------------- | ---------------------------------- |
| `ADB.js`            | 数据库连接管理、事务封装               | 单例模式，禁止多处 new             |
| `ChatMessageModel.js` | 消息本地持久化、分页查询           | Model 层只写 SQL，不做业务判断     |
| `ChatSessionUserModel.js` | 会话-用户关联                   | 同上                               |
| `Tables.js`         | 表结构定义                             | 表结构变更必须同步 ALTER 迁移逻辑  |
| `UserSetting.js`    | 用户本地设置读写                       | 键名变更需评估存量兼容性           |

## 4. L3 / L4 治理对齐（与根 §7.1 共享 openspec/ 落点）

前端 L3 / L4 变更同样强制四件套（`openspec/changes/<name>/`），额外要求：

- 涉及主进程 / preload / 渲染三层联动变更时，`design.md` 必须附「三层交互时序图」。
- 涉及 UI 交互变化的 `spec-delta.md` 必须附「关键页面截图或原型描述」。
- 归档后 QA 报告除终端输出外，**证据必须包含页面截图**（UI 改动必附）。

## 5. L4 硬门禁清单（前端专项）

- **preload 暴露面**：`contextBridge.exposeInMainWorld` 新增或收窄暴露 API。
- **WS 链路**：主进程 `wsClient.js` 重连策略 / 心跳间隔 / 包络格式变更。
- **打包配置**：`electron-builder.yml` 签名、文件包含、asar 配置。
- **本地 db 迁移**：`Tables.js` 表结构变更、新增表。
- **主进程窗口生命周期**：窗口创建 / 关闭 / 多窗口通信机制变更。
- **生产依赖**：新增 Electron / Vite 插件或主进程 Node.js 依赖。

## 6. Review 结论三选一

与根 `../AGENTS.md` §9 对齐，另加前端维度评审项：

- 规格符合性
- 代码质量
- **三层边界合规**（是否越层访问：渲染直调 Node.js / preload 写业务 / 主进程直操 DOM）

## 7. 提交 scope（前端固定枚举）

与根 §6.3 对齐，前端专属 scope：

| scope      | 含义                                                |
| ---------- | --------------------------------------------------- |
| `main`     | Electron 主进程（src/main/）改动                     |
| `preload`  | 预加载脚本（src/preload/）改动                      |
| `renderer` | Vue 渲染进程（src/renderer/）改动                    |
| `ipc`      | ipcMain / ipcRenderer 通道注册或协议变更            |
| `build`    | electron.vite.config.js / electron-builder.yml      |
| `ws`       | WebSocket 客户端链路 / 消息分发                     |
| `db`       | 本地 SQLite db/ 模型层                              |
| `ui`       | 通用组件或样式（components/、assets/）               |
| `req`      | HTTP 请求层（utils/request.js / 分片上传）           |

仓库名（`frontend`）、架构层（`components / stores`）**不是 scope**；按改动所属域取名。
