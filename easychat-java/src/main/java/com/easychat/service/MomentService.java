package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.MomentCommentVO;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.entity.vo.MomentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 朋友圈业务接口
 */
public interface MomentService {

    /**
     * 发布朋友圈
     *
     * @param content       文本内容
     * @param visibility    可见范围 0公开 1好友 2私密 3白名单 4黑名单
     * @param visibleList   白名单/分组 JSON
     * @param invisibleList 黑名单 JSON
     * @param location      地理位置
     */
    MomentVO publish(String content, Integer visibility, String visibleList, String invisibleList, String location, TokenUserInfoDto tokenUserInfoDto);

    /**
     * 查询可见的朋友圈列表
     */
    List<MomentVO> loadMomentList(TokenUserInfoDto tokenUserInfoDto, Integer pageNo, Integer pageSize);

    /**
     * 点赞/取消点赞
     */
    MomentLikeResultVO likeOrCancel(Long momentId, boolean cancel, TokenUserInfoDto tokenUserInfoDto);

    /**
     * 新增评论
     */
    MomentCommentVO addComment(Long momentId, String content, Long parentId, String replyToUserId, TokenUserInfoDto tokenUserInfoDto);

    /**
     * 上传朋友圈媒体文件
     */
    String uploadMedia(Long momentId, MultipartFile file, Integer mediaType, TokenUserInfoDto tokenUserInfoDto);
}


