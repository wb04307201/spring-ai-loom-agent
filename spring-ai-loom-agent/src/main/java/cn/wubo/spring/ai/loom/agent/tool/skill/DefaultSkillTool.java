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

    @Override
    @Tool(description = "分页列出所有可用的技能，包含技能名和描述。默认每页20条。")
    public String listSkills(
        @ToolParam(description = "页码，从1开始") Integer page,
        @ToolParam(description = "每页数量，-1表示全部") Integer size,
        ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        List<SkillRecord> allSkills = skillStorage.list(username).stream()
            .filter(SkillRecord::load)
            .toList();

        int total = allSkills.size();
        int pageSize = (size == null || size <= 0) ? 20 : size;
        int currentPage = (page == null || page < 1) ? 1 : page;

        List<SkillRecord> pageSkills;
        int totalPages;

        if (pageSize == -1) {
            pageSkills = allSkills;
            totalPages = 1;
            currentPage = 1;
        } else {
            totalPages = (int) Math.ceil((double) total / pageSize);
            int fromIndex = (currentPage - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);
            pageSkills = (fromIndex < total) ? allSkills.subList(fromIndex, toIndex) : List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("技能目录（共 %d 个，第 %d/%d 页）:%n%n", total, currentPage, totalPages));
        sb.append(String.format("%-20s %-50s%n", "技能名", "技能描述"));
        sb.append("-".repeat(70)).append("\n");

        for (SkillRecord skill : pageSkills) {
            sb.append(String.format("%-20s %-50s%n", skill.name(), skill.description()));
        }

        if (totalPages > 1 && pageSize != -1) {
            sb.append(String.format("%n提示：共 %d 页，调用 @listSkills {\"page\": %d} 查看下一页，或 @listSkills {\"size\": -1} 查看全部",
                totalPages, currentPage + 1));
        }

        return sb.toString();
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
}
