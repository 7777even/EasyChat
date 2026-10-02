package com.easychat.service;

import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.UserInfoVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


/**
 * 业务接口
 */
public interface UserInfoService {

    /**
     * 根据条件查询列表
     */
    List<UserInfo> findListByParam(UserInfoQuery param);

    /**
     * 根据条件查询列表
     */
    Integer findCountByParam(UserInfoQuery param);

    /**
     * 分页查询
     */
    PaginationResultVO<UserInfo> findListByPage(UserInfoQuery param);

    /**
     * 新增
     */
    Integer add(UserInfo bean);

    /**
     * 批量新增
     */
    Integer addBatch(List<UserInfo> listBean);

    /**
     * 批量新增/修改
     */
    Integer addOrUpdateBatch(List<UserInfo> listBean);

    /**
     * 多条件更新
     */
    Integer updateByParam(UserInfo bean, UserInfoQuery param);

    /**
     * 多条件删除
     */
    Integer deleteByParam(UserInfoQuery param);

    /**
     * 根据UserId查询对象
     */
    UserInfo getUserInfoByUserId(String userId);


    /**
     * 根据UserId修改
     */
    Integer updateUserInfoByUserId(UserInfo bean, String userId);


    /**
     * 根据UserId删除
     */
    Integer deleteUserInfoByUserId(String userId);


    /**
     * 根据Email查询对象
     */
    UserInfo getUserInfoByEmail(String email);


    /**
     * 根据Email修改
     */
    Integer updateUserInfoByEmail(UserInfo bean, String email);


    /**
     * 根据Email删除
     */
    Integer deleteUserInfoByEmail(String email);

    void register(String email, String nickName, String password);

    UserInfoVO login(String email, String password);

    void updateUserInfo(UserInfo userInfo, MultipartFile avatarFile, MultipartFile avatarCover) throws IOException;

    void updateUserStatus(Integer status, String userId);

    void forceOffLine(String userId);

    /**
     * 修改密码：必须校验原密码，校验通过后才更新
     */
    void updatePassword(String userId, String oldPassword, String newPassword);

    /**
     * 发送邮箱验证码（type：0注册 1找回密码）
     */
    void sendEmailCode(String email, Integer type);

    /**
     * 通过邮箱验证码重置密码（忘记密码找回）
     */
    void resetPasswordByEmail(String email, String code, String newPassword);

    /**
     * 更新「加我的方式」（{@code user_info.join_type}）
     * <p>
     * 0 直接加入 / 1 加我时需验证。Service 直读 DB（{@code applyAdd} 亦直读），
     * 故保存后对之后发起的好友申请立即生效，无需失效任何缓存。
     *
     * @param userId   当前登录用户 id（由 Controller 从 token 取得，接口不接受前端传入）
     * @param joinType 0 或 1
     * @throws com.easychat.exception.BusinessException joinType 非法 → CODE_1001；用户不存在 → CODE_2101
     * @since 2026-10-02 加我方式与黑名单管理
     */
    void updateJoinType(String userId, Integer joinType);

    /**
     * 更新朋友圈可见范围（用户级默认）
     * <p>
     * <b>仅作为发布朋友圈时的默认值</b>，不参与 {@code MomentServiceImpl#canView} 判定
     * （ADR-001：改用户级设置不追溯已发布的历史动态，与微信一致）。
     *
     * @param userId           当前登录用户 id（接口不接受前端传入）
     * @param momentVisibility 0 公开 / 1 仅好友 / 2 仅自己 / 3 白名单 / 4 黑名单
     * @param visibleList      白名单 JSON 数组字符串，visibility=3 时**必填且非空**
     * @param invisibleList    黑名单 JSON 数组字符串，visibility=4 时**必填且非空**
     * @throws com.easychat.exception.BusinessException
     *         visibility 非法 / 名单与 visibility 不匹配 / 名单含非好友 / 名单超长 / 非法 JSON → CODE_1001；
     *         用户不存在 → CODE_2101
     * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
     */
    void updateMomentPrivacy(String userId, Integer momentVisibility,
                             String visibleList, String invisibleList);

    /**
     * 更新「是否对好友展示在线状态」
     * <p>
     * 本方法只落库；推帧/停播由调用方（Controller）按新旧值编排：
     * 置 0 立即推 {@code ONLINE_STATUS_HIDDEN(27)} 抹除好友端已有状态，
     * 置 1 立即广播一次当前状态。
     *
     * @param userId  当前登录用户 id
     * @param visible 1 展示 / 0 隐藏
     * @throws com.easychat.exception.BusinessException visible 非法 → CODE_1001；用户不存在 → CODE_2101
     * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
     */
    void updateOnlineStatusVisible(String userId, Integer visible);
}