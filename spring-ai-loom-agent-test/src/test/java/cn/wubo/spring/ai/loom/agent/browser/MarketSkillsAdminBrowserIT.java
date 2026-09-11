package cn.wubo.spring.ai.loom.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 market-skills.html 深路径浏览器 IT:技能市场审批流
 * PENDING→APPROVED(通过)/ PENDING→REJECTED(拒绝评论必填拦截 + 填评论落 REJECTED)。
 *
 * <p><b>执行时校准点 1 —— 提交到市场的 API 契约(LoomAgentConfiguration L3216 源码核实):</b>
 * {@code POST /spring/ai/loom/user/market-skills},请求体反序列化为
 * {@code MarketCreateRequest(name, description, content, category)}(category 在 submit
 * 路径被忽略),转 {@code MarketSkillSubmitRequest} 调 {@code svc.submit} → 200 返回
 * {@code MarketSkill} JSON(含 id / status=PENDING)。普通用户提交即 PENDING(M4/#4 审批语义)。
 *
 * <p><b>执行时校准点 2 —— 通过按钮与确认交互(market-skills.js L237-247 源码核实):</b>
 * {@code button.approve-btn[data-id]} 点击后走<b>原生 {@code confirm("确认通过该技能?")}</b>,
 * 不是页面内 {@code #confirm-modal}(那个只服务下架/撤销公告的 confirmDialog)——
 * 必须用 {@code page.onDialog} accept。确认后 POST
 * {@code /admin/market-skills/{id}/approve} → showToast("已通过") + loadList 重渲染,
 * 徽章来自服务端重拉,即持久化证据。徽章形态(market-admin.js approvalBadge L499):
 * {@code span.approval-badge.approval-status-{PENDING|APPROVED|REJECTED}}。
 *
 * <p><b>执行时校准点 3 —— 拒绝评论必填 UI 形态(market-skills.js L248-260 源码核实):</b>
 * {@code button.reject-btn[data-id]} 点击 → 原生 {@code prompt("拒绝理由(必填):")};
 * 取消(null)直接 return;<b>空/空白评论由前端拦截</b>:
 * {@code alert("拒绝理由不能为空")} 且不发请求,状态仍 PENDING(拦截层次:click handler
 * 空白检查为第一道,MarketAdmin.reject 的 REJECT_COMMENT_REQUIRED 为第二道,后端 400
 * "reject 必须填评论" 为第三道——空评论在前端第一道即被拦下,断言按前端实际行为写)。
 * 填评论 → POST {@code /admin/market-skills/{id}/reject} body {comment} → REJECTED,
 * 评论回显在 approvalBadge 的 {@code .approval-comment} 块。
 *
 * <p><b>执行时校准点 4 —— 行定位(market-skills.js renderTable L202 源码核实):</b>
 * 每行 {@code <tr data-id="{id}">},id 取自提交响应 JSON,用
 * {@code #skill-table-container tr[data-id='...']} 精确定位(不靠 hasText 过滤技能名,
 * 时间戳名理论上可能撞历史行;data-id 全局唯一)。表格排序 PENDING 优先,新行必在第一页。
 *
 * <p><b>数据清理(控制器裁定):</b>本测试创建的 market_skill 行(通过与被拒各 1)在用例
 * 结束时经 {@code DELETE /admin/market-skills/{id}} 删除,@AfterEach 兜底(断言失败也清),
 * 防止残留行污染 Task 13 的 market-skills 页截图基线;提交用户经
 * {@code DELETE /admin/users/{username}} 删除,同样 @AfterEach 兜底。
 */
@DisplayName("P2 market-skills.html:审批流 PENDING→APPROVED / PENDING→REJECTED(评论必填)")
class MarketSkillsAdminBrowserIT extends BrowserTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUBMIT_PASS = "mktpass1";

    /** 被测提交用户;@AfterEach 兜底删除。 */
    private String createdUser;
    /** 被测 market_skill 行 id;@AfterEach 兜底下架(防污染 Task 13 截图基线)。 */
    private Long createdSkillId;

    @AfterEach
    void cleanup() {
        Long skillId = createdSkillId;
        createdSkillId = null;
        String user = createdUser;
        createdUser = null;
        if (skillId == null && user == null) return;
        try (BrowserContext admin = adminContext()) {
            // 先删技能行(级联清 user_skill/role_skill 引用),再删用户
            if (skillId != null) {
                apiSend(admin, "DELETE", UI + "admin/market-skills/" + skillId, null);
            }
            if (user != null) {
                apiSend(admin, "DELETE", UI + "admin/users/" + user, null);
            }
        } catch (Exception ignored) {
            // 清场尽力而为:可能已被测试主体删除(apiSend 对非 2xx 不抛)
        }
    }

    /** admin API 预置普通提交用户(校准点 1 前置);登记到 createdUser 供兜底清理。 */
    private String provisionSubmitter() {
        String user = "mktu" + System.currentTimeMillis() % 100000;
        try (BrowserContext admin = adminContext()) {
            APIResponse resp = apiSend(admin, "POST", UI + "admin/users",
                    "{\"username\":\"" + user + "\",\"nickname\":\"MKT U\","
                            + "\"password\":\"" + SUBMIT_PASS + "\",\"type\":\"USER\"}");
            assertThat(resp.status()).as("provision submitter " + user).isEqualTo(200);
        }
        createdUser = user;
        return user;
    }

    /**
     * 普通用户 context 走 POST /user/market-skills 提交 → PENDING(校准点 1)。
     * 返回 market_skill 行 id(供 UI data-id 行定位 + admin API 清理)。
     */
    private long submitSkill(String user, String skillName) throws Exception {
        try (BrowserContext ctx = newContext()) {
            loginViaApi(ctx, user, SUBMIT_PASS);
            APIResponse resp = apiSend(ctx, "POST", UI + "user/market-skills",
                    "{\"name\":\"" + skillName + "\",\"description\":\"e2e 审批测试技能\","
                            + "\"content\":\"# " + skillName + "\\n测试内容\",\"category\":\"测试\"}");
            assertThat(resp.status()).as("submit skill " + skillName).isEqualTo(200);
            JsonNode body = MAPPER.readTree(resp.text());
            assertThat(body.get("status").asText())
                    .as("普通用户提交 → PENDING(M4/#4 审批语义)").isEqualTo("PENDING");
            long id = body.get("id").asLong();
            createdSkillId = id;
            return id;
        }
    }

    private Locator rowById(Page page, long id) {
        return page.locator("#skill-table-container tr[data-id='" + id + "']");
    }

    /** 轮询等待 dialog 记录数达到 expected(回调在 Playwright 连接线程派发)。 */
    private void awaitDialogs(List<String> dialogs, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (dialogs.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void approvePendingSkillViaUi() throws Exception {
        String user = provisionSubmitter();
        String skill = "E2E审批通过" + System.currentTimeMillis() % 10000;
        long id = submitSkill(user, skill);
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            // 校准点 2:通过按钮走原生 confirm,必须 onDialog accept(无监听时 Playwright 自动 dismiss)
            List<String> dialogs = new CopyOnWriteArrayList<>();
            page.onDialog(d -> {
                dialogs.add(d.type() + "|" + d.message());
                d.accept();
            });
            page.navigate(baseUrl + UI + "admin/market-skills.html");
            page.waitForSelector("#skill-table-container tr[data-id='" + id + "']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // 初始状态:PENDING 徽章 + 通过/拒绝按钮在场
            Locator row = rowById(page, id);
            assertThat(row.innerText()).contains(skill);
            assertThat(row.locator("span.approval-status-PENDING").count()).isEqualTo(1);
            assertThat(row.locator("button.approve-btn").count()).isEqualTo(1);

            // 点击通过 → confirm("确认通过该技能?") → approve API → loadList 服务端重拉
            row.locator("button.approve-btn").click();
            page.waitForSelector(
                    "#skill-table-container tr[data-id='" + id + "'] span.approval-status-APPROVED",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            // 确认交互形态:恰好 1 个原生 confirm,文案精确(market-skills.js L239)
            awaitDialogs(dialogs, 1);
            assertThat(dialogs).hasSize(1);
            assertThat(dialogs.get(0)).isEqualTo("confirm|确认通过该技能?");

            // 重渲染后(徽章来自服务端数据 = 持久化证据):APPROVED 徽章,审批按钮消失
            Locator rowAfter = rowById(page, id);
            assertThat(rowAfter.locator("span.approval-status-APPROVED").count()).isEqualTo(1);
            assertThat(rowAfter.locator("button.approve-btn").count()).isZero();
            assertThat(rowAfter.locator("button.reject-btn").count()).isZero();
            assertThat(consoleErrorsOf(page)).isEmpty();

            // 清理:admin API 下架技能行 + 删提交用户(防污染 Task 13 截图基线)
            assertThat(apiSend(ctx, "DELETE", UI + "admin/market-skills/" + id, null).status())
                    .as("cleanup market_skill " + id).isEqualTo(200);
            createdSkillId = null;
            assertThat(apiSend(ctx, "DELETE", UI + "admin/users/" + user, null).status())
                    .as("cleanup user " + user).isEqualTo(200);
            createdUser = null;
        }
    }

    @Test
    void rejectRequiresComment() throws Exception {
        String user = provisionSubmitter();
        String skill = "E2E审批拒绝" + System.currentTimeMillis() % 10000;
        long id = submitSkill(user, skill);
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            // 校准点 3:拒绝走原生 prompt("拒绝理由(必填):");空评论 → 原生 alert 拦截。
            // promptReply 控制 prompt 应答:先 ""(测拦截),后真实评论(测落 REJECTED)。
            List<String> dialogs = new CopyOnWriteArrayList<>();
            AtomicReference<String> promptReply = new AtomicReference<>("");
            page.onDialog(d -> {
                dialogs.add(d.type() + "|" + d.message());
                if ("prompt".equals(d.type())) {
                    d.accept(promptReply.get());
                } else {
                    d.accept();
                }
            });
            page.navigate(baseUrl + UI + "admin/market-skills.html");
            page.waitForSelector("#skill-table-container tr[data-id='" + id + "']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            Locator row = rowById(page, id);
            assertThat(row.locator("span.approval-status-PENDING").count()).isEqualTo(1);

            // ===== 1. 空评论 → 前端第一道拦截:prompt 回 "" → alert,不发 reject 请求 =====
            row.locator("button.reject-btn").click();
            awaitDialogs(dialogs, 2);
            assertThat(dialogs).hasSize(2);
            assertThat(dialogs.get(0)).isEqualTo("prompt|拒绝理由(必填):");
            assertThat(dialogs.get(1)).isEqualTo("alert|拒绝理由不能为空");

            // 状态仍 PENDING,拒绝按钮仍在(提交被拦截,无请求发出)
            Locator rowStillPending = rowById(page, id);
            assertThat(rowStillPending.locator("span.approval-status-PENDING").count()).isEqualTo(1);
            assertThat(rowStillPending.locator("button.reject-btn").count()).isEqualTo(1);
            assertThat(rowStillPending.locator("span.approval-status-REJECTED").count()).isZero();

            // ===== 2. 填评论 → POST /reject → REJECTED 徽章 + 评论回显 =====
            promptReply.set("E2E 拒绝理由:内容不符合规范");
            rowStillPending.locator("button.reject-btn").click();
            page.waitForSelector(
                    "#skill-table-container tr[data-id='" + id + "'] span.approval-status-REJECTED",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
            // 第 2 次点击再弹 prompt(第 3 条记录),带真实评论应答;成功路径用 showToast,
            // 不再产生 alert(失败会 alert"拒绝失败: ..." → 第 4 条且徽章不变)
            awaitDialogs(dialogs, 3);
            assertThat(dialogs).hasSize(3);
            assertThat(dialogs.get(2)).isEqualTo("prompt|拒绝理由(必填):");

            Locator rowAfter = rowById(page, id);
            assertThat(rowAfter.locator("span.approval-status-REJECTED").count()).isEqualTo(1);
            assertThat(rowAfter.locator("button.approve-btn").count()).isZero();
            assertThat(rowAfter.locator("button.reject-btn").count()).isZero();
            // 拒绝评论回显在 approvalBadge 的 .approval-comment 块(market-admin.js L495-497)
            assertThat(rowAfter.locator(".approval-comment").innerText())
                    .contains("E2E 拒绝理由:内容不符合规范");
            assertThat(consoleErrorsOf(page)).isEmpty();

            // 清理:admin API 下架被拒技能行 + 删提交用户
            assertThat(apiSend(ctx, "DELETE", UI + "admin/market-skills/" + id, null).status())
                    .as("cleanup market_skill " + id).isEqualTo(200);
            createdSkillId = null;
            assertThat(apiSend(ctx, "DELETE", UI + "admin/users/" + user, null).status())
                    .as("cleanup user " + user).isEqualTo(200);
            createdUser = null;
        }
    }
}
