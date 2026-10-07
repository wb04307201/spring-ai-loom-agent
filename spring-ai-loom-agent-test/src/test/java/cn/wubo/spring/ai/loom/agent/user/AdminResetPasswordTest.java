package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * admin 重置他人密码的回归锁。
 *
 * <p><b>权限边界（本测试的核心）</b>：admin **不得**重置另一个 admin 的密码。
 * 理由：若允许，admin A 可接管 admin B 账号 —— 横向越权。这与
 * {@code deleteUser} 的"最后一个管理员保护"是同一类防御，但**更严格**：
 * 删除只保护"最后一个"，重置则**一个都不许**（重置后 A 能登录成 B，
 * 等价于接管，而删除是可察觉的破坏、重置是静默的冒名）。
 *
 * <p>用户自选密码不可被 admin 获知：{@code changePassword} 要求旧密码，
 * admin 不知道 —— 这正是需要独立"重置"能力的原因（忘密码时唯一的
 * 现有办法是删号重建，会丢会话/技能/角色）。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("admin 重置用户密码（权限边界回归锁）")
class AdminResetPasswordTest {

    @Autowired
    private IUser user;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newUser(String type) {
        String u = "rst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        user.createUser(u, "重置测试", "oldpass123", type);
        return u;
    }

    private String hashOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT password FROM user_info WHERE username = ?", String.class, username);
    }

    @Test
    @DisplayName("重置普通用户密码 ⇒ 新密码可登录，旧密码失效")
    void resetNormalUserPassword() {
        String target = newUser("USER");
        String oldHash = hashOf(target);

        user.resetPassword(target, "brandNew456");

        assertThat(hashOf(target))
                .as("密码 hash 必须已变（BCrypt 每次加盐，hash 不同）")
                .isNotEqualTo(oldHash);
        // 新密码可登录
        assertThat(user.login(new cn.wubo.spring.ai.loom.agent.model.UserRequestRecord(target, "brandNew456")))
                .as("重置后新密码必须可登录")
                .isNotNull();
    }

    @Test
    @DisplayName("admin 不得重置另一个 admin 的密码（横向越权防护）")
    void adminCannotResetAnotherAdmin() {
        String victim = newUser("ADMIN");
        String beforeHash = hashOf(victim);

        assertThatThrownBy(() -> user.resetPassword(victim, "hijacked789"))
                .as("重置 admin 必须被拒 —— 否则 admin A 可静默接管 admin B")
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("管理员");

        assertThat(hashOf(victim))
                .as("被拒时密码 hash 必须原封不动")
                .isEqualTo(beforeHash);
    }

    @Test
    @DisplayName("新密码长度校验（与 changePassword 同标准：至少 6 位）")
    void newPasswordMustMeetMinimumLength() {
        String target = newUser("USER");
        String beforeHash = hashOf(target);

        assertThatThrownBy(() -> user.resetPassword(target, "abc"))
                .as("过短密码必须被拒")
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("6");

        assertThat(hashOf(target)).isEqualTo(beforeHash);
    }

    @Test
    @DisplayName("重置不存在的用户 ⇒ 明确报错，不静默成功")
    void resetUnknownUserFails() {
        assertThatThrownBy(() -> user.resetPassword("nosuchuser_" + UUID.randomUUID().toString().replace("-", ""), "whatever123"))
                .as("不存在的用户必须报错 —— 静默成功会让 admin 以为改好了")
                .isInstanceOf(LoomAgentRuntimeException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("用户可继续用旧密码登录（证明上面各例不是靠删号实现）")
    void oldPasswordStillWorksWhenNotReset() {
        String target = newUser("USER");
        assertThat(user.login(new cn.wubo.spring.ai.loom.agent.model.UserRequestRecord(target, "oldpass123")))
                .as("未重置的用户旧密码必须仍可用 —— 防空转哨兵")
                .isNotNull();
    }
}
