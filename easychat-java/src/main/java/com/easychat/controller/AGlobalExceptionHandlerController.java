package com.easychat.controller;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 - 将各种异常统一转换为 Result<T> 响应结构
 *
 * 异常映射规则：
 * | 场景                  | HTTP Status | body code |
 * |----------------------|-------------|-----------|
 * | 成功                  | 200         | 0         |
 * | 参数校验失败            | 400         | 1001      |
 * | 业务异常               | 4xx/按异常    | 按异常码     |
 * | 主键冲突               | 409         | 2102      |
 * | 资源不存在              | 404         | 1003      |
 * | 未捕获异常             | 500         | 1002      |
 */
@RestControllerAdvice
public class AGlobalExceptionHandlerController {

    private static final Logger logger = LoggerFactory.getLogger(AGlobalExceptionHandlerController.class);

    /**
     * 业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = ex.getHttpStatus();
        if (status == null) {
            status = inferHttpStatus(ex.getCode());
        }
        return ResponseEntity
                .status(status)
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 参数校验失败（@Valid @RequestBody）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001.getCode(), msg));
    }

    /**
     * 参数绑定失败（@Valid @ModelAttribute）
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001.getCode(), msg));
    }

    /**
     * Bean 校验违反（@NotEmpty @Email 等）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001.getCode(), msg));
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001.getCode(), "参数类型不匹配: " + ex.getName()));
    }

    /**
     * 404 - 资源不存在
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ResponseCodeEnum.CODE_1003));
    }

    /**
     * 主键/唯一索引冲突
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Result.fail(ResponseCodeEnum.CODE_2102));
    }

    /**
     * 未捕获异常（兜底）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex, HttpServletRequest request) {
        logger.error("请求错误，请求地址: {}，错误信息:", request.getRequestURL(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResponseCodeEnum.CODE_1002));
    }

    /**
     * 根据错误码推断 HTTP 状态码
     */
    private HttpStatus inferHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (code >= 2000 && code < 2100) {
            return HttpStatus.UNAUTHORIZED;      // 鉴权域
        }
        if (code == 2003) {
            return HttpStatus.FORBIDDEN;        // 无权限
        }
        if (code >= 2100 && code < 2700) {
            return HttpStatus.BAD_REQUEST;      // 业务域错误统一 400
        }
        return HttpStatus.BAD_REQUEST;
    }
}
