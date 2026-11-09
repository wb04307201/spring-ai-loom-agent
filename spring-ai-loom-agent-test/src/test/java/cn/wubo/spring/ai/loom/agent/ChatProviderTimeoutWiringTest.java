package cn.wubo.spring.ai.loom.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行态装配锁:{@code spring.ai.openai.timeout}(库侧 BFPP 默认 600s)必须真正落到
 * Spring AI 流式路径使用的 okhttp client 上。
 *
 * <p><b>缺陷现场(2026-11-08)</b>:yml 显式 600s / BFPP 默认 PT600S 都绑进了
 * {@code OpenAiCommonProperties}(RuntimeBindingTest 绿),但真实流式请求仍精确
 * 60.0±1s 被 {@code okhttp3.RealCall.timeoutExit → InterruptedIOException: timeout}
 * 掐断 ⇒ 断点在 properties → OkHttpClient 之间。本测试反射到真实的
 * {@code okhttp3.OkHttpClient.callTimeoutMillis()},把断点钉死:
 * <ul>
 *   <li>client 级就是 60s ⇒ Spring AI autoconfig 没把属性喂给 client 构建;</li>
 *   <li>client 级是 600s ⇒ 是 per-call {@code RequestOptions.timeout} 在
 *       {@code SpringAiOpenAiHttpClient.newCall()} 里覆盖了 client 级配置
 *       (该方法对每个请求 newBuilder() 重建并覆写 callTimeout)。</li>
 * </ul>
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ChatProviderTimeoutWiringTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private org.springframework.ai.chat.client.ChatClient chatClient;

    private static Object getField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found on " + target.getClass());
    }

    /** OpenAiChatModel → openAiClientAsync(Impl 字段 clientOptions) → httpClient → OkHttpClient */
    private long callTimeoutMillisOfAsyncClient() throws Exception {
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        Object asyncClient = getField(chatModel, "openAiClientAsync");
        Object options = getField(asyncClient, "clientOptions");
        // ClientOptions.httpClient 是装饰器链(RetryingHttpClient → LoggingHttpClient → …),
        // 沿 HttpClient 类型字段一路剥到 Spring AI 的 SpringAiOpenAiHttpClient
        Object httpClient = getField(options, "httpClient");
        java.util.List<String> chain = new java.util.ArrayList<>();
        while (!(httpClient instanceof org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient)) {
            chain.add(httpClient.getClass().getSimpleName());
            Object next = firstHttpClientField(httpClient);
            if (next == null) break;
            httpClient = next;
        }
        System.out.println("[wiring-probe] decorator chain = " + chain);
        assertThat(httpClient)
                .as("装饰器链末端必须是 SpringAiOpenAiHttpClient")
                .isInstanceOf(org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.class);
        Object okHttp = getField(httpClient, "okHttpClient");
        return (int) okHttp.getClass().getMethod("callTimeoutMillis").invoke(okHttp);
    }

    private static Object firstHttpClientField(Object wrapper) throws Exception {
        Class<?> c = wrapper.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (com.openai.core.http.HttpClient.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(wrapper);
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @Test
    void streamingOkHttpCarriesConfiguredCallTimeout() throws Exception {
        long millis = callTimeoutMillisOfAsyncClient();
        System.out.println("[wiring-probe] async client callTimeoutMillis = " + millis);
        assertThat(Duration.ofMillis(millis))
                .as("流式 okhttp client 的 callTimeout 必须是配置的 600s,而非 SDK 60s 硬默认")
                .isEqualTo(Duration.ofSeconds(600));
    }

    /**
     * 上游修复锁(Spring AI 2.0.2 / PR #6837,2026-11-09 升级):
     * 2.0.1 的缺陷是 {@code OpenAiChatOptions.getTimeout()} 永不返回 null(未设时回落
     * {@code DEFAULT_TIMEOUT=60s}),且 {@code toOptions()} 不映射 timeout ⇒ 每请求都被
     * per-request RequestOptions.timeout=60s 覆盖 client 级 callTimeout,长流精确 60.0s 死。
     * 2.0.2 起:未设时 timeout 为 null(继承 client 配置),autoconfig 把 *resolved* timeout
     * 注入 chat model 的 default options。
     *
     * <p>本锁钉死升级后契约:{@code spring.ai.openai.timeout=600s}(test yml 显式 +
     * 库侧 BFPP 双通道)必须体现在 model default options 上 —— 要么 null(继承 client 级
     * 600s),要么 ≥600s;<b>绝不允许是 60s</b>。变异方向:把 pom 降回 2.0.1 ⇒
     * getTimeout()=PT1M ⇒ 红(这正是升级的意义所在)。
     *
     * <p>同时断言 loom 不再挂 chatClient optionsCustomizer —— 2.0.2 后 f6fc16b0 的
     * 反射 override 已删(上游根治,库侧补丁退役),customizer 应为 null。
     */
    @Test
    void modelDefaultOptionsHonorConfiguredTimeout_afterUpstreamFix() throws Exception {
        assertThat(chatModel)
                .as("前提:当前 provider 是 OpenAI(2.0.2 契约)")
                .isInstanceOf(OpenAiChatModel.class);
        org.springframework.ai.openai.OpenAiChatOptions modelOpts =
                ((OpenAiChatModel) chatModel).getOptions();
        Duration timeout = modelOpts.getTimeout();
        System.out.println("[wiring-probe] model defaultOptions timeout = " + timeout);
        assertThat(timeout == null || timeout.compareTo(Duration.ofSeconds(600)) >= 0)
                .as("2.0.2(#6837)后 model options timeout 必须为 null(继承 client)或 ≥600s,绝不能是 60s 陷阱")
                .isTrue();

        // f6fc16b0 的库侧 override 已退役:chatClient 不得再挂 optionsCustomizer
        assertThat(chatClient)
                .as("反射链前提")
                .isInstanceOf(org.springframework.ai.chat.client.DefaultChatClient.class);
        Object spec = getField(chatClient, "defaultChatClientRequest");
        Object customizer = getField(spec, "optionsCustomizer");
        assertThat(customizer)
                .as("上游已根治 per-request 覆盖,库侧反射 override 应已删除(customizer 为 null)")
                .isNull();
    }
}
