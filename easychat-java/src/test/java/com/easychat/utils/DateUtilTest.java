package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * 日期格式化工具类测试（2026-10-04 建立，2026-10-05 随 java.time 迁移更新）
 *
 * <p><b>2026-10-05 背景</b>：实现已由 {@code SimpleDateFormat} + {@code ThreadLocal}
 * 整体迁移到 {@link java.time.format.DateTimeFormatter}（immutable，天然线程安全）。
 * 「并发不串扰」用例**保留**——它验证的是**契约**（多线程下格式化结果正确），
 * 而非某个具体实现手段。手段从 ThreadLocal 换成 ConcurrentHashMap 缓存后，
 * 该契约仍必须成立，故用例不作废。
 *
 * <p>另新增一组<b>与迁移前实现逐字节对拍</b>的用例：把 {@code SimpleDateFormat}
 * 作为参照物留在测试里，证明迁移**没有改变 {@code format} 的输出**。
 * 这是本次迁移最关键的安全性质——5 个生产调用点都依赖输出不变。
 */
@DisplayName("DateUtil — 日期格式化与解析")
class DateUtilTest {

    private static Date utc(int y, int m, int d, int h, int mi, int s) {
        return Date.from(LocalDateTime.of(y, m, d, h, mi, s)
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 迁移前的参照实现：{@code SimpleDateFormat}。
     *
     * <p>仅用于<b>对拍</b>——证明迁移后 {@code format} 的输出逐字节未变。
     */
    private static String formatBySimpleDateFormat(Date date, String pattern) {
        return new SimpleDateFormat(pattern).format(date);
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
    @DisplayName("format: null 日期抛 NPE（迁移前后一致，仅补上可读信息）")
    void formatNullDateThrows() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> DateUtil.format(null, "yyyy-MM-dd"));
        // 迁移前是裸 NPE（无信息）；现补上可读信息，便于定位是哪个参数为 null
        assertNotNull(e.getMessage());
    }

    // ── 与迁移前实现逐字节对拍 ─────────────────────────────────────
    // 这是本次迁移最关键的安全性质：5 个生产调用点（4 个 POJO 的 toString +
    // ChatMessageServiceImpl 两处按月归档）都依赖 format 输出不变。

    @Test
    @DisplayName("★ format: 与迁移前 SimpleDateFormat 输出逐字节一致")
    void formatMatchesLegacyImplementation() {
        Date[] samples = {
                utc(2026, 10, 4, 15, 30, 45),
                utc(2026, 1, 2, 3, 4, 5),
                utc(1999, 12, 31, 23, 59, 59),
                utc(2024, 2, 29, 0, 0, 0),
                utc(2026, 7, 9, 12, 0, 0)
        };
        // 覆盖本仓 DateTimePatternEnum 的全部 pattern + 若干额外 pattern
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyyMM",
                "yyyy-MM", "HH:mm:ss", "yyyy", "MM-dd"
        };
        for (Date d : samples) {
            for (String p : patterns) {
                assertEquals(formatBySimpleDateFormat(d, p), DateUtil.format(d, p),
                        "pattern=" + p + " 时输出与迁移前不一致");
            }
        }
    }

    @Test
    @DisplayName("★ format: DateTimePatternEnum 的三个生产 pattern 均与迁移前一致")
    void formatMatchesEnumPatterns() {
        Date d = utc(2026, 10, 4, 15, 30, 45);
        for (com.easychat.entity.enums.DateTimePatternEnum e
                : com.easychat.entity.enums.DateTimePatternEnum.values()) {
            String p = e.getPattern();
            assertEquals(formatBySimpleDateFormat(d, p), DateUtil.format(d, p),
                    "生产 pattern " + p + " 输出变了");
        }
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

    // ── #14 已修：解析失败不再返回「此刻」，改为抛异常 ──────────────
    // 迁移前：`catch (ParseException) { e.printStackTrace(); } return new Date();`
    //   → 调用方拿到一个语法合法、时间错误的值，且除 stdout 栈迹外无任何信号。
    //   叠加 SimpleDateFormat 默认 lenient，「2026-13-45」还会被**静默进位**成 2027-02-14。
    //   迁移后：STRICT 解析，失败抛 BusinessException(CODE_1001)。

    @Test
    @DisplayName("#14 parse: 解析失败抛 CODE_1001，不再返回「此刻」")
    void parseFailureThrows() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> DateUtil.parse("这不是日期", "yyyy-MM-dd"));
        assertEquals(1001, e.getCode());
    }

    @Test
    @DisplayName("#14 parse: 非法日期不再静默进位（13 月 45 日）")
    void parseRejectsImpossibleDate() {
        // 迁移前：进位成 2027-02-14，脏数据被改写成「看似合理」的值。
        assertThrows(BusinessException.class, () -> DateUtil.parse("2026-13-45", "yyyy-MM-dd"));
        assertThrows(BusinessException.class, () -> DateUtil.parse("2026-02-30", "yyyy-MM-dd"));
    }

    @Test
    @DisplayName("#14 parse: 尾部多余内容不再被忽略")
    void parseRejectsTrailingGarbage() {
        // 迁移前：SimpleDateFormat.parse 只解析能匹配的前缀，尾巴被静默丢弃。
        assertThrows(BusinessException.class,
                () -> DateUtil.parse("2026-10-04 这后面是乱码", "yyyy-MM-dd"));
    }

    @Test
    @DisplayName("#14 parse: 空入参抛 CODE_1001")
    void parseRejectsEmptyInput() {
        for (String s : new String[]{null, "", "   "}) {
            assertThrows(BusinessException.class, () -> DateUtil.parse(s, "yyyy-MM-dd"),
                    "空入参应被拒：" + s);
        }
    }

    @Test
    @DisplayName("#14 parse: 合法日期仍能解析（未被严格模式误伤）")
    void parseStillAcceptsValidDates() {
        assertEquals("2026-10-04", DateUtil.format(
                DateUtil.parse("2026-10-04", "yyyy-MM-dd"), "yyyy-MM-dd"));
        // 闰年 2 月 29 日必须接受（STRICT 下靠 proleptic year 才能正确判闰）
        assertEquals("2024-02-29", DateUtil.format(
                DateUtil.parse("2024-02-29", "yyyy-MM-dd"), "yyyy-MM-dd"));
        // 非闰年 2 月 29 日必须拒绝
        assertThrows(BusinessException.class, () -> DateUtil.parse("2026-02-29", "yyyy-MM-dd"));
    }

    @Test
    @DisplayName("#14 toProlepticYear: yyyy 改写为 uuuu，但跳过单引号字面量")
    void toProlepticYearTranslation() {
        assertEquals("uuuu-MM-dd HH:mm:ss", DateUtil.toProlepticYear("yyyy-MM-dd HH:mm:ss"));
        assertEquals("uuuuMM", DateUtil.toProlepticYear("yyyyMM"));
        // 字面量里的 yyyy 不可改
        assertEquals("'year yyyy' uuuu", DateUtil.toProlepticYear("'year yyyy' yyyy"));
        // 非 yyyy 字段原样保留
        assertEquals("uuuu年MM月dd日 E", DateUtil.toProlepticYear("yyyy年MM月dd日 E"));
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
