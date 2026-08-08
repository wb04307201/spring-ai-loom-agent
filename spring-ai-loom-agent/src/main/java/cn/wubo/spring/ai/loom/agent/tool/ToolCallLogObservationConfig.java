package cn.wubo.spring.ai.loom.agent.tool;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * V5.3：把 {@link ToolCallLogObservationHandler} 注册到 Spring AI 用的
 * {@link ObservationRegistry}。Spring AI 1.1+ 的 tool call observation
 * 走 {@code GlobalObservationRegistry}，handler 必须显式
 * {@code observationConfig().observationHandler(...)} 才能收到事件。
 *
 * <p>用 {@link PostConstruct} 直接从 Spring 容器拿 {@link ObservationRegistry}
 * bean（Spring Boot 自动配置）然后注册 handler。不需要 spring-boot-actuator。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ToolCallLogObservationConfig {

    private final ObservationRegistry observationRegistry;
    private final ToolCallLogObservationHandler handler;

    @PostConstruct
    public void register() {
        observationRegistry.observationConfig().observationHandler(handler);
        // V5.4：开启 Reactor 自动 ThreadLocal 传播，让 ToolCallContextHolder
        // 跨 async/stream 边界可见（SseController 在 runAsync block 设，
        // ObservationHandler 在 Reactor stream 线程读）
        Hooks.enableAutomaticContextPropagation();
        log.info("V5.3 ToolCallLogObservationHandler registered with ObservationRegistry + reactor Hooks enabled");
    }
}
