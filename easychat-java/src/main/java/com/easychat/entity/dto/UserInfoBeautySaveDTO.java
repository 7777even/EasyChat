package com.easychat.entity.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;

/**
 * 管理端「保存靓号」入参 DTO。
 *
 * <p>2026-10-13 新增（openspec {@code 2026-10-13-beauty-account-dto}）：
 * 原先 {@code AdminUserInfoBeautyController#saveBeautAccount} 直接接收
 * {@code entity.po.UserInfoBeauty} —— 全仓<b>唯一</b>违反 AGENTS §3.6
 * 「不复用 Entity 做出入参」的端点（立项前已穷举 26 个 PO / 9 个 DTO / 28 个 Query
 * × 全部 Controller 确认只此一处）。
 *
 * <p><b>为什么这不是安全缺口、却仍要修</b>：该端点有 {@code checkAdmin = true} 守着，
 * 且 {@code UserInfoBeautyServiceImpl#saveAccount} 已有四重校验（含 2026-10-12 遗留 #19
 * 补的 {@code checkBeautyUserId} 纯数字校验），PO 上 4 个字段也都是管理员域内可改的数据，
 * <b>无跨用户越权</b>。真正的风险是<b>结构性的</b>：PO 直接绑定意味着
 * 「将来给这个 PO 加一个内部字段，它会自动变成可由外部提交的入参」，
 * 而构建、测试、门禁都不会给出任何提示。
 *
 * <p>⚠ 字段名即<b>请求参数名</b>（Spring 按名绑定 {@code form-urlencoded}），改名会破坏前端契约。
 * 现前端 {@code BeautyAccountEdit.vue} 提交 {@code id} / {@code email} / {@code userId} / {@code status}
 * 四项，本 DTO 保持同名，故前端零改动。
 *
 * <p>📌 <b>不加 {@code @Schema} 的原因</b>：本仓<b>没有引入 swagger 注解依赖</b>
 * （全仓零 {@code ApiModel} / {@code Schema} 使用，AGENTS §6「零新生产依赖」）。
 * AGENTS §3.6 要求的是「中文注释<b>或</b> {@code @Schema}」，此处取中文注释。
 * 若将来引入 swagger 依赖，应回填本类与其它 DTO 的 {@code @Schema}，而不是现在硬加一个无来源的注解。
 *
 * @see com.easychat.controller.AdminUserInfoBeautyController#saveBeautAccount
 */
public class UserInfoBeautySaveDTO {

    /**
     * 靓号记录主键。
     *
     * <p><b>刻意不加任何校验</b>（openspec ADR-003）：本字段的语义是
     * 「编辑时带上、新建时为空」，由 Service 的 {@code beauty.getId() == null} 分流。
     * 若在 DTO 层表达这条约束就得写自定义校验器，收益不抵复杂度，
     * 且会让「新建/编辑分流」在 Controller 与 Service 两层各写一遍。
     */
    private Integer id;

    /** 靓号对应的邮箱（系统按邮箱投递验证码 / 找回密码） */
    @NotEmpty(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 靓号本体（须为纯数字，由 Service 的 checkBeautyUserId 兜底校验） */
    @NotEmpty(message = "靓号不能为空")
    private String userId;

    /**
     * 使用状态：0 未使用、1 已使用。
     *
     * <p><b>刻意不加 {@code @NotNull}</b>（openspec ADR-002）：新建场景下前端不传该字段
     * 是正常路径，加了非空约束会把合法的新建请求判为非法 —— 属回归。
     */
    private Integer status;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}