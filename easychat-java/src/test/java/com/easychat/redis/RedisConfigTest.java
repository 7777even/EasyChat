package com.easychat.redis;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link RedisConfig#redissonClient()} 的失败行为验证。
 *
 * <p>🔴 <b>实测发现的既有缺陷</b>：原实现 catch 的是
 * {@code io.lettuce.core.RedisConnectionException}（Lettuce 的类型），
 * 而 {@code Redisson.create(config)} 抛的是
 * {@code org.redisson.client.RedisConnectionException}（Redisson 自己的类型）——
 * <b>两个是不同的类</b>，catch <b>永不触发</b>。后果：
 * <ol>
 *   <li>那句 {@code logger.error("redis配置错误，请检查redis配置")} <b>是死代码</b></li>
 *   <li>末尾的 {@code return null} <b>不可达</b>（只有 catch 块能走到）</li>
 *   <li>Redis 不可用时调用方拿到的是 Redisson 的<b>原始</b>异常，排障时无从判断
 *       「这是配置问题还是服务没起来、连的到底是哪个地址」</li>
 * </ol>
 *
 * <p>⚠ <b>为什么修法是「快速失败 + 可诊断信息」而不是注释暗示的「降级」</b>：
 * {@code MessageHandler} / {@code RedisComponet} / {@code RedissonUtils} 都依赖本 Bean，
 * 返回 {@code null} 只会把失败推迟成更晚、更难定位的 {@code NullPointerException}。
 *
 * <p>⚠ <b>断言为何必须锚定「我们自己的消息」</b>：初版断言只查「异常链里含 redis/地址」，
 * 而 Redisson 原始消息里本就含 {@code redis://127.0.0.1:1} —— 于是该断言在**缺陷代码上照样通过**，
 * 等于零判别力（AGENTS §2.1 第 14 条：断言通过 ≠ 断言在做事）。
 * 故改为断言「消息由本方法产出且带可操作指引」，并另设用例证明原始异常未被吞掉。
 */
public class RedisConfigTest {

    /** 构造一个指向**不可达端口**的配置（连接被拒，秒级失败，不依赖任何外部服务）。 */
    private RedisConfig configPointingAt(int port) {
        RedisConfig config = new RedisConfig<>();
        ReflectionTestUtils.setField(config, "redisHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "redisPort", port);
        return config;
    }

    /**
     * Redis 连不上时：必须抛出**由本方法包装的**、带可操作指引的异常。
     *
     * <p>判据锚在自产消息上 —— 这是让断言在缺陷代码上**转红**的关键。
     */
    @Test
    public void unreachableRedisFailsFastWithActionableMessage() {
        try {
            configPointingAt(1).redissonClient();
            fail("Redis 不可达时必须抛出异常 —— 返回 null 只会把失败推迟成下游 NPE");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            assertTrue(
                    "必须由本方法产出「Redis 连接失败」的可操作提示，"
                            + "而不是把 Redisson 的原始异常直接抛给调用方（实得：" + msg + "）",
                    msg.contains("Redis 连接失败"));
            assertTrue(
                    "提示须指出排查方向（实得：" + msg + "）",
                    msg.contains("redis.host") && msg.contains("redis.port"));
        }
    }

    /**
     * 原始异常必须**作为 cause 保留**，不得被吞掉 —— 否则丢失底层细节，排障时更瞎。
     */
    @Test
    public void keepsOriginalCauseForDiagnosis() {
        try {
            configPointingAt(1).redissonClient();
            fail("应抛出异常");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            assertNotNull("必须保留原始 cause，否则底层细节全丢（实得：" + e + "）", cause);
            assertTrue(
                    "cause 应当来自 Redisson 侧（实得：" + cause.getClass().getName() + "）",
                    cause.getClass().getName().toLowerCase().contains("redisson"));
        }
    }

    /**
     * 反向制衡：绝不能返回 {@code null}。
     *
     * <p>若实现被改成 {@code catch (Exception e) { return null; }}，
     * 本条会红 —— 防止「为了让测试绿而把失败悄悄吞掉」这种反向作弊。
     */
    @Test
    public void neverReturnsNullSilently() {
        try {
            Object client = configPointingAt(1).redissonClient();
            assertNotNull("redissonClient() 绝不能返回 null", client);
        } catch (Exception expected) {
            if (expected.getClass() != IllegalStateException.class) {
                fail("统一抛 IllegalStateException，避免调用方误判类型（实得："
                        + expected.getClass().getName() + "）");
            }
        }
    }
}
