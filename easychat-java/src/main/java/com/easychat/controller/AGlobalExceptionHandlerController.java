package com.easychat.controller;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
     * 405 - 请求方法不允许（如用 GET 调 @PostMapping 端点）
     *
     * ⚠ 必须显式处理：兜底的 {@code @ExceptionHandler(Exception.class)} 优先级高于 Spring 的
     *   {@code DefaultHandlerExceptionResolver}（ExceptionHandlerExceptionResolver 先执行），
     *   一旦落入兜底，本该 405 的客户端错误会被报成 <b>500 系统错误</b>，监控与客户端都会误判
     *   成服务端故障。2026-10-10 由 AccountControllerMockMvcTest#loginRejectsGet 实证抓出。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ResponseCodeEnum.CODE_1001, "请求方法不支持: " + ex.getMethod()));
    }

    /**
     * 415 - 媒体类型不支持（如用 form 提交只收 JSON 的端点）
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(ResponseCodeEnum.CODE_1001, "媒体类型不支持: " + ex.getContentType()));
    }

    /**
     * 400 - 消息体不可读（@RequestBody 收到畸形 JSON / 空体）
     *
     * ⚠ 同样不能落入兜底：客户端传错报文是参数问题，报 500 会掩盖真实故障面。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001, "请求体格式错误"));
    }

    /**
     * 400 - 缺少必填请求参数（如漏传必填的 @RequestParam）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResponseCodeEnum.CODE_1001, "缺少必填参数: " + ex.getParameterName()));
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
        // ⚠ 2003 必须**先于**下面的区间判断：2003 落在 [2000,2100) 内，
        //   若按区间先命中就会返回 401，客户端会把「无权限」误当成「登录过期」而跳登录
        //   （AGENTS §3.2 要求无权限为 403）。2026-10-10 由 GlobalExceptionHandlerHttpStatusTest 抓出。
        if (code == ResponseCodeEnum.CODE_2003.getCode()) {
            return HttpStatus.FORBIDDEN;        // 无权限
        }
        if (code >= 2000 && code < 2100) {
            return HttpStatus.UNAUTHORIZED;      // 鉴权域
        }
        if (code >= 2100 && code < 2700) {
            return HttpStatus.BAD_REQUEST;      // 业务域错误统一 400
        }
        return HttpStatus.BAD_REQUEST;
    }
}
