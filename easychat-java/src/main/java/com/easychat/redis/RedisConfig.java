package com.easychat.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class RedisConfig<V> {
    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.redis.host:}")
    private String redisHost;

    @Value("${spring.redis.port:}")
    private Integer redisPort;

    /**
     * 创建 Redisson 客户端。
     *
     * <p><b>2026-10-14 修复（openspec：无 Change，L2 缺陷修复）</b>：原实现 catch 的是
     * {@code io.lettuce.core.RedisConnectionException}，而 {@code Redisson.create()} 实际抛
     * {@code org.redisson.client.RedisConnectionException}（实测 cause 链为
     * {@code RedissonConnectionException ← io.netty...AnnotatedConnectException}）——
     * <b>两者是不同的类，catch 永不触发</b>。后果：那句「redis配置错误，请检查redis配置」
     * 是<b>死代码</b>，末尾 {@code return null} 不可达，调用方拿到的是没有指向性的原始异常。
     *
     * <p><b>为何是「快速失败」而不是注释暗示的「降级」</b>：{@code MessageHandler} /
     * {@code RedisComponet} / {@code RedissonUtils} 都依赖本 Bean，返回 {@code null}
     * 只会把失败推迟成更晚、更难定位的 {@code NullPointerException}。故此处统一抛
     * {@link IllegalStateException} 并带排查指引，同时<b>保留原始 cause</b> 不丢底层细节。
     */
    @Bean(name = "redissonClient", destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        try {
            // 根据config创建出RedissonClient实例
            return Redisson.create(buildConfig());
        } catch (Exception e) {
            // ⚠ 捕获范围是**全部** Exception：此处只做「包装 + 指引 + 保留 cause」，
            //   不改变失败这一事实（不会吞掉、不会返回 null）。
            //   若只 catch 某个连接异常类型，任何其它失败都会绕过本方法的可诊断信息 ——
            //   那正是原缺陷的形态（类型不匹配 ⇒ catch 永不触发 ⇒ 日志成死代码）。
            String message = "Redis 连接失败，请检查 redis.host / redis.port 配置"
                    + "（当前目标 redis://" + redisHost + ":" + redisPort + "）";
            logger.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * Redisson 自动重试次数：**0**（关闭）。
     *
     * <p><b>为何必须关（2026-10-21 实测，Redis 被阻塞时逐帧记录）</b>：
     * Redisson 3.12.3 的默认是 {@code timeout=3000ms} + {@code retryAttempts=3}
     * + {@code retryInterval=1500ms}。叠加出两个**真缺陷**：
     * <ol>
     *   <li><b>重复投递</b>：重试会把同一条 {@code PUBLISH} 再发一次 →
     *       Redis 恢复后**排队的多条 PUBLISH 一起执行** →
     *       呼叫失败时发起方收到 <b>5 份</b>「通话服务暂时不可用」（实测：1 份本地 + 4 份回声）；</li>
     *   <li><b>失败反馈被拖到 25.8s</b>：建房要等重试链走完才判失败
     *       （实测第 1 份错误帧在阻塞后 25.8s 才到，用户干等半分钟）。</li>
     * </ol>
     * 根因是 {@code PUBLISH} / {@code HSETNX} 都**不是幂等**命令：
     * 重试它们等于「可能重复投递」与「其实成功了但回复丢了 → 被误判成 callId 冲突」。
     *
     * <p><b>影响面已穷举</b>：Redisson 仅服务通话房间、通话信令广播、普通消息广播
     * （{@code RedissonUtils} 是死代码）；**鉴权 / 会话 / 验证码 / 限流全走 Jedis**
     * （{@code RedisTemplate}，{@code spring.redis.timeout=2000}），不受本条影响。
     *
     * <p>⚠ <b>代价（诚实登记）</b>：普通消息广播在 Redis **瞬时**故障时不再自动重试
     * （原先靠 Redisson 内部重试撑 ~16.8s）。但消息本身已落库、且有 SYNC 补推机制，
     * 且「Redis 挂了 3s 以上还想实时送达」本就不是合理预期 → 已在 §14 登记为遗留项。
     */
    private static final int REDISSON_RETRY_ATTEMPTS = 0;

    /**
     * 构造 Redisson 配置（**抽出以便单测**：{@code Redisson.create()} 一旦调用就连真 Redis，
     * 单测里既连不上也不该连；把「配置长什么样」变成可断言的纯函数）。
     *
     * <p>包级可见：仅供同包单测断言，不对外承诺 API。
     */
    Config buildConfig() {
        Config config = new Config();
        // 使用 JSON 编码替代 FST，避免 Java 17+ 模块化系统下的反射访问限制
        config.setCodec(new JsonJacksonCodec());
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setRetryAttempts(REDISSON_RETRY_ATTEMPTS);
        return config;
    }


    @Bean("redisTemplate")
    public RedisTemplate<String, V> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, V> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // 设置key的序列化方式
        template.setKeySerializer(RedisSerializer.string());
        // 设置value的序列化方式
        template.setValueSerializer(RedisSerializer.json());
        // 设置hash的key的序列化方式
        template.setHashKeySerializer(RedisSerializer.string());
        // 设置hash的value的序列化方式
        template.setHashValueSerializer(RedisSerializer.json());
        template.afterPropertiesSet();
        return template;
    }
}