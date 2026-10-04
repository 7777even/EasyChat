package com.easychat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日期格式化工具类测试（2026-10-04）
 *
 * <p>{@code SimpleDateFormat} 非线程安全，本类用 {@code ThreadLocal} 规避，
 * 本测试须覆盖并发路径——否则「换了线程就串格式」这类缺陷不会被发现。
 *
 * <p>⚠️ <b>断言口径</b>：锁定现状。标注「⚠ 现状」的用例对应缺陷，
 * 已在 {@code docs/system-facts.md} §14 登记。
 */
@DisplayName("DateUtil — 日期格式化与解析")
class DateUtilTest {

    private static Date utc(int y, int m, int d, int h, int mi, int s) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            return f.parse(String.format("%04d-%02d-%02d %02d:%02d:%02d", y, m, d, h, mi, s));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // format
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("format: 常用 pattern")
    void formatCommonPatterns() {
        Date d = utc(2026, 10, 4, 15, 30, 45);
        assertEquals("2026-10-04 15:30:45", DateUtil.format(d, "yyyy-MM-dd HH:mm:ss"));
        assertEquals("20261004", DateUtil.format(d, "yyyyMMdd"));
        assertEquals("2026-10", DateUtil.format(d, "yyyy-MM"));
        assertEquals("15:30:45", DateUtil.format(d, "HH:mm:ss"));
    }

    @Test
    @DisplayName("format: 零填充")
    void formatZeroPadding() {
        Date d = utc(2026, 1, 2, 3, 4, 5);
        assertEquals("2026-01-02 03:04:05", DateUtil.format(d, "yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    @DisplayName("format: ⚠ 现状 null 日期抛 NullPointerException（本类不拦截）")
    void formatNullDateThrows() {
        // ⚠ 现状：SimpleDateFormat.format(null) 抛 NPE，本类不捕获，
        //   直接向上抛。调用方需自行保证非 null。
        //   对比 parse —— 那个方法反而吞掉异常返回 now，两者错误策略不一致。
        assertThrows(NullPointerException.class, () -> DateUtil.format(null, "yyyy-MM-dd"));
    }

    // ─────────────────────────────────────────────────────────────
    // parse
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("parse: 正常解析")
    void parseValid() {
        Date d = DateUtil.parse("2026-10-04 15:30:45", "yyyy-MM-dd HH:mm:ss");
        assertNotNull(d);
        assertEquals("2026-10-04 15:30:45", DateUtil.format(d, "yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    @DisplayName("⚠ 现状 parse: 解析失败**返回当前时间**而非抛异常")
    void parseFailureReturnsNow() {
        // ⚠ 现状（已登记为缺陷）：`catch (ParseException) { e.printStackTrace(); }`
        //   之后 `return new Date()` —— 即**返回「此刻」**。
        //   调用方拿到的是一个语法合法、时间错误的值，
        //   且除 stdout 栈迹外没有任何信号（异常被吞）。
        //
        //   注意本方法**当前主代码零调用**（只有 format 被用了 5 处），
        //   故属潜伏陷阱；但下一个调用者会直接踩中。
        long before = System.currentTimeMillis();
        Date d = DateUtil.parse("这不是日期", "yyyy-MM-dd");
        long after = System.currentTimeMillis();

        assertNotNull(d, "现状是返回 now，不是返回 null");
        assertTrue(d.getTime() >= before && d.getTime() <= after,
                "返回值应落在调用前后时刻之间（即 now），实际=" + d.getTime());
    }

    @Test
    @DisplayName("⚠ 现状 parse: SimpleDateFormat 宽松模式，非法日期被静默进位")
    void parseIsLenient() {
        // ⚠ 现状（已登记为缺陷）：SimpleDateFormat 默认 lenient=true，
        //   「2026-13-45」不报错而是进位成 2027-02-14。
        //   即「脏数据」被静默改写成「看似合理」的值。
        Date d = DateUtil.parse("2026-13-45", "yyyy-MM-dd");
        assertNotNull(d);
        String normalized = DateUtil.format(d, "yyyy-MM-dd");
        assertEquals("2027-02-14", normalized,
                "宽松模式把 13 月 45 日进位成了次年 2 月 14 日");
    }

    @Test
    @DisplayName("parse: 部分可解析的前缀也能过（宽松的另一面）")
    void parseAcceptsPartialInput() {
        // ⚠ 与上一条同源：SimpleDateFormat.parse 只解析能匹配的前缀，
        //   尾部多余内容被忽略，故「2026-10-04 乱码尾巴」也能解析成功。
        Date d = DateUtil.parse("2026-10-04 这后面是乱码", "yyyy-MM-dd");
        assertEquals("2026-10-04", DateUtil.format(d, "yyyy-MM-dd"));
    }

    @Test
    @DisplayName("parse: format → parse 往返一致")
    void parseFormatRoundTrip() {
        Date original = utc(2026, 10, 4, 15, 30, 45);
        String text = DateUtil.format(original, "yyyy-MM-dd HH:mm:ss");
        Date back = DateUtil.parse(text, "yyyy-MM-dd HH:mm:ss");
        assertEquals(text, DateUtil.format(back, "yyyy-MM-dd HH:mm:ss"));
    }

    // ─────────────────────────────────────────────────────────────
    // 并发（ThreadLocal 存在的主要理由）
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("并发 format: 同一 pattern 多线程不串扰（ThreadLocal 生效）")
    void concurrentFormatSamePattern() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            Date expectedDate = utc(2026, 10, 4, 15, 30, 45);
            String expected = "2026-10-04 15:30:45";
            for (int i = 0; i < 400; i++) {
                tasks.add(() -> DateUtil.format(expectedDate, "yyyy-MM-dd HH:mm:ss"));
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (Future<String> f : futures) {
                // 若 SimpleDateFormat 被共享，此处在高并发下会随机出现错乱格式
                assertEquals(expected, f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发 format: 不同 pattern 并存互不干扰")
    void concurrentFormatMixedPatterns() throws Exception {
        // 用 List 而非 Set：需要按下标轮转取 pattern（Set 无 get(int)）
        List<String> patternList = new ArrayList<>(List.of(
                "yyyy-MM-dd HH:mm:ss", "yyyyMMdd", "yyyy-MM", "HH:mm:ss"));
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            Date d = utc(2026, 10, 4, 15, 30, 45);
            String full = "2026-10-04 15:30:45";
            for (int i = 0; i < 400; i++) {
                final String p = patternList.get(i % patternList.size());
                tasks.add(() -> DateUtil.format(d, p));
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                String p = patternList.get(i % patternList.size());
                String actual = futures.get(i).get(10, TimeUnit.SECONDS);
                String expected = DateUtil.format(d, p);
                assertEquals(expected, actual,
                        "pattern=" + p + " 在并发下被别的 pattern 污染");
            }
            assertEquals(full, DateUtil.format(d, "yyyy-MM-dd HH:mm:ss"));
        } finally {
            pool.shutdownNow();
        }
    }
}
