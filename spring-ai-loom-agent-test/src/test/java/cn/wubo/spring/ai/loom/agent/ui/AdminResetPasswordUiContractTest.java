package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * admin 重置密码的前端契约锁。
 *
 * <p>锁住的核心是**权限边界的 UI 侧一致性**：后端拒绝重置 admin，
 * 前端也必须让 admin 行的按钮**不可点** —— 否则用户点了才被告知不行，
 * 是糟糕的体验（且暗示"也许能绕过"）。
 */
@DisplayName("admin 重置密码前端契约")
class AdminResetPasswordUiContractTest {

    private static final String ADMIN = "/META-INF/resources/spring/ai/loom/admin/";

    private String source(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("%s 必须存在", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("用户列表有重置密码按钮，且 admin 行禁用")
    void resetButtonExistsAndAdminRowDisabled() throws IOException {
        String js = source(ADMIN + "console.js");
        assertThat(js)
                .as("必须有重置密码按钮")
                .contains("reset-pwd-btn")
                .contains("重置密码");
        assertThat(js)
                .as("ADMIN 行的按钮必须 disabled —— 与后端拒绝重置 admin 的边界保持一致，"
                        + "否则用户点了才被告知不行")
                .contains("u.type === \"ADMIN\" ? ' disabled");
        assertThat(js)
                .as("禁用时应给出原因提示")
                .contains("不能重置管理员的密码");
        assertThat(js)
                .as("已禁用的按钮不应绑定点击处理器")
                .contains("if (btn.disabled) return;");
    }

    @Test
    @DisplayName("调用正确的端点与方法")
    void callsCorrectEndpoint() throws IOException {
        String js = source(ADMIN + "console.js");
        assertThat(js)
                .as("必须 PUT 到 /admin/users/{username}/password")
                .contains("/password")
                .contains("method: \"PUT\"");
        assertThat(js)
                .as("请求体字段必须是 newPassword")
                .contains("JSON.stringify({ newPassword: pwd })");
    }

    @Test
    @DisplayName("前端做密码长度预校验（与后端同标准 6 位）")
    void validatesPasswordLengthClientSide() throws IOException {
        String js = source(ADMIN + "console.js");
        assertThat(js)
                .as("前端应预校验，避免无谓往返")
                .contains("pwd.length < 6")
                .contains("新密码至少 6 位");
    }

    @Test
    @DisplayName("弹窗提示 admin 会知道新密码（用户已确认的取舍）")
    void modalWarnsAdminKnowsPassword() throws IOException {
        String html = source(ADMIN + "console.html");
        assertThat(html)
                .as("必须提示'请将新密码转告用户' —— 本方案的取舍是 admin 手输密码，"
                        + "即 admin 会知道它，UI 应明示")
                .contains("请将新密码转告用户");
        assertThat(html)
                .as("必须说明原密码立即失效")
                .contains("原密码将立即失效");
        assertThat(html)
                .as("弹窗结构必须存在")
                .contains("id=\"reset-pwd-modal\"")
                .contains("id=\"reset-pwd-new\"");
    }
}
