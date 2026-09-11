package cn.wubo.spring.ai.loom.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 角色管理深路径浏览器 IT(roles.html + roles.js:创建 → 动态工具授权 → 保存持久化 → 删除)。
 *
 * <p><b>执行时校准点 1 —— 详情弹窗触发方式(roles.js renderTable L119-144 源码核实):</b>
 * 角色行 {@code <tr data-code>} <b>不可点击</b>(无行级 click 绑定);每行操作列渲染
 * {@code <button class="edit-role-btn" data-code="{code}">编辑 / 授权</button>},
 * click 监听调 {@code openDetail(code)} → {@code #role-detail-modal} display:flex。
 * brief 草稿里的 {@code getByText(code).click()}(点 code 文本)不会打开详情,已替换为
 * {@code .edit-role-btn[data-code='...']} 精确选择器。
 *
 * <p><b>执行时校准点 2 —— #rd-tools 条目形态(roles.js renderRoleToolList L344-445 源码核实):</b>
 * 两段式渲染,<b>不存在</b> {@code input[type=checkbox][value=tool_git]} 这种形态:
 * <ul>
 *   <li>{@code #rd-tools-allowed-list}(已授权):每条含
 *       {@code <input type="checkbox" class="default-tool-cb" data-tool-name="{group}">}(语义=「默认启用」开关)
 *       + 上移/下移 + {@code .remove-tool-btn[data-tool-name]}「移除」。</li>
 *   <li>{@code #rd-tools-available-list}(未授权):每条含
 *       {@code <button class="add-tool-btn" data-tool-name="{group}">添加授权</button>}。</li>
 * </ul>
 * 授权 tool_git 的动作 = 点击 available 段的 {@code .add-tool-btn[data-tool-name='tool_git']}
 * (push {groupName, defaultEnabled:true} 后重渲染,条目移入 allowed 段且「默认启用」勾选),
 * 再点 {@code #rd-save} → saveDetail 并行 PUT mcps/skills/knowledge/tools 四个端点,
 * tools body = {@code {"items":[{"groupName":"tool_git","defaultEnabled":true}]}}。
 *
 * <p><b>工具列表数据源与 universal 隐藏(CapabilityService.listAll L112-133 源码核实):</b>
 * openDetail 从 {@code /admin/capabilities} 拉列表,只取 type=LOCAL;服务端 listAll() 对
 * {@code universalGroups.contains(ci.id())} 直接 continue(M6 Q3「完全隐藏」)→
 * #rd-tools 只会出现 RBAC 4 组(tool_git / tool_maven / tool_compile / tool_render),
 * universal 7 组(tool_schedule / subtask / knowledge / time / skill / file / askUser)一个都不出现。
 *
 * <p>删除走 admin API(brief 裁定:API 保证清场;级联清 5 张子表是 DEFECT-Q3-1,
 * 本任务只验证 UI/API 层删除后 {@code GET /admin/roles} 不含该 code)。
 */
@DisplayName("P2 roles.html:创建角色/动态工具授权/保存持久化/删除级联")
class RolesAdminBrowserIT extends BrowserTestBase {

    /** RBAC 4 组(@ToolGroup 无 defaultGranted):admin 授权页应动态出现全部 4 组。 */
    private static final List<String> RBAC_GROUPS = List.of(
            "tool_git", "tool_maven", "tool_compile", "tool_render");
    /** universal 7 组(defaultGranted=true):listAll() 服务端剔除,授权页完全不展示。 */
    private static final List<String> UNIVERSAL_GROUPS = List.of(
            "tool_schedule", "tool_subtask", "tool_knowledge", "tool_time",
            "tool_skill", "tool_file", "tool_askUser");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 被测角色 code;@AfterEach 兜底删除(断言失败也要清场)。 */
    private String roleCode;

    @AfterEach
    void cleanupRole() {
        String code = roleCode;
        roleCode = null;
        if (code == null) return;
        try (BrowserContext admin = adminContext()) {
            apiSend(admin, "DELETE", UI + "admin/roles/" + code, null);
        } catch (Exception ignored) {
            // 清场尽力而为:角色可能已被测试主体删除
        }
    }

    @Test
    void createGrantAndDeleteRoleViaUi() throws Exception {
        String code = "e2erole" + System.currentTimeMillis() % 100000;
        roleCode = code;
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/roles.html");
            page.waitForSelector("#create-role-btn");
            // 表格首次渲染完成(loadRoles 异步;种子至少有 base 角色行)
            page.waitForSelector("#role-table-container tr[data-code]",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 1. UI 创建角色 =====
            page.click("#create-role-btn");
            page.waitForSelector("#create-role-modal");
            page.fill("#new-role-code", code);
            page.fill("#new-role-name", "E2E 角色");
            page.fill("#new-role-desc", "浏览器 IT 创建");
            page.click("#create-role-submit");
            // submitCreate 成功 → closeCreate + loadRoles 重渲染 → 新行出现(比固定 sleep 精确)
            page.waitForSelector("#role-table-container tr[data-code='" + code + "']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 2. 打开详情弹窗(校准点 1:.edit-role-btn,行不可点) =====
            page.click("#role-table-container .edit-role-btn[data-code='" + code + "']");
            page.waitForSelector("#role-detail-modal");
            // rd-tools 在 openDetail 里最后渲染(需先 await /admin/capabilities);
            // 新角色 allowed 为空 → RBAC 组全在 available 段等「添加授权」按钮
            page.waitForSelector("#rd-tools-available-list .add-tool-btn[data-tool-name]",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 3. #rd-tools 精确断言(校准点 2) =====
            @SuppressWarnings("unchecked")
            List<String> groups = (List<String>) page.evaluate(
                    "() => [...document.querySelectorAll('#rd-tools [data-tool-name]')]"
                            + ".map(e => e.getAttribute('data-tool-name'))");
            // RBAC 4 组动态出现(数据源 /admin/capabilities,非硬编码 KNOWN_TOOL_GROUPS)
            assertThat(groups).containsExactlyInAnyOrderElementsOf(RBAC_GROUPS);
            // universal 7 组完全不出现(listAll 服务端剔除 + 授权页无入口,CLAUDE.md M6)
            String toolsHtml = page.locator("#rd-tools").innerHTML();
            for (String u : UNIVERSAL_GROUPS) {
                assertThat(toolsHtml).as("授权页不展示 universal 组 " + u).doesNotContain(u);
            }
            // 新角色尚未授权任何工具:allowed 段无「默认启用」checkbox
            assertThat(page.locator("#rd-tools-allowed-list .default-tool-cb").count()).isZero();

            // ===== 4. 授权 tool_git:点「添加授权」→ 移入 allowed 段(默认启用勾选)→ 保存 =====
            page.click("#rd-tools-available-list .add-tool-btn[data-tool-name='tool_git']");
            page.waitForSelector("#rd-tools-allowed-list .default-tool-cb[data-tool-name='tool_git']");
            assertThat(page.isChecked(
                    "#rd-tools-allowed-list .default-tool-cb[data-tool-name='tool_git']"))
                    .as("添加授权后「默认启用」默认勾选").isTrue();
            assertThat(page.locator(
                    "#rd-tools-available-list .add-tool-btn[data-tool-name='tool_git']").count())
                    .as("tool_git 已移出可选段").isZero();

            page.click("#rd-save");
            // saveDetail 成功 → showToast + closeDetail(display:none)+ loadRoles
            page.waitForSelector("#role-detail-modal",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.HIDDEN).setTimeout(15000));
            // 保存失败会在弹窗内显示 #rd-error(此时弹窗不关);弹窗已关即 4 个 PUT 全 ok,
            // 仍显式断言错误框隐藏,防「关闭由其他路径触发」的假绿
            assertThat(page.locator("#rd-error").isVisible()).isFalse();

            // ===== 5. API 验证持久化:GET tools 精确到 groupName + defaultEnabled =====
            JsonNode tools = MAPPER.readTree(apiGet(ctx, UI + "admin/roles/" + code + "/tools"));
            assertThat(tools.isArray()).isTrue();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).path("groupName").asText()).isEqualTo("tool_git");
            assertThat(tools.get(0).path("defaultEnabled").asBoolean()).isTrue();

            // ===== 6. 删除(API 保证清场)+ GET roles 验证消失 =====
            assertThat(apiSend(ctx, "DELETE", UI + "admin/roles/" + code, null).status())
                    .isEqualTo(200);
            roleCode = null; // 已删,@AfterEach 无需重复
            assertThat(apiGet(ctx, UI + "admin/roles")).doesNotContain("\"" + code + "\"");

            // 全程无 console 错误
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}
