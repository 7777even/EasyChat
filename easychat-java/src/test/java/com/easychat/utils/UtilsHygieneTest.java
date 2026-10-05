package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 属性拷贝与 JSON 反序列化工具类测试（2026-10-05）
 *
 * <p>本文件锁定三项**日志与异常卫生**修复，它们本身不改变业务返回值，
 * 但决定「出问题时能查到什么」与「会不会把不该落盘的东西写进日志」。
 */
class UtilsHygieneTest {

    // ════════════════════════════════════════════════════════════════
    // #18 UserInfo.toString() 不得包含密码哈希
    // ════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#18 UserInfo.toString() 不含密码明文或哈希")
    void userInfoToStringMasksPassword() throws Exception {
        String bcrypt = "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP";
        com.easychat.entity.po.UserInfo u = new com.easychat.entity.po.UserInfo();
        u.setUserId("U001");
        u.setPassword(bcrypt);

        String s = u.toString();
        org.junit.jupiter.api.Assertions.assertFalse(s.contains(bcrypt),
                "toString() 泄露了完整的 BCrypt 哈希");
        org.junit.jupiter.api.Assertions.assertFalse(s.contains(bcrypt.substring(0, 20)),
                "toString() 泄露了哈希前缀");
        // 仍应保留「已设置」这一事实，否则排障时无法区分「没密码」与「密码被打码」
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("已设置"),
                "应保留密码是否已设置的信息，实际：" + s);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#18 密码为 null / 空串时 toString() 正常")
    void userInfoToStringHandlesNullPassword() {
        com.easychat.entity.po.UserInfo u = new com.easychat.entity.po.UserInfo();
        org.junit.jupiter.api.Assertions.assertTrue(u.toString().contains("密码:"));
        u.setPassword("");
        org.junit.jupiter.api.Assertions.assertTrue(u.toString().contains("密码:空"));
    }

    // ════════════════════════════════════════════════════════════════
    // #17 JsonUtils 不得把原文 JSON 写进日志
    // ════════════════════════════════════════════════════════════════

    /**
     * 捕获 slf4j 输出。
     *
     * <p>logback-classic 1.2.10 由 spring-boot-starter-logging 带入（在 compile classpath）。
     * 用 logback 自带的 {@code ListAppender} 而**不是** {@code java.lang.reflect.Proxy}：
     * {@code AppenderBase} 是**类**不是接口，代理无法通过 {@code addAppender} 的类型检查。
     */
    private static List<String> captureLogs (Runnable action) {
        ch.qos.logback.classic.Logger lb =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JsonUtils.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        lb.addAppender(appender);
        try {
            action.run();
        } finally {
            lb.detachAppender(appender);
            appender.stop();
        }
        List<String> out = new java.util.ArrayList<>();
        for (ch.qos.logback.classic.spi.ILoggingEvent e : new java.util.ArrayList<>(appender.list)) {
            out.add(e.getFormattedMessage());
        }
        return out;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#17 解析失败时日志不含原文 JSON（用户可控内容）")
    void jsonUtilsDoesNotLogRawPayload() {
        // 模拟一条用户可控的 WebSocket 消息正文，含伪造的日志行
        String malicious = "{\"a\":1}\n[INFO] 伪造的日志行";
        String logged = String.join("\n", captureLogs(() -> {
            try {
                JsonUtils.convertJson2Obj(malicious, JsonUtilsTest.Sample.class);
            } catch (BusinessException ignoreExpected) {
                // 期望抛 CODE_2102
            }
        }));

        org.junit.jupiter.api.Assertions.assertFalse(logged.contains("伪造的日志行"),
                "原文 JSON 被写进了日志，等于允许任意用户向日志注入内容");
        org.junit.jupiter.api.Assertions.assertFalse(logged.contains("{\"a\":1}"),
                "原文 JSON 被写进了日志");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#17 数组反序列化失败同样不落原文")
    void jsonArrayUtilsDoesNotLogRawPayload() {
        String malicious = "not-json\n[ERROR] 又一行伪造";
        String logged = String.join("\n", captureLogs(() -> {
            try {
                JsonUtils.convertJsonArray2List(malicious, JsonUtilsTest.Sample.class);
            } catch (BusinessException ignoreExpected) {
                // 期望抛 CODE_2102
            }
        }));
        org.junit.jupiter.api.Assertions.assertFalse(logged.contains("又一行伪造"),
                "原文被写进了日志");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#17 失败日志仍保留可诊断信息（类型 + 长度）")
    void jsonUtilsStillLogsDiagnostics() {
        String logged = String.join("\n", captureLogs(() -> {
            try {
                JsonUtils.convertJson2Obj("not-json", JsonUtilsTest.Sample.class);
            } catch (BusinessException ignoreExpected) {
                // 期望抛 CODE_2102
            }
        }));
        // 完全没有诊断信息同样不可接受：那就无法区分「格式错」还是「类型不匹配」
        org.junit.jupiter.api.Assertions.assertTrue(logged.contains("Sample"),
                "日志应含目标类型名，实际：" + logged);
        org.junit.jupiter.api.Assertions.assertTrue(logged.contains("原文长度=8"),
                "日志应含原文长度（not-json 为 8 字符），实际：" + logged);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#17 解析失败仍抛 CODE_2102（契约未变）")
    void jsonUtilsStillThrowsSameCode() {
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> JsonUtils.convertJson2Obj("not-json", JsonUtilsTest.Sample.class));
        org.junit.jupiter.api.Assertions.assertEquals(2102, e.getCode());
    }

    // ════════════════════════════════════════════════════════════════
    // #16 CopyTools 实例化失败必须抛出可诊断异常
    // ════════════════════════════════════════════════════════════════

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#16 目标类无无参构造器时异常信息指向真实原因")
    void copyToolsReportsRealCause() {
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> CopyTools.copy(new CopyToolsTest.Src(), CopyToolsTest.NoDefaultCtor.class));
        // 错误信息必须让人看出是「目标类无法实例化」，而不是 Spring 的 Target must not be null
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage() != null && e.getMessage().contains("NoDefaultCtor"),
                "异常信息应含目标类名，实际：" + e.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(
                e.getMessage() != null && e.getMessage().contains("Target must not be null"),
                "仍在抛 Spring 的语义不明异常，说明 InstantiationException 仍被吞");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("#16 正常拷贝不受影响")
    void copyToolsStillWorks() {
        CopyToolsTest.Src s = new CopyToolsTest.Src();
        s.setName("名字");
        s.setAge(1);
        CopyToolsTest.Dst d = CopyTools.copy(s, CopyToolsTest.Dst.class);
        org.junit.jupiter.api.Assertions.assertNotNull(d);
        org.junit.jupiter.api.Assertions.assertEquals("名字", d.getName());
        org.junit.jupiter.api.Assertions.assertEquals(1, d.getAge());
    }
}
