package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.redis.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ABaseController} 基类自身的行为测试。
 *
 * <p>基类只有三件事，但每一件都是<b>全局地基</b>：① 统一响应封装（AGENTS §3.1 禁止第二套外壳）；
 * ② 从 {@code token} 头取会话；③ 刷新会话有效期。
 * 它们被 23 个控制器继承，任何一处退化都是**全站级**故障，故单独立测——
 * 此前它只被各控制器的间接使用覆盖，从未被直接断言过。
 */
class ABaseControllerTest {

    /** 用一个最小子类暴露 protected 方法（基类本身无端点） */
    static class Probe extends ABaseController {
        Result<String> callSuccessWithData(String data) {
            return success(data);
        }

        Result<Void> callSuccessWithoutData() {
            return success();
        }

        TokenUserInfoDto callGetTokenUserInfo(MockHttpServletRequest request) {
            return getTokenUserInfo(request);
        }

        void callResetTokenUserInfo(MockHttpServletRequest request, TokenUserInfoDto dto) {
            resetTokenUserInfo(request, dto);
        }
    }

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final Probe probe = new Probe();

    private Probe probeWithRedis() {
        try {
            java.lang.reflect.Field f = ABaseController.class.getDeclaredField("redisUtils");
            f.setAccessible(true);
            f.set(probe, redisUtils);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return probe;
    }

    @Test
    @DisplayName("success(data) → code=0 且 data 原样返回（统一包络，不得出现第二套外壳）")
    void successWithDataUsesZeroCode() {
        Result<String> r = probe.callSuccessWithData("hi");
        assertEquals(Integer.valueOf(0), r.getCode());
        assertEquals("hi", r.getData());
    }

    @Test
    @DisplayName("success() → code=0 且 data 为 null")
    void successWithoutData() {
        Result<Void> r = probe.callSuccessWithoutData();
        assertEquals(Integer.valueOf(0), r.getCode());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("getTokenUserInfo：按 token 头取会话，键前缀正确")
    void getTokenUserInfoUsesTokenHeader() {
        Probe p = probeWithRedis();
        TokenUserInfoDto session = MockMvcSupport.sessionUser();
        when(redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + MockMvcSupport.TOKEN)).thenReturn(session);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("token", MockMvcSupport.TOKEN);

        assertSame(session, p.callGetTokenUserInfo(request));
        verify(redisUtils).get(Constants.REDIS_KEY_WS_TOKEN + MockMvcSupport.TOKEN);
    }

    @Test
    @DisplayName("getTokenUserInfo：token 缺失 → 返回 null（判无效由拦截器负责）")
    void getTokenUserInfoReturnsNullWithoutHeader() {
        Probe p = probeWithRedis();
        // 现状：并不会因缺 token 头而短路，而是用「前缀 + null」拼键查一次 Redis
        // （无 token 的请求本就该被 @GlobalInterceptor 拦掉，故这只是**一次冗余查询**，
        //   不是安全缺陷；此处按真实行为锁定，避免写出「因假设不符而恒红」的断言）
        assertNull(p.callGetTokenUserInfo(new MockHttpServletRequest()));
        verify(redisUtils).get(Constants.REDIS_KEY_WS_TOKEN + "null");
    }

    @Test
    @DisplayName("resetTokenUserInfo：以两倍过期期回写会话（续期语义，键前缀一致）")
    void resetTokenUserInfoExtendsExpiry() {
        Probe p = probeWithRedis();
        TokenUserInfoDto dto = MockMvcSupport.sessionUser();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("token", MockMvcSupport.TOKEN);

        p.callResetTokenUserInfo(request, dto);

        // setex 第三参是原始 long，匹配值也必须是 long（写 eq(30) 会被 Integer 静默不匹配）
        verify(redisUtils).setex(eq(Constants.REDIS_KEY_WS_TOKEN + MockMvcSupport.TOKEN),
                eq(dto), eq((long) Constants.REDIS_KEY_EXPIRES_DAY * 2));
    }

    @Test
    @DisplayName("COOKIE_KEY_TOKEN 常量为 'token'（与前端请求头名一致，改动即全站掉线）")
    void cookieKeyTokenConstant() {
        assertEquals("token", ABaseController.COOKIE_KEY_TOKEN);
    }
}
