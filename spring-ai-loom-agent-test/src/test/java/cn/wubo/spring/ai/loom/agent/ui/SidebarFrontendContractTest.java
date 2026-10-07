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

        assertThat(source).contains("createConversation: \"/spring/ai/loom/user-conversations\"");
        assertThat(source).contains("renameConversation: (id) => `/spring/ai/loom/user-conversations/${id}`");
        assertThat(source).contains("sidebar-item-rename");
        // 2026-10-01:此处原为 \r\n,断言从写下那天就永远不成立 —— app.js 自引入起
        // (c51d8caf,2026-07-20)一直是纯 LF(实测当时 CRLF=0 / LF=3315,今天同样 CRLF=0)。
        // 同一断言里的兄弟项(下方 doesNotContain 三条)用的都是 \n,只有这条混进了 \r\n,
        // 属孤立笔误。改为 \n 后本用例才真正开始守护它想守护的那段逻辑。
        assertThat(source).contains("if (!state.conversationId) {\n      await conversation.createNew();\n      if (!state.conversationId) return;");
        assertThat(source).contains("if (deleted)");
        assertThat(source).contains("reason === \"forbidden\"");
        assertThat(source).contains("无权重命名该对话");
        assertThat(source).doesNotContain("else {\n await conversation.createNew();");
        assertThat(source).doesNotContain("if (ok) {\n if (state.conversationId === id)");
        // Old contract: bare `return r.ok` for rename — replaced by structured {ok, reason}
        assertThat(source).doesNotContain("async renameConversation(id, title) {\n const r = await apiFetch(API.renameConversation(id), {\n method: 'PATCH',\n headers: { 'Content-Type': 'application/json' },\n body: JSON.stringify({ title }),\n });\n return r.ok;\n }");
    }
}
