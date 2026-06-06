package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class DefaultSkillTool implements ISkillTool {

    private final ISkillStorage skillStorage;

    public DefaultSkillTool(ISkillStorage skillStorage) {
        this.skillStorage = skillStorage;
    }

    @Tool(description = "列出所有可用的技能，包含技能名和描述。配合 getSkill 工具使用，先调用此工具查看有哪些技能，再根据名称获取详细内容。")
    @Override
    public String skillContents(ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        List<SkillRecord> results = skillStorage
                .list(username)
                .stream()
                .filter(SkillRecord::load).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("技能目录（包含 %d 个技能）:%n%n", results.size()));
        sb.append(String.format("%-10s %-50s%n", "技能名", "技能描述"));

        for (SkillRecord skill : results) {
            sb.append(String.format("%-10s %-50s%n", skill.name(), skill.description()));
        }

        sb.append("%n%n提示：调用 @getSkill {\"skill_name\": \"匹配的技能名\"} 获取详细信息");
        return sb.toString();
    }

    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    @Override
    public String getSkill(@ToolParam(description = "技能名") String name, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        SkillRecord skill = skillStorage.get(name, username);

        return String.format("技能名:%s%n", skill.name()) +
                String.format("技能描述:%s%n", skill.description()) +
                String.format("技能内容:%s%n", skill.content());
    }
}
