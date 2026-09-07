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
      // M2 T21: 并行 fetch 每条记录的 tag,装饰到 row.tags,失败静默
      if (
        MarketAdmin &&
        typeof MarketAdmin.listWithTags === "function"
      ) {
        try {
          await MarketAdmin.listWithTags("KNOWLEDGE", allKnowledge);
        } catch (_) {
          // 静默 — 表仍可显示
        }
      }
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

  /** M2 T21: 把 tag 数组渲染成可读 chip 列表;空时显示 "—"。 */
  function renderTagChipsHtml(tags) {
    const list = Array.isArray(tags) ? tags.filter(Boolean) : [];
    if (list.length === 0) {
      return '<span style="color: var(--text-muted)">—</span>';
    }
    return (
      '<div class="tag-chip-group">' +
      list
        .map(
          (t) =>
            '<span class="tag-chip" data-tag="' +
            escapeHtml(String(t)) +
            '">' +
            escapeHtml(String(t)) +
            "</span>",
        )
        .join("") +
      "</div>"
    );
  }

  /**
   * Render the table.
   *
   * Columns (M0 T13 + T19 + Task 7): 名称 / 状态 / 作者 / 官方 / 排序 / 分类 / 提交时间 / 公告 / 评分 / 操作.
   * The KB owner field is `username` (not the Skill-side `author` field).
   * PENDING rows render 通过 / 拒绝 approval buttons (Task 7); edit and 下架 remain.
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
        // 公告 cell — T19
        const hasAnn =
          (m.announcementTitle && m.announcementBody) ||
          (m.announcement && m.announcement.title);
        const annCell = hasAnn
          ? '<span class="type-badge ADMIN" title="' +
            escapeHtml(
              (m.announcement && m.announcement.title) ||
                m.announcementTitle ||
                "",
            ) +
            '">📢 已发布</span>'
          : '<span style="color: var(--text-muted)">—</span>';
        // 评分 cell — T19
        const agg = m.aggregate || m.stats || {};
        const avg = Number(agg.avg || agg.ratingAvg || 0);
        const count = Number(agg.count || agg.ratingCount || 0);
        const ratingCell = count > 0
          ? `${MarketAdmin.renderStarWidget(Math.round(avg), true)}<span style="font-size:12px;color:var(--text-muted);margin-left:4px;">${count}</span>`
          : '<span style="color: var(--text-muted); font-size:12px;">无评价</span>';
        // Task 7: PENDING 行显示 通过 / 拒绝 审批按钮（KB id 是 String UUID,不 parseInt）
        const statusValue = String(m.status || "").toUpperCase();
        const approvalBtns = statusValue === "PENDING"
          ? `<button class="primary-btn approve-btn btn-sm" data-id="${escapeHtml(id)}" style="padding:4px 10px;font-size:12px;margin-right:4px;">通过</button>` +
            `<button class="delete-btn reject-btn btn-sm" data-id="${escapeHtml(id)}" style="margin-right:4px;">拒绝</button>`
          : "";
        return `<tr data-id="${escapeHtml(id)}">
 <td><strong>${escapeHtml(m.name)}</strong></td>
 <td>${statusBadge}</td>
 <td>${escapeHtml(m.username)}</td>
 <td>${officialMark}</td>
 <td>${rank}</td>
 <td>${category}</td>
 <td>${renderTagChipsHtml(m.tags)}</td>
 <td>${submitted}</td>
 <td>${annCell}<button class="secondary-btn ann-btn btn-sm" data-id="${escapeHtml(id)}" style="padding:2px 8px;font-size:11px;margin-left:6px;">${hasAnn ? "编辑" : "发布"}</button></td>
 <td>${ratingCell}<button class="secondary-btn reviews-btn btn-sm" data-id="${escapeHtml(id)}" style="padding:2px 8px;font-size:11px;margin-left:6px;">管理</button></td>
 <td>
 ${approvalBtns}
 <button class="secondary-btn tag-edit-row-btn btn-sm" data-id="${escapeHtml(id)}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑标签</button>
 <button class="secondary-btn edit-btn" data-id="${escapeHtml(id)}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑</button>
 <button class="delete-btn del-btn btn-sm" data-id="${escapeHtml(id)}">下架</button>
 </td>
 </tr>`;
      })
      .join("");

    tableContainer.innerHTML = `
 <table class="user-table">
 <thead><tr><th>名称</th><th>状态</th><th>作者</th><th>官方</th><th>排序</th><th>分类</th><th>标签</th><th>提交时间</th><th>公告</th><th>评分</th><th>操作</th></tr></thead>
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
    // Task 7: PENDING 行审批按钮（KB id 是 String UUID,不 parseInt）
    tableContainer.querySelectorAll(".approve-btn").forEach((btn) =>
      btn.addEventListener("click", async () => {
        if (!confirm("确认通过该知识库?")) return;
        try {
          await MarketAdmin.approve("KNOWLEDGE", btn.getAttribute("data-id"));
          showToast("已通过", "success");
          await loadList();
        } catch (e) {
          alert("通过失败: " + e.message);
        }
      }));
    tableContainer.querySelectorAll(".reject-btn").forEach((btn) =>
      btn.addEventListener("click", async () => {
        const comment = prompt("拒绝理由(必填):");
        if (comment === null) return;
        if (!comment.trim()) { alert("拒绝理由不能为空"); return; }
        try {
          await MarketAdmin.reject("KNOWLEDGE", btn.getAttribute("data-id"), comment);
          showToast("已拒绝", "success");
          await loadList();
        } catch (e) {
          alert("拒绝失败: " + e.message);
        }
      }));
    tableContainer.querySelectorAll(".del-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        deleteKnowledge(btn.getAttribute("data-id")),
      );
    });
    tableContainer.querySelectorAll(".ann-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openAnnouncement(btn.getAttribute("data-id")),
      );
    });
    tableContainer.querySelectorAll(".reviews-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openReviews(btn.getAttribute("data-id")),
      );
    });
    tableContainer.querySelectorAll(".tag-edit-row-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openTagEdit(btn.getAttribute("data-id")),
      );
    });
  }

  // ============== M2 T21: 标签批量编辑 ==============

  function parseTagInput(text) {
    // 接受中英文逗号、空格、顿号分隔。空标签和重复项忽略。
    if (!text) return [];
    const parts = String(text)
      .split(/[,，、 \t\r\n]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const seen = new Set();
    const out = [];
    for (const p of parts) {
      if (!seen.has(p)) {
        seen.add(p);
        out.push(p);
      }
    }
    return out;
  }

  function renderTagEditCurrent(tags) {
    const list = Array.isArray(tags) ? tags.filter(Boolean) : [];
    const el = document.getElementById("tag-edit-current");
    if (!el) return;
    if (list.length === 0) {
      el.innerHTML =
        '<div class="tag-edit-list-empty">（暂无标签）</div>';
      return;
    }
    el.innerHTML =
      '<div class="tag-chip-group">' +
      list
        .map(
          (t) =>
            '<span class="tag-chip tag-chip-removable">' +
            escapeHtml(String(t)) +
            "</span>",
        )
        .join("") +
      "</div>";
  }

  function openTagEdit(id) {
    const m = allKnowledge.find((x) => String(x.id) === String(id));
    if (!m) return;
    document.getElementById("tag-edit-title-text").textContent =
      "编辑标签：" + (m.name || ("#" + id));
    const existing =
      m && Array.isArray(m.tags) ? m.tags : [];
    renderTagEditCurrent(existing);
    document.getElementById("tag-edit-input").value = existing.join(", ");
    document.getElementById("tag-edit-error").style.display = "none";
    document.getElementById("tag-edit-modal").style.display = "flex";
    document
      .getElementById("tag-edit-modal")
      .setAttribute("data-current-id", String(id));
    // autofocus 输入框
    setTimeout(() => {
      const inp = document.getElementById("tag-edit-input");
      if (inp) {
        inp.focus();
        inp.select();
      }
    }, 50);
  }

  function closeTagEdit() {
    document.getElementById("tag-edit-modal").style.display = "none";
  }

  async function saveTagEdit() {
    const modal = document.getElementById("tag-edit-modal");
    const id = modal.getAttribute("data-current-id");
    const errEl = document.getElementById("tag-edit-error");
    const raw = document.getElementById("tag-edit-input").value;
    const tags = parseTagInput(raw);
    try {
      await MarketAdmin.updateMarketTags("KNOWLEDGE", id, tags);
      // 乐观更新本地 row,避免重拉整个列表
      const m = allKnowledge.find((x) => String(x.id) === String(id));
      if (m) m.tags = tags.slice();
      renderTagEditCurrent(tags);
      showToast("已保存 " + tags.length + " 个标签", "success");
      closeTagEdit();
      // 重渲染表格 — 简单做法:直接调用 renderTable() 用更新后的 allKnowledge
      renderTable();
    } catch (e) {
      errEl.textContent = "保存失败：" + e.message;
      errEl.style.display = "block";
    }
  }

  // ============== M0 T19: 公告 + 评论 管理 ==============

  function openAnnouncement(id) {
    const m = allKnowledge.find(
      (x) => String(x.id) === String(id),
    );
    if (!m) return;
    document.getElementById("announcement-title-text").textContent =
      "管理公告：" + (m.name || ("#" + id));
    document.getElementById("ann-title").value =
      (m.announcement && m.announcement.title) || m.announcementTitle || "";
    document.getElementById("ann-body").value =
      (m.announcement && m.announcement.body) || m.announcementBody || "";
    document.getElementById("ann-error").style.display = "none";
    document.getElementById("announcement-modal").style.display = "flex";
    document
      .getElementById("announcement-modal")
      .setAttribute("data-current-id", String(id));
  }

  function closeAnnouncement() {
    document.getElementById("announcement-modal").style.display = "none";
  }

  async function saveAnnouncement() {
    const modal = document.getElementById("announcement-modal");
    const id = modal.getAttribute("data-current-id");
    const title = document.getElementById("ann-title").value.trim();
    const body = document.getElementById("ann-body").value.trim();
    const errEl = document.getElementById("ann-error");
    if (!title || !body) {
      errEl.textContent = "标题和正文不能为空";
      errEl.style.display = "block";
      return;
    }
    try {
      await MarketAdmin.setAnnouncement("KNOWLEDGE", id, title, body);
      showToast("公告已发布", "success");
      closeAnnouncement();
      await loadList();
    } catch (e) {
      errEl.textContent = "发布失败：" + e.message;
      errEl.style.display = "block";
    }
  }

  async function removeAnnouncement() {
    const modal = document.getElementById("announcement-modal");
    const id = modal.getAttribute("data-current-id");
    const ok = await confirmDialog({
      title: "撤销公告",
      message: "确认撤销该市场知识库的公告？撤销后用户市场 Tab 不再展示。",
      okText: "撤销",
    });
    if (!ok) return;
    try {
      await MarketAdmin.deleteAnnouncement("KNOWLEDGE", id);
      showToast("已撤销", "success");
      closeAnnouncement();
      await loadList();
    } catch (e) {
      showToast("撤销失败：" + e.message, "error");
    }
  }

  async function openReviews(id) {
    const m = allKnowledge.find(
      (x) => String(x.id) === String(id),
    );
    if (!m) return;
    document.getElementById("review-title-text").textContent =
      "用户评价：" + (m.name || ("#" + id));
    const listEl = document.getElementById("review-modal-list");
    const aggEl = document.getElementById("review-modal-aggregate");
    listEl.innerHTML = '<div class="review-loading">加载中...</div>';
    aggEl.innerHTML = "";
    document.getElementById("review-modal").style.display = "flex";
    document
      .getElementById("review-modal")
      .setAttribute("data-current-id", String(id));
    try {
      const result = await MarketAdmin.reviewList("KNOWLEDGE", id, {
        isAdmin: true,
      });
      const avg = result.aggregate && result.aggregate.avg;
      const count = result.aggregate && result.aggregate.count;
      if (count > 0) {
        aggEl.innerHTML =
          '<div class="review-aggregate">' +
          `<span class="review-aggregate-avg">${Number(avg).toFixed(1)}</span>` +
          MarketAdmin.renderStarWidget(Math.round(avg), true) +
          `<span class="review-aggregate-count">${count} 条评价</span>` +
          "</div>";
      } else {
        aggEl.innerHTML =
          '<div class="review-aggregate review-aggregate-empty">尚无评价</div>';
      }
      listEl.innerHTML = result.html;
      listEl.querySelectorAll(".review-del-btn").forEach((btn) => {
        btn.addEventListener("click", async (ev) => {
          ev.stopPropagation();
          const username = btn.getAttribute("data-username");
          const ok2 = await confirmDialog({
            title: "删除评价",
            message: `确认删除用户「${username}」的评价？该操作不可撤销。`,
            okText: "删除",
          });
          if (!ok2) return;
          try {
            await MarketAdmin.deleteReview("KNOWLEDGE", id, username);
            showToast("已删除", "success");
            await openReviews(id);
          } catch (e) {
            showToast("删除失败：" + e.message, "error");
          }
        });
      });
    } catch (e) {
      listEl.innerHTML =
        '<div style="color:var(--error-color);font-size:12px;">加载失败：' +
        escapeHtml(e.message) +
        "</div>";
    }
  }

  function closeReviews() {
    document.getElementById("review-modal").style.display = "none";
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
      // 新建走工具栏「+ 新增知识库」(MarketAdmin.form)，saveEdit 仅编辑;防御性兜底
      showErr("请使用「+ 新增知识库」按钮创建知识库");
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

  // Task 7: 「+ 新增知识库」按钮 — create 模式 POST 到 ADMIN_API.KNOWLEDGE（createApproved → APPROVED）
  document.getElementById("create-knowledge-btn")?.addEventListener("click", async () => {
    const result = await MarketAdmin.form("KNOWLEDGE", "create", null);
    if (result) {
      showToast("已新增", "success");
      await loadList();
    }
  });
  document.getElementById("refresh-btn")?.addEventListener("click", loadList);
  document
    .getElementById("edit-knowledge-close")
    ?.addEventListener("click", closeEdit);
  document
    .getElementById("ek-cancel")
    ?.addEventListener("click", closeEdit);
  document.getElementById("ek-save")?.addEventListener("click", saveEdit);

  // T19: 公告 + 评论 管理模态框事件
  document
    .getElementById("announcement-close")
    ?.addEventListener("click", closeAnnouncement);
  document
    .getElementById("ann-cancel")
    ?.addEventListener("click", closeAnnouncement);
  document
    .getElementById("ann-save")
    ?.addEventListener("click", saveAnnouncement);
  document
    .getElementById("ann-delete")
    ?.addEventListener("click", removeAnnouncement);
  document
    .getElementById("review-close")
    ?.addEventListener("click", closeReviews);
  document
    .getElementById("review-cancel")
    ?.addEventListener("click", closeReviews);

  // T21: 标签批量编辑 模态框事件
  document
    .getElementById("tag-edit-close")
    ?.addEventListener("click", closeTagEdit);
  document
    .getElementById("tag-edit-cancel")
    ?.addEventListener("click", closeTagEdit);
  document
    .getElementById("tag-edit-save")
    ?.addEventListener("click", saveTagEdit);

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
