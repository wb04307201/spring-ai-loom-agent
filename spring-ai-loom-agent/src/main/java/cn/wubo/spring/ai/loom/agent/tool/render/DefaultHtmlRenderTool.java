package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.common.FileIdBridge;
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
 * <b>D8 铁律:所有失败分支返回文本,绝不抛异常</b> —— LastChunkMessageChatMemoryAdvisor
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
    private final FileIdBridge fileIdBridge;
    private final String fileBasePath;
    private final LoomAgentProperties.RenderProperty cfg;

    public DefaultHtmlRenderTool(HtmlRenderEngine engine, IFile file, String fileBasePath,
                                 LoomAgentProperties.RenderProperty cfg) {
        this.engine = engine;
        this.fileIdBridge = new FileIdBridge(file);
        this.fileBasePath = fileBasePath;
        this.cfg = cfg;
    }

    @Tool(description = "把一个本地单页 HTML 文件用无头 Chromium 渲染成 PNG 截图,返回图片预览链接(markdown 可直接内嵌)。"
            + "适用:界面原型图、数据分析单页、报告可视化。HTML 必须自包含(内联 CSS/JS)——渲染时禁止一切外部网络请求。"
            + "典型流程:先用文件写入工具生成 .html 文件,再调用本工具渲染,把返回的 markdown 图片片段嵌入需求文档。")
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

        Path baseDir = Paths.get(fileBasePath, username);
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

        String fileId = fileIdBridge.getOrCreateFileId(pngPath, username);
        if (fileId == null) {
            return "[渲染失败] 截图文件注册失败,无法生成预览链接";
        }
        String baseUrl = (String) toolContext.getContext().get("baseUrl");
        String url = (baseUrl == null || baseUrl.isBlank())
                ? "/file/view/" + fileId
                : baseUrl + "/file/view/" + fileId;
        String rel = "prototypes/" + pngName;
        return "渲染成功: " + rel + " (" + result.widthPx() + "x" + result.heightPx() + ")\n"
                + "预览链接:" + url + "\n"
                + "markdown格式:![" + baseName + "](" + url + ")\n";
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
