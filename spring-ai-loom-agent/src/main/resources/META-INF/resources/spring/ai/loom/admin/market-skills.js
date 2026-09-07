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

  /** M4 T5: 把 tag 数组渲染成可读 chip 列表;空时显示 "—"（镜像 knowledge-market.js）。 */
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
   * Columns (M0 T12 + T19 + M4 T5): 名称 / 状态 / 作者 / 官方 / 排序 / 分类 / 标签 / 提交时间 / 公告 / 评分 / 操作
   * - 状态: MarketAdmin.approvalBadge(status, reviewer, reviewedAt, comment)
   * - 官方: 🏛️ if isOfficial truthy, else "—"
   * - 排序: featuredRank (or "—" if null/empty)
   * - 分类: category (or "—" if null/empty)
   * - 提交时间: submittedAt (truncated to minutes)
   * - 公告: "✓ 已发布" if announcementTitle present, else "—". Button opens
   *         the announcement modal.
   * - 评分: aggregate stars + count (from /stats + /reviews aggregate).
   *         Button opens the review list modal.
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
        const reviewer = m.reviewer ?? m.reviewedBy ?? m.reviewed_by;
        const statusBadge = MarketAdmin.approvalBadge(
          m.status,
          reviewer,
          m.reviewedAt,
          m.reviewComment,
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
        // 评分 cell — T19: avg + count from embedded stats if available.
        const agg = m.aggregate || m.stats || {};
        const avg = Number(agg.avg || agg.ratingAvg || 0);
        const count = Number(agg.count || agg.ratingCount || 0);
        const ratingCell = count > 0
          ? `${MarketAdmin.renderStarWidget(Math.round(avg), true)}<span style="font-size:12px;color:var(--text-muted);margin-left:4px;">${count}</span>`
          : '<span style="color: var(--text-muted); font-size:12px;">无评价</span>';
        // Task 7: PENDING 行显示 通过 / 拒绝 审批按钮
        const status = String(m.status || "").toUpperCase();
        const approvalBtns = status === "PENDING"
          ? `<button class="primary-btn approve-btn btn-sm" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">通过</button>` +
            `<button class="delete-btn reject-btn btn-sm" data-id="${m.id}" style="margin-right:4px;">拒绝</button>`
          : "";
        return `<tr data-id="${m.id}">
 <td><strong>${escapeHtml(m.name)}</strong></td>
 <td>${statusBadge}</td>
 <td>${escapeHtml(m.author)}</td>
 <td>${officialMark}</td>
 <td>${rank}</td>
 <td>${category}</td>
 <td>${renderTagChipsHtml(Array.isArray(m.tags) ? m.tags : [])}</td>
 <td>${submittedAt}</td>
 <td>${annCell}<button class="secondary-btn ann-btn btn-sm" data-id="${m.id}" style="padding:2px 8px;font-size:11px;margin-left:6px;">${hasAnn ? "编辑" : "发布"}</button></td>
 <td>${ratingCell}<button class="secondary-btn reviews-btn btn-sm" data-id="${m.id}" style="padding:2px 8px;font-size:11px;margin-left:6px;">管理</button></td>
 <td>
 ${approvalBtns}
 <button class="secondary-btn tag-edit-row-btn btn-sm" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑标签</button>
 <button class="secondary-btn edit-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">编辑</button>
 <button class="delete-btn del-btn btn-sm" data-id="${m.id}">下架</button>
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
        openEdit(parseInt(btn.getAttribute("data-id"))),
      );
    });
    // Task 7: PENDING 行审批按钮
    tableContainer.querySelectorAll(".approve-btn").forEach((btn) =>
      btn.addEventListener("click", async () => {
        if (!confirm("确认通过该技能?")) return;
        try {
          await MarketAdmin.approve("SKILL", parseInt(btn.getAttribute("data-id")));
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
          await MarketAdmin.reject("SKILL", parseInt(btn.getAttribute("data-id")), comment);
          showToast("已拒绝", "success");
          await loadList();
        } catch (e) {
          alert("拒绝失败: " + e.message);
        }
      }));
    tableContainer.querySelectorAll(".del-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        deleteSkill(parseInt(btn.getAttribute("data-id"))),
      );
    });
    // T19: 公告 + 评论 管理按钮
    tableContainer.querySelectorAll(".ann-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openAnnouncement(parseInt(btn.getAttribute("data-id"))),
      );
    });
    tableContainer.querySelectorAll(".reviews-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openReviews(parseInt(btn.getAttribute("data-id"))),
      );
    });
    // M4 T5: 标签批量编辑按钮
    tableContainer.querySelectorAll(".tag-edit-row-btn").forEach((btn) => {
      btn.addEventListener("click", () =>
        openTagEdit(btn.getAttribute("data-id")),
      );
    });
  }

  // ============== M4 T5: 标签批量编辑（镜像 knowledge-market.js M2 T21） ==============

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
    const m = allSkills.find((x) => String(x.id) === String(id));
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
      await MarketAdmin.updateMarketTags("SKILL", id, tags);
      // 乐观更新本地 row,避免重拉整个列表
      const m = allSkills.find((x) => String(x.id) === String(id));
      if (m) m.tags = tags.slice();
      renderTagEditCurrent(tags);
      showToast("已保存 " + tags.length + " 个标签", "success");
      closeTagEdit();
      // 重渲染表格 — 简单做法:直接调用 renderTable() 用更新后的 allSkills
      renderTable();
    } catch (e) {
      errEl.textContent = "保存失败：" + e.message;
      errEl.style.display = "block";
    }
  }

  // ============== M0 T19: 公告 + 评论 管理 ==============

  function openAnnouncement(id) {
    const m = allSkills.find((x) => x.id === id);
    if (!m) return;
    document.getElementById("announcement-title-text").textContent =
      "管理公告：" + (m.name || ("#" + id));
    document.getElementById("ann-title").value =
      (m.announcement && m.announcement.title) || m.announcementTitle || "";
    document.getElementById("ann-body").value =
      (m.announcement && m.announcement.body) || m.announcementBody || "";
    document.getElementById("ann-error").style.display = "none";
    document.getElementById("announcement-modal").style.display = "flex";
    // 暂存 id
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
      await MarketAdmin.setAnnouncement("SKILL", id, title, body);
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
      message: "确认撤销该市场技能的公告？撤销后用户市场 Tab 不再展示。",
      okText: "撤销",
    });
    if (!ok) return;
    try {
      await MarketAdmin.deleteAnnouncement("SKILL", id);
      showToast("已撤销", "success");
      closeAnnouncement();
      await loadList();
    } catch (e) {
      showToast("撤销失败：" + e.message, "error");
    }
  }

  async function openReviews(id) {
    const m = allSkills.find((x) => x.id === id);
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
      const result = await MarketAdmin.reviewList("SKILL", id, {
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
      // 绑定「删除」按钮（每次重新渲染都要重新绑定）
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
            await MarketAdmin.deleteReview("SKILL", id, username);
            showToast("已删除", "success");
            await openReviews(id); // refresh
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
    const m = allSkills.find((x) => x.id === id);
    if (!m) return;
    currentEdit = { id };
    document.getElementById("edit-skill-title").textContent = "编辑技能";
    document.getElementById("es-name").value = m.name;
    document.getElementById("es-name").disabled = true; // 编辑模式：name 不能改（PK 关联）
    document.getElementById("es-desc").value = m.description || "";
    document.getElementById("es-content").value = m.content || "";
    // Task 7: 回填 category / featuredRank / official
    document.getElementById("edit-category").value = m.category || "";
    document.getElementById("edit-featured-rank").value =
      m.featuredRank == null ? "" : String(m.featuredRank);
    document.getElementById("edit-official").checked = !!m.isOfficial;
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
      // 新建走工具栏「+ 新增技能」(MarketAdmin.form)，saveEdit 仅编辑;防御性兜底
      showErr("请使用「+ 新增技能」按钮创建技能");
      return;
    }
    // Task 7: category / official / rank 与 KB 编辑弹窗对齐;不再发 status
    // （v2 PUT 不吃 status,状态只走 approve/reject）
    const category = document.getElementById("edit-category").value.trim();
    const rankText = document.getElementById("edit-featured-rank").value.trim();
    let featuredRank = null;
    if (rankText !== "") {
      featuredRank = Number(rankText);
      if (!Number.isInteger(featuredRank) || featuredRank < 0) {
        showErr("精选排序必须是非负整数");
        return;
      }
    }
    const body = {
      description: desc,
      content,
      category: category || null,
      isOfficial: document.getElementById("edit-official").checked,
      featuredRank,
    };
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
  // Task 7: 恢复「+ 新增技能」按钮 — create 模式 POST 到 ADMIN_API.SKILL（createApproved → APPROVED）
  document.getElementById("create-skill-btn").addEventListener("click", async () => {
    const result = await MarketAdmin.form("SKILL", "create", null);
    if (result) {
      showToast("已新增", "success");
      await loadList();
    }
  });
  document.getElementById("refresh-btn").addEventListener("click", loadList);
  document
    .getElementById("edit-skill-close")
    .addEventListener("click", closeEdit);
  document.getElementById("es-cancel").addEventListener("click", closeEdit);
  document.getElementById("es-save").addEventListener("click", saveEdit);

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

  // M4 T5: 标签批量编辑 模态框事件
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