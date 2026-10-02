#!/usr/bin/env node
/**
 * MyBatis Mapper XML 参数占位符审计（防 BindingException 500）
 *
 * 背景：`/emoji/list`、`/userStatus/set` 曾长期 500，根因是 XML 里写了裸属性
 * 占位符（`#{userId}`），而对应 Mapper 方法的具名参数是 `bean` / `query` / `list`，
 * 运行期直接抛 `BindingException: Parameter 'userId' not found`。
 * 这类缺陷单测抓不到（单测 mock 掉了 Mapper，根本不解析 XML），
 * 活体也容易被误判成「表不存在」，所以用静态审计兜住整类问题。
 *
 * 规则：
 *   1. 解析 Mapper 接口方法 → 该语句可用的具名参数集合（来自 @Param）；
 *      继承 BaseMapper 的方法参数名从 BaseMapper 取。
 *   2. 扫描 XML 语句里的 `#{...}` 根名。
 *   3. 根名不在该方法具名参数集合内 → 违规。
 *      （裸属性 `#{userId}` 在运行期永远解析不到：MyBatis 只注册
 *        param1 / arg0 / 真实参数名，不注册 POJO 属性名）
 *   4. 例外：`<foreach item="x">` 内部的 `#{x.field}` 按 item 名豁免。
 *
 * 用法：node scripts/verify/verify_mapper_params.mjs
 * 退出码：0 全通过 / 1 有违规
 */
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const JAVA_DIR = resolve(ROOT, 'easychat-java/src/main/java/com/easychat/mappers')
const XML_DIR = resolve(ROOT, 'easychat-java/src/main/resources/com/easychat/mappers')

/** 参数列表里允许一层嵌套括号，才能正确吃掉 @Param("x") */
const ARGS = '((?:[^()]|\\([^()]*\\))*)'
const METHOD_RE = new RegExp(
  `(?:^|\\n)\\s*(?:public\\s+|private\\s+|abstract\\s+)*(?:[\\w<>,\\[\\]\\.\\s]+?)\\s+(\\w+)\\s*\\(${ARGS}\\)\\s*[;{]`,
  'g'
)

/** 去掉行注释与块注释，避免把注释里的方法签名当成真方法 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')
}

function parseMapper(file) {
  const src = stripComments(readFileSync(file, 'utf8'))
  const methods = new Map()
  let m
  METHOD_RE.lastIndex = 0
  while ((m = METHOD_RE.exec(src)) !== null) {
    const name = m[1]
    const argStr = m[2].trim()
    if (!argStr) continue
    const names = new Set()
    const pm = /@Param\(\s*"([^"]+)"\s*\)/g
    let p
    while ((p = pm.exec(argStr)) !== null) names.add(p[1])
    // 参数个数（顶层逗号分隔，忽略 @Param("x") 内部的逗号）
    const topLevel = argStr.replace(/\([^()]*\)/g, 'X')
    const argCount = topLevel.split(',').filter((s) => s.trim()).length
    methods.set(name, { names, argCount })
  }
  return { methods, extendsBase: /extends\s+BaseMapper/.test(src) }
}

console.log('===== MyBatis Mapper XML 参数占位符审计 =====\n')

// BaseMapper 继承方法（selectList/insert/updateByParam…）的具名参数名对所有实现通用
const BASE = parseMapper(join(JAVA_DIR, 'BaseMapper.java')).methods
console.log(`BaseMapper 提供具名参数的方法：${[...BASE.keys()].join(', ')}\n`)
console.log('=== 逐个 Mapper 接口 × XML 配对审计 ===')

const results = []
let orphans = 0
for (const xmlName of readdirSync(XML_DIR).filter((f) => f.endsWith('.xml')).sort()) {
  const base = xmlName.replace(/\.xml$/, '')
  let parsed
  try {
    parsed = parseMapper(join(JAVA_DIR, base + '.java'))
  } catch {
    continue // 无对应接口文件
  }
  const available = new Map()
  if (parsed.extendsBase) for (const [k, v] of BASE) if (!available.has(k)) available.set(k, v)
  for (const [k, v] of parsed.methods) available.set(k, v)
  const xml = readFileSync(join(XML_DIR, xmlName), 'utf8')
  const stmtRe = /<(insert|update|delete|select)\s+id="(\w+)"[^>]*>([\s\S]*?)<\/\1>/g
  const found = []
  const orphan = []
  let sm
  while ((sm = stmtRe.exec(xml)) !== null) {
    const [, tag, id, rawBody] = sm
    const meta = available.get(id)
    if (!meta) {
      orphan.push(id)
      continue
    }
    const itemNames = new Set()
    const feRe = /<foreach[^>]*\bitem="(\w+)"/g
    let fm
    while ((fm = feRe.exec(rawBody)) !== null) itemNames.add(fm[1])
    const body = rawBody.replace(/<foreach[\s\S]*?<\/foreach>/g, '')

    // 运行期语义：
    //   有 @Param        → parameterObject 是 ParamMap，必须用限定名
    //   无 @Param 且单参数 → parameterObject 就是 POJO 本身，裸属性可用
    //   无 @Param 且多参数 → 只能用 param1..paramN / arg0..，裸属性必失败
    let bad = new Set()
    const allowBare = meta.names.size === 0 && meta.argCount === 1
    const allowed = new Set(meta.names)
    if (allowBare) allowed.add('*bare*')
    if (meta.names.size === 0 && meta.argCount > 1) {
      for (let i = 1; i <= meta.argCount; i++) allowed.add('param' + i), allowed.add('arg' + (i - 1))
    }

    const phRe = /#\{([\w.]+)\}/g
    let pm
    while ((pm = phRe.exec(body)) !== null) {
      const raw = pm[1]
      const dot = raw.indexOf('.')
      const root = dot === -1 ? raw : raw.slice(0, dot)
      if (dot !== -1 && itemNames.has(root)) continue
      if (allowBare) continue
      if (!allowed.has(root)) bad.add(raw)
    }
    const availDesc = allowBare ? '单参数无@Param（裸属性合法）' : [...allowed].join(', ')
    if (bad.size) found.push(`${id}(${tag}) 用 [${[...bad].join(', ')}]，可用 [${availDesc}]`)
  }
  orphans += orphan.length
  const ok = found.length === 0
  results.push([base, ok])
  console.log(
    `   [${ok ? 'PASS' : 'FAIL'}] ${xmlName}` +
      (found.length ? ' | ' + found.join(' ; ') : '') +
      (orphan.length ? ` | 仅提示：XML 有 ${orphan.length} 个语句在接口中无声明（${orphan.join(', ')}）` : '')
  )
}

const failed = results.filter(([, ok]) => !ok)
console.log(`\n===== 结论：${results.length - failed.length}/${results.length} 通过 =====`)
if (failed.length) {
  console.log('违规文件：' + failed.map(([n]) => n).join(', '))
  process.exit(1)
}
process.exit(0)
