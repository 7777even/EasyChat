import axios from 'axios'
import { ElLoading } from 'element-plus'
import Message from '../utils/Message'
import Api from '../utils/Api'

const contentTypeForm = 'application/x-www-form-urlencoded;charset=UTF-8'
const contentTypeJson = 'application/json'
const responseTypeJson = 'json'

let loading = null;

const instance = axios.create({
    withCredentials: true,
    baseURL: (import.meta.env.PROD ? Api.prodDomain : Api.devDomain) + "/api",
    timeout: 10 * 1000,
});

// ======================== 错误码枚举（与后端同步） ========================
// ⚠ 双向对账由门禁 scripts/verify/verify_error_code.mjs 锁住：
//   本枚举与后端 ResponseCodeEnum 任一侧多出项都报红。
//   2026-10-14 起新增 1004/1005（error-code-split L4）。
export const ErrorCode = {
    SUCCESS: 0,
    PARAM_ERROR: 1001,
    SYSTEM_ERROR: 1002,
    NOT_FOUND: 1003,
    /** 请求过于频繁（API 限流）。2026-10-14 独立成码：此前与 1001 共用，
     *  客户端无法区分「需要等待」与「需要改输入」。 */
    RATE_LIMITED: 1004,
    /** 验证码错误或已过期。2026-10-14 独立成码：此前与 1001 共用，
     *  客户端无法识别「验证码已作废、需重新获取」。 */
    CAPTCHA_ERROR: 1005,
    TOKEN_EXPIRED: 2001,
    TOKEN_INVALID: 2002,
    FORBIDDEN: 2003,
    USER_NOT_FOUND: 2101,
    USER_EXISTS: 2102,
    PASSWORD_ERROR: 2103,
    FILE_NOT_FOUND: 2104,
};

/**
 * 服务端限流窗口秒数。
 *
 * <p>⚠ **刻意与后端同名**（`GlobalOperationAspect.RATE_LIMIT_WINDOW_SECONDS`），
 * 使门禁能做**静态对账**：两者一旦漂移即报红。
 * 不为此给响应体加字段 —— 传一个服务端已知的常量属「用契约膨胀换零信息」（ADR-002）。
 */
export const RATE_LIMIT_WINDOW_SECONDS = 60;

/**
 * 构造失败拒绝对象。
 *
 * <p>🔴 **向后兼容的关键**：老版本客户端不认识 1004/1005，会落入默认分支，
 *   仍按 `message` 弹出提示 —— 与变更前**完全一致**。
 *   因此本函数只在**新增**字段，不改变既有 `showError` / `msg` 的语义。
 *
 * @param {object} responseData 后端 Result 结构
 * @param {boolean} showError   是否弹 Toast
 * @returns {Promise} reject 对象，含 showError / msg / code，限流时含 rateLimitSeconds
 */
const buildReject = (responseData, showError) => {
    const code = responseData.code;
    const base = {
        showError,
        msg: responseData.message || responseData.info || '请求失败',
        code,
    };
    if (code === ErrorCode.RATE_LIMITED) {
        // 让调用方据此禁用按钮并倒计时，而不是让用户干等
        base.rateLimitSeconds = RATE_LIMIT_WINDOW_SECONDS;
    }
    return Promise.reject(base);
};

// ======================== 请求前拦截器 ========================
instance.interceptors.request.use(
    (config) => {
        if (config.showLoading) {
            loading = ElLoading.service({
                lock: true,
                text: '加载中......',
                background: 'rgba(0, 0, 0, 0.7)',
            });
        }
        return config;
    },
    (error) => {
        if (error.config.showLoading && loading) {
            loading.close();
        }
        Message.error("请求发送失败");
        return Promise.reject("请求发送失败");
    }
);

// ======================== 请求后拦截器 ========================
instance.interceptors.response.use(
    (response) => {
        const { showLoading, errorCallback, showError = true, responseType } = response.config;
        if (showLoading && loading) {
            loading.close()
        }
        const responseData = response.data;
        if (responseType == "arraybuffer" || responseType == "blob") {
            return responseData;
        }

        // 正常请求成功
        if (responseData.code == 0) {
            return responseData;
        }
        // Token 过期 -> 跳转登录
        else if (responseData.code == ErrorCode.TOKEN_EXPIRED) {
            setTimeout(() => {
                window.ipcRenderer.send('reLogin')
            }, 2000);
            return Promise.reject({ showError: true, msg: "登录超时" });
        }
        // 其他错误（含 1001 / 1004 / 1005 等）
        // ⚠ 老客户端不认识 1004/1005 时也走这里 → 行为与变更前完全一致（向后兼容）
        else {
            if (errorCallback) {
                errorCallback(responseData);
            }
            return buildReject(responseData, showError);
        }
    },
    (error) => {
        if (error.config && error.config.showLoading && loading) {
            loading.close();
        }
        const reqCfg = error.config;
        // HTTP 非 2xx 但后端返回了 Result 结构（如 400/401/403 + {code, message}）：
        // 按业务错误处理，避免把真实错误信息误报为网络异常
        const rejectByBody = (responseData) => {
            if (responseData && responseData.code !== undefined) {
                if (responseData.code == ErrorCode.TOKEN_EXPIRED) {
                    setTimeout(() => {
                        window.ipcRenderer.send('reLogin')
                    }, 2000);
                    return Promise.reject({ showError: true, msg: "登录超时" });
                }
                const showError = reqCfg ? reqCfg.showError !== false : true;
                if (reqCfg && reqCfg.errorCallback) {
                    reqCfg.errorCallback(responseData);
                }
                return buildReject(responseData, showError);
            }
            // 开发环境打印详细错误
            if (error && reqCfg) {
                const { url, data, params } = reqCfg;
                console.error('[Network Error]', url, { data, params });
            }
            return Promise.reject({ showError: true, msg: "网络异常，请检查后端是否启动" })
        };

        // blob / arraybuffer 请求（如 CSV 导出）的错误体是二进制而非 JSON，
        // 需先按文本解出再判断 Result 结构，否则真实错误码会被误报为网络异常
        const responseType = reqCfg && reqCfg.responseType;
        const rawData = error.response && error.response.data;
        if ((responseType === 'blob' || responseType === 'arraybuffer') && rawData) {
            let textPromise;
            if (typeof rawData === 'string') {
                textPromise = Promise.resolve(rawData);
            } else if (typeof rawData.text === 'function') {
                textPromise = rawData.text();
            } else {
                try {
                    textPromise = Promise.resolve(new TextDecoder().decode(rawData));
                } catch (e) {
                    textPromise = Promise.resolve(null);
                }
            }
            return textPromise.then(
                (text) => {
                    let parsed = null;
                    try {
                        parsed = text ? JSON.parse(text) : null;
                    } catch (e) {
                        // 非 JSON 错误体（如网关 HTML 页），按网络异常处理
                    }
                    return rejectByBody(parsed);
                },
                () => rejectByBody(null)
            );
        }
        return rejectByBody(rawData);
    }
);

// ======================== 请求封装 ========================
const request = (config) => {
    const { url, params, dataType, method = 'POST', showLoading = true, responseType = responseTypeJson, showError = true } = config;
    let contentType = contentTypeForm;
    let formData;

    // 如果 params 已经是 FormData，直接使用
    if (params instanceof FormData) {
        formData = params;
        contentType = 'multipart/form-data';
    } else {
        formData = new FormData();
        if (params) {
            for (let key in params) {
                formData.append(key, params[key] == undefined ? "" : params[key]);
            }
        }
        if (dataType != null && dataType == 'json') {
            contentType = contentTypeJson;
        }
    }

    const token = localStorage.getItem('token')
    let headers = {
        'X-Requested-With': 'XMLHttpRequest',
        "token": token
    }

    if (contentType !== 'multipart/form-data') {
        headers['Content-Type'] = contentType;
    }

    const reqConfig = {
        headers: headers,
        showLoading: showLoading,
        errorCallback: config.errorCallback,
        showError: showError,
        responseType: responseType
    };

    if (method.toLowerCase() === 'get') {
        return instance.get(url, {
            ...reqConfig,
            params: params
        }).catch(error => {
            if (error.showError) {
                Message.error(error.msg);
            }
            return null;
        });
    }

    return instance.post(url, formData, reqConfig).catch(error => {
        if (error.showError) {
            Message.error(error.msg);
        }
        return null;
    });
};

export default request;
