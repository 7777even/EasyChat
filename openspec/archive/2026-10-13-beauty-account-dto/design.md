# Design — 靓号保存端点改收 DTO

## 架构

```
POST /admin/saveBeautAccount  (form-urlencoded: id / email / userId / status)
   │
   ▼
AdminUserInfoBeautyController#saveBeautAccount(UserInfoBeautySaveDTO dto)
   │                                    ← 本次改动止于此：入参形状从 PO 变成 DTO
   │  @Valid 在方法参数上触发 Bean Validation
   ▼
UserInfoBeautyServiceImpl#saveAccount(UserInfoBeauty)   ← 签名不变，13 例测试零改动
   │  内部四重校验（邮箱/靓号唯一、未注册）+ #19 的 checkBeautyUserId 纯数字
   ▼
EmailVerifyCodeMapper → user_info_beauty
```

## 决策（ADR）

### ADR-001: DTO 与 PO 之间在 Controller 内手工转换，不让 Service 收 DTO

`saveAccount` 现在收 `UserInfoBeauty`。三个选项：

| 选项 | 代价 |
|---|---|
| A. Service 也改收 DTO | Service 层 13 例测试**全部要改签名**；且 Mapper 层仍需 PO（落库对象），多一层转换 |
| **B. Controller 内 DTO → PO 转换**（选中） | 3 行样板代码；Service / Mapper / 测试**全部不动** |
| C. 让 DTO 继承 PO | 字段一改两处、且「DTO 是独立契约」被打破 |

**选 B**：本 Change 的目的是纠正「入参形状」这一个契约偏差，不是重构整个靓号链路。
把爆炸半径压到 Controller 一个文件，是这个规模下最诚实的做法。

### ADR-002: `status` 不加校验，但保留在 DTO 中

`status` 是 0未使用/1已使用，语义上「不传」是合法的（`UserInfoBeauty` 默认 null，
Service 会按新建处理）。若加 `@NotNull` 会把「不传 status 的新建」判为非法 —— **回归**。
故 `status` 在 DTO 中保持可选，仅加 `@Schema` 说明取值含义。

### ADR-003: `id` 不加 `@NotNull`，且不做「必须为空 / 必须非空」校验

`id` 的语义是「编辑时带上、新建时为空」，由 Service 的 `beauty.getId() == null` 分流。
DTO 层若要表达这个约束就得写自定义校验器 —— 收益不抵复杂度，且 Service 已按 null 分流。
**刻意不做**，避免把「新建/编辑分流」在两层各写一遍。

### ADR-004: DTO 字段名与 PO 完全同名，前端零改动

Spring 按名绑定 `form-urlencoded`，只要 DTO 字段名与前端提交的一致即无感。
前端 `BeautyAccountEdit.vue` 提交 `Object.assign(params, formData.value)`，
即列表返回的整行 4 字段。DTO 保留 4 个同名字段 → `check-api-contract.mjs` 应仍 exit 0。

### ADR-005: 不动 `AGlobalExceptionHandlerController#handleBindException` 的文案格式

`@Valid` 失败会走该处理器，文案格式为 `字段名: 提示`。
这是**既有**实现（#2 已在 login/register 上触发过），改动它会影响所有走 `BindException` 的端点。
与 #2 的 ADR 保持一致，本次同样不动。

## 风险

| 风险 | 缓解 |
|---|---|
| 字段名写错导致前端静默丢参 | DTO 字段名逐字对照 PO；`beauty-account-edit-mount.spec.js` 已有 3 例断言提交参数，补一条端到端断言 |
| 转换时漏字段 | 转换只写 4 行，且由 MockMvc 测试逐字段断言落库对象 |
| `@Email` 规则与 Service 的邮箱规则不一致 | **不改 Service 规则**，DTO 的 `@Email` 只是「更早拦截明显非法值」，二者是包含关系 |
| 存量调用方（其他 Controller / 测试）反射调 `saveBeautAccount` | 全仓 grep 确认只有前端一个调用方；测试侧改用 MockMvc 端到端 |

## 依赖

**零新依赖**。用现有的 `javax.validation`。

## 数据影响

**零**。不写库结构、不改字段、不迁移数据。

**「DB 加列必须改满三处」不适用**——本 Change 不动表。