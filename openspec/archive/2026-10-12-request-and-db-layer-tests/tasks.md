# Tasks — 补齐 HTTP 请求层与主进程 db 层

- 关联 Design: 2026-10-12-request-and-db-layer-tests/design.md
- 创建日期: 2026-10-12
- 预估总工时: 6h（阶段 A 2h / 阶段 B 4h）

## 阶段一：四件套

- [x] 1.1 proposal / design / spec-delta / tasks + `.openspec.yaml` — ≤30min

## 阶段二：HTTP 请求层（阶段 A，**不改生产代码**）

- [x] **[TDD]** 2.1 建 `request-layer.spec.js` 骨架：mock `axios.create` 捕获两个拦截器 handler — ≤30min
- [x] **[TDD]** 2.2 `request()` 错误吞成 null / showError=false 不弹 / 成功返回 body — ≤30min
- [x] **[TDD]** 2.3 三分支：code=0 成功、2001 重登录（含 error 路径）、其他业务错误 — ≤30min
- [x] **[TDD]** 2.4 `rejectByBody`：非 2xx 带 body、无 body 网络异常、blob 错误体（JSON 与 HTML 两路）— ≤40min
- [x] 2.5 `errorCallback` 恰好调用一次 — ≤10min
- [x] 2.6 `npm run test` 全绿（81 spec / 767 例）— ≤20min

> **初版有两处自造假覆盖，已当场纠正**（§2.1 第 14 条）：
> ① T1 曾写一个 `runAndSwallow` 辅助函数**复刻**了 `request()` 的 catch 逻辑，
>    等于测的是测试自己。改为让 mock 的 `instance.post` 可控 reject，
>    直接走 `request()` 自身的 `.catch`。
> ② `code=2001` 那条没接住返回的 rejected promise → 变成 unhandled rejection，
>    在**后续**用例里以「Serialized Error」炸出来，伪装成别人的失败。

## 阶段三：db 层纯核心（阶段 B）

- [x] **[TDD]** 3.1 建 `dbModelCore.mjs` + 门禁 `verify_db_model_core.mjs` 骨架，
      先写红（核心尚不存在，Model 尚有内联 SQL）— ≤30min
- [x] **[TDD]** 3.2 抽 `ChatMessageModel` 的查询构造（分页 / 全量 / 单条 / 存在性 / 删除 / 搜索），
      **逐字搬移** SQL 与参数顺序 — ≤50min
- [x] **[TDD]** 3.3 抽 `ChatSessionUserModel` 的构造（列表 / 更新 / 未读自增 / 属性同步 / 预览占位）— ≤40min
- [x] **[TDD]** 3.4 抽 `UserSetting` 的端口分配逻辑（10240 起递增 / 无记录取起始值）— ≤30min
- [x] **[TDD]** 3.5 抽 `LaterHandleModel` 的构造 — ≤20min
- [x] 3.6 门禁增加：核心不得 require sqlite3 / 不得含 process.exit — ≤15min

## 阶段四：接线与验证

- [x] 4.1 4 个 Model 改调用核心，**IO 顺序与语义不变** — ≤40min
- [x] 4.2 门禁增加：Model 层不得残留内联 SQL 字面量（剥注释后）— ≤20min
- [x] 4.3 门禁接 `setup-git-hooks.mjs` + `ci.yml`（**接前已实跑，exit 0**）— ≤20min
- [x] 4.4 **变异验证**：调换 `updateNoReadCount` 参数顺序 → 门禁转红（1 条，精确命中）— ≤30min
- [x] 4.5 变异验证：删掉会话列表的 `status = 1` → 门禁转红（2 条）— ≤20min
- [x] 4.5b 变异验证：`buildSessionAttrSet` 的 `!== undefined` 改成真值过滤 → 门禁转红（3 条）— ≤20min
- [x] 4.5c 变异验证：在 Model 层重新内联一份 SQL → 「无内联 SQL 字面量」转红（1 条）— ≤20min
- [x] 4.6 前端全量 `npm run test`（767 例）+ `npm run build`（exit 0）+ eslint（0 error）；后端 `mvn test`（866 例）— ≤40min

## 阶段五：收尾

- [x] 5.1 回写台账 #7（覆盖计数更新 + 剩余缺口）— ≤15min
- [x] 5.2 spec 回写 `openspec/specs/`（新建 `http-request-layer` + 扩充 `local-sqlite-cache`）— ≤20min
- [x] 5.3 归档 Change + QA / Retro — ≤40min

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] 阶段 A **生产代码零改动**（`git diff --stat easychat-front/src/renderer/src/utils/Request.js` 为空）
- [x] 前端 `npm run test` 全绿（81 spec / **767 例**，本轮前 746）、`npm run build` exit 0、eslint 0 error
- [x] 后端 `mvn test` 全绿（**866** 例）
- [x] 全部 `scripts/verify/*.mjs` exit 0（pre-push **16 道**，含新增 `verify_db_model_core.mjs`）
- [x] **4 处变异验证均转红，且记录了转红的具体断言名**（见阶段四 4.4~4.5c）
- [x] 归档闭环完成（spec 回写 + `git mv` 到 `archive/`）