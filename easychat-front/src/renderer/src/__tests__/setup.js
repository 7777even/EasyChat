// 组件测试的全局环境准备（vitest setupFiles）
//
// 存在理由：渲染层依赖若干**浏览器专有能力**，jsdom 一个都不提供。
// 缺任何一个都会让组件在挂载那一刻就抛错，而错误信息往往指向组件内部
// 一堆无关代码，极难定位。故集中在此补齐，且**每个桩都注明为何需要**。
//
// ⚠ 本文件若被清空，`verify_frontend_test_base.mjs` 门禁会直接失败
//   （它断言本文件存在且含 ResizeObserver 桩）——缺桩属高频坑，值得机控。

import { beforeEach } from 'vitest'

// ── 1. 尺寸观察器 ────────────────────────────────────────────
// MessageVirtualList 用它监听容器高度以驱动虚拟滚动。
// 无桩 → mount 即抛 "ResizeObserver is not defined"。
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserver {
    constructor (callback) {
      this._cb = callback
    }

    observe () { /* 桩：测试不需要真实尺寸变化 */ }

    unobserve () {}

    disconnect () {}
  }
}

// ── 2. 交叉观察器 ────────────────────────────────────────────
// 部分列表/懒加载用它判断元素是否进入视口。
if (typeof globalThis.IntersectionObserver === 'undefined') {
  globalThis.IntersectionObserver = class IntersectionObserver {
    constructor () {}

    observe () {}

    unobserve () {}

    disconnect () {}

    takeRecords () { return [] }
  }
}

// ── 3. 媒体查询 ──────────────────────────────────────────────
// Element Plus 的响应式断点、deep dark 模式都靠 matchMedia。
// 无桩 → 组件内任何 a:hover / 断点判断处抛错。
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener () {},
    removeListener () {},
    addEventListener () {},
    removeEventListener () {},
    dispatchEvent () { return false }
  })
}

// ── 4. 主进程桥接（preload 暴露面）───────────────────────────
// ADR-002：用 Proxy 而非逐个列举——
//   preload 暴露了几十个方法，逐个列举必然遗漏；遗漏后组件调到未列举的通道
//   会拿到 undefined，真正的异常推迟到后续调用，错误信息完全失真。
//
//   这里给的是**永不真正执行**的桩：记录调用但不碰文件系统/网络/数据库，
//   确保测试既不误伤真实数据，又能让「组件调了哪个通道」成为可断言的事实。
const ipcCalls = []
const ipcHandler = {
  get (target, prop) {
    if (prop === '__calls__') return ipcCalls
    if (prop === '__reset__') {
      ipcCalls.length = 0
      return true
    }
    if (prop === Symbol.toPrimitive || prop === 'then') return undefined
    // 所有 IPC 方法统一返回 undefined 并记录，不真正执行
    return (...args) => {
      ipcCalls.push({ channel: String(prop), args })
      return undefined
    }
  }
}

beforeEach(() => {
  ipcCalls.length = 0

  if (!window.ipcRenderer) {
    window.ipcRenderer = new Proxy({}, ipcHandler)
  }
  // preload 还暴露了一批 window.xxx 方法（sendCallFrame / getUserInfo 等），
  // 一并用同样的桩兜住，避免逐个组件 mock。
  const winHandler = {
    get (target, prop) {
      if (prop === '__calls__') return ipcCalls
      if (prop === '__reset__') {
        ipcCalls.length = 0
        return true
      }
      if (typeof prop !== 'string') return undefined
      if (prop in window) return window[prop]
      return (...args) => {
        ipcCalls.push({ channel: prop, args })
        return undefined
      }
    },
    has () { return true }
  }
  // 只在缺失时兜底，不覆盖 jsdom 自带的 window 属性
  Object.defineProperty(window, '__ipcProxyTarget__', { value: new Proxy({}, winHandler), configurable: true })
})

// ── 5. crypto.randomUUID ─────────────────────────────────────
// useCallStore.startCall 用它生成通话 ID。Node 24 的 globalThis.crypto 有，
// 但 jsdom 环境的 window.crypto 可能没有；补一份。
if (typeof globalThis.crypto === 'undefined') {
  globalThis.crypto = {}
}
if (typeof globalThis.crypto.randomUUID !== 'function') {
  globalThis.crypto.randomUUID = () => '00000000-0000-4000-8000-000000000000'
}
if (typeof window.crypto === 'undefined') {
  window.crypto = globalThis.crypto
} else if (typeof window.crypto.randomUUID !== 'function') {
  window.crypto.randomUUID = globalThis.crypto.randomUUID
}

// ── 6. URL.createObjectURL ───────────────────────────────────
// 图片/文件预览（ShowLocalImage / Avatar）用它生成 blob URL，jsdom 未实现。
if (typeof URL.createObjectURL !== 'function') {
  URL.createObjectURL = () => 'blob:mock'
}
if (typeof URL.revokeObjectURL !== 'function') {
  URL.revokeObjectURL = () => {}
}

// ── 7. 抑制测试期噪音 ────────────────────────────────────────
// 生产代码里有 console.warn 用于上报「写入未生效」这类真实问题。
// 测试里保留输出（那是信号不是噪音），但过滤掉 jsdom 自身的 CSS 解析告警。
const originalWarn = console.warn
console.warn = (...args) => {
  const first = String(args[0] || '')
  if (first.includes('Could not parse CSS stylesheet')) return
  originalWarn.apply(console, args)
}

export { ipcCalls }