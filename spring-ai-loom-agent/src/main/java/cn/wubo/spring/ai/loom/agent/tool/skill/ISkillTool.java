package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public interface ISkillTool extends IEmbedTool {

    @Tool(description = "列出当前用户可访问的技能（仅 name + 描述，不含完整内容；完整内容请用 @getSkill）。默认返回全部（上限 200）。可选按 keyword 模糊匹配 name/description，或按 source 过滤。")
    String listSkills(
        @ToolParam(description = "模糊匹配关键词，匹配技能名或描述，可选", required = false) String keyword,
        @ToolParam(description = "按 source 过滤：USER_CREATED / MARKET_VIEW / ROLE_GRANTED / MARKET_PULLED，可选", required = false) String source,
        @ToolParam(description = "最多返回数量，默认 200，可选", required = false) Integer maxCount,
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
