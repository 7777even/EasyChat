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
import java.util.Comparator;
import java.util.List;

@Service("sensitiveWordService")
public class SensitiveWordServiceImpl implements SensitiveWordService {

    @Resource
    private SensitiveWordMapper sensitiveWordMapper;

    private volatile List<SensitiveWord> wordList = new ArrayList<>();

    /**
     * 与 {@link #wordList} <b>元素集合恒等、仅顺序不同</b>的「长度降序」列表，供替换阶段遍历。
     * <p>
     * <b>为何必须与 wordList 分开存</b>：拦截阶段（level=3）必须以<b>原始未替换内容</b>判定，
     * 且其语义与顺序无关；替换阶段则需要固定顺序。若直接改 {@code wordList} 的顺序去服务替换阶段，
     * 就把「两个阶段顺序需求不同」这件事藏进了同一个列表，后续任一阶段改动都易互相踩。
     * <p>
     * <b>为何与 wordList 成对发布</b>：两字段若分两次赋值，并发读线程可能看到
     * 「新 wordList + 旧 maskingList」的撕裂组合，使输出在短时间内退回旧行为。
     * 故 {@link #reload()} 内成对构建后一次发布。
     */
    private volatile List<SensitiveWord> maskingList = new ArrayList<>();

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public void reload() {
        List<SensitiveWord> list = sensitiveWordMapper.selectByStatus(1);
        List<SensitiveWord> raw = list == null ? new ArrayList<>() : list;
        List<SensitiveWord> ordered = new ArrayList<>(raw);
        // 长度**降序** + 空词排最后；同长度按字面量升序兜底，使「与顺序无关」这一性质
        // 不依赖具体排序算法的稳定性实现（List#sort 本身稳定，但换实现也不应改变输出）。
        //
        // ⚠ 两个易错点，均由本次 TDD 抓出：
        //   ① `Comparator.comparingInt` 是**升序**。直接 `comparingInt(len)` 得到的是
        //      「短词优先」——恰好与目标相反，且与修复前的旧行为一致，故旧测试全绿、
        //      只有新增的「顺序无关」用例能抓到。降序必须显式 `.reversed()`。
        //   ② 空词的键取 -1（而非 MAX_VALUE）：reversed 之后「键大的在前」，
        //      取 MAX_VALUE 会把空词排到**最前**，反而最先被遍历到。
        ordered.sort(Comparator
                .comparingInt((SensitiveWord sw) -> StringTools.isEmpty(sw.getWord())
                        ? -1 : sw.getWord().length())
                .reversed()
                .thenComparing(SensitiveWord::getWord, Comparator.nullsFirst(Comparator.naturalOrder())));
        // 先构建完再赋值：避免撕裂（见 maskingList 的 javadoc）
        this.wordList = raw;
        this.maskingList = ordered;
    }

    @Override
    public String filter(String content) {
        if (StringTools.isEmpty(content) || wordList.isEmpty()) {
            return content;
        }
        // 第一遍：命中 level=3（禁止发送）直接拦截，消息不入库、不发送
        // 遍历 wordList（原始顺序）—— 本遍以原始 content 判定，与顺序天然无关
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
        // 遍历 maskingList（长度降序）—— 互为子串时让长词先获得匹配机会，
        // 从而使输出成为「在册词条集合」的纯函数，而非「词表行顺序」的函数。
        // 排序在 reload() 内预计算，此处只做线性扫描：filter 是每条消息都走的热路径。
        String result = content;
        for (SensitiveWord sw : maskingList) {
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
