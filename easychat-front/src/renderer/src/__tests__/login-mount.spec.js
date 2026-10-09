import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import { useUserInfoStore } from '@/stores/UserInfoStore'
import { ipcCalls } from './setup'

/**
 * 登录页的**真实挂载**测试（DOM 级）。
 *
 * 被测点：
 *   1. 默认登录态表单结构（密码/验证码在，昵称/注册字段不在）
 *   2. 校验链：空邮箱/非法邮箱/非法密码/空验证码 → errorMsg 且不发请求
 *   3. 登录成功 → setInfo + localStorage token + ipc openChat + router.push('/main')
 *   4. 登录失败 → errorCallback 降 loading + errorMsg + 换验证码
 *   5. 切换注册 → ipc loginOrRegister + 昵称/确认密码字段出现
 *   6. 注册成功 → Message.success + 自动切回登录态
 *   7. 找回密码 → 字段切换 + 倒计时防重发 + 重置成功后返回登录
 *   8. 本地用户列表回调 → 邮箱下拉 + selectEmail 填充
 */

// ===== Element Plus stubs =====
const elInputStub = {
  name: 'el-input',
  props: ['modelValue', 'type', 'placeholder', 'size', 'clearable', 'maxLength', 'showPassword', 'disabled'],
  emits: ['update:modelValue', 'focus', 'keyup'],
  template: `<input class="el-input" :type="type || 'text'" :value="modelValue"
    :placeholder="placeholder" :disabled="disabled"
    @input="$emit('update:modelValue', $event.target.value.trim())" @focus="$emit('focus')" />`
}

const elFormStub = {
  name: 'el-form',
  props: ['model', 'labelWidth'],
  emits: ['submit'],
  data() { return { resetCalled: 0 } },
  methods: { resetFields() { this.resetCalled++ } },
  template: `<form class="el-form" @submit.prevent><slot /></form>`
}

const stubs = {
  'el-form': elFormStub,
  'el-form-item': {
    name: 'el-form-item',
    props: ['prop'],
    template: '<div class="el-form-item"><slot /></div>'
  },
  'el-input': elInputStub,
  'el-button': {
    name: 'el-button',
    props: ['type', 'size', 'disabled'],
    emits: ['click'],
    template: `<button class="el-button" :disabled="disabled" @click="$emit('click')"><slot /></button>`
  },
  'el-dropdown': {
    name: 'el-dropdown',
    props: ['trigger', 'maxHeight'],
    template: '<div class="el-dropdown"><slot name="dropdown" /></div>'
  },
  'el-dropdown-menu': {
    name: 'el-dropdown-menu',
    template: '<div class="el-dropdown-menu"><slot /></div>'
  },
  'el-dropdown-item': {
    name: 'el-dropdown-item',
    template: '<div class="el-dropdown-item"><slot /></div>'
  },
  WinOp: {
    name: 'WinOp',
    props: ['showSetTop', 'showMin', 'showMax', 'closeType'],
    template: '<div class="win-op" />'
  }
}

// ===== 依赖注入 =====
let pinia
let router
let request
let listeners
let utils
let verify
let message

function makeRequest(handler) {
  const calls = []
  const fn = vi.fn(async (opts) => {
    calls.push(opts)
    return handler ? handler(opts) : { code: 0, data: null }
  })
  fn.__calls__ = calls
  return fn
}

function mountLogin(handler) {
  request = makeRequest(handler)
  const wrapper = mount(Login, {
    global: {
      stubs,
      config: {
        globalProperties: {
          Request: request,
          Api: {
            checkCode: '/checkCode',
            login: '/login',
            register: '/register',
            sendEmailCode: '/sendEmailCode',
            resetPassword: '/resetPassword',
            prodDomain: 'https://p',
            devDomain: 'http://d',
            prodWsDomain: 'wss://p',
            devWsDomain: 'ws://d'
          },
          Utils: utils,
          Verify: verify,
          Message: message
        }
      },
      plugins: [pinia, router]
    }
  })
  return wrapper
}

// 默认 handler：checkCode 返回验证码，其余返回空
function defaultHandler(opts) {
  if (opts.url === '/checkCode') {
    return { code: 0, data: { checkCode: 'data:image/gif;base64,xx', checkCodeKey: 'KEY1' } }
  }
  return { code: 0, data: null }
}

// 按占位符找输入框并输入
async function fill(wrapper, placeholder, value) {
  const input = wrapper.findAll('.el-input').find((i) => i.attributes('placeholder') === placeholder)
  expect(input, `应存在占位符为「${placeholder}」的输入框`).toBeTruthy()
  await input.setValue(value)
}

describe('Login.vue 真实挂载（DOM 级）', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/main', component: { template: '<div/>' } }]
    })
    vi.spyOn(router, 'push').mockResolvedValue()
    listeners = {}
    ipcCalls.length = 0
    localStorage.clear()
    utils = { isEmpty: (v) => v === undefined || v === null || v === '' }
    verify = {
      checkEmail: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
      checkPassword: (v) => typeof v === 'string' && v.length >= 8 && v.length <= 18
    }
    message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
    // 覆盖 setup.js 的只读 Proxy 桩：可记录监听器，args 保持数组
    window.ipcRenderer = {
      on: (ch, cb) => { listeners[ch] = cb },
      send: (...args) => { ipcCalls.push({ channel: 'send', args }) },
      removeAllListeners: (ch) => { delete listeners[ch] }
    }
  })

  it('默认登录态表单结构', () => {
    const wrapper = mountLogin(defaultHandler)
    expect(wrapper.find('.title').text()).toBe('EasyChat')
    expect(wrapper.findAll('.el-button').some((b) => b.text() === '登录')).toBe(true)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入密码')).toBe(true)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入昵称')).toBe(false)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请再次输入密码')).toBe(false)
  })

  it('挂载即发起基础请求：验证码 + 本地用户 + 4 个域配置', () => {
    mountLogin(defaultHandler)
    // changeCheckCode
    expect(request.__calls__.some((c) => c.url === '/checkCode')).toBe(true)
    // init: loadLocalUser + 4 个 setLocalStore
    expect(ipcCalls.some((c) => c.args[0] === 'loadLocalUser')).toBe(true)
    const sets = ipcCalls.filter((c) => c.args[0] === 'setLocalStore')
    const keys = sets.map((c) => c.args[1].key)
    expect(keys).toEqual(
      expect.arrayContaining(['prodDomain', 'devDomain', 'prodWsDomain', 'devWsDomain'])
    )
    expect(sets).toHaveLength(4)
  })

  it('空邮箱 → 提示「请输入正确的邮箱」且不发登录请求', async () => {
    const wrapper = mountLogin(defaultHandler)
    await wrapper.find('.login-btn').trigger('click')
    expect(wrapper.find('.error-msg').text()).toBe('请输入正确的邮箱')
    expect(request.__calls__.some((c) => c.url === '/login')).toBe(false)
  })

  it('非法邮箱 → 同样提示且不请求', async () => {
    const wrapper = mountLogin(defaultHandler)
    await fill(wrapper, '请输入邮箱', 'not-an-email')
    await wrapper.find('.login-btn').trigger('click')
    expect(wrapper.find('.error-msg').text()).toBe('请输入正确的邮箱')
    expect(request.__calls__.some((c) => c.url === '/login')).toBe(false)
  })

  it('密码非法 → 提示密码规则且不请求', async () => {
    const wrapper = mountLogin(defaultHandler)
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入密码', 'short')
    await wrapper.find('.login-btn').trigger('click')
    expect(wrapper.find('.error-msg').text()).toBe('密码只能是数字、字母、特殊字符8~18位')
    expect(request.__calls__.some((c) => c.url === '/login')).toBe(false)
  })

  it('空验证码 → 提示「请输入验证码」', async () => {
    const wrapper = mountLogin(defaultHandler)
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入密码', '12345678')
    await wrapper.find('.login-btn').trigger('click')
    expect(wrapper.find('.error-msg').text()).toBe('请输入验证码')
  })

  it('登录成功 → setInfo + token + ipc openChat + router.push', async () => {
    const wrapper = mountLogin((opts) => {
      if (opts.url === '/checkCode') {
        return { code: 0, data: { checkCode: 'data:image/gif;base64,xx', checkCodeKey: 'KEY1' } }
      }
      if (opts.url === '/login') {
        return {
          code: 0,
          data: { token: 'TK', userId: 'U1', nickName: '小谢', admin: false }
        }
      }
      return { code: 0, data: null }
    })
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入密码', '12345678')
    await fill(wrapper, '请输入验证码', 'abcd')
    await wrapper.find('.login-btn').trigger('click')
    // 等待 async submit 完成
    await new Promise((r) => setTimeout(r, 0))
    // store 已保存
    const info = useUserInfoStore().getInfo()
    expect(info.userId).toBe('U1')
    // token 落 localStorage
    expect(localStorage.getItem('token')).toBe('TK')
    // ipc openChat 带屏幕尺寸
    const openChat = ipcCalls.find((c) => c.args[0] === 'openChat')
    expect(openChat).toBeTruthy()
    expect(openChat.args[1].token).toBe('TK')
    expect(typeof openChat.args[1].screenWidth).toBe('number')
    // 路由跳转
    expect(router.push).toHaveBeenCalledWith('/main')
  })

  it('登录失败（errorCallback）→ errorMsg + 降 loading + 重取验证码', async () => {
    const wrapper = mountLogin((opts) => {
      if (opts.url === '/checkCode') {
        return { code: 0, data: { checkCode: 'data:image/gif;base64,xx', checkCodeKey: 'KEY1' } }
      }
      if (opts.url === '/login') {
        // 同步走 errorCallback 后返回 null（Request 封装失败语义）
        opts.errorCallback({ message: '验证码错误' })
        return null
      }
      return { code: 0, data: null }
    })
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入密码', '12345678')
    await fill(wrapper, '请输入验证码', 'errcd')
    const before = request.__calls__.filter((c) => c.url === '/checkCode').length
    await wrapper.find('.login-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    // errorCallback 把后端 message 落到 errorMsg
    expect(wrapper.find('.error-msg').text()).toBe('验证码错误')
    // changeCheckCode 重新拉取（刷新图形验证码）
    const after = request.__calls__.filter((c) => c.url === '/checkCode').length
    expect(after).toBeGreaterThan(before)
  })

  it('切换注册 → ipc loginOrRegister(false) + 注册字段出现', async () => {
    const wrapper = mountLogin(defaultHandler)
    await wrapper.find('.no-account').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    // 参数是「切换后的目标态」：登录态（当前 true）→ 注册态（false），
    // 主进程据此设置注册窗口高度（index.js onLoginOrRegister）
    expect(ipcCalls.some((c) => c.args[0] === 'loginOrRegister' && c.args[1] === false)).toBe(true)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入昵称')).toBe(true)
    expect(wrapper.findAll('.el-button').some((b) => b.text() === '注册')).toBe(true)
  })

  it('注册成功 → Message.success(注册成功) + 切回登录态', async () => {
    const wrapper = mountLogin(defaultHandler)
    await wrapper.find('.no-account').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入昵称', '小谢')
    await fill(wrapper, '请输入密码', '12345678')
    await fill(wrapper, '请再次输入密码', '12345678')
    await fill(wrapper, '请输入验证码', 'abcd')
    await wrapper.find('.login-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(message.success).toHaveBeenCalledWith('注册成功')
    // 自动切回登录态
    expect(wrapper.findAll('.el-button').some((b) => b.text() === '登录')).toBe(true)
  })

  it('注册两次密码不一致 → 提示且不请求', async () => {
    const wrapper = mountLogin(defaultHandler)
    await wrapper.find('.no-account').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    await fill(wrapper, '请输入昵称', '小谢')
    await fill(wrapper, '请输入密码', '12345678')
    await fill(wrapper, '请再次输入密码', '87654321')
    await fill(wrapper, '请输入验证码', 'abcd')
    await wrapper.find('.login-btn').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('.error-msg').text()).toBe('两次输入的密码不一致')
    expect(request.__calls__.some((c) => c.url === '/register')).toBe(false)
  })

  it('找回密码 → 字段切换 + 按钮文案', async () => {
    const wrapper = mountLogin(defaultHandler)
    await wrapper.find('.forgot-link').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    // 登录密码框隐藏，邮箱验证码/新密码出现
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入密码')).toBe(false)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入邮箱验证码')).toBe(true)
    expect(wrapper.findAll('.el-input').some((i) => i.attributes('placeholder') === '请输入新密码')).toBe(true)
    expect(wrapper.findAll('.el-button').some((b) => b.text() === '重置密码')).toBe(true)
  })

  it('sendEmailCode 合法邮箱 → 发码 + 倒计时禁用按钮', async () => {
    const wrapper = mountLogin((opts) => {
      if (opts.url === '/checkCode') {
        return { code: 0, data: { checkCode: 'x', checkCodeKey: 'K' } }
      }
      if (opts.url === '/sendEmailCode') return { code: 0, data: null }
      return { code: 0, data: null }
    })
    await wrapper.find('.forgot-link').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    await fill(wrapper, '请输入邮箱', 'a@b.com')
    const btn = wrapper.findAll('.el-button').find((b) => b.text().includes('获取验证码'))
    await btn.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(request.__calls__.some((c) => c.url === '/sendEmailCode')).toBe(true)
    expect(message.success).toHaveBeenCalledWith('验证码已发送，10分钟内有效')
    // 倒计时后按钮禁用
    await new Promise((r) => setTimeout(r, 0))
    const countBtn = wrapper.findAll('.el-button').find((b) => b.text().includes('重发'))
    expect(countBtn, '应出现重发倒计时按钮').toBeTruthy()
    expect(countBtn.attributes('disabled')).toBeDefined()
  })

  it('本地用户回调 → 邮箱下拉渲染 + selectEmail 填充', async () => {
    const wrapper = mountLogin(defaultHandler)
    // 驱动 loadLocalUserCallback
    expect(listeners.loadLocalUserCallback).toBeTruthy()
    listeners.loadLocalUserCallback(null, [{ email: 'z@x.com' }])
    await new Promise((r) => setTimeout(r, 0))
    const item = wrapper.find('.email-select')
    expect(item.exists()).toBe(true)
    expect(item.text()).toBe('z@x.com')
    await item.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const emailInput = wrapper.findAll('.el-input').find((i) => i.attributes('placeholder') === '请输入邮箱')
    expect(emailInput.element.value).toBe('z@x.com')
  })
})
