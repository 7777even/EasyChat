package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.redis.RedisUtils;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controller 行为测试的共享装配工具。
 *
 * <p>抽出来是因为每个控制器测试都要重复同一段装配，而这段装配里藏着一个易踩的坑：
 * {@code ABaseController.redisUtils} 是<b>基类私有字段</b>，
 * {@code @InjectMocks} 会注入继承字段——不提供它就会在 {@code getTokenUserInfo} 处 NPE，
 * 表现为整片 500，看起来极像业务缺陷（首版 ChatController 测试即踩中）。
 */
final class MockMvcSupport {

    static final String TOKEN = "tok-abc";
    static final String USER_ID = "U001";

    private MockMvcSupport() {
    }

    /** 构造带全局异常处理器的 MockMvc，并把会话用户接到基类的 redisUtils 上 */
    static MockMvc mockMvc(Object controller, String userId) {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
        return mvc;
    }

    /** 造一个会话用户对象（测试里断言「身份来自会话」时需要比对 userId） */
    static TokenUserInfoDto sessionUser() {
        return sessionUser(USER_ID, TOKEN, "小明");
    }

    static TokenUserInfoDto sessionUser(String userId, String token, String nickName) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setToken(token);
        dto.setUserId(userId);
        dto.setNickName(nickName);
        dto.setAdmin(false);
        return dto;
    }

    /** 把会话挂到 controller 已注入的 redisUtils 桩上（需与被测类用同一 mock 实例） */
    static void stubSession(RedisUtils redisUtils, TokenUserInfoDto session) {
        when(redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + TOKEN)).thenReturn(session);
    }
}
