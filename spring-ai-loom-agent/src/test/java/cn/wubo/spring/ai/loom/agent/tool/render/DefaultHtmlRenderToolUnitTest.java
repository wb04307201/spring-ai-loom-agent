package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-01 (Bug #5 重构) 单元测试:验证 renderHtmlFile 工具不再返回可嵌入 markdown,
 * 只返回 PNG 文件相对路径 + 后续工具提示(viewFileUrl / downloadFileUrl),
 * 把 URL → markdown 语法选择权交给模型,避免工具预先选择的 markdown 语法跟 URL 类型不匹配
 * (file-view 的 /file/view/<id> 是 HTML viewer 端点不是 image bytes)。
 *
 * 纯函数测试,不需要真 Chromium:mock HtmlRenderEngine.render() 返回固定 RenderResult。
 */
class DefaultHtmlRenderToolUnitTest {

    @Test
    @DisplayName("renderHtmlFile 返回 PNG 路径 + 提示调 viewFileUrl/downloadFileUrl,不包含 ![alt](url) 嵌入语法")
    void renderHtmlFileReturnShape() throws Exception {
        // mock engine: render 任何输入都返回固定 PNG bytes
        HtmlRenderEngine engine = mock(HtmlRenderEngine.class);
        byte[] fakePng = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        when(engine.render(anyString(), any(HtmlRenderEngine.Viewport.class), anyBoolean(), anyInt()))
                .thenReturn(new HtmlRenderEngine.RenderResult(fakePng, 100, 200));

        IFile file = mock(IFile.class);
        LoomAgentProperties.RenderProperty cfg = new LoomAgentProperties.RenderProperty();
        DefaultHtmlRenderTool tool = new DefaultHtmlRenderTool(engine, file, null, cfg);

        // 工具要从 username 目录下的 prototypes/ 读 login.html,预先写一个最小 HTML
        java.nio.file.Path baseDir = cn.wubo.loom.file.core.LoomPaths.userFileDir(null, "wb04307201");
        java.nio.file.Path protoDir = baseDir.resolve("prototypes");
        java.nio.file.Files.createDirectories(protoDir);
        java.nio.file.Files.writeString(protoDir.resolve("login.html"),
                "<html><body><h1>LOGIN</h1></body></html>");
            // ToolContext 是 final class,用构造器注入 Map;getContext() 直接返回该 Map
            ToolContext ctx = new ToolContext(Map.of(
                    "baseUrl", "http://localhost:8080",
                    "username", "wb04307201"));
            String out = tool.renderHtmlFile(
                    "prototypes/login.html",
                    "login",
                    "desktop",
                    true,
                    ctx);
            System.out.println("===TOOL OUTPUT===");
            System.out.println(out);
            System.out.println("===END===");
            // 1. 必须包含 PNG 相对路径
            assertThat(out).contains("PNG 已生成:");
            assertThat(out).contains("prototypes/login-");
            assertThat(out).contains(".png");
            // 2. 必须包含宽高
            assertThat(out).contains("(100x200)");
            // 3. 必须提示调 viewFileUrl 拿预览 markdown 链接
            assertThat(out).contains("viewFileUrl('prototypes/login-");
            assertThat(out).contains("拿预览 markdown 链接");
            // 4. 必须提示调 downloadFileUrl 拿下载链接
            assertThat(out).contains("downloadFileUrl('prototypes/login-");
            assertThat(out).contains("拿下载链接");
            // 5. 必须严禁 markdown ![]() 嵌入语法(避免 broken image 复发 Bug #5)
//    注意:警告文本里含字面 `![alt]`,所以这里用 Pattern 而不是字符串包含检查真正的图片嵌入语法。
            assertThat(out)
                    .as("renderHtmlFile 返回不能含 ![alt](url) 真实嵌入语法(警告文本里的字面字符不算)")
                    .doesNotMatch(".*!\\[[^\\]]*\\]\\([^)]*\\).*");
            // 6. 不能内联给用户预览链接(避免决定 markdown 链接语法)
            assertThat(out)
                    .as("renderHtmlFile 不能内联生成 markdown 预览链接 —— URL 是 file-view HTML viewer,不应预拼")
                    .doesNotContain("markdown 格式")
                    .doesNotContain("预览链接:");
    }
}