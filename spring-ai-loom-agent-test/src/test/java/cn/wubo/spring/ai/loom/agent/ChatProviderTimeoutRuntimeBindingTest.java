package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文级回归锁:库侧 {@code ChatProviderTimeoutDefaultsBeanFactoryPostProcessor} 注入的
 * {@code spring.ai.openai.timeout} 默认值,必须在<b>真实应用上下文</b>里落到 Spring AI
 * 真正消费的那个 properties bean 上({@code OpenAiCommonProperties.getTimeout()},
 * 经 {@code SpringAiOpenAiHttpClient} 映射为 okhttp callTimeout)。
 *
 * <p><b>为什么需要</b>(2026-11-08 实测):{@code ChatProviderTimeoutDefaultTest} 全绿,
 * 但运行态长流仍在 60.0s 处被 okhttp callTimeout 掐断(readMediaFile 轮 18:33:22.216→
 * 18:34:22.233 = 60.017s;无附件纯长流探针 19:02:40→19:03:41)。单元级锁测的是
 * "BFPP 往 Environment 里写键 + binder 能绑",测不到"真实上下文里 BFPP 是否执行、
 * 绑出来的值是否喂给了 okhttp"这一整段 —— 本类就是那段缺失的锁。
 *
 * <p>变异自证方向:把 BFPP 的 {@code addLast} 改注释 / 把 {@code containsProperty}
 * 守卫改成恒 true,BFPP 静默失效时本类必须变红。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ChatProviderTimeoutRuntimeBindingTest {

    @Autowired
    private Environment environment;

    @Autowired
    private OpenAiCommonProperties commonProperties;

    @Test
    void environmentCarriesLibraryDefaultTimeout() {
        assertThat(environment.getProperty("spring.ai.openai.timeout"))
                .as("test yml 未显式配置 spring.ai.openai.timeout,库侧默认值必须在场")
                .isEqualTo("PT600S");
    }

    @Test
    void springAiConsumesLibraryDefaultTimeout() {
        assertThat(commonProperties.getTimeout())
                .as("Spring AI 真消费的 timeout 必须是库侧 600s,不是它自己的 60s 硬默认")
                .isEqualTo(Duration.ofSeconds(600));
    }
}
