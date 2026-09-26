// 主题应用工具
// Element Plus 暗色模式通过在 <html> 上切换 .dark 类生效，
// 该类会激活 element-plus/theme-chalk/dark/css-vars.css 中定义的暗色 CSS 变量。
// 自定义外壳颜色通过 base.scss 中的 --ec-* 变量随 .dark 联动。

export function applyTheme(theme) {
    const el = document.documentElement
    if (theme === 'dark') {
        el.classList.add('dark')
    } else {
        el.classList.remove('dark')
    }
}

export function getTheme() {
    return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}
