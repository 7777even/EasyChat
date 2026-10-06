# Proposal — 消除 Electron 打包配置双份（删除永不生效的 electron-builder.yml）

- 创建日期: 2026-10-06
- 效率等级: L4

## Why

仓库里存在**两份互相冲突的打包配置**：`electron-builder.yml` 与 `package.json` 的 `build` 字段。
经实跑与源码双重核实，**`electron-builder.yml` 从项目首个 commit（`a102bd9`）起就从未生效过**——
它表达的每一项意图都是空文，而其中一项已造成实际损失：

- `npmRebuild: false` 未生效 → 每次打包从源码重编 `sqlite3` + `@parcel/watcher`，
  **本机实跑 1800 秒超时未完成**，安装包根本产不出来。
- `files` 白名单未生效 → `src/`（含 `__tests__/`）、`.eslintrc.cjs`、`vitest.config.mjs`、
  `.npmrc` 等开发文件全部打进 `app.asar`；`assets/`（188 MB 的 ffmpeg/ffprobe）
  与 `extraResources` **重复打包**，是 `app.asar` 膨胀到 274.7 MB 的主因。

更危险的不是配置错，而是**它看起来是活的**：下一个人会照着改 `electron-builder.yml`，
改完发现毫无效果，却不会想到去查为什么。本仓已在同类「文档/配置说一套、实际跑另一套」上
连续栽过（`orderBy` 门禁、`lint --fix`、变异脚本空转）。

## What Changes

- 后端: 无
- 前端:
  - **删除** `easychat-front/electron-builder.yml`（死配置，删除后不可逆）
  - **修改** `easychat-front/package.json` 的 `build` 字段，新增两项：
    - `npmRebuild: false`（经生产依赖闭包核实安全，见 design.md §5）
    - `files` 白名单（排除源码、测试、开发配置、与 `extraResources` 重复的 `assets/`）
  - **新增** `scripts/verify/verify_packaging_config.mjs`，把「不得有第二份打包配置」
    「`npmRebuild` 必须显式声明」「`files` 必须排除开发文件」变成机控断言
  - **不修** `asarmor.js`（另一份未接线的死文件，见 Impact）
- 数据库: 无

## Capabilities

- C1: 打包配置唯一真源 —— 仓库中**有且仅有一份**生效的 Electron 打包配置，
      且任何「第二份配置」的存在都会被门禁判 FAIL
- C2: 打包不重编原生依赖 —— `npmRebuild` 必须显式声明，且本项目的声明值经依赖闭包核实为安全
- C3: 产物不含开发文件 —— `app.asar` 内不得出现源码、测试、开发配置，
      且与 `extraResources` 重复的资源只保留一份

## Impact

- 对外接口: 无
- 存量数据: 无
- 性能 / 安全:
  - 性能：**安装包体积显著下降**（`app.asar` 274.7 MB → 预期显著小于此，交付时贴实测值）。
    不再往客户端下发测试代码、lint 配置与 `.npmrc`。
  - 安全：当前 `.env*` 文件**不存在**，故本次不构成凭据泄露的修复（不得对外宣称修复了泄露）；
    但白名单化后 `.env*` 将被硬排除，未来误建也不会被打进包。
- 回退方案: `git revert` 该提交即可恢复（删除的文件随 revert 回来）。
  **但注意**：恢复后打包会重新变回 30 分钟超时状态。

---

## ☐ 人工确认关卡

> 本提案经 **用户** 于 **2026-10-06** 确认，允许进入 design 阶段。
>
> 确认方式：在方案 A / B 的二选一中选定 **A（删除 `electron-builder.yml`，
> 将有效意图并入 `package.json.build`）**，并明确排除方案 B（仅补 `npmRebuild`、
> 保留死配置）。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估