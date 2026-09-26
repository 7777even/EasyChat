package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.service.ChatMessageService;
import com.easychat.service.ChatSessionUserService;
import com.easychat.utils.StringTools;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.Max;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

@RestController
@RequestMapping("/chat")
public class ChatController extends ABaseController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Resource
    private ChatMessageService chatMessageService;

    @Resource
    private ChatSessionUserService chatSessionUserService;

    @Resource
    private ChatSessionUserMapper<ChatSessionUser, com.easychat.entity.query.ChatSessionUserQuery> chatSessionUserMapper;

    @Resource
    private AppConfig appConfig;


    @PostMapping("/sendMessage")
    @GlobalInterceptor
    public Result<MessageSendDto> sendMessage(HttpServletRequest request,
                                              @NotEmpty String contactId,
                                              @NotEmpty @Max(500) String messageContent,
                                              @NotNull Integer messageType,
                                              Long fileSize,
                                              String fileName,
                                              Integer fileType,
                                              String clientId,
                                              String extraData,
                                              String atUserIds,
                                              Integer duration) {
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(messageType);
        if (null == messageTypeEnum || !ArrayUtils.contains(new Integer[]{MessageTypeEnum.CHAT.getType(), MessageTypeEnum.MEDIA_CHAT.getType()}, messageType)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setContactId(contactId);
        chatMessage.setMessageContent(messageContent);
        chatMessage.setFileSize(fileSize);
        chatMessage.setFileName(fileName);
        chatMessage.setFileType(fileType);
        chatMessage.setMessageType(messageType);
        chatMessage.setClientId(clientId);
        // 引用回复 / 转发 / @ 提及等扩展数据（原样落库并随帧回推）
        chatMessage.setExtraData(extraData);
        chatMessage.setAtUserIds(atUserIds);
        chatMessage.setDuration(duration);
        MessageSendDto messageSendDto = chatMessageService.saveMessage(chatMessage, tokenUserInfoDto);
        return success(messageSendDto);
    }

    @PostMapping("uploadFile")
    @GlobalInterceptor
    public Result<Void> uploadFile(HttpServletRequest request,
                                   @NotNull Long messageId,
                                   @NotNull MultipartFile file,
                                   MultipartFile cover) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        chatMessageService.saveMessageFile(userInfoDto.getUserId(), messageId, file, cover);
        return success();
    }

    @PostMapping("downloadFile")
    @GlobalInterceptor
    public void downloadFile(HttpServletRequest request, HttpServletResponse response,
                             @NotEmpty String fileId,
                             @NotNull Boolean showCover,
                             String partType) throws Exception {
        logger.info("下载文件请求: fileId={}, showCover={}, partType={}", fileId, showCover, partType);

        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        OutputStream out = null;
        FileInputStream in = null;
        try {
            File file = null;
            // 处理朋友圈文件
            if ("moment".equals(partType)) {
                String momentFolderName = Constants.FILE_FOLDER_FILE + "moment/";
                String momentPath = appConfig.getProjectFolder() + momentFolderName + fileId;
                if (showCover) {
                    momentPath = momentPath + Constants.COVER_IMAGE_SUFFIX;
                }
                logger.info("朋友圈文件路径: {}", momentPath);
                file = new File(momentPath);
                if (!file.exists()) {
                    logger.error("朋友圈文件不存在: {}", momentPath);
                    throw new BusinessException(ResponseCodeEnum.CODE_2104);
                }
            } else if (!StringTools.isNumber(fileId)) {
                // 处理头像文件
                String avatarFolderName = Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_AVATAR_NAME;
                String avatarPath = appConfig.getProjectFolder() + avatarFolderName + fileId + Constants.IMAGE_SUFFIX;
                if (showCover) {
                    avatarPath = avatarPath + Constants.COVER_IMAGE_SUFFIX;
                }
                file = new File(avatarPath);
                if (!file.exists()) {
                    throw new BusinessException(ResponseCodeEnum.CODE_2104);
                }
            } else if ("group".equals(partType)) {
                // 处理群文件
                String groupFolderName = Constants.FILE_FOLDER_FILE + Constants.FILE_FOLDER_GROUP;
                String groupPath = appConfig.getProjectFolder() + groupFolderName + fileId;
                file = new File(groupPath);
                if (!file.exists()) {
                    throw new BusinessException(ResponseCodeEnum.CODE_2601);
                }
            } else {
                // 处理聊天消息文件
                file = chatMessageService.downloadFile(userInfoDto, Long.parseLong(fileId), showCover);
            }
            response.setContentType("application/x-msdownload; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment;");
            response.setContentLengthLong(file.length());
            in = new FileInputStream(file);
            byte[] byteData = new byte[1024];
            out = response.getOutputStream();
            int len = 0;
            while ((len = in.read(byteData)) != -1) {
                out.write(byteData, 0, len);
            }
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.error("IO异常", e);
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.error("IO异常", e);
                }
            }
        }
    }

    @PostMapping("/recallMessage")
    @GlobalInterceptor
    public Result<MessageSendDto> recallMessage(HttpServletRequest request,
                                                @NotNull Long messageId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        MessageSendDto messageSendDto = chatMessageService.recallMessage(messageId, tokenUserInfoDto);
        return success(messageSendDto);
    }

    /**
     * 会话内消息搜索
     * <p>
     * 安全修复：必须校验 sessionId 归属当前用户（会话 ID 可推算，历史实现存在越权读取他人消息的风险）。
     */
    @PostMapping("/searchMessage")
    @GlobalInterceptor
    public Result<PaginationResultVO<ChatMessage>> searchMessage(HttpServletRequest request,
                                                                 @NotEmpty String sessionId,
                                                                 String keyword,
                                                                 String sendUserId,
                                                                 Integer messageType,
                                                                 Long startTime,
                                                                 Long endTime,
                                                                 Integer pageNo) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        checkSessionOwner(tokenUserInfoDto.getUserId(), sessionId);
        ChatMessageQuery query = new ChatMessageQuery();
        query.setSessionId(sessionId);
        query.setPageNo(pageNo);
        query.setPageSize(20);
        return success(chatMessageService.searchMessage(query, keyword, sendUserId, messageType, startTime, endTime));
    }

    /**
     * 云端消息漫游：按会话分页拉取服务端历史消息
     * <p>
     * lastMessageId 为空时从最新一条往前取；否则取 messageId &lt; lastMessageId 的更早消息。
     * 同样校验会话归属，防止越权。
     */
    @PostMapping("/loadHistoryMessage")
    @GlobalInterceptor
    public Result<PaginationResultVO<ChatMessage>> loadHistoryMessage(HttpServletRequest request,
                                                                      @NotEmpty String sessionId,
                                                                      Long lastMessageId,
                                                                      Integer pageSize) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        checkSessionOwner(tokenUserInfoDto.getUserId(), sessionId);
        return success(chatMessageService.loadHistoryMessage(sessionId, lastMessageId,
                pageSize == null || pageSize <= 0 || pageSize > 100 ? 20 : pageSize));
    }

    /**
     * 定位到指定消息：返回该消息所在页（用于搜索结果跳转 / @ 提及跳转）
     */
    @PostMapping("/locateMessage")
    @GlobalInterceptor
    public Result<PaginationResultVO<ChatMessage>> locateMessage(HttpServletRequest request,
                                                                 @NotNull Long messageId,
                                                                 Integer pageSize) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        ChatMessage message = chatMessageService.getChatMessageByMessageId(messageId);
        if (message == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2201);
        }
        checkSessionOwner(tokenUserInfoDto.getUserId(), message.getSessionId());
        return success(chatMessageService.locateMessage(messageId,
                pageSize == null || pageSize <= 0 || pageSize > 100 ? 20 : pageSize));
    }

    /**
     * 全局搜索：跨会话消息 + 联系人 + 群组
     *
     * @param scope all / message / contact / group
     */
    @PostMapping("/globalSearch")
    @GlobalInterceptor
    public Result<com.easychat.entity.vo.GlobalSearchResultVO> globalSearch(HttpServletRequest request,
                                                                           @NotEmpty String keyword,
                                                                           String scope) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        String realScope = StringTools.isEmpty(scope) ? "all" : scope;
        return success(chatMessageService.globalSearch(tokenUserInfoDto.getUserId(), keyword, realScope));
    }

    /**
     * 置顶 / 取消置顶（服务端真源，跨端同步）
     */
    @PostMapping("/setSessionTop")
    @GlobalInterceptor
    public Result<Void> setSessionTop(HttpServletRequest request,
                                      @NotEmpty String contactId,
                                      @NotNull Integer topType) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        chatSessionUserService.setSessionTop(tokenUserInfoDto.getUserId(), contactId, topType);
        return success();
    }

    /**
     * 会话免打扰
     */
    @PostMapping("/setSessionNoDisturb")
    @GlobalInterceptor
    public Result<Void> setSessionNoDisturb(HttpServletRequest request,
                                            @NotEmpty String contactId,
                                            @NotNull Integer noDisturb) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        chatSessionUserService.setSessionNoDisturb(tokenUserInfoDto.getUserId(), contactId, noDisturb);
        return success();
    }

    /**
     * 保存会话草稿（跨端同步）
     */
    @PostMapping("/saveSessionDraft")
    @GlobalInterceptor
    public Result<Void> saveSessionDraft(HttpServletRequest request,
                                         @NotEmpty String contactId,
                                         String draft) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        chatSessionUserService.saveSessionDraft(tokenUserInfoDto.getUserId(), contactId, draft);
        return success();
    }

    /**
     * 校验会话归属：chat_session_user 中存在 (userId, sessionId) 记录才允许访问
     */
    private void checkSessionOwner(String userId, String sessionId) {
        com.easychat.entity.query.ChatSessionUserQuery query = new com.easychat.entity.query.ChatSessionUserQuery();
        query.setUserId(userId);
        query.setSessionId(sessionId);
        Integer count = chatSessionUserMapper.selectCount(query);
        if (count == null || count == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_2202);
        }
    }
}
