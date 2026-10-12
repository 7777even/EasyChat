package com.easychat.service.impl;

import com.easychat.entity.enums.BeautyAccountStatusEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserInfoBeautyQuery;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.UserInfoBeautyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.service.UserInfoBeautyService;
import com.easychat.utils.SortWhitelistTools;
import com.easychat.utils.StringTools;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


/**
 * 靓号 业务接口实现
 */
@Service("userInfoBeautyService")
public class UserInfoBeautyServiceImpl implements UserInfoBeautyService {

    @Resource
    private UserInfoBeautyMapper<UserInfoBeauty, UserInfoBeautyQuery> userInfoBeautyMapper;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<UserInfoBeauty> findListByParam(UserInfoBeautyQuery param) {
        return this.userInfoBeautyMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(UserInfoBeautyQuery param) {
        return this.userInfoBeautyMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<UserInfoBeauty> findListByPage(UserInfoBeautyQuery param) {
        // 排序白名单：/admin/loadBeautyAccountList 直接绑定 UserInfoBeautyQuery，
        // 调用方可指定排序但只能取白名单内的项；未指定则回填默认项
        SortWhitelistTools.resolveSort(param, "user_info_beauty");
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<UserInfoBeauty> list = this.findListByParam(param);
        PaginationResultVO<UserInfoBeauty> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(UserInfoBeauty bean) {
        return this.userInfoBeautyMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<UserInfoBeauty> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userInfoBeautyMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<UserInfoBeauty> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.userInfoBeautyMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(UserInfoBeauty bean, UserInfoBeautyQuery param) {
        StringTools.checkParam(param);
        return this.userInfoBeautyMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(UserInfoBeautyQuery param) {
        StringTools.checkParam(param);
        return this.userInfoBeautyMapper.deleteByParam(param);
    }

    /**
     * 根据Id获取对象
     */
    @Override
    public UserInfoBeauty getUserInfoBeautyById(Integer id) {
        return this.userInfoBeautyMapper.selectById(id);
    }

    /**
     * 根据Id修改
     */
    @Override
    public Integer updateUserInfoBeautyById(UserInfoBeauty bean, Integer id) {
        return this.userInfoBeautyMapper.updateById(bean, id);
    }

    /**
     * 根据Id删除
     */
    @Override
    public Integer deleteUserInfoBeautyById(Integer id) {
        return this.userInfoBeautyMapper.deleteById(id);
    }

    /**
     * 根据UserId获取对象
     */
    @Override
    public UserInfoBeauty getUserInfoBeautyByUserId(String userId) {
        return this.userInfoBeautyMapper.selectByUserId(userId);
    }

    /**
     * 根据UserId修改
     */
    @Override
    public Integer updateUserInfoBeautyByUserId(UserInfoBeauty bean, String userId) {
        return this.userInfoBeautyMapper.updateByUserId(bean, userId);
    }

    /**
     * 根据UserId删除
     */
    @Override
    public Integer deleteUserInfoBeautyByUserId(String userId) {
        return this.userInfoBeautyMapper.deleteByUserId(userId);
    }

    /**
     * 根据Email获取对象
     */
    @Override
    public UserInfoBeauty getUserInfoBeautyByEmail(String email) {
        return this.userInfoBeautyMapper.selectByEmail(email);
    }

    /**
     * 根据Email修改
     */
    @Override
    public Integer updateUserInfoBeautyByEmail(UserInfoBeauty bean, String email) {
        return this.userInfoBeautyMapper.updateByEmail(bean, email);
    }

    /**
     * 根据Email删除
     */
    @Override
    public Integer deleteUserInfoBeautyByEmail(String email) {
        return this.userInfoBeautyMapper.deleteByEmail(email);
    }

    /**
     * 校验靓号的 userId 片段必须为纯数字。
     *
     * <p>2026-10-12（遗留 #19）：与 {@code StringTools.getUserId()}（"U" + 随机数(11)）对齐，
     * 使 userId 字符集收敛为「U + 数字」∪ 系统账号 {@code Urobot}，
     * 从而使客户端 @ 提及正则得以精确表达真实 ID 集合。
     *
     * <p>用 {@code String.matches} 而非 {@code StringTools.isEmpty} 前置判断：
     * {@code "".matches("\\d+")} 为 false，null 由显式判断拦下。
     * 注意 {@code matches} 要求**整串**匹配（等价于隐式加了 {@code ^$}），故空串自然被拒。
     *
     * @param bareUserId 不含 "U" 前缀的靓号
     */
    private void checkBeautyUserId(String bareUserId) {
        if (bareUserId == null || !bareUserId.matches("\\d+")) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001);
        }
    }

    @Override
    public void saveAccount(UserInfoBeauty beauty) {
        // 2026-10-12（遗留 #19）：靓号字符集约束。
        // 靓号是**唯一**能把任意字符写进 user_info.user_id 的入口，此前零格式校验 →
        // 字符集无上界，客户端 @ 提及正则无法收紧（收紧成 \d+ 会把字母靓号用户彻底挡在 @ 之外）。
        // 约束与 StringTools.getUserId()（"U" + 随机数(11)，纯数字）严格对齐。
        //
        // 刻意置于**所有校验与写库之前**：既保证非法值不落库，
        // 也避免拿含通配/超长/空值的 userId 去查唯一性（selectByUserId 前缀 "U"+值）做无意义查询。
        // 活库已实测无存量影响（靓号表 0 行、非「U+数字」的 user_id 0 条）。
        checkBeautyUserId(beauty.getUserId());
        if (beauty.getId() != null) {
            UserInfoBeauty dbInfo = this.userInfoBeautyMapper.selectById(beauty.getId());
            if (BeautyAccountStatusEnum.USEED.getStatus().equals(dbInfo.getStatus())) {
                //已经使用的不允许修改
                throw new BusinessException(ResponseCodeEnum.CODE_1001);
            }
        }
        //判断邮箱是否已经存在
        UserInfoBeauty dbInfo = this.userInfoBeautyMapper.selectByEmail(beauty.getEmail());
        if (beauty.getId() == null && dbInfo != null) {
            throw new BusinessException("靓号邮箱已经存在");
        }

        if (beauty.getId() != null && dbInfo != null && dbInfo.getId() != null && !beauty.getId().equals(dbInfo.getId())) {
            throw new BusinessException("靓号邮箱已经存在");
        }

        //判断靓号是否存在
        dbInfo = this.userInfoBeautyMapper.selectByUserId(UserContactTypeEnum.USER.getPrefix() + beauty.getUserId());
        if (beauty.getId() == null && dbInfo != null) {
            throw new BusinessException("靓号已经存在");
        }

        if (beauty.getId() != null && dbInfo != null && dbInfo.getId() != null && !beauty.getId().equals(dbInfo.getId())) {
            throw new BusinessException("靓号已经存在");
        }

        //判断邮箱是否已经注册
        UserInfo userInfo = this.userInfoMapper.selectByEmail(beauty.getEmail());
        if (userInfo != null) {
            throw new BusinessException("靓号邮箱已经被注册");
        }

        userInfo = this.userInfoMapper.selectByUserId(UserContactTypeEnum.USER.getPrefix() + beauty.getUserId());
        if (userInfo != null) {
            throw new BusinessException("靓号已经被注册");
        }

        if (beauty.getId() != null) {
            this.userInfoBeautyMapper.updateById(beauty, beauty.getId());
        } else {
            this.userInfoBeautyMapper.insert(beauty);
        }
    }
}