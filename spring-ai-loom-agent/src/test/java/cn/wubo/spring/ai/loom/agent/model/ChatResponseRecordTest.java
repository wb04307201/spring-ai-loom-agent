package cn.wubo.spring.ai.loom.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseRecordTest {

    @Test
    void twoArgConstructorLeavesAskUserNull() {
        ChatResponseRecord rec = new ChatResponseRecord("hello", "thinking");
        assertThat(rec.content()).isEqualTo("hello");
        assertThat(rec.reasoningContent()).isEqualTo("thinking");
        assertThat(rec.askUser()).isNull();
    }

    @Test
    void threeArgConstructorCarriesAskUserEvent() {
        AskUserEvent ev = new AskUserEvent("q-1", "选哪个?", "部署", "背景说明",
                List.of(new AskUserOption("Docker", "容器部署"), new AskUserOption("java -jar", null)),
                false, true, 300L);
        ChatResponseRecord rec = new ChatResponseRecord(null, null, ev);
        assertThat(rec.askUser().questionId()).isEqualTo("q-1");
        assertThat(rec.askUser().options()).hasSize(2);
        assertThat(rec.askUser().options().get(0).label()).isEqualTo("Docker");
        assertThat(rec.askUser().multiSelect()).isFalse();
        assertThat(rec.askUser().allowCustomInput()).isTrue();
        assertThat(rec.askUser().timeoutSeconds()).isEqualTo(300L);
    }

    @Test
    void jacksonSerializesAllEventFields() throws Exception {
        AskUserEvent ev = new AskUserEvent("q-2", "问题", "标题", "背景",
                List.of(new AskUserOption("A", "说明A"), new AskUserOption("B", "说明B")),
                true, false, 60L);
        String json = new ObjectMapper().writeValueAsString(new ChatResponseRecord(null, null, ev));
        assertThat(json).contains("\"questionId\":\"q-2\"")
                .contains("\"multiSelect\":true")
                .contains("\"allowCustomInput\":false")
                .contains("\"timeoutSeconds\":60")
                .contains("\"label\":\"A\"");
    }

    @Test
    void jacksonRoundTripsOptionRecord() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AskUserOption opt = om.readValue("{\"label\":\"A\",\"description\":\"说明\"}", AskUserOption.class);
        assertThat(opt.label()).isEqualTo("A");
        assertThat(opt.description()).isEqualTo("说明");
    }
}
