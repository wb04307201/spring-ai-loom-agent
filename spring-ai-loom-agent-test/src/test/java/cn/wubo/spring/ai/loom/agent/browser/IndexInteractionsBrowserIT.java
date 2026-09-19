package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 主应用交互深化浏览器 IT:会话 CRUD + 文件 / 工具 / 知识空间 / 技能 4 弹窗。
 *
 * <p><b>校准点(全部经 app.js / index.html / style.css / LoomAgentConfiguration 源码核实,非按 brief 猜测):</b>
 * <ol>
 *   <li><b>会话创建</b>:走真实 UI 路径 —— 点 {@code #new-chat-btn} →
 *       {@code conversation.createNew()}(app.js L1449)→ {@code POST /spring/ai/loom/user-conversations},
 *       body {@code {"title":"新对话 M-D H:MM"}}({@code generateDefaultConversationTitle} L154;
 *       后端 LoomAgentConfiguration L2359 读 Map.title,返回 201 + ConversationRecord)。
 *       brief 猜的 {@code {"title":"e2e-conv"}} 结构碰巧正确,但按任务裁定优先 UI 点击,
 *       API 直发仅作失败兜底清理用。创建成功后 {@code state.conversationId} 被置位并
 *       loadList 重渲染 → 新 item 带 {@code active} class(L1460/L1406)。</li>
 *   <li><b>改名 / 删除交互</b>:改名<b>不走</b>确认弹窗 —— {@code startRename}(L1490)
 *       在 item 内隐藏 .sidebar-item-text/.sidebar-item-actions 并插入
 *       {@code input.sidebar-item-edit},Enter → {@code PATCH /user-conversations/{id}}
 *       body {@code {"title":...}}(L461);删除走 {@code dialog.confirm}(L1586)→
 *       {@code #confirm-modal-overlay} display:flex(L261)+ {@code #confirm-modal-ok}
 *       → {@code DELETE /conversation/{id}}(L478)。两个 action 按钮常驻 DOM 但默认
 *       {@code opacity:0},{@code .sidebar-item:hover} 时才置 1(style.css L276-294)→
 *       先 hover 再点(真实用户路径);viewport 1280 &gt; 1200 断点,
 *       .sidebar-item-actions 不会被 media query 折叠(style.css L1703)。</li>
 *   <li><b>弹窗触发按钮与容器 id</b>(index.html 逐个核实存在):
 *       {@code #file-manager-button}→{@code #file-modal-overlay}/{@code #file-list}/
 *       {@code #file-close-btn}(L437/441/447);{@code #mcp-button}→{@code #mcp-modal-overlay}/
 *       {@code #mcp-list}/{@code #mcp-close-btn}(L273/279/284);{@code #ks-button}→
 *       {@code #ks-modal-overlay}/{@code #ks-sidebar}/{@code #ks-close-btn}(L542/623/632,
 *       关闭钮经 {@code "#ks-modal-overlay .close-button"} 绑 closePanel,app.js L6602);
 *       {@code #skills-button}→{@code #skills-modal-overlay}/
 *       {@code button.skill-tab[data-tab=market]}/{@code #skills-close-btn}(L305/361-376/417,
 *       4 个 tab:mine/market/submit/mysubmit)。显隐 = ui.showModal/hideModal 设
 *       style.display=flex/none(L1321-1327)。</li>
 *   <li><b>内容确定性</b>:{@code #file-list} 渲染仅两个分支 —— {@code .file-tree} 或
 *       「目录为空」(renderTree L3307-3312);{@code #mcp-list} 恒含 RBAC 4 组
 *       .skill-item(universal 被服务端剔除,与 RbacToolsBrowserIT 已证结论一致);
 *       技能 mine tab = V1.1 为 wb04307201 种子 6 条 user_skill;market tab =
 *       V1.0 尾部种子 2 条官方 APPROVED market_skill,行 class {@code .ks-item}(L5008),
 *       搜索栏 {@code #skill-market-search} 由 _renderMarketTab 同步渲染(L4753,先于 fetch await)。</li>
 * </ol>
 *
 * <p><b>就绪门</b>:app.js 为 type=module 延迟脚本,{@code #textarea}(静态 HTML)可见时
 * 事件绑定未必完成 —— openIndex 统一等 {@code window._loomAgent}(模块求值完成后写入,
 * L6616;bindAllEvents 在其之前的 init L6202 已同步执行)+ {@code #sidebarList} 的
 * .sidebar-loading 静态占位消失(init 尾部 conversation.loadList → renderSidebar
 * 必整体替换为 item 列表或「暂无对话」,L1388-1392),之后任何按钮点击都有 handler
 * 且首拉响应不会晚到覆盖新建 item 的渲染。
 *
 * <p><b>数据隔离</b>:会话 CRUD 用例创建的 2 个会话在用例内经 UI 删除流程删净,
 * finally 再按 id API DELETE 兜底(路由幂等软删,LoomAgentConfiguration L2411);
 * 会话数据落 ./target/test-ds(IT 仪式清库),文件目录 ./target/e2e-files。
 */
@DisplayName("P1 主应用交互:会话 CRUD / 文件模态 / 工具弹窗 / 知识空间弹窗 / 技能弹窗 tab")
class IndexInteractionsBrowserIT extends BrowserTestBase {

    private static final Page.WaitForFunctionOptions WF_10S =
            new Page.WaitForFunctionOptions().setTimeout(10_000);

    private Page openIndex(BrowserContext ctx) {
        Page page = newPage(ctx);
        page.navigate(baseUrl + UI + "index.html");
        page.waitForSelector("#textarea");
        // 就绪门 1:模块求值完成(bindAllEvents 已同步跑完,所有按钮 handler 就位)
        page.waitForFunction("() => !!window._loomAgent", null, WF_10S);
        // 就绪门 2:init 尾部 conversation.loadList 完成 —— #sidebarList 静态初始内容是
        // .sidebar-loading 三点占位(index.html L44-50),renderSidebar(L1388)必整体替换为
        // item 列表或「暂无对话」→ loading 占位消失 = 首拉落地。不先等这个就点 #new-chat-btn
        // 会有竞态:init 的陈旧 list 响应晚到并覆盖掉新建 item 的渲染。
        page.waitForFunction(
                "() => { const el = document.getElementById('sidebarList');"
                        + " return !!el && !el.querySelector('.sidebar-loading'); }",
                null, WF_10S);
        return page;
    }

    private static String itemSelector(String convId) {
        return "#sidebarList .sidebar-item[data-conversation-id='" + convId + "']";
    }

    /** 弹窗关闭断言:ui.hideModal 设 inline display:none(app.js L1325-1327)。 */
    private void assertOverlayHidden(Page page, String overlayId) {
        page.waitForFunction(
                "(id) => document.getElementById(id).style.display === 'none'",
                overlayId, WF_10S);
        assertThat(page.isVisible("#" + overlayId)).isFalse();
    }

    @Test
    void conversationCrudViaSidebar() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);

            // ── 创建 A:UI 点 #new-chat-btn → createNew() → POST user-conversations ──
            page.click("#new-chat-btn");
            page.waitForSelector("#sidebarList .sidebar-item.active",
                    new Page.WaitForSelectorOptions().setTimeout(10_000));
            String convA = (String) page.evalOnSelector(
                    "#sidebarList .sidebar-item.active", "el => el.dataset.conversationId");
            assertThat(convA).as("UI 创建的会话 A 有 data-conversation-id").isNotBlank();
            // 默认标题 = generateDefaultConversationTitle():「新对话 M-D H:MM」(L154-160)
            assertThat(page.textContent(itemSelector(convA) + " .sidebar-item-text"))
                    .as("UI 创建走默认标题格式")
                    .matches("新对话 \\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2}");

            // ── 创建 B(第二条,供"激活切换"验证):B 成为 active,A 失去 active ──
            page.click("#new-chat-btn");
            page.waitForFunction(
                    "(a) => { const el = document.querySelector('#sidebarList .sidebar-item.active');"
                            + " return !!el && el.dataset.conversationId !== a; }",
                    convA, WF_10S);
            String convB = (String) page.evalOnSelector(
                    "#sidebarList .sidebar-item.active", "el => el.dataset.conversationId");
            assertThat(convB).isNotBlank().isNotEqualTo(convA);

            try {
                // ── 激活:点 A 的文本区(非 actions)→ div click → switchTo(A) → active class 迁移 ──
                page.locator(itemSelector(convA) + " .sidebar-item-text").click();
                page.waitForFunction(
                        "(ids) => {"
                                + " const ea = document.querySelector(\"#sidebarList .sidebar-item[data-conversation-id='\" + ids[0] + \"']\");"
                                + " const eb = document.querySelector(\"#sidebarList .sidebar-item[data-conversation-id='\" + ids[1] + \"']\");"
                                + " return !!ea && ea.classList.contains('active')"
                                + " && !!eb && !eb.classList.contains('active'); }",
                        List.of(convA, convB), WF_10S);

                // ── 改名:hover → ✎(.sidebar-item-rename)→ 行内 input.sidebar-item-edit → Enter ──
                Locator itemA = page.locator(itemSelector(convA));
                itemA.hover();
                itemA.locator(".sidebar-item-rename").click();
                Locator edit = itemA.locator(".sidebar-item-edit");
                edit.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                edit.fill("e2e-renamed");
                page.keyboard().press("Enter");
                // save() → PATCH → loadList 重渲染 → A 的文本变为新标题
                page.waitForFunction(
                        "(a) => { const t = document.querySelector("
                                + "\"#sidebarList .sidebar-item[data-conversation-id='\" + a + \"'] .sidebar-item-text\");"
                                + " return !!t && t.textContent === 'e2e-renamed'; }",
                        convA, WF_10S);
                assertThat(page.locator("#sidebarList").innerHTML()).contains("e2e-renamed");

                // ── 删除 A:hover → ×(.sidebar-item-delete)→ #confirm-modal-overlay → #confirm-modal-ok ──
                deleteViaUi(page, convA);
                // A 是 active → delete() 自动 switchTo(remaining[0]) = B(created_at desc 首条)
                page.waitForFunction(
                        "(b) => { const el = document.querySelector('#sidebarList .sidebar-item.active');"
                                + " return !!el && el.dataset.conversationId === b; }",
                        convB, WF_10S);

                // ── 删除 B(用例内清场)──
                deleteViaUi(page, convB);
                page.waitForFunction(
                        "(ids) => !document.querySelector(\"#sidebarList .sidebar-item[data-conversation-id='\" + ids[0] + \"']\")"
                                + " && !document.querySelector(\"#sidebarList .sidebar-item[data-conversation-id='\" + ids[1] + \"']\")",
                        List.of(convA, convB), WF_10S);

                assertThat(consoleErrorsOf(page)).as("会话 CRUD 全程 console 无 error").isEmpty();
            } finally {
                // 兜底清理:中途断言失败也不留会话残骸(路由幂等,已删则 0 行更新仍 200)
                apiSend(ctx, "DELETE", UI + "conversation/" + convA, null);
                apiSend(ctx, "DELETE", UI + "conversation/" + convB, null);
            }
        }
    }

    private void deleteViaUi(Page page, String convId) {
        Locator item = page.locator(itemSelector(convId));
        item.hover();
        item.locator(".sidebar-item-delete").click();
        page.waitForSelector("#confirm-modal-overlay",
                new Page.WaitForSelectorOptions().setTimeout(5000));
        // dialog.confirm 由 conversation.delete(L1586-1592)固定文案
        assertThat(page.textContent("#confirm-modal-title")).isEqualTo("删除对话");
        page.click("#confirm-modal-ok");
        // onOk → _hide()(display:none)→ DELETE /conversation/{id} → loadList 移除该行
        page.waitForFunction(
                "(a) => !document.querySelector(\"#sidebarList .sidebar-item[data-conversation-id='\" + a + \"']\")",
                convId, WF_10S);
        assertOverlayHidden(page, "confirm-modal-overlay");
    }

    @Test
    void fileManagerModalOpensWithTree() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#file-manager-button");
            page.waitForSelector("#file-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            assertThat(page.isVisible("#file-list")).as("#file-list 容器可见").isTrue();
            // loadTree 异步(GET /file/tree 恒 200)→ renderTree 必写两个分支之一。
            // 等待条件必须排除静态「加载中...」占位(.loading-indicator,index.html):
            // 只等 innerHTML 非空会被占位立即满足,高负载下 fetch 未返回就断言 → 竞态
            // (2026-09-19 全量 *IT 门禁 flake 实证,画板功能引入时暴露)。
            page.waitForFunction(
                    "() => { const el = document.getElementById('file-list');"
                            + " return !!el && el.innerHTML.trim().length > 0"
                            + " && !el.querySelector('.loading-indicator'); }",
                    null, WF_10S);
            String html = page.locator("#file-list").innerHTML();
            assertThat(html.contains("file-tree") || html.contains("目录为空"))
                    .as("#file-list 渲染为 .file-tree 或「目录为空」二分支(renderTree L3307)").isTrue();

            assertThat(consoleErrorsOf(page)).as("文件弹窗 console 无 error").isEmpty();
            page.click("#file-close-btn");
            assertOverlayHidden(page, "file-modal-overlay");
        }
    }

    @Test
    void toolsModalOpensAndListsCapabilities() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#mcp-button");
            page.waitForSelector("#mcp-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            // /api/capabilities:RBAC 4 组(tool_git/maven/compile/render)恒在列表
            // (universal 7 组被服务端剔除 — RbacToolsBrowserIT 已证契约),渲染为 .skill-item
            page.waitForSelector("#mcp-list .skill-item",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
            assertThat(page.locator("#mcp-list .skill-item").count())
                    .as("#mcp-list 至少含 RBAC 4 组 capability").isGreaterThanOrEqualTo(4);
            assertThat(page.locator("#mcp-list").innerHTML()).isNotBlank();

            assertThat(consoleErrorsOf(page)).as("工具弹窗 console 无 error").isEmpty();
            page.click("#mcp-close-btn");
            assertOverlayHidden(page, "mcp-modal-overlay");
        }
    }

    @Test
    void knowledgeSpaceModalOpens() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            // 默认 profile rag.enabled=true → features.knowledge=true → #ks-button 保持可见
            // (PageHealthBrowserIT 正向门控已证;此处显式断言给出清晰诊断)
            assertThat(page.isVisible("#ks-button")).as("默认 profile ks-button 可见").isTrue();
            page.click("#ks-button");
            page.waitForSelector("#ks-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            assertThat(page.isVisible("#ks-sidebar")).as("#ks-sidebar 容器可见").isTrue();
            assertThat(page.locator("#ks-sidebar").innerHTML())
                    .as("#ks-sidebar 有内容(静态「暂无知识库」占位或 loadList 渲染结果)").isNotBlank();

            assertThat(consoleErrorsOf(page)).as("知识空间弹窗 console 无 error").isEmpty();
            page.click("#ks-close-btn");
            assertOverlayHidden(page, "ks-modal-overlay");
        }
    }

    @Test
    void skillsModalTabsSwitch() {
        try (BrowserContext ctx = adminContext()) {
            Page page = openIndex(ctx);
            page.click("#skills-button");
            page.waitForSelector("#skills-modal-overlay",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            // 默认 mine tab:listSkills → V1.1 为 wb04307201 种子 6 条 user_skill(+角色同步官方技能)
            page.waitForSelector("#skills-list .skill-item",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
            assertThat(page.locator("#skills-list .skill-item").count())
                    .as("mine tab 至少含种子技能").isGreaterThanOrEqualTo(1);
            assertThat(page.locator("#skills-modal-overlay button.skill-tab[data-tab='mine']")
                    .getAttribute("class")).contains("active");

            // ── 切 market tab:active class 迁移 + 搜索栏同步渲染 + 种子市场行异步加载 ──
            page.click("#skills-modal-overlay button.skill-tab[data-tab='market']");
            page.waitForSelector("#skill-market-search",
                    new Page.WaitForSelectorOptions().setTimeout(5000));
            assertThat(page.locator("#skills-modal-overlay button.skill-tab[data-tab='market']")
                    .getAttribute("class")).contains("active");
            assertThat(page.locator("#skills-modal-overlay button.skill-tab[data-tab='mine']")
                    .getAttribute("class")).doesNotContain("active");
            // V1.0 尾部种子 2 条官方 APPROVED market_skill → 行 class .ks-item(L5008)
            page.waitForSelector("#skills-list .ks-item",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
            assertThat(page.locator("#skills-list .ks-item").count())
                    .as("market tab 至少含 2 条官方种子技能").isGreaterThanOrEqualTo(2);

            assertThat(consoleErrorsOf(page)).as("技能弹窗 tab 切换 console 无 error").isEmpty();
            page.click("#skills-close-btn");
            assertOverlayHidden(page, "skills-modal-overlay");
        }
    }
}
