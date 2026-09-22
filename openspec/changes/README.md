# openspec/changes/ — 进行中的规格变更

本目录存放 L3 / L4 级别改动对应的进行中的规格变更（Change）。

## 目录结构

```
changes/<YYYY-MM-DD-<name>/
├─ .openspec.yaml           ← 元数据（schema + created 日期）
├─ proposal.md              ← Why / What / Capabilities / Impact
├─ design.md                ← 架构、ADR、风险、依赖
├─ tasks.md                 ← 可勾选任务清单（≤2h/条）
└─ spec-delta.md            ← 新增 / 修改 / 移除
```

## 命名规则

- 目录前缀 `YYYY-MM-DD-`，以创建日期开头；同名能力后缀加 `-2` / `-3` 区分。
- `.openspec.yaml` 必备：`schema: spec-driven` + `created: <YYYY-MM-DD>`。

## 归档纪律

`tasks.md` 全部勾选 → 必须在**同一次交付内**完成收尾：
1. `spec-delta.md` 合入 `openspec/specs/<capability>/spec.md`；
2. `git mv openspec/changes/<name> openspec/archive/<YYYY-MM-DD>-<name>`。

禁止滞留全勾的 Change 在 `changes/`。详见根 `AGENTS.md` §7.1。
