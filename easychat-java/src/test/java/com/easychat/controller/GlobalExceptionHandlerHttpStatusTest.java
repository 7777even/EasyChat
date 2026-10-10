package com.easychat.controller;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全局异常处理器「错误码 → HTTP Status」映射契约测试。
 *
 * <p>背景：{@code AGlobalExceptionHandlerController#inferHttpStatus} 是纯分支逻辑，
 * 此前<b>零测试覆盖</b>，而 AGENTS §3.2 把 HTTP 语义列为硬契约
 * （成功 200 / 参数错误 400 / 未认证 401 / 无权限 403 / 资源不存在 404 / 冲突 409 / 系统错误 500）。
 * 该方法一旦分支顺序写错，<b>不抛任何异常</b>、只在客户端表现为「该跳登录时跳登录、
 * 该提示无权限时却提示登录过期」——属静默失效。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>2000-2099 鉴权域 → 401</li>
 *   <li>2003 无权限 → 403（枚举里明确定义了「无权限操作」，契约要求 403）</li>
 *   <li>2100-2699 业务域 → 400</li>
 *   <li>其它（1001/1002/1003）→ 400（兜底）</li>
 *   <li>null 码 → 500</li>
 *   <li>显式指定 httpStatus 的构造器 → 以显式值为准，<b>不走推断</b></li>
 * </ul>
 */
class GlobalExceptionHandlerHttpStatusTest {

    private final AGlobalExceptionHandlerController handler = new AGlobalExceptionHandlerController();

    private HttpStatus statusOf(Integer code) {
        BusinessException ex = new BusinessException(code, "测试");
        ResponseEntity<?> resp = handler.handleBusiness(ex);
        return resp.getStatusCode();
    }

    @Test
    @DisplayName("鉴权域 2000-2099 → 401（登录过期 / Token 无效）")
    void authDomainMapsTo401() {
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(ResponseCodeEnum.CODE_2001.getCode()));
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(ResponseCodeEnum.CODE_2002.getCode()));
    }

    @Test
    @DisplayName("2003 无权限 → 403（不得回落成 401，否则客户端误跳登录）")
    void noPermissionMapsTo403() {
        assertEquals(HttpStatus.FORBIDDEN, statusOf(ResponseCodeEnum.CODE_2003.getCode()));
    }

    @Test
    @DisplayName("业务域 2100-2699 → 400")
    void businessDomainMapsTo400() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ResponseCodeEnum.CODE_2102.getCode()));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ResponseCodeEnum.CODE_2101.getCode()));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ResponseCodeEnum.CODE_2301.getCode()));
    }

    @Test
    @DisplayName("通用域 1001/1003 → 400 兜底")
    void commonDomainMapsTo400() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ResponseCodeEnum.CODE_1001.getCode()));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ResponseCodeEnum.CODE_1003.getCode()));
    }

    @Test
    @DisplayName("码为 null → 500（无法推断时不得当成参数错误）")
    void nullCodeMapsTo500() {
        BusinessException ex = new BusinessException((Integer) null, "测试");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, handler.handleBusiness(ex).getStatusCode());
    }

    @Test
    @DisplayName("显式指定 httpStatus 时以显式值为准，不被推断覆盖")
    void explicitHttpStatusWins() {
        BusinessException ex = new BusinessException(HttpStatus.CONFLICT, ResponseCodeEnum.CODE_1001);
        assertEquals(HttpStatus.CONFLICT, handler.handleBusiness(ex).getStatusCode());
    }

    @Test
    @DisplayName("响应体沿用异常自身的 code 与 message（不被处理器改写）")
    void bodyKeepsOriginalCodeAndMessage() {
        BusinessException ex = new BusinessException(ResponseCodeEnum.CODE_1003, "群组不存在");
        ResponseEntity<Result<Void>> resp = handler.handleBusiness(ex);
        assertEquals(ResponseCodeEnum.CODE_1003.getCode(), resp.getBody().getCode());
        assertEquals("群组不存在", resp.getBody().getMessage());
    }

    @Test
    @DisplayName("未捕获异常兜底 → 500 且 body 为 1002（不得把内部错误暴露成 500 裸页）")
    void unexpectedExceptionMapsTo500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/account/login");
        ResponseEntity<Result<Void>> resp = handler.handleException(new RuntimeException("boom"), request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(ResponseCodeEnum.CODE_1002.getCode(), resp.getBody().getCode());
    }
}
