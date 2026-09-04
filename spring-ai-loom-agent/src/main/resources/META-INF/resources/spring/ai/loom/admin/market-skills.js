(function () {
  "use strict";

  const tableContainer = document.getElementById("skill-table-container");
  const pendingChipContainer = document.getElementById("pending-chip-container");
  const API = {
    list: "/spring/ai/loom/admin/market-skills",
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

  /**
   * Render the top-of-page red "待审核 (N)" chip.
   * Counts items whose status === "PENDING". Hidden if zero.
   * Inserted into #pending-chip-container (declared in market-skills.html).
   */
  function renderPendingChip(items) {
    if (!pendingChipContainer) return;
    const n = items.filter(
      (m) => String(m.status || "").toUpperCase() === "PENDING",
    ).length;
    if (n > 0) {
      pendingChipContainer.innerHTML =
        '<div class="pending-chip pending-chip-red">待审核 (' +
        n +
        ")</div>";
      pendingChipContainer.style.display = "";
    } else {
      pendingChipContainer.innerHTML = "";
      pendingChipContainer.style.display = "none";
    }
  }

  async function loadList() {
    tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      // M0 T11/T12: route list fetch through MarketAdmin.list (single source of truth).
      // Throws on network/auth/HTTP failure (handles 401/403 redirect internally).
      allSkills = await MarketAdmin.list("SKILL");
      renderPendingChip(allSkills);
      renderTable();
    } catch (e) {
      tableContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
      if (pendingChipContainer) {
        pendingChipContainer.innerHTML = "";
        pendingChipContainer.style.display = "none";
      }
    }
  }

  /**
   * Render the table.
   *
   * Columns (M0 T12): 名称 / 状态 / 作者 / 官方 / 排序 / 分类 / 提交时间 / 操作
   * - 状态: MarketAdmin.approvalBadge(status, reviewer, reviewedAt, comment)
   * - 官方: 🏛️ if isOfficial truthy, else "—"
   * - 排序: featuredRank (or "—" if null/empty)
   * - 分类: category (or "—" if null/empty)
   * - 提交时间: submittedAt (truncated to minutes)
   *
   * Sort: 状态升序 (PENDING first → APPROVED → REJECTED → WITHDRAWN), then author+name.
   * Filter: all statuses visible (admin can review/re-approve/etc.).
   */
  function renderTable() {
    if (!allSkills || allSkills.length === 0) {
      tableContainer.innerHTML =
        '<div class="empty-state">市场暂无任何技能</div>';
      return;
    }
    const STATUS_ORDER = { PENDING: 0, APPROVED: 1, REJECTED: 2, WITHDRAWN: 3 };
    const sorted = [...allSkills].sort((a, b) => {
      const sa = STATUS_ORDER[String(a.status || "").toUpperCase()] ?? 99;
      const sb = STATUS_ORDER[String(b.status || "").toUpperCase()] ?? 99;
      if (sa !== sb) return sa - sb;
      return (a.author + a.name).localeCompare(b.author + b.name);
    });
    const rows = sorted
      .map((m) => {
        const officialMark = m.isOfficial ? "🏛️" : "—";
        const rank = m.featuredRank == null ? "—" : m.featuredRank;
        const category = m.category ? escapeHtml(m.category) : "—";
        const submittedAt = m.submittedAt
          ? escapeHtml(String(m.submittedAt).slice(0, 16).replace("T", " "))
          : "—";
        const statusBadge = MarketAdmin.approvalBadge(
          m.status,
          m.reviewer,
          m.reviewedAt,
          m.reviewComment,
        );
        return `<tr data-id="${m.id}">
 <td><strong>${escapeHtml(m.name)}</strong></td>
 <td>${statusBadge}</td>
 <td>${escapeHtml(m.author)}</td>
 <td>${officialMark}</td>
 <td>${rank}</td>
 <td>${category}</td>
 <td>${submittedAt}</td>
 <td>
 <button class="secondary-btn edit-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑</button>
 <button class="delete-btn del-btn btn-sm" data-id="${m.id}">下架</button>
 </td>
 </tr>`;
      })
      .join("");
    tableContainer.innerHTML = `
 <table class="user-table">
 <thead><tr><th>名称</th><th>状态</th><th>作者</th><th>官方</th><th>排序</th><th>分类</th><th>提交时间</th><th>操作</th></tr></thead>
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