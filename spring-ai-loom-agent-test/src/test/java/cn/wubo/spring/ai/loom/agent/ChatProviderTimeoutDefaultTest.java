package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the library-side default for the chat provider's HTTP timeout.
 *
 * <p><b>Why this exists</b>：Spring AI 2.0.1 对 OpenAI provider 硬编码
 * {@code AbstractOpenAiProperties.DEFAULT_TIMEOUT = Duration.ofSeconds(60)}，
 * 且该值经 {@code OpenAiChatAutoConfiguration} 施加到 okhttp {@code callTimeout}
 * （整体超时，非 readTimeout）。长思考 + tool_use 的流式请求会稳定在 60s 处被整体掐断，
 * 底��是 {@code StreamResetException: stream was reset: CANCEL}，
 * 且流式帧仍活跃（实测 60.095s、1174 帧、每秒有帧），并非 token 上限正常收尾。
 *
 * <p><b>前车之鉴</b>：本仓 2026-09-30 已因同一症状给 {@code anthropic} 段打过
 * {@code timeout: 600s} 补丁（见 test app application.yml 注释），但当前生效的
 * {@code openai} 段没有该配置 ⇒ 同一 bug 原样复发。本次在库侧补缺省值根治。
 *
 * <p><b>契约</b>：仅当消费方<b>未</b>显式配置 {@code spring.ai.openai.timeout} 时补默认值；
 * 显式配置一律优先，绝不覆盖。
 *
 * <p>covers: R1, R2, R3
 */
class ChatProviderTimeoutDefaultTest {

    private final LoomAgentConfiguration.InfrastructureConfiguration config =
            new LoomAgentConfiguration.InfrastructureConfiguration();

    /**
     * 施加默认值后读到的实际值。
     *
     * <p>刻意按 {@link Duration} 读（Spring AI 自己就是绑到 {@code Duration}），
     * 而不是 {@code getProperty(key)} 拿 String —— {@code MockEnvironment} 没有
     * 注册 Duration→String 转换器，按 String 读会抛 ConverterNotFound，那是测试写法问题
     * 不是产品行为。
     */
    private Duration resolvedTimeout(org.springframework.core.env.ConfigurableEnvironment env) {
        // 按 String 读再parse：MockEnvironment 未注册 Duration 转换器（两种方向都没有），
        // 按 Duration 读会抛 ConverterNotFound —— 那是测试夹具的局限，不是产品行为。
        // 生产路径由 Spring AI 自己的 binder把该属性绑到 Duration，不受此影响。
        String raw = env.getProperty("spring.ai.openai.timeout");
        if (raw == null) return null;
        // 库侧注入的是 ISO-8601（PT600S），消费方可能写 Spring 简写（30s / 10m / 1h）。
        // 两种都要认，故先试 ISO 再退回 Spring 的 DurationStyle。
        try {
            return Duration.parse(raw);
        } catch (java.time.format.DateTimeParseException notIso) {
            return org.springframework.boot.convert.DurationStyle.detectAndParse(raw);
        }
    }

    /**
     * 走真实路径：binder bean 解析出 {@code chat.timeout-seconds}，再由 BFPP 施加到 Environment。
     * 这样测的是"yml → 最终属性"的完整链路，而非单个类的孤立行为。
     *
     * <p><b>为什么用 StandardEnvironment 而不是 MockEnvironment</b>：MockEnvironment 的
     * {@code withProperty(...)} 与 BFPP 的 {@code addLast(...)} 落在<b>同一个</b> mock
     * property source 里，{@code getProperty} 无法区分二者优先级—— 变异测试证明此时
     * 把 {@code addLast} 改成 {@code addFirst} 用例仍全绿，即 R2 是<b>假锁</b>。
     * StandardEnvironment 有真实的多property source 优先级链，才能真正验证
     * "消费方配置压过库默认值"。
     */
    private org.springframework.core.env.StandardEnvironment bound(
            org.springframework.core.env.StandardEnvironment env) {
        LoomAgentProperties props = config.loomAgentProperties(env);
        new ChatProviderTimeoutDefaultsBeanFactoryPostProcessor(
                env, props.getChat().getTimeoutSeconds())
                .postProcessBeanFactory(null);
        return env;
    }

    /** 模拟"消费方在 application.yml 里显式配置"：放进高优先级的 property source。 */
    private org.springframework.core.env.StandardEnvironment consumerEnv(String key, String value) {
        org.springframework.core.env.StandardEnvironment env =
                new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "application.yml", java.util.Map.of(key, value)));
        return env;
    }

    /** 无任何消费方配置的干净环境。 */
    private org.springframework.core.env.StandardEnvironment cleanEnv() {
        return new org.springframework.core.env.StandardEnvironment();
    }

    // ==================== R1: 缺省时补足 ====================

    @Test
    void noConsumerConfig_injectsDefault() {
        var env = bound(cleanEnv());

        assertThat(resolvedTimeout(env))
                .as("消费方未配置时应补默认值，避免 60s 掐断长流")
                .isEqualTo(Duration.ofSeconds(600));
    }

    // ==================== R2: 显式配置绝不覆盖 ====================

    @Test
    void explicitConsumerConfig_isNotOverridden() {
        var env = bound(consumerEnv("spring.ai.openai.timeout", "30s"));

        assertThat(resolvedTimeout(env))
                .as("消费方显式配置必须优先，库不得覆盖")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void explicitConsumerConfig_arbitraryValue_isNotOverridden() {
        // 即便消费方配了一个"看起来不健康"的值（比默认小），也不得被库改写
        var env = bound(consumerEnv("spring.ai.openai.timeout", "5s"));

        assertThat(resolvedTimeout(env)).isEqualTo(Duration.ofSeconds(5));
    }

    // ==================== R3: 库侧配置入口可调 ====================

    @Test
    void librarySideConfig_overridesDefault() {
        var env0 = consumerEnv("spring.ai.loom.agent.chat.timeout-seconds", "900");
        var env = bound(env0);

        assertThat(resolvedTimeout(env))
                .as("部署方应能通过库侧配置项调整超时")
                .isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void librarySideConfig_winsOverNothing_butLosesToConsumer() {
        // 两边都配时，消费方的 provider 级配置优先（更具体、离 provider 更近）
        var env0 = consumerEnv("spring.ai.loom.agent.chat.timeout-seconds", "900");
        env0.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "application.yml-provider", java.util.Map.of("spring.ai.openai.timeout", "120s")));
        var env = bound(env0);

        assertThat(resolvedTimeout(env)).isEqualTo(Duration.ofSeconds(120));
    }

    // ==================== 幂等 / 无副作用 ====================

    @Test
    void applyTwice_isIdempotent() {
        var env = cleanEnv();
        bound(env);
        Duration first = resolvedTimeout(env);
        bound(env);
        assertThat(resolvedTimeout(env)).isEqualTo(first);
    }

    @Test
    void applyTwice_doesNotAccumulateIsoFormat() {
        // 若实现用"拼字符串"而非 setProperty，重复施加会得到 PT600S PT600S 之类
        var env = cleanEnv();
        bound(env);
        bound(env);
        assertThat(resolvedTimeout(env)).isEqualTo(Duration.ofSeconds(600));
    }

    // ============ R1 的真实链路：经 Spring AI 自己的 properties 类绑定 ============

    /**
     * 端到端验证：库注入的 ISO-8601 字符串能被 Spring AI 的
     * {@code OpenAiChatProperties}（其timeout 字段定义在父类
     * {@code AbstractOpenAiProperties}）<b>真的绑定成 600 秒</b>。
     *
     * <p>这是 R1 的关键一环 —— 前面的用例只证明"Environment 里有了这个键"，
     * 但若Spring Boot 的 Duration 转换器不认PT600S，Spring AI 就会退回它自己的
     * 60s 默认值，修复形同虚设。此例直接用 Spring AI 的 properties 类做绑定，
     * 断言拿到的是 600 而非 60。
     */
    @Test
    void springAiProperties_actuallyBindInjectedDefault() {
        var env = bound(cleanEnv());

        //必须走真实 ApplicationContext：@ConfigurationProperties 风格的宽松绑定
        //（relaxed binding，"PT600S" → Duration）依赖 ConfigurationPropertySources，
        // 裸 MockEnvironment 不attach 它，bind("spring.ai.openai", ...) 会返回空。
        org.springframework.web.context.support.GenericWebApplicationContext ctx =
                new org.springframework.web.context.support.GenericWebApplicationContext();
        ctx.getEnvironment().getPropertySources().addLast(
                env.getPropertySources().get("chat-provider-timeout-defaults"));
        java.util.concurrent.atomic.AtomicReference<Duration> boundTimeout =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.boot.context.properties.bind.Binder.get(ctx.getEnvironment())
                .bind("spring.ai.openai",
                        org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties.class)
                .ifBound(props -> boundTimeout.set(props.getTimeout()));

        assertThat(boundTimeout.get())
                .as("Spring AI 真实绑定后必须拿到 600s，否则修复无效（会退回它自己的 60s 默认值）")
                .isEqualTo(Duration.ofSeconds(600));

        assertThat(org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties.DEFAULT_TIMEOUT)
                .as("前提校验：Spring AI 自身的默认值确实是 60s —— 这正是本次要绕过的坑")
                .isEqualTo(Duration.ofSeconds(60));
    }
}