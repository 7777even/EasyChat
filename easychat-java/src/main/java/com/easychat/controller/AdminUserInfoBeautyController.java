package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.UserInfoBeautySaveDTO;
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
import javax.validation.Valid;
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

    /**
     * 保存靓号（新建 / 编辑同入口，以 {@code id} 是否为空分流）。
     *
     * <p>2026-10-13（openspec {@code 2026-10-13-beauty-account-dto}）：
     * 入参由 {@code entity.po.UserInfoBeauty} 改为 {@link UserInfoBeautySaveDTO}，
     * 消除 AGENTS §3.6「不复用 Entity 做出入参」的偏差（全仓仅此一处）。
     *
     * <p>⚠ Service 层签名**刻意不变**（openspec ADR-001）：本 Change 纠正的是「入参形状」
     * 这一个契约偏差，不是重构整个靓号链路。DTO → PO 的转换**逐字段手写**，
     * 不用 {@code BeanUtils.copyProperties} —— 后者会**静默漏掉**将来新增的字段，
     * 正是本次要消除的那类结构性风险。
     */
    @PostMapping("/saveBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> saveBeautAccount(@Valid UserInfoBeautySaveDTO dto) {
        UserInfoBeauty beauty = new UserInfoBeauty();
        beauty.setId(dto.getId());
        beauty.setEmail(dto.getEmail());
        beauty.setUserId(dto.getUserId());
        beauty.setStatus(dto.getStatus());
        userInfoBeautyService.saveAccount(beauty);
        return success(null);
    }

    @PostMapping("/delBeautAccount")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Integer> delBeautAccount(@NotNull Integer id) {
        return success(userInfoBeautyService.deleteUserInfoBeautyById(id));
    }
}