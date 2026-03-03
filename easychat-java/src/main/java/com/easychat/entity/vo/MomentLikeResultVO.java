package com.easychat.entity.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 点赞操作结果
 */
public class MomentLikeResultVO implements Serializable {
    private Boolean liked;
    private Integer likeCount;
    private List<MomentLikeVO> likeList;

    public Boolean getLiked() {
        return liked;
    }

    public void setLiked(Boolean liked) {
        this.liked = liked;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public List<MomentLikeVO> getLikeList() {
        return likeList;
    }

    public void setLikeList(List<MomentLikeVO> likeList) {
        this.likeList = likeList;
    }
}


