package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.file.IUpload;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 知识库路由抽测 —— can-edit 透传、DELETE 委派 deleteAllKnowledge。
 */
@DisplayName("knowledge router 抽测")
class KnowledgeRouterAuthzTest {

    private IKnowledge knowledge;
    private IUpload upload;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        UserContextHolder.setCurrentUser("alice");
        knowledge = mock(IKnowledge.class);
        upload = mock(IUpload.class);
        router = routerWith(upload, mock(org.springframework.ai.vectorstore.VectorStore.class));
    }

    /** 组装知识路由:IUpload 与 VectorStore 各自可缺席(降级态组合)。 */
    private RouterFunction<ServerResponse> routerWith(IUpload u,
                                                       org.springframework.ai.vectorstore.VectorStore vs) {
        return new LoomAgentConfiguration.WebConfiguration()
                .loomAgentKnowledgeRouter(knowledge, prov(u), prov(vs), mock(IFile.class));
    }

    /** ObjectProvider 测试替身:getIfAvailable 返回给定实例(null = 该依赖缺席)。 */
    static <T> org.springframework.beans.factory.ObjectProvider<T> prov(T instance) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                if (instance == null) throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("bean");
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private ServerResponse route(String method, String path) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }

    @Test
    @DisplayName("can-edit：透传 knowledge.canEdit 结果")
    @SuppressWarnings("unchecked")
    void canEditPassthrough() throws Exception {
        when(knowledge.canEdit("kb-1")).thenReturn(false);

        ServerResponse response = route("GET", "/spring/ai/loom/knowledge/kb-1/can-edit");

        assertEquals(200, response.statusCode().value());
        Map<String, Object> body = (Map<String, Object>) ((EntityResponse<?>) response).entity();
        assertEquals(false, body.get("canEdit"));
        verify(knowledge).canEdit("kb-1");
    }

    @Test
    @DisplayName("DELETE 知识库：委派 upload.deleteAllKnowledge")
    void deleteDelegatesToUpload() throws Exception {
        when(upload.deleteAllKnowledge("kb-1")).thenReturn(3);

        ServerResponse response = route("DELETE", "/spring/ai/loom/knowledge/kb-1");

        assertEquals(200, response.statusCode().value());
        verify(upload).deleteAllKnowledge("kb-1");
    }

    @Test
    @DisplayName("checkKnowledgeUpload：IUpload 在场 → true")
    void checkKnowledgeUploadWithUploadReturnsTrue() throws Exception {
        ServerResponse response = route("GET", "/spring/ai/loom/knowledge/checkKnowledgeUpload");
        assertEquals(200, response.statusCode().value());
        assertEquals(Boolean.TRUE, ((EntityResponse<?>) response).entity());
    }

    // ===== 降级态组合(2026-09-19 解耦后:判据 = VectorStore 缺席;IUpload 恒在)=====

    /** 知识链路降级:IUpload 在、VectorStore 缺席(无 embedding 部署的真实形态)。 */
    private RouterFunction<ServerResponse> degradedRouter() {
        return routerWith(upload, null);
    }

    private ServerResponse routeDegraded(String method, String path) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        return degradedRouter().route(request).orElseThrow().handle(request);
    }

    @Test
    @DisplayName("降级(VectorStore 缺席):checkKnowledgeUpload → false(知识空间 UI 据此隐藏上传入口)")
    void checkKnowledgeUploadWithoutVectorStoreReturnsFalse() throws Exception {
        ServerResponse response = routeDegraded("GET", "/spring/ai/loom/knowledge/checkKnowledgeUpload");
        assertEquals(200, response.statusCode().value());
        assertEquals(Boolean.FALSE, ((EntityResponse<?>) response).entity());
    }

    @Test
    @DisplayName("降级(VectorStore 缺席):DELETE 知识库仍委派 upload.deleteAllKnowledge(向量清理内部跳过)")
    void deleteWithUploadButNoVectorStoreStillDelegates() throws Exception {
        when(upload.deleteAllKnowledge("kb-1")).thenReturn(2);

        ServerResponse response = routeDegraded("DELETE", "/spring/ai/loom/knowledge/kb-1");

        assertEquals(200, response.statusCode().value());
        verify(upload).deleteAllKnowledge("kb-1");
    }

    /** IUpload 整体缺席(消费方显式排除的极端上下文):DELETE KB 降级为仅删元数据。 */
    private ServerResponse routeNoUpload(String method, String path) throws Exception {
        RouterFunction<ServerResponse> r = routerWith(null, null);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
        return r.route(request).orElseThrow().handle(request);
    }

    @Test
    @DisplayName("IUpload 缺席:DELETE 知识库 → 仅删元数据(knowledge.delete),不触碰 IUpload")
    void deleteWithoutUploadFallsBackToMetadataDelete() throws Exception {
        when(knowledge.delete("kb-1")).thenReturn(1);

        ServerResponse response = routeNoUpload("DELETE", "/spring/ai/loom/knowledge/kb-1");

        assertEquals(200, response.statusCode().value());
        // 降级分支直接返回 knowledge.delete 的行数(Integer),不包 Map
        assertEquals(1, ((EntityResponse<?>) response).entity());
        verify(knowledge).delete("kb-1");
    }

    @Test
    @DisplayName("降级(VectorStore 缺席)：KB 文件上传 → 503 + 明确 message")
    @SuppressWarnings("unchecked")
    void uploadWithoutUploadReturns503() throws Exception {
        ServerResponse response = routeDegraded("POST", "/spring/ai/loom/knowledge/kb-1/upload");
        assertEquals(503, response.statusCode().value());
        Map<String, Object> body = (Map<String, Object>) ((EntityResponse<?>) response).entity();
        assertEquals("知识库文件功能未启用(知识空间未启用/embedding 未配置)", body.get("message"));
    }

    @Test
    @DisplayName("降级：KB 元数据 CRUD 保留 —— can-edit 照常透传")
    void canEditStillWorksDegraded() throws Exception {
        when(knowledge.canEdit("kb-1")).thenReturn(true);

        ServerResponse response = routeDegraded("GET", "/spring/ai/loom/knowledge/kb-1/can-edit");

        assertEquals(200, response.statusCode().value());
        verify(knowledge).canEdit("kb-1");
    }
}
