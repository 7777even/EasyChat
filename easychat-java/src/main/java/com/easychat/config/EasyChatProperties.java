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
