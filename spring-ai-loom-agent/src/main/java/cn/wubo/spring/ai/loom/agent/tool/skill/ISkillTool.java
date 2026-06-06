package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;

public interface ISkillTool extends IEmbedTool {

    String skillContents(ToolContext toolContext);

    String getSkill(String name, ToolContext toolContext);
}
