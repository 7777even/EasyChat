# Spec Delta — 靓号保存端点改收 DTO

- 关联 Tasks: 2026-10-13-beauty-account-dto/tasks.md
- 创建日期: 2026-10-13

## ADDED Requirements

### Requirement: 管理端写入口不得直接接收持久化对象

管理端 Controller 的写入口必须接收 DTO，不得直接接收 `entity/po` 下的对象。

**理由**（非形式主义）：PO 直接绑定 = **客户端可提交 PO 上的任意字段**。
今天 `UserInfoBeauty` 只有 4 个无害字段，但一旦有人给这个 PO 加一个内部用字段
（如 `source` / `operatorId`），它会**自动变成可由外部提交**的入参，
且构建、测试、门禁都不会给任何提示。这是**结构性**风险。

#### Scenario: 靓号保存端点接收 DTO

- **WHEN** 审视 `AdminUserInfoBeautyController` 的写入口
- **THEN** `saveBeautAccount` 的入参是 DTO 而非 `entity.po.UserInfoBeauty`
- **AND** 该入参带 `@Valid`，使 DTO 上的约束注解真正生效
- **AND** 全仓 Controller 中「直接收 PO」的端点数为 **0**

#### Scenario: DTO 字段名与前端提交名一致（前端零改动）

- **WHEN** 前端以 `form-urlencoded` 提交 `id` / `email` / `userId` / `status`
- **THEN** DTO 按**同名**绑定，请求契约不变
- **AND** `check-api-contract.mjs --strict` 仍 exit 0

#### Scenario: Service 层签名不因入参收敛而改变

- **WHEN** Controller 收敛入参
- **THEN** `UserInfoBeautyService#saveAccount` 的入参**仍是** `UserInfoBeauty`
- **AND** DTO → PO 的转换只发生在 Controller 内
- **AND** 转换**逐字段**赋值，不依赖反射拷贝（否则新增字段会被静默漏掉）

---

### Requirement: 靓号保存入参的必填校验

`email` 与 `userId` 是靓号的身份标识，任一为空都无意义，必须在进入 Service 前拒绝。

#### Scenario: 邮箱为空

- **WHEN** `POST /admin/saveBeautAccount` 且 `email` 为空
- **THEN** 返回 `1001`，提示「邮箱不能为空」
- **AND** **不**进入 Service

#### Scenario: 邮箱格式非法

- **WHEN** `email` 存在但不是合法邮箱格式
- **THEN** 返回 `1001`，提示邮箱格式不正确
- **AND** **不**进入 Service

#### Scenario: 靓号为空

- **WHEN** `userId` 为空
- **THEN** 返回 `1001`，提示「靓号不能为空」
- **AND** **不**进入 Service

#### Scenario: 新建 / 编辑语义不被 DTO 干扰

- **WHEN** `id` 为空
- **THEN** DTO **不**报错（新建是合法场景）
- **AND** 由 Service 的 `id == null` 分流走新建分支
- **WHEN** `id` 非空
- **THEN** 同样**不**报错，由 Service 走编辑分支
- **AND** DTO 层**不**重复表达这条分流规则（避免两层各写一遍）

#### Scenario: status 可选

- **WHEN** `status` 未提交
- **THEN** **不**报错（新建场景的正常情况）
- **AND** 由 Service 按缺省处理

---

## MODIFIED Requirements

（无）

---

## REMOVED Requirements

### Requirement: 靓号保存端点直接接收 UserInfoBeauty

**移除原因**：该端点是全仓唯一违反「不复用 Entity 做出入参」的端点，
且 PO 直接绑定带来「PO 新增字段会被外部自动提交」的结构性风险。
移除后由 ADDED 的两条 Requirement 取代。

#### 迁移说明

- 无对外契约破坏：路径、方法、参数名、响应结构全部不变
- 唯一行为变化：`email` / `userId` 为空或邮箱格式非法时，新增返回 `1001`（此前直达 Service）
- 回退方式：把 Controller 入参改回 `UserInfoBeauty`、删除 DTO 即可，无残留

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 管理端写入口不得直接接收持久化对象 | 2.1~2.3, 3.1, 3.2 |
| C1 | ADDED: 靓号保存入参的必填校验 | 2.1, 2.2, 2.4 |
| C1 | REMOVED: 靓号保存端点直接接收 UserInfoBeauty | 3.2 |