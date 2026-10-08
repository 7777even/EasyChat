package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupFile;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupFileQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.vo.GroupFileVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupFileMapper;
import com.easychat.service.FileUploadService;
import com.easychat.service.GroupInfoService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GroupFileServiceImpl 单元测试。
 *
 * <p>群文件的「成员校验 / 删除归属」是权限面：上传与列表仅成员可用，
 * 删除仅上传者本人或群主/管理员可用，且跨群 / 已删除文件一律 2601。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupFileServiceImplTest {

    private static final String MEMBER = "U_member";
    private static final String UPLOADER = "U_uploader";
    private static final String GROUP_ID = "G001";
    private static final String OTHER_GROUP_ID = "G002";
    private static final Long FILE_ID = 1001L;
    private static final String FILE_ID_STR = "F001";

    @InjectMocks
    private GroupFileServiceImpl groupFileService;

    @Mock
    private GroupFileMapper<GroupFile, GroupFileQuery> groupFileMapper;

    @Mock
    private FileUploadService fileUploadService;

    @Mock
    private GroupInfoService groupInfoService;

    @Mock
    private AppConfig appConfig;

    // ======================== 工具方法 ========================

    private TokenUserInfoDto tokenOf(String userId) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        dto.setNickName(userId);
        return dto;
    }

    private UserContact contactOf(String userId, Integer role) {
        UserContact contact = new UserContact();
        contact.setUserId(userId);
        contact.setContactId(GROUP_ID);
        contact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        contact.setRole(role);
        return contact;
    }

    private GroupFile fileOf(String groupId, String uploader, Integer status) {
        GroupFile file = new GroupFile();
        file.setId(FILE_ID);
        file.setGroupId(groupId);
        file.setFileName("报告.pdf");
        file.setFilePath("stored-1001.pdf");
        file.setFileSize(1024L);
        file.setFileType(2);
        file.setUploadUserId(uploader);
        file.setCreateTime(System.currentTimeMillis());
        file.setStatus(status);
        return file;
    }

    private void stubMemberRole(String userId, Integer role) {
        when(groupInfoService.checkGroupRole(eq(userId), eq(GROUP_ID), eq(GroupMemberRoleEnum.MEMBER)))
                .thenReturn(contactOf(userId, role));
    }

    // ======================== uploadGroupFile ========================

    @Test
    public void uploadGroupFile_notMember_throws2305() {
        doThrow(new BusinessException(ResponseCodeEnum.CODE_2305))
                .when(groupInfoService).checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
        try {
            groupFileService.uploadGroupFile(tokenOf(MEMBER), FILE_ID_STR, GROUP_ID, "报告.pdf", 3, 2, 1024L);
            fail("非群成员上传应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
        verify(fileUploadService, never()).mergeGroupFile(anyString(), anyString(), any(), any());
        verify(groupFileMapper, never()).insert(any(GroupFile.class));
    }

    @Test
    public void uploadGroupFile_success_persistsMergedFile() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(fileUploadService.mergeGroupFile(eq(FILE_ID_STR), eq("报告.pdf"), eq(3),
                any(TokenUserInfoDto.class)))
                .thenReturn("stored-F001.pdf");

        GroupFile result = groupFileService.uploadGroupFile(tokenOf(MEMBER), FILE_ID_STR, GROUP_ID,
                "报告.pdf", 3, 2, 1024L);

        // 分片合并后的存储文件名落库
        assertNotNull(result);
        assertEquals("stored-F001.pdf", result.getFilePath());
        assertEquals(GROUP_ID, result.getGroupId());
        assertEquals("报告.pdf", result.getFileName());
        assertEquals(Long.valueOf(1024L), result.getFileSize());
        assertEquals(Integer.valueOf(2), result.getFileType());
        assertEquals(MEMBER, result.getUploadUserId());
        assertEquals(Integer.valueOf(1), result.getStatus());
        assertNotNull("创建时间由服务端填充", result.getCreateTime());
        verify(groupFileMapper).insert(any(GroupFile.class));
    }

    // ======================== getGroupFileList ========================

    @Test
    public void getGroupFileList_notMember_throws2305() {
        doThrow(new BusinessException(ResponseCodeEnum.CODE_2305))
                .when(groupInfoService).checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
        try {
            groupFileService.getGroupFileList(tokenOf(MEMBER), GROUP_ID, 1, 20);
            fail("非群成员查看列表应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void getGroupFileList_success_queryShapeAndDefaultPaging() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectCount(any(GroupFileQuery.class))).thenReturn(2);
        when(groupFileMapper.selectList(any(GroupFileQuery.class))).thenReturn(Arrays.asList(
                fileOf(GROUP_ID, UPLOADER, 1),
                fileOf(GROUP_ID, MEMBER, 1)));

        // pageNo / pageSize 传 null → 默认第 1 页、每页 20 条
        PaginationResultVO<GroupFileVO> result =
                groupFileService.getGroupFileList(tokenOf(MEMBER), GROUP_ID, null, null);

        ArgumentCaptor<GroupFileQuery> queryCaptor = ArgumentCaptor.forClass(GroupFileQuery.class);
        verify(groupFileMapper).selectCount(queryCaptor.capture());
        GroupFileQuery query = queryCaptor.getValue();
        assertEquals(GROUP_ID, query.getGroupId());
        assertEquals(Integer.valueOf(1), query.getStatus());
        assertTrue("列表需带上传人信息", query.getQueryUploadUserInfo());
        assertEquals(SortOption.GROUP_FILE_CREATE_TIME_DESC, query.getSortOption());
        SimplePage page = query.getSimplePage();
        assertEquals(1, page.getPageNo());
        assertEquals(PageSize.SIZE20.getSize(), page.getPageSize());

        assertEquals(Integer.valueOf(2), result.getTotalCount());
        assertEquals(2, result.getList().size());
        assertEquals("报告.pdf", result.getList().get(0).getFileName());
        assertEquals(UPLOADER, result.getList().get(0).getUploadUserId());
    }

    @Test
    public void getGroupFileList_invalidPaging_fallsBackToDefaults() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectCount(any(GroupFileQuery.class))).thenReturn(0);
        when(groupFileMapper.selectList(any(GroupFileQuery.class))).thenReturn(Arrays.asList());

        // 非法页码（0 / 负数）回落默认值，不抛异常
        PaginationResultVO<GroupFileVO> result =
                groupFileService.getGroupFileList(tokenOf(MEMBER), GROUP_ID, 0, -5);

        ArgumentCaptor<GroupFileQuery> queryCaptor = ArgumentCaptor.forClass(GroupFileQuery.class);
        verify(groupFileMapper).selectCount(queryCaptor.capture());
        SimplePage page = queryCaptor.getValue().getSimplePage();
        assertEquals(1, page.getPageNo());
        assertEquals(PageSize.SIZE20.getSize(), page.getPageSize());
        assertEquals(Integer.valueOf(0), result.getTotalCount());
    }

    // ======================== deleteGroupFile ========================

    @Test
    public void deleteGroupFile_notMember_throws2305() {
        doThrow(new BusinessException(ResponseCodeEnum.CODE_2305))
                .when(groupInfoService).checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
        try {
            groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
            fail("非群成员删除应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void deleteGroupFile_fileMissing_throws2601() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(null);
        try {
            groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
            fail("文件不存在应抛 2601");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2601.getCode(), e.getCode());
        }
    }

    @Test
    public void deleteGroupFile_fileFromOtherGroup_throws2601() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        // 文件属于另一个群：防越权删除
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(fileOf(OTHER_GROUP_ID, MEMBER, 1));
        try {
            groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
            fail("跨群文件应抛 2601");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2601.getCode(), e.getCode());
        }
    }

    @Test
    public void deleteGroupFile_fileAlreadyDeleted_throws2601() {
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(fileOf(GROUP_ID, MEMBER, 0));
        try {
            groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
            fail("已删除文件应抛 2601");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2601.getCode(), e.getCode());
        }
    }

    @Test
    public void deleteGroupFile_nonUploaderMember_throws1002() {
        // 普通成员删他人文件 → 系统错误码（无权删除）
        stubMemberRole(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(fileOf(GROUP_ID, UPLOADER, 1));
        try {
            groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
            fail("普通成员删除他人文件应抛 1002");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1002.getCode(), e.getCode());
        }
        verify(groupFileMapper, never()).updateByParam(any(GroupFile.class), any(GroupFileQuery.class));
    }

    @Test
    public void deleteGroupFile_uploader_deletes() {
        stubMemberRole(UPLOADER, GroupMemberRoleEnum.MEMBER.getRole());
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(fileOf(GROUP_ID, UPLOADER, 1));
        groupFileService.deleteGroupFile(tokenOf(UPLOADER), GROUP_ID, FILE_ID);

        ArgumentCaptor<GroupFile> beanCaptor = ArgumentCaptor.forClass(GroupFile.class);
        ArgumentCaptor<GroupFileQuery> queryCaptor = ArgumentCaptor.forClass(GroupFileQuery.class);
        verify(groupFileMapper).updateByParam(beanCaptor.capture(), queryCaptor.capture());
        // 逻辑删除：status -> 0，且只删除目标文件
        assertEquals(Integer.valueOf(0), beanCaptor.getValue().getStatus());
        assertEquals(FILE_ID, queryCaptor.getValue().getId());
    }

    @Test
    public void deleteGroupFile_adminDeletesOthersUpload() {
        // 群主 / 管理员可删除任意成员上传的文件
        stubMemberRole(MEMBER, GroupMemberRoleEnum.ADMIN.getRole());
        when(groupFileMapper.selectById(FILE_ID)).thenReturn(fileOf(GROUP_ID, UPLOADER, 1));
        groupFileService.deleteGroupFile(tokenOf(MEMBER), GROUP_ID, FILE_ID);
        verify(groupFileMapper).updateByParam(any(GroupFile.class), any(GroupFileQuery.class));
    }
}
