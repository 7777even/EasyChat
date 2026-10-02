# Tasks — 加我方式可配置 + 黑名单可查可解

- 关联 Design: 2026-10-02-join-type-and-blacklist/design.md
- 创建日期: 2026-10-02
- 预估总工时: 9h

> 任务按实施顺序排列；单条 ≤2h。
> **[TDD]** 标记的任务必须先写失败测试再实现。

## 阶段一：单测先行（红）

- [x] 1.1 **[TDD]** `UserInfoServiceImplTest` 新增 `updateJoinType_toZero` / `_toOne` / `_onlyWritesJoinTypeColumn`（护栏：不得覆盖 nickName/password/status/sex）/ `_illegalValue_rejected`（2/-1/99 → CODE_1001 且不落库）/ `_nullValue_rejected` / `_userNotFound`（CODE_2101），共 6 例 — ≤1h
- [x] 1.2 **[TDD]** 护栏：断言传入的 `UserInfo#userId` 恒被 Service 覆写为当前登录用户（防越权） — ≤30min
- [x] 1.3 **[TDD]** 新增 `UserContactBlacklistTest#loadBlackList_queryScopeIsExact`：断言 `userId=我`、`contactType=USER`、`statusArray` 只含 `BLACKLIST(4)` 不含 `BLACKLIST_BE(5)`、`queryContactUserInfo=true`、`orderBy=last_update_time desc`；`_emptyListIsNotAnError`；`_returnsRows`（含昵称） — ≤1h
- [x] 1.4 **[TDD]** 补「群组不入黑名单」断言（`contactType=USER` 约束） — ≤30min
- [x] 1.5 **[TDD]** 新增 `removeBlackList_*`：`_removesBothDirectionsAndClearsCache`、`_cannotRemoveOthersBlacklistOnMe`（**安全红线**）、`_targetNotRelated`、`_keepsOtherDirectionWhenMutuallyBlacklisted`、`_doesNotTouchOtherRows`，共 5 例 — ≤1h
- [x] 1.6 **[TDD]** 补 `_idempotent`（重复解除第二次 2401） — ≤30min

## 阶段二：后端实施（转绿）

- [x] 2.1 `UserInfoService` / `Impl` 新增 `updateJoinType`：joinType 白名单校验（只接受 0/1，否则 CODE_1001）、`selectByUserId` 存在性（否则 CODE_2101）、**只构造 userId+joinType 两字段**的 `UserInfo` 调 `updateByUserId` — ≤1h
- [x] 2.2 `UserContactService` / `Impl` 新增 `loadBlackList`：`UserContactQuery`（`statusArray=[BLACKLIST]`、`contactType=USER`、`queryContactUserInfo=true`、`orderBy=last_update_time desc`）→ 既有 `findListByParam` — ≤1h
- [x] 2.3 `UserContactService` / `Impl` 新增 `removeBlackList`（`@Transactional`）：守卫 **行存在且 `status=BLACKLIST(4)`**（否则 CODE_2401）→ 删我行 → **仅当反向行 `status=BLACKLIST_BE(5)` 才删反向行** → 双向 `redisComponet.removeUserContact` — ≤1h
- [x] 2.4 Controller 新增 3 端点：`/userInfo/updateJoinType`（`@NotNull`，补 `javax.validation.constraints.NotNull` 导入）、`/contact/loadBlackList`、`/contact/removeBlackList`（`@NotEmpty`）；均 `@GlobalInterceptor` + `getTokenUserInfo`，**不接收 userId** — ≤1h
- [x] 2.5 **[TDD]** 变异检验：临时去掉 `removeBlackList` 的 `status` 守卫 → `removeBlackList_cannotRemoveOthersBlacklistOnMe` 转红（Tests run 9, Failures 1）→ 立即还原 → 9/9 绿（补 TDD 红阶段跳过的证据，见 QA §5） — ≤30min
- [x] 2.6 **[TDD]** `mvn -B test` **156/156**（140 → 156，新增 16，既有零回归）；`mvn -B clean package -DskipTests` 0 error；`check-api-contract --strict` exit 0；`verify_mapper_params` exit 0 — ≤1h

## 阶段三：前端

- [x] 3.1 `UserInfo.vue`「朋友权限」由只读文本改为 `el-radio-group`（直接加入 / 加我时需验证）；沿用同文件 `themeChange` 的「乐观更新 + 失败回滚」范式；`joinType` 为 null 时默认选中保守项并在 UI 提示；`getUserInfo` 回填时同步 `joinTypeBeforeSave` — ≤1h
- [x] 3.2 `Api.js` 补 3 个端点常量（`loadBlackList` / `removeBlackList` / `updateJoinType`） — ≤30min
- [x] 3.3 新增 `views/setting/Blacklist.vue`：`ContentPanel` + 列表（`Avatar` + 昵称 + `moment` 格式化拉黑时间 + 解除按钮）+ `utils/Confirm.js` 二次确认（签名仅 `{message, okfun, showCancelBtn, okText}`）+ 空态 + **加载失败保留旧列表** + 深色变量 — ≤1h
- [x] 3.4 `Setting.vue` 菜单加「黑名单」项（`icon-lock` 在 iconfont 中不存在，改用实际存在的 `icon-close` = close-bold e685，已在 design §6 预预警）+ `router/index.js` 新增 `/setting/blacklist`；`UserDetail.vue` 加黑成功后提示「可在设置 → 黑名单中解除」 — ≤1h
- [x] 3.5 `npm run build` 0 error（22.96s）；`npx eslint Blacklist.vue` **0 error**（首轮有 1 个我引入的 `vue/require-v-for-key`，已补 `:key`）；另 3 个改动文件 error 数与 git stash 基线一致（11 → 11）；`check-ipc-registration --strict` 不涉及（未新增 IPC 通道） — ≤1h

## 阶段四：活体验证与文档同步

- [x] 4.1 **[TDD]** 新增 `scripts/smoke/smoke_blacklist.py`（39 断言，自清理）并跑通：加我方式 0↔1 往返 + 非法值 1001 + **非法值未污染 DB** + **改完立即对新申请生效**（join_type=0 直接成好友 / =1 落申请单） — ≤2h
- [x] 4.2 活体冒烟：拉黑双向生效（4/5）、列表含昵称且不含「被拉黑」、只返回自己的黑名单、解除后**双向行均删除** + 可重新申请 + 重复解除 2401、**安全红线**（status=5 不可解除且双方行均未删）、**双向拉黑时反向行保留**、群组不入列、finally 还原 join_type — ≤1h
- [x] 4.3 **修复冒烟首跑暴露的既有缺陷**：`removeUserContact` 的 BLACKLIST 分支由 `updateByUserIdAndContactId`（对无关系行的陌生人是 no-op → **拉黑静默失效**）改为 `insertOrUpdate` upsert + 新增 `fillContactRow` 补齐主键/维度/role；`DEL` 分支保持 update 不变；**改写把缺陷固化的既有测试** `removeUserContact_blacklist` 并新增 `removeUserContact_delStillUsesUpdate` 护栏 — ≤1h
- [x] 4.4 同步 `docs/system-facts.md`（变更日志一行，含 join_type 更新/黑名单三端点/拉黑 upsert 缺陷修复/2②-B 待办）与 `easychat.sql` 核对（**确认无 DDL 变更**） — ≤30min
- [x] 4.5 同步 `engineering/qa/2026-10-02-join-type-and-blacklist.md` + 冒烟快照 `2026-10-02-join-type-and-blacklist-smoke.txt` — ≤30min

## 阶段五：收尾

- [x] 5.1 同步 `engineering/retro/2026-10-02-join-type-and-blacklist.md`（四段式，含「TDD 顺序没走对」的自省） — ≤30min
- [x] 5.2 落实 Retro「立即」项：`AGENTS.md` §2.1 补两条硬纪律（新增方法须先加 `UnsupportedOperationException` 桩跑红；`edit` 大文件后必须回读） — ≤30min
- [x] 5.3 spec-delta 回写 `openspec/specs/privacy-settings/spec.md`（新建 capability，遗留表登记 2②-B 待办）+ 归档 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/archive/2026-10-02-join-type-and-blacklist/tasks.md` 全部勾选
- [x] `mvn -B test` 全绿 **156/156**（既有 140 例零回归 + 新增 16 例）
- [x] `mvn -B clean package -DskipTests` 0 error
- [x] `check-api-contract --strict` / `verify_mapper_params` / `check-openspec-hygiene` 全 exit 0（契约门禁 0 孤儿路由）
- [x] **安全红线已验证**：无法解除「别人对我的拉黑」（`2401`），且双方关系行均未被删除 —— 单测 + 冒烟双重锁定，另经变异检验确认用例有判别力
- [x] 解除黑名单后双向行均被删除、缓存双向清理（冒烟 SQL 断言）
- [x] 3 个新增端点**均不接受 userId 入参**（防越权）
- [x] 拉黑陌生人不再静默失效（upsert）；`DEL` 分支未被波及（`removeUserContact_delStillUsesUpdate`）
- [x] 无 DDL 变更，`easychat.sql` 与迁移脚本无需改动（确认非遗漏）
- [x] 归档闭环完成（spec-delta 回写 `specs/privacy-settings/` + 移入 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`

> **未在本次交付内完成、需本机跟进的验证活动**（非实施任务，故不占勾选框；已登记于 `engineering/qa/2026-10-02-join-type-and-blacklist.md` §4「未运行 / 未覆盖项」）：
> 前端 GUI 交互（朋友权限切换与失败回滚、黑名单页列表/解除/空态/二次确认、菜单图标视觉）与截图证据。
> 按 AGENTS §7.1 不得谎报为通过，故在此显式声明。
