package cn.wubo.spring.ai.loom.agent.tool;

import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * ：把 {@link ToolCallLogObservationHandler} 注册到 Spring AI 用的
 * {@link ObservationRegistry}。Spring AI 1.1+ 的 tool call observation
 * 走 {@code GlobalObservationRegistry}，handler 必须显式
 * {@code observationConfig().observationHandler(...)} 才能收到事件。
 *
 * <p>用 {@link PostConstruct} 直接从 Spring 容器拿 {@link ObservationRegistry}
 * bean（Spring Boot 自动配置）然后注册 handler。不需要 spring-boot-actuator。
 *
 * <p>默认 <b>禁用</b>。原因：Spring AI 1.1.7 DashScope 流式 chunk 让
 * onStart+onStop 各写一次 → 1 次实际工具调用产生 2 行 DB（与真实调用次数严重不符）。
 * 修复后唯一写入入口改为 {@link LoggingToolCallback}（DefaultChat.stream() 用
 * {@code ToolCallbacks.from(embedTools.toArray())} 预生成 MethodToolCallback 再 wrap）。
 * 如需旧行为（双写），设 {@code spring.ai.loom.agent.tool-observation.enabled=true}。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.ai.loom.agent.tool-observation.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ToolCallLogObservationConfig {

    private final ObservationRegistry observationRegistry;
    private final ToolCallLogObservationHandler handler;

    @PostConstruct
    public void register() {
        observationRegistry.observationConfig().observationHandler(handler);
        // ：开启 Reactor 自动 ThreadLocal 传播，让 ToolCallContextHolder
        // 跨 async/stream 边界可见（SseController 在 runAsync block 设，
        // ObservationHandler 在 Reactor stream 线程读）
        Hooks.enableAutomaticContextPropagation();
        log.info(" ToolCallLogObservationHandler registered with ObservationRegistry + reactor Hooks enabled");
    }
}
