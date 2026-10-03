package com.easychat.aspect;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisUtils;
import com.easychat.utils.StringTools;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

@Component("operationAspect")
@Aspect
public class GlobalOperationAspect {

    /**
     * 每分钟每维度（token 或 IP）的请求上限。
     * 提为常量而非散落的字面量，便于单测直接对齐阈值（{@code GlobalOperationAspectTest#RATE_LIMIT}）。
     */
    static final int RATE_LIMIT_PER_MINUTE = 60;

    /**
     * 限流窗口长度（秒）。
     */
    static final int RATE_LIMIT_WINDOW_SECONDS = 60;

    @Resource
    private RedisUtils redisUtils;

    private static Logger logger = LoggerFactory.getLogger(GlobalOperationAspect.class);


    @Before("@annotation(com.easychat.annotation.GlobalInterceptor)")
    public void interceptorDo(JoinPoint point) {
        try {
            Method method = ((MethodSignature) point.getSignature()).getMethod();
            GlobalInterceptor interceptor = method.getAnnotation(GlobalInterceptor.class);
            if (null == interceptor) {
                return;
            }
            /**
             * 校验登录
             */
            if (interceptor.checkLogin() || interceptor.checkAdmin()) {
                checkLogin(interceptor.checkAdmin());
            }
            /**
             * API 限流
             */
            if (interceptor.checkRateLimit()) {
                checkRateLimit();
            }
        } catch (BusinessException e) {
            logger.error("全局拦截器异常", e);
            throw e;
        } catch (Exception e) {
            logger.error("全局拦截器异常", e);
            throw new BusinessException(ResponseCodeEnum.CODE_1002);
        } catch (Throwable e) {
            logger.error("全局拦截器异常", e);
            throw new BusinessException(ResponseCodeEnum.CODE_1002);
        }
    }

    //校验登录
    private void checkLogin(Boolean checkAdmin) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String token = request.getHeader("token");
        TokenUserInfoDto tokenUserInfoDto = (TokenUserInfoDto) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + token);
        if (tokenUserInfoDto == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2001);
        }
        if (checkAdmin && !tokenUserInfoDto.getAdmin()) {
            throw new BusinessException(ResponseCodeEnum.CODE_1003);
        }
    }

    /**
     * API 限流：每分钟最多 60 次请求
     *
     * <p><b>2026-10-03 修复</b>：原实现开头是
     * {@code if (token == null) { return; }}，即<b>没有 token 就直接放行</b>。
     * 而全仓标注 {@code checkRateLimit = true} 的 4 个端点（login / register /
     * sendEmailCode / resetPassword）恰好全是 {@code checkLogin = false} 的未登录端点，
     * 前端 {@code Request.js} 在登录页 {@code localStorage.getItem('token')} 为 null、
     * axios 会丢弃 null header，于是这 4 个端点的限流<b>从未生效</b>——
     * 不抛异常、不打警告，常规冒烟与 Service 层单测都照不到。
     *
     * <p>现改为：token 存在时按 token 计数（保持原语义，登录态端点不受影响）；
     * token 缺失时**降级为按客户端 IP 计数**，而不是静默放行。
     * 刻意不用「全体匿名用户共用一个空前缀键」——那等于把限流变成 DoS 放大器。
     *
     * @see openspec/changes/2026-10-03-password-session-and-mail/design.md ADR-003
     */
    private void checkRateLimit() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String token = request.getHeader("token");
        String key = "rate_limit:";
        if (!StringTools.isEmpty(token)) {
            key = key + token;
        } else {
            key = key + "ip:" + resolveClientIp(request);
        }
        Long count = redisUtils.incr(key);
        if (count != null && count == 1) {
            // 第一次请求，设置过期时间 60 秒
            redisUtils.expire(key, RATE_LIMIT_WINDOW_SECONDS);
        }
        if (count != null && count > RATE_LIMIT_PER_MINUTE) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "请求过于频繁，请稍后再试");
        }
    }

    /**
     * 解析客户端 IP，优先取反向代理透传的 {@code X-Forwarded-For} 首段
     * （即最初发起请求的客户端），缺失时回退 {@link HttpServletRequest#getRemoteAddr()}。
     *
     * <p>⚠️ {@code X-Forwarded-For} 由客户端可伪造，仅当服务部署在可信反向代理
     * 之后该值才可信。直连部署时攻击者可伪造此头换取多个独立限流配额——
     * 这是「用 IP 限流」的固有代价，取舍见 design ADR-003（本次不叠加更重的方案）。
     *
     * @return 客户端 IP；取不到时返回 {@code "unknown"}（仍参与计数，避免退化为放行）
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (!StringTools.isEmpty(forwarded)) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            first = first.trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return StringTools.isEmpty(remote) ? "unknown" : remote;
    }
}