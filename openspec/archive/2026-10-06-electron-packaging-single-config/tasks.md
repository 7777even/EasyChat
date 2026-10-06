# Tasks — 消除 Electron 打包配置双份

- 关联 Design: 2026-10-06-electron-packaging-single-config/design.md
- 创建日期: 2026-10-06
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段零：反例验证（AGENTS §2.1 第 14 条，**先于写门禁**）

> 新增静态断言后，必须先造一条「它本该抓住」的反例并确认它报出来，再谈基线自检。
> 基线自检通过是必要不充分条件——它只证明「当前仓库不触发」，不证明这条断言扫得到目标。

- [x] 记录三条反例并**逐条确认门禁会报红**（在门禁写完后回填实测输出） — ≤30min
  - R1：`electron-builder.yml` 与 `package.json.build` 并存 ⇒ 须 FAIL
  - R2：删掉 `npmRebuild`（退回默认 `true`）⇒ 须 FAIL
  - R3：`files` 中去掉 `!src/**` ⇒ 须 FAIL
- [x] 三条反例**全部报红**才允许进入阶段一；任一条不报红即回到本阶段修断言 — ≤30min

## 阶段一：门禁（先红后绿）

- [x] **[TDD]** 新增 `scripts/verify/verify_packaging_config.mjs` — ≤1h
  - [x] 断言 1：打包配置**唯一真源** —— `electron-builder.yml` 与 `package.json` 的 `build`
        字段不得并存（并存即 yml 静默失效）
  - [x] 断言 2：`build.npmRebuild` 必须**显式声明**（未声明 = 默认 `true` = 每次重编原生依赖）
  - [x] 断言 3：`build.files` 必须排除 `src/**`、`.eslintrc.cjs`、`vitest.config.mjs`、
        `.npmrc`、`electron.vite.config.js`
  - [x] 断言 4：`build.files` 必须排除 `assets/**`（与 `extraResources` 重复）
  - [x] 断言 5：**解析器自检** —— 能真正读到 `build` 字段且 `files` 解析出 ≥5 条
        （防「解析器失配 → 零违规通过」，§2.1 第 14 条）
- [x] **[TDD]** 在**未修改的仓库现状**上跑该门禁 ⇒ **必须 FAIL**（红阶段；
      当前仓库 yml 存在、`npmRebuild` 与 `files` 均缺失，三条断言都应命中） — ≤30min
- [x] 阶段二完成后复跑 ⇒ 必须 PASS（绿阶段） — ≤10min

## 阶段二：配置收敛

- [x] 删除 `easychat-front/electron-builder.yml` — ≤5min
- [x] 在 `easychat-front/package.json` 的 `build` 中新增 `npmRebuild: false` — ≤5min
- [x] 在 `easychat-front/package.json` 的 `build` 中新增 `files` 排除白名单 — ≤15min
      （清单见 design.md ADR-004 / ADR-005；必须逐条对应「阶段一断言 3、4」）
- [x] 复跑门禁 ⇒ PASS — ≤10min

## 阶段三：活体验证（打包实跑）

> §2.1 第 1 条：交付前必须实跑并贴出退出码。
> 本机因未启用开发者模式无法解压 `winCodeSign`，故沿用已验证可行的参数组合。

- [x] `npx electron-vite build` exit 0 — ≤2min
- [x] `npx electron-builder --win -c.win.signAndEditExecutable=false`（**配置已内含 `npmRebuild: false`**，
      故不再需要 `-c.npmRebuild`）exit 0 — ≤5min
      - ⚠ 必须实测**耗时**：改造前为 1800s 超时，改造后应显著下降，这是本 Change 的核心验收指标
- [x] 断言产物存在且为真：`dist/EasyChatSetup.1.0.0.exe`（MZ 头）、`app.asar` — ≤5min
- [x] 断言**运行期必需文件未被误排**：`out/main/index.js`、`out/preload/index.js`、
      `out/renderer/index.html`、`dist/win-unpacked/resources/assets/ffmpeg.exe` — ≤5min
- [x] 断言**开发文件确已排除**：`app.asar` 内不含 `src/`、`__tests__`、`.eslintrc.cjs`、
      `vitest.config.mjs`、`.npmrc`、`assets/` — ≤10min
- [x] 贴出改造后 `app.asar` 与安装包体积，与基线（274.7 MB / 197.0 MB）对比 — ≤5min

## 阶段四：门禁接入与文档同步

- [x] 接入 `.github/workflows/ci.yml` 的 `gates` job — ≤10min
  （只接**静态门禁**；**刻意不接打包 job**，理由见 design.md §7）
- [x] 同步 `AGENTS.md` §10 门禁表 — ≤5min
- [x] 同步 `scripts/README.md` — ≤5min
- [x] 同步 `docs/system-facts.md`：记录「打包配置唯一真源 = `package.json.build`」这一事实，
      并登记 `asarmor.js` 未接线（**本 Change 不修，仅登记**） — ≤15min

## 阶段五：记录与归档

- [x] 同步 `engineering/qa/2026-10-06-electron-packaging-single-config.md`（含证据文件引用） — ≤20min
- [x] 同步 `engineering/retro/2026-10-06-electron-packaging-single-config.md` — ≤20min
- [x] spec-delta 回写 `openspec/specs/desktop-packaging/spec.md`（新建 capability） — ≤10min
- [x] `git mv openspec/changes/2026-10-06-electron-packaging-single-config openspec/archive/2026-10-06-electron-packaging-single-config` — ≤5min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-10-06-electron-packaging-single-config/tasks.md` 全部勾选
- [x] 阶段零的三条反例**逐条贴出报红输出**（不得只写「已验证」）
- [x] 按 AGENTS.md §2 矩阵：前端构建 / 打包实跑 exit 0，退出码已贴出
- [x] 阶段三全部断言为**实跑产出**，非静态推断
- [x] 门禁在接入 CI 前，已在当前 main 上跑通（§2.1 第 1 条）
- [x] 归档闭环完成（spec-delta 回写 `specs/desktop-packaging/` + `git mv` 到 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
- [x] **未越界**：`src/main` / `preload` / `renderer` 三层源码零改动；`asarmor.js` 未接线一事仅登记未修