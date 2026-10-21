package com.easychat.websocket;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.easychat.redis.RedisComponet;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * **WS 通道注册不得依赖 Redis**（2026-10-21 实测发现的独立缺陷，遗留 #35 的更深根因）。
 *
 * <p><b>缺陷</b>：{@code ChannelContextUtils#addContext} 的执行顺序是
 * <pre>
 *   ① redisComponet.getUserContactList(userId)   ← Redis（Jedis）
 *   ② addUserChannel(userId, channel)             ← 本地注册（必须做）
 *   ③ saveUserHeartBeat / updateUserStatus / …    ← Redis + DB
 * </pre>
 * ①②③③… 全部在**同一个 try** 里。Redis 不可用时 ① 抛
 * {@code RedisCommandTimeoutException} → 被最外层 catch 吞掉 →
 * <b>② 永远不会执行</b> → 该用户的 WS 连接<b>连上了、却不在投递体系内</b>：
 * 消息、通话信令、在线状态广播**全部收不到**，且**没有任何错误提示**
 * （catch 只打了一行「初始化链接失败」）。
 *
 * <p><b>实测现场</b>（2026-10-21 双实例实跑，Redis 被 DEBUG SLEEP 阻塞）：
 * 客户端已连上实例 A，随后发起呼叫 → 服务端 3s 内判 Redis 不可用 →
 * 回 2803 错误帧 → **客户端一帧都没收到**；而实例日志里
 * {@code addContext} 的栈正停在 {@code RedisComponet.getUserContactList}。
 *
 * <p><b>为什么这条独立于「2803 重复 5 份」</b>：那一条是投递路径重复（已修），
 * 这一条是<b>通道压根没注册</b>—— 即使投递路径完全正确，帧也送不出去。
 */
public class ChannelContextRegistrationTest {

    private static final String USER = "U-reg-test-user";

    private ChannelContextUtils channelContextUtils;
    private RedisComponet redisComponet;

    @Before
    public void setUp() {
        channelContextUtils = new ChannelContextUtils();
        redisComponet = mock(RedisComponet.class);
        ReflectionTestUtils.setField(channelContextUtils, "redisComponet", redisComponet);
    }

    @After
    public void tearDown() {
        // USER_CONTEXT_MAP 是静态的 —— 必须清理，否则污染同 JVM 内的其他用例
        ChannelContextUtils.USER_CONTEXT_MAP.remove(USER);
    }

    /**
     * 🔴 Redis 不可用时，<b>本地通道仍必须注册</b>。
     *
     * <p>判据锚在「通道在不在投递体系内」，而不是「有没有抛异常」——
     * 原实现既不抛也不报，只是静默地把用户排除在外。
     */
    @Test
    public void channelIsRegisteredEvenWhenRedisIsUnavailable() {
        when(redisComponet.getUserContactList(anyString()))
                .thenThrow(new RedisConnectionFailureException("模拟 Redis 不可用"));

        channelContextUtils.addContext(USER, new EmbeddedChannel());

        assertTrue("Redis 不可用时通道也必须注册 —— 否则用户「连上了却收不到任何推送」，"
                        + "且无任何错误提示",
                ChannelContextUtils.USER_CONTEXT_MAP.containsKey(USER));
    }
}
