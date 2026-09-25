package com.easychat.service;

import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.query.ChatSessionUserQuery;
import com.easychat.entity.vo.PaginationResultVO;

import java.util.List;


/**
 * 会话用户 业务接口
 */
public interface ChatSessionUserService {

    /**
     * 根据条件查询列表
     */
    List<ChatSessionUser> findListByParam(ChatSessionUserQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(ChatSessionUserQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<ChatSessionUser> findListByPage(ChatSessionUserQuery param);

    /**
     * 新增
     */
    Integer add(ChatSessionUser bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<ChatSessionUser> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<ChatSessionUser> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(ChatSessionUser bean, ChatSessionUserQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(ChatSessionUserQuery param);

    /**
     * 根据UserIdAndContactId查询对象
     */
    ChatSessionUser getChatSessionUserByUserIdAndContactId(String userId, String contactId);


    /**
     * 根据UserIdAndContactId修改
     */
    Integer updateChatSessionUserByUserIdAndContactId(ChatSessionUser bean, String userId, String contactId);


    /**
     * 根据UserIdAndContactId删除
     */
    Integer deleteChatSessionUserByUserIdAndContactId(String userId, String contactId);

    void updateRedundanceInfo(String contactName, String contactId);

    /**
     * 设置会话置顶状态（服务端真源），并向该用户其他在线设备广播同步帧
     *
     * @param userId    当前操作用户
     * @param contactId 会话联系人 ID
     * @param topType   0取消置顶 1置顶
     */
    void setSessionTop(String userId, String contactId, Integer topType);

    /**
     * 设置会话免打扰
     *
     * @param noDisturb 0正常 1免打扰
     */
    void setSessionNoDisturb(String userId, String contactId, Integer noDisturb);

    /**
     * 保存会话草稿（跨端同步）
     */
    void saveSessionDraft(String userId, String contactId, String draft);
}