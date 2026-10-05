package com.easychat.utils;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日期格式化与解析工具类
 *
 * <p><b>2026-10-05 由 {@code SimpleDateFormat} 整体迁移到 {@link DateTimeFormatter}</b>，
 * 一次解决三项已登记遗留：
 *
 * <ol>
 *   <li><b>#14 解析失败返回「此刻」</b>：原实现 {@code catch (ParseException) {
 *       e.printStackTrace(); } return new Date();} —— 解析失败返回**当前时间**，
 *       调用方拿到一个语法合法、时间错误的值，且除 stdout 栈迹外无任何信号。
 *       叠加 {@code SimpleDateFormat} 默认 lenient，{@code parse("2026-13-45")} 会被
 *       <b>静默进位成 2027-02-14</b>，{@code parse("2026-10-04 后面是乱码")} 也「成功」。
 *       现改为 {@link ResolverStyle#STRICT} 严格解析，失败抛
 *       {@link BusinessException}（{@code CODE_1001}）。</li>
 *   <li><b>#15 不安全发布</b>：原实现是 {@code HashMap} + 同步块内二次检查的
 *       double-checked locking，且字段非 {@code volatile} —— 同步块外的读没有
 *       happens-before 保证。{@code DateTimeFormatter} 是 <b>immutable 且线程安全</b>的，
 *       故只需一个 {@link ConcurrentHashMap} 缓存即可，竞态从根上不存在。</li>
 *   <li><b>lenient 进位</b>：随 #14 一并消除（STRICT 下 13 月 45 日直接报错）。</li>
 * </ol>
 *
 * <p><b>兼容性</b>：{@link #format} 的输出与原实现<b>逐字节一致</b>——
 * 两者都用系统默认时区与 {@code Locale.Category.FORMAT}；本仓的 pattern
 * （{@code DateTimePatternEnum}：{@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd} /
 * {@code yyyyMM}）全为纯数字，不涉及月份名称等受 Locale 影响的字段。
 *
 * <p><b>已知的刻意取舍</b>：{@link #parse} 采用严格模式，故
 * {@code 2026-1-4} 这类非零填充输入会被拒绝（要求与 pattern 宽度一致）。
 * 这是「宁可报错也不静默改写」的直接后果，符合本项目一贯取向。
 */
public class DateUtil {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 宽松格式化器（供 format 使用）。DateTimeFormatter immutable，天然线程安全。 */
    private static final Map<String, DateTimeFormatter> FORMATTERS = new ConcurrentHashMap<>();

    /** 严格解析器（供 parse 使用）。独立缓存，避免与 format 混用同一实例。 */
    private static final Map<String, DateTimeFormatter> STRICT_FORMATTERS = new ConcurrentHashMap<>();

    private DateUtil() {
    }

    // ════════════════════════════════════════════════════════════════

    /**
     * 格式化日期。
     *
     * @param date    非 null
     * @param pattern 见 {@code DateTimePatternEnum}
     * @return 格式化结果；输出与迁移前逐字节一致
     * @throws NullPointerException date 为 null（迁移前亦然，仅补上可读信息）
     */
    public static String format(Date date, String pattern) {
        Objects.requireNonNull(date, "date 不能为 null");
        Objects.requireNonNull(pattern, "pattern 不能为 null");
        DateTimeFormatter f = FORMATTERS.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
        return f.format(LocalDateTime.ofInstant(date.toInstant(), ZONE));
    }

    /**
     * 解析日期字符串（**严格**）。
     *
     * <p>与迁移前的三处行为差异（均为有意）：
     * <ol>
     *   <li>失败时**抛异常**，不再返回「此刻」；</li>
     *   <li>非法日期（如 {@code 2026-13-45}）**不再静默进位**；</li>
     *   <li>尾部多余内容**不再被忽略**（STRICT 要求整串被消费）。</li>
     * </ol>
     *
     * @param dateStr 待解析文本
     * @param pattern 见 {@code DateTimePatternEnum}
     * @return 解析结果
     * @throws BusinessException 文本不匹配 pattern 时抛出 {@code CODE_1001}
     */
    public static Date parse(String dateStr, String pattern) {
        if (dateStr == null || dateStr.trim().isEmpty() || pattern == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "日期字符串与格式不能为空");
        }
        DateTimeFormatter f = STRICT_FORMATTERS.computeIfAbsent(pattern, DateUtil::strictFormatter);
        try {
            // ⚠️ 必须用 parseBest 而非 LocalDateTime.parse：
            //   本仓的 DateTimePatternEnum 含**纯日期** pattern（如 `yyyy-MM-dd`），
            //   而 `LocalDateTime.parse` 要求有完整时间字段，纯日期输入会抛异常。
            //   实测确认过：`LocalDate.parse("2026-10-04", uuuu-MM-dd)` 成功，
            //   `LocalDateTime.parse(同)` 失败。
            //   纯日期按「当日 00:00:00」处理，与迁移前 SimpleDateFormat 的默认行为一致。
            java.time.temporal.TemporalAccessor ta =
                    f.parseBest(dateStr.trim(), LocalDateTime::from, java.time.LocalDate::from);
            LocalDateTime dt = (ta instanceof LocalDateTime)
                    ? (LocalDateTime) ta
                    : ((java.time.LocalDate) ta).atStartOfDay();
            return new Date(dt.atZone(ZONE).toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001,
                    "日期格式不合法：" + dateStr + "（期望格式 " + pattern + "）");
        }
    }

    // ════════════════════════════════════════════════════════════════

    private static DateTimeFormatter strictFormatter(String pattern) {
        return DateTimeFormatter.ofPattern(toProlepticYear(pattern))
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * 把 pattern 中的 {@code yyyy}（year-of-era）改写为 {@code uuuu}（proleptic year）。
     *
     * <p><b>为何必须改写</b>：{@link ResolverStyle#STRICT} 下 {@code yyyy} 表示
     * 「纪元中的年份」，没有 era 就无法消解，故即便输入是完整的 {@code 2026-10-04}
     * 也会抛 {@code DateTimeParseException}。{@code uuuu} 才是 STRICT 要求的写法。
     *
     * <p>本仓的调用方（{@code DateTimePatternEnum}）全部使用 {@code yyyy} 写法，
     * 而 {@link #format} 侧必须保持原样以保证输出不变 —— 故只在<b>解析</b>侧做改写，
     * 且改写会跳过单引号包裹的字面量（{@code 'at' yyyy} 中的 yyyy 是字面量，不可改）。
     */
    static String toProlepticYear(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() + 8);
        boolean inLiteral = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                // '' 表示转义后的单引号，不切换状态
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                    sb.append(c).append(c);
                    i++;
                    continue;
                }
                inLiteral = !inLiteral;
                sb.append(c);
                continue;
            }
            if (!inLiteral && c == 'y' && i + 3 < pattern.length()
                    && pattern.charAt(i + 1) == 'y'
                    && pattern.charAt(i + 2) == 'y'
                    && pattern.charAt(i + 3) == 'y') {
                sb.append("uuuu");
                i += 3;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
