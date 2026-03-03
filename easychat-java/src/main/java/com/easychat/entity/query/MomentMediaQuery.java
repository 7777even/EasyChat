package com.easychat.entity.query;

/**
 * 朋友圈媒体查询
 */
public class MomentMediaQuery extends BaseParam {

    private Long id;

    private Long momentId;

    private Integer mediaType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMomentId() {
        return momentId;
    }

    public void setMomentId(Long momentId) {
        this.momentId = momentId;
    }

    public Integer getMediaType() {
        return mediaType;
    }

    public void setMediaType(Integer mediaType) {
        this.mediaType = mediaType;
    }
}

