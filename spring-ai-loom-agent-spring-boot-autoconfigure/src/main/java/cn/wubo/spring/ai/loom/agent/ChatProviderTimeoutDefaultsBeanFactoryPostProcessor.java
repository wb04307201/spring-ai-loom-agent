package cn.wubo.spring.ai.loom.agent;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 补齐聊天链路（LLM provider 连接）的缺省超时，仅在消费方未显式配置时生效。
 *
 * <p><b>为什么需要</b>：Spring AI 2.0.1 的
 * {@code AbstractOpenAiProperties.DEFAULT_TIMEOUT} 硬编码 {@code Duration.ofSeconds(60)}，
 * 并经 {@code OpenAiChatAutoConfiguration} 施加到 okhttp {@code callTimeout}
 * —— <b>整体</b>超时而非 readTimeout。长思考 + tool_use 的流式请求会稳定在 60s 处被
 * 整体掐断，底层 {@code StreamResetException: stream was reset: CANCEL}，且流式帧仍活跃
 * （实测 60.095s / 1174 帧 / 每秒有帧，{@code finish_reason} 从未出现 ⇒ 非 token 上限收尾）。
 *
 * <p><b>为什么"复发"</b>：本仓 2026-09-30 已因同一症状给 {@code anthropic} 段打过
 * {@code timeout: 600s} 补丁（见 test app application.yml 注释），但当前生效的
 * {@code openai} 段无此配置 ⇒ 同一 bug 原样复现。只补一个段的教训是：这类默认值要沉到库侧。
 *
 * <p><b>优先级</b>：用 {@link MapPropertySource#addLast} 追加，优先级低于用户的
 * application.yml ⇒ <b>消费方显式配置一律优先，库绝不覆盖</b>。这与
 * {@link FileViewDefaultsBeanFactoryPostProcessor} 是同一套路。
 *
 * <p><b>取值来源</b>：{@code spring.ai.loom.agent.chat.timeout-seconds}（库侧配置项，
 * 默认 600）—— 部署方无需知道 provider 实现的差异即可调整。
 *
 * <p>回归锁：spring-ai-loom-agent-test 的 {@code ChatProviderTimeoutDefaultTest}。
 */
public class ChatProviderTimeoutDefaultsBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    /** Spring AI 对 OpenAI provider 的属性前缀（{@code @ConfigurationProperties("spring.ai.openai")}）。 */
    public static final String OPENAI_TIMEOUT_KEY = "spring.ai.openai.timeout";

    /** 库侧配置入口（{@code spring.ai.loom.agent.chat.timeout-seconds}）。 */
    public static final String LOOM_CHAT_TIMEOUT_SECONDS_KEY = "spring.ai.loom.agent.chat.timeout-seconds";

    private final ConfigurableEnvironment environment;
    private final long timeoutSeconds;

    public ChatProviderTimeoutDefaultsBeanFactoryPostProcessor(ConfigurableEnvironment environment,
                                                              long timeoutSeconds) {
        this.environment = environment;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 消费方已显式配置 → 完全让路，绝不改写（含"看起来不健康"的小值）。
        if (environment.containsProperty(OPENAI_TIMEOUT_KEY)) {
            return;
        }
        // 库侧配置被显式设为非正数时视为"不干预"，退回 provider 自身默认值，
        // 避免写入 0/负数让 Duration 解析直接抛异常导致启动失败。
        if (timeoutSeconds <= 0) {
            return;
        }

        Map<String, Object> defaults = new HashMap<>();
        //刻意存 ISO-8601 字符串而非 Duration 对象：MapPropertySource 里放Duration 时，
        // 任何按 String 读该键的代码都会因缺 Duration→String 转换器而抛异常；且重复施加
        // 不会像字符串拼接那样累积。Spring AI 的 binder 能把 "PT10M" 正常绑到 Duration。
        defaults.put(OPENAI_TIMEOUT_KEY, "PT" + timeoutSeconds + "S");

        PropertySource<?> defaultPropertySource = new MapPropertySource("chat-provider-timeout-defaults", defaults);
        environment.getPropertySources().addLast(defaultPropertySource);
    }
}