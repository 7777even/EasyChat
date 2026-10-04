#!/usr/bin/env node
// 变异检验：故意把 ChatMessage.vue 的分发改回缺陷实现，
// 验证 chat-message-inner-dispatch.spec.js 是否真的会失败。
//
// 最重要的一条是「退回死组件」：把 ChatMessageVoice 的 import 删掉、
// 让 24 落进 `v-else-if="data.messageType != 5"` 的纯文本兜底分支 ——
// 即 2026-10-03 真实发生过的那起事故。它若不被抓住，这批测试就没有价值。
//
// 用法：node scripts/verify/mutation_chat_dispatch.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const FRONT = path.join(ROOT, 'easychat-front')

const FILES = [
  'src/renderer/src/views/chat/ChatMessage.vue'
]

let allCaught = true

/**
 * 沙箱策略：**原地临时改 + 必还原**，不复制整个工程。
 *
 * ⚠ 初版用 robocopy 把工程复制到临时目录跑 vitest，两个问题：
 *   ① robocopy 退出码语义特殊（0=无文件复制，1=复制成功，≥8 才错），
 *      被当成「复制成功=失败」而抛错，脚本第一次 resetSandbox 就崩；
 *   ② 修掉 robocopy 后基线自检立刻拦下：**沙箱没有 node_modules**，
 *      `vitest` 命令根本不存在 → 所有用例都会「红」，那不是判别力是环境缺失。
 *
 * 本项目的测试**只读被测源码、不写任何文件**，故原地改是安全的；
 * 用 try/finally 保证必还原，比「复制一份」更可靠也更快。
 */
const TARGET = path.join(FRONT, FILES[0])
let pristine = null

function savePristine () {
  if (pristine === null) pristine = fs.readFileSync(TARGET, 'utf8')
}

function restore () {
  if (pristine !== null) fs.writeFileSync(TARGET, pristine, 'utf8')
}

function runTests () {
  try {
    const out = execFileSync('cmd', ['/c', 'npm run test'], {
      cwd: FRONT, encoding: 'utf8', stdio: 'pipe', maxBuffer: 32 * 1024 * 1024
    })
    return { code: 0, out }
  } catch (e) {
    return {
      code: e.status === undefined ? null : e.status,
      out: (e.stdout || '') + (e.stderr || '')
    }
  }
}

function mutate (find, repl) {
  savePristine()
  const src = fs.readFileSync(TARGET, 'utf8')
  if (!src.includes(find)) {
    throw new Error('锚点未命中 ← ' + JSON.stringify(find.slice(0, 100)))
  }
  fs.writeFileSync(TARGET, src.replace(find, repl), 'utf8')
}

const CASES = [
  {
    // ★ 2026-10-03 真实事故：ChatMessageVoice.vue 是死组件，语音掉进纯文本分支
    name: '★ 退回死组件事故：删掉 Voice 分支，24 落进纯文本兜底',
    apply () {
      mutate('<ChatMessageVoice :data="data" v-else-if="data.messageType == 24" />', '')
    }
  },
  {
    name: '位置消息分支被删（25 掉进纯文本）',
    apply () {
      mutate('<ChatMessageLocation :data="data" v-if="data.messageType == 25" />', '')
    }
  },
  {
    name: '语音与位置类型号互换（24/25 写反）',
    apply () {
      mutate('<ChatMessageLocation :data="data" v-if="data.messageType == 25" />',
        '<ChatMessageLocation :data="data" v-if="data.messageType == 24" />')
    }
  },
  {
    name: '撤回态条件被删（14 渲染出原文）',
    apply () {
      mutate('<div class="content recalled-content" v-if="data.messageType == 14 || data.messageType == 20">',
        '<div class="content recalled-content" v-if="false">')
    }
  },
  {
    name: '管理员删除态与撤回态区分消失（20 也显示撤回文案）',
    apply () {
      mutate("<span class=\"recall-text\">{{ data.messageType == 20 ? '该消息已被管理员删除' : getRecallText() }}</span>",
        '<span class="recall-text">已撤回</span>')
    }
  }
]

console.log('===== 通话消息分发变异检验 =====\n')
console.log(`被测文件：${FILES[0]}`)
console.log(`用例数：${CASES.length}\n`)

let invalid = 0
let missed = 0
try {
  // 前置自检：未变异时必须全绿，否则「捕获」毫无意义
  savePristine()
  const base = runTests()
  if (base.code !== 0) {
    console.log('  [致命] 基线测试就没通过（exit=' + base.code + '）')
    base.out.split('\n').filter((l) => l.trim()).slice(0, 5)
      .forEach((l) => console.log('         ' + l.trim().slice(0, 130)))
    console.log('         请先修好测试再谈判别力')
    process.exitCode = 1
  } else {
    console.log('  [基线] 未变异时测试全绿（确认后续「捕获」不是环境问题）\n')
  }

  for (const c of CASES) {
    restore()
    try {
      c.apply()
    } catch (e) {
      console.log(`  [无效] ${c.name}\n          变异未生效：${e.message}`)
      invalid++
      allCaught = false
      continue
    }
    const r = runTests()
    restore()
    if (r.code === 0) {
      console.log(`  [漏网] ${c.name}\n          ⚠ 测试仍然全绿 —— 该断言没有判别力`)
      missed++
      allCaught = false
    } else {
      const failed = (r.out.match(/×/g) || []).length
      const crash = /Failed to parse|ENOENT|Cannot find module|not recognized/.test(r.out)
      console.log(`  [捕获] ${c.name}\n          exit=${r.code}，约 ${failed} 个用例转红`
        + (crash ? '（⚠ 疑似解析/环境错误而非断言失败）' : ''))
      if (crash) allCaught = false
    }
  }

  // ⚠ 汇总必须计入 [漏网] 与 [无效]，否则固定显示 N/N 时
  //   「5/5 全部捕获」与「4 捕获 1 漏网」**在输出上完全无法区分**。
  //   2026-10-04 在本脚本与 local-db 脚本上各踩一次。
  const caught = CASES.length - invalid - missed
  console.log(`\n===== 结论：${caught}/${CASES.length} 个变异被测试捕获`
    + `${missed > 0 ? `，${missed} 个漏网` : ''}`
    + `${invalid > 0 ? `，${invalid} 个用例无效（变异未生效）` : ''} =====`)
  if (allCaught) {
    console.log('✓ 测试有判别力')
  } else {
    console.log('✗ 存在无判别力的断言或无效用例')
    process.exitCode = 1
  }
} finally {
  // 必还原：即使中途抛错也不能把变异留在工作区
  restore()
}