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

    public static final Long FILE_SIZE_MB = 1024 * 1024L;

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
