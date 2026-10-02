package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.EmojiVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.EmojiService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 表情包控制器
 */
@RestController
@RequestMapping("/emoji")
public class EmojiController extends ABaseController {

    @Resource
    private EmojiService emojiService;

    /**
     * 查询表情包列表
     */
    @GetMapping("/list")
    @GlobalInterceptor
    public Result<List<EmojiVO>> listEmoji(HttpServletRequest request) {
        TokenUserInfoDto userInfo = getUserInfo(request);
        List<EmojiVO> list = emojiService.listEmoji(userInfo.getUserId());
        return Result.success(list);
    }

    /**
     * 上传表情包
     */
    @PostMapping("/upload")
    @GlobalInterceptor
    public Result<EmojiVO> uploadEmoji(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        TokenUserInfoDto userInfo = getUserInfo(request);
        EmojiVO emoji = emojiService.uploadEmoji(userInfo.getUserId(), file);
        return Result.success(emoji);
    }

    /**
     * 删除表情包
     */
    @PostMapping("/delete")
    @GlobalInterceptor
    public Result<Void> deleteEmoji(HttpServletRequest request, @RequestParam("emojiId") Long emojiId) {
        TokenUserInfoDto userInfo = getUserInfo(request);
        emojiService.deleteEmoji(userInfo.getUserId(), emojiId);
        return Result.success();
    }

    /**
     * 取当前登录用户。
     * <p>
     * 必须走 ABaseController#getTokenUserInfo（按 token 头查 Redis 会话），
     * 不能读 request.getAttribute("userInfo")——全局拦截器只做校验、从不写入该
     * attribute，读它恒为 null，会直接 NPE 变成 500（2026-10-02 修复）。
     */
    private TokenUserInfoDto getUserInfo(HttpServletRequest request) {
        return getTokenUserInfo(request);
    }
}
