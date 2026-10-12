# Proposal — 接通认证入口的 DTO 字段校验（@Valid 全仓零使用）

- 创建日期: 2026-10-12
- 效率等级: L3（接口入参校验口径变更 → 响应文案随之改变）

## Why

`AccountController` 的 `login` / `register` 接收 `UserLoginDTO` / `UserRegisterDTO`，
两个 DTO 上共 11 条 Bean Validation 约束（`@NotEmpty` / `@Email`），
但 **`@Valid` 在全仓控制器中使用 0 次** —— 参数以 `@ModelAttribute` 方式绑定时
必须显式标注 `@Valid` 才会触发校验，故这 11 条约束**一条都没有生效**。

**2026-10-12 实证**（临时探针，用完即删）：

| 请求 | 实际响应 |
|---|---|
| `POST /account/login` `email=`（空） | `HTTP 200 {"code":0,"message":"success"}` |
| `POST /account/login` `email=not-an-email` | `HTTP 200 {"code":0,"message":"success"}` |
| `POST /account/register` `nickName=`（空） | `HTTP 200 {"code":0,"message":"success"}` |

三者**均直达 Service 层**（探针中 mock 返回 null，故落到 `success(null)`）。

危害不在于「能建出空邮箱账号」——下游 `UserInfoServiceImpl#register` 另有判空；
而在于**校验意图与实现长期背离**：DTO 作者写下了 `邮箱格式不正确` 这类提示，
实际永远不会出现；用户拿到的是下游泛化错误（如「账号密码错误」），
无从判断到底是邮箱格式错、密码错、还是验证码错。

## What Changes

- 后端:
  - `AccountController#login` / `#register` 的 DTO 参数补 `@Valid`；
  - 新增 `UserResetPasswordDTO`（`email` / `code` / `newPassword`，含 `@NotEmpty` 与
    `@Pattern(REGEX_PASSWORD)`），`resetPassword` 由「三个散参数 + 手写校验」改为
    **接收 DTO + `@Valid`**，与 login/register 口径统一。
- 前端: **不改**。三个端点的请求参数名（`email` / `code` / `newPassword` /
  `password` / `nickName` / `checkCode` / `checkCodeKey`）保持不变，Spring 仍按名绑定。
- 数据库: 无变更。

## Capabilities

- C1: 认证入口的请求字段级校验真正生效，非法输入在**进入 Service 前**即被拒绝，
  且返回**字段级**的中文提示（而非下游泛化错误）
- C2: login / register / resetPassword 三个端点使用**同一套**校验口径（DTO + `@Valid`），
  不再存在「一半靠注解、一半靠手写 if」的双轨

## Impact

- 对外接口:
  - **路由与请求参数名不变**（前端零改动，`check-api-contract.mjs` 不受影响）
  - **响应文案改变**：原先走下游泛化错误的场景，改为返回 `code=1001` + 字段级提示，
    如「邮箱格式不正确」。前端 `Request.js` 错误分支本就弹 `body.message`，**无需改动**
  - `resetPassword` 的 **Java 方法签名**变化（3 个 `String` → 1 个 DTO）。
    这是刻意的：与 AGENTS §6.4-10「删字段而非改名」的同一手法 ——
    **删签名让全部调用点逐一编译失败**，把隐藏的调用点全部暴露出来。
- 存量数据: 无影响（不涉及持久化）。
- 性能 / 安全: 无性能影响。校验前置于 Service，减少了无效的下游调用。
- 回退方案: 去掉两处 `@Valid` 并还原签名即可；`@Valid` 不生效时行为与改动前完全一致。

---

## ☑ 人工确认关卡

> 本提案经 **用户（需求方）** 于 **2026-10-12** 确认，允许进入 design 阶段。
> 原始选项：「A. 补 @Valid 并收敛 resetPassword（L3）」。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 决策依据：DTO 上的约束**本身写得对**，只是没接线；补上即兑现既有设计意图，
> 成本极小。不选「删掉失效约束改手写」，是因为那要动 11 处调用点且并不能让校验生效。

> ⚠ **§3.6 的偏差登记**：`AdminUserInfoBeautyController#saveBeautAccount` 直接收
> `UserInfoBeauty` **PO**（无 `@Valid`），本身违反「不复用 Entity 做出入参」。
> 本 Change **不修**它（属无关重构），仅在此登记。