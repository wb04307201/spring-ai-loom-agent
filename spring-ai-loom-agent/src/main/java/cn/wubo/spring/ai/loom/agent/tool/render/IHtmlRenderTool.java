package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;

/**
 * HTML 渲染截图工具(spec 2026-09-09-html-render-tool-design)。
 * <p>
 * 把用户文件目录里的自包含单页 HTML 用无头 Chromium 渲染成 PNG(界面原型图 / 数据分析单页),
 * 存到 {@code {fileBasePath}/{username}/prototypes/},经 IFile usage='temp' 桥接出 fileId,
 * 返回预览链接 + markdown 内嵌片段。
 * <p>
 * <b>RBAC 工具</b>(D3):defaultGranted 缺省 false → capability id {@code tool_render},
 * 走 role_tool 表显式授权 —— 无头浏览器吃资源(~150-300MB RAM/实例),对齐
 * compile/git/maven 的"重副作用工具显式授权"分类。
 * <p>
 * 注意:{@code @Tool}/{@code @ToolParam} 注解在实现类方法上(Spring AI 反射不继承
 * 接口注解),本接口只声明签名。
 */
@ToolGroup(value = "render", description = "renderHtmlFile — 本地 HTML 文件渲染成 PNG 截图(界面原型图 / 数据分析单页)")
public interface IHtmlRenderTool extends IEmbedTool {

    /**
     * 渲染本地 HTML 文件为 PNG 截图。
     *
     * @param htmlFilePath 相对用户文件目录的 HTML 路径(必须已存在,先用 IFileTool.writeFile 落盘)
     * @param imageName    输出图片名(不含扩展名);可空 = 取 HTML 文件名
     * @param device       desktop(1440x900,默认)/ tablet(768x1024)/ mobile(390x844);非法值 fallback desktop
     * @param fullPage     true=截整页(默认);false=只截视口
     * @param toolContext  username / baseUrl 由框架注入
     * @return 成功:三行文本(渲染成功/预览链接/markdown格式);失败:[渲染失败]/[渲染不可用] 前缀文本,**绝不抛异常**
     */
    String renderHtmlFile(String htmlFilePath, String imageName, String device,
                          Boolean fullPage, ToolContext toolContext);
}
