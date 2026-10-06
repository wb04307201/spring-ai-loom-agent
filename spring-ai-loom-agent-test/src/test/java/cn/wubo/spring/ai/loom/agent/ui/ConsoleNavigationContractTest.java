package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制台导航链路的契约锁（2026-10-06 修复）。
 *
 * <p>锁两件事：
 * <ol>
 *   <li><b>用户列表 → 用户详情页的入口</b>：修复前用户名是纯文本，
 *       admin 无法从控制台进入 user.html，「用户列表 → 会话 → 对话日志」
 *       这条链路的第一环是断的。</li>
 *   <li><b>待审核 chip 可点跳转</b>：修复前 chip 是纯展示的 {@code <div>}，
 *       提示了待办却去不了；且两个市场的数字被**求和**成一个，看不出是哪一个。</li>
 * </ol>
 */
@DisplayName("控制台导航链路契约")
class ConsoleNavigationContractTest {

    private String source(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("%s 必须存在", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String ADMIN = "/META-INF/resources/spring/ai/loom/admin/";

    @Test
    @DisplayName("用户列表的用户名必须是可点链接（打通到 user.html）")
    void usernameLinksToUserDetail() throws IOException {
        String js = source(ADMIN + "console.js");
        assertThat(js)
                .as("用户名必须包在 <a> 里指向 user.html —— 否则 admin 进不去详情页")
                .contains("user-detail-link")
                .contains("href=\"user.html?username=");
        assertThat(js)
                .as("username 必须 URL 编码（防注入/特殊字符）")
                .contains("encodeURIComponent(u.username)");
        // 旧形态：纯文本单元格
        assertThat(js)
                .as("不应再保留纯文本用户名单元格")
                .doesNotContain("<td><strong>${escapeHtml(u.username)}</strong></td>");

        String css = source(ADMIN + "console.css");
        assertThat(css)
                .as("链接样式必须存在（去掉下划线、hover 提示）")
                .contains(".user-detail-link");
    }

    @Test
    @DisplayName("待审核 chip 分市场显示且可点（不再求和成一个数字）")
    void pendingChipIsPerMarketAndClickable() throws IOException {
        String js = source(ADMIN + "console.js");
        assertThat(js)
                .as("chip 必须是 <a> 可点 —— 修复前是纯 <div>，提示了去不了")
                .contains("pending-chip-link")
                .contains("href=\"");
        assertThat(js)
                .as("必须分别标注两个市场，而非求和")
                .contains("技能市场")
                .contains("知识库市场");
        assertThat(js)
                .as("不应再把两个市场的计数求和")
                .doesNotContain("counts.reduce((a, b) => a + b, 0)");
        // 0 项的市场不显示（避免「知识库市场 (0)」噪声）
        assertThat(js)
                .as("无待办的市场不应渲染 chip")
                .contains(".filter((c) => c && c.n > 0)");

        String css = source(ADMIN + "console.css");
        assertThat(css)
                .as("chip 链接样式必须存在")
                .contains(".pending-chip-link");
    }

    @Test
    @DisplayName("市场页支持 ?status= 过滤（chip 跳过来才看得见待审条目）")
    void marketPagesSupportStatusFilter() throws IOException {
        for (String page : new String[]{"market-skills.js", "knowledge-market.js"}) {
            String js = source(ADMIN + page);
            assertThat(js)
                    .as("%s 必须读 URL 的 status 参数 —— 否则 chip 跳转只是跳页，用户还得自己找", page)
                    .contains("URLSearchParams(location.search).get(\"status\")");
            assertThat(js)
                    .as("%s 必须对筛选结果为空时给出友好提示", page)
                    .contains("下没有条目");
        }
    }
}
