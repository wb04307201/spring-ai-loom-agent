package cn.wubo.spring.ai.loom.agent.tool.time;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;

@ToolGroup("time")
public interface ITimeTool extends IEmbedTool {

    String getCurrentTime(String timezone);

    String convertTime(String sourceTimezone, String time, String targetTimezone);
}
