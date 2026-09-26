package com.easychat.entity.query;


/**
 * 群文件查询参数
 */
public class GroupFileQuery extends BaseParam {


    /**
     * ID
     */
    private Long id;

    private Long idAnd;

    /**
     * 群ID
     */
    private String groupId;

    private String groupIdFuzzy;

    /**
     * 文件名
     */
    private String fileName;

    private String fileNameFuzzy;

    /**
     * 0图片 1视频 2文件
     */
    private Integer fileType;

    /**
     * 上传人
     */
    private String uploadUserId;

    private String uploadUserIdFuzzy;

    /**
     * 1正常 0删除
     */
    private Integer status;

    /**
     * 列表查询时附加上传人昵称
     */
    private Boolean queryUploadUserInfo;


    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setIdAnd(Long idAnd) {
        this.idAnd = idAnd;
    }

    public Long getIdAnd() {
        return this.idAnd;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupId() {
        return this.groupId;
    }

    public void setGroupIdFuzzy(String groupIdFuzzy) {
        this.groupIdFuzzy = groupIdFuzzy;
    }

    public String getGroupIdFuzzy() {
        return this.groupIdFuzzy;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return this.fileName;
    }

    public void setFileNameFuzzy(String fileNameFuzzy) {
        this.fileNameFuzzy = fileNameFuzzy;
    }

    public String getFileNameFuzzy() {
        return this.fileNameFuzzy;
    }

    public void setFileType(Integer fileType) {
        this.fileType = fileType;
    }

    public Integer getFileType() {
        return this.fileType;
    }

    public void setUploadUserId(String uploadUserId) {
        this.uploadUserId = uploadUserId;
    }

    public String getUploadUserId() {
        return this.uploadUserId;
    }

    public void setUploadUserIdFuzzy(String uploadUserIdFuzzy) {
        this.uploadUserIdFuzzy = uploadUserIdFuzzy;
    }

    public String getUploadUserIdFuzzy() {
        return this.uploadUserIdFuzzy;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setQueryUploadUserInfo(Boolean queryUploadUserInfo) {
        this.queryUploadUserInfo = queryUploadUserInfo;
    }

    public Boolean getQueryUploadUserInfo() {
        return this.queryUploadUserInfo;
    }

}
