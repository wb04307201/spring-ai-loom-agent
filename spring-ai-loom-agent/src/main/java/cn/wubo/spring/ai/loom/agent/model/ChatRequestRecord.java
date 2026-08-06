package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

/**
 * 聊天请求 record。
 *
 * 新增字段：
 * - selectedSkillName: 用户在前端通过 / 命令精准选中的 Skill 名；
 *   null/空表示无显式选择。DefaultChat.stream() 会按当前用户权限读取该 Skill，
 *   并将完整 content 追加到 system prompt 末尾（仅作用于本轮对话）。
 */
public record ChatRequestRecord(String message,
                                String conversationId,
                                List<String> mcps,
                                List<String> enabledKnowledgeIds,
                                List<String> fileIds,
                                String selectedSkillName) {

    /**
     * 兼容旧调用方：未传 {@code selectedSkillName} 时为 {@code null}。
     * <p>
     * 该 5 参数构造器保留是为了不破坏既有代码（例如旧的测试 / 业务调用），它仅委托到 canonical 6 参数构造器，
     * 并把 {@code selectedSkillName} 设为 {@code null}（等价于"无显式选中技能"）。
     */
    public ChatRequestRecord(String message,
                             String conversationId,
                             List<String> mcps,
                             List<String> enabledKnowledgeIds,
                             List<String> fileIds) {
        this(message, conversationId, mcps, enabledKnowledgeIds, fileIds, null);
    }
}
