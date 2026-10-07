(function () {
  "use strict";

  const yearInput = document.getElementById("year-input");
  const monthInput = document.getElementById("month-input");
  const barChart = document.getElementById("bar-chart");
  const statsTable = document.getElementById("stats-table");
  const monthLabel = document.getElementById("month-label");

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
        // 2026-10-07: 去掉跳转链接 —— 本 tab 是纯统计视图。
        // 进用户对话的入口统一在「对话日志」tab（那里不依赖当月是否有用量,
        // 新部署/无数据时也能进）。此前这里和下方表格各有一个 user.html 链接,
        // 与对话日志 tab 构成三处重复入口。
        return `<div class="bar-row">
 <div class="bar-label">${escapeHtml(r.username)}</div>
 <div class="bar-track"><div class="bar-fill" style="width: ${pct}%"></div></div>
 <div class="bar-value">${r.totalTokens.toLocaleString()}</div>
 </div>`;
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
 <td>${escapeHtml(r.username)}</td>
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

  document.getElementById("reload-btn").addEventListener("click", load);
  document.getElementById("refresh-btn").addEventListener("click", load);

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

  // ===== Tab 切换（2026-10-07 新增）=====
  // 「Token 用量」与「对话日志」两个面板。对话日志 tab 首次激活时才拉用户列表
  // （避免每次打开日志页都多打一次 /admin/users）。
  const convUserTable = document.getElementById("conv-user-table");
  let convTabLoaded = false;

  function switchTab(name) {
    document.querySelectorAll(".admin-tab").forEach((b) => {
      const on = b.dataset.tab === name;
      b.classList.toggle("active", on);
      b.setAttribute("aria-selected", on ? "true" : "false");
    });
    document.querySelectorAll(".admin-tab-panel").forEach((p) => {
      p.style.display = p.id === "tab-" + name ? "" : "none";
    });
    if (name === "conversations" && !convTabLoaded) {
      convTabLoaded = true;
      loadConvUsers();
    }
  }

  document.querySelectorAll(".admin-tab").forEach((b) => {
    b.addEventListener("click", () => switchTab(b.dataset.tab));
  });

  /**
   * 对话日志 tab：用户列表。
   * 点用户名 → user.html（会话列表）→ conversation.html（对话流水）。
   * 这条链路原本藏在「用户管理」页里 —— 归位到日志页（审计视角更合理）。
   */
  async function loadConvUsers() {
    if (!convUserTable) return;
    convUserTable.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      const r = await fetch("/spring/ai/loom/admin/users", {
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
      });
      if (!r.ok) {
        convUserTable.innerHTML =
          '<div class="empty-state">加载失败：HTTP ' + r.status + "</div>";
        return;
      }
      const users = await r.json();
      renderConvUsers(Array.isArray(users) ? users : []);
    } catch (e) {
      convUserTable.innerHTML =
        '<div class="empty-state">加载失败：' + escapeHtml(e.message) + "</div>";
    }
  }

  function renderConvUsers(users) {
    if (!users.length) {
      convUserTable.innerHTML = '<div class="empty-state">暂无用户</div>';
      return;
    }
    const rows = users
      .map((u) => {
        const typeLabel = u.type === "ADMIN" ? "管理员" : "普通用户";
        return (
          '<tr data-username="' + escapeHtml(u.username) + '">' +
          '<td><a class="user-link" href="user.html?username=' +
          encodeURIComponent(u.username) +
          '" title="查看该用户的会话与对话流水"><strong>' +
          escapeHtml(u.username) +
          "</strong></a></td>" +
          "<td>" + escapeHtml(u.nickname || "") + "</td>" +
          '<td><span class="type-badge ' + escapeHtml(u.type) + '">' +
          typeLabel + "</span></td>" +
          "</tr>"
        );
      })
      .join("");
    convUserTable.innerHTML =
      '<table class="user-table">' +
      "<thead><tr><th>用户名</th><th>昵称</th><th>类型</th></tr></thead>" +
      "<tbody>" + rows + "</tbody></table>";
  }

  const convRefreshBtn = document.getElementById("conv-refresh-btn");
  if (convRefreshBtn) {
    convRefreshBtn.addEventListener("click", () => {
      convTabLoaded = true;
      loadConvUsers();
    });
  }

  load();
})();
