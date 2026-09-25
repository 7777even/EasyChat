package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.po.AppUpdate;
import com.easychat.entity.query.AppUpdateQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.AppUpdateService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.IOException;

/**
 * app发布 Controller
 */
@RestController("adminAppUpdateController")
@RequestMapping("/admin")
@Validated
public class AdminAppUpdateController extends ABaseController {

    @Resource
    private AppUpdateService appUpdateService;

    /**
     * 根据条件分页查询
     */
    @PostMapping("/loadUpdateList")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO> loadUpdateList(AppUpdateQuery query) {
        query.setOrderBy("id desc");
        return success(appUpdateService.findListByPage(query));
    }

    @PostMapping("/saveUpdate")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> saveUpdate(Integer id,
                                 @NotEmpty String version,
                                 @NotEmpty String updateDesc,
                                 @NotNull Integer fileType,
                                 String outerLink,
                                 MultipartFile file) throws IOException {
        AppUpdate appUpdate = new AppUpdate();
        appUpdate.setId(id);
        appUpdate.setVersion(version);
        appUpdate.setUpdateDesc(updateDesc);
        appUpdate.setFileType(fileType);
        appUpdate.setOuterLink(outerLink);
        appUpdateService.saveUpdate(appUpdate, file);
        return success(null);
    }

    @PostMapping("/delUpdate")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> delUpdate(@NotNull Integer id) {
        appUpdateService.deleteAppUpdateById(id);
        return success(null);
    }

    @PostMapping("/postUpdate")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> postUpdate(@NotNull Integer id, @NotNull Integer status, String grayscaleUid) {
        appUpdateService.postUpdate(id, status, grayscaleUid);
        return success(null);
    }
}