/**
 * 工具调用条目的共享渲染器 —— 主聊天界面（app.js）与 admin 流水页（conversation.js）共用。
 *
 * <p><b>为什么需要它</b>：历史回看时，工具调用以独立条目（messageType=TOOL_CALL）
 * 出现在会话响应里。两个页面都要把它渲染成富 UI（askUser → 卡片、子任务 → chip、
 * 其他 → 折叠行），但**行为不同**：
 * <ul>
 *   <li>实时渲染：askUser 卡片可交互（选项可点、可提交）</li>
 *   <li>历史渲染：只读快照（显示"已答 ✓"与用户答案，不可再提交）</li>
 * </ul>
 * 故本渲染器只产出**只读形态**，复用既有的 .askuser-card / .subtask-chip 样式类
 * 保证视觉一致，但不绑定任何交互事件。
 *
 * <p>普通 script（同 canvas-board.js / market-admin.js 惯例），挂到 window.ToolCallRenderer。
 */
(function () {
  "use strict";

  /** HTML 转义 —— 工具参数/结果来自 LLM 与用户输入，必须转义防 XSS。 */
  function esc(s) {
    if (s === null || s === undefined) return "";
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  /** 把毫秒渲染成人读形式。 */
  function fmtDuration(ms) {
    if (ms === null || ms === undefined || ms < 0) return "";
    if (ms < 1000) return ms + "ms";
    if (ms < 60_000) return (ms / 1000).toFixed(1) + "s";
    return Math.floor(ms / 60_000) + "m" + Math.round((ms % 60_000) / 1000) + "s";
  }

  /**
   * 剥离字符串外层引号。
   *
   * <p>{@code loom_tool_call_log.result_text} 存的是工具返回值的**字面量**，
   * 可能带外层引号（如 {@code "[用户已回答] 喝茶"}）。不剥离会让
   * 「用户答案 vs 选项 label」的比对失败，选中项不高亮。
   */
  function stripOuterQuotes(s) {
    if (!s) return s;
    const t = s.trim();
    if (t.length >= 2 && (t[0] === '"' || t[0] === "'") && t[t.length - 1] === t[0]) {
      return t.slice(1, -1);
    }
    return s;
  }

  /**
   * 解析 askUser 的 optionsJson（它是一层 JSON 字符串嵌套在 arguments 里）。
   * 解析失败返回空数组 —— 卡片会退化成只显示问题文本，不崩。
   */
  function parseOptions(args) {
    if (!args || !args.optionsJson) return [];
    try {
      const raw = args.optionsJson;
      const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
      return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
      return [];
    }
  }

  /** askUser 历史卡片：只读快照，显示选项与用户已选答案。 */
  function renderAskUserCard(entry) {
    const args = entry.arguments || {};
    const options = parseOptions(args);
    const result = stripOuterQuotes(String(entry.result || ""));
    // result 形如 "[用户已回答] 面条" —— 抽出答案部分用于高亮。
    // 注意：loom_tool_call_log.result_text 可能带外层 JSON 引号（"[用户已回答] x"），
    // 不剥离会导致答案比对失败、选中项不高亮（实测踩过）。
    const answered = /\[用户已回答\]\s*(.*)/.exec(result);
    const answer = answered ? answered[1].trim().replace(/^["']|["']$/g, "") : null;

    const optionsHtml = options
      .map((opt) => {
        const label = esc(opt.label || "");
        const chosen = answer && String(opt.label || "").trim() === answer;
        return `
        <label class="askuser-opt${chosen ? " askuser-opt-chosen" : ""}">
          <input class="askuser-opt-input" type="${args.multiSelect ? "checkbox" : "radio"}"
                 ${chosen ? "checked" : ""} disabled/>
          <span class="askuser-opt-label">${label}</span>
          ${opt.description ? `<span class="askuser-opt-desc">${esc(opt.description)}</span>` : ""}
        </label>`;
      })
      .join("");

    const stateBadge = answer
      ? `<span class="askuser-state askuser-state-ok">已答 ✓</span>`
      : `<span class="askuser-state">未作答</span>`;

    return `
      <div class="askuser-card askuser-frozen" data-tool-call-id="${esc(entry.toolCallId)}">
        <div class="askuser-header">
          <span class="askuser-title">${esc(args.header || "提问")}</span>
          ${stateBadge}
        </div>
        <div class="askuser-question">${esc(args.question || "")}</div>
        ${args.background ? `<div class="askuser-background">${esc(args.background)}</div>` : ""}
        ${optionsHtml ? `<div class="askuser-options">${optionsHtml}</div>` : ""}
        ${answer ? `<div class="askuser-summary askuser-summary-ok">
            <span class="askuser-summary-text">${esc(answer)}</span>
          </div>` : ""}
      </div>`;
  }

  /** 子任务历史 chip：复用实时 chip 的样式，附耗时与结果。 */
  function renderSubtaskChip(entry, subtaskEvent) {
    const st = subtaskEvent || {};
    const prompt = String(st.prompt || (entry.arguments && entry.arguments.prompt) || "");
    const status = String(st.status || "UNKNOWN");
    const ok = status === "COMPLETED";
    const id = String(st.id || entry.toolCallId || "");
    const dur = fmtDuration(st.durationMs != null ? st.durationMs : entry.durationMs);
    const result = String(st.result || entry.result || "");
    const error = st.error;

    return `
      <div class="subtask-chip subtask-chip-history ${ok ? "subtask-chip-ok" : "subtask-chip-failed"}"
           data-subtask-id="${esc(id)}">
        <span class="subtask-chip-spinner">${ok ? "✅" : "⚠️"}</span>
        <span class="subtask-chip-id">${esc(id.slice(0, 8))}</span>
        ${dur ? `<span class="subtask-chip-elapsed">${esc(dur)}</span>` : ""}
        <span class="subtask-chip-prompt">${esc(prompt)}</span>
        <span class="subtask-chip-expand">▸</span>
      </div>
      <div class="subtask-detail" style="display:none">
        <div class="subtask-detail-row">
          <span class="subtask-detail-label">子任务 ID:</span><code>${esc(id)}</code>
        </div>
        <div class="subtask-detail-row">
          <span class="subtask-detail-label">任务描述:</span>
          <div class="subtask-detail-prompt">${esc(prompt)}</div>
        </div>
        <div class="subtask-detail-row">
          <span class="subtask-detail-label">状态:</span>
          <span class="subtask-detail-status">${esc(status)}</span>
        </div>
        ${dur ? `<div class="subtask-detail-row">
          <span class="subtask-detail-label">耗时:</span><span>${esc(dur)}</span>
        </div>` : ""}
        ${error ? `<div class="subtask-detail-row">
          <span class="subtask-detail-label">错误:</span>
          <div class="subtask-detail-error">${esc(error)}</div>
        </div>` : ""}
        ${result ? `<div class="subtask-detail-row">
          <span class="subtask-detail-label">结果:</span>
          <div class="subtask-detail-result">${esc(result)}</div>
        </div>` : ""}
      </div>`;
  }

  /** 其余工具：折叠行（工具名 + 耗时 + 可展开的参数/结果）。 */
  function renderGenericToolCall(entry) {
    const name = esc(entry.toolName || "tool");
    const dur = fmtDuration(entry.durationMs);
    const isErr = !!entry.isError;
    const argsJson = JSON.stringify(entry.arguments || {}, null, 2);
    const result = String(entry.result || "");
    const truncatedNote = entry.truncated
      ? `<div class="tool-call-truncated">… (已截断，完整内容见 admin 流水页)</div>`
      : "";

    return `
      <div class="tool-call-row${isErr ? " tool-call-error" : ""}"
           data-tool-call-id="${esc(entry.toolCallId)}">
        <div class="tool-call-head">
          <span class="tool-call-icon">🔧</span>
          <span class="tool-call-name">${name}</span>
          ${dur ? `<span class="tool-call-duration">${esc(dur)}</span>` : ""}
          ${isErr ? `<span class="tool-call-error-badge">失败</span>` : ""}
          <span class="tool-call-toggle">▸</span>
        </div>
        <div class="tool-call-body" style="display:none">
          <div class="tool-call-section-label">参数</div>
          <pre class="tool-call-args">${esc(argsJson)}</pre>
          <div class="tool-call-section-label">结果</div>
          <pre class="tool-call-result">${esc(result)}</pre>
          ${truncatedNote}
        </div>
      </div>`;
  }

  /**
   * 渲染一个 TOOL_CALL 条目。
   *
   * @param entry 后端注入的工具调用条目
   * @param subtaskEvent 可选 —— 若该工具调用对应一个 SUBTASK 事件（由 id 匹配），
   *                     传进来可渲染出带状态/耗时的完整 chip
   * @returns {string} HTML 字符串
   */
  function render(entry, subtaskEvent) {
    if (!entry || entry.messageType !== "TOOL_CALL") return "";
    const name = entry.toolName || "";
    if (name === "askUser") return renderAskUserCard(entry);
    if (name === "startSubTask" || name === "start_sub_task") {
      return renderSubtaskChip(entry, subtaskEvent);
    }
    return renderGenericToolCall(entry);
  }

  /**
   * 绑定折叠交互（只读卡片无交互，故只处理 chip / 通用行的展开收起）。
   * 幂等 —— 重复调用不会重复绑定（用 dataset 标记）。
   */
  function bindInteractions(root) {
    if (!root) return;
    root.querySelectorAll(".tool-call-row, .subtask-chip-history").forEach((el) => {
      if (el.dataset.tcBound === "1") return;
      el.dataset.tcBound = "1";
      el.style.cursor = "pointer";
      el.addEventListener("click", () => {
        // 通用工具行：切换 .tool-call-body
        const body = el.parentElement && el.parentElement.querySelector(".tool-call-body");
        if (body) {
          const open = body.style.display !== "none";
          body.style.display = open ? "none" : "block";
          const tg = el.querySelector(".tool-call-toggle");
          if (tg) tg.textContent = open ? "▸" : "▾";
          return;
        }
        // 子任务 chip：切换紧随其后的 .subtask-detail
        const detail = el.nextElementSibling;
        if (detail && detail.classList.contains("subtask-detail")) {
          const open = detail.style.display !== "none";
          detail.style.display = open ? "none" : "block";
          const tg = el.querySelector(".subtask-chip-expand");
          if (tg) tg.textContent = open ? "▸" : "▾";
        }
      });
    });
  }

  window.ToolCallRenderer = {
    render: render,
    bindInteractions: bindInteractions,
    // 导出给单测/调试
    _internal: { esc: esc, fmtDuration: fmtDuration, parseOptions: parseOptions },
  };
})();
