#!/usr/bin/env node
/**
 * setup-git-hooks.mjs
 * 
 * 安装 EasyChat 项目 Git hook 到 .git/hooks/。
 * 用法：node scripts/setup-git-hooks.mjs
 * 
 * 会安装：
 *   commit-msg  → 调用 scripts/commit-msg-lint.mjs
 *   pre-commit  → 调用 scripts/pre-commit-guard.mjs
 *   pre-push    → 调用 scripts/check-openspec-hygiene.mjs
 * 
 * 仅在 Windows 上需要同时生成 .bat 入口（Git for Windows 调用 hook 时需要）。
 */

import { existsSync, writeFileSync, chmodSync, mkdirSync, readdirSync, copyFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const HOOKS_DIR = join(ROOT, '.git', 'hooks');
const SCRIPTS_DIR = join(ROOT, 'scripts');

if (!existsSync(HOOKS_DIR)) {
  mkdirSync(HOOKS_DIR, { recursive: true });
}

const hooks = {
  'commit-msg': `#!/bin/sh
node "$(git rev-parse --show-toplevel)/scripts/commit-msg-lint.mjs" "$1"
`,
  'pre-commit': `#!/bin/sh
node "$(git rev-parse --show-toplevel)/scripts/pre-commit-guard.mjs"
`,
  'pre-push': `#!/bin/sh
node "$(git rev-parse --show-toplevel)/scripts/check-openspec-hygiene.mjs"
`,
};

let installed = 0;
for (const [name, content] of Object.entries(hooks)) {
  const target = join(HOOKS_DIR, name);
  writeFileSync(target, content, { encoding: 'utf-8' });
  try { chmodSync(target, 0o755); } catch (_) { /* Windows 下无效但忽略 */ }

  // Windows：同目录写一个 .bat 让 TortoiseGit / 部分 IDE 能调起
  const batContent = `@echo off\r\nnode "%~dp0\\..\\..\\scripts\\${name === 'commit-msg' ? 'commit-msg-lint' : name === 'pre-commit' ? 'pre-commit-guard' : 'check-openspec-hygiene'}.mjs" %*\r\n`;
  writeFileSync(target + '.bat', batContent, { encoding: 'utf-8' });

  console.log(`[setup-hooks] ✓ 安装 ${name} → ${target}`);
  installed++;
}

console.log(`[setup-hooks] 共安装 ${installed} 个 hook。后续执行 git commit / push 时会自动触发。`);
