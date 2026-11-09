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
     * 真凶锁(2026-11-09 定位):Spring AI 2.0.1 的 {@code OpenAiChatOptions.timeout}
     * <b>无法经属性配置</b>({@code OpenAiChatProperties.toOptions()} 不映射 timeout,
     * 构造时回落到 {@code AbstractOpenAiOptions.DEFAULT_TIMEOUT = PT1M});而
     * {@code OpenAiChatModel.buildRequestOptions(Prompt)} 把 options.timeout 塞进
     * per-request {@code RequestOptions.timeout},{@code SpringAiOpenAiHttpClient.newCall()}
     * 再对每个请求 {@code newBuilder()} 重建 client 并<b>覆盖</b> client 级 callTimeout
     * —— 所以 client 级 600s 永远被 per-request 60s 压掉,流稳定死在 60.0s。
     *
     * <p>loom 的修复面:在 {@code chatClient} bean 上挂 {@code defaultOptions(
     * modelOptions.mutate().timeout(...))} —— prompt 级 options 合并后带 600s,
     * per-request 覆盖值随之变成 600s。本测试钉死该 customizer 在场且 timeout=600s;
     * 变异方向:删掉 chatClient 的 defaultOptions 行 ⇒ customizer 为 null ⇒ 变红。
     *
     * <p>注意:模型自身的 default options 仍是 60s(属性面不可配,Spring AI 缺陷),
     * 故本锁<b>不</b>断言模型层,断言 loom 可控的 ChatClient 层。
     */
    @Test
    void chatClientDefaultOptionsCarryTimeoutOverride() throws Exception {
        assertThat(chatClient)
                .as("loom chatClient 必须是 DefaultChatClient(反射链前提)")
                .isInstanceOf(org.springframework.ai.chat.client.DefaultChatClient.class);
        Object spec = getField(chatClient, "defaultChatClientRequest");
        Object customizer = getField(spec, "optionsCustomizer");
        assertThat(customizer)
                .as("chatClient 必须挂 defaultOptions customizer,否则 per-request 60s 覆盖无解")
                .isNotNull();
        assertThat(customizer).isInstanceOf(org.springframework.ai.chat.prompt.ChatOptions.Builder.class);
        org.springframework.ai.chat.prompt.ChatOptions built =
                ((org.springframework.ai.chat.prompt.ChatOptions.Builder<?>) customizer).build();
        assertThat(built).isInstanceOf(org.springframework.ai.openai.OpenAiChatOptions.class);
        Duration timeout = ((org.springframework.ai.openai.OpenAiChatOptions) built).getTimeout();
        System.out.println("[wiring-probe] chatClient defaultOptions timeout = " + timeout);
        assertThat(timeout)
                .as("prompt 级 timeout 必须 ≥600s,per-request 覆盖才不会把流掐在 60s")
                .isEqualTo(Duration.ofSeconds(600));
    }
}
