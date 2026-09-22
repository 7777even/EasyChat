package com.easychat.entity.vo;

import com.easychat.entity.enums.ResponseCodeEnum;

/**
 * 统一响应包络 - 所有业务响应统一使用此结构
 * <p>
 * JSON 结构:
 * {
 *   "code": 0,       // 0=成功；非零=业务细分码
 *   "message": "success",
 *   "data": {}
 * }
 */
public class Result<T> {

    /** 业务状态码：0=成功；非零=业务错误细分码 */
    private Integer code;

    /** 人类可读提示 */
    private String message;

    /** 载荷：成功为业务数据；失败可为 null */
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ======================== 成功 ========================

    public static <T> Result<T> success() {
        return new Result<>(ResponseCodeEnum.CODE_200.getCode(), ResponseCodeEnum.CODE_200.getMsg(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResponseCodeEnum.CODE_200.getCode(), ResponseCodeEnum.CODE_200.getMsg(), data);
    }

    // ======================== 失败 ========================

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(ResponseCodeEnum codeEnum) {
        return new Result<>(codeEnum.getCode(), codeEnum.getMsg(), null);
    }

    public static <T> Result<T> fail(ResponseCodeEnum codeEnum, String customMessage) {
        return new Result<>(codeEnum.getCode(), customMessage, null);
    }

    // ======================== Getter / Setter ========================

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
