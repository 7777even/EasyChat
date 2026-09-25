package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.entity.query.UserInfoBeautyQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.UserInfoBeautyService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;

/**
 * 靓号 Controller
 */
@RestController("userInfoBeautyController")
@RequestMapping("/admin")
@Validated
public class AdminUserInfoBeautyController extends ABaseController {

    @Resource
    private UserInfoBeautyService userInfoBeautyService;

    /**
     * 根据条件分页查询
     */
    @PostMapping("/loadBeautyAccountList")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO<UserInfoBeauty>> loadBeautyAccountList(UserInfoBeautyQuery query) {
        return success(userInfoBeautyService.findListByPage(query));
    }

    @PostMapping("/saveBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> saveBeautAccount(UserInfoBeauty beauty) {
        userInfoBeautyService.saveAccount(beauty);
        return success(null);
    }

    @PostMapping("/delBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Integer> delBeautAccount(@NotNull Integer id) {
        return success(userInfoBeautyService.deleteUserInfoBeautyById(id));
    }
}