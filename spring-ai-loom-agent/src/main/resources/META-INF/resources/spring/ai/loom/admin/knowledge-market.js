/**
 * 管理台 · 知识库市场（无审批流）
 * 列出 + 下架（DELETE 级联清理 user_knowledge + role_knowledge）
 */
(function () {
  "use strict";

  const ENDPOINTS = {
    list: "/spring/ai/loom/admin/market-knowledge",
    remove: (marketId) =>
      `/spring/ai/loom/admin/market-knowledge/${encodeURIComponent(marketId)}`,
  };

  /** 通用 fetch 封装：带 cookie，错误统一捕获 */
  async function api(url, options = {}) {
    const resp = await fetch(url, {
      credentials: "include",
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });
    let body = null;
    try {
      body = await resp.json();
    } catch (_) {
      body = null;
    }
    if (!resp.ok) {
      const msg = (body && body.error) || `HTTP ${resp.status}`;
      throw new Error(msg);
    }
    return body;
  }

  /** Toast 提示 */
  function toast(text, type) {
    const t = document.getElementById("toast-notification");
    if (!t) return;
    t.textContent = text;
    t.className =
      "toast" +
      (type === "error" ? " error" : type === "success" ? " success" : "");
    t.style.opacity = "1";
    clearTimeout(toast._t);
    toast._t = setTimeout(() => {
      t.style.opacity = "0";
    }, 2500);
  }

  /** 截断长文本 */
  function clamp(s, n) {
    if (s == null) return "";
    s = String(s);
    return s.length > n ? s.slice(0, n) + "…" : s;
  }

  /** 转义 HTML */
  function esc(s) {
    if (s == null) return "";
    return String(s).replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  }

  /** 渲染整张表格（无 PENDING 状态，无审批按钮） */
  function render(items) {
    const c = document.getElementById("knowledge-table-container");
    if (!c) return;
    if (!items || items.length === 0) {
      c.innerHTML = '<div class="empty-state">暂无市场知识库</div>';
      return;
    }

    const rows = items
      .map((k) => {
        const reviewedAt = k.reviewedAt
          ? k.reviewedAt.slice(0, 16).replace("T", " ")
          : "-";
        const reviewedBy = k.reviewedBy || "-";
        return `
 <tr data-id="${esc(k.id)}">
 <td><div style="font-weight:500;">${esc(k.name || "")}</div></td>
 <td><div style="max-width:300px;">${esc(clamp(k.description || "", 80))}</div></td>
 <td>${esc(k.username || "")}</td>
 <td><span class="type-badge ADMIN">${esc(reviewedBy)}</span><br><span class="text-muted" style="font-size:11px;">${esc(reviewedAt)}</span></td>
 <td class="row-actions">
 <button class="delete-btn" data-act="delete" data-id="${esc(k.id)}">下架</button>
 </td>
 </tr>`;
      })
      .join("");

    c.innerHTML = `
 <table class="user-table">
 <thead>
 <tr>
 <th style="width:20%;">名称</th>
 <th style="width:35%;">描述</th>
 <th style="width:12%;">作者</th>
 <th style="width:18%;">上架时间 / 人</th>
 <th style="width:15%;">操作</th>
 </tr>
 </thead>
 <tbody>${rows}</tbody>
 </table>`;
  }

  /** 加载列表 */
  async function load() {
    const c = document.getElementById("knowledge-table-container");
    if (c) c.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      const items = await api(ENDPOINTS.list);
      render(items || []);
    } catch (e) {
      toast("加载失败：" + e.message, "error");
      if (c)
        c.innerHTML = `<div class="empty-state error">加载失败：${esc(e.message)}</div>`;
    }
  }

  /** 确认对话框 */
  function confirmDialog(title, message, okText = "确定") {
    return new Promise((resolve) => {
      const m = document.getElementById("confirm-modal");
      document.getElementById("confirm-title").textContent = title;
      document.getElementById("confirm-message").textContent = message;
      const ok = document.getElementById("confirm-ok");
      const cancel = document.getElementById("confirm-cancel");
      const closeBtn = document.getElementById("confirm-close");
      ok.textContent = okText;
      const cleanup = (result) => {
        m.style.display = "none";
        ok.removeEventListener("click", onOk);
        cancel.removeEventListener("click", onCancel);
        closeBtn.removeEventListener("click", onCancel);
        resolve(result);
      };
      const onOk = () => cleanup(true);
      const onCancel = () => cleanup(false);
      ok.addEventListener("click", onOk);
      cancel.addEventListener("click", onCancel);
      closeBtn.addEventListener("click", onCancel);
      m.style.display = "flex";
    });
  }

  /** 下架（admin 删除） */
  async function doDelete(id, name) {
    const ok = await confirmDialog(
      "下架市场知识库",
      `确定要下架「${name}」吗？\n\n会同时清理所有用户订阅（user_knowledge）和角色授权（role_knowledge）。`,
      "下架",
    );
    if (!ok) return;
    try {
      await api(ENDPOINTS.remove(id), { method: "DELETE" });
      toast("已下架", "success");
      await load();
    } catch (e) {
      toast("下架失败：" + e.message, "error");
    }
  }

  /** 事件绑定 */
  function bind() {
    document.getElementById("refresh-btn")?.addEventListener("click", load);
    document
      .getElementById("knowledge-table-container")
      ?.addEventListener("click", async (e) => {
        const btn = e.target.closest("button[data-act]");
        if (!btn) return;
        const id = btn.dataset.id;
        const act = btn.dataset.act;
        if (act === "delete") {
          const items = await api(ENDPOINTS.list).catch(() => []);
          const rec = (items || []).find((x) => String(x.id) === String(id));
          doDelete(id, rec ? rec.name : `#${id}`);
        }
      });
  }

  document.addEventListener("DOMContentLoaded", () => {
    bind();
    load();
  });
})();
