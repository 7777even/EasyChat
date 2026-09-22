# scripts/ — 自动化门禁脚本

本目录存放对齐 `AGENTS.md` §10 的机控脚本。规范靠人遵守会有漂移；脚本则把关键纪律翻译成"不通过就失败"的硬闸门。

## 脚本清单

| 脚本 | 触发时机 | 守门内容 |
|------|----------|----------|
| `commit-msg-lint.mjs` | git hook `commit-msg` | 提交格式 `type(scope): 描述`、type / scope 枚举、描述含中文、禁止 body |
| `pre-commit-guard.mjs` | git hook `pre-commit` | 暂存区黑名单（构建产物、日志、临时文件）；QA 证据附件除外 |
| `check-api-contract.mjs` | 本地手动 / CI | 后端 Controller 路由 vs 前端 `Api.js` 调用，找出孤儿路由 / 潜在漂移 |
| `check-openspec-hygiene.mjs` | git hook `pre-push` | 进行中的 Change 是否四件套齐全、tasks.md 全勾但未归档阻断推送 |
| `setup-git-hooks.mjs` | 一键安装脚本 | 把上述脚本注册到 `.git/hooks/` |

## 安装

```bash
node scripts/setup-git-hooks.mjs
```

安装后：
- `git commit` → 自动触发 commit-msg-lint + pre-commit-guard
- `git push`  → 自动触发 check-openspec-hygiene（全勾未归档则拒绝推送）

## 跳过（慎用）

紧急修复时如需临时跳过 hook：`git commit --no-verify`。
跳过即视为主动豁免，AI 不得自主建议 `--no-verify`。

## 纯脚本组合（不装 hook）

```bash
node scripts/commit-msg-lint.mjs /path/to/commit-msg-file
node scripts/pre-commit-guard.mjs
node scripts/check-api-contract.mjs [--strict]
node scripts/check-openspec-hygiene.mjs [--strict]
```

## --strict 模式

`check-api-contract.mjs --strict` 与 `check-openspec-hygiene.mjs --strict`：
将 WARN 级别也升级为 exit 1，用于 CI 门禁收紧。
