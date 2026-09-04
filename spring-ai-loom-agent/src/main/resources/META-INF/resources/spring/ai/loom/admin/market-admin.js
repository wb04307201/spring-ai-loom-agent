/**
 * market-admin.js
 *
 * Shared namespace for admin Skill / Knowledge market pages.
 *
 * Declared in M0 Task 11 (forward-decl). Full DOM / modal / render work lands in:
 *   - T12 (admin skill market page)
 *   - T13 (admin knowledge market page)
 *   - T19 (reviewList + approvalBadge real fetches + full render)
 *
 * Exposes: window.MarketAdmin = { list, form, reviewList, approvalBadge }
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
   * Renders the create / edit / review-edit modal for a market entry.
   *   kind  : 'SKILL' | 'KNOWLEDGE'
   *   mode  : 'create' | 'edit' | 'review-edit'
   *   entry : existing record (or null when mode === 'create')
   *
   * Resolves to a record descriptor:
   *   { name, description, content, category, isOfficial?, featuredRank?,
   *     rejectReason?, reviewComment? }
   * Resolves to null if the user cancelled.
   *
   * STUB: T12 (skill) / T13 (knowledge) provide the modal DOM + handlers.
   * Throwing now gives T12 / T13 a loud failure if they wire UI before replacing this body.
   */
  function form(kind, mode, entry) {
    return Promise.reject(
      new Error(
        "MarketAdmin.form not yet implemented (kind=" +
          (KIND_LABEL[kind] || kind) +
          ", mode=" +
          mode +
          ", entry=" +
          (entry ? entry.name || "(unnamed)" : "null") +
          ")",
      ),
    );
  }

  /**
   * reviewList(kind, marketId) -> Promise<Array>
   *
   * Fetches the public review list for a market entry:
   *   GET /spring/ai/loom/market-{kind}s/{marketId}/reviews
   *
   * DEFERRED: the endpoint lands in T18; the render lands in T19.
   */
  function reviewList(kind, marketId) {
    return Promise.reject(
      new Error(
        "MarketAdmin.reviewList not yet implemented (kind=" +
          (KIND_LABEL[kind] || kind) +
          ", marketId=" +
          JSON.stringify(marketId) +
          ")",
      ),
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

  window.MarketAdmin = {
    list: list,
    form: form,
    reviewList: reviewList,
    approvalBadge: approvalBadge,
  };
})();
