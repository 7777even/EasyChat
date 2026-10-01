package com.easychat.service;

import com.easychat.entity.po.Emoji;
import com.easychat.entity.query.EmojiQuery;
import com.easychat.entity.vo.EmojiVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 表情包业务接口
 */
public interface EmojiService {

    /**
     * 查询表情包列表
     */
    List<EmojiVO> listEmoji(String userId);

    /**
     * 上传表情包
     */
    EmojiVO uploadEmoji(String userId, MultipartFile file);

    /**
     * 删除表情包
     */
    void deleteEmoji(String userId, Long emojiId);
}
