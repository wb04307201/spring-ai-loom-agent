package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarFrontendContractTest {

 @Test
 void sidebarUsesPersistedCreateRenameAndDeleteResult() throws IOException {
 String source;
 try (var in = getClass().getResourceAsStream(
 "/META-INF/resources/spring/ai/loom/app.js")) {
 assertThat(in).isNotNull();
 source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
 }

 assertThat(source).contains("createConversation: '/spring/ai/loom/user-conversations'");
 assertThat(source).contains("renameConversation: (id) => `/spring/ai/loom/user-conversations/${id}`");
 assertThat(source).contains("sidebar-item-rename");
 assertThat(source).contains("if (!state.conversationId) {\r\n await conversation.createNew();");
 assertThat(source).contains("if (deleted)");
 assertThat(source).contains("reason === 'forbidden'");
 assertThat(source).contains("无权重命名该对话");
 assertThat(source).doesNotContain("else {\n await conversation.createNew();");
 assertThat(source).doesNotContain("if (ok) {\n if (state.conversationId === id)");
 // Old contract: bare `return r.ok` for rename — replaced by structured {ok, reason}
 assertThat(source).doesNotContain("async renameConversation(id, title) {\n const r = await apiFetch(API.renameConversation(id), {\n method: 'PATCH',\n headers: { 'Content-Type': 'application/json' },\n body: JSON.stringify({ title }),\n });\n return r.ok;\n }");
 }
}
