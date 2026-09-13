package cn.wubo.spring.ai.loom.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-3/P0-4 RBAC 工具过滤浏览器 IT(子任务 RBAC 安全修复 f7b49d6 的前端可见性回归)。
 *
 * <p><b>/api/capabilities 契约(源码核验 CapabilityService.list + 执行期实测,非猜测):</b>
 * <ul>
 *   <li>universal 7 组(tool_schedule/subtask/knowledge/time/skill/file/askUser)
 *       <b>完全不出现在响应里</b>(list() 第 74 行 {@code if (universalGroups.contains(ci.id())) continue;},
 *       M6 Q4 "完全不显示" 决定)— "对所有用户可见" 的语义在 tool-callback 层
 *       (visibleToolGroupsFor = role ∪ universal),不在 API 列表层。
 *       前端因此天然无 universal checkbox(无感调用契约)。</li>
 *   <li>RBAC 4 组(tool_git/maven/compile/render)<b>总是出现</b>,授权状态用
 *       {@code effectiveEnabled} 布尔标注:未授权=false(前端渲染 disabled + 强制 unchecked
 *       checkbox,服务端 DefaultChat 经 allowedCapabilityIdsFor role∩pick 过滤掉 tool callback)。
 *       即"未授权不可用"的 enforcement 是 effectiveEnabled=false + 服务端过滤,不是从列表剔除。</li>
 * </ul>
 *
 * <p><b>#mcp-list DOM 观察结论(app.js renderModal L4419-4466 源码读取 + 执行期断言实证,
 * 2026-09-11,zero-role 用户):</b>
 * 每个 capability 渲染为一个 {@code div.skill-item},内部
 * {@code <input type="checkbox" class="mcp-checkbox" [disabled]>} +
 * {@code <div class="skill-item-name">{title||name}<span>本地|MCP</span></div>};
 * LOCAL 条目显示的是 raw name(如 "git",即 @ToolGroup value),<b>不是</b> group id("tool_git");
 * effectiveEnabled=false → checkbox 带 disabled 属性且 item 加 "disabled" class。
 * universal 组因被服务端从 JSON 剔除 → 弹窗中既无其 id 也无其名称条目、更无可勾选 input。
 * 断言据此写死:采集每个 .skill-item 的名称文本节点 + checkbox.disabled 精确断言。
 */
@DisplayName("P0-3/P0-4 RBAC:capabilities 过滤 + 工具弹窗 universal 无 checkbox + 授权后可见")
class RbacToolsBrowserIT extends BrowserTestBase {

    private static final List<String> UNIVERSAL = List.of(
            "tool_schedule", "tool_subtask", "tool_knowledge", "tool_time",
            "tool_skill", "tool_file", "tool_askUser");
    private static final List<String> RBAC_GROUPS = List.of(
            "tool_git", "tool_maven", "tool_compile", "tool_render");
    /** universal 组的 raw name(@ToolGroup value;弹窗按 title/name 渲染,须按名称断言不存在)。 */
    private static final List<String> UNIVERSAL_RAW = List.of(
            "schedule", "subtask", "knowledge", "time", "skill", "file", "askUser");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String user;
    private String role;

    @AfterEach
    void cleanup() {
        try (BrowserContext admin = adminContext()) {
            if (user != null) apiSend(admin, "DELETE", UI + "admin/users/" + user, null);
            if (role != null) apiSend(admin, "DELETE", UI + "admin/roles/" + role, null);
        }
        user = null;
        role = null;
    }

    private void provision(String roleCode, boolean grantGit) {
        long stamp = System.currentTimeMillis() % 1000000;
        user = "rbacu" + stamp;
        role = roleCode + stamp;
        try (BrowserContext admin = adminContext()) {
            assertThat(apiSend(admin, "POST", UI + "admin/users",
                    "{\"username\":\"" + user + "\",\"nickname\":\"RBAC U\",\"password\":\"rbacpass1\",\"type\":\"USER\"}").status())
                    .isEqualTo(200);
            assertThat(apiSend(admin, "POST", UI + "admin/roles",
                    "{\"code\":\"" + role + "\",\"name\":\"rbac-e2e\",\"description\":\"e2e\",\"mcpNames\":[]}").status())
                    .isEqualTo(200);
            if (grantGit) {
                assertThat(apiSend(admin, "PUT", UI + "admin/roles/" + role + "/tools",
                        "{\"items\":[{\"groupName\":\"tool_git\",\"defaultEnabled\":true}]}").status())
                        .isEqualTo(200);
            }
            assertThat(apiSend(admin, "PUT", UI + "admin/users/" + user + "/roles",
                    "{\"roleCodes\":[\"" + role + "\"]}").status()).isEqualTo(200);
        }
    }

    /** GET /api/capabilities 并解析为 JSON 数组。 */
    private JsonNode capabilities(BrowserContext ctx) {
        try {
            return MAPPER.readTree(apiGet(ctx, UI + "api/capabilities"));
        } catch (Exception e) {
            throw new IllegalStateException("parse capabilities failed", e);
        }
    }

    /** 按 id 找 capability 节点;不存在返回 null。 */
    private static JsonNode byId(JsonNode caps, String id) {
        for (JsonNode n : caps) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }

    @Test
    void zeroRoleUserSeesOnlyUniversalInCapabilities() {
        // 名称沿 brief;实测语义 = universal 组整体隐藏(Q4)+ RBAC 组列出但全部 effectiveEnabled=false
        provision("zerorole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            JsonNode caps = capabilities(ctx);
            assertThat(caps.isArray()).isTrue();
            // universal 7 组:完全不出现在列表(无感调用契约,M6 Q4 决定)——
            // 它们的"对所有用户可见"落在 tool-callback 层(visibleToolGroupsFor 含 universal)
            for (String u : UNIVERSAL) {
                assertThat(byId(caps, u)).as("universal 不进 capabilities 列表 " + u).isNull();
            }
            // RBAC 4 组:出现但 effectiveEnabled=false(未授权 → 前端 disabled + 服务端过滤)
            for (String r : RBAC_GROUPS) {
                JsonNode n = byId(caps, r);
                assertThat(n).as("RBAC 组在列表 " + r).isNotNull();
                assertThat(n.path("effectiveEnabled").asBoolean())
                        .as("未授权 RBAC 组 effectiveEnabled=false " + r).isFalse();
            }
            // 全局安全断言:zero-role(且 role mcpNames=[])→ 列表中不存在任何 effectiveEnabled=true 条目
            for (JsonNode n : caps) {
                assertThat(n.path("effectiveEnabled").asBoolean())
                        .as("zero-role 无任何启用条目: " + n.path("id").asText()).isFalse();
            }
        }
    }

    @Test
    void grantedRoleUserSeesToolGit() {
        provision("gitrole", true);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            JsonNode caps = capabilities(ctx);
            JsonNode git = byId(caps, "tool_git");
            assertThat(git).isNotNull();
            assertThat(git.path("effectiveEnabled").asBoolean())
                    .as("角色授权 tool_git 后 effectiveEnabled=true").isTrue();
            // 精确:只授权了 git,其余 3 个 RBAC 组仍 false(不放宽为"至少 git 可见")
            for (String r : List.of("tool_maven", "tool_compile", "tool_render")) {
                JsonNode n = byId(caps, r);
                assertThat(n).as("RBAC 组在列表 " + r).isNotNull();
                assertThat(n.path("effectiveEnabled").asBoolean())
                        .as("未授权 RBAC 组仍 effectiveEnabled=false " + r).isFalse();
            }
            // universal 依旧完全隐藏
            for (String u : UNIVERSAL) {
                assertThat(byId(caps, u)).as("universal 不进 capabilities 列表 " + u).isNull();
            }
        }
    }

    @Test
    void toolsModalHidesUniversalCheckboxes() {
        provision("modalrole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.click("#mcp-button");
            page.waitForSelector("#mcp-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            // 等列表渲染完成(openModal → loadList 异步 → renderModal;RBAC 4 组必在)
            page.waitForSelector("#mcp-list .skill-item",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            // DOM 实测形态(见类 javadoc):div.skill-item > input.mcp-checkbox[disabled?]
            //   + .skill-item-name 首文本节点 = raw name(LOCAL 为 @ToolGroup value,如 "git")
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.evaluate(
                    "() => [...document.querySelectorAll('#mcp-list .skill-item')].map(item => {"
                            + " const nameEl = item.querySelector('.skill-item-name');"
                            + " const box = item.querySelector('input.mcp-checkbox');"
                            + " return { name: nameEl && nameEl.childNodes[0] ? nameEl.childNodes[0].textContent.trim() : '',"
                            + " disabled: !!(box && box.disabled) }; })");
            assertThat(items).isNotEmpty();
            List<String> names = items.stream().map(m -> String.valueOf(m.get("name"))).toList();
            // universal 组:服务端已从 JSON 剔除 → 弹窗中无其名称条目、无其可勾选 input
            for (String u : UNIVERSAL_RAW) {
                assertThat(names).as("弹窗不渲染 universal 组条目 " + u).doesNotContain(u);
            }
            // zero-role:RBAC 4 组渲染为 disabled checkbox(修复前曾是 checked+disabled 死锁态)
            for (String r : List.of("git", "maven", "compile", "render")) {
                Map<String, Object> item = items.stream()
                        .filter(m -> r.equals(String.valueOf(m.get("name"))))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("弹窗缺少 RBAC 条目 " + r));
                assertThat(item.get("disabled"))
                        .as("未授权 RBAC 条目 checkbox disabled: " + r).isEqualTo(true);
            }
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }

    @Test
    void subtaskAndScheduleButtonsVisibleForPlainUser() {
        provision("subtaskrole", false);
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, "rbacpass1");
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            // universal 入口按钮:静态工具栏元素,index.html 172-190 行,无 features/RBAC 隐藏逻辑
            assertThat(page.isVisible("#subtask-button")).isTrue();
            assertThat(page.isVisible("#schedule-button")).isTrue();
        }
    }
}
