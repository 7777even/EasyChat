# Tasks — CORS 允许来源可配置 + 宽松组合显式告警

- 对应 Design: 2026-10-21-cors-origin-whitelist/design.md
- 创建日期: 2026-10-21
- 预计实施时长: 2h

> 实施顺序遵循 TDD：先写失败测试，再写实现。

## 阶段一：测试骨架（先红）

- [x] **[TDD]** 在 `CorsConfigTest` 补 4 例：可配置且白名单外**不回显** / 通配判定纯逻辑 /
      告警须含配置键与风险条件 / 空配置回退且仍判宽松 —— ≤1h
- [x] 补 `CorsConfig` 的方法**桩**（先只给签名与占位实现，让用例编译并**跑红**）—— ≤30min
- [x] 跑红确认：4 条新用例转红、**既有 4 例保持绿**（证明默认行为未变）—— ≤15min

## 阶段二：实现

- [x] **[TDD]** 实现 `parseOriginPatterns` / `isWildcardOrigin` / `isBlankConfig` / `permissiveWarning`
      （纯函数，可单测）—— ≤1h
- [x] `CorsConfig#corsFilterRegistration` 改用解析后的来源列表（默认仍 `*`）—— ≤20min
- [x] 新增 `@PostConstruct` 启动告警（空配置 → ERROR；宽松组合 → WARN）—— ≤20min
- [x] `application.properties` 新增 `easychat.cors.allowed-origin-patterns=*`（**默认不变**）—— ≤10min
- [x] 跑绿：8 例全绿（既有 4 + 新增 4）—— ≤20min

## 阶段三：回归与文档

- [x] `mvn test` 全量（**978 → 982**，0 失败）—— ≤30min
- [x] 17 道本地门禁全绿（`pre-push` 批量）—— ≤20min
- [x] 同步 `docs/system-facts.md` §14 #30 与变更日志；`README` §7 增坑位说明 —— ≤20min
- [x] `engineering/qa/` 证据附件 + `engineering/retro/` 四段式 —— ≤30min

## 阶段四：收尾

- [x] spec-delta 回写 `openspec/specs/cors/spec.md` + 归档 Change —— ≤30min

## DoD 对照（完成即可勾选）

- [x] `openspec/changes/2026-10-21-cors-origin-whitelist/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 最小验证：`mvn compile` / `mvn package` 0 error
- [x] 接口契约 / 权限 / 数据结构**未**改变（默认行为逐字一致）
- [x] 归档闭环：spec-delta 已回写 `specs/cors/` + `git mv` 到 `archive/`
- [x] QA / Retro 记录落在 `engineering/`
