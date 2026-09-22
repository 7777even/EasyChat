package com.easychat.entity.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 用户信息更新 DTO
 */
public class UserUpdateDTO {

    /** 用户昵称 */
    private String nickName;

    /** 性别：0-未知，1-男，2-女 */
    private Integer sex;

    /** 个人简介 */
    private String personalSignature;

    /** 头像文件 */
    private MultipartFile avatarFile;

    /** 头像封面文件（裁剪后） */
    private MultipartFile avatarCover;

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public Integer getSex() {
        return sex;
    }

    public void setSex(Integer sex) {
        this.sex = sex;
    }

    public String getPersonalSignature() {
        return personalSignature;
    }

    public void setPersonalSignature(String personalSignature) {
        this.personalSignature = personalSignature;
    }

    public MultipartFile getAvatarFile() {
        return avatarFile;
    }

    public void setAvatarFile(MultipartFile avatarFile) {
        this.avatarFile = avatarFile;
    }

    public MultipartFile getAvatarCover() {
        return avatarCover;
    }

    public void setAvatarCover(MultipartFile avatarCover) {
        this.avatarCover = avatarCover;
    }
}
