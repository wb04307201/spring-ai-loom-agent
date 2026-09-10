# 子任务/定时任务执行器 RBAC 过滤 — 设计文档

> **日期**:2026-09-10
> **触发**:IHtmlRenderTool 特性的最终全分支评审(opus)发现 finding 5 —— `DefaultSubTaskExecutor` 只排除自身工具,不按角色授权过滤本地 embed 工具,导致 RBAC 工具(render/git/maven/compile)可经 universal 的子任务/定时任务路径绕过授权。用户复核后确认这是真缺陷,选择走独立 SDD 循环修复。
> **状态**:设计已与用户分节确认(范围 = 只做 RBAC 过滤,递归防护维持既有 instanceof 守卫;过滤逻辑抽共享 helper;剔除日志按 `(user, droppedSet)` 去重 WARN),待写实现计划
> **性质**:安全修复(pre-existing,非 IHtmlRenderTool 引入;该特性只是让泄漏集合多了 render 一项)

---

## 0. 决策记录

| # | 决策 | 备选 | 理由 |
|---|---|---|---|
| D1 | 修复落点 = `DefaultSubTaskExecutor.doExecute` 的 embed 工具过滤段,**一处修好全部 4 个 RBAC 工具** | 在每个 RBAC 工具内部回查授权 | 本项目的 RBAC 模型是"交给 LLM 前先过滤工具列表"(DefaultChat 即如此);工具自身不回查授权。在执行器补同一道过滤 = 与既有模型一致,单点修复 |
| D2 | 过滤集合用 `CapabilityService.visibleToolGroupsFor(username)`(= 角色授权 ∪ universal) | `allowedCapabilityIdsFor(username, pick)` | 子任务/定时任务没有"前端勾选"(pick)这个输入维度,语义就是"该用户角色可用的全集"。`allowedCapabilityIdsFor` 的 pick 参数在此无对应物 |
| D3 | 过滤逻辑**抽成共享 helper 放 `CapabilityService`**,`DefaultChat` 与执行器都调它 | 在执行器内复制 DefaultChat 的内联反射 | 用户裁决(抽共享 helper)。DefaultChat 现有内联反射(:154-166)与 `CapabilityService.findToolInterface` 已是两份同源逻辑,第三份不可接受;抽取同时消除既有重复 |
| D4 | 执行器注入 `CapabilityService` 必须 **`@Lazy`** | eager 注入 | bean 图成环且构造器注入不可解:`CapabilityService(List<IEmbedTool>, IMcp, IRoleService)` → `List<IEmbedTool>` → `DefaultSubTaskTool` → `ISubTaskExecutor` → `CapabilityService`。既有代码已用 `@Lazy` 破同类环(`LoomAgentConfiguration` 的 `defaultSubTaskExecutor` 上 `@Lazy embedTools` / `@Lazy SubTaskRegistry`,DefaultChat bean 上 `@Lazy List<IEmbedTool>`),沿用同一手法;`@Lazy` 代理在 worker 线程首次 `doExecute` 时解析,此时容器已就绪 |
| D5 | 既有 `instanceof` 自身工具排除(`ISubTaskTool`/`IScheduleTool`/`IAskUserTool`)**保持不动**,与 RBAC 过滤作为正交两步 | 合并成一个过滤条件 | 两者语义不同:前者是防递归/防打断(结构性,与用户无关),后者是防越权(按角色)。self-tools 是 universal,会通过 RBAC 关再被 instanceof 关剔除,合并写法会让"为什么剔除"变得不可读 |
| D6 | 被 RBAC 剔除时记 **WARN,但按 `(username, droppedSet)` 组合去重**(Caffeine 有界缓存,仅首次 WARN,后续同组合降 DEBUG) | 每次 dropped 非空都 WARN(原提议) | 用户裁决(去重 WARN)。关键事实:**多数用户常态没有任何 RBAC 授权** → dropped(render/git/maven/compile)几乎每次子任务都非空,逐次 WARN 会刷爆日志。去重把日志量封顶在"不同用户 × 不同授权集";授权变更 → droppedSet 变 → 新 key → 自然重新 WARN 一次(恰是"授权面变了"这一最想看到的信号)。Caffeine 已是 lib 依赖(pom 既有),`maximumSize` 封顶内存 |
| D7 | **不**加执行器层 ThreadLocal 嵌套深度守卫 | 加深度守卫做 defense-in-depth | 用户裁决(只做 RBAC 过滤)。递归防护已由 schema 级工具剔除实现且有回归测试锁定;深度守卫只为"消费者自定义 bean 绕过 instanceof"这一假想场景服务,属 YAGNI → 记 follow-up |
| D8 | 不为行为变更做数据迁移/用户通知 | 扫描既有定时任务并提示受影响用户 | 被影响的是"未授权却白拿 RBAC 工具"的越权能力面,收回即正确;admin 若需恢复,给角色授权即可(既有 admin UI 路径)。定时任务本身继续按 cron 触发,只是能力面收窄 |

---

## 1. 缺陷陈述(证据)

### 1.1 工具授权属性(`@ToolGroup` 注解地面真相)

| 类别 | 工具 | `defaultGranted` |
|---|---|---|
| Universal(无需授权) | subtask、schedule、time、skill、file、knowledge、askUser | `= true` |
| **RBAC(必须授权)** | **render、git、maven、compile** | 缺省 `false` |

### 1.2 三条路径的过滤现状

| 路径 | 工具列表构建处 | RBAC 过滤? |
|---|---|---|
| 主聊天 | `DefaultChat.stream`(:147-166) | ✅ `allowedCapabilityIdsFor` + 内联反射过滤 |
| 子任务 MCP 侧 | `DefaultSubTaskExecutor.doExecute`(:222-227) | ✅ `mcp.getVisibleToolCallbackProvider(req.username(), List.of())` |
| **子任务/定时任务 本地 embed 侧** | `DefaultSubTaskExecutor.doExecute`(:208-217) | ❌ **只排除 3 个自身工具,无 RBAC 过滤** |

同一个执行器内 MCP 半边按用户可见性过滤、本地 embed 半边给全量 —— 且其类注释(:49-51)自述意图是 "propagates the full tool set **available to the user**",embed 半边未实现该意图。

### 1.3 可利用性

`ISubTaskTool` / `IScheduleTool` 均为 universal → **任何登录用户**都能发起子任务或创建定时任务。于是:

> 角色未授权 `tool_git` 的用户,在主聊天里看不到 git 工具(DefaultChat 正确拦截)→ 但可创建定时任务"gitClone + gitPush 到某仓库"→ 触发时 `DefaultScheduleTool.runAsSubTask`(:221-236)→ `executor.execute` → `doExecute` 把 `IGitTool` 无条件放进子任务工具列表 → 以该用户身份执行 git push。

工具自身不回查授权(RBAC 模型就是"进列表即可用"),因此**没有第二道闸**。compile(起 Docker 容器)、maven(任意构建)、render(起无头浏览器)同理。

### 1.4 归属

`doExecute` 的过滤段早于 IHtmlRenderTool 特性存在,git/maven/compile 一直在漏;该特性的最终评审已核验其分支**未触碰** `DefaultSubTaskExecutor`。本 spec 修的是 pre-existing 的 class-wide 缺口。

---

## 2. 设计

### 2.1 `CapabilityService` 新增两个方法(D3)

```java
/**
 * bean → capability id("tool_" + @ToolGroup value);未标 @ToolGroup 的接口返回 null。
 * null 语义 = 老实现,调用方一律放行(与 DefaultChat 既有兼容行为一致)。
 */
public static String toolGroupIdOf(Object bean) {
    Class<?> iface = findToolInterface(bean);
    if (iface == null) return null;
    ToolGroup ann = iface.getAnnotation(ToolGroup.class);
    return ann == null ? null : "tool_" + ann.value();
}

/**
 * 按 capability id 集合过滤本地 embed 工具。DRY 单一实现,DefaultChat 与
 * DefaultSubTaskExecutor 共用(两处 visible 集来源不同,过滤逻辑相同)。
 * id==null(未标 @ToolGroup)放行 —— 保持向后兼容语义。
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

配套内部改动:既有 `private Class<?> findToolInterface(Object bean)` 改为 `private static`(它不使用任何实例字段;两个既有调用方 `universalToolGroups` / `toLocalCapability` 是实例方法,调静态方法合法,无需改调用处)。

### 2.2 `DefaultChat` 改用 helper(语义零变化)

`:154-166` 的内联 stream 反射过滤整段替换为一行:

```java
  java.util.List<IEmbedTool> filteredEmbedTools =
          capabilityService.filterEmbedToolsByCapabilityIds(embedTools, visibleToolGroups);
```

等价性论证(评审须核验):原逻辑三个分支 —— `iface == null → true`(放行)、`ann == null → true`(放行)、否则 `visibleToolGroups.contains("tool_" + ann.value())`;helper 的 `id == null → 保留` 覆盖前两个分支(`findToolInterface` 返回 null,或注解为 null),`allowedIds.contains(id)` 覆盖第三个。`visibleToolGroups` 的计算(:147-152)不动。

### 2.3 `DefaultSubTaskExecutor` 加 RBAC 过滤(D1/D2/D5/D6)

构造器尾部追加第 7 个参数 `CapabilityService capabilityService`(存为 final 字段)。类内新增一个去重缓存字段(D6):

```java
    /**
     * RBAC 剔除日志去重(D6):key = username + "|" + 排序后的 dropped 集,值仅占位。
     * 多数用户常态无 RBAC 授权 → dropped 几乎每次非空,逐次 WARN 会刷爆日志;
     * 有界缓存把 WARN 封顶在"不同用户 × 不同授权集",授权变更时 dropped 集变 → 新 key → 重新 WARN 一次。
     * Caffeine 已是 lib 依赖;maximumSize 封顶内存,expireAfterWrite 让长期不变的组合偶尔复述一次。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> rbacWarned =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(512)
                    .expireAfterWrite(java.time.Duration.ofHours(1))
                    .build();
```

`doExecute` 里现有 embed 过滤段改为两步:

```java
            // 第一步:RBAC 过滤(本 spec 新增)—— 子任务/定时任务继承用户角色授权,
            // 未授权的 RBAC 工具(render/git/maven/compile)不进列表。
            // universal 工具因 visibleToolGroupsFor = 角色授权 ∪ universal 恒在集合内,照常可用。
            java.util.Set<String> visibleGroups = capabilityService.visibleToolGroupsFor(req.username());
            List<IEmbedTool> authorized = capabilityService.filterEmbedToolsByCapabilityIds(embedTools, visibleGroups);

            // D6:被剔除时按 (username, droppedSet) 去重记 WARN(仅首次),后续同组合降 DEBUG
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
                    log.debug("Sub-task RBAC filter(已警告过,降级 DEBUG): user={} dropped={}", req.username(), dropped);
                }
            }

            // 第二步:自身工具排除(既有,保持不动)—— 防递归 + 子任务不打断用户提问
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

注:`dropped` 的计算是对 ~11 个元素的第二次反射遍历,只为日志服务;清晰度优先于这点开销。`putIfAbsent` 在 Caffeine `asMap()` 上是原子的,多线程子任务并发下同一组合只 WARN 一次。类 javadoc 的工具过滤条目(:42-48)补一句 RBAC 过滤说明。

### 2.4 bean 接线(D4)

`LoomAgentConfiguration.SubTaskConfiguration.defaultSubTaskExecutor` 参数表追加:

```java
                // @Lazy 破环:CapabilityService 构造器注入 List<IEmbedTool>,该 list 含
                // defaultSubTaskTool → defaultSubTaskExecutor → CapabilityService。
                // 构造器注入的环 Spring 不可解;@Lazy 代理在 worker 线程首次 doExecute 时解析
                // (与本 bean 既有 @Lazy embedTools / @Lazy SubTaskRegistry 同一手法)。
                @Lazy cn.wubo.spring.ai.loom.agent.capability.CapabilityService capabilityService) {
            return new cn.wubo.spring.ai.loom.agent.subtask.DefaultSubTaskExecutor(
                    chatClient, memoryAdvisor, loomSubTaskExecutor, mcp, embedTools,
                    subTaskRegistry, capabilityService);
        }
```

**启动即验证**:环若未破,容器启动直接 `BeanCurrentlyInCreationException`。全上下文 IT(test 模块 `@SpringBootTest` 系)与活体启动是本改动的环依赖回归门;autoconfig 切片测试**不能**充当此门(切片不组装完整 bean 图)。

### 2.5 递归防护现状(确认不动,D7)

用户提出"子任务/定时任务不应再触发子任务/定时任务"—— 经核验**已是现状**:

- `doExecute` 三个 `instanceof` continue(schema 级剔除:工具不注册进子任务 ChatClient,LLM 看不到;Spring AI 只派发已注册 callback,幻觉工具名也无法执行)
- 定时任务经 `runAsSubTask` → `executor.execute` → 同一 `doExecute`,**自动继承**该排除(代码注释 :207 明写)
- 创建入口唯一性:sub-task / schedule 的**创建**只有工具入口(REST 只有 list/history/kill 读与取消),排除工具 = 排除一切创建路径
- 既有回归锁:`DefaultSubTaskExecutorTest.subTaskToolListExcludesAskUserAndSelfTools` 断言 `containsExactly(timeTool)`
- 深度硬上限 = 1(主对话 → 子任务,到此为止)

本次改动后两层过滤正交叠加:self-tools 是 universal → 通过 RBAC 关 → 再被 instanceof 关剔除,最终集合与今日一致。

---

## 3. 日志与行为变更

### 3.1 新增日志(D6)

| 级别 | 触发 | 内容 |
|---|---|---|
| WARN | 有工具被 RBAC 剔除 **且** `(username, 排序后 droppedSet)` 组合在去重缓存中首次出现 | `Sub-task RBAC filter: user={} conv={} fromScheduler={} dropped={} visible={}` |
| DEBUG | 同组合再次出现(WARN 已发过) | `Sub-task RBAC filter(已警告过,降级 DEBUG): user={} dropped={}` |

去重缓存:Caffeine `maximumSize(512)` + `expireAfterWrite(1h)`(Caffeine 已是 lib 依赖)。授权变更后 droppedSet 变 → 新 key → 重新 WARN(这正是"授权面变化"的信号);长期不变的组合每小时最多复述一次。`asMap().putIfAbsent` 原子,并发子任务下同组合只 WARN 一次。

### 3.2 行为变更(如实声明)

| 对象 | 变化 |
|---|---|
| 已授权用户的子任务/定时任务 | **零变化**(authorized 工具照常进列) |
| 未授权用户的既有定时任务(此前靠缺陷白拿 RBAC 工具) | 这些工具从子任务列表消失;LLM 调不到 → 如实报告无法执行 / 任务 FAILED。**这是收回越权能力,不是回归** |
| 主聊天 `DefaultChat` | **零行为变化**(helper 替换后语义逐字等价,见 §2.2 论证) |
| universal 工具(time/skill/file/knowledge) | 子任务中照常可用(`visibleToolGroupsFor` 恒含 universal) |
| 数据/迁移 | 无 schema 变更、无数据迁移、无通知(D8) |

### 3.3 性能

每次子任务执行多一组 DB 查询(`getUserRoles` + 每角色一次 `role_tool` 查询,经 `visibleToolGroupsFor`)。子任务是低频操作,与主聊天每次请求的同类开销持平;不加缓存(YAGNI)。

---

## 4. 测试策略

| 层 | 内容 |
|---|---|
| 单元(lib,`CapabilityServiceTest` 追加) | `toolGroupIdOf`:@ToolGroup 接口 → `"tool_"+value`;无注解 → null。`filterEmbedToolsByCapabilityIds`:RBAC 工具不在 allowed → 剔除 / 在 allowed → 保留;universal 在 allowed → 保留;未标注 bean → 放行(兼容老实现);空 allowed → 只剩未标注 bean |
| 单元(test 模块,`DefaultSubTaskExecutorTest`) | **注入真 `CapabilityService`**(配 mock `IRoleService` stub `getVisibleToolsForUser`,mock `IMcp`)而非 mock CapabilityService —— 否则过滤逻辑被 stub 掉,断言沦为 mock 回音。用例:① 未授权 → `containsExactly(timeTool)`(git/render 被剔);② 授权 `tool_git` → git 保留;③ 既有 `subTaskToolListExcludesAskUserAndSelfTools` 适配(stub visible 含 `tool_time`,断言不变 → 证两层正交);④ 构造器新参波及的既有用例统一补真 CapabilityService;⑤ **WARN 去重**:同一 `(user, droppedSet)` 连续 execute 两次,用 Logback `ListAppender` 断言 WARN 只出现 1 次、第二次降 DEBUG;换 user 或换 dropped 集 → 重新 WARN |
| 单元(test 模块,`ChatTest` 同层) | DefaultChat helper 替换的等价性:若既有测试未覆盖过滤行为,补一个轻量用例 —— 未授权 group 的工具不进 `toolCallbacks`(此处 mock `CapabilityService` 即可,只验 DefaultChat 把 visible 集交给了 helper) |
| IT(真 DB) | `SubTaskRbacFilterIT`:`@SpringBootTest(classes = LoomAgentTestApplication.class)` + `@MockBean(ChatClient.class)`(沿用 `H2VectorStoreIT` 的 `@MockBean` 既有惯例)+ autowire `ISubTaskExecutor` / `IRoleService`;建用户 + 角色,授权 `tool_git` 前后各 `execute` 一次 `SubTaskRequest`,用 `ArgumentCaptor` 捕 `spec.tools(...)` 实参,断言 `IGitTool` 实例不在场 / 在场 —— 端到端证明 `role_tool` 表 → 执行器过滤链路(执行器的 `embedTools` 是容器真 bean 列表,含真 `IGitTool`)。**该 IT 加载完整上下文,同时充当 §2.4 `@Lazy` 破环的启动回归门** |
| 启动回归 | 全上下文 IT / 活体启动验证 `@Lazy` 破环成功(无 `BeanCurrentlyInCreationException`) |
| 回归门 | lib + test 模块全绿;清库 IT gate 全绿;基线见 IHtmlRenderTool 交付记录(lib 191/0 · test 435/0 · IT 131/0/3skip) |

---

## 5. 不在本次范围

- **执行器层 ThreadLocal 嵌套深度守卫**(D7):防"消费者自定义 bean 不实现 `ISubTaskTool` 却注入 `ISubTaskExecutor`"绕过 instanceof 守卫 → follow-up
- `file://` iframe 渲染 IT(IHtmlRenderTool 终审建议)
- `loomAgentProperties` 手动拷贝块漏 `subtask`/`schedule`(2bd0b5d 同类,pre-existing)
- `LoomAgentConfiguration` by-path router 处重复 `getOrCreateFileId`(可改走 `FileIdBridge`)
- `BatchedCounterServiceTest` 时序 flake 排查
- 文档漂移:TOOLS.md TOC stale 块 / TOOLS.zh-CN.md 编号跳变 / CLAUDE.md ToolConfiguration 行 bean 计数
- `.gitattributes *.sh eol=lf`
- 概览图 PNG 重生成(待 DASHSCOPE_API_KEY)

---

## 6. 文档同步(本次要做)

| 文件 | 改动 |
|---|---|
| `CLAUDE.md` | `ISubTaskExecutor` 行的 "tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` ...) to prevent recursion" 补 RBAC 语义:工具集同时经 `CapabilityService.visibleToolGroupsFor(username)` 过滤,未授权的 RBAC 工具(render/git/maven/compile)不进子任务 —— 子任务真正"继承 user 角色" |
| `docs/SUBTASK-SCHEDULER.md` | L11 "the sub-task has the same tool access as the main conversation (files / MCP / Skill / time, etc.)" 改为如实描述:与主对话**同等受角色授权约束**(universal 工具 + 该用户角色授权的 RBAC 工具),并保留"不能再次启动子任务/定时器"的既有说明 |
| `docs/SUBTASK-SCHEDULER.zh-CN.md` | L11 对应中文镜像:"子任务拥有与主对话相同的工具访问" → "子任务的工具访问与主对话同样受角色授权约束(通用工具 + 该用户角色已授权的 RBAC 工具),未授权的 render/git/maven/compile 不可用" |

注:`CLAUDE.md` 的 `ISubTaskTool`/`IScheduleTool` 行描述的是工具自身的隔离语义,不涉及本次过滤,**不动**。
