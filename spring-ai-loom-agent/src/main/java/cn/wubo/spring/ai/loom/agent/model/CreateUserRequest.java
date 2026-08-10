package cn.wubo.spring.ai.loom.agent.model;

public record CreateUserRequest(
        String username,
        String nickname,
        String password,
        String type
) {
}
