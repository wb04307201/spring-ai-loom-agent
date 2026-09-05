/**
 * market-admin.js
 *
 * Shared namespace for admin Skill / Knowledge market pages.
 *
 * Declared in M0 Task 11 (forward-decl). Full DOM / modal / render work
 * lands in:
 *   - T12 (admin skill market page)
 *   - T13 (admin knowledge market page)
 *   - T19 (form() / reviewList() real impls + announcement banner +
 *          review submission widget — this file)
 *
 * Exposes: window.MarketAdmin = { list, form, reviewList, approvalBadge,
 *                                 announcementHtml, getAnnouncement,
 *                                 setAnnouncement, deleteAnnouncement,
 *                                 deleteReview, submitReview,
 *                                 renderStarWidget,
 *                                 getMarketTags, updateMarketTags }
 *
 * Convention: plain JS + template strings (matches existing skills-market.js /
 * knowledge-market.js style). No Vue / React / external deps.
 */
(function () {
  "use strict";

  /** Admin list endpoints — mirror of LoomAgentConfiguration WebConfiguration router beans. */
  const ADMIN_API = {
    SKILL: "/spring/ai/loom/admin/market-skills",
    KNOWLEDGE: "/spring/ai/loom/admin/market-knowledge",
  };

  /** Public read endpoints (used by reviewList / announcement fetch). */
  const PUBLIC_API = {
    SKILL: "/spring/ai/loom/market-skills",
    KNOWLEDGE: "/spring/ai/loom/market-knowledge",
  };

  /** Admin write endpoints (delete review by username, write announcement). */
  const ADMIN_REVIEW_API = (kind, id, username) =>
    `/spring/ai/loom/admin/market-${kind.toLowerCase()}s/${encodeURIComponent(
      id,
    )}/reviews/${encodeURIComponent(username)}`;
  const ADMIN_ANNOUNCEMENT_API = (kind, id) =>
    `/spring/ai/loom/admin/market-${kind.toLowerCase()}s/${encodeURIComponent(
      id,
    )}/announcement`;

  /** Human-readable kind label (used in thrown errors). */
  const KIND_LABEL = {
    SKILL: "技能",
    KNOWLEDGE: "知识库",
  };

  /** Tiny HTML escape, used by approvalBadge. */
  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  /**
   * list(kind) -> Promise<Array>
   *
   * Fetches the admin-side list of market entries for the given kind.
   * URL pattern: GET /spring/ai/loom/admin/market-{kind}s
   *   kind === "SKILL"     -> /spring/ai/loom/admin/market-skills
   *   kind === "KNOWLEDGE" -> /spring/ai/loom/admin/market-knowledge
   *
   * Returns the raw JSON array (T12 / T13 wrap it into a table + actions).
   * Throws on unknown kind, network failure, or non-2xx HTTP.
   */
  async function list(kind) {
    const url = ADMIN_API[kind];
    if (!url) {
      throw new Error(
        "MarketAdmin.list: unknown kind " +
          JSON.stringify(kind) +
          " (expected 'SKILL' or 'KNOWLEDGE')",
      );
    }
    const r = await fetch(url, {
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
    });
    if (r.status === 401 || r.status === 403) {
      // Session expired — bounce to login, matching skills-market.js convention.
      window.location.replace("/spring/ai/loom/index.html");
      return [];
    }
    if (!r.ok) {
      let body = "";
      try {
        body = await r.text();
      } catch (_) {
        body = "";
      }
      throw new Error(
        "MarketAdmin.list(" +
          (KIND_LABEL[kind] || kind) +
          ") failed: HTTP " +
          r.status +
          (body ? " — " + body : ""),
      );
    }
    return await r.json();
  }

  /**
   * form(kind, mode, entry) -> Promise<RecordDescriptor | null>
   *
   * Renders the create / edit modal for a market entry.
   *   kind  : 'SKILL' | 'KNOWLEDGE'
   *   mode  : 'create' | 'edit'
   *   entry : existing record (or null when mode === 'create')
   *
   * Returns a Promise that resolves to a record descriptor:
   *   { name, description, content, category, isOfficial?, featuredRank? }
   * on Save (and the PUT to /admin/market-{kind}s/{id} succeeds),
   * or `null` if the user cancelled.
   *
   * Knowledge entries expose `category`, `isOfficial`, `featuredRank`;
   * skill entries do not. The function renders the appropriate field set
   * based on `kind`.
   */
  function form(kind, mode, entry) {
    if (kind !== "SKILL" && kind !== "KNOWLEDGE") {
      return Promise.reject(
        new Error("MarketAdmin.form: unknown kind " + JSON.stringify(kind)),
      );
    }
    if (mode !== "create" && mode !== "edit") {
      return Promise.reject(
        new Error(
          "MarketAdmin.form: unsupported mode " +
            JSON.stringify(mode) +
            " (expected 'create' or 'edit')",
        ),
      );
    }

    const isEdit = mode === "edit";
    const isKnowledge = kind === "KNOWLEDGE";
    const overlay = document.createElement("div");
    overlay.className = "modal-overlay market-form-overlay";
    overlay.style.display = "flex";

    const titleText =
      (KIND_LABEL[kind] || kind) + (isEdit ? " · 编辑" : " · 新增");
    const safe = (v) => escapeHtml(v == null ? "" : v);
    const cur = entry || {};
    const submitUrl = isEdit
      ? ADMIN_API[kind] + "/" + encodeURIComponent(cur.id)
      : ADMIN_API[kind];
    const submitMethod = isEdit ? "PUT" : "POST";

    const nameField = isEdit
      ? `<input type="text" id="mf-name" class="form-input" disabled value="${safe(cur.name)}"/>`
      : `<input type="text" id="mf-name" class="form-input" placeholder="例如：周报生成" value="${safe(cur.name)}"/>`;
    const contentField = isKnowledge
      ? ""
      : `<div class="form-group">
           <label>内容（Prompt 模板）<span style="color: var(--error-color)">*</span></label>
           <textarea id="mf-content" class="form-input" rows="14" style="font-family: var(--font-mono, monospace); font-size: 12px; line-height: 1.6;" placeholder="支持 {param} 占位符">${safe(cur.content)}</textarea>
         </div>`;
    const kbExtraFields = isKnowledge
      ? `<div class="form-group">
           <label>分类</label>
           <input type="text" id="mf-category" class="form-input" placeholder="例如：技术文档" value="${safe(cur.category)}"/>
         </div>
         <div class="form-group">
           <label>精选排序</label>
           <input type="number" id="mf-featured-rank" class="form-input" min="0" step="1" placeholder="留空表示不设置" value="${cur.featuredRank == null ? "" : safe(cur.featuredRank)}"/>
         </div>
         <div class="form-group">
           <label><input type="checkbox" id="mf-official" ${cur.isOfficial ? "checked" : ""}/> 标记为官方</label>
         </div>`
      : "";

    overlay.innerHTML = `
 <div class="modal-content" style="max-width: 720px; max-height: 85vh;">
   <div class="modal-header">
     <h3>${escapeHtml(titleText)}</h3>
     <div class="close-button" data-role="close">&times;</div>
   </div>
   <div class="modal-body" style="padding: 24px 32px; overflow-y: auto; max-height: 70vh;">
     <div class="form-group">
       <label>名称 <span style="color: var(--error-color)">*</span></label>
       ${nameField}
     </div>
     <div class="form-group">
       <label>描述</label>
       <textarea id="mf-description" class="form-input" rows="3" placeholder="简要描述功能或内容">${safe(cur.description)}</textarea>
     </div>
     ${contentField}
     ${kbExtraFields}
     <div id="mf-error" class="error-msg" style="display:none"></div>
   </div>
   <div class="modal-footer">
     <button class="secondary-btn" data-role="cancel">取消</button>
     <button class="primary-btn" data-role="save">保存</button>
   </div>
 </div>`;

    document.body.appendChild(overlay);

    return new Promise((resolve) => {
      const close = (value) => {
        document.body.removeChild(overlay);
        document.removeEventListener("keydown", onKey);
        resolve(value);
      };
      const onKey = (ev) => {
        if (ev.key === "Escape") close(null);
      };
      document.addEventListener("keydown", onKey);
      overlay.querySelector('[data-role="close"]').addEventListener(
        "click",
        () => close(null),
      );
      overlay.querySelector('[data-role="cancel"]').addEventListener(
        "click",
        () => close(null),
      );
      overlay.addEventListener("click", (ev) => {
        if (ev.target === overlay) close(null);
      });
      overlay.querySelector('[data-role="save"]').addEventListener(
        "click",
        async () => {
          const errEl = overlay.querySelector("#mf-error");
          errEl.style.display = "none";
          const name = overlay.querySelector("#mf-name").value.trim();
          const description = overlay
            .querySelector("#mf-description")
            .value.trim();
          if (!name) {
            errEl.textContent = "名称不能为空";
            errEl.style.display = "block";
            return;
          }
          const body = { name, description };
          if (!isKnowledge) {
            const content = overlay.querySelector("#mf-content").value;
            if (!content.trim()) {
              errEl.textContent = "内容不能为空";
              errEl.style.display = "block";
              return;
            }
            body.content = content;
          } else {
            const category = overlay
              .querySelector("#mf-category")
              .value.trim();
            const rankText = overlay
              .querySelector("#mf-featured-rank")
              .value.trim();
            body.category = category || null;
            let featuredRank = null;
            if (rankText !== "") {
              featuredRank = Number(rankText);
              if (
                !Number.isInteger(featuredRank) ||
                featuredRank < 0
              ) {
                errEl.textContent = "精选排序必须是非负整数";
                errEl.style.display = "block";
                return;
              }
            }
            body.isOfficial = overlay.querySelector("#mf-official").checked;
            body.featuredRank = featuredRank;
          }

          try {
            const resp = await fetch(submitUrl, {
              method: submitMethod,
              credentials: "include",
              headers: { "Content-Type": "application/json; charset=UTF-8" },
              body: JSON.stringify(body),
            });
            if (!resp.ok) {
              let t = "";
              try {
                t = await resp.text();
              } catch (_) {
                t = "";
              }
              errEl.textContent =
                "保存失败：" + (t || "HTTP " + resp.status);
              errEl.style.display = "block";
              return;
            }
            let saved = null;
            try {
              saved = await resp.json();
            } catch (_) {
              saved = null;
            }
            const descriptor = Object.assign({}, body);
            if (saved && typeof saved === "object") {
              if (saved.id != null) descriptor.id = saved.id;
              if (saved.name) descriptor.name = saved.name;
            } else if (isEdit && cur.id != null) {
              descriptor.id = cur.id;
            }
            close(descriptor);
          } catch (e) {
            errEl.textContent = "网络错误：" + e.message;
            errEl.style.display = "block";
          }
        },
      );
    });
  }

  /**
   * reviewList(kind, marketId) -> Promise<{aggregate, reviews, html}>
   *
   * Fetches `GET /spring/ai/loom/market-{kind}s/{id}/reviews` and renders
   * the public review list as HTML. Also fetches the aggregate (count + avg)
   * from the same response if available, otherwise computes it client-side.
   *
   * Returns a Promise resolving to {aggregate, reviews, html} where `html`
   * is a ready-to-inject HTML string (star widgets + comment items).
   * Includes a per-item "删除" button when invoked in admin mode (set
   * `opts.isAdmin = true`) — those buttons do nothing until bound by the
   * caller (e.g. event delegation on the container).
   *
   * Failure modes:
   *   - unknown kind        -> rejects
   *   - HTTP non-2xx        -> rejects with descriptive Error
   *   - 401/403             -> redirects to /index.html (login bounce)
   */
  async function reviewList(kind, marketId, opts) {
    const urlKind = PUBLIC_API[kind];
    if (!urlKind) {
      throw new Error(
        "MarketAdmin.reviewList: unknown kind " + JSON.stringify(kind),
      );
    }
    const url =
      urlKind + "/" + encodeURIComponent(marketId) + "/reviews?page=0&size=20";
    const r = await fetch(url, {
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
    });
    if (r.status === 401 || r.status === 403) {
      window.location.replace("/spring/ai/loom/index.html");
      return { aggregate: { count: 0, avg: 0 }, reviews: [], html: "" };
    }
    if (!r.ok) {
      let body = "";
      try {
        body = await r.text();
      } catch (_) {
        body = "";
      }
      throw new Error(
        "reviewList failed: HTTP " + r.status + (body ? " — " + body : ""),
      );
    }
    const data = await r.json();
    const items = Array.isArray(data) ? data : (data && data.items) || [];
    let aggregate;
    if (data && data.aggregate) {
      aggregate = data.aggregate;
    } else {
      const total = items.length;
      const sum = items.reduce((a, r) => a + (Number(r.rating) || 0), 0);
      aggregate = { count: total, avg: total ? sum / total : 0 };
    }
    const isAdmin = !!(opts && opts.isAdmin);
    const html = renderReviewListHtml(items, isAdmin);
    return { aggregate, reviews: items, html };
  }

  function renderReviewListHtml(items, isAdmin) {
    if (!items || items.length === 0) {
      return '<div class="review-empty">还没有评价</div>';
    }
    return (
      '<div class="review-list">' +
      items
        .map((rv) => {
          const stars = renderStarWidget(rv.rating || 0, true);
          const user = escapeHtml(rv.username || "匿名");
          const ts = rv.updatedAt || rv.createdAt;
          const timeStr = ts
            ? escapeHtml(String(ts).slice(0, 16).replace("T", " "))
            : "";
          const cmt = escapeHtml(rv.comment || "");
          const editTag =
            rv.editCount && rv.editCount > 0
              ? '<span class="review-edit-tag">已编辑</span>'
              : "";
          const delBtn = isAdmin
            ? `<button class="delete-btn btn-sm review-del-btn" data-username="${user}" type="button">删除</button>`
            : "";
          return (
            '<div class="review-item">' +
            `<div class="review-row1">${stars}<span class="review-user">${user}</span><span class="review-time">${timeStr}</span>${editTag}${delBtn}</div>` +
            (cmt
              ? `<div class="review-comment">${cmt}</div>`
              : '<div class="review-comment review-comment-empty">（无评论）</div>') +
            "</div>"
          );
        })
        .join("") +
      "</div>"
    );
  }

  /** Read-only star widget (filled / empty glyphs). */
  function renderStarWidget(rating, readonly) {
    const r = Math.max(0, Math.min(5, Math.round(Number(rating) || 0)));
    const glyphs = ["★", "★", "★", "★", "★"]
      .map((g, i) =>
        i < r
          ? `<span class="star-filled">${g}</span>`
          : `<span class="star-empty">${g}</span>`,
      )
      .join("");
    return (
      '<span class="star-rating ' +
      (readonly ? "star-rating-readonly" : "star-rating-editable") +
      '" data-rating="' +
      r +
      '">' +
      glyphs +
      "</span>"
    );
  }

  /**
   * approvalBadge(status, reviewer, reviewedAt, comment) -> string (HTML)
   *
   * Returns a small inline badge + reviewer / date / comment block for use
   * inside admin tables / detail panels.
   *   status     : 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | ...
   *   reviewer   : username (or null / undefined)
   *   reviewedAt : ISO timestamp string (or null)
   *   comment    : free-text reviewer comment (or null)
   *
   * Minimal placeholder — shows status + reviewer + date + optional comment.
   * T19 may enhance with full CSS theming + i18n labels.
   */
  function approvalBadge(status, reviewer, reviewedAt, comment) {
    const safeStatus = escapeHtml(status || "UNKNOWN");
    const safeReviewer = escapeHtml(reviewer || "—");
    const safeDate = reviewedAt
      ? escapeHtml(String(reviewedAt).slice(0, 16).replace("T", " "))
      : "—";
    const safeComment = comment
      ? '<div class="approval-comment">' + escapeHtml(comment) + "</div>"
      : "";
    return (
      '<span class="approval-badge approval-status-' +
      safeStatus +
      '">' +
      safeStatus +
      "</span>" +
      '<span class="approval-meta">' +
      safeReviewer +
      " · " +
      safeDate +
      "</span>" +
      safeComment
    );
  }

  /**
   * announcementHtml(kind, id, ann) -> string (HTML)
   *
   * Renders a `.market-announcement` banner from a MarketAnnouncement record
   * `{title, body, createdAt}`. Returns empty string when `ann` is null /
   * missing. Banner is plain-text only (no markdown) — body is HTML-escaped.
   */
  function announcementHtml(ann) {
    if (!ann || !ann.title || !ann.body) return "";
    const t = escapeHtml(ann.title);
    const b = escapeHtml(ann.body);
    const ts = ann.createdAt
      ? escapeHtml(String(ann.createdAt).slice(0, 16).replace("T", " "))
      : "";
    return (
      '<div class="market-announcement">' +
      `<div class="market-announcement-title">${window.I18N && window.I18N.t ? window.I18N.t("market.admin.announcement.badge", "📢 公告") : "📢 公告"}</div>` +
      `<div class="market-announcement-body">${b}</div>` +
      (ts
        ? `<div class="market-announcement-time">${ts}</div>`
        : "") +
      "</div>"
    );
  }

  /**
   * M3+ T2.2 — listWithAnnouncements has been removed. The backend list DTO
   * (MarketSkill / MarketKnowledgeRecord, post T2.1) now embeds
   * announcementTitle / announcementBody directly via LEFT JOIN, so the
   * frontend does not need per-row secondary GETs. Call sites should
   * read `row.announcementTitle` / `row.announcementBody` directly from
   * the list response.
   */

  /**
   * getAnnouncement(kind, id) -> Promise<MarketAnnouncement | null>
   *
   * Calls the public read endpoint `GET /market-{kind}s/{id}/announcement`
   * (any logged-in user; no admin required). Resolves to the announcement
   * record `{title, body, ...}` when present, or `null` when no announcement
   * is set (HTTP 204).
   *
   * 401/403 → bounces to /index.html (login redirect) since the user has no
   * session. 404 / 204 / network failure → resolves to `null` so the UI can
   * silently skip rendering the banner.
   */
  async function getAnnouncement(kind, id) {
    if (!PUBLIC_API[kind]) {
      throw new Error(
        "MarketAdmin.getAnnouncement: unknown kind " + JSON.stringify(kind),
      );
    }
    const url =
      PUBLIC_API[kind] + "/" + encodeURIComponent(id) + "/announcement";
    let resp;
    try {
      resp = await fetch(url, {
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
      });
    } catch (_) {
      return null;
    }
    if (resp.status === 401 || resp.status === 403) {
      window.location.replace("/spring/ai/loom/index.html");
      return null;
    }
    if (resp.status === 204) return null;
    if (resp.status === 404) return null;
    if (!resp.ok) return null;
    try {
      const data = await resp.json();
      if (data && data.title) return data;
      return null;
    } catch (_) {
      return null;
    }
  }

  /**
   * setAnnouncement(kind, id, title, body) -> Promise<MarketAnnouncement>
   *
   * Admin-side: PUT /admin/market-{kind}s/{id}/announcement with the body
   * {title, body}. Returns the saved announcement record.
   */
  async function setAnnouncement(kind, id, title, body) {
    const url = ADMIN_ANNOUNCEMENT_API(kind, id);
    const resp = await fetch(url, {
      method: "PUT",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ title, body }),
    });
    if (!resp.ok) {
      let t = "";
      try {
        t = await resp.text();
      } catch (_) {
        t = "";
      }
      throw new Error(
        "setAnnouncement failed: HTTP " +
          resp.status +
          (t ? " — " + t : ""),
      );
    }
    return await resp.json();
  }

  /**
   * deleteAnnouncement(kind, id) -> Promise<true>
   */
  async function deleteAnnouncement(kind, id) {
    const url = ADMIN_ANNOUNCEMENT_API(kind, id);
    const resp = await fetch(url, {
      method: "DELETE",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
    });
    if (!resp.ok) {
      let t = "";
      try {
        t = await resp.text();
      } catch (_) {
        t = "";
      }
      throw new Error(
        "deleteAnnouncement failed: HTTP " +
          resp.status +
          (t ? " — " + t : ""),
      );
    }
    return true;
  }

  /**
   * deleteReview(kind, id, username) -> Promise<true>
   *
   * Admin-only: removes a specific user's review.
   */
  async function deleteReview(kind, id, username) {
    const url = ADMIN_REVIEW_API(kind, id, username);
    const resp = await fetch(url, {
      method: "DELETE",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
    });
    if (!resp.ok) {
      let t = "";
      try {
        t = await resp.text();
      } catch (_) {
        t = "";
      }
      throw new Error(
        "deleteReview failed: HTTP " +
          resp.status +
          (t ? " — " + t : ""),
      );
    }
    return true;
  }

  /**
   * submitReview(kind, id, rating, comment) -> Promise<ReviewRow>
   *
   * User-side: POST /market-{kind}s/{id}/reviews with {rating, comment}.
   */
  async function submitReview(kind, id, rating, comment) {
    const url = PUBLIC_API[kind] + "/" + encodeURIComponent(id) + "/reviews";
    const resp = await fetch(url, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ rating, comment: comment || null }),
    });
    if (!resp.ok) {
      let t = "";
      try {
        t = await resp.text();
      } catch (_) {
        t = "";
      }
      const err = new Error(
        "submitReview failed: HTTP " + resp.status + (t ? " — " + t : ""),
      );
      err.status = resp.status;
      throw err;
    }
    return await resp.json();
  }

  /**
   * getMarketTags(kind, id) -> Promise<string[]>
   *
   * Public read endpoint: `GET /market-{kind}s/{id}/tags`.
   * Resolves to an array of tag strings (may be empty). Per-row fetch failures
   * (404 / 204 / network) resolve to `[]` so callers can safely decorate rows
   * without try/catch. 401/403 bounce to /index.html (login redirect).
   */
  async function getMarketTags(kind, id) {
    if (!PUBLIC_API[kind]) {
      throw new Error(
        "MarketAdmin.getMarketTags: unknown kind " + JSON.stringify(kind),
      );
    }
    const url = PUBLIC_API[kind] + "/" + encodeURIComponent(id) + "/tags";
    let resp;
    try {
      resp = await fetch(url, {
        credentials: "include",
        headers: { "Content-Type": "application/json; charset=UTF-8" },
      });
    } catch (_) {
      return [];
    }
    if (resp.status === 401 || resp.status === 403) {
      window.location.replace("/spring/ai/loom/index.html");
      return [];
    }
    if (resp.status === 204 || resp.status === 404) return [];
    if (!resp.ok) return [];
    try {
      const data = await resp.json();
      if (Array.isArray(data)) return data.map(String);
      if (data && Array.isArray(data.tags)) return data.tags.map(String);
      return [];
    } catch (_) {
      return [];
    }
  }

  /**
   * M3+ T2.2 — listWithTags has been removed for the same reason as
   * listWithAnnouncements: KB list DTO embeds `tags: string[]` from
   * the batch tag SELECT (T2.1 follow-up). Call sites should read
   * `row.tags` directly from the list response. For now the
   * MarketKnowledgeRecord.tags field is null until the batch tag
   * SELECT is wired (separate task); frontend should treat
   * `row.tags` as an optional array.
   */

  /**
   * updateMarketTags(kind, id, tags) -> Promise<string[]>
   *
   * Admin-side: PUT /admin/market-{kind}s/{id}/tags with body
   * `{tags: [...]}`. Returns the saved tag array as returned by the
   * backend (`{tags: [...]}` → `[...]`). Empty array clears all tags.
   */
  async function updateMarketTags(kind, id, tags) {
    if (!ADMIN_API[kind]) {
      throw new Error(
        "MarketAdmin.updateMarketTags: unknown kind " + JSON.stringify(kind),
      );
    }
    const url = ADMIN_API[kind] + "/" + encodeURIComponent(id) + "/tags";
    const resp = await fetch(url, {
      method: "PUT",
      credentials: "include",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ tags: Array.isArray(tags) ? tags : [] }),
    });
    if (!resp.ok) {
      let t = "";
      try {
        t = await resp.text();
      } catch (_) {
        t = "";
      }
      throw new Error(
        "updateMarketTags failed: HTTP " +
          resp.status +
          (t ? " — " + t : ""),
      );
    }
    try {
      const data = await resp.json();
      if (data && Array.isArray(data.tags)) return data.tags.map(String);
      return Array.isArray(tags) ? tags.map(String) : [];
    } catch (_) {
      return Array.isArray(tags) ? tags.map(String) : [];
    }
  }

  window.MarketAdmin = {
    list: list,
    form: form,
    reviewList: reviewList,
    approvalBadge: approvalBadge,
    announcementHtml: announcementHtml,
    getAnnouncement: getAnnouncement,
    setAnnouncement: setAnnouncement,
    deleteAnnouncement: deleteAnnouncement,
    deleteReview: deleteReview,
    submitReview: submitReview,
    renderStarWidget: renderStarWidget,
    getMarketTags: getMarketTags,
    updateMarketTags: updateMarketTags,
  };
})();
