package cn.wubo.spring.ai.loom.agent.model;

/**
 * 知识库记录。
 *
 * <p>description 字段的语义：<b>知识库内容摘要</b>（不是用户标签），由 LLM 在 system prompt
 * 【知识库】段中读取，用于判断"用户问题是否能从这个 KB 找到答案"。
 *
 * <p>好的 description 示例：
 * <pre>
 * "本知识库收录产品保修条款、故障排查流程、退换货政策"
 * "本知识库收录项目部署最佳实践：构建耗时、镜像路径、健康检查、Docker 配置"
 * </pre>
 *
 * <p>不好的 description（仅是主题标签，LLM 没法决策）：
 * <pre>
 * "产品手册"
 * "DEBUG 测试用 KB"
 * </pre>
 *
 * <p>未来计划：上传文件后由 LLM 自动生成/更新本字段，取代手动填写。
 *
 * @param id          知识库 ID
 * @param username    所有者用户名
 * @param name        知识库名称（人类可读，可短）
 * @param description 内容摘要（LLM 决策用，应当详尽）
 */
public record KnowledgeRecord(
        String id,
        String username,
        String name,
        String description
) {
}
