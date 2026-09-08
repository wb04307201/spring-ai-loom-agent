(function () {
  "use strict";

  const yearInput = document.getElementById("year-input");
  const monthInput = document.getElementById("month-input");
  const barChart = document.getElementById("bar-chart");
  const statsTable = document.getElementById("stats-table");
  const monthLabel = document.getElementById("month-label");
  const askLogsTable = document.getElementById("ask-logs-table");
  const askLogsUser = document.getElementById("ask-logs-user");

  const now = new Date();
  yearInput.value = now.getFullYear();
  monthInput.value = now.getMonth() + 1;

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  async function load() {
    const year = parseInt(yearInput.value);
    const month = parseInt(monthInput.value);
    if (!year || !month || month < 1 || month > 12) {
      alert("请输入有效的年月");
      return;
    }
    monthLabel.textContent = `${year}-${String(month).padStart(2, "0")} 月用量`;
    statsTable.innerHTML = '<div class="loading-indicator">加载中...</div>';
    barChart.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      const r = await fetch(
        `/spring/ai/loom/admin/stats/tokens/monthly?year=${year}&month=${month}`,
        {
          credentials: "include",
        },
      );
      if (r.status === 401) {
        window.location.replace("/spring/ai/loom/login.html");
        return;
      }
      if (!r.ok) {
        statsTable.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
        barChart.innerHTML = '<div class="empty-state">-</div>';
        return;
      }
      const list = await r.json();
      renderBarChart(list);
      renderTable(list, year, month);
    } catch (e) {
      statsTable.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
      barChart.innerHTML = '<div class="empty-state">-</div>';
    }
  }

  function renderBarChart(list) {
    if (!list || list.length === 0) {
      barChart.innerHTML = '<div class="empty-state">本月无用量</div>';
      return;
    }
    const max = Math.max(1, ...list.map((r) => r.totalTokens));
    barChart.innerHTML = list
      .map((r) => {
        const pct = ((r.totalTokens / max) * 100).toFixed(1);
        // ：整行包 <a>，可点击跳 user.html
        return `<a class="bar-row" href="user.html?username=${encodeURIComponent(r.username)}">
 <div class="bar-label">${escapeHtml(r.username)}</div>
 <div class="bar-track"><div class="bar-fill" style="width: ${pct}%"></div></div>
 <div class="bar-value">${r.totalTokens.toLocaleString()}</div>
 </a>`;
      })
      .join("");
  }

  function renderTable(list, year, month) {
    if (!list || list.length === 0) {
      statsTable.innerHTML = `<div class="empty-state">${year}-${month} 无用量记录</div>`;
      return;
    }
    const totalAll = list.reduce((s, r) => s + r.totalTokens, 0);
    const rows = list
      .map((r) => {
        const pct =
          totalAll > 0 ? ((r.totalTokens / totalAll) * 100).toFixed(1) : "0.0";
        return `<tr>
 <td><a class="user-link" href="user.html?username=${encodeURIComponent(r.username)}">${escapeHtml(r.username)}</a></td>
 <td>${r.callCount.toLocaleString()}</td>
 <td>${r.promptTokens.toLocaleString()}</td>
 <td>${r.completionTokens.toLocaleString()}</td>
 <td><strong>${r.totalTokens.toLocaleString()}</strong></td>
 <td>${pct}%</td>
 </tr>`;
      })
      .join("");
    statsTable.innerHTML = `
 <table class="user-table">
 <thead>
 <tr><th>用户</th><th>调用次数</th><th>输入 Token</th><th>输出 Token</th><th>总 Token</th><th>占比</th></tr>
 </thead>
 <tbody>${rows}</tbody>
 <tfoot>
 <tr style="font-weight: 600; background: var(--bg-secondary);">
 <td>合计</td>
 <td>${list.reduce((s, r) => s + r.callCount, 0).toLocaleString()}</td>
 <td>${list.reduce((s, r) => s + r.promptTokens, 0).toLocaleString()}</td>
 <td>${list.reduce((s, r) => s + r.completionTokens, 0).toLocaleString()}</td>
 <td>${totalAll.toLocaleString()}</td>
 <td>100%</td>
 </tr>
 </tfoot>
 </table>`;
  }

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

  document.getElementById("reload-btn").addEventListener("click", load);
  document.getElementById("refresh-btn").addEventListener("click", load);
  document.getElementById("ask-logs-refresh").addEventListener("click", loadAskLogs);
  askLogsUser.addEventListener("keydown", (e) => {
    if (e.key === "Enter") loadAskLogs();
  });

  // 顶部右侧渲染当前用户名（统一 header 风格）
  fetch("/spring/ai/loom/user/currentUser", {
    method: "POST",
    credentials: "include",
  })
    .then((r) => (r.ok ? r.json() : null))
    .then((me) => {
      if (me) {
        const el = document.getElementById("admin-username");
        if (el)
          el.textContent = `${me.nickname || me.username}（${me.type === "ADMIN" ? "管理员" : "用户"}）`;
      }
    })
    .catch(() => {});

  load();
  loadAskLogs();
})();
