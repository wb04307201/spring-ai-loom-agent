package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 admin 深路径收尾浏览器 IT:knowledge-market.html(tag UI)+ user.html(per-user
 * ask-logs 区块)。只读体检,不创建持久数据,无需清理。
 *
 * <p><b>执行时校准点 1 —— knowledge-market tag UI(knowledge-market.js 源码核实):</b>
 * 表格单元格 tag 渲染为 {@code div.tag-chip-group} 包 {@code span.tag-chip[data-tag]}
 * (renderTagChipsHtml L142-161,无 tag 时显示 "—");行内编辑按钮
 * {@code button.tag-edit-row-btn[data-id]}(renderTable L252)→ openTagEdit(L363)
 * 把 {@code #tag-edit-modal} 置 display:flex,预填 {@code #tag-edit-input}(现有 tag
 * join ", ")、渲染 {@code #tag-edit-current},保存走 {@code #tag-edit-save} →
 * MarketAdmin.updateMarketTags。<b>种子库 loom_market_knowledge 无市场知识种子</b>
 * (V1.0 尾部只种 2 条 market_skill)→ 列表初始为空,renderTable(L171-174)输出
 * {@code <div class="empty-state">市场暂无任何知识库</div>},tag 编辑按钮 0 个。
 * 因此本用例的实质断言落在容器 + 空态文案 + toolbar 按钮(非条件),tag 编辑弹窗
 * 开合按 brief 条件执行(count>0 才点),两条路径互斥覆盖、绝不恒真。
 *
 * <p><b>执行时校准点 2 —— user.html ask-logs 区块(user.js 源码核实):</b>
 * 所有 id 真实存在于 user.html:{@code #ask-logs-card}(角色分配卡之后)/
 * {@code #ask-logs-table}(div.table-container 含初始"加载中..."指示);数据源
 * {@code GET /admin/ask-logs?limit=50&username=<URL>}(loom_tool_call_log 无
 * askUser 行 → renderAskLogs 空态"暂无提问记录");per-user 视图无"用户"列(永远
 * 链接到当前 URL 用户)、无"用户"过滤输入框(URL 已固定)、会话列是可点击链接
 * {@code <a class="user-link" href="conversation.html?id=...">}(跳转上下文)。
 * 顶部 {@code #refresh-btn} 点击 → loadAskLogsForUser() 重拉(user.js 顶部
 * refresh 处理器 L430-434 末尾追加);无原生 dialog、无 confirm,console 无 error。
 */
@DisplayName("P2 knowledge-market.html(tag UI)+ user.html(per-user ask-logs 区块)")
class KnowledgeMarketAndUserBrowserIT extends BrowserTestBase {

    @Test
    void knowledgeMarketPageRendersTagUi() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/knowledge-market.html");
            // 校准点 1:容器初始即存在(加载中指示);等列表加载完成 = 空态或表格行出现
            page.waitForSelector("#knowledge-table-container");
            page.waitForSelector(
                    "#knowledge-table-container .empty-state, #knowledge-table-container table",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 非条件实质断言:toolbar + 容器状态(空态文案精确匹配源码 L173)=====
            assertThat(page.isVisible("#create-knowledge-btn")).isTrue();
            assertThat(page.isVisible("#refresh-btn")).isTrue();
            int rowCount = page.locator("#knowledge-table-container tbody tr").count();
            if (rowCount == 0) {
                // 种子库无市场知识 → 空态文案必须精确(不是"加载失败",加载失败走 L133 分支)
                assertThat(page.locator("#knowledge-table-container .empty-state").innerText())
                        .isEqualTo("市场暂无任何知识库");
            } else {
                // 有行(历史残留数据)→ 表头含"标签"列(renderTable L262 共 11 列)。
                // 定位 thead(单元素)而非 thead th(多元素)—— Playwright Java
                // innerText() 对多元素 locator 抛 strict mode violation。
                assertThat(page.locator("#knowledge-table-container thead").innerText())
                        .contains("标签");
            }
            assertThat(consoleErrorsOf(page)).isEmpty();

            // ===== 条件路径:tag 编辑弹窗开合(有数据行才点;空库时上方空态断言兜底)=====
            if (page.locator("button.tag-edit-row-btn").count() > 0) {
                page.locator("button.tag-edit-row-btn").first().click();
                page.waitForSelector("#tag-edit-modal");
                assertThat(page.isVisible("#tag-edit-modal")).isTrue();
                assertThat(page.isVisible("#tag-edit-input")).isTrue();
                // 当前 tag 列表容器已渲染(有 tag → chip 组;无 tag → "（暂无标签）",L341-361)
                assertThat(page.locator("#tag-edit-current").innerHTML())
                        .containsAnyOf("tag-chip", "（暂无标签）");
                // 关闭走取消按钮(knowledge-market.js L696:tag-edit-cancel → closeTagEdit)
                page.click("#tag-edit-cancel");
                assertThat(page.isVisible("#tag-edit-modal")).isFalse();
                assertThat(consoleErrorsOf(page)).isEmpty();
            }
        }
    }

    @Test
    void userPageRendersAskLogsSection() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/user.html?username=" + ADMIN_USER);
            // 校准点 2:#ask-logs-table 初始即存在(加载中指示),等 loadAskLogsForUser 完成
            page.waitForSelector("#ask-logs-table");
            page.waitForSelector(
                    "#ask-logs-table .empty-state, #ask-logs-table table",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            assertThat(page.isVisible("#ask-logs-card")).isTrue();
            if (page.locator("#ask-logs-table table").count() == 0) {
                // 无 askUser 行 → 空态文案必须精确(不是"加载失败",加载失败走另一分支)
                assertThat(page.locator("#ask-logs-table .empty-state").innerText())
                        .isEqualTo("暂无提问记录");
            } else {
                // 有数据(历史残留)→ 表头不含"用户"列(per-user 视图自带过滤)且含
                // "答案 / 状态"列;thead 单元素定位(多元素 th 触发 strict mode violation)
                assertThat(page.locator("#ask-logs-table thead").innerText())
                        .contains("答案 / 状态")
                        .doesNotContain("用户");
            }

            // ===== 校准点 2 续:顶部 #refresh-btn 点击 → loadAskLogsForUser 重拉 =====
            page.click("#refresh-btn");
            page.waitForSelector(
                    "#ask-logs-table .empty-state, #ask-logs-table table",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            assertThat(page.isVisible("#ask-logs-table")).isTrue();
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}