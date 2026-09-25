package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.GroupStatusEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.GroupInfoVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;


@RestController("groupController")
@RequestMapping("/group")
public class GroupController extends ABaseController {

    @Resource
    private GroupInfoService groupInfoService;

    @Resource
    private UserContactService userContactService;

    @PostMapping(value = "/saveGroup")
    @GlobalInterceptor
    public Result<Void> saveGroup(HttpServletRequest request,
                                  String groupId,
                                  @NotEmpty String groupName,
                                  String groupNotice,
                                  @NotNull Integer joinType,
                                  MultipartFile avatarFile,
                                  MultipartFile avatarCover) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupOwnerId(tokenUserInfoDto.getUserId());
        groupInfo.setGroupName(groupName);
        groupInfo.setGroupNotice(groupNotice);
        groupInfo.setJoinType(joinType);
        this.groupInfoService.saveGroup(groupInfo, avatarFile, avatarCover);
        return success();
    }

    @PostMapping(value = "/loadMyGroup")
    @GlobalInterceptor
    public Result<List<GroupInfo>> loadMyGroup(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        GroupInfoQuery infoQuery = new GroupInfoQuery();
        infoQuery.setGroupOwnerId(tokenUserInfoDto.getUserId());
        infoQuery.setOrderBy("create_time desc");
        infoQuery.setStatus(GroupStatusEnum.NORMAL.getStatus());
        List<GroupInfo> groupInfoList = this.groupInfoService.findListByParam(infoQuery);
        return success(groupInfoList);
    }

    /**
     * 获取群信息
     */
    @PostMapping(value = "/getGroupInfo")
    @GlobalInterceptor
    public Result<GroupInfo> getGroupInfo(HttpServletRequest request,
                                          @NotEmpty String groupId) {
        GroupInfo groupInfo = getGroupDetailCommon(request, groupId);
        UserContactQuery userContactQuery = new UserContactQuery();
        userContactQuery.setContactId(groupId);
        Integer memberCount = this.userContactService.findCountByParam(userContactQuery);
        groupInfo.setMemberCount(memberCount);
        return success(groupInfo);
    }

    private GroupInfo getGroupDetailCommon(HttpServletRequest request, String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserContact userContact = this.userContactService.getUserContactByUserIdAndContactId(tokenUserInfoDto.getUserId(), groupId);
        if (userContact == null || !UserContactStatusEnum.FRIEND.getStatus().equals(userContact.getStatus())) {
            throw new BusinessException("你不在群聊或者群聊不存在或已经解散");
        }
        GroupInfo groupInfo = this.groupInfoService.getGroupInfoByGroupId(groupId);
        if (groupInfo == null || !GroupStatusEnum.NORMAL.getStatus().equals(groupInfo.getStatus())) {
            throw new BusinessException("群聊不存在或已经解散");
        }
        return groupInfo;
    }

    @PostMapping(value = "/getGroupInfo4Chat")
    @GlobalInterceptor
    public Result<GroupInfoVO> getGroupInfo4Chat(HttpServletRequest request, @NotEmpty String groupId) {
        GroupInfo groupInfo = getGroupDetailCommon(request, groupId);
        UserContactQuery userContactQuery = new UserContactQuery();
        userContactQuery.setContactId(groupId);
        userContactQuery.setQueryUserInfo(true);
        userContactQuery.setOrderBy("create_time asc");
        userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        List<UserContact> userContactList = this.userContactService.findListByParam(userContactQuery);
        GroupInfoVO groupInfoVo = new GroupInfoVO();
        groupInfoVo.setGroupInfo(groupInfo);
        groupInfoVo.setUserContactList(userContactList);
        return success(groupInfoVo);
    }

    /**
     * 退群
     */
    @PostMapping(value = "/leaveGroup")
    @GlobalInterceptor
    public Result<Void> leaveGroup(HttpServletRequest request, @NotEmpty String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.leaveGroup(tokenUserInfoDto.getUserId(), groupId, MessageTypeEnum.LEAVE_GROUP);
        return success();
    }

    /**
     * 解散群
     */
    @PostMapping(value = "/dissolutionGroup")
    @GlobalInterceptor
    public Result<Void> dissolutionGroup(HttpServletRequest request, @NotEmpty String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.dissolutionGroup(tokenUserInfoDto.getUserId(), groupId);
        return success();
    }

    /**
     * 添加或者移除人员
     */
    @PostMapping(value = "/addOrRemoveGroupUser")
    @GlobalInterceptor
    public Result<Void> addOrRemoveGroupUser(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String selectContacts,
                                             @NotNull Integer opType) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.addOrRemoveGroupUser(tokenUserInfoDto, groupId, selectContacts, opType);
        return success();
    }

    /**
     * 转让群主（仅群主可操作）
     */
    @PostMapping(value = "/transferOwner")
    @GlobalInterceptor
    public Result<Void> transferOwner(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String newOwnerUserId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.transferOwner(tokenUserInfoDto, groupId, newOwnerUserId);
        return success();
    }

    /**
     * 设置/取消管理员（仅群主可操作，role 0=取消管理员 1=设为管理员）
     */
    @PostMapping(value = "/setAdmin")
    @GlobalInterceptor
    public Result<Void> setAdmin(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String userId, @NotNull Integer role) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        GroupMemberRoleEnum roleEnum = GroupMemberRoleEnum.ADMIN.getRole().equals(role)
                ? GroupMemberRoleEnum.ADMIN : GroupMemberRoleEnum.MEMBER;
        groupInfoService.setAdmin(tokenUserInfoDto, groupId, userId, roleEnum);
        return success();
    }

    /**
     * 禁言/解除禁言（群主与管理员可操作，minutes=0 表示解除）
     */
    @PostMapping(value = "/muteMember")
    @GlobalInterceptor
    public Result<Void> muteMember(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String userId, Integer minutes) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        if (minutes == null) {
            minutes = 0;
        }
        groupInfoService.muteMember(tokenUserInfoDto, groupId, userId, minutes);
        return success();
    }

    /**
     * 编辑群公告（群主与管理员可操作，保存后会向全体群成员推送）
     */
    @PostMapping(value = "/editNotice")
    @GlobalInterceptor
    public Result<Void> editNotice(HttpServletRequest request, @NotEmpty String groupId, @NotEmpty String notice) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        groupInfoService.editGroupNotice(tokenUserInfoDto, groupId, notice);
        return success();
    }

    /**
     * 分页获取群成员列表
     */
    @PostMapping(value = "/memberList")
    @GlobalInterceptor
    public Result<PaginationResultVO<UserContact>> memberList(HttpServletRequest request, @NotEmpty String groupId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        PaginationResultVO<UserContact> result = groupInfoService.getGroupMemberList(tokenUserInfoDto, groupId);
        return success(result);
    }
}
