#!/usr/bin/env node
/**
 * pre-commit-guard.mjs
 * 
 * 检查暂存区文件，命中黑名单即拒绝提交。
 * 用法：node scripts/pre-commit-guard.mjs
 * 建议作为 git hook（pre-commit）执行。
 * 
 * 放行 openspec/ 下的 QA 证据附件（png / snap.txt）。
 */

import { execSync } from 'node:child_process';

// ── 黑名单（正则 / 路径段匹配） ─────────────────────────────
const BLOCKLIST = [
  /[\\/]target[\\/]/,          // Java 构建产物
  /[\\/]build[\\/]/,           // 前端 / Electron 构建产物
  /[\\/]out[\\/]/,
  /[\\/]dist[\\/]/,
  /[\\/]node_modules[\\/]/,
  /\.asar$/,                    // Electron asar 包
  /\.exe$/,
  /\.dmg$/,
  /\.msi$/,
  /\.deb$/,
  /\.AppImage$/,
  /\.log$/,
  /\.tmp$/,
  /~$/,                        // 编辑器备份
  /\.bak$/,
  /\.swp$/,                    // vim
  /\.swo$/,
  /-out\.txt$/,                // tsc-out mvn-out 等
  /\.pyc$/,
  /[\\/]\.DS_Store$/,
  /[\\/]Thumbs\.db$/,
];

// ── 白名单（命中即放行，即使命中黑名单） ───────────────────
const ALLOWLIST = [
  /[\\/]openspec[\\/].*\.(png|jpg|jpeg|webp|snap\.txt)$/, // QA 证据附件
];

// ── 实现 ───────────────────────────────────────────────────
function getStagedFiles() {
  try {
    const out = execSync('git diff --cached --name-only --diff-filter=ACM', {
      encoding: 'utf-8',
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    return out.split('\n').map(s => s.trim()).filter(Boolean);
  } catch (e) {
    console.error('[pre-commit-guard] × git diff 失败：' + (e.stderr || e.message).trim());
    process.exit(2);
  }
}

const staged = getStagedFiles();
if (staged.length === 0) {
  console.log('[pre-commit-guard] ✓ 暂存区无文件变更');
  process.exit(0);
}

const blocked = [];
for (const f of staged) {
  // 白名单优先
  if (ALLOWLIST.some(re => re.test(f))) continue;
  // 黑名单命中
  const hit = BLOCKLIST.find(re => re.test(f));
  if (hit) blocked.push({ file: f, rule: hit.source });
}

if (blocked.length > 0) {
  console.error(`[pre-commit-guard] × 暂存区暂不允许提交 ${blocked.length} 个文件（命中黑名单）：`);
  for (const b of blocked) {
    console.error(`  - ${b.file}  (规则: ${b.rule})`);
  }
  console.error('[pre-commit-guard]   如确需提交，请移入 .gitignore 或用 git reset HEAD <file> 移出暂存区');
  console.error('[pre-commit-guard]   openspec/ 下的 QA 证据附件（png/snap.txt）不在黑名单');
  process.exit(1);
}

console.log(`[pre-commit-guard] ✓ 暂存区 ${staged.length} 项通过检查，无黑名单命中`);
process.exit(0);
