package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.po.SensitiveWord;
import com.easychat.entity.query.SensitiveWordQuery;
import com.easychat.entity.vo.ImportResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.entity.vo.SensitiveWordVO;
import com.easychat.service.SensitiveWordAdminService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

/**
 * 敏感词库管理端（管理员专属）
 * - 五端点均 checkAdmin，非管理员 -> CODE_1003
 * - 写操作（保存/删除/导入）由 Service 侧自动 reload 热更
 */
@Validated
@RestController("adminSensitiveWordController")
@RequestMapping("/admin/sensitiveWord")
public class AdminSensitiveWordController extends ABaseController {

    @Resource
    private SensitiveWordAdminService sensitiveWordAdminService;

    /**
     * 词条分页列表（keyword/level/status 过滤）
     */
    @PostMapping("/loadWord")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO<SensitiveWordVO>> loadWord(SensitiveWordQuery query) {
        return success(sensitiveWordAdminService.loadWord(query));
    }

    /**
     * 新增/编辑词条（带 id 即编辑）
     */
    @PostMapping("/saveWord")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> saveWord(Long id,
                                 @NotEmpty String word,
                                 @NotNull Integer level,
                                 @NotNull Integer status) {
        SensitiveWord bean = new SensitiveWord();
        bean.setId(id);
        bean.setWord(word);
        bean.setLevel(level);
        bean.setStatus(status);
        sensitiveWordAdminService.saveWord(bean);
        return success();
    }

    /**
     * 逻辑删除词条
     */
    @PostMapping("/deleteWord")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> deleteWord(@NotNull Long id) {
        sensitiveWordAdminService.deleteWord(id);
        return success();
    }

    /**
     * 批量导入（.txt 统一级别状态 / .csv 三列 word,level,status）
     */
    @PostMapping("/importWords")
    @GlobalInterceptor(checkAdmin = true)
    public Result<ImportResultVO> importWords(MultipartFile file, Integer level, Integer status) {
        return success(sensitiveWordAdminService.importWords(file, level, status));
    }

    /**
     * 导出词库 CSV（文件流，非 Result 包络，对齐 downloadFile 先例）
     */
    @GetMapping("/exportWords")
    @GlobalInterceptor(checkAdmin = true)
    public void exportWords(HttpServletResponse response) throws Exception {
        byte[] data = sensitiveWordAdminService.exportWords();
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=sensitive-words.csv");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }
}
