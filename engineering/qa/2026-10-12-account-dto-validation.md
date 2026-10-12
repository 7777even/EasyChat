# QA — 接通认证入口的 DTO 字段校验

- Change: `openspec/archive/2026-10-12-account-dto-validation`
- 执行日期: 2026-10-12
- 等级: L3

## 范围与验收口径

范围：`@Valid` 接线 + `resetPassword` 签名收敛 + 新增 `UserResetPasswordDTO`。
验收口径来自 spec-delta 的 Scenario，逐条对应下表用例。

## 缺陷的实证（修复前）

临时探针（用完即删），三个请求**全部 `HTTP 200 {"code":0,"message":"success"}`** 直达 Service 层：

| 请求 | 修复前实际 |
|---|---|
| `POST /account/login` `email=` | 200 success |
| `POST /account/login` `email=not-an-email` | 200 success |
| `POST /account/register` `nickName=` | 200 success |

根因：`@Valid` 全仓 0 次使用，而参数以 `@ModelAttribute` 绑定时必须显式标注才触发。

## 实际执行的命令与结果

| 命令 | 结果 |
|---|---|
| `mvn test`（后端全量） | **866 通过 / 0 失败 / 0 错误**（基线 858，+8） |
| 全部 `scripts/verify/*.mjs`（14 道） | 全部 exit 0，含 `check-api-contract.mjs`（证明**路由未变**） |

## TDD 红→绿证据

`AccountControllerMockMvcTest` 16 → **25**（+9）。
红阶段：接线前 **6 例红**（5 条字段级校验 + 1 条类级 `@Validated`）。
绿阶段：接线后 5 条转绿；第 6 条经诊断为**测试环境限制**（见下），改为结构断言。

## 变异验证

| 变异 | 转红结果 |
|---|---|
| 去掉 `login` 的 `@Valid` | **2 条转红**：`loginEmptyEmailRejectedBeforeService`、`loginInvalidEmailRejectedBeforeService` |
| 把 `UserResetPasswordDTO` 的 `@Pattern` 正则改松为 `^.*$` | **1 条转红**：`resetPasswordWeakPasswordRejectedBeforeService` |
| （间接）删除 `resetPassword` 签名 | `GlobalOperationAspectTest` **8 处 `NoSuchMethodException`** —— 证明「删签名暴露隐藏调用点」的设计意图已实证 |

## 三处需要说明的判断

### 1. 响应文案是 `字段名: 提示`（如 `email: 邮箱格式不正确`）

我最初按 DTO 里的原始文案写断言，实现后实测为带字段名前缀。核查发现
`AGlobalExceptionHandlerController#handleBindException` **本就写好了**该格式
（`field + ": " + message`，多条以 `"; "` 连接），注释即写着「参数校验失败（@Valid @ModelAttribute）」，
只是全仓无人用 `@Valid` 而**从未触发**。

**决定**：不改处理器。改格式会影响所有走 `BindException` 的端点（各类 Query 对象），
超出本 Change 范围。字段名前缀会暴露给终端用户，属**已知的 UX 特性而非缺陷**，
已在测试注释与 spec 中写明。

### 2. 类级 `@Validated` 无法用 MockMvc 端到端验证

`standaloneSetup` 不注册 `MethodValidationPostProcessor`，故
`sendEmailCode(@NotEmpty String email, …)` 这类校验在单测环境下**根本不触发**——
写端到端断言必然恒红，那是**环境缺失**而非缺陷。

**决定**：改为**结构断言**（反射检查类上 `@Validated` 与该参数 `@NotEmpty` 仍在），
并在测试注释写明「为何不能用端到端验」。
按 AGENTS §2.1 第 3 条登记为不可单测检测项（台账 #32），由真机验证兜底。

### 3. 「删签名而非改名」的用意已实证

`resetPassword` 由 3 个 `String` 改为单个 DTO 后，`GlobalOperationAspectTest` 的
`getMethod("resetPassword", String×3)` **8 处全部抛 `NoSuchMethodException`**——
与 AGENTS §6.4-10 删除 `BaseParam.orderBy` 是同一手法：让隐藏调用点编译期暴露，
而非静默失效。

## 未运行项 / 已知缺口

1. **未做真机联调**：三个端点的实际前端提示文案未在浏览器中点击验证。
2. **UI 证据形式**：按 `easychat-front/AGENTS.md` §4.1 判为 **B 形式**——
   前端**零改动**（请求参数名不变），界面呈现不变；证据为上述 9 条用例 + spec-delta 逐条对应。
3. **前端未验证**：`check-api-contract.mjs` exit 0 证明路由无漂移；
   `UserPassword.vue` / `Login.vue` 提交的参数名未改动，但未在浏览器中实测。

## 结论

**通过**。红→绿证据完整，两处变异均验证到判别力，契约不变已由门禁佐证。