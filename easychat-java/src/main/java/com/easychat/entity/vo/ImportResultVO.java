package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 敏感词批量导入结果出参（三态计数）
 */
public class ImportResultVO implements Serializable {

    /**
     * 成功新增行数
     */
    private int success;

    /**
     * 跳过行数（词条已存在 / 重复）
     */
    private int skipped;

    /**
     * 失败行数（格式非法 / 校验不通过）
     */
    private int failed;

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }
}
