package cn.wubo.spring.ai.loom.agent.model;

public record RoleInfo(
 String code,
 String name,
 boolean system,
 String description
) {
}
