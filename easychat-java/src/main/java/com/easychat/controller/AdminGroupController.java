package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import com.easychat.service.GroupInfoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotEmpty;

@RestController("adminGroupController")
@RequestMapping("/admin")
public class AdminGroupController extends ABaseController {

    @Resource
    private GroupInfoService groupInfoService;

    @PostMapping("/loadGroup")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO> loadGroup(GroupInfoQuery groupInfoQuery) {
        groupInfoQuery.setQueryGroupOwnerName(true);
        groupInfoQuery.setQueryMemberCount(true);
        PaginationResultVO resultVO = groupInfoService.findListByPage(groupInfoQuery);
        return success(resultVO);
    }

    @PostMapping("/dissolutionGroup")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> dissolutionGroup(@NotEmpty String groupId) {
        GroupInfo groupInfo = groupInfoService.getGroupInfoByGroupId(groupId);
        if (null == groupInfo) {
            throw new BusinessException(ResponseCodeEnum.CODE_200);
        }
        groupInfoService.dissolutionGroup(groupInfo.getGroupOwnerId(), groupId);
        return success(null);
    }
}
