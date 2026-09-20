package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM-callable tool:向当前用户提出一个选择式问题并阻塞等待作答(#1,spec D3/D5)。
 * <p>
 * 问题以卡片形式推入当前聊天流(SSE askUser 帧),用户在卡片上单选/多选/自定义输入,
 * 前端 POST answer 端点唤醒阻塞的工具线程,答案以 tool_result 回到同一条流。
 * <p>
 * universal 工具(defaultGranted=true):仅向"当前流的本人"提问,答案回同一流,
 * 无越权风险;子任务/定时任务被 schema 级排除(见 DefaultSubTaskExecutor 过滤器)。
 * <p>
 * 触发率引导(2026-09-20):实现类 {@code DefaultAskUserTool} 的 @Tool 描述采用
 * 正向场景枚举 + 澄清模式循环协议(用户要求"一问一答"时逐轮连续提问直到无疑问),
 * 并由 {@code DefaultChat.buildDynamicSystemPrompt} 的【提问与澄清】段在 system
 * prompt 层同步引导(对齐 Claude Code "工具可用即注入引导片段"机制);
 * 措辞契约由 AskUserGuidanceContractTest 锁定。
 */
@ToolGroup(value = "askUser", defaultGranted = true,
        description = "ask_user — 向当前用户提出选择卡片并等待作答")
public interface IAskUserTool extends IEmbedTool {

    /**
     * @param question         问题正文(必填非空)
     * @param header           短标题/chip(可空)
     * @param background       背景说明(可空)
     * @param optionsJson      选项 JSON 数组字符串,2-4 个 {@code {"label","description"}},
     *                         推荐项放第一位并在 label 后标注"(推荐)"
     *                         (String 而非 List<record>:对 qwen 系模型的 tool-args
     *                         JSON 容错更好,服务端用 Spring AI 宽容 JsonParser 解析)
     * @param multiSelect      是否多选(null=false)
     * @param allowCustomInput 是否允许自定义输入(null=false)
     * @param toolContext      Spring AI 工具上下文(username / parentConversationId)
     * @return "[用户已回答] ...",或 "[用户未作答] ...",或 "[提问失败] ..." 纠错文本
     */
    String askUser(
            @ToolParam(description = "要问用户的问题文本,一句话,清晰具体") String question,
            @ToolParam(description = "问题的短标题(2-6 字,如'部署方式'),可传 null") String header,
            @ToolParam(description = "为什么问这个问题的背景说明(1-2 句),可传 null") String background,
            @ToolParam(description = "选项 JSON 数组,2-4 个,推荐项放第一位并在 label 后标注'(推荐)',形如 [{\"label\":\"选项A(推荐)\",\"description\":\"补充说明\"},{\"label\":\"选项B\"}]") String optionsJson,
            @ToolParam(description = "是否允许多选,默认 false") Boolean multiSelect,
            @ToolParam(description = "是否允许用户自由输入自定义答案,默认 false") Boolean allowCustomInput,
            ToolContext toolContext);
}
