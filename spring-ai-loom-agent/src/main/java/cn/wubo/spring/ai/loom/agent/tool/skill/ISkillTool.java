package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@ToolGroup(value = "skill", description = "getSkill / createOrUpdateSkill — 技能市场查询 + 自建技能管理")
public interface ISkillTool extends IEmbedTool {

    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    String getSkill(
            @ToolParam(description = "技能名") String name,
            ToolContext toolContext
    );

    @Tool(description = "创建或更新一个属于当前用户的技能。name 重复时覆盖现有内容（已审批的市场技能和已锁定的角色授权技能除外；MARKET_PULLED 的内容也被锁定，要修改请用 duplicateSkill 复制后再改）。")
    String createOrUpdateSkill(
            @ToolParam(description = "技能名（必填，字母/数字/中划线/下划线/中文，最长 128）") String name,
            @ToolParam(description = "技能描述（必填）") String description,
            @ToolParam(description = "技能 Prompt 内容（必填）") String content,
            ToolContext toolContext
    );
}
