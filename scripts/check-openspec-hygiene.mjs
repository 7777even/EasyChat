#!/usr/bin/env node
/**
 * check-openspec-hygiene.mjs
 * 
 * 扫描 openspec 目录结构，检查：
 *   1. changes/ 下的 Change 是否四件套齐全
 *   2. tasks.md 全部勾选但未归档（视为「未归档」）
 *   3. archive/ 下的归档目录命名是否符合 YYYY-MM-DD-<name> 前缀
 * 
 * 用法：node scripts/check-openspec-hygiene.mjs [--strict]
 *   --strict 时 WARN 升级为阻塞 exit 1
 */

import { readdirSync, readSync, existsSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const STRICT = process.argv.includes('--strict');

const OPENSPEC = join(ROOT, 'openspec');
const CHANGES = join(OPENSPEC, 'changes');
const ARCHIVE = join(OPENSPEC, 'archive');

const REQUIRED_FILES = ['.openspec.yaml', 'proposal.md', 'design.md', 'tasks.md', 'spec-delta.md'];

const warnings = [];
const errors = [];
const infos = [];

function readDirSafe(p) {
  try { return readdirSync(p, { withFileTypes: true }); }
  catch (_) { return null; }
}

// ① 检查 changes/ 目录
const changeEntries = readDirSafe(CHANGES);
if (changeEntries === null) {
  infos.push('openspec/changes/ 目录不存在（尚无进行中的 L3/L4 变更）');
} else {
  const dirs = changeEntries.filter(e => e.isDirectory());
  for (const d of dirs) {
    const changePath = join(CHANGES, d.name);
    const files = readdirSync(changePath).filter(f => !f.startsWith('.'));

    // 四件套完整性
    const missing = REQUIRED_FILES.filter(f => !existsSync(join(changePath, f)));
    if (missing.length > 0 && missing.length < REQUIRED_FILES.length) {
      // 部分缺失：存量 Change 允许只有 proposal+tasks
      if (missing.length > 2 || !missing.includes('spec-delta')) {
        warnings.push(`changes/${d.name} 缺: ${missing.join(', ')}`);
      }
    }

    // 归档检查：tasks.md 全勾？
    const tasksPath = join(changePath, 'tasks.md');
    if (existsSync(tasksPath)) {
      const content = readFileSync(tasksPath, 'utf-8');
      const allChecks = [...content.matchAll(/- \[[ x]\]/g)];
      const checked = [...content.matchAll(/- \[x\]/g)];
      if (allChecks.length > 0 && checked.length === allChecks.length) {
        errors.push(`changes/${d.name} tasks.md 已全勾但未归档（全勾必归档！）`);
      }
    }
  }
}

// ② 检查 archive/ 目录
const archiveEntries = readDirSafe(ARCHIVE);
if (archiveEntries !== null) {
  const dirs = archiveEntries.filter(e => e.isDirectory());
  const datePrefix = /^\d{4}-\d{2}-\d{2}-/;
  for (const d of dirs) {
    if (!datePrefix.test(d.name)) {
      warnings.push(`archive/${d.name} 缺少 YYYY-MM-DD- 日期前缀`);
    }
  }
}

// ── 输出 ──────────────────────────────────────────────────
for (const m of infos) console.log(`[openspec-hygiene] ℹ ${m}`);
for (const m of warnings) console.log(`[openspec-hygiene] ⚠ ${m}`);
for (const m of errors) console.log(`[openspec-hygiene] × ${m}`);

const total = { infos: infos.length, warns: warnings.length, errs: errors.length };
console.log(`[openspec-hygiene] 结论：${total.errs} 错误 / ${total.warns} 警告 / ${total.infos} 信息`);

if (total.errs > 0 || (STRICT && total.warns > 0)) {
  console.log('[openspec-hygiene] × 未通过 hygiene 检查。修复后重试，或加 --no-hook-bypass 跳过（不推荐）');
  process.exit(1);
}
console.log('[openspec-hygiene] ✓ 通过');
process.exit(0);
