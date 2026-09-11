package cn.wubo.spring.ai.loom.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 console.html 深路径浏览器 IT:UI 创建用户 → UI 分配角色 → API 验证持久化 → 清理;
 * ADMIN 行 strict-RBAC 提示;admin/user.html、mcps.html、conversation.html 三页冒烟。
 *
 * <p><b>执行时校准点 1 —— 用户行"分配角色"入口(console.js renderTable L100-150 源码核实):</b>
 * 每行渲染 {@code <tr data-username data-type>},操作列含
 * {@code <button class="secondary-btn assign-role-btn" data-username>分配角色</button>}
 * (click → openAssignRole)。brief 草稿的 {@code getByText("角色")} 会命中表头
 * 「已分配角色」th 与角色徽章列,已替换为
 * {@code #user-table-container .assign-role-btn[data-username='...']} 精确选择器。
 * 弹窗 {@code #assign-role-modal},列表 {@code #assign-role-list}(条目为
 * {@code input.assign-role-cb},value 即角色 code),保存 {@code #assign-role-save},
 * 取消 {@code #assign-role-cancel} —— 与 brief 猜测一致。
 *
 * <p><b>执行时校准点 2 —— ADMIN 行 strict-RBAC 提示(console.js openAssignRole L401-412 源码核实):</b>
 * 2026-09-08 起 ADMIN early-return 已删,{@code type==='ADMIN'} 时 {@code #assign-role-hint}
 * 追加实际文案「管理员同样受角色授权约束（strict RBAC），未分配角色时仅平台默认能力（universal 工具）可用。」
 * 断言用该实际文案关键子串("管理员同样受角色授权约束" + "strict RBAC"),不硬猜"ADMIN"。
 *
 * <p><b>执行时校准点 3 —— 创建用户表单(console.html L84-135 源码核实):</b>
 * {@code #create-user-btn} → {@code #create-user-modal}(display:flex),字段
 * {@code #new-username / #new-nickname / #new-password / #new-type}(select,option USER/ADMIN)
 * / 提交 {@code #create-submit-btn};submitCreate 校验密码 ≥6 位。成功后 closeCreate +
 * loadUsers 重渲染 → 用 {@code tr[data-username='...']} 出现替代固定 sleep。
 *
 * <p><b>执行时校准点 4 —— 三页冒烟参数(user.js / conversation.js 顶部守卫源码核实):</b>
 * user.html 缺 {@code ?username=}、conversation.html 缺 {@code ?id=} 时 JS 直接
 * {@code location.replace("console.html")}(Task 6 已实证)——冒烟必须带参数:
 * user.html?username=wb04307201;conversation.html?id=console-probe(假 id,
 * /flow 端点只读兜底不抛);mcps.html 无参数要求(mcps.js 无 URLSearchParams 守卫)。
 *
 * <p>API 事实(LoomAgentConfiguration L2127/L2138/L2255 源码核实):
 * POST /admin/users → 200 body true;DELETE /admin/users/{username} → 200;
 * GET /admin/users/{username}/roles → JSON 数组(如 ["base"]);种子角色 base 由 V1.0 落地。
 */
@DisplayName("P2 console.html 用户创建/角色分配(含 ADMIN strict-RBAC 提示)+ user/mcps/conversation 冒烟")
class ConsoleAdminBrowserIT extends BrowserTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 被测用户名;@AfterEach 兜底删除(断言失败也要清场)。 */
    private String createdUser;

    @AfterEach
    void cleanupUser() {
        String user = createdUser;
        createdUser = null;
        if (user == null) return;
        try (BrowserContext admin = adminContext()) {
            apiSend(admin, "DELETE", UI + "admin/users/" + user, null);
        } catch (Exception ignored) {
            // 清场尽力而为:用户可能已被测试主体删除
        }
    }

    @Test
    void createUserAndAssignRoleViaUi() throws Exception {
        String user = "conu" + System.currentTimeMillis() % 100000;
        createdUser = user;
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/console.html");
            page.waitForSelector("#create-user-btn");
            // 用户列表首次渲染完成(loadUsers 异步;种子至少有 admin 行)
            page.waitForSelector("#user-table-container tr[data-username]",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 1. UI 创建用户(校准点 3:精确 id,密码 ≥6 位) =====
            page.click("#create-user-btn");
            page.waitForSelector("#create-user-modal");
            page.fill("#new-username", user);
            page.fill("#new-nickname", "Console E2E");
            page.fill("#new-password", "conpass1");
            page.selectOption("#new-type", "USER");
            page.click("#create-submit-btn");
            // submitCreate 成功 → closeCreate + loadUsers 重渲染 → 新行出现(比固定 sleep 精确)
            page.waitForSelector("#user-table-container tr[data-username='" + user + "']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            // 创建失败会在弹窗内显示 #create-error(此时弹窗不关);弹窗已关即 POST ok
            assertThat(page.locator("#create-user-modal").isVisible()).isFalse();

            // ===== 2. UI 分配角色(校准点 1:.assign-role-btn,精确 value=base) =====
            page.click("#user-table-container .assign-role-btn[data-username='" + user + "']");
            page.waitForSelector("#assign-role-modal");
            // 非 ADMIN 行:hint 为基础并集说明,不含 strict-RBAC 追加句
            assertThat(page.locator("#assign-role-hint").innerText())
                    .contains("勾选要分配给该用户的角色")
                    .doesNotContain("strict RBAC");
            // 角色列表加载完成(种子至少有 base 角色 checkbox)
            page.waitForSelector("#assign-role-list .assign-role-cb[value='base']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            page.check("#assign-role-list .assign-role-cb[value='base']");
            page.click("#assign-role-save");
            // 保存成功 → showToast + closeAssignRole(display:none);失败则弹窗内 #assign-role-error
            page.waitForSelector("#assign-role-modal",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.HIDDEN).setTimeout(15000));
            assertThat(page.locator("#assign-role-error").isVisible()).isFalse();

            // ===== 3. API 验证持久化:GET roles 精确含 base =====
            JsonNode roles = MAPPER.readTree(apiGet(ctx, UI + "admin/users/" + user + "/roles"));
            assertThat(roles.isArray()).isTrue();
            assertThat(roles).hasSize(1);
            assertThat(roles.get(0).asText()).isEqualTo("base");

            // ===== 4. API 删除清场 + GET users 验证消失 =====
            assertThat(apiSend(ctx, "DELETE", UI + "admin/users/" + user, null).status())
                    .isEqualTo(200);
            createdUser = null; // 已删,@AfterEach 无需重复
            assertThat(apiGet(ctx, UI + "admin/users")).doesNotContain("\"" + user + "\"");

            // 全程无 console 错误
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    @Test
    void adminUserCanOpenAssignModalForAdminRow() {
        // 2026-09-08 起 ADMIN early-return 已删:分配角色弹窗对 ADMIN 用户同样打开,
        // #assign-role-hint 追加 strict-RBAC 提示句(校准点 2,实际文案关键子串断言)
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/console.html");
            page.waitForSelector("#user-table-container tr[data-username='" + ADMIN_USER + "']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            page.click("#user-table-container .assign-role-btn[data-username='" + ADMIN_USER + "']");
            page.waitForSelector("#assign-role-modal");
            // 标题带目标用户名
            assertThat(page.locator("#assign-role-title").innerText())
                    .isEqualTo("分配角色：" + ADMIN_USER);
            // strict-RBAC 提示句(console.js L411 实际文案)
            assertThat(page.locator("#assign-role-hint").innerText())
                    .contains("管理员同样受角色授权约束")
                    .contains("strict RBAC");
            // 弹窗同样加载出角色 checkbox 列表(与普通用户完全相同的分配流程)
            page.waitForSelector("#assign-role-list .assign-role-cb",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            page.click("#assign-role-cancel");
            page.waitForSelector("#assign-role-modal",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.HIDDEN).setTimeout(15000));
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    @Test
    void userMcpsConversationPagesSmoke() {
        // 校准点 4:user.html / conversation.html 必须带参数,否则 JS 重定向 console.html
        try (BrowserContext ctx = adminContext()) {
            // user.html?username=admin —— 用户详情页正常渲染(标题带用户名)
            Page userPage = newPage(ctx);
            userPage.navigate(baseUrl + UI + "admin/user.html?username=" + ADMIN_USER);
            userPage.waitForLoadState(LoadState.NETWORKIDLE);
            assertThat(userPage.url()).contains("admin/user.html"); // 未被守卫重定向
            assertThat(userPage.locator("#user-title").innerText())
                    .contains("用户详情").contains(ADMIN_USER);
            assertThat(consoleErrorsOf(userPage)).as("user.html 无 console error").isEmpty();
            userPage.close();

            // mcps.html —— 无参数要求;表格容器渲染完成(种子 12 个 mcp_server 或空态兜底)
            Page mcpsPage = newPage(ctx);
            mcpsPage.navigate(baseUrl + UI + "admin/mcps.html");
            mcpsPage.waitForLoadState(LoadState.NETWORKIDLE);
            mcpsPage.waitForSelector("#mcp-table-container table, #mcp-table-container .empty-state",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            assertThat(consoleErrorsOf(mcpsPage)).as("mcps.html 无 console error").isEmpty();
            mcpsPage.close();

            // conversation.html?id=console-probe —— 假 id 只读兜底(/flow 端点 200 空数据),
            // 不重定向、容器存在、无 console error
            Page convPage = newPage(ctx);
            convPage.navigate(baseUrl + UI + "admin/conversation.html?id=console-probe");
            convPage.waitForLoadState(LoadState.NETWORKIDLE);
            assertThat(convPage.url()).contains("admin/conversation.html"); // 未被守卫重定向
            convPage.waitForSelector("#flow-container",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            assertThat(consoleErrorsOf(convPage)).as("conversation.html 无 console error").isEmpty();
            convPage.close();
        }
    }
}
