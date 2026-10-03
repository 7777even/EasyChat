#!/usr/bin/env node
// 变异检验：故意破坏 Flyway 迁移自动化的六条不变量，验证 verify_migration_flyway.mjs
// 是否真的会失败。
//
// 目的：证明门禁**有判别力**，而不是「脚本存在所以有门禁」（AGENTS §2.1 第 1 条）。
// 这六条里至少三条是典型的「配错了不报错」：
//   · 版本号被顺手改回随 parent → 本机 5.7 起不来（直到有人本地启动才发现）
//   · baseline-version 忘记同步 → 新迁移在存量库被当作已应用而**静默跳过**
//   · 迁移文件混入 DELIMITER → Flyway 解析器过不了，自动执行路径整体失败
// 三者都不会在 CI 上留下任何痕迹，故必须用变异证明门禁真的会红。
//
// 沙箱而非改真文件：门禁的 ROOT 由脚本自身位置解析，把被读文件一起复制到
// 临时目录，它就只读沙箱副本。无需脏工作区守卫。
//
// ⚠ 维护纪律：门禁若新增读取的仓库内文件，必须同步补进下面的 FILES，
//   否则沙箱缺文件 → 门禁在沙箱里恒红 → 后续「全部捕获」全是假通过。
//
// 用法：node scripts/verify/mutation_migration_flyway.cjs
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..', '..')
const GATE_REL = 'scripts/verify/verify_migration_flyway.mjs'

// 门禁会读取的仓库内文件（缺一即沙箱恒红）
const FILES = [
  'easychat-sql-placeholder', // 占位，下面用真实文件替换
  'easychat-java/pom.xml',
  'easychat-java/src/main/resources/application.properties',
  'docker-compose.yml',
  '.github/workflows/ci.yml',
  'AGENTS.md',
  'scripts/migrate/preflight-baseline-check.mjs'
].filter((f) => f !== 'easychat-sql-placeholder')

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ec-migflyway-'))
let allCaught = true
let skipped = 0

function resetSandbox () {
  fs.rmSync(sandbox, { recursive: true, force: true })
  fs.mkdirSync(sandbox, { recursive: true })
  for (const rel of FILES) {
    const dst = path.join(sandbox, rel)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.copyFileSync(path.join(ROOT, rel), dst)
  }
  // 迁移文件：整目录复制（含编号，baseline-version 断言依赖它）
  for (const f of fs.readdirSync(ROOT)) {
    if (/^easychat-migration-\d{3}-.*\.sql$/.test(f)) {
      fs.copyFileSync(path.join(ROOT, f), path.join(sandbox, f))
    }
  }
  const gateDst = path.join(sandbox, GATE_REL)
  fs.mkdirSync(path.dirname(gateDst), { recursive: true })
  fs.copyFileSync(path.join(ROOT, GATE_REL), gateDst)
}

function runGate () {
  try {
    const out = execFileSync('node', [path.join(sandbox, GATE_REL)], {
      cwd: sandbox, encoding: 'utf8', stdio: 'pipe', maxBuffer: 32 * 1024 * 1024
    })
    return { code: 0, out }
  } catch (e) {
    return {
      code: e.status === undefined ? null : e.status,
      out: (e.stdout || '') + (e.stderr || '')
    }
  }
}

/** 对沙箱副本做字符串替换；锚点未命中则抛错（避免「变异没生效」被误判成「门禁抓到了」） */
/**
 * 锚点匹配对换行不敏感。
 *
 * 踩过的坑：一开始这里直接 `src.includes(find)`，而锚点里写的是 `\n`。
 * 但仓库里的 pom.xml / docker-compose.yml / .sql **全是 CRLF**（Windows 上编辑），
 * 于是六个用例的锚点全部落空、被判成「变异未生效」。
 * 那看起来像是「门禁抓到了」——实际是变异根本没发生。
 * 用换行无关的正则，把「锚点没命中」从静默通过变成显式抛错。
 */
function mutate (rel, find, repl) {
  const p = path.join(sandbox, rel)
  const src = fs.readFileSync(p, 'utf8')
  const re = new RegExp(escapeRe(find).replace(/\\n/g, '\\r?\\n'))
  if (!re.test(src)) {
    throw new Error('锚点未命中：' + rel + ' ← ' + JSON.stringify(find.slice(0, 90)))
  }
  fs.writeFileSync(p, src.replace(re, repl.replace(/\n/g, '\r\n')), 'utf8')
}

/**
 * ⚠ 这里刻意**不**用「转义后把 \n 换成 \r?\n」那套写法：
 *   escapeRe 会先把换行转成反斜杠 + n 的**字面两字符**，随后的
 *   `.replace(/\\n/g, ...)` 匹配不到它们（匹配的是单个反斜杠 + 字符类 n），
 *   正则里留下的仍是裸 \n —— 与 CRLF 文件永远匹配不上。
 *   实测：test=false，而文件里明明是 CRLF。纯正则方案在此**不可用**。
 *
 * 正确做法：先用一条 CRLF/LF 通吃的正则探测锚点是否存在，
 * 再用「归一化换行后的文本」做真正的替换。
 */
function toLf (s) {
  return s.replace(/\r\n/g, '\n')
}

function mutate (rel, find, repl) {
  const p = path.join(sandbox, rel)
  const src = fs.readFileSync(p, 'utf8')
  const srcLf = toLf(src)
  if (!srcLf.includes(find)) {
    throw new Error('锚点未命中：' + rel + ' ← ' + JSON.stringify(find.slice(0, 90)))
  }
  // 全程在归一化（LF）文本上做替换，再按原文的换行风格写回，
  // 避免 CRLF 与 LF 混用产生半个新行 —— 那会让后续变异锚点更难命中。
  const mutated = srcLf.replace(find, repl)
  const eol = src.includes('\r\n') ? '\r\n' : '\n'
  fs.writeFileSync(p, eol === '\r\n' ? mutated.replace(/\n/g, '\r\n') : mutated, 'utf8')
}

function mutateAnyMigrations (find, repl) {
  const hits = fs.readdirSync(sandbox)
    .filter((f) => /^easychat-migration-\d{3}-.*\.sql$/.test(f))
    .map((f) => path.join(sandbox, f))
    .filter((p) => fs.readFileSync(p, 'utf8').includes(find))
  if (!hits.length) throw new Error('迁移文件里没有锚点：' + JSON.stringify(find))
  // 只改第一个命中，模拟「有人在某一篇里加了 DELIMITER」
  const p = hits[0]
  fs.writeFileSync(p, fs.readFileSync(p, 'utf8').replace(find, repl), 'utf8')
  return path.basename(p)
}

const CASES = [
  {
    name: 'flyway 版本被改回 8.0.4（随 parent）→ Community 不支持 MySQL 5.7',
    apply () {
      mutate('easychat-java/pom.xml', '<version>7.15.0</version>\n        </dependency>',
        '<version>8.0.4</version>\n        </dependency>')
    }
  },
  {
    name: 'flyway 版本被删掉（改为随 parent 漂移）',
    apply () {
      mutate('easychat-java/pom.xml',
        '<artifactId>flyway-core</artifactId>\n            <version>7.15.0</version>',
        '<artifactId>flyway-core</artifactId>')
    }
  },
  {
    name: 'baseline-version 忘记随新增迁移同步（12 → 11）',
    apply () {
      mutate('easychat-java/src/main/resources/application.properties',
        'SPRING_FLYWAY_BASELINE_VERSION:12', 'SPRING_FLYWAY_BASELINE_VERSION:11')
    }
  },
  {
    name: '迁移文件混入 DELIMITER（mysql 客户端专有指令）',
    apply () {
      mutateAnyMigrations('SET @ddl = (SELECT IF',
        'DELIMITER $$\nSET @ddl = (SELECT IF')
    }
  },
  {
    name: '迁移文件混入 CREATE PROCEDURE',
    apply () {
      mutateAnyMigrations('SET @ddl = (SELECT IF',
        'CREATE PROCEDURE `ec_x`() BEGIN SELECT 1; END;\nSET @ddl = (SELECT IF')
    }
  },
  {
    name: 'sql-migration-prefix 被删（Flyway 认不出既有命名）',
    apply () {
      const rel = 'easychat-java/src/main/resources/application.properties'
      const p = path.join(sandbox, rel)
      fs.writeFileSync(p, fs.readFileSync(p, 'utf8')
        .replace(/^spring\.flyway\.sql-migration-prefix=.*$/m, ''), 'utf8')
    }
  },
  {
    name: '迁移文件不再打进 jar（<resources> 被删）',
    apply () {
      const rel = 'easychat-java/pom.xml'
      const p = path.join(sandbox, rel)
      const src = fs.readFileSync(p, 'utf8')
      const stripped = src.replace(/<targetPath>db\/migration<\/targetPath>/, '<targetPath>db/nowhere</targetPath>')
      if (stripped === src) throw new Error('锚点未命中：targetPath')
      fs.writeFileSync(p, stripped, 'utf8')
    }
  },
  {
    name: 'validate-on-migrate 被关掉（已应用迁移被改动也不再报错）',
    apply () {
      mutate('easychat-java/src/main/resources/application.properties',
        'spring.flyway.validate-on-migrate=true', 'spring.flyway.validate-on-migrate=false')
    }
  },
  {
    name: 'compose 里 backend 不再依赖 migrate 闸门（fail-closed 失效）',
    apply () {
      mutate('docker-compose.yml',
        '      migrate:\n        condition: service_completed_successfully\n', '')
    }
  },
  {
    // ⚠ 用「把所有非 0 退出改成 0」而不是「只改一处」。
    //   初版只替换第一处 process.exit(1)，结果脚本里「放行分支」的
    //   process.exit(0) 之前也有 exit(1)… 实际上第一处 exit(1) 就在
    //   库为空的放行分支之前，容易出现「改了一处另一处仍红」的假捕获。
    //   这里改成全局把所有 exit(1) 变 exit(0)，逼门禁必须靠
    //   「拒绝分支本身消失」来抓，而不是靠残留的另一个 exit(1)。
    name: '前置校验改成永不失败（全局把所有非 0 退出变成 0）',
    apply () {
      const rel = 'scripts/migrate/preflight-baseline-check.mjs'
      const p = path.join(sandbox, rel)
      const src = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n')
      if (!src.includes('process.exit(1)')) throw new Error('脚本里已无 process.exit(1)')
      fs.writeFileSync(p, src.split('process.exit(1)').join('process.exit(0)'), 'utf8')
    }
  },
  // ⚠ 刻意**没有**「拒绝分支条件反转」这个用例。
  //   它确实漏网了：门禁对该脚本的断言全是**读源码找字样**，
  //   把放行条件 `if (!missing) {...}` 反写成 `if (missing) {...}` 后，
  //   「拒绝分支」「非 0 退出」「读 env」「比对基线」四条断言全部照样通过——
  //   因为这些字面量一个都没变。
  //   这不是门禁写错了，而是**静态断言的固有上限**：
  //   「条件分支的方向」无法从文本推出。
  //   该行为由真机验证覆盖（见 engineering/qa/2026-10-04-flyway-migration-automation.md
  //   的 T2.3 三路径：0 漂移放行 / 有漂移拒绝 / env 声明后放行）。
  //   在此登记为**已知不可静态检测项**，不假装门禁能覆盖它。
  {
    name: '前置校验不再读 env 里的 baseline-version（运维声明对闸门失效）',
    apply () {
      mutate('scripts/migrate/preflight-baseline-check.mjs',
        'const env = process.env.SPRING_FLYWAY_BASELINE_VERSION',
        'const env = undefined // 故意忽略环境变量')
    }
  },
  {
    name: '中间某篇迁移被删（007 与 009 之间出现缺口）',
    apply () {
      const f = fs.readdirSync(sandbox)
        .filter((x) => /^easychat-migration-008-.*\.sql$/.test(x))[0]
      if (!f) throw new Error('找不到 008 迁移文件')
      fs.rmSync(path.join(sandbox, f))
    }
  },
  {
    name: '首篇迁移被删（编号不再从 001 起）',
    apply () {
      const f = fs.readdirSync(sandbox)
        .filter((x) => /^easychat-migration-001-.*\.sql$/.test(x))[0]
      if (!f) throw new Error('找不到 001 迁移文件')
      fs.rmSync(path.join(sandbox, f))
    }
  },
  {
    name: 'backend 与 migrate 的 baseline-version 不再同源（闸门校验的值≠后端执行的值）',
    apply () {
      mutate('docker-compose.yml',
        '      SPRING_REDIS_PORT: 6379\n      # ⚠ 必须与 migrate 闸门用**同一个** baseline-version，否则闸门校验的是一个值、\n' +
        '      #   Flyway 实际执行的是另一个值 —— 那正是本变更要防的静默漂移。\n' +
        '      SPRING_FLYWAY_BASELINE_VERSION: ${SPRING_FLYWAY_BASELINE_VERSION:-12}',
        '      SPRING_REDIS_PORT: 6379')
    }
  }
]

console.log('===== Flyway 门禁变异检验 =====\n')
console.log(`沙箱：${sandbox}`)
console.log(`用例数：${CASES.length}\n`)

for (const c of CASES) {
  resetSandbox()
  try {
    c.apply()
  } catch (e) {
    console.log(`  [ERROR] ${c.name}\n          变异未生效：${e.message}`)
    allCaught = false
    continue
  }
  const r = runGate()
  if (r.code !== 0) {
    const failed = (r.out.match(/\[FAIL\]/g) || []).length
    const first = (r.out.match(/\[FAIL\][^\r\n]*/) || [''])[0].replace('[FAIL] ', '').trim()
    console.log(`  [捕获] ${c.name}\n          exit=${r.code}，${failed} 项失败，首项：${first}`)
  } else {
    console.log(`  [漏网] ${c.name}\n          ⚠ 门禁仍然 exit=0 —— 该断言没有判别力`)
    allCaught = false
  }
}

fs.rmSync(sandbox, { recursive: true, force: true })
console.log(`\n===== 结论：${CASES.length - skipped}/${CASES.length} 个变异全部被门禁捕获 =====`)
if (allCaught) {
  console.log('✓ 门禁有判别力')
  process.exit(0)
}
console.log('✗ 存在无判别力的断言')
process.exit(1)