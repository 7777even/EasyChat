package com.easychat.utils;

import com.easychat.entity.enums.SortOption;
import com.easychat.entity.query.BaseParam;

/**
 * 排序白名单解析（ADR-001 / ADR-004 的唯一落点）。
 *
 * <p><b>为什么抽成工具类</b>：管理端有 3 个列表端点走同一段「把 HTTP 的 sortField/sortDirection
 * 解析成 SortOption，并在未指定时回填默认项」的逻辑。若各写一份，将来修一处漏两处，
 * 就会出现「某些端点非法值被静默接受」的**不一致**——而静默接受正是本 Change 要消灭的东西。
 *
 * <p><b>三条不变式</b>（每条都有对应单测）：
 * <ol>
 *   <li>调用方给了排序参数 → 一律走 {@link SortOption#fromHttp}，
 *       <b>非法即抛 {@code CODE_1001}</b>（ADR-004：显式反馈，不静默回退）；</li>
 *   <li>没给且未预设 → 回填<b>该表默认项</b>，保证分页恒有 ORDER BY
 *       （C4：修复前 {@code orderBy} 为空时这些端点<b>完全没有排序</b>，
 *       MySQL 不保证无 ORDER BY 时的稳定序，翻页会重复 / 漏行）；</li>
 *   <li>没给但服务端已预设 → <b>保留预设值</b>，不被默认值覆盖
 *       （内部调用方如 {@code GroupController} 的非管理端列表会显式设排序）。</li>
 * </ol>
 *
 * <p><b>为什么不放在 SortOption 里</b>：那样枚举就得反向依赖 {@code BaseParam}，
 * 形成 entity.enums → entity.query 的反向耦合；此处只依赖查询基类，依赖方向单向。
 *
 * <p><b>注意</b>：{@code table} 必须由<b>服务端写死</b>，绝不能来自请求 ——
 * 它决定「这张表允许哪些排序列」，若可被调用方指定，等于绕过整个白名单。
 */
public class SortWhitelistTools {

    private SortWhitelistTools() {
    }

    /**
     * 把 query 上的排序入参解析为 {@code sortOption}。
     *
     * @param param 查询对象（其 sortField / sortDirection 由 Spring 从请求绑定）
     * @param table 目标表名，**必须由服务端写死**
     * @throws com.easychat.exception.BusinessException 排序参数非法（CODE_1001）
     */
    public static void resolveSort(BaseParam param, String table) {
        boolean hasInput = !StringTools.isEmpty(param.getSortField())
                || !StringTools.isEmpty(param.getSortDirection());
        if (hasInput) {
            param.setSortOption(SortOption.fromHttp(table, param.getSortField(), param.getSortDirection()));
            return;
        }
        if (param.getSortOption() == null) {
            param.setSortOption(SortOption.defaultOf(table));
        }
    }
}
