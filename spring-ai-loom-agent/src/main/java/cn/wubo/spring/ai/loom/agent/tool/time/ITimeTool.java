package cn.wubo.spring.ai.loom.agent.tool.time;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;

@ToolGroup(value = "time", defaultGranted = true, description = "getCurrentTime / convertTime — 当前时间查询 + 跨时区时间换算")
public interface ITimeTool extends IEmbedTool {

    String getCurrentTime(String timezone);

    String convertTime(String sourceTimezone, String time, String targetTimezone);
}
