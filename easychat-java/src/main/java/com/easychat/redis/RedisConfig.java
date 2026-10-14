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
            // 创建配置 指定redis地址及节点信息
            Config config = new Config();
            // 使用 JSON 编码替代 FST，避免 Java 17+ 模块化系统下的反射访问限制
            config.setCodec(new JsonJacksonCodec());
            config.useSingleServer().setAddress("redis://" + redisHost + ":" + redisPort);
            // 根据config创建出RedissonClient实例
            return Redisson.create(config);
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