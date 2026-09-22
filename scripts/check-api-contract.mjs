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

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const STRICT = process.argv.includes('--strict');

// ── 后端路由扫描 ──────────────────────────────────────────
function scanBackendRoutes() {
  const { execSync } = require('node:child_process');
  // 用 grep 提取 @GetMapping / @PostMapping / @RequestMapping
  const patterns = {
    GetMapping: 'GetMapping',
    PostMapping: 'PostMapping',
    PutMapping: 'PutMapping',
    DeleteMapping: 'DeleteMapping',
    PatchMapping: 'PatchMapping',
    RequestMapping: 'RequestMapping',
  };

  const routes = [];
  try {
    // 扫描 java 注解
    for (const [method, anno] of Object.entries(patterns)) {
      try {
        const raw = execSync(
          `grep -rno "@${anno}(\\"[^\\"]*\\")" --include="*.java" easychat-java/src/main/java/com/easychat/controller/`,
          { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] }
        );
        for (const line of raw.split('\n').filter(Boolean)) {
          const m = line.match(/@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\("([^"]+)"\)/);
          if (m && !line.includes('class')) {
            const foundMethod = m[1].replace('Mapping', '').toUpperCase();
            const path = m[2];
            routes.push({
              method: foundMethod === 'REQUEST' ? 'ANY' : foundMethod,
              path: path.startsWith('/api/') ? path : `/api${path}`,
              source: line.split(':')[0],
            });
          }
        }
      } catch (_) { /* grep 空结果抛错忽略 */ }
    }
  } catch (e) {
    // fallback: 文件扫描
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
    // 粗略抓 url: "..." 或 url:'...'
    const urlRe = /url\s*[:=]\s*['"`]([^'"`]+)['"`]/g;
    const methodRe = /method\s*[:=]\s*['"`]([^'"`]+)['"`]/g;
    const calls = [];
    let m;
    while ((m = urlRe.exec(content)) !== null) {
      calls.push({ path: m[1], method: 'ANY', source: 'Api.js:' + content.substring(0, m[1].length).split('\n').length });
    }
    return calls;
  } catch (e) {
    return [];
  }
}

// ── 入口 ──────────────────────────────────────────────────
const backendRoutes = scanBackendRoutes();
const frontendCalls = scanFrontendCalls();

const warnings = [];
const infos = [];

// 找「孤儿路由」：后端有但前端没调
const fcPaths = new Set(frontendCalls.map(c => c.path));
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
  `前端调用 ${frontendCalls.length} 项；` +
  `${infos.length} 个潜在孤儿 / ${warnings.length} 个潜在漂移`;

if (warnings.length > 0 && STRICT) {
  console.log(summary);
  console.log('[api-contract] × STRICT 模式：存在潜在漂移，建议人工确认路由实现');
  process.exit(1);
} else {
  console.log(summary + '（非阻断，仅提示）');
  process.exit(0);
}
