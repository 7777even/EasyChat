# Tasks — 审计日志补记客户端 IP + @所有人 权限下沉服务端

- 关联 Design: `openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth/design.md`
- 创建日期: 2026-10-03
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。
> **TDD 红阶段纪律（根 AGENTS.md §2.1-3）**：新增方法先只落方法签名 + `throw new UnsupportedOperationException()` 桩，让测试**编译通过并跑红**，再填实现。

## 阶段零：门禁先红后绿

- [x] **T0.1** 新增 `scripts/verify/verify_audit_and_at_all.mjs`：静态断言
      ① `OperationLogServiceImpl` 不再把入参 ipAddress 直接透传（存在空值自动补齐分支）
      ② 6 处 `recordLog` 调用点不硬传 `null` 以外的错误语义（即保持 null 由实现补齐，且门禁覆盖新调用点）
      ③ `ChatMessageServiceImpl#saveMessage` 内存在 `isAtAll` 判定与 `checkGroupRole(..., ADMIN)` 调用
      ④ `GlobalOperationAspect` 不再自带私有 IP 解析（已委托 `IpTools`）
      在**未改造**代码上实跑，必须 exit=1 — ≤1h
- [x] **T0.2** 补 `scripts/verify/mutation_audit_and_at_all.cjs`（沙箱副本 + 11 条变异） — ≤40min
- [x] **T0.3** T0.1 接入 `ci.yml` gates 与 `pre-push` — ≤20min

## 阶段一：C1/C2 IP 取值与补齐

- [x] **T1.1** **[TDD]** 新增 `IpToolsTest`：XFF 首段 / XFF 多跳取首段 / 无 XFF 回退 remoteAddr / 两者皆空返回 `"-"` / **无请求上下文返回 `"-"` 且不抛异常** / XFF 为空串回退 remoteAddr。**先落桩跑红** — ≤1h
- [x] **T1.2** 新增 `utils/IpTools.getClientIp()`：全程 try-catch，无上下文返回 `"-"` — ≤40min
- [x] **T1.3** `GlobalOperationAspect`：删除私有 `resolveClientIp`，改委托 `IpTools.getClientIp()`；`GlobalOperationAspectTest` 13 例全量回归 — ≤40min
- [x] **T1.4** **[TDD]** `OperationLogServiceImplTest` 新增：ipAddress 为 null → 落库行带真实 IP；ipAddress 非空 → **不被覆盖**；无请求上下文 → 落 `"-"`；`IpTools` 抛异常 → 不影响主流程（不外抛）。先落桩跑红 — ≤1h
- [x] **T1.5** `OperationLogServiceImpl#recordLog` 实现空值补齐 — ≤30min
- [x] **T1.6** 变异检验：把补齐分支改回直接透传，确认 T1.4 用例转红 — ≤20min

## 阶段二：C3 @所有人 服务端鉴权

- [x] **T2.1** **[TDD]** 新增 `ExtraDataToolsTest`：`{"atAll":true}` → true；`{"atAll":false}` → false；`{"atAll":"1"}` → false（只认布尔）；缺字段 → false；**非法 JSON → false 不抛异常**；空串 / null → false；超长 → false；**嵌套 `{"a":{"atAll":true}}` → false**。先落桩跑红 — ≤1h
- [x] **T2.2** 新增 `utils/ExtraDataTools.isAtAll(String)`：fastjson 解析 + 顶层 `getBoolean` + 全程 try-catch — ≤40min
- [x] **T2.3** **[TDD]** `ChatMessageServiceImplTest` 新增 3 例：群聊 + atAll + 群主/管理员 → 放行；群聊 + atAll + 普通成员 → `CODE_2305`；**单聊 + atAll=true → 不做任何校验直接放行**（@所有人 只对群聊有意义）。先落桩跑红 — ≤1h
- [x] **T2.4** `ChatMessageServiceImpl#saveMessage` 群聊分支加鉴权（置于 `ROBOT_UID` 判断之内，与 `checkMuted` 同处） — ≤40min
- [x] **T2.5** 变异检验：① 把 `checkGroupRole` 调用删掉 ② 把 `isAtAll` 判断改反 ③ 把校验挪到 `ROBOT_UID` 判断之外，各确认对应用例转红 — ≤30min

## 阶段三：验证与同步

- [x] **T3.1** `mvn -B clean test` 全绿（基线 237 例 + 新增），记录例数 — ≤30min
- [x] **T3.2** `mvn -B package -DskipTests` 0 error；产物 `Spring-Boot-Version` / `Build-Jdk-Spec` 记入 QA — ≤20min
- [x] **T3.3** 活体冒烟 `scripts/smoke/smoke_audit_and_at_all.py`：① 触发一次登录失败 → `operation_log` 新行 `ip_address` 非 NULL 且等于 127.0.0.1 ② **群主发 atAll → 成功** ③ **普通成员发 atAll → 2305 且不发帧/不落库** ④ 普通成员发普通消息 → 成功（未被误伤）⑤ 单聊带 atAll → 成功（未误拦）⑥ 确认调用点未变（仍 6 处） — ≤2h
- [x] **T3.4** 变异脚本 `mutation_audit_and_at_all.cjs` 实跑有判别力 — ≤20min
- [x] **T3.5** 回归全绿：12 个门禁 + `verify_password_session` 变异 — ≤20min
- [x] **T3.6** 同步 `docs/system-facts.md` §12（审计日志 IP / @所有人 鉴权两条事实）+ §14 遗留表删除 #2、#3 + 变更日志一行；同步 `openspec/specs/at-all/spec.md` 的「已知边界」段（移除「服务端未实现」） — ≤40min

## 阶段四：收尾

- [x] **T4.1** `engineering/qa/2026-10-03-operation-log-ip-and-at-all-auth.md`（含冒烟输出证据文件） — ≤30min
- [x] **T4.2** `engineering/retro/2026-10-03-operation-log-ip-and-at-all-auth.md`（四段式） — ≤30min
- [x] **T4.3** spec-delta 回写：新增 `openspec/specs/operation-log/spec.md`；MODIFIED `openspec/specs/at-all/spec.md` — ≤40min
- [x] **T4.4** `git mv` 归档至 `openspec/archive/2026-10-03-operation-log-ip-and-at-all-auth`；按域拆提交（`common` / `chat` / `docs`） — ≤30min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| `X-Forwarded-For` 客户端可伪造（直连部署下可伪造 IP 与限流维度） | 引入可信代理白名单属部署配置变更，且会改变现有限流行为 | 独立 Change |
| 历史 `operation_log.ip_address` 为 NULL | 无数据可回填（过去从未采集） | 不可回填，属正常 |
| `at_user_ids` 未做服务端成员校验（可填任意 userId） | 与 @所有人 同类但危害低（只多一次红点提醒，无权限冒充） | 独立 Change |
| 前端零自动化测试（遗留 #7） | 需引入 vitest（L3） | 下一批 |
| 迁移执行自动化（遗留 #9） | 部署配置变更（L4） | 下一批 |

## DoD 自检（完成后逐项确认）

- [x] `tasks.md` 全部勾选
- [x] `mvn clean test` 全绿、`mvn package -DskipTests` 0 error
- [x] 零接口契约变更、零 DDL；`check-api-contract --strict` 0 漂移
- [x] 门禁先红后绿 + 变异检验有判别力（脚本自身不漏判）
- [x] `docs/system-facts.md` §12 新增事实、§14 删除 #2/#3、变更日志已追加
- [x] `openspec/specs/at-all/spec.md` 的「服务端未实现」边界已移除
- [x] 证据已落 `engineering/qa/`，未运行项如实标注
- [x] 归档闭环完成（spec-delta 回写 + `git mv` 到 `archive/`）
