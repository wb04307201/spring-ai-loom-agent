package cn.wubo.spring.ai.loom.agent.model;

public record SkillRecord(
 String name,
 String description,
 boolean load,
 String content,
 String source
) {
}
