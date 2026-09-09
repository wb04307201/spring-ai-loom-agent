# HTML 渲染截图工具(IHtmlRenderTool)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 RBAC 本地工具 `renderHtmlFile` —— 用无头 Chromium(Playwright)把用户文件目录里的自包含单页 HTML 渲染成 PNG 截图(界面原型图 / 数据分析单页),返回 fileId 桥接的预览链接 + markdown 内嵌片段。

**Architecture:** 三层。① `HtmlRenderEngine`(lib)移植 sql-forge `PlaywrightRenderer` 的懒启动单例 BrowserHolder + FutureTask 启动超时骨架,增加三级 Chromium 探测、route abort + CSP 双保险网络屏蔽、Semaphore(1) 串行;② `DefaultHtmlRenderTool`(lib)负责参数校验、路径沙箱、存图、IFile temp 桥接,所有失败分支返回文本绝不抛异常;③ autoconfigure `ToolConfiguration` 用 `@ConditionalOnClass(playwright) + @ConditionalOnMissingBean` 注册两个 bean(engine 带 `destroyMethod="close"`)。playwright 1.50.0 在 lib pom 为 optional,test 模块显式引入。

**Tech Stack:** Spring Boot 3.5.16 / Spring AI 1.1.8 / JDK 17 / com.microsoft.playwright:playwright:1.50.0(optional) / JUnit5 + AssertJ + Mockito / Maven 多模块

**Spec:** `docs/superpowers/specs/2026-09-09-html-render-tool-design.md`(D1-D9 决策是权威;本计划与 spec 冲突时以 spec 为准,并把冲突上报给控制器裁决)

## Global Constraints

- **playwright 版本锁定 `1.50.0`**(sql-forge 父 pom `playwright.version` 同版,生产已验证)。父 pom `dependencyManagement` 声明版本;lib 模块依赖带 `<optional>true</optional>`;test 模块显式引入(不写 version)
- **无 yml `enabled` 开关**(M3 起废弃):bean 门控只用 `@ConditionalOnClass(name = "com.microsoft.playwright.Playwright")` + `@ConditionalOnMissingBean`(镜像 `IMavenTool`/maven-invoker 先例,见 `LoomAgentConfiguration.java:851-856`)
- **`@Tool` / `@ToolParam` 必须标注在实现类方法上**,不标在接口方法上(Spring AI 反射不继承接口注解 —— askUser 复验教训)。接口只声明签名 + `@ToolGroup`
- **工具绝不抛异常**:每个失败分支返回 `[渲染失败] ...` 或 `[渲染不可用] ...` 文本。理由:`LastChunkMessageChatMemoryAdvisor` 只在 `ON_COMPLETE` 落库,工具抛异常 = 整轮对话记忆丢失
- **RBAC**:`@ToolGroup(value = "render", description = "...")` 不写 `defaultGranted`(缺省 false)→ capability id = `tool_render`,走 `role_tool` 表授权。**不改 V1.0 schema、不加任何 seed 行**(admin UI 动态拉取可选工具列表,第四轮 Q6 已验证)
- **`loomAgentProperties` 手动拷贝块必须加 `properties.setRender(bound.getRender())`**(`LoomAgentConfiguration.java:440-465`)。2bd0b5d 前车之鉴:漏拷贝 → yml/cmdline 配置被静默丢弃,且默认值看起来"正常"很难发现
- **网络屏蔽双保险**:`context.route("**", route -> route.abort())`(仅 `networkBlocked=true` 时)+ CSP meta 注入(**任何情况下都注入**,`networkBlocked=false` 只是逃生门)
- **路径沙箱**:`htmlFilePath` 相对 `{fileBasePath}/{username}/` 解析,用 `cn.wubo.loom.file.core.PathSecurityUtils.assertInsideBaseDir(resolved, baseDir, true)` 校验(与 `DefaultFileTool.resolvePathForRead` 同语义);输出只写 `prototypes/` 子目录
- **命令统一在仓库根目录执行,单模块测试一律带 `-am`**:`mvn -q -pl <module> -am -Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`。**不带 `-am` 会用本地仓库里的陈旧 SNAPSHOT**(本特性改了父 pom + lib + autoconfigure,不重建上游 = 测的是旧代码,RED/GREEN 全是假信号)。全量:`mvn -q clean install -Dgpg.skip=true`。Windows Git Bash 下杀进程用 `taskkill //PID <pid> //F`
- **回归门基线**(当前):lib 185/0 · test 模块 412/0 · IT gate 128/0/3skip。每个 Task 结束不得让基线变红;全部完成后 IT gate 允许新增 skip(Chromium 不可用时)

## File Structure

| 文件 | 动作 | 责任 |
|---|---|---|
| `pom.xml`(父) | Modify | `<playwright.version>1.50.0</playwright.version>` + dependencyManagement 条目 |
| `spring-ai-loom-agent/pom.xml` | Modify | playwright 依赖(optional=true) |
| `spring-ai-loom-agent-test/pom.xml` | Modify | playwright 依赖(显式,IT 真跑) |
| `.../agent/model/LoomAgentProperties.java` | Modify | 新增 `render` 字段 + `RenderProperty` 静态内部类 |
| `.../agent/tool/render/HtmlRenderEngine.java` | Create | 浏览器生命周期 + 三级探测 + 网络屏蔽 + CSP 注入 + Semaphore + PNG 尺寸解析 |
| `.../agent/tool/render/IHtmlRenderTool.java` | Create | 工具接口 + `@ToolGroup("render")` |
| `.../agent/tool/render/DefaultHtmlRenderTool.java` | Create | 校验 / 沙箱 / 渲染 / 存图 / fileId 桥接 / 文本契约 |
| `.../agent/tool/common/FileIdBridge.java` | Create | 从 `DefaultFileTool` 抽出的 `getOrCreateFileId` 共享逻辑(DRY) |
| `.../agent/tool/file/DefaultFileTool.java` | Modify | 私有 `getOrCreateFileId` 改为委托 `FileIdBridge` |
| `.../agent/LoomAgentConfiguration.java`(autoconfigure) | Modify | `setRender` 拷贝 + ToolConfiguration 两个 bean |
| `docs/provision-chromium.sh` | Create | Linux 裸机一次性 provision 脚本 |
| `README.md` / `README.zh-CN.md` / `docs/TOOLS.md` / `docs/TOOLS.zh-CN.md` / `CLAUDE.md` | Modify | 工具表 +1 行、新章节、render 配置项、裸机三步部署 |
| `.claude/skills/project-overview-image/scripts/generate.py` | Modify | EN/ZH 布局 10→11 TOOLS(+1 卡片 `Html render` / `HTML渲染`) |
| 测试(见各 Task) | Create/Modify | 单元 / 契约 / binding / autoconfig 切片 / IT |

**执行顺序**:Task 1(配置)→ Task 2(**pom 依赖** + 引擎)→ Task 3(FileIdBridge 抽取 + 工具实现)→ Task 4(bean 注册 + 契约/切片测试)→ Task 5(真 Chromium IT)→ Task 6(provision 脚本 + 文档 + 概览图布局)。三处 pom 改动全部落在 Task 2 Step 1(引擎代码 import playwright,依赖必须先在 classpath 才能编译;test 模块显式引入同批做掉,Task 5 的 IT 才有 playwright)。

---

### Task 1: RenderProperty 配置 + binding 回归

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`(在 `askuser` 字段后加 `render` 字段;在 `AskUserProperty` 类后加 `RenderProperty` 类)
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java:462`(`properties.setAskuser(...)` 后加一行)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/LoomAgentPropertiesBindingTest.java`(追加 2 个用例)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentPropertiesDefaultsTest.java`(追加 1 个用例)

**Interfaces:**
- Consumes: 无(纯配置)
- Produces:
  - `LoomAgentProperties#getRender()` → `LoomAgentProperties.RenderProperty`
  - `RenderProperty` getter/setter(Lombok `@Data`):`String getChromiumPath()`(默认 `null`)、`int getDeviceScaleFactor()`(默认 `2`)、`int getTimeoutSeconds()`(默认 `30`)、`int getRenderWaitMs()`(默认 `1500`)、`boolean isNetworkBlocked()`(默认 `true`)、`long getMaxHtmlBytes()`(默认 `2 * 1024 * 1024`)
  - yml 前缀:`spring.ai.loom.agent.render.*`(kebab-case:`chromium-path` / `device-scale-factor` / `timeout-seconds` / `render-wait-ms` / `network-blocked` / `max-html-bytes`)

- [ ] **Step 1: 写失败测试(binding)**

在 `LoomAgentPropertiesBindingTest` 类体末尾(`askuserTimeoutSecondsDefaultsTo300WhenUnset` 之后、类的最后一个 `}` 之前)追加:

```java
    @Test
    void renderChromiumPathAndTimeoutFromEnvironmentAreBound() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.loom.agent.render.chromium-path", "/usr/bin/chromium-browser");
        env.setProperty("spring.ai.loom.agent.render.timeout-seconds", "77");
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getRender().getChromiumPath()).isEqualTo("/usr/bin/chromium-browser");
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(77);
    }

    @Test
    void renderDefaultsSurviveManualCopy() {
        // 守卫 2bd0b5d 类缺陷:loomAgentProperties 手动拷贝块漏 setRender() 时,
        // bound 实例的 render 会被丢掉,返回默认实例 —— 默认值断言能过但覆盖值会丢,
        // 所以上面那个用例是主守卫,这里锁默认值本身。
        MockEnvironment env = new MockEnvironment();
        LoomAgentProperties props =
                new LoomAgentConfiguration.InfrastructureConfiguration().loomAgentProperties(env);
        assertThat(props.getRender()).isNotNull();
        assertThat(props.getRender().getChromiumPath()).isNull();
        assertThat(props.getRender().getDeviceScaleFactor()).isEqualTo(2);
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getRender().getRenderWaitMs()).isEqualTo(1500);
        assertThat(props.getRender().isNetworkBlocked()).isTrue();
        assertThat(props.getRender().getMaxHtmlBytes()).isEqualTo(2L * 1024 * 1024);
    }
```

在 `LoomAgentPropertiesDefaultsTest` 类体末尾追加:

```java
    @Test
    void renderProperty_defaultsMatchSpec() {
        LoomAgentProperties props = new LoomAgentProperties();
        assertThat(props.getRender().getDeviceScaleFactor()).isEqualTo(2);
        assertThat(props.getRender().getTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getRender().getRenderWaitMs()).isEqualTo(1500);
        assertThat(props.getRender().isNetworkBlocked()).isTrue();
        assertThat(props.getRender().getMaxHtmlBytes()).isEqualTo(2L * 1024 * 1024);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest='LoomAgentPropertiesBindingTest,LoomAgentPropertiesDefaultsTest' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: 编译失败 —— `cannot find symbol: method getRender()`(RenderProperty 尚不存在)

- [ ] **Step 3: 加 RenderProperty**

`LoomAgentProperties.java` 里,在 `private AskUserProperty askuser = new AskUserProperty();`(L96)之后加:

```java
    private RenderProperty render = new RenderProperty();
```

在 `AskUserProperty` 静态内部类(L379-382)之后、类的最后一个 `}` 之前加:

```java
    /**
     * HTML 渲染截图工具配置(IHtmlRenderTool,spec 2026-09-09 D5/D6/D9)。
     * yml 前缀 {@code spring.ai.loom.agent.render.*}。
     * <ul>
     * <li>{@code chromiumPath} — 可选:显式 Chromium 二进制路径(如系统包
     * {@code /usr/bin/chromium-browser});空 = Playwright 默认探测(三级探测的第 2/3 级)</li>
     * <li>{@code deviceScaleFactor} — 截图清晰度倍数(2 = Retina,默认 2)</li>
     * <li>{@code timeoutSeconds} — 单次渲染超时秒(含 Semaphore 排队,默认 30)</li>
     * <li>{@code renderWaitMs} — setContent 后固定等待毫秒,让内联 JS 渲染完成(默认 1500)</li>
     * <li>{@code networkBlocked} — 是否 route abort 屏蔽全部外部网络(默认 true;
     * 关掉只去掉 route abort,CSP 仍然注入 —— 这是逃生门不是常规配置)</li>
     * <li>{@code maxHtmlBytes} — HTML 文件大小上限字节(默认 2MB)</li>
     * </ul>
     * 无 enabled 开关:M3 起工具 bean 总是创建,启停由 role_tool RBAC 表控制;
     * bean 是否创建取决于 classpath 有没有 playwright(optional 依赖)。
     */
    @Data
    public static class RenderProperty {
        private String chromiumPath;
        private int deviceScaleFactor = 2;
        private int timeoutSeconds = 30;
        private int renderWaitMs = 1500;
        private boolean networkBlocked = true;
        private long maxHtmlBytes = 2 * 1024 * 1024;
    }
```

- [ ] **Step 4: 加手动拷贝行**

`LoomAgentConfiguration.java` 的 `loomAgentProperties(Environment)` 方法里,在 `properties.setAskuser(bound.getAskuser());`(L462)之后加:

```java
                properties.setRender(bound.getRender());
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest='LoomAgentPropertiesBindingTest,LoomAgentPropertiesDefaultsTest' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS(5 个 binding/defaults 用例全绿:2 旧 + 3 新)

- [ ] **Step 6: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/LoomAgentPropertiesBindingTest.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentPropertiesDefaultsTest.java
git commit -m "feat: render 配置项(RenderProperty)+ loomAgentProperties 手动拷贝 setRender"
```

---

### Task 2: playwright 依赖 + HtmlRenderEngine(渲染引擎)

**Files:**
- Modify: `pom.xml`(父,`<properties>` L64 后 + `<dependencyManagement>` flex-schedule 条目后)
- Modify: `spring-ai-loom-agent/pom.xml`(maven-invoker 依赖后,L77 之后)
- Modify: `spring-ai-loom-agent-test/pom.xml`(spring-boot-starter-test 依赖后)
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngine.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineUnitTest.java`

**Interfaces:**
- Consumes: `LoomAgentProperties.RenderProperty`(Task 1)
- Produces(Task 3/5 依赖,签名精确):
  - `public class HtmlRenderEngine`,构造器 `HtmlRenderEngine(LoomAgentProperties.RenderProperty cfg)`(**不启动浏览器,懒启动**)
  - `public record HtmlRenderEngine.Viewport(int width, int height)`
  - `public record HtmlRenderEngine.RenderResult(byte[] png, int widthPx, int heightPx)`
  - `public RenderResult render(String html, Viewport vp, boolean fullPage, int scale)` — 抛 `RenderUnavailableException`(Chromium 不可用)/ `RenderBusyException`(排队超时)/ 普通 `RuntimeException`(渲染失败,message 为裸原因,无前缀)
  - `public void close()` — bean `destroyMethod` 目标
  - `public static class RenderUnavailableException extends RuntimeException`,方法 `String reason()` / `String installHint()`
  - `public static class RenderBusyException extends RuntimeException`
  - 包私有静态:`String injectCsp(String html)`、`int[] pngSize(byte[] png)`、`Semaphore renderSemaphore()`(测试缝)

- [ ] **Step 1: 三处 pom 加依赖**

父 `pom.xml` — `<properties>` 里 `<flex-schedule.version>1.2.2</flex-schedule.version>` 之后加:

```xml
        <playwright.version>1.50.0</playwright.version>
```

父 `pom.xml` — `<dependencyManagement>` 里 flex-schedule-spring-boot-starter 条目(`</dependency>`,约 L122)之后加:

```xml
            <dependency>
                <groupId>com.microsoft.playwright</groupId>
                <artifactId>playwright</artifactId>
                <version>${playwright.version}</version>
            </dependency>
```

`spring-ai-loom-agent/pom.xml` — maven-invoker 依赖(约 L74-77)之后加:

```xml
        <!-- HTML 渲染截图工具(IHtmlRenderTool)依赖。optional:消费者不引入 →
             HtmlRenderEngine / IHtmlRenderTool bean 因 @ConditionalOnClass 不创建(镜像 maven-invoker 先例) -->
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <optional>true</optional>
        </dependency>
```

`spring-ai-loom-agent-test/pom.xml` — spring-boot-starter-test 依赖之后加:

```xml
        <!-- lib 的 playwright 是 optional(不传递);IT(HtmlRenderEngineIT)要真跑 Chromium,显式引入 -->
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析**

Run: `mvn -q -pl spring-ai-loom-agent -am compile -Dgpg.skip=true`
Expected: BUILD SUCCESS(playwright 1.50.0 能从仓库拉取;此步失败 = 版本号写错或仓库不可达,先解决再继续)

- [ ] **Step 3: 写失败单元测试**

Create `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineUnitTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HtmlRenderEngine 纯函数 + 并发闸门的单元测试(不需要真浏览器)。
 * 真 Chromium 渲染在 HtmlRenderEngineIT(Task 5)。
 */
class HtmlRenderEngineUnitTest {

    @Test
    @DisplayName("injectCsp:有 <head> 时 meta 紧随其后插入,原内容保留")
    void injectCspAfterExistingHead() {
        String out = HtmlRenderEngine.injectCsp("<html><head><title>t</title></head><body>b</body></html>");
        assertThat(out).contains("<head><meta http-equiv=\"Content-Security-Policy\"");
        assertThat(out).contains("default-src 'none'");
        assertThat(out).contains("<title>t</title>").contains("<body>b</body>");
    }

    @Test
    @DisplayName("injectCsp:<head 带属性> 支持;<header> 不误匹配")
    void injectCspHeadWithAttributesAndNoHeaderFalsePositive() {
        String withAttrs = HtmlRenderEngine.injectCsp("<html><head lang=\"zh\"><title>t</title></head></html>");
        assertThat(withAttrs).contains("<head lang=\"zh\"><meta http-equiv=\"Content-Security-Policy\"");

        String headerOnly = HtmlRenderEngine.injectCsp("<div><header>h</header><p>x</p></div>");
        // 没有真 <head> → 走最小骨架包裹分支,<header> 后不被插 meta
        assertThat(headerOnly).startsWith("<!doctype html>");
        assertThat(headerOnly).doesNotContain("<header><meta");
        assertThat(headerOnly).contains("<header>h</header>");
    }

    @Test
    @DisplayName("injectCsp:无 head 时包裹最小骨架")
    void injectCspWrapsWhenNoHead() {
        String out = HtmlRenderEngine.injectCsp("<p>hello</p>");
        assertThat(out).startsWith("<!doctype html><html><head>");
        assertThat(out).contains("default-src 'none'").contains("<p>hello</p>");
    }

    @Test
    @DisplayName("pngSize:从 IHDR(offset 16/20,big-endian)解析宽高;坏输入 {0,0}")
    void pngSizeReadsIhdr() {
        byte[] fake = new byte[32];
        fake[18] = 0x05; fake[19] = (byte) 0xA0;  // 0x05A0 = 1440
        fake[22] = 0x03; fake[23] = (byte) 0x84;  // 0x0384 = 900
        assertThat(HtmlRenderEngine.pngSize(fake)).containsExactly(1440, 900);
        assertThat(HtmlRenderEngine.pngSize(new byte[10])).containsExactly(0, 0);
        assertThat(HtmlRenderEngine.pngSize(null)).containsExactly(0, 0);
    }

    @Test
    @DisplayName("busy 路径:Semaphore 许可被占,timeoutSeconds 后抛 RenderBusyException(D9)")
    void busyWhenSemaphoreOccupied() throws Exception {
        LoomAgentProperties.RenderProperty cfg = new LoomAgentProperties.RenderProperty();
        cfg.setTimeoutSeconds(1);
        HtmlRenderEngine engine = new HtmlRenderEngine(cfg);
        engine.renderSemaphore().acquire();  // 模拟另一渲染进行中
        try {
            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    engine.render("<p>x</p>", new HtmlRenderEngine.Viewport(100, 100), false, 1);
                } catch (Throwable ex) {
                    err.set(ex);
                }
            });
            t.start();
            t.join(10_000);
            assertThat(err.get()).isInstanceOf(HtmlRenderEngine.RenderBusyException.class);
        } finally {
            engine.renderSemaphore().release();
        }
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q -pl spring-ai-loom-agent -am -Dtest=HtmlRenderEngineUnitTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: 编译失败 —— `package cn.wubo.spring.ai.loom.agent.tool.render does not exist`

- [ ] **Step 5: 实现 HtmlRenderEngine**

Create `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngine.java`(完整文件):

```java
package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无头 Chromium HTML→PNG 渲染引擎(spec 2026-09-09-html-render-tool-design D5/D6/D9)。
 * <p>
 * 移植 sql-forge {@code PlaywrightRenderer} 的浏览器生命周期骨架:懒启动单例 BrowserHolder +
 * FutureTask 启动超时 + {@link #close()} 释放(bean destroyMethod 目标)。差异点:
 * <ul>
 *   <li>三级 Chromium 探测(spec D5):chromium-path 配置 → Playwright 默认缓存 → setChannel("chromium") 系统包</li>
 *   <li>网络屏蔽双保险(spec D6):route("**") abort(仅 networkBlocked=true)+ CSP meta 注入(任何情况都注入)</li>
 *   <li>Semaphore(1) 串行渲染(spec D9),排队最多 timeoutSeconds,超时抛 {@link RenderBusyException}</li>
 *   <li>输出 PNG 字节 + IHDR 实际尺寸(不再是 amis PreviewResult)</li>
 * </ul>
 * <p>
 * <b>裸机 Linux 已知坑</b>(详见 docs/provision-chromium.sh):缺系统 so 库(libnss3 等)launch 直接
 * crash;缺 CJK 字体截图中文全豆腐块(□□□);root 跑 Chromium 需要 --no-sandbox —— 建议非 root
 * 运行服务,本引擎不自动加该参数(安全默认)。
 */
public class HtmlRenderEngine {

    private static final Logger log = LoggerFactory.getLogger(HtmlRenderEngine.class);

    /**
     * Chromium 启动超时(毫秒)。sql-forge 原值 10s;这里放宽到 60s:dev 机首次
     * Playwright.create() 可能触发浏览器在线下载(数十秒),10s 会把下载拦腰截断。
     * Linux provisioned 机器 launch < 3s,60s 只影响"真坏了"的场景。
     */
    public static final int LAUNCH_TIMEOUT_MS = 60_000;

    /** CSP:允许 inline CSS/JS(自包含页面必需),封死一切外部源(spec D6/§4.1)。route abort 是双保险。 */
    static final String CSP =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:; font-src data:";

    /** 匹配 <head> 开标签(可带属性),排除 <header> 等误匹配。 */
    private static final Pattern HEAD_OPEN = Pattern.compile("<head(?=[\\s>])", Pattern.CASE_INSENSITIVE);

    /** 设备视口预设(spec §1.2 device 白名单)。 */
    public record Viewport(int width, int height) {}

    /** 渲染产物:PNG 字节 + 图片实际像素尺寸(IHDR)。 */
    public record RenderResult(byte[] png, int widthPx, int heightPx) {}

    /** Chromium 不可用(三级探测全失败 / 启动超时)—— 工具层转 [渲染不可用] 文本(D8)。 */
    public static class RenderUnavailableException extends RuntimeException {
        private final String reason;
        private final String installHint;

        public RenderUnavailableException(String reason, String installHint) {
            super(reason);
            this.reason = reason;
            this.installHint = installHint;
        }

        public String reason() { return reason; }
        public String installHint() { return installHint; }
    }

    /** Semaphore 排队超时(D9)—— 工具层转 "[渲染失败] 渲染器忙,请稍后重试"。 */
    public static class RenderBusyException extends RuntimeException {
        public RenderBusyException(String message) { super(message); }
    }

    /** Chromium 不可用时的安装指引(工具层拼进 [渲染不可用] 文本)。 */
    public static final String INSTALL_HINT =
            "Linux 裸机部署请执行 provision-chromium.sh(见 docs/);Windows/macOS 开发机首次使用需联网由 Playwright 自动下载。";

    private final LoomAgentProperties.RenderProperty cfg;
    private final Semaphore semaphore = new Semaphore(1);
    private volatile BrowserHolder browserHolder;

    public HtmlRenderEngine(LoomAgentProperties.RenderProperty cfg) {
        this.cfg = cfg;
    }

    /** 测试缝:busy 路径单测需要预占许可(package-private,生产代码勿用)。 */
    Semaphore renderSemaphore() {
        return semaphore;
    }

    /**
     * 渲染自包含 HTML → PNG。串行(Semaphore(1)),排队 + 启动 + 渲染全程受配置约束。
     *
     * @param html     HTML 内容(调用方已做大小上限校验)
     * @param vp       视口(CSS 像素)
     * @param fullPage true=截整页;false=只截视口
     * @param scale    deviceScaleFactor(2=Retina)
     * @throws RenderUnavailableException Chromium 不可用
     * @throws RenderBusyException        排队超过 timeoutSeconds
     * @throws RuntimeException           其余渲染失败(message 为裸原因,工具层加 [渲染失败] 前缀)
     */
    public RenderResult render(String html, Viewport vp, boolean fullPage, int scale) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderBusyException("渲染排队被中断");
        }
        if (!acquired) {
            throw new RenderBusyException("渲染器忙(等待超过 " + cfg.getTimeoutSeconds() + "s 未获得渲染许可)");
        }
        try {
            BrowserHolder holder;
            try {
                holder = acquireBrowser();
            } catch (RenderUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new RenderUnavailableException(String.valueOf(e.getMessage()), INSTALL_HINT);
            }
            try (BrowserContext ctx = holder.browser().newContext(new Browser.NewContextOptions()
                    .setViewportSize(vp.width(), vp.height())
                    .setDeviceScaleFactor(scale));
                 Page page = ctx.newPage()) {
                if (cfg.isNetworkBlocked()) {
                    // SSRF/外联双保险之一:一切网络请求 abort(setContent 注入本身不是网络请求,不受影响)
                    ctx.route("**", route -> route.abort());
                }
                page.setContent(injectCsp(html),
                        new Page.SetContentOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                // 无网络请求可等,固定小等待让内联 JS 渲染完成(spec §2)
                page.waitForTimeout(cfg.getRenderWaitMs());
                byte[] png = page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(fullPage)
                        .setType(ScreenshotType.PNG));
                int[] size = pngSize(png);
                return new RenderResult(png, size[0], size[1]);
            } catch (Exception e) {
                // message 保持裸原因,工具层负责加 "[渲染失败] " 前缀(避免双重前缀)
                throw new RuntimeException(e.getMessage(), e);
            }
        } finally {
            semaphore.release();
        }
    }

    /** 释放浏览器 + Playwright(bean destroyMethod="close" 目标;移植 sql-forge shutdown/BrowserHolder.close)。 */
    public void close() {
        BrowserHolder holder = this.browserHolder;
        if (holder != null) {
            try {
                holder.close();
            } catch (Exception e) {
                log.warn("HtmlRenderEngine 浏览器关闭失败: {}", e.getMessage());
            }
            this.browserHolder = null;
        }
    }

    /** 懒启动 + 连接复用(移植 sql-forge acquireBrowser 的 FutureTask 超时骨架)。 */
    private BrowserHolder acquireBrowser() throws Exception {
        BrowserHolder holder = this.browserHolder;
        if (holder != null && holder.browser().isConnected()) {
            return holder;
        }
        synchronized (this) {
            if (this.browserHolder != null && this.browserHolder.browser().isConnected()) {
                return this.browserHolder;
            }
            FutureTask<BrowserHolder> task = new FutureTask<>(this::launchWithProbe);
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "loom-html-render-launch");
                t.setDaemon(true);
                return t;
            });
            try {
                executor.submit(task);
                this.browserHolder = task.get(LAUNCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return this.browserHolder;
            } catch (TimeoutException te) {
                task.cancel(true);
                throw new RenderUnavailableException(
                        "Chromium 启动超时(> " + LAUNCH_TIMEOUT_MS + "ms);dev 机首次使用可能正在在线下载浏览器,请稍后重试",
                        INSTALL_HINT);
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause() == null ? ee : ee.getCause();
                if (c instanceof RenderUnavailableException rue) {
                    throw rue;
                }
                throw new RenderUnavailableException(String.valueOf(c.getMessage()), INSTALL_HINT);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * 三级探测(spec D5):① chromium-path 配置 → ② Playwright 默认缓存(~/.cache/ms-playwright /
     * %LOCALAPPDATA%\ms-playwright)→ ③ setChannel("chromium") 系统包。全部失败时抛
     * IllegalStateException,reason 汇总三级各自的失败原因。
     */
    private BrowserHolder launchWithProbe() {
        Playwright pw = Playwright.create();
        List<String> reasons = new ArrayList<>();
        String configured = cfg.getChromiumPath();
        if (configured != null && !configured.isBlank()) {
            try {
                Browser b = pw.chromium().launch(baseLaunch().setExecutablePath(Paths.get(configured.trim())));
                log.info("HtmlRenderEngine Chromium 启动成功(chromium-path={})", configured);
                return new BrowserHolder(pw, b, "chromium-path");
            } catch (Exception e) {
                reasons.add("chromium-path(" + configured + "): " + e.getMessage());
            }
        }
        try {
            Browser b = pw.chromium().launch(baseLaunch());
            log.info("HtmlRenderEngine Chromium 启动成功(Playwright 默认缓存)");
            return new BrowserHolder(pw, b, "playwright-default");
        } catch (Exception e) {
            reasons.add("Playwright 默认缓存: " + e.getMessage());
        }
        try {
            Browser b = pw.chromium().launch(baseLaunch().setChannel("chromium"));
            log.info("HtmlRenderEngine Chromium 启动成功(系统 channel=chromium)");
            return new BrowserHolder(pw, b, "channel:chromium");
        } catch (Exception e) {
            reasons.add("channel=chromium: " + e.getMessage());
        }
        try { pw.close(); } catch (Exception ignore) { }
        throw new IllegalStateException("Chromium 三级探测全部失败 — " + String.join(" | ", reasons));
    }

    private BrowserType.LaunchOptions baseLaunch() {
        return new BrowserType.LaunchOptions()
                .setHeadless(true)
                // 防御纵深:Node driver 的 deprecation 警告不回流 stdout(移植 sql-forge)
                .setEnv(Map.of("NODE_NO_WARNINGS", "1"));
    }

    /**
     * CSP 注入(spec §2):有 {@code <head>}(可带属性)则在开标签后插 meta;无 head 则整页包裹
     * 最小骨架。任何情况都注入 —— networkBlocked=false 只去掉 route abort,CSP 仍在(spec §4.1)。
     */
    static String injectCsp(String html) {
        String meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">";
        String src = html == null ? "" : html;
        Matcher m = HEAD_OPEN.matcher(src);
        if (m.find()) {
            int gt = src.indexOf('>', m.end() - 1);
            if (gt >= 0) {
                return src.substring(0, gt + 1) + meta + src.substring(gt + 1);
            }
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" + meta + "</head><body>" + src + "</body></html>";
    }

    /**
     * 读 PNG IHDR 宽高(big-endian)。PNG 布局:8B 签名 + 4B 长度 + "IHDR" + 4B 宽(offset 16)
     * + 4B 高(offset 20)。坏输入返回 {0, 0}(工具层文本仍可用,只是尺寸显示 0x0)。
     */
    static int[] pngSize(byte[] png) {
        if (png == null || png.length < 24) {
            return new int[]{0, 0};
        }
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return new int[]{w, h};
    }

    /** 浏览器持有器(移植 sql-forge BrowserHolder;source 记录三级探测命中的是哪一级,仅日志用)。 */
    record BrowserHolder(Playwright pw, Browser browser, String source) {
        void close() {
            try { browser.close(); } catch (Exception ignore) { }
            try { pw.close(); } catch (Exception ignore) { }
        }
    }
}
```

- [ ] **Step 6: 运行单元测试确认通过**

Run: `mvn -q -pl spring-ai-loom-agent -am -Dtest=HtmlRenderEngineUnitTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS 5/5

- [ ] **Step 7: 提交**

```bash
git add pom.xml spring-ai-loom-agent/pom.xml spring-ai-loom-agent-test/pom.xml \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngine.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineUnitTest.java
git commit -m "feat: HtmlRenderEngine — 移植 sql-forge 浏览器骨架 + 三级 Chromium 探测 + CSP/route abort 双保险网络屏蔽(playwright 1.50.0 optional)"
```

---

### Task 3: FileIdBridge 抽取 + IHtmlRenderTool + DefaultHtmlRenderTool

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/common/FileIdBridge.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/file/DefaultFileTool.java:413-447`(私有 `getOrCreateFileId` 改委托)
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/IHtmlRenderTool.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderTool.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderToolTest.java`

**Interfaces:**
- Consumes:
  - Task 1:`LoomAgentProperties.RenderProperty`(`getMaxHtmlBytes()` / `getTimeoutSeconds()` 等)
  - Task 2:`HtmlRenderEngine#render(String html, Viewport vp, boolean fullPage, int scale)` → `RenderResult(byte[] png, int widthPx, int heightPx)`;`RenderResult` / `Viewport` record;`RenderUnavailableException#reason()` / `#installHint()`;`RenderBusyException`;`HtmlRenderEngine` 是具体类(测试用 Mockito mock)
  - 既有:`IFile#getByExactPath(String path, String username)` / `#insert(FileRecord, String username)` / `#update(String id, String newPath, String newName, Long newSize, String username)`;`FileRecord(String id, String knowledgeId, String fileName, long size, LocalDateTime uploadTime, String path, String usage, String mimeType)`;`cn.wubo.loom.file.core.PathSecurityUtils.assertInsideBaseDir(Path, Path, boolean)`;`ToolContext#getContext()` map 里的 `username` / `baseUrl`
- Produces(Task 4 bean 注册依赖):
  - `public interface IHtmlRenderTool extends IEmbedTool`,接口方法 `String renderHtmlFile(String htmlFilePath, String imageName, String device, Boolean fullPage, ToolContext toolContext)`(**接口上无 @Tool/@ToolParam,只有 @ToolGroup**)
  - `public class DefaultHtmlRenderTool implements IHtmlRenderTool`,构造器 `DefaultHtmlRenderTool(HtmlRenderEngine engine, IFile file, String fileBasePath, LoomAgentProperties.RenderProperty cfg)`
  - `public class FileIdBridge`(tool.common 包),构造器 `FileIdBridge(IFile file)`,方法 `String getOrCreateFileId(Path filePath, String username)`(异常/失败返回 null,语义与原 DefaultFileTool 私有方法一致)
  - `static String DefaultHtmlRenderTool.sanitizeImageName(String raw)`(测试缝:剥路径分隔符/非法字符,空→null 语义)

- [ ] **Step 1: 抽取 FileIdBridge(DRY,行为零变化)**

Create `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/common/FileIdBridge.java`:

```java
package cn.wubo.spring.ai.loom.agent.tool.common;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.util.TikaUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 路径 → fileId 桥接(getOrCreateFileId),从 DefaultFileTool 私有方法原样抽出
 * (行为零变化),供 DefaultFileTool 的预览/下载链接与 DefaultHtmlRenderTool 的
 * 截图链接共用(spec D7:完全复用既有 usage='temp' 桥接机制,零新基建)。
 * <p>
 * 语义:已存在记录 → 尺寸变化时 update,返回原 id;不存在 → Tika 探测 mimeType,
 * UUID 生成 fileId,insert usage='temp' 行;任何异常 → 返回 null(调用方转失败文本)。
 */
public class FileIdBridge {

    private final IFile file;

    public FileIdBridge(IFile file) {
        this.file = file;
    }

    public String getOrCreateFileId(Path filePath, String username) {
        try {
            String pathStr = filePath.toString();
            FileRecord existing = file.getByExactPath(pathStr, username);
            if (existing != null) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    if (attrs.size() != existing.size()) {
                        file.update(existing.id(), pathStr, filePath.getFileName().toString(), attrs.size(), username);
                    }
                } catch (Exception ignored) {
                    // 读取 / 更新失败时仍然返回已有 id
                }
                return existing.id();
            }

            String mimeType = TikaUtils.TIKA.detect(filePath.toFile());
            if (mimeType == null) mimeType = "application/octet-stream";
            String fileId = UUID.randomUUID().toString();
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            file.insert(new FileRecord(
                    fileId,
                    null,
                    filePath.getFileName().toString(),
                    attrs.size(),
                    LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()),
                    pathStr,
                    "temp",
                    mimeType
            ), username);
            return fileId;
        } catch (Exception e) {
            return null;
        }
    }
}
```

然后 Modify `DefaultFileTool.java`:
1. 删除私有方法 `getOrCreateFileId`(L413-447,从 `private String getOrCreateFileId(Path filePath, String username) {` 到其配对的 `}`)
2. 加字段 + 构造器初始化:字段区(`private final FileOperations fileOps;` 后)加 `private final cn.wubo.spring.ai.loom.agent.tool.common.FileIdBridge fileIdBridge;`,构造器末尾(`this.fileOps = ...` 赋值后)加:
```java
        this.fileIdBridge = new cn.wubo.spring.ai.loom.agent.tool.common.FileIdBridge(file);
```
3. `downloadFileUrl` / `viewFileUrl` 里两处调用 `getOrCreateFileId(filePath, username)` 改为 `fileIdBridge.getOrCreateFileId(filePath, username)`
4. 若 `FileRecord` / `TikaUtils` / `BasicFileAttributes` / `LocalDateTime` / `ZoneId` / `UUID` import 因此不再被引用,删掉对应 import(编译器会提示 unused;`FileRecord` 在 `cleanupFileRecords` 仍用到,**保留**)

- [ ] **Step 2: 运行既有 DefaultFileTool 回归确认抽取无破坏**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest='DefaultFileToolTest,FileByPathBridgeTest' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS(既有用例全绿 = 抽取行为零变化;若 FileByPathBridgeTest 反射调用了被删的私有方法,把该测试改为走 FileIdBridge 公有方法 —— 语义相同)

- [ ] **Step 3: 写失败单元测试(DefaultHtmlRenderToolTest)**

Create `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderToolTest.java`:

```java
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
        Path p = baseDir.resolve(user).resolve(relPath);
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
        // 越权目标:baseDir/secret.html(在别的用户目录之外构造一个文件)
        Files.writeString(baseDir.resolve("secret.html"), "<p>secret</p>");
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
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=DefaultHtmlRenderToolTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: 编译失败 —— `cannot find symbol: class IHtmlRenderTool / DefaultHtmlRenderTool`

- [ ] **Step 5: 写接口 IHtmlRenderTool**

Create `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/IHtmlRenderTool.java`:

```java
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
```

- [ ] **Step 6: 写实现 DefaultHtmlRenderTool**

Create `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderTool.java`:

```java
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
```

- [ ] **Step 7: 运行单元测试确认通过**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=DefaultHtmlRenderToolTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS 15/15

- [ ] **Step 8: lib 模块全量回归(抽取 FileIdBridge 不破坏既有)**

Run: `mvn -q -pl spring-ai-loom-agent -am test -Dgpg.skip=true`
Expected: PASS(185 + 新增引擎单测,0 失败)

- [ ] **Step 9: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/common/FileIdBridge.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/file/DefaultFileTool.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/IHtmlRenderTool.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderTool.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/DefaultHtmlRenderToolTest.java
git commit -m "feat: IHtmlRenderTool/renderHtmlFile — 沙箱校验 + prototypes 存图 + FileIdBridge 抽取复用(D7/D8 文本契约全分支)"
```

---

### Task 4: autoconfigure bean 注册 + RBAC 契约测试 + autoconfig 切片

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/pom.xml`(playwright optional 依赖)
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(imports + ToolConfiguration 尾部两个 bean,`defaultAskUserTool` 之后)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderToolContractTest.java`(Create)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/autoconfigure/LoomAgentToolAutoConfigTest.java`(Modify)

**Interfaces:**
- Consumes: Task 2 `HtmlRenderEngine(RenderProperty)` 构造器 + `close()`;Task 3 `DefaultHtmlRenderTool(HtmlRenderEngine, IFile, String, RenderProperty)` 构造器、`IHtmlRenderTool`(带 `@ToolGroup("render")`)
- Produces: Spring bean `htmlRenderEngine`(destroyMethod="close")+ `defaultHtmlRenderTool`;`IHtmlRenderTool extends IEmbedTool` → `DefaultChat` 的 `List<IEmbedTool>` 注入自动收集,RBAC 过滤(`tool_render`)自动生效,**零 DefaultChat/CapabilityService 改动**

- [ ] **Step 1: 写失败契约测试**

Create `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderToolContractTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IHtmlRenderTool RBAC + 注解落位契约(spec 2026-09-09 D3 + Global Constraints)。
 * 元数据单一真源 = 接口上的 @ToolGroup;@Tool/@ToolParam 必须在实现类
 * (Spring AI 反射不继承接口注解 —— askUser 复验教训)。
 */
class HtmlRenderToolContractTest {

    @Test
    @DisplayName("@ToolGroup(render) 在接口上,defaultGranted=false(RBAC 工具,重副作用必须显式授权)")
    void toolGroupIsRenderAndNotDefaultGranted() {
        ToolGroup ann = IHtmlRenderTool.class.getAnnotation(ToolGroup.class);
        assertThat(ann).as("IHtmlRenderTool 必须声明 @ToolGroup").isNotNull();
        assertThat(ann.value()).isEqualTo("render");
        assertThat(ann.defaultGranted()).isFalse();
        assertThat(ann.description()).contains("renderHtmlFile");
    }

    @Test
    @DisplayName("接口 extends IEmbedTool(DefaultChat List<IEmbedTool> 自动收集)")
    void extendsIEmbedTool() {
        assertThat(IEmbedTool.class.isAssignableFrom(IHtmlRenderTool.class)).isTrue();
    }

    @Test
    @DisplayName("实现类方法带 @Tool + 4 个 @ToolParam(htmlFilePath 必填,其余 optional),ToolContext 无注解")
    void implCarriesToolAnnotations() throws Exception {
        Method m = DefaultHtmlRenderTool.class.getMethod("renderHtmlFile",
                String.class, String.class, String.class, Boolean.class, ToolContext.class);
        assertThat(m.isAnnotationPresent(Tool.class)).isTrue();
        assertThat(m.getAnnotation(Tool.class).description()).contains("PNG");

        java.lang.annotation.Annotation[][] pa = m.getParameterAnnotations();
        for (int i = 0; i < 4; i++) {
            ToolParam tp = (ToolParam) Arrays.stream(pa[i])
                    .filter(a -> a instanceof ToolParam)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("参数 " + i + " 缺 @ToolParam"));
            assertThat(tp.required()).as("参数 %d 的 required", i).isEqualTo(i == 0);
            assertThat(tp.description()).isNotBlank();
        }
        assertThat(Arrays.stream(pa[4]).anyMatch(a -> a instanceof ToolParam)).isFalse();
    }

    @Test
    @DisplayName("接口方法不带 @Tool(注解归实现类)")
    void interfaceHasNoToolAnnotation() throws Exception {
        Method m = IHtmlRenderTool.class.getMethod("renderHtmlFile",
                String.class, String.class, String.class, Boolean.class, ToolContext.class);
        assertThat(m.isAnnotationPresent(Tool.class)).isFalse();
    }
}
```

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=HtmlRenderToolContractTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS 4/4(接口/实现是 Task 3 产物,契约即刻可验;此步是"契约已满足"确认而非 RED —— 若 Task 3 实现有偏差这里会 RED,修正实现而不是改测试)

- [ ] **Step 2: autoconfigure pom 加 playwright(optional)**

`spring-ai-loom-agent-spring-boot-autoconfigure/pom.xml` — 在 `bucket4j-core` 依赖之后加(先例:bucket4j-core 同为 autoconfigure 的 optional 依赖;bean 方法签名引用 `HtmlRenderEngine`,javac 解析其成员时可能需要 playwright 类型在 classpath,optional 不传递给消费者):

```xml
        <!-- IHtmlRenderTool/HtmlRenderEngine bean 定义编译期需要(lib 侧是 optional 不传递);
             optional=true:starter/消费者不引入,运行时门控靠 @ConditionalOnClass(name=...) 字符串形式 -->
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <optional>true</optional>
        </dependency>
```

- [ ] **Step 3: ToolConfiguration 注册两个 bean**

`LoomAgentConfiguration.java` — imports 区(与既有 `cn.wubo.spring.ai.loom.agent.tool.maven.IMavenTool` import 同段)加:

```java
import cn.wubo.spring.ai.loom.agent.tool.render.DefaultHtmlRenderTool;
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine;
import cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool;
```

(`IFile` import 已存在 —— `defaultFileTool` 在用;若不存在则补 `import cn.wubo.spring.ai.loom.agent.file.IFile;`)

`ToolConfiguration` 内,`defaultAskUserTool` bean(约 L888-896)之后追加:

```java
        /**
         * HTML 渲染截图引擎(spec 2026-09-09-html-render-tool-design D4/D5)。
         * @ConditionalOnClass 字符串形式:消费者 classpath 无 playwright → bean 不创建,
         * 且不触发 HtmlRenderEngine 类加载(镜像 IMavenTool/maven-invoker 先例)。
         * 构造器不启动浏览器(懒启动,首次 render 才 acquireBrowser);
         * destroyMethod="close" 在容器关闭时释放 Chromium 进程(engine 是普通类
         * 不是 @Component,@PreDestroy 不生效,必须显式声明)。
         */
        @ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
        @ConditionalOnMissingBean(HtmlRenderEngine.class)
        @Bean(destroyMethod = "close")
        public HtmlRenderEngine htmlRenderEngine(LoomAgentProperties properties) {
            return new HtmlRenderEngine(properties.getRender());
        }

        /**
         * IHtmlRenderTool(spec D2/D3):RBAC 工具 tool_render(role_tool 表显式授权,
         * 非 universal)。@ConditionalOnMissingBean 保证消费者可整体替换。
         */
        @ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
        @ConditionalOnMissingBean(IHtmlRenderTool.class)
        @Bean
        public IHtmlRenderTool defaultHtmlRenderTool(HtmlRenderEngine htmlRenderEngine,
                                                     IFile file,
                                                     LoomAgentProperties properties) {
            return new DefaultHtmlRenderTool(htmlRenderEngine, file,
                    properties.getFileBasePath(), properties.getRender());
        }
```

- [ ] **Step 4: autoconfig 切片测试补 render bean**

`LoomAgentToolAutoConfigTest.java`:
1. imports 加:
```java
import cn.wubo.spring.ai.loom.agent.tool.render.HtmlRenderEngine;
import cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool;
```
2. `allToolsLoadedByDefault()` 的断言块末尾(`hasSingleBean(IKnowledgeTool.class)` 之后)加:
```java
            // playwright 在 test 模块 classpath(显式依赖)→ @ConditionalOnClass 命中
            assertThat(ctx).hasSingleBean(HtmlRenderEngine.class);
            assertThat(ctx).hasSingleBean(IHtmlRenderTool.class);
```
3. 类级 `@DisplayName` "验证所有 9 个 I*Tool 默认都加载" → "验证所有 10 个 I*Tool 默认都加载";方法级 `@DisplayName` "默认配置下 8 个 I*Tool bean 加载(…)" → "默认配置下 9 个 I*Tool bean 加载(IScheduleTool 需要 flex-schedule classpath,本测试无该依赖)"(数字与既有文本保持一致口径:类级含 schedule,方法级不含)
4. 类体末尾(TestConfig 之前)加替换性用例:
```java
    @Test
    @DisplayName("消费者自定义 IHtmlRenderTool bean → 默认实现不创建(@ConditionalOnMissingBean 可替换)")
    void customRenderToolReplacesDefault() {
        runner.withBean("customHtmlRenderTool", IHtmlRenderTool.class,
                        () -> (htmlFilePath, imageName, device, fullPage, toolContext) -> "custom")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IHtmlRenderTool.class);
                    assertThat(ctx.getBean(IHtmlRenderTool.class))
                            .isNotInstanceOf(cn.wubo.spring.ai.loom.agent.tool.render.DefaultHtmlRenderTool.class);
                });
    }
```

- [ ] **Step 5: 运行切片 + 契约测试**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest='LoomAgentToolAutoConfigTest,HtmlRenderToolContractTest' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS(autoconfig 3 用例 + 契约 4 用例)

- [ ] **Step 6: test 模块全量回归**

Run: `mvn -q -pl spring-ai-loom-agent-test -am test -Dgpg.skip=true`
Expected: PASS,0 失败(基线 412 + 本特性新增;若出现与既有用例冲突,修实现不修基线断言)

- [ ] **Step 7: 提交**

```bash
git add spring-ai-loom-agent-spring-boot-autoconfigure/pom.xml \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderToolContractTest.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/autoconfigure/LoomAgentToolAutoConfigTest.java
git commit -m "feat: HtmlRenderEngine/IHtmlRenderTool bean 注册(@ConditionalOnClass playwright)+ RBAC 契约测试 + autoconfig 切片"
```

---

### Task 5: HtmlRenderEngineIT(真 Chromium,环境缺失条件跳过)

**Files:**
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineIT.java`(Create)

**Interfaces:**
- Consumes: Task 2 `HtmlRenderEngine`(全部公有面);Task 1 `RenderProperty`
- Produces: IT gate 新增 3 用例(Chromium 不可用时 3 skip,镜像 `DefaultMavenToolRealProjectIT` 的 assumeTrue 先例)

**背景**:纯 JUnit IT(不拉 Spring 上下文、不碰 H2),镜像 `DefaultMavenToolRealProjectIT` 风格。dev Windows 机首次跑 `Playwright.create()` 会在线下载 Chromium(需联网,数十秒,LAUNCH_TIMEOUT_MS=60s 内);Linux 无浏览器环境三级探测全失败 → `assumeTrue` 跳过。**`*IT` 命名会被 IT gate 的 `-Dtest='*IT'` 拾取,也会被普通 `mvn test` 拾取(本项目无 failsafe,surefire 默认 `*Test` 不含 `*IT` —— 确认:本模块 surefire 未改 includes,`IT` 结尾类默认**不**跑,只有显式 `-Dtest='*IT'` 才跑,与既有 80 个 IT 行为一致)。**

- [ ] **Step 1: 写 IT**

Create `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineIT.java`:

```java
package cn.wubo.spring.ai.loom.agent.tool.render;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HtmlRenderEngine 真 Chromium 集成测试(spec §5 IT 行)。
 * <p>
 * 环境缺失条件跳过(镜像 DefaultMavenToolRealProjectIT 的 assumeTrue 先例):
 * Linux 裸机未 provision / 首次 Playwright 下载失败 → 3 用例全 skip,不算失败。
 * dev Windows 机首跑会在线下载 Chromium(需联网)。
 */
@DisplayName("HtmlRenderEngine 真 Chromium 集成测试")
class HtmlRenderEngineIT {

    private static HtmlRenderEngine engine;

    @BeforeAll
    static void probeAvailability() {
        engine = new HtmlRenderEngine(new LoomAgentProperties.RenderProperty());
        boolean available;
        try {
            engine.render("<html><body>ping</body></html>",
                    new HtmlRenderEngine.Viewport(100, 100), false, 1);
            available = true;
        } catch (Exception e) {
            available = false;
            System.out.println("[HtmlRenderEngineIT] Chromium 不可用,跳过: " + e.getMessage());
        }
        assumeTrue(available,
                "跳过:Chromium 不可用(Linux 裸机未 provision / 首次 Playwright 下载失败)");
    }

    @AfterAll
    static void closeBrowser() {
        if (engine != null) engine.close();
    }

    @Test
    @DisplayName("中文 + inline CSS → PNG(非空 + magic + 尺寸 = 视口 × deviceScaleFactor)")
    void rendersChineseHtmlToPngWithScaledDimensions() {
        String html = "<html><head><style>h1{color:#6366f1;font-family:sans-serif}</style></head>"
                + "<body><h1>登录页原型</h1><p>中文渲染无豆腐块需要系统 CJK 字体(provision 脚本装 fonts-noto-cjk)</p>"
                + "<input placeholder='用户名'><input type='password' placeholder='密码'></body></html>";
        HtmlRenderEngine.RenderResult r = engine.render(html,
                new HtmlRenderEngine.Viewport(1440, 900), false, 2);
        assertThat(r.png()).isNotNull();
        assertThat(r.png().length).isGreaterThan(1000);
        // PNG magic: 0x89 'P' 'N' 'G'
        assertThat(r.png()[0] & 0xFF).isEqualTo(0x89);
        assertThat(r.png()[1]).isEqualTo((byte) 'P');
        assertThat(r.png()[2]).isEqualTo((byte) 'N');
        assertThat(r.png()[3]).isEqualTo((byte) 'G');
        assertThat(r.widthPx()).isEqualTo(2880);   // 1440 * scale 2
        assertThat(r.heightPx()).isEqualTo(1800);  // fullPage=false → 视口 900 * 2
    }

    @Test
    @DisplayName("网络屏蔽:页面外链图片 → 探测服务器 0 命中(route abort + CSP 双保险,D6)")
    void blocksExternalNetworkRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe.png", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String html = "<html><body><img src='http://127.0.0.1:" + port + "/probe.png'>"
                    + "<p>x</p></body></html>";
            HtmlRenderEngine.RenderResult r = engine.render(html,
                    new HtmlRenderEngine.Viewport(800, 600), false, 1);
            assertThat(r.png()).isNotNull();
            assertThat(hits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("fullPage=true:超高内容截整页(高度 ≥ 内容高,宽 = 视口宽)")
    void fullPageCapturesBeyondViewport() {
        StringBuilder sb = new StringBuilder("<html><body style='margin:0'>");
        for (int i = 0; i < 200; i++) {
            sb.append("<div style='height:40px'>row ").append(i).append("</div>");
        }
        sb.append("</body></html>");
        HtmlRenderEngine.RenderResult r = engine.render(sb.toString(),
                new HtmlRenderEngine.Viewport(800, 600), true, 1);
        assertThat(r.widthPx()).isEqualTo(800);
        assertThat(r.heightPx()).isGreaterThanOrEqualTo(7900); // 200*40=8000,容忍取整
    }
}
```

- [ ] **Step 2: 运行 IT(dev 机应真跑;若本机 Chromium 下载失败则 3 skip,都算通过)**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=HtmlRenderEngineIT -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS 3/3 或 SKIP 3(assumeTrue);**FAIL = 引擎缺陷,修引擎**

- [ ] **Step 3: 确认普通 `mvn test` 不拾取 IT(surefire 默认 excludes `*IT`)**

Run: `mvn -q -pl spring-ai-loom-agent-test -am test -Dgpg.skip=true 2>&1 | grep -c "HtmlRenderEngineIT" || true`
Expected: `0`(类名未出现在常规 surefire 输出;若出现 → 该模块 surefire includes 被改过,IT 会拖慢每次构建,上报控制器裁决)

- [ ] **Step 4: 提交**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/tool/render/HtmlRenderEngineIT.java
git commit -m "test: HtmlRenderEngineIT — 真 Chromium 渲染/网络屏蔽/fullPage,环境缺失 assumeTrue 跳过"
```

---

### Task 6: provision 脚本 + 文档 + 概览图布局

**Files:**
- Create: `docs/provision-chromium.sh`
- Modify: `docs/TOOLS.md`(RBAC 表 + 新 §13 + TOC + 原 §13 改 §14)
- Modify: `docs/TOOLS.zh-CN.md`(§1 开关表 + §2 总览表 + 新 §15 + TOC + 原 §15 改 §16)
- Modify: `README.md`(feature bullet L38 + Built-in Tools 表 L45-56)
- Modify: `README.zh-CN.md`(对应位置镜像)
- Modify: `CLAUDE.md`(Core Interfaces 表 + ToolConfiguration 行 + RBAC 工具行 + Configuration Properties)
- Modify: `.claude/skills/project-overview-image/scripts/generate.py`(EN_LAYOUT + ZH_LAYOUT,10→11 TOOLS)

**Interfaces:**
- Consumes: Task 1-5 全部落地事实(配置键名、工具签名、bean 门控方式)
- Produces: 无代码接口;文档一致性(概览图 PNG 重生成继续 deferred,只改布局脚本 —— spec §6)

- [ ] **Step 1: Create `docs/provision-chromium.sh`**(spec §3.3 全文,一字不改):

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

验证:`bash -n docs/provision-chromium.sh`(语法检查;环境有 shellcheck 则再跑 `shellcheck docs/provision-chromium.sh`)

- [ ] **Step 2: docs/TOOLS.md**

1. §1 首段 "Since M3, all 10 `I*Tool` beans are **always created**" → "Since M3, all 10 always-on `I*Tool` beans are **always created** (plus `IHtmlRenderTool`, which is created only when `com.microsoft.playwright:playwright` is on the classpath)"
2. §1 首段 "The 3 RBAC tools are listed below." → "The 4 RBAC tools are listed below."
3. RBAC tools 表(`ICompileAndDeployTool` 行后)加一行:

```markdown
| `IHtmlRenderTool` | `tool_render` | Spawns a headless Chromium process (~150-300MB RAM per instance); requires the optional `com.microsoft.playwright:playwright` dependency on the classpath — without it the bean is not created at all |
```

4. TOC 加 `IHtmlRenderTool` 条目(编号 13),原 "13. Replacing a Sub-Tool" → 14;正文原 `## 13. Replacing a Sub-Tool` 标题改为 `## 14. Replacing a Sub-Tool`
5. 在原 §13 之前插入新章节:

```markdown
## 13. `IHtmlRenderTool` — HTML Render Screenshot

Renders a local self-contained single-page HTML file into a PNG screenshot with headless
Chromium (Playwright) — designed for LLM-written UI prototypes embedded in requirement docs,
data-analysis one-pagers, and report visuals.

| Method | Parameters | Description |
|--------|-----------|-------------|
| `renderHtmlFile` | `htmlFilePath` (required), `imageName?`, `device?` (desktop/tablet/mobile), `fullPage?` (default true) | Renders `{fileBasePath}/{username}/{htmlFilePath}` into `{fileBasePath}/{username}/prototypes/{name}-{timestamp}.png`, bridges a `usage='temp'` file record, and returns a preview URL + markdown embed snippet |

**Enable it (2 requirements):**

1. Add the optional dependency (the library does not pull it transitively):

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.50.0</version>
</dependency>
```

2. Admin console → Roles → authorize the `tool_render` group for the role (RBAC tool, not universal).

**Bare-metal Linux deployment (jar without Docker):** run `docs/provision-chromium.sh /path/to/app.jar`
once as root (installs system shared libraries + `fonts-noto-cjk` so Chinese text does not render as
tofu boxes, then downloads the Playwright-managed Chromium), then start the jar as a **non-root** user.
Dev machines (Windows/macOS) need no provisioning — Playwright downloads the browser on first use
(requires network). If Chromium is unavailable at render time the tool returns an `[渲染不可用]`
text with the provisioning hint — it never throws.

**Security:** all external network requests are blocked twice (context route abort + injected CSP
`default-src 'none'`); the HTML must be self-contained (inline CSS/JS). Input paths are sandboxed to
the user's file directory; output only ever lands in its `prototypes/` subdirectory. Renders are
serialized (one at a time) with a 30s timeout; HTML size cap 2MB.

**Configuration (`spring.ai.loom.agent.render.*`):**

| Property | Default | Description |
|----------|---------|-------------|
| `chromium-path` | _(empty)_ | Explicit Chromium binary (e.g. `/usr/bin/chromium-browser`); empty = Playwright probe (managed cache → system channel) |
| `device-scale-factor` | `2` | Screenshot scale (2 = Retina) |
| `timeout-seconds` | `30` | Per-render timeout including queue wait |
| `render-wait-ms` | `1500` | Fixed wait after `setContent` for inline JS to finish |
| `network-blocked` | `true` | Route-abort all external requests (CSP stays injected even when false) |
| `max-html-bytes` | `2097152` | HTML file size cap |
```

- [ ] **Step 3: docs/TOOLS.zh-CN.md**

1. §1 开关表末尾加一行(zh 文档仍是开关表风格,如实说明 render 无开关、classpath 门控):

```markdown
| _(无 enabled 开关)_ | — | — | HTML 渲染截图(`IHtmlRenderTool` — 无头 Chromium 把本地单页 HTML 渲染成 PNG 原型图)。**classpath 门控**:引入 `com.microsoft.playwright:playwright:1.50.0`(库侧 optional)才创建 bean;RBAC 工具,需在角色授权页勾选 `tool_render`。Linux 裸机先跑 `docs/provision-chromium.sh`。 |
```

2. §2 `IEmbedTool` 总览表末尾(`ICompileAndDeployTool` 行后)加:

```markdown
| `IHtmlRenderTool` | `DefaultHtmlRenderTool`(Playwright 1.50.0) | 1 | **classpath 门控** | `renderHtmlFile` — HTML→PNG 截图;需 playwright 依赖 + `tool_render` 角色授权 |
```

3. TOC 加条目(编号 15),原 "## 15. 替换子工具" 改为 "## 16. 替换子工具";在其前插入新章节 `## 15. \`IHtmlRenderTool\` — HTML 渲染截图`,内容为 Step 2 英文新章节的中文对译(方法表 / 启用两条件 / 裸机部署三步 / 安全约束 / 配置表,配置表键名与默认值照抄)

- [ ] **Step 4: README.md + README.zh-CN.md**

`README.md`:
1. L38 feature bullet:`git / maven (opt-in)` → `git / maven / html-render (opt-in)`
2. Built-in Tools 表(L45-56)`Compile & Deploy` 行后加:

```markdown
| Html Render | `IHtmlRenderTool` | 1 | ❌ classpath-gated | add `playwright` dep + `tool_render` grant |
```

`README.zh-CN.md`: 找到对应 feature bullet 与内置工具表,镜像以上两处改动(中文表述:`HTML 渲染截图`、`❌ classpath 门控`、`引入 playwright 依赖 + tool_render 授权`)

- [ ] **Step 5: CLAUDE.md**

1. Core Interfaces 表 `IAskUserTool` 行后加:

```markdown
| `IHtmlRenderTool` | `DefaultHtmlRenderTool` | HTML 渲染截图:`renderHtmlFile(htmlFilePath, imageName?, device?, fullPage?)` 用无头 Chromium(Playwright,lib 侧 optional 依赖 + `@ConditionalOnClass` 门控)把用户目录下自包含单页 HTML 渲染成 PNG(界面原型图/数据分析单页),存 `{fileBasePath}/{username}/prototypes/` 并经 FileIdBridge 桥接 fileId 返回预览链接 + markdown 片段;route abort + CSP 双保险屏蔽全部外联;Semaphore(1) 串行 + 30s 超时;失败一律返回文本(D8)。RBAC 工具 `tool_render`;Linux 裸机部署先跑 `docs/provision-chromium.sh` |
```

2. Auto-Configuration 节 `ToolConfiguration` 行:在 "ITimeTool, ISkillTool, IKnowledgeTool, IFileTool, IGitTool, IMavenTool, ICompileAndDeployTool" 列表尾加 ", IHtmlRenderTool(+ HtmlRenderEngine)",并把 "**10 个 I*Tool bean 总是创建**" 改为 "**10 个 I*Tool bean 总是创建;IHtmlRenderTool/HtmlRenderEngine 由 `@ConditionalOnClass(playwright)` 门控(optional 依赖,消费者引入才创建)**"
3. "**RBAC 工具(走 `role_tool` 表)**:`IGitTool` / `IMavenTool` / `ICompileAndDeployTool`" 行 → 追加 " / `IHtmlRenderTool`(无头浏览器吃 ~150-300MB RAM/实例,且需 optional playwright 依赖)"
4. Configuration Properties 节 `askuser` 行后加:

```markdown
- `render` — chromiumPath(空=Playwright 三级探测)/ deviceScaleFactor(2)/ timeoutSeconds(30)/ renderWaitMs(1500)/ networkBlocked(true)/ maxHtmlBytes(2MB)。无 enabled 开关:bean 门控 = playwright optional 依赖 + `@ConditionalOnClass`
```

5. Extension Points 节 `IMavenTool uses @ConditionalOnClass ...` 段后加一句:

```markdown
`IHtmlRenderTool` follows the same pattern with `com.microsoft.playwright.Playwright`: add the optional `playwright` dependency (1.50.0) to enable the bean, then grant `tool_render` to a role. On bare-metal Linux run `docs/provision-chromium.sh` once (system so-libs + fonts-noto-cjk + Playwright-managed Chromium); dev machines auto-download on first use.
```

- [ ] **Step 6: generate.py 概览图布局(EN + ZH,只改布局不重生成 PNG)**

`.claude/skills/project-overview-image/scripts/generate.py`:

EN_LAYOUT:
1. L50 胶囊:`06 PILLARS  -  10 TOOLS  -  08 ON  -  02 OPT-IN` → `06 PILLARS  -  11 TOOLS  -  08 ON  -  03 OPT-IN`
2. L54:`EXACTLY 10 plain rounded rectangle cards` → `EXACTLY 11 plain rounded rectangle cards`;`all ten visible` → `all eleven visible`;caption `8 on  2 opt-in` → `8 on  3 opt-in`
3. L55 卡片列表:`Files, Knowledge, Git, Maven, Deploy, Time, Skill, Sub-task, Schedule, Ask-user` → 末尾追加 `, Html render`
4. L56:`For each of these ten cards` → `For each of these eleven cards`;徽章 `16, 1, 28, 6, 1, 2, 2, 4, 4, 1` → `16, 1, 28, 6, 1, 2, 2, 4, 4, 1, 1`;`render all ten` → `render all eleven`

ZH_LAYOUT:
1. L81 胶囊:`10 TOOLS` → `11 TOOLS`;`02 OPT-IN` → `03 OPT-IN`
2. L85:`8 启用  2 手动` → `8 启用  3 手动`;`EXACTLY 10 张` → `EXACTLY 11 张`;`10 张全部可见` → `11 张全部可见`
3. L86 卡片列表:`文件、知识库、Git、Maven、部署、时间、技能、子任务、定时、问答` → 末尾追加 `、HTML渲染`
4. L87:徽章 `16、1、28、6、1、2、2、4、4、1` → `16、1、28、6、1、2、2、4、4、1、1`;漏卡警告句里的数量词同步(如有"10 张"字样)

**不执行 generate.py 重生成**(需 DASHSCOPE_API_KEY,与前两轮一致继续 deferred — spec §6)

- [ ] **Step 7: 一致性自检 + 提交**

自检清单:
- `grep -rn "10 TOOLS\|EXACTLY 10\|all ten" .claude/skills/project-overview-image/scripts/generate.py` → 0 命中
- `grep -n "tool_render\|IHtmlRenderTool" docs/TOOLS.md docs/TOOLS.zh-CN.md README.md README.zh-CN.md CLAUDE.md` → 每文件 ≥1 命中
- `bash -n docs/provision-chromium.sh` → 通过

```bash
git add docs/provision-chromium.sh docs/TOOLS.md docs/TOOLS.zh-CN.md README.md README.zh-CN.md CLAUDE.md .claude/skills/project-overview-image/scripts/generate.py
git commit -m "docs: IHtmlRenderTool 文档落地 — provision-chromium.sh + TOOLS/README/CLAUDE.md + 概览图布局 10→11 TOOLS(PNG 重生成仍 deferred)"
```

---

### Task 7: 全量回归门 + 活体冒烟

**Files:** 无新增(纯验证;发现问题回相应 Task 修复)

**Interfaces:**
- Consumes: Task 1-6 全部产物
- Produces: 回归门数字(写入交付报告)

- [ ] **Step 1: 全量构建 + 全部单测**

Run: `mvn -q clean install -Dgpg.skip=true`
Expected: BUILD SUCCESS;lib **190/0**(185+5 引擎单测)· test 模块 **435/0**(412 + 3 binding/defaults + 15 工具单测 + 4 契约 + 1 autoconfig 替换用例)

- [ ] **Step 2: IT gate(清库)**

先杀干净所有 spring-boot app fork / mvn wrapper JVM(ghost 进程会以 AUTO_SERVER 顶替新库 —— 第四轮已踩两次;保留 IDE 与 loom-*-mcp 开发工具进程),确认 `test -d ~/.loom/datasource` 为假后再 wipe:

```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn -pl spring-ai-loom-agent-test -am -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true
```

Expected: **131/0**(128 + HtmlRenderEngineIT 3)或 128/0 + HtmlRenderEngineIT 3 skip(Chromium 不可用);0 失败

- [ ] **Step 3: 活体冒烟(dev 机,test 模块 classpath 已有 playwright → bean 应创建)**

```bash
mvn spring-boot:run -pl spring-ai-loom-agent-test
```

1. 启动日志无 bean 创建错误;`GET /spring/ai/loom/api/capabilities`(登录 wb04307201)→ 列表出现 `tool_render`(LOCAL,effectiveEnabled=false —— base 角色未授权)
2. admin 控制台 → 角色 base → 授权本地工具 → 勾选 render → PUT 成功
3. 聊天:"写一个登录页单页 HTML 存到 prototypes/login.html,然后渲染成截图" → 预期工具链 writeFile → renderHtmlFile → 回复含 `渲染成功: prototypes/login-*.png` + 预览链接;点开链接可见 PNG(dev Windows 若无 CJK 字体问题则中文正常)
4. 冒烟产生的测试数据(角色授权、聊天、文件)如实记录到交付报告;是否清库还原由控制器裁决

**注意**:首次 render 触发 Playwright 在线下载 Chromium(数十秒),tool 30s 超时可能返回 `[渲染不可用] ... 请稍后重试` —— 重试一次即可(下载已完成,启动 <3s)。这是 D5 已知 dev 机首跑行为,不是缺陷。

- [ ] **Step 4: 交付报告数字汇总(供控制器写 roadmap/最终评审)**

记录:lib/test/IT 三门数字、冒烟结果(含首跑下载行为实录)、任何裁决与偏差。

---

## Self-Review 记录(计划作者自检)

1. **Spec 覆盖**:D1(仅路径入参)→T3;D2(命名)→T3;D3(RBAC)→T3 注解+T4 契约;D4(@ConditionalOnClass 无 yml 开关)→T4;D5(三级探测+provision)→T2+T6;D6(route abort+CSP)→T2+T5;D7(prototypes/+FileIdBridge)→T3;D8(no-throw 文本契约)→T3 全分支单测;D9(Semaphore+30s)→T2 单测+T3 busy 分支;§3.1(RenderProperty+setRender+binding)→T1;§5 测试策略六层→T1(binding)/T2(单元)/T3(单元)/T4(契约+切片)/T5(IT)/T7(活体冒烟≈E2E,真实 LLM Chrome E2E 留给下一轮全面测试);§3.3 provision+文档→T6。无缺口。
2. **占位符扫描**:所有 Step 均含完整代码/命令/期望值;无 TBD、无"类似 Task N"。
3. **类型一致性**:`RenderResult(byte[] png, int widthPx, int heightPx)`、`Viewport(int width, int height)`、`renderHtmlFile(String,String,String,Boolean,ToolContext)`、`DefaultHtmlRenderTool(HtmlRenderEngine,IFile,String,RenderProperty)`、`FileIdBridge(IFile)`+`getOrCreateFileId(Path,String)` 在 T2/T3/T4/T5 全文一致。已修正一处:Task 3 sanitize 测试期望 `..-..-etc`(`.` 是合法文件名字符,仅 `/` 被替换),正则含 `\s`(空格→`-`)保证 `login page/v2`→`login-page-v2`。
4. **计划内裁决**(执行时如遇再冲突,以 spec 为准并 ledger 记录):
   - **R1** LAUNCH_TIMEOUT_MS=60s(非 sql-forge 的 10s):spec 未定值;10s 会把 dev 机首次 Playwright 在线下载拦腰截断,违背 D5"dev 机零配置"。代价:Linux 真坏环境下首次失败反馈慢 50s(有 Semaphore+文本降级兜底)。
   - **R2** IT 网络屏蔽断言用本机 HttpServer 探测 0 命中(而非 spec §5 字面"请求被 abort"):CSP 在请求发出前就拦截,route abort 计数不可观测;"0 命中"证明的是同一安全性质(页面零外联),且确定性更强。
   - **R3** FileIdBridge 抽取(spec 未点名,DRY 驱动):DefaultHtmlRenderTool 与 DefaultFileTool 需要同一段 getOrCreateFileId;逐字复制会被评审打回。抽取保持行为零变化,以既有 DefaultFileToolTest 全绿为回归门。
   - **R4** autoconfigure pom 也加 playwright(optional):bean 方法签名引用 HtmlRenderEngine,编译期需要 playwright 类型;optional 不传递给消费者,运行时门控仍是 @ConditionalOnClass 字符串形式(先例:autoconfigure 的 bucket4j-core optional)。
   - **R5** generate.py 胶囊 `02 OPT-IN`→`03 OPT-IN`(render 计入 opt-in:需引依赖+需授权,双重 opt-in 语义与 git/maven 同类)。
