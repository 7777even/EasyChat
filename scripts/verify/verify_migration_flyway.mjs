#!/usr/bin/env node
/**
 * verify_migration_flyway.mjs —— Flyway 迁移自动化契约验证
 *
 * 背景（openspec/changes/2026-10-04-flyway-migration-automation）：
 *   迁移执行链路长期缺最后一环——verify_schema_drift.mjs 能「发现」改了基线但没迁移，
 *   却没有任何东西**执行**迁移。本变更引入 Flyway 把「执行 + 记账」自动化。
 *
 *   本门禁守的**不是「Flyway 配好了」**，而是三条容易静默失效的不变量：
 *   ① 版本号钉在 7.15.0 —— 随 parent 走的 8.0.4 **不支持 MySQL 5.7**（实测报错），
 *      而本机开发库就是 5.7；一旦有人「顺手」删掉 <version>，开发者本地会直接起不来。
 *   ② `baseline-version` 必须等于仓库内最大迁移编号 —— 忘记同步会让新增迁移
 *      在存量库被**静默跳过**（本项目已因「只改基线忘迁移」吃过两次 500）。
 *   ③ 迁移文件不得含 `DELIMITER` / `CREATE PROCEDURE` —— 那是 mysql 客户端指令，
 *      Flyway 的 MySQL 解析器过不了，会让自动执行路径直接失败。
 *
 * 用法：node scripts/verify/verify_migration_flyway.mjs
 * 退出码：0 全通过 / 1 有失败项
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (p) => readFileSync(resolve(ROOT, p), 'utf8')
const has = (p) => existsSync(resolve(ROOT, p))

const results = []
const check = (name, cond, detail = '') => {
  results.push([name, !!cond])
  console.log(`   [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' | ' + detail : ''}`)
}

const POM = 'easychat-java/pom.xml'
const BASELINE_PROPS = 'easychat-java/src/main/resources/application.properties'
const COMPOSE = 'docker-compose.yml'
const PREFLIGHT = 'scripts/migrate/preflight-baseline-check.mjs'

console.log('===== Flyway 迁移自动化契约验证 =====\n')

// ── 1. 依赖：版本必须钉住 ────────────────────────────────────
console.log('=== 1. flyway-core 依赖 ===')
const pom = read(POM)
check('pom.xml 引入 flyway-core', /<artifactId>flyway-core<\/artifactId>/.test(pom))
const flywayDep = pom.match(/<artifactId>flyway-core<\/artifactId>(\s*<version>([^<]+)<\/version>)?/)
check('flyway-core 显式钉了版本', !!flywayDep && !!flywayDep[2],
  flywayDep && flywayDep[2] ? flywayDep[2] : '未钉版本（会随 parent 漂到不支持 MySQL 5.7 的版本）')
// 钉的版本必须仍在 Community 支持 MySQL 5.7 的范围内。Flyway 8.0 起 CE 移除 5.7 支持。
if (flywayDep && flywayDep[2]) {
  const major = parseInt(flywayDep[2].split('.')[0], 10)
  check(`flyway-core ${flywayDep[2]} 支持 MySQL 5.7（major < 8）`, major < 8,
    major >= 8 ? 'Flyway 8.0+ 的 Community 版不再支持 MySQL 5.7，本机开发库正是 5.7' : '')
}
// 钉版本是本仓唯一允许的例外，必须留有据可查的注释，避免后人「顺手改回」
check('pom 中写明了钉版本的原因', /MySQL 5\.7 is no longer supported|no longer supported by Flyway Community/.test(pom))

// 理由注释不能只是一句「某某不支持」——它得指向**本项目自己的库版本**，
// 否则后人看到「MySQL 5.7」仍可能以为那是理论问题，照样把版本改回去。
check('pom 钉版本注释点明了本项目本机库就是 MySQL 5.7',
  /本机开发库就是 MySQL 5\.7|本机开发库.*5\.7|5\.7.*开发者本地/.test(pom),
  '缺这条，后人会以为 5.7 与本项目无关')

// ── 2. 迁移文件进 jar ───────────────────────────────────────
console.log('\n=== 2. 迁移文件打包 ===')
check('pom 配置了 <resources> 把 easychat-migration-*.sql 复制到 db/migration',
  /easychat-migration-\*\.sql/.test(pom) && /<targetPath>db\/migration<\/targetPath>/.test(pom))
check('资源目录指向仓库根（${project.basedir}/..）',
  /<directory>\$\{project\.basedir\}\/\.\.<\/directory>/.test(pom))
const props = read(BASELINE_PROPS)
check('locations 指向 classpath:db/migration', /spring\.flyway\.locations=classpath:db\/migration/.test(props))
check('声明了 sql-migration-prefix（适配既有命名，零改名）',
  /spring\.flyway\.sql-migration-prefix=easychat-migration-/.test(props))
check('声明了 sql-migration-separator', /spring\.flyway\.sql-migration-separator=-/.test(props))
check('validate-on-migrate 显式开启（已应用迁移被改动须启动失败）',
  /spring\.flyway\.validate-on-migrate=true/.test(props))

// ── 3. 迁移集与 baseline-version 同步（ADR-004 核心） ────────
console.log('\n=== 3. baseline-version 与迁移集同步 ===')
const migFiles = readdirSync(ROOT).filter((f) => /^easychat-migration-\d{3}-.*\.sql$/.test(f))
const versions = migFiles.map((f) => Number(f.match(/^easychat-migration-(\d{3})-/)[1])).sort((a, b) => a - b)
const maxVer = versions[versions.length - 1]
const bvMatch = props.match(/SPRING_FLYWAY_BASELINE_VERSION:(\d+)/)
check('baseline-version 有配置', !!bvMatch, bvMatch ? bvMatch[1] : '')
check(`baseline-version 等于仓库内最大迁移编号（${maxVer}）`,
  !!bvMatch && Number(bvMatch[1]) === maxVer,
  `配置 ${bvMatch ? bvMatch[1] : '?'} vs 实际最大 ${maxVer}；不等意味着新增迁移会在存量库被静默跳过`)
// 编号连续性（003 是有意保留的退役占位，仍算连续）
const gaps = []
for (let i = 1; i < versions.length; i++) {
  if (versions[i] !== versions[i - 1] + 1) gaps.push(`${versions[i - 1]}→${versions[i]}`)
}
check('迁移编号连续无缺口', gaps.length === 0, gaps.join(', '))
// ⚠ 只查「相邻两号连续」不够：末尾少一个文件（012 被删）时，
//   剩下的 001~011 内部依然「连续」，断言照样绿——而 baseline-version
//   若随之改成 11，门禁第 4 节也全过，唯一的变化是最新的迁移凭空消失。
// 故必须硬钉「最大编号 == baseline-version」这条等价约束的另一半：
// 断言最大编号本身与配置一致（上面已查）之外，再查起点必须是 001。
check('迁移编号从 001 起（末尾被删不会表现为「内部连续」）',
  versions.length > 0 && versions[0] === 1,
  versions.length ? `实际最小编号 ${versions[0]}` : '未找到迁移文件')
check('baseline-on-migrate 显式开启', /spring\.flyway\.baseline-on-migrate=/.test(props))

// ── 4. 迁移文件不得含客户端专有语法 ───────────────────────────
console.log('\n=== 4. 迁移脚本可被 Flyway 解析 ===')
for (const f of migFiles) {
  const c = read(f)
  check(`${f} 不含 DELIMITER（客户端专有指令，Flyway 解析器不支持）`, !/^\s*DELIMITER\b/im.test(c))
  check(`${f} 不建存储过程/函数/触发器`, !/CREATE\s+(PROCEDURE|FUNCTION|TRIGGER|EVENT)\b/i.test(c))
}

// ── 5. 幂等性声明与现实一致 ─────────────────────────────────
console.log('\n=== 5. 幂等性：文档不得与现实矛盾 ===')
// 项目规范要求迁移幂等，但实测 6/12 迁移并非幂等。门禁此处不做强制（改造成本高），
// 只断言「不存在'全部迁移都幂等'这种错误声明」，避免后来人误信。
const agentsDoc = has('AGENTS.md') ? read('AGENTS.md') : ''
check('AGENTS 未声称「全部迁移脚本幂等」',
  !/全部迁移脚本[^。]*幂等|所有迁移[^。]*均幂等/.test(agentsDoc))

// ── 6. 前置校验（fail-closed） ────────────────────────────────
console.log('\n=== 6. 存量库纳管前置校验 ===')
check('preflight-baseline-check.mjs 存在', has(PREFLIGHT))
if (has(PREFLIGHT)) {
  const pf = read(PREFLIGHT)
  // ⚠ 下面三条都是**读源码找字样**，属静态断言，有固有上限：
  //   「条件分支的方向」无法从文本推出。若有人把放行条件
  //   `if (!missing) { 放行 }` 反写成 `if (missing) { 放行 }`，
  //   这三条断言全部照样通过。
  //   该情形由**真机验证**覆盖（T2.3 三路径实测），不在门禁能力范围内。
  const rejectBranch = /该库声称自己是最新版[^;]*不一致|拒绝启动|❌/.test(pf)
  check('前置校验存在明确的「拒绝」分支', rejectBranch,
    rejectBranch ? '' : '未找到拒绝分支标记（该脚本可能在任何情况下都放行）')
  check('前置校验的拒绝分支会真的以非 0 退出',
    /process\.exit\(\s*1\s*\)|process\.exitCode\s*=\s*1/.test(pf))
  check('前置校验比对了活库与基线', /information_schema/.test(pf) && /easychat\.sql/.test(pf))
  // 闸门读 baseline-version 时若只读 properties 而不读 env，
  // 运维声明的真实版本号对它不生效 —— 正是 T2.3 路径③实测踩到的问题。
  check('前置校验优先读 SPRING_FLYWAY_BASELINE_VERSION 环境变量',
    /process\.env\.SPRING_FLYWAY_BASELINE_VERSION/.test(pf),
    '缺这条，env 声明的真实版本号对闸门不生效，合法的存量库升级路径会被堵死')
}

// ── 7. compose 接入 ──────────────────────────────────────────
console.log('\n=== 7. 容器编排接入 ===')
check('docker-compose.yml 存在', has(COMPOSE))
if (has(COMPOSE)) {
  const compose = read(COMPOSE)
  check('compose 含 migrate service', /^\s{2}migrate:/m.test(compose))
  check('compose 出现过 service_completed_successfully', /service_completed_successfully/.test(compose))

  /**
   * 取某个 service 的块正文。
   *
   * ⚠ 踩过的坑：初版写成 `compose.match(/^ {2}backend:\n([\s\S]*?)(?=^ {2}\S|\Z)/m)`，
   *   其中 `\Z` **在 JavaScript 正则里根本不存在**（那是 PCRE 的写法），
   *   JS 把它当成字面量 `Z` 去匹配，于是前瞻永不成立，`*?` 会一路吞到文件末尾
   *   —— 拿到的 backend「块」其实是整个文件后半段，断言语义全错。
   *   变异检验里表现为「某些变异靠不相干的断言红」，一度像是门禁有判别力。
   *
   * 改为：按 `^  <service>:` 切出全部 service 块，再按名字取。
   * 这样边界由真实的行首缩进决定，不依赖任何前瞻。
   */
  const serviceBlocks = (() => {
    const body = compose.replace(/\r\n/g, '\n')
    const out = new Map()
    // services: 之后到文件末尾（volumes: 之类同级块不参与）
    const svcIdx = body.search(/^services:\s*$/m)
    if (svcIdx < 0) return out
    // volumes: 及其后内容不属于任何 service，必须截断，
    // 否则 mysql-data / redis-data 会被误当成 service 名
    const svcTail = body.slice(svcIdx)
    const volIdx = svcTail.search(/^ {0,2}volumes:[ \t]*$/m)
    const svcBody = volIdx >= 0 ? svcTail.slice(0, volIdx) : svcTail
    const re = /^ {2}([A-Za-z0-9_-]+):[ \t]*$/gm
    const hits = []
    let m
    while ((m = re.exec(svcBody)) !== null) hits.push({ name: m[1], start: m.index })
    hits.forEach((h, i) => {
      const end = i + 1 < hits.length ? hits[i + 1].start : svcBody.length
      out.set(h.name, svcBody.slice(h.start, end))
    })
    return out
  })()

  check('compose 的 services 段可被正确解析（能取出 migrate 与 backend）',
    serviceBlocks.has('migrate') && serviceBlocks.has('backend'),
    `实际解析到：${[...serviceBlocks.keys()].join(', ') || '(空)'}`)

  const backendBlock = serviceBlocks.get('backend') || ''
  const migrateBlock = serviceBlocks.get('migrate') || ''

  // ⚠ 光断言「文本里出现过 service_completed_successfully」是不够的：
  //   那个串出现在 migrate service 的**注释**里也可能，而注释删掉后
  //   backend 的 depends_on 会静默失效——闸门照样绿。
  // 故必须解析结构：backend 块的 depends_on 里真有一条 migrate 依赖。
  check('backend 的 depends_on 里确有 migrate 条目',
    /^ {6}migrate:\n {8}condition: service_completed_successfully\s*$/m.test(backendBlock),
    backendBlock ? 'backend 块内未见 migrate 依赖（可能只在注释里出现过该字符串）' : '未找到 backend 块')

  // 闸门与后端必须用**同一个** baseline-version。
  // 若只有 migrate 侧注入、后端侧漏了，则 env 声明 9 时闸门按 9 放行，
  // 后端却按 properties 默认 12 执行 —— 正好是本变更要防的静默漂移。
  check('migrate 闸门注入了 baseline-version',
    /SPRING_FLYWAY_BASELINE_VERSION:\s*\$\{SPRING_FLYWAY_BASELINE_VERSION:-?\d+\}/.test(migrateBlock))
  check('backend 与 migrate 使用同一个 baseline-version 变量（闸门校验值 = 后端执行值）',
    /SPRING_FLYWAY_BASELINE_VERSION:\s*\$\{SPRING_FLYWAY_BASELINE_VERSION:-?\d+\}/.test(backendBlock),
    'backend 侧漏注入会导致 env 声明的版本号对后端不生效')
}

// ── 8. 门禁自身接入 CI ──────────────────────────────────────
console.log('\n=== 8. 门禁接入 CI ===')
const ci = read('.github/workflows/ci.yml')
check('ci.yml 已接入 verify_migration_flyway.mjs', /verify_migration_flyway\.mjs/.test(ci))

// ── 汇总 ────────────────────────────────────────────────────
const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach(([n]) => console.log('  - ' + n))
  process.exit(1)
}
process.exit(0)