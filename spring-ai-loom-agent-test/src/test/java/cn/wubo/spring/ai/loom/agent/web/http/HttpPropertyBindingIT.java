package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺陷回归锁:{@code spring.ai.loom.agent.http.*} 的 yml 绑定链路。
 *
 * <p><b>缺陷来源</b>(2026-10-03 Chrome 端到端实测发现):
 * {@code LoomAgentConfiguration.loomAgentProperties} 是<b>手工逐字段拷贝</b>的 ——
 * {@code binder.bind("spring.ai.loom.agent", …)} 拿到的 {@code bound} 对象要一行行
 * {@code properties.setXxx(bound.getXxx())} 搬进真正被消费的 {@code properties}。
 * 那段拷贝<b>漏了 {@code http}</b> ⇒ {@code bound.getHttp()} 从未被搬运。
 *
 * <p><b>为什么难发现</b>:除 {@code allowedDomains} 外,其余 6 个配置项的默认值
 * 都是"可用的常用值"({@code timeoutMs=10000}、{@code maxBatchConcurrency=10} …),
 * 漏拷贝时不显形。唯独 {@code allowedDomains} 默认空集 + {@code DefaultHttpTool:43}
 * 硬编码 {@code failClosed=true} ⇒ 组合结果是<b>开箱即全灭</b>:
 * 一切 {@code invokeEndpoint} 返回 {@code DomainNotAllowed},
 * 且用户<b>无论怎么配 yml 都救不回来</b>。
 *
 * <p><b>本类锁的是绑定链路本身</b>,不依赖 LLM、不依赖外网、不发真实请求 ——
 * 只要 yml 写进了 {@code LoomAgentProperties} bean,契约就成立。
 * 真实调用链路由 {@code HttpToolInvokeIT}(不过 LLM)与
 * {@code HttpToolBrowserIT}(真实 Chrome + LLM)分别覆盖。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class,
        properties = {
                // 用一个哨兵值:若绑定链路通,bean 里就能读到它;若漏拷贝,读到的仍是默认空集。
                "spring.ai.loom.agent.http.allowed-domains[0]=example.invalid",
                "spring.ai.loom.agent.http.timeout-ms=31337",
                "spring.ai.loom.agent.http.max-batch-concurrency=7",
                "spring.ai.loom.agent.http.history-max-entries-per-system=42",
                "spring.ai.loom.agent.http.max-response-size-bytes=2048",
                "spring.ai.loom.agent.http.max-request-body-bytes=4096"
        })
@DisplayName("embedded HTTP 工具的 yml 绑定链路(缺陷回归锁)")
class HttpPropertyBindingIT {

    @Autowired
    private LoomAgentProperties properties;

    @Test
    @DisplayName("allowedDomains 被搬运到真正被消费的 properties(不是默认空集)")
    void allowedDomainsReachesProperties() {
        assertThat(properties.getHttp().getAllowedDomains())
                .as("spring.ai.loom.agent.http.allowed-domains 必须真的生效 —— "
                        + "漏拷贝会让它恒为空集,而 DefaultHttpTool 硬编码 fail-closed,"
                        + "组合结果是开箱即全灭且无法通过配置修复")
                .contains("example.invalid");
    }

    @Test
    @DisplayName("timeoutMs 被搬运(默认 10000,哨兵 31337)")
    void timeoutMsReachesProperties() {
        assertThat(properties.getHttp().getTimeoutMs()).isEqualTo(31337L);
    }

    @Test
    @DisplayName("maxBatchConcurrency 被搬运(默认 10,哨兵 7)")
    void maxBatchConcurrencyReachesProperties() {
        assertThat(properties.getHttp().getMaxBatchConcurrency()).isEqualTo(7);
    }

    @Test
    @DisplayName("historyMaxEntriesPerSystem 被搬运(默认 1000,哨兵 42)")
    void historyMaxEntriesReachesProperties() {
        assertThat(properties.getHttp().getHistoryMaxEntriesPerSystem()).isEqualTo(42);
    }

    @Test
    @DisplayName("maxResponseSizeBytes 被搬运(默认 1MB,哨兵 2048)")
    void maxResponseSizeReachesProperties() {
        assertThat(properties.getHttp().getMaxResponseSizeBytes()).isEqualTo(2048L);
    }

    @Test
    @DisplayName("maxRequestBodyBytes 被搬运(默认 10MB,哨兵 4096)")
    void maxRequestBodyReachesProperties() {
        assertThat(properties.getHttp().getMaxRequestBodyBytes()).isEqualTo(4096L);
    }

    /**
     * 防"未来又漏一个字段"的泛化锁:逐字段比对 bound 与 properties。
     * 这条比上面 6 条都强 —— 它在字段数变化时会立刻失败,提醒补断言。
     */
    @Test
    @DisplayName("HttpProperty 每个字段都必须能从 yml 到达(防将来再漏拷贝)")
    void everyHttpFieldIsCopied() {
        var http = properties.getHttp();
        assertThat(http).isNotNull();
        // 逐字段断言:任何一个字段漏拷贝,这里会给出明确的字段名
        assertThat(http.isEnabled()).as("enabled").isTrue();
        assertThat(http.getAllowedDomains()).as("allowedDomains").isNotEmpty();
        assertThat(http.getTimeoutMs()).as("timeoutMs").isNotEqualTo(10_000L);
        assertThat(http.getMaxResponseSizeBytes()).as("maxResponseSizeBytes").isNotEqualTo(1024L * 1024L);
        assertThat(http.getMaxBatchConcurrency()).as("maxBatchConcurrency").isNotEqualTo(10);
        assertThat(http.getMaxRequestBodyBytes()).as("maxRequestBodyBytes").isNotEqualTo(10L * 1024 * 1024);
        assertThat(http.getHistoryMaxEntriesPerSystem()).as("historyMaxEntriesPerSystem").isNotEqualTo(1000);
    }
}
