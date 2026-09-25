package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.UserInfoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@RestController("adminUserInfoController")
@RequestMapping("/admin")
public class AdminUserInfoController extends ABaseController {

    @Resource
    private UserInfoService userInfoService;

    @PostMapping("/loadUser")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO> loadUser(UserInfoQuery userInfoQuery) {
        userInfoQuery.setOrderBy("create_time desc");
        PaginationResultVO resultVO = userInfoService.findListByPage(userInfoQuery);
        return success(resultVO);
    }


    @PostMapping("/updateUserStatus")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> updateUserStatus(@NotNull Integer status,
                                       @NotEmpty String userId) {
        userInfoService.updateUserStatus(status, userId);
        return success(null);
    }

    @PostMapping("/forceOffLine")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> forceOffLine(@NotEmpty String userId) {
        userInfoService.forceOffLine(userId);
        return success(null);
    }
}
