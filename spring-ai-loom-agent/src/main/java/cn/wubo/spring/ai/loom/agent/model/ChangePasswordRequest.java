package cn.wubo.spring.ai.loom.agent.model;

public record ChangePasswordRequest(
        String oldPassword,
        String newPassword
) {
}
