package cn.wubo.spring.ai.loom.agent.model;

/**
 * admin 重置他人密码的请求体。
 *
 * <p>只有新密码 —— 与 {@link ChangePasswordRequest} 的关键差异是**没有 oldPassword**：
 * 管理员无从得知用户的自选密码。这是刻意的：能重置 ≠ 能获知。
 *
 * <p>注意：本接口的语义是"admin 手输新密码"，故 admin 会知道重置后的密码。
 * 这是产品取舍（另一方案是系统生成随机密码只返回一次，更安全但 admin 需转告）。
 */
public record ResetPasswordRequest(String newPassword) {
}
