package com.easychat.service.impl;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentNotify;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.MomentNotifyQuery;
import com.easychat.entity.query.MomentQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.MomentNotifyVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentNotifyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.service.MomentNotifyService;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 朋友圈通知服务实现
 */
@Service("momentNotifyService")
public class MomentNotifyServiceImpl implements MomentNotifyService {

    private static final Logger logger = LoggerFactory.getLogger(MomentNotifyServiceImpl.class);

    /** 通知类型 → WS 帧 messageType 映射（15 新动态 / 16 点赞 / 17 评论 / 18 @） */
    private static final Map<Integer, Integer> TYPE_2_MESSAGE_TYPE = new HashMap<>();

    static {
        TYPE_2_MESSAGE_TYPE.put(0, MessageTypeEnum.MOMENT_NEW.getType());
        TYPE_2_MESSAGE_TYPE.put(1, MessageTypeEnum.MOMENT_LIKE.getType());
        TYPE_2_MESSAGE_TYPE.put(2, MessageTypeEnum.MOMENT_COMMENT.getType());
        TYPE_2_MESSAGE_TYPE.put(3, MessageTypeEnum.MOMENT_COMMENT.getType());
        TYPE_2_MESSAGE_TYPE.put(4, MessageTypeEnum.MOMENT_AT.getType());
    }

    @Resource
    private MomentNotifyMapper<MomentNotify, MomentNotifyQuery> momentNotifyMapper;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private MomentMapper<Moment, MomentQuery> momentMapper;

    @Resource
    private ChannelContextUtils channelContextUtils;

    @Override
    public void pushNotify(String userId, Integer type, Long refId, String fromUserId, String content) {
        if (StringTools.isEmpty(userId) || type == null) {
            return;
        }
        // 自己触发的动作不给自己发通知
        if (userId.equals(fromUserId)) {
            return;
        }
        MomentNotify notify = new MomentNotify();
        notify.setUserId(userId);
        notify.setType(type);
        notify.setRefId(refId);
        notify.setFromUserId(fromUserId);
        notify.setReadStatus(0);
        notify.setCreateTime(System.currentTimeMillis());
        try {
            momentNotifyMapper.insert(notify);
        } catch (Exception e) {
            // 通知写入失败不影响主流程（动态/点赞/评论已成功）
            logger.error("朋友圈通知写入失败, userId={}, type={}", userId, type, e);
            return;
        }

        // 实时推送 WS 帧；离线用户由通知列表兜底（红点靠未读数接口）
        MessageSendDto sendDto = new MessageSendDto();
        Integer messageType = TYPE_2_MESSAGE_TYPE.get(type);
        sendDto.setMessageType(messageType == null ? MessageTypeEnum.MOMENT_NEW.getType() : messageType);
        sendDto.setContactId(userId);
        sendDto.setSendUserId(fromUserId);
        sendDto.setSendTime(notify.getCreateTime());
        sendDto.setMessageContent(content);
        Map<String, Object> extendData = new HashMap<>();
        extendData.put("notifyId", notify.getId());
        extendData.put("notifyType", type);
        extendData.put("refId", refId);
        extendData.put("content", content);
        UserInfo fromUser = userInfoMapper.selectByUserId(fromUserId);
        extendData.put("fromNickName", fromUser == null ? "" : fromUser.getNickName());
        extendData.put("unreadCount", getUnreadCount(userId));
        sendDto.setExtendData(extendData);
        channelContextUtils.sendMessage(sendDto);
    }

    @Override
    public Integer getUnreadCount(String userId) {
        MomentNotifyQuery query = new MomentNotifyQuery();
        query.setUserId(userId);
        query.setReadStatus(0);
        Integer count = momentNotifyMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    @Override
    public PaginationResultVO<MomentNotifyVO> loadNotifyList(String userId, Integer pageNo, Integer pageSize) {
        int realPageNo = pageNo == null || pageNo <= 0 ? 1 : pageNo;
        int realPageSize = pageSize == null || pageSize <= 0 ? PageSize.SIZE20.getSize() : Math.min(pageSize, PageSize.SIZE40.getSize());
        MomentNotifyQuery query = new MomentNotifyQuery();
        query.setUserId(userId);
        query.setOrderBy("create_time desc");
        int count = momentNotifyMapper.selectCount(query);
        SimplePage page = new SimplePage((realPageNo - 1) * realPageSize, realPageSize);
        query.setSimplePage(page);
        List<MomentNotify> list = momentNotifyMapper.selectList(query);
        List<MomentNotifyVO> voList = buildVOList(list);
        return new PaginationResultVO<>(count, realPageSize, realPageNo,
                (count + realPageSize - 1) / realPageSize, voList);
    }

    @Override
    public List<MomentNotifyVO> loadRecentNotify(String userId, Integer limit) {
        MomentNotifyQuery query = new MomentNotifyQuery();
        query.setUserId(userId);
        query.setOrderBy("create_time desc");
        query.setSimplePage(new SimplePage(0, limit == null || limit <= 0 ? 5 : limit));
        return buildVOList(momentNotifyMapper.selectList(query));
    }

    private List<MomentNotifyVO> buildVOList(List<MomentNotify> list) {
        List<MomentNotifyVO> result = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return result;
        }
        Map<String, UserInfo> userCache = new HashMap<>();
        for (MomentNotify item : list) {
            MomentNotifyVO vo = new MomentNotifyVO();
            vo.setId(item.getId());
            vo.setType(item.getType());
            vo.setRefId(item.getRefId());
            vo.setFromUserId(item.getFromUserId());
            vo.setReadStatus(item.getReadStatus());
            vo.setCreateTime(item.getCreateTime());
            UserInfo fromUser = loadUserInfo(item.getFromUserId(), userCache);
            vo.setFromNickName(fromUser == null ? "" : fromUser.getNickName());
            vo.setFromAvatar(item.getFromUserId());
            vo.setContent(buildContent(item));
            result.add(vo);
        }
        return result;
    }

    /**
     * 通知摘要：优先用写入时保存的摘要，缺省时按类型拼装
     */
    private String buildContent(MomentNotify notify) {
        Integer type = notify.getType();
        String prefix;
        if (type == null) {
            prefix = "有新动态";
        } else {
            switch (type) {
                case 0:
                    prefix = "发布了一条新动态";
                    break;
                case 1:
                    prefix = "赞了你的动态";
                    break;
                case 2:
                    prefix = "评论了你的动态";
                    break;
                case 3:
                    prefix = "回复了你的评论";
                    break;
                case 4:
                    prefix = "在动态中@了你";
                    break;
                default:
                    prefix = "有新动态";
            }
        }
        // 附上动态正文摘要，便于通知中心一眼看懂
        String momentBrief = "";
        if (notify.getRefId() != null) {
            Moment moment = momentMapper.selectById(notify.getRefId());
            if (moment != null && !StringTools.isEmpty(moment.getContent())) {
                String content = moment.getContent();
                momentBrief = "：" + (content.length() > 20 ? content.substring(0, 20) + "…" : content);
            }
        }
        return prefix + momentBrief;
    }

    private UserInfo loadUserInfo(String userId, Map<String, UserInfo> cache) {
        if (StringTools.isEmpty(userId)) {
            return null;
        }
        if (cache.containsKey(userId)) {
            return cache.get(userId);
        }
        UserInfo userInfo = userInfoMapper.selectByUserId(userId);
        if (userInfo != null) {
            cache.put(userId, userInfo);
        }
        return userInfo;
    }

    @Override
    public void markAllRead(String userId) {
        momentNotifyMapper.markReadByUserId(userId, null);
        notifyUnreadChange(userId);
    }

    @Override
    public void markReadByType(String userId, Integer type) {
        momentNotifyMapper.markReadByUserId(userId, type);
        notifyUnreadChange(userId);
    }

    @Override
    public void markRead(String userId, Long notifyId) {
        MomentNotify updateInfo = new MomentNotify();
        updateInfo.setReadStatus(1);
        MomentNotifyQuery query = new MomentNotifyQuery();
        query.setId(notifyId);
        query.setUserId(userId);
        momentNotifyMapper.updateByParam(updateInfo, query);
        notifyUnreadChange(userId);
    }

    @Override
    public void clearNotify(String userId) {
        MomentNotifyQuery query = new MomentNotifyQuery();
        query.setUserId(userId);
        momentNotifyMapper.deleteByParam(query);
        notifyUnreadChange(userId);
    }

    /**
     * 未读数变化后推一帧给本人，前端用于实时消红点（多端同步）
     */
    private void notifyUnreadChange(String userId) {
        MessageSendDto sendDto = new MessageSendDto();
        sendDto.setMessageType(Constants.WS_MOMENT_UNREAD_MESSAGE_TYPE);
        sendDto.setContactId(userId);
        Map<String, Object> extendData = new HashMap<>();
        extendData.put("unreadCount", getUnreadCount(userId));
        sendDto.setExtendData(extendData);
        channelContextUtils.sendMessage(sendDto);
    }
}
