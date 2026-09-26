package com.easychat.service;

import com.easychat.entity.po.SensitiveWord;
import com.easychat.entity.query.SensitiveWordQuery;
import com.easychat.entity.vo.ImportResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.SensitiveWordVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 敏感词库管理端：分页筛选 / 保存（查重）/ 逻辑删除 / 批量导入 / 导出。
 * 所有写操作成功后自动触发 {@link SensitiveWordService#reload()} 热更。
 */
public interface SensitiveWordAdminService {

    /**
     * 分页查询存活词条（keyword/level/status 过滤）
     */
    PaginationResultVO<SensitiveWordVO> loadWord(SensitiveWordQuery query);

    /**
     * 新增或编辑词条（有 id 即编辑）。
     * - 新增重复词条 / 编辑改名遇同名存活词条 -> CODE_2704
     * - 成功后 reload
     */
    void saveWord(SensitiveWord bean);

    /**
     * 逻辑删除词条（置 delete_flag = 当前时间戳 ms）。
     * - 词条不存在或已删除 -> CODE_2705
     * - 成功后 reload，删掉的词可重新导入/新增
     */
    void deleteWord(Long id);

    /**
     * 批量导入（.txt 统一级别状态 / .csv 三列 word,level,status）。
     * 逐行容错：新增 success / 重复 skipped / 非法 failed；
     * 文件超 2MB、超 5000 行、扩展名不支持 -> CODE_1001。
     * 有新增成功行才 reload。
     */
    ImportResultVO importWords(MultipartFile file, Integer level, Integer status);

    /**
     * 导出存活词条为 CSV 字节流（UTF-8 BOM，列 word,level,status，含公式注入防护）
     */
    byte[] exportWords();
}
