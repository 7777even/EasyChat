package com.easychat.service.impl;

import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.SensitiveWord;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.SensitiveWordQuery;
import com.easychat.entity.vo.ImportResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.SensitiveWordVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.SensitiveWordMapper;
import com.easychat.service.SensitiveWordAdminService;
import com.easychat.service.SensitiveWordService;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service("sensitiveWordAdminService")
public class SensitiveWordAdminServiceImpl implements SensitiveWordAdminService {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveWordAdminServiceImpl.class);

    /**
     * 导入文件上限：2MB
     */
    private static final long MAX_IMPORT_SIZE = 2 * 1024 * 1024L;

    /**
     * 导入行数上限：5000 行
     */
    private static final int MAX_IMPORT_LINES = 5000;

    /**
     * 词条长度上限（对齐 sensitive_word.word varchar(50)）
     */
    private static final int MAX_WORD_LENGTH = 50;

    /**
     * 批量插入分片大小
     */
    private static final int BATCH_SIZE = 500;

    @Resource
    private SensitiveWordMapper sensitiveWordMapper;

    @Resource
    private SensitiveWordService sensitiveWordService;

    @Override
    public PaginationResultVO<SensitiveWordVO> loadWord(SensitiveWordQuery query) {
        int count = sensitiveWordMapper.selectCount(query);
        int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
        SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
        query.setSimplePage(page);
        List<SensitiveWordVO> list = sensitiveWordMapper.selectList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }

    @Override
    public void saveWord(SensitiveWord bean) {
        String word = bean.getWord() == null ? null : bean.getWord().trim();
        if (StringTools.isEmpty(word) || word.length() > MAX_WORD_LENGTH) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        checkLevelStatus(bean.getLevel(), bean.getStatus());

        boolean isEdit = bean.getId() != null;
        if (isEdit) {
            SensitiveWord exists = sensitiveWordMapper.selectAliveById(bean.getId());
            if (exists == null) {
                throw new BusinessException(ResponseCodeEnum.CODE_2705);
            }
        }
        // 查重：只看存活行（已删行不占用 word 唯一位，可重新新增）
        SensitiveWord duplicated = sensitiveWordMapper.selectAliveByWord(word);
        if (duplicated != null && (duplicated.getId() == null || !duplicated.getId().equals(bean.getId()))) {
            throw new BusinessException(ResponseCodeEnum.CODE_2704);
        }

        bean.setWord(word);
        if (isEdit) {
            sensitiveWordMapper.updateById(bean, bean.getId());
        } else {
            bean.setCreateTime(System.currentTimeMillis());
            try {
                sensitiveWordMapper.insert(bean);
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底（并发新增）
                throw new BusinessException(ResponseCodeEnum.CODE_2704);
            }
        }
        sensitiveWordService.reload();
        logger.info("敏感词库变更已热更: id={}, word={}, op={}", bean.getId(), word, isEdit ? "update" : "insert");
    }

    @Override
    public void deleteWord(Long id) {
        SensitiveWord exists = sensitiveWordMapper.selectAliveById(id);
        if (exists == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2705);
        }
        sensitiveWordMapper.deleteById(id, System.currentTimeMillis());
        sensitiveWordService.reload();
        logger.info("敏感词已逻辑删除并热更: id={}, word={}", id, exists.getWord());
    }

    @Override
    public ImportResultVO importWords(MultipartFile file, Integer level, Integer status) {
        ImportResultVO result = new ImportResultVO();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        if (file.getSize() > MAX_IMPORT_SIZE) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "导入文件不能超过 2MB");
        }
        String fileName = file.getOriginalFilename();
        String suffix = "";
        if (fileName != null) {
            int idx = fileName.lastIndexOf('.');
            if (idx >= 0) {
                suffix = fileName.substring(idx + 1).toLowerCase();
            }
        }
        boolean isCsv = "csv".equals(suffix);
        if (!isCsv && !"txt".equals(suffix)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "仅支持 .txt / .csv 文件");
        }
        if (!isCsv) {
            // txt 由调用方指定统一级别与状态
            checkLevelStatus(level, status);
        }

        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "文件读取失败");
        }
        // 去 BOM
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        String[] lines = content.split("\\r?\\n");
        if (lines.length > MAX_IMPORT_LINES) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "导入行数不能超过 5000 行");
        }

        List<SensitiveWord> toInsert = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 一次性载入全部存活词，避免逐行查库（5000 行上限）
        Set<String> aliveWords = new HashSet<>();
        for (SensitiveWord alive : sensitiveWordMapper.selectAllAlive()) {
            if (alive.getWord() != null) {
                aliveWords.add(alive.getWord());
            }
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String word;
            int rowLevel;
            int rowStatus;
            if (isCsv) {
                String[] cols = line.split(",", -1);
                if (cols.length != 3) {
                    result.setFailed(result.getFailed() + 1);
                    continue;
                }
                word = unquote(cols[0].trim());
                String levelStr = unquote(cols[1].trim());
                String statusStr = unquote(cols[2].trim());
                // 首行表头跳过
                if (i == 0 && "word".equalsIgnoreCase(word)) {
                    continue;
                }
                if (!StringTools.isNumber(levelStr) || !StringTools.isNumber(statusStr)) {
                    result.setFailed(result.getFailed() + 1);
                    continue;
                }
                rowLevel = Integer.parseInt(levelStr);
                rowStatus = Integer.parseInt(statusStr);
            } else {
                word = line;
                rowLevel = level;
                rowStatus = status;
            }
            word = word.trim();
            // 导出时对 =+-@ 开头值前置了单引号（防 Excel 公式注入），导回时剥离以保证往返等价
            if (word.length() > 1 && word.charAt(0) == '\'' && "=+-@".indexOf(word.charAt(1)) >= 0) {
                word = word.substring(1);
            }
            if (StringTools.isEmpty(word) || word.length() > MAX_WORD_LENGTH
                    || rowLevel < 1 || rowLevel > 3 || rowStatus < 0 || rowStatus > 1) {
                result.setFailed(result.getFailed() + 1);
                continue;
            }
            // 文件内重复
            if (!seen.add(word)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            // 库内已存在（存活行）
            if (aliveWords.contains(word)) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            SensitiveWord bean = new SensitiveWord();
            bean.setWord(word);
            bean.setLevel(rowLevel);
            bean.setStatus(rowStatus);
            bean.setCreateTime(System.currentTimeMillis());
            toInsert.add(bean);
        }

        if (!toInsert.isEmpty()) {
            insertBatchTolerant(toInsert, result);
            sensitiveWordService.reload();
        }
        logger.info("敏感词批量导入完成: success={}, skipped={}, failed={}",
                result.getSuccess(), result.getSkipped(), result.getFailed());
        return result;
    }

    @Override
    public byte[] exportWords() {
        List<SensitiveWord> list = sensitiveWordMapper.selectAllAlive();
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM：保证 Excel 正确识别中文
        sb.append('\uFEFF');
        sb.append("word,level,status\r\n");
        for (SensitiveWord item : list) {
            sb.append(csvField(item.getWord())).append(',')
              .append(item.getLevel() == null ? "" : item.getLevel()).append(',')
              .append(item.getStatus() == null ? "" : item.getStatus()).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 分片批量插入；单片撞唯一索引时回退为逐行插入（冲突行计入 skipped）
     */
    private void insertBatchTolerant(List<SensitiveWord> toInsert, ImportResultVO result) {
        for (int from = 0; from < toInsert.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, toInsert.size());
            List<SensitiveWord> part = toInsert.subList(from, to);
            try {
                sensitiveWordMapper.insertBatch(part);
                result.setSuccess(result.getSuccess() + part.size());
            } catch (DuplicateKeyException e) {
                for (SensitiveWord item : part) {
                    try {
                        sensitiveWordMapper.insert(item);
                        result.setSuccess(result.getSuccess() + 1);
                    } catch (DuplicateKeyException dup) {
                        result.setSkipped(result.getSkipped() + 1);
                    }
                }
            }
        }
    }

    /**
     * CSV 字段转义：防公式注入（=+-@ 开头前置单引号）+ 含分隔符/引号时加引号
     */
    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }

    private void checkLevelStatus(Integer level, Integer status) {
        if (level == null || level < 1 || level > 3 || status == null || status < 0 || status > 1) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
    }
}
