#!/usr/bin/env node
/**
 * check-api-contract.mjs
 * 
 * 扫描后端 Controller 与前端的 request.js 调用，对拍找出：
 *  - 后端有路由但前端没有调用的「孤儿路由」（INFO 级别，不阻断）
 *  - 前端调用了但后端未找到对应路由的「潜在漂移」（WARN 级别）
 * 
 * 注意：这是「结构级」守门，不做 schema 深度 diff（那是 CI + OpenAPI 做的事）。
 *   当前无 OpenAPI 契约文件，先做路径 + method 的粗粒度对拍。
 * 
 * 用法：node scripts/check-api-contract.mjs [--strict]
 *   --strict 时 WARN 视为 exit 1
 */

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const STRICT = process.argv.includes('--strict');

// ── 后端路由扫描（纯 Node，不依赖 grep 子进程） ──────────
function scanBackendRoutes() {
  const ctrlDir = join(ROOT, 'easychat-java/src/main/java/com/easychat/controller');
  const annoRe = /@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\)/g;

  const routes = [];
  let files = [];
  try {
    files = readdirSync(ctrlDir).filter(f => f.endsWith('.java'));
  } catch (_) {
    return routes;
  }
  for (const f of files) {
    const content = readFileSync(join(ctrlDir, f), 'utf-8');
    const classIdx = content.search(/public\s+class\s/);
    // 类级前缀：class 声明之前的第一个 @RequestMapping("...")
    let prefix = '';
    const before = classIdx > 0 ? content.slice(0, classIdx) : '';
    const pm = before.match(/@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\)/);
    if (pm) prefix = pm[1];
    // 方法级注解（跳过类级那条）
    let m;
    annoRe.lastIndex = 0;
    while ((m = annoRe.exec(content)) !== null) {
      if (classIdx > 0 && m.index < classIdx) continue; // 类级声明，非路由
      const method = m[1] === 'Request' ? 'ANY' : m[1].toUpperCase();
      const raw = m[2];
      let path = (prefix.replace(/\/$/, '') + '/' + raw.replace(/^\//, '')).replace(/\/{2,}/g, '/');
      if (!path.startsWith('/')) path = '/' + path;
      const lineNo = content.slice(0, m.index).split('\n').length;
      routes.push({ method, path, source: `${f}:${lineNo}` });
    }
  }
  return routes;
}

// ── 前端调用扫描 ──────────────────────────────────────────
function scanFrontendCalls() {
  try {
    const content = readFileSync(
      join(ROOT, 'easychat-front/src/renderer/src/utils/Api.js'),
      'utf-8'
    );
    // Api.js 为对象字面量：key: "/path"（以 / 开头的才是路由，跳过 http 域名等）
    const urlRe = /(^|\n)\s*[A-Za-z_$][\w$]*\s*:\s*['"](\/[^'"]*)['"]/g;
    const calls = [];
    let m;
    while ((m = urlRe.exec(content)) !== null) {
      const path = m[2];
      const lineNo = content.slice(0, m.index).split('\n').length;
      calls.push({ path, method: 'ANY', source: 'Api.js:' + lineNo });
    }
    return calls;
  } catch (e) {
    return [];
  }
}

// ── 主进程（Electron main）调用扫描：补盲区，避免孤儿路由误报 ──
function walkJs(dir, acc = []) {
  let entries = [];
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch (_) {
    return acc;
  }
  for (const e of entries) {
    const p = join(dir, e.name);
    if (e.isDirectory()) walkJs(p, acc);
    else if (e.isFile() && e.name.endsWith('.js')) acc.push(p);
  }
  return acc;
}

// 主进程直接拼 URL 调后端（如 src/main/file.js 的 /api/chat/downloadFile、
// /api/update/download），不走 Api.js，门禁需认识这类调用方。
function scanMainProcessCalls() {
  const calls = [];
  let files = [];
  try {
    files = walkJs(join(ROOT, 'easychat-front/src/main'));
  } catch (_) {
    return calls;
  }
  const urlRe = /['"`][^'"`]*\/api\/([\w/{}:.\-]+)['"`]/g;
  for (const f of files) {
    let content;
    try {
      content = readFileSync(f, 'utf-8');
    } catch (_) {
      continue;
    }
    let m;
    while ((m = urlRe.exec(content)) !== null) {
      const path = '/' + m[1];
      const lineNo = content.slice(0, m.index).split('\n').length;
      calls.push({ path, method: 'ANY', source: `${f}:${lineNo}` });
    }
  }
  return calls;
}

// ── 入口 ──────────────────────────────────────────────────
const backendRoutes = scanBackendRoutes();
const frontendCalls = scanFrontendCalls();
const mainCalls = scanMainProcessCalls();

const warnings = [];
const infos = [];

// 找「孤儿路由」：后端有但前端没调
const fcPaths = new Set([...frontendCalls, ...mainCalls].map(c => c.path));
for (const r of backendRoutes) {
  const matched = [...fcPaths].some(fp => fp === r.path || fp.startsWith(r.path + '/'));
  if (!matched) {
    infos.push(`后端路由 ${r.method} ${r.path} 在 Api.js 中未找到调用方 (${r.source})`);
  }
}

// 找「潜在漂移」：前端有但后端没声明
const brPaths = backendRoutes.map(r => r.path);
for (const c of frontendCalls) {
  const matched = brPaths.some(bp => bp === c.path || c.path.startsWith(bp + '/'));
  if (!matched && c.path.startsWith('/')) {
    warnings.push(`前端调用 ${c.method} ${c.path} 未在已知后端路由表找到匹配 (${c.source})`);
  }
}

// ── 输出 ──────────────────────────────────────────────────
const log = (lvl, msg) => {
  const tag = lvl === 'WARN' ? '⚠' : 'ℹ';
  console.log(`[api-contract] ${tag} ${msg}`);
};

for (const m of infos) log('INFO', m);
for (const m of warnings) log('WARN', m);

const summary =
  `[api-contract] 后端路由 ${backendRoutes.length} 项；` +
  `前端调用 ${frontendCalls.length} 项；主进程调用 ${mainCalls.length} 项；` +
  `${infos.length} 个潜在孤儿 / ${warnings.length} 个潜在漂移`;

if (warnings.length > 0 && STRICT) {
  console.log(summary);
  console.log('[api-contract] × STRICT 模式：存在潜在漂移，建议人工确认路由实现');
  process.exit(1);
} else {
  console.log(summary + '（非阻断，仅提示）');
  process.exit(0);
}
