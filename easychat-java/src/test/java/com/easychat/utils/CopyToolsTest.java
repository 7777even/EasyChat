package com.easychat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean 属性拷贝工具类测试（2026-10-04）
 *
 * <p>{@code CopyTools} 被 22 处调用（Controller 出参、Service 内 DTO 转换），
 * 是全仓使用频次最高的工具类之一，且**静默失败**风险集中在这里：
 * 实例化失败时异常被吞、目标对象保持 null。
 */
@DisplayName("CopyTools — Bean 属性拷贝")
class CopyToolsTest {

    /** 源对象。 */
    public static class Src {
        private String name;
        private Integer age;
        private String onlyInSrc;

        public String getName() { return name; }

        public void setName(String name) { this.name = name; }

        public Integer getAge() { return age; }

        public void setAge(Integer age) { this.age = age; }

        public String getOnlyInSrc() { return onlyInSrc; }

        public void setOnlyInSrc(String onlyInSrc) { this.onlyInSrc = onlyInSrc; }
    }

    /** 目标对象：字段部分同名，用于验证「只拷同名同类型」。 */
    public static class Dst {
        private String name;
        private Integer age;

        public String getName() { return name; }

        public void setName(String name) { this.name = name; }

        public Integer getAge() { return age; }

        public void setAge(Integer age) { this.age = age; }
    }

    /** 无无参构造器 —— 用于触发实例化失败分支。 */
    public static class NoDefaultCtor {
        public NoDefaultCtor(String required) {
            if (required == null) {
                throw new IllegalArgumentException("必须提供参数");
            }
        }
    }

    private static Src sample() {
        Src s = new Src();
        s.setName("名字");
        s.setAge(30);
        s.setOnlyInSrc("仅源端有");
        return s;
    }

    // ─────────────────────────────────────────────────────────────
    // copy
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("copy: 同名同类型属性被拷贝")
    void copyMatchingProperties() {
        Dst d = CopyTools.copy(sample(), Dst.class);
        assertNotNull(d);
        assertEquals("名字", d.getName());
        assertEquals(30, d.getAge());
    }

    @Test
    @DisplayName("copy: 目标端不存在的属性被忽略，不报错")
    void copyIgnoresUnmatchedProperties() {
        // Src 有 onlyInSrc，Dst 没有 —— BeanUtils 静默跳过，不应抛异常。
        assertNotNull(CopyTools.copy(sample(), Dst.class));
    }

    @Test
    @DisplayName("copy: 源字段为 null 时目标端保持 null")
    void copyNullFields() {
        Src s = new Src();
        s.setName(null);
        s.setAge(null);
        Dst d = CopyTools.copy(s, Dst.class);
        assertNotNull(d);
        assertEquals(null, d.getName());
        assertEquals(null, d.getAge());
    }

    @Test
    @DisplayName("目标类无无参构造器时抛出的异常信息指向真实原因（#16 已修）")
    void copyWithoutDefaultConstructorThrows() {
        // 修复前：`newInstance()` 的 InstantiationException 被 `printStackTrace()` 吞掉
        // （打到 stdout，不受日志级别控制），随后 Spring 抛语义不明的
        // IllegalArgumentException("Target must not be null")，真实原因未被指向。
        // 修复后：BusinessException 的信息里含目标类名与「无参构造器」这一真实原因。
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> CopyTools.copy(sample(), NoDefaultCtor.class));
        assertTrue(e.getMessage() != null && e.getMessage().contains("NoDefaultCtor"),
                "异常信息应含目标类名，实际：" + e.getMessage());
        assertTrue(e.getMessage() != null && e.getMessage().contains("无参构造器"),
                "异常信息应指出真实原因，实际：" + e.getMessage());
        assertFalse(e.getMessage() != null && e.getMessage().contains("Target must not be null"),
                "仍在抛 Spring 的语义不明异常，说明 InstantiationException 仍被吞");
    }

    // ─────────────────────────────────────────────────────────────
    // copyList
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("copyList: 逐项拷贝并保持顺序与长度")
    void copyListPreservesOrderAndSize() {
        Src a = new Src();
        a.setName("A");
        a.setAge(1);
        Src b = new Src();
        b.setName("B");
        b.setAge(2);

        List<Dst> list = CopyTools.copyList(Arrays.asList(a, b), Dst.class);
        assertEquals(2, list.size());
        assertEquals("A", list.get(0).getName());
        assertEquals(1, list.get(0).getAge());
        assertEquals("B", list.get(1).getName());
        assertEquals(2, list.get(1).getAge());
    }

    @Test
    @DisplayName("copyList: 空列表返回空列表")
    void copyListEmpty() {
        assertTrue(CopyTools.copyList(Collections.emptyList(), Dst.class).isEmpty());
    }

    @Test
    @DisplayName("copyList: null 入参返回空列表（#16 已修，原为裸 NPE）")
    void copyListNullReturnsEmpty() {
        // 修复前：`for (S s : null)` 直接 NPE，异常信息完全没指向是入参为 null。
        assertNotNull(CopyTools.copyList(null, Dst.class));
        assertTrue(CopyTools.copyList(null, Dst.class).isEmpty());
    }

    @Test
    @DisplayName("⚠ 现状 copyList: 元素为 null 时抛 IllegalArgumentException")
    void copyListWithNullElementThrows() {
        // ⚠ 现状（已登记为缺陷）：Spring 的 BeanUtils.copyProperties 对 **source 为 null**
        //   也做 Assert.notNull，故抛 IllegalArgumentException("Source must not be null")。
        //   本轮初版以为 null 元素会被「洗」成空对象，实测并非如此。
        //   22 个调用点均来自 Mapper 结果列表（元素不会是 null），故当前不会触发。
        Src a = new Src();
        a.setName("A");
        List<Src> withNull = Arrays.asList((Src) null, a);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CopyTools.copyList(withNull, Dst.class));
        assertTrue(e.getMessage() != null && e.getMessage().contains("Source"),
                "错误信息应指向 source 为 null，实际：" + e.getMessage());
    }

    // ─────────────────────────────────────────────────────────────
    // 并发
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("并发 copy: 多线程下结果仍正确（工具类无共享可变状态）")
    void concurrentCopyIsCorrect() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final String expected = "n" + i;
                tasks.add(() -> {
                    Src s = new Src();
                    s.setName(expected);
                    s.setAge(1);
                    return CopyTools.copy(s, Dst.class).getName();
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                assertEquals("n" + i, futures.get(i).get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
