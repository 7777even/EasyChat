# Tasks — 统一密码传递口径

- 关联 Design: 2026-10-01-password-handoff-unify/design.md
- 创建日期: 2026-10-01
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：先写失败测试

- [x] **[TDD]** `UserInfoServiceImplTest` 新增 BCrypt 账号登录成功用例（存量密码为 BCrypt 哈希）— ≤30min
- [x] **[TDD]** 新增「注册后用同一明文登录成功」用例（BCrypt 全链路）— ≤30min
- [x] **[TDD]** 新增「MD5 老账号登录成功后密码被升级为 BCrypt」升级断言用例 — ≤30min
- [x] **[TDD]** 新增「BCrypt 账号用 MD5 摘要登录失败」用例（锁死本缺陷）— ≤30min
- [x] 运行 `mvn test` 确认上述用例存在覆盖缺口的事实被记录 — ≤15min
- [x] **[TDD]** 新增 `scripts/verify/verify_password_handoff.mjs` 契约守卫（前端发明文 / 服务端双验证 / 冒烟脚本发明文，共 21 项断言），先跑红 14 项失败 — ≤40min

## 阶段一·补：存量库 DDL 对齐（活体验证后追加，L4）

- [x] 记录迁移前结构证据：`password` = `varchar(32)`、`emoji`/`favorite`/`user_status`/`operation_log` 四表不存在 — ≤10min
- [x] 新建 `easychat-migration-010-password-and-im-tables.sql`：`password` 32→60 + 建四表（逐字对齐基线，幂等写法） — ≤40min
- [x] 在本机 `easychat` 库执行 migration-010 — ≤10min
- [x] 记录迁移后结构证据：`password` = `varchar(60)`、四表均存在；`/favorite/list`、`/userStatus/get` 由 500 转 200 — ≤10min

## 阶段二：实现口径统一

- [x] `views/Login.vue` 登录分支移除 `md5()` 包装 — ≤15min
- [x] `views/Login.vue` 删除 `js-md5` import — ≤10min
- [x] 全仓确认 `js-md5` 无残留引用（`git grep js-md5`，仅剩 `package.json`，按 ADR-003 保留） — ≤10min
- [x] 运行 `mvn test`，全部用例（含新增 4 例）0 失败 — ≤30min
- [x] 契约守卫 `verify_password_handoff.mjs` 转绿 21/21 — ≤10min

## 阶段三：连带修正冒烟脚本

- [x] `scripts/smoke/probe_admin.py` 登录密码改明文（并移除不再使用的 `hashlib` 导入） — ≤15min
- [x] `scripts/smoke/smoke_sensitive_word.py` 改明文 — ≤15min
- [x] `scripts/smoke/smoke_group_file.py` 改明文 — ≤15min
- [x] `scripts/smoke/smoke_admin_report.py` 改明文（直插 MD5 账号的 fixture 保持 MD5，用作老账号验证） — ≤20min
- [x] `scripts/smoke/smoke_admin_msg_delete.py` 改明文 — ≤15min
- [x] `scripts/smoke/smoke_chat_backup.py` 改明文 — ≤15min
- [x] `scripts/smoke/smoke_call_log.py` 改明文（3 处直插 fixture 保持 MD5） — ≤20min
- [x] `scripts/smoke/smoke_nudge.py` 无需改（本就发明文，守卫已确认） — ≤15min
- [x] 8 个脚本 `python -m py_compile` 全部通过 — ≤10min

## 阶段四：验证

- [x] 后端 `mvn -B -o test` 全绿（120 例，原 116 + 新增 4） — ≤30min
- [x] 前端 `npx eslint src/main/index.js src/renderer/src/views/Login.vue` 0 error + `npm run build` 通过 — ≤30min
- [x] 活体冒烟（本机 MySQL 3306 / Redis 6379 均在线，后端 5050 已起，**全部通过**）：
  - [x] 存量 `md5(明文)` 账号以明文登录成功（HTTP 200 / code 0），且库中密码升级为 `$2a$10$`、长度 60 — ≤20min
  - [x] 再次登录（此时库中已是 BCrypt）仍成功，证明 BCrypt 校验路径通 — ≤10min
  - [x] 发送 `md5(明文)` 登录被拒：HTTP 400 / code 1001「账号或者密码错误」（非 500） — ≤10min
  - [x] 改密 `Test@123456` → `Test@1234567` 成功，新密码登录成功，再改回原值成功，环境已还原 — ≤10min
- [x] 三门禁：`check-openspec-hygiene` 通过、`check-ipc-registration --strict` 通过、`verify_password_handoff.mjs` 21/21 — ≤15min
- [x] `check-api-contract.mjs` 复核：仍有 7 个孤路由（favorite×3 + 群码/邀请×4），属既有遗留，不在本变更范围 — ≤10min
- [x] 同步 `engineering/qa/` 验证报告（含实际执行命令、用例数、结构对照与登录证据） — ≤30min

## 阶段五：收尾

- [x] 同步 `docs/system-facts.md`（密码口径行、迁移/表清单、变更日志追加一行） — ≤20min
- [x] 订正 `engineering/qa/2026-09-30-password-bcrypt.md` 中「后端测试全部通过」的误导性结论 — ≤15min
- [x] 同步 `engineering/retro/` 复盘记录（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [x] spec-delta 回写 `openspec/specs/password-bcrypt/spec.md` — ≤20min
- [x] 归档 Change 到 `openspec/archive/2026-10-01-password-handoff-unify` — ≤15min

## 遗留（不在本变更范围，转后续清理）

- `UserStatusMapper.xml` 的 `insert` 用 `#{userId}` 而非 `#{bean.userId}` → `/userStatus/set` 500（`BindingException: Parameter 'userId' not found`），属已归档 `2026-09-30-user-status` 的缺陷。
- `EmojiMapper.xml` 的 `insert` / `selectList` / `selectCount` 同样缺参数前缀 → `/emoji/list`、`/emoji/upload` 500，属已归档 `2026-09-30-voice-emoji` 的缺陷。
- 「DDL 只改基线、不写迁移」的流程缺口在其他环境可能仍有残留，需一次全环境对账。
- 存量 `md5(md5(明文))` 账号（BCrypt 改造前经旧版注册页创建的）仍需用户自行用邮箱找回密码，见 ADR-006。

## DoD 自检（完成后逐项确认）

- [x] 本 `tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，`mvn test` 0 failure（120 例）、前端 `npm run build` 通过
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方（基线无需改；DDL 走 migration-010；8 个冒烟脚本已对齐明文口径）
- [x] 归档闭环完成（spec-delta 回写 `specs/` + `git mv` 到 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`（QA + 活体证据 txt + Retro）
