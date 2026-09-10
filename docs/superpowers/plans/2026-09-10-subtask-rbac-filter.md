# 子任务/定时任务执行器 RBAC 过滤 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 堵住 RBAC 绕过 —— `DefaultSubTaskExecutor` 的子任务/定时任务工具集此前不按角色授权过滤,未授权用户可经 universal 的 subtask/schedule 路径调用 render/git/maven/compile;本计划让子任务工具集与主聊天同受 RBAC 约束,并把两处重复的过滤逻辑收敛为 `CapabilityService` 单一共享 helper。

**Architecture:** 三点改动。① `CapabilityService` 新增 `toolGroupIdOf(bean)`(static)+ `filterEmbedToolsByCapabilityIds(tools, allowedIds)`(共享 helper,语义 = DefaultChat 现有内联过滤);② `DefaultChat` 的内联反射过滤(13 行)替换为一行 helper 调用(语义零变化);③ `DefaultSubTaskExecutor.doExecute` 在既有 instanceof 自身工具排除**之前**加 RBAC 过滤(`visibleToolGroupsFor(username)`),剔除时按 `(username, droppedSet)` 去重记 WARN(Caffeine 有界缓存)。bean 接线:executor 注入 `@Lazy CapabilityService`(构造器环 Spring 不可解,沿用该 bean 既有 @Lazy 手法)。

**Tech Stack:** Spring Boot 3.5.16 / Spring AI 1.1.8 / JDK 17 / Caffeine(lib 既有依赖)/ JUnit5 + AssertJ + Mockito + Logback ListAppender

**Spec:** `docs/superpowers/specs/2026-09-10-subtask-rbac-filter-design.md`(D1-D8 决策是权威;本计划与 spec 冲突时以 spec 为准并上报控制器裁决)

## Global Constraints

- **安全语义**:未授权 RBAC 工具(render/git/maven/compile = `tool_render`/`tool_git`/`tool_maven`/`tool_compile`)绝不进子任务工具集;universal 工具(time/skill/file/knowledge)恒可用(`visibleToolGroupsFor` = 角色授权 ∪ universal)
- **既有 instanceof 自身工具排除(`ISubTaskTool`/`IScheduleTool`/`IAskUserTool`)一字不动**(spec D5,防递归与 RBAC 正交);执行顺序 = 先 RBAC 过滤,后 instanceof 排除
- **helper 兼容语义**:`toolGroupIdOf` 返回 null(未标 @ToolGroup 的老实现 bean)→ 放行 —— 与 DefaultChat 既有 `iface == null → true` 逐字等价(spec §2.2 论证)
- **`@Lazy` 强制**:executor bean 注入 `CapabilityService` 必须 `@Lazy`(环:`CapabilityService(List<IEmbedTool>) → DefaultSubTaskTool → ISubTaskExecutor → CapabilityService`,构造器注入不可解;漏 @Lazy = 启动即 `BeanCurrentlyInCreationException`)
- **WARN 去重**(spec D6):Caffeine `maximumSize(512)` + `expireAfterWrite(1h)`,key = `username + "|" + 排序后 dropped 逗号连接`,`asMap().putIfAbsent` 原子;首次 WARN、同组合再犯降 DEBUG
- **无 schema / seed / 配置项变更**;不动 MCP 侧过滤(已正确);不动 `DefaultSubTaskTool`/`DefaultScheduleTool`
- **命令一律仓库根执行,单模块测试带 `-am`**:`mvn -q -pl <module> -am -Dtest=Xxx -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`;全量构建用 **scoped 模块列表**(运行中的 loom-*-mcp dev 进程锁 target jar,全 reactor clean 会失败):`mvn clean install -Dgpg.skip=true -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter,spring-ai-loom-agent-test -am`
- **回归门基线**(IHtmlRenderTool 交付后):lib **191/0** · test 模块 **435/0** · 清库 IT gate **131/0/3skip**(3 skip = MavenTool×2 + MarketAcceptance×1 环境跳过)。已知 flake:`BatchedCounterServiceTest.concurrentIncrementDuringFlushDoesNotLoseUpdates` 若单独红,重跑一次确认时序性后放行并记录(pre-existing,与本改动零代码路径重叠)
- Windows Git Bash:杀进程 `taskkill //PID <pid> //F`;控制台 GBK mojibake 仅显示层,验文件用 `python -X utf8`

## File Structure

| 文件 | 动作 | 责任 |
|---|---|---|
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityService.java` | Modify | +`toolGroupIdOf`(public static)+`filterEmbedToolsByCapabilityIds`(public);`findToolInterface` 改 private static |
| `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityServiceTest.java` | Modify | 新 @Nested 组 4 用例(helper 语义) |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java` | Modify | :154-166 内联过滤 → 一行 helper 调用 |
| `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java` | Modify | 构造器第 7 参 + `rbacWarned` 缓存字段 + doExecute 两步过滤 + javadoc |
| `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` | Modify | `defaultSubTaskExecutor` 追加 `@Lazy CapabilityService` 参数并传构造器 |
| `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java` | Modify | 构造器 7 参适配(2 处)+ 3 新用例(未授权剔除/授权保留/WARN 去重) |
| `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRbacFilterIT.java` | Create | 真 DB 端到端:role_tool 授权翻转 → 子任务工具集含/不含 IGitTool;兼作 @Lazy 破环启动回归门 |
| `CLAUDE.md` / `docs/SUBTASK-SCHEDULER.md` / `docs/SUBTASK-SCHEDULER.zh-CN.md` | Modify | spec §6 文档同步三处 |

**执行顺序**:Task 1(helper + 单测)→ Task 2(DefaultChat 切换)→ Task 3(executor 过滤 + 接线 + 单测)→ Task 4(IT)→ Task 5(文档 + 全量回归门)。Task 2/3 都依赖 Task 1 的 helper;Task 4 依赖 Task 3 的构造器签名。

---

### Task 1: CapabilityService 共享 helper

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityService.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityServiceTest.java`

**Interfaces:**
- Consumes: 既有 `private Class<?> findToolInterface(Object bean)`(:305-310,遍历 `bean.getClass().getInterfaces()` 找带 `@ToolGroup` 的接口,无则 null)
- Produces(Task 2/3 依赖,签名精确):
  - `public static String CapabilityService.toolGroupIdOf(Object bean)` → `"tool_" + @ToolGroup value`,未标注/无注解 → `null`
  - `public List<IEmbedTool> CapabilityService.filterEmbedToolsByCapabilityIds(List<IEmbedTool> tools, Set<String> allowedIds)` → 保留 `id == null || allowedIds.contains(id)` 的元素,**保序**,返回新 `ArrayList`
  - `findToolInterface` 变为 `private static`(两个既有实例方法调用方无需改动)

- [ ] **Step 1: 写失败测试**

`CapabilityServiceTest.java` 类体末尾(最后一个 `}` 之前)追加一个 @Nested 组(fixture 接口 `ITestFileTool`(RBAC 型,无 defaultGranted)/`ITestUniversalSkillTool`(universal)与 `newService(...)`/mock 字段均为该测试类既有,直接复用):

```java
    // ============================================================
    //  toolGroupIdOf / filterEmbedToolsByCapabilityIds — 子任务 RBAC 过滤共享 helper
    //  (spec 2026-09-10-subtask-rbac-filter §2.1)
    // ============================================================
    @Nested
    @DisplayName("embed 工具过滤 helper")
    class EmbedToolFilter {

        @Test
        @DisplayName("toolGroupIdOf:@ToolGroup 接口 → tool_+value;未标注 bean → null")
        void groupIdOf() {
            assertEquals("tool_file", CapabilityService.toolGroupIdOf(mock(ITestFileTool.class)));
            assertEquals("tool_skill", CapabilityService.toolGroupIdOf(mock(ITestUniversalSkillTool.class)));
            assertNull(CapabilityService.toolGroupIdOf(new IEmbedTool() {}));
        }

        @Test
        @DisplayName("filter:RBAC 工具不在 allowed → 剔除;在 allowed → 保留;保序")
        void filtersByAllowedIds() {
            ITestFileTool file = mock(ITestFileTool.class);
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            CapabilityService svc = newService(file, skill);

            List<IEmbedTool> onlyUniversal =
                    svc.filterEmbedToolsByCapabilityIds(List.of(file, skill), Set.of("tool_skill"));
            assertEquals(List.of(skill), onlyUniversal);

            List<IEmbedTool> both =
                    svc.filterEmbedToolsByCapabilityIds(List.of(file, skill), Set.of("tool_file", "tool_skill"));
            assertEquals(List.of(file, skill), both);
        }

        @Test
        @DisplayName("filter:未标注 bean 放行(老实现兼容,与 DefaultChat 既有语义一致)")
        void unannotatedPassesThrough() {
            IEmbedTool legacy = new IEmbedTool() {};
            List<IEmbedTool> out = newService().filterEmbedToolsByCapabilityIds(List.of(legacy), Set.of());
            assertEquals(List.of(legacy), out);
        }

        @Test
        @DisplayName("filter:空 allowed → 只剩未标注 bean")
        void emptyAllowedKeepsOnlyLegacy() {
            ITestFileTool file = mock(ITestFileTool.class);
            IEmbedTool legacy = new IEmbedTool() {};
            List<IEmbedTool> out = newService().filterEmbedToolsByCapabilityIds(List.of(file, legacy), Set.of());
            assertEquals(List.of(legacy), out);
        }
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl spring-ai-loom-agent -am -Dtest=CapabilityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: 编译失败 —— `cannot find symbol: method toolGroupIdOf` / `filterEmbedToolsByCapabilityIds`

- [ ] **Step 3: 实现 helper**

`CapabilityService.java`:

1. `findToolInterface` 签名从 `private Class<?> findToolInterface(Object bean)` 改为 `private static Class<?> findToolInterface(Object bean)`(方法体不动;两个调用方 `universalToolGroups`/`toLocalCapability` 是实例方法,调静态合法,无需改)
2. 在 `visibleToolGroupsFor` 方法(:215-217)之后、`universalToolGroups` 之前插入:

```java
    /**
     * bean → capability id({@code "tool_" + @ToolGroup value});未标 @ToolGroup 的接口返回 {@code null}。
     * <p>
     * null 语义 = 老实现,调用方一律放行(与 DefaultChat 既有兼容行为一致,
     * 见 spec 2026-09-10-subtask-rbac-filter §2.1)。
     */
    public static String toolGroupIdOf(Object bean) {
        Class<?> iface = findToolInterface(bean);
        if (iface == null) return null;
        ToolGroup ann = iface.getAnnotation(ToolGroup.class);
        return ann == null ? null : "tool_" + ann.value();
    }

    /**
     * 按 capability id 集合过滤本地 embed 工具 —— DRY 单一实现,
     * {@code DefaultChat}(主聊天)与 {@code DefaultSubTaskExecutor}(子任务/定时任务)共用;
     * 两处 visible 集来源不同(主聊天 = allowedCapabilityIdsFor 含前端勾选交集,
     * 子任务 = visibleToolGroupsFor 角色全集),过滤逻辑相同。
     * <p>
     * {@code toolGroupIdOf(t) == null}(未标 @ToolGroup 的老实现)放行 —— 向后兼容语义。
     * 保序,返回新列表。
     */
    public List<IEmbedTool> filterEmbedToolsByCapabilityIds(List<IEmbedTool> tools, Set<String> allowedIds) {
        List<IEmbedTool> kept = new ArrayList<>();
        for (IEmbedTool t : tools) {
            String id = toolGroupIdOf(t);
            if (id == null || allowedIds.contains(id)) kept.add(t);
        }
        return kept;
    }
```

(`ArrayList`/`List`/`Set`/`ToolGroup`/`IEmbedTool` 均已 import,无需新增。)

- [ ] **Step 4: 运行确认通过 + lib 全量回归**

Run: `mvn -q -pl spring-ai-loom-agent -am -Dtest=CapabilityServiceTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS(既有用例 + 新 4 用例全绿)

Run: `mvn -q -pl spring-ai-loom-agent -am test -Dgpg.skip=true`
Expected: lib 模块 **195/0**(191 + 4)

- [ ] **Step 5: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityService.java \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/capability/CapabilityServiceTest.java
git commit -m "feat: CapabilityService 共享 embed 工具过滤 helper(toolGroupIdOf + filterEmbedToolsByCapabilityIds)"
```

---

### Task 2: DefaultChat 切换到共享 helper(语义零变化)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java:154-166`

**Interfaces:**
- Consumes: Task 1 `capabilityService.filterEmbedToolsByCapabilityIds(List<IEmbedTool>, Set<String>)`;DefaultChat 既有字段 `capabilityService`(构造器已注入,无需改构造器)与局部变量 `visibleToolGroups`(:147-152 计算,**不动**)
- Produces: 无新接口;DefaultChat 行为逐字等价(见 Step 1 等价性说明)

- [ ] **Step 1: 替换内联过滤为一行 helper 调用**

把 :154-166 整段(从 `java.util.List<IEmbedTool> filteredEmbedTools = embedTools.stream()` 到 `.toList();`)替换为:

```java
  // 2026-09-10 起走 CapabilityService 共享 helper(spec §2.2,语义逐字等价:
  // 未标 @ToolGroup 的 bean 放行(兼容老实现)+ 不在 visibleToolGroups 的 group 剔除)。
  java.util.List<IEmbedTool> filteredEmbedTools =
          capabilityService.filterEmbedToolsByCapabilityIds(embedTools, visibleToolGroups);
```

等价性对照(评审核验点):原 lambda 三分支 `iface == null → true`、`ann == null → true`、`contains("tool_"+value)` ↔ helper 的 `id == null → 保留`(前两支)+ `allowedIds.contains(id)`(第三支)。上方 :141-146 的 M3/M4 注释块**保留不动**(仍准确)。返回类型从 `.toList()`(不可变)变为 `ArrayList`(可变)——下游只有 `filteredEmbedTools.toArray()`,无变更风险。

- [ ] **Step 2: lib 编译 + 全量回归(两模块)**

Run: `mvn -q -pl spring-ai-loom-agent -am test -Dgpg.skip=true`
Expected: lib **195/0**

Run: `mvn -q -pl spring-ai-loom-agent-test -am test -Dgpg.skip=true`
Expected: test 模块 **435/0**(DefaultChat 无专属过滤单测,靠 helper 单测 + 全量回归兜底 —— 见计划末尾 Ruling R1)

- [ ] **Step 3: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java
git commit -m "refactor: DefaultChat 内联 @ToolGroup 过滤切换为 CapabilityService 共享 helper(语义零变化)"
```

---

### Task 3: DefaultSubTaskExecutor RBAC 过滤 + 去重 WARN + bean 接线

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java:1028-1050`(`defaultSubTaskExecutor` bean 方法)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java`

**Interfaces:**
- Consumes: Task 1 `CapabilityService.toolGroupIdOf(Object)`(static)/ `filterEmbedToolsByCapabilityIds` / `visibleToolGroupsFor(String)`;Task 3 自己的新构造器签名
- Produces(Task 4 IT 依赖):
  - 构造器 `DefaultSubTaskExecutor(ChatClient, BaseChatMemoryAdvisor, ExecutorService, IMcp, List<IEmbedTool>, SubTaskRegistry, CapabilityService)`(**第 7 参新增**)
  - WARN 日志格式 `Sub-task RBAC filter: user={} conv={} fromScheduler={} dropped={} visible={}`(去重后首犯);DEBUG `Sub-task RBAC filter(已警告过,降级 DEBUG): user={} dropped={}`

- [ ] **Step 1: 写失败测试(3 新用例 + 2 处构造适配 + 2 个测试 helper)**

`DefaultSubTaskExecutorTest.java` 改动:

(a) 字段区(`private DefaultSubTaskExecutor target;` 之后)加:

```java
    private cn.wubo.spring.ai.loom.agent.rbac.IRoleService roleService;
    private cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService;
```

(b) `setUp()` 中 `subTaskRegistry = new SubTaskRegistry(8, 100);` 之后、`target = new DefaultSubTaskExecutor(...)` 之前插入,并把 target 构造改为 7 参:

```java
        // RBAC 过滤用真 CapabilityService(mock IRoleService 做数据源)——
        // mock CapabilityService 会把过滤逻辑 stub 掉,断言沦为 mock 回音(spec §4)。
        roleService = mock(cn.wubo.spring.ai.loom.agent.rbac.IRoleService.class);
        capabilityService = new cn.wubo.spring.ai.loom.agent.capability.CapabilityService(
                embedTools, mcp, roleService);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                embedTools, subTaskRegistry, capabilityService);
```

(c) setUp 之后加两个 helper:

```java
    /** 重建 executor + CapabilityService(两者必须共享同一 embedTools 列表 —— universalToolGroups 靠扫它)。 */
    private void rebuildWithTools(cn.wubo.spring.ai.loom.agent.tool.IEmbedTool... tools) {
        embedTools = java.util.List.of(tools);
        capabilityService = new cn.wubo.spring.ai.loom.agent.capability.CapabilityService(
                embedTools, mcp, roleService);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                embedTools, subTaskRegistry, capabilityService);
    }

    /** 完整 stub chat 链,返回可做 verify 的 spec mock(链路 stub 模式逐字抄自本测试类既有 happy-path / excludesAskUser 用例,勿自创变体)。 */
    private ChatClient.ChatClientRequestSpec stubChain(String reply) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn(reply);
        return spec;
    }
```

(MCP 分支无需 stub:`mcp` 是 mock,`getVisibleToolCallbackProvider` 默认返回 null,executor 的 `if (mcpProvider != null)` 自然跳过 `spec.toolCallbacks(...)` 调用。)

```java
    private SubTaskRequest req(String user) {
        return new SubTaskRequest("sub-" + user, "conv-" + user, null, user, "do X", null, false);
    }
```

(d) 既有 `subTaskToolListExcludesAskUserAndSelfTools`:把其中的

```java
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                java.util.List.of(timeTool, askTool, selfTool, schedTool), subTaskRegistry);
```

替换为

```java
        rebuildWithTools(timeTool, askTool, selfTool, schedTool);
```

并在该用例开头注释追加一句:`// 两层过滤正交:4 个工具全是 universal → 通过 RBAC 关;instanceof 关再剔 3 个自身工具 → 断言不变`。其余断言(`containsExactly(timeTool)`)**一字不改**。

(e) 类体末尾加 3 个新用例:

```java
    @Test
    void subTaskToolListExcludesUnauthorizedRbacTools() {
        // spec 2026-09-10-subtask-rbac-filter:未授权 RBAC 工具不进子任务工具集
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        var renderTool = mock(cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool.class);
        rebuildWithTools(timeTool, gitTool, renderTool);
        var spec = stubChain("ok");

        SubTaskResult result = target.execute(req("alice"));

        assertThat(result.status()).isEqualTo(SubTaskStatus.COMPLETED);
        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        // time = universal 保留;git/render = RBAC 未授权(roleService 默认空)剔除
        assertThat(captor.getValue()).containsExactly(timeTool);
    }

    @Test
    void subTaskToolListIncludesAuthorizedRbacTools() {
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        var renderTool = mock(cn.wubo.spring.ai.loom.agent.tool.render.IHtmlRenderTool.class);
        rebuildWithTools(timeTool, gitTool, renderTool);
        when(roleService.getVisibleToolsForUser("alice")).thenReturn(java.util.List.of("tool_git"));
        var spec = stubChain("ok");

        target.execute(req("alice"));

        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        // 授权 tool_git → git 进列;render 仍未授权;保 embedTools 原序
        assertThat(captor.getValue()).containsExactly(timeTool, gitTool);
    }

    @Test
    void warnLoggedOncePerUserAndDroppedSet() {
        // D6:WARN 按 (username, droppedSet) 去重;换 user → 新 key → 重新 WARN
        var timeTool = mock(cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool.class);
        var gitTool = mock(cn.wubo.spring.ai.loom.agent.tool.git.IGitTool.class);
        rebuildWithTools(timeTool, gitTool);
        stubChain("ok");

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(DefaultSubTaskExecutor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            target.execute(req("alice"));
            target.execute(req("alice"));   // 同 user 同 dropped([tool_git]) → 第二次只 DEBUG
            assertThat(countRbacWarns(appender)).isEqualTo(1);

            target.execute(req("bob"));     // 换 user → 新 dedup key → 重新 WARN
            assertThat(countRbacWarns(appender)).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static long countRbacWarns(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                // 只数 RBAC 过滤的 WARN —— 同 subTaskId 重复 execute 会触发 registry 的
                // "already registered" WARN,靠消息内容区分
                .filter(e -> e.getFormattedMessage().contains("Sub-task RBAC filter"))
                .count();
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=DefaultSubTaskExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: 编译失败 —— 构造器 7 参不存在(`constructor DefaultSubTaskExecutor cannot be applied to given types`)

- [ ] **Step 3: 实现 executor 改动**

`DefaultSubTaskExecutor.java`:

(a) 字段区 `private final SubTaskRegistry subTaskRegistry;` 之后加:

```java
    private final cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService;

    /**
     * RBAC 剔除日志去重(spec 2026-09-10-subtask-rbac-filter D6):
     * key = username + "|" + 排序后 dropped 集,值仅占位。多数用户常态无 RBAC 授权 →
     * dropped 几乎每次非空,逐次 WARN 会刷爆日志;有界缓存把 WARN 封顶在
     * "不同用户 × 不同授权集",授权变更 → dropped 集变 → 新 key → 重新 WARN 一次。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> rbacWarned =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(512)
                    .expireAfterWrite(java.time.Duration.ofHours(1))
                    .build();
```

(b) 构造器追加第 7 参并赋值:

```java
    public DefaultSubTaskExecutor(ChatClient chatClient,
                                  BaseChatMemoryAdvisor memoryAdvisor,
                                  ExecutorService executor,
                                  IMcp mcp,
                                  List<IEmbedTool> embedTools,
                                  SubTaskRegistry subTaskRegistry,
                                  cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
        this.chatClient = chatClient;
        this.memoryAdvisor = memoryAdvisor;
        this.executor = executor;
        this.mcp = mcp;
        this.embedTools = embedTools;
        this.subTaskRegistry = subTaskRegistry;
        this.capabilityService = capabilityService;
    }
```

(c) `doExecute` 里把现有过滤段(从 `// Attach embedTools (filtered to exclude...` 注释到 `if (!filtered.isEmpty()) { spec.tools(...); }` 结束)整体替换为:

```java
            // 第一步:RBAC 过滤(spec 2026-09-10-subtask-rbac-filter)—— 子任务/定时任务
            // 继承用户角色授权,未授权的 RBAC 工具(render/git/maven/compile)不进列表。
            // universal 工具因 visibleToolGroupsFor = 角色授权 ∪ universal 恒在集合内,照常可用。
            java.util.Set<String> visibleGroups = capabilityService.visibleToolGroupsFor(req.username());
            List<IEmbedTool> authorized =
                    capabilityService.filterEmbedToolsByCapabilityIds(embedTools, visibleGroups);

            // D6:被剔除时按 (username, droppedSet) 去重记 WARN(仅首次),同组合再犯降 DEBUG
            java.util.List<String> dropped = new java.util.ArrayList<>();
            for (var t : embedTools) {
                String id = cn.wubo.spring.ai.loom.agent.capability.CapabilityService.toolGroupIdOf(t);
                if (id != null && !visibleGroups.contains(id)) dropped.add(id);
            }
            if (!dropped.isEmpty()) {
                java.util.Collections.sort(dropped);
                String dedupKey = req.username() + "|" + String.join(",", dropped);
                if (rbacWarned.asMap().putIfAbsent(dedupKey, Boolean.TRUE) == null) {
                    log.warn("Sub-task RBAC filter: user={} conv={} fromScheduler={} dropped={} visible={}",
                            req.username(), req.parentConversationId(), req.fromScheduler(), dropped, visibleGroups);
                } else {
                    log.debug("Sub-task RBAC filter(已警告过,降级 DEBUG): user={} dropped={}",
                            req.username(), dropped);
                }
            }

            // 第二步:自身工具排除(既有,保持不动)—— 防递归 + 子任务不打断用户提问
            // (ISubTaskTool/IScheduleTool 防递归;IAskUserTool 见 #1 spec D6:
            //  子任务只做主任务规划好的执行并返回结果,疑问写进结果由主任务决定是否提问。
            //  定时任务经子任务路径执行,自动继承本排除。)
            List<Object> filtered = new ArrayList<>();
            for (var t : authorized) {
                if (t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool) continue;
                filtered.add(t);
            }
            if (!filtered.isEmpty()) {
                spec.tools(filtered.toArray());
            }
```

(d) 类 javadoc 的 `Per-call filters the {@link IEmbedTool} list...` bullet 之后新增一个 bullet:

```java
 * <li>RBAC 过滤(spec 2026-09-10-subtask-rbac-filter):embed 工具列表先经
 * {@code CapabilityService.visibleToolGroupsFor(username)} 过滤 —— 子任务/定时任务
 * 继承用户角色授权,未授权的 RBAC 工具(render/git/maven/compile)不进子任务工具集
 * (修复此前"universal 的 subtask/schedule 入口绕过 role_tool 授权"的越权面)。
 * 剔除按 (username, droppedSet) 去重记 WARN(Caffeine 有界缓存),同组合再犯降 DEBUG。</li>
```

- [ ] **Step 4: bean 接线(@Lazy 破环)**

`LoomAgentConfiguration.java` `defaultSubTaskExecutor`(:1028-1050):参数表在 `@Lazy java.util.List<...IEmbedTool> embedTools)` 之后追加(注意给 embedTools 行补逗号):

```java
                @Lazy java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools,
                // @Lazy 破环(spec D4):CapabilityService 构造器注入 List<IEmbedTool>,
                // 该 list 含 defaultSubTaskTool → defaultSubTaskExecutor → CapabilityService,
                // 构造器注入的环 Spring 不可解。@Lazy 代理在 worker 线程首次 doExecute 时
                // 解析(与本 bean 既有 @Lazy embedTools / @Lazy SubTaskRegistry 同一手法)。
                @Lazy cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
            return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskExecutor(
                    chatClient, memoryAdvisor, loomSubTaskExecutor, mcp, embedTools,
                    subTaskRegistry, capabilityService);
        }
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q -pl spring-ai-loom-agent-test -am -Dtest=DefaultSubTaskExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true`
Expected: PASS **7/7**(既有 4 + 新 3)

- [ ] **Step 6: test 模块全量回归**

Run: `mvn -q -pl spring-ai-loom-agent-test -am test -Dgpg.skip=true`
Expected: **438/0**(435 + 3)。构造器调用点已核验仅两处:`LoomAgentConfiguration`(Step 4 已改)与 `DefaultSubTaskExecutorTest`(Step 1 已改);其余子任务系测试(`SubTaskAndScheduleHistoryIntegrationTest` 等)autowire 容器 bean,自动走新构造链。若仍有意外的编译错误/失败,按实际报错适配并在报告记录

- [ ] **Step 7: 提交**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java
git commit -m "fix(security): 子任务/定时任务工具集按角色授权过滤(RBAC 绕过修复)+ 去重 WARN + @Lazy 破环接线"
```

(若 Step 6 波及其他测试文件,一并 `git add`。)

---

### Task 4: SubTaskRbacFilterIT(真 DB 端到端 + @Lazy 破环启动门)

**Files:**
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRbacFilterIT.java`(Create)

**Interfaces:**
- Consumes: Task 3 executor 7 参构造 + RBAC 过滤;容器 bean `ISubTaskExecutor`(经 `@Lazy CapabilityService` 接线)、`IRoleService`;真 `IGitTool` bean 在容器的 `List<IEmbedTool>` 里
- Produces: 清库 IT gate 新增 1 用例;**同时充当 `@Lazy` 破环的启动回归门**(加载完整 `LoomAgentTestApplication` 上下文,环没破就 `BeanCurrentlyInCreationException` 启动失败)

**关键设计**:mock 的是 `ChatClient`(不是 executor)——executor 是容器真 bean,`embedTools` 是容器真列表(含真 `IGitTool`),`CapabilityService` 真、`roleService` 真连 DB。通过 `@MockBean ChatClient` 拦截 `chatClient.prompt()...call()` 链,用 `ArgumentCaptor` 捕 `spec.tools(Object[])` 实参,断言 `IGitTool` 实例在场/不在场。这样断言的是**真 role_tool 表 → 真过滤链**的端到端结果。

- [ ] **Step 1: 写 IT**

Create `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRbacFilterIT.java`:

```java
package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.tool.git.IGitTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子任务 RBAC 过滤真 DB 端到端(spec 2026-09-10-subtask-rbac-filter §4)。
 * <p>
 * 证明 role_tool 表授权 → 子任务工具集的真实链路:未授权 tool_git 时 IGitTool 不进列表,
 * 授权后进列表。mock 的是 ChatClient(拦截 prompt→call 链、捕 spec.tools 实参);
 * executor / CapabilityService / IRoleService / embedTools 都是容器真 bean。
 * <p>
 * 本 IT 加载完整 {@link LoomAgentTestApplication} 上下文 —— 若 Task 3 的 {@code @Lazy}
 * 破环没做对,容器启动即 {@code BeanCurrentlyInCreationException},本类直接 error(充当启动回归门)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("子任务 RBAC 过滤 IT")
class SubTaskRbacFilterIT {

    @MockBean
    private ChatClient chatClient;   // @Qualifier("chatClient") 的同一 bean 被替换

    @Autowired
    private ISubTaskExecutor executor;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String ROLE = "rbac-it-role";
    private static final String USER = "rbac-it-user";

    private void cleanup() {
        jdbc.update("DELETE FROM loom_subtask_history WHERE username = ?", USER);
        jdbc.update("DELETE FROM user_role WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_tool WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_mcp WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role_skill WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM loom_role_knowledge WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM role WHERE code = ?", ROLE);
        jdbc.update("DELETE FROM user_info WHERE username = ?", USER);
    }

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update("INSERT INTO user_info (username, nickname, password, type) VALUES (?,?,?,?)",
                USER, "RbacIT", "x", "USER");
        roleService.create(ROLE, "RBAC IT 角色", "subtask rbac filter", null);
        roleService.setUserRoles(USER, List.of(ROLE));
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    /**
     * Stub 真正被链式使用的调用:prompt()→call()→chatResponse()→getResult()→getOutput()→getText()。
     * doExecute 里 spec.user/system/advisors/toolContext/tools/toolCallbacks 全是语句调用
     * (返回值被忽略),不 stub 也不会 NPE,且 tools() 照样被 mock 记录供 verify。
     */
    private ChatClient.ChatClientRequestSpec stubChain() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse resp = mock(ChatResponse.class);
        Generation gen = mock(Generation.class);
        AssistantMessage msg = mock(AssistantMessage.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(resp);
        when(resp.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("done");
        return spec;
    }

    private List<Object> captureTools(ChatClient.ChatClientRequestSpec spec, String subId) {
        executor.execute(new SubTaskRequest(subId, "conv-it", null, USER, "do", null, false));
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        return java.util.Arrays.asList(captor.getValue());
    }

    @Test
    @DisplayName("未授权 tool_git → 子任务工具集不含 IGitTool;授权后 → 含")
    void gitToolFollowsRoleGrant() {
        // 授权前:role_tool 空 → git 不在子任务工具集
        ChatClient.ChatClientRequestSpec spec1 = stubChain();
        List<Object> beforeTools = captureTools(spec1, "it-before");
        // 防空过断言(fix round 1):captured 数组若为空,noneMatch 平凡通过 ——
        // 先正向 pin universal 工具(ITimeTool 恒在 visibleToolGroupsFor)确实进了子任务集。
        assertThat(beforeTools)
                .as("universal 工具(ITimeTool)必须恒在子任务工具集(防空过断言)")
                .anyMatch(t -> t instanceof cn.wubo.spring.ai.loom.agent.tool.time.ITimeTool);
        assertThat(beforeTools)
                .as("未授权 tool_git 时 IGitTool 不得进子任务工具集(RBAC 绕过修复)")
                .noneMatch(t -> t instanceof IGitTool);

        // 授权 tool_git 后:git 进子任务工具集
        roleService.setRoleTools(ROLE, List.of(new IRoleService.RoleToolItem("tool_git", true)));
        ChatClient.ChatClientRequestSpec spec2 = stubChain();
        List<Object> afterTools = captureTools(spec2, "it-after");
        assertThat(afterTools)
                .as("授权 tool_git 后 IGitTool 应进子任务工具集")
                .anyMatch(t -> t instanceof IGitTool);
    }
}
```

**为什么两次 execute 不串味**:`stubChain()` 每次 `mock()` 出**全新** spec 并重新 `when(chatClient.prompt()).thenReturn(该 spec)`。`captureTools` 在 execute 之后立即 `verify(本 spec).tools(...)` —— 第一次 captureTools(spec1) 完整跑完(含 verify)才轮到 setRoleTools + 第二次 stubChain()。因此 spec1 只记录 execute-1、spec2 只记录 execute-2,各自 `verify(...).tools(...)` 计数为 1,互不污染。实现时保持"captureTools 内 execute + verify 成对、两次之间才换 spec"这个顺序即可。

- [ ] **Step 2: 运行 IT(清库,确保 role_tool 从空开始)**

本 IT 用 `target/test-ds`(test 资源 `datasource-dir`),与运行中的 8080 实例(`~/.loom/datasource`)物理隔离,**无需杀任何进程**;只需清 test-ds 让 role_tool 从空开始:

```bash
rm -rf spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn -q -pl spring-ai-loom-agent-test -am -Dtest=SubTaskRbacFilterIT -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true
```

Expected: **PASS 1/1**(上下文成功加载 = @Lazy 破环成立;git 授权翻转断言通过)。若启动报 `BeanCurrentlyInCreationException` → Task 3 的 @Lazy 漏了,回去修接线而非改 IT。

- [ ] **Step 3: 确认普通 mvn test 不拾取本 IT**

Run: `mvn -q -pl spring-ai-loom-agent-test -am test -Dgpg.skip=true 2>&1 | grep -c "SubTaskRbacFilterIT" || true`
Expected: `0`(`*IT` 不被默认 surefire 拾取)

- [ ] **Step 4: 提交**

```bash
git add spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/SubTaskRbacFilterIT.java
git commit -m "test: SubTaskRbacFilterIT — 真 DB 验证 role_tool 授权翻转子任务工具集(兼 @Lazy 破环启动门)"
```

---

### Task 5: 文档同步 + 全量回归门

**Files:**
- Modify: `CLAUDE.md`(`ISubTaskExecutor` 行)
- Modify: `docs/SUBTASK-SCHEDULER.md`(L11)
- Modify: `docs/SUBTASK-SCHEDULER.zh-CN.md`(L11)

**Interfaces:**
- Consumes: Task 1-4 全部落地事实
- Produces: 文档与代码一致;最终回归门数字

- [ ] **Step 1: CLAUDE.md `ISubTaskExecutor` 行**

找到 Core Interfaces 表里 `ISubTaskExecutor` 那一行,其 Responsibility 单元格当前以 "Runs a sub-task synchronously on the dedicated `loomSubTaskExecutor` pool via `ChatClient.call`; tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` ...) to prevent recursion." 开头。把 "tools filtered to exclude self-tools" 这一段改为同时含 RBAC 语义:

将
```
tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` — 子任务不能向用户提问,疑问写进执行结果由主任务决定) to prevent recursion.
```
改为
```
tools filtered two ways — (1) RBAC: `CapabilityService.visibleToolGroupsFor(username)` so sub-tasks inherit the user's role grants (未授权的 render/git/maven/compile 不进子任务;修复此前 universal subtask/schedule 入口绕过 role_tool 的越权面,spec 2026-09-10-subtask-rbac-filter), (2) recursion guard: exclude self-tools (no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` — 子任务不能向用户提问,疑问写进执行结果由主任务决定).
```

(只改这一处;`ISubTaskTool`/`IScheduleTool` 行描述的是工具自身隔离语义,不动。)

- [ ] **Step 2: SUBTASK-SCHEDULER.md L11**

把
```
- The sub-task has the same tool access as the main conversation (files / MCP / Skill / time, etc.), but it **cannot** spawn another sub-task or create a scheduled task (filtered at the tool-collection level to prevent self-recursion).
```
改为
```
- The sub-task's tool access is **role-authorized just like the main conversation**: it gets the universal tools (file / skill / knowledge / time, etc.) plus whatever RBAC tools (render / git / maven / compile) the user's roles grant — unauthorized RBAC tools never enter the sub-task tool set. It also **cannot** spawn another sub-task or create a scheduled task (filtered at the tool-collection level to prevent self-recursion).
```

- [ ] **Step 3: SUBTASK-SCHEDULER.zh-CN.md L11**

把
```
- 子任务拥有与主对话相同的工具访问（文件 / MCP / Skill / 时间等），**但不能**再次启动子任务或创建定时器（从工具集合层面过滤，杜绝自递归）。
```
改为
```
- 子任务的工具访问**与主对话一样受角色授权约束**：获得通用工具（文件 / Skill / 知识库 / 时间等）+ 该用户角色已授权的 RBAC 工具（render / git / maven / compile），未授权的 RBAC 工具绝不进入子任务工具集。同时**不能**再次启动子任务或创建定时器（从工具集合层面过滤，杜绝自递归）。
```

- [ ] **Step 4: 一致性自检**

```bash
grep -rn "visibleToolGroupsFor\|role-authorized\|受角色授权" CLAUDE.md docs/SUBTASK-SCHEDULER.md docs/SUBTASK-SCHEDULER.zh-CN.md
```
Expected: 三文件各 ≥1 命中。文档为 UTF-8(中文不乱码;`python -X utf8` 抽查 SUBTASK-SCHEDULER.zh-CN.md L11)。

- [ ] **Step 5: 全量回归门(scoped 模块)**

```bash
mvn clean install -Dgpg.skip=true -pl spring-ai-loom-agent,spring-ai-loom-agent-spring-boot-autoconfigure,spring-ai-loom-agent-spring-boot-starter,spring-ai-loom-agent-test -am > /tmp/rbac-gate1.log 2>&1; echo "EXIT=$?"; grep -E "BUILD SUCCESS|BUILD FAILURE" /tmp/rbac-gate1.log
```
Expected: BUILD SUCCESS;lib **195/0**(191+4 helper)· test 模块 **438/0**(435+3 executor)。若 `BatchedCounterServiceTest` 单独红 → 重跑一次确认时序 flake 后放行并记录。

清库 IT gate:
```bash
rm -rf spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn -pl spring-ai-loom-agent-test -am -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false test -Dgpg.skip=true > /tmp/rbac-gate2.log 2>&1; echo "EXIT=$?"; grep -E "Tests run:" /tmp/rbac-gate2.log | tail -1
```
Expected: IT **132/0/3skip**(131 + SubTaskRbacFilterIT);3 skip 全 pre-existing。

- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md docs/SUBTASK-SCHEDULER.md docs/SUBTASK-SCHEDULER.zh-CN.md
git commit -m "docs: 子任务工具集 RBAC 授权语义同步(CLAUDE.md + SUBTASK-SCHEDULER 双语)"
```

- [ ] **Step 7: 交付报告数字汇总(供控制器写 roadmap/最终评审)**

记录:lib/test/IT 三门数字、IT 是否真启动成功(@Lazy 破环)、flake 是否复现、任何裁决与偏差。

---

## Self-Review 记录(计划作者自检)

1. **Spec 覆盖**:D1(单点修 executor)→T3;D2(visibleToolGroupsFor)→T3 Step3c;D3(共享 helper 落 CapabilityService)→T1;D4(@Lazy 破环)→T3 Step4 + T4 启动门;D5(instanceof 守卫不动 + 两步顺序)→T3 Step3c;D6(去重 WARN + Caffeine)→T3 Step3a/c + 单测 ⑤;D7(不加深度守卫)→无对应任务(正确);D8(无迁移/通知)→无 schema 改动(正确);spec §2.2(DefaultChat 等价切换)→T2;§4 测试五层→T1(helper 单测)/T3(executor 单测真 CapabilityService + WARN 去重)/T4(IT + 启动门)/T5(回归门);§6 文档同步三处→T5。无缺口。
2. **占位符扫描**:所有 Step 含完整代码/命令/期望值;无 TBD、无"类似 Task N"。
3. **类型一致性**:`toolGroupIdOf(Object)→String` / `filterEmbedToolsByCapabilityIds(List<IEmbedTool>, Set<String>)→List<IEmbedTool>` / 构造器第 7 参 `CapabilityService` 在 T1/T3/T4 全文一致;WARN/DEBUG 日志字面在 T3 实现与 T3 单测⑤断言一致(`contains("Sub-task RBAC filter")` 同时匹配 WARN 与 DEBUG 两条,但 countRbacWarns 只数 WARN 级,正确);executor 测试 helper `rebuildWithTools`/`stubChain`/`req` 在 3 新用例 + 既有用例适配中一致。
4. **计划内裁决**(执行时遇冲突以 spec 为准 + ledger 记录):
   - **R1** DefaultChat 无专属过滤单测:helper 语义已被 T1 单测覆盖(含 id==null 放行 / contains 判定),DefaultChat 改动是"内联 → 调同一 helper"的纯替换,靠 lib+test 全量回归兜底等价性;不单独为 DefaultChat 写过滤 mock 测试(mock CapabilityService 只会验证"调用了 helper",价值低于成本)。spec §4 "ChatTest 同层"那条按此降级 —— 若评审认为需要,补一个 `verify(capabilityService).filterEmbedToolsByCapabilityIds(...)` 的轻量用例。
   - **R2** executor 单测注入**真 CapabilityService**(mock IRoleService),非 mock CapabilityService —— spec §4 明令(否则过滤被 stub 掉、断言沦为 mock 回音)。`rebuildWithTools` 同时重建 executor 与 CapabilityService 以保证两者共享同一 embedTools 列表(universalToolGroups 靠扫它)。
   - **R3** IT mock `ChatClient` 而非 executor:executor/CapabilityService/IRoleService/embedTools 全用容器真 bean,才能证明真 role_tool→真过滤链;两次 execute 各用新鲜 stubChain 的 spec 防 verify 串味(Step1 注意事项)。
   - **R4** IT 兼作 @Lazy 破环启动门:加载完整 LoomAgentTestApplication 上下文,环没破即 BeanCurrentlyInCreationException;无需单独的 autoconfig 切片(切片不组装完整 bean 图,测不出环)。
   - **R5** WARN 去重单测用 Logback ListAppender(项目无既有先例,logback 是 spring-boot 默认日志实现,classpath 必有);countRbacWarns 按消息内容 `contains("Sub-task RBAC filter")` + WARN 级双过滤,避开 registry "already registered" WARN 干扰。
   - **R6** IT 清理覆盖 RBAC 5 张子表(role_tool/role_mcp/role_skill/loom_role_knowledge/user_role)+ loom_subtask_history(execute 会写)+ role + user_info;镜像 RoleDeleteCascadeIT 的 cleanup 模式。

