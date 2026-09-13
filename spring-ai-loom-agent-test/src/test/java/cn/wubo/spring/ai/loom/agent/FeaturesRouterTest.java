package cn.wubo.spring.ai.loom.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * /spring/ai/loom/api/features 端点单测(知识空间全局关闭开关,方案 A+B):
 * knowledge 标志 = 容器内 VectorStore bean 存在性。
 */
@DisplayName("features 端点")
class FeaturesRouterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> provider(VectorStore instance) {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getObject() {
                if (instance == null) throw new NoSuchBeanDefinitionException(VectorStore.class);
                return instance;
            }

            @Override
            public VectorStore getIfAvailable() {
                return instance;
            }

            @Override
            public VectorStore getIfUnique() {
                return instance;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callFeatures(VectorStore instance) throws Exception {
        RouterFunction<ServerResponse> router = new LoomAgentConfiguration.WebConfiguration()
                .loomAgentFeaturesRouter(provider(instance));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/spring/ai/loom/api/features");
        servletRequest.setRequestURI("/spring/ai/loom/api/features");
        servletRequest.setServletPath("/spring/ai/loom/api/features");
        ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        ServerResponse response = router.route(request).orElseThrow().handle(request);
        assertThat(response.statusCode().value()).isEqualTo(200);
        return (Map<String, Object>) ((EntityResponse<?>) response).entity();
    }

    @Test
    @DisplayName("VectorStore 在场 → {knowledge:true}")
    void vectorStorePresent_knowledgeTrue() throws Exception {
        assertThat(callFeatures(mock(VectorStore.class))).containsEntry("knowledge", true);
    }

    @Test
    @DisplayName("VectorStore 缺席(RAG 关闭)→ {knowledge:false}")
    void vectorStoreAbsent_knowledgeFalse() throws Exception {
        assertThat(callFeatures(null)).containsEntry("knowledge", false);
    }
}
