# Spec：http-request-layer（HTTP 请求层契约）

> 真源：`easychat-front/src/renderer/src/utils/Request.js`。本 capability 约束**全站
> 唯一的 HTTP 出口**的行为契约 —— 它被 200+ 处组件调用，一处语义漂移即集体失效。
>
> 2026-10-12 补立：此前**零自动化覆盖**，而其中两条分支（④⑤）是 2026-10-04 才补上的，
> 补完即无任何测试守护。

## Requirement: 错误被吞成 null 而非抛出

`request()` 必须把错误**吞成 `null`** 而非向上抛出。所有调用方都依赖该语义做判空；
改成 throw 会让全站调用点在未捕获异常上崩溃。

#### Scenario: 业务错误被吞成 null

- **WHEN** 后端返回 `code != 0`（如 1001），拦截器 reject 出 `{ showError, msg }`
- **THEN** `request()` 的解析结果为 **`null`**
- **AND** 弹出 Toast 文案取自 `msg`
- **AND** **不**抛异常

#### Scenario: showError=false 时不弹 Toast 但仍吞成 null

- **WHEN** 调用时传 `showError: false`
- **THEN** 结果仍为 `null`
- **AND** **不**调用 `Message.error`

#### Scenario: GET 与 POST 两分支的 catch 各自独立

- **WHEN** 任一 method 分支（`instance.get` / `instance.post`）发生错误
- **THEN** 该分支的 catch 同样吞成 `null`
- **AND** 两个分支**都**须有覆盖（各自是独立的 `.catch`）

#### Scenario: 成功时返回完整 body

- **WHEN** 后端返回 `code == 0`
- **THEN** `request()` 返回该 body 对象本身（`data` 可用）
- **AND** 不弹任何错误

---

## Requirement: 响应拦截器的三分支

拦截器必须严格按 `code` 三分支处理，`code == 0` 是**成功**的唯一判据。

#### Scenario: code=0 原样返回

- **WHEN** HTTP 200 且 body `code == 0`
- **THEN** 原样返回该 body（不重新包装、不剥离 `data`）

#### Scenario: 登录过期走重登录

- **WHEN** body `code == 2001`
- **THEN** 解析为「登录超时」
- **AND** 经 `window.ipcRenderer.send('reLogin')` 通知重登录
- **AND** 结果为 `null`

#### Scenario: 其他业务错误取 body.message

- **WHEN** body `code != 0` 且 `!= 2001`
- **THEN** reject 出 `{ msg: body.message, showError: true }`

---

## Requirement: 非 2xx 响应须按 body 判业务错误，不得误报网络异常

后端对多数错误码同时返回**非 2xx HTTP 状态**与**标准 `Result<T>` body**
（AGENTS §3.2：400/401/403/404/409 各自对应）。若只按 HTTP 状态判错，
真实业务错误会被统一报成「网络异常，请检查后端是否启动」——**误导排查方向**。

#### Scenario: 非 2xx 但 body 是 Result 结构

- **WHEN** HTTP 403 + body `{"code":2003,"message":"无权限"}`
- **THEN** Toast 文案为 `无权限`
- **AND** **不**显示「网络异常，请检查后端是否启动」

#### Scenario: HTTP 401 + body 携带 2001 同样走重登录

- **WHEN** error 路径且 body 为 `{"code":2001,...}`
- **THEN** 解析为「登录超时」并 `send('reLogin')`
- **AND** **不**误报为「网络异常」

#### Scenario: 无 body 的网络错误才报网络异常

- **WHEN** error 且 `error.response` 为空（连不上后端）
- **THEN** Toast 文案为「网络异常，请检查后端是否启动」

#### Scenario: body 为 null 也算网络异常

- **WHEN** HTTP 500 且 body 为 `null`
- **THEN** Toast 文案为「网络异常，请检查后端是否启动」

---

## Requirement: 二进制响应类型的错误体须先解文本

`responseType = 'blob' | 'arraybuffer'` 时，错误响应体是**二进制**，不能直接当 JSON 解析。
文件下载 / 分片上传走这两条路径，是「下载失败却报网络异常」的根源。

#### Scenario: blob 错误体是 JSON

- **WHEN** `responseType='blob'` 且错误体是 JSON 的 Blob
- **THEN** 从 Blob 读出文本并解析为 `Result`
- **AND** 按业务错误处理（**不**误报网络异常）

#### Scenario: blob 错误体是非 JSON

- **WHEN** `responseType='blob'` 且错误体是 HTML（如网关 502 页）
- **THEN** 回退为「网络异常」文案
- **AND** **不**抛解析异常

#### Scenario: arraybuffer 错误体经 TextDecoder 解析

- **WHEN** `responseType='arraybuffer'` 且错误体是 ArrayBuffer
- **THEN** 经 `TextDecoder` 解出文本后按业务错误处理

#### Scenario: 解码自身失败须回退而非抛出

- **WHEN** blob 的 `text()` reject
- **THEN** 回退为「网络异常」文案
- **AND** **不**把解码异常抛给调用方

---

## Requirement: errorCallback 恰好调用一次

#### Scenario: 成功路径不调用

- **WHEN** 调用方传了 `errorCallback` 且请求成功
- **THEN** `errorCallback` **不**被调用

#### Scenario: 业务错误路径调用一次

- **WHEN** 调用方传了 `errorCallback` 且发生业务错误（HTTP 200 与非 2xx 两条路径）
- **THEN** `errorCallback` **恰好被调用一次**，参数为响应 body
- **AND** 结果仍为 `null`