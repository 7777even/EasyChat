# QA — 靓号保存端点改收 DTO（遗留 #31）

- 日期: 2026-10-13
- 关联 Change: `openspec/archive/2026-10-13-beauty-account-dto/`
- 效率等级: L3

## 一、范围

| 对象 | 性质 |
|---|---|
| `AdminUserInfoBeautyController#saveBeautAccount` 入参 `UserInfoBeauty` → `UserInfoBeautySaveDTO` | 契约收敛 |
| `UserInfoBeautySaveDTO`（**新增**） | 入参对象 |
| `AdminUserInfoBeautyControllerMockMvcTest`（**新增**，7 例） | 行为测试 |

**未改**：Service 层、前端、数据库表结构与迁移脚本（DoD 已逐项验证）。

## 二、验收口径与实际执行

### 2.1 TDD 红阶段（tasks 2.1）

命令：`mvn test -Dtest=AdminUserInfoBeautyControllerMockMvcTest`
结果：**7 例中 5 条转红**：

```
saveAccount_emailBlank_isRejectedBeforeService       Status expected:<400> but was:<200>
saveAccount_emailMalformed_isRejectedBeforeService   Status expected:<400> but was:<200>
saveAccount_userIdBlank_isRejectedBeforeService      Status expected:<400> but was:<200>
saveAccount_parameterMustNotBePersistEntity          入参仍是 UserInfoBeauty ==> expected: <false> but was: <true>
delBeautAccount_idBlank_isRejected                   Status expected:<400> but was:<200>   ← 非预期
```

前 4 条是**预期的红**（无 DTO → 无约束 → 直达 Service）。
第 5 条**非预期**，见 §4.1。

### 2.2 转绿

同一命令 → **Tests run: 7, Failures: 0, Errors: 0**。

### 2.3 变异验证（**3/3 全部精确捕获**）

| # | 变异 | 转红用例 |
|---|---|---|
| 1 | 去掉方法参数上的 `@Valid` | `saveAccount_emailBlank_isRejectedBeforeService`、`saveAccount_emailMalformed_...`、`saveAccount_userIdBlank_...`（**3 条**） |
| 2 | 去掉 DTO 的 `@Email` | `saveAccount_emailMalformed_isRejectedBeforeService`（**1 条**） |
| 3 | 转换漏掉 `beauty.setUserId(...)`（模拟 `BeanUtils` 式漏字段） | `saveAccount_validCreate_mapsEveryField`（**1 条**，`expected: <U01234567890> but was: <null>`） |

每条都只命中**对应**用例，无连带误伤。

### 2.4 全量与零改动核验

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `mvn test` | **873 例**，Failures 0 / Errors 0（本轮前 866，+7） |
| Service 层改动 | `git diff --name-only HEAD` 过滤 | **无** |
| 前端改动 | 同上 | **无** |
| DB / 迁移改动 | 同上 | **无** |
| 接口契约 | `node scripts/check-api-contract.mjs --strict` | **exit 0** |
| 前端相关用例 | `vitest run __tests__/beauty-account-edit-mount.spec.js` | **7 passed** |

## 三、Scenario → 断言对应

| spec-delta Scenario | 用例 |
|---|---|
| 邮箱为空 → 1001 且不进 Service | `saveAccount_emailBlank_isRejectedBeforeService` |
| 邮箱格式非法 → 1001 且不进 Service | `saveAccount_emailMalformed_isRejectedBeforeService` |
| 靓号为空 → 1001 且不进 Service | `saveAccount_userIdBlank_isRejectedBeforeService` |
| 合法新建 → 四字段逐字段转换 | `saveAccount_validCreate_mapsEveryField` |
| 合法编辑 → id 透传 | `saveAccount_validEdit_passesIdThrough` |
| 入参形状不是 PO | `saveAccount_parameterMustNotBePersistEntity` |
| 新建/编辑语义不被干扰（结构侧） | `delBeautAccount_idIsAnnotatedNotNull`（见 §4.1） |

## 四、本轮自身失误与修正

### 4.1 把「环境缺件」写成了行为断言（初版恒红）

初版有一条 `delBeautAccount_idBlank_isRejected`：「id 为空 → 400 + 1001」。
实测**返回 200**。根因**不是产品行为**，而是 MockMvc `standaloneSetup`
**不注册 `MethodValidationPostProcessor`**，故类级 `@Validated` + 方法参数上的
`@NotNull Integer id` 在单测下**根本不触发** —— 这正是台账 §14 **遗留 #32** 登记的现象。

若照初版保留，会得到一条「因环境缺件而恒红」的用例：既验证不了产品，
又会在将来有人修好测试装配时变成假失败。
**已按 AGENTS §2.1 第 3 条改为结构断言**（只断言注解确实声明在参数上），
并在测试注释中写明行为验证缺口交由真机兜底。

> 注：本文件其余 6 条**性质不同**——它们走 DTO 字段级约束 + `@Valid`，
> 由 `ModelAttributeMethodProcessor` 触发，**在 `standaloneSetup` 下确实生效**
> （遗留 #2 的变异已实证过）。故本次并不受 #32 影响。

### 4.2 凭空引用了不存在的 swagger 依赖

DTO 初稿给每个字段加了 `@Schema` / `@ApiModel`，编译报
`程序包io.swagger.annotations不存在`。全仓 grep 确认**零** swagger 注解使用——
本仓从未引入该依赖（AGENTS §6「零新生产依赖」）。
**已删**，改用中文注释（§3.6 要求的是「中文注释**或** `@Schema`」），
并在 DTO 注释中写明「若将来引入 swagger 依赖应回填，而非现在硬加无来源的注解」。

### 4.3 反射 API 用错（编译期报错，已即时修正）

`m.getParameterAnnotations()[0]` 写成了 `Annotation[]` 参与 for-each，
编译器报 `Annotation 无法转换为 Annotation[]`。
实际 API 返回 `Annotation[][]`，需两层循环。已修正并在代码注释中写明，避免复犯。

## 五、未运行 / 不可运行项

| 项 | 原因 |
|---|---|
| 真实管理端页面操作 | 需 GUI + 启动后端；本轮入参形状不变，`check-api-contract` 已 exit 0 佐证契约未破 |
| `delBeautAccount` 方法参数约束的行为验证 | 见 §4.1，属台账 #32 登记的覆盖缺口 |
| CI 首跑 | `gh` 未认证，**不做推测** |

## 六、结论

**通过**。3 处变异全部被精确捕获；Service / 前端 / DB 三处零改动；
新增约束的响应文案格式（`字段名: 提示`）与遗留 #2 刚引入的格式一致，未新造文案格式。