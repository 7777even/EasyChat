# templates/ — 模板体系索引

本目录存放 L3 / L4 治理所需的 **可复用模板**，配合根 `AGENTS.md` §7（四件套 + QA/Retro 即刻记录 + 模板体系）。新建对应文件时**复制模板填充**，避免格式漂移。

## 四件套（openspec/changes/<name>/）

| 模板                               | 落点文件        | 关键约束                                                                          |
| ---------------------------------- | --------------- | --------------------------------------------------------------------------------- |
| `_openspec-proposal_template.md`   | `proposal.md`   | Why / What / Capabilities / Impact + 人工确认关卡；≤500 字；必须写明契约同步与数据影响 |
| `_openspec-design_template.md`     | `design.md`     | 架构、接口设计、数据模型、安全设计、ADR、风险表、依赖                              |
| `_openspec-tasks_template.md`      | `tasks.md`      | 可勾选、单条 ≤2h；[TDD] 先写失败测试；含数据层与契约同步任务、DoD                  |
| `_openspec-spec-delta_template.md` | `spec-delta.md` | ADDED / MODIFIED / REMOVED Requirements + Scenario(WHEN/THEN)                      |

四者闭环：`proposal` 的 Capabilities ↔ `spec-delta` 的 Requirement ↔ `tasks` 的验收标准一一对应；人工确认（L3 须过、L4 实施前须过）后才动手。L1 / L2 不建 OpenSpec Change。

## 过程记录（engineering/）

| 模板                        | 落点目录                 | 关键约束                                                               |
| --------------------------- | ------------------------ | ---------------------------------------------------------------------- |
| `_qa_template.md`           | `engineering/qa/`         | 范围 / 验收口径 / 实际命令与用例数 / 未运行项 / 证据附件 / 结论         |
| `_retro_template.md`        | `engineering/retro/`      | 做得好 / 问题 / 原因 / 改进方案 四段式                                 |
| `_ship_template.md`         | `engineering/ship/`       | 发布检查与回滚五段式（代码 / 迁移 / 环境 / 部署 / 回滚）                |

L3 / L4 任务完成后**即刻**写 QA + Retro，不允许攒到最后补。

## 用法

1. 新建 L3 / L4 变更：`cp templates/_openspec-{proposal,design,tasks,spec-delta}_template.md openspec/changes/<name>/`。
2. 完成后：`cp templates/_qa_template.md engineering/qa/YYYY-MM-DD-<slug>.md`，同理 Retro。
3. 发布检查：`cp templates/_ship_template.md engineering/ship/YYYY-MM-DD-<slug>.md`。

> 本目录为模板源，不参与构建；文件名带 `_` 前缀以区别于真实实例。
