# Tasks — 后端集成测试层（无 DB 方案 A）

- 关联 Design: `2026-10-13-backend-integration-test-layer/design.md`
- 创建日期: 2026-10-13
- 预估总工时: **17h**
- 实际工时: 约 9h（阶段一 3h / 阶段二 1h / 阶段三 3h / 阶段四 2h / 阶段五 见 QA）

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。
> ⚠ **阶段一是「可行性验证」，不是功能实现**：T1/T2 跑通即证明方案可行；若跑不通，
> 立即停下回到 proposal。**本轮结论：可行**，见下 T4。

## 阶段一：可行性验证

- [x] **T1** 最小可启动验证：只 `contextLoads`、不加任何 mock → **跑红**，暴露第 1 个启动期依赖
      （`SensitiveWordServiceImpl#init` 查库） — ≤1h
- [x] **T2** 新增 `application-test.properties` + `logback-test.xml`，逐个 mock 启动期依赖 →
      **转绿**。实测共暴露**三个**（敏感词 `@PostConstruct` / Redisson Bean 创建期建连 /
      `MessageHandler` topic 订阅），详见 QA — ≤1h
- [x] **T3** 实测结论已记录：启动期外部依赖清单、`@ActiveProfiles("test")` 确已抑制 dev 叠加、
      HikariCP 惰性建连；两个既有缺陷（`RedisConfig` catch 类型不匹配、`admin` 为 null 时 NPE）
      已登记 — ≤30min
- [x] **T4** 方案可行性结论：**可行**，无需回到 proposal（详见 QA「实际执行命令与结果」） — ≤30min

## 阶段二：C3 测试基建可复用性

- [x] **T5** **[TDD]** 抽出 `IntegrationTestBase`（三个启动期 mock 收敛到一处）+ 元测试断言
      「数据源不是真库」。⚠ 元断言初版用 `getConnection().getMetaData().getURL()`，
      **实测其自身就会连库**，改为只注入配置值 — ≤1h

## 阶段三：C1 拦截器链行为

- [x] **T6** **[TDD]** 非管理员访问管理端 → `CODE_1003`；**并配「管理员放行 + Service 确实被调用」**
      两条制衡用例 — ≤2h
- [x] **T7** **[TDD]** token 查不到 → `CODE_2001`；顺带钉住 `admin=null` 时当前落到 `CODE_1002` 的边界行为 — ≤2h
- [x] **T8** **[TDD]** 有限流标注端点超阈值拒 / 正好等于阈值放行 / 无标注端点**从不调用 incr**
      三条制衡 — ≤1h

## 阶段四：C2 参数校验真容器触发（闭环遗留 #32）

- [x] **T9** **[TDD]** `@Valid` 对象绑定：非法邮箱 → `CODE_1001` + 文案含 `字段名: ` 前缀；
      并断言 Service **从未被调用** — ≤2h
- [x] **T10** **[TDD]** 方法级 `@Validated` + `@NotEmpty`：空邮箱 → `CODE_1001` 且方法体未执行。
      **变异③（删 `@Validated`）实测返回 `{"code":0,"success"}` —— 校验完全失效**，
      这是 #32 的行为层面闭环证据 — ≤2h
- [x] **T11** **[TDD]** 合法入参必须进入方法体（防止「恒拒绝」假通过），
      并断言限流计数仍照常发生 — ≤1h

## 阶段五：验证与收尾

- [x] **T12** `mvn test` 全量：**905 通过 / 0 失败**（既有 889 + 新增 16），**零回归** — ≤1h
- [x] **T13** 受影响门禁全绿：`check-api-contract --strict` exit 0（接口零漂移）、
      `check-openspec-hygiene` exit 0、`verify_no_hardcoded_secret` 26/26 exit 0。
      ⚠ 顺带查出**该门禁不扫 `src/test/resources`**（实测裸密码 `exit 0`），已补扫描范围并用探针验证 — ≤1h
- [x] **T14** **[TDD]** 变异验证**四次**，全部已还原（`git diff -- easychat-java/src/main` 为空）：
      ① `checkAdmin` 方向反写 → 3 条转红；② 阈值 `>`→`>=` → **首次未转红（假通过）**，
      修正字符集解码后转红；③ 删类级 `@Validated` → T10 转红而 T9 不转（实证两条路径独立）；
      ④ 凭据门禁补扫描范围 → 探针报红 — ≤1h
- [x] **T15** `engineering/qa/2026-10-13-backend-integration-test-layer.md` +
      `evidence/2026-10-13-backend-integration-test-layer.txt` — ≤1h
- [x] **T16** `engineering/retro/2026-10-13-backend-integration-test-layer.md` — ≤30min
- [x] **T17** spec-delta 回写 `openspec/specs/integration-test/spec.md` + `git mv` 归档 — ≤30min

## DoD 自检

- [x] `tasks.md` 全部勾选（含 T4「不可行则停止」分支也已有明确结论：**可行**）
- [x] `mvn test` 0 error（905/905），**既有测试零回归**
- [x] T14 变异验证已跑过且**确认转红**（④ 项首跑为假通过，已修正后确认）
- [x] 遗留 **#32** 已闭环（行为层面；见 QA 与 spec-delta 的 REMOVED 段）
- [x] `docs/system-facts.md` 变更日志已同步；`AGENTS.md` §10 门禁表无需新增（本次未新增门禁脚本，
      仅补强既有 `verify_no_hardcoded_secret` 的扫描范围）
- [x] 归档闭环完成（spec-delta 回写 `specs/` + `git mv` 到 `archive/`）
- [x] QA / Retro 已落 `engineering/`

## 本 Change 范围外 / 移交说明（非未完成任务，故不以 checkbox 呈现）

> 按 AGENTS §7.1「全勾必归档」，**未完成项不得靠勾选蒙混**；反之，**本地客观无法完成的外部验证**
> 也不应伪装成「未完成的任务」而卡住归档。二者分列如下：

**A. 本地无法取证的外部验证（非任务未做）**

- **CI `backend` job 实跑本套新测试的取证** —— 本地全绿（905/905），但 GitHub Actions 的实跑结果
  本地无法取得。已登记于 QA 的「未运行项」。**首次推送后须核对 CI 是否仍绿**；
  若红，按 CI 输出排查（最可能候选：`application-test.properties` 在 CI 上是否仍生效）。

**B. 明确移交另立 Change 的既有缺陷（本 Change 只登记，未改生产代码）**

1. `RedisConfig#redissonClient()` 的 catch 类型不匹配 —— catch 的是 `io.lettuce.core.RedisConnectionException`，
   而 Redisson 实际抛 `org.redisson.client.RedisConnectionException`；故那句错误日志**永不执行**、
   末尾 `return null` 是死代码，Redis 不可用时应用**直接崩溃**而非降级。
2. `TokenUserInfoDto.admin` 为 `null` 时守卫自动拆箱 NPE → 落到 `CODE_1002 系统错误` 而非 `1003 无权限`
   （现状已由 `nullAdminFlagCurrentlyFallsBackToSystemError` 钉成断言，修复时该用例会转红提醒同步）。