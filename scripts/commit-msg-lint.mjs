#!/usr/bin/env node
/**
 * commit-msg-lint.mjs
 * 
 * 校验 git commit message 是否符合 EasyChat 提交规范。
 * 用法：node scripts/commit-msg-lint.mjs <commit-msg-file>
 * 建议作为 git hook（commit-msg）执行。
 * 
 * 规范（对齐 AGENTS.md §6.3）：
 *   type(scope): 描述   （仅一行，禁止 body）
 *   type ∈ 允许枚举
 *   scope ∈ 允许枚举（无括号时视为 type-only 的跨域提交）
 *   描述至少含 1 个中文字符
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const __root = join(__dirname, '..');

// ── type 枚举 ──────────────────────────────────────────────
const ALLOWED_TYPES = [
  'feat', 'fix', 'refactor', 'perf',
  'docs', 'style', 'test',
  'chore', 'ci', 'build', 'revert',
];

// ── scope 枚举（对齐 AGENTS.md §6.3 + 前端 §7） ─────────────
const ALLOWED_SCOPES = [
  // 后端业务域
  'auth', 'user', 'chat', 'group', 'contact', 'moment',
  'file', 'ws', 'admin', 'common', 'config',
  // 前端域（Electron 专属）
  'main', 'preload', 'renderer', 'ipc', 'ui', 'req', 'db',
  // 跨域/横向
  'build', 'contract', 'openspec',
  // 文档与杂项
  'docs', 'chore',
];

const SCOPE_PATTERN = ALLOWED_SCOPES.join('|');
const TYPE_PATTERN = ALLOWED_TYPES.join('|');

// ── Header 正则：type(scope): 描述  或  type: 描述 ─────────
const HEADER_RE = new RegExp(
  `^(${TYPE_PATTERN})(?:\\((${SCOPE_PATTERN})\\))?:\\s+(.+)$`
);

// ── 逻辑 ──────────────────────────────────────────────────
function lint(rawMessage) {
  // 剥掉 git 以 # 开头的注释行
  const linesRaw = rawMessage.split('\n').filter(l => !l.startsWith('#'));
  const nonEmpty = linesRaw.find(l => l.trim().length > 0);

  if (!nonEmpty) {
    return { ok: false, error: '提交信息为空' };
  }

  const header = nonEmpty.trim();

  // git merge / rebase 等自动提交放行
  if (header.startsWith('Merge ') || header.startsWith('Rebase ')) {
    return { ok: true, note: 'auto-skip: auto-generated message' };
  }

  const m = header.match(HEADER_RE);
  if (!m) {
    // 细化错误
    const typeOnly = header.match(/^(\w+)(?:\(([^)]*))?\:/);
    if (!typeOnly) {
      return {
        ok: false,
        error: `提交 header 不匹配 "type(scope): 描述" 格式\n  → 当前: "${header}"`,
      };
    }
    const gotType = typeOnly[1];
    const gotScope = typeOnly[2];
    if (!ALLOWED_TYPES.includes(gotType)) {
      return {
        ok: false,
        error: `type "${gotType}" 不在枚举内\n  → 允许: ${ALLOWED_TYPES.join(', ')}`,
      };
    }
    if (gotScope && !ALLOWED_SCOPES.includes(gotScope)) {
      return {
        ok: false,
        error: `scope "${gotScope}" 不在枚举内\n  → 允许: ${ALLOWED_SCOPES.join(', ')}`,
      };
    }
    return {
      ok: false,
      error: `提交 header 格式有误\n  → 当前: "${header}"\n  → 期望: "type(scope): 描述" 或 "type: 描述"`,
    };
  }

  const [, type, scope, subject] = m;

  // 至少一个中文字符
  if (!/[\u4e00-\u9fff]/.test(subject)) {
    return {
      ok: false,
      error: `描述需包含中文字符\n  → 当前: "${subject}"`,
    };
  }

  // 单行：检查原始提交是否包含 body（空行后还有内容）
  const firstEmpty = rawMessage.split('\n').findIndex((l, i) => i > 0 && l.trim() === '');
  if (firstEmpty > 0) {
    const bodyAfter = rawMessage.split('\n').slice(firstEmpty + 1).join('\n').trim();
    // git 注释之后的空行不是真正的 body；剥掉注释段再判断
    const rawNoComment = rawMessage.split('\n').filter(l => !l.startsWith('#')).join('\n');
    const lines2 = rawNoComment.split('\n');
    const blankIdx = lines2.findIndex((l, i) => i > 0 && l.trim() === '');
    if (blankIdx > 0 && lines2.slice(blankIdx + 1).join('\n').trim().length > 0) {
      return {
        ok: false,
        error: '禁止提交 body / 分段描述\n  → 如有说明请写入代码注释、docs/ 或 openspec',
      };
    }
  }

  return { ok: true, type, scope: scope ?? null, subject };
}

// ── CLI 入口 ──────────────────────────────────────────────
const targetFile = process.argv[2];
if (!targetFile) {
  console.error('用法: node scripts/commit-msg-lint.mjs <commit-msg-file>');
  process.exit(2);
}

let content;
try {
  content = readFileSync(targetFile, 'utf-8');
} catch (e) {
  console.error(`× 无法读取 ${targetFile}: ${e.message}`);
  process.exit(2);
}

const result = lint(content);
if (result.ok) {
  const body = `[commit-msg-lint] ✓ ${result.type}${result.scope ? `(${result.scope})` : ''}: ${result.subject}`;
  if (result.note) console.log(`[commit-msg-lint] ↳ ${result.note}`);
  console.log(body);
  process.exit(0);
} else {
  console.error(`[commit-msg-lint] × 提交被拒绝：${result.error}`);
  console.error('[commit-ms   g-lint]   允许 type : ' + ALLOWED_TYPES.join(', '));
  console.error('[commit-msg-lint]   允许 scope: ' + ALLOWED_SCOPES.join(', '));
  process.exit(1);
}
