package com.easychat.entity.constants;

import com.easychat.entity.enums.UserContactTypeEnum;

public class Constants {
    public static final String ZERO_STR = "0";

    public static final Integer ZERO = 0;

    public static final Integer ONE = 1;

    public static final Integer LENGTH_10 = 10;
    public static final Integer LENGTH_11 = 11;
    public static final Integer LENGTH_20 = 20;

    public static final Integer LENGTH_30 = 30;

    /** 邮箱验证码长度 */
    public static final Integer LENGTH_6 = 6;

    public static final String SESSION_KEY = "session_key";

    public static final String FILE_FOLDER_FILE = "/file/";

    public static final String FILE_FOLDER_GROUP = "group/";

    public static final String FILE_FOLDER_TEMP = "/temp/";

    public static final String FILE_FOLDER_TEMP_2 = "temp";

    public static final String FILE_FOLDER_IMAGE = "images/";

    public static final String FILE_FOLDER_AVATAR_NAME = "avatar/";

    public static final String CHECK_CODE_KEY = "check_code_key";

    public static final String IMAGE_SUFFIX = ".png";

    public static final String COVER_IMAGE_SUFFIX = "_cover.png";

    public static final String[] IMAGE_SUFFIX_LIST = new String[]{".jpeg", ".jpg", ".png", ".gif", ".bmp", ".webp"};

    public static final String[] VIDEO_SUFFIX_LIST = new String[]{".mp4", ".avi", ".rmvb", ".mkv", ".mov"};

    /**
     * 音频后缀白名单（2026-10-03 语音消息接通）。
     *
     * <p>覆盖前端 {@code MediaRecorder} 默认输出（{@code .webm} / {@code .ogg}）
     * 与常见音频容器。全部为**媒体容器**，不含脚本/可执行内容，
     * 与 {@code DANGEROUS_SUFFIX_LIST} 的可执行文件硬拦截并存不冲突。
     *
     * <p>刻意<b>不含 {@code .amr}</b>（微信同款）：兼容它需引入转码依赖与失败路径，
     * 而播放体验对用户不可感知（见 openspec design ADR-003）。
     */
    public static final String[] AUDIO_SUFFIX_LIST = new String[]{".webm", ".m4a", ".mp3", ".wav", ".ogg"};

    public static final Long FILE_SIZE_MB = 1024 * 1024L;

    /**
     * 语音消息时长上限（秒，2026-10-03）。
     *
     * <p>对标微信的 60 秒。前端 {@code MessageSend.vue#MAX_RECORDING_TIME} 同为 60000ms，
     * 此处是<b>服务端</b>的同值守卫 —— {@code duration} 由客户端传参，可伪造。
     *
     * <p>若将来放宽前端上限，<b>必须同步改这里</b>（否则合法语音会被拒）。
     */
    public static final Integer VOICE_MAX_DURATION_SECONDS = 60;

    /**
     * redis key 相关
     */

    /**
     * 过期时间 1分钟
     */
    public static final Integer REDIS_KEY_EXPIRES_ONE_MIN = 60;


    public static final Integer REDIS_KEY_EXPIRES_HEART_BEAT = 6;

    /**
     * 过期时间 1天
     */
    public static final Integer REDIS_KEY_EXPIRES_DAY = REDIS_KEY_EXPIRES_ONE_MIN * 60 * 24;


    public static final Integer REDIS_KEY_TOKEN_EXPIRES = REDIS_KEY_EXPIRES_DAY * 2;


    public static final String REDIS_KEY_CHECK_CODE = "easychat:checkcode:";
    public static final String REDIS_KEY_WS_TOKEN = "easychat:ws:token:";

    // ── 邮箱验证码失败次数计数（2026-10-13，openspec 2026-10-13-email-code-failure-limit）──
    //
    // 背景（§14 遗留 #1 残余风险）：端点限流 60 次/分 × 验证码寿命 10 分钟
    //   ⇒ 单邮箱 600 次尝试；6 位码空间 10⁶ ⇒ 命中 0.06%。命中即等于账号被接管。
    //
    // ⚠ 为什么放 Redis 而**不是**给 email_verify_code 加 fail_count 列（ADR-001）：
    //   DB 方案粒度是「每个验证码行 N 次」，而攻击者**每 60 秒就能重发一个新码**
    //   重置自己的额度（重发是受害者无法阻止的动作）—— 限流等于没加。
    //   Redis 方案按 email 在整个窗口内累计，跨验证码生效，重发不重置。
    //
    // ⚠ 已知取舍（写进 spec 的「已知边界」，不假装没有）：
    //   知道某邮箱的人可用 N 次错误尝试把该邮箱锁 10 分钟。
    //   这是**所有**基于次数的验证码限流的固有代价（GitHub / Google 同）；
    //   缓解是窗口与验证码寿命一致，被锁时长不会超过码本身的可用时长，且自动恢复。
    public static final String REDIS_KEY_EMAIL_CODE_FAIL = "easychat:emailcode:fail:";

    /** 同一邮箱在窗口内允许的验证码错误次数上限。超过即拒绝，直到窗口过期。 */
    public static final Integer EMAIL_CODE_FAIL_MAX = 5;

    /**
     * 失败计数窗口（秒）。**刻意与验证码寿命一致**（10 分钟）：
     * 若窗口短于码寿命，攻击者在码还有效时计数就过期了，等于没限；
     * 若长于码寿命，受害者被锁的时间会超过「这批码本来还有多久可用」，
     * 变成纯粹的损失。10 分钟 = 验证码 10 分钟 + 重发冷却 60 秒的量级。
     */
    public static final Integer EMAIL_CODE_FAIL_WINDOW_SECONDS = 600;

    public static final String REDIS_KEY_WS_TOKEN_USERID = "easychat:ws:token:userid";

    // 多端登录：同一账号的全部有效 token 列表（用于整体登出 / 挤下线）
    public static final String REDIS_KEY_WS_TOKEN_USERID_LIST = "easychat:ws:token:userid:list:";

    public static final String REDIS_KEY_WS_USER_HEART_BEAT = "easychat:ws:user:heartbeat";

    public static final String REDIS_KEY_WS_ON_LINE_USER = "easychat:ws:online:";

    //用户联系人列表
    public static final String REDIS_KEY_USER_CONTACT = "easychat:ws:user:contact:";

    //用户参与的会话列表
    public static final String REDIS_KEY_USER_SESSION = "easychat:ws:user:session:";

    public static final Long MILLISECOND_3DAYS_AGO = 3 * 24 * 60 * 60 * 1000L;

    public static final String ROBOT_UID = UserContactTypeEnum.USER.getPrefix() + "robot";

    //系统设置
    public static final String REDIS_KEY_SYS_SETTING = "easychat:syssetting:";

    // 消息会话内序列号计数器（INCR 产生单调 seq）
    public static final String REDIS_KEY_MSG_SEQ = "easychat:msg:seq:";

    // 离线消息缓冲队列（LPUSH，消费后删除）
    public static final String REDIS_KEY_WS_OFFLINE_MSG = "easychat:ws:offline:";

    // 用户在线状态（1=在线 2=忙碌 3=离线）
    public static final String REDIS_KEY_WS_USER_STATUS = "easychat:ws:user:status:";

    // 群二维码 token
    public static final String REDIS_KEY_GROUP_QRCODE = "easychat:group:qrcode:";

    // 群二维码 token → groupId 反查（join 时用，与正向同 TTL）
    public static final String REDIS_KEY_GROUP_QRCODE_TOKEN = "easychat:group:qrcode:token:";

    // 群邀请链接 token
    public static final String REDIS_KEY_GROUP_INVITE = "easychat:group:invite:";

    // 群邀请链接 token → groupId 反查（join 时用，与正向同 TTL）
    public static final String REDIS_KEY_GROUP_INVITE_TOKEN = "easychat:group:invite:token:";

    // 消息可靠性 ACK 帧类型标识（在 WS 推送 extendData.messageType 中使用 -1）
    public static final Integer WS_ACK_MESSAGE_TYPE = -1;

    // 客户端→服务端：同步（请求服务端补推 seq > lastSeq 的消息）
    public static final Integer WS_SYNC_MESSAGE_TYPE = -2;

    // 客户端→服务端：心跳包标识
    public static final Integer WS_HEARTBEAT_MESSAGE_TYPE = -4;

    /**
     * 服务端→客户端：跨端会话同步。通知其他设备某会话的元数据已更新（最后消息、未读数等），需立即刷新。
     */
    public static final Integer WS_SYNC_SESSION_MESSAGE_TYPE = -6;

    /**
     * 服务端→客户端：会话用户级属性同步（置顶 / 免打扰 / 草稿）。
     * extendData = {action:'top'|'noDisturb'|'draft', sessionId, contactId, value}
     */
    public static final Integer WS_SYNC_SESSION_USER_MESSAGE_TYPE = -7;

    /**
     * 服务端→客户端：朋友圈未读通知数变化（用于实时点亮/消除朋友圈红点）
     * extendData = {unreadCount: N}
     */
    public static final Integer WS_MOMENT_UNREAD_MESSAGE_TYPE = -8;

    // ===== 语音/视频通话信令帧（WS 控制帧，负区间，避免与聊天消息类型 0~19 冲突） =====
    public static final Integer WS_CALL_INVITE = -10;
    public static final Integer WS_CALL_ACCEPT = -11;
    public static final Integer WS_CALL_REJECT = -12;
    public static final Integer WS_CALL_SIGNAL = -13;
    public static final Integer WS_CALL_HANGUP = -14;
    public static final Integer WS_CALL_CANCEL = -15;
    public static final Integer WS_CALL_BUSY = -16;
    public static final Integer WS_CALL_JOIN = -17;

    public static final String APP_UPDATE_FOLDER = "/app/";

    public static final String APP_NAME = "EasyChatSetup.";
    public static final String APP_EXE_SUFFIX = ".exe";

    //正则
    public static final String REGEX_PASSWORD = "^(?=.*\\d)(?=.*[a-zA-Z])[\\da-zA-Z~!@#$%^&*_]{8,18}$";

    //申请信息模板
    public static final String APPLY_INFO_TEMPLATE = "我是%s";

    //自己退群
    public static final String out_group_TEMPLATE_self = "%s退出了群聊";

    //被管理员踢群
    public static final String out_group_TEMPLATE = "%s被管理员移出了群聊";

}
