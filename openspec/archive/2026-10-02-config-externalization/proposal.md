# Proposal — 运行时配置外置（profile 分离 + .env 凭据注入）

- 创建日期: 2026-10-02
- 效率等级: L4

## Why

`application.properties` 把 DB 凭据（`root/root`）、TURN 公共凭据（`guest/guess`）、管理员邮箱、文件落盘根目录全部硬编码进版本库，且无 dev/prod 区分——改配置等于改生产基线。推到任何共享环境即等于凭据泄露。这是"功能对标微信桌面 IM"目标下最廉价的降级项：一轮配置重构换掉一整类安全与运维债务。

## What Changes

- 后端: 拆分 `application.properties` 为「公共基线 + dev/prod 两份 profile」；敏感项改为 `${ENV_VAR:默认值}` 占位；新增 `.env.example`（入库）与 `.env`（gitignore）
- 前端: 无改动
- 数据库: 无表结构/字段变更，不需迁移脚本

## Capabilities

- C1: 公共基线 `application.properties` 只保留跨环境一致的配置项，敏感项以 `${ENV_VAR:默认值}` 形式声明，**缺省值可让零配置本地启动仍然成功**
- C2: `application-dev.properties` 承载本地开发值（含公共 TURN 联调凭据），`application-prod.properties` 作为生产模板入库且 **TURN 留空降级为纯 STUN**
- C3: 真实凭据经 `.env` / 系统环境变量注入，`.env` 不入库、`.env.example` 入库；Spring 激活 profile 可由 `SPRING_PROFILES_ACTIVE` 覆盖

## Impact

- 对外接口: 无（端口、路由、错误码、`Result<T>` 包络均不变）
- 存量数据: 无影响，不需迁移脚本
- 性能: 无
- 安全: DB 密码、TURN 凭据、管理员邮箱退出版本库；生产默认不再指向公共中继
- 回退方案: 全部改动集中在 `application*.properties` 与 `.gitignore`，回退即恢复原 `application.properties` 单文件（`git revert` 该提交即可），无数据面影响

---

## ☑ 人工确认关卡

> 本提案经 **用户（项目 owner）** 于 **2026-10-02** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 确认要点（两处显式选择）：
> 1. 方案 = **三段 profile + `.env` 双保险**（`application.properties` 公共基线 + `application-dev` + `application-prod` + `.env`/`.env.example`）
> 2. TURN 公共凭据 = **保留在 dev profile，生产 profile 留空**（生产降级纯 STUN，README §7 已写明必须自建 coturn）
