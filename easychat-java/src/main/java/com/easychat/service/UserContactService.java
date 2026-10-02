package com.easychat.service;

import com.easychat.entity.dto.UserContactSearchResultDto;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.PaginationResultVO;

import java.util.List;


/**
 * 联系人 业务接口
 */
public interface UserContactService {

    /**
     * 根据条件查询列表
     */
    List<UserContact> findListByParam(UserContactQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(UserContactQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<UserContact> findListByPage(UserContactQuery param);

    /**
     * 新增
     */
    Integer add(UserContact bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<UserContact> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<UserContact> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(UserContact bean, UserContactQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(UserContactQuery param);

    /**
     * 根据UserIdAndContactId查询对象
     */
    UserContact getUserContactByUserIdAndContactId(String userId, String contactId);


    /**
     * 根据UserIdAndContactId修改
     */
    Integer updateUserContactByUserIdAndContactId(UserContact bean, String userId, String contactId);


    /**
     * 根据UserIdAndContactId删除
     */
    Integer deleteUserContactByUserIdAndContactId(String userId, String contactId);


    UserContactSearchResultDto searchContact(String userId, String contactId);

    /**
     * 添加联系人
     *
     * @param applyUserId
     * @param receiveUserId
     * @param contactId
     * @param contactType
     * @param applyInfo
     */
    void addContact(String applyUserId, String receiveUserId, String contactId, Integer contactType, String applyInfo);

    /**
     * 删除，拉黑用户
     *
     * @param userId
     * @param contactId
     * @param statusEnum
     */
    void removeUserContact(String userId, String contactId, UserContactStatusEnum statusEnum);

    void removeGroupContact(String userId, String groupId, String contactId, UserContactStatusEnum statusEnum);

    void addContact4Robot(String userId);

    /**
     * 设置好友备注名
     */
    void setContactRemark(String userId, String contactId, String remark);

    /**
     * 设置好友分组
     */
    void setContactGroup(String userId, String contactId, String groupName);

    /**
     * 按关键词搜索好友：匹配备注名 / 昵称 / 用户ID / 分组名
     */
    java.util.List<UserContact> searchContactByKeyword(String userId, String keyword);

    /**
     * 拍一拍
     */
    void sendNudge(String userId, String contactId, String suffix);

    /**
     * 加载我拉黑的用户列表（黑名单管理页）
     * <p>
     * 只含 {@code status=BLACKLIST(4)}（我拉黑他人），
     * <b>不含</b> {@code BLACKLIST_BE(5)}（他人拉黑我）——解除他人的拉黑不在我的权限内。
     * 只查好友维度（contactType=0），群组不进入黑名单。
     *
     * @param userId 当前登录用户 id
     * @return 黑名单行，按最近拉黑倒序，含对方昵称
     * @since 2026-10-02 加我方式与黑名单管理
     */
    java.util.List<UserContact> loadBlackList(String userId);

    /**
     * 解除黑名单：删除我与对方的关系行，并清双向缓存
     * <p>
     * 守卫：{@code (我, 他)} 行必须存在<b>且</b> {@code status=BLACKLIST(4)}，否则抛 {@code CODE_2401}。
     * 反向行仅当 {@code status=BLACKLIST_BE(5)} 时才删除——若对方也拉黑了我，
     * 他的拉黑记录不归我处置。
     *
     * @param userId    当前登录用户 id
     * @param contactId 被解除拉黑的用户 id
     * @throws com.easychat.exception.BusinessException 目标不在我的黑名单中 → CODE_2401
     * @since 2026-10-02 加我方式与黑名单管理
     */
    void removeBlackList(String userId, String contactId);
}