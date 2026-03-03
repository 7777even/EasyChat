package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentComment;
import com.easychat.entity.po.MomentLike;
import com.easychat.entity.po.MomentMedia;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.MomentCommentQuery;
import com.easychat.entity.query.MomentLikeQuery;
import com.easychat.entity.query.MomentMediaQuery;
import com.easychat.entity.query.MomentQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.MomentCommentVO;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.entity.vo.MomentLikeVO;
import com.easychat.entity.vo.MomentVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.MomentCommentMapper;
import com.easychat.mappers.MomentLikeMapper;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentMediaMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.MomentService;
import com.easychat.utils.StringTools;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service("momentService")
public class MomentServiceImpl implements MomentService {

    @Resource
    private MomentMapper<Moment, MomentQuery> momentMapper;
    @Resource
    private MomentMediaMapper<MomentMedia, MomentMediaQuery> momentMediaMapper;
    @Resource
    private MomentLikeMapper<MomentLike, MomentLikeQuery> momentLikeMapper;
    @Resource
    private MomentCommentMapper<MomentComment, MomentCommentQuery> momentCommentMapper;
    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;
    @Resource
    private RedisComponet redisComponet;

    @Override
    public MomentVO publish(String content, Integer visibility, String visibleList, String invisibleList, String location, TokenUserInfoDto tokenUserInfoDto) {
        if (StringTools.isEmpty(content)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Integer safeVisibility = visibility == null ? 0 : visibility;
        if (safeVisibility < 0 || safeVisibility > 4) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Long now = System.currentTimeMillis();
        Moment moment = new Moment();
        moment.setUserId(tokenUserInfoDto.getUserId());
        moment.setContent(content);
        moment.setMediaType(0);
        moment.setLocation(location);
        moment.setVisibility(safeVisibility);
        moment.setVisibleList(visibleList);
        moment.setInvisibleList(invisibleList);
        moment.setStatus(1);
        moment.setCreateTime(now);
        moment.setUpdateTime(now);
        momentMapper.insert(moment);
        Map<String, UserInfo> userCache = new HashMap<>();
        Map<String, Set<String>> contactCache = new HashMap<>();
        return buildMomentVO(moment, tokenUserInfoDto.getUserId(), userCache, contactCache);
    }

    @Override
    public List<MomentVO> loadMomentList(TokenUserInfoDto tokenUserInfoDto, Integer pageNo, Integer pageSize) {
        int realPageNo = pageNo == null || pageNo <= 0 ? 1 : pageNo;
        int realPageSize = pageSize == null || pageSize <= 0 ? PageSize.SIZE20.getSize() : Math.min(pageSize, PageSize.SIZE40.getSize());
        MomentQuery query = new MomentQuery();
        query.setStatus(1);
        query.setOrderBy("create_time desc");
        query.setSimplePage(new SimplePage((realPageNo - 1) * realPageSize, realPageSize));
        List<Moment> dataList = momentMapper.selectList(query);
        List<MomentVO> resultList = new ArrayList<>();
        Map<String, UserInfo> userCache = new HashMap<>();
        Map<String, Set<String>> contactCache = new HashMap<>();
        for (Moment item : dataList) {
            if (!canView(item, tokenUserInfoDto.getUserId(), contactCache)) {
                continue;
            }
            resultList.add(buildMomentVO(item, tokenUserInfoDto.getUserId(), userCache, contactCache));
        }
        return resultList;
    }

    @Override
    public MomentLikeResultVO likeOrCancel(Long momentId, boolean cancel, TokenUserInfoDto tokenUserInfoDto) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || moment.getStatus() == null || moment.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        //校验可见
        Map<String, Set<String>> contactCache = new HashMap<>();
        if (!canView(moment, tokenUserInfoDto.getUserId(), contactCache)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        MomentLikeQuery query = new MomentLikeQuery();
        query.setMomentId(momentId);
        query.setUserId(tokenUserInfoDto.getUserId());
        List<MomentLike> existingList = momentLikeMapper.selectList(query);
        if (cancel) {
            if (!existingList.isEmpty()) {
                momentLikeMapper.deleteByParam(query);
            }
        } else if (existingList.isEmpty()) {
            MomentLike momentLike = new MomentLike();
            momentLike.setMomentId(momentId);
            momentLike.setUserId(tokenUserInfoDto.getUserId());
            momentLike.setCreateTime(System.currentTimeMillis());
            momentLikeMapper.insert(momentLike);
        }
        return buildLikeResult(momentId, tokenUserInfoDto.getUserId(), new HashMap<>());
    }

    @Override
    public MomentCommentVO addComment(Long momentId, String content, Long parentId, String replyToUserId, TokenUserInfoDto tokenUserInfoDto) {
        if (StringTools.isEmpty(content)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || moment.getStatus() == null || moment.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Map<String, Set<String>> contactCache = new HashMap<>();
        if (!canView(moment, tokenUserInfoDto.getUserId(), contactCache)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        Long now = System.currentTimeMillis();
        MomentComment comment = new MomentComment();
        comment.setMomentId(momentId);
        comment.setUserId(tokenUserInfoDto.getUserId());
        comment.setParentId(parentId == null ? 0L : parentId);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(content);
        comment.setStatus(1);
        comment.setCreateTime(now);
        momentCommentMapper.insert(comment);
        Map<String, UserInfo> userCache = new HashMap<>();
        userCache.put(tokenUserInfoDto.getUserId(), copyUserInfo(tokenUserInfoDto));
        MomentCommentVO vo = buildCommentVO(comment, userCache);
        if (!StringTools.isEmpty(replyToUserId)) {
            UserInfo replyUser = loadUserInfo(replyToUserId, userCache);
            if (replyUser != null) {
                vo.setReplyToNickName(replyUser.getNickName());
            }
        }
        return vo;
    }

    private MomentVO buildMomentVO(Moment moment, String viewerId, Map<String, UserInfo> userCache, Map<String, Set<String>> contactCache) {
        MomentVO vo = new MomentVO();
        vo.setId(moment.getId());
        vo.setUserId(moment.getUserId());
        UserInfo author = loadUserInfo(moment.getUserId(), userCache);
        vo.setNickName(author == null ? "" : author.getNickName());
        vo.setContent(moment.getContent());
        vo.setMediaType(moment.getMediaType());
        vo.setLocation(moment.getLocation());
        vo.setVisibility(moment.getVisibility());
        vo.setCreateTime(moment.getCreateTime());

        //媒体
        MomentMediaQuery mediaQuery = new MomentMediaQuery();
        mediaQuery.setMomentId(moment.getId());
        List<MomentMedia> mediaList = momentMediaMapper.selectList(mediaQuery);
        vo.setMediaList(mediaList);

        //点赞
        vo.setLikeList(buildLikeList(moment.getId(), userCache));
        vo.setLikedByMe(vo.getLikeList().stream().anyMatch(item -> viewerId.equals(item.getUserId())));

        //评论
        vo.setCommentList(buildCommentList(moment.getId(), userCache));
        return vo;
    }

    private List<MomentLikeVO> buildLikeList(Long momentId, Map<String, UserInfo> userCache) {
        MomentLikeQuery likeQuery = new MomentLikeQuery();
        likeQuery.setMomentId(momentId);
        likeQuery.setOrderBy("create_time asc");
        List<MomentLike> likeList = momentLikeMapper.selectList(likeQuery);
        List<MomentLikeVO> result = new ArrayList<>();
        for (MomentLike like : likeList) {
            MomentLikeVO vo = new MomentLikeVO();
            vo.setUserId(like.getUserId());
            UserInfo userInfo = loadUserInfo(like.getUserId(), userCache);
            vo.setNickName(userInfo == null ? "" : userInfo.getNickName());
            vo.setCreateTime(like.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private List<MomentCommentVO> buildCommentList(Long momentId, Map<String, UserInfo> userCache) {
        MomentCommentQuery commentQuery = new MomentCommentQuery();
        commentQuery.setMomentId(momentId);
        commentQuery.setStatus(1);
        commentQuery.setOrderBy("create_time asc");
        List<MomentComment> commentList = momentCommentMapper.selectList(commentQuery);
        List<MomentCommentVO> result = new ArrayList<>();
        for (MomentComment comment : commentList) {
            MomentCommentVO vo = buildCommentVO(comment, userCache);
            if (!StringTools.isEmpty(comment.getReplyToUserId())) {
                UserInfo replyUser = loadUserInfo(comment.getReplyToUserId(), userCache);
                vo.setReplyToNickName(replyUser == null ? "" : replyUser.getNickName());
            }
            result.add(vo);
        }
        return result;
    }

    private MomentCommentVO buildCommentVO(MomentComment comment, Map<String, UserInfo> userCache) {
        MomentCommentVO vo = new MomentCommentVO();
        vo.setId(comment.getId());
        vo.setMomentId(comment.getMomentId());
        vo.setUserId(comment.getUserId());
        UserInfo userInfo = loadUserInfo(comment.getUserId(), userCache);
        vo.setNickName(userInfo == null ? "" : userInfo.getNickName());
        vo.setParentId(comment.getParentId());
        vo.setReplyToUserId(comment.getReplyToUserId());
        vo.setContent(comment.getContent());
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }

    private MomentLikeResultVO buildLikeResult(Long momentId, String userId, Map<String, UserInfo> userCache) {
        List<MomentLikeVO> likeList = buildLikeList(momentId, userCache);
        MomentLikeResultVO resultVO = new MomentLikeResultVO();
        resultVO.setLikeList(likeList);
        resultVO.setLikeCount(likeList.size());
        resultVO.setLiked(likeList.stream().anyMatch(item -> userId.equals(item.getUserId())));
        return resultVO;
    }

    private UserInfo loadUserInfo(String userId, Map<String, UserInfo> userCache) {
        if (userCache.containsKey(userId)) {
            return userCache.get(userId);
        }
        UserInfo userInfo = userInfoMapper.selectByUserId(userId);
        if (userInfo != null) {
            userCache.put(userId, userInfo);
        }
        return userInfo;
    }

    private boolean canView(Moment moment, String viewerId, Map<String, Set<String>> contactCache) {
        if (moment.getVisibility() == null) {
            return true;
        }
        Integer visibility = moment.getVisibility();
        if (moment.getUserId().equals(viewerId)) {
            return true;
        }
        switch (visibility) {
            case 0:
                return true;
            case 1:
                return isFriend(moment.getUserId(), viewerId, contactCache);
            case 2:
                return false;
            case 3:
                List<String> visible = parseList(moment.getVisibleList());
                return visible.contains(viewerId);
            case 4:
                List<String> invisible = parseList(moment.getInvisibleList());
                return !invisible.contains(viewerId);
            default:
                return false;
        }
    }

    private boolean isFriend(String ownerId, String viewerId, Map<String, Set<String>> contactCache) {
        Set<String> contactSet = contactCache.get(ownerId);
        if (contactSet == null) {
            List<String> contacts = redisComponet.getUserContactList(ownerId);
            if (contacts == null) {
                contacts = new ArrayList<>();
            }
            contactSet = contacts.stream()
                    .filter(item -> !StringTools.isEmpty(item) && UserContactTypeEnum.USER.getPrefix().equals(item.substring(0, 1)))
                    .collect(Collectors.toSet());
            contactCache.put(ownerId, contactSet);
        }
        return contactSet.contains(viewerId);
    }

    private List<String> parseList(String json) {
        if (StringTools.isEmpty(json)) {
            return new ArrayList<>();
        }
        String temp = json.trim();
        if (temp.startsWith("[") && temp.endsWith("]")) {
            temp = temp.substring(1, temp.length() - 1);
        }
        if (StringTools.isEmpty(temp)) {
            return new ArrayList<>();
        }
        String[] arr = temp.split(",");
        Set<String> set = new HashSet<>();
        for (String s : arr) {
            if (!StringTools.isEmpty(s)) {
                set.add(s.trim().replaceAll("\"", ""));
            }
        }
        return new ArrayList<>(set);
    }

    private UserInfo copyUserInfo(TokenUserInfoDto tokenUserInfoDto) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(tokenUserInfoDto.getUserId());
        userInfo.setNickName(tokenUserInfoDto.getNickName());
        return userInfo;
    }
}


