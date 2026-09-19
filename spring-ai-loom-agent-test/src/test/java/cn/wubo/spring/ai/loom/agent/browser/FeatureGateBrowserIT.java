package cn.wubo.spring.ai.loom.agent.browser;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 知识空间全局开关浏览器门控 IT(rag.enabled=false 独立 profile)。
 *
 * <p>本类自带完整 @SpringBootTest 注解(properties 含
 * {@code spring.ai.loom.agent.rag.enabled=false}),就近覆盖 {@link BrowserTestBase}
 * 上的注解 → 独立 Spring context + 独立随机端口;H2 {@code AUTO_SERVER=TRUE}
 * 允许与默认 context 共享 ./target/test-ds 文件库。
 *
 * <p>前端隐藏机制(app.js init 段已确认,非猜测):features.knowledge === false →
 * {@code document.getElementById("ks-button").style.display = "none"}
 * (inline style,元素保留在 DOM);fail-open(探测失败保持显示)。
 * 断言据此用 waitForFunction 轮询 computedStyle,而非盲等固定时长。
 *
 * <p>服务端事实:{@code GET /spring/ai/loom/api/features}(需登录)→
 * {@code {"knowledge":<VectorStore bean 存在性>}};rag.enabled=false 时
 * RagConfiguration 不激活、容器无 VectorStore → false。
 */
@DisplayName("P0-2 知识空间门控:rag.enabled=false → features.knowledge=false + ks-button 隐藏")
@SpringBootTest(classes = LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.loom.agent.rag.enabled=false",
                "spring.ai.loom.agent.users-base-path=./target/e2e-files/users"
        })
class FeatureGateBrowserIT extends BrowserTestBase {

    @Test
    void featuresReportsKnowledgeFalse() {
        try (BrowserContext ctx = adminContext()) {
            String body = apiGet(ctx, UI + "api/features");
            assertThat(body).contains("\"knowledge\"").contains("false");
        }
    }

    @Test
    void ksButtonHiddenWhenRagDisabled() {
        try (BrowserContext ctx = adminContext()) {
            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            // app.js init 异步 fetch /api/features 后置 style.display='none':
            // 轮询等待隐藏生效(默认 30s 超时;fail-open 未隐藏则超时失败,不放水)
            page.waitForFunction(
                    "() => { const ks = document.getElementById('ks-button');"
                            + " return !ks || getComputedStyle(ks).display === 'none'; }");
            assertThat(page.isVisible("#ks-button")).isFalse();
        }
    }

    /**
     * 聊天附件上传与 RAG 解耦的回归锁(2026-09-19 解耦改造):
     * rag.enabled=false 部署里 {@code +}/画板按钮恒可见、POST /file/upload 可用
     * (聊天附件腿只需要落盘 + file_info,不需要 embedding);知识链路门控报 false。
     * 改造前:IUpload 被 {@code @ConditionalOnBean(VectorStore)} 门控 → 上传路由缺席 404、
     * 按钮默认隐藏 —— 纯聊天场景被知识库组件可用性拖累。
     */
    @Test
    @DisplayName("rag.enabled=false:+/画板按钮恒可见 + 聊天附件上传 200 拿 fileId + 知识门控 false")
    void chatAttachmentUploadWorksWhenRagDisabled() {
        try (BrowserContext ctx = adminContext()) {
            // 知识上传链路门控报 false(知识空间降级,供知识空间 UI 使用)
            String gate = apiGet(ctx, UI + "knowledge/checkKnowledgeUpload");
            assertThat(gate).as("降级部署知识门控应报 false").contains("false");

            Page page = newPage(ctx);
            page.navigate(baseUrl + UI + "index.html");
            page.waitForSelector("#textarea");
            page.waitForFunction("() => !!window._loomAgent", null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));

            // +/画板按钮不受 RAG 门控,恒可见
            assertThat(page.isVisible("#image-add-btn")).as("+ 按钮恒可见").isTrue();
            assertThat(page.isVisible("#canvas-add-btn")).as("画板按钮恒可见").isTrue();

            // 聊天附件上传真实可用:multipart POST → 200 + fileId
            APIResponse resp = ctx.request().post(baseUrl + UI + "file/upload",
                    RequestOptions.create().setMultipart(
                            com.microsoft.playwright.options.FormData.create()
                                    .set("file", new com.microsoft.playwright.options.FilePayload(
                                            "e2e-attach.txt", "text/plain",
                                            "chat attachment without RAG".getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)))));
            assertThat(resp.status()).as("降级部署聊天附件上传应 200").isEqualTo(200);
            assertThat(resp.text()).as("应返回 fileId").contains("fileId");
        }
    }
}
