import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * HTTP 请求层 `Request.js` 的**行为**测试（docs/system-facts.md §14 遗留 #7 剩余缺口之一）。
 *
 * ## 为什么这样测
 * 拦截器的价值全在「拿到 response / error 之后如何分支」，与网络无关。
 * 因此 mock `axios.create` 让它返回一个可控桩，**捕获 Request.js 在模块加载时
 * 真实注册的那两个 handler**，再直接调用它们 ——
 * 被测代码是生产代码本身，**不是复写**，故不存在「测试与实现互相迁就」的风险。
 *
 * ## 锁定的是四条契约
 *  ① `request()` 把错误**吞成 null**（全站调用方都依赖它判空，改成 throw 会集体炸）
 *  ② 成功 / 登录过期(2001) / 业务错误 三分支
 *  ③ **HTTP 非 2xx 但 body 是 Result 结构**时按业务错误处理（不得误报「网络异常」）
 *     —— 2026-10-04 才补的分支，至今无任何测试守护
 *  ④ blob / arraybuffer 的错误体是**二进制**，须先解文本再判业务错误
 */

// ── 捕获注册进来的拦截器 handler ──────────────────────────────
const captured = {
  requestUse: null,
  requestReject: null,
  responseUse: null,
  responseReject: null
}
const instanceCalls = { get: [], post: [] }
/**
 * 让被 mock 的 instance.get/post 返回**由用例指定**的 promise。
 * ⚠ 这是关键：若让它们恒定 resolve，则 `request()` 的 catch 分支永远走不到，
 *   那些用例就成了「复刻 request() 的逻辑」的假覆盖（本文件初版即踩中）。
 */
const netBehaviour = {
  get: () => Promise.resolve({ data: { code: 0 } }),
  post: () => Promise.resolve({ data: { code: 0 } })
}
const setNet = (impl) => {
  netBehaviour.get = impl.get || netBehaviour.get
  netBehaviour.post = impl.post || netBehaviour.post
}

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: {
        use: (ok, err) => { captured.requestUse = ok; captured.requestReject = err }
      },
      response: {
        use: (ok, err) => { captured.responseUse = ok; captured.responseReject = err }
      }
    },
    get: (...a) => { instanceCalls.get.push(a); return netBehaviour.get(...a) },
    post: (...a) => { instanceCalls.post.push(a); return netBehaviour.post(...a) }
  }
  return {
    default: {
      create: vi.fn(() => instance)
    }
  }
})

const MessageStub = { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
vi.mock('@/utils/Message', () => ({ default: MessageStub }))
vi.mock('element-plus', () => ({ ElLoading: { service: vi.fn(() => ({ close: vi.fn() })) } }))
vi.mock('@/utils/Api', () => ({ default: { prodDomain: 'http://h', devDomain: 'http://h' } }))

const request = (await import('@/utils/Request')).default

const TOKEN = 'tok-123'

/** 构造一个 2xx 响应，触发响应拦截器的「成功」分支 */
const okResponse = (data, config = {}) => ({
  data,
  config: { showLoading: false, showError: true, ...config }
})

/** 构造一个非 2xx 错误 */
const httpError = (status, data, config = {}) => ({
  config: { showLoading: false, showError: true, ...config },
  response: { status, data }
})

beforeEach(() => {
  MessageStub.error.mockClear()
  localStorage.clear()
  localStorage.setItem('token', TOKEN)
  instanceCalls.get.length = 0
  instanceCalls.post.length = 0
  // 复位为默认成功，避免上一条用例的 reject 污染下一条
  setNet({
    get: () => Promise.resolve({ data: { code: 0 } }),
    post: () => Promise.resolve({ data: { code: 0 } })
  })
  vi.useRealTimers()
})

describe('T1 request() 的错误契约：吞成 null 而非抛出', () => {
  it('成功（code=0）返回完整 body，不弹错误', async () => {
    const body = { code: 0, message: 'success', data: { userId: 'U001' } }
    // mock 的 instance 不经过真实 axios 的响应拦截器，故直接把 body 作为 resolve 值
    setNet({ post: () => Promise.resolve(body) })

    const r = await request({ url: '/x', params: { a: 1 }, showLoading: false })

    expect(r).toEqual(body)
    expect(MessageStub.error).not.toHaveBeenCalled()
  })

  it('★ 业务错误 → request() 返回 null（不是 throw），且弹 body.message', async () => {
    // 让 post 走 request() **自己的** catch 分支，而不是在测试里复刻它
    setNet({ post: () => Promise.reject({ showError: true, msg: '邮箱格式不正确' }) })

    const r = await request({ url: '/x', params: { a: 1 }, showLoading: false })

    expect(r).toBeNull()
    expect(MessageStub.error).toHaveBeenCalledWith('邮箱格式不正确')
  })

  it('★ showError=false → 仍吞成 null，但不弹 Toast', async () => {
    setNet({ post: () => Promise.reject({ showError: false, msg: '静默' }) })

    const r = await request({ url: '/x', params: { a: 1 }, showLoading: false })

    expect(r).toBeNull()
    expect(MessageStub.error).not.toHaveBeenCalled()
  })

  it('★ GET 分支同样吞成 null（两个分支的 catch 各自独立，都要有覆盖）', async () => {
    setNet({ get: () => Promise.reject({ showError: true, msg: 'GET 失败' }) })

    const r = await request({ url: '/x', method: 'GET', params: {}, showLoading: false })

    expect(r).toBeNull()
    expect(MessageStub.error).toHaveBeenCalledWith('GET 失败')
  })
})

describe('T2 响应拦截器：成功 / 登录过期 / 业务错误 三分支', () => {
  it('code=0 → 原样返回 body', () => {
    const body = { code: 0, data: [1, 2] }
    expect(captured.responseUse(okResponse(body))).toBe(body)
  })

  it('★ code=2001 → reject「登录超时」并 send reLogin', async () => {
    vi.useFakeTimers()
    const send = vi.fn()
    global.window.ipcRenderer = { send }
    // ⚠ 必须接住这个 rejected promise：否则它变成 unhandled rejection，
    //   在后续用例里以「Serialized Error」的形式炸出来，伪装成别人的失败。
    await expect(captured.responseUse(okResponse({ code: 2001, message: 'x' })))
      .rejects.toMatchObject({ msg: '登录超时' })
    vi.advanceTimersByTime(2000)
    expect(send).toHaveBeenCalledWith('reLogin')
  })

  it('★ HTTP 401 + body code=2001 → 同样走 reLogin（不得误报网络异常）', async () => {
    vi.useFakeTimers()
    const send = vi.fn()
    global.window.ipcRenderer = { send }
    const p = captured.responseReject(httpError(401, { code: 2001, message: '登录超时' }))
    await expect(p).rejects.toMatchObject({ msg: '登录超时' })
    vi.advanceTimersByTime(2000)
    expect(send).toHaveBeenCalledWith('reLogin')
  })

  it('业务错误 → reject body.message', async () => {
    await expect(captured.responseUse(okResponse({ code: 1001, message: '参数错误' })))
      .rejects.toMatchObject({ msg: '参数错误', showError: true })
  })

  it('errorCallback 恰好被调用一次', async () => {
    const cb = vi.fn()
    await expect(captured.responseUse(okResponse(
      { code: 1001, message: 'x' }, { errorCallback: cb })))
      .rejects.toBeTruthy()
    expect(cb).toHaveBeenCalledTimes(1)
  })
})

describe('T3 ★ rejectByBody：非 2xx 带 Result body 必须按业务错误处理', () => {
  it('HTTP 403 + code=2003 → 文案取 body.message', async () => {
    await expect(captured.responseReject(httpError(403, { code: 2003, message: '无权限' })))
      .rejects.toMatchObject({ msg: '无权限' })
  })

  it('★ 无 body（连不上）→ 才报「网络异常」', async () => {
    const e = { config: { showLoading: false }, response: undefined }
    await expect(captured.responseReject(e))
      .rejects.toMatchObject({ msg: '网络异常，请检查后端是否启动' })
  })

  it('body 为 null → 网络异常', async () => {
    await expect(captured.responseReject(httpError(500, null)))
      .rejects.toMatchObject({ msg: '网络异常，请检查后端是否启动' })
  })

  it('errorCallback 在 error 路径也被调用且只一次', async () => {
    const cb = vi.fn()
    await expect(captured.responseReject(httpError(403, { code: 2003, message: 'x' }, { errorCallback: cb })))
      .rejects.toBeTruthy()
    expect(cb).toHaveBeenCalledTimes(1)
  })
})

describe('T4 ★ blob / arraybuffer 错误体：先解文本再判业务错误', () => {
  const blobBody = (text) => ({ text: () => Promise.resolve(text) })

  it('★ blob 错误体是 JSON → 按业务错误处理（不得误报网络异常）', async () => {
    const e = {
      config: { showLoading: false, responseType: 'blob' },
      response: { data: blobBody(JSON.stringify({ code: 2604, message: '文件类型不支持' })) }
    }
    await expect(captured.responseReject(e))
      .rejects.toMatchObject({ msg: '文件类型不支持' })
  })

  it('blob 错误体是 HTML → 回退「网络异常」，且不抛解析异常', async () => {
    const e = {
      config: { showLoading: false, responseType: 'blob' },
      response: { data: blobBody('<html>502 Bad Gateway</html>') }
    }
    await expect(captured.responseReject(e))
      .rejects.toMatchObject({ msg: '网络异常，请检查后端是否启动' })
  })

  it('arraybuffer 错误体是 ArrayBuffer → 经 TextDecoder 解析', async () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ code: 1002, message: '系统错误' }))
    const e = {
      config: { showLoading: false, responseType: 'arraybuffer' },
      response: { data: bytes.buffer }
    }
    await expect(captured.responseReject(e))
      .rejects.toMatchObject({ msg: '系统错误' })
  })

  it('blob 的 text() 自身 reject → 仍回退网络异常（不得把异常抛给调用方）', async () => {
    const e = {
      config: { showLoading: false, responseType: 'blob' },
      response: { data: { text: () => Promise.reject(new Error('boom')) } }
    }
    await expect(captured.responseReject(e))
      .rejects.toMatchObject({ msg: '网络异常，请检查后端是否启动' })
  })
})

describe('T5 请求封装：token 头 / FormData / GET 走 params', () => {
  it('token 从 localStorage 取出并放进请求头', async () => {
    await request({ url: '/y', params: { a: 1 }, showLoading: false })
    // instance.post(url, formData, reqConfig) —— **三个**参数，reqConfig 是第 3 个
    const [, , reqConfig] = instanceCalls.post[0]
    expect(reqConfig.headers.token).toBe(TOKEN)
  })

  it('GET 走 instance.get 并把 params 原样传', async () => {
    await request({ url: '/y', method: 'GET', params: { a: 1 }, showLoading: false })
    expect(instanceCalls.get.length).toBe(1)
    expect(instanceCalls.get[0][1].params).toEqual({ a: 1 })
  })

  it('非 FormData 时走 form-urlencoded', async () => {
    await request({ url: '/y', params: { a: 1 }, showLoading: false })
    const [, , reqConfig] = instanceCalls.post[0]
    expect(reqConfig.headers['Content-Type']).toBe('application/x-www-form-urlencoded;charset=UTF-8')
  })

  it('传 FormData 时改走 multipart 且不覆盖 Content-Type', async () => {
    const fd = new FormData()
    fd.append('k', 'v')
    await request({ url: '/y', params: fd, showLoading: false })
    const [, , reqConfig] = instanceCalls.post[0]
    expect(reqConfig.headers['Content-Type']).toBeUndefined()
  })
})