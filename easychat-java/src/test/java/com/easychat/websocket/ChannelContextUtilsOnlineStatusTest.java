package com.easychat.websocket;

import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.OnlineStatusEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelContextUtils} 的在线状态可见性相关单测。
 *
 * <p>覆盖 openspec/specs/privacy-settings 的 C3「在线状态可见性」：
 * {@code broadcastOnlineStatus} 的开关判定，以及 {@code pushOnlineStatusHidden} 的帧构造。
 *
 * <p><b>为什么这些必须有单测</b>：WS 帧号 ↔ 客户端 {@code case} 是跨进程契约，
 * 错位后的表现是「不崩但功能静默失效」，是最难靠人工回归发现的一类问题。
 * {@code scripts/verify/verify_ws_frame_parity.mjs} 能抓「漏加 case / 帧号漂移」，
 * 但抓不到「帧推了但内容是错的」「该推的没推」「推给了不该推的人」——那正是本类的职责。
 *
 * <p>实现手法：用 Mockito spy 拦截 {@code sendRawToUser}（真正写 Netty 帧的方法），
 * 直接断言「推给了谁 + 推了什么」，不依赖真实的 WS 连接。
 * 「是否在线」通过向静态 {@code USER_CONTEXT_MAP} 放/删占位 ChannelGroup 来控制。
 *
 * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
 */
@RunWith(MockitoJUnitRunner.class)
public class ChannelContextUtilsOnlineStatusTest {

    private static final String SELF = "U_self";
    private static final String FRIEND_ONLINE = "U_friend_online";
    private static final String FRIEND_OFFLINE = "U_friend_offline";

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    private ChannelContextUtils target;
    private ChannelContextUtils spy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Before
    public void setUp () {
        target = new ChannelContextUtils();
        ReflectionTestUtils.setField(target, "redisComponet", redisComponet);
        ReflectionTestUtils.setField(target, "userInfoMapper", userInfoMapper);
        spy = spy(target);
        clearContextMap();
    }

    @After
    public void tearDown () {
        // USER_CONTEXT_MAP 是静态共享，必须清理避免污染其他测试类
        clearContextMap();
    }

    // ==================== 夹具 ====================

    private void clearContextMap () {
        ChannelContextUtils.USER_CONTEXT_MAP.clear();
    }

    /** 造一个只带开关值的 UserInfo（其余字段为 null，模拟 DB 读取的真实形态） */
    private UserInfo userWithVisible (Integer visible) {
        UserInfo info = new UserInfo();
        info.setUserId(SELF);
        info.setOnlineStatusVisible(visible);
        return info;
    }

    private void stubFriends (String... friendIds) {
        when(redisComponet.getUserContactList(SELF)).thenReturn(new ArrayList<>(Arrays.asList(friendIds)));
    }

    /**
     * 把用户标记为「在线」：往 USER_CONTEXT_MAP 放一个<b>含 Channel 的</b> ChannelGroup。
     * <p>
     * 必须真的 add 一个 Channel —— {@code isUserOnline} 判的是 {@code group != null && !group.isEmpty()}，
     * 空 group 会被判为离线（这是本测试首跑 6 例失败的原因：放空 group 全部走离线分支）。
     */
    private void markOnline (String userId) {
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        // 必须用真实 Channel：DefaultChannelGroup.add 内部会读 channel.config()/pipeline()/allocator()，
        // mock 会在此 NPE。EmbeddedChannel 是 Netty 官方提供的无网络 Channel，构造轻量且线程安全。
        group.add(new EmbeddedChannel());
        ChannelContextUtils.USER_CONTEXT_MAP.put(userId, group);
    }

    private void markOffline (String userId) {
        ChannelContextUtils.USER_CONTEXT_MAP.remove(userId);
    }

    private JsonNode readJson (String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("推送帧不是合法 JSON: " + json, e);
        }
    }

    // ==================== broadcastOnlineStatus：开关判定 ====================

    /**
     * 关闭「展示在线状态」后，状态变更<b>不应广播</b>给任何好友。
     * 这是隐私设置的核心承诺：不广播 = 好友端不会因该用户的状态变化而更新。
     */
    @Test
    public void broadcastOnlineStatus_hidden_noFramePushed () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(userWithVisible(0));
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        spy.broadcastOnlineStatus(SELF, OnlineStatusEnum.ONLINE.getStatus());

        verify(spy, never()).sendRawToUser(anyString(), anyString());
    }

    /** 开关=0 时，连取好友列表都不该做（提前 return，避免无意义的 Redis 查询）。 */
    @Test
    public void broadcastOnlineStatus_hidden_skipsFriendLookup () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(userWithVisible(0));

        spy.broadcastOnlineStatus(SELF, OnlineStatusEnum.ONLINE.getStatus());

        verify(redisComponet, never()).getUserContactList(anyString());
    }

    /** 开关=1（展示）时正常广播，且只推给在线好友。 */
    @Test
    public void broadcastOnlineStatus_visible_pushesToOnlineFriendOnly () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(userWithVisible(1));
        stubFriends(FRIEND_ONLINE, FRIEND_OFFLINE);
        markOnline(FRIEND_ONLINE);
        markOffline(FRIEND_OFFLINE);

        spy.broadcastOnlineStatus(SELF, OnlineStatusEnum.ONLINE.getStatus());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), payload.capture());
        // 离线好友不得收到
        verify(spy, never()).sendRawToUser(eq(FRIEND_OFFLINE), anyString());

        JsonNode node = readJson(payload.getValue());
        assertEquals(MessageTypeEnum.ONLINE_STATUS.getType().intValue(), node.get("messageType").asInt());
        assertEquals(SELF, node.get("contactId").asText());
        assertEquals(SELF, node.get("sendUserId").asText());
        assertEquals(OnlineStatusEnum.ONLINE.getStatus().intValue(), node.get("extendData").asInt());
    }

    /** 忙碌/离线等其他状态值同样受开关管辖（不只是 ONLINE）。 */
    @Test
    public void broadcastOnlineStatus_hidden_blocksAllStatusValues () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(userWithVisible(0));
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        for (OnlineStatusEnum status : OnlineStatusEnum.values()) {
            spy.broadcastOnlineStatus(SELF, status.getStatus());
        }

        verify(spy, never()).sendRawToUser(anyString(), anyString());
    }

    /**
     * 查不到用户记录时按<b>展示</b>处理，避免因查不到而误伤正常用户
     * （与 {@code isOnlineStatusVisible} 的约定一致；列本身 NOT NULL，查不到只可能是用户被删）。
     */
    @Test
    public void broadcastOnlineStatus_userNotFound_defaultsToVisible () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(null);
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        spy.broadcastOnlineStatus(SELF, OnlineStatusEnum.ONLINE.getStatus());

        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), anyString());
    }

    /** 开关列为 NULL（历史脏数据）同样按展示处理。 */
    @Test
    public void broadcastOnlineStatus_nullSwitch_defaultsToVisible () {
        when(userInfoMapper.selectByUserId(SELF)).thenReturn(userWithVisible(null));
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        spy.broadcastOnlineStatus(SELF, OnlineStatusEnum.BUSY.getStatus());

        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), anyString());
    }

    /** userId 为空时直接返回，连开关都不查（避免无意义 SQL）。 */
    @Test
    public void broadcastOnlineStatus_blankUserId_returnsEarly () {
        spy.broadcastOnlineStatus("", OnlineStatusEnum.ONLINE.getStatus());
        spy.broadcastOnlineStatus(null, OnlineStatusEnum.ONLINE.getStatus());

        verify(userInfoMapper, never()).selectByUserId(anyString());
        verify(spy, never()).sendRawToUser(anyString(), anyString());
    }

    // ==================== pushOnlineStatusHidden：抹除帧 ====================

    /**
     * 关闭时必须推 27 帧，帧结构为 messageType=27 / contactId=自己 / extendData={"hidden":true}。
     * 客户端 {@code wsClient.js} case 27 据此清掉 onlineStatusMap 里的状态点。
     */
    @Test
    public void pushOnlineStatusHidden_pushesFrame27WithHiddenMarker () {
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        spy.pushOnlineStatusHidden(SELF);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), payload.capture());

        JsonNode node = readJson(payload.getValue());
        assertEquals(MessageTypeEnum.ONLINE_STATUS_HIDDEN.getType().intValue(), node.get("messageType").asInt());
        assertEquals(SELF, node.get("contactId").asText());
        assertEquals(SELF, node.get("sendUserId").asText());
        assertTrue("extendData 必须带 hidden=true 标记",
                node.get("extendData").get("hidden").asBoolean());
    }

    /** 抹除帧同样只推给在线好友。 */
    @Test
    public void pushOnlineStatusHidden_onlyToOnlineFriends () {
        stubFriends(FRIEND_ONLINE, FRIEND_OFFLINE);
        markOnline(FRIEND_ONLINE);
        markOffline(FRIEND_OFFLINE);

        spy.pushOnlineStatusHidden(SELF);

        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), anyString());
        verify(spy, never()).sendRawToUser(eq(FRIEND_OFFLINE), anyString());
    }

    /**
     * 关键区分：{@code pushOnlineStatusHidden} 是「抹除已有状态」，
     * <b>不受开关值影响</b>——即便此时开关已是 0 也必须推出去。
     * 否则好友端会残留「卡在在线」的旧状态点，而这正是本功能要解决的直觉问题
     * （用户关闭后好友应立刻看不到，而不是等对方掉线）。
     */
    @Test
    public void pushOnlineStatusHidden_ignoresSwitchValue () {
        // 刻意不 stub selectByUserId：本方法不应读开关
        stubFriends(FRIEND_ONLINE);
        markOnline(FRIEND_ONLINE);

        spy.pushOnlineStatusHidden(SELF);

        verify(spy, times(1)).sendRawToUser(eq(FRIEND_ONLINE), anyString());
        verify(userInfoMapper, never()).selectByUserId(anyString());
    }

    /** 抹除帧的帧号必须是 27 且与 ONLINE_STATUS(22) 不同（否则客户端无法区分两种语义）。 */
    @Test
    public void pushOnlineStatusHidden_frameNumberDiffersFromOnlineStatus () {
        assertEquals(Integer.valueOf(27), MessageTypeEnum.ONLINE_STATUS_HIDDEN.getType());
        assertEquals(Integer.valueOf(22), MessageTypeEnum.ONLINE_STATUS.getType());
        assertTrue("抹除帧号不得等于在线状态帧号",
                !MessageTypeEnum.ONLINE_STATUS_HIDDEN.getType().equals(MessageTypeEnum.ONLINE_STATUS.getType()));
    }

    /** 无好友 / userId 为空时不推任何帧，且不抛异常。 */
    @Test
    public void pushOnlineStatusHidden_noFriends_noFrame () {
        when(redisComponet.getUserContactList(SELF)).thenReturn(new ArrayList<>());
        when(redisComponet.getUserContactList("U_nobody")).thenReturn(null);

        spy.pushOnlineStatusHidden(SELF);
        spy.pushOnlineStatusHidden("");
        spy.pushOnlineStatusHidden("U_nobody");

        verify(spy, never()).sendRawToUser(anyString(), anyString());
    }

    /**
     * 回归护栏：隐藏开关只应拦截「状态变更广播」，
     * <b>不应误伤正常消息的投递</b>——聊天消息走 {@code sendRawToUser} 直投路径，
     * 不经过 {@code broadcastOnlineStatus}，因此关闭在线状态后好友仍必须能收到消息。
     *
     * <p>本例<b>不打桩 sendRawToUser</b>，直接验证真实写入 Netty：
     * 从 {@code EmbeddedChannel} 的 outbound 缓冲里取出帧并断言内容，
     * 证明「开关=0 的用户，其好友依然能收到聊天消息」。
     */
    @Test
    public void hiddenSwitch_doesNotAffectMessageDelivery () {
        // 刻意不 stub selectByUserId：本例断言的正是「投递路径完全不读在线状态开关」，
        // 若投递逻辑改为读开关，verify(never()) 会失败 —— 这比 stub 一个值再断言更有判别力。
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        group.add(channel);
        ChannelContextUtils.USER_CONTEXT_MAP.put(FRIEND_ONLINE, group);

        // 真实方法（非 spy）：模拟聊天消息直投
        target.sendRawToUser(FRIEND_ONLINE, "{\"messageType\":2,\"messageContent\":\"hi\"}");

        Object outbound = channel.readOutbound();
        assertTrue("关闭在线状态后，好友仍必须收到聊天消息（开关只管状态广播）",
                outbound instanceof TextWebSocketFrame);
        TextWebSocketFrame frame = (TextWebSocketFrame) outbound;
        JsonNode node = readJson(frame.text());
        assertEquals(MessageTypeEnum.CHAT.getType().intValue(), node.get("messageType").asInt());
        assertEquals("hi", node.get("messageContent").asText());
        verify(userInfoMapper, never()).selectByUserId(anyString());
    }
}