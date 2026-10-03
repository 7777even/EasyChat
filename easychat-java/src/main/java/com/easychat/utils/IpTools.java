package com.easychat.utils;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 工具。
 *
 * <p><b>为什么单独抽一个类</b>：客户端 IP 的取值规则会被两处消费——
 * {@code GlobalOperationAspect#checkRateLimit}（限流维度）与
 * {@code OperationLogServiceImpl#recordLog}（审计溯源）。
 * 规则一旦有两份实现，改了一处忘了另一处，就会出现「限流按 A 规则、审计按 B 规则」，
 * 事后对不上账。故收敛到本类作为唯一实现（design.md C2）。
 *
 * <p><b>与 AGENTS §3.4 的关系</b>：红线是「Service 不感知 HttpServletRequest」。
 * 本类把 request 的读取**封在工具内部**，因此调用方
 * （{@code OperationLogServiceImpl} / {@code GlobalOperationAspect}）代码里
 * 不会出现任何 {@code HttpServletRequest}，业务逻辑仍可脱离 Web 层单测（design.md ADR-002）。
 *
 * @since 2026-10-03（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）
 */
public class IpTools {

    /**
     * 取不到任何 IP 时的占位值。
     *
     * <p><b>刻意用固定串而非 null / 空串</b>：
     * 若返回空串，限流 key 会退化成 {@code rate_limit:ip:} —— 全体客户端共用一个 key，
     * 等于限流彻底失效（比「限流不准」更糟）。占位值让「取不到」在数据上可与「取到了」区分。
     */
    public static final String UNKNOWN = "-";

    /**
     * 请求头名：反向代理透传的客户端 IP 链。
     */
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 获取客户端 IP。
     *
     * <p>取值顺序：
     * <ol>
     *   <li>{@code X-Forwarded-For} 的<b>首段</b>（多跳时首段才是最初发起请求的客户端）；</li>
     *   <li>回退 {@code HttpServletRequest#getRemoteAddr()}（直连场景）；</li>
     *   <li>再取不到则返回 {@link #UNKNOWN}。</li>
     * </ol>
     *
     * <p><b>本方法保证不抛异常</b>：非 HTTP 线程调用时 {@code RequestContextHolder} 返回 null，
     * 必须返回占位值而不是 NPE——因为 {@code recordLog} 的外层 catch 会把异常吞掉，
     * 表现为「审计日志静默丢失」，那比抛异常更难发现。
     *
     * <p>⚠️ <b>{@code X-Forwarded-For} 由客户端可伪造</b>。仅当服务部署在<b>可信反向代理</b>之后
     * 该值才可信；直连部署时攻击者可伪造此头获取多个独立限流配额。
     * 因此本值**只作审计线索与限流维度，不作为任何授权依据**（design.md §7 遗留项）。
     *
     * @return 客户端 IP；不可得时返回 {@link #UNKNOWN}
     */
    public static String getClientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() == null) {
                return UNKNOWN;
            }
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            if (request == null) {
                return UNKNOWN;
            }
            String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
            String fromHeader = firstSegment(forwarded);
            if (!StringTools.isEmpty(fromHeader)) {
                return fromHeader;
            }
            String remote = request.getRemoteAddr();
            return StringTools.isEmpty(remote) ? UNKNOWN : remote;
        } catch (Exception e) {
            // 取 IP 失败绝不能影响主流程（限流 / 审计都是旁路能力）
            return UNKNOWN;
        }
    }

    /**
     * 取 {@code X-Forwarded-For} 的首段并裁掉空白；入参为空或首段为空白时返回空串。
     */
    private static String firstSegment(String forwarded) {
        if (StringTools.isEmpty(forwarded)) {
            return "";
        }
        int comma = forwarded.indexOf(',');
        String first = comma >= 0 ? forwarded.substring(0, comma) : forwarded;
        return first == null ? "" : first.trim();
    }
}