package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.GroupFile;
import com.easychat.entity.query.GroupFileQuery;
import com.easychat.entity.vo.GroupFileVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.GroupFileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

/**
 * 群文件控制器
 */
@RestController
@RequestMapping("/group/file")
public class GroupFileController extends ABaseController {

    @Resource
    private GroupFileService groupFileService;

    /**
     * 上传群文件：合并已上传的分片并入库（分片经 /upload/uploadChunk 先行上传）
     */
    @PostMapping("/upload")
    @GlobalInterceptor
    public Result<GroupFile> upload(HttpServletRequest request,
                                    @NotEmpty String fileId,
                                    @NotEmpty String groupId,
                                    @NotEmpty String fileName,
                                    @NotNull Integer totalChunks,
                                    @NotNull Integer fileType,
                                    Long fileSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        GroupFile groupFile = groupFileService.uploadGroupFile(userInfoDto, fileId, groupId,
                fileName, totalChunks, fileType, fileSize);
        return success(groupFile);
    }

    /**
     * 群文件列表（按上传时间倒序，分页）
     */
    @PostMapping("/list")
    @GlobalInterceptor
    public Result<PaginationResultVO<GroupFileVO>> list(HttpServletRequest request,
                                                        @NotEmpty String groupId,
                                                        Integer pageNo,
                                                        Integer pageSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        PaginationResultVO<GroupFileVO> result = groupFileService.getGroupFileList(userInfoDto, groupId, pageNo, pageSize);
        return success(result);
    }

    /**
     * 删除群文件（逻辑删除）：上传者本人或群主/管理员可删
     */
    @PostMapping("/delete")
    @GlobalInterceptor
    public Result<Void> delete(HttpServletRequest request,
                               @NotEmpty String groupId,
                               @NotNull Long fileId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        groupFileService.deleteGroupFile(userInfoDto, groupId, fileId);
        return success();
    }
}
