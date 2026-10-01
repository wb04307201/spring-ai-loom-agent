package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * IHtmlRenderTool 默认实现(spec 2026-09-09-html-render-tool-design §1.2)。
 * <p>
 * 校验顺序(镜像 DefaultAskUserTool):username 上下文 → htmlFilePath 非空 → 沙箱解析 +
 * 存在性 + 扩展名(.html/.htm)→ 读文件(大小上限 maxHtmlBytes)→ device 白名单
 * (非法值 fallback desktop)→ 渲染 → 存图 prototypes/ → FileIdBridge 桥接 → 三行返回契约。
 * <p>
 * <b>D8 铁律:所有失败分支返回文本,绝不抛异常</b> —— MessageChatMemoryAdvisor
 * 只在 ON_COMPLETE 落库,工具抛异常 = 整轮对话记忆丢失。
 */
public class DefaultHtmlRenderTool implements IHtmlRenderTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultHtmlRenderTool.class);

    /** 输出文件名时间戳格式(spec §1.2 返回契约示例 login-20260909153012.png)。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final Map<String, HtmlRenderEngine.Viewport> DEVICES = Map.of(
            "desktop", new HtmlRenderEngine.Viewport(1440, 900),
            "tablet", new HtmlRenderEngine.Viewport(768, 1024),
            "mobile", new HtmlRenderEngine.Viewport(390, 844));

    private final HtmlRenderEngine engine;
    /** 用户树根（沙箱 = {usersBasePath}/{username}/file），null/blank 回退 LoomPaths 默认。 */
    private final String usersBasePath;
    private final LoomAgentProperties.RenderProperty cfg;

    public DefaultHtmlRenderTool(HtmlRenderEngine engine, IFile file, String usersBasePath,
                                 LoomAgentProperties.RenderProperty cfg) {
        this.engine = engine;
        this.usersBasePath = cn.wubo.loom.file.core.LoomPaths.orDefaultUsersBase(usersBasePath);
        this.cfg = cfg;
    }

    @Tool(description = "把一个本地单页 HTML 文件用无头 Chromium 渲染成 PNG 截图并保存。"
            + "适用:界面原型图、数据分析单页、报告可视化。HTML 必须自包含(内联 CSS/JS)——渲染时禁止一切外部网络请求。"
            + "典型流程:1) 先用文件写入工具生成 .html 文件;2) 调本工具渲染;3) 调 viewFileUrl(path) 拿预览 markdown 链接(给用户点击查看);4) 可选 downloadFileUrl(path) 拿下载链接。"
            + "本工具**只保存 PNG 并返回相对路径**,**不**生成可嵌入 markdown —— 把 markdown 语法选择权交回模型,"
            + "**严禁**自己拼 ![alt](url) 因为 /file/view/<id> 返回 HTML viewer 页 不是 image bytes,内嵌会 broken image。"
            + "要预览给用户用 viewFileUrl(path);要 inline 嵌入先调 viewFileUrl 拿到真实 URL 路径再决定 markdown 语法。")
    @Override
    public String renderHtmlFile(
            @ToolParam(description = "HTML 文件路径,相对于用户文件目录(如 prototypes/login.html);必须先用文件写入工具创建") String htmlFilePath,
            @ToolParam(description = "输出图片名(不含扩展名);可空,自动取 HTML 文件名", required = false) String imageName,
            @ToolParam(description = "设备预设:desktop(1440x900,默认)/ tablet(768x1024)/ mobile(390x844)", required = false) String device,
            @ToolParam(description = "true=截整页(默认);false=只截当前视口", required = false) Boolean fullPage,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "[渲染失败] 缺少用户会话上下文";
        if (htmlFilePath == null || htmlFilePath.isBlank()) return "[渲染失败] htmlFilePath 不能为空";

        Path baseDir = cn.wubo.loom.file.core.LoomPaths.userFileDir(usersBasePath, username);
        Path htmlPath;
        try {
            String normalized = htmlFilePath.replace('\\', java.io.File.separatorChar);
            htmlPath = baseDir.resolve(normalized).toAbsolutePath().normalize();
            cn.wubo.loom.file.core.PathSecurityUtils.assertInsideBaseDir(htmlPath, baseDir, true);
        } catch (SecurityException e) {
            return "[渲染失败] 路径超出用户文件目录: " + e.getMessage();
        } catch (Exception e) {
            return "[渲染失败] 路径解析失败: " + e.getMessage();
        }
        if (!Files.exists(htmlPath) || !Files.isRegularFile(htmlPath)) {
            return "[渲染失败] HTML 文件不存在: " + htmlFilePath;
        }
        String fileName = htmlPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".html") && !fileName.endsWith(".htm")) {
            return "[渲染失败] 不是 .html 文件: " + htmlFilePath;
        }
        String html;
        try {
            long size = Files.size(htmlPath);
            if (size > cfg.getMaxHtmlBytes()) {
                return "[渲染失败] 超过 " + humanSize(cfg.getMaxHtmlBytes())
                        + " 上限(实际 " + size + " 字节),请精简 HTML 后重试";
            }
            html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[渲染失败] HTML 读取失败: " + e.getMessage();
        }

        HtmlRenderEngine.Viewport vp = DEVICES.getOrDefault(
                device == null ? "desktop" : device.trim().toLowerCase(Locale.ROOT),
                DEVICES.get("desktop"));
        boolean full = fullPage == null || fullPage;

        HtmlRenderEngine.RenderResult result;
        try {
            result = engine.render(html, vp, full, cfg.getDeviceScaleFactor());
        } catch (HtmlRenderEngine.RenderUnavailableException e) {
            log.warn("renderHtmlFile Chromium 不可用: {}", e.reason());
            return "[渲染不可用] Chromium 未安装或启动失败(" + e.reason() + ")。" + e.installHint();
        } catch (HtmlRenderEngine.RenderBusyException e) {
            return "[渲染失败] 渲染器忙,请稍后重试";
        } catch (Exception e) {
            log.warn("renderHtmlFile 渲染失败: {}", e.getMessage());
            return "[渲染失败] " + e.getMessage();
        }

        // 存图:{base}/{username}/prototypes/{name}-{ts}.png(D7)
        String baseName = sanitizeImageName(imageName);
        if (baseName == null) {
            String hn = htmlPath.getFileName().toString();
            int dot = hn.lastIndexOf('.');
            baseName = sanitizeImageName(dot > 0 ? hn.substring(0, dot) : hn);
            if (baseName == null) baseName = "render";
        }
        String pngName = baseName + "-" + LocalDateTime.now().format(TS) + ".png";
        Path pngPath;
        try {
            Path protoDir = baseDir.resolve("prototypes");
            Files.createDirectories(protoDir);
            pngPath = protoDir.resolve(pngName);
            Files.write(pngPath, result.png());
        } catch (Exception e) {
            return "[渲染失败] 截图写入失败: " + e.getMessage();
        }

        // 2026-10-01 (Bug #5 重构): 工具只保存并返回文件路径 + 后续工具提示。
        // **不**自己生成 markdown 链接 —— /file/view/<id> 是 HTML viewer 端点不是 image bytes,
        // 直接拼 ![alt](url) 会 broken image;模型应该按需调用 viewFileUrl / downloadFileUrl 拿真实 URL,
        // 由模型根据上下文选择 markdown 语法([预览:](url) 链接 或 ![alt](url) 嵌入)。
        // 这样设计把 URL → markdown 翻译权交给有上下文的模型,避免本工具决定的语法跟 URL 类型不匹配。
        String rel = "prototypes/" + pngName;
        return "PNG 已生成: " + rel + " (" + result.widthPx() + "x" + result.heightPx() + ")\n"
                + "提示:\n"
                + "  - 调 viewFileUrl('" + rel + "') 拿预览 markdown 链接(给用户点击查看 viewer 页)\n"
                + "  - 调 downloadFileUrl('" + rel + "') 拿下载链接(带 Content-Disposition)\n"
                + "  - 想 inline 嵌入:先调 viewFileUrl 拿到预览 URL,判断能否嵌入(取决于 viewer URL 类型),"
                + "不要自己拼 ![alt] 指向 /file/view/<id>(那个 URL 是 HTML viewer 不是 image bytes)\n";
    }

    /** 默认 2MB 配置下渲染 spec §1.2 字面文本 "超过 2MB 上限";非整 MB 配置回退字节数。 */
    private static String humanSize(long bytes) {
        if (bytes % (1024 * 1024) == 0) return (bytes / (1024 * 1024)) + "MB";
        return bytes + " 字节";
    }

    /**
     * 输出文件名消毒:路径分隔符/控制字符/常见非法字符 → '-',压缩连续 '-',截 80 字符。
     * 消毒后为空(全非法)→ null(调用方走 HTML 文件名兜底)。
     * 防 imageName 携带 ../ 或分隔符把 PNG 写到 prototypes/ 之外(输出侧沙箱,D7/§4.2)。
     */
    static String sanitizeImageName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[\\\\/:*?\"<>|\\s\\p{Cntrl}]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return null;
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private String tryGetUsername(ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
        Object u = ctx == null ? null : ctx.get("username");
        if (u == null || u.toString().isBlank()) {
            return null;
        }
        return u.toString();
    }
}
