package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
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
import com.easychat.service.MomentNotifyService;
import com.easychat.service.MomentService;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service("momentService")
public class MomentServiceImpl implements MomentService {

    private static final Logger logger = LoggerFactory.getLogger(MomentServiceImpl.class);

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
    @Resource
    private AppConfig appConfig;
    @Resource
    private MomentNotifyService momentNotifyService;

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
        // 朋友圈通知：新动态推给可见范围内的好友（私密动态不通知）
        pushNewMomentNotify(moment, tokenUserInfoDto);
        Map<String, UserInfo> userCache = new HashMap<>();
        Map<String, Set<String>> contactCache = new HashMap<>();
        return buildMomentVO(moment, tokenUserInfoDto.getUserId(), userCache, contactCache);
    }

    /**
     * 新动态通知：给可见范围内的好友各写一条 type=0 通知并推 WS 帧。
     * 私密（visibility=2）不通知；白名单（3）只通知白名单内；黑名单过滤（4）排除名单内。
     */
    private void pushNewMomentNotify(Moment moment, TokenUserInfoDto tokenUserInfoDto) {
        Integer visibility = moment.getVisibility() == null ? 0 : moment.getVisibility();
        if (visibility == 2) {
            return;
        }
        List<String> contactList = redisComponet.getUserContactList(tokenUserInfoDto.getUserId());
        if (contactList == null || contactList.isEmpty()) {
            return;
        }
        List<String> visible = parseList(moment.getVisibleList());
        List<String> invisible = parseList(moment.getInvisibleList());
        String brief = moment.getContent() == null ? "" : moment.getContent();
        for (String contactId : contactList) {
            if (StringTools.isEmpty(contactId)
                    || !UserContactTypeEnum.USER.getPrefix().equals(contactId.substring(0, 1))) {
                continue;
            }
            if (visibility == 3 && !visible.contains(contactId)) {
                continue;
            }
            if (visibility == 4 && invisible.contains(contactId)) {
                continue;
            }
            momentNotifyService.pushNotify(contactId, 0, moment.getId(), tokenUserInfoDto.getUserId(),
                    tokenUserInfoDto.getNickName() + "发布了一条新动态：" + brief);
        }
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
            // 朋友圈通知：点赞通知动态作者
            momentNotifyService.pushNotify(moment.getUserId(), 1, momentId,
                    tokenUserInfoDto.getUserId(), tokenUserInfoDto.getNickName() + "赞了你的动态");
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

        // 朋友圈通知：评论通知动态作者；回复他人时额外通知被回复人
        String brief = content.length() > 30 ? content.substring(0, 30) + "…" : content;
        momentNotifyService.pushNotify(moment.getUserId(), 2, momentId,
                tokenUserInfoDto.getUserId(), tokenUserInfoDto.getNickName() + "评论你的动态：" + brief);
        if (!StringTools.isEmpty(replyToUserId) && !replyToUserId.equals(moment.getUserId())) {
            momentNotifyService.pushNotify(replyToUserId, 3, comment.getId(),
                    tokenUserInfoDto.getUserId(), tokenUserInfoDto.getNickName() + "回复了你的评论：" + brief);
        }
        // @ 提及：内容里形如 "@Uxxxx" 的用户收到 @ 提醒
        for (String atUserId : parseAtUserIds(content)) {
            if (atUserId.equals(moment.getUserId()) || atUserId.equals(replyToUserId)) {
                continue;
            }
            momentNotifyService.pushNotify(atUserId, 4, momentId,
                    tokenUserInfoDto.getUserId(), tokenUserInfoDto.getNickName() + "在评论中@了你");
        }

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

    /**
     * 解析文本中的 @ 提及用户：约定格式为 "@Uxxxxxxxx"（@ 后紧跟用户 ID）
     */
    private List<String> parseAtUserIds(String content) {
        List<String> result = new ArrayList<>();
        if (StringTools.isEmpty(content)) {
            return result;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("@(" + UserContactTypeEnum.USER.getPrefix() + "[A-Za-z0-9]+)")
                .matcher(content);
        while (matcher.find()) {
            String userId = matcher.group(1);
            if (!result.contains(userId)) {
                result.add(userId);
            }
        }
        return result;
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

    @Override
    public String uploadMedia(Long momentId, MultipartFile file, Integer mediaType, TokenUserInfoDto tokenUserInfoDto) {
        logger.info("开始上传朋友圈媒体文件, momentId: {}, mediaType: {}, fileName: {}", momentId, mediaType, file.getOriginalFilename());
        
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || !moment.getUserId().equals(tokenUserInfoDto.getUserId())) {
            logger.error("朋友圈不存在或无权限, momentId: {}, userId: {}", momentId, tokenUserInfoDto.getUserId());
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        
        try {
            String momentFolderName = Constants.FILE_FOLDER_FILE + "moment/";
            File folder = new File(appConfig.getProjectFolder() + momentFolderName);
            if (!folder.exists()) {
                folder.mkdirs();
                logger.info("创建朋友圈文件夹: {}", folder.getAbsolutePath());
            }
            
            String originalFilename = file.getOriginalFilename();
            String fileExtName = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtName = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            
            Long mediaId = System.currentTimeMillis();
            String fileName = momentId + "_" + mediaId + fileExtName;
            String filePath = fileName;
            
            File uploadFile = new File(folder, fileName);
            file.transferTo(uploadFile);
            logger.info("文件保存成功: {}", uploadFile.getAbsolutePath());
            
            MomentMedia momentMedia = new MomentMedia();
            momentMedia.setMomentId(momentId);
            momentMedia.setFilePath(filePath);
            momentMedia.setMediaType(mediaType == null ? 0 : mediaType);
            
            Integer result = momentMediaMapper.insert(momentMedia);
            logger.info("数据库插入结果: {}, momentMedia: momentId={}, filePath={}, mediaType={}", 
                result, momentMedia.getMomentId(), momentMedia.getFilePath(), momentMedia.getMediaType());
            
            return filePath;
        } catch (Exception e) {
            logger.error("文件上传失败", e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteMoment(Long momentId, TokenUserInfoDto tokenUserInfoDto) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || moment.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        
        // 只有作者本人可以删除
        if (!moment.getUserId().equals(tokenUserInfoDto.getUserId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        
        // 软删除：更新状态为0
        moment.setStatus(0);
        moment.setUpdateTime(System.currentTimeMillis());
        momentMapper.updateById(moment, momentId);
        
        logger.info("删除朋友圈成功, momentId: {}, userId: {}", momentId, tokenUserInfoDto.getUserId());
    }

    @Override
    public void deleteComment(Long commentId, TokenUserInfoDto tokenUserInfoDto) {
        MomentComment comment = momentCommentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() == null || comment.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_2501);
        }
        Moment moment = momentMapper.selectById(comment.getMomentId());
        if (moment == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2501);
        }
        // 评论本人或动态作者可删除
        boolean isCommentOwner = comment.getUserId().equals(tokenUserInfoDto.getUserId());
        boolean isMomentOwner = moment.getUserId().equals(tokenUserInfoDto.getUserId());
        if (!isCommentOwner && !isMomentOwner) {
            throw new BusinessException(ResponseCodeEnum.CODE_2502);
        }
        comment.setStatus(0);
        momentCommentMapper.updateById(comment, commentId);
    }

    @Override
    public List<MomentVO> loadUserMomentList(String targetUserId, TokenUserInfoDto tokenUserInfoDto, Integer pageNo, Integer pageSize) {
        int realPageNo = pageNo == null || pageNo <= 0 ? 1 : pageNo;
        int realPageSize = pageSize == null || pageSize <= 0 ? PageSize.SIZE20.getSize() : Math.min(pageSize, PageSize.SIZE40.getSize());
        MomentQuery query = new MomentQuery();
        query.setUserId(targetUserId);
        query.setStatus(1);
        query.setOrderBy("create_time desc");
        query.setSimplePage(new SimplePage((realPageNo - 1) * realPageSize, realPageSize));
        List<Moment> dataList = momentMapper.selectList(query);
        List<MomentVO> result = new ArrayList<>();
        Map<String, UserInfo> userCache = new HashMap<>();
        Map<String, Set<String>> contactCache = new HashMap<>();
        for (Moment item : dataList) {
            if (!canView(item, tokenUserInfoDto.getUserId(), contactCache)) {
                continue;
            }
            result.add(buildMomentVO(item, tokenUserInfoDto.getUserId(), userCache, contactCache));
        }
        return result;
    }

    @Override
    public MomentVO loadMomentDetail(Long momentId, TokenUserInfoDto tokenUserInfoDto) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || moment.getStatus() == null || moment.getStatus() == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_2501);
        }
        Map<String, Set<String>> contactCache = new HashMap<>();
        if (!canView(moment, tokenUserInfoDto.getUserId(), contactCache)) {
            throw new BusinessException(ResponseCodeEnum.CODE_2502);
        }
        Map<String, UserInfo> userCache = new HashMap<>();
        return buildMomentVO(moment, tokenUserInfoDto.getUserId(), userCache, contactCache);
    }

    /**
     * 获取朋友圈媒体分片临时目录
     */
    private File getMomentChunkTempFolder(String fileId, String userId) {
        String tempPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "temp/moment/" + userId + "/" + fileId;
        File folder = new File(tempPath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    @Override
    public void uploadMediaChunk(String fileId, Integer chunkIndex, Integer totalChunks,
                                 MultipartFile chunk, TokenUserInfoDto userInfoDto) {
        try {
            File chunkFolder = getMomentChunkTempFolder(fileId, userInfoDto.getUserId());
            File chunkFile = new File(chunkFolder, chunkIndex + ".chunk");
            
            chunk.transferTo(chunkFile);
            
            logger.info("朋友圈媒体分片上传成功: fileId={}, chunkIndex={}/{}, size={}", 
                    fileId, chunkIndex, totalChunks, chunk.getSize());
        } catch (Exception e) {
            logger.error("朋友圈媒体分片上传失败", e);
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }

    @Override
    public String mergeMediaChunks(String fileId, Long momentId, String fileName,
                                   Integer totalChunks, Integer mediaType, TokenUserInfoDto userInfoDto) {
        File chunkFolder = getMomentChunkTempFolder(fileId, userInfoDto.getUserId());
        
        try {
            // 验证所有分片是否存在
            for (int i = 0; i < totalChunks; i++) {
                File chunkFile = new File(chunkFolder, i + ".chunk");
                if (!chunkFile.exists()) {
                    throw new BusinessException("分片" + i + "不存在，无法合并");
                }
            }

            // 生成文件名
            String fileExtName = fileName.substring(fileName.lastIndexOf("."));
            String newFileName = StringTools.getRandomString(Constants.LENGTH_20) + fileExtName;
            
            // 创建目标文件目录
            String momentFolderName = Constants.FILE_FOLDER_FILE + "moment/";
            File targetFolder = new File(appConfig.getProjectFolder() + momentFolderName);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }
            
            File targetFile = new File(targetFolder, newFileName);
            
            // 合并分片
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile);
                 java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos)) {
                
                for (int i = 0; i < totalChunks; i++) {
                    File chunkFile = new File(chunkFolder, i + ".chunk");
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(chunkFile);
                         java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis)) {
                        
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = bis.read(buffer)) != -1) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                bos.flush();
            }

            logger.info("朋友圈媒体文件合并成功: fileId={}, momentId={}, targetFile={}", 
                    fileId, momentId, targetFile.getAbsolutePath());

            // 保存媒体记录
            String filePath = newFileName;
            MomentMedia momentMedia = new MomentMedia();
            momentMedia.setMomentId(momentId);
            momentMedia.setFilePath(filePath);
            momentMedia.setMediaType(mediaType);
            momentMediaMapper.insert(momentMedia);

            // 清理临时分片文件
            deleteChunkFolder(chunkFolder);

            return filePath;

        } catch (Exception e) {
            logger.error("朋友圈媒体文件合并失败", e);
            throw new BusinessException("文件合并失败: " + e.getMessage());
        }
    }

    @Override
    public List<Integer> checkMediaChunks(String fileId, Integer totalChunks, TokenUserInfoDto userInfoDto) {
        List<Integer> uploadedChunks = new ArrayList<>();
        File chunkFolder = getMomentChunkTempFolder(fileId, userInfoDto.getUserId());
        
        if (!chunkFolder.exists()) {
            return uploadedChunks;
        }

        for (int i = 0; i < totalChunks; i++) {
            File chunkFile = new File(chunkFolder, i + ".chunk");
            if (chunkFile.exists()) {
                uploadedChunks.add(i);
            }
        }

        logger.info("检查朋友圈媒体已上传分片: fileId={}, uploaded={}/{}", fileId, uploadedChunks.size(), totalChunks);
        return uploadedChunks;
    }

    /**
     * 删除分片临时文件夹
     */
    private void deleteChunkFolder(File folder) {
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            folder.delete();
            logger.info("清理朋友圈媒体临时分片文件夹: {}", folder.getAbsolutePath());
        }
    }
}


