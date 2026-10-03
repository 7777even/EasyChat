package com.easychat.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExtraDataTools} 单元测试。
 *
 * <p><b>为什么这些必须有单测</b>：{@code isAtAll} 的返回值直接决定
 * {@code saveMessage} 是否对群角色做校验。判错方向不同，后果完全不同：
 * <ul>
 *   <li><b>误判为 true</b>（普通成员被当作@所有人）→ 普通成员在群里<b>发不出消息</b>，
 *       属于功能性故障且极难定位（用户只会说「发不了消息」）；</li>
 *   <li><b>误判为 false</b>（@所有人 被当作普通消息）→ 权限校验被绕过，
 *       即本次要堵的洞。</li>
 * </ul>
 * 因此本类的默认倾向必须是 <b>false</b>：只有<b>明确</b>的顶层布尔 {@code atAll=true}
 * 才返回 true。
 *
 * <p>另一条硬约束：<b>解析失败绝不能抛异常打断正常发消息</b>。{@code extraData} 是
 * 客户端原文，可能为空、超长、非法 JSON——这些都必须安静地按「非 @所有人」处理。
 *
 * @since 2026-10-03（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）
 */
public class ExtraDataToolsTest {

    // ==================== 明确为 true ====================

    @Test
    public void isAtAll_explicitTrue() {
        assertTrue(ExtraDataTools.isAtAll("{\"atAll\":true}"));
    }

    @Test
    public void isAtAll_trueAmongOtherFields() {
        assertTrue(ExtraDataTools.isAtAll("{\"quoteId\":123,\"atAll\":true,\"note\":\"hi\"}"));
    }

    // ==================== 明确为 false ====================

    @Test
    public void isAtAll_explicitFalse() {
        assertFalse(ExtraDataTools.isAtAll("{\"atAll\":false}"));
    }

    @Test
    public void isAtAll_missingField() {
        assertFalse(ExtraDataTools.isAtAll("{\"quoteId\":123}"));
    }

    /**
     * 只认<b>布尔</b> {@code true}。字符串 "1" / "true" / 数字 1 都不算——
     * 否则 {@code {"atAll":"false"}} 这类值会被 fastjson 宽松地转成 true，
     * 造成普通成员被误拦。
     */
    @Test
    public void isAtAll_stringOne_isNotTrue() {
        assertFalse(ExtraDataTools.isAtAll("{\"atAll\":\"1\"}"));
    }

    @Test
    public void isAtAll_stringTrue_isNotTrue() {
        assertFalse(ExtraDataTools.isAtAll("{\"atAll\":\"true\"}"));
    }

    @Test
    public void isAtAll_numericOne_isNotTrue() {
        assertFalse(ExtraDataTools.isAtAll("{\"atAll\":1}"));
    }

    /** 嵌套同名字段不算：顶层没有 atAll 就不是 @所有人 消息 */
    @Test
    public void isAtAll_nestedField_isNotTrue() {
        assertFalse(ExtraDataTools.isAtAll("{\"data\":{\"atAll\":true}}"));
    }

    // ==================== 异常输入一律 false 且不抛 ====================

    @Test
    public void isAtAll_null_isFalse() {
        assertFalse(ExtraDataTools.isAtAll(null));
    }

    @Test
    public void isAtAll_emptyString_isFalse() {
        assertFalse(ExtraDataTools.isAtAll(""));
    }

    @Test
    public void isAtAll_blankString_isFalse() {
        assertFalse(ExtraDataTools.isAtAll("   "));
    }

    @Test
    public void isAtAll_invalidJson_isFalseWithoutThrowing() {
        // 绝不能抛异常打断正常发消息
        assertFalse(ExtraDataTools.isAtAll("{not json"));
    }

    @Test
    public void isAtAll_jsonArray_isFalse() {
        // 数组不是对象，顶层取不到 atAll
        assertFalse(ExtraDataTools.isAtAll("[{\"atAll\":true}]"));
    }

    @Test
    public void isAtAll_oversizedInput_isFalse() {
        // 超长输入直接拒（防拿大 JSON 撑内存），且必须是 false 而不是抛异常
        StringBuilder sb = new StringBuilder("{\"pad\":\"");
        for (int i = 0; i < 5000; i++) {
            sb.append('x');
        }
        sb.append("\",\"atAll\":true}");
        assertFalse(ExtraDataTools.isAtAll(sb.toString()));
    }
}