package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * embedded HTTP 工具({@code IHttpTool},走 Spring AI {@code @Tool} 的内部工具模式)
 * 的<b>真实对话端到端</b>验证。
 *
 * <p><b>为什么需要它</b>:既有的 {@code HttpToolInvokeIT} 类 javadoc 明确写着
 * "为什么不打 LLM:模型可能不调用工具、或调用方式与预期不同" —— 它<b>绕过 LLM</b>
 * 直调工具方法。因此"这 5 个工具在真实聊天里能不能被 LLM 调通"长期是盲区。
 * 本类补的正是这一层,且是 2026-10-03 首次补上。
 *
 * <p><b>它已经抓到一个真实缺陷</b>:首轮跑之前,{@code spring.ai.loom.agent.http.*}
 * 的 yml 配置<b>全部失效</b>({@code LoomAgentConfiguration.loomAgentProperties}
 * 手工拷贝字段时漏了 {@code http})。回归锁在
 * {@code HttpPropertyBindingIT}(绑定层,不过 LLM)。
 *
 * <p><b>分层设计</b>:本类刻意区分两类用例 ——
 * <ul>
 *   <li><b>确定性</b>(用例 1、3):不依赖 LLM 选择,不烧 token,CI 可稳定跑;</li>
 *   <li><b>端到端</b>(用例 2):真调 MiniMax-M3,验证 LLM 真的会用工具且拿到真实数据。</li>
 * </ul>
 *
 * <p><b>上游</b>:{@code jsonplaceholder.typicode.com}(固定数据集,GET 200 / POST 201,
 * 实测 0.7-0.9s)。白名单配在 {@code src/test/resources/application.yml} ——
 * embedded 模式 {@code DefaultHttpTool:43} 硬编码 fail-closed,
 * {@code allowed-domains} 是唯一开关,且<b>纯 host 模式是 port-agnostic 的</b>
 * ({@code DomainWhitelist.allows} 的无端口分支),故无需写 :443。
 *
 * <p><b>断言落点</b>:聊天页面<b>没有</b>工具调用痕迹(app.js 的 SSE 帧只处理
 * subTaskEvent / askUser / reasoningContent / content),唯一权威痕迹是
 * {@code loom_tool_call_log},经 {@code GET admin/conversations/{id}/flow} 暴露。
 * 故断言走 admin API 而非 DOM。
 */
@DisplayName("embedded HTTP 工具 —— 真实 Chrome + 真实 LLM 端到端")
class HttpToolBrowserIT extends BrowserTestBase {

    private static final String UPSTREAM = "https://jsonplaceholder.typicode.com";
    private static final String SYSTEM = "jsonplaceholder";
    private static final String USER = "e2e_http_user";
    private static final String PASS = "e2eHttpPass1";
    private static final String ROLE = "e2e_http_role";

    @BeforeAll
    void requireLlm() {
        String key = System.getenv("MINIMAX_API_KEY");
        assumeTrue(key != null && !key.isBlank(),
            "跳过:MINIMAX_API_KEY 未设置(test 配置已切 MiniMax,见 src/test/resources/application.yml)");
    }

    // ==================== provisioning ====================

    /**
     * 建用户 + 角色 + 授权两组工具。
     *
     * <p><b>两组都要授</b>,少一组会以不同方式失败,极易误判:
     * <ul>
     *   <li>{@code tool_http_manage} — REST 写面。没有它 {@code POST /api/http/systems}
     *       被 {@code HttpManageGuard} 拒 403,system 注册不上;</li>
     *   <li>{@code tool_http} — LLM 调用面。没有它工具<b>根本不注入</b>给模型。</li>
     * </ul>
     *
     * <p>{@code defaultEnabled:true} 是硬要求:前端 localStorage 为空时走
     * {@code defaultToolIds} fallback,只勾 defaultEnabled=true 的组。
     *
     * <p>用 admin 身份完成 provisioning(user / role / tools / roles 四步都是
     * 管理动作),返回<b>测试用户自己的</b>已登录 context —— 注册 system 必须用
     * 它,因为 {@code HttpRouterSupport.engineFor(currentUsername())} 按
     * <b>会话用户</b>构造引擎、system 落在该用户自己的
     * {@code LoomPaths.userHttpDir} 下。用 admin 注册的话 system 存在 admin 名下,
     * 测试用户的 LLM 根本看不到(实测会 403 或 unknownEndpoint)。
     */
    private BrowserContext provision(BrowserContext admin) {
        APIResponse u = apiSend(admin, "POST", UI + "admin/users",
            "{\"username\":\"" + USER + "\",\"nickname\":\"HTTP E2E\",\"password\":\"" + PASS
                + "\",\"type\":\"USER\"}");
        assertThat(u.status()).as("创建用户,body=%s", u.text()).isEqualTo(200);

        APIResponse r = apiSend(admin, "POST", UI + "admin/roles",
            "{\"code\":\"" + ROLE + "\",\"name\":\"HTTP E2E\",\"description\":\"端到端验证用\",\"mcpNames\":[]}");
        assertThat(r.status()).as("创建角色,body=%s", r.text()).isEqualTo(200);

        APIResponse t = apiSend(admin, "PUT", UI + "admin/roles/" + ROLE + "/tools",
            "{\"items\":[{\"groupName\":\"tool_http\",\"defaultEnabled\":true},"
                + "{\"groupName\":\"tool_http_manage\",\"defaultEnabled\":true}]}");
        assertThat(t.status()).as("授两组工具,body=%s", t.text()).isEqualTo(200);

        APIResponse ur = apiSend(admin, "PUT", UI + "admin/users/" + USER + "/roles",
            "{\"roleCodes\":[\"" + ROLE + "\"]}");
        assertThat(ur.status()).as("绑角色,body=%s", ur.text()).isEqualTo(200);

        // ↓ 注册 system 用**测试用户自己的会话**,不是 admin
        BrowserContext ctx = newContext();
        loginViaApi(ctx, USER, PASS);
        APIResponse s = apiSend(ctx, "POST", UI + "api/http/systems",
            "{\"name\":\"" + SYSTEM + "\",\"baseUrl\":\"" + UPSTREAM + "\"}");
        assertThat(s.status())
            .as("以测试用户身份注册 system —— system 按会话用户隔离存储,body=%s", s.text())
            .isEqualTo(200);
        return ctx;
    }

    @AfterEach
    void cleanup() {
        try (BrowserContext admin = adminContext()) {
            apiSend(admin, "DELETE", UI + "api/http/systems/" + SYSTEM, null);
            apiSend(admin, "DELETE", UI + "admin/roles/" + ROLE, null);
            apiSend(admin, "DELETE", UI + "admin/users/" + USER, null);
        } catch (Exception ignored) {
            // 清理失败不应掩盖测试本身的结论
        }
    }

    /** 打开聊天页并等到 app.js 就绪(ChatSmokeBrowserIT 缺这一步是隐患,此处补上)。 */
    private void openChatReady(Page page) {
        page.navigate(baseUrl + UI + "index.html");
        page.waitForSelector("#textarea");
        page.waitForFunction("() => !!window._loomAgent", null,
            new Page.WaitForFunctionOptions().setTimeout(15_000));
        page.waitForFunction(
            "() => { const el=document.getElementById('sidebarList');"
                + " return !!el && !el.querySelector('.sidebar-loading'); }", null,
            new Page.WaitForFunctionOptions().setTimeout(15_000));
        // 新用户首次进入时侧边栏没有任何会话 ⇒ 发消息后拿不到 conversationId,
        // 而工具调用痕迹只能按会话 id 从 admin flow API 读。先建一个空会话。
        if (currentConversationId(page).isBlank()) {
            page.click("#new-chat-btn");
            page.waitForFunction(
                "() => !!document.querySelector('#sidebarList .sidebar-item.active')", null,
                new Page.WaitForFunctionOptions().setTimeout(15_000));
        }
    }

    private void sendAndAwaitStream(Page page, String message) {
        page.fill("#textarea", message);
        page.click("#send-btn");
        // ① 流开始:send 禁用 + stop 可见(页面初始态与"流结束"态相同,必须分两步)
        page.waitForFunction(
            "() => { const s=document.getElementById('send-btn');"
                + " const t=document.getElementById('stop-btn');"
                + " return s && s.disabled && t && t.style.display !== 'none'; }", null,
            new Page.WaitForFunctionOptions().setTimeout(30_000).setPollingInterval(500));
        // ② 流结束
        page.waitForFunction(
            "() => { const s=document.getElementById('send-btn');"
                + " const t=document.getElementById('stop-btn');"
                + " return s && !s.disabled && s.textContent.trim() === '发送消息'"
                + " && t && t.style.display === 'none'; }", null,
            new Page.WaitForFunctionOptions().setTimeout(180_000).setPollingInterval(1_000));
    }

    /** 连续 3 次采样(2s 间隔)文本不变 ⇒ 渲染完成(照抄 ChatSmokeBrowserIT 的既定做法)。 */
    private String awaitStableBubble(Page page) {
        page.waitForSelector(".chat-item-left .bubble");
        String prev = "", cur = "";
        int stable = 0;
        for (int i = 0; i < 60 && stable < 3; i++) {
            cur = page.locator(".chat-item-left .bubble").last().innerText();
            stable = cur.equals(prev) && !cur.isBlank() ? stable + 1 : 0;
            prev = cur;
            if (stable < 3) page.waitForTimeout(2_000);
        }
        return cur;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** 从 flow 的 stats 里取后端自己统计的 toolCallCount(独立于文本匹配的第二道证据)。 */
    private static int extractToolCallCount(String flow) {
        var m = java.util.regex.Pattern.compile("\"toolCallCount\":(\\d+)").matcher(flow);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private String currentConversationId(Page page) {
        Object id = page.evaluate(
            "() => document.querySelector('#sidebarList .sidebar-item.active')?.dataset?.conversationId || ''");
        return id == null ? "" : id.toString();
    }

    // ==================== 用例 1:工具可见性(确定性,不打 LLM) ====================

    @Test
    @DisplayName("授权后 http 工具在聊天面板可见且默认勾选(defaultEnabled 生效)")
    void httpToolVisibleAndCheckedInPicker() {
        try (BrowserContext admin = adminContext(); BrowserContext ctx = provision(admin)) {
            Page page = newPage(ctx);
            openChatReady(page);

            page.click("#mcp-button");
            page.waitForSelector("#mcp-list .skill-item",
                new Page.WaitForSelectorOptions().setTimeout(20_000));

            // 本地工具项渲染的是 `c.title || c.name`(即 "http"),**不含 group id**
            // (见 app.js:4712)⇒ 用 description 里的稳定关键词过滤。
            var httpItem = page.locator("#mcp-list .skill-item")
                .filter(new com.microsoft.playwright.Locator.FilterOptions()
                    .setHasText("invokeEndpoint"));
            assertThat(httpItem.count())
                .as("tool_http 应出现在工具面板(#mcp-list)里 —— 其 description 含 invokeEndpoint")
                .isGreaterThanOrEqualTo(1);

            var checkbox = httpItem.first().locator("input.mcp-checkbox");
            assertThat(checkbox.count()).isGreaterThanOrEqualTo(1);
            assertThat(checkbox.first().isDisabled())
                .as("已授权的 tool_http 不应是 disabled —— disabled 表示 effectiveEnabled=false")
                .isFalse();
            assertThat(checkbox.first().isChecked())
                .as("defaultEnabled=true ⇒ 首次进入(localStorage 空)应自动勾选")
                .isTrue();

            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== 用例 2:端到端真调 LLM ====================

    @Test
    @DisplayName("LLM 在真实对话里调用 invokeEndpoint 并拿到上游真实数据")
    void llmCallsHttpToolEndToEnd() {
        try (BrowserContext admin = adminContext(); BrowserContext ctx = provision(admin)) {
            Page page = newPage(ctx);
            openChatReady(page);

            String convId = currentConversationId(page);
            assertThat(convId).as("发消息前应已有一个活动会话").isNotBlank();

            sendAndAwaitStream(page,
                "请调用 invokeEndpoint 工具,请求 " + SYSTEM + " 系统的 GET /posts/1 端点,"
                    + "把返回 JSON 里的 title 字段原样告诉我。");

            String reply = awaitStableBubble(page);
            assertThat(reply).as("AI 回复非空").isNotBlank();
            assertThat(reply).doesNotContain("发送失败");

            // 核心断言:工具真被调用,且没被白名单拒。
            // flow 端点在 /admin/** 下 ⇒ 必须用 **admin** 会话读(普通用户会被
            // auth.adminPathPatterns 挡回登录页 HTML,不是 403 而是整页登录页)。
            String flow = apiGet(admin, UI + "admin/conversations/" + convId
                + "/flow?username=" + USER + "&page=0&size=500");
            assertThat(flow)
                .as("loom_tool_call_log 里应出现 invokeEndpoint —— 页面上没有工具痕迹,这是唯一权威来源")
                .doesNotContain("<!doctype html>")
                .contains("invokeEndpoint")
                .contains("TOOL_CALL")
                .contains("TOOL_RESULT")
                .doesNotContain("DomainNotAllowed");

            // LLM 应能读出真实数据(证明响应结构对它可用,不只是"没报错")
            assertThat(reply)
                .as("LLM 应把上游真实 title 读出来")
                .contains("sunt aut facere");

            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    // ==================== 用例 3:负向确定性(不打 LLM 的那半) ====================

    /*
     * ⚠️ <b>已知 flaky</b>(2026-10-04 首次全量 IT 闸门时观察到):
     * 本用例要等一次真实 LLM 往返,而断言只关心"工具没被注入"。
     * 全量闸门(227 例,含多个真实 LLM 用例)下 MiniMax 响应变慢,
     * {@link #sendAndAwaitStream} 的 180s 上限偶发被击穿 → TimeoutError。
     * 单独重跑稳定通过(实测 35.8s)。
     *
     * <p><b>为什么不修</b>:本用例的价值是"RBAC 过滤真的挡住了",而该断言的
     * 权威来源是 {@code loom_tool_call_log} —— 工具若被注入,LLM 极可能调用它,
     * 记录随之出现。但"极可能"不等于"必然":纯靠 LLM 行为来制造观察窗口,
     * 本质上无法完全确定性。加大超时只是把概率推低而非消除。
     * 真正的确定性做法是<b>不经 LLM</b> 直查该用户的可见工具集
     * ({@code GET /api/capabilities}),但那会与本类"真实对话端到端"的定位分家,
     * 属于另一个测试类的职责。**如实记录,不做假装确定的粉饰。**
     */

    @Test
    @DisplayName("未授 tool_http 的用户:工具不进 tool_callbacks(RBAC 过滤真的挡住)")
    void unauthorizedUserGetsNoHttpTool() {
        try (BrowserContext admin = adminContext(); BrowserContext ctx = newContext()) {
            // 只授 tool_http_manage(够注册 system),**不授** tool_http
            APIResponse u = apiSend(admin, "POST", UI + "admin/users",
                "{\"username\":\"" + USER + "\",\"nickname\":\"无 HTTP 权限\",\"password\":\"" + PASS
                    + "\",\"type\":\"USER\"}");
            assertThat(u.status()).isEqualTo(200);
            APIResponse r = apiSend(admin, "POST", UI + "admin/roles",
                "{\"code\":\"" + ROLE + "\",\"name\":\"无 http\",\"description\":\"负向对照\",\"mcpNames\":[]}");
            assertThat(r.status()).isEqualTo(200);
            apiSend(admin, "PUT", UI + "admin/roles/" + ROLE + "/tools",
                "{\"items\":[{\"groupName\":\"tool_http_manage\",\"defaultEnabled\":true}]}");
            apiSend(admin, "PUT", UI + "admin/users/" + USER + "/roles",
                "{\"roleCodes\":[\"" + ROLE + "\"]}");

            loginViaApi(ctx, USER, PASS);
            Page page = newPage(ctx);
            openChatReady(page);

            // 面板里不该出现可用的 http 调用面
            page.click("#mcp-button");
            page.waitForSelector("#mcp-list .skill-item",
                new Page.WaitForSelectorOptions().setTimeout(20_000));
            // 同用例 1:面板渲染 title/name("http"),不含 group id ⇒ 用 description 关键词
            var httpItem = page.locator("#mcp-list .skill-item")
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("invokeEndpoint"));
            if (httpItem.count() > 0) {
                assertThat(httpItem.first().locator("input.mcp-checkbox").first().isDisabled())
                    .as("未授权的 tool_http 必须 disabled(effectiveEnabled=false)")
                    .isTrue();
            }
            page.evaluate("() => document.getElementById('mcp-close-btn').click()");

            String convId = currentConversationId(page);
            if (!convId.isBlank()) {
                sendAndAwaitStream(page,
                    "请调用 invokeEndpoint 工具请求 " + SYSTEM + " 系统的 GET /posts/1。");
                awaitStableBubble(page);
                // ⚠️ 必须用 admin 会话读:普通用户读 /admin/** 会被挡回**登录页 HTML**
                // (不是 403),那样 doesNotContain("invokeEndpoint") 会**假绿**。
                String flow = apiGet(admin, UI + "admin/conversations/" + convId
                    + "/flow?username=" + USER + "&page=0&size=500");
                // ⚠️ 不能断言整段 flow 不含 "invokeEndpoint" —— prompt 里就写了它,
                // USER 事件的文本会被匹配到 ⇒ 永远失败。必须只统计**工具事件**。
                int toolEvents = countOccurrences(flow, "\"type\":\"TOOL_");
                assertThat(toolEvents)
                    .as("未授 tool_http ⇒ 工具压根不该注入给模型 ⇒ 不该有任何 TOOL_CALL/TOOL_RESULT"
                        + "。实测 stats.toolCallCount=%s", extractToolCallCount(flow))
                    .isZero();
                assertThat(extractToolCallCount(flow))
                    .as("后端统计的 toolCallCount 也应为 0")
                    .isZero();
            }
        }
    }

    /** 保留:若将来要用 UI 逐个点授权,可复用这段(RbacToolsBrowserIT 已验证等价 API 路径)。 */
    @SuppressWarnings("unused")
    private static final List<String> UI_ROLES_PATH_HINT =
        List.of("#role-table-container .edit-role-btn[data-code='" + ROLE + "']",
            "#rd-tools-available-list .add-tool-btn[data-tool-name='tool_http']", "#rd-save");
}
