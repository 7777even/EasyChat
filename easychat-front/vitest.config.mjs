import { resolve } from 'path'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 前端测试配置（2026-10-04，L3）
//
// ⚠ 为什么**独立于 electron.vite.config.js**：
//   那个配置带 externalizeDepsPlugin()，会把 dependencies 全部**外置**
//   （运行时从 node_modules 加载）。测试进程不需要这层，且 vitest 的
//   transform 管线与 electron 构建不同；混在一起会让 vitest 继承外置策略
//   而找不到模块。故不复用、不 mergeConfig，各自独立。
//
// ⚠ 为什么 `@` 别名要在这里**重新声明**：
//   vitest 不读 electron.vite.config.js 的 resolve.alias（除非显式 mergeConfig）。
//   代价是两处需同步——已由 scripts/verify/verify_frontend_test_base.mjs 断言二者一致。
//
// ⚠ 为什么 vitest 钉 1.6.0（不带 ^）：
//   vitest 2/3/5 的 peer 要求 vite 5+，而本项目是 vite@4.5.14 + electron-vite@1。
//   用 ^ 会某天自动升级到 2.x 并**连带升级 vite，拖坏 Electron 构建**。
//   该约束已固化为门禁断言。

export default defineConfig({
  // ⚠ 必须挂 @vitejs/plugin-vue，否则 .vue 单文件组件无法被解析。
  //   实测踩过：漏掉时报 `Failed to parse source for import analysis because
  //   the content contains invalid JS syntax. Install @vitejs/plugin-vue`。
  //   vitest **不会**自动启用它（它只对 electron-vite 的构建生效）。
  //   该包本就是既有 devDependency（^4.3.1），**无需新增**。
  plugins: [vue()],
  test: {
    // 组件依赖 DOM（尺寸观察器、媒体查询、交叉观察器），node 环境一个都没有
    environment: 'jsdom',
    globals: false,
    setupFiles: [resolve(__dirname, 'src/renderer/src/__tests__/setup.js')],
    include: ['src/renderer/src/**/__tests__/**/*.spec.js'],
    // 组件挂载 + jsdom 首启较慢，给足超时
    testTimeout: 20000,
    reporters: 'default'
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src/renderer/src')
    }
  }
})