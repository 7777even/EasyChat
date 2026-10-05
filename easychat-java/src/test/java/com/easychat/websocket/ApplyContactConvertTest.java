package com.easychat.websocket;

import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.dto.MessageSendDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ChannelContextUtils#applyContactConvert} 联系人语义转换测试（2026-10-05）
 *
 * <p><b>为什么必须测</b>：这是<b>跨进程契约</b>的一部分。AGENTS §7.4 明确
 * 「投递前会被服务端改写的帧（如 {@code ADD_FRIEND_SELF(13)} 经本方法转成
 * {@code ADD_FRIEND(1)}）客户端<b>不得</b>有 case」——门禁
 * {@code verify_ws_frame_parity.mjs} 只校验<b>帧级</b>契约（帧号两端对齐、
 * 被改写的帧客户端无 case），<b>转换逻辑内部的三个条件判定零覆盖</b>：
 * <ol>
 *   <li>白名单 {@code CONTACT_CONVERT_TYPES}（14 帧）才转换；</li>
 *   <li>{@code contactType == 1}（群聊）<b>跳过</b>转换（ADR-003：否则客户端会凭空
 *       生成与发送人的单聊脏会话）；</li>
 *   <li>{@code ADD_FRIEND_SELF} 分支转换后<b>必须 return</b>，否则会继续落入白名单分支
 *       把刚设好的 contactId 覆盖成 sendUserId。</li>
 * </ol>
 * 任一条漏掉的后果都是**静默的**：会话凭空多出一个、或打招呼消息显示错联系人，
 * 服务端不报错、前端无警告。
 *
 * <p><b>断言口径</b>：白名单由<b>反射读出</b>、全集由 {@code MessageTypeEnum.values()}
 * 枚举得出 —— 测试**自己推导真值**而非硬编码，故新增/删除帧时断言自动跟随，
 * 不会因硬编码而假绿（同 {@code PasswordEncoderTest} 的推导式断言）。
 */
@DisplayName("ChannelContextUtils#applyContactConvert — 联系人语义转换")
class ApplyContactConvertTest {

    private static final String SENDER = "U_SENDER";
    private static final String SENDER_NICK = "发送者昵称";
    private static final int GROUP_CONTACT_TYPE = 1;

    @SuppressWarnings("unchecked")
    private static Set<Integer> whitelist() {
        try {
            Field f = ChannelContextUtils.class.getDeclaredField("CONTACT_CONVERT_TYPES");
            f.setAccessible(true);
            return (Set<Integer>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 CONTACT_CONVERT_TYPES 失败（字段已改名？）", e);
        }
    }

    private static void convert(MessageSendDto dto) {
        try {
            Method m = ChannelContextUtils.class
                    .getDeclaredMethod("applyContactConvert", MessageSendDto.class);
            m.setAccessible(true);
            m.invoke(new ChannelContextUtils(), dto);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IllegalStateException(c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 applyContactConvert 失败", e);
        }
    }

    private static MessageSendDto dto(Integer type, Integer contactType) {
        MessageSendDto d = new MessageSendDto();
        d.setMessageType(type);
        d.setContactType(contactType);
        d.setSendUserId(SENDER);
        d.setSendUserNickName(SENDER_NICK);
        d.setContactId("ORIGINAL_CONTACT");
        d.setContactName("原始联系人名");
        return d;
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界：null 与 messageType 为 null
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("dto 为 null → 不抛异常")
    void nullDtoTolerated() {
        convert(null);
    }

    @Test
    @DisplayName("messageType 为 null → 原样返回，不做任何转换")
    void nullMessageTypeUntouched() {
        MessageSendDto d = dto(null, 0);
        convert(d);
        assertEquals("ORIGINAL_CONTACT", d.getContactId());
        assertEquals("原始联系人名", d.getContactName());
    }

    // ═══════════════════════════════════════════════════════════════
    // ADD_FRIEND_SELF → ADD_FRIEND（跨进程契约，AGENTS §7.4）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ ADD_FRIEND_SELF → ADD_FRIEND，联系人=打招呼对象本人")
    void addFriendSelfConvertsToAddFriend() {
        UserInfo self = new UserInfo();
        self.setUserId("U_SELF");
        self.setNickName("我自己");

        MessageSendDto d = dto(MessageTypeEnum.ADD_FRIEND_SELF.getType(), 0);
        d.setExtendData(self);
        convert(d);

        assertEquals(MessageTypeEnum.ADD_FRIEND.getType(), d.getMessageType(),
                "13 必须转成 1（客户端不得有 13 的 case）");
        assertEquals("U_SELF", d.getContactId(), "联系人是打招呼对象本人");
        assertEquals("我自己", d.getContactName());
        assertNull(d.getExtendData(), "extendData 承载 UserInfo，转换后必须清空（否则会被序列化出去）");
    }

    @Test
    @DisplayName("★ ADD_FRIEND_SELF 转换后不会被白名单分支覆盖（真实机制是白名单查的是**局部变量**）")
    void addFriendSelfIsNotOverwrittenByWhitelistBranch() {
        // ⚠ **初版此用例的前提是错的**（2026-10-05 自查发现）：
        //   我以为「ADD_FRIEND_SELF 分支末尾漏了 return → 落进白名单分支 → contactId 被覆盖」。
        //   实测删掉那个 return **行为不变** —— 因为白名单判断用的是方法开头捕获的
        //   **局部变量** `messageType`（值仍是 13），而非被改成 1 的 `messageSendDto.getMessageType()`；
        //   13 不在白名单内 → 直接 return。故那个 return 是**冗余的防御**，不是承重结构。
        //
        //   真正会破坏不变式的是**白名单判断改用 DTO 字段**（此时 return 才成为承重结构）。
        //   本用例断言的是**结果**（不被覆盖），故两种实现下都成立；
        //   破坏机制的那条由变异「白名单判断改用 messageSendDto.getMessageType()」覆盖。
        UserInfo self = new UserInfo();
        self.setUserId("U_SELF");
        self.setNickName("我自己");

        MessageSendDto d = dto(MessageTypeEnum.ADD_FRIEND_SELF.getType(), 0);
        d.setExtendData(self);
        convert(d);

        assertEquals(MessageTypeEnum.ADD_FRIEND.getType(), d.getMessageType());
        assertEquals("U_SELF", d.getContactId(),
                "contactId 被白名单分支覆盖成发送人 —— 打招呼消息会显示成「自己给自己打招呼」");
        assertEquals("我自己", d.getContactName(), "contactName 同样不得被覆盖");
    }

    @Test
    @DisplayName("白名单转换必须同时改 contactId 与 contactName（只改其一会导致会话名显示错）")
    void whitelistConversionSetsBothContactIdAndName() {
        // ⚠ 补一处真实测试缺口：初版只断言 contactId，
        //   于是「contactName 不再取发送人昵称」这个变异**完全逃过**。
        for (Integer type : whitelist()) {
            MessageSendDto d = dto(type, 0);
            convert(d);
            assertEquals(SENDER, d.getContactId(), "帧 " + nameOf(type) + " 的 contactId 未转换");
            assertEquals(SENDER_NICK, d.getContactName(),
                    "帧 " + nameOf(type) + " 的 contactName 未取发送人昵称"
                            + "（会话列表会显示原 contactId 对应的旧名字）");
        }
    }

    @Test
    @DisplayName("ADD_FRIEND_SELF 但 extendData 为 null → 保持原帧不转换（不产生半成品）")
    void addFriendSelfWithoutPayloadStaysUnconverted() {
        MessageSendDto d = dto(MessageTypeEnum.ADD_FRIEND_SELF.getType(), 0);
        d.setExtendData(null);
        convert(d);

        assertEquals(MessageTypeEnum.ADD_FRIEND_SELF.getType(), d.getMessageType(),
                "无载荷时不得改 messageType（否则客户端收到 1 却无联系人信息）");
        assertEquals("ORIGINAL_CONTACT", d.getContactId(), "不得动 contactId");
    }

    // ═══════════════════════════════════════════════════════════════
    // 白名单内帧（单聊）→ 联系人 = 发送人
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 白名单内全部帧（单聊）→ contactId/Name 置为发送人")
    void allWhitelistedFramesConvertInSingleChat() {
        List<Integer> converted = new ArrayList<>();
        for (Integer type : whitelist()) {
            MessageSendDto d = dto(type, 0);           // contactType=0 单聊
            convert(d);
            if (SENDER.equals(d.getContactId())) {
                converted.add(type);
            } else {
                // ADD_FRIEND_SELF 不在白名单里，故白名单内每一项都应当被转换
                assertEquals("ORIGINAL_CONTACT", d.getContactId(),
                        "白名单帧 " + describe(type) + " 未被转换");
            }
        }
        assertEquals(whitelist().size(), converted.size(),
                "白名单内应全部被转换");
    }

    @Test
    @DisplayName("白名单外帧 → 一律不转换（INIT/强制下线/群解散等 contactId 语义不同）")
    void nonWhitelistedFramesNeverConvert() {
        List<String> skipped = new ArrayList<>();
        for (MessageTypeEnum e : MessageTypeEnum.values()) {
            Integer type = e.getType();
            if (whitelist().contains(type)) {
                continue;
            }
            if (type.equals(MessageTypeEnum.ADD_FRIEND_SELF.getType())) {
                continue;   // 由专属用例覆盖
            }
            MessageSendDto d = dto(type, 0);
            convert(d);
            if ("ORIGINAL_CONTACT".equals(d.getContactId())) {
                skipped.add(describe(type));
            } else {
                assertEquals("ORIGINAL_CONTACT", d.getContactId(),
                        "非白名单帧 " + describe(type) + " 被错误转换");
            }
        }
        assertTrue(skipped.size() > 0, "应存在非白名单帧（否则本用例失去意义）");
        assertTrue(skipped.contains(describe(MessageTypeEnum.INIT.getType())),
                "INIT 必须在非白名单内，实际跳过集合：" + skipped);
        assertTrue(skipped.contains(describe(MessageTypeEnum.FORCE_OFF_LINE.getType())),
                "FORCE_OFF_LINE 必须在非白名单内，实际跳过集合：" + skipped);
    }

    // ═══════════════════════════════════════════════════════════════
    // ★ contactType == 1（群聊）跳过转换 —— ADR-003
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 群聊（contactType=1）时白名单帧**跳过**转换（防凭空生成单聊脏会话）")
    void groupChatSkipsConversion() {
        for (Integer type : whitelist()) {
            MessageSendDto d = dto(type, GROUP_CONTACT_TYPE);
            convert(d);
            assertEquals("ORIGINAL_CONTACT", d.getContactId(),
                    "群聊帧 " + describe(type) + " 被转换成发送人 —— "
                            + "客户端会凭空生成与发送人的单聊脏会话（ADR-003 要防的正是这个）");
        }
    }

    @Test
    @DisplayName("群聊跳过转换的判定只认 contactType==1，其它值仍转换")
    void onlyExactGroupTypeOneSkips() {
        // 用 != 0 之类的判断会导致单聊也跳过；用 == 1 之外的精确判断才正确。
        MessageSendDto g = dto(MessageTypeEnum.CHAT.getType(), 1);
        convert(g);
        assertEquals("ORIGINAL_CONTACT", g.getContactId(), "contactType=1 应跳过");

        for (Integer ct : Arrays.asList(0, 2, 3)) {
            MessageSendDto d = dto(MessageTypeEnum.CHAT.getType(), ct);
            convert(d);
            assertEquals(SENDER, d.getContactId(), "contactType=" + ct + " 应照常转换");
        }
    }

    @Test
    @DisplayName("★ ADD_FRIEND_SELF 在群聊上下文同样转换（好友打招呼不是群消息）")
    void addFriendSelfConvertsEvenInGroupContext() {
        // ADD_FRIEND_SELF 分支在 contactType 判断**之前** return，故群聊标记不影响它。
        UserInfo self = new UserInfo();
        self.setUserId("U_SELF");
        MessageSendDto d = dto(MessageTypeEnum.ADD_FRIEND_SELF.getType(), GROUP_CONTACT_TYPE);
        d.setExtendData(self);
        convert(d);
        assertEquals(MessageTypeEnum.ADD_FRIEND.getType(), d.getMessageType());
        assertEquals("U_SELF", d.getContactId());
    }

    // ═══════════════════════════════════════════════════════════════
    // 回归快照
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 全帧矩阵：逐帧记录是否被转换（任何变更都必须显式改这里）")
    void fullMatrixSnapshot() {
        // ⚠ 断言必须基于**结构化数据**，不能基于格式化后的文本 ——
        //   初版把断言写成 matrix.contains(name + "(" + type + ") 单聊=不转")，
        //   而打印用了 %-22s 补白（INIT 被填成 "INIT                  ( 0)"），
        //   于是断言因**自己的补白**而失败 —— 行为其实是对的。
        //   这与 AGENTS §2.1 第 8 条同源：断言的匹配形态必须唯一。
        java.util.Map<String, String> outcome = new java.util.LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (MessageTypeEnum e : MessageTypeEnum.values()) {
            Integer t = e.getType();
            MessageSendDto single = dto(t, 0);
            convert(single);
            MessageSendDto group = dto(t, GROUP_CONTACT_TYPE);
            convert(group);
            boolean singleConv = SENDER.equals(single.getContactId());
            boolean groupConv = SENDER.equals(group.getContactId());
            outcome.put(e.name(), (singleConv ? "转" : "不转") + "|" + (groupConv ? "转" : "不转"));
            rows.add(String.format("%-22s(%3s) 单聊=%-4s 群聊=%-4s",
                    e.name(), t, singleConv ? "转" : "不转", groupConv ? "转" : "不转"));
        }
        System.out.println("=== applyContactConvert 全帧矩阵 ===\n" + String.join("\n", rows));

        for (MessageTypeEnum e : MessageTypeEnum.values()) {
            Integer t = e.getType();
            if (!whitelist().contains(t) && !t.equals(MessageTypeEnum.ADD_FRIEND_SELF.getType())) {
                assertEquals("不转|不转", outcome.get(e.name()),
                        "非白名单帧 " + e.name() + "(" + t + ") 在单聊与群聊下都不应被转换");
            }
        }
        for (Integer t : whitelist()) {
            String name = nameOf(t);
            assertNotNull(name, "白名单帧号 " + t + " 在 MessageTypeEnum 中不存在（枚举已改名？）");
            assertEquals("转|不转", outcome.get(name),
                    "白名单帧 " + name + "(" + t + ")" + "应在单聊下转换、群聊下跳过");
        }
    }

    private static String nameOf(Integer type) {
        for (MessageTypeEnum e : MessageTypeEnum.values()) {
            if (e.getType().equals(type)) {
                return e.name();
            }
        }
        return null;
    }

    private static String describe(Integer type) {
        for (MessageTypeEnum e : MessageTypeEnum.values()) {
            if (e.getType().equals(type)) {
                return e.name() + "(" + type + ")";
            }
        }
        return String.valueOf(type);
    }
}
