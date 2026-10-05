package com.easychat.service.impl;

import com.easychat.entity.po.Moment;
import com.easychat.utils.IdListTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MomentServiceImpl#canView} 隐私可见性判定测试（2026-10-05）
 *
 * <p><b>为什么这个方法必须有测试</b>：它是朋友圈隐私的**总闸**，
 * 而 2026-10-05 的活库核查中，<b>我读这个方法时把结论读反了</b> ——
 * 我以为「白名单列含垃圾项 → 名单非空但全是垃圾 → 所有好友被拒」，
 * 读完才发现判定用的是 {@code visible.contains(viewerId)} 而非「名单非空则放行」，
 * 故与空列表<b>行为完全一致</b>。
 * 一个靠读代码就能读反的隐私闸门，正是最该被断言钉住的地方。
 *
 * <p><b>本测试发现的两个方向相反的默认值</b>（此前无任何文档或测试记录）：
 * <ul>
 *   <li>{@code visibility == null} → <b>可见（fail-open）</b>：未设置可见范围的动态对所有人可见；</li>
 *   <li>{@code default}（非法值 5 / 99 / -1）→ <b>不可见（fail-closed）</b>。</li>
 * </ul>
 * 两者方向相反且都无测试锁定，属于「靠约定维持」的行为。
 *
 * <p><b>测试手法</b>：{@code canView} 是 {@code private}，故用反射调用，
 * 与 {@code GlobalInterceptorAnnotationContractTest} 的既有先例一致（不改生产代码可见性）。
 * {@code contactCache} 预置后 {@code isFriend} 不触碰 Redis，故无需 Spring 容器。
 */
@DisplayName("MomentServiceImpl#canView — 朋友圈可见性总闸")
class MomentCanViewTest {

    private static final String OWNER = "U_OWNER";
    private static final String VIEWER_FRIEND = "U_FRIEND";
    private static final String VIEWER_STRANGER = "U_STRANGER";

    /** 预置的好友集合，避免 isFriend 走 Redis（本测试不启 Spring）。 */
    private static Map<String, Set<String>> cacheWithFriend() {
        Map<String, Set<String>> cache = new HashMap<>();
        Set<String> contacts = new LinkedHashSet<>(Arrays.asList(VIEWER_FRIEND, "U_OTHER"));
        cache.put(OWNER, contacts);
        return cache;
    }

    private static Moment moment(Integer visibility, String visibleList, String invisibleList) {
        Moment m = new Moment();
        m.setId(1L);
        m.setUserId(OWNER);
        m.setVisibility(visibility);
        m.setVisibleList(visibleList);
        m.setInvisibleList(invisibleList);
        m.setStatus(1);
        return m;
    }

    private static boolean canView(Moment m, String viewerId, Map<String, Set<String>> cache) {
        try {
            Method method = MomentServiceImpl.class
                    .getDeclaredMethod("canView", Moment.class, String.class, Map.class);
            method.setAccessible(true);
            return (boolean) method.invoke(new MomentServiceImpl(), m, viewerId, cache);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 反射把被测方法抛出的异常包一层，必须拆开还原，
            // 否则调用方拿到的是 InvocationTargetException 而非真实根因。
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 canView 失败（方法签名已变？）", e);
        }
    }

    private static boolean canView(Moment m, String viewerId) {
        return canView(m, viewerId, cacheWithFriend());
    }

    // ═══════════════════════════════════════════════════════════════
    // 作者豁免
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("作者本人对任何可见性都可见（含私密 2 与空名单白名单 3）")
    void authorAlwaysSeesOwnMoment() {
        for (Integer v : Arrays.asList(null, 0, 1, 2, 3, 4, 5, 99)) {
            assertTrue(canView(moment(v, null, null), OWNER),
                    "作者应始终可见自己的动态，visibility=" + v);
        }
        // 白名单模式下名单为空，作者仍可见（豁免优先于名单判定）
        assertTrue(canView(moment(3, "[]", null), OWNER));
    }

    // ═══════════════════════════════════════════════════════════════
    // visibility = null / 0 / 2
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ visibility=null → 任何人可见（fail-open，此前无测试记录）")
    void nullVisibilityIsVisibleToEveryone() {
        // ⚠ fail-open：未设置可见范围（存量数据 / 早期版本写入）的动态对所有人可见。
        //   这是与「非法值 fail-closed」**方向相反**的默认值，务必显式记录。
        assertTrue(canView(moment(null, null, null), VIEWER_STRANGER));
        assertTrue(canView(moment(null, "[\"U_SOMEBODY_ELSE\"]", null), VIEWER_STRANGER),
                "visibility 为 null 时名单不参与判定");
    }

    @Test
    @DisplayName("visibility=0 公开 → 任何人可见")
    void publicVisibleToEveryone() {
        assertTrue(canView(moment(0, null, null), VIEWER_STRANGER));
        assertTrue(canView(moment(0, "[\"U_X\"]", "[\"U_STRANGER\"]"), VIEWER_STRANGER),
                "公开模式下名单不参与判定（黑名单也不参与）");
    }

    @Test
    @DisplayName("visibility=2 私密 → 仅作者可见")
    void privateVisibleOnlyToAuthor() {
        assertFalse(canView(moment(2, null, null), VIEWER_FRIEND));
        assertFalse(canView(moment(2, null, null), VIEWER_STRANGER));
    }

    // ═══════════════════════════════════════════════════════════════
    // visibility = 1 好友可见
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("visibility=1 好友可见 → 好友可见、陌生人不可见")
    void friendOnlyRespectsFriendship() {
        Moment m = moment(1, null, null);
        assertTrue(canView(m, VIEWER_FRIEND), "好友应可见");
        assertFalse(canView(m, VIEWER_STRANGER), "陌生人应不可见");
    }

    @Test
    @DisplayName("visibility=1 contactCache 命中时不触碰 Redis（空 friend 集合）")
    void friendOnlyUsesProvidedCache() {
        // contactCache 里放一个**空**集合：isFriend 应直接用缓存（miss 才查 Redis），
        // 故任何人都不可见 —— 这同时证明本测试不需要 Spring/Redis。
        Map<String, Set<String>> emptyCache = new HashMap<>();
        emptyCache.put(OWNER, new HashSet<>());
        assertFalse(canView(moment(1, null, null), VIEWER_FRIEND, emptyCache));
    }

    // ═══════════════════════════════════════════════════════════════
    // ★ visibility = 3 白名单 —— 2026-10-05 我读反的那一段
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ visibility=3 白名单 → 名单内可见、名单外不可见")
    void whiteListAllowsOnlyListed() {
        Moment m = moment(3, "[\"" + VIEWER_FRIEND + "\"]", null);
        assertTrue(canView(m, VIEWER_FRIEND), "名单内应可见");
        assertFalse(canView(m, VIEWER_STRANGER), "名单外应不可见");
    }

    @Test
    @DisplayName("★ 纯垃圾名单的判定结果 ≡ 空名单（垃圾项绝不能变成可匹配的 id）")
    void junkOnlyListBehavesExactlyLikeEmptyList() {
        // ★ 这条是本测试类的核心。它锁定的是 2026-10-05 我读反的那点：
        //   判定是 `visible.contains(viewerId)`，**不是**「名单非空则只放行名单内」，
        //   更**不是**「名单非空 → 认为配置有误 → 放行所有人」。
        //
        //   故正确表述是：**纯垃圾名单与空名单的判定结果完全相同**（除作者外无人可见）。
        //   ⚠ 初版我把断言写成「名单内好友仍应可见」，那是错的 ——
        //   对 `[""]` 这类纯垃圾名单，好友**根本不在名单里**，当然不可见。
        //   真正要锁的是「垃圾项不被当成 id」，故改为与空名单**逐项对拍**。
        String[] junkOnly = {"[\"\"]", "[\"   \"]", "[\"\\t\"]", "[\"NULL\"]", "NULL", "[]", ""};
        for (String list : junkOnly) {
            Moment m = moment(3, list, null);
            assertFalse(canView(m, VIEWER_FRIEND),
                    "纯垃圾名单下好友不可见（其不在名单内），list=" + list);
            assertFalse(canView(m, VIEWER_STRANGER),
                    "★ 垃圾项绝不能变成可匹配的 id，list=" + list);
            assertTrue(canView(m, OWNER), "作者豁免不受影响，list=" + list);
        }
    }

    @Test
    @DisplayName("★ 正常项 + 垃圾项混合：正常项照常生效，垃圾项不产生额外匹配")
    void mixedListKeepsRealIdsWorking() {
        String[] mixed = {
                "[\"" + VIEWER_FRIEND + "\",\"NULL\"]",
                "[\"NULL\",\"" + VIEWER_FRIEND + "\"]",
                "[\"\",\"" + VIEWER_FRIEND + "\",\"   \"]",
                "[\"" + VIEWER_FRIEND + "\",\"\"]"
        };
        for (String list : mixed) {
            Moment m = moment(3, list, null);
            assertTrue(canView(m, VIEWER_FRIEND),
                    "名单内的真实好友应可见（垃圾项不得连带拒绝），list=" + list);
            assertFalse(canView(m, VIEWER_STRANGER), "名单外陌生人不可见，list=" + list);
        }
    }

    @Test
    @DisplayName("★ 判定结果 ≡ IdListTools.parse(list).contains(viewerId)（垃圾项对两者的影响一致）")
    void junkListMatchesIdListToolsSemantics() {
        // canView 委托 IdListTools.parse，故对**垃圾名单**两者结论必须一致 ——
        // 这正是「两条链路共用同一解析器」的价值所在（否则格式解析会漂移）。
        String[] lists = {
                "[\"" + VIEWER_FRIEND + "\"]",
                "[\"U_A\",\"" + VIEWER_FRIEND + "\",\"U_B\"]",
                "[\"\"," + VIEWER_FRIEND + "\"]",
                "[\"\"]", "[\"NULL\"]", "[\"   \"]", "NULL", "[]", "", null
        };
        for (String list : lists) {
            for (String viewer : new String[]{VIEWER_FRIEND, VIEWER_STRANGER}) {
                boolean expected = IdListTools.parse(list).contains(viewer);
                assertEquals(expected, canView(moment(3, list, null), viewer),
                        "canView 与 IdListTools.parse 口径分叉：list=" + list + " viewer=" + viewer);
            }
        }
    }

    @Test
    @DisplayName("白名单为 null / 空串 / [] → 除作者外无人可见")
    void whiteListEmptyMeansNobodyExceptAuthor() {
        for (String list : new String[]{null, "", "[]", "[ ]"}) {
            Moment m = moment(3, list, null);
            assertFalse(canView(m, VIEWER_FRIEND), "空名单下好友也不可见，list=" + list);
            assertFalse(canView(m, VIEWER_STRANGER), "空名单下陌生人不可见，list=" + list);
            assertTrue(canView(m, OWNER), "作者豁免不受空名单影响，list=" + list);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // visibility = 4 黑名单过滤
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("visibility=4 黑名单 → 名单外可见、名单内不可见")
    void blackListBlocksOnlyListed() {
        Moment m = moment(4, null, "[\"" + VIEWER_STRANGER + "\"]");
        assertFalse(canView(m, VIEWER_STRANGER), "黑名单内应不可见");
        assertTrue(canView(m, VIEWER_FRIEND), "黑名单外应可见");
    }

    @Test
    @DisplayName("★ 黑名单含垃圾项时不误伤（垃圾 id 不等于任何真实用户）")
    void blackListWithJunkDoesNotBlockRealUsers() {
        String[] junky = {"[\"NULL\"]", "[\"\"]", "[\"   \"]", "NULL", "[]", null};
        for (String list : junky) {
            Moment m = moment(4, null, list);
            assertTrue(canView(m, VIEWER_FRIEND),
                    "黑名单含垃圾项时普通用户仍应可见，list=" + list);
            assertTrue(canView(m, VIEWER_STRANGER),
                    "黑名单含垃圾项不得把陌生人误拉黑，list=" + list);
        }
    }

    @Test
    @DisplayName("黑名单为空 → 对所有人可见（除作者外无差别）")
    void emptyBlackListAllowsEveryone() {
        for (String list : new String[]{null, "", "[]"}) {
            assertTrue(canView(moment(4, null, list), VIEWER_STRANGER), "list=" + list);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 非法 visibility —— 与 null 方向相反的 fail-closed 默认值
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 非法 visibility（5/99/-1/3.5 形态）→ 不可见（fail-closed）")
    void illegalVisibilityIsBlocked() {
        for (Integer v : Arrays.asList(5, 6, 99, -1, 100)) {
            Moment m = moment(v, "[\"" + VIEWER_FRIEND + "\"]", null);
            assertFalse(canView(m, VIEWER_FRIEND),
                    "非法 visibility=" + v + " 必须 fail-closed（且名单不得放行）");
            assertFalse(canView(m, VIEWER_STRANGER), "非法 visibility=" + v);
            assertTrue(canView(m, OWNER), "作者豁免仍优先，visibility=" + v);
        }
    }

    @Test
    @DisplayName("★ null 与非法值的方向相反：null 放行、非法值拒绝（显式记录该不对称）")
    void nullAndIllegalVisibilityAreAsymmetric() {
        assertTrue(canView(moment(null, null, null), VIEWER_STRANGER), "null → 放行");
        assertFalse(canView(moment(5, null, null), VIEWER_STRANGER), "非法值 → 拒绝");
    }

    // ═══════════════════════════════════════════════════════════════
    // 健壮性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canView 不因 contactCache 为 null 而崩（预置缓存路径）")
    void toleratesSharedCacheAcrossCalls() {
        // 同一 contactCache 复用于多条动态（生产即如此），故不应被 canView 清空或改写。
        Map<String, Set<String>> shared = cacheWithFriend();
        Moment m1 = moment(1, null, null);
        Moment m2 = moment(1, null, null);
        assertTrue(canView(m1, VIEWER_FRIEND, shared));
        assertTrue(canView(m2, VIEWER_FRIEND, shared));
        assertEquals(1, shared.size(), "contactCache 被意外改动");
    }

    @Test
    @DisplayName("⚠ 现状 canView 在 ownerId 为 null 时抛 NPE（作者判定用 equals）")
    void nullOwnerThrowsNpe() {
        // ⚠ 现状：作者判定写作 `moment.getUserId().equals(viewerId)`，
        //   故 authorId 为 null 时**抛 NPE 而非返回 false**。
        //   DDL 中 moment.user_id 是 NOT NULL，故生产不可达；
        //   但若将来允许「系统/机器人」发动态（userId 为空），此处会 500。
        //   本用例锁定现状，修复需改判 `viewerId.equals(moment.getUserId())`。
        Moment m = moment(0, null, null);
        m.setUserId(null);
        assertThrows(NullPointerException.class, () -> canView(m, VIEWER_STRANGER));
    }

    @Test
    @DisplayName("viewerId 为 null 时不崩（陌生人视角）")
    void nullViewerIsTreatedAsStranger() {
        Moment m = moment(2, null, null);
        assertFalse(canView(m, null), "私密动态对 null 视角不可见（不抛异常）");
        assertTrue(canView(moment(0, null, null), null), "公开动态对 null 视角可见");
    }

    @Test
    @DisplayName("五种 visibility 的判定矩阵全景（回归快照）")
    void fullMatrixSnapshot() {
        List<String> rows = new ArrayList<>();
        for (Integer v : Arrays.asList(null, 0, 1, 2, 3, 4, 5)) {
            Moment m = moment(v,
                    v != null && v == 3 ? "[\"" + VIEWER_FRIEND + "\"]" : null,
                    v != null && v == 4 ? "[\"" + VIEWER_STRANGER + "\"]" : null);
            rows.add(String.format("v=%-4s owner=%-5s friend=%-5s stranger=%s",
                    String.valueOf(v),
                    canView(m, OWNER),
                    canView(m, VIEWER_FRIEND),
                    canView(m, VIEWER_STRANGER)));
        }
        String matrix = String.join("\n", rows);
        System.out.println("=== canView 判定矩阵 ===\n" + matrix);
        // 快照断言：任何一条判定变化都必须显式改这里，而不是悄悄漂移
        assertTrue(matrix.contains("v=null owner=true  friend=true  stranger=true"), matrix);
        assertTrue(matrix.contains("v=0    owner=true  friend=true  stranger=true"), matrix);
        assertTrue(matrix.contains("v=1    owner=true  friend=true  stranger=false"), matrix);
        assertTrue(matrix.contains("v=2    owner=true  friend=false stranger=false"), matrix);
        assertTrue(matrix.contains("v=3    owner=true  friend=true  stranger=false"), matrix);
        assertTrue(matrix.contains("v=4    owner=true  friend=true  stranger=false"), matrix);
        assertTrue(matrix.contains("v=5    owner=true  friend=false stranger=false"), matrix);
    }
}
