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

import static org.junit.jupiter.api.Assertions.*;
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
        router = new LoomAgentConfiguration.WebConfiguration()
                .loomAgentKnowledgeRouter(knowledge, upload, mock(IFile.class));
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
}
