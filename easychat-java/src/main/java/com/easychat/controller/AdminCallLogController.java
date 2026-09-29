package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.query.CallLogQuery;
import com.easychat.entity.vo.AdminCallLogVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.AdminCallLogService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController("adminCallLogController")
@RequestMapping("/admin/callLog")
public class AdminCallLogController extends ABaseController {

    @Resource
    private AdminCallLogService adminCallLogService;

    /**
     * 通话记录列表（分页 + 过滤 + 昵称群名解析）
     */
    @PostMapping("/loadCallLog")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO<AdminCallLogVO>> loadCallLog(CallLogQuery query) {
        return success(adminCallLogService.loadCallLog(query));
    }
}
