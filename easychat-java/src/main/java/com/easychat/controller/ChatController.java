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
import com.easychat.exception.BusinessException;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
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


    /**
     * 标记语音消息已播放（未播放红点）。
     *
     * <p>「已播放」是 <b>每接收方独立</b> 的状态，落旁挂表 {@code chat_message_voice_read}（ADR-001）。
     * 发送者本人不参与标记，非接收方拒绝，消息不存在 → CODE_2201。
     *
     * @since 2026-10-03 位置消息与语音消息接通
     */
    @PostMapping("/markVoiceRead")
    @GlobalInterceptor
    public Result<Void> markVoiceRead(HttpServletRequest request, @NotNull Long messageId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        chatMessageService.markVoiceRead(tokenUserInfoDto.getUserId(), messageId);
        return success();
    }

    /**
     * 批量查询「已播放」的语音消息 id 列表（仅限本人）。
     *
     * <p>供前端渲染气泡红点：返回当前用户在传入消息列表中已播过的 id。
     * 强制按 {@code userId} 过滤，不会泄露他人播放状态；入参超过 200 → CODE_1001。
     *
     * @since 2026-10-03 位置消息与语音消息接通
     */
    @PostMapping("/loadVoiceRead")
    @GlobalInterceptor
    public Result<java.util.List<Long>> loadVoiceRead(HttpServletRequest request,
                                                       @NotEmpty String messageIdList) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String s : messageIdList.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                try {
                    ids.add(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    throw new BusinessException(ResponseCodeEnum.CODE_1001,
                            "messageIdList 存在非数字元素: " + s);
                }
            }
        }
        return success(chatMessageService.loadVoiceRead(tokenUserInfoDto.getUserId(), ids));
    }

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
        if (null == messageTypeEnum || !ArrayUtils.contains(
                new Integer[]{
                        MessageTypeEnum.CHAT.getType(),
                        MessageTypeEnum.MEDIA_CHAT.getType(),
                        // 2026-10-03 接通位置 / 语音消息。
                        // 之前此处白名单只有 {2,5}，点「发送位置」/「按住说话」直接 CODE_1001，
                        // 连消息都建不出来 —— 这是位置/语音功能从未可用的第一层断链（probe 取证确认）。
                        MessageTypeEnum.VOICE.getType(),
                        MessageTypeEnum.LOCATION.getType()
                }, messageType)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        // 语音消息必填守卫：防伪造参数绕过前端 60s 上限（duration 由客户端传）
        if (MessageTypeEnum.VOICE.getType().equals(messageType)) {
            if (StringTools.isEmpty(fileName)) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "语音消息缺少文件名");
            }
            if (duration == null || duration < 1) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "语音消息缺少时长");
            }
            if (duration > Constants.VOICE_MAX_DURATION_SECONDS) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001,
                        "语音时长不能超过 " + Constants.VOICE_MAX_DURATION_SECONDS + " 秒");
            }
            // 语音必须有 fileType=3 标记，否则接收方无法走音频渲染分支
            if (fileType == null || fileType != 3) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "语音消息 fileType 必须为 3");
            }
        }
        // 位置消息必填守卫：extraData 必须是合法 JSON 且含 location
        if (MessageTypeEnum.LOCATION.getType().equals(messageType)) {
            if (StringTools.isEmpty(extraData)) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "位置消息缺少位置信息");
            }
            // 复用 IdListTools 的严格校验思路？不——位置是 JSON 对象而非数组。
            // 直接用 fastjson 解析，失败即拒；同时校验 location 字段存在。
            try {
                com.alibaba.fastjson.JSONObject obj = com.alibaba.fastjson.JSON.parseObject(extraData);
                if (obj == null || StringTools.isEmpty(obj.getString("location"))) {
                    throw new BusinessException(ResponseCodeEnum.CODE_1001, "位置信息缺少 location 字段");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "位置信息格式错误");
            }
            if (extraData.length() > 2000) {
                throw new BusinessException(ResponseCodeEnum.CODE_1001, "位置信息超长");
            }
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
