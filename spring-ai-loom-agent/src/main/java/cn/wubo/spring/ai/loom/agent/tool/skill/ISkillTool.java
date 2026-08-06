package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public interface ISkillTool extends IEmbedTool {

    @Tool(description = "分页列出所有可用的技能，包含技能名和描述。默认每页20条。")
    String listSkills(
        @ToolParam(description = "页码，从1开始") Integer page,
        @ToolParam(description = "每页数量，-1表示全部") Integer size,
        ToolContext toolContext
    );

    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    String getSkill(
        @ToolParam(description = "技能名") String name,
        ToolContext toolContext
    );

    @Tool(description = "创建或更新一个属于当前用户的技能。name 重复时覆盖现有内容（已审批的市场技能和已锁定的角色授权技能除外）。")
    String createOrUpdateSkill(
        @ToolParam(description = "技能名（必填，字母/数字/中划线/下划线/中文，最长 128）") String name,
        @ToolParam(description = "技能描述（必填）") String description,
        @ToolParam(description = "技能 Prompt 内容（必填）") String content,
        ToolContext toolContext
    );
}
