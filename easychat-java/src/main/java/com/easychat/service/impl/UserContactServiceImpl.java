package com.easychat.service.impl;

import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.UserContactSearchResultDto;
import com.easychat.entity.enums.*;
import com.easychat.entity.po.*;
import com.easychat.entity.query.*;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.*;
import com.easychat.redis.RedisComponet;
import com.easychat.service.UserContactService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * 联系人 业务接口实现
 */
@Service("userContactService")
public class UserContactServiceImpl implements UserContactService {

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Resource
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private ChannelContextUtils channelContextUtils;


    /**
     * 根据条件查询列表
     */
    @Override
    public List<UserContact> findListByParam(UserContactQuery param) {
        return this.userContactMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(UserContactQuery param) {
        return this.userContactMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<UserContact> findListByPage(UserContactQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<UserContact> list = this.findListByParam(param);
        PaginationResultVO<UserContact> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(UserContact bean) {
        return this.userContactMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<UserContact> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userContactMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<UserContact> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userContactMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(UserContact bean, UserContactQuery param) {
        StringTools.checkParam(param);
        return this.userContactMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(UserContactQuery param) {
        StringTools.checkParam(param);
        return this.userContactMapper.deleteByParam(param);
    }

    /**
     * 根据UserIdAndContactId获取对象
     */
    @Override
    public UserContact getUserContactByUserIdAndContactId(String userId, String contactId) {
        return this.userContactMapper.selectByUserIdAndContactId(userId, contactId);
    }

    /**
     * 根据UserIdAndContactId修改
     */
    @Override
    public Integer updateUserContactByUserIdAndContactId(UserContact bean, String userId, String contactId) {
        return this.userContactMapper.updateByUserIdAndContactId(bean, userId, contactId);
    }

    /**
     * 根据UserIdAndContactId删除
     */
    @Override
    public Integer deleteUserContactByUserIdAndContactId(String userId, String contactId) {
        return this.userContactMapper.deleteByUserIdAndContactId(userId, contactId);
    }

    @Override
    public UserContactSearchResultDto searchContact(String userId, String contactId) {
        UserContactTypeEnum typeEnum = UserContactTypeEnum.getByPrefix(contactId);
        if (typeEnum == null) {
            return null;
        }
        UserContactSearchResultDto resultDto = new UserContactSearchResultDto();
        switch (typeEnum) {
            case USER:
                UserInfo userInfo = userInfoMapper.selectByUserId(contactId);
                if (userInfo == null) {
                    return null;
                }
                resultDto = CopyTools.copy(userInfo, UserContactSearchResultDto.class);
                break;
            case GROUP:
                GroupInfo groupInfo = groupInfoMapper.selectByGroupId(contactId);
                if (null == groupInfo) {
                    return null;
                }
                resultDto.setNickName(groupInfo.getGroupName());
                break;
        }
        resultDto.setContactType(typeEnum.toString());
        resultDto.setContactId(contactId);

        if (userId.equals(contactId)) {
            resultDto.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            return resultDto;
        }
        //查询是否已经是好友
        UserContact userContact = this.userContactMapper.selectByUserIdAndContactId(userId, contactId);
        resultDto.setStatus(userContact == null ? null : userContact.getStatus());
        return resultDto;
    }

    @Override
    public void addContact(String applyUserId, String receiveUserId, String contactId, Integer contactType, String applyInfo) {
        //群人上限判断
        if (UserContactTypeEnum.GROUP.getType().equals(contactType)) {
            UserContactQuery contactQuery = new UserContactQuery();
            contactQuery.setContactId(contactId);
            contactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            Integer count = userContactMapper.selectCount(contactQuery);
            SysSettingDto sysSettingDto = redisComponet.getSysSetting();
            if (count >= sysSettingDto.getMaxGroupMemberCount()) {
                throw new BusinessException("成员已满，无法加入");
            }
        }
        Date curDate = new Date();
        //同意 双方添加为好友
        List<UserContact> contactList = new ArrayList<>();
        //申请人添加对方
        UserContact userContact = new UserContact();
        userContact.setUserId(applyUserId);
        userContact.setContactId(contactId);
        userContact.setContactType(contactType);
        userContact.setCreateTime(curDate);
        userContact.setLastUpdateTime(curDate);
        userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        // 群组新成员默认角色：成员（群主在 saveGroup 中设置）
        if (UserContactTypeEnum.GROUP.getType().equals(contactType)) {
            userContact.setRole(GroupMemberRoleEnum.MEMBER.getRole());
        }
        contactList.add(userContact);
        //如果是申请好友 接收人添加申请人  群组不用添加对方为好友
        if (UserContactTypeEnum.USER.getType().equals(contactType)) {
            userContact = new UserContact();
            userContact.setUserId(receiveUserId);
            userContact.setContactId(applyUserId);
            userContact.setContactType(contactType);
            userContact.setCreateTime(curDate);
            userContact.setLastUpdateTime(curDate);
            userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            contactList.add(userContact);
        }
        //批量加入
        userContactMapper.insertOrUpdateBatch(contactList);

        //如果是好友申请,接收人也添加申请人为联系人
        if (UserContactTypeEnum.USER.getType().equals(contactType)) {
            redisComponet.addUserContact(receiveUserId, applyUserId);
        }
        //审核通过，将申请人的联系人添加上 我 或 群组
        redisComponet.addUserContact(applyUserId, contactId);


        //创建会话信息
        String sessionId = null;
        if (UserContactTypeEnum.USER.getType().equals(contactType)) {
            sessionId = StringTools.getChatSessionId4User(new String[]{applyUserId, contactId});
        } else {
            sessionId = StringTools.getChatSessionId4Group(contactId);
        }

        //会话参与人
        List<ChatSessionUser> chatSessionUserList = new ArrayList<>();
        if (UserContactTypeEnum.USER.getType().equals(contactType)) {
            //创建会话
            ChatSession chatSession = new ChatSession();
            chatSession.setSessionId(sessionId);
            chatSession.setLastReceiveTime(curDate.getTime());
            chatSession.setLastMessage(applyInfo);
            this.chatSessionMapper.insertOrUpdate(chatSession);

            //申请人session
            ChatSessionUser applySessionUser = new ChatSessionUser();
            applySessionUser.setUserId(applyUserId);
            applySessionUser.setContactId(contactId);
            applySessionUser.setSessionId(sessionId);
            applySessionUser.setLastReceiveTime(curDate.getTime());
            applySessionUser.setLastMessage(applyInfo);
            //查询接收人信息
            UserInfo contactUser = this.userInfoMapper.selectByUserId(contactId);
            applySessionUser.setContactName(contactUser.getNickName());
            chatSessionUserList.add(applySessionUser);

            //接受人session
            ChatSessionUser contactSessionUser = new ChatSessionUser();
            contactSessionUser.setUserId(contactId);
            contactSessionUser.setContactId(applyUserId);
            contactSessionUser.setSessionId(sessionId);
            contactSessionUser.setLastReceiveTime(curDate.getTime());
            contactSessionUser.setLastMessage(applyInfo);
            //查询申请人信息
            UserInfo applyUserInfo = this.userInfoMapper.selectByUserId(applyUserId);
            contactSessionUser.setContactName(applyUserInfo.getNickName());
            chatSessionUserList.add(contactSessionUser);
            this.chatSessionUserMapper.insertOrUpdateBatch(chatSessionUserList);

            //记录消息消息表
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setSessionId(sessionId);
            chatMessage.setMessageType(MessageTypeEnum.ADD_FRIEND.getType());
            chatMessage.setMessageContent(applyInfo);
            chatMessage.setSendUserId(applyUserId);
            chatMessage.setSendUserNickName(applyUserInfo.getNickName());
            chatMessage.setSendTime(curDate.getTime());
            chatMessage.setContactId(contactId);
            chatMessage.setContactType(UserContactTypeEnum.USER.getType());
            chatMessage.setStatus(MessageStatusEnum.SENDED.getStatus());
            chatMessageMapper.insert(chatMessage);

            MessageSendDto messageSendDto = CopyTools.copy(chatMessage, MessageSendDto.class);
            /**
             * 发送给接受好友申请的人
             */
            messageHandler.sendMessage(messageSendDto);

            /**
             * 发送给申请人 发送人就是接收人，联系人就是申请人
             */
            messageSendDto.setMessageType(MessageTypeEnum.ADD_FRIEND_SELF.getType());
            messageSendDto.setContactId(applyUserId);
            messageSendDto.setExtendData(contactUser);
            messageHandler.sendMessage(messageSendDto);

        } else {
            //加入群组
            ChatSessionUser chatSessionUser = new ChatSessionUser();
            chatSessionUser.setUserId(applyUserId);
            chatSessionUser.setContactId(contactId);
            GroupInfo groupInfo = this.groupInfoMapper.selectByGroupId(contactId);
            chatSessionUser.setContactName(groupInfo.getGroupName());
            chatSessionUser.setSessionId(sessionId);
            this.chatSessionUserMapper.insertOrUpdate(chatSessionUser);

            //将群组加入到用户的联系人列表
            redisComponet.addUserContact(applyUserId, groupInfo.getGroupId());

            channelContextUtils.addUser2Group(applyUserId, groupInfo.getGroupId());


            UserInfo applyUserInfo = this.userInfoMapper.selectByUserId(applyUserId);

            String sendMessage = String.format(MessageTypeEnum.ADD_GROUP.getInitMessage(), applyUserInfo.getNickName());

            //增加session信息
            ChatSession chatSession = new ChatSession();
            chatSession.setSessionId(sessionId);
            chatSession.setLastReceiveTime(curDate.getTime());
            chatSession.setLastMessage(sendMessage);
            this.chatSessionMapper.insertOrUpdate(chatSession);

            //增加聊天消息
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setSessionId(sessionId);
            chatMessage.setMessageType(MessageTypeEnum.ADD_GROUP.getType());
            chatMessage.setMessageContent(sendMessage);
            chatMessage.setSendUserId(null);
            chatMessage.setSendUserNickName(null);
            chatMessage.setSendTime(curDate.getTime());
            chatMessage.setContactId(contactId);
            chatMessage.setContactType(UserContactTypeEnum.GROUP.getType());
            chatMessage.setStatus(MessageStatusEnum.SENDED.getStatus());
            chatMessageMapper.insert(chatMessage);

            //发送群消息
            MessageSendDto messageSend = CopyTools.copy(chatMessage, MessageSendDto.class);
            messageSend.setContactId(groupInfo.getGroupId());
            //获取群人数量
            UserContactQuery userContactQuery = new UserContactQuery();
            userContactQuery.setContactId(contactId);
            userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            Integer memberCount = this.userContactMapper.selectCount(userContactQuery);
            messageSend.setMemberCount(memberCount);
            messageSend.setContactName(groupInfo.getGroupName());
            messageHandler.sendMessage(messageSend);
        }
    }

    @Override
    public void removeUserContact(String userId, String contactId, UserContactStatusEnum statusEnum) {
        //移除好友
        UserContact userContact = new UserContact();
        userContact.setStatus(statusEnum.getStatus());
        if (UserContactStatusEnum.BLACKLIST == statusEnum) {
            // 拉黑必须 upsert：搜索到的陌生人此前从未成为好友，user_contact 里根本没有行，
            // update 对不存在的行是 no-op → 拉黑会「静默失效」（界面上看着加了黑，实际没加）。
            // 这是在 2026-10-02 黑名单可查可解改造中由活体冒烟发现的既有缺陷。
            fillContactRow(userContact, userId, contactId);
            this.userContactMapper.insertOrUpdate(userContact);
        } else {
            this.userContactMapper.updateByUserIdAndContactId(userContact, userId, contactId);
        }

        //好友中也移除自己
        UserContact friendContact = new UserContact();
        if (UserContactStatusEnum.DEL == statusEnum) {
            friendContact.setStatus(UserContactStatusEnum.DEL_BE.getStatus());
            this.userContactMapper.updateByUserIdAndContactId(friendContact, contactId, userId);
        } else if (UserContactStatusEnum.BLACKLIST == statusEnum) {
            friendContact.setStatus(UserContactStatusEnum.BLACKLIST_BE.getStatus());
            fillContactRow(friendContact, contactId, userId);
            this.userContactMapper.insertOrUpdate(friendContact);
        }
        //将我从对方的好友缓存中删除
        redisComponet.removeUserContact(contactId, userId);
        //将对方从我的列表中删除
        redisComponet.removeUserContact(userId, contactId);
    }

    @Override
    public void removeGroupContact(String userId, String groupId, String contactId, UserContactStatusEnum statusEnum) {
        GroupInfo groupInfo = groupInfoMapper.selectByGroupId(groupId);
        if (null == groupInfo || !groupInfo.getGroupOwnerId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        UserContact userContact = new UserContact();
        userContact.setStatus(statusEnum.getStatus());
        userContactMapper.updateByUserIdAndContactId(userContact, contactId, groupId);
        //将群组从群员列表中删除
        redisComponet.removeUserContact(contactId, groupId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addContact4Robot(String userId) {
        Date curDate = new Date();

        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        String contactId = sysSettingDto.getRobotUid();
        String contactName = sysSettingDto.getRobotNickName();
        String senMessage = sysSettingDto.getRobotWelcome();
        senMessage = StringTools.cleanHtmlTag(senMessage);
        //增加机器人好友
        UserContact userContact = new UserContact();
        userContact.setUserId(userId);
        userContact.setContactId(contactId);
        userContact.setContactType(UserContactTypeEnum.USER.getType());
        userContact.setCreateTime(curDate);
        userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        userContact.setLastUpdateTime(curDate);
        userContactMapper.insert(userContact);

        //增加会话信息
        String sessionId = StringTools.getChatSessionId4User(new String[]{userId, contactId});
        ChatSession chatSession = new ChatSession();
        chatSession.setLastMessage(senMessage);
        chatSession.setSessionId(sessionId);
        chatSession.setLastReceiveTime(curDate.getTime());
        this.chatSessionMapper.insert(chatSession);

        ChatSessionUser applySessionUser = new ChatSessionUser();
        applySessionUser.setUserId(userId);
        applySessionUser.setContactId(contactId);
        applySessionUser.setContactName(contactName);
        applySessionUser.setSessionId(sessionId);
        this.chatSessionUserMapper.insertOrUpdate(applySessionUser);

        //增加聊天消息
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setSessionId(sessionId);
        chatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
        chatMessage.setMessageContent(senMessage);
        chatMessage.setSendUserId(contactId);
        chatMessage.setSendUserNickName(contactName);
        chatMessage.setSendTime(curDate.getTime());
        chatMessage.setContactId(userId);
        chatMessage.setContactType(UserContactTypeEnum.USER.getType());
        chatMessage.setStatus(MessageStatusEnum.SENDED.getStatus());
        chatMessageMapper.insert(chatMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setContactRemark(String userId, String contactId, String remark) {
        UserContact userContact = requireFriendContact(userId, contactId);
        UserContact updateInfo = new UserContact();
        updateInfo.setRemark(remark);
        userContactMapper.updateByUserIdAndContactId(updateInfo, userId, contactId);
        // 备注变更后同步会话显示名（有备注用备注，否则回退昵称）
        String displayName = StringTools.isEmpty(remark)
                ? (userContact.getContactName() == null ? "" : userContact.getContactName())
                : remark;
        // 会话列表显示名同步（仅更新该用户自己的会话记录）
        ChatSessionUser updateSessionUser = new ChatSessionUser();
        updateSessionUser.setContactName(displayName);
        ChatSessionUserQuery sessionUserQuery = new ChatSessionUserQuery();
        sessionUserQuery.setUserId(userId);
        sessionUserQuery.setContactId(contactId);
        chatSessionUserMapper.updateByParam(updateSessionUser, sessionUserQuery);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setContactGroup(String userId, String contactId, String groupName) {
        requireFriendContact(userId, contactId);
        UserContact updateInfo = new UserContact();
        updateInfo.setGroupName(groupName);
        userContactMapper.updateByUserIdAndContactId(updateInfo, userId, contactId);
    }

    /**
     * 校验好友关系存在（仅好友可设置备注 / 分组）
     */
    private UserContact requireFriendContact(String userId, String contactId) {
        UserContact userContact = userContactMapper.selectByUserIdAndContactId(userId, contactId);
        if (userContact == null || !UserContactStatusEnum.FRIEND.getStatus().equals(userContact.getStatus())) {
            throw new BusinessException(ResponseCodeEnum.CODE_2401);
        }
        return userContact;
    }

    @Override
    public List<UserContact> searchContactByKeyword(String userId, String keyword) {
        if (StringTools.isEmpty(keyword)) {
            return new ArrayList<>();
        }
        UserContactQuery query = new UserContactQuery();
        query.setUserId(userId);
        query.setContactType(UserContactTypeEnum.USER.getType());
        query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        query.setQueryContactUserInfo(true);
        List<UserContact> contactList = userContactMapper.selectList(query);
        List<UserContact> result = new ArrayList<>();
        if (contactList == null) {
            return result;
        }
        for (UserContact item : contactList) {
            String remark = item.getRemark();
            String nickName = item.getContactName();
            String groupName = item.getGroupName();
            boolean matched = (remark != null && remark.contains(keyword))
                    || (nickName != null && nickName.contains(keyword))
                    || (item.getContactId() != null && item.getContactId().contains(keyword))
                    || (groupName != null && groupName.contains(keyword));
            if (matched) {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendNudge(String userId, String contactId, String suffix) {
        // 1. 校验好友关系
        UserContact userContact = requireFriendContact(userId, contactId);

        // 2. 获取发送者信息
        UserInfo sendUserInfo = userInfoMapper.selectByUserId(userId);
        if (sendUserInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }

        // 3. 构建消息内容
        String messageContent;
        if (StringTools.isEmpty(suffix)) {
            messageContent = String.format(MessageTypeEnum.NUDGE.getInitMessage(), sendUserInfo.getNickName());
        } else {
            messageContent = sendUserInfo.getNickName() + "拍了拍" + suffix;
        }

        // 4. 获取会话ID
        String sessionId = StringTools.getChatSessionId4User(new String[]{userId, contactId});

        // 5. 创建聊天消息
        Date curDate = new Date();
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setSessionId(sessionId);
        chatMessage.setMessageType(MessageTypeEnum.NUDGE.getType());
        chatMessage.setMessageContent(messageContent);
        chatMessage.setSendUserId(userId);
        chatMessage.setSendUserNickName(sendUserInfo.getNickName());
        chatMessage.setSendTime(curDate.getTime());
        chatMessage.setContactId(contactId);
        chatMessage.setContactType(UserContactTypeEnum.USER.getType());
        chatMessage.setStatus(MessageStatusEnum.SENDED.getStatus());
        chatMessageMapper.insert(chatMessage);

        // 6. 更新会话最后消息
        ChatSession chatSession = new ChatSession();
        chatSession.setSessionId(sessionId);
        chatSession.setLastMessage(messageContent);
        chatSession.setLastReceiveTime(curDate.getTime());
        chatSessionMapper.insertOrUpdate(chatSession);

        // 7. 更新会话用户记录
        ChatSessionUser sendSessionUser = chatSessionUserMapper.selectByUserIdAndContactId(userId, contactId);
        if (sendSessionUser != null) {
            sendSessionUser.setLastMessage(messageContent);
            sendSessionUser.setLastReceiveTime(curDate.getTime());
            chatSessionUserMapper.updateByUserIdAndContactId(sendSessionUser, userId, contactId);
        }

        ChatSessionUser contactSessionUser = chatSessionUserMapper.selectByUserIdAndContactId(contactId, userId);
        if (contactSessionUser != null) {
            contactSessionUser.setLastMessage(messageContent);
            contactSessionUser.setLastReceiveTime(curDate.getTime());
            chatSessionUserMapper.updateByUserIdAndContactId(contactSessionUser, contactId, userId);
        }

        // 8. 发送消息
        MessageSendDto messageSendDto = CopyTools.copy(chatMessage, MessageSendDto.class);
        messageHandler.sendMessage(messageSendDto);
    }

    // ==================== 黑名单管理 ====================

    /**
     * 补齐 insertOrUpdate 所需的主键与维度字段
     * <p>
     * {@code UserContactMapper.xml#insertOrUpdate} 的列由 {@code <if test="bean.xxx != null">} 决定，
     * 未设的列不会出现在 INSERT 里。故 upsert 前必须显式补齐 userId/contactId/contactType，
     * 否则会插入一条缺主键的脏行。
     * <p>
     * role 显式置 {@link GroupMemberRoleEnum#MEMBER}：好友维度的 role 语义上无意义，
     * 但 DB 列可空且既有行多为 NULL；统一写 2 便于后续按 role 过滤时不会漏。
     */
    private void fillContactRow(UserContact bean, String userId, String contactId) {
        bean.setUserId(userId);
        bean.setContactId(contactId);
        bean.setContactType(UserContactTypeEnum.USER.getType());
        bean.setRole(GroupMemberRoleEnum.MEMBER.getRole());
        bean.setCreateTime(new Date());
    }
    /**
     * 加载我拉黑的用户列表
     * <p>
     * 修复前只能加黑、没有列表，用户点错一次就永久无法退出。
     * <p>
     * 条件精确性（改动时勿放宽）：
     * <ul>
     *   <li>{@code statusArray} 只含 {@link UserContactStatusEnum#BLACKLIST}（我拉黑他人）——
     *       <b>不含</b> {@link UserContactStatusEnum#BLACKLIST_BE}（他人拉黑我）</li>
     *   <li>{@code contactType=USER}——群组不进入黑名单</li>
     * </ul>
     *
     * @param userId 当前登录用户 id
     * @return 黑名单行，按最近拉黑倒序，含对方昵称；空黑名单返回空列表
     * @since 2026-10-02 加我方式与黑名单管理（openspec/specs/privacy-settings）
     */
    @Override
    public List<UserContact> loadBlackList(String userId) {
        UserContactQuery contactQuery = new UserContactQuery();
        contactQuery.setUserId(userId);
        contactQuery.setContactType(UserContactTypeEnum.USER.getType());
        contactQuery.setStatusArray(new Integer[]{
                UserContactStatusEnum.BLACKLIST.getStatus()});
        contactQuery.setQueryContactUserInfo(true);
        contactQuery.setOrderBy("last_update_time desc");
        return this.findListByParam(contactQuery);
    }

    /**
     * 解除黑名单：删除我与对方的关系行并清双向缓存
     * <p>
     * 拉黑是<b>双向</b>写的（我→他=4 BLACKLIST，他→我=5 BLACKLIST_BE），
     * 只删自己那行会留下「我已解除、对方仍显示被拉黑」的单向不一致，故反向行也删。
     * <p>
     * <b>安全红线</b>：守卫必须校验 status。若只判「行存在」，
     * 面对 {@code status=5}（他拉黑了我）我就能单方解除别人的拉黑。
     * 同理反向 DELETE 只在 status=BLACKLIST_BE 时执行——
     * 对方也拉黑了我时（反向 status=4），他的拉黑记录不归我处置。
     *
     * @param userId    当前登录用户 id
     * @param contactId 被解除拉黑的用户 id
     * @throws BusinessException 目标不在我的黑名单中 → CODE_2401
     * @since 2026-10-02 加我方式与黑名单管理（openspec/specs/privacy-settings）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBlackList(String userId, String contactId) {
        UserContact myContact = this.userContactMapper.selectByUserIdAndContactId(userId, contactId);
        if (myContact == null
                || !UserContactStatusEnum.BLACKLIST.getStatus().equals(myContact.getStatus())) {
            throw new BusinessException(ResponseCodeEnum.CODE_2401);
        }
        this.userContactMapper.deleteByUserIdAndContactId(userId, contactId);
        // 仅当反向行是「被拉黑」时才删；反向为 BLACKLIST 说明对方也拉黑了我，保留
        UserContact otherContact = this.userContactMapper.selectByUserIdAndContactId(contactId, userId);
        if (otherContact != null
                && UserContactStatusEnum.BLACKLIST_BE.getStatus().equals(otherContact.getStatus())) {
            this.userContactMapper.deleteByUserIdAndContactId(contactId, userId);
        }
        // 双向清联系人缓存
        this.redisComponet.removeUserContact(contactId, userId);
        this.redisComponet.removeUserContact(userId, contactId);
    }
}