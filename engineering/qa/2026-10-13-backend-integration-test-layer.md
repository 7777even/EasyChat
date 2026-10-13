# QA — 后端集成测试层（无 DB 方案 A）

- 日期: 2026-10-13
- 效率等级: **L3**（`openspec/changes/2026-10-13-backend-integration-test-layer`）
- 范围: 新增 `com.easychat.integration` 测试包（5 个文件）、`src/test/resources`（2 个文件）；
  **生产代码零改动**；另补 `scripts/verify/verify_no_hardcoded_secret.mjs` 的扫描范围（T13 实测发现的缺口）。

## 验收口径

- **C1 认证与限流守卫的行为可被自动化验证**：非管理员访问管理端 → `CODE_1003`；未登录 → `CODE_2001`；
  超阈值 → 限流；无 `checkRateLimit` 标注的端点不进入限流分支。
- **C2 参数校验在真实 Web 上下文中触发**：`@Valid`（对象绑定）与 `@Validated`（方法级参数）**两条路径各自**
  被验证；这是遗留 #32 的闭环对象。
- **C3 Spring 测试上下文可在无 MySQL / 无 Redis 下启动**，且**绝不指向真实库**。
- **DoD 关键项**：既有 59 个测试文件**零回归**；新增断言须经**变异验证**证明有判别力；
  生产代码零改动。

## 实际执行命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -B test -Dtest=IntegrationContextLoadsTest` | **3/3 通过**，`BUILD SUCCESS` |
| `mvn -B test -Dtest=InterceptorChainBehaviourTest` | **5/5 通过**，`BUILD SUCCESS` |
| `mvn -B test -Dtest=RateLimitBehaviourTest` | **3/3 通过**，`BUILD SUCCESS` |
| `mvn -B test -Dtest=ParamValidationBehaviourTest` | **5/5 通过**，`BUILD SUCCESS` |
| `mvn -B test`（**全量**） | **905 通过 / 0 失败 / 0 错误**（既有 889 + 新增 16），`BUILD SUCCESS` |
| `node scripts/check-api-contract.mjs --strict` | `exit 0`（新增 5 个测试类后**接口零漂移**） |
| `node scripts/check-openspec-hygiene.mjs` | `exit 0` |
| `node scripts/verify/verify_no_hardcoded_secret.mjs` | **26/26 通过**，`exit 0`（补扫描范围后由 25 项增至 26 项） |

### 变异验证（T14，四次，全部已还原）

| # | 变异内容 | 期望 | 实测 |
|---|---------|------|------|
| ① | `GlobalOperationAspect` 的 `!getAdmin()` → `getAdmin()` | T6 三条转红 | ✅ 非管理员 1003 → **0（放行）**；管理员 200 → **404（误拒）** |
| ② | `count > 60` → `count >= 60` | 用例②转红 | ⚠ **首次未转红**（见下「假通过」）；修正编码后 ✅ 转红 |
| ③ | 删除 `AccountController` 类级 `@Validated` | T10 转红 | ✅ `{"code":0,"success"}` —— 空邮箱**完全放行、进入方法体**；且 **T9 未跟着转红**，实证两条校验路径**互相独立** |
| ④ | 往 `application-test.properties` 写裸密码 | 凭据门禁报红 | ✅ 补扫描范围前 **exit 0（缺口属实）**；补后 `[FAIL] 测试资源敏感键为占位符形式`，`25/26`、`exit 1` |

还原复核：`git diff -- easychat-java/src/main` **为空**。

### 过程中查出的两个既有缺陷（**本 Change 不改，已登记**）

1. **`RedisConfig#redissonClient()` 的 catch 类型不匹配**：catch 的是
   `io.lettuce.core.RedisConnectionException`，Redisson 实际抛 `org.redisson.client.RedisConnectionException`
   → 那句「redis 配置错误请检查」日志**永不执行**，末尾 `return null` 是死代码；
   Redis 不可用时应用**直接启动崩溃**而非按注释意图降级。
2. **`TokenUserInfoDto.admin` 为 null 时守卫 NPE**：`checkAdmin && !getAdmin()` 对包装类型 `Boolean`
   自动拆箱抛 NPE，被切面 `catch (Exception)` 兜成 `CODE_1002 系统错误`（而非 `1003`）。
   已在 `nullAdminFlagCurrentlyFallsBackToSystemError` 中**把现状钉成断言**，将来修复时该用例会转红提醒。

## 未运行项

- **CI 上的实跑未取证**：本地全绿，但 GitHub Actions 的 `backend` job 跑这套新测试的实跑结果**未验证**
  （本地无法运行 GitHub Actions）。首次推送后须核对 CI 是否仍绿。
- **MyBatis XML 真实 SQL 语义 / 事务边界（`afterCommit` 推帧 20）未覆盖** —— 属方案 B，
  按 ADR-001 明确不在本次范围（Testcontainers 因**本机 Docker daemon 未运行**而排除）。
- **前端 E2E 未做** —— 不在本次范围。
- **未做 `mvn package -DskipTests`** —— 本次无生产代码变更，`mvn test` 已含编译；
  按 AGENTS §2 矩阵，「单个 Controller / Service 局部修改」行对应 `mvn compile`，此处改动为测试代码，
  `mvn test` 的 testCompile 已覆盖编译。

## 证据附件

- `evidence/2026-10-13-backend-integration-test-layer.txt`
  —— 由脚本从**真实** mvn / 门禁输出中抽取（仅截断超长行、丢弃 `BeanCreationException` 包装层、
  GBK→UTF-8 兜底转码），含 T2 四步启动期依赖暴露、三次变异转红、全量 905、凭据门禁红→绿。
  ⚠ 其中「最早的敏感词 `@PostConstruct` 查库失败」那次输出未存档（走了屏幕管道），
  该结论记录在 `IntegrationContextLoadsTest` 类注释中，证据文件已如实标注。

## 结论

**达成**。C1 / C2 / C3 全部由真容器行为断言覆盖，遗留 **#32 已闭环**（且是行为层面闭环，
而非 #32 当年被迫采用的结构断言）。全量 905 通过、既有测试零回归、生产代码零改动。

遗留项与建议（**与本改动无关的既有缺陷，须另立变更**）：

1. `RedisConfig` 的 catch 类型不匹配 → 建议单独修复（生产代码改动，属 L2）。
2. `TokenUserInfoDto.admin` 为 null 时应判 `1003` 而非 `1002` → 建议改为 `Boolean.TRUE.equals(...)`。
3. 方案 B（MyBatis XML / 事务边界的真实行为验证）待后续立项，届时的前置条件是**本机可用的 Docker**。
4. 本仓**限流与业务校验共用 `CODE_1001`**（限流「请求过于频繁」vs 登录「验证码已过期」均 1001），
   已实测确认。它不影响正确性（文案可区分），但会让「按错误码判断成因」的排查手法失效 —— 建议后续考虑拆分。