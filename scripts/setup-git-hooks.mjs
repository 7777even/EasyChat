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
 *   pre-push    → 依次调用契约 / IPC / 规格卫生 / 配置凭据 4 条静态门禁
 *                 （纯静态、无需启动服务；构建与单测由 CI 的 backend/frontend job 负责）
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
  'commit-msg': {
    sh: `#!/bin/sh
node "$(git rev-parse --show-toplevel)/scripts/commit-msg-lint.mjs" "$1"
`,
    batScript: 'commit-msg-lint',
  },
  'pre-commit': {
    sh: `#!/bin/sh
node "$(git rev-parse --show-toplevel)/scripts/pre-commit-guard.mjs"
`,
    batScript: 'pre-commit-guard',
  },
  'pre-push': {
    sh: `#!/bin/sh
set -e
ROOT="$(git rev-parse --show-toplevel)"
node "$ROOT/scripts/check-api-contract.mjs" --strict
node "$ROOT/scripts/check-ipc-registration.mjs" --strict
node "$ROOT/scripts/check-openspec-hygiene.mjs"
node "$ROOT/scripts/verify/verify_no_hardcoded_secret.mjs"
node "$ROOT/scripts/verify/verify_ws_frame_parity.mjs"
node "$ROOT/scripts/verify/verify_file_type_content_type.mjs"
`,
    // Windows .bat 入口：pre-push 串多条门禁，逐条失败即中断
    batScript: 'pre-push-gates',
  },
};

let installed = 0;
for (const [name, def] of Object.entries(hooks)) {
  const target = join(HOOKS_DIR, name);
  writeFileSync(target, def.sh, { encoding: 'utf-8' });
  try { chmodSync(target, 0o755); } catch (_) { /* Windows 下无效但忽略 */ }

  // Windows：同目录写一个 .bat 让 TortoiseGit / 部分 IDE 能调起
  const batBody = name === 'pre-push'
    ? [
        '@echo off',
        'setlocal',
        'for %%I in ("%~dp0..") do set "ROOT=%%~fI"',
        'node "%ROOT%\\scripts\\check-api-contract.mjs" --strict || exit /b 1',
        'node "%ROOT%\\scripts\\check-ipc-registration.mjs" --strict || exit /b 1',
        'node "%ROOT%\\scripts\\check-openspec-hygiene.mjs" || exit /b 1',
        'node "%ROOT%\\scripts\\verify\\verify_no_hardcoded_secret.mjs" || exit /b 1',
        'node "%ROOT%\\scripts\\verify\\verify_ws_frame_parity.mjs" || exit /b 1',
        'node "%ROOT%\\scripts\\verify\\verify_file_type_content_type.mjs" || exit /b 1',
      ].join('\r\n')
    : `@echo off\r\nnode "%~dp0..\\..\\scripts\\${def.batScript}.mjs" %*\r\n`;
  writeFileSync(target + '.bat', batBody, { encoding: 'utf-8' });

  console.log(`[setup-hooks] ✓ 安装 ${name} → ${target}`);
  installed++;
}

console.log(`[setup-hooks] 共安装 ${installed} 个 hook。后续执行 git commit / push 时会自动触发。`);
