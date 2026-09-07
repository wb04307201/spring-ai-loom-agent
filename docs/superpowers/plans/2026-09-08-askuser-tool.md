# AskUser 交互工具(#1)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 LLM 可调用工具 `askUser`:聊天流内嵌问题卡片(单选/多选/自定义输入),工具方法阻塞等待用户作答(默认 5 分钟),答案以 tool_result 形态回同一条流,LLM 无缝继续。

**Architecture:** 路径 1 阻塞同流(spec D5):工具方法内推 SSE 卡片事件(`ChatResponseRecord` 新增第 3 组件 `askUser`)→ `CompletableFuture` 入纯内存 `AskUserRegistry` → `future.get(timeout)` 阻塞 → 前端 `POST /spring/ai/loom/ask/{questionId}/answer` complete Future → 工具 return 答案文本。子任务/定时任务 schema 级排除(self-tool 过滤器追加)。universal 工具(`@ToolGroup(defaultGranted=true)`),零 CapabilityService/RBAC 改动。

**Tech Stack:** JDK 17、Spring Boot 3.x、Spring AI 1.1.8(`pom.xml:57`)、SseEmitter、原生 JS(app.js,无框架)、Jackson。

**Spec:** `docs/superpowers/specs/2026-09-08-askuser-tool-design.md`(D1-D9 决策 + §2 权威事实 + §5 错误矩阵为本计划的裁决来源;实施时两个文件都要读)

## Global Constraints

- Spring AI 固定 1.1.8;`internalToolExecutionEnabled` 保持默认 true(全仓库无设置点,勿新增)。
- **无 schema 变更**:Registry 纯内存(spec §3),不动 `V1.0__init.sql`,无 Flyway 改动。
- **不加 `askuser.enabled` yml 开关**(M3 政策:I*Tool bean 总是创建;askUser 是 universal,永远可用)。
- **ChatMemory 硬约束**:超时路径工具必须正常 return 文本(不抛异常),让 Flux 走到 ON_COMPLETE(`LastChunkMessageChatMemoryAdvisor` L97-104 只在 ON_COMPLETE 写库)。stop 路径维持既有 CANCEL 语义(本来就不存,非新增丢失)。
- **工具描述不硬编码工具名**(项目 memory 约定):子任务契约文案描述能力,不点名 `askUser`。
- app.js 所有 fetch 显式 `Content-Type: application/json; charset=UTF-8`(CLAUDE.md GBK 教训)。
- 前端渲染 LLM/用户提供字符串一律 `escapeHtml`。
- record 加组件 = 可接受破坏(M4 T3 先例);`ChatResponseRecord` 必须保留 2-arg 兼容构造器(SseController L627 旧发送点零改动)。
- 跨用户操作统一返回与"不存在"相同的 404 文案(`DefaultSubTaskTool` L129-138 防存在性泄露先例)。
- commit 前缀:`feat:` / `fix:` / `test:` / `docs:` / `refactor:`;每 Task 一个 commit。
- 回归门基线:库单元 151 / test 模块单元 382 / IT gate 123(3 skip),命令见 Task 7。
- 新代码包名:`cn.wubo.spring.ai.loom.agent.askuser`(库模块);测试镜像包路径。

**任务依赖**:T1(事件模型)→ T2(Registry)→ T3(工具+bean)→ T4(answer 路由+stop 集成)→ T5(子任务排除)→ T6(前端卡片)→ T7(文档+回归门)。T5 与 T4/T6 无依赖可换序;T7 终端。

**条件应急预案(spec D9,非任务步骤)**:T6 完成后 Chrome 复验若发现工具阻塞期间主流内容帧明显卡顿(ForkJoinPool common 线程被占满),兜底 = 在 `DefaultAskUserTool.askUser` 的阻塞段外包 `Schedulers.boundedElastic()` 执行。不预防性引入;复验通过则不动。

---

### Task 1: 事件模型(AskUserEvent / AskUserOption / ChatResponseRecord 扩展)

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/AskUserOption.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/AskUserEvent.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java`(全文替换,现仅 5 行)
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecordTest.java`

**Interfaces:**
- Consumes: 无(起点任务)。
- Produces(T2/T3/T4/T6 依赖,签名逐字):
  - `record AskUserOption(String label, String description)`
  - `record AskUserEvent(String questionId, String question, String header, String background, java.util.List<AskUserOption> options, boolean multiSelect, boolean allowCustomInput, long timeoutSeconds)`
  - `record ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser)` + 2-arg 兼容构造器 `ChatResponseRecord(String content, String reasoningContent)`(委托 `this(content, reasoningContent, null)`)

- [ ] **Step 1: 写失败测试**

```java
package cn.wubo.spring.ai.loom.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseRecordTest {

    @Test
    void twoArgConstructorLeavesAskUserNull() {
        ChatResponseRecord rec = new ChatResponseRecord("hello", "thinking");
        assertThat(rec.content()).isEqualTo("hello");
        assertThat(rec.reasoningContent()).isEqualTo("thinking");
        assertThat(rec.askUser()).isNull();
    }

    @Test
    void threeArgConstructorCarriesAskUserEvent() {
        AskUserEvent ev = new AskUserEvent("q-1", "选哪个?", "部署", "背景说明",
                List.of(new AskUserOption("Docker", "容器部署"), new AskUserOption("java -jar", null)),
                false, true, 300L);
        ChatResponseRecord rec = new ChatResponseRecord(null, null, ev);
        assertThat(rec.askUser().questionId()).isEqualTo("q-1");
        assertThat(rec.askUser().options()).hasSize(2);
        assertThat(rec.askUser().options().get(0).label()).isEqualTo("Docker");
        assertThat(rec.askUser().multiSelect()).isFalse();
        assertThat(rec.askUser().allowCustomInput()).isTrue();
        assertThat(rec.askUser().timeoutSeconds()).isEqualTo(300L);
    }

    @Test
    void jacksonSerializesAllEventFields() throws Exception {
        AskUserEvent ev = new AskUserEvent("q-2", "问题", "标题", "背景",
                List.of(new AskUserOption("A", "说明A"), new AskUserOption("B", "说明B")),
                true, false, 60L);
        String json = new ObjectMapper().writeValueAsString(new ChatResponseRecord(null, null, ev));
        assertThat(json).contains("\"questionId\":\"q-2\"")
                .contains("\"multiSelect\":true")
                .contains("\"allowCustomInput\":false")
                .contains("\"timeoutSeconds\":60")
                .contains("\"label\":\"A\"");
    }

    @Test
    void jacksonRoundTripsOptionRecord() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AskUserOption opt = om.readValue("{\"label\":\"A\",\"description\":\"说明\"}", AskUserOption.class);
        assertThat(opt.label()).isEqualTo("A");
        assertThat(opt.description()).isEqualTo("说明");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=ChatResponseRecordTest -Dgpg.skip=true`
Expected: COMPILATION ERROR(`AskUserEvent`/`AskUserOption` 不存在,3-arg 构造器不存在)

- [ ] **Step 3: 实现三个 model 文件**

`AskUserOption.java`:

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * askUser 问题卡片的一个选项。
 *
 * @param label       选项文本(必填非空)
 * @param description 可选补充说明(渲染在 label 下方,可为 null)
 */
public record AskUserOption(String label, String description) {
}
```

`AskUserEvent.java`:

```java
package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

/**
 * askUser 工具推给前端的提问卡片事件(#1,spec D4)。
 * 作为 {@link ChatResponseRecord#askUser()} 第 3 组件随 SSE 帧下发;
 * 前端 onChunk 按字段分派渲染,askUser=null 的普通内容帧不受影响。
 *
 * @param questionId       UUID,answer 端点按此索引挂起的 CompletableFuture
 * @param question         问题正文
 * @param header           短标题/chip(可空)
 * @param background       背景说明(可空)
 * @param options          2-4 个选项(工具入参校验保证)
 * @param multiSelect      是否多选
 * @param allowCustomInput 是否允许"其他"自定义输入
 * @param timeoutSeconds   前端倒计时用(与工具阻塞超时同值)
 */
public record AskUserEvent(String questionId,
                           String question,
                           String header,
                           String background,
                           List<AskUserOption> options,
                           boolean multiSelect,
                           boolean allowCustomInput,
                           long timeoutSeconds) {
}
```

`ChatResponseRecord.java`(全文替换):

```java
package cn.wubo.spring.ai.loom.agent.model;

/**
 * SSE 唯一下行帧。content/reasoningContent 为流式文本增量;
 * askUser 非空时表示一张提问卡片事件(#1 AskUser 工具,普通帧为 null)。
 * 2-arg 构造器保持旧发送点(SseController 内容帧)源码兼容。
 */
public record ChatResponseRecord(String content,
                                 String reasoningContent,
                                 AskUserEvent askUser) {

    public ChatResponseRecord(String content, String reasoningContent) {
        this(content, reasoningContent, null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过 + 全模块编译**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=ChatResponseRecordTest -Dgpg.skip=true`
Expected: PASS 4/4

Run: `mvn compile -pl spring-ai-loom-agent-spring-boot-autoconfigure -Dgpg.skip=true`
Expected: BUILD SUCCESS(证明 SseController L627 的 2-arg 构造点未破坏)

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/AskUserOption.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/AskUserEvent.java spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecord.java spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/model/ChatResponseRecordTest.java
git commit -m "feat: AskUserEvent/AskUserOption records + ChatResponseRecord 3rd component (2-arg compat ctor)"
```

---

### Task 2: AskUserRegistry(纯内存挂起问题注册表)

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserRegistry.java`
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserRegistryTest.java`

**Interfaces:**
- Consumes: T1 的 `AskUserEvent`(8 组件,签名逐字见 T1)。
- Produces(T3/T4 依赖,签名逐字):
  - `public static final String CANCELLED_SENTINEL = "__ASKUSER_CANCELLED__"`
  - `record AskUserRegistry.PendingQuestion(AskUserEvent event, String username, String conversationId, java.util.concurrent.CompletableFuture<String> answer, long createdAt)`
  - `void register(PendingQuestion q)`(以 `q.event().questionId()` 为 key)
  - `PendingQuestion get(String questionId)`(null-safe)
  - `void remove(String questionId)`(null-safe)
  - `boolean answer(String questionId, String username, String answerText)` — 未知 id → false;username 不符 → false(防泄露,同"不存在"语义);`future.complete` 返回 false(已被超时/取消抢先)→ false
  - `int cancelAll(String username, String conversationId)` — 匹配项以 `CANCELLED_SENTINEL` complete + remove,返回取消数
- 注意:**不加 `@Component`**(镜像 `SubTaskRegistry` 纯类 + autoconfigure `@Bean` 模式,T3 注册 bean)。

- [ ] **Step 1: 写失败测试**

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AskUserRegistryTest {

    private AskUserRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AskUserRegistry();
    }

    private AskUserRegistry.PendingQuestion pending(String qid, String user, String conv) {
        AskUserEvent ev = new AskUserEvent(qid, "问题", null, null,
                List.of(new AskUserOption("A", null), new AskUserOption("B", null)),
                false, false, 300L);
        return new AskUserRegistry.PendingQuestion(ev, user, conv,
                new CompletableFuture<>(), System.currentTimeMillis());
    }

    @Test
    void registerThenAnswerCompletesFuture() throws Exception {
        registry.register(pending("q-1", "alice", "conv-1"));
        boolean ok = registry.answer("q-1", "alice", "A");
        assertThat(ok).isTrue();
        assertThat(registry.get("q-1").answer().get(1, TimeUnit.SECONDS)).isEqualTo("A");
    }

    @Test
    void answerUnknownQuestionIdReturnsFalse() {
        assertThat(registry.answer("nope", "alice", "A")).isFalse();
        assertThat(registry.answer(null, "alice", "A")).isFalse();
    }

    @Test
    void answerWrongUsernameReturnsFalseAndDoesNotComplete() {
        registry.register(pending("q-2", "alice", "conv-1"));
        assertThat(registry.answer("q-2", "mallory", "A")).isFalse();
        assertThat(registry.get("q-2").answer().isDone()).isFalse();
    }

    @Test
    void secondAnswerReturnsFalseAfterFirstCompletes() {
        registry.register(pending("q-3", "alice", "conv-1"));
        assertThat(registry.answer("q-3", "alice", "A")).isTrue();
        assertThat(registry.answer("q-3", "alice", "B")).isFalse();
    }

    @Test
    void cancelAllCompletesSentinelAndClears() throws Exception {
        registry.register(pending("q-4", "alice", "conv-1"));
        CompletableFuture<String> future = registry.get("q-4").answer();
        int n = registry.cancelAll("alice", "conv-1");
        assertThat(n).isEqualTo(1);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.CANCELLED_SENTINEL);
        assertThat(registry.get("q-4")).isNull();
    }

    @Test
    void cancelAllIgnoresOtherUserAndConversation() {
        registry.register(pending("q-5", "alice", "conv-1"));
        registry.register(pending("q-6", "bob", "conv-1"));
        registry.register(pending("q-7", "alice", "conv-2"));
        int n = registry.cancelAll("alice", "conv-1");
        assertThat(n).isEqualTo(1);
        assertThat(registry.get("q-5")).isNull();
        assertThat(registry.get("q-6")).isNotNull();
        assertThat(registry.get("q-7")).isNotNull();
    }

    @Test
    void removeIsIdempotentAndNullSafe() {
        registry.register(pending("q-8", "alice", "conv-1"));
        registry.remove("q-8");
        registry.remove("q-8");
        registry.remove(null);
        assertThat(registry.get("q-8")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=AskUserRegistryTest -Dgpg.skip=true`
Expected: COMPILATION ERROR(`AskUserRegistry` 不存在)

- [ ] **Step 3: 实现 AskUserRegistry**

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挂起提问注册表(#1 AskUser,spec §3):questionId → PendingQuestion,纯内存。
 * <p>
 * 生命周期 ≤ timeoutSeconds(默认 5 分钟),不落库(spec 非目标);应用重启即清空,
 * 前端随后提交会收到 404 "问题已失效"(可接受,见 spec §5)。
 * <p>
 * 并发语义:{@link #answer} 与 {@link #cancelAll} 都走
 * {@code CompletableFuture.complete}(先到先得);complete 返回 false 表示已被
 * 对方抢先,answer 据此映射 404(spec §5 竞态行)。
 */
public class AskUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(AskUserRegistry.class);

    /** stop 取消哨兵:DefaultAskUserTool 识别后返回"用户已停止"文本(spec D7)。 */
    public static final String CANCELLED_SENTINEL = "__ASKUSER_CANCELLED__";

    private final Map<String, PendingQuestion> pending = new ConcurrentHashMap<>();

    /**
     * 一个挂起的提问。
     *
     * @param event          推给前端的卡片事件(含 questionId)
     * @param username       提问归属用户(answer 端点校验用)
     * @param conversationId 提问归属会话(cancelAll 按此匹配)
     * @param answer         工具阻塞等待的 Future
     * @param createdAt      注册时刻(调试用)
     */
    public record PendingQuestion(AskUserEvent event,
                                  String username,
                                  String conversationId,
                                  CompletableFuture<String> answer,
                                  long createdAt) {
    }

    public void register(PendingQuestion q) {
        pending.put(q.event().questionId(), q);
    }

    public PendingQuestion get(String questionId) {
        return questionId == null ? null : pending.get(questionId);
    }

    public void remove(String questionId) {
        if (questionId != null) pending.remove(questionId);
    }

    /**
     * 提交答案。
     *
     * @return true=Future 被本次调用 complete;false=未知 id / 用户不符(统一防泄露,
     *         spec D8)/ 已被超时或取消抢先 complete。路由层把 false 一律映射 404。
     */
    public boolean answer(String questionId, String username, String answerText) {
        PendingQuestion q = get(questionId);
        if (q == null) return false;
        if (q.username() == null || !q.username().equals(username)) {
            // 与"不存在"同语义,不泄露 questionId 是否存在于其他用户(spec D8)
            log.warn("拒绝跨用户提交 askUser 答案: caller={}, qid={}", username, questionId);
            return false;
        }
        return q.answer().complete(answerText);
    }

    /**
     * 取消某会话全部挂起提问(用户点 stop 时由 SseController onStop 调用,spec D7)。
     * 哨兵 complete 释放被 future.get() 阻塞的工具线程 —— Flux dispose 本身不中断阻塞。
     *
     * @return 取消数量
     */
    public int cancelAll(String username, String conversationId) {
        int n = 0;
        for (PendingQuestion q : pending.values()) {
            if (q.username() != null && q.username().equals(username)
                    && q.conversationId() != null && q.conversationId().equals(conversationId)) {
                q.answer().complete(CANCELLED_SENTINEL);
                pending.remove(q.event().questionId());
                n++;
            }
        }
        if (n > 0) log.info("askUser cancelAll: user={} conv={} cancelled={}", username, conversationId, n);
        return n;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=AskUserRegistryTest -Dgpg.skip=true`
Expected: PASS 7/7

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserRegistry.java spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserRegistryTest.java
git commit -m "feat: AskUserRegistry (in-memory pending-question map, answer/cancelAll race-safe via CompletableFuture)"
```

---

### Task 3: IAskUserTool + DefaultAskUserTool + 配置 + bean 注册

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/IAskUserTool.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/DefaultAskUserTool.java`
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java`(L86 `private SubTaskProperty subtask...` 附近加字段;文件末尾 L364-366 `ScheduleProperty` 之后加嵌套类)
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(`ToolConfiguration` L813-863 末尾、`defaultKnowledgeTool` bean 之后加两个 bean)
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/DefaultAskUserToolTest.java`
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/PropertiesDefaultsTest.java`(追加 1 个方法;该文件已存在,含 `new LoomAgentProperties()` 断言先例)

**Interfaces:**
- Consumes: T1 `AskUserEvent`/`AskUserOption`/`ChatResponseRecord(3-arg)`;T2 `AskUserRegistry`(全部 API + `CANCELLED_SENTINEL` + `PendingQuestion` 5 组件);既有 `SseEmitterRegistry.get(username, conversationId)` 返回 `Entry(emitter, disposable, startMs, onStop)`;`ToolContext.getContext()` 键 `username`/`parentConversationId`(`DefaultChat` L193-208 注入)。
- Produces(T4/T5/T6/T7 依赖,签名逐字):
  - `@ToolGroup(value = "askUser", defaultGranted = true, description = "ask_user — 向当前用户提出选择卡片并等待作答") public interface IAskUserTool extends IEmbedTool`
  - `String askUser(String question, String header, String background, String optionsJson, Boolean multiSelect, Boolean allowCustomInput, ToolContext toolContext)`(接口带 @ToolParam,实现带 @Tool —— 镜像 ISubTaskTool/DefaultSubTaskTool 分工)
  - `new DefaultAskUserTool(AskUserRegistry, SseEmitterRegistry, long timeoutSeconds)`
  - `LoomAgentProperties.getAskuser().getTimeoutSeconds()`(默认 300L)
  - autoconfigure beans:`askUserRegistry()`(无条件 + `@ConditionalOnMissingBean`)、`defaultAskUserTool(...)`(`@ConditionalOnMissingBean(IAskUserTool.class)`)
- 工具返回文本契约(T6 前端提示语 / T7 文档引用):
  - 成功:`"[用户已回答] " + answerText`
  - 超时:`"[用户未作答] 用户未在 N 分钟内作答。请基于现有信息自行合理决策并继续,或改用其他方式推进。"`
  - 取消哨兵:`"[用户未作答] 用户已停止本次对话,未作答。"`
  - 校验失败:`"[提问失败] ..."`(纠错文案,LLM 自我修正)
  - 流不可用:`"[提问失败] 无法向用户提问(会话流不可用)。"`

- [ ] **Step 1: 写失败测试(库模块)**

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultAskUserToolTest {

    private static final String OPTIONS_JSON =
            "[{\"label\":\"Docker\",\"description\":\"容器部署\"},{\"label\":\"java -jar\",\"description\":null}]";

    private AskUserRegistry askRegistry;
    private SseEmitterRegistry sseRegistry;
    private SseEmitter emitter;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        askRegistry = new AskUserRegistry();
        sseRegistry = new SseEmitterRegistry();
        emitter = mock(SseEmitter.class);
        sseRegistry.register("alice", "conv-1", emitter, null, null);
        ctx = new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-1"));
    }

    /** send 时截获 questionId,另起线程提交答案 —— 模拟用户点卡片。 */
    private void answerOnSend(String answerText) throws Exception {
        doAnswer(inv -> {
            ChatResponseRecord rec = inv.getArgument(0);
            String qid = rec.askUser().questionId();
            Thread t = new Thread(() -> askRegistry.answer(qid, "alice", answerText));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
    }

    @Test
    void returnsAnswerAndPushesCompleteCardEvent() throws Exception {
        answerOnSend("java -jar");
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        String r = tool.askUser("选择部署方式?", "部署", "需要确认部署形态",
                OPTIONS_JSON, false, true, ctx);

        assertThat(r).isEqualTo("[用户已回答] java -jar");
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(emitter).send(sent.capture(), eq(MediaType.APPLICATION_JSON));
        AskUserEvent ev = ((ChatResponseRecord) sent.getValue()).askUser();
        assertThat(ev.question()).isEqualTo("选择部署方式?");
        assertThat(ev.header()).isEqualTo("部署");
        assertThat(ev.background()).isEqualTo("需要确认部署形态");
        assertThat(ev.options()).hasSize(2);
        assertThat(ev.options().get(0).label()).isEqualTo("Docker");
        assertThat(ev.options().get(0).description()).isEqualTo("容器部署");
        assertThat(ev.multiSelect()).isFalse();
        assertThat(ev.allowCustomInput()).isTrue();
        assertThat(ev.timeoutSeconds()).isEqualTo(5L);
        assertThat(((ChatResponseRecord) sent.getValue()).content()).isNull();
        // 提问结束后注册表已清空(finally remove)
        assertThat(askRegistry.get(ev.questionId())).isNull();
    }

    @Test
    void timesOutAndReturnsNotAnsweredText() {
        // timeoutSeconds=0 → future.get(0, SECONDS) 立即 TimeoutException
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 0);
        String r = tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx);
        assertThat(r).startsWith("[用户未作答]").contains("未在").contains("分钟内作答");
    }

    @Test
    void cancelSentinelMapsToStoppedText() throws Exception {
        doAnswer(inv -> {
            Thread t = new Thread(() -> askRegistry.cancelAll("alice", "conv-1"));
            t.setDaemon(true);
            t.start();
            return null;
        }).when(emitter).send(any(Object.class), any(MediaType.class));
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);

        String r = tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx);
        assertThat(r).isEqualTo("[用户未作答] 用户已停止本次对话,未作答。");
    }

    @Test
    void rejectsBlankQuestion() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("  ", null, null, OPTIONS_JSON, false, false, ctx))
                .startsWith("[提问失败]").contains("question");
        verifyNoInteractions(emitter);
    }

    @Test
    void rejectsOneOption() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "[{\"label\":\"A\"}]", false, false, ctx))
                .startsWith("[提问失败]").contains("2-4");
    }

    @Test
    void rejectsFiveOptions() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        String five = "[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"},{\"label\":\"D\"},{\"label\":\"E\"}]";
        assertThat(tool.askUser("选哪个?", null, null, five, false, false, ctx))
                .startsWith("[提问失败]").contains("2-4");
    }

    @Test
    void rejectsMalformedOptionsJson() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "not-json", false, false, ctx))
                .startsWith("[提问失败]").contains("optionsJson");
    }

    @Test
    void rejectsBlankOptionLabel() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, "[{\"label\":\"A\"},{\"label\":\" \"}]", false, false, ctx))
                .startsWith("[提问失败]").contains("label");
    }

    @Test
    void returnsUnavailableWhenNoActiveStream() throws Exception {
        SseEmitterRegistry emptyRegistry = new SseEmitterRegistry(); // 未 register
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, emptyRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, ctx))
                .isEqualTo("[提问失败] 无法向用户提问(会话流不可用)。");
    }

    @Test
    void rejectsMissingToolContextIdentity() {
        DefaultAskUserTool tool = new DefaultAskUserTool(askRegistry, sseRegistry, 5);
        assertThat(tool.askUser("选哪个?", null, null, OPTIONS_JSON, false, false, new ToolContext(Map.of())))
                .startsWith("[提问失败]").contains("会话上下文");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=DefaultAskUserToolTest -Dgpg.skip=true`
Expected: COMPILATION ERROR(`IAskUserTool`/`DefaultAskUserTool` 不存在)

- [ ] **Step 3: 实现接口**

`IAskUserTool.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM-callable tool:向当前用户提出一个选择式问题并阻塞等待作答(#1,spec D3/D5)。
 * <p>
 * 问题以卡片形式推入当前聊天流(SSE askUser 帧),用户在卡片上单选/多选/自定义输入,
 * 前端 POST answer 端点唤醒阻塞的工具线程,答案以 tool_result 回到同一条流。
 * <p>
 * universal 工具(defaultGranted=true):仅向"当前流的本人"提问,答案回同一流,
 * 无越权风险;子任务/定时任务被 schema 级排除(见 DefaultSubTaskExecutor 过滤器)。
 */
@ToolGroup(value = "askUser", defaultGranted = true,
        description = "ask_user — 向当前用户提出选择卡片并等待作答")
public interface IAskUserTool extends IEmbedTool {

    /**
     * @param question         问题正文(必填非空)
     * @param header           短标题/chip(可空)
     * @param background       背景说明(可空)
     * @param optionsJson      选项 JSON 数组字符串,2-4 个 {@code {"label","description"}}
     *                         (String 而非 List<record>:对 qwen 系模型的 tool-args
     *                         JSON 容错更好,服务端用 Spring AI 宽容 JsonParser 解析)
     * @param multiSelect      是否多选(null=false)
     * @param allowCustomInput 是否允许自定义输入(null=false)
     * @param toolContext      Spring AI 工具上下文(username / parentConversationId)
     * @return "[用户已回答] ...",或 "[用户未作答] ...",或 "[提问失败] ..." 纠错文本
     */
    String askUser(
            @ToolParam(description = "要问用户的问题文本,一句话,清晰具体") String question,
            @ToolParam(description = "问题的短标题(2-6 字,如'部署方式'),可传 null") String header,
            @ToolParam(description = "为什么问这个问题的背景说明(1-2 句),可传 null") String background,
            @ToolParam(description = "选项 JSON 数组,2-4 个,形如 [{\"label\":\"选项A\",\"description\":\"补充说明\"},{\"label\":\"选项B\"}]") String optionsJson,
            @ToolParam(description = "是否允许多选,默认 false") Boolean multiSelect,
            @ToolParam(description = "是否允许用户自由输入自定义答案,默认 false") Boolean allowCustomInput,
            ToolContext toolContext);
}
```

- [ ] **Step 4: 实现 DefaultAskUserTool**

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import cn.wubo.spring.ai.loom.agent.model.ChatResponseRecord;
import cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 默认实现:推卡片 → future.get(timeout) 阻塞 → 返回答案文本(spec §4 数据流)。
 * <p>
 * 所有失败路径都 return 文本、不抛异常 —— 保住 Flux ON_COMPLETE 让
 * LastChunkMessageChatMemoryAdvisor 落库(ChatMemory 硬约束,spec §0 目标 3)。
 * 阻塞发生在 Spring AI 同步 tool 执行线程(subtask 的 future.get() 同模式先例);
 * D9 应急预案(复验卡顿才启用)见 plan Global Constraints。
 */
public class DefaultAskUserTool implements IAskUserTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultAskUserTool.class);

    private final AskUserRegistry askUserRegistry;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final long timeoutSeconds;

    public DefaultAskUserTool(AskUserRegistry askUserRegistry,
                              SseEmitterRegistry sseEmitterRegistry,
                              long timeoutSeconds) {
        this.askUserRegistry = askUserRegistry;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 与 DefaultSubTaskTool.readContextString 同款(L34-38)。 */
    private static String readContextString(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) return "";
        Object value = toolContext.getContext().get(key);
        return (value instanceof String s) ? s : "";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static List<AskUserOption> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return List.of();
        // Spring AI 的共享 ObjectMapper:InfrastructureConfiguration 已开
        // ALLOW_COMMENTS / ALLOW_SINGLE_QUOTES(qwen tool-args 容错先例)
        ObjectMapper om = org.springframework.ai.util.json.JsonParser.getObjectMapper();
        try {
            return om.readValue(optionsJson,
                    om.getTypeFactory().constructCollectionType(List.class, AskUserOption.class));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Tool(description = "当需要用户在若干明确选项中做出选择、确认或澄清时,向当前用户提出一个问题。"
            + "问题会以选项卡片形式出现在聊天窗口,用户点选后你会收到答案并继续当前任务。"
            + "等待期间当前回复会暂停,这是正常的。仅在确实需要用户决策时使用;"
            + "能自行合理决定的不要问。一次只问一个问题,需要多个答案时分多次调用。")
    @Override
    public String askUser(String question, String header, String background,
                          String optionsJson, Boolean multiSelect, Boolean allowCustomInput,
                          ToolContext toolContext) {
        String username = readContextString(toolContext, "username");
        String conversationId = readContextString(toolContext, "parentConversationId");
        if (username.isEmpty() || conversationId.isEmpty()) {
            log.warn("askUser 缺少会话上下文,拒绝提问: username='{}' conv='{}'", username, conversationId);
            return "[提问失败] 当前调用缺少用户会话上下文,无法向用户提问。";
        }
        if (question == null || question.isBlank()) {
            return "[提问失败] question 不能为空,请提供要问用户的问题文本后重试。";
        }

        List<AskUserOption> options;
        try {
            options = parseOptions(optionsJson);
        } catch (IllegalArgumentException e) {
            return "[提问失败] optionsJson 解析失败: " + e.getMessage()
                    + "。请提供形如 [{\"label\":\"选项A\",\"description\":\"说明\"},{\"label\":\"选项B\"}] 的 JSON 数组后重试。";
        }
        if (options.size() < 2 || options.size() > 4) {
            return "[提问失败] options 数量必须在 2-4 个之间,当前 " + options.size() + " 个。请调整后重试。";
        }
        for (AskUserOption o : options) {
            if (o.label() == null || o.label().isBlank()) {
                return "[提问失败] 每个选项必须有非空 label。请修正 optionsJson 后重试。";
            }
        }

        SseEmitterRegistry.Entry entry = sseEmitterRegistry.get(username, conversationId);
        if (entry == null || entry.emitter() == null) {
            log.warn("askUser 无活跃流: user={} conv={}", username, conversationId);
            return "[提问失败] 无法向用户提问(会话流不可用)。";
        }

        String questionId = UUID.randomUUID().toString();
        AskUserEvent event = new AskUserEvent(questionId, question.trim(),
                blankToNull(header), blankToNull(background), options,
                Boolean.TRUE.equals(multiSelect), Boolean.TRUE.equals(allowCustomInput),
                timeoutSeconds);
        CompletableFuture<String> future = new CompletableFuture<>();
        askUserRegistry.register(new AskUserRegistry.PendingQuestion(
                event, username, conversationId, future, System.currentTimeMillis()));

        try {
            // ResponseBodyEmitter.send 内部 synchronized,与 SseController 内容帧并发安全
            entry.emitter().send(new ChatResponseRecord(null, null, event), MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            askUserRegistry.remove(questionId);
            log.warn("askUser 卡片推送失败: user={} conv={} err={}", username, conversationId, e.getMessage());
            return "[提问失败] 无法向用户提问(会话流不可用)。";
        }
        log.info("askUser 提问: qid={} user={} conv={} question={}", questionId, username, conversationId, question);

        String answer;
        try {
            answer = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            long minutes = Math.max(1, timeoutSeconds / 60);
            return "[用户未作答] 用户未在 " + minutes + " 分钟内作答。"
                    + "请基于现有信息自行合理决策并继续,或改用其他方式推进。";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "[提问被中断] 等待用户作答时被中断,未获得答案。";
        } catch (ExecutionException ee) {
            log.warn("askUser 等待异常: qid={} err={}", questionId, ee.getMessage());
            return "[提问失败] 等待用户作答时发生异常: " + ee.getMessage();
        } finally {
            // 覆盖正常/超时/中断/异常全部路径:注册表不留幽灵行
            askUserRegistry.remove(questionId);
        }

        if (AskUserRegistry.CANCELLED_SENTINEL.equals(answer)) {
            return "[用户未作答] 用户已停止本次对话,未作答。";
        }
        return "[用户已回答] " + answer;
    }
}
```

- [ ] **Step 5: LoomAgentProperties 加配置**

在 `LoomAgentProperties.java` L94 `private ScheduleProperty schedule = new ScheduleProperty();` 之后加字段:

```java
 private AskUserProperty askuser = new AskUserProperty();
```

在文件末尾 `ScheduleProperty` 嵌套类(L363-366)之后、类收尾 `}` 之前加:

```java
 /**
 * AskUser 交互工具配置(#1)。yml 通过 {@code spring.ai.loom.agent.askuser.*} 配置。
 * <ul>
 * <li>{@code timeoutSeconds} — 工具阻塞等待用户作答的最长秒数(默认 300 = 5 分钟,spec D2)。
 * 超时后工具返回"用户未作答"文本给 LLM,Flux 正常 complete(ChatMemory 本轮保住)。</li>
 * </ul>
 * 无 enabled 开关:M3 起工具 bean 总是创建;askUser 是 universal 工具(defaultGranted),
 * 不受 role_tool RBAC 控制。
 */
 @Data
 public static class AskUserProperty {
 private long timeoutSeconds = 300;
 }
```

- [ ] **Step 6: autoconfigure 注册 bean**

`LoomAgentConfiguration.ToolConfiguration`(L813)内、`defaultKnowledgeTool` bean 之后加:

```java
        /**
         * #1 AskUser:挂起提问注册表(纯内存,镜像 SubTaskRegistry 的 @Bean 模式)。
         */
        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry.class)
        public cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry askUserRegistry() {
            return new cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry();
        }

        /**
         * #1 AskUser 工具(universal,defaultGranted=true — 零 CapabilityService 改动,
         * universalToolGroups() 反射自动拾取)。ISubTaskTool bean 同款注册模式(L993-999)。
         */
        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool.class)
        public cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool defaultAskUserTool(
                cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry askUserRegistry,
                cn.wubo.spring.ai.loom.agent.stream.SseEmitterRegistry sseEmitterRegistry,
                LoomAgentProperties properties) {
            return new cn.wubo.spring.ai.loom.agent.askuser.DefaultAskUserTool(
                    askUserRegistry, sseEmitterRegistry, properties.getAskuser().getTimeoutSeconds());
        }
```

> 依赖说明:`SseEmitterRegistry` 是库模块 `@Component`(stream 包 L20-21),消费方应用组件扫描到 `cn.wubo.spring.ai.loom.agent.**` 即可注入(现状即如此);若某上下文缺该 bean,`defaultAskUserTool` 装配失败会快速暴露,不做 ObjectProvider 软化(YAGNI)。

- [ ] **Step 7: PropertiesDefaultsTest 追加断言(test 模块)**

在 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/PropertiesDefaultsTest.java` 末尾追加(镜像该文件既有 `@Test void xxx_defaults...` 风格):

```java
    @Test
    void askuserTimeoutSeconds_defaultsTo300() {
        LoomAgentProperties props = new LoomAgentProperties();
        org.assertj.core.api.Assertions.assertThat(props.getAskuser().getTimeoutSeconds()).isEqualTo(300L);
    }
```

- [ ] **Step 8: 跑测试确认通过 + install**

Run: `mvn test -pl spring-ai-loom-agent -Dtest='DefaultAskUserToolTest,AskUserRegistryTest' -Dgpg.skip=true`
Expected: PASS 17/17(工具 10 + Registry 7)

Run: `mvn clean install -Dgpg.skip=true -DskipTests`
Expected: BUILD SUCCESS(3 库模块;autoconfigure 对新 bean 编译通过)

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=PropertiesDefaultsTest -Dgpg.skip=true`
Expected: PASS(含新增 askuser 断言)

- [ ] **Step 9: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/ spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/ spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/LoomAgentProperties.java spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/PropertiesDefaultsTest.java
git commit -m "feat: IAskUserTool + DefaultAskUserTool (blocking same-stream ask, universal) + askuser.timeoutSeconds + beans"
```

---

### Task 4: answer 路由 + stop 集成

**Files:**
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`
  - `SseController`(L556):加字段 + L589 onStop lambda 替换
  - `WebConfiguration`(L1575):加 `loomAgentAskRouter` bean
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/AskUserRouterTest.java`(新建;包名必须是 `cn.wubo.spring.ai.loom.agent` 才能 new 包私有的 `WebConfiguration` —— AdminRouterSpotTest 先例)

**Interfaces:**
- Consumes: T2 `AskUserRegistry.answer(questionId, username, answerText) → boolean` / `cancelAll(username, conversationId) → int` / `PendingQuestion` / `CANCELLED_SENTINEL`;T1 `AskUserEvent`;既有 `UserContextHolder.getCurrentUser()`(AuthenticationFilter L73 每请求注入)、`LoomAgentTestUtil.safeRoute(router, method, path, jsonBody)`。
- Produces(T6 前端依赖,契约逐字):
  - `POST spring/ai/loom/ask/{questionId}/answer`(无前导斜杠,镜像 subtask router L1013 风格;实际 URL `/spring/ai/loom/ask/{questionId}/answer`)
  - 请求体:`{"answer": "选项label"}`(单选/自定义)或 `{"answer": ["label1","label2"]}`(多选,服务端按提交顺序 join `"; "`)
  - 响应:`200 {"ok":true}` / `400 {"error":"invalid answer"}`(body 非法 JSON、answer 缺失、非 string/array、空白)/ `404 {"error":"not found"}`(未知 qid、跨用户、已被超时或取消抢先 —— 三者同文案,spec D8)

- [ ] **Step 1: 写失败测试**

```java
package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry;
import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import cn.wubo.spring.ai.loom.agent.model.AskUserOption;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1 AskUser answer 端点路由测试(真 router + 真 Registry,无 Spring 上下文 ——
 * AdminRouterSpotTest 先例;spec §6 的"接线 IT"由本类等价覆盖,不依赖 DB)。
 */
@DisplayName("askUser answer 路由")
class AskUserRouterTest {

    private AskUserRegistry registry;
    private RouterFunction<ServerResponse> router;

    @BeforeEach
    void setUp() {
        registry = new AskUserRegistry();
        router = new LoomAgentConfiguration.WebConfiguration().loomAgentAskRouter(registry);
        UserContextHolder.setCurrentUser("alice");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private CompletableFuture<String> registerPending(String qid, String user, String conv) {
        AskUserEvent ev = new AskUserEvent(qid, "问题", null, null,
                List.of(new AskUserOption("A", null), new AskUserOption("B", null)),
                false, false, 300L);
        CompletableFuture<String> future = new CompletableFuture<>();
        registry.register(new AskUserRegistry.PendingQuestion(ev, user, conv, future,
                System.currentTimeMillis()));
        return future;
    }

    @Test
    void answerHappyPath200AndCompletesFuture() throws Exception {
        CompletableFuture<String> future = registerPending("q-1", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-1/answer", "{\"answer\":\"B\"}");
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("B");
    }

    @Test
    void multiSelectArrayAnswerJoinedWithSemicolon() throws Exception {
        CompletableFuture<String> future = registerPending("q-2", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-2/answer", "{\"answer\":[\"A\",\"B\"]}");
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("A; B");
    }

    @Test
    void unknownQuestionId404() throws Exception {
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/nope/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(((EntityResponse<?>) resp).entity()).isEqualTo(Map.of("error", "not found"));
    }

    @Test
    void crossUserAnswer404SameBodyAsUnknown() throws Exception {
        registerPending("q-3", "bob", "conv-9");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-3/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(((EntityResponse<?>) resp).entity()).isEqualTo(Map.of("error", "not found"));
        assertThat(registry.get("q-3").answer().isDone()).isFalse();
    }

    @Test
    void blankAnswer400() throws Exception {
        registerPending("q-4", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-4/answer", "{\"answer\":\"  \"}");
        assertThat(resp.statusCode().value()).isEqualTo(400);
        assertThat(registry.get("q-4").answer().isDone()).isFalse();
    }

    @Test
    void malformedJson400() throws Exception {
        registerPending("q-5", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-5/answer", "not-json");
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void missingAnswerField400() throws Exception {
        registerPending("q-6", "alice", "conv-1");
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-6/answer", "{}");
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void raceWithCancelMaps404() throws Exception {
        CompletableFuture<String> future = registerPending("q-7", "alice", "conv-1");
        registry.cancelAll("alice", "conv-1"); // 模拟 stop 抢先
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "POST",
                "/spring/ai/loom/ask/q-7/answer", "{\"answer\":\"A\"}");
        assertThat(resp.statusCode().value()).isEqualTo(404);
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(AskUserRegistry.CANCELLED_SENTINEL);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserRouterTest -Dgpg.skip=true`
Expected: COMPILATION ERROR(`loomAgentAskRouter` 方法不存在)

- [ ] **Step 3: WebConfiguration 加 router bean**

`LoomAgentConfiguration.WebConfiguration`(L1575)内追加(与既有 router bean 并列):

```java
        /**
         * #1 AskUser:用户提交问题卡片答案(spec §3 A4)。
         * 鉴权:AuthenticationFilter 已注入 UserContextHolder;跨用户/未知/已失效
         * 统一 404 "not found"(防存在性泄露,spec D8)。
         */
        @Bean("loomAgentAskRouter")
        public RouterFunction<ServerResponse> loomAgentAskRouter(
                cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry askUserRegistry) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.POST("spring/ai/loom/ask/{questionId}/answer", request -> {
                String user = cn.wubo.spring.ai.loom.agent.user.UserContextHolder.getCurrentUser();
                String qid = request.pathVariable("questionId");
                Map<String, Object> body;
                try {
                    body = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(request.body(String.class), Map.class);
                } catch (Exception e) {
                    return ServerResponse.badRequest().body(Map.of("error", "invalid answer"));
                }
                Object answer = body == null ? null : body.get("answer");
                String answerText;
                if (answer instanceof java.util.List<?> list) {
                    answerText = list.stream()
                            .map(String::valueOf)
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .collect(java.util.stream.Collectors.joining("; "));
                } else if (answer instanceof String s) {
                    answerText = s.trim();
                } else {
                    return ServerResponse.badRequest().body(Map.of("error", "invalid answer"));
                }
                if (answerText.isBlank()) {
                    return ServerResponse.badRequest().body(Map.of("error", "invalid answer"));
                }
                boolean ok = askUserRegistry.answer(qid, user, answerText);
                if (!ok) {
                    return ServerResponse.status(org.springframework.http.HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "not found"));
                }
                return ServerResponse.ok().body(Map.of("ok", true));
            });
            return builder.build();
        }
```

> import 说明:`Map`/`RouterFunction`/`RouterFunctions`/`ServerResponse` 在 WebConfiguration 已有 import(subtask router 同款用法在 SubTaskConfiguration;若 WebConfiguration 缺 `Map` import 则按该文件既有风格用全限定名,不新增 import 冲突)。

- [ ] **Step 4: SseController stop 集成**

4a. `SseController` 字段区(L558-561)追加一行:

```java
            private final cn.wubo.spring.ai.loom.agent.askuser.AskUserRegistry askUserRegistry;
```

4b. L589 的 onStop lambda(现内容):

```java
                        () -> { /* 用户主动 stop 时不再落库（：usage 实时从 chat_memory 聚合） */ });
```

替换为:

```java
                        () -> {
                            // 用户主动 stop 时不再落库（usage 实时从 chat_memory 聚合）;
                            // #1 AskUser(spec D7):哨兵 complete 释放被 future.get() 阻塞的
                            // 工具线程 —— Flux dispose 本身不会中断阻塞。
                            askUserRegistry.cancelAll(username, conversationId);
                        });
```

- [ ] **Step 5: 跑测试确认通过 + install**

Run: `mvn clean install -Dgpg.skip=true -DskipTests`
Expected: BUILD SUCCESS

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserRouterTest -Dgpg.skip=true`
Expected: PASS 8/8

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/AskUserRouterTest.java
git commit -m "feat: askUser answer endpoint (404 anti-leak) + stop-path cancelAll releases blocked tool thread"
```

---

### Task 5: 子任务/定时任务 schema 级排除 + 委派契约文档化

**Files:**
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutor.java`(L42-43 类 javadoc + L201-207 过滤器)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskTool.java`(L40-42 @Tool description)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ISubTaskTool.java`(接口 javadoc L8-15)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java`(追加 1 个方法)

**Interfaces:**
- Consumes: T3 的 `IAskUserTool`(instanceof 判定);既有 `ITimeTool`(测试里的"应保留"对照工具)。
- Produces: 无新签名(行为变更:子任务 LLM 的 tool schema 不含 askUser)。定时任务经子任务路径执行 → 自动继承排除,零额外改动。

- [ ] **Step 1: 写失败测试(追加到 DefaultSubTaskExecutorTest)**

```java
    @Test
    void subTaskToolListExcludesAskUserAndSelfTools() {
        // #1 AskUser(spec D6):子任务 LLM 看不到 askUser(schema 级排除),
        // 同时保留既有 ISubTaskTool/IScheduleTool 防递归排除的回归断言。
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        cn.wubo.spring.ai.loom.agent.tool.ITimeTool timeTool =
                mock(cn.wubo.spring.ai.loom.agent.tool.ITimeTool.class);
        cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool askTool =
                mock(cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool.class);
        ISubTaskTool selfTool = mock(ISubTaskTool.class);
        cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool schedTool =
                mock(cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool.class);

        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp,
                java.util.List.of(timeTool, askTool, selfTool, schedTool), subTaskRegistry);

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
        when(msg.getText()).thenReturn("done");

        SubTaskRequest req = new SubTaskRequest("sub-f", "conv-f", null, "alice",
                "do X", null, false);
        target.execute(req);

        org.mockito.ArgumentCaptor<Object[]> captor =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(spec).tools(captor.capture());
        assertThat(captor.getValue()).containsExactly(timeTool);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest='DefaultSubTaskExecutorTest#subTaskToolListExcludesAskUserAndSelfTools' -Dgpg.skip=true`
Expected: FAIL — `containsExactly(timeTool)` 不成立(实际含 askTool,过滤器还没排除 IAskUserTool)

- [ ] **Step 3: 改过滤器 + javadoc + 契约文案**

3a. `DefaultSubTaskExecutor.java` L201-207,现内容:

```java
            // Attach embedTools (filtered to exclude ISubTaskTool/IScheduleTool so the
            // sub-task cannot recursively spawn sub-tasks or schedules).
            List<Object> filtered = new ArrayList<>();
            for (var t : embedTools) {
                if (t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool) continue;
                filtered.add(t);
            }
```

替换为:

```java
            // Attach embedTools (filtered to exclude ISubTaskTool/IScheduleTool so the
            // sub-task cannot recursively spawn sub-tasks or schedules, and IAskUserTool
            // so sub-tasks / scheduled runs can never block on a user question — spec #1 D6:
            // 子任务只做主任务规划好的执行并返回结果,疑问写进结果由主任务决定是否提问。
            // 定时任务经子任务路径执行,自动继承本排除)。
            List<Object> filtered = new ArrayList<>();
            for (var t : embedTools) {
                if (t instanceof cn.wubo.spring.ai.loom.agent.subtask.ISubTaskTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool) continue;
                if (t instanceof cn.wubo.spring.ai.loom.agent.askuser.IAskUserTool) continue;
                filtered.add(t);
            }
```

3b. 同文件类 javadoc L42-43,现内容:

```java
 * <li>Per-call filters the {@link IEmbedTool} list passed in via constructor
 * to drop {@code ISubTaskTool}/{@code IScheduleTool} (recursion guard).
```

替换为:

```java
 * <li>Per-call filters the {@link IEmbedTool} list passed in via constructor
 * to drop {@code ISubTaskTool}/{@code IScheduleTool} (recursion guard) and
 * {@code IAskUserTool} (sub-tasks execute what the main task planned and return
 * results; questions for the user belong to the main conversation — #1 spec D6).
```

3c. `DefaultSubTaskTool.java` L40-42 @Tool description,现内容:

```java
    @Tool(description = "把一段任务委派给一个'子模型'去执行。子任务拥有与主对话相同的"
            + "工具访问(文件/MCP/Skill/时间等),但不能再次启动子任务或创建定时器。"
            + "主对话会同步等待子任务完成,然后拿到最终文本。")
```

替换为(不点名 askUser 工具,Global Constraints):

```java
    @Tool(description = "把一段任务委派给一个'子模型'去执行。子任务只做你规划好的任务执行"
            + "并返回执行结果:它拥有与主对话相同的工具访问(文件/MCP/Skill/时间等),"
            + "但不能再次启动子任务、创建定时器,也不能直接向用户提问 —— 需要用户决策的"
            + "疑问应写入委派指令的已知约束,或让子任务把疑问写进返回结果,由你收到结果后"
            + "决定是否向用户提问。主对话会同步等待子任务完成,然后拿到最终文本。")
```

3d. `ISubTaskTool.java` 接口 javadoc(L8-15)末尾 `</p>` 之前追加一段:

```java
 * <p>
 * Delegation contract (#1 spec D6): a sub-task only executes what the main task
 * planned and returns its result. It cannot ask the user questions — anything
 * needing user decision must be written into the sub-task's returned result, and
 * the main conversation decides whether to ask the user. Scheduled tasks run
 * through the same sub-task path and inherit this exclusion.
 * </p>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=DefaultSubTaskExecutorTest -Dgpg.skip=true`
Expected: PASS 4/4(既有 3 + 新增 1)

- [ ] **Step 5: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/subtask/ spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/subtask/DefaultSubTaskExecutorTest.java
git commit -m "feat: exclude askUser from sub-task tool schema + delegation-contract docs (#1 D6)"
```

---

### Task 6: 前端问题卡片(app.js + style.css)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
  - `ui` 对象附近(renderBotMessage L1160-1188 之后)加 `askUserCards` 模块
  - `chat.send` 的 onChunk(L1671-1689)加 `askUser` 分支
  - complete 回调(L1690-1702)/ error 回调(L1703-1714)/ `stopStream`(L1734-1767)加 `cancelAllActive`
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`(文件末尾追加卡片样式)

**Interfaces:**
- Consumes: T1 `AskUserEvent` JSON 形态(字段名逐字:questionId/question/header/background/options[{label,description}]/multiSelect/allowCustomInput/timeoutSeconds);T4 端点 `POST /spring/ai/loom/ask/{questionId}/answer`,body `{"answer": string | string[]}`,响应 200/400/404(spec §5 竞态:404 → 卡片显示"已失效")。
- Produces: 无后端依赖。全局模块 `askUserCards`(`render(ev)` / `cancelAllActive(reasonText)`),app.js 内部使用。

- [ ] **Step 1: app.js 加 askUserCards 模块**

在 `renderBotMessage(id) {...}`(L1160-1188)所属 `ui` 对象定义结束之后、`const chat = {`(L1610)之前,插入(与 `imageUpload` 等既有模块级 IIFE 并列):

```js
/**
 * #1 AskUser:LLM 提问卡片(聊天流内嵌,spec D1)。
 * 卡片仅活于当前流:提交/超时/取消后就地定格;刷新页面不重建(历史里只有文本)。
 */
const askUserCards = (() => {
  const active = new Map(); // questionId -> { el, timer, submitBtn, countdownEl }

  function fmtRemaining(sec) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${String(s).padStart(2, "0")}`;
  }

  function freeze(qid, stateText, ok) {
    const card = active.get(qid);
    if (!card) return;
    clearInterval(card.timer);
    active.delete(qid);
    card.el.classList.add("askuser-frozen");
    card.el.querySelectorAll("input,button").forEach((n) => (n.disabled = true));
    const badge = card.el.querySelector(".askuser-state");
    if (badge) {
      badge.textContent = stateText;
      badge.classList.toggle("askuser-state-ok", !!ok);
    }
  }

  async function submit(qid, ev) {
    const card = active.get(qid);
    if (!card) return;
    const inputs = card.el.querySelectorAll(".askuser-opt-input:checked");
    const labels = Array.from(inputs).map((n) => n.value);
    const customInput = card.el.querySelector(".askuser-custom-input");
    const customText = customInput ? customInput.value.trim() : "";
    if (customText) labels.push(customText);
    if (labels.length === 0) {
      showToast("请先选择一个选项或输入自定义答案", "error");
      return;
    }
    card.submitBtn.disabled = true;
    card.submitBtn.textContent = "提交中...";
    try {
      const r = await fetch(
        `/spring/ai/loom/ask/${encodeURIComponent(qid)}/answer`,
        {
          method: "POST",
          credentials: "include",
          headers: { "Content-Type": "application/json; charset=UTF-8" },
          body: JSON.stringify({ answer: ev.multiSelect ? labels : labels[0] }),
        },
      );
      if (r.ok) {
        freeze(qid, "已答 ✓", true);
      } else {
        // 404 = 已超时/已取消/已失效(spec §5 竞态行)
        freeze(qid, r.status === 404 ? "已失效(超时或已取消)" : "提交失败", false);
      }
    } catch (e) {
      freeze(qid, "提交失败:" + (e.message || "网络错误"), false);
    }
  }

  function render(ev) {
    if (!ev || !ev.questionId) return;
    const qid = ev.questionId;
    const inputType = ev.multiSelect ? "checkbox" : "radio";
    const optionsHtml = (ev.options || [])
      .map(
        (opt, i) => `
      <label class="askuser-opt">
        <input class="askuser-opt-input" type="${inputType}" name="askuser-${qid}" value="${escapeHtml(opt.label)}"/>
        <span class="askuser-opt-label">${escapeHtml(opt.label)}</span>
        ${opt.description ? `<span class="askuser-opt-desc">${escapeHtml(opt.description)}</span>` : ""}
      </label>`,
      )
      .join("");
    const customHtml = ev.allowCustomInput
      ? `<div class="askuser-custom">
          <label class="askuser-opt">
            <input class="askuser-opt-input" type="${ev.multiSelect ? "checkbox" : "radio"}" name="askuser-${qid}" value="" data-custom-trigger="1"/>
            <span class="askuser-opt-label">其他:</span>
          </label>
          <input class="askuser-custom-input" type="text" placeholder="输入自定义答案..." maxlength="500"/>
        </div>`
      : "";

    const item = document.createElement("div");
    item.className = "chat-item chat-item-left";
    item.innerHTML = `
      <div class="avatar"><img src="${aiImage}" alt="AI"/></div>
      <div class="bubble">
        <div class="askuser-card" id="askuser-${qid}">
          <div class="askuser-head">
            ${ev.header ? `<span class="askuser-header-chip">${escapeHtml(ev.header)}</span>` : ""}
            <span class="askuser-countdown">⏳ <span class="askuser-countdown-num"></span></span>
            <span class="askuser-state"></span>
          </div>
          <div class="askuser-question">${escapeHtml(ev.question)}</div>
          ${ev.background ? `<div class="askuser-background">${escapeHtml(ev.background)}</div>` : ""}
          <div class="askuser-options">${optionsHtml}${customHtml}</div>
          <button class="askuser-submit">提交答案</button>
        </div>
      </div>`;
    ui.mainContent.appendChild(item);
    ui.scrollToBottom();

    const el = item.querySelector(".askuser-card");
    const submitBtn = el.querySelector(".askuser-submit");
    const countdownEl = el.querySelector(".askuser-countdown-num");
    submitBtn.addEventListener("click", () => submit(qid, ev));
    // 单选时点选项文字也可提交(减少一次点击);多选保留显式提交
    if (!ev.multiSelect) {
      el.querySelectorAll(".askuser-opt-input").forEach((n) =>
        n.addEventListener("change", () => {
          const custom = el.querySelector(".askuser-custom-input");
          if (n.dataset.customTrigger && custom) {
            custom.focus(); // "其他"选项:聚焦输入框,等用户填完点提交
            return;
          }
          if (custom) custom.value = "";
          submit(qid, ev);
        }),
      );
    }

    // 本地倒计时(spec D2:与后端 timeoutSeconds 同值;归零仅置灰前端,
    // 后端超时以工具返回文本为准 —— 竞态窗口内提交会收到 404 → "已失效")
    let remaining = Number(ev.timeoutSeconds) || 300;
    countdownEl.textContent = fmtRemaining(remaining);
    const timer = setInterval(() => {
      remaining -= 1;
      if (remaining <= 0) {
        freeze(qid, "已超时", false);
        return;
      }
      countdownEl.textContent = fmtRemaining(remaining);
    }, 1000);

    active.set(qid, { el, timer, submitBtn, countdownEl });
  }

  /** 流结束(complete/error/stop)时把仍在等待的卡片定格。 */
  function cancelAllActive(reasonText) {
    for (const qid of Array.from(active.keys())) {
      freeze(qid, reasonText, false);
    }
  }

  return { render, cancelAllActive };
})();
```

- [ ] **Step 2: onChunk 加 askUser 分支**

L1671-1689 的 onChunk 回调内,`if (data.reasoningContent)` 分支之前插入:

```js
          // #1 AskUser:提问卡片事件帧(按字段分派,普通内容帧不受影响)
          if (data.askUser) {
            askUserCards.render(data.askUser);
          }
```

- [ ] **Step 3: 流终态定格卡片**

3a. complete 回调(L1690-1702)首行加:

```js
          askUserCards.cancelAllActive("已结束");
```

3b. error 回调(L1703-1714)首行加:

```js
          askUserCards.cancelAllActive("已取消");
```

3c. `stopStream()`(L1734)的 `finally` 块内、`ui.setStopButtonVisible(false);` 之前加:

```js
      askUserCards.cancelAllActive("已取消");
```

- [ ] **Step 4: style.css 追加卡片样式(文件末尾)**

```css
/* ===== #1 AskUser 提问卡片 ===== */
.askuser-card { margin: 12px 16px; min-width: 320px; max-width: 560px; }
.askuser-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.askuser-header-chip {
  background: var(--primary, #6366f1); color: #fff; border-radius: 10px;
  padding: 2px 10px; font-size: 12px; font-weight: 600;
}
.askuser-countdown { margin-left: auto; font-size: 12px; color: var(--text-secondary, #64748b); }
.askuser-state { font-size: 12px; color: var(--text-secondary, #64748b); }
.askuser-state-ok { color: var(--success-color, #22c55e); font-weight: 600; }
.askuser-question { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.askuser-background { font-size: 12px; color: var(--text-secondary, #64748b); margin-bottom: 8px; }
.askuser-options { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
.askuser-opt {
  display: flex; align-items: baseline; gap: 8px; padding: 8px 10px;
  border: 1px solid var(--border-color, #e2e8f0); border-radius: 8px; cursor: pointer;
  transition: border-color .15s, background .15s;
}
.askuser-opt:hover { border-color: var(--primary, #6366f1); background: rgba(99,102,241,.04); }
.askuser-opt-label { font-size: 13px; font-weight: 500; }
.askuser-opt-desc { font-size: 12px; color: var(--text-secondary, #64748b); }
.askuser-custom { display: flex; flex-direction: column; gap: 6px; }
.askuser-custom-input {
  border: 1px solid var(--border-color, #e2e8f0); border-radius: 8px;
  padding: 7px 10px; font-size: 13px; width: 100%; box-sizing: border-box;
}
.askuser-submit {
  background: var(--primary, #6366f1); color: #fff; border: none; border-radius: 8px;
  padding: 7px 18px; font-size: 13px; cursor: pointer;
}
.askuser-submit:disabled { opacity: .5; cursor: not-allowed; }
.askuser-frozen { opacity: .65; }
.askuser-frozen .askuser-opt { cursor: default; }
.askuser-frozen .askuser-opt:hover { border-color: var(--border-color, #e2e8f0); background: none; }
```

- [ ] **Step 5: 语法验证**

Run: `node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
Expected: 无输出(exit 0;app.js 是 ES module,`--check` 按 module 解析 import 语法)

若 `node --check` 因 import 语句报 "Cannot use import statement outside a module",改用:
Run: `node --input-type=module --check < spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
Expected: exit 0

- [ ] **Step 6: install + 启动测试应用 + Chrome 手动复验(spec §6)**

```bash
mvn clean install -Dgpg.skip=true -DskipTests
mvn spring-boot:run -pl spring-ai-loom-agent-test
```

复验清单(逐项过,任何一项失败回到对应 Task 修):
1. **happy path**:对话输入"我想部署一个应用,请先问我用 Docker 还是 java -jar,给我选项卡片" → 卡片出现在流内(header chip/问题/选项/倒计时)→ 点选 → 卡片变"已答 ✓" → **LLM 同流继续**输出基于所选答案的内容。
2. **多选+自定义**:诱导 LLM 提 multiSelect=true + allowCustomInput=true 的问题 → 勾 2 项 + 填自定义 → 提交 → LLM 收到 "A; B; 自定义文本"。
3. **超时**:test 模块 `application.yml` 临时加 `spring.ai.loom.agent.askuser.timeoutSeconds: 20` → 提问后不答 → 20s 后卡片置灰"已超时",LLM 收到"用户未作答"继续;**刷新页面后本轮对话完整保留**(ChatMemory ON_COMPLETE 验证);复验完删除临时配置。
4. **stop**:提问挂起时点停止按钮 → 卡片变"已取消";后端日志出现 `askUser cancelAll`;无线程悬挂(再次提问正常)。
5. **D9 线程验证**:工具阻塞期间(提问挂起时)观察该流是否还能收到 LLM 已产出的内容帧;新开第二轮对话确认不卡顿。若主流明显卡顿 → 启用 Global Constraints 里的 boundedElastic 应急预案(改 `DefaultAskUserTool` 阻塞段,补单测,单独 `fix:` commit)。
6. **普通帧回归**:不带提问的普通对话流式输出/思考过程展示与改动前一致(askUser=null 帧不受影响)。

- [ ] **Step 7: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css
git commit -m "feat: inline askUser question card (radio/checkbox/custom input, countdown, freeze states)"
```

---

### Task 7: 文档同步 + 回归门 + 概览图 + roadmap 落地记录

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`、`README.zh-CN.md`
- Modify: `docs/API.md`、`docs/API.zh-CN.md`
- Modify: `docs/TOOLS.md`、`docs/TOOLS.zh-CN.md`
- Modify: `docs/superpowers/roadmap-2026-09-four-items.md`
- Modify: `.claude/skills/project-overview-image/generate.py`(EN_LAYOUT/ZH_LAYOUT 工具组胶囊 +askUser;**图生成本身需用户确认 + DASHSCOPE_WORKSPACE_ID/DASHSCOPE_API_KEY,提醒用户后执行**)

**Interfaces:**
- Consumes: T1-T6 的 commit SHA(`git log --oneline -8` 取,填 roadmap 落地记录);T6 Step 6 回归门数字。
- Produces: 无(终端任务)。

- [ ] **Step 1: CLAUDE.md 五处**

1a. 核心接口表(`IScheduleTool` 行之后)加行:

```markdown
| `IAskUserTool` | `DefaultAskUserTool` | LLM-callable `askUser(question, header, background, optionsJson, multiSelect, allowCustomInput)` — 聊天流内嵌选择卡片向当前用户提问,工具方法阻塞等待作答(默认 `askuser.timeoutSeconds=300`),答案以 tool_result 回同一条流。超时/stop 返回"用户未作答"文本保 ChatMemory ON_COMPLETE。默认 enabled(universal) |
```

1b. `ISubTaskExecutor` 行的 "tools filtered to exclude self-tools (no `ISubTaskTool`/`IScheduleTool`)" 改为 "(no `ISubTaskTool`/`IScheduleTool`/`IAskUserTool` — 子任务不能向用户提问,疑问写进执行结果由主任务决定)"。

1c. Capability 模型 "Universal 工具" 表加行:

```markdown
| `IAskUserTool` | `tool_askUser` | 仅向当前流内的本人提问,答案回同一流,无越权风险;子任务/定时任务 schema 级排除 |
```

同时把该节文字里的 "6 个 universal 工具"/"9 个 I*Tool" 计数改为 7 / 10(`ToolConfiguration` 行 "9 个 I*Tool bean 总是创建" → 10 个)。

1d. Configuration Properties 节加行:

```markdown
- `askuser` — `timeoutSeconds`(default 300):askUser 工具阻塞等待用户作答的最长秒数;超时返回"用户未作答"文本,Flux 正常 complete
```

1e. Data Layer / schema 无变化 —— 不加任何 schema 描述(Registry 纯内存)。

- [ ] **Step 2: README×2**

功能特性段与工具列表各加 askUser 一条(中文 README.zh-CN / 英文 README 对应语言):"AskUser 交互工具 — AI 在聊天流中以选项卡片向你提问(单选/多选/自定义输入),作答后无缝继续;子任务与定时任务不会打扰你"。工具计数文案(若有"9 个工具组")同步 10。

- [ ] **Step 3: API×2**

3a. SSE 帧结构节:`ChatResponseRecord` 说明补第 3 字段 `askUser`(null=普通帧;非空=提问卡片事件,字段表照 `AskUserEvent` 8 组件)。

3b. 新增小节 "AskUser answer endpoint":

```markdown
### POST /spring/ai/loom/ask/{questionId}/answer

提交问题卡片的答案(askUser 工具阻塞等待中)。

- 请求体:`{"answer": "选项label"}`(单选/自定义)或 `{"answer": ["label1","label2"]}`(多选)
- `200 {"ok":true}` — 答案已送达,工具线程被唤醒
- `400 {"error":"invalid answer"}` — body 非法 / answer 缺失或空白
- `404 {"error":"not found"}` — 未知 questionId、跨用户提交、或问题已超时/已取消(三者同响应,防存在性泄露)
```

- [ ] **Step 4: TOOLS×2**

新增 askUser 工具章节:参数表(question/header/background/optionsJson/multiSelect/allowCustomInput)、卡片形态、返回文本契约("[用户已回答]/[用户未作答]/[提问失败]")、超时与 stop 行为、**子任务/定时任务排除契约**(引用 ISubTaskTool javadoc 措辞)。

- [ ] **Step 5: 回归门三段式(全绿才能进 Step 6)**

```bash
# ① 库单元(151 基线 + 本计划新增 ≈ 21)
mvn test -pl spring-ai-loom-agent -Dgpg.skip=true
# ② install
mvn clean install -Dgpg.skip=true -DskipTests
# ③ test 模块单元(382 基线 + 新增 ≈ 10)
mvn test -pl spring-ai-loom-agent-test -Dgpg.skip=true
# ④ IT gate(123 基线;清库起跑 —— CLAUDE.md verification gotcha)
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip=true
```

Expected: ①②③④ 全部 0 failures(④ 允许 3 skipped = 既有 Maven 工具条件跳过)。记录实际数字备 Step 6。

- [ ] **Step 6: roadmap 落地记录**

6a. 总排期表:`| 4 | #1 AskUser 交互工具 | 🔵 | 大 |` → `| 4 | #1 AskUser 交互工具 | ✅ 完成(2026-09-08) | 大 |`;"末"行状态按 Step 7 结果更新。

6b. `## #1 AskUser 交互工具(🔵)` → `(✅ 完成 2026-09-08)`;"候选路径"小节后追加:

```markdown
### 落地记录(2026-09-08)

- **最终形态**:路径 1 阻塞同流落地。新包 `askuser`:IAskUserTool(@ToolGroup universal)+ DefaultAskUserTool(future.get 阻塞,timeoutSeconds 可注入)+ AskUserRegistry(纯内存);ChatResponseRecord 第 3 组件 askUser(2-arg 兼容构造器);`POST /ask/{questionId}/answer`(跨用户/未知/已失效统一 404);stop 路径 cancelAll 哨兵释放阻塞线程;子任务/定时任务 schema 级排除(DefaultSubTaskExecutor 过滤器 + 委派契约文案);前端内嵌卡片(单选即点即交/多选显式提交/自定义输入/倒计时/已答·已超时·已取消定格)。
- **Commits(按 Task 序)**:T1 `<sha>` / T2 `<sha>` / T3 `<sha>` / T4 `<sha>` / T5 `<sha>` / T6 `<sha>` / T7 本 commit。(实施时 `git log --oneline` 回填)
- **与 spec 偏差**:<实施中如实记录;无则写"无">
- **回归门**(<日期>):库单元 <N>/0;test 模块单元 <N>/0;IT gate <N>/0/3-skip。(Step 5 实测数字回填)
- **D9 线程预案**:Chrome 复验<通过/启用了 boundedElastic 兜底>。
- **概览图**:工具组 9→10,<已重生成/待用户确认重生成>。
```

- [ ] **Step 7: 概览图重生成(需用户参与)**

**提醒用户**:"#1 已让工具组 9→10,按 roadmap 约定现在需要重生成 `docs/project-overview-{en,zh}.png`(需 `DASHSCOPE_WORKSPACE_ID` + `DASHSCOPE_API_KEY` 环境变量)。"

7a. 编辑 `.claude/skills/project-overview-image/generate.py` 的 `EN_LAYOUT` / `ZH_LAYOUT`:工具组胶囊区加 askUser(措辞镜像既有胶囊,如 EN "AskUser" / ZH "用户提问");若布局含工具组计数文案(如 "9 tool groups")同步改 10。

7b. 用户确认环境变量就绪后,按该 skill 的 SKILL.md 触发流程运行 `generate.py` 重生成两张 PNG;成功后 `git add docs/project-overview-*.png .claude/skills/project-overview-image/generate.py`。若用户暂不重生成,roadmap "末"行保持 🔵 并注明"待用户确认",本 Task 其余部分照常收尾。

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md README.md README.zh-CN.md docs/ docs/superpowers/roadmap-2026-09-four-items.md
git commit -m "docs: #1 AskUser landing — CLAUDE/README×2/API×2/TOOLS×2/roadmap (+ overview image if regenerated)"
```

(若 7b 生成了 PNG,把 PNG 与 generate.py 一并 add 进本 commit。)

---

## Self-Review 记录(plan 作者已跑,实施者无需重复)

1. **Spec 覆盖**:D1 卡片形态→T6;D2 超时→T3(timeoutSeconds 注入 + TimeoutException 分支)+T6 倒计时;D3 单问题→T3(一次一问,无循环);D4 字段→T1 AskUserEvent 8 组件;D5 路径 1→T3 阻塞 + T4 端点;D6 子任务排除→T5(过滤器+javadoc+@Tool 文案+回归测试);D7 stop→T4 Step 4;D8 鉴权 404→T2 answer + T4 router + 测试;D9 线程→Global Constraints 应急预案 + T6 复验项 5;§5 错误矩阵 11 行全部映射(超时/stop/竞态×2/校验/跨用户/流不可用/子任务/连续提问[单流同步天然串行]/answer 非法/重启);§6 测试计划→T1(4)+T2(7)+T3(10+1)+T4(8)+T5(1)+T6(手动 6 项)+T7(回归门);§7 文档→T7;§8 顺序=T1-T7。**无缺口**。spec §6 说的"接线 IT"以 AskUserRouterTest(真 router+真 Registry,无 DB 依赖)等价交付——偏离已在该类 javadoc 注明,理由:answer 端点零 DB 交互,AdminRouterSpotTest 先例同形态。
2. **占位符扫描**:无 TBD/TODO;"similar to Task N" 零处;每个代码步骤均给出完整代码;文档步骤给出逐字插入内容与精确锚点(行号以 2026-09-08 HEAD `9c362c0` 为准,实施时以内容锚点优先、行号辅助)。
3. **类型一致性**:`AskUserEvent` 8 组件在 T1 定义,T2 测试/T3 工具构造/T4 测试/T6 前端字段(questionId/question/header/background/options/multiSelect/allowCustomInput/timeoutSeconds)逐字段核对一致;`PendingQuestion` 5 组件 T2 定义、T3/T4 消费一致;`CANCELLED_SENTINEL` T2 定义、T3 识别、T4 测试断言一致;`answer(questionId, username, answerText)→boolean` T2 定义、T4 router 消费一致;`cancelAll(username, conversationId)→int` T2 定义、T4 SseController 消费一致;工具返回文本契约 T3 Produces 声明、T3 测试断言、T7 文档引用三处一致;`DefaultAskUserTool(AskUserRegistry, SseEmitterRegistry, long)` 构造器 T3 定义、T3 bean 注册消费一致。
