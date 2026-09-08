# AskUser 四项后续调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec 四节:§3 admin 可分配角色(前端解锁)→ §1 askUser 卡片终态折叠成一行摘要 → §2 日志页展示提问记录(只读查询 + UI)→ §4 V1.0 种子两个官方一问一答技能(STAR-IJ / 靶心人公式)。

**Architecture:** §1/§3 纯前端(app.js / console.js);§2 后端只读(接口 + JdbcTemplate 实现 + RouterFunction,复用既有 `loom_tool_call_log` 数据,零新表)+ stats 页区块;§4 纯 SQL 种子(V1.0 尾部幂等 INSERT...WHERE NOT EXISTS)。四节相互独立,按依赖最轻先落。

**Tech Stack:** Spring Boot 3.x / Spring AI 1.1.8 / JDK 17 / H2 + Flyway(单 V1.0 fresh-DB)/ 原生 JS 前端(无框架构建)/ JUnit5 + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-08-askuser-followups-design.md`(决策记录 D1-D8;本 plan 与 spec 冲突时以 spec 为准)

## Global Constraints

- **回归基线**:库单元 `mvn test -pl spring-ai-loom-agent` = **175** run / 0 fail;test 模块 `mvn test -pl spring-ai-loom-agent-test` = **397** run / 0 fail;IT gate = **123** run / 0 fail / 3 skip。每个 Task 落地后至少跑受影响模块;T6 跑全量三段。
- **IT gate 跑法**(项目无 failsafe,IT 类混在 surefire 里):先清库 `rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports`,再 `mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false`。
- **install 命令**(Windows 文件锁,4 个 MCP 模块运行中被锁,既定先例):`mvn clean install -DskipTests -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'`。
- **fresh-DB 政策**:改 V1.0 后,任何已运行实例必须 `rm -rf ~/.loom/datasource` 清库重启;不接受增量迁移。
- **CSS token**:只准用 `:root` 已定义的真实 token(`--primary-color` / `--border-color` / `--text-secondary` / `--success-color` 等);**禁止** `var(--primary,`(契约测试锁死)。
- **前端安全**:所有 LLM/用户来源文本渲染必须走 `escapeHtml`(app.js / stats.js 已有同名函数);`textContent` 赋值天然安全。fetch 一律 `credentials: "include"`,POST/PUT 带 `Content-Type: application/json; charset=UTF-8`。
- **admin 门禁**:`/spring/ai/loom/admin/**` 由 `AuthenticationFilter` + `adminPathPatterns`(默认 `List.of("/spring/ai/loom/admin/**")`)自动 admin-only —— §2 新路由挂在 `spring/ai/loom/admin/ask-logs` 路径下即自动受门禁,**不得**另写 admin 校验代码。
- **技能内容三铁律**(项目 memory):① 不硬编码工具名(内容里**不得出现 `askUser` 字面量**,只描述"提问能力");② 防 qwen 自白死循环(必须含"不要描述你打算做什么"+"每次提问后必须等待用户真实回答,不得代替用户作答"+"信息足够时立即汇总");③ 纯文本(不得含裸 HTML 标签如 `<a href>`,链接用 `[text](URL)`)。
- **commit 前缀**:`feat:` / `fix:` / `refactor:` / `docs:` / `test:`,一 Task 一 commit。
- **不改动范围**(spec §0):`loomAgentProperties` 其它漏 setter、answer 长度上限、多标签页同步、i18n、概览图 PNG —— 一律不碰。

---

### Task 1: §3 admin 可被分配角色(前端解锁 + @Deprecated)

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/console.js`(openAssignRole,约 L401-417)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/rbac/IRoleService.java`(L22 声明)
- Modify: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/rbac/DefaultRoleService.java`(L108 实现)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/rbac/SetUserRolesOrSkipAdminDeprecatedTest.java`(新建)

**Interfaces:**
- Consumes: 既有 `PUT /spring/ai/loom/admin/users/{username}/roles`(console.js L472 已在调,后端零改动)
- Produces: 无(纯行为解锁;后续 Task 不依赖本 Task 产物)

- [ ] **Step 1: 写失败测试(反射断言 @Deprecated)**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/rbac/SetUserRolesOrSkipAdminDeprecatedTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.rbac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3(spec 2026-09-08-askuser-followups-design.md):
 * setUserRolesOrSkipAdmin 的名字是历史残留 —— M5 删除 admin 短路后行为等同
 * setUserRoles。admin 现已可在控制台被分配角色(strict RBAC 闭环)。
 * 该方法标记 @Deprecated,下一 minor 版本删除(T3 v1 shim policy:1 minor version)。
 * 本测试锁死注解存在,防止误删注解或误删方法时无人察觉。
 */
@DisplayName("setUserRolesOrSkipAdmin @Deprecated 契约")
class SetUserRolesOrSkipAdminDeprecatedTest {

    @Test
    void interfaceMethodIsDeprecated() throws NoSuchMethodException {
        Method m = IRoleService.class.getMethod("setUserRolesOrSkipAdmin", String.class, List.class);
        assertThat(m.isAnnotationPresent(Deprecated.class))
                .as("IRoleService.setUserRolesOrSkipAdmin 必须标 @Deprecated(名字误导,行为等同 setUserRoles)")
                .isTrue();
    }

    @Test
    void implMethodIsDeprecated() throws NoSuchMethodException {
        Method m = DefaultRoleService.class.getMethod("setUserRolesOrSkipAdmin", String.class, List.class);
        assertThat(m.isAnnotationPresent(Deprecated.class))
                .as("DefaultRoleService.setUserRolesOrSkipAdmin 必须标 @Deprecated")
                .isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SetUserRolesOrSkipAdminDeprecatedTest`
Expected: 2 FAIL("必须标 @Deprecated" 断言失败 —— 注解尚未加)

- [ ] **Step 3: 加 @Deprecated + javadoc**

`IRoleService.java` L22 声明改为(保留原有 javadoc,追加):

```java
    /**
     * 历史残留名字:M5 起 admin 不再被跳过,行为完全等同 {@link #setUserRoles(String, List)}。
     * §3(2026-09-08):admin 现可在控制台被分配角色(strict RBAC,admin 的 MCP/工具同样按角色过滤)。
     *
     * @deprecated 名字误导(并不 skip admin);请直接调用 {@link #setUserRoles(String, List)}。
     *             下一 minor 版本删除。
     */
    @Deprecated
    void setUserRolesOrSkipAdmin(String username, List<String> roleCodes);
```

`DefaultRoleService.java` 的实现方法(L108 附近,保留既有 javadoc 与 findUserType 校验)加注解:

```java
    @Deprecated
    @Override
    public void setUserRolesOrSkipAdmin(String username, List<String> roleCodes) {
```

- [ ] **Step 4: 删 console.js 的 ADMIN early-return**

`console.js` `openAssignRole` 中,把这一段(L404-417):

```js
    if (type === "ADMIN") {
      assignHint.textContent =
        "管理员账号默认拥有全部 MCP 服务，无需分配角色。";
      assignList.innerHTML = "";
      assignSave.style.display = "none";
      assignModal.style.display = "flex";
      return;
    }
    assignHint.textContent =
      "勾选要分配给该用户的角色（可多选）。用户实际可用的 MCP = 所有已选角色授权 MCP 的并集。";
```

整段替换为:

```js
    // §3: admin 也走 strict RBAC(M3 起无 bypass,admin 的 MCP/工具按角色过滤)——
    // 与普通用户完全相同的分配流程;旧版这里对 ADMIN early-return 并显示
    // "管理员默认拥有全部 MCP 服务"的过时文案,已删除。
    assignHint.textContent =
      "勾选要分配给该用户的角色（可多选）。用户实际可用的 MCP = 所有已选角色授权 MCP 的并集。" +
      (type === "ADMIN"
        ? "管理员同样受角色授权约束（strict RBAC），未分配角色时仅平台默认能力（universal 工具）可用。"
        : "");
```

后续的 `assignSave.style.display = ""` / 加载角色列表 / `renderAssignRoleList` 保持原样(现在 admin 也会走到)。

- [ ] **Step 5: 跑测试确认通过 + console.js 语法检查**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SetUserRolesOrSkipAdminDeprecatedTest`
Expected: 2 PASS

Run: `node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/console.js`
Expected: 无输出(语法 OK;若环境无 node,跳过 —— Chrome 复验在 T6)

Run: `mvn test -pl spring-ai-loom-agent-test` 并确认无 @Deprecated 引起的新告警失败
Expected: **398** run(397+1 新类 2 用例 = 399?以实际为准:397 + 2 = **399**), 0 fail

- [ ] **Step 6: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/console.js \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/rbac/IRoleService.java \
        spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/rbac/DefaultRoleService.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/rbac/SetUserRolesOrSkipAdminDeprecatedTest.java
git commit -m "feat: admin 用户可被分配角色(删 console.js ADMIN early-return + setUserRolesOrSkipAdmin @Deprecated)"
```

---

### Task 2: §1 askUser 卡片终态折叠成一行摘要

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`(askUserCards IIFE,L1613-1780)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css`(`/* ===== #1 AskUser 提问卡片 ===== */` 块末尾追加)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/ui/AskUserCardCollapseContractTest.java`(新建)

**Interfaces:**
- Consumes: 既有 `freeze(qid, stateText, ok)` 及其 4 个调用点(L1678 已答 / L1681 已失效 / L1760 已超时 / L1772 cancelAllActive 转发);`restorePending`(**不得**调 freeze —— 可重试失败卡片保持交互,spec D8)
- Produces: `freeze(qid, stateText, ok, answerText)` 4 参签名;DOM 结构 `.askuser-wrap > (.askuser-summary + .askuser-card)`;Task 4 的日志页徽章语义复用本 Task 的状态文案(已答/已超时/已取消/已失效)

- [ ] **Step 1: 写失败契约测试**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/ui/AskUserCardCollapseContractTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §1 卡片折叠契约(spec 2026-09-08-askuser-followups-design.md):
 * askUser 卡片终态必须折叠成一行摘要(点击展开回看),而不是整卡常驻对话流。
 * 镜像 AskUserCardStyleContractTest 的静态读文件断言风格。
 */
class AskUserCardCollapseContractTest {

    private String read(String resource) throws IOException {
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("resource must exist: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String appJs() throws IOException {
        return read("/META-INF/resources/spring/ai/loom/app.js");
    }

    private String styleCss() throws IOException {
        return read("/META-INF/resources/spring/ai/loom/style.css");
    }

    @Test
    void freezeTakesAnswerTextParam() throws IOException {
        // 4 参签名:已答路径传用户答案进摘要行;其余终态传 null/省略
        assertThat(appJs()).contains("function freeze(qid, stateText, ok, answerText)");
    }

    @Test
    void summaryDomStructureExists() throws IOException {
        String js = appJs();
        assertThat(js).contains("askuser-wrap");
        assertThat(js).contains("askuser-summary");
        // 提交成功路径必须把答案传进 freeze(摘要行显示 问题 → 答案)
        assertThat(js).contains("freeze(qid, \"已答 ✓\", true,");
    }

    @Test
    void summaryCssExistsAndUsesRealTokens() throws IOException {
        String css = styleCss();
        int idx = css.indexOf("/* ===== #1 AskUser 提问卡片 ===== */");
        assertThat(idx).isGreaterThan(0);
        String block = css.substring(idx);
        assertThat(block).contains(".askuser-summary {");
        assertThat(block).contains("text-overflow: ellipsis");
        // 真实 token(不得再出现未定义 --primary;既有 AskUserCardStyleContractTest 全文锁死,
        // 这里锁摘要行用了 success/secondary/border token)
        assertThat(block).contains("var(--success-color");
        assertThat(block).doesNotContain("var(--primary,");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserCardCollapseContractTest`
Expected: 3 FAIL(freeze 仍是 3 参 / 无 askuser-wrap / 无 .askuser-summary CSS)

- [ ] **Step 3: 改 app.js — freeze 4 参 + 折叠逻辑**

把 `function freeze(qid, stateText, ok) {` 整个函数体(L1622-1639)替换为:

```js
  // §1 终态折叠:freeze 后卡片隐藏,显示一行摘要(点击 toggle 展开回看)。
  // answerText 仅"已答"路径传(提交成功时的答案);其余终态传 null。
  // restorePending(可重试失败)不调用本函数 —— 卡片保持可交互(spec D8)。
  function freeze(qid, stateText, ok, answerText) {
    const card = active.get(qid);
    if (!card) return;
    clearInterval(card.timer);
    active.delete(qid);
    card.el.classList.add("askuser-frozen");
    card.el.querySelectorAll("input,button").forEach((n) => (n.disabled = true));
    // 终态隐藏提交按钮:否则按钮会永远停在"提交中..."(submit 在 fetch 前设的
    // 在飞标签,freeze 只 disable 不复位)—— 看起来像卡死。终态语义由徽章表达
    // (已答 ✓ / 已超时 / 已取消 / 已失效),四种终态共用本函数,无单一合适按钮文案。
    if (card.submitBtn) card.submitBtn.style.display = "none";
    const badge = card.el.querySelector(".askuser-state");
    if (badge) {
      badge.textContent = stateText;
      badge.classList.toggle("askuser-state-ok", !!ok);
    }
    // 折叠成摘要行(问题文本来自卡片 DOM 的 textContent —— 天然已转义;
    // 摘要行用 textContent 赋值,LLM 问题/用户答案均不可信,不得 innerHTML)
    const summary = card.summaryEl;
    if (summary) {
      const qEl = card.el.querySelector(".askuser-question");
      const question = qEl ? qEl.textContent : "";
      const icon = ok ? "✓" : stateText === "已超时" ? "⏳" : "✗";
      const tail = ok
        ? answerText || ""
        : stateText === "已超时"
          ? "已超时，未作答"
          : stateText === "已失效(超时或已取消)"
            ? "已失效"
            : stateText;
      const textEl = summary.querySelector(".askuser-summary-text");
      if (textEl) textEl.textContent = `${icon} ${question} → ${tail}`;
      summary.classList.toggle("askuser-summary-ok", !!ok);
      card.el.style.display = "none";
      summary.style.display = "flex";
    }
  }
```

- [ ] **Step 4: 改 app.js — submit 成功路径传答案**

`submit()` 内(L1677-1678):

```js
      if (r.ok) {
        freeze(qid, "已答 ✓", true);
```

改为:

```js
      if (r.ok) {
        freeze(qid, "已答 ✓", true, vals.join("、"));
```

(`vals` 是 submit 内已 trim+filter 的最终答案数组;多选用 `、` join —— spec §1.2)

其余 3 个 freeze 调用点(L1681 已失效 / L1760 已超时 / L1772 cancelAllActive)**不改** —— 第 4 参缺省 undefined,摘要走状态文案分支。

- [ ] **Step 5: 改 app.js — render() 加 wrap + summary DOM + 展开交互**

render() 内 `item.innerHTML = ...` 的 bubble 部分(L1717-1733),把:

```js
      <div class="bubble">
        <div class="askuser-card" id="askuser-${qid}">
```

改为:

```js
      <div class="bubble">
        <div class="askuser-wrap">
        <div class="askuser-summary" style="display: none;">
          <span class="askuser-summary-text"></span><span class="askuser-summary-arrow">▸</span>
        </div>
        <div class="askuser-card" id="askuser-${qid}">
```

并把该模板串中卡片闭合处(原 `</div>\n      </div>` 前的结构,即 `<button class="askuser-submit">提交答案</button>` 之后):

```js
          <button class="askuser-submit">提交答案</button>
        </div>
      </div>`;
```

改为(多闭合一层 `.askuser-wrap`):

```js
          <button class="askuser-submit">提交答案</button>
        </div>
        </div>
      </div>`;
```

然后在 `const el = item.querySelector(".askuser-card");` 之后(L1734 附近)加:

```js
    const summaryEl = item.querySelector(".askuser-summary");
    // 摘要行点击 toggle 展开/收起完整卡片(终态只读回看;卡片保持冻结态)
    summaryEl.addEventListener("click", () => {
      const cardHidden = el.style.display === "none";
      el.style.display = cardHidden ? "" : "none";
      summaryEl.querySelector(".askuser-summary-arrow").textContent = cardHidden ? "▾" : "▸";
    });
```

并把注册行 `active.set(qid, { el, timer, submitBtn, countdownEl });` 改为:

```js
    active.set(qid, { el, timer, submitBtn, countdownEl, summaryEl });
```

- [ ] **Step 6: 改 style.css — 摘要行样式**

在 `/* ===== #1 AskUser 提问卡片 ===== */` 块末尾(`.askuser-frozen .askuser-opt:hover` 规则之后)追加:

```css
/* §1 终态折叠摘要行(点击展开回看完整卡片) */
.askuser-wrap { min-width: 0; }
.askuser-summary {
  display: none; align-items: center; gap: 8px; margin: 12px 16px;
  max-width: 560px; min-width: 0; box-sizing: border-box;
  padding: 8px 12px; border: 1px solid var(--border-color, #e2e8f0); border-radius: 8px;
  font-size: 13px; color: var(--text-secondary, #64748b); cursor: pointer;
  transition: border-color .15s;
}
.askuser-summary:hover { border-color: var(--primary-color, #6366f1); }
.askuser-summary-ok { color: var(--success-color, #22c55e); }
.askuser-summary-text {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.askuser-summary-arrow { flex: none; font-size: 12px; }
```

- [ ] **Step 7: 跑测试确认通过 + 全套前端契约回归**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest='AskUserCardCollapseContractTest,AskUserCardStyleContractTest'`
Expected: 6 PASS(新 3 + 既有 3 —— 既有样式契约不得被破坏)

Run: `node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js`
Expected: 无输出

Run: `mvn test -pl spring-ai-loom-agent-test`
Expected: **402** run(399 + 3), 0 fail

- [ ] **Step 8: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/app.js \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/style.css \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/ui/AskUserCardCollapseContractTest.java
git commit -m "feat: askUser 卡片终态折叠成一行摘要(点击展开回看,答案/状态入摘要,escapeHtml 安全)"
```

---

### Task 3: §2 后端 — 提问日志只读查询 + admin 路由

**Files:**
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserLogRecord.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/IAskUserLogQuery.java`
- Create: `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/JdbcAskUserLogQuery.java`
- Modify: `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java`(ToolConfiguration 加 bean,在 `askUserRegistry()`/`defaultAskUserTool()` 旁,约 L876-895;WebConfiguration 加 router bean,在 `loomAgentAskRouter` 旁,约 L1775-1815)
- Test: `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserLogParsingTest.java`(新建,lib 模块纯单元)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/AskUserLogRouterTest.java`(新建)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/JdbcAskUserLogQueryIT.java`(新建)

**Interfaces:**
- Consumes: 既有表 `loom_tool_call_log`(DDL 见 V1.0;`LoggingToolCallback` 已把所有 askUser 调用写入,`tool_name='askUser'`);`DefaultAskUserTool` 的返回前缀(逐字):`[用户已回答] ` / `[用户未作答] 用户未在 N 分钟内作答...` / `[用户未作答] 用户已停止本次对话...` / `[提问被中断] ` / `[提问失败] `
- Produces(Task 4 前端依赖):
  - `record AskUserLogRecord(long logId, String conversationId, String username, String question, String header, String answerText, String status, long durationMs, java.time.Instant createdAt)`
  - `interface IAskUserLogQuery { List<AskUserLogRecord> recent(int limit, String usernameOrNull); }`
  - status 取值:`ANSWERED` / `TIMEOUT` / `CANCELLED` / `FAILED` / `UNKNOWN`
  - `GET spring/ai/loom/admin/ask-logs?limit=&username=` → JSON 数组(字段名 = record 组件名)

- [ ] **Step 1: 写失败单元测试(状态推导 + 参数解析,纯静态无 DB)**

新建 `spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/AskUserLogParsingTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 提问日志解析契约(spec 2026-09-08-askuser-followups-design.md):
 * status 从 result_text 前缀推导(前缀逐字对齐 DefaultAskUserTool 返回值),
 * question/header 从 arguments_json 解析,任何畸形输入不得抛异常。
 */
@DisplayName("askUser 日志解析")
class AskUserLogParsingTest {

    @Test
    void answeredPrefixMapsToAnswered() {
        assertThat(JdbcAskUserLogQuery.deriveStatus("[用户已回答] 选项A")).isEqualTo("ANSWERED");
    }

    @Test
    void timeoutPrefixMapsToTimeout() {
        assertThat(JdbcAskUserLogQuery.deriveStatus(
                "[用户未作答] 用户未在 5 分钟内作答。请基于现有信息自行合理决策并继续,或改用其他方式推进。"))
                .isEqualTo("TIMEOUT");
    }

    @Test
    void stopCancelledMapsToCancelled() {
        // 同为 [用户未作答] 前缀,靠正文"已停止"细分(spec §2.2 status 表)
        assertThat(JdbcAskUserLogQuery.deriveStatus("[用户未作答] 用户已停止本次对话,未作答。"))
                .isEqualTo("CANCELLED");
        assertThat(JdbcAskUserLogQuery.deriveStatus("[提问被中断] 等待用户作答时被中断,未获得答案。"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void failedPrefixMapsToFailed() {
        assertThat(JdbcAskUserLogQuery.deriveStatus("[提问失败] question 不能为空,请提供要问用户的问题文本后重试。"))
                .isEqualTo("FAILED");
    }

    @Test
    void nullOrUnknownResultTextMapsToUnknown() {
        assertThat(JdbcAskUserLogQuery.deriveStatus(null)).isEqualTo("UNKNOWN");
        assertThat(JdbcAskUserLogQuery.deriveStatus("")).isEqualTo("UNKNOWN");
        assertThat(JdbcAskUserLogQuery.deriveStatus("随便什么")).isEqualTo("UNKNOWN");
    }

    @Test
    void answerTextExtractedAfterPrefix() {
        assertThat(JdbcAskUserLogQuery.extractAnswer("[用户已回答] 选项A；自定义答案B"))
                .isEqualTo("选项A；自定义答案B");
        assertThat(JdbcAskUserLogQuery.extractAnswer("[用户未作答] 用户已停止本次对话,未作答。"))
                .isNull();
        assertThat(JdbcAskUserLogQuery.extractAnswer(null)).isNull();
    }

    @Test
    void questionAndHeaderParsedFromArgumentsJson() {
        String json = "{\"question\":\"用哪种数据库?\",\"header\":\"数据库选型\","
                + "\"optionsJson\":\"[{\\\"label\\\":\\\"MySQL\\\"}]\",\"multiSelect\":false}";
        assertThat(JdbcAskUserLogQuery.parseQuestion(json)).isEqualTo("用哪种数据库?");
        assertThat(JdbcAskUserLogQuery.parseHeader(json)).isEqualTo("数据库选型");
    }

    @Test
    void malformedArgumentsJsonFallsBackWithoutThrowing() {
        assertThat(JdbcAskUserLogQuery.parseQuestion("not-json")).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseQuestion(null)).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseHeader("not-json")).isNull();
        // 合法 JSON 但缺字段 → 各自兜底
        assertThat(JdbcAskUserLogQuery.parseQuestion("{}")).isEqualTo("(解析失败)");
        assertThat(JdbcAskUserLogQuery.parseHeader("{\"question\":\"q\"}")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=AskUserLogParsingTest`
Expected: COMPILATION ERROR(JdbcAskUserLogQuery 不存在)

- [ ] **Step 3: 写 record + 接口 + JDBC 实现**

新建 `AskUserLogRecord.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

/**
 * §2 提问卡片日志行(只读视图,来源 loom_tool_call_log WHERE tool_name='askUser')。
 *
 * @param logId          loom_tool_call_log.log_id
 * @param conversationId 会话 ID
 * @param username       提问所属用户
 * @param question       问题文本(从 arguments_json 解析;解析失败 = "(解析失败)")
 * @param header         问题卡片 chip 标题(可空)
 * @param answerText     用户答案(仅 ANSWERED;其余状态为 null)
 * @param status         ANSWERED / TIMEOUT / CANCELLED / FAILED / UNKNOWN
 * @param durationMs     工具阻塞时长 —— 含用户思考+作答时间,前端标注为"等待 Ns"(spec D3)
 * @param createdAt      写入时间(Instant,与 ToolCallLog 一致;Spring Boot 默认序列化为 ISO-8601)
 */
public record AskUserLogRecord(
        long logId,
        String conversationId,
        String username,
        String question,
        String header,
        String answerText,
        String status,
        long durationMs,
        java.time.Instant createdAt
) {
}
```

新建 `IAskUserLogQuery.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import java.util.List;

/**
 * §2 提问卡片日志只读查询(spec 2026-09-08-askuser-followups-design.md)。
 *
 * <p>数据已由 {@code LoggingToolCallback} 全量写入 {@code loom_tool_call_log}
 * (tool_name='askUser'),本接口只做读取+解析,不新增表、不改写入路径(spec D2)。
 * 消费方是 admin 日志页路由 {@code GET /spring/ai/loom/admin/ask-logs}
 * (adminPathPatterns 门禁自动 admin-only)。
 */
public interface IAskUserLogQuery {

    /**
     * 最近的提问日志,按 created_at 倒序。
     *
     * @param limit           返回条数;实现必须钳制到 [1, 200](防滥用)
     * @param usernameOrNull  可选用户名过滤;null/blank = 全部用户
     */
    List<AskUserLogRecord> recent(int limit, String usernameOrNull);
}
```

新建 `JdbcAskUserLogQuery.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@link IAskUserLogQuery} 默认实现:直查 loom_tool_call_log。
 * 解析辅助方法均为 static 包内可测(AskUserLogParsingTest),畸形数据一律兜底不抛。
 */
public class JdbcAskUserLogQuery implements IAskUserLogQuery {

    /** 单次查询条数上限(spec §2.2:limit 钳制防滥用)。 */
    static final int MAX_LIMIT = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE_SQL =
            "select log_id, conversation_id, username, arguments_json, result_text, "
                    + "duration_ms, created_at from loom_tool_call_log where tool_name = 'askUser' ";

    private final JdbcTemplate jdbcTemplate;

    public JdbcAskUserLogQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AskUserLogRecord> recent(int limit, String usernameOrNull) {
        int eff = Math.max(1, Math.min(limit, MAX_LIMIT));
        boolean byUser = usernameOrNull != null && !usernameOrNull.isBlank();
        String sql = BASE_SQL
                + (byUser ? "and username = ? " : "")
                + "order by created_at desc, log_id desc limit ?";
        // H2 支持 LIMIT ?(仓库先例:DefaultKnowledgeMarketService L289 / KnowledgeTagService L151)
        return byUser
                ? jdbcTemplate.query(sql, JdbcAskUserLogQuery::mapRow, usernameOrNull, eff)
                : jdbcTemplate.query(sql, JdbcAskUserLogQuery::mapRow, eff);
    }

    private static AskUserLogRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        String args = rs.getString("arguments_json");
        String result = rs.getString("result_text");
        String status = deriveStatus(result);
        long duration = rs.getObject("duration_ms") == null ? 0L : rs.getLong("duration_ms");
        java.time.Instant createdAt = rs.getTimestamp("created_at") == null
                ? java.time.Instant.EPOCH
                : rs.getTimestamp("created_at").toInstant();
        return new AskUserLogRecord(
                rs.getLong("log_id"),
                rs.getString("conversation_id"),
                rs.getString("username"),
                parseQuestion(args),
                parseHeader(args),
                extractAnswer(result),
                status,
                duration,
                createdAt);
    }

    /** result_text 前缀 → status(前缀逐字对齐 DefaultAskUserTool 返回值,spec §2.2)。 */
    static String deriveStatus(String resultText) {
        if (resultText == null || resultText.isEmpty()) return "UNKNOWN";
        if (resultText.startsWith("[用户已回答]")) return "ANSWERED";
        if (resultText.startsWith("[用户未作答]")) {
            // 同为"未作答":超时 vs 用户主动停止(cancelAll 哨兵),靠正文细分
            return resultText.contains("已停止") ? "CANCELLED" : "TIMEOUT";
        }
        if (resultText.startsWith("[提问被中断]")) return "CANCELLED";
        if (resultText.startsWith("[提问失败]")) return "FAILED";
        return "UNKNOWN";
    }

    /** ANSWERED 时取前缀之后的正文;其余状态 null。 */
    static String extractAnswer(String resultText) {
        if (resultText == null) return null;
        String prefix = "[用户已回答] ";
        if (!resultText.startsWith(prefix)) return null;
        String answer = resultText.substring(prefix.length()).trim();
        return answer.isEmpty() ? null : answer;
    }

    static String parseQuestion(String argumentsJson) {
        JsonNode node = readTree(argumentsJson);
        if (node == null) return "(解析失败)";
        JsonNode q = node.get("question");
        return q == null || q.asText("").isBlank() ? "(解析失败)" : q.asText();
    }

    static String parseHeader(String argumentsJson) {
        JsonNode node = readTree(argumentsJson);
        if (node == null) return null;
        JsonNode h = node.get("header");
        return h == null || h.asText("").isBlank() ? null : h.asText();
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null; // 畸形 JSON 兜底,不抛(spec §2.2)
        }
    }
}
```

- [ ] **Step 4: 跑单元测试确认通过**

Run: `mvn test -pl spring-ai-loom-agent -Dtest=AskUserLogParsingTest`
Expected: 8 PASS

- [ ] **Step 5: 写失败路由测试**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/AskUserLogRouterTest.java`:

```java
package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.askuser.AskUserLogRecord;
import cn.wubo.spring.ai.loom.agent.askuser.IAskUserLogQuery;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 ask-logs 路由测试(真 router + 假 IAskUserLogQuery,无 Spring 上下文 ——
 * AskUserRouterTest / AdminRouterSpotTest 先例)。
 * admin 门禁由 AuthenticationFilter + adminPathPatterns 在真实容器层负责,
 * 本测试只锁路由行为(limit 解析 / username 透传 / 非法 limit 400)。
 */
@DisplayName("admin ask-logs 路由")
class AskUserLogRouterTest {

    private static AskUserLogRecord sample() {
        return new AskUserLogRecord(1L, "conv-1", "alice", "用哪种数据库?", "数据库选型",
                "MySQL", "ANSWERED", 42_000L, Instant.parse("2026-09-08T01:02:03Z"));
    }

    private RouterFunction<ServerResponse> routerWith(IAskUserLogQuery query) {
        return new LoomAgentConfiguration.WebConfiguration().loomAgentAskLogRouter(query);
    }

    @Test
    void defaultLimitIs50AndNoUsernameFilter() throws Exception {
        AtomicInteger seenLimit = new AtomicInteger();
        AtomicReference<String> seenUser = new AtomicReference<>();
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> {
            seenLimit.set(limit);
            seenUser.set(username);
            return List.of(sample());
        });
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenLimit.get()).isEqualTo(50);
        assertThat(seenUser.get()).isNull();
    }

    @Test
    void limitAndUsernameParamsPassThrough() throws Exception {
        AtomicInteger seenLimit = new AtomicInteger();
        AtomicReference<String> seenUser = new AtomicReference<>();
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> {
            seenLimit.set(limit);
            seenUser.set(username);
            return List.of();
        });
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs?limit=10&username=alice", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenLimit.get()).isEqualTo(10);
        assertThat(seenUser.get()).isEqualTo("alice");
    }

    @Test
    void nonNumericLimitIs400() throws Exception {
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> List.of());
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs?limit=abc", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void bodyCarriesParsedRecord() throws Exception {
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> List.of(sample()));
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs", null);
        assertThat(resp).isNotNull();
        // EntityResponse body 即 record 列表(字段名 = 组件名,Jackson 序列化)
        assertThat(resp).isInstanceOf(org.springframework.web.servlet.function.EntityResponse.class);
        Object body = ((org.springframework.web.servlet.function.EntityResponse<?>) resp).entity();
        assertThat(body).isInstanceOf(List.class);
        assertThat((List<?>) body).hasSize(1);
        assertThat(((List<?>) body).get(0)).isInstanceOf(AskUserLogRecord.class);
    }
}
```

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=AskUserLogRouterTest`
Expected: COMPILATION ERROR(loomAgentAskLogRouter 不存在)

- [ ] **Step 6: 接线 — bean + 路由**

`LoomAgentConfiguration.java` **ToolConfiguration**(在 `defaultAskUserTool(...)` bean 之后)加:

```java
        /**
         * §2 提问卡片日志只读查询(spec 2026-09-08-askuser-followups-design.md):
         * 数据已由 LoggingToolCallback 写入 loom_tool_call_log,本 bean 只读取+解析。
         */
        @Bean
        @ConditionalOnMissingBean(cn.wubo.spring.ai.loom.agent.askuser.IAskUserLogQuery.class)
        public cn.wubo.spring.ai.loom.agent.askuser.IAskUserLogQuery jdbcAskUserLogQuery(
                JdbcTemplate jdbcTemplate) {
            return new cn.wubo.spring.ai.loom.agent.askuser.JdbcAskUserLogQuery(jdbcTemplate);
        }
```

(若该处 JdbcTemplate 未被 import,用全限定名 `org.springframework.jdbc.core.JdbcTemplate`。)

**WebConfiguration**(在 `loomAgentAskRouter` bean 之后)加:

```java
        /**
         * §2 admin 日志页"提问卡片"区块数据源(spec 2026-09-08-askuser-followups-design.md)。
         * 路径落在 adminPathPatterns(/spring/ai/loom/admin/**)门禁内,自动 admin-only,
         * 无需路由内校验。limit 非法 → 400(镜像 stats/tokens/monthly 的 year/month 先例)。
         */
        @Bean("loomAgentAskLogRouter")
        public RouterFunction<ServerResponse> loomAgentAskLogRouter(
                cn.wubo.spring.ai.loom.agent.askuser.IAskUserLogQuery askUserLogQuery) {
            RouterFunctions.Builder builder = RouterFunctions.route();
            builder.GET("spring/ai/loom/admin/ask-logs", request -> {
                int limit = 50;
                String l = request.param("limit").orElse(null);
                if (l != null && !l.isBlank()) {
                    try {
                        limit = Integer.parseInt(l.trim());
                    } catch (NumberFormatException nfe) {
                        return ServerResponse.badRequest().body(Map.of(
                                "error", "limit 必须是数字: limit=" + l));
                    }
                }
                String username = request.param("username").orElse(null);
                return ServerResponse.ok().body(askUserLogQuery.recent(limit, username));
            });
            return builder.build();
        }
```

- [ ] **Step 7: 写失败 IT(真 DB 往返)**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/JdbcAskUserLogQueryIT.java`:

```java
package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 真 DB 往返:loom_tool_call_log 插入 askUser 行 → recent() 读回并正确解析。
 * 走 LoomAgentTestApplication 全上下文(清库 IT gate 的一员)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("askUser 日志查询 IT")
class JdbcAskUserLogQueryIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IAskUserLogQuery askUserLogQuery;

    private String conv;

    @BeforeEach
    void seed() {
        conv = "conv-asklog-" + System.nanoTime();
        String user = "u-" + System.nanoTime();
        // 一答一超时两行(模拟 LoggingToolCallback 写入形态)
        insert(user, conv + "-1",
                "{\"question\":\"用哪种数据库?\",\"header\":\"选型\"}",
                "[用户已回答] MySQL", 42_000L, Instant.now().minusSeconds(120));
        insert(user, conv + "-2",
                "{\"question\":\"部署到哪个环境?\"}",
                "[用户未作答] 用户未在 5 分钟内作答。请基于现有信息自行合理决策并继续,或改用其他方式推进。",
                300_000L, Instant.now().minusSeconds(60));
        // 干扰行:非 askUser 工具,不得混入结果
        jdbcTemplate.update("insert into loom_tool_call_log (conversation_id, username, "
                        + "tool_call_id, tool_name, arguments_json, result_text, result_is_error, "
                        + "duration_ms, created_at) values (?,?,?,?,?,?,?,?,?)",
                conv + "-3", user, "call-noise", "getCurrentTime", "{}", "2026-09-08", false, 5L,
                Timestamp.from(Instant.now()));
        this.username = user;
    }

    private String username;

    private void insert(String user, String conversationId, String args, String result,
                        long durationMs, Instant createdAt) {
        jdbcTemplate.update("insert into loom_tool_call_log (conversation_id, username, "
                        + "tool_call_id, tool_name, arguments_json, result_text, result_is_error, "
                        + "duration_ms, created_at) values (?,?,?,?,?,?,?,?,?)",
                conversationId, user, "call-" + System.nanoTime(), "askUser", args, result,
                false, durationMs, Timestamp.from(createdAt));
    }

    @Test
    void recentReturnsParsedRowsNewestFirstFilteredByUser() {
        List<AskUserLogRecord> rows = askUserLogQuery.recent(50, username);
        assertThat(rows).hasSize(2);
        // created_at desc:超时行(60s 前)在已答行(120s 前)之前
        assertThat(rows.get(0).status()).isEqualTo("TIMEOUT");
        assertThat(rows.get(0).question()).isEqualTo("部署到哪个环境?");
        assertThat(rows.get(0).header()).isNull();
        assertThat(rows.get(0).durationMs()).isEqualTo(300_000L);
        assertThat(rows.get(1).status()).isEqualTo("ANSWERED");
        assertThat(rows.get(1).question()).isEqualTo("用哪种数据库?");
        assertThat(rows.get(1).header()).isEqualTo("选型");
        assertThat(rows.get(1).answerText()).isEqualTo("MySQL");
        // 干扰行(getCurrentTime)不在结果里
        assertThat(rows).allMatch(r -> r.conversationId().startsWith(conv));
    }

    @Test
    void limitClampedToOneMinimum() {
        List<AskUserLogRecord> rows = askUserLogQuery.recent(0, username);
        assertThat(rows).hasSize(1); // 钳制到 1
    }
}
```

- [ ] **Step 8: 跑 IT 确认通过(需清库)**

Run:
```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='JdbcAskUserLogQueryIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 2 PASS(注意先 `mvn clean install -DskipTests -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'` 让 lib 新类进本地仓库)

- [ ] **Step 9: 跑受影响模块全量**

Run: `mvn test -pl spring-ai-loom-agent`
Expected: **183** run(175+8), 0 fail(lib 模块首跑如现既有计时型 flaky,连跑两次确认,先例见第二轮报告 P0)

Run: `mvn test -pl spring-ai-loom-agent-test`
Expected: **406** run(402+4 路由), 0 fail(IT 类不在默认 `mvn test` 里跑 —— 项目无 failsafe,`*IT` 需显式 `-Dtest`)

- [ ] **Step 10: Commit**

```bash
git add spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/askuser/ \
        spring-ai-loom-agent/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/ \
        spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/AskUserLogRouterTest.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/askuser/JdbcAskUserLogQueryIT.java
git commit -m "feat: askUser 提问日志只读查询(IAskUserLogQuery + JDBC 实现 + GET /admin/ask-logs 路由)"
```

---

### Task 4: §2 前端 — 日志页"提问卡片"区块

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.html`(main 末尾、`stats-table` div 之后)
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.js`(IIFE 内追加)

**Interfaces:**
- Consumes: Task 3 的 `GET /spring/ai/loom/admin/ask-logs?limit=&username=` → JSON 数组,字段:`logId, conversationId, username, question, header, answerText, status(ANSWERED/TIMEOUT/CANCELLED/FAILED/UNKNOWN), durationMs, createdAt(ISO-8601)`
- Produces: 无

- [ ] **Step 1: stats.html 加区块**

在 `<div id="stats-table" class="table-container">...</div>` 之后、`</main>` 之前插入:

```html
          <!-- §2 提问卡片(askUser)日志:数据源 loom_tool_call_log,只读 -->
          <div class="stats-card" style="margin-top: 16px">
            <h3
              style="
                margin: 0 0 12px 0;
                font-size: 14px;
                color: var(--text-muted);
                font-weight: 500;
              "
            >
              提问卡片(askUser)日志
              <span style="font-size: 12px; margin-left: 8px">
                用户：
                <input
                  type="text"
                  id="ask-logs-user"
                  placeholder="全部用户"
                  style="
                    width: 140px;
                    padding: 4px 8px;
                    border: 1px solid var(--border-color);
                    border-radius: 4px;
                  "
                />
                <button
                  id="ask-logs-refresh"
                  class="secondary-btn"
                  style="padding: 4px 10px; margin-left: 6px"
                >
                  刷新
                </button>
              </span>
            </h3>
            <div id="ask-logs-table" class="table-container">
              <div class="loading-indicator">加载中...</div>
            </div>
          </div>
```

- [ ] **Step 2: stats.js 加加载/渲染逻辑**

在 IIFE 内 `const monthLabel = ...` 之后加 DOM 引用:

```js
  const askLogsTable = document.getElementById("ask-logs-table");
  const askLogsUser = document.getElementById("ask-logs-user");
```

在 `renderTable` 函数之后追加三个函数:

```js
  // ===== §2 提问卡片(askUser)日志 =====

  function fmtWait(ms) {
    // durationMs 主体是用户思考+作答的阻塞时间 —— 标注"等待"而非"耗时"(spec D3)
    if (ms == null || isNaN(ms)) return "-";
    const sec = Math.round(ms / 1000);
    if (sec < 60) return `等待 ${sec}s`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `等待 ${m}m ${s}s`;
  }

  function askStatusBadge(status) {
    // 语义/配色与聊天卡片摘要行一致(spec §2.2:已答绿/超时灰/取消灰/失败红)
    const map = {
      ANSWERED: ["已答", "var(--success-color, #22c55e)"],
      TIMEOUT: ["已超时", "var(--text-muted, #94a3b8)"],
      CANCELLED: ["已取消", "var(--text-muted, #94a3b8)"],
      FAILED: ["失败", "#ef4444"],
      UNKNOWN: ["未知", "var(--text-muted, #94a3b8)"],
    };
    const [label, color] = map[status] || map.UNKNOWN;
    return `<span style="color: ${color}; font-weight: 600; font-size: 12px;">${label}</span>`;
  }

  async function loadAskLogs() {
    askLogsTable.innerHTML = '<div class="loading-indicator">加载中...</div>';
    const user = askLogsUser.value.trim();
    const qs = `limit=50${user ? `&username=${encodeURIComponent(user)}` : ""}`;
    try {
      const r = await fetch(`/spring/ai/loom/admin/ask-logs?${qs}`, {
        credentials: "include",
      });
      if (r.status === 401) {
        window.location.replace("/spring/ai/loom/login.html");
        return;
      }
      if (!r.ok) {
        askLogsTable.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
        return;
      }
      renderAskLogs(await r.json());
    } catch (e) {
      askLogsTable.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
    }
  }

  function renderAskLogs(list) {
    if (!list || list.length === 0) {
      askLogsTable.innerHTML = '<div class="empty-state">暂无提问记录</div>';
      return;
    }
    const rows = list
      .map((rec) => {
        const q = rec.question || "";
        const qShort = q.length > 60 ? q.slice(0, 60) + "…" : q;
        const answer =
          rec.status === "ANSWERED" && rec.answerText
            ? escapeHtml(rec.answerText)
            : askStatusBadge(rec.status);
        const when = rec.createdAt
          ? new Date(rec.createdAt).toLocaleString("zh-CN", { hour12: false })
          : "-";
        const convShort = (rec.conversationId || "").slice(0, 8);
        return `<tr>
 <td style="white-space: nowrap;">${when}</td>
 <td><a class="user-link" href="user.html?username=${encodeURIComponent(rec.username || "")}">${escapeHtml(rec.username)}</a></td>
 <td title="${escapeHtml(q)}">${escapeHtml(qShort)}</td>
 <td>${answer}</td>
 <td style="white-space: nowrap;">${fmtWait(rec.durationMs)}</td>
 <td title="${escapeHtml(rec.conversationId || "")}">${escapeHtml(convShort)}</td>
 </tr>`;
      })
      .join("");
    askLogsTable.innerHTML = `
 <table class="user-table">
 <thead>
 <tr><th>时间</th><th>用户</th><th>问题</th><th>答案 / 状态</th><th>等待时长</th><th>会话</th></tr>
 </thead>
 <tbody>${rows}</tbody>
 </table>`;
  }
```

在文件末尾 `document.getElementById("refresh-btn").addEventListener("click", load);` 之后加:

```js
  document.getElementById("ask-logs-refresh").addEventListener("click", loadAskLogs);
  askLogsUser.addEventListener("keydown", (e) => {
    if (e.key === "Enter") loadAskLogs();
  });
```

并把 IIFE 收尾的 `load();` 改为:

```js
  load();
  loadAskLogs();
```

- [ ] **Step 3: 语法检查**

Run: `node --check spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.js`
Expected: 无输出(无 node 则跳过,Chrome 复验在 T6)

Run: `mvn test -pl spring-ai-loom-agent-test`
Expected: **406** run, 0 fail(本 Task 无新测试;确认资源改动没破坏静态契约测试)

- [ ] **Step 4: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.html \
        spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/admin/stats.js
git commit -m "feat: admin 日志页新增提问卡片区块(时间/用户/问题/答案或状态/等待时长/会话)"
```

---

### Task 5: §4 V1.0 种子两个官方一问一答技能

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql`(文件末尾,`idx_loom_vector_store_created` 之后追加)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillContentContractTest.java`(新建)
- Test: `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillIT.java`(新建)

**Interfaces:**
- Consumes: `market_skill` 全部列(基础列 L207 CREATE + `is_official` L644 / `featured_rank` L647 / `category` L650 / `created_by_kind` L653 / `updated_at` L739 ALTER —— 种子段在文件尾,列全存在);admin 种子先例(L262 INSERT...SELECT...WHERE NOT EXISTS)
- Produces: 清库后 `market_skill` 里 2 条 author='system' 官方技能(市场页可见、用户可 pull)

- [ ] **Step 1: 写失败内容契约测试(静态读 V1.0)**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillContentContractTest.java`:

```java
package cn.wubo.spring.ai.loom.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 官方种子技能内容契约(spec 2026-09-08-askuser-followups-design.md §4.2 三铁律):
 * ① 不硬编码工具名(种子段不得出现 askUser 字面量 —— 描述"提问能力",LLM 从
 *    function schema 自选,Spring AI 按方法名注册,写死易与实际注册名不符);
 * ② 防 qwen 自白死循环(必须含反自白 + "不得代替用户作答" + "立即汇总"纪律);
 * ③ 纯文本(不得含裸 HTML 标签)。
 * 另锁:官方已审字段(author=system / APPROVED / is_official / ADMIN / 表达沟通)+ 幂等 WHERE NOT EXISTS。
 */
@DisplayName("V1.0 官方种子技能契约")
class SeedSkillContentContractTest {

    private static final String MARKER = "官方种子技能：一问一答表达训练";

    private String seedSegment() throws IOException {
        try (var in = getClass().getResourceAsStream("/db/migration/V1.0__init.sql")) {
            assertThat(in).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int idx = sql.indexOf(MARKER);
            assertThat(idx).as("V1.0 必须含种子段 marker: " + MARKER).isGreaterThan(0);
            return sql.substring(idx);
        }
    }

    @Test
    void bothSkillsSeededAsOfficialApproved() throws IOException {
        String seg = seedSegment();
        assertThat(seg).contains("'STAR-IJ 讲清一件事'");
        assertThat(seg).contains("'靶心人公式 讲好一个故事'");
        assertThat(seg).contains("'system', 'APPROVED'");
        assertThat(seg).contains("'ADMIN', '表达沟通'");
        // 幂等:每条 INSERT 都带 WHERE NOT EXISTS
        assertThat(seg).contains("WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = 'STAR-IJ 讲清一件事')");
        assertThat(seg).contains("WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = '靶心人公式 讲好一个故事')");
    }

    @Test
    void noHardcodedToolNames() throws IOException {
        // 铁律①:种子段不得出现 askUser 字面量(工具名从 function schema 自选)
        assertThat(seedSegment()).doesNotContain("askUser");
    }

    @Test
    void antiSelfTalkDisciplinePresent() throws IOException {
        String seg = seedSegment();
        // 铁律②:三条纪律逐字锁死(两个技能都必须有)
        assertThat(seg).contains("不要描述你打算做什么");
        assertThat(seg).contains("不得代替用户作答");
        assertThat(seg).contains("信息足够时立即进入汇总");
    }

    @Test
    void plainTextNoRawHtml() throws IOException {
        // 铁律③:纯文本,无裸 HTML 标签(链接用 markdown)
        assertThat(seedSegment()).doesNotContain("<a href");
        assertThat(seedSegment()).doesNotContain("<div");
        assertThat(seedSegment()).doesNotContain("<br");
    }

    @Test
    void starIjHasSixStepsAndTargetHasSeven() throws IOException {
        String seg = seedSegment();
        // STAR-IJ 六步维度名
        for (String dim : new String[]{"情境", "任务", "行动", "结果", "项目价值", "个人成长"}) {
            assertThat(seg).as("STAR-IJ 必含维度: " + dim).contains(dim);
        }
        // 靶心人七步(原词"转弯"非"转折",spec D7)
        for (String step : new String[]{"目标", "阻碍", "努力", "意外", "转弯", "结局"}) {
            assertThat(seg).as("靶心人必含步骤: " + step).contains(step);
        }
        assertThat(seg).contains("努力人公式");
        assertThat(seg).contains("意外人公式");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SeedSkillContentContractTest`
Expected: FAIL(marker 不存在)

- [ ] **Step 3: V1.0 末尾追加种子段(内容逐字如下)**

在 `CREATE INDEX IF NOT EXISTS idx_loom_vector_store_created ON loom_vector_store(created_at);` 之后追加(**content 里的中文引号/破折号原样保留;SQL 字符串内的单引号已确认不存在,无需转义**):

```sql

-- =============================================================
-- ==== 官方种子技能：一问一答表达训练(#4 follow-up,spec 2026-09-08)====
-- 两条 market_skill:author=system / APPROVED / is_official / created_by_kind=ADMIN
-- / category=表达沟通。fresh-DB 政策:本段随全新库执行一次;
-- WHERE NOT EXISTS 幂等(镜像默认 admin 种子先例),UNIQUE(author,name) 双保险。
-- 内容三铁律:①不硬编码工具名(只描述"提问能力");②防 qwen 自白死循环;③纯文本。
-- =============================================================

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT 'STAR-IJ 讲清一件事',
       '用户想「讲清楚一件事」(项目复盘 / 绩效述职 / 面试准备 / 经验沉淀)时触发：按 情境-任务-行动-结果-项目价值-个人成长 六步一问一答收集信息，汇总成结构化叙述与 30 秒电梯稿',
       '用户想「讲清楚一件事」（项目复盘 / 绩效述职 / 面试准备 / 经验沉淀）时触发本技能。
你的任务：按 STAR-IJ 六步（S情境 - T任务 - A行动 - R结果 - I项目价值 - J个人成长）逐步向用户提问，每步一次提问，收集完成后汇总成结构化叙述。

⛔ 执行纪律（必读，否则任务失败）：
1. 不要描述你打算做什么 ——「我将为您梳理 / 接下来我会问」这类自白没有意义，直接发起提问。
2. 每次提问后必须等待用户真实回答，不得代替用户作答、不得自问自答、不得把引导选项当成用户的选择。
3. 一次只问一步。用户答完当前步骤再问下一步，不要一次抛出多个问题。
4. 信息足够时立即进入汇总，不要为凑步数反复追问。

提问方式：使用你的提问能力（向用户发起带选项的提问卡片），每步提供 3-4 个引导选项并允许自由输入。用户选择或输入后，仅在「行动 / 结果」两步可追问一轮细节，然后进入下一步。

六步提问流程：
第 1 步 S 情境：这件事发生的背景是什么？（时间、场合、当时的局面）
  引导选项示例：项目启动期 / 攻坚期 / 收尾复盘 / 自由描述
第 2 步 T 任务：你在其中承担什么角色、要达成什么目标？
  引导选项示例：整体负责人 / 核心执行 / 协作支持 / 自由描述
第 3 步 A 行动：你具体做了哪些关键动作？（引导用户拆成 2-4 个动作，可多选，可追问一轮细节）
  引导选项示例：方案设计 / 资源协调 / 技术攻坚 / 沟通推进
第 4 步 R 结果：取得了什么可量化的成果？
  追问策略：用户答得笼统时，追问一次「有没有具体数字？对比之前改善多少？」，只追问一次。
第 5 步 I 项目价值：这件事对团队 / 业务 / 用户产生了什么意义？
  引导选项示例：提效 / 降本 / 增收 / 风险控制 / 体验改善
第 6 步 J 个人成长：你从中学到了什么、哪些能力得到提升？
  引导选项示例：方法论沉淀 / 技术突破 / 协作能力 / 认知升级

汇总产出（六步答完后立即输出，不要再提问）：
1. 结构化叙述：按 S/T/A/R/I/J 六段展开，每段 2-4 句，保留用户原话中的关键数字与细节。
2. 一句话版本：30 秒电梯稿，突出结果与价值。
3. 如用户说明了用途（面试 / 述职 / 复盘），按该场景调整语气与详略。

边界处理：
- 用户某步拒答或答「不知道」：记录该步为「略过」，继续下一步，汇总时如实标注。
- 用户跑题：温和拉回当前步骤的问题。
- 用户中途要求直接汇总：用已收集的信息立即汇总，缺失步骤标注「未提供」。',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '表达沟通'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = 'STAR-IJ 讲清一件事');

INSERT INTO market_skill (name, description, content, author, status,
                          reviewed_at, reviewed_by,
                          is_official, created_by_kind, category)
SELECT '靶心人公式 讲好一个故事',
       '用户想「讲好一个故事」(品牌故事 / 演讲 / 个人经历分享 / 短视频脚本)时触发：按 目标-阻碍-努力-结果-意外-转弯-结局 七步一问一答引导，汇总成有张力的故事与故事骨架',
       '用户想「讲好一个故事」（品牌故事 / 演讲 / 个人经历分享 / 短视频脚本）时触发本技能。
你的任务：按「靶心人公式」七步逐步向用户提问，收集完成后汇总成一个有张力的故事。

靶心人公式七步：目标 → 阻碍 → 努力 → 结果 → 意外 → 转弯 → 结局。
（注意：第六步的原词是「转弯」，指意外给主角或局面带来的转变，不是简单的「转折」。）

⛔ 执行纪律（必读，否则任务失败）：
1. 不要描述你打算做什么 ——「我将帮您打磨故事」这类自白没有意义，直接发起提问。
2. 每次提问后必须等待用户真实回答，不得代替用户作答、不得自问自答、不得虚构用户没说的细节。
3. 一次只问一步，用户答完再问下一步。
4. 素材足够时立即进入汇总，信息足够时立即进入汇总产出，不要为凑满七步硬追问。

提问方式：使用你的提问能力（向用户发起带选项的提问卡片），每步提供引导选项并允许自由输入。第 1 步提问的背景说明里顺带确认故事主角与场合（如「这是你自己的经历，还是品牌 / 产品的故事？」），不要为此单独多问一轮。

七步提问流程：
第 1 步 目标：主角想要什么？（一句话目标，越具体越好）
第 2 步 阻碍：什么在阻挡主角？（人 / 事 / 环境 / 自身局限）
第 3 步 努力：主角为克服阻碍做了什么？（可追问 1-2 轮细节，好故事需要具体动作）
第 4 步 结果：努力的直接结果如何？（常见是没成功或只部分成功 —— 这正是故事的张力所在，如实收集，不要美化）
第 5 步 意外：出现了什么意料之外的事？
第 6 步 转弯：这个意外让主角或局面发生了什么转变？（认知、策略、关系的转变）
第 7 步 结局：最终如何收场？你希望听众记住什么？

快速变体（按素材复杂度自选，并在提问前一句话告知用户所用版本）：
- 努力人公式（4 步）：目标 → 阻碍 → 努力 → 结局。适合简单场景、时间有限的用户。
- 意外人公式（4 步）：目标 → 意外 → 转弯 → 结局。适合反转突出的故事。
判断依据：第 4、5 步若用户表示「没有意外 / 一切顺利」，主动建议改用努力人公式，不硬编七步。

汇总产出（收集完成后立即输出，不要再提问）：
1. 连贯故事文本：300-600 字，按七步（或所选变体）推进，保留用户原话的关键细节，在「意外 → 转弯」处放慢节奏制造张力。
2. 故事骨架：每步一行，供用户二次创作或做 PPT 大纲。

边界处理：
- 用户某步拒答或答「不知道」：记录该步为「略过」，继续下一步，汇总时如实标注或自然过渡。
- 用户跑题：温和拉回当前步骤的问题。
- 用户中途要求直接成稿：用已收集的信息立即汇总，缺失步骤以合理过渡带过并标注「未提供细节」。',
       'system', 'APPROVED', CURRENT_TIMESTAMP, 'system',
       TRUE, 'ADMIN', '表达沟通'
WHERE NOT EXISTS (SELECT 1 FROM market_skill WHERE author = 'system' AND name = '靶心人公式 讲好一个故事');
```

- [ ] **Step 4: 跑内容契约测试确认通过**

Run: `mvn test -pl spring-ai-loom-agent-test -Dtest=SeedSkillContentContractTest`
Expected: 5 PASS

- [ ] **Step 5: 写失败 IT(清库后种子行存在 + 幂等)**

新建 `spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillIT.java`:

```java
package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 官方种子技能 IT:清库启动后 market_skill 存在 2 条 author=system 官方已审行,
 * 且迁移幂等(WHERE NOT EXISTS —— 同库二次执行 Flyway 不重复,V1.0 只跑一次由
 * flyway_schema_history 保证;本测试断言当前库恰好各 1 条)。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("官方种子技能 IT")
class SeedSkillIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoOfficialSeedSkillsExist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, status, is_official, created_by_kind, category, author "
                        + "from market_skill where author = 'system' order by name");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("status"))).isEqualTo("APPROVED");
            assertThat(row.get("is_official")).isEqualTo(Boolean.TRUE);
            assertThat(String.valueOf(row.get("created_by_kind"))).isEqualTo("ADMIN");
            assertThat(String.valueOf(row.get("category"))).isEqualTo("表达沟通");
        }
        assertThat(rows).extracting(r -> String.valueOf(r.get("name")))
                .containsExactly("STAR-IJ 讲清一件事", "靶心人公式 讲好一个故事");
    }

    @Test
    void seedContentIsSubstantialAndPullable() {
        // content 非空且长度达标(设计目标 1200+ 字符),description 非空(市场列表展示)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, description, content from market_skill where author = 'system'");
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("description"))).isNotBlank();
            assertThat(String.valueOf(row.get("content")).length())
                    .as("content of " + row.get("name")).isGreaterThan(1200);
        }
    }
}
```

- [ ] **Step 6: install + 清库跑 IT**

Run:
```bash
mvn clean install -DskipTests -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='SeedSkillIT,JdbcAskUserLogQueryIT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 4 PASS

- [ ] **Step 7: 清库跑全量 IT gate(V1.0 改动必须全量验证)**

Run:
```bash
rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: **127+** run(123 + SeedSkillIT 2 + JdbcAskUserLogQueryIT 2), 0 fail, 3 skip。特别注意 `MarketAcceptanceIT` / `MarketApprovalFlowIT` 等市场 IT —— 种子行会出现在 `market_skill` 全表查询里,若有测试断言"全新库市场为空"会红;红了就按"种子行是预期数据"修正该测试的断言(改为排除 author='system' 或计数 +2),并在 commit message 里记录。

- [ ] **Step 8: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/db/migration/V1.0__init.sql \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillContentContractTest.java \
        spring-ai-loom-agent-test/src/test/java/cn/wubo/spring/ai/loom/agent/skill/SeedSkillIT.java
git commit -m "feat: V1.0 种子两个官方一问一答技能(STAR-IJ 讲清一件事 / 靶心人公式 讲好一个故事)"
```
(若 Step 7 修了既有市场 IT,一并 `git add` 进本 commit。)

---

### Task 6: 文档同步 + 全量回归门 + Chrome 复验

**Files:**
- Modify: `CLAUDE.md`(数据层 V1.0 描述、admin 日志页职责、ISkillStorage/角色分配相关行)
- Modify: `docs/superpowers/roadmap-2026-09-four-items.md`(#1 小节追加"四项后续调整落地记录")
- Modify: `docs/API.md` + `docs/API.zh-CN.md`(新增 `GET /admin/ask-logs` 端点行,镜像既有 admin stats 端点的记载位置)

**Interfaces:**
- Consumes: T1-T5 全部产物
- Produces: 最终可测状态(交给第三轮全面测试)

- [ ] **Step 1: CLAUDE.md 同步(4 处)**

1. `ISkillStorage` 表格行末或 M0/M1/M2 段:补一句"V1.0 尾部种子 2 条官方技能(STAR-IJ / 靶心人公式,author=system,category=表达沟通)"。
2. 数据层 `V1.0__init.sql` 描述行:在表清单后补"+ 2 条官方种子 market_skill 行"。
3. admin 控制台/日志页相关描述(如有 stats.html 提及处):补"日志页含提问卡片(askUser)区块,数据源 `GET /admin/ask-logs`(loom_tool_call_log 只读视图)"。
4. RBAC 段 strict RBAC 描述处:补"admin 也可在控制台被分配角色(2026-09-08 起,console.js ADMIN early-return 已删;setUserRolesOrSkipAdmin @Deprecated)"。

- [ ] **Step 2: docs/API*.md 补端点**

在两份 API 文档的 admin 端点表(`stats/tokens/monthly` 附近)加一行:

```markdown
| `GET /spring/ai/loom/admin/ask-logs?limit=&username=` | admin | 提问卡片(askUser)日志(只读,来源 loom_tool_call_log;limit 默认 50 上限 200;status: ANSWERED/TIMEOUT/CANCELLED/FAILED/UNKNOWN) |
```

(英文版对应英文描述;镜像该表既有行的格式。)

- [ ] **Step 3: roadmap 落地记录**

`docs/superpowers/roadmap-2026-09-four-items.md` #1 小节末尾追加"### 四项后续调整落地记录(2026-09-08)"段:列 spec/plan 路径、T1-T6 commit 号、回归门数字、Chrome 复验结论、"§4 合入后运行实例需清库重启"提醒。

- [ ] **Step 4: 全量回归门(三段,顺序执行)**

```bash
mvn test -pl spring-ai-loom-agent
# Expected: 183 run, 0 fail(175 + 8 AskUserLogParsingTest;flaky 连跑两次确认)

mvn clean install -DskipTests -Dgpg.skip=true -pl '!loom-file-mcp,!loom-git-mcp,!loom-maven-mcp,!loom-compile-mcp'
# Expected: BUILD SUCCESS

mvn test -pl spring-ai-loom-agent-test
# Expected: 411 run, 0 fail(397 + 2 T1 + 3 T2 + 4 T3 + 5 T5)

rm -rf ~/.loom/datasource spring-ai-loom-agent-test/target/test-ds spring-ai-loom-agent-test/target/surefire-reports
mvn test -pl spring-ai-loom-agent-test -Dtest='*IT' -Dsurefire.failIfNoSpecifiedTests=false
# Expected: 127 run, 0 fail, 3 skip(123 + 2 SeedSkillIT + 2 JdbcAskUserLogQueryIT)
```

- [ ] **Step 5: Chrome 复验(真实浏览器,合成帧优先;服务跑 8080 全新库)**

前置:清 `~/.loom/datasource` 后用全量配置重启 test 应用(8080),登录 wb04307201/123456。

| # | 验证点 | 方法 | 期望 |
|---|---|---|---|
| V1 | §1 已答折叠 | evaluate_script 注入合成 askUser SSE 帧(第二轮 DT1 同款)→ 点选提交 | 卡片隐藏,摘要行 `✓ 问题 → 答案` 绿色;点击摘要 → 卡片展开(终态只读),箭头 ▸→▾ |
| V2 | §1 超时/取消折叠 | 合成帧 timeoutSeconds=3 不作答;另跑一次点 stop | 摘要 `⏳ 问题 → 已超时，未作答` / `✗ 问题 → 已取消`,灰色 |
| V3 | §1 XSS | 合成帧 question/答案含 `<img onerror=...>` payload | 摘要行显示字面文本,`__XSS_*` 标记全 undefined |
| V4 | §2 日志页 | 用 V1 的真实作答(或 curl 直插 loom_tool_call_log 后刷新) | "提问卡片"区块出现记录:已答绿徽章 + 答案文本 + `等待 Ns`;username 过滤生效;console 零错误 |
| V5 | §3 admin 分配角色 | console.html → wb04307201 行"分配角色" | 弹窗加载角色列表(不再是旧文案)、可勾选保存、刷新后保持;hint 含 strict RBAC 提示句 |
| V6 | §3 RBAC 闭环 | 给 admin 分配一个带 MCP 授权的角色 → 回聊天页开"工具"弹窗 | 对应 MCP/工具出现(effectiveEnabled 变化) |
| V7 | §4 种子技能 | admin/market-skills.html | 2 条官方技能,带官方徽章,category=表达沟通;编辑弹窗字段完整 |
| V8 | §4 pull + 真实 LLM 一问一答 | 普通视角 pull STAR-IJ → 聊天选中该技能 → "帮我讲清楚上周的项目上线" | LLM 逐步行 6 步提问(不自问自答、不描述计划),答完输出结构化汇总 + 电梯稿(真实 LLM,1 次即可,观察不通过则记录进第三轮测试) |
| V9 | 回归 | 既有聊天流/工具弹窗/文件模态框抽查 | 无破坏(第二轮 PE 清单快跑) |

截图存 `.superpowers/sdd/2026-09-08-askuser-followups/`(V1/V4/V5/V7 至少各一张)。

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/API.md docs/API.zh-CN.md docs/superpowers/roadmap-2026-09-four-items.md
git commit -m "docs: 四项后续调整文档同步(CLAUDE/API×2/roadmap 落地记录)"
```

---

## Self-Review 记录

1. **Spec coverage**:§1→T2(freeze 4 参/摘要 DOM/CSS/契约测试/restorePending 不折叠 D8 ✅);§2→T3(接口/JDBC/路由/钳制/解析兜底)+T4(区块/等待 Ns D3/徽章配色/过滤);§3→T1(early-return 删除/RBAC 提示句/@Deprecated D4);§4→T5(幂等种子/三铁律契约测试/六步七步内容/D5-D7 字段与原词"转弯");执行顺序 §3→§1→§2→§4 = T1→T2→T3/T4→T5 ✅;spec §5 回归门+文档→T6 ✅。无缺口。
2. **Placeholder scan**:T5 种子 SQL 全文逐字给出(spec §4.1 的 `<description>`/`<content>` 模板标记已在 T5 Step 3 落实为完整文本);无 TBD/TODO。
3. **Type consistency**:`freeze(qid, stateText, ok, answerText)` T2 内定义与调用一致;`AskUserLogRecord` 9 组件在 T3 定义、T3 路由测试与 T4 前端字段(logId/conversationId/username/question/header/answerText/status/durationMs/createdAt)一致;`IAskUserLogQuery.recent(int, String)` 签名贯穿;status 枚举 5 值 T3 推导 = T4 徽章 map。spec 记录用的 `LocalDateTime` 在 plan 里改为 `Instant`(与既有 ToolCallLog 一致,Jackson ISO-8601 序列化)—— 已作为偏差记录,前端 `new Date(iso)` 兼容。
