package cn.wubo.spring.ai.loom.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用版本读取的回归锁。
 *
 * <p><b>为什么需要它</b>：版本号此前在前端 `app.js` 的 {@code ABOUT_INFO.version}
 * 里硬编码，与 `pom.xml` / `docs/API.md` 各说各话（实测三处不一致）。
 * 本类从 jar 里读真实版本，使其成为单一真源 —— 改 pom 即生效，无需改前端。
 *
 * <p><b>数据来源</b>：Maven **默认**在 jar 里生成
 * {@code META-INF/maven/{groupId}/{artifactId}/pom.properties}，其中含 {@code version}。
 * 无需配置 {@code spring-boot-maven-plugin} 的 {@code build-info} goal
 * （那会额外要求打包阶段，而 {@code mvn spring-boot:run} 下不一定有）。
 *
 * <p>降级链：pom.properties → MANIFEST 的 {@code Implementation-Version} → "unknown"。
 */
@DisplayName("应用版本读取")
class AppVersionTest {

    @Test
    @DisplayName("能从 classpath 读到版本号，且不是 unknown")
    void readsVersionFromClasspath() {
        String v = AppVersion.get();
        assertThat(v)
                .as("必须能读到版本 —— 读不到会显示 unknown，用户就看不到真实版本")
                .isNotBlank()
                .isNotEqualTo("unknown");
        // 本仓库的版本形如 1.0-SNAPSHOT / 1.2.0 / 1.1.48
        assertThat(v)
                .as("应是 Maven 版本号形态（数字开头）")
                .matches("^\\d+.*");
    }

    @Test
    @DisplayName("与 pom.properties 里记录的版本一致（不是硬编码）")
    void matchesPomProperties() throws Exception {
        // 直接从 classpath 读 pom.properties 作为对照真源
        String path = "META-INF/maven/io.github.wb04307201/spring-ai-loom-agent/pom.properties";
        try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("jar 里必须有 pom.properties（Maven 默认生成）").isNotNull();
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String fromJar = p.getProperty("version");
            assertThat(AppVersion.get())
                    .as("读取结果必须等于 jar 里记录的版本 —— 证明是读来的而非写死的")
                    .isEqualTo(fromJar);
        }
    }

    @Test
    @DisplayName("重复调用返回相同结果（缓存不引入漂移）")
    void isStableAcrossCalls() {
        assertThat(AppVersion.get()).isEqualTo(AppVersion.get());
    }
}
