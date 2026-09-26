package com.easychat.service.impl;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.SensitiveWord;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.SensitiveWordMapper;
import com.easychat.service.SensitiveWordService;
import com.easychat.utils.StringTools;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service("sensitiveWordService")
public class SensitiveWordServiceImpl implements SensitiveWordService {

    @Resource
    private SensitiveWordMapper sensitiveWordMapper;

    private volatile List<SensitiveWord> wordList = new ArrayList<>();

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public void reload() {
        List<SensitiveWord> list = sensitiveWordMapper.selectByStatus(1);
        this.wordList = list == null ? new ArrayList<>() : list;
    }

    @Override
    public String filter(String content) {
        if (StringTools.isEmpty(content) || wordList.isEmpty()) {
            return content;
        }
        // 第一遍：命中 level=3（禁止发送）直接拦截，消息不入库、不发送
        for (SensitiveWord sw : wordList) {
            String word = sw.getWord();
            if (StringTools.isEmpty(word)) {
                continue;
            }
            if (content.contains(word) && sw.getLevel() != null && sw.getLevel() == 3) {
                throw new BusinessException(ResponseCodeEnum.CODE_2701);
            }
        }
        // 第二遍：命中 level=1/2（提醒/替换）的词替换为 ***
        String result = content;
        for (SensitiveWord sw : wordList) {
            String word = sw.getWord();
            if (StringTools.isEmpty(word)) {
                continue;
            }
            if (result.contains(word) && (sw.getLevel() == null || sw.getLevel() == 1 || sw.getLevel() == 2)) {
                result = result.replace(word, "***");
            }
        }
        return result;
    }
}
