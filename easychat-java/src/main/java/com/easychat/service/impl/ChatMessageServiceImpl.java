package com.easychat.service.impl;

import com.easychat.config.EasyChatProperties;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.*;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSession;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.ChatSessionQuery;
import com.easychat.entity.query.ChatSessionUserQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.GlobalSearchResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.ChatMessageService;
import com.easychat.service.GroupInfoService;
import com.easychat.service.SensitiveWordService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.DateUtil;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import jodd.util.ArraysUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;


/**
 * 聊天消息表 业务接口实现
 */
@Service("chatMessageService")
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private SensitiveWordService sensitiveWordService;

    @Resource
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private AppConfig appConfig;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private ChannelContextUtils channelContextUtils;

    @Resource
    @Lazy
    private GroupInfoService groupInfoService;

    @Resource
    private EasyChatProperties easyChatProperties;

    @Resource
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<ChatMessage> findListByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<ChatMessage> findListByPage(ChatMessageQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<ChatMessage> list = this.findListByParam(param);
        PaginationResultVO<ChatMessage> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(ChatMessage bean) {
        return this.chatMessageMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(ChatMessage bean, ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.deleteByParam(param);
    }

    /**
     * 根据MessageId获取对象
     */
    @Override
    public ChatMessage getChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.selectByMessageId(messageId);
    }

    /**
     * 根据MessageId修改
     */
    @Override
    public Integer updateChatMessageByMessageId(ChatMessage bean, Long messageId) {
        return this.chatMessageMapper.updateByMessageId(bean, messageId);
    }

    /**
     * 根据MessageId删除
     */
    @Override
    public Integer deleteChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.deleteByMessageId(messageId);
    }


    @Override
    public MessageSendDto saveMessage(ChatMessage chatMessage, TokenUserInfoDto tokenUserInfoDto) {
        //不是机器人回复，判断好友状态
        if (!Constants.ROBOT_UID.equals(tokenUserInfoDto.getUserId())) {
            List<String> contactList = redisComponet.getUserContactList(tokenUserInfoDto.getUserId());
            if (!contactList.contains(chatMessage.getContactId())) {
                UserContactTypeEnum userContactTypeEnum = UserContactTypeEnum.getByPrefix(chatMessage.getContactId());
                if (UserContactTypeEnum.USER == userContactTypeEnum) {
                    throw new BusinessException(ResponseCodeEnum.CODE_902);
                } else {
                    throw new BusinessException(ResponseCodeEnum.CODE_903);
                }
            }
            // 群聊禁言校验：被群主/管理员禁言的成员不允许发言
            if (UserContactTypeEnum.GROUP == UserContactTypeEnum.getByPrefix(chatMessage.getContactId())) {
                groupInfoService.checkMuted(tokenUserInfoDto.getUserId(), chatMessage.getContactId());
            }
        }
        String sessionId = null;
        String sendUserId = tokenUserInfoDto.getUserId();
        String contactId = chatMessage.getContactId();
        Long curTime = System.currentTimeMillis();
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(chatMessage.getMessageType());
        String lastMessage = chatMessage.getMessageContent();
        String messageContent = StringTools.resetMessageContent(chatMessage.getMessageContent());
        // 敏感词过滤：level3 命中抛 CODE_2701 阻断发送；level1/2 命中替换为 ***
        messageContent = sensitiveWordService.filter(messageContent);
        chatMessage.setMessageContent(messageContent);
        Integer status = MessageTypeEnum.MEDIA_CHAT == messageTypeEnum ? MessageStatusEnum.SENDING.getStatus() : MessageStatusEnum.SENDED.getStatus();
        // ===== 消息可靠性：提前声明 clientId，供 insert 回写 + ACK 使用 =====
        String clientId = chatMessage.getClientId();
        if (ArraysUtil.contains(new Integer[]{
                MessageTypeEnum.CHAT.getType(),
                MessageTypeEnum.GROUP_CREATE.getType(),
                MessageTypeEnum.ADD_FRIEND.getType(),
                MessageTypeEnum.MEDIA_CHAT.getType()
        }, messageTypeEnum.getType())) {
            if (UserContactTypeEnum.USER == contactTypeEnum) {
                sessionId = StringTools.getChatSessionId4User(new String[]{sendUserId, contactId});
            } else {
                sessionId = StringTools.getChatSessionId4Group(contactId);
            }
            //更新会话消息
            ChatSession chatSession = new ChatSession();
            chatSession.setLastMessage(messageContent);
            if (UserContactTypeEnum.GROUP == contactTypeEnum && !MessageTypeEnum.GROUP_CREATE.getType().equals(messageTypeEnum.getType())) {
                chatSession.setLastMessage(tokenUserInfoDto.getNickName() + "：" + messageContent);
            }
            lastMessage = chatSession.getLastMessage();
            //如果是媒体文件
            chatSession.setLastReceiveTime(curTime);
            chatSessionMapper.updateBySessionId(chatSession, sessionId);
            //记录消息消息表
            chatMessage.setSessionId(sessionId);
            chatMessage.setSendUserId(sendUserId);
            chatMessage.setSendUserNickName(tokenUserInfoDto.getNickName());
            chatMessage.setSendTime(curTime);
            chatMessage.setContactType(contactTypeEnum.getType());
            chatMessage.setStatus(status);
            // ===== 消息可靠性：INCR seq + 填充 clientId =====
            if (clientId != null && !clientId.isEmpty()) {
                Long seq = redisComponet.nextMessageSeq(sessionId);
                if (seq != null && seq > 0) {
                    chatMessage.setSeq(seq);
                }
            }
            chatMessageMapper.insert(chatMessage);
        }
        MessageSendDto messageSend = CopyTools.copy(chatMessage, MessageSendDto.class);
        // 把 seq 与 clientId 同步到 WS 推送体，客户端按 seq 排序/去重
        messageSend.setSeq(chatMessage.getSeq());
        messageSend.setClientId(chatMessage.getClientId());
        if (Constants.ROBOT_UID.equals(contactId)) {
            SysSettingDto sysSettingDto = redisComponet.getSysSetting();
            TokenUserInfoDto robot = new TokenUserInfoDto();
            robot.setUserId(sysSettingDto.getRobotUid());
            robot.setNickName(sysSettingDto.getRobotNickName());
            ChatMessage robotChatMessage = new ChatMessage();
            robotChatMessage.setContactId(sendUserId);
            //这里可以对接Ai 根据输入的信息做出回答
            robotChatMessage.setMessageContent("我只是一个机器人无法识别你的消息");
            robotChatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
            saveMessage(robotChatMessage, robot);
        } else {
            messageHandler.sendMessage(messageSend);
            // ===== 消息可靠性：推 ACK 回执给发送方 =====
            if (clientId != null && !clientId.isEmpty() && chatMessage.getSeq() != null && chatMessage.getMessageId() != null) {
                MessageSendDto ackDto = new MessageSendDto();
                ackDto.setMessageId(chatMessage.getMessageId());
                ackDto.setClientId(clientId);
                ackDto.setSeq(chatMessage.getSeq());
                ackDto.setSessionId(sessionId);
                ackDto.setContactId(chatMessage.getContactId());
                ackDto.setContactType(chatMessage.getContactType());
                channelContextUtils.sendAck(ackDto, sendUserId);
            }
            // ===== 多端漫游：广播 SYNC_SESSION 给发送方的所有在线设备 =====
            // 其他设备收到后更新本地会话列表（最后一条消息等）
            java.util.Map<String, Object> sessionData = new java.util.HashMap<>();
            sessionData.put("sessionId", sessionId);
            sessionData.put("lastMessage", lastMessage);
            sessionData.put("lastReceiveTime", curTime);
            sessionData.put("contactType", contactTypeEnum.getType());
            channelContextUtils.broadcastSyncSession(sendUserId, sessionData);
        }
        return messageSend;
    }

    /**
     * 可执行文件/脚本后缀黑名单：白名单之上再叠一层硬拦截（安全红线 §6.2-5）
     */
    private static final List<String> DANGEROUS_SUFFIX_LIST = Arrays.asList(
            "exe", "bat", "cmd", "com", "scr", "pif", "msi", "dll", "sys", "jar", "sh", "ps1", "vbs", "vbe", "js", "jse", "wsf", "lnk");

    /**
     * 上传文件校验：类型白名单 + 可执行文件黑名单 + 分类大小上限。
     * 不通过时抛出业务异常（2603 超限 / 2604 类型不支持），不做静默丢弃。
     */
    private void checkFileAllowed(MultipartFile file, SysSettingDto sysSettingDto) {
        String originalName = file.getOriginalFilename();
        String fileSuffix = StringTools.getFileSuffix(originalName);
        if (StringTools.isEmpty(fileSuffix)) {
            throw new BusinessException(ResponseCodeEnum.CODE_2604);
        }
        String suffix = fileSuffix.toLowerCase().replace(".", "");
        // 1. 可执行文件硬拦截
        if (DANGEROUS_SUFFIX_LIST.contains(suffix)) {
            throw new BusinessException(ResponseCodeEnum.CODE_2604, "禁止上传可执行文件");
        }
        // 2. 类型白名单：配置允许的文件类型 + 视频后缀
        String allowedFileTypes = easyChatProperties.getFileUpload().getAllowedFileTypes();
        boolean inWhiteList = false;
        if (!StringTools.isEmpty(allowedFileTypes)) {
            inWhiteList = Arrays.asList(allowedFileTypes.toLowerCase().split(",")).contains(suffix);
        }
        if (!inWhiteList) {
            for (String videoSuffix : Constants.VIDEO_SUFFIX_LIST) {
                if (videoSuffix.toLowerCase().replace(".", "").equals(suffix)) {
                    inWhiteList = true;
                    break;
                }
            }
        }
        if (!inWhiteList) {
            throw new BusinessException(ResponseCodeEnum.CODE_2604);
        }
        // 3. 分类大小上限
        boolean isImage = Arrays.asList(Constants.IMAGE_SUFFIX_LIST).contains(fileSuffix.toLowerCase());
        boolean isVideo = Arrays.asList(Constants.VIDEO_SUFFIX_LIST).contains(fileSuffix.toLowerCase());
        long maxSize;
        if (isImage && sysSettingDto.getMaxImageSize() != null) {
            maxSize = Constants.FILE_SIZE_MB * sysSettingDto.getMaxImageSize();
        } else if (isVideo && sysSettingDto.getMaxVideoSize() != null) {
            maxSize = Constants.FILE_SIZE_MB * sysSettingDto.getMaxVideoSize();
        } else {
            maxSize = Constants.FILE_SIZE_MB * (sysSettingDto.getMaxFileSize() == null ? 15 : sysSettingDto.getMaxFileSize());
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException(ResponseCodeEnum.CODE_2603,
                    "文件大小超出限制，最大 " + (maxSize / Constants.FILE_SIZE_MB) + "MB");
        }
    }

    @Override
    public void saveMessageFile(String userId, Long messageId, MultipartFile file, MultipartFile cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        if (null == message) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (!message.getSendUserId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        // 文件类型与大小校验：白名单之外一律拒绝（含 .exe 等可执行文件），超限抛错而非静默丢弃
        checkFileAllowed(file, sysSettingDto);
        String fileName = file.getOriginalFilename();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File uploadFile = new File(folder.getPath() + "/" + fileRealName);
        try {
            file.transferTo(uploadFile);
            if (cover != null) {
                cover.transferTo(new File(uploadFile.getPath() + Constants.COVER_IMAGE_SUFFIX));
            }
        } catch (Exception e) {
            logger.error("上传文件失败", e);
            throw new BusinessException("文件上传失败");
        }
        ChatMessage updateInfo = new ChatMessage();
        updateInfo.setStatus(MessageStatusEnum.SENDED.getStatus());
        ChatMessageQuery messageQuery = new ChatMessageQuery();
        messageQuery.setMessageId(messageId);
        chatMessageMapper.updateByParam(updateInfo, messageQuery);

        MessageSendDto messageSend = new MessageSendDto();
        messageSend.setStatus(MessageStatusEnum.SENDED.getStatus());
        messageSend.setMessageId(message.getMessageId());
        messageSend.setMessageType(MessageTypeEnum.FILE_UPLOAD.getType());
        messageSend.setContactId(message.getContactId());
        messageHandler.sendMessage(messageSend);
    }

    @Override
    public File downloadFile(TokenUserInfoDto userInfoDto, Long messageId, Boolean cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        String contactId = message.getContactId();
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        if (UserContactTypeEnum.USER.getType().equals(contactTypeEnum) && !userInfoDto.getUserId().equals(message.getContactId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)) {
            UserContactQuery userContactQuery = new UserContactQuery();
            userContactQuery.setUserId(userInfoDto.getUserId());
            userContactQuery.setContactType(UserContactTypeEnum.GROUP.getType());
            userContactQuery.setContactId(contactId);
            userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            Integer contactCount = userContactMapper.selectCount(userContactQuery);
            if (contactCount == 0) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        String fileName = message.getFileName();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;

        if (cover != null && cover) {
            fileRealName = fileRealName + Constants.COVER_IMAGE_SUFFIX;
        }
        File file = new File(folder.getPath() + "/" + fileRealName);
        if (!file.exists()) {
            logger.info("文件不存在");
            throw new BusinessException(ResponseCodeEnum.CODE_602);
        }
        return file;
    }

    @Override
    public MessageSendDto recallMessage(Long messageId, TokenUserInfoDto tokenUserInfoDto) {
        // 查询消息
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        if (message == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        // 检查是否是发送者本人
        if (!message.getSendUserId().equals(tokenUserInfoDto.getUserId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        // 检查消息类型，只有普通聊天消息和媒体消息可以撤回
        if (!ArraysUtil.contains(new Integer[]{
                MessageTypeEnum.CHAT.getType(),
                MessageTypeEnum.MEDIA_CHAT.getType()
        }, message.getMessageType())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        // 检查是否在2分钟内（120000毫秒）
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - message.getSendTime();
        if (timeDiff > 120000) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        // 更新消息类型为撤回消息
        ChatMessage updateMessage = new ChatMessage();
        updateMessage.setMessageType(MessageTypeEnum.RECALL_MESSAGE.getType());
        updateMessage.setMessageContent("该消息已撤回");
        chatMessageMapper.updateByMessageId(updateMessage, messageId);

        // 构建撤回通知消息
        MessageSendDto recallNotify = new MessageSendDto();
        recallNotify.setMessageId(messageId);
        recallNotify.setMessageType(MessageTypeEnum.RECALL_MESSAGE.getType());
        recallNotify.setSessionId(message.getSessionId());
        recallNotify.setContactId(message.getContactId());
        recallNotify.setContactType(message.getContactType());
        recallNotify.setSendUserId(tokenUserInfoDto.getUserId());
        recallNotify.setSendUserNickName(tokenUserInfoDto.getNickName());
        recallNotify.setSendTime(currentTime);
        recallNotify.setMessageContent("该消息已撤回");

        // 发送WebSocket通知
        messageHandler.sendMessage(recallNotify);

        return recallNotify;
    }


    @Override
    public PaginationResultVO<ChatMessage> searchMessage(ChatMessageQuery query, String keyword, String sendUserId,
                                                          Integer messageType, Long startTime, Long endTime) {
        // 设置搜索条件
        if (!StringTools.isEmpty(keyword)) {
            query.setMessageContentFuzzy(keyword);
        }
        if (!StringTools.isEmpty(sendUserId)) {
            query.setSendUserId(sendUserId);
        }
        if (startTime != null) {
            query.setSendTimeStart(startTime);
        }
        if (endTime != null) {
            query.setSendTimeEnd(endTime);
        }

        // 类型筛选与「只搜聊天/媒体消息」的默认范围互斥：
        // 传了 messageType 就按该类型精确过滤，否则限定在普通聊天 + 媒体消息内。
        // 历史实现两者叠加（message_type = X AND message_type IN (2,5)），
        // 导致筛选非 2/5 类型时结果必然为空。
        if (messageType != null && messageType > 0) {
            query.setMessageType(messageType);
        } else {
            query.setMessageTypeList(new Integer[]{
                    MessageTypeEnum.CHAT.getType(),
                    MessageTypeEnum.MEDIA_CHAT.getType()
            });
        }

        query.setOrderBy("send_time desc");

        return this.findListByPage(query);
    }

    @Override
    public PaginationResultVO<ChatMessage> loadHistoryMessage(String sessionId, Long lastMessageId, Integer pageSize) {
        ChatMessageQuery query = new ChatMessageQuery();
        query.setSessionId(sessionId);
        query.setMessageIdLt(lastMessageId);
        query.setPageNo(1);
        query.setPageSize(pageSize);
        // 向上翻页：从新到旧
        query.setOrderBy("message_id desc");
        return this.findListByPage(query);
    }

    @Override
    public PaginationResultVO<ChatMessage> locateMessage(Long messageId, Integer pageSize) {
        ChatMessage target = chatMessageMapper.selectByMessageId(messageId);
        if (target == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2201);
        }
        // 以目标消息为锚点，取其之后（更新）的一页，保证目标在页内
        ChatMessageQuery query = new ChatMessageQuery();
        query.setSessionId(target.getSessionId());
        query.setMessageIdGe(messageId);
        query.setPageNo(1);
        query.setPageSize(pageSize);
        query.setOrderBy("message_id asc");
        return this.findListByPage(query);
    }

    @Override
    public GlobalSearchResultVO globalSearch(String userId, String keyword, String scope) {
        GlobalSearchResultVO result = new GlobalSearchResultVO();
        if (StringTools.isEmpty(keyword)) {
            return result;
        }
        boolean searchMessage = "all".equals(scope) || "message".equals(scope);
        boolean searchContact = "all".equals(scope) || "contact".equals(scope);
        boolean searchGroup = "all".equals(scope) || "group".equals(scope);

        if (searchMessage) {
            // 只在当前用户参与的会话里搜，避免跨用户越权
            ChatSessionUserQuery sessionUserQuery = new ChatSessionUserQuery();
            sessionUserQuery.setUserId(userId);
            List<ChatSessionUser> sessionList = chatSessionUserMapper.selectList(sessionUserQuery);
            if (sessionList != null && !sessionList.isEmpty()) {
                List<String> sessionIds = sessionList.stream()
                        .map(ChatSessionUser::getSessionId).distinct().collect(Collectors.toList());
                ChatMessageQuery query = new ChatMessageQuery();
                query.setMessageContentFuzzy(keyword);
                query.setSessionIdList(sessionIds);
                query.setMessageTypeList(new Integer[]{
                        MessageTypeEnum.CHAT.getType(),
                        MessageTypeEnum.MEDIA_CHAT.getType()
                });
                query.setPageNo(1);
                query.setPageSize(20);
                query.setOrderBy("send_time desc");
                result.setMessageList(this.findListByParam(query));
            }
        }

        if (searchContact || searchGroup) {
            UserContactQuery contactQuery = new UserContactQuery();
            contactQuery.setUserId(userId);
            contactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            List<UserContact> contactList = userContactMapper.selectList(contactQuery);
            if (contactList != null && !contactList.isEmpty()) {
                List<UserContact> matched = contactList.stream().filter(item -> {
                    String name = item.getRemark() != null ? item.getRemark() : item.getContactName();
                    return (name != null && name.contains(keyword))
                            || (item.getContactId() != null && item.getContactId().contains(keyword));
                }).collect(Collectors.toList());
                if (searchContact) {
                    result.setContactList(matched.stream()
                            .filter(item -> UserContactTypeEnum.USER.getType().equals(item.getContactType()))
                            .collect(Collectors.toList()));
                }
                if (searchGroup) {
                    result.setGroupList(matched.stream()
                            .filter(item -> UserContactTypeEnum.GROUP.getType().equals(item.getContactType()))
                            .collect(Collectors.toList()));
                }
            }
        }
        return result;
    }

}
