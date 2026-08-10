package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

public record McpRecord(String name,
                        String title,
                        String version,
                        String description,
                        List<ToolRecord> tools) {
}
