# Tasks — BCrypt 哈希变体识别与 `matches()` 对齐

- 关联 Design: `openspec/changes/2026-10-04-bcrypt-variant-detection/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 1.5h

## 阶段零：基线固化

- [x] **T0.1** 记录基线：实现为 `startsWith("$2a$")`；全量 `Tests run: 270, Failures: 0` / `exit=0` — ≤15min
- [x] **T0.2** 确认 `PasswordEncoderTest` **不存在**（`utils/` 下仅 `ExtraDataToolsTest` / `IpToolsTest` / `StringToolsTest`） — ≤5min

> **T0.1 踩坑**：首轮基线报 **271**。排查发现是**已删除探针的残留 `.class`**——
> `target/test-classes/com/easychat/utils/_ProbePrefixTest.class` 仍被 surefire 扫到。
> 删除源码不等于删除编译产物。清理后真实基线 **270**（与 Change B 记录一致）。
> **教训：删临时探针后必须 `mvn clean`，否则基线用例数不可信。**

## 阶段一：[TDD] 先写失败测试

- [x] **T1.1** 新建 `PasswordEncoderTest`（11 例），编译通过、实跑**红** — ≤20min
      > **改判说明**：`tasks.md` 原写「仅方法签名 + `UnsupportedOperationException` 桩」，
      > 该纪律针对**新增方法**。本变更的 `isBCrypt`/`encode`/`matches` **均已存在**，
      > 要红的是**行为**。故直接写断言、对**未改动的实现**跑红——
      > 目的（证明用例能检出该缺陷）完全一致，且这才是 Java 里可执行的形式。

- [x] **T1.2** 断言组 A（不变式 I，**推导式**，ADR-002）：遍历 minor `abcdefxyz0123456789`
      + 无 minor + 大写 `A`，凡 `matches` 能校验通过者 `isBCrypt` 必须 true；
      另含 `accepted > 0` **防空转**（探不到任何变体即判环境失效，而非通过） — ≤25min
- [x] **T1.3** 断言组 B（不得放宽过头）：MD5 / `$1$` / `$20$`~`$29$` / `$2$` 无 minor /
      `null` / 空串 / 短码 → 全 false — ≤20min
- [x] **T1.4** 断言组 C（既有语义不回归）：`encode` 60 字符且内置盐（两次编码不同）、
      `matches` 正误密码、空入参守卫 — ≤20min
- [x] **T1.5** 断言组 D（硬编码侧，防「多拒」）：`$2a$`/`$2b$`/`$2x$`/`$2y$` 必须全放行；
      并含基准哈希可校验的前提自检 — ≤10min
- [x] **T1.6** **确认红因正确**：`exit=1`，**3 例转红**，
      失败信息精确指向 `isBCrypt` 断言（非 `NoSuchMethodError`、非桩异常） — ≤10min

> **T1.6 的第三个反例推翻了 proposal 的判断**：除两条预期内的红
> （`$2b$` 漏放）外，**`nonBcryptShapesRejected` 也红了**——首版实现把 `"$2a$"`
> 这类截断串判为 BCrypt。即缺陷是**双向**的，不只「判严」。
> 我在 proposal 里写的「长度校验不增安全性、只增加出错面」**不成立**：
> BCrypt 格式**长度恒为 60**，是格式定义而非启发式阈值。
> 已就此**单独请求 L4 人工确认**（ADR-004），获批后纳入，未静默扩范围。

## 阶段二：实现

- [x] **T2.1** `isBCrypt` 改为「长度 == 60 且前缀命中 `{$2a$,$2b$,$2x$,$2y$}` 白名单」（ADR-001 + ADR-004），
      保留 `StringTools.isEmpty` 短路 — ≤15min
- [x] **T2.2** 注释写明不变式方向不对称、为何不照抄 Spring 接受集、为何要求定长；
      并显式警告「后续维护者不得顺手收紧」 — ≤10min
- [x] **T2.3** `mvn test -Dtest=PasswordEncoderTest` **转绿**：`Tests run: 11, Failures: 0` / `exit=0` — ≤10min

> **T2.1 刻意不用 `startsWith("$2")`**：那会放进无 minor 的 `$2$` 与 `$20$`~`$29$`，
> 掩盖真正的非 BCrypt 数据。已在常量注释写明。

## 阶段三：判别力验证

- [x] **T3.1** 变异①：退回原缺陷（只认 `$2a$`、无长度校验）→ **捕获** — ≤15min
- [x] **T3.2** 变异②：放宽过头（`startsWith("$2")`、无定长校验）→ **捕获**（组 B 捕获） — ≤15min
- [x] **T3.3** 变异③：漏放 `$2b$`（集合退化为 a/x/y）→ **捕获** — ≤10min
- [x] **T3.4** 汇总行区分 `[捕获]` / `[漏网]` / `[无效]`：**5 捕获 / 0 漏网 / 0 无效** / `exit=0` — ≤10min

> **变异脚本首版两轮环境 bug**（均被基线自检或无效计数拦下，未污染结论）：
> ① `execFileSync('mvn', …)` 在 Windows 上无法执行 `mvn.cmd`（`mvn` 实为批处理），
>    抛出的 ENOENT 被 `catch` 吞成「测试失败」→ **5 个假「捕获」**。
>    基线自检（未变异必须先绿）当场拦下。已改走 `cmd /c` 并打印底层错误。
> ② 两个用例锚点写死缩进 / 漏算中间 Javadoc 行 → 被判 `[无效]` 而非 `[漏网]`，
>    区分二者让我立刻看出是锚点问题而非测试无判别力。已改为按方法体整体替换。
>
> ★ 变异⑤「拒绝分支方向反转」（`if(startsWith)` → `if(!startsWith)`）**被捕获**。
> 这**限定了遗留 #11 的适用范围**：静态字面量断言测不出分支方向，**行为测试能测出**。
> `docs/system-facts.md` #11 已据此收敛表述。

## 阶段四：回归与收尾

- [x] **T4.1** 全量 `mvn test`：`Tests run: 281, Failures: 0, Errors: 0` / `BUILD SUCCESS` / `exit=0` — ≤30min
- [x] **T4.2** `verify_password_handoff.mjs`：`21/21 通过` / `exit=0` — ≤10min
- [x] **T4.3** `engineering/qa/2026-10-04-bcrypt-variant-detection.md` — ≤20min
- [x] **T4.4** `engineering/retro/2026-10-04-bcrypt-variant-detection.md`；
      `AGENTS.md` §6.2-2 订正「`$2a$` 开头」表述（**该表述很可能正是缺陷成因**）；
      `docs/system-facts.md` §14 #11 收敛 + 变更日志追加 — ≤15min
- [x] **T4.5** spec-delta 回写 `openspec/specs/password-bcrypt/spec.md`；`git mv` 归档 — ≤20min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| 活体验证「库中出现 `$2b$` 时登录恢复正常」 | 需构造活库数据；且存量全 `$2a$`，冒烟只会重复登录未变数据 | QA「未运行项」已如实登记，未按通过计 |
| 批量迁移存量非 `$2a$` 数据 | 无此类数据（ADR-003） | 若将来导入外部账号再评估 |
| 移除 MD5 双验证分支 | 需先确认存量 MD5 账号已清零，涉及活库 | 独立 Change |
| `StringTools.encodeByMD5` 单测 | 属同批「5 个工具类补测」范围，已在后续任务处理 | — |

## DoD 自检

- [x] `tasks.md` 全部勾选
- [x] **T1.6 红阶段已实跑并留证**（红因指向 `isBCrypt` 断言）
- [x] **T3 五个变异全部被捕获**（证明用例有判别力，非「恒绿」）
- [x] 全量后端测试无新增失败（281/281，基线 270）
- [x] 密码交接门禁 21/21
- [x] `docs/system-facts.md` §14 台账 + 变更日志已同步
- [x] `AGENTS.md` §6.2-2 的「`$2a$` 开头」表述已订正
- [x] 归档闭环完成（spec-delta 已回写 `specs/password-bcrypt/spec.md`）
