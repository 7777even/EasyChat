package com.easychat.service.impl;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.ChatSessionUserQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.service.ChatSessionUserService;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


/**
 * 会话用户 业务接口实现
 */
@Service("chatSessionUserService")
public class ChatSessionUserServiceImpl implements ChatSessionUserService {

    @Resource
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private ChannelContextUtils channelContextUtils;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<ChatSessionUser> findListByParam(ChatSessionUserQuery param) {
        return this.chatSessionUserMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(ChatSessionUserQuery param) {
        return this.chatSessionUserMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<ChatSessionUser> findListByPage(ChatSessionUserQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<ChatSessionUser> list = this.findListByParam(param);
        PaginationResultVO<ChatSessionUser> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(ChatSessionUser bean) {
        return this.chatSessionUserMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<ChatSessionUser> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatSessionUserMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<ChatSessionUser> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatSessionUserMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(ChatSessionUser bean, ChatSessionUserQuery param) {
        StringTools.checkParam(param);
        return this.chatSessionUserMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(ChatSessionUserQuery param) {
        StringTools.checkParam(param);
        return this.chatSessionUserMapper.deleteByParam(param);
    }

    /**
     * 根据UserIdAndContactId获取对象
     */
    @Override
    public ChatSessionUser getChatSessionUserByUserIdAndContactId(String userId, String contactId) {
        return this.chatSessionUserMapper.selectByUserIdAndContactId(userId, contactId);
    }

    /**
     * 根据UserIdAndContactId修改
     */
    @Override
    public Integer updateChatSessionUserByUserIdAndContactId(ChatSessionUser bean, String userId, String contactId) {
        return this.chatSessionUserMapper.updateByUserIdAndContactId(bean, userId, contactId);
    }

    /**
     * 根据UserIdAndContactId删除
     */
    @Override
    public Integer deleteChatSessionUserByUserIdAndContactId(String userId, String contactId) {
        return this.chatSessionUserMapper.deleteByUserIdAndContactId(userId, contactId);
    }

    /**
     * 会话用户级属性变更的公共落库 + 跨端广播逻辑
     */
    private void updateSessionUserAttr(String userId, String contactId, String action, Object value,
                                       java.util.function.BiConsumer<ChatSessionUser, Object> setter) {
        ChatSessionUser dbInfo = chatSessionUserMapper.selectByUserIdAndContactId(userId, contactId);
        if (dbInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1003);
        }
        ChatSessionUser updateInfo = new ChatSessionUser();
        setter.accept(updateInfo, value);
        chatSessionUserMapper.updateByUserIdAndContactId(updateInfo, userId, contactId);
        // 其他在线设备同步（本地 SQLite 只是缓存，服务端才是真源）
        channelContextUtils.broadcastSessionUserSync(userId, action, dbInfo.getSessionId(), contactId, value);
    }

    @Override
    public void setSessionTop(String userId, String contactId, Integer topType) {
        if (topType == null || (topType != Constants.ZERO && topType != Constants.ONE)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        updateSessionUserAttr(userId, contactId, "top", topType,
                (bean, v) -> bean.setTopType((Integer) v));
    }

    @Override
    public void setSessionNoDisturb(String userId, String contactId, Integer noDisturb) {
        if (noDisturb == null || (noDisturb != Constants.ZERO && noDisturb != Constants.ONE)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        updateSessionUserAttr(userId, contactId, "noDisturb", noDisturb,
                (bean, v) -> bean.setNoDisturb((Integer) v));
    }

    @Override
    public void saveSessionDraft(String userId, String contactId, String draft) {
        updateSessionUserAttr(userId, contactId, "draft", draft == null ? "" : draft,
                (bean, v) -> bean.setDraft((String) v));
    }

    @Override
    public void updateRedundanceInfo(String contactName, String contactId) {
        if (StringTools.isEmpty(contactName)) {
            return;
        }
        ChatSessionUser updateInfo = new ChatSessionUser();
        updateInfo.setContactName(contactName);

        ChatSessionUserQuery chatSessionUserQuery = new ChatSessionUserQuery();
        chatSessionUserQuery.setContactId(contactId);
        this.chatSessionUserMapper.updateByParam(updateInfo, chatSessionUserQuery);

        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);

        if (contactTypeEnum == UserContactTypeEnum.GROUP) {
            MessageSendDto messageSendDto = new MessageSendDto();
            messageSendDto.setContactType(UserContactTypeEnum.getByPrefix(contactId).getType());
            messageSendDto.setContactId(contactId);
            messageSendDto.setExtendData(contactName);
            messageSendDto.setMessageType(MessageTypeEnum.CONTACT_NAME_UPDATE.getType());
            messageHandler.sendMessage(messageSendDto);
        } else {
            UserContactQuery userContactQuery = new UserContactQuery();
            userContactQuery.setContactType(UserContactTypeEnum.USER.getType());
            userContactQuery.setContactId(contactId);
            userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            List<UserContact> userContactList = userContactMapper.selectList(userContactQuery);
            for (UserContact userContact : userContactList) {
                MessageSendDto messageSendDto = new MessageSendDto();
                messageSendDto.setContactType(contactTypeEnum.getType());
                messageSendDto.setContactId(userContact.getUserId());
                messageSendDto.setExtendData(contactName);
                messageSendDto.setMessageType(MessageTypeEnum.CONTACT_NAME_UPDATE.getType());
                messageSendDto.setSendUserId(contactId);
                messageSendDto.setSendUserNickName(contactName);
                messageHandler.sendMessage(messageSendDto);
            }
        }
    }
}