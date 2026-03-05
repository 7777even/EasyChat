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
//请求前拦截器
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
//请求后拦截器
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

        //正常请求
        if (responseData.code == 200) {
            return responseData;
        } else if (responseData.code == 901) {
            //登录超时
            setTimeout(() => {
                window.ipcRenderer.send('reLogin')
            }, 2000);
            return Promise.reject({ showError: true, msg: "登录超时" });

        } else {
            //其他错误
            if (errorCallback) {
                errorCallback(responseData);
            }
            return Promise.reject({ showError: showError, msg: responseData.info });
        }
    },
    (error) => {
        if (error.config.showLoading && loading) {
            loading.close();
        }
        return Promise.reject({ showError: true, msg: "网络异常" })
    }
);

const request = (config) => {
    const { url, params, dataType, showLoading = true, responseType = responseTypeJson, showError = true } = config;
    let contentType = contentTypeForm;
    let requestData;
    
    // 如果 params 已经是 FormData，直接使用
    if (params instanceof FormData) {
        requestData = params;
        contentType = 'multipart/form-data'; // 让浏览器自动设置正确的 Content-Type（包含 boundary）
    } else if (dataType != null && dataType == 'json') {
        // JSON 请求：直接发送 JSON 对象，过滤掉 undefined 值
        contentType = contentTypeJson;
        requestData = {};
        for (let key in params) {
            if (params[key] !== undefined) {
                requestData[key] = params[key];
            }
        }
    } else {
        // 表单请求：创建 FormData
        requestData = new FormData();
        for (let key in params) {
            requestData.append(key, params[key] == undefined ? "" : params[key]);
        }
    }
    
    const token = localStorage.getItem('token')
    let headers = {
        'X-Requested-With': 'XMLHttpRequest',
        "token": token
    }
    
    // 如果是 multipart/form-data，不设置 Content-Type，让浏览器自动设置
    if (contentType !== 'multipart/form-data') {
        headers['Content-Type'] = contentType;
    }
    
    return instance.post(url, requestData, {
        headers: headers,
        showLoading: showLoading,
        errorCallback: config.errorCallback,
        showError: showError,
        responseType: responseType
    }).catch(error => {
        if (error.showError) {
            Message.error(error.msg);
        }
        return null;
    });
};

export default request;
