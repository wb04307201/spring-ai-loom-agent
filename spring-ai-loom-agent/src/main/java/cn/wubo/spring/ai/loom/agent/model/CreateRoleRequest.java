package cn.wubo.spring.ai.loom.agent.model;

public record CreateRoleRequest(
        String code,
        String name,
        String description,
        java.util.List<String> mcpNames
) {
}
