(function () {
  "use strict";

  const tableContainer = document.getElementById("knowledge-table-container");
  const pendingChipContainer = document.getElementById("pending-chip-container");
  const API = {
    update: (id) =>
      `/spring/ai/loom/admin/market-knowledge/${encodeURIComponent(id)}`,
    del: (id) =>
      `/spring/ai/loom/admin/market-knowledge/${encodeURIComponent(id)}`,
  };

  let currentEdit = null;
  let allKnowledge = [];

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
    if (!el) return;
    el.textContent = text;
    el.className = "toast show " + type;
    setTimeout(() => {
      el.className = "toast";
    }, 2500);
  }

  function showErr(msg) {
    const el = document.getElementById("ek-error");
    if (!el) return;
    el.textContent = msg;
    el.style.display = "block";
  }

  function confirmDialog({ title, message, okText = "确定" }) {
    return new Promise((resolve) => {
      const titleEl = document.getElementById("confirm-title");
      const messageEl = document.getElementById("confirm-message");
      const ok = document.getElementById("confirm-ok");
      const cancel = document.getElementById("confirm-cancel");
      const close = document.getElementById("confirm-close");
      const overlay = document.getElementById("confirm-modal");
      if (!titleEl || !messageEl || !ok || !cancel || !close || !overlay) {
        resolve(false);
        return;
      }

      titleEl.textContent = title;
      messageEl.textContent = message;
      ok.textContent = okText;
      overlay.style.display = "flex";
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
   * Counts items whose status is PENDING and hides the chip when none exist.
   */
  function renderPendingChip(items) {
    if (!pendingChipContainer) return;
    const list = Array.isArray(items) ? items : [];
    const count = list.filter(
      (m) => String(m.status || "").toUpperCase() === "PENDING",
    ).length;
    if (count > 0) {
      pendingChipContainer.innerHTML =
        '<div class="pending-chip pending-chip-red">待审核 (' +
        count +
        ")</div>";
      pendingChipContainer.style.display = "";
    } else {
      pendingChipContainer.innerHTML = "";
      pendingChipContainer.style.display = "none";
    }
  }

  /**
   * The v2 admin endpoint returns a Page object while the legacy endpoint
   * returns an array. Accept both shapes so either route remains usable.
   */
  function listItems(payload) {
    if (Array.isArray(payload)) return payload;
    if (payload && Array.isArray(payload.items)) return payload.items;
    return [];
  }

  async function loadList() {
    if (!tableContainer) return;
    tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
    try {
      // M0 T11/T13: route list fetch through the shared MarketAdmin namespace.
      allKnowledge = listItems(await MarketAdmin.list("KNOWLEDGE"));
      renderPendingChip(allKnowledge);
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
   * Columns (M0 T13): 名称 / 状态 / 作者 / 官方 / 排序 / 分类 / 提交时间 / 操作.
   * The KB owner field is `username` (not the Skill-side `author` field).
   * Approval controls are intentionally deferred to T18; edit and 下架 remain.
   */
  function renderTable() {
    if (!allKnowledge || allKnowledge.length === 0) {
      tableContainer.innerHTML =
        '<div class="empty-state">市场暂无任何知识库</div>';
      return;
    }

    const STATUS_ORDER = { PENDING: 0, APPROVED: 1, REJECTED: 2, WITHDRAWN: 3 };
    const sorted = [...allKnowledge].sort((a, b) => {
      const sa = STATUS_ORDER[String(a.status || "").toUpperCase()] ?? 99;
      const sb = STATUS_ORDER[String(b.status || "").toUpperCase()] ?? 99;
      if (sa !== sb) return sa - sb;
      return (
        String(a.username || "") + String(a.name || "")
      ).localeCompare(String(b.username || "") + String(b.name || ""));
    });

    const rows = sorted
      .map((m) => {
        const id = m.id;
        const official = m.isOfficial ?? m.is_official;
        const rankValue = m.featuredRank ?? m.featured_rank;
        const categoryValue = m.category ?? "";
        const submittedAt = m.submittedAt ?? m.submitted_at;
        const reviewer = m.reviewer ?? m.reviewedBy ?? m.reviewed_by;
        const reviewComment = m.reviewComment ?? m.review_comment;
        const officialMark = official ? "🏛️" : "—";
        const rank =
          rankValue == null || rankValue === ""
            ? "—"
            : escapeHtml(rankValue);
        const category = categoryValue
          ? escapeHtml(categoryValue)
          : "—";
        const submitted = submittedAt
          ? escapeHtml(String(submittedAt).slice(0, 16).replace("T", " "))
          : "—";
        const statusBadge = MarketAdmin.approvalBadge(
          m.status,
          reviewer,
          m.reviewedAt ?? m.reviewed_at,
          reviewComment,
        );
        return `<tr data-id="${escapeHtml(id)}">
 <td><strong>${escapeHtml(m.name)}</strong></td>
 <td>${statusBadge}</td>
 <td>${escapeHtml(m.username)}</td>
 <td>${officialMark}</td>
 <td>${rank}</td>
 <td>${category}</td>
 <td>${submitted}</td>
 <td>
 <button class="secondary-btn edit-btn" data-id="${escapeHtml(id)}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑</button>
 <button class="delete-btn del-btn btn-sm" data-id="${escapeHtml(id)}">下架</button>
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
        openEdit(btn.getAttribute("data-id")),
      );
    });
    tableContainer.querySelectorAll(".del-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        deleteKnowledge(btn.getAttribute("data-id")),
      );
    });
  }

  function openEdit(id) {
    const entry = allKnowledge.find(
      (item) => String(item.id) === String(id),
    );
    if (!entry) return;

    currentEdit = { id: entry.id };
    document.getElementById("edit-knowledge-title").textContent = "编辑知识库";
    document.getElementById("ek-name").value = entry.name || "";
    document.getElementById("ek-name").disabled = true;
    document.getElementById("ek-description").value = entry.description || "";
    document.getElementById("ek-category").value = entry.category || "";
    const rank = entry.featuredRank ?? entry.featured_rank;
    document.getElementById("ek-featured-rank").value =
      rank == null ? "" : rank;
    document.getElementById("ek-official").checked = Boolean(
      entry.isOfficial ?? entry.is_official,
    );
    document.getElementById("ek-error").style.display = "none";
    document.getElementById("edit-knowledge-modal").style.display = "flex";
  }

  function closeEdit() {
    document.getElementById("edit-knowledge-modal").style.display = "none";
    currentEdit = null;
  }

  async function saveEdit() {
    const name = document.getElementById("ek-name").value.trim();
    const description = document
      .getElementById("ek-description")
      .value.trim();
    const category = document.getElementById("ek-category").value.trim();
    const rankText = document.getElementById("ek-featured-rank").value.trim();
    const isOfficial = document.getElementById("ek-official").checked;

    if (!name) {
      showErr("名称不能为空");
      return;
    }
    if (!currentEdit) {
      showErr("控制台不再新建知识库");
      return;
    }

    let featuredRank = null;
    if (rankText !== "") {
      featuredRank = Number(rankText);
      if (!Number.isInteger(featuredRank) || featuredRank < 0) {
        showErr("精选排序必须是非负整数");
        return;
      }
    }

    const body = {
      name,
      description,
      category: category || null,
      isOfficial,
      featuredRank,
    };
    try {
      const response = await fetch(API.update(currentEdit.id), {
        method: "PUT",
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const text = await response.text();
        showErr("保存失败：" + (text || "HTTP " + response.status));
        return;
      }
      showToast("已保存", "success");
      closeEdit();
      await loadList();
    } catch (e) {
      showErr("网络错误：" + e.message);
    }
  }

  async function deleteKnowledge(id) {
    const entry = allKnowledge.find(
      (item) => String(item.id) === String(id),
    );
    if (!entry) return;
    const ok = await confirmDialog({
      title: "下架市场知识库",
      message: `确定要下架「${entry.name}」（作者：${entry.username}）？\n\n会同时清理所有 user_knowledge / role_knowledge 中对它的引用。`,
      okText: "下架",
    });
    if (!ok) return;

    try {
      const response = await fetch(API.del(id), {
        method: "DELETE",
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
      });
      if (!response.ok) {
        const text = await response.text();
        showToast("下架失败：" + (text || "HTTP " + response.status), "error");
        return;
      }
      showToast("已下架", "success");
      await loadList();
    } catch (e) {
      showToast("网络错误：" + e.message, "error");
    }
  }

  document.getElementById("refresh-btn")?.addEventListener("click", loadList);
  document
    .getElementById("edit-knowledge-close")
    ?.addEventListener("click", closeEdit);
  document
    .getElementById("ek-cancel")
    ?.addEventListener("click", closeEdit);
  document.getElementById("ek-save")?.addEventListener("click", saveEdit);

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", loadList);
  } else {
    loadList();
  }

  fetch("/spring/ai/loom/user/currentUser", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json; charset=UTF-8" },
  })
    .then((response) => (response.ok ? response.json() : null))
    .then((me) => {
      if (me) {
        const el = document.getElementById("admin-username");
        if (el) {
          el.textContent = `${me.nickname || me.username}（${me.type === "ADMIN" ? "管理员" : "用户"}）`;
        }
      }
    })
    .catch(() => {});
})();
