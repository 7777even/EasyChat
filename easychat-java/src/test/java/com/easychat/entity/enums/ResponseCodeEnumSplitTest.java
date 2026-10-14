package com.easychat.entity.enums;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * 错误码定义验证（2026-10-14，error-code-split T1）。
 *
 * <p>覆盖三件事：① 两个新码存在且落在通用段；② 文案非空且互不相同；
 * ③ 全枚举**无重复数值**（重复码会让按码分流静默失效）。
 */
public class ResponseCodeEnumSplitTest {

    private static final int GENERIC_LOW = 1000;
    private static final int GENERIC_HIGH = 1999;

    @Test
    public void rateLimitedCodeExists() {
        ResponseCodeEnum code = ResponseCodeEnum.CODE_1004;
        assertNotNull("CODE_1004 必须存在", code);
        // getCode() 返回 Integer：JUnit4 下 assertEquals(int, Integer) 二义，必须显式 intValue
        assertEquals(1004, code.getCode().intValue());
    }

    @Test
    public void captchaCodeExists() {
        ResponseCodeEnum code = ResponseCodeEnum.CODE_1005;
        assertNotNull("CODE_1005 必须存在", code);
        assertEquals(1005, code.getCode().intValue());
    }

    /** 新码必须落在通用段内（AGENTS §3.1：新增码须落在对应分段） */
    @Test
    public void newCodesFallInGenericSegment() {
        for (ResponseCodeEnum c : new ResponseCodeEnum[] {
                ResponseCodeEnum.CODE_1004, ResponseCodeEnum.CODE_1005 }) {
            assertTrue(c.name() + " 必须落在通用段 " + GENERIC_LOW + "-" + GENERIC_HIGH
                    + "，实际 " + c.getCode(),
                    c.getCode() >= GENERIC_LOW && c.getCode() <= GENERIC_HIGH);
        }
    }

    @Test
    public void newCodesHaveDistinctNonEmptyMessages() {
        String a = ResponseCodeEnum.CODE_1004.getMsg();
        String b = ResponseCodeEnum.CODE_1005.getMsg();
        assertNotNull(a);
        assertNotNull(b);
        assertFalse("文案不得为空", a.trim().isEmpty() || b.trim().isEmpty());
        assertFalse("两个新码文案不得相同（否则客户端按码分流无意义）：" + a, a.equals(b));
    }

    /**
     * 反向制衡：若两个新码被写成同一个数值，按码分流就会**静默失效**
     * （客户端以为在区分限流与验证码，实际拿到的是同一个码）。
     */
    @Test
    public void noDuplicateCodesInEnum() {
        Set<Integer> seen = new HashSet<>();
        for (ResponseCodeEnum c : ResponseCodeEnum.values()) {
            assertTrue("重复错误码：" + c.name() + " = " + c.getCode(), seen.add(c.getCode()));
        }
    }

    /** 成功码必须保持 0 —— 它是全仓「成功」的唯一判据，动它等于全站崩 */
    @Test
    public void successCodeRemainsZero() {
        assertEquals(0, ResponseCodeEnum.CODE_200.getCode().intValue());
    }
}
