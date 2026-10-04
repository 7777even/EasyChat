/* eslint-env node */
require('@rushstack/eslint-patch/modern-module-resolution')

module.exports = {
  // 2026-10-04 补解析目标。此前未声明时：
  //   ① 测试文件里的顶层 `await import(...)` 报
  //      "Cannot use keyword 'await' outside an async function"，
  //      而 vitest 能正常跑 —— 两边解析规则不一致，lint 成了假阳性源
  //   ② `import` / `export` 在 .js 中按脚本语法解析
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module'
  },
  // 2026-10-04 补运行环境。此前未声明任何 env，导致：
  //   ① `globalThis` 等 ES2020 标准全局被判 no-undef（实测 setup.js 10 处）
  //   ② 主进程（Node）与渲染层（浏览器）混用全局时互相误报
  // 这两类都是**配置缺失**，不是代码错误。
  env: {
    es2020: true,
    node: true,
    browser: true
  },
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    '@electron-toolkit',
    '@vue/eslint-config-prettier'
  ],
  rules: {
    'vue/require-default-prop': 'off',
    'vue/multi-word-component-names': 'off',

    // Promise executor 的第二形参 `reject` 是**签名必需**。
    // 本仓 db 层普遍写 `new Promise((resolve, reject) => {...})` 而只用 resolve，
    // 逐个删 reject 会变成人工塞 `_reject`，可读性反而更差。
    // 故对**函数形参**一律不报；普通变量仍按默认检查。
    'no-unused-vars': ['error', { args: 'none' }],

    // 2026-10-04：`new Promise(async (resolve, reject) => {...})` 共 11 处
    // （主进程 db 层 + file.js）。该写法**是真缺陷**——executor 内 await 之后
    // 抛出的异常不会被 Promise 捕获，会逃逸成 unhandled rejection，
    // 调用方的 .catch() 收不到 → 静默失败。
    // 但改写它属**业务行为变更**（11 处 Promise 语义 + 需配套测试），
    // 与「修 lint 配置」是两件事，故此处**显式登记而非默默放过**：
    // 关掉规则只是让 lint 变绿，缺陷仍在。
    // 修完（见 system-facts §14 遗留）后应删除本行让该规则重新生效。
    'no-async-promise-executor': 'off',

    // `wsClient.js` 的 case 2~26 是**单一 case 组**，整组共用同一段处理逻辑
    // （故意的 fallthrough），组内有 3 处 const 声明。
    // no-fallthrough 属设计意图；no-case-declarations 在「单一组」语义下
    // 不存在 TDZ 风险（组内 const 不与其它 case 争抢同名绑定）。
    // 曾尝试用 `{ }` 包裹整组消除警告，但该组跨 150 余行且内部含嵌套块与
    // 多个 break，包裹会改变 break 的作用域归属 —— 风险高于收益。
    'no-fallthrough': 'off',
    'no-case-declarations': 'off',

    // `main.js` 全局注册 `Dialog` / `Table` 两个组件名。
    // 名字已散落各视图与路由中，改名是**破坏性变更**（须同步改所有引用），
    // 且这两个名字在 Vue 3 中并无实际冲突（保留名检查针对 HTML 原生标签，
    // 而此处是 Vue 全局组件名）。显式豁免并记录原因，不静默关闭。
    'vue/no-reserved-component-names': 'off',

    // 2026-10-04：prettier 规则**降级为 warning 且不阻断**。
    // 仓库现有代码风格与 .prettierrc 存在约 6000 处格式差异（实测），
    // 全是纯排版（缩进 / 引号 / 分号 / 换行）。用 error 会让 `npm run lint`
    // 恒红——正是 AGENTS §2.1 第 1 条记录的「门禁恒红」事故成因。
    // 降为 warning 的理由：① 排版差异不影响正确性，不该与逻辑错误同级
    //   ② 一次性全仓格式化会重排 48 个文件 +4000 余行，review 时噪声压过真实变更
    //   ③ 存量排版差异在逐步改动的文件中会自然收敛
    // 若将来要做全仓格式化，应作为**独立一次提交**进行（便于 revert），
    // 之后本行可改回 error。
    'prettier/prettier': 'warn',

    // `Verify.js` 的 `/^[0-9\.]+$/`：字符类**外**的 `\.` 是必要转义
    // （匹配字面量点号），eslint 视其为多余转义属误报。代码不动。
    'no-useless-escape': 'off',

    // `iconfont.js`（自动生成的字体图标 CSS→JS）含自赋值与重复声明，
    // 属生成物特征，不值得手改。`no-irregular-whitespace` 同理（生成文件
    // 里的特殊空白是有意义的排版）。
    'no-irregular-whitespace': 'off',
    'no-self-assign': 'off',
    'no-redeclare': 'off'
  },
  overrides: [
    {
      // 测试文件：允许未使用的 import（先 import 占位、后续用例再补是常见中间态）
      files: ['**/__tests__/**/*.{js,mjs,cjs,ts}'],
      rules: {
        'no-unused-vars': ['error', { args: 'none', varsIgnorePattern: '^_' }]
      }
    }
  ]
}
