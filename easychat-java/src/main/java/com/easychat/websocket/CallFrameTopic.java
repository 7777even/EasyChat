package com.easychat.websocket;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;

/**
 * 通话信令广播通道（call-multi-instance层一 / C2，ADR-001）。
 *
 * <p><b>解决什么问题</b>：{@code CallService#sendTo} 原经
 * {@link ChannelContextUtils#sendRawToUser} <b>只写本地 Netty ChannelGroup</b>，
 * 而 {@code USER_CONTEXT_MAP} 是进程内的 —— 多实例下 invite 一发出去就静默丢失
 * （查不到本地 ChannelGroup 即 return，无日志无异常）。
 *
 * <p><b>做法</b>：复刻 {@link MessageHandler} 已在本仓验证过的模式 ——
 * 广播经 Redisson RTopic，各实例监听后<b>本地投递</b>。
 * 每个实例各自判断本地是否持有目标用户的连接，无需知道「对方在哪个实例」。
 *
 * <p><b>为何独立 Topic 而非复用 {@code message.topic}</b>：后者载荷是
 * {@code MessageSendDto}，混入通话帧会污染语义与序列化边界，
 * 且两者生命周期/重试策略不同。
 *
 * <p>⚠ 本类只做 IO 接线；<b>判定逻辑</b>全部是静态纯函数，便于脱离 Redis 单测。
 */
@Component("callFrameTopic")
public class CallFrameTopic {

    private static final Logger logger = LoggerFactory.getLogger(CallFrameTopic.class);

    /**
     * 通话信令专用 Topic。
     *
     * <p>刻意与 {@code MessageHandler} 的 {@code message.topic} 不同名（ADR-001）。
     */
    public static final String TOPIC_NAME = "call.frame.topic";

    /**
     * 广播载荷。{@code targetUserId} 非空才会被广播（见 {@link #shouldBroadcast}）。
     *
     * <p>⚠ 刻意<b>不用</b> Java {@code record}：本仓用 fastjson 1.x，
     * 它不识别 record 的规范构造器，反序列化会失败（实测 {@code decode} 恒返回 null）。
     * 故用普通 POJO + 无参构造。
     */
    public static class CallFramePayload {
        private String targetUserId;
        private String frameJson;

        public CallFramePayload() {
        }

        public CallFramePayload(String targetUserId, String frameJson) {
            this.targetUserId = targetUserId;
            this.frameJson = frameJson;
        }

        public String getTargetUserId() {
            return targetUserId;
        }

        public void setTargetUserId(String targetUserId) {
            this.targetUserId = targetUserId;
        }

        public String getFrameJson() {
            return frameJson;
        }

        public void setFrameJson(String frameJson) {
            this.frameJson = frameJson;
        }
    }

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ChannelContextUtils channelContextUtils;

    /**
     * 广播开关（ADR-005 的紧急回退手段）。
     *
     * <p>默认 true。置 false 时退化为「本地直投」的旧行为 —— 用于线上事故时快速止血，
     * 此时启动会打显著 WARN 提示多实例不可用。
     */
    @Value("${easychat.call.broadcast-signaling:true}")
    private boolean broadcastEnabled;

    /**
     * 订阅广播：收到后按 {@code targetUserId} 在<b>本实例</b>本地投递。
     *
     * <p>⚠ 与 {@code MessageHandler} 同样在 {@code @PostConstruct} 订阅，
     * 集成测试需 mock {@code redissonClient}（见 {@code IntegrationTestBase} 注释 ②③）。
     */
    @PostConstruct
    public void subscribe() {
        RTopic rTopic;
        try {
            rTopic = redissonClient.getTopic(TOPIC_NAME);
        } catch (Exception e) {
            logger.warn("[通话信令] getTopic({}) 失败，跳过订阅", TOPIC_NAME, e);
            return;
        }
        if (rTopic == null) {
            // Redisson 契约上不返回 null；这里是集成测试 mock 场景。
            // 选择 WARN + 跳过而非 NPE：NPE 会让整个上下文启动失败。
            logger.warn("[通话信令] getTopic({}) 返回 null，跳过订阅", TOPIC_NAME);
            return;
        }
        rTopic.addListener(CallFramePayload.class, (MessageListener<CallFramePayload>) (channel, msg) -> {
            if (msg == null) {
                return;
            }
            if (!shouldBroadcast(msg.getTargetUserId())) {
                return;
            }
            // 本地投递：USER_CONTEXT_MAP 查不到就静默丢弃（目标不在本实例），
            // 这正是「广播 + 各自本地投递」模型下应有的行为。
            channelContextUtils.sendRawToUser(msg.getTargetUserId(), msg.getFrameJson());
        });
    }

    /**
     * 广播一帧通话信令给指定用户。
     *
     * @param targetUserId 目标用户；为空则不广播（见 {@link #shouldBroadcast}）
     * @param frameJson     已序列化的信令帧
     */
    public void broadcast(String targetUserId, String frameJson) {
        if (!broadcastEnabled) {
            // 紧急回退路径：调用方应自行本地直投
            return;
        }
        if (!shouldBroadcast(targetUserId)) {
            logger.warn("[通话信令] targetUserId 为空，跳过广播 frame={}", frameJson);
            return;
        }
        try {
            redissonClient.getTopic(TOPIC_NAME).publish(new CallFramePayload(targetUserId, frameJson));
        } catch (Exception e) {
            // 广播失败不应让信令处理崩溃；调用方仍有本地直投兜底
            logger.error("[通话信令] 广播失败 target={}", targetUserId, e);
        }
    }

    // ── 纯判定（无 IO，可脱离 Redis 单测）────────────────────────────

    /** 只有 targetUserId 非空（且非 "null" 字面量）才值得广播。 */
    public static boolean shouldBroadcast(String targetUserId) {
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            return false;
        }
        return !"null".equalsIgnoreCase(targetUserId.trim());
    }

    public static String encode(CallFramePayload p) {
        return p == null ? null : JSON.toJSONString(p);
    }

    /** 反序列化失败返回 null 而不是抛异常 —— 广播通道上单帧损坏不应拖垮监听线程。 */
    public static CallFramePayload decode(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(json, CallFramePayload.class);
        } catch (Exception e) {
            logger.warn("[通话信令] 载荷反序列化失败，已丢弃：{}", json, e);
            return null;
        }
    }
}
