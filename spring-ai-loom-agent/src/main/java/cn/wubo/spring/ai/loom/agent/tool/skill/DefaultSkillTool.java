package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class DefaultSkillTool implements ISkillTool {

    /**
     * 已删除 listSkills。
     * 原因：技能全量列表（name + description）由 buildDynamicSystemPrompt 注入到 system prompt【技能】段，
     * LLM 再调工具列一遍是冗余。需要按 name 取完整内容请用 @getSkill。
     * 与 DefaultKnowledgeTool 删除 listKnowledgeBases 的设计对称（参考其 javadoc）。
     */
    private final ISkillStorage skillStorage;

    public DefaultSkillTool(ISkillStorage skillStorage) {
        this.skillStorage = skillStorage;
    }

    @Override
    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    public String getSkill(@ToolParam(description = "技能名") String name, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        SkillRecord skill = skillStorage.get(name, username);

        return String.format("技能名:%s%n", skill.name()) +
                String.format("技能描述:%s%n", skill.description()) +
                String.format("技能内容:%s%n", skill.content());
    }

    @Override
    @Tool(description = "创建或更新一个属于当前用户的技能。name 重复时覆盖现有内容（已审批的市场技能和已锁定的角色授权技能除外）。")
    public String createOrUpdateSkill(
            @ToolParam(description = "技能名（必填，字母/数字/中划线/下划线/中文，最长 128）") String name,
            @ToolParam(description = "技能描述（必填）") String description,
            @ToolParam(description = "技能 Prompt 内容（必填）") String content,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        if (name == null || name.isBlank()) {
            throw new LoomAgentRuntimeException(400, "name 不能为空");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 128) {
            throw new LoomAgentRuntimeException(400, "name 长度超过 128");
        }
        if (description == null) description = "";
        if (content == null || content.isBlank()) {
            throw new LoomAgentRuntimeException(400, "content 不能为空");
        }

        // 探测是否已存在（不存在则 status=created，已存在则 updated）
        boolean existed;
        try {
            skillStorage.get(trimmedName, username);
            existed = true;
        } catch (LoomAgentRuntimeException notFound) {
            existed = false;
        }

        // 锁检查交由 skillStorage.save 内部处理：ROLE_GRANTED / MARKET_PULLED 锁定会抛 403
        skillStorage.save(new SkillRecord(trimmedName, description, true, content, "USER_CREATED"), username);
        return String.format("已%s技能 %s，描述：%s", existed ? "更新" : "创建", trimmedName, description);
    }
}
