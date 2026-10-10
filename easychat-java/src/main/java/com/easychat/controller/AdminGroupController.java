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
            // ⚠ 原为 CODE_200，而该枚举项码值就是 0（成功码），
            //   会得到 HTTP 400 + body {code:0, message:"success"}：
            //   前端错误分支直接把 body.message 当文案弹出 → 管理员看到一条写着 "success" 的错误提示，
            //   且真实原因丢失。资源不存在按 AGENTS §3.1/§3.2 应用 CODE_1003 + 404。
            //   （2026-10-10 由 AdminGroupSettingUpdateControllersMockMvcTest 实证抓出）
            throw new BusinessException(ResponseCodeEnum.CODE_1003);
        }
        groupInfoService.dissolutionGroup(groupInfo.getGroupOwnerId(), groupId);
        return success(null);
    }
}
