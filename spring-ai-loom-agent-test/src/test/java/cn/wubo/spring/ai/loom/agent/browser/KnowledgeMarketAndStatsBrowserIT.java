package cn.wubo.spring.ai.loom.agent.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 admin 深路径收尾浏览器 IT:knowledge-market.html(tag UI)+ stats.html
 * (token 用量 + ask-logs 双区块)。只读体检,不创建持久数据,无需清理。
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
 * <p><b>执行时校准点 2 —— stats 双区块(stats.js 源码核实):</b>
 * 所有 id 真实存在于 stats.html:{@code #bar-chart}(L113 div.bar-chart,非 canvas)/
 * {@code #stats-table}(L118)/ {@code #year-input} {@code #month-input}(stats.js L12-14
 * 初始化为当前年月)/ {@code #reload-btn}(查询,L201)/ {@code #month-label}(load 时置
 * "{year}-{MM} 月用量",L32);数据源 {@code GET /admin/stats/tokens/monthly?year&month}
 * (L36-37,loom_chat_usage 初始空 → renderBarChart 空态"本月无用量"L62、renderTable
 * 空态"{year}-{month} 无用量记录"L81)。ask-logs 区块:{@code #ask-logs-table}(L155)/
 * {@code #ask-logs-user}(过滤输入,L145)/ {@code #ask-logs-refresh}(L203 →
 * loadAskLogs);数据源 {@code GET /admin/ask-logs?limit=50[&username=]}(L148,
 * loom_tool_call_log 无 askUser 行 → renderAskLogs 空态"暂无提问记录"L167)。
 * 断言适配空态但每条都是具体文案/值匹配,非恒真。
 *
 * <p><b>执行时校准点 3 —— #ask-logs-refresh 点击(stats.js L203):</b>直接绑定
 * loadAskLogs,无原生 dialog、无 confirm;成功路径重渲染表格,失败也只写
 * empty-state 文案(不抛 console error)。断言:点击后容器仍可见 + consoleErrorsOf 为空。
 */
@DisplayName("P2 knowledge-market.html(tag UI)+ stats.html(token 用量 + ask-logs 区块)")
class KnowledgeMarketAndStatsBrowserIT extends BrowserTestBase {

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
                // 有行(历史残留数据)→ 表头含"标签"列(renderTable L262 共 11 列)
                assertThat(page.locator("#knowledge-table-container thead th").innerText())
                        .contains("标签");
                assertThat(page.locator("#knowledge-table-container tbody tr").count())
                        .isEqualTo(rowCount);
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
    void statsPageRendersBothSections() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "admin/stats.html");
            // 校准点 2:#bar-chart 初始即存在("加载中..."),等 load() 完成 = 空态或 bar 行出现
            page.waitForSelector("#bar-chart");
            page.waitForSelector("#bar-chart .empty-state, #bar-chart .bar-row",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // ===== 区块一:token 用量(loom_chat_usage 初始空 → 空态文案精确)=====
            LocalDate now = LocalDate.now();
            assertThat(page.inputValue("#year-input"))
                    .isEqualTo(String.valueOf(now.getYear()));
            assertThat(page.inputValue("#month-input"))
                    .isEqualTo(String.valueOf(now.getMonthValue()));
            // month-label 由 load() 置为 "{year}-{MM} 月用量"(stats.js L32,月补零)
            assertThat(page.innerText("#month-label")).isEqualTo(
                    now.getYear() + "-" + String.format("%02d", now.getMonthValue()) + " 月用量");
            assertThat(page.isVisible("#reload-btn")).isTrue();
            assertThat(page.isVisible("#stats-table")).isTrue();
            if (page.locator("#bar-chart .bar-row").count() == 0) {
                assertThat(page.locator("#bar-chart .empty-state").innerText())
                        .isEqualTo("本月无用量");
                assertThat(page.locator("#stats-table .empty-state").innerText())
                        .isEqualTo(now.getYear() + "-" + now.getMonthValue() + " 无用量记录");
            } else {
                // 有用量(历史数据)→ 表格含 6 列表头 + 合计行(renderTable L99-115)
                assertThat(page.locator("#stats-table thead th").innerText())
                        .contains("用户").contains("总 Token");
                assertThat(page.locator("#stats-table tfoot").innerText()).contains("合计");
            }

            // ===== 区块二:ask-logs(loom_tool_call_log 无 askUser 行 → 空态)=====
            assertThat(page.isVisible("#ask-logs-table")).isTrue();
            assertThat(page.isVisible("#ask-logs-user")).isTrue();
            page.waitForSelector("#ask-logs-table .empty-state, #ask-logs-table table",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            if (page.locator("#ask-logs-table table").count() == 0) {
                assertThat(page.locator("#ask-logs-table .empty-state").innerText())
                        .isEqualTo("暂无提问记录");
            } else {
                assertThat(page.locator("#ask-logs-table thead th").innerText())
                        .contains("答案 / 状态");
            }

            // ===== 校准点 3:点击 #ask-logs-refresh → loadAskLogs 重拉,console 无 error =====
            page.click("#ask-logs-refresh");
            page.waitForSelector("#ask-logs-table .empty-state, #ask-logs-table table",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            assertThat(page.isVisible("#ask-logs-table")).isTrue();
            assertThat(consoleErrorsOf(page)).isEmpty();
        }
    }
}
