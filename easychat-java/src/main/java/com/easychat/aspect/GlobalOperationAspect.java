package com.easychat.aspect;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisUtils;
import com.easychat.utils.IpTools;
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
                checkRateLimit(interceptor.rateLimitByParam());
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
        // ⚠ 必须用 Boolean.TRUE.equals(...) 而非 !getAdmin()：
        //   TokenUserInfoDto#admin 是**包装类型 Boolean**，为 null 时
        //   `!getAdmin()` 会自动拆箱抛 NullPointerException，被上层 catch(Exception)
        //   兜成 CODE_1002「系统错误」—— 用户看到的是系统错误而不是「无权访问」。
        //   2026-10-14 实测确认：当前三条会话写入路径（登录、昵称同步、token 刷新）
        //   均会 setAdmin，故该 NPE 目前**不可达**；但它属于「一旦历史数据缺字段就炸」的
        //   隐患，且修复成本仅一行，故按防御性修复处理。
        if (checkAdmin && !Boolean.TRUE.equals(tokenUserInfoDto.getAdmin())) {
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
     * <p>2026-10-03 第二次调整：IP 取值规则已抽到 {@link IpTools}（供审计日志共用，
     * 见 openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）。
     * 本类不再自带实现，避免出现「限流按一套规则、审计按另一套规则」而事后对不上账。
     *
     * @see openspec/changes/2026-10-03-password-session-and-mail/design.md ADR-003
     */
    private void checkRateLimit(String rateLimitByParam) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String key = "rate_limit:";
        // 2026-10-12（遗留 #1）：优先按「需要保护的客体」计数（当前是目标邮箱）。
        // 取不到值时**退回 IP 维度**而非空键 —— 空键等于全体匿名用户共用一个配额，
        // 那会把限流变成 DoS 放大器（见下方既有注释）。
        if (!StringTools.isEmpty(rateLimitByParam)) {
            String target = request.getParameter(rateLimitByParam);
            if (!StringTools.isEmpty(target)) {
                String normalized = target.trim().toLowerCase();
                key = key + "email:" + normalized;
                applyRateLimit(key);
                return;
            }
        }
        String token = request.getHeader("token");
        if (!StringTools.isEmpty(token)) {
            key = key + token;
        } else {
            key = key + "ip:" + IpTools.getClientIp();
        }
        applyRateLimit(key);
    }

    /** 阈值判定与窗口设置，供上面两条维度分支共用（改动后只有一处逻辑，不会走偏） */
    private void applyRateLimit(String key) {
        Long count = redisUtils.incr(key);
        if (count != null && count == 1) {
            // 第一次请求，设置过期时间 60 秒
            redisUtils.expire(key, RATE_LIMIT_WINDOW_SECONDS);
        }
        if (count != null && count > RATE_LIMIT_PER_MINUTE) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "请求过于频繁，请稍后再试");
        }
    }
    // 注：原私有方法 resolveClientIp(HttpServletRequest) 已于 2026-10-03 迁至
    //     utils/IpTools#getClientIp()，与审计日志共用同一实现（design.md C2 / ADR-002）。
}