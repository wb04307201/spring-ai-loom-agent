# HTML 渲染截图工具(IHtmlRenderTool)— 设计文档

> **日期**:2026-09-09
> **触发**:用户目标 —— LLM 编写需求文档时自动生成界面原型图(LLM 写单页 HTML → 无头 Chromium 渲染 → 截图 PNG);同样适用于"数据分析单页 → 渲染截图"。目标服务器为 **Linux 裸机(jar 启动,无 Docker)**。
> **状态**:设计已批准(用户 2026-09-09 分节确认),待写实现计划
> **前置资产**:`C:\developer\IdeaProjects\sql-forge\sql-forge-mcp` 的 `PlaywrightRenderer`(懒启动单例 BrowserHolder + LAUNCH_TIMEOUT FutureTask + @PreDestroy + 降级 PreviewResult/installHint)—— 已在生产验证,移植其浏览器生命周期骨架。

---

## 0. 决策记录

| # | 决策 | 备选 | 理由 |
|---|---|---|---|
| D1 | 入参**仅文件路径**(htmlFilePath),HTML 由 LLM 先用既有 IFileTool.writeFile 落盘 | inline HTML 字符串 / 双入参 | 用户拍板。实证:第四轮 Q5 目击 qwen 把长参数(optionsJson)生成到一半截断 —— 几十 KB 完整 HTML 页塞 tool-call 参数截断风险高;路径入参永远小;HTML 原件天然留存可 live 预览(viewFileUrl) |
| D2 | 命名 `IHtmlRenderTool`,group=`render`,方法 `renderHtmlFile` | IPrototypeTool("prototype") / IScreenshotTool | 用户拍板。通用诚实:原型图/分析单页/任何 HTML→图都名正言顺;"screenshot"易被 LLM 误解为任意 URL 截图(本工具不访问 URL) |
| D3 | **RBAC 工具**(defaultGranted=false → `tool_render` 走 role_tool 表);工具组 10→11 | universal(defaultGranted=true) | 用户拍板 RBAC。无头浏览器吃资源(~150-300MB RAM/实例),对齐 compile/git/maven 的"重副作用工具显式授权"分类;@ToolGroup javadoc 明文:任意可执行副作用的工具必须 defaultGranted=false |
| D4 | bean 门控 = **@ConditionalOnClass(com.microsoft.playwright.Playwright) + @ConditionalOnMissingBean**,无 yml enabled 开关 | @ConditionalOnProperty(render.enabled) | M3 起项目废弃 yml enabled 门控(实证:LoomAgentConfiguration 全文件仅剩 chat.ui.init 与 mcp stdio 两个 ConditionalOnProperty;GitProperty.enabled 是文档化石)。playwright 依赖 optional=true(镜像 maven-invoker 先例),消费者不引入则 bean 不创建 |
| D5 | Chromium 获取 = **部署期 provision 为主路径**(provision-chromium.sh:apt 系统 so 库 + fonts-noto-cjk + Playwright CLI install chromium),运行时**零下载**;三级探测:配置 chromium-path → Playwright 默认缓存(~/.cache/ms-playwright)→ setChannel("chromium") 系统包 | 运行时自动下载 / 内置进 jar | 用户拍板裸机 jar。运行时下载需出网+不装系统库(缺 libnss3 等直接 crash);内置 150-300MB 平台二进制进 jar 违打包卫生。dev 机(Windows)自然走 Playwright 默认下载,零配置 |
| D6 | 渲染时**屏蔽全部网络**(context.route abort + CSP 注入) | 允许 CDN(sql-forge amis 模式) | 自包含 HTML 不需要外部资源;LLM 生成 HTML 里的 `<script>fetch(内网)</script>` = SSRF 面,route abort 一举消除;渲染确定性与速度都更好 |
| D7 | 输出 PNG 存 `{fileBasePath}/{username}/prototypes/{name}-{timestamp}.png`,走既有 `IFile.getByExactPath`/temp 桥接出 fileId → 返回 `{baseUrl}/file/view/{fileId}` 预览链接 + markdown 内嵌片段 | 新表/新端点存图 | 完全复用 viewFileUrl 既有机制(零新基建);PNG 进用户文件目录 → 文件管理 UI 天然可见 |
| D8 | 降级契约:Chromium 不可用/渲染失败 → **返回文本**(含 provision 指引),绝不抛异常 | 抛 LoomAgentRuntimeException | LastChunkMessageChatMemoryAdvisor 只在 ON_COMPLETE 落库(askUser 同款铁律);工具抛异常 = 整轮对话记忆丢失 |
| D9 | 并发 = 单例浏览器 + **Semaphore(1)** 串行渲染;超时 timeout-seconds(默认 30s)硬限 | 浏览器池/每请求新实例 | 内存保护(裸机服务器);原型渲染是低频操作,串行足够;超时后 page 关闭释放 |

---

## 1. 工具面(LLM 可见)

### 1.1 接口(lib 模块 `cn.wubo.spring.ai.loom.agent.tool.render`)

```java
@ToolGroup(value = "render", description = "renderHtmlFile — 本地 HTML 文件渲染成 PNG 截图(界面原型图 / 数据分析单页)")
public interface IHtmlRenderTool extends IEmbedTool {

    String renderHtmlFile(String htmlFilePath, String imageName, String device,
                          Boolean fullPage, ToolContext toolContext);
}
```

### 1.2 实现 DefaultHtmlRenderTool(@Tool/@ToolParam 在**实现类方法**上 —— askUser 复验教训:接口注解不被反射继承)

```java
@Tool(description = "把一个本地单页 HTML 文件用无头 Chromium 渲染成 PNG 截图,返回图片预览链接(markdown 可直接内嵌)。"
    + "适用:界面原型图、数据分析单页、报告可视化。HTML 必须自包含(内联 CSS/JS)——渲染时禁止一切外部网络请求。"
    + "典型流程:先用文件写入工具生成 .html 文件,再调用本工具渲染,把返回的 markdown 图片片段嵌入需求文档。")
public String renderHtmlFile(
    @ToolParam(description = "HTML 文件路径,相对于用户文件目录(如 prototypes/login.html);必须先用文件写入工具创建") String htmlFilePath,
    @ToolParam(description = "输出图片名(不含扩展名);可空,自动取 HTML 文件名", required = false) String imageName,
    @ToolParam(description = "设备预设:desktop(1440x900,默认)/ tablet(768x1024)/ mobile(390x844)", required = false) String device,
    @ToolParam(description = "true=截整页(默认);false=只截当前视口", required = false) Boolean fullPage,
    ToolContext toolContext)
```

- 参数校验顺序(镜像 DefaultAskUserTool):username 上下文 → htmlFilePath 非空 → 沙箱解析+存在性+扩展名(.html/.htm)→ 读文件(大小上限 2MB,超限拒绝文本)→ device 白名单(desktop/tablet/mobile,非法值 fallback desktop)→ 渲染 → 存图 → 桥接 fileId
- **返回契约**(成功):
  ```
  渲染成功: prototypes/login-20260909153012.png (2880x4680)
  预览链接:{baseUrl}/file/view/{fileId}
  markdown格式:![login](预览链接)
  ```
- **返回契约**(失败,全部文本,D8):
  - 缺上下文:`[渲染失败] 缺少用户会话上下文`
  - 文件问题:`[渲染失败] HTML 文件不存在: xxx` / `不是 .html 文件` / `超过 2MB 上限`
  - Chromium 不可用:`[渲染不可用] Chromium 未安装或启动失败({reason})。Linux 裸机部署请执行 provision-chromium.sh(见 docs/);Windows/macOS 开发机首次使用需联网由 Playwright 自动下载。`
  - 渲染超时/异常:`[渲染失败] {message}`

### 1.3 RBAC 接线(零代码,机制自动生效)

- `@ToolGroup(value="render")` defaultGranted 缺省 false → CapabilityService 自动纳入 `tool_render` group → admin 授权页"可选本地工具"自动出现(第四轮 Q6 已验证该面板是动态拉取)→ role_tool 授权后 `DefaultChat` 的 visibleToolGroups 过滤自动放行
- **概览图**:工具组 10→11 → `generate.py` EN_LAYOUT/ZH_LAYOUT 胶囊 10→11 TOOLS、卡片区 +1 张(Html render/HTML渲染);PNG 重生成继续 deferred(待 DASHSCOPE_API_KEY,与前两轮一致)

---

## 2. 渲染引擎(HtmlRenderEngine,lib 模块同包)

移植 sql-forge `PlaywrightRenderer` 骨架,差异点:

| 维度 | sql-forge(源) | 本工具(移植后) |
|---|---|---|
| 输入 | amis JSON schema → buildHtml 拼 CDN HTML | 用户 HTML 文件内容(自包含) |
| 网络 | 拉 jsdelivr CDN | **route("**") abort 全屏蔽 + CSP 注入** |
| 输出 | PreviewResult(成败/错误) | PNG 字节 + 尺寸 |
| 浏览器管理 | BrowserHolder 单例 + FutureTask 超时 + @PreDestroy | **原样移植** |
| 探测 | Playwright 默认 | **三级**:chromium-path 配置 → 默认缓存 → setChannel("chromium") |

```java
public class HtmlRenderEngine {           // @PreDestroy 由 Spring bean 生命周期驱动
    public record RenderResult(byte[] png, int widthPx, int heightPx) {}
    public RenderResult render(String html, Viewport vp, boolean fullPage, int scale);
    public void close();                  // 关 browser + playwright(destroyMethod 目标,移植 BrowserHolder.close)
    // 内部:acquireBrowser() 三级探测 + LAUNCH_TIMEOUT_MS FutureTask(移植);
    // newContext(viewport, deviceScaleFactor) → route abort all → setContent(html,
    //   前置注入 <meta http-equiv="Content-Security-Policy" content="default-src 'none'; ...">)
    // → waitForTimeout(renderWaitMs 默认 1500,自包含页面无 CDN 等待) → screenshot(fullPage)
    // Chromium 不可用时抛 RenderUnavailableException(带 reason + installHint),工具层转文本(D8)
}
```

- **注入 CSP 方式**:读入的 HTML 若已有 `<head>` 则在其后插 meta CSP;无 head 则整页包裹最小骨架。CSP:`default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:; font-src data:` —— inline CSS/JS 允许(自包含必需),一切外部源封死;route abort 是双保险
- **等待策略**:setContent waitUntil=DOMCONTENTLOADED + 固定 renderWaitMs(可配,默认 1500ms;无网络请求可等,固定小等待让 JS 渲染完成)
- **Semaphore(1)**:render() 入口 acquire(timeout-seconds),超时返回 `[渲染失败] 渲染器忙,请稍后重试`(D9)

---

## 3. 配置 + 裸机 provision

### 3.1 LoomAgentProperties 新增

```java
private RenderProperty render = new RenderProperty();

@Data
public static class RenderProperty {
    /** 可选:显式 Chromium 二进制路径(系统包 /usr/bin/chromium-browser 等);空=Playwright 默认探测 */
    private String chromiumPath;
    /** 截图清晰度倍数(2=Retina) */
    private int deviceScaleFactor = 2;
    /** 单次渲染超时秒(含排队) */
    private int timeoutSeconds = 30;
    /** setContent 后固定等待毫秒(JS 渲染完成) */
    private int renderWaitMs = 1500;
    /** 是否屏蔽全部外部网络(安全默认 true,强烈不建议关) */
    private boolean networkBlocked = true;
    /** HTML 文件大小上限字节(默认 2MB) */
    private long maxHtmlBytes = 2 * 1024 * 1024;
}
```

- **loomAgentProperties 手动拷贝块必须加 `target.setRender(source.getRender())`** —— askuser 的 2bd0b5d 前车之鉴(yml/cmdline 配置被静默丢弃);`LoomAgentPropertiesBindingTest` 加 render 用例(chromium-path/timeout-seconds env 覆盖 → 断言生效 + 默认值断言)
- 依赖:lib pom `com.microsoft.playwright:playwright` **1.50.0**(sql-forge 父 pom `playwright.version` 同版,生产已验证)**optional=true**(镜像 maven-invoker 先例);test 模块 pom 显式引入(IT 要真跑)

### 3.2 bean 注册(autoconfigure ToolConfiguration)

```java
@ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
@ConditionalOnMissingBean(HtmlRenderEngine.class)
@Bean(destroyMethod = "close")
public HtmlRenderEngine htmlRenderEngine(LoomAgentProperties properties) { ... }

@ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
@ConditionalOnMissingBean(IHtmlRenderTool.class)
@Bean
public IHtmlRenderTool defaultHtmlRenderTool(HtmlRenderEngine engine, IFile file, LoomAgentProperties properties) { ... }
```

(destroyMethod 显式声明而非依赖 @PreDestroy —— engine 是普通类不是 @Component)

### 3.3 provision-chromium.sh(随库发布 `docs/provision-chromium.sh`)

```bash
#!/bin/bash
# 一次性在 Linux 裸机(Debian/Ubuntu)上 provision Chromium 运行环境。需要 root。
# RHEL/CentOS 用户:把 apt-get 换成 yum/dnf 对应包(见注释),Chromium 装系统包后
# 配置 spring.ai.loom.agent.render.chromium-path=/usr/bin/chromium-browser
set -e
JAR_PATH="${1:?用法: provision-chromium.sh /path/to/app.jar}"

# 1. 系统 so 库 + CJK 字体(缺字体 = 中文截图全豆腐块 □□□)
apt-get update && apt-get install -y \
  fonts-noto-cjk fonts-noto-color-emoji \
  libnss3 libgbm1 libxkbcommon0 libasound2 libatk1.0-0 libatk-bridge2.0-0 \
  libcups2 libdrm2 libxcomposite1 libxdamage1 libxrandr2 libpango-1.0-0 \
  libxfixes3 libxext6 libx11-6 libxcb1 libatspi2.0-0

# 2. Playwright 管理的 Chromium(下载到 ~/.cache/ms-playwright/,版本与 jar 内 playwright 依赖锁定一致)
java -cp "$JAR_PATH" com.microsoft.playwright.CLI install chromium

echo "✅ provision 完成。以非 root 用户启动服务即可(root 跑 Chromium 需 --no-sandbox,不推荐)。"
```

- README / README.zh-CN / CLAUDE.md / docs(API+TOOLS)记录裸机部署三步:① 跑 provision 脚本 ② jar 启动 ③ admin 给角色授权 tool_render
- 已知裸机坑写入 javadoc+文档:root/sandbox、/tmp 空间、内存、缺字体豆腐块

---

## 4. 安全(spec 级硬约束)

1. **SSRF/外联**:route("**") abort + CSP default-src 'none'(D6 双保险);networkBlocked=false 时仅去掉 route abort,CSP 仍注入(配置项是逃生门不是默认)
2. **路径沙箱**:htmlFilePath 经 `fileBasePath/{username}/` 解析(移植 DefaultFileTool.resolvePathForRead 语义:normalize + startsWith 校验,目录穿越 `../` 拒绝);输出只写 `prototypes/` 子目录
3. **任意 JS 执行**:接受(工具本质就是渲染 LLM 写的页面);边界 = 无网络 + 无 file:// 导航(setContent 注入,不 navigate)+ 浏览器进程本身沙箱 + 非 root 运行建议
4. **资源**:2MB HTML 上限;30s 超时;Semaphore(1);单例浏览器 @PreDestroy/destroyMethod 释放
5. **XSS 出图**:PNG 是像素,无 XSS 面;返回文本里的文件名/链接经既有 fileId 桥接(非用户输入拼接)

---

## 5. 测试策略

| 层 | 内容 |
|---|---|
| 单元(lib) | 路径沙箱(穿越拒绝/扩展名/大小上限)、device 白名单 fallback、返回文本契约(各失败分支)、CSP 注入函数(有 head/无 head 两形态) |
| 契约(test 模块) | @ToolGroup(value="render") 存在且 defaultGranted=false(反射,镜像既有 RBAC 工具契约测试);实现方法 @Tool/@ToolParam 齐全 |
| binding | LoomAgentPropertiesBindingTest 加 render 用例(env 覆盖 chromium-path/timeout-seconds → 生效;默认值 30/2/1500/true) |
| autoconfig 切片 | LoomAgentToolAutoConfigTest 补 render bean 存在性(playwright 在 test classpath) |
| IT(真 Chromium) | HtmlRenderEngineIT:渲染含中文+inline CSS 的最小 HTML → PNG 非空+尺寸=device×scale;网络屏蔽断言(HTML 里 img src=外网 → 请求被 abort);**Linux 无浏览器环境条件跳过**(assumeTrue engine 可用,镜像 MavenTool IT 的 3-skip 先例);dev Windows 机真跑 |
| Chrome E2E | 真实 LLM:"写一个登录页原型 HTML 并渲染截图" → writeFile → renderHtmlFile → markdown 图片出现在回复 → 点链接可见 PNG(中文无豆腐块) |
| provision 脚本 | shellcheck(若环境有)+ 人工审查;不在 CI 跑(需 root) |

回归门基线(当前):lib 185/0 · test 412/0 · IT 128/0/3skip → 本特性后相应增长;V1.0 无 schema 改动(role_tool 行由 admin UI 动态授权,无需 seed;若用户后续要求 base 角色默认带 tool_render 再议)。

---

## 6. 不在本次范围

- 需求文档载体/模板(markdown 内嵌图片即可,用户明确"先不考虑")
- PDF 导出、多页截图、HTML→HTML 美化
- 浏览器池/并行渲染(Semaphore(1) 够原型场景)
- Windows 服务器 provision(目标明确 Linux 裸机;dev Windows 走 Playwright 自动下载)
- 概览图 PNG 重生成(继续 deferred,布局脚本本次同步改)
