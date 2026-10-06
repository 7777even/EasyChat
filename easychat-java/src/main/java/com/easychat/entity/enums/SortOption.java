package com.easychat.entity.enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.easychat.exception.BusinessException;
import com.easychat.utils.StringTools;

/**
 * 排序白名单 —— 调用方**只能**取本枚举内的排序项，SQL 片段是**代码里的字面量**。
 *
 * <p>为什么需要它：修复前 17 处 Mapper 用 {@code order by ${query.orderBy}} 把
 * {@code Query.orderBy} 直接拼进 SQL（违反 AGENTS §6.2-3），而 {@code AdminGroupController#loadGroup}
 * 与 {@code AdminUserInfoBeautyController#loadBeautyAccountList} 直接绑定 {@code *Query}、
 * Controller 与 Service 均未设排序 → 调用方可把任意字符串送进 SQL。
 *
 * <p>本枚举是 ADR-001 的落点：**排序能力保留，但只能取白名单内的项**。
 * 每个枚举项自带一个 XML 内可逐字复现的 SQL 片段字面量，
 * Mapper 用 {@code <choose>} + {@code <when test="sortOption == @…SortOption@XXX">} 产出排序，
 * 因此**整条链路上没有任何运行时字符串进入 SQL**（不是「枚举 → 字符串 → ${}」的折中）。
 *
 * <p>命名规则：{@code <表>_<列...>_<方向>}。多列排序用 {@code Then} 连接。
 */
public enum SortOption {

    // ── group_info（/admin/loadGroup 可由调用方指定）──
    GROUP_INFO_CREATE_TIME_DESC("group_info", "createTime", "desc", "create_time desc"),
    GROUP_INFO_CREATE_TIME_ASC("group_info", "createTime", "asc", "create_time asc"),

    // ── user_info（/admin/loadUser）──
    USER_INFO_CREATE_TIME_DESC("user_info", "createTime", "desc", "create_time desc"),
    USER_INFO_CREATE_TIME_ASC("user_info", "createTime", "asc", "create_time asc"),

    // ── user_info_beauty（/admin/loadBeautyAccountList；该表无时间列，默认按 id 倒序）──
    USER_INFO_BEAUTY_ID_DESC("user_info_beauty", "id", "desc", "id desc"),
    USER_INFO_BEAUTY_ID_ASC("user_info_beauty", "id", "asc", "id asc"),

    // ── app_update ──
    APP_UPDATE_ID_DESC("app_update", "id", "desc", "id desc"),

    // ── user_contact（三种：会话列表 / 群成员列表 / 群内按角色）──
    USER_CONTACT_LAST_UPDATE_TIME_DESC("user_contact", "lastUpdateTime", "desc", "last_update_time desc"),
    USER_CONTACT_CREATE_TIME_ASC("user_contact", "createTime", "asc", "create_time asc"),
    USER_CONTACT_ROLE_THEN_CREATE_TIME_ASC("user_contact", "roleThenCreateTime", "asc", "role asc, create_time asc"),

    // ── user_contact_apply ──
    USER_CONTACT_APPLY_LAST_APPLY_TIME_DESC("user_contact_apply", "lastApplyTime", "desc", "last_apply_time desc"),

    // ── chat_session_user ──
    CHAT_SESSION_USER_LAST_RECEIVE_TIME_DESC("chat_session_user", "lastReceiveTime", "desc", "last_receive_time desc"),

    // ── group_file ──
    GROUP_FILE_CREATE_TIME_DESC("group_file", "createTime", "desc", "create_time desc"),

    // ── moment / moment_like / moment_comment / moment_notify ──
    MOMENT_CREATE_TIME_DESC("moment", "createTime", "desc", "create_time desc"),
    MOMENT_LIKE_CREATE_TIME_ASC("moment_like", "createTime", "asc", "create_time asc"),
    MOMENT_COMMENT_CREATE_TIME_ASC("moment_comment", "createTime", "asc", "create_time asc"),
    MOMENT_NOTIFY_CREATE_TIME_DESC("moment_notify", "createTime", "desc", "create_time desc"),

    // ── chat_message（四种：按发送时间 / 按 id 定位游标 / 按 seq 补拉空洞）──
    CHAT_MESSAGE_SEND_TIME_DESC("chat_message", "sendTime", "desc", "send_time desc"),
    CHAT_MESSAGE_MESSAGE_ID_DESC("chat_message", "messageId", "desc", "message_id desc"),
    CHAT_MESSAGE_MESSAGE_ID_ASC("chat_message", "messageId", "asc", "message_id asc"),
    CHAT_MESSAGE_SEQ_ASC("chat_message", "seq", "asc", "seq asc"),

    // ── email_verify_code：生产代码（UserInfoServiceImpl#sendEmailCode / #resetPasswordByEmail）
    //    原本就设的是 create_time desc，故默认项取它而非自拟的 id desc ──
    EMAIL_VERIFY_CODE_CREATE_TIME_DESC("email_verify_code", "createTime", "desc", "create_time desc"),
    EMAIL_VERIFY_CODE_ID_DESC("email_verify_code", "id", "desc", "id desc"),

    // ── 以下两张表修复前**无人设置** orderBy（即分页无 ORDER BY），此处补默认项 ──
    MOMENT_MEDIA_ID_ASC("moment_media", "id", "asc", "id asc"),
    CHAT_SESSION_LAST_RECEIVE_TIME_DESC("chat_session", "lastReceiveTime", "desc", "last_receive_time desc");

    private final String table;
    private final String httpField;
    private final String direction;
    private final String sql;

    /** 每张表的默认排序项（修复前 orderBy 为 null 时无排序，这里给出确定性默认） */
    private static final Map<String, SortOption> DEFAULT_BY_TABLE;

    static {
        Map<String, SortOption> m = new HashMap<>();
        m.put("group_info", GROUP_INFO_CREATE_TIME_DESC);
        m.put("user_info", USER_INFO_CREATE_TIME_DESC);
        m.put("user_info_beauty", USER_INFO_BEAUTY_ID_DESC);
        m.put("app_update", APP_UPDATE_ID_DESC);
        m.put("user_contact", USER_CONTACT_LAST_UPDATE_TIME_DESC);
        m.put("user_contact_apply", USER_CONTACT_APPLY_LAST_APPLY_TIME_DESC);
        m.put("chat_session_user", CHAT_SESSION_USER_LAST_RECEIVE_TIME_DESC);
        m.put("group_file", GROUP_FILE_CREATE_TIME_DESC);
        m.put("moment", MOMENT_CREATE_TIME_DESC);
        m.put("moment_like", MOMENT_LIKE_CREATE_TIME_ASC);
        m.put("moment_comment", MOMENT_COMMENT_CREATE_TIME_ASC);
        m.put("moment_notify", MOMENT_NOTIFY_CREATE_TIME_DESC);
        m.put("chat_message", CHAT_MESSAGE_SEND_TIME_DESC);
        m.put("moment_media", MOMENT_MEDIA_ID_ASC);
        m.put("chat_session", CHAT_SESSION_LAST_RECEIVE_TIME_DESC);
        m.put("email_verify_code", EMAIL_VERIFY_CODE_CREATE_TIME_DESC);
        DEFAULT_BY_TABLE = Collections.unmodifiableMap(m);
    }

    SortOption(String table, String httpField, String direction, String sql) {
        this.table = table;
        this.httpField = httpField;
        this.direction = direction;
        this.sql = sql;
    }

    public String getTable() {
        return table;
    }

    public String getHttpField() {
        return httpField;
    }

    public String getDirection() {
        return direction;
    }

    public String getSql() {
        return sql;
    }

    /**
     * 解析调用方传入的排序参数。
     *
     * <p>ADR-004（人工决策）：**非法值显式报错，不静默回退** —— 排序传错必须立即可见，
     * 否则「枚举 ↔ XML 分支」契约被破坏时会表现为「排序静默失效」，最难排查。
     *
     * @param table     目标表名（由服务端按端点写死，不来自请求）
     * @param field     调用方传入的列名（camelCase），空表示用默认项
     * @param direction asc / desc，空表示 desc
     * @return 白名单内的枚举项；field 为空时返回该表默认项
     * @throws BusinessException 参数非法（CODE_1001）
     */
    public static SortOption fromHttp(String table, String field, String direction) {
        if (StringTools.isEmpty(table) || !DEFAULT_BY_TABLE.containsKey(table)) {
            // 消息**不回显** table / field / direction 原值：这三个都可能是调用方可控内容，
            // 回显即等于把载荷写进日志（与 JsonUtils 只记长度的做法同一原则）
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "排序参数非法：目标表不在白名单内");
        }
        String dir = StringTools.isEmpty(direction) ? "desc" : direction.trim().toLowerCase();
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "排序参数非法：排序方向只能是 asc 或 desc");
        }
        String f = StringTools.isEmpty(field) ? null : field.trim();
        if (f == null || f.isEmpty()) {
            return DEFAULT_BY_TABLE.get(table);
        }
        for (SortOption s : values()) {
            if (s.table.equals(table) && s.httpField.equals(f) && s.direction.equals(dir)) {
                return s;
            }
        }
        throw new BusinessException(ResponseCodeEnum.CODE_1001, "排序参数非法：列名与方向的组合不在白名单内");
    }

    /**
     * 取某张表的默认排序项。
     *
     * @throws BusinessException 表名不在白名单内（CODE_1001）
     */
    public static SortOption defaultOf(String table) {
        SortOption s = StringTools.isEmpty(table) ? null : DEFAULT_BY_TABLE.get(table);
        if (s == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "排序参数非法：目标表不在白名单内");
        }
        return s;
    }
}
