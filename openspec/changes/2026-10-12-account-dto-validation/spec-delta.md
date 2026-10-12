# Spec Delta — 接通认证入口的 DTO 字段校验

- 关联 Tasks: 2026-10-12-account-dto-validation/tasks.md
- 创建日期: 2026-10-12

## ADDED Requirements

### Requirement: 认证入口字段级校验真正生效（对齐 proposal Capability C1）

login / register / resetPassword 三个端点必须在**进入 Service 之前**校验请求字段，
非法输入返回 `code=1001` 与**字段级**中文提示。

#### Scenario: 登录邮箱格式非法

- **WHEN** `POST /account/login`，`email=not-an-email`，其余字段合法
- **THEN** 返回 `HTTP 400` + `code=1001`
- **AND** `message` 为「邮箱格式不正确」
- **AND** **不调用** `userInfoService.login`

#### Scenario: 登录邮箱为空

- **WHEN** `POST /account/login`，`email=`（空）
- **THEN** 返回 `code=1001` + 「邮箱不能为空」
- **AND** 不调用 Service

#### Scenario: 注册昵称为空

- **WHEN** `POST /account/register`，`nickName=`（空），其余合法
- **THEN** 返回 `code=1001` + 「昵称不能为空」
- **AND** 不调用 Service

#### Scenario: 多个字段同时非法时返回其中一条

- **WHEN** `POST /account/register`，`email` 与 `nickName` 均为空
- **THEN** 返回 `code=1001`，`message` 为**某一条**字段级提示
- **AND** 不调用 Service

#### Scenario: 重置密码新密码强度不足

- **WHEN** `POST /account/resetPassword`，`newPassword=123`（不含字母，不足 8 位）
- **THEN** 返回 `code=1001` + 「密码格式不正确」
- **AND** 不调用 `userInfoService.resetPasswordByEmail`

#### Scenario: 重置密码字段为空

- **WHEN** `POST /account/resetPassword`，`code=`（空）
- **THEN** 返回 `code=1001` + 「验证码不能为空」
- **AND** 不调用 Service

---

### Requirement: 认证入口校验口径统一（对齐 proposal Capability C2）

三个端点一律「DTO + `@Valid`」，不得再出现「DTO 靠注解、手写参数靠 `@NotNull`」的双轨。

#### Scenario: resetPassword 接收 DTO

- **WHEN** 检查 `AccountController#resetPassword` 的签名
- **THEN** 形参为**单个** `UserResetPasswordDTO`，不再是三个 `String`
- **AND** 该参数标注 `@Valid`

#### Scenario: 请求参数名保持不变（前端零改动）

- **WHEN** 以表单参数 `email` / `code` / `newPassword` 调用 `/account/resetPassword`
- **THEN** 参数正确绑定到 DTO 对应字段
- **AND** 校验通过时正常调用 Service（**前端无需任何改动**）

#### Scenario: 类级 @Validated 不再承担参数校验职责

- **WHEN** `AccountController` 上仍有其它 `@NotNull` 等方法参数校验
- **THEN** 它们**继续生效**（本 Change 不移除类级 `@Validated`，只改 `resetPassword` 的写法）

---

## MODIFIED Requirements

### Requirement: 认证入口的失败反馈语义

原先非法字段输入会穿过 Controller 抵达 Service，得到**下游泛化错误**
（如「账号密码错误」）；现在改为在入口即被拒绝并返回**字段级**提示。

#### Scenario: 空邮箱登录

- **WHEN** 以空邮箱登录一个真实存在的账号
- **THEN** 响应 `message` 为「邮箱不能为空」，而**非**「账号密码错误」

**变更前**: `@Valid` 未标注，11 条 DTO 约束全部不生效，请求直达 Service，
空邮箱由 `UserInfoServiceImpl#login` 的下游判空拦截，返回泛化错误文案。

**变更后**: `@Valid` 生效，字段级提示在入口返回，更早也更准确。

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 认证入口字段级校验真正生效 | 2.1~2.5, 3.1, 3.2 |
| C2 | ADDED: 认证入口校验口径统一 | 1.1, 1.2, 3.3 |
| （失败反馈语义） | MODIFIED: 认证入口的失败反馈语义 | 2.1~2.5 |