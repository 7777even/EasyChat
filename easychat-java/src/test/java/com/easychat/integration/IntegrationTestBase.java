package com.easychat.integration;

import com.easychat.mappers.SensitiveWordMapper;
import com.easychat.websocket.MessageHandler;
import org.junit.runner.RunWith;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 集成测试公共基类（T5）。
 *
 * <p><b>为什么要有基类</b>：启动期必须屏蔽的外部依赖有<b>三个</b>（T1/T2 逐轮实测得出，见
 * {@link IntegrationContextLoadsTest} 的类注释）。若每个测试类各写一遍，迟早出现某个类漏掉其中
 * 一个 —— 而漏掉的症状是「上下文启动失败」，排查成本远高于 DRY 的成本。
 *
 * <p><b>三个 mock 的必要性（均为实测结论，非假设）</b>：
 * <ol>
 *   <li>{@code sensitiveWordMapper} —— {@code SensitiveWordServiceImpl#init} 的
 *       {@code @PostConstruct} 会查库。<b>必须按 bean 名替换</b>：MyBatis 的 Mapper bean 由
 *       {@code MapperFactoryBean} 动态注册，只按类型 mock 实测<b>不生效</b>。</li>
 *   <li>{@code redissonClient} —— {@code Redisson.create()} 在 <b>Bean 创建期</b>就真连 Redis
 *       （与 HikariCP 的惰性建连不同）。</li>
 *   <li>{@code messageHandler} —— {@code MessageHandler#init} 会
 *       {@code getTopic().addListener(...)} 订阅广播 topic；RedissonClient 被 mock 时
 *       {@code getTopic()} 返回 null，仍会 NPE。</li>
 * </ol>
 *
 * <p><b>隔离配置</b>见 {@code src/test/resources/application-test.properties}
 * （Flyway 关闭 + 数据源指向不可达端口 + Hikari 不做启动期校验），
 * 日志降噪见 {@code src/test/resources/logback-test.xml}。
 *
 * <p>⚠ 子类若要 mock 业务 Service，直接再加 {@code @MockBean} 即可（同名冲突时子类优先）。
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** 启动期唯一需要 DB 的依赖；按 bean 名替换才生效（见类注释 ①） */
    @MockBean(name = "sensitiveWordMapper")
    protected SensitiveWordMapper sensitiveWordMapper;

    /** 见类注释 ② */
    @MockBean
    protected RedissonClient redissonClient;

    /** 见类注释 ③ */
    @MockBean
    protected MessageHandler messageHandler;
}