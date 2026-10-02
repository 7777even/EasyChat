package com.easychat.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;

import java.util.ArrayList;
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
            if (!StringTools.isEmpty(s)) {
                set.add(s.trim().replaceAll("\"", ""));
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
