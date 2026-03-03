package com.easychat.entity.vo;

import com.easychat.entity.po.MomentMedia;

import java.io.Serializable;
import java.util.List;

/**
 * 朋友圈动态视图
 */
public class MomentVO implements Serializable {
    private Long id;
    private String userId;
    private String nickName;
    private String content;
    private Integer mediaType;
    private String location;
    private Integer visibility;
    private Long createTime;
    private Boolean likedByMe;
    private List<MomentMedia> mediaList;
    private List<MomentLikeVO> likeList;
    private List<MomentCommentVO> commentList;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getMediaType() {
        return mediaType;
    }

    public void setMediaType(Integer mediaType) {
        this.mediaType = mediaType;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getVisibility() {
        return visibility;
    }

    public void setVisibility(Integer visibility) {
        this.visibility = visibility;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Boolean getLikedByMe() {
        return likedByMe;
    }

    public void setLikedByMe(Boolean likedByMe) {
        this.likedByMe = likedByMe;
    }

    public List<MomentMedia> getMediaList() {
        return mediaList;
    }

    public void setMediaList(List<MomentMedia> mediaList) {
        this.mediaList = mediaList;
    }

    public List<MomentLikeVO> getLikeList() {
        return likeList;
    }

    public void setLikeList(List<MomentLikeVO> likeList) {
        this.likeList = likeList;
    }

    public List<MomentCommentVO> getCommentList() {
        return commentList;
    }

    public void setCommentList(List<MomentCommentVO> commentList) {
        this.commentList = commentList;
    }
}


