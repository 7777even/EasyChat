package com.easychat.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 名单类 JSON 数组的解析与序列化。
 *
 * <p><b>唯一真源说明</b>：本类的 {@link #parse(String)} 语义与朋友圈单条动态可见范围判定
 * （{@code MomentServiceImpl#canView}）所依赖的解析**逐字一致**——剥 {@code []}、按 {@code ,} 切分、
 * 去 {@code "}、去重、忽略空项。{@code MomentServiceImpl} 已改为委托本类，
 * 故「用户级名单」与「单条动态名单」不可能解析出不同结果。
 *
 * <p>若要修改解析语义，<b>必须</b>同时评估朋友圈可见范围判定的回归面。
 *
 * <p>存储格式示例：{@code ["U01234567890","U09876543210"]}，
 * 与既有 {@code moment.visible_list} / {@code moment.invisible_list} 同构。
 *
 * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
 */
public class IdListTools {

    /**
     * 名单里**不算成员**的空值字面量（大小写不敏感）。
     *
     * <p>2026-10-13 新增（openspec {@code 2026-10-13-id-list-null-literal-guard}，遗留 #20 治本）。
     *
     * <p><b>为什么需要它</b>：{@link StringTools#isEmpty} 只认<b>小写</b> {@code "null"}
     * （{@code "null".equals(str)}），所以 {@code "NULL"} / {@code "undefined"} /
     * {@code "NaN"} / {@code "None"} 会一路走到 {@code set.add(...)}，
     * 被当成一个<b>真实的用户 id</b>。活库 {@code user_info.moment_visible_list}
     * 上那条字面量 {@code "NULL"}（非 SQL NULL）就是这么来的。
     *
     * <p><b>为什么用黑名单而不是「id 形状白名单」</b>（ADR-001）：
     * 真实 id 一律以 {@code U}（用户）/ {@code G}（群）开头 ——
     * {@code StringTools#getUserId} / {@code #getGroupId} 都是拼接
     * {@code UserContactTypeEnum} 的 prefix —— <b>不可能等于</b>下面这几个字面量，
     * 故剔除它们<b>可证</b>不误伤任何合法 id。
     * 反过来，形状白名单会误伤：既有单测用的是 {@code U001}（3 位），
     * 生产是 {@code U} + 11 位数字，两种都该放行，任何一种形状规则都会打穿其中一边。
     *
     * <p>★ 判定是<b>全等</b>而非包含，故 {@code U0null1} 这类 id 不受影响。
     */
    private static final Set<String> NULL_LITERAL_TOKENS = new HashSet<>(
            Arrays.asList("null", "undefined", "nan", "none"));

    /**
     * 单个名单列的长度上限（字符）。
     * <p>
     * 对应 {@code TEXT} 列（最大 65535 字节，UTF-8 下中文 3 字节）。
     * 留出余量，避免极端名单把列撑爆导致整行写入失败。
     * 微信好友量级上限在数千，60000 字符足够容纳约 2000+ 个 12 位 id。
     */
    public static final int MAX_LENGTH = 60000;

    private IdListTools() {
    }

    /**
     * 解析 JSON 数组字符串为去重后的 id 列表。
     * <p>
     * 宽松解析：空 / null / 非数组文本均返回空列表，不抛错。
     * 读自己写入的数据用这个。
     *
     * @param json JSON 数组字符串，可为 null
     * @return 去重后的 id 列表，永不为 null
     */
    /**
     * 判断一个 token 是否为「空值字面量」（大小写不敏感、全等匹配）。
     *
     * <p>刻意做成<b>大小写不敏感</b>（ADR-002）：污染源是各种工具的数据往返，
     * Java / JS / Python 的空值表示各不相同，只挡一种变体等于给下一种留门。
     *
     * @see #NULL_LITERAL_TOKENS
     */
    private static boolean isNullLiteral(String token) {
        return NULL_LITERAL_TOKENS.contains(token.toLowerCase());
    }

    public static List<String> parse(String json) {
        List<String> result = new ArrayList<>();
        if (StringTools.isEmpty(json)) {
            return result;
        }
        String temp = json.trim();
        if (temp.startsWith("[") && temp.endsWith("]")) {
            temp = temp.substring(1, temp.length() - 1);
        }
        if (StringTools.isEmpty(temp)) {
            return result;
        }
        String[] arr = temp.split(",");
        Set<String> set = new HashSet<>();
        for (String s : arr) {
            // ⚠️ 顺序要紧：**先规范化、再判空**（2026-10-05 修复）。
            //
            // 原写法是「先判空、再 trim 并剥引号」，而判空作用于**未剥引号的原文**：
            //   isEmpty("\"\"")    长度 2 → false → 进入分支 → 剥引号后留下空串
            //   isEmpty("\"   \"") 长度 5 → false → 进入分支 → 剥引号后留下 "   "
            // 即 javadoc 承诺的「忽略空项」**从未生效**，空串/空白会真的成为名单成员。
            // （这类缺陷不会报错，只表现为「多了个不存在的用户」，最难靠回归发现。）
            //
            // 第二次 trim 不可省：`"  "` 剥掉引号后得到 `  `，若只在剥引号前 trim
            // 一次，这段空白仍会漏进结果。
            String norm = s.trim().replaceAll("\"", "").trim();
            // ★ 2026-10-13（遗留 #20 治本）：除 isEmpty 外，再剔除**空值字面量**。
            //   isEmpty 只认小写 "null"，故 "NULL" / "undefined" / "NaN" / "None"
            //   会漏过并被当成一个真实用户 id —— 白名单模式下即「除作者外无人能看」。
            //   剔除后脏名单与空名单在 canView 里表现一致（设计见 ADR-005 恒等性表）。
            if (!StringTools.isEmpty(norm) && !isNullLiteral(norm)) {
                set.add(norm);
            }
        }
        result.addAll(set);
        return result;
    }

    /**
     * 序列化 id 列表为 JSON 数组字符串。
     *
     * @param ids id 集合，可为 null 或空
     * @return {@code ["a","b"]} 形式；入参为空时返回 {@code null}（便于「不写该列」）
     * @throws BusinessException 序列化后超长 → CODE_1001
     */
    public static String serialize(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        String json = JSON.toJSONString(new ArrayList<>(new HashSet<>(ids)));
        if (json.length() > MAX_LENGTH) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        return json;
    }

    /**
     * 严格校验：确认是合法 JSON 数组，且元素均为非空字符串。
     * <p>
     * 与 {@link #parse(String)} 的宽松不同，这个供<b>写入口</b>使用——
     * 用户传了 {@code not-a-json} 这种脏数据必须在落库前被拒。
     *
     * @param json 待校验文本，可为 null（返回 true，表示「未提供」由调用方按业务规则处理）
     * @return 合法返回 true
     * @throws BusinessException 非法 JSON 或元素类型不对 → CODE_1001
     */
    public static boolean validate(String json) {
        if (StringTools.isEmpty(json) || json.trim().isEmpty()) {
            return true;
        }
        String trimmed = json.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        JSONArray array;
        try {
            array = JSON.parseArray(trimmed);
        } catch (Exception e) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        if (array == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof String) || StringTools.isEmpty(((String) item).trim())) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001);
            }
        }
        return true;
    }
}
