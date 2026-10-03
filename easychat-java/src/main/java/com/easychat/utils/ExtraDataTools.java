package com.easychat.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 消息扩展数据（{@code chat_message.extra_data}）的解析工具。
 *
 * <p><b>存在理由</b>：{@code extraData} 是<strong>客户端原文 JSON</strong>，
 * 目前已有两处消费它——{@code ChatController} 里解析位置消息的 {@code location}，
 * 以及本类解析 @所有人 的 {@code atAll}。若各处内联 fastjson，解析风格与容错策略会分叉。
 * 收敛到工具类后，「非预期输入一律当作无该标记」这条规则只有一份实现。
 *
 * @since 2026-10-03（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）
 */
public class ExtraDataTools {

    /**
     * {@code extra_data} 允许的最大长度（字符）。
     *
     * <p>与 {@code ChatController} 对位置消息的 2000 字符上限同量级。
     * 超长直接判 false 而不是解析——客户端没有理由发这么长的扩展数据，
     * 解析大 JSON 既费 CPU 又可能拖垮内存。
     */
    public static final int MAX_LENGTH = 2000;

    /**
     * 判断扩展数据是否为「@所有人」消息。
     *
     * <p><b>默认倾向必须是 false</b>：只有<b>明确的顶层布尔</b> {@code atAll=true} 才返回 true。
     * 理由是两个误判方向的后果不对称：
     * <ul>
     *   <li>误判 <b>true</b> → 普通成员被当作@所有人，服务端角色校验会拦下他，
     *       表现为「群成员发不出消息」，属功能性故障且极难定位；</li>
     *   <li>误判 <b>false</b> → 权限校验被绕过，即本次要堵的洞。</li>
     * </ul>
     * 因此字符串 {@code "1"} / {@code "true"} / 数字 {@code 1} 一律<b>不</b>算 true：
     * fastjson 会宽松地把它们转成 true，从而放大了误判 true 的风险。
     *
     * <p><b>本方法保证不抛异常</b>：{@code extraData} 是客户端原文，可能为空、
     * 非法 JSON、超长——这些都必须安静地返回 false，<b>绝不能</b>打断正常发消息。
     *
     * @param extraData 客户端传入的扩展数据原文，可为 null
     * @return 仅当存在顶层 {@code "atAll": true} 时返回 true
     */
    public static boolean isAtAll(String extraData) {
        try {
            if (StringTools.isEmpty(extraData) || extraData.length() > MAX_LENGTH) {
                return false;
            }
            JSONObject obj = JSON.parseObject(extraData);
            if (obj == null) {
                return false;
            }
            // getBoolean 对 "1"/1 等非布尔值会返回 true（fastjson 宽松转换），
            // 故先确认原始类型确实是 Boolean，再取值。
            Object raw = obj.get("atAll");
            if (!(raw instanceof Boolean)) {
                return false;
            }
            return (Boolean) raw;
        } catch (Exception e) {
            // 解析失败一律按「非 @所有人」处理，且不打断主流程
            return false;
        }
    }
}