package com.easychat.service.impl;

import com.easychat.entity.enums.SortOption;
import com.easychat.config.EasyChatProperties;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.*;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.entity.po.EmailVerifyCode;
import com.easychat.entity.query.*;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.mappers.EmailVerifyCodeMapper;
import com.easychat.mappers.UserInfoBeautyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.ChatSessionUserService;
import com.easychat.service.MailService;
import com.easychat.service.UserContactService;
import com.easychat.service.UserInfoService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.IdListTools;
import com.easychat.utils.SortWhitelistTools;
import com.easychat.utils.StringTools;
import com.easychat.websocket.MessageHandler;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * 业务接口实现
 */
@Service("userInfoService")
public class UserInfoServiceImpl implements UserInfoService {

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private AppConfig appConfig;

    @Resource
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private RedisComponet redisComponet;

    /** 2026-10-03 新增：邮箱验证码的真实投递通道，取代原先「写日志」的降级实现 */
    @Resource
    private MailService mailService;


    @Resource
    private ChatSessionUserService chatSessionUserService;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private UserContactService userContactService;

    @Resource
    private UserInfoBeautyMapper<UserInfoBeauty, UserInfoBeautyQuery> userInfoBeautyMapper;

    @Resource
    private EasyChatProperties easyChatProperties;

    @Resource
    private EmailVerifyCodeMapper<EmailVerifyCode, EmailVerifyCodeQuery> emailVerifyCodeMapper;

    @Resource
    private com.easychat.service.OperationLogService operationLogService;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UserInfoServiceImpl.class);

    /**
     * 根据条件查询列表
     */
    @Override
    public List<UserInfo> findListByParam(UserInfoQuery param) {
        return this.userInfoMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(UserInfoQuery param) {
        return this.userInfoMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<UserInfo> findListByPage(UserInfoQuery param) {
        // 排序白名单：/admin/loadUser 直接绑定 UserInfoQuery，调用方可指定排序但只能取
        // 白名单内的项；未指定则回填默认项（table 由服务端写死，不来自请求）
        SortWhitelistTools.resolveSort(param, "user_info");
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<UserInfo> list = this.findListByParam(param);
        PaginationResultVO<UserInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(UserInfo bean) {
        return this.userInfoMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<UserInfo> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userInfoMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<UserInfo> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userInfoMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(UserInfo bean, UserInfoQuery param) {
        StringTools.checkParam(param);
        return this.userInfoMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(UserInfoQuery param) {
        StringTools.checkParam(param);
        return this.userInfoMapper.deleteByParam(param);
    }

    /**
     * 根据UserId获取对象
     */
    @Override
    public UserInfo getUserInfoByUserId(String userId) {
        return this.userInfoMapper.selectByUserId(userId);
    }

    /**
     * 根据UserId修改
     */
    @Override
    public Integer updateUserInfoByUserId(UserInfo bean, String userId) {
        return this.userInfoMapper.updateByUserId(bean, userId);
    }

    /**
     * 根据UserId删除
     */
    @Override
    public Integer deleteUserInfoByUserId(String userId) {
        return this.userInfoMapper.deleteByUserId(userId);
    }

    /**
     * 根据Email获取对象
     */
    @Override
    public UserInfo getUserInfoByEmail(String email) {
        return this.userInfoMapper.selectByEmail(email);
    }

    /**
     * 根据Email修改
     */
    @Override
    public Integer updateUserInfoByEmail(UserInfo bean, String email) {
        return this.userInfoMapper.updateByEmail(bean, email);
    }

    /**
     * 根据Email删除
     */
    @Override
    public Integer deleteUserInfoByEmail(String email) {
        return this.userInfoMapper.deleteByEmail(email);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(String email, String nickName, String password) {
        UserInfo userInfo = this.userInfoMapper.selectByEmail(email);
        if (null != userInfo) {
            throw new BusinessException("邮箱账号已经存在");
        }
        Date curDate = new Date();
        String userId = StringTools.getUserId();

        //查询邮箱是否需要设置靓号
        UserInfoBeauty beautyAccount = this.userInfoBeautyMapper.selectByEmail(email);
        Boolean useBeautyAccount = null != beautyAccount && BeautyAccountStatusEnum.NO_USE.getStatus().equals(beautyAccount.getStatus());
        if (useBeautyAccount) {
            userId = UserContactTypeEnum.USER.getPrefix() + beautyAccount.getUserId();
        }
        userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setNickName(nickName);
        userInfo.setEmail(email);
        userInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(password));
        userInfo.setCreateTime(curDate);
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
        userInfo.setLastOffTime(curDate.getTime());
        this.userInfoMapper.insert(userInfo);
        //更新靓号状态
        if (useBeautyAccount) {
            UserInfoBeauty updateBeauty = new UserInfoBeauty();
            updateBeauty.setStatus(BeautyAccountStatusEnum.USEED.getStatus());
            this.userInfoBeautyMapper.updateById(updateBeauty, beautyAccount.getId());
        }
        //创建机器人好友
        userContactService.addContact4Robot(userId);
    }

    @Override
    public UserInfoVO login(String email, String password) {
        UserInfo userInfo = this.userInfoMapper.selectByEmail(email);
        if (null == userInfo) {
            throw new BusinessException("账号或者密码错误");
        }
        // 验证密码：支持 BCrypt 和 MD5 双验证
        boolean passwordValid = false;
        if (com.easychat.utils.PasswordEncoder.isBCrypt(userInfo.getPassword())) {
            // BCrypt 验证
            passwordValid = com.easychat.utils.PasswordEncoder.matches(password, userInfo.getPassword());
        } else {
            // MD5 验证（兼容旧密码）
            passwordValid = userInfo.getPassword().equals(StringTools.encodeByMD5(password));
        }
        if (!passwordValid) {
            // 记录登录失败日志
            operationLogService.recordLog(userInfo.getUserId(), "LOGIN_FAILED", "登录失败：密码错误", null);
            throw new BusinessException("账号或者密码错误");
        }
        if (UserStatusEnum.DISABLE.getStatus().equals(userInfo.getStatus())) {
            // 记录登录失败日志
            operationLogService.recordLog(userInfo.getUserId(), "LOGIN_FAILED", "登录失败：账号已禁用", null);
            throw new BusinessException("账号已禁用");
        }

        // 记录登录成功日志
        operationLogService.recordLog(userInfo.getUserId(), "LOGIN_SUCCESS", "登录成功", null);

        // 如果是 MD5 验证，自动升级为 BCrypt
        if (!com.easychat.utils.PasswordEncoder.isBCrypt(userInfo.getPassword())) {
            String bcryptPassword = com.easychat.utils.PasswordEncoder.encode(password);
            UserInfo updateInfo = new UserInfo();
            updateInfo.setPassword(bcryptPassword);
            userInfoMapper.updateByUserId(updateInfo, userInfo.getUserId());
            userInfo.setPassword(bcryptPassword);
        }

        //查询联系人
        UserContactQuery contactQuery = new UserContactQuery();
        contactQuery.setUserId(userInfo.getUserId());
        contactQuery.setStatusArray(new Integer[]{UserContactStatusEnum.FRIEND.getStatus()});
        List<UserContact> contactList = userContactMapper.selectList(contactQuery);
        List<String> contactIdList = contactList.stream().map(item -> item.getContactId()).collect(Collectors.toList());

        redisComponet.cleanUserContact(userInfo.getUserId());
        if (!contactIdList.isEmpty()) {
            redisComponet.addUserContactBatch(userInfo.getUserId(), contactIdList);
        }

        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfoDto(userInfo);
        // 登录策略：默认允许多端同时在线（与 openspec/specs/multi-device-sync 一致）；
        // 开启 easychat.login.single-device=true 时，新登录把旧设备挤下线而不是报错拒绝。
        if (Boolean.TRUE.equals(easyChatProperties.getLogin().getSingleDevice())) {
            Long lastHeartBeat = redisComponet.getUserHeartBeat(tokenUserInfoDto.getUserId());
            if (lastHeartBeat != null) {
                // 旧设备先踢下线（推 FORCE_OFF_LINE 帧并关闭其 WS 连接），新登录继续成功
                forceOffLine(tokenUserInfoDto.getUserId());
            }
        }

        //保存登录信息到redis中
        String token = StringTools.encodeByMD5(tokenUserInfoDto.getUserId() + StringTools.getRandomString(Constants.LENGTH_20));
        tokenUserInfoDto.setToken(token);
        redisComponet.saveTokenUserInfoDto(tokenUserInfoDto);

        UserInfoVO userInfoVO = CopyTools.copy(userInfo, UserInfoVO.class);
        userInfoVO.setToken(tokenUserInfoDto.getToken());
        userInfoVO.setAdmin(tokenUserInfoDto.getAdmin());
        return userInfoVO;
    }

    private TokenUserInfoDto getTokenUserInfoDto(UserInfo userInfo) {
        TokenUserInfoDto tokenUserInfoDto = new TokenUserInfoDto();
        tokenUserInfoDto.setUserId(userInfo.getUserId());
        tokenUserInfoDto.setNickName(userInfo.getNickName());

        // 管理员白名单统一读 easychat.admin-emails（EasyChatProperties）；
        // AppConfig 读的是不存在的 admin.emails，会导致 admin 恒为 false、管理端全部 404
        String adminEmails = easyChatProperties.getAdminEmails();
        if (!StringTools.isEmpty(adminEmails) && ArrayUtils.contains(adminEmails.split(","), userInfo.getEmail())) {
            tokenUserInfoDto.setAdmin(true);
        } else {
            tokenUserInfoDto.setAdmin(false);
        }
        return tokenUserInfoDto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserInfo(UserInfo userInfo, MultipartFile avatarFile, MultipartFile avatarCover) throws IOException {
        if (avatarFile != null) {
            String baseFolder = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE;
            File targetFileFolder = new File(baseFolder + Constants.FILE_FOLDER_AVATAR_NAME);
            if (!targetFileFolder.exists()) {
                targetFileFolder.mkdirs();
            }
            String filePath = targetFileFolder.getPath() + "/" + userInfo.getUserId() + Constants.IMAGE_SUFFIX;
            avatarFile.transferTo(new File(filePath));
            if (avatarCover != null) {
                avatarCover.transferTo(new File(filePath + Constants.COVER_IMAGE_SUFFIX));
            }
        }
        UserInfo dbInfo = this.userInfoMapper.selectByUserId(userInfo.getUserId());

        this.userInfoMapper.updateByUserId(userInfo, userInfo.getUserId());

        //更新相关表冗余的字段
        String contactNameUpdate = null;
        if (!dbInfo.getNickName().equals(userInfo.getNickName())) {
            contactNameUpdate = userInfo.getNickName();
        }
        if (contactNameUpdate == null) {
            return;
        }
        //更新token中的昵称
        TokenUserInfoDto tokenUserInfoDto = redisComponet.getTokenUserInfoDtoByUserId(userInfo.getUserId());
        tokenUserInfoDto.setNickName(contactNameUpdate);
        redisComponet.saveTokenUserInfoDto(tokenUserInfoDto);

        chatSessionUserService.updateRedundanceInfo(contactNameUpdate, userInfo.getUserId());
    }

    @Override
    public void updateUserStatus(Integer status, String userId) {
        UserStatusEnum userStatusEnum = UserStatusEnum.getByStatus(status);
        if (userStatusEnum == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        UserInfo updateInfo = new UserInfo();
        updateInfo.setStatus(userStatusEnum.getStatus());
        userInfoMapper.updateByUserId(updateInfo, userId);
    }

    @Override
    public void updatePassword(String userId, String oldPassword, String newPassword) {
        if (StringTools.isEmpty(oldPassword) || StringTools.isEmpty(newPassword)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        UserInfo dbInfo = userInfoMapper.selectByUserId(userId);
        if (dbInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }
        // 验证旧密码：支持 BCrypt 和 MD5 双验证
        boolean oldPasswordValid = false;
        if (com.easychat.utils.PasswordEncoder.isBCrypt(dbInfo.getPassword())) {
            oldPasswordValid = com.easychat.utils.PasswordEncoder.matches(oldPassword, dbInfo.getPassword());
        } else {
            oldPasswordValid = dbInfo.getPassword().equals(StringTools.encodeByMD5(oldPassword));
        }
        if (!oldPasswordValid) {
            throw new BusinessException(ResponseCodeEnum.CODE_2103);
        }
        // 验证新密码是否与原密码相同
        boolean samePassword = false;
        if (com.easychat.utils.PasswordEncoder.isBCrypt(dbInfo.getPassword())) {
            samePassword = com.easychat.utils.PasswordEncoder.matches(newPassword, dbInfo.getPassword());
        } else {
            samePassword = dbInfo.getPassword().equals(StringTools.encodeByMD5(newPassword));
        }
        if (samePassword) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        UserInfo updateInfo = new UserInfo();
        updateInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(newPassword));
        userInfoMapper.updateByUserId(updateInfo, userId);
        // 记录修改密码日志
        operationLogService.recordLog(userId, "UPDATE_PASSWORD", "修改密码", null);
        // 2026-10-03：改密视为「凭据可能已泄露」，清空该用户全部端 Token 并强制下线。
        // 必须放在密码写入成功之后——上面的旧密码校验 / 新旧相同校验失败都已在写库前抛异常，
        // 走不到这里，因此不存在「密码没改但 Token 被清」的窗口（design ADR-001）。
        // 副作用（刻意，与微信一致）：发起改密的当前会话也一并失效，用户需重新登录。
        redisComponet.cleanUserTokenByUserId(userId);
        forceOffLine(userId);
    }

    @Override
    public void sendEmailCode(String email, Integer type) {
        if (StringTools.isEmpty(email)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        // 找回密码时必须校验邮箱已注册；注册时则相反
        UserInfo userInfo = userInfoMapper.selectByEmail(email);
        if (type != null && type == 1) {
            if (userInfo == null) {
                throw new BusinessException(ResponseCodeEnum.CODE_2101);
            }
        } else if (userInfo != null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2102);
        }
        // 60 秒内不重复发送
        EmailVerifyCodeQuery query = new EmailVerifyCodeQuery();
        query.setEmail(email);
        query.setType(type == null ? 0 : type);
        query.setStatus(0);
        query.setSortOption(SortOption.EMAIL_VERIFY_CODE_CREATE_TIME_DESC);
        query.setSimplePage(new SimplePage(0, 1));
        List<EmailVerifyCode> latest = emailVerifyCodeMapper.selectList(query);
        long now = System.currentTimeMillis();
        if (!latest.isEmpty() && now - latest.get(0).getCreateTime() < 60 * 1000L) {
            throw new BusinessException("验证码已发送，请稍后再试");
        }
        String code = StringTools.getRandomNumber(Constants.LENGTH_6);
        EmailVerifyCode bean = new EmailVerifyCode();
        bean.setEmail(email);
        bean.setCode(code);
        bean.setType(type == null ? 0 : type);
        bean.setStatus(0);
        bean.setCreateTime(now);
        bean.setExpireTime(now + 10 * 60 * 1000L);
        emailVerifyCodeMapper.insert(bean);
        // 2026-10-03：改为通过 SMTP 真实投递。
        // 原实现在此处 logger.info("...code={}", code) 把验证码明文写进应用日志——
        // 日志读权限（容器 stdout / 日志聚合 / CI 归档）远宽于普通用户，
        // 等价于「谁能看到日志谁就能重置任意账号（含 admin）的密码」。
        // 未配置邮件服务时 MailService 会 fail-closed 抛 CODE_1002，不再退回打日志
        // （openspec/design.md ADR-002）。
        // 注意：先落库再发信，发信失败不回滚验证码记录——用户可重新点一次获取。
        mailService.sendVerifyCode(email, code, type == null ? 0 : type);
    }

    @Override
    public void resetPasswordByEmail(String email, String code, String newPassword) {        if (StringTools.isEmpty(email) || StringTools.isEmpty(code) || StringTools.isEmpty(newPassword)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        EmailVerifyCodeQuery query = new EmailVerifyCodeQuery();
        query.setEmail(email);
        query.setCode(code);
        query.setType(1);
        query.setStatus(0);
        query.setSortOption(SortOption.EMAIL_VERIFY_CODE_CREATE_TIME_DESC);
        query.setSimplePage(new SimplePage(0, 1));
        List<EmailVerifyCode> list = emailVerifyCodeMapper.selectList(query);
        if (list.isEmpty()) {
            throw new BusinessException("验证码错误");
        }
        EmailVerifyCode verifyCode = list.get(0);
        if (verifyCode.getExpireTime() != null && System.currentTimeMillis() > verifyCode.getExpireTime()) {
            throw new BusinessException("验证码已过期");
        }
        UserInfo userInfo = userInfoMapper.selectByEmail(email);
        if (userInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }
        UserInfo updateInfo = new UserInfo();
        updateInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(newPassword));
        userInfoMapper.updateByUserId(updateInfo, userInfo.getUserId());

        EmailVerifyCode used = new EmailVerifyCode();
        used.setStatus(1);
        EmailVerifyCodeQuery updateQuery = new EmailVerifyCodeQuery();
        updateQuery.setId(verifyCode.getId());
        emailVerifyCodeMapper.updateByParam(used, updateQuery);

        // 2026-10-03：重置密码同样视为凭据可能泄露，清空全部端 Token 并强制下线。
        // 必须放在**全部**校验（验证码存在 / 未过期 / 邮箱已注册）与写库之后：
        // 上面三条早退路径若在此之前就吊销，任何人输错验证码即可把受害者踢下线，
        // 反而成了拒绝服务（对应单测 resetPasswordByEmail_codeNotFound_doesNotInvalidateSessions）。
        redisComponet.cleanUserTokenByUserId(userInfo.getUserId());
        forceOffLine(userInfo.getUserId());
    }

    @Override
    public void forceOffLine(String userId) {
        MessageSendDto sendDto = new MessageSendDto();
        sendDto.setContactType(UserContactTypeEnum.USER.getType());
        sendDto.setMessageType(MessageTypeEnum.FORCE_OFF_LINE.getType());
        sendDto.setContactId(userId);
        messageHandler.sendMessage(sendDto);
        // 记录强制下线日志
        operationLogService.recordLog(userId, "FORCE_OFFLINE", "被强制下线", null);
    }

    // ==================== 加我方式 ====================

    /**
     * 更新「加我的方式」（join_type）
     * <p>
     * 修复前 join_type 无任何更新入口（{@code UserUpdateDTO} 不含该字段），
     * 用户看得到自己的设置却改不了。
     * <p>
     * 只写 join_type 一列：绝不构造带 nickName/password/status 的对象去 update，
     * 否则会把这几列一起覆盖成 null。
     *
     * @param userId   当前登录用户 id
     * @param joinType 0 直接加入 / 1 加我时需验证
     * @throws BusinessException joinType 非法 → CODE_1001；用户不存在 → CODE_2101
     * @since 2026-10-02 加我方式与黑名单管理（openspec/specs/privacy-settings）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateJoinType(String userId, Integer joinType) {
        if (!JoinTypeEnum.JOIN.getType().equals(joinType)
                && !JoinTypeEnum.APPLY.getType().equals(joinType)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        if (this.userInfoMapper.selectByUserId(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }
        // 只带 userId + joinType 两个字段，避免覆盖昵称/密码等
        UserInfo updateInfo = new UserInfo();
        updateInfo.setUserId(userId);
        updateInfo.setJoinType(joinType);
        this.userInfoMapper.updateByUserId(updateInfo, userId);
    }

    // ==================== 隐私设置 ====================

    /** 朋友圈可见范围：0 公开 / 1 仅好友 / 2 仅自己 / 3 白名单 / 4 黑名单 */
    private static final Integer MOMENT_VISIBILITY_PUBLIC = 0;
    private static final Integer MOMENT_VISIBILITY_FRIENDS = 1;
    private static final Integer MOMENT_VISIBILITY_SELF = 2;
    private static final Integer MOMENT_VISIBILITY_WHITE_LIST = 3;
    private static final Integer MOMENT_VISIBILITY_BLACK_LIST = 4;

    /**
     * 更新朋友圈可见范围（用户级默认）
     * <p>
     * <b>只作为发布朋友圈时的默认值</b>，不参与 {@code MomentServiceImpl#canView} 判定
     * （ADR-001：改用户级设置不追溯已发布的历史动态，与微信一致）。
     * <p>
     * 三条业务约束（缺一即 CODE_1001 且不落库）：
     * <ol>
     *   <li>{@code momentVisibility} 必须落在 0–4</li>
     *   <li>{@code =3} 必须给非空白名单；{@code =4} 必须给非空黑名单
     *       ——空名单会让「白名单=谁都看不到」或「黑名单=谁都能看」，属用户误操作</li>
     *   <li>名单中每个 id 都必须是当前用户的<b>好友</b>（一次性查好友集合做子集断言，非逐个查询）</li>
     * </ol>
     * {@code 0/1/2} 时不传名单则<b>不覆盖</b>已有名单列，便于用户切回 3/4 时名单还在。
     *
     * @throws BusinessException 上述任一约束不满足 → CODE_1001；用户不存在 → CODE_2101
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMomentPrivacy(String userId, Integer momentVisibility,
                                    String visibleList, String invisibleList) {
        if (momentVisibility == null
                || momentVisibility < MOMENT_VISIBILITY_PUBLIC
                || momentVisibility > MOMENT_VISIBILITY_BLACK_LIST) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        if (this.userInfoMapper.selectByUserId(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }

        // 只构造这 3 列，避免覆盖昵称/密码/加我方式
        UserInfo updateInfo = new UserInfo();
        updateInfo.setUserId(userId);
        updateInfo.setMomentVisibility(momentVisibility);

        if (MOMENT_VISIBILITY_WHITE_LIST.equals(momentVisibility)) {
            List<String> white = requireFriendSubset(userId, visibleList, "白名单");
            updateInfo.setMomentVisibleList(IdListTools.serialize(white));
        } else if (MOMENT_VISIBILITY_BLACK_LIST.equals(momentVisibility)) {
            List<String> black = requireFriendSubset(userId, invisibleList, "黑名单");
            updateInfo.setMomentInvisibleList(IdListTools.serialize(black));
        }
        // 0/1/2：不带名单列，保留原值（用户切回 3/4 时名单还在）
        this.userInfoMapper.updateByUserId(updateInfo, userId);
    }

    /**
     * 校验名单：合法 JSON 数组 → 非空 → 全部是好友。任一不满足抛 CODE_1001。
     *
     * @param label 名单名称，仅用于异常场景可读性
     * @return 去重后的 id 列表
     */
    private List<String> requireFriendSubset(String userId, String rawList, String label) {
        // 1) 严格 JSON 校验（非法格式 / 元素非字符串 / 超长 → CODE_1001）
        IdListTools.validate(rawList);
        List<String> ids = IdListTools.parse(rawList);
        if (ids.isEmpty()) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        // 2) 一次性查好友集合做子集断言（避免逐个主键查询）
        Set<String> friendSet = new HashSet<>();
        UserContactQuery friendQuery = new UserContactQuery();
        friendQuery.setUserId(userId);
        friendQuery.setContactType(UserContactTypeEnum.USER.getType());
        friendQuery.setStatusArray(new Integer[]{UserContactStatusEnum.FRIEND.getStatus()});
        List<UserContact> friends = this.userContactService.findListByParam(friendQuery);
        if (friends != null) {
            for (UserContact friend : friends) {
                if (friend != null && !StringTools.isEmpty(friend.getContactId())) {
                    friendSet.add(friend.getContactId());
                }
            }
        }
        for (String id : ids) {
            if (!friendSet.contains(id)) {
                logger.warn("隐私设置{}含非好友 id，userId={}, id={}", label, userId, id);
                throw new BusinessException(ResponseCodeEnum.CODE_1001);
            }
        }
        return ids;
    }

    /**
     * 更新「是否对好友展示在线状态」
     * <p>
     * 本方法<b>只落库</b>；推帧/停播由 Controller 按新旧值编排（置 0 推 ONLINE_STATUS_HIDDEN 抹除，
     * 置 1 立即广播当前状态）。拆开是为了让 Service 保持「只管数据」的单测友好。
     *
     * @throws BusinessException visible 非 0/1 → CODE_1001；用户不存在 → CODE_2101
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOnlineStatusVisible(String userId, Integer visible) {
        if (visible == null || (visible != 0 && visible != 1)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
        if (this.userInfoMapper.selectByUserId(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }
        // 只构造这 1 列
        UserInfo updateInfo = new UserInfo();
        updateInfo.setUserId(userId);
        updateInfo.setOnlineStatusVisible(visible);
        this.userInfoMapper.updateByUserId(updateInfo, userId);
    }
}