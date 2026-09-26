package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 群文件 / 群相册
 */
public class GroupFile implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 群ID
     */
    private String groupId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 存储路径（相对文件名：{fileId}.{ext}）
     */
    private String filePath;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 0图片 1视频 2文件
     */
    private Integer fileType;

    /**
     * 封面
     */
    private String coverPath;

    /**
     * 上传人
     */
    private String uploadUserId;

    /**
     * 上传人昵称（列表查询时由 user_info 关联带出，仅展示用）
     */
    private String uploadUserNickName;

    /**
     * 上传时间毫秒
     */
    private Long createTime;

    /**
     * 1正常 0删除
     */
    private Integer status;


    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupId() {
        return this.groupId;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return this.fileName;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return this.filePath;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getFileSize() {
        return this.fileSize;
    }

    public void setFileType(Integer fileType) {
        this.fileType = fileType;
    }

    public Integer getFileType() {
        return this.fileType;
    }

    public void setCoverPath(String coverPath) {
        this.coverPath = coverPath;
    }

    public String getCoverPath() {
        return this.coverPath;
    }

    public void setUploadUserId(String uploadUserId) {
        this.uploadUserId = uploadUserId;
    }

    public String getUploadUserId() {
        return this.uploadUserId;
    }

    public void setUploadUserNickName(String uploadUserNickName) {
        this.uploadUserNickName = uploadUserNickName;
    }

    public String getUploadUserNickName() {
        return this.uploadUserNickName;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    @Override
    public String toString() {
        return "群ID:" + (groupId == null ? "空" : groupId) + "，文件名:" + (fileName == null ? "空" : fileName) + "，存储路径:" + (filePath == null ? "空" : filePath) + "，文件大小:" + (fileSize == null ? "空" : fileSize) + "，0图片 1视频 2文件:" + (fileType == null ? "空" : fileType) + "，封面:" + (coverPath == null ? "空" : coverPath) + "，上传人:" + (uploadUserId == null ? "空" : uploadUserId) + "，上传时间毫秒:" + (createTime == null ? "空" : createTime) + "，1正常 0删除:" + (status == null ? "空" : status);
    }
}
