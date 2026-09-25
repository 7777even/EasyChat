#!/usr/bin/env node
/**
 * check-ipc-registration.mjs
 *
 * 扫描主进程 IPC 通道的「注册遗漏」：
 *   ipc.js 中定义并 export 的 onXxx() 注册函数，必须在 index.js 中真实调用一次，
 *   否则该通道在运行时根本不存在（构建不会报错，功能静默失效）。
 *
 * 背景：2026-09-26 发现 5 个通道（onSaveOrUpdateMessage / onDelLocalMessage /
 *   onCopyText / onSetSessionNoDisturb / onSaveSessionDraft）只加进了 ipc.js 的
 *   export 列表，从未在 index.js 调用注册 —— 代码写完、构建通过、功能全废。
 *   见 engineering/retro/2026-09-26-chat-record-export.md。
 *
 * 用法：node scripts/check-ipc-registration.mjs [--strict]
 *   --strict 时未注册视为 exit 1（建议挂 pre-commit）
 */

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const STRICT = process.argv.includes('--strict');

const IPC_FILE = join(ROOT, 'easychat-front/src/main/ipc.js');
const INDEX_FILE = join(ROOT, 'easychat-front/src/main/index.js');

// 已在 index.js 之外注册的通道（例如由 wsClient / windowProxy 自行注册）
const ALLOWLIST = new Set([
  'onLoginOrRegister', // 由 index.js 以 callback 形式传入，调用形态不同
]);

function fail(msg) {
  console.error('[ipc-registration] × ' + msg);
}

function main() {
  if (!existsSync(IPC_FILE) || !existsSync(INDEX_FILE)) {
    console.log('[ipc-registration] ℹ 未找到主进程文件，跳过');
    return 0;
  }

  const ipcSrc = readFileSync(IPC_FILE, 'utf-8');
  const indexSrc = readFileSync(INDEX_FILE, 'utf-8');

  // 1. ipc.js 中形如 `const onXxx = () => {` 的定义
  const defined = new Set();
  for (const m of ipcSrc.matchAll(/const\s+(on[A-Za-z0-9_]*)\s*=\s*\(/g)) {
    defined.add(m[1]);
  }

  // 2. ipc.js 的 export 列表（证明它是对外暴露的注册函数）
  const exported = new Set();
  const exportBlock = ipcSrc.slice(ipcSrc.lastIndexOf('export {'));
  if (exportBlock) {
    for (const m of exportBlock.matchAll(/\b(on[A-Za-z0-9_]*)\b/g)) {
      exported.add(m[1]);
    }
  }

  // 3. index.js 中的实际调用 `onXxx(`
  const called = new Set();
  for (const m of indexSrc.matchAll(/\b(on[A-Za-z0-9_]*)\s*\(/g)) {
    called.add(m[1]);
  }

  const missing = [...exported]
    .filter((name) => defined.has(name))
    .filter((name) => !called.has(name))
    .filter((name) => !ALLOWLIST.has(name))
    .sort();

  // 反向：index.js 调用了但 ipc.js 没导出（多半是 import 遗漏，构建期才炸，这里只提示）
  const unknown = [...called]
    .filter((name) => !exported.has(name))
    .filter((name) => !/^on(Added|Ready|Activate|Browser|Will|Second|Open|Close|Before|Cert|Select|Update|Login|Register|New|Session|Window|Move|Resize|Show|Hide|Minimize|Maximize|Enter|Leave|Focus|Blur|Swing|Drop|Scroll|Swipe|Context|Menu|Page|Download|Certificate|Child|Plugin|Remote|Console|Ipc|App|Quit|All|Request|Response|Error|Finish|Start|End|Change|Input|Touch|Access|Content|Media)/.test(name))
    .sort();

  console.log(`[ipc-registration] ipc.js 注册函数 ${defined.size} 个 / export ${exported.size} 个 / index.js 调用 ${called.size} 个`);

  if (missing.length > 0) {
    missing.forEach((name) => {
      fail(`通道 ${name} 在 ipc.js 已定义并导出，但 index.js 未调用注册 —— 运行时该通道不存在`);
    });
  }

  if (unknown.length > 0) {
    unknown.forEach((name) => {
      console.log(`[ipc-registration] ℹ index.js 调用了 ${name}，但 ipc.js 未导出（若来自其他模块请忽略）`);
    });
  }

  if (missing.length === 0) {
    console.log('[ipc-registration] ✓ 全部 IPC 通道均已注册');
    return 0;
  }

  return STRICT ? 1 : 0;
}

const code = main();
process.exit(code);
