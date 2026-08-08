package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.model.AdminConversationView;
import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import cn.wubo.spring.ai.loom.agent.user.IUserConversation;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationRouterTest {

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void createAndRenameRoutesDelegateToCurrentUserScopedService() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        when(conversations.create("tmp-conv-1"))
                .thenReturn(new ConversationRecord("tmp-id", "tmp-conv-1", null, null));
        when(conversations.rename("tmp-id", "tmp-conv-renamed")).thenReturn(1);
        RouterFunction<ServerResponse> router = router(conversations);
        UserContextHolder.setCurrentUser("alice");

        ServerResponse created = route(router, "POST", "/spring/ai/loom/user-conversations",
                "{\"title\":\"tmp-conv-1\"}");
        ServerResponse renamed = route(router, "PATCH", "/spring/ai/loom/user-conversations/tmp-id",
                "{\"title\":\"tmp-conv-renamed\"}");

        assertThat(created.statusCode().value()).isEqualTo(201);
        assertThat(renamed.statusCode().value()).isEqualTo(200);
        verify(conversations).create("tmp-conv-1");
        verify(conversations).rename("tmp-id", "tmp-conv-renamed");
    }

    @Test
    void renameReturnsForbiddenWhenConversationIsNotOwned() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        when(conversations.rename("tmp-bob-id", "tmp-stolen-title")).thenReturn(0);
        RouterFunction<ServerResponse> router = router(conversations);
        UserContextHolder.setCurrentUser("alice");

        ServerResponse response = route(router, "PATCH", "/spring/ai/loom/user-conversations/tmp-bob-id",
                "{\"title\":\"tmp-stolen-title\"}");

        assertThat(response.statusCode().value()).isEqualTo(403);

        // The body MUST be a structured error object so the frontend can distinguish
        // a cross-user 403 from a generic 500/network failure. The previous bare
        // `body(false)` was indistinguishable from a transport drop.
        Object entity = readBody(response);
        assertThat(entity).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity;
        assertThat(body).containsEntry("error", "forbidden");
        assertThat(body).containsEntry("code", 403);
    }

    @Test
    void getReturnsForbiddenWhenConversationIsNotOwned() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        when(conversations.exists(any())).thenReturn(false);
        RouterFunction<ServerResponse> router = router(conversations);
        UserContextHolder.setCurrentUser("alice");

        ServerResponse response = route(router, "GET", "/spring/ai/loom/conversation/tmp-bob-id", "");

        assertThat(response.statusCode().value()).isEqualTo(403);
        verify(conversations).exists(argThat(record ->
                "alice".equals(record.username()) && "tmp-bob-id".equals(record.conversationId())));
    }

    @Test
    void getReturnsMessagesWhenConversationIsOwned() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        when(conversations.exists(any())).thenReturn(true);
        JdbcChatMemoryRepository memory = mock(JdbcChatMemoryRepository.class);
        when(memory.findByConversationId("tmp-alice-id")).thenReturn(java.util.List.of());
        RouterFunction<ServerResponse> router = router(conversations, memory);
        UserContextHolder.setCurrentUser("alice");

        ServerResponse response = route(router, "GET", "/spring/ai/loom/conversation/tmp-alice-id", "");

        assertThat(response.statusCode().value()).isEqualTo(200);
        verify(memory).findByConversationId("tmp-alice-id");
    }

    @Test
    void adminCanReadOwnedTargetConversationMessages() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        JdbcChatMemoryRepository memory = mock(JdbcChatMemoryRepository.class);
        when(conversations.adminListByUsername("bob")).thenReturn(java.util.List.of(
                new AdminConversationView("bob-conv", "bob", "Bob", "preview", null, null, null, false)));
        when(memory.findByConversationId("bob-conv")).thenReturn(java.util.List.of());
        RouterFunction<ServerResponse> router = router(conversations, memory);

        ServerRequest request = ServerRequest.create(
                buildServletRequest("GET", "/spring/ai/loom/admin/conversations/bob-conv/messages", "username=bob"),
                java.util.List.of(new MappingJackson2HttpMessageConverter()));
        ServerResponse response = router.route(request).orElseThrow().handle(request);

        assertThat(response.statusCode().value()).isEqualTo(200);
        verify(memory).findByConversationId("bob-conv");
    }

    @Test
    void adminMessageRouteReturnsNotFoundForUnknownConversation() throws Exception {
        IUserConversation conversations = mock(IUserConversation.class);
        when(conversations.adminListByUsername("bob")).thenReturn(java.util.List.of());
        RouterFunction<ServerResponse> router = router(conversations);

        ServerRequest request = ServerRequest.create(
                buildServletRequest("GET", "/spring/ai/loom/admin/conversations/missing/messages", "username=bob"),
                java.util.List.of(new MappingJackson2HttpMessageConverter()));
        ServerResponse response = router.route(request).orElseThrow().handle(request);

        assertThat(response.statusCode().value()).isEqualTo(404);
    }

    private static RouterFunction<ServerResponse> router(IUserConversation conversations) {
        return router(conversations, mock(JdbcChatMemoryRepository.class));
    }

    private static RouterFunction<ServerResponse> router(IUserConversation conversations,
                                                         JdbcChatMemoryRepository memory) {
        LoomAgentConfiguration.WebConfiguration configuration = new LoomAgentConfiguration.WebConfiguration();
        return configuration.loomAgentConversationRouter(
                memory,
                conversations,
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static MockHttpServletRequest buildServletRequest(String method, String path, String query) {
        String pathOnly = path;
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, pathOnly);
        servletRequest.setRequestURI(pathOnly);
        servletRequest.setServletPath(pathOnly);
        servletRequest.setPathInfo(pathOnly);
        if (query != null) {
            servletRequest.setQueryString(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    servletRequest.addParameter(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        servletRequest.setContentType("application/json");
        return servletRequest;
    }

    private static ServerResponse route(RouterFunction<ServerResponse> router,
                                        String method,
                                        String path,
                                        String body) throws Exception {
        String[] pathParts = path.split("\\?", 2);
        String pathOnly = pathParts[0];
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, pathOnly);
        servletRequest.setRequestURI(pathOnly);
        servletRequest.setServletPath(pathOnly);
        servletRequest.setPathInfo(pathOnly);
        if (pathParts.length > 1) servletRequest.setQueryString(pathParts[1]);
        servletRequest.setContentType("application/json");
        servletRequest.setContent(body.getBytes(StandardCharsets.UTF_8));
        ServerRequest request = ServerRequest.create(
                servletRequest, java.util.List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }
    private static Object readBody(ServerResponse response) {
        // The PATCH 403 path uses .body(Map) which produces an EntityResponse whose
        // entity() exposes the body directly. We don't need to round-trip through
        // HttpMessageConverters to verify the body shape.
        return ((org.springframework.web.servlet.function.EntityResponse<?>) response).entity();
    }
}
