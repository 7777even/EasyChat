package com.easychat.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class JsonUtils {
    private static final Logger logger = LoggerFactory.getLogger(JsonUtils.class);

    /** 解析失败时日志中记录的原文最大长度。0 表示完全不记录原文。 */
    private static final int LOG_PAYLOAD_LIMIT = 0;

    public static SerializerFeature[] FEATURES = new SerializerFeature[]{SerializerFeature.WriteMapNullValue};

    public static String convertObj2Json(Object obj) {
        return JSON.toJSONString(obj, FEATURES);
    }

    public static <T> T convertJson2Obj(String json, Class<T> classz) {
        try {
            return JSONObject.parseObject(json, classz);
        } catch (Exception e) {
            logger.error("convertJson2Obj 解析失败，目标类型={}，原文长度={}{}", classz.getSimpleName(), len(json), payload(json), e);
            throw new BusinessException(ResponseCodeEnum.CODE_2102);
        }
    }

    public static <T> List<T> convertJsonArray2List(String json, Class<T> classz) {
        try {
            return JSONArray.parseArray(json, classz);
        } catch (Exception e) {
            logger.error("convertJsonArray2List 解析失败，目标类型={}，原文长度={}{}", classz.getSimpleName(), len(json), payload(json), e);
            throw new BusinessException(ResponseCodeEnum.CODE_2102);
        }
    }

    private static int len(String json) {
        return json == null ? 0 : json.length();
    }

    /**
     * 构造可安全写入日志的原文片段。
     *
     * <p>唯一调用点是 {@code ChannelContextUtils} 解析 WebSocket 消息，
     * 原文即**用户可控的消息正文**——把全文写进日志等于让任意用户向日志注入内容
     * （日志伪造 / PII 扩散）。与「验证码不得写日志」同源，理由见
     * {@code openspec/specs/password-bcrypt/spec.md} 的「邮箱验证码真实投递」。
     *
     * <p>默认 {@link #LOG_PAYLOAD_LIMIT} = 0，即**完全不记录原文**；
     * 排障需要原文时由排查者临时调高该值，而非默认全量落盘。
     */
    private static String payload(String json) {
        if (LOG_PAYLOAD_LIMIT <= 0 || json == null) {
            return "";
        }
        return "，原文片段=" + (json.length() > LOG_PAYLOAD_LIMIT ? json.substring(0, LOG_PAYLOAD_LIMIT) + "…" : json);
    }
}
