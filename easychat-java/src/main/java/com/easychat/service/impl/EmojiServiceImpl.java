package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.po.Emoji;
import com.easychat.entity.query.EmojiQuery;
import com.easychat.entity.vo.EmojiVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.EmojiMapper;
import com.easychat.service.EmojiService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.StringTools;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表情包业务实现
 */
@Service("emojiService")
public class EmojiServiceImpl implements EmojiService {

    @Resource
    private EmojiMapper<Emoji, EmojiQuery> emojiMapper;

    @Resource
    private AppConfig appConfig;

    @Override
    public List<EmojiVO> listEmoji(String userId) {
        EmojiQuery query = new EmojiQuery();
        query.setUserId(userId);
        List<Emoji> emojiList = emojiMapper.selectList(query);
        if (emojiList == null) {
            return new ArrayList<>();
        }
        return emojiList.stream()
                .map(emoji -> CopyTools.copy(emoji, EmojiVO.class))
                .collect(Collectors.toList());
    }

    @Override
    public EmojiVO uploadEmoji(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        // 校验文件大小（最大 5MB）
        long maxSize = 5 * 1024 * 1024L;
        if (file.getSize() > maxSize) {
            throw new BusinessException("文件大小超过限制，最大 5MB");
        }
        // 校验文件类型（仅支持图片）
        String fileName = file.getOriginalFilename();
        if (StringTools.isEmpty(fileName)) {
            throw new BusinessException("文件名不能为空");
        }
        String suffix = StringTools.getFileSuffix(fileName).toLowerCase();
        if (!Arrays.asList(Constants.IMAGE_SUFFIX_LIST).contains(suffix)) {
            throw new BusinessException("仅支持图片格式");
        }
        // 保存文件
        String baseFolder = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "emoji/";
        File targetFolder = new File(baseFolder);
        if (!targetFolder.exists()) {
            targetFolder.mkdirs();
        }
        String fileRealName = System.currentTimeMillis() + suffix;
        File targetFile = new File(targetFolder.getPath() + "/" + fileRealName);
        try {
            file.transferTo(targetFile);
        } catch (Exception e) {
            throw new BusinessException("文件上传失败");
        }
        // 保存到数据库
        Emoji emoji = new Emoji();
        emoji.setUserId(userId);
        emoji.setFileName(fileName);
        emoji.setFilePath(fileRealName);
        emoji.setFileSize(file.getSize());
        emoji.setEmojiType(1);
        emoji.setCreateTime(System.currentTimeMillis());
        emojiMapper.insert(emoji);
        return CopyTools.copy(emoji, EmojiVO.class);
    }

    @Override
    public void deleteEmoji(String userId, Long emojiId) {
        if (emojiId == null) {
            throw new BusinessException("表情包ID不能为空");
        }
        // 查询表情包
        EmojiQuery query = new EmojiQuery();
        query.setUserId(userId);
        List<Emoji> emojiList = emojiMapper.selectList(query);
        Emoji emoji = emojiList.stream()
                .filter(e -> e.getId().equals(emojiId))
                .findFirst()
                .orElse(null);
        if (emoji == null) {
            throw new BusinessException("表情包不存在");
        }
        // 删除数据库记录
        emojiMapper.deleteById(emojiId);
        // 删除文件
        String baseFolder = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "emoji/";
        File file = new File(baseFolder + emoji.getFilePath());
        if (file.exists()) {
            file.delete();
        }
    }
}
