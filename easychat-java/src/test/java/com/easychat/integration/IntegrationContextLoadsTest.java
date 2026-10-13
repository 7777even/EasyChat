package com.easychat.integration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T1 / T2 —— 最小可启动验证：验证 Spring 上下文能在**无 MySQL / 无 Redis** 下加载。
 *
 * <p>目的（tasks.md T1+T2）：让启动报错**自己暴露**「哪些自动装配在启动期就要求连外部依赖」，
 * 而不是靠读代码推断。依据 AGENTS §2.1 第 12 条（禁止用局部证据推断系统行为）。
 *
 * <p>🔴 <b>T1/T2 实测结论（2026-10-13，不可假设、不可省略）</b>：不加任何 mock 时，上下文启动失败，
 * 且**每修掉一个才会暴露下一个**。最终确认启动期共有<b>三个</b>外部依赖（详见
 * {@link IntegrationTestBase} 的逐条说明）：
 * <ol>
 *   <li>{@code SensitiveWordServiceImpl#init}（{@code @PostConstruct} → {@code reload()}
 *       → {@code SensitiveWordMapper.selectByStatus}）需要 DB；</li>
 *   <li>{@code RedisConfig#redissonClient} 的 {@code Redisson.create()} 在 <b>Bean 创建期</b>
 *       就真连 Redis（与 HikariCP 的惰性建连不同）；</li>
 *   <li>{@code MessageHandler#init} 订阅广播 topic。</li>
 * </ol>
 * 由此同时确认：{@code @ActiveProfiles("test")} <b>确实</b>抑制了 application.properties 的
 * {@code active=dev}（否则会先因加载 dev 配置而报别的错）；HikariCP 是<b>惰性建连</b>；
 * 除上述三处外<b>没有第二处</b>启动期 DB 访问（MyBatis 的 Mapper 代理创建时不连库）。
 *
 * <p>🔴 <b>排查本问题时顺带发现的一处真实缺陷</b>（不在本 Change 范围，仅登记不改）：
 * {@code RedisConfig#redissonClient()} 的 catch 导入的是
 * {@code io.lettuce.core.RedisConnectionException}，而 Redisson 实际抛的是
 * {@code org.redisson.client.RedisConnectionException} —— <b>catch 类型不匹配</b>，
 * 故「redis 配置错误请检查」那条日志<b>永远不会执行</b>，方法末尾的 {@code return null}
 * 也成了死代码；Redis 不可用时应用会**直接启动崩溃**，而非按注释意图降级。
 * 该文件属生产代码，改动需另立项。
 *
 * <p>隔离配置见 {@code src/test/resources/application-test.properties}（Flyway 关闭、
 * 数据源指向不可达端口 + Hikari 不做启动期校验），日志降噪见 {@code logback-test.xml}。
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class IntegrationContextLoadsTest extends IntegrationTestBase {

    /** 只读配置值、不建立连接 —— 见 dataSourceMustNotPointAtRealDatabase 的踩坑记录 */
    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void contextLoads() {
        // 刻意空实现：本任务的首要产出是「启动期失败原因」这条信息，不是一条通过断言。
    }

    /**
     * T5 元断言：**测试上下文绝不能指向真实库**。
     *
     * <p>为什么这条必须是测试而不是注释：一旦有人误删 {@code @ActiveProfiles("test")}
     * 或误改 application-test.properties，本用例会立刻报出「数据源指向真实地址」，
     * 而其余用例仍可能照常通过（它们照样绿），从而**静默地**开始连活库。
     *
     * <p>🔴 踩坑记录（初版写错）：初版用 {@code dataSource.getConnection().getMetaData().getURL()}
     * 取 URL，实测报 {@code SQLTransientConnectionException} —— <b>取 URL 本身就要先连库</b>，
     * 而这恰恰是「数据源指向真库」时最危险的动作（元断言反而先把库连上了）。
     * 改为只注入配置值、<b>不建立任何连接</b>。
     */
    @Test
    public void dataSourceMustNotPointAtRealDatabase() {
        org.junit.Assert.assertTrue(
                "测试数据源指向了真实数据库：" + datasourceUrl,
                datasourceUrl != null && datasourceUrl.contains("should_never_connect"));
    }

    /** 顺带固化 T1 的一个结论：MockMvc 已由真容器提供（不是 standaloneSetup） */
    @Test
    public void mockMvcComesFromRealWebApplicationContext() {
        org.junit.Assert.assertNotNull(mockMvc);
    }
}