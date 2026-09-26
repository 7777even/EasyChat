package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.po.GroupFile;
import com.easychat.entity.query.GroupFileQuery;
import com.easychat.entity.vo.GroupFileVO;
import com.easychat.entity.vo.PaginationResultVO;

/**
 * 群文件业务接口
 */
public interface GroupFileService {

    /**
     * 上传群文件：合并分片并入库
     */
    GroupFile uploadGroupFile(TokenUserInfoDto userInfoDto, String fileId, String groupId,
                              String fileName, Integer totalChunks, Integer fileType, Long fileSize);

    /**
     * 分页获取群文件列表（按上传时间倒序，仅 status=1）
     */
    PaginationResultVO<GroupFileVO> getGroupFileList(TokenUserInfoDto userInfoDto, String groupId,
                                                     Integer pageNo, Integer pageSize);

    /**
     * 删除群文件（逻辑删除）：上传者本人或群主/管理员可删
     */
    void deleteGroupFile(TokenUserInfoDto userInfoDto, String groupId, Long fileId);
}
