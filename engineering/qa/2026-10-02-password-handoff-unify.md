# QA 报告 — 统一密码传递口径（含存量库 DDL 对齐）

- 日期: 2026-10-02
- 关联 Change: openspec/changes/2026-10-01-password-handoff-unify
- 证据文件: `2026-10-02-password-handoff-live.txt`（同目录，终端输出快照）

## 范围

| 端 | 改动 |
|----|------|
| 前端渲染层 | `views/Login.vue` 登录分支去 `md5()`、删 `js-md5` import |
| 后端 | 无代码改动（BCrypt + MD5 双验证 + 自动升级本已完备） |
| 单测 | `UserInfoServiceImplTest` 新增 4 例（BCrypt 登录 / 注册后可登录 / MD5 自动升级 / 拒收 MD5 摘要） |
| 机控 | 新增 `scripts/verify/verify_password_handoff.mjs`（21 项断言，0 依赖） |
| 脚本 | `scripts/smoke/` 8 个脚本登录处改发明文 |
| 数据库 | `easychat-migration-010-password-and-im-tables.sql`：`password` 32→60 + 补建 4 表 |

## 验收口径

1. 单测：BCrypt 路径有覆盖且全绿；`mvn test` 0 failure。
2. 机控：`verify_password_handoff.mjs` 21/21 通过。
3. 活体（真连 MySQL + Redis + 后端）：
   - 存量 `md5(明文)` 账号以明文登录成功，且密码自动升级为 BCrypt；
   - 升级后再次登录成功；
   - 发送 `md5(明文)` 被拒且非 500；
   - 改密 → 新密码登录 → 改回原值，全程通。
4. 前端：`npx eslint` 0 error、`npm run build` 通过。
5. 存量库：迁移后 `password` 列宽 ≥60、四表存在，相关接口由 500 转 200。

## 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `node scripts/verify/verify_password_handoff.mjs`（修复前） | **7/21 通过，14 项 FAIL**（红） |
| `node scripts/verify/verify_password_handoff.mjs`（修复后） | **21/21 通过，0 FAIL** |
| `mvn -B -o test -Dtest=UserInfoServiceImplTest` | Tests run: **29**, Failures: 0（原 25 + 新增 4） |
| `mvn -B -o test`（全量） | Tests run: **120**, Failures: 0（原 116） |
| `python -m py_compile` × 8 冒烟脚本 | 8/8 通过 |
| `npx eslint src/main/index.js` | **0 error**（385 prettier warning 为历史 CRLF/格式债，非本变更引入） |
| `npm run build`（electron-vite） | built in 18.97s |
| `node scripts/check-openspec-hygiene.mjs` | 通过 |
| `node scripts/check-ipc-registration.mjs --strict` | 通过（39/39 通道已注册） |
| `node scripts/check-api-contract.mjs` | 112 后端路由 / 103 前端调用 / **7 个孤路由**（既有遗留，不在本变更范围） |

### 活体证据（关键）

迁移前：

```
password                          varchar(32)     ← BCrypt 写不进去
（emoji / favorite / user_status / operation_log 四表不存在）
```

迁移后：

```
password column: varchar(60)
table: emoji / favorite / operation_log / user_status
```

登录链路（明文 `Test@123456`）：

```
1. 存量 MD5 账号明文登录   → http=200 code=0 success，token acquired: True
2. 再次登录（已是 BCrypt）  → http=200 code=0 success
3. md5(明文) 登录          → http=400 code=1001「账号或者密码错误」（非 500）
```

自动升级实证：

```
karina7710@test.com | len=60 | $2a$10$      ← 登录前是 len=32 的 md5
test@qq.com         | len=32 | aff8c7c      ← 未登录，仍是 MD5（保留为老账号样本）
```

改密往返：

```
login: 200 0
updatePassword -> Test@1234567 : 200 0 success
login with new pwd: 200 0
restore pwd -> Test@123456 : 200 0 success
login with restored pwd: 200 0
```

迁移顺带修复的效果：

```
/favorite/list  500 → 200
/userStatus/get 500 → 200
```

## 过程中发现的问题（重要）

### 1. 根因是 DDL 只改基线、没写迁移（已在本变更修掉）

首次活体登录返回 **HTTP 500 / CODE_1002**，堆栈根因：

```
Caused by: com.mysql.cj.jdbc.exceptions.MysqlDataTruncation:
  Data truncation: Data too long for column 'password' at row 1
```

即 `password-bcrypt`（2026-09-30）把列宽改成 60 只改了 `easychat.sql` 基线，**既无迁移脚本也未在存量库执行**。结果是：BCrypt 改造上线后，存量库走「MD5 → BCrypt 自动升级」必然 500。这说明该变更的 QA 结论「后端测试全部通过，收藏功能正常」严重失真（见下）。

### 2. 「注册后无法登录」的缺陷早于 BCrypt 改造

核查 `e0b139f~1` 版本发现：改造前 `register` 存的是 `md5(md5(明文))`（客户端先 md5、服务端再 md5），而当时 `login` 是直接比对收到的值。也就是说**旧版注册页创建的账号当年就登不上**，并非本次回归。本机两个账号存的是 `md5(明文)` 单哈希，属可自动升级的正常存量；详见 design ADR-006。

## 未运行项

- **Electron GUI 端到端**（登录页实际点击 → 进主界面）：沙箱无 GUI，未覆盖。代码层证据为 `Login.vue` 已发明文 + build 通过 + 守卫断言通过。
- **全环境迁移对账**：只验证了本机 `easychat` 库，其他环境是否存在同类漂移未知，列为遗留。
- **HTTPS 传输保护**：不在本变更范围（proposal 待确认要点 1 已说明）。

## 结论

**通过**。登录/注册/改密/找回四条链路口径已统一，服务端零改动；单测、机控守卫、活体链路三层证据齐备；存量库结构已对齐。`mvn test` 120 例全绿，`verify_password_handoff.mjs` 21/21。

遗留：`/userStatus/set`、`/emoji/list` 曾仍 500（MyBatis 参数前缀缺陷，属已归档变更），**已于同日修复并复测通过**，记录见 `engineering/qa/2026-10-02-mapper-and-emoji-500-fix.md`。
