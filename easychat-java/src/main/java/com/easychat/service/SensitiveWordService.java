package com.easychat.service;

/**
 * 敏感词过滤：聊天 / 朋友圈发布 / 评论写入前的实时拦截与替换
 */
public interface SensitiveWordService {

    /**
     * 过滤文本内容：
     * - 命中 level=3（禁止发送）抛 BusinessException(CODE_2701)
     * - 命中 level=1/2（提醒/替换）将该词替换为 ***
     * - 空内容或空词库原样返回
     */
    String filter(String content);

    /**
     * 重新加载敏感词库到内存（预留给管理端调用）
     */
    void reload();
}
