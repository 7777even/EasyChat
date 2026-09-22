package com.easychat.exception;

import com.easychat.entity.enums.ResponseCodeEnum;
import org.springframework.http.HttpStatus;

/**
 * 业务异常 - 携带业务错误码 + 可选 HTTP 状态码
 *
 * 用法：
 * 1. 仅业务错误码：throw new BusinessException(ResponseCodeEnum.CODE_2101)
 * 2. 自定义错误消息：throw new BusinessException(ResponseCodeEnum.CODE_1001, "邮箱格式不正确")
 * 3. 指定 HTTP 状态码：throw new BusinessException(HttpStatus.UNAUTHORIZED, ResponseCodeEnum.CODE_2001)
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    private final String message;

    /** HTTP 状态码（可选，默认由全局异常处理器根据错误码段推断） */
    private final HttpStatus httpStatus;

    // ======================== 构造器 ========================

    public BusinessException(String message) {
        super(message);
        this.code = ResponseCodeEnum.CODE_1001.getCode();
        this.message = message;
        this.httpStatus = null;
    }

    public BusinessException(String message, Throwable e) {
        super(message, e);
        this.code = ResponseCodeEnum.CODE_1001.getCode();
        this.message = message;
        this.httpStatus = null;
    }

    public BusinessException(Throwable e) {
        super(e);
        this.code = ResponseCodeEnum.CODE_1002.getCode();
        this.message = ResponseCodeEnum.CODE_1002.getMsg();
        this.httpStatus = null;
    }

    public BusinessException(ResponseCodeEnum codeEnum) {
        super(codeEnum.getMsg());
        this.code = codeEnum.getCode();
        this.message = codeEnum.getMsg();
        this.httpStatus = null;
    }

    public BusinessException(ResponseCodeEnum codeEnum, String customMessage) {
        super(customMessage);
        this.code = codeEnum.getCode();
        this.message = customMessage;
        this.httpStatus = null;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.httpStatus = null;
    }

    /**
     * 携带 HTTP 状态码的构造器（用于需要精确控制状态码的场景）
     */
    public BusinessException(HttpStatus httpStatus, ResponseCodeEnum codeEnum) {
        super(codeEnum.getMsg());
        this.code = codeEnum.getCode();
        this.message = codeEnum.getMsg();
        this.httpStatus = httpStatus;
    }

    public BusinessException(HttpStatus httpStatus, ResponseCodeEnum codeEnum, String customMessage) {
        super(customMessage);
        this.code = codeEnum.getCode();
        this.message = customMessage;
        this.httpStatus = httpStatus;
    }

    public BusinessException(HttpStatus httpStatus, Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    // ======================== Getter ========================

    public Integer getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 重写 fillInStackTrace - 业务异常不需要堆栈信息，提高效率
     */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    // ======================== 向后兼容（遗留代码使用） ========================

    /**
     * @deprecated 使用 {@link #getCode()} 替代
     */
    @Deprecated
    public ResponseCodeEnum getCodeEnum() {
        return null;
    }
}
