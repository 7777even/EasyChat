# Design — 接通认证入口的 DTO 字段校验

## 架构

```
HTTP 请求（表单/查询参数，参数名不变）
    ↓
AccountController.login(UserLoginDTO dto)        ← 补 @Valid
AccountController.register(UserRegisterDTO dto)  ← 补 @Valid
AccountController.resetPassword(UserResetPasswordDTO dto)  ← 签名由 3×String 改为 DTO
    ↓  ↑ 校验失败在此抛 MethodArgumentNotValidException → AGlobalExceptionHandler → 1001 + 字段级 message
    ↓
userInfoService.login / register / resetPasswordByEmail
```

## 决策（ADR）

### ADR-001: 用 `@Valid` 而非 `@Validated` + `@NotNull` 手写

`@NotNull` 直接标在方法参数上需要类上 `@Validated` + 方法参数注解解析（Spring 的
`MethodValidationPostProcessor`），而 `@Valid` 走标准 MVC 绑定流程、异常也统一落到
`AGlobalExceptionHandler` 已有的 `MethodArgumentNotValidException` 分支。
本仓 `AGlobalExceptionHandler` **已经有**该 handler（第 66 行注释即写着「参数校验失败（@Valid @RequestBody）」），
只是从未被 `@Valid` 触发过 —— 即**异常处理侧已就绪，只缺接线**。

### ADR-002: `resetPassword` 改签名而非保留散参数

**保留散参数 + 手写 `@NotEmpty`** 是现状。改动的理由不只是「统一」：

1. 手写 `@NotEmpty String email` 依赖类上已有的 `@Validated`，**这条路已经在走**，
   但它与 DTO 注解是两套机制，且 DTO 那套完全没接线 → **双轨**。
2. **删签名能暴露隐藏调用点**。`AccountControllerMockMvcTest` 与
   `GlobalOperationAspectTest` 都用 `getMethod("resetPassword", String.class, String.class, String.class)`
   反射定位该端点——签名一改，这些调用点**逐一编译失败**，不会出现「改了签名但某处仍按老签名调用且静默生效」。
   这与 AGENTS §6.4-10 删除 `BaseParam.orderBy` 是同一手法。
3. 散参数 3 个 + 3 条手写注解，换成 1 个 DTO 后校验声明与字段**写在一起**，改一处漏一处的空间消失。

**代价**：Spring 仍按参数名绑定 DTO 字段（`email` / `code` / `newPassword`），
**前端请求格式零变化**，已由测试断言锁定。

### ADR-003: 不改 `login` / `register` 的 DTO 字段定义

约束本身正确（`@NotEmpty` / `@Email`），缺的只是触发。只接线、不改约束，
使本次改动面严格限定在「接线 + 签名收敛」，不引入新的校验规则争议。

### ADR-004: 密码强度校验复用 `Constants.REGEX_PASSWORD`

`resetPassword` 手写的 `@Pattern(regexp = Constants.REGEX_PASSWORD)` 原样搬进 DTO，
**不趁机调整正则**——密码规则是独立的产品决策，不应夹带在「接线」这个改动里。

## 风险

| 风险 | 评估 |
|---|---|
| 前端某处依赖旧的泛化错误文案 | 前端 `Request.js` 只弹 `body.message`，不匹配具体文案；已 grep 确认无硬编码这些提示 |
| 删签名漏改调用点 | **编译期暴露**，不会静默 |
| 空邮箱从「下游报账号密码错误」变成「邮箱不能为空」，算不算行为回退 | 属**改进**：字段级提示本就是 DTO 作者的设计意图 |
| 图形验证码校验顺序变化 | `@Valid` 在**进入方法体之前**执行，故「空邮箱」会先于「验证码过期」被拒。这是更早、更准的失败反馈 |

## 依赖

无新增依赖。`javax.validation` 已在 `pom.xml` 中（DTO 现役使用）。

## 数据影响

**零**。无表结构变更、无字段变更、无数据迁移。