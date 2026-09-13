package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine.RenderResult;
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine.RenderUnavailableException;
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine.RenderBusyException;
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine.Viewport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultHtmlRenderTool 单元测试(mock 引擎,不碰真浏览器)。
 * 覆盖 spec §1.2 参数校验顺序 + 返回文本契约全分支 + D7 存图/桥接 + D8 绝不抛异常。
 */
class DefaultHtmlRenderToolTest {

    @TempDir
    Path baseDir;

    private IFile file;
    private HtmlRenderEngine engine;
    private LoomAgentProperties.RenderProperty cfg;
    private DefaultHtmlRenderTool tool;

    @BeforeEach
    void setUp() {
        file = mock(IFile.class);
        engine = mock(HtmlRenderEngine.class);
        cfg = new LoomAgentProperties.RenderProperty();
        tool = new DefaultHtmlRenderTool(engine, file, baseDir.toString(), cfg);
    }

    private ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        if (username != null) m.put("username", username);
        m.put("baseUrl", "http://localhost:8080");
        return new ToolContext(m);
    }

    private Path writeHtml(String user, String relPath, String content) throws Exception {
        // 沙箱 = {usersBasePath}/{username}/file
        Path p = baseDir.resolve(user).resolve("file").resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("缺 username 上下文 → [渲染失败] 文本,不抛")
    void missingUsername() {
        String out = tool.renderHtmlFile("a.html", null, null, null, ctx(null));
        assertThat(out).isEqualTo("[渲染失败] 缺少用户会话上下文");
    }

    @Test
    @DisplayName("htmlFilePath 空 → [渲染失败]")
    void blankPath() {
        assertThat(tool.renderHtmlFile(null, null, null, null, ctx("u"))).startsWith("[渲染失败]");
        assertThat(tool.renderHtmlFile("  ", null, null, null, ctx("u"))).startsWith("[渲染失败]");
    }

    @Test
    @DisplayName("文件不存在 → [渲染失败] HTML 文件不存在")
    void fileNotFound() {
        String out = tool.renderHtmlFile("prototypes/none.html", null, null, null, ctx("u"));
        assertThat(out).startsWith("[渲染失败] HTML 文件不存在");
    }

    @Test
    @DisplayName("非 .html/.htm 扩展名 → [渲染失败] 不是 .html 文件")
    void wrongExtension() throws Exception {
        writeHtml("u", "note.txt", "hello");
        String out = tool.renderHtmlFile("note.txt", null, null, null, ctx("u"));
        assertThat(out).startsWith("[渲染失败] 不是 .html 文件");
    }

    @Test
    @DisplayName("路径穿越 ../ → [渲染失败](PathSecurityUtils SecurityException 转文本)")
    void pathTraversalRejected() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        // 越权目标:baseDir/u/secret.html(在沙箱 baseDir/u/file 之外一层)
        Files.writeString(baseDir.resolve("u").resolve("secret.html"), "<p>secret</p>");
        String out = tool.renderHtmlFile("../secret.html", null, null, null, ctx("u"));
        assertThat(out).startsWith("[渲染失败]");
        assertThat(out).doesNotContain("渲染成功");
        verify(engine, never()).render(anyString(), any(), anyBoolean(), anyInt());
    }

    @Test
    @DisplayName("超过 maxHtmlBytes → [渲染失败] 超过 ... 上限")
    void tooLarge() throws Exception {
        cfg.setMaxHtmlBytes(64);
        writeHtml("u", "big.html", "<p>" + "x".repeat(200) + "</p>");
        String out = tool.renderHtmlFile("big.html", null, null, null, ctx("u"));
        // humanSize(64) 非整 MB → "64 字节",全文 "超过 64 字节 上限";默认 2MB 配置下才渲染 spec 字面 "超过 2MB 上限"
        assertThat(out).startsWith("[渲染失败] 超过 64 字节");
    }

    @Test
    @DisplayName("Chromium 不可用 → [渲染不可用] 含 reason + provision 指引(D8)")
    void chromiumUnavailable() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RenderUnavailableException("三级探测全部失败", HtmlRenderEngine.INSTALL_HINT));
        String out = tool.renderHtmlFile("a.html", null, null, null, ctx("u"));
        assertThat(out).startsWith("[渲染不可用]");
        assertThat(out).contains("三级探测全部失败").contains("provision-chromium.sh");
    }

    @Test
    @DisplayName("渲染器忙 → [渲染失败] 渲染器忙,请稍后重试(D9)")
    void busy() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RenderBusyException("busy"));
        String out = tool.renderHtmlFile("a.html", null, null, null, ctx("u"));
        assertThat(out).isEqualTo("[渲染失败] 渲染器忙,请稍后重试");
    }

    @Test
    @DisplayName("渲染异常 → [渲染失败] {message},不抛")
    void renderError() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RuntimeException("Target page crashed"));
        String out = tool.renderHtmlFile("a.html", null, null, null, ctx("u"));
        assertThat(out).isEqualTo("[渲染失败] Target page crashed");
    }

    @Test
    @DisplayName("成功:存图到 prototypes/{name}-{ts}.png + temp 桥接 + 三行返回契约(D7)")
    void successWritesPngAndBridges() throws Exception {
        Path html = writeHtml("u", "prototypes/login.html", "<p>登录页</p>");
        // 真 1x1 PNG(Tika 按 8 字节 magic 探测 mimeType,伪造字节会测出 octet-stream)
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(new RenderResult(png, 2880, 1800));
        when(file.getByExactPath(anyString(), eq("u"))).thenReturn(null);
        when(file.insert(any(FileRecord.class), eq("u"))).thenReturn(1);

        String out = tool.renderHtmlFile("prototypes/login.html", "login", null, null, ctx("u"));

        assertThat(out).startsWith("渲染成功: prototypes/login-").contains(".png (2880x1800)");
        assertThat(out).contains("预览链接:http://localhost:8080/file/view/").contains("markdown格式:![login](");
        // PNG 真的落盘在 {base}/u/prototypes/ 下
        try (var stream = Files.list(html.getParent())) {
            assertThat(stream.map(p -> p.getFileName().toString()).toList())
                    .anyMatch(n -> n.startsWith("login-") && n.endsWith(".png"));
        }
        // insert 走 usage='temp' + image/png
        ArgumentCaptor<FileRecord> cap = ArgumentCaptor.forClass(FileRecord.class);
        verify(file).insert(cap.capture(), eq("u"));
        assertThat(cap.getValue().usage()).isEqualTo("temp");
        assertThat(cap.getValue().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("device 白名单:非法值 fallback desktop(1440x900);mobile=390x844;tablet=768x1024")
    void deviceWhitelist() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(new RenderResult(new byte[]{1}, 10, 10));
        when(file.getByExactPath(anyString(), anyString())).thenReturn(null);

        tool.renderHtmlFile("a.html", null, "smart-tv", null, ctx("u"));
        ArgumentCaptor<Viewport> vp = ArgumentCaptor.forClass(Viewport.class);
        verify(engine).render(anyString(), vp.capture(), anyBoolean(), anyInt());
        assertThat(vp.getValue()).isEqualTo(new Viewport(1440, 900));

        tool.renderHtmlFile("a.html", null, "mobile", null, ctx("u"));
        tool.renderHtmlFile("a.html", null, "tablet", null, ctx("u"));
        ArgumentCaptor<Viewport> all = ArgumentCaptor.forClass(Viewport.class);
        verify(engine, org.mockito.Mockito.times(3)).render(anyString(), all.capture(), anyBoolean(), anyInt());
        assertThat(all.getAllValues().get(1)).isEqualTo(new Viewport(390, 844));
        assertThat(all.getAllValues().get(2)).isEqualTo(new Viewport(768, 1024));
    }

    @Test
    @DisplayName("fullPage 缺省 true;显式 false 透传")
    void fullPageDefault() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(new RenderResult(new byte[]{1}, 10, 10));
        when(file.getByExactPath(anyString(), anyString())).thenReturn(null);

        tool.renderHtmlFile("a.html", null, null, null, ctx("u"));
        tool.renderHtmlFile("a.html", null, null, false, ctx("u"));
        ArgumentCaptor<Boolean> fp = ArgumentCaptor.forClass(Boolean.class);
        verify(engine, org.mockito.Mockito.times(2)).render(anyString(), any(), fp.capture(), anyInt());
        assertThat(fp.getAllValues()).containsExactly(true, false);
    }

    @Test
    @DisplayName("sanitizeImageName:剥分隔符/非法字符;全非法或空 → null(用 HTML 文件名兜底)")
    void sanitize() {
        assertThat(DefaultHtmlRenderTool.sanitizeImageName("login page/v2")).isEqualTo("login-page-v2");
        // "../../etc" 逐字符:. . / . . / e t c → '/' 转 '-', '.' 是合法文件名字符保留 → "..-..-etc"
        assertThat(DefaultHtmlRenderTool.sanitizeImageName("../../etc")).isEqualTo("..-..-etc");
        assertThat(DefaultHtmlRenderTool.sanitizeImageName("  ")).isNull();
        assertThat(DefaultHtmlRenderTool.sanitizeImageName(null)).isNull();
        assertThat(DefaultHtmlRenderTool.sanitizeImageName("///")).isNull();
    }

    @Test
    @DisplayName("imageName 缺省 → 用 HTML 文件名(不含扩展名)")
    void imageNameFallbackToHtmlFileName() throws Exception {
        writeHtml("u", "dashboard.htm", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(new RenderResult(new byte[]{1}, 10, 10));
        when(file.getByExactPath(anyString(), anyString())).thenReturn(null);
        String out = tool.renderHtmlFile("dashboard.htm", null, null, null, ctx("u"));
        assertThat(out).startsWith("渲染成功: prototypes/dashboard-").contains("![dashboard](");
    }

    @Test
    @DisplayName("fileId 桥接失败(insert 抛/返回后 getByExactPath null)→ [渲染失败] 文件注册失败")
    void bridgeFailure() throws Exception {
        writeHtml("u", "a.html", "<p>x</p>");
        when(engine.render(anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(new RenderResult(new byte[]{1}, 10, 10));
        when(file.getByExactPath(anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        String out = tool.renderHtmlFile("a.html", null, null, null, ctx("u"));
        assertThat(out).isEqualTo("[渲染失败] 截图文件注册失败,无法生成预览链接");
    }
}
