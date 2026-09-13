package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

/**
 * askUser 工具推给前端的提问卡片事件(#1,spec D4)。
 * 作为 {@link ChatResponseRecord#askUser()} 第 3 组件随 SSE 帧下发;
 * 前端 onChunk 按字段分派渲染,askUser=null 的普通内容帧不受影响。
 *
 * @param questionId       UUID,answer 端点按此索引挂起的 CompletableFuture
 * @param question         问题正文
 * @param header           短标题/chip(可空)
 * @param background       背景说明(可空)
 * @param options          2-4 个选项(工具入参校验保证)
 * @param multiSelect      是否多选
 * @param allowCustomInput 是否允许"其他"自定义输入
 * @param timeoutSeconds   前端倒计时用(与工具阻塞超时同值)
 */
public record AskUserEvent(String questionId,
                           String question,
                           String header,
                           String background,
                           List<AskUserOption> options,
                           boolean multiSelect,
                           boolean allowCustomInput,
                           long timeoutSeconds) {
}
