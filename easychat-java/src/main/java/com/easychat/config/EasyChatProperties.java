package com.easychat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * EasyChat 应用级配置属性
 *
 * 所有以 easychat 为前缀的配置项集中在此类管理，
 * 不得在 Controller、Service、Component 中分散使用 @Value 注入。
 *
 * 配置示例（application.properties）：
 * easychat.project-folder=c:/easychat/
 * easychat.admin-emails=test@qq.com
 */
@Component
@ConfigurationProperties(prefix = "easychat")
public class EasyChatProperties {

    /** 项目本地存储目录 */
    private String projectFolder = "c:/easychat/";

    /** 超级管理员邮箱（多个用逗号分隔） */
    private String adminEmails = "test@qq.com";

    /** 文件上传配置 */
    private FileUpload fileUpload = new FileUpload();

    /** 登录策略配置 */
    private Login login = new Login();

    public String getProjectFolder() {
        return projectFolder;
    }

    public void setProjectFolder(String projectFolder) {
        this.projectFolder = projectFolder;
    }

    public String getAdminEmails() {
        return adminEmails;
    }

    public void setAdminEmails(String adminEmails) {
        this.adminEmails = adminEmails;
    }

    public FileUpload getFileUpload() {
        return fileUpload;
    }

    public void setFileUpload(FileUpload fileUpload) {
        this.fileUpload = fileUpload;
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    /**
     * 登录策略配置
     * <p>
     * singleDevice=true 时：只允许单端在线，新登录会把旧设备挤下线（推 FORCE_OFF_LINE 帧）。<br>
     * singleDevice=false（默认）时：允许多端同时在线，符合 openspec/specs/multi-device-sync 承诺。
     */
    public static class Login {

        /** 是否强制单端登录：true=新登录挤掉旧登录；false=允许多端在线 */
        private Boolean singleDevice = false;

        /** 多端模式下同一账号允许的同时在线设备数上限（0 或 null 表示不限制） */
        private Integer maxDeviceCount = 0;

        public Boolean getSingleDevice() {
            return singleDevice;
        }

        public void setSingleDevice(Boolean singleDevice) {
            this.singleDevice = singleDevice;
        }

        public Integer getMaxDeviceCount() {
            return maxDeviceCount;
        }

        public void setMaxDeviceCount(Integer maxDeviceCount) {
            this.maxDeviceCount = maxDeviceCount;
        }
    }

    /**
     * 文件上传相关配置
     */
    public static class FileUpload {

        /** 单个文件最大大小 */
        private String maxFileSize = "15MB";

        /** 单个请求最大大小 */
        private String maxRequestSize = "15MB";

        /** 允许上传的图片类型 */
        private String allowedImageTypes = "jpg,jpeg,png,gif,bmp,webp";

        /** 允许上传的文件类型 */
        private String allowedFileTypes = "jpg,jpeg,png,gif,bmp,webp,pdf,doc,docx,xls,xlsx,ppt,pptx,zip,rar,txt,mp4,mp3";

        public String getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(String maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public String getMaxRequestSize() {
            return maxRequestSize;
        }

        public void setMaxRequestSize(String maxRequestSize) {
            this.maxRequestSize = maxRequestSize;
        }

        public String getAllowedImageTypes() {
            return allowedImageTypes;
        }

        public void setAllowedImageTypes(String allowedImageTypes) {
            this.allowedImageTypes = allowedImageTypes;
        }

        public String getAllowedFileTypes() {
            return allowedFileTypes;
        }

        public void setAllowedFileTypes(String allowedFileTypes) {
            this.allowedFileTypes = allowedFileTypes;
        }
    }
}
