package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识空间全局关闭开关的前端契约(方案 A+B):
 * features 端点探测 → knowledge=false 时隐藏 #ks-button;探测失败 fail-open。
 */
@DisplayName("知识空间开关前端契约")
class KnowledgeFeatureGateContractTest {

    private String appJs() throws IOException {
        try (var in = getClass().getResourceAsStream(
                "/META-INF/resources/spring/ai/loom/app.js")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("API 表注册 features 端点")
    void featuresEndpointRegistered() throws IOException {
        assertThat(appJs()).contains("features: \"/spring/ai/loom/api/features\"");
    }

    @Test
    @DisplayName("loadFeatures 存在且 fail-open(探测失败返回 knowledge:true,不误藏入口)")
    void loadFeaturesFailsOpen() throws IOException {
        String source = appJs();
        assertThat(source).contains("async loadFeatures()");
        assertThat(source).contains("return { knowledge: true }");
        assertThat(source).contains("state.features = await api.loadFeatures();");
    }

    @Test
    @DisplayName("knowledge=false → 隐藏 #ks-button(知识空间入口)")
    void ksButtonHiddenWhenKnowledgeDisabled() throws IOException {
        String source = appJs();
        assertThat(source).contains("state.features.knowledge === false");
        // 隐藏动作绑定在 features 探测块内(getElementById + display:none)
        int gateIdx = source.indexOf("state.features.knowledge === false");
        int hideIdx = source.indexOf("ks.style.display = \"none\"", gateIdx);
        assertThat(hideIdx).isGreaterThan(gateIdx);
    }

    @Test
    @DisplayName("state.features 默认 knowledge:true(旧后端无 features 端点时不闪隐)")
    void stateFeaturesDefaultsTrue() throws IOException {
        assertThat(appJs()).contains("features: { knowledge: true }");
    }
}
