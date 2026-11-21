package cn.wubo.spring.ai.loom.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2(agentic-loop-steering):SSE 新增 steerAck / iteration 两种帧的 record 形状锁。
 * A1 约束:旧发送点(SseController 内容帧 2-arg / askUser 3-arg / subTask 3-arg 与 4-arg
 * 位置构造)源码零改动仍编译,且新字段恒为 null —— 帧形状向后兼容。
 * 注意:4-arg 构造器是 record 规范构造器的位置委托,分量顺序被本测试锁死
 * (DefaultSubTaskExecutor 用位置参传 SubTaskEvent,若有人在中部插入新分量,
 * 语义会静默错位 —— 这里显式断言分量归属)。
 */
class ChatResponseRecordFrameTest {

    @Test
    void legacy_2arg_still_null_new_fields() {
        ChatResponseRecord r = new ChatResponseRecord("hi", null);
        assertThat(r.steerAck()).isNull();
        assertThat(r.iteration()).isNull();
        assertThat(r.content()).isEqualTo("hi"); // A1:旧帧形状不变
    }

    @Test
    void steerAck_frame() {
        ChatResponseRecord r = new ChatResponseRecord(new SteerAck("m1"));
        assertThat(r.steerAck().messageId()).isEqualTo("m1");
        assertThat(r.content()).isNull();
    }

    @Test
    void iteration_frame() {
        assertThat(new ChatResponseRecord(new IterationEvent(2)).iteration().index()).isEqualTo(2);
    }

    @Test
    void legacy_askUser_subTask_canonical_constructors_keep_component_positions() {
        AskUserEvent ask = new AskUserEvent("q-1", "问题", null, null,
                List.of(new AskUserOption("A", null)), false, true, 300L);
        SubTaskEvent sub = new SubTaskEvent("s-1", "RUNNING", "p", 1L, 0L, null);

        ChatResponseRecord askFrame = new ChatResponseRecord(null, null, ask);
        assertThat(askFrame.askUser()).isSameAs(ask);
        assertThat(askFrame.subTaskEvent()).isNull();
        assertThat(askFrame.steerAck()).isNull();
        assertThat(askFrame.iteration()).isNull();

        ChatResponseRecord subFrame = new ChatResponseRecord(null, null, sub);
        assertThat(subFrame.subTaskEvent()).isSameAs(sub);
        assertThat(subFrame.askUser()).isNull();
        assertThat(subFrame.steerAck()).isNull();
        assertThat(subFrame.iteration()).isNull();

        // 4-arg 位置委托(DefaultSubTaskExecutor:175 的实际调用形态):ev 必须落在 subTaskEvent 分量
        ChatResponseRecord canonical4 = new ChatResponseRecord("t", "r", null, sub);
        assertThat(canonical4.content()).isEqualTo("t");
        assertThat(canonical4.reasoningContent()).isEqualTo("r");
        assertThat(canonical4.askUser()).isNull();
        assertThat(canonical4.subTaskEvent()).isSameAs(sub);
        assertThat(canonical4.steerAck()).isNull();
        assertThat(canonical4.iteration()).isNull();
    }
}
