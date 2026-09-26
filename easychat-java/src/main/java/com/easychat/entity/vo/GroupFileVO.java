package com.easychat.entity.vo;

import com.easychat.entity.po.GroupFile;

/**
 * 群文件列表展示对象（在 PO 基础上附加上传人昵称）
 */
public class GroupFileVO extends GroupFile {

    /**
     * 上传人昵称
     */
    private String uploadUserNickName;

    public String getUploadUserNickName() {
        return uploadUserNickName;
    }

    public void setUploadUserNickName(String uploadUserNickName) {
        this.uploadUserNickName = uploadUserNickName;
    }
}
