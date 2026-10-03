package com.easychat.service;

import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.vo.PaginationResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;


/**
 * 聊天消息表 业务接口
 */
public interface ChatMessageService {

    /**
     * 根据条件查询列表
     */
    List<ChatMessage> findListByParam(ChatMessageQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(ChatMessageQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<ChatMessage> findListByPage(ChatMessageQuery param);

    /**
     * 新增
     */
    Integer add(ChatMessage bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<ChatMessage> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<ChatMessage> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(ChatMessage bean, ChatMessageQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(ChatMessageQuery param);

    /**
     * 根据MessageId查询对象
     */
    ChatMessage getChatMessageByMessageId(Long messageId);


    /**
     * 根据MessageId修改
     */
    Integer updateChatMessageByMessageId(ChatMessage bean, Long messageId);


    /**
     * 根据MessageId删除
     */
    Integer deleteChatMessageByMessageId(Long messageId);

    MessageSendDto saveMessage(ChatMessage chatMessage, TokenUserInfoDto tokenUserInfoDto);

    /**
     * 标记语音消息已播放（仅接收方可为本人标记）
     *
     * <p>「未播放红点」状态是 <b>每接收方独立</b> 的，落在旁挂表
     * {@code chat_message_voice_read}（ADR-001：不能加列到共享的 chat_message）。
     *
     * @param userId    当前登录用户（只能是接收方本人）
     * @param messageId 语音消息 id
     * @throws com.easychat.exception.BusinessException
     *         消息不存在 / 已删 → CODE_2201；非接收方 → CODE_1001；
     *         该消息不是语音（VOICE=24）→ CODE_1001
     * @since 2026-10-03 位置消息与语音消息接通
     */
    void markVoiceRead(String userId, Long messageId);

    /**
     * 批量查询「已播放」的语音消息 id（仅限本人）
     *
     * @param userId       当前登录用户
     * @param messageIdList 消息 id 列表（调用方须先校验这些消息属于该用户所在会话）
     * @return 已播放的 message_id 列表（不可变视图）
     * @throws com.easychat.exception.BusinessException 入参超过 200 个 → CODE_1001
     * @since 2026-10-03 位置消息与语音消息接通
     */
    List<Long> loadVoiceRead(String userId, List<Long> messageIdList);

    void saveMessageFile(String userId, Long messageId, MultipartFile file, MultipartFile cover);

    File downloadFile(TokenUserInfoDto userInfoDto, Long messageId, Boolean cover);

    /**
     * 撤回消息
     */
    MessageSendDto recallMessage(Long messageId, TokenUserInfoDto tokenUserInfoDto);

    /**
     * 管理端删除消息（举报处置 DELETE_CONTENT）：
     * delete_flag 置位 + 会话最新消息预览占位 + 事务后推 20 ADMIN_DELETE 帧
     * （单聊双方 + 群离线成员离线缓冲，ADR-002/003/004）。
     *
     * @param messageId 消息 ID
     * @param admin     处置管理员（审计上下文）
     * @return true=本次新删除；false=消息不存在或此前已删除（幂等跳过，不改写不重推）
     */
    boolean adminDeleteMessage(Long messageId, TokenUserInfoDto admin);

    /**
     * 搜索消息
     */
    PaginationResultVO<ChatMessage> searchMessage(ChatMessageQuery query, String keyword, String sendUserId,
                                                   Integer messageType, Long startTime, Long endTime);

    /**
     * 云端消息漫游：按会话分页拉取服务端历史消息（新设备可拉全量历史）
     *
     * @param sessionId     会话 ID（调用方需已校验归属）
     * @param lastMessageId 上一页最早一条消息的 ID；为空表示从最新开始
     * @param pageSize      每页条数
     */
    PaginationResultVO<ChatMessage> loadHistoryMessage(String sessionId, Long lastMessageId, Integer pageSize);

    /**
     * 定位到指定消息：返回该消息所在的一页（用于搜索结果跳转 / @ 提及跳转）
     */
    PaginationResultVO<ChatMessage> locateMessage(Long messageId, Integer pageSize);

    /**
     * 全局搜索：跨会话检索消息 + 联系人 + 群组
     *
     * @param userId   当前用户
     * @param keyword  关键词
     * @param scope    all / message / contact / group
     */
    com.easychat.entity.vo.GlobalSearchResultVO globalSearch(String userId, String keyword, String scope);
}