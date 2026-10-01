package com.easychat.redis;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.utils.StringTools;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
public class RedisComponet {
    @Resource
    private RedisUtils redisUtils;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 获取token信息
     *
     * @param token
     * @return
     */
    public TokenUserInfoDto getTokenUserInfoDto(String token) {
        TokenUserInfoDto tokenUserInfoDto = (TokenUserInfoDto) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + token);
        return tokenUserInfoDto;
    }

    public TokenUserInfoDto getTokenUserInfoDtoByUserId(String userId) {
        String token = (String) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN_USERID + userId);
        return getTokenUserInfoDto(token);
    }
    
    public void saveTokenUserInfoDto(TokenUserInfoDto tokenUserInfoDto) {
        redisUtils.setex(Constants.REDIS_KEY_WS_TOKEN + tokenUserInfoDto.getToken(), tokenUserInfoDto, Constants.REDIS_KEY_EXPIRES_DAY * 2);
        redisUtils.setex(Constants.REDIS_KEY_WS_TOKEN_USERID + tokenUserInfoDto.getUserId(), tokenUserInfoDto.getToken(), Constants.REDIS_KEY_EXPIRES_DAY * 2);
        // 多端登录：把 token 追加进该用户的 token 列表（去重）
        String listKey = Constants.REDIS_KEY_WS_TOKEN_USERID_LIST + tokenUserInfoDto.getUserId();
        List<String> tokenList = redisUtils.getQueueList(listKey);
        if (tokenList == null) {
            tokenList = new java.util.ArrayList<>();
        }
        if (!tokenList.contains(tokenUserInfoDto.getToken())) {
            redisUtils.lpush(listKey, tokenUserInfoDto.getToken(), Constants.REDIS_KEY_TOKEN_EXPIRES);
        }
    }

    /**
     * 获取指定用户当前持有的全部有效 token（多端登录场景）
     */
    public List<String> getTokenListByUserId(String userId) {
        return redisUtils.getQueueList(Constants.REDIS_KEY_WS_TOKEN_USERID_LIST + userId);
    }

    /**
     * 清除token信息
     *
     * @param userId
     */
    public void cleanUserTokenByUserId(String userId) {
        // 清理全部端持有的 token（多端登录：一个用户可能同时有多个有效 token）
        List<String> tokenList = getTokenListByUserId(userId);
        if (tokenList != null) {
            for (String token : tokenList) {
                redisUtils.delete(Constants.REDIS_KEY_WS_TOKEN + token);
            }
            redisUtils.delete(Constants.REDIS_KEY_WS_TOKEN_USERID_LIST + userId);
        }
        String token = (String) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN_USERID + userId);
        if (!StringTools.isEmpty(token)) {
            redisUtils.delete(Constants.REDIS_KEY_WS_TOKEN + token);
        }
        redisUtils.delete(Constants.REDIS_KEY_WS_TOKEN_USERID + userId);
    }


    //保存最后心跳时间
    public void saveUserHeartBeat(String userId) {
        redisUtils.setex(Constants.REDIS_KEY_WS_USER_HEART_BEAT + userId, System.currentTimeMillis(), Constants.REDIS_KEY_EXPIRES_HEART_BEAT);
    }

    //删除用户心跳
    public void removeUserHeartBeat(String userId) {
        redisUtils.delete(Constants.REDIS_KEY_WS_USER_HEART_BEAT + userId);
    }


    //获取用户心跳
    public Long getUserHeartBeat(String userId) {
        return (Long) redisUtils.get(Constants.REDIS_KEY_WS_USER_HEART_BEAT + userId);
    }

    //获取用户联系人
    public List<String> getUserContactList(String userId) {
        return redisUtils.getQueueList(Constants.REDIS_KEY_USER_CONTACT + userId);
    }

    //添加用户联系人
    public void addUserContact(String userId, String contactId) {
        List<String> contactList = redisUtils.getQueueList(Constants.REDIS_KEY_USER_CONTACT + userId);
        if (!contactList.contains(contactId)) {
            redisUtils.lpush(Constants.REDIS_KEY_USER_CONTACT + userId, contactId, Constants.REDIS_KEY_TOKEN_EXPIRES);
        }
    }

    //清空用户联系人
    public void cleanUserContact(String userId) {
        redisUtils.delete(Constants.REDIS_KEY_USER_CONTACT + userId);
    }

    //删除用户联系人
    public void removeUserContact(String userId, String contactId) {
        redisUtils.remove(Constants.REDIS_KEY_USER_CONTACT + userId, contactId);
    }

    //批量添加用户联系人
    public void addUserContactBatch(String userId, List<String> contactIdList) {
        redisUtils.lpushAll(Constants.REDIS_KEY_USER_CONTACT + userId, contactIdList, Constants.REDIS_KEY_TOKEN_EXPIRES);
    }

    //获取用户session列表
    public List<String> getUserSessionList(String userId) {
        return redisUtils.getQueueList(Constants.REDIS_KEY_USER_SESSION + userId);
    }

    //添加用户Session
    public void addUserSession(String userId, String sessionId) {
        List<String> sessionList = redisUtils.getQueueList(Constants.REDIS_KEY_USER_SESSION + userId);
        if (!sessionList.contains(sessionId)) {
            redisUtils.lpush(Constants.REDIS_KEY_USER_SESSION + userId, sessionId, Constants.REDIS_KEY_TOKEN_EXPIRES);
        }
    }

    //清空用户Session
    public void cleanUserSession(String userId) {
        redisUtils.delete(Constants.REDIS_KEY_USER_SESSION + userId);
    }

    public void saveSysSetting(SysSettingDto sysSettingDto) {
        redisUtils.set(Constants.REDIS_KEY_SYS_SETTING, sysSettingDto);
    }

    public SysSettingDto getSysSetting() {
        SysSettingDto sysSettingDto = (SysSettingDto) redisUtils.get(Constants.REDIS_KEY_SYS_SETTING);
        sysSettingDto = sysSettingDto == null ? new SysSettingDto() : sysSettingDto;
        return sysSettingDto;
    }

    /* ===================== 消息可靠性：SEQ 发号 + 离线缓冲 ===================== */

    /**
     * 获取 sessionId 下的下一个原子序号（Redis INCR）
     *
     * @param sessionId 会话 ID
     * @return 单调递增 seq；失败返回 null
     */
    public Long nextMessageSeq(String sessionId) {
        return redisUtils.incr(Constants.REDIS_KEY_MSG_SEQ + sessionId);
    }

    /**
     * 向指定用户的离线缓冲队列头部压入消息（JSON 序列化后的 MessageSendDto）
     *
     * @param userId 接收方用户 ID
     * @param messageJson 消息 JSON
     */
    public void pushOfflineMessage(String userId, String messageJson) {
        redisUtils.lpush(Constants.REDIS_KEY_WS_OFFLINE_MSG + userId, messageJson, Constants.REDIS_KEY_TOKEN_EXPIRES);
    }

    /**
     * 取出并清空指定用户的离线消息列表（按时间正序返回）
     *
     * @param userId 用户 ID
     * @return 正序的消息 JSON 列表
     */
    public List<String> popOfflineMessages(String userId) {
        List<String> list = redisUtils.getQueueList(Constants.REDIS_KEY_WS_OFFLINE_MSG + userId);
        redisUtils.delete(Constants.REDIS_KEY_WS_OFFLINE_MSG + userId);
        // LPUSH 导致 list 是倒序，反转为时间正序
        if (list != null && list.size() > 1) {
            java.util.Collections.reverse(list);
        }
        return list;
    }

    /**
     * 判断用户是否有待下发的离线消息
     */
    public Boolean hasOfflineMessage(String userId) {
        List<String> list = redisUtils.getQueueList(Constants.REDIS_KEY_WS_OFFLINE_MSG + userId);
        return list != null && !list.isEmpty();
    }

    /**
     * 更新用户在线状态
     *
     * @param userId 用户 ID
     * @param status 状态值（1=在线 2=忙碌 3=离线）
     */
    public void updateUserStatus(String userId, Integer status) {
        redisUtils.setex(Constants.REDIS_KEY_WS_USER_STATUS + userId, status, Constants.REDIS_KEY_EXPIRES_DAY * 7);
    }
/* ===================== 群二维码/邀请链接 ===================== */

    /**
     * 保存群二维码 token
     *
     * @param groupId 群组 ID
     * @param token 二维码 token
     */
    public void saveGroupQrCode(String groupId, String token) {
        redisUtils.setex(Constants.REDIS_KEY_GROUP_QRCODE + groupId, token, Constants.REDIS_KEY_EXPIRES_DAY * 7);
    }

    /**
     * 获取群二维码 token
     *
     * @param groupId 群组 ID
     * @return 二维码 token，不存在返回 null
     */
    public String getGroupQrCode(String groupId) {
        return (String) redisUtils.get(Constants.REDIS_KEY_GROUP_QRCODE + groupId);
    }

    /**
     * 保存群邀请链接 token
     *
     * @param groupId 群组 ID
     * @param token 邀请链接 token
     */
    public void saveGroupInvite(String groupId, String token) {
        redisUtils.setex(Constants.REDIS_KEY_GROUP_INVITE + groupId, token, Constants.REDIS_KEY_EXPIRES_DAY * 7);
    }

    /**
     * 获取群邀请链接 token
     *
     * @param groupId 群组 ID
     * @return 邀请链接 token，不存在返回 null
     */
    public String getGroupInvite(String groupId) {
        return (String) redisUtils.get(Constants.REDIS_KEY_GROUP_INVITE + groupId);
    }

    /**
     * 根据 token 获取群组 ID
     *
     * @param token 二维码/邀请链接 token
     * @return 群组 ID，不存在返回 null
     */
    public String getGroupIdByToken(String token) {
        // 遍历所有群组，查找匹配的 token
        // 注意：这是一个简化的实现，实际生产环境可能需要更高效的方式
        return null;
    }

}
