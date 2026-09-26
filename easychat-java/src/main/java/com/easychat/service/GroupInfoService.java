package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


/**
 * 业务接口
 */
public interface GroupInfoService {

    /**
     * 根据条件查询列表
     */
    List<GroupInfo> findListByParam(GroupInfoQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(GroupInfoQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<GroupInfo> findListByPage(GroupInfoQuery param);

    /**
     * 新增
     */
    Integer add(GroupInfo bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<GroupInfo> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<GroupInfo> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(GroupInfo bean, GroupInfoQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(GroupInfoQuery param);

    /**
     * 根据GroupId查询对象
     */
    GroupInfo getGroupInfoByGroupId(String groupId);


    /**
     * 根据GroupId修改
     */
    Integer updateGroupInfoByGroupId(GroupInfo bean, String groupId);


    /**
     * 根据GroupId删除
     */
    Integer deleteGroupInfoByGroupId(String groupId);

    void saveGroup(GroupInfo groupInfo, MultipartFile avatarFile, MultipartFile avatarCover);

    void dissolutionGroup(String userId, String groupId);

    void leaveGroup(String userId, String groupId, MessageTypeEnum messageTypeEnum);

    void addOrRemoveGroupUser(TokenUserInfoDto tokenUserInfoDto, String groupId, String contactIds, Integer opType);

    /**
     * 转让群主（仅群主可操作）
     */
    void transferOwner(TokenUserInfoDto tokenUserInfoDto, String groupId, String newOwnerUserId);

    /**
     * 设置/取消管理员（仅群主可操作，不可操作群主自己）
     */
    void setAdmin(TokenUserInfoDto tokenUserInfoDto, String groupId, String userId, GroupMemberRoleEnum roleEnum);

    /**
     * 禁言/解除禁言群成员（群主与管理员可操作，minutes=0 表示解除）
     */
    void muteMember(TokenUserInfoDto tokenUserInfoDto, String groupId, String userId, Integer minutes);

    /**
     * 编辑群公告（群主与管理员可操作）
     */
    void editGroupNotice(TokenUserInfoDto tokenUserInfoDto, String groupId, String notice);

    /**
     * 分页获取群成员列表（含角色、禁言状态）
     */
    PaginationResultVO<UserContact> getGroupMemberList(TokenUserInfoDto tokenUserInfoDto, String groupId);

    /**
     * 校验群成员是否被禁言；若是则抛出业务异常（供消息发送前调用）
     */
    void checkMuted(String userId, String groupId);

    /**
     * 校验用户群角色：非成员抛 CODE_2304；角色超过 minRole 权限则抛 CODE_2305；通过则返回成员关系（含 role）
     */
    UserContact checkGroupRole(String userId, String groupId, GroupMemberRoleEnum minRole);
}