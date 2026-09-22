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
    baseURL: (import.meta.env.PROD ? Api.prodDomain : "") + "/api",
    timeout: 10 * 1000,
});

// ======================== 错误码枚举（与后端同步） ========================
export const ErrorCode = {
    SUCCESS: 0,
    PARAM_ERROR: 1001,
    SYSTEM_ERROR: 1002,
    NOT_FOUND: 1003,
    TOKEN_EXPIRED: 2001,
    TOKEN_INVALID: 2002,
    FORBIDDEN: 2003,
    USER_NOT_FOUND: 2101,
    USER_EXISTS: 2102,
    PASSWORD_ERROR: 2103,
    FILE_NOT_FOUND: 2104,
    // 向后兼容旧码
    LEGACY_TOKEN_EXPIRED: 901,
    LEGACY_NOT_FRIEND: 902,
    LEGACY_NOT_IN_GROUP: 903,
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
        else if (responseData.code == ErrorCode.TOKEN_EXPIRED || responseData.code == ErrorCode.LEGACY_TOKEN_EXPIRED) {
            setTimeout(() => {
                window.ipcRenderer.send('reLogin')
            }, 2000);
            return Promise.reject({ showError: true, msg: "登录超时" });
        }
        // 其他错误
        else {
            if (errorCallback) {
                errorCallback(responseData);
            }
            return Promise.reject({ showError: showError, msg: responseData.message || responseData.info || '请求失败' });
        }
    },
    (error) => {
        if (error.config && error.config.showLoading && loading) {
            loading.close();
        }
        // 开发环境打印详细错误
        if (error && error.config) {
            const { url, data, params } = error.config;
            console.error('[Network Error]', url, { data, params });
        }
        return Promise.reject({ showError: true, msg: "网络异常，请检查后端是否启动" })
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
