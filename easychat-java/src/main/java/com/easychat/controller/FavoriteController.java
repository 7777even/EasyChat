package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.FavoriteVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.FavoriteService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 收藏控制器
 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController extends ABaseController {

    @Resource
    private FavoriteService favoriteService;

    /**
     * 添加收藏
     */
    @PostMapping("/add")
    @GlobalInterceptor
    public Result<Void> addFavorite(HttpServletRequest request,
                                   @NotNull Long messageId,
                                   String content,
                                   String filePath) {
        TokenUserInfoDto userInfo = getTokenUserInfo(request);
        favoriteService.addFavorite(userInfo.getUserId(), messageId, content, filePath);
        return success();
    }

    /**
     * 取消收藏
     */
    @PostMapping("/cancel")
    @GlobalInterceptor
    public Result<Void> cancelFavorite(HttpServletRequest request, @NotNull Long favoriteId) {
        TokenUserInfoDto userInfo = getTokenUserInfo(request);
        favoriteService.cancelFavorite(userInfo.getUserId(), favoriteId);
        return success();
    }

    /**
     * 查询收藏列表
     */
    @GetMapping("/list")
    @GlobalInterceptor
    public Result<List<FavoriteVO>> listFavorite(HttpServletRequest request) {
        TokenUserInfoDto userInfo = getTokenUserInfo(request);
        List<FavoriteVO> list = favoriteService.listFavorite(userInfo.getUserId());
        return success(list);
    }
}
