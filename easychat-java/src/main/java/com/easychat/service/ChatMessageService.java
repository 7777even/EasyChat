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

    void saveMessageFile(String userId, Long messageId, MultipartFile file, MultipartFile cover);

    File downloadFile(TokenUserInfoDto userInfoDto, Long messageId, Boolean cover);

    /**
     * 撤回消息
     */
    MessageSendDto recallMessage(Long messageId, TokenUserInfoDto tokenUserInfoDto);

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