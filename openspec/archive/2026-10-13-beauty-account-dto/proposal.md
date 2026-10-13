# Proposal — 靓号保存端点改收 DTO，收敛 §3.6 偏差

- 创建日期: 2026-10-13
- 效率等级: L3

## Why

`docs/system-facts.md` §14 遗留 #31 的落点列留了一条**未修的偏差登记**：

> `AdminUserInfoBeautyController#saveBeautAccount` 直接收 `UserInfoBeauty` **PO**（无 `@Valid`），
> 违反「不复用 Entity 做出入参」。

该登记是上一轮 `#2`（认证入口补 `@Valid`）时留下的**顺带发现**，当时以「属无关重构」为由未修。
本 Change 单独处理它。

### 先查实：这**不是**安全缺口

动手前必须确认它值不值得修，故先穷举了后果链：

1. **写入路径有几条**：仅 1 条 —— `POST /admin/saveBeautAccount` → `UserInfoBeautyServiceImpl#saveAccount`。
2. **是否拦得住**：`@GlobalInterceptor(checkAdmin = true)` 守着，非管理员进不来。
3. **下游是否已校验**：`saveAccount` 已有四重校验（邮箱/靓号池内唯一、邮箱/靓号未被注册），
   且 2026-10-12 遗留 #19 又补了 `checkBeautyUserId`（须为纯数字），实测有 13 例覆盖。
4. **PO 有无可越权字段**：`id` / `email` / `userId` / `status` 四个。`id` 由客户端控制，
   但改的是**靓号池行**，而这本来就是管理员可改的域内数据 —— **无跨用户越权**。

**结论**：无安全后果。真正的问题是**契约层面的偏差** —— AGENTS §3.6 明文规定
「不复用 Entity 做出入参」「DTO 每个字段必须有中文注释 / `@Schema`」，
而这是全仓**唯一**违反该条的端点。

> 全仓穷举口径（不是拿台账措辞当前提）：扫 26 个 PO / 9 个 DTO / 28 个 Query
> × 全部 Controller 的方法入参，命中「直接收 PO」的端点**恰好 1 处**，即本 Change 的对象。

### 为什么仍要修

- **偏差登记是「已知不管」**。留着它，等于 §3.6 事实上没有约束力；
  下一个开发者照着这个先例写 Controller 不会被任何东西拦住。
- **PO 直接绑定 = 客户端可提交任意字段**。今天 `UserInfoBeauty` 只有 4 个无害字段，
  但一旦有人给这个 PO 加字段（比如内部用的 `source`、`operatorId`），
  它会**自动变成可由外部提交**的入参，且没有任何提示。这是**结构性**风险，不是当前风险。
- 修复成本低（1 个 DTO + 1 行 Controller + 测试），且与 #2 刚建立的 DTO 范式一致。

## What Changes

- 后端:
  - 新增 `com.easychat.entity.dto.UserInfoBeautySaveDTO`（`id` / `email` / `userId` / `status`，
    带 `@NotEmpty` / `@Email`）；
  - `AdminUserInfoBeautyController#saveBeautAccount` 入参由 `UserInfoBeauty` 改为该 DTO，
    在方法体内显式转成 PO 传给 Service（**Service 签名不变**，故 13 例 Service 测试零改动）。
- 前端: **零改动**（字段名即参数名，未变；`check-api-contract.mjs` 应仍 exit 0）。
- 数据库: **无变更**。
- 权限语义: **无变更**（`checkAdmin = true` 不动）。

## Capabilities

- C1: 管理端靓号保存端点的入参不再是持久化对象，改为带校验注解的 DTO，
     且「PO 加字段会被外部自动提交」这一结构性风险被消除

## Impact

- 对外接口: **路径、方法、参数名、响应结构全部不变**；
  **响应文案新增**一类：`email` / `userId` 为空或邮箱格式非法时返回
  `1001` + `字段名: 提示`（与 #2 在 login/register 上刚引入的格式一致）。
  这是本 Change 唯一的对外行为变化，且方向是「更早更准地拒绝」。
- 存量数据: 无影响（只改入参形状，不改写任何行）。
- 性能 / 安全: 无性能影响；安全上是净收益（消除结构性风险）。
- 回退方案: 把 Controller 入参改回 `UserInfoBeauty`、删除 DTO 即可，无残留。

---

## ☑ 人工确认关卡

> 本提案经 **用户（需求方）** 于 **2026-10-13** 确认，允许进入 design 阶段。
> 原始选项：「#31 + #1 根治（L4）」中选定本项；#1 另立 Change。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

> **立项前已查实的规模**（避免选项建立在假设上）：
> 全仓「Controller 直接收 PO」的端点**恰好 1 处**（自建脚本穷举 26 PO / 9 DTO / 28 Query × 全部 Controller）；
> `UserInfoBeautyServiceImplTest` 已有 **13 例**覆盖 `saveAccount`，Service 侧无需改动；
> 前端 `BeautyAccountEdit.vue` 提交整行 4 字段，DTO 用**同名**字段即零改动。