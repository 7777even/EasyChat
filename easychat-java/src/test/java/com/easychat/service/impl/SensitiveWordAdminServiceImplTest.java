package com.easychat.service.impl;

import com.easychat.entity.po.SensitiveWord;
import com.easychat.entity.query.SensitiveWordQuery;
import com.easychat.entity.vo.ImportResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.SensitiveWordVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.SensitiveWordMapper;
import com.easychat.service.SensitiveWordService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SensitiveWordAdminServiceImpl 单元测试。
 *
 * <p>敏感词库管理是内容治理的配置面：「词条
 * 非空且 ≤50 字 / 级别 1-3 / 状态 0-1」四重
 * 校验、「存活行唯一（已删行不占位）」、「写
 * 后热更 reload」、导入的「2MB / 5000 行 /
 * txt-csv 格式 / 行级容错（失败行不计成功）」
 * 与导出的「CSV 公式注入防护（=+-@ 前置单引
 * 号）」是其核心契约。此前为零覆盖
 * （system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class SensitiveWordAdminServiceImplTest {

    private static final long MAX_IMPORT_SIZE = 2 * 1024 * 1024L;

    @InjectMocks
    private SensitiveWordAdminServiceImpl sensitiveWordAdminService;

    @Mock
    private SensitiveWordMapper sensitiveWordMapper;

    @Mock
    private SensitiveWordService sensitiveWordService;

    private SensitiveWord wordOf(Long id, String word, Integer level, Integer status) {
        SensitiveWord bean = new SensitiveWord();
        bean.setId(id);
        bean.setWord(word);
        bean.setLevel(level);
        bean.setStatus(status);
        return bean;
    }

    // ======================== loadWord ========================

    @Test
    public void loadWord_success_defaultPageSize15() {
        when(sensitiveWordMapper.selectCount(any(SensitiveWordQuery.class))).thenReturn(3);
        when(sensitiveWordMapper.selectList(any(SensitiveWordQuery.class)))
                .thenReturn(Collections.singletonList(new SensitiveWordVO()));

        SensitiveWordQuery query = new SensitiveWordQuery();
        PaginationResultVO<SensitiveWordVO> result =
                sensitiveWordAdminService.loadWord(query);

        assertEquals(Integer.valueOf(3), result.getTotalCount());
        assertEquals(Integer.valueOf(15), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertNotNull(query.getSimplePage());
        assertEquals(1, result.getList().size());
    }

    // ======================== saveWord ========================

    @Test
    public void saveWord_empty_throws1001() {
        try {
            sensitiveWordAdminService.saveWord(wordOf(null, "  ", 1, 1));
            fail("词条为空应拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
    }

    @Test
    public void saveWord_tooLong_throws1001() {
        // 对齐 sensitive_word.word varchar(50)
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            longWord.append("词");
        }
        try {
            sensitiveWordAdminService.saveWord(wordOf(null, longWord.toString(), 1, 1));
            fail("词条超长应拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
    }

    @Test
    public void saveWord_invalidLevel_throws1001() {
        try {
            sensitiveWordAdminService.saveWord(wordOf(null, "词条", 0, 1));
            fail("非法级别应拒绝（仅 1-3）");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
    }

    @Test
    public void saveWord_editMissing_throws2705() {
        SensitiveWord bean = wordOf(5L, "词条", 1, 1);
        when(sensitiveWordMapper.selectAliveById(5L)).thenReturn(null);
        try {
            sensitiveWordAdminService.saveWord(bean);
            fail("编辑不存在的词条应抛 2705");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(2705), e.getCode());
        }
    }

    @Test
    public void saveWord_duplicate_throws2704() {
        SensitiveWord bean = wordOf(null, "词条", 1, 1);
        when(sensitiveWordMapper.selectAliveByWord("词条"))
                .thenReturn(wordOf(9L, "词条", 1, 1));
        try {
            sensitiveWordAdminService.saveWord(bean);
            fail("词条重复应抛 2704");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(2704), e.getCode());
        }
    }

    @Test
    public void saveWord_create_success_trimsAndInserts() {
        SensitiveWord bean = wordOf(null, " 词条 ", 2, 0);
        when(sensitiveWordMapper.selectAliveByWord("词条")).thenReturn(null);

        sensitiveWordAdminService.saveWord(bean);

        // 去除首尾空白后落库 + 服务端创建时间
        ArgumentCaptor<SensitiveWord> captor = ArgumentCaptor.forClass(SensitiveWord.class);
        verify(sensitiveWordMapper).insert(captor.capture());
        SensitiveWord inserted = captor.getValue();
        assertEquals("词条", inserted.getWord());
        assertEquals(Integer.valueOf(2), inserted.getLevel());
        assertEquals(Integer.valueOf(0), inserted.getStatus());
        assertNotNull("创建时间由服务端填充", inserted.getCreateTime());
        // 写后热更
        verify(sensitiveWordService).reload();
    }

    @Test
    public void saveWord_create_concurrentDuplicate_throws2704() {
        // 并发新增撞唯一索引 → 兜底转 2704
        SensitiveWord bean = wordOf(null, "词条", 1, 1);
        when(sensitiveWordMapper.selectAliveByWord("词条")).thenReturn(null);
        doThrow(new DuplicateKeyException("dup"))
                .when(sensitiveWordMapper).insert(any(SensitiveWord.class));
        try {
            sensitiveWordAdminService.saveWord(bean);
            fail("并发重复应抛 2704");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(2704), e.getCode());
        }
    }

    @Test
    public void saveWord_edit_success_updatesById() {
        SensitiveWord bean = wordOf(5L, "词条", 1, 1);
        when(sensitiveWordMapper.selectAliveById(5L)).thenReturn(wordOf(5L, "旧词", 1, 1));
        // 唯一行就是本条 → 不算重复
        when(sensitiveWordMapper.selectAliveByWord("词条")).thenReturn(wordOf(5L, "词条", 1, 1));

        sensitiveWordAdminService.saveWord(bean);

        verify(sensitiveWordMapper).updateById(any(SensitiveWord.class), eq(5L));
        verify(sensitiveWordMapper, never()).insert(any(SensitiveWord.class));
        verify(sensitiveWordService).reload();
    }

    // ======================== deleteWord ========================

    @Test
    public void deleteWord_missing_throws2705() {
        when(sensitiveWordMapper.selectAliveById(5L)).thenReturn(null);
        try {
            sensitiveWordAdminService.deleteWord(5L);
            fail("删除不存在的词条应抛 2705");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(2705), e.getCode());
        }
    }

    @Test
    public void deleteWord_success_logicDeletesAndReloads() {
        when(sensitiveWordMapper.selectAliveById(5L))
                .thenReturn(wordOf(5L, "词条", 1, 1));

        sensitiveWordAdminService.deleteWord(5L);

        // 逻辑删除：deleteFlag = 服务端时间戳
        verify(sensitiveWordMapper).deleteById(eq(5L), anyLong());
        verify(sensitiveWordService).reload();
    }

    // ======================== importWords：前置校验 ========================

    @Test
    public void importWords_emptyFile_throws1001() {
        try {
            sensitiveWordAdminService.importWords(
                    new MockMultipartFile("file", "words.txt",
                            "text/plain", new byte[0]), 1, 1);
            fail("空文件应拒绝");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
    }

    @Test
    public void importWords_oversize_throwsWithMessage() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(MAX_IMPORT_SIZE + 1);
        try {
            sensitiveWordAdminService.importWords(file, 1, 1);
            fail("超限文件应拒绝");
        } catch (BusinessException e) {
            assertEquals("导入文件不能超过 2MB", e.getMessage());
        }
    }

    @Test
    public void importWords_badSuffix_throwsWithMessage() {
        MockMultipartFile file = new MockMultipartFile("file", "words.xlsx",
                "application/octet-stream", "词".getBytes(StandardCharsets.UTF_8));
        try {
            sensitiveWordAdminService.importWords(file, 1, 1);
            fail("非 txt/csv 应拒绝");
        } catch (BusinessException e) {
            assertEquals("仅支持 .txt / .csv 文件", e.getMessage());
        }
    }

    @Test
    public void importWords_overLineLimit_throwsWithMessage() {
        // 5001 行 > 5000 行上限
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            content.append("词").append(i).append('\n');
        }
        MockMultipartFile file = new MockMultipartFile("file", "words.txt",
                "text/plain", content.toString().getBytes(StandardCharsets.UTF_8));
        try {
            sensitiveWordAdminService.importWords(file, 1, 1);
            fail("超行数应拒绝");
        } catch (BusinessException e) {
            assertEquals("导入行数不能超过 5000 行", e.getMessage());
        }
    }

    // ======================== importWords：解析与容错 ========================

    @Test
    public void importWords_txt_success_insertsBatchAndReloads() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        MockMultipartFile file = new MockMultipartFile("file", "words.txt",
                "text/plain", "词一\n词二\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, 1, 1);

        assertEquals(2, result.getSuccess());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SensitiveWord>> captor = ArgumentCaptor.forClass(List.class);
        verify(sensitiveWordMapper).insertBatch(captor.capture());
        List<SensitiveWord> inserted = captor.getValue();
        assertEquals(2, inserted.size());
        assertEquals("词一", inserted.get(0).getWord());
        assertEquals(Integer.valueOf(1), inserted.get(0).getLevel());
        assertEquals(Integer.valueOf(1), inserted.get(0).getStatus());
        assertNotNull(inserted.get(0).getCreateTime());
        verify(sensitiveWordService).reload();
    }

    @Test
    public void importWords_txt_skipsBlankFileDupAndAlive() {
        // 空行跳过、文件内重复计 skipped、库内存活词计 skipped
        when(sensitiveWordMapper.selectAllAlive())
                .thenReturn(Collections.singletonList(wordOf(1L, "词二", 1, 1)));
        MockMultipartFile file = new MockMultipartFile("file", "words.txt",
                "text/plain", "词一\n\n词一\n词二\n词三\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, 1, 1);

        assertEquals(2, result.getSuccess());
        assertEquals(2, result.getSkipped());
        assertEquals(0, result.getFailed());
    }

    @Test
    public void importWords_csv_headerSkipped() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        MockMultipartFile file = new MockMultipartFile("file", "words.csv",
                "text/csv", "word,level,status\r\n词一,1,1\r\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, null, null);

        // 表头行跳过；csv 自带级别 / 状态，无需调用方指定
        assertEquals(1, result.getSuccess());
        assertEquals(0, result.getFailed());
    }

    @Test
    public void importWords_csv_badColumns_failed() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        // 仅 2 列 → 行失败
        MockMultipartFile file = new MockMultipartFile("file", "words.csv",
                "text/csv", "词一,1\r\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, null, null);

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
        // 无可插入行 → 不触发热更
        verify(sensitiveWordService, never()).reload();
    }

    @Test
    public void importWords_csv_invalidLevel_failed() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        // 级别 9 超出 1-3 → 行失败
        MockMultipartFile file = new MockMultipartFile("file", "words.csv",
                "text/csv", "词一,9,1\r\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, null, null);

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
    }

    @Test
    public void importWords_csv_invalidStatus_failed() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        MockMultipartFile file = new MockMultipartFile("file", "words.csv",
                "text/csv", "词一,1,2\r\n".getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, null, null);

        assertEquals(0, result.getSuccess());
        assertEquals(1, result.getFailed());
    }

    @Test
    public void importWords_overBatchSize_splitsIntoMultipleBatches() {
        // 501 行超过单批 500 上限 → 分两批（500 + 1）插入
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            content.append("词").append(i).append('\n');
        }
        MockMultipartFile file = new MockMultipartFile("file", "words.txt",
                "text/plain", content.toString().getBytes(StandardCharsets.UTF_8));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, 1, 1);

        assertEquals(501, result.getSuccess());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        // 分批：第一批 500 行、第二批 1 行
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SensitiveWord>> captor = ArgumentCaptor.forClass(List.class);
        verify(sensitiveWordMapper, times(2)).insertBatch(captor.capture());
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(1, captor.getAllValues().get(1).size());
        verify(sensitiveWordService).reload();
    }

    @Test
    public void importWords_batchDuplicate_fallsBackToRowByRow() {
        // 整批撞唯一索引 → 回退逐行插入：首行成功、次行冲突计 skipped
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Collections.emptyList());
        MockMultipartFile file = new MockMultipartFile("file", "words.txt",
                "text/plain", "词一\n词二\n".getBytes(StandardCharsets.UTF_8));
        doThrow(new DuplicateKeyException("batch dup"))
                .when(sensitiveWordMapper).insertBatch(anyList());
        when(sensitiveWordMapper.insert(any(SensitiveWord.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("row dup"));

        ImportResultVO result = sensitiveWordAdminService.importWords(file, 1, 1);

        assertEquals(1, result.getSuccess());
        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
        // 回退路径同样触发热更
        verify(sensitiveWordService).reload();
    }

    // ======================== exportWords ========================

    @Test
    public void exportWords_formatWithFormulaInjectionGuard() {
        when(sensitiveWordMapper.selectAllAlive()).thenReturn(Arrays.asList(
                wordOf(1L, "=公式", 1, 1),
                wordOf(2L, "含,逗号", 2, 0),
                wordOf(3L, "普通", 3, 1)));

        byte[] bytes = sensitiveWordAdminService.exportWords();

        String csv = new String(bytes, StandardCharsets.UTF_8);
        // UTF-8 BOM + 表头
        assertTrue("应以 BOM 开头（Excel 识别中文）",
                csv.charAt(0) == '﻿');
        assertTrue(csv.startsWith("word,level,status\r\n", 1));
        // = 开头值前置单引号防公式注入
        assertTrue(csv.contains("'=公式,1,1\r\n"));
        // 含分隔符值加引号
        assertTrue(csv.contains("\"含,逗号\",2,0\r\n"));
        assertTrue(csv.contains("普通,3,1\r\n"));
    }
}
