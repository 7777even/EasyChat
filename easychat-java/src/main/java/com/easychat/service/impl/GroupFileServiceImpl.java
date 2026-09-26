package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.GroupFile;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupFileQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.vo.GroupFileVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupFileMapper;
import com.easychat.service.FileUploadService;
import com.easychat.service.GroupFileService;
import com.easychat.service.GroupInfoService;
import com.easychat.utils.CopyTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 群文件业务实现
 */
@Service("groupFileService")
public class GroupFileServiceImpl implements GroupFileService {

    private static final Logger logger = LoggerFactory.getLogger(GroupFileServiceImpl.class);

    @Resource
    private GroupFileMapper<GroupFile, GroupFileQuery> groupFileMapper;

    @Resource
    private FileUploadService fileUploadService;

    @Resource
    private GroupInfoService groupInfoService;

    @Resource
    private AppConfig appConfig;

    @Override
    public GroupFile uploadGroupFile(TokenUserInfoDto userInfoDto, String fileId, String groupId,
                                     String fileName, Integer totalChunks, Integer fileType, Long fileSize) {
        // 仅群成员可上传
        groupInfoService.checkGroupRole(userInfoDto.getUserId(), groupId, GroupMemberRoleEnum.MEMBER);

        String storedFileName = fileUploadService.mergeGroupFile(fileId, fileName, totalChunks, userInfoDto);

        GroupFile groupFile = new GroupFile();
        groupFile.setGroupId(groupId);
        groupFile.setFileName(fileName);
        groupFile.setFilePath(storedFileName);
        groupFile.setFileSize(fileSize);
        groupFile.setFileType(fileType);
        groupFile.setUploadUserId(userInfoDto.getUserId());
        groupFile.setCreateTime(System.currentTimeMillis());
        groupFile.setStatus(1);
        groupFileMapper.insert(groupFile);
        logger.info("群文件上传成功: groupId={}, fileId={}, fileName={}", groupId, groupFile.getId(), fileName);
        return groupFile;
    }

    @Override
    public PaginationResultVO<GroupFileVO> getGroupFileList(TokenUserInfoDto userInfoDto, String groupId,
                                                           Integer pageNo, Integer pageSize) {
        // 仅群成员可查看
        groupInfoService.checkGroupRole(userInfoDto.getUserId(), groupId, GroupMemberRoleEnum.MEMBER);

        GroupFileQuery query = new GroupFileQuery();
        query.setGroupId(groupId);
        query.setStatus(1);
        query.setQueryUploadUserInfo(true);
        query.setOrderBy("create_time desc");

        int count = groupFileMapper.selectCount(query);
        int pNo = (pageNo == null || pageNo < 1) ? 1 : pageNo;
        int pSize = (pageSize == null || pageSize < 1) ? PageSize.SIZE20.getSize() : pageSize;
        SimplePage page = new SimplePage(pNo, count, pSize);
        query.setSimplePage(page);

        List<GroupFile> list = groupFileMapper.selectList(query);
        List<GroupFileVO> voList = list.stream().map(f -> CopyTools.copy(f, GroupFileVO.class))
                .collect(Collectors.toList());

        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), voList);
    }

    @Override
    public void deleteGroupFile(TokenUserInfoDto userInfoDto, String groupId, Long fileId) {
        // 成员校验 + 取回成员关系（含角色）
        UserContact contact =
                groupInfoService.checkGroupRole(userInfoDto.getUserId(), groupId, GroupMemberRoleEnum.MEMBER);

        GroupFile file = groupFileMapper.selectById(fileId);
        if (file == null || !groupId.equals(file.getGroupId()) || file.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_2601);
        }

        boolean isOwnerOrAdmin = GroupMemberRoleEnum.OWNER.getRole().equals(contact.getRole())
                || GroupMemberRoleEnum.ADMIN.getRole().equals(contact.getRole());
        if (!userInfoDto.getUserId().equals(file.getUploadUserId()) && !isOwnerOrAdmin) {
            throw new BusinessException(ResponseCodeEnum.CODE_1002);
        }

        GroupFile updateBean = new GroupFile();
        updateBean.setStatus(0);
        GroupFileQuery updateQuery = new GroupFileQuery();
        updateQuery.setId(fileId);
        groupFileMapper.updateByParam(updateBean, updateQuery);
        logger.info("群文件删除成功: groupId={}, fileId={}, operator={}", groupId, fileId, userInfoDto.getUserId());
    }
}
