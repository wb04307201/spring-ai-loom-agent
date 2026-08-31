(function () {
  "use strict";

  const tableContainer = document.getElementById("skill-table-container");
  const API = {
    list: "/spring/ai/loom/admin/market-skills",
    create: "/spring/ai/loom/admin/market-skills",
    update: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
    del: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
  };

  let currentEdit = null; // null = 新建; {id} = 编辑
  let allSkills = [];

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function showToast(text, type = "success") {
    const el = document.getElementById("toast-notification");
    el.textContent = text;
    el.className = "toast show " + type;
    setTimeout(() => {
      el.className = "toast";
    }, 2500);
  }

  function showErr(msg) {
    const e = document.getElementById("es-error");
    e.textContent = msg;
    e.style.display = "block";
  }

  function confirmDialog({ title, message, okText = "确定" }) {
    return new Promise((resolve) => {
      document.getElementById("confirm-title").textContent = title;
      document.getElementById("confirm-message").textContent = message;
      document.getElementById("confirm-ok").textContent = okText;
      const overlay = document.getElementById("confirm-modal");
      overlay.style.display = "flex";
      const ok = document.getElementById("confirm-ok");
      const cancel = document.getElementById("confirm-cancel");
      const close = document.getElementById("confirm-close");
      const onOk = () => {
        cleanup();
        overlay.style.display = "none";
        resolve(true);
      };
      const onCancel = () => {
        cleanup();
        overlay.style.display = "none";
        resolve(false);
      };
      ok.onclick = onOk;
      cancel.onclick = onCancel;
      close.onclick = onCancel;
      function cleanup() {
        ok.onclick = null;
        cancel.onclick = null;
        close.onclick = null;
      }
    });
  }

  async function loadList() {
    tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      const r = await fetch(API.list, { credentials: "include" });
      if (r.status === 401 || r.status === 403) {
        window.location.replace("/spring/ai/loom/index.html");
        return;
      }
      if (!r.ok) throw new Error("HTTP " + r.status);
      allSkills = await r.json();
      renderTable();
    } catch (e) {
      tableContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
    }
  }

  /**
   * 去掉审批流 —— 提交即上架。
   * 所有列出的市场 Skill 都是 APPROVED（用户提交 + admin 创建）。
   * 表格按 author, name 排序。
   */
  function renderTable() {
    if (!allSkills || allSkills.length === 0) {
      tableContainer.innerHTML =
        '<div class="empty-state">市场暂无任何技能</div>';
      return;
    }
    const sorted = [...allSkills].sort((a, b) =>
      (a.author + a.name).localeCompare(b.author + b.name),
    );
    const rows = sorted
      .map((m) => {
        return `<tr data-id="${m.id}">
 <td><strong>${escapeHtml(m.name)}</strong></td>
 <td>${escapeHtml(m.description || "（无）")}</td>
 <td>${escapeHtml(m.author)}</td>
 <td>${m.reviewedAt ? escapeHtml(m.reviewedAt.slice(0, 16).replace("T", " ")) : "-"}</td>
 <td>
 <button class="secondary-btn edit-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑</button>
 <button class="delete-btn del-btn btn-sm" data-id="${m.id}">下架</button>
 </td>
 </tr>`;
      })
      .join("");
    tableContainer.innerHTML = `
 <table class="user-table">
 <thead><tr><th>名称</th><th>描述</th><th>作者</th><th>上架时间</th><th>操作</th></tr></thead>
 <tbody>${rows}</tbody>
 </table>`;
    bindRowActions();
  }

  function bindRowActions() {
    tableContainer.querySelectorAll(".edit-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openEdit(parseInt(btn.getAttribute("data-id"))),
      );
    });
    tableContainer.querySelectorAll(".del-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        deleteSkill(parseInt(btn.getAttribute("data-id"))),
      );
    });
  }

  function openEdit(id) {
    const m = allSkills.find((x) => x.id === id);
    if (!m) return;
    currentEdit = { id };
    document.getElementById("edit-skill-title").textContent = "编辑技能";
    document.getElementById("es-name").value = m.name;
    document.getElementById("es-name").disabled = true; // 编辑模式：name 不能改（PK 关联）
    document.getElementById("es-desc").value = m.description || "";
    document.getElementById("es-content").value = m.content || "";
    document.getElementById("es-error").style.display = "none";
    document.getElementById("edit-skill-modal").style.display = "flex";
  }

  // 删除 openCreate() —— admin 控制台不再新建技能

  function closeEdit() {
    document.getElementById("edit-skill-modal").style.display = "none";
    currentEdit = null;
  }

  async function saveEdit() {
    const name = document.getElementById("es-name").value.trim();
    const desc = document.getElementById("es-desc").value.trim();
    const content = document.getElementById("es-content").value;
    if (!name || !content.trim()) {
      showErr("名称、内容不能为空");
      return;
    }
    if (!currentEdit) {
      // UI 已去掉新建按钮，但作为防御性兜底，禁止 saveEdit 在没 currentEdit 时提交
      showErr("控制台不再新建技能");
      return;
    }
    const body = { name, description: desc, content, status: "APPROVED" };
    try {
      const r = await fetch(API.update(currentEdit.id), {
        method: "PUT",
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
        body: JSON.stringify(body),
      });
      if (!r.ok) {
        const t = await r.text();
        showErr("保存失败：" + (t || "HTTP " + r.status));
        return;
      }
      showToast("已保存", "success");
      closeEdit();
      loadList();
    } catch (e) {
      showErr("网络错误：" + e.message);
    }
  }

  async function deleteSkill(id) {
    const m = allSkills.find((x) => x.id === id);
    if (!m) return;
    const ok = await confirmDialog({
      title: "下架市场技能",
      message: `确定要下架「${m.name} v${m.version}」（作者：${m.author}）？\n\n会同时清理所有 user_skill / role_skill 里对它的引用（拉取者将无法再访问该技能）。`,
      okText: "下架",
    });
    if (!ok) return;
    try {
      const r = await fetch(API.del(id), {
        method: "DELETE",
        credentials: "include",
      });
      if (!r.ok) {
        const t = await r.text();
        showToast("下架失败：" + (t || "HTTP " + r.status), "error");
        return;
      }
      showToast("已下架", "success");
      loadList();
    } catch (e) {
      showToast("网络错误：" + e.message, "error");
    }
  }

  // 事件
  // 去掉 create-skill-btn 事件绑定（不再新建）
  document.getElementById("refresh-btn").addEventListener("click", loadList);
  document
    .getElementById("edit-skill-close")
    .addEventListener("click", closeEdit);
  document.getElementById("es-cancel").addEventListener("click", closeEdit);
  document.getElementById("es-save").addEventListener("click", saveEdit);

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", loadList);
  } else {
    loadList();
  }

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
})();
