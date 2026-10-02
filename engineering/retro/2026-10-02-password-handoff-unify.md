# Retro — 统一密码传递口径（2026-10-02）

- 关联 Change: openspec/changes/2026-10-01-password-handoff-unify
- 关联 QA: engineering/qa/2026-10-02-password-handoff-unify.md

## 做得好

1. **先写守卫再改代码，缺陷被当场锁死**。`scripts/verify/verify_password_handoff.mjs` 先跑出 14 项 FAIL，改完转 21/21，全程可复现；且守卫覆盖到「冒烟脚本也必须发明文」，避免修完主链路却让 7 个冒烟脚本集体失效。
2. **第一版守卫自己撒了谎，及时发现**。初版按 `/account/login` 切分脚本文本判断密码口径，而各脚本把 body 写在 login 调用之前，切分结果为空 → 8 个脚本全部「假绿」。改成「解析密码字段取值标识符 + 回溯其赋值」后才真实抓出 7 处违规。**假绿比没有测试更危险**，这条教训值得固化。
3. **活体验证是本次最大价值**。单测全绿、代码看着没问题，但真连库一打就 500，直接挖出「DDL 只改基线、没写迁移」这个单测和 mock 永远发现不了的流程缺口，并顺带暴露 4 张表在存量库根本不存在。
4. **诚实记录而非粉饰**。QA 里明确写出「未运行项：Electron GUI 端到端未覆盖」「全环境迁移对账未做」，以及发现旧版注册页缺陷早于 BCrypt 改造这一事实。

## 问题

1. **上一轮的 QA 结论失真，且被当作已交付依据**。`2026-09-30-password-bcrypt.md` 写着「后端测试全部通过，密码迁移正常」，但该变更：① 新增 0 个测试；② 唯一能验证登录的活体项被列为「未运行」；③ DDL 只改基线未迁移。三者叠加导致「登录必然 500」被签字放行。
2. **「全勾必归档」被架空**。`password-bcrypt` 等 5 个 Change 在 tasks 未勾完的情况下先归档、再移回，`tasks.md` 与真实进度脱节，失去了进度真源的作用。
3. **PowerShell 改文件毁编码**。批量替换冒烟脚本时用 `Get-Content -Raw | Set-Content -Encoding UTF8`，中文注释全部变 mojibake、diff 从 1 行膨胀到 400+ 行。已 `git checkout` 回滚并改用编辑工具重做。**Windows 下改含中文的文件不要用 PowerShell 文本管道。**
4. **迁移脚本执行方式踩坑**。`$sql | mysql.exe` 与 `cmd /c '""...\mysql.exe" ... < file'` 双双被 PowerShell 参数解析搞坏，最终用 `mysql -e "source <path>"` 才通——已把可用的执行方式与失败方式一并记进迁移脚本注释。
5. **本次改动带出两个既有缺陷**：`/emoji/list`、`/userStatus/set` 仍 500（MyBatis XML 参数前缀缺失）。它们不是本次引入的，但此前被「表不存在」的 500 掩盖着，迁移后才浮出水面。

## 原因

- 根因是**验证层级与风险不匹配**：认证属于 L4 域，风险最高的路径恰恰是验证最薄的一环（0 新增测试 + 活体未跑 + DDL 未对账）。
- 根因是**流程门禁只管形式不管事实**：`check-openspec-hygiene` 能查「四件套齐不齐」，查不出「tasks 全勾但 QA 结论是假的」；`check-api-contract` 能查孤路由，查不出表在存量库根本不存在。
- 根因是**「基线即真相」的误用**：改 `easychat.sql` 被当成「结构已对齐」，实际存量库与基线之间没有任何强制对账机制。

## 改进方案

| 优先级 | 方案 | 落点 |
|--------|------|------|
| 高 | 新增 `scripts/verify/verify_schema_drift.mjs`：比对 `easychat.sql` 与目标库 `information_schema`，缺表/列宽不符即非零退出 | 建议纳入 `scripts/verify/`，可挂 pre-push |
| 高 | L4 变更的 QA 模板增加「DDL 是否已对存量库执行」必填项，并要求贴 `SHOW COLUMNS` 前后对照 | `templates/_qa_template.md` |
| 高 | 「改了基线必须同批产出迁移脚本」写入 AGENTS.md §6.4 数据库规则 | `AGENTS.md` §6.4 |
| 中 | 为 MyBatis XML 参数前缀建立约定（单 POJO 用 `#{bean.x}` / `#{query.x}`，具名用 `@Param`），并加一条检查脚本扫描 `@Param` 与 XML 占位符一致性 | `scripts/verify/` |
| 中 | hygiene 门禁增加一条：归档 Change 的 QA 若「未运行项」含关键链路（登录/鉴权/消息收发），提示需补验证 | `scripts/check-openspec-hygiene.mjs` |
| 中 | 修完 `/emoji/list`、`/userStatus/set` 后，为每个新增后端端点补一条最小冒烟 | `scripts/smoke/` |
| 低 | 清理 `easychat-front/src/renderer/src/utils/ChunkUploadExample.js`（零引用死代码）与仓库根 4 个 `*.log` 产物 | 择机 |
| 低 | 全环境迁移对账（各环境 `SHOW CREATE TABLE` 与基线 diff），纳入发布检查清单 | `scripts/README.md` |

## 遗留 / 后续

- `/emoji/list`、`/userStatus/set` 的 MyBatis 参数前缀缺陷 → 已登记在 tasks.md「遗留」段。
- 存量 `md5(md5(明文))` 账号仍需用户自行邮箱找回密码（见 design ADR-006），本机两个账号不受影响。
- 本变更未删除 `js-md5` 依赖项（按 ADR-003 留待另开 L3 变更）。
