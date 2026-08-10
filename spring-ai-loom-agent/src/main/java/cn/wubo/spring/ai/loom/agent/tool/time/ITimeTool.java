package cn.wubo.spring.ai.loom.agent.tool.time;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;

public interface ITimeTool extends IEmbedTool {

    String getCurrentTime(String timezone);

    String convertTime(String sourceTimezone, String time, String targetTimezone);
}
