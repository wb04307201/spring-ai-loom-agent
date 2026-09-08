/**
 * app.js — Spring AI LoomAgent Frontend
 * 14-partition modular architecture.
 * §4 API service layer = only fetch() calls.
 * §12 UI components = only DOM manipulation.
 * §2 Global state = only state writes.
 */

import { sanitizeHtml } from "./markdown-renderer.js";

// ===================== §1 Constants & Configuration =====================
const API_PREFIX = "";
const API = {
  autoLogin: "/spring/ai/loom/user/isAutoLogin",
  login: "/spring/ai/loom/user/login",
  logout: "/spring/ai/loom/user/logout",
  currentUser: "/spring/ai/loom/user/currentUser",
  currentIsAdmin: "/spring/ai/loom/user/currentIsAdmin",
  changePassword: "/spring/ai/loom/user/changePassword",
  listUsers: "/spring/ai/loom/admin/users",
  createUser: "/spring/ai/loom/admin/users",
  deleteUser: (username) =>
    `/spring/ai/loom/admin/users/${encodeURIComponent(username)}`,
  listConversations: "/spring/ai/loom/conversation",
  createConversation: "/spring/ai/loom/user-conversations",
  renameConversation: (id) => `/spring/ai/loom/user-conversations/${id}`,
  getConversation: (id) => `/spring/ai/loom/conversation/${id}`,
  deleteConversation: (id) => `/spring/ai/loom/conversation/${id}`,
  stream: "/spring/ai/loom/stream",
  listMcps: "/spring/ai/loom/mcps",
  listCapabilities: "/spring/ai/loom/api/capabilities",
  // mcpTools 用 query string 而不是 path variable，避免 name 含 "/" 时
  // Tomcat 把 "%2F" 当 "/" 拆路径，导致 404（实测：
  // "spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch" 调不通）
  mcpTools: (name) =>
    `/spring/ai/loom/mcps/tools?name=${encodeURIComponent(name)}`,
  listSkills: "/spring/ai/loom/skill",
  getSkill: (name) => `/spring/ai/loom/skill/${name}`,
  createSkill: "/spring/ai/loom/skill",
  updateSkill: "/spring/ai/loom/skill",
  patchSkill: (name) => `/spring/ai/loom/skill/${name}`,
  deleteSkill: (name) => `/spring/ai/loom/skill/${name}`,
  listMarketSkills: "/spring/ai/loom/market-skills",
  pullMarketSkill: (id) => `/spring/ai/loom/market-skills/${id}/pull`,
  submitMarketSkill: "/spring/ai/loom/user/market-skills",
  listMySubmittedSkills: "/spring/ai/loom/user/market-skills",
  withdrawMarketSkill: (id) => `/spring/ai/loom/user/market-skills/${id}`,
  listKnowledge: "/spring/ai/loom/knowledge",
  createKnowledge: "/spring/ai/loom/knowledge",
  deleteKnowledge: (id) => `/spring/ai/loom/knowledge/${id}`,
  updateKnowledge: (id) => `/spring/ai/loom/knowledge/${id}`,
  canEditKnowledge: (id) => `/spring/ai/loom/knowledge/${id}/can-edit`,
  uploadToKnowledge: (id) => `/spring/ai/loom/knowledge/${id}/upload`,
  listKnowledgeFiles: (id) => `/spring/ai/loom/knowledge/${id}/file`,
  deleteKnowledgeFile: (knowledgeId, fileId) =>
    `/spring/ai/loom/knowledge/${knowledgeId}/file/${fileId}`,
  uploadFile: "/spring/ai/loom/file/upload",
  checkKnowledgeUpload: "/spring/ai/loom/knowledge/checkKnowledgeUpload",
  // Knowledge market
  // M4 T2: KB 市场 tab 默认分支改走 v2 分页路由（0-based，返回 Page{items,total,page,size}）。
  // 旧 v1 /api/knowledge-market（1-based）不再被本 SPA 调用；pull/my-submitted 等仍走各自 v1 路由。
  listMarketKnowledge: "/spring/ai/loom/market-knowledge",
  pullMarketKnowledge: (id) =>
    `/spring/ai/loom/api/knowledge-market/${id}/pull`,
  submitToMarket: (id) => `/spring/ai/loom/api/knowledge/${id}/submit`,
  listMySubmittedKnowledge: "/spring/ai/loom/api/knowledge-market/my-submitted",
  withdrawMarketKnowledge: (id) => `/spring/ai/loom/api/knowledge-market/${id}`,
  listAccessibleKnowledge: "/spring/ai/loom/api/knowledge/accessible",
  titleMaxLength: 20,
  sseTimeout: 0,
};

// ===================== §2 Global State =====================
// Cookie-based auth (BFF pattern): no token stored in localStorage.
// Browser automatically sends HttpOnly session cookie with each request.
const state = {
  username: null,
  nickname: null,
  userType: null, // 'ADMIN' / 'USER'
  conversationId: null,
  conversationTitle: null, // tracks the current conversation's title to gate auto-rename
  selectedMcps: [],      // legacy MCP checkbox state(向后兼容;新面板用 capabilities + selectedToolGroups)
  capabilities: [],     // M5:统一 capability 列表(本地 tool group + MCP server),从 /api/capabilities 拉
  selectedToolGroups: [], // M5:用户在前端勾选的本地 tool group 名列表(纯 group_name,如 "tool_file")
  enabledKnowledgeIds: [],
  selectedSkill: null, // {name, description} | null，用户通过 / 命令精准选中的 Skill
  isStreaming: false,
  controller: null, // AbortController for SSE
  mcps: [],
  skills: [],
  currentChatMessageId: null,
  pendingImages: [], // array of { fileId, objectUrl, fileName }
};

// ===================== §3 Utility Functions =====================

/** Minimal SSE event parser — replaces eventsource-parser dependency */
function createParser(handlers) {
  let buffer = "";
  return {
    feed(chunk) {
      buffer += chunk;
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop(); // keep incomplete last line
      let data = "";
      for (const line of lines) {
        if (line.startsWith("data:")) {
          data += line.slice(5).replace(/^\s/, "");
        } else if (line === "" && data) {
          handlers.onEvent?.({ data });
          data = "";
        }
      }
    },
  };
}

function showToast(message, type = "success") {
  const toast = document.getElementById("toast-notification");
  toast.textContent = message;
  toast.className = "show " + type;
  setTimeout(() => {
    toast.className = toast.className.replace("show", "");
  }, 3000);
}

/** Wrapper for fetch that clears state on 401 */
async function apiFetch(url, options = {}) {
  let resp;
  try {
    resp = await fetch(url, options);
  } catch (e) {
    if (e && e.name === "AbortError") throw e;
    // 网络错误：返回一个合成的 Response，让上层走 r.ok === false 分支
    console.warn("[apiFetch] network error for", url, e);
    return new Response(null, {
      status: 599,
      statusText: e.message || "network error",
    });
  }
  if (resp.status === 401 && state.username) {
    // Session expired or invalidated — clear client-side state
    auth.clear();
  }
  return resp;
}

/** Build a unique-by-moment default title for newly-created conversations.
 * e.g. "新对话 7-24 14:32" — two conversations opened in the same minute are
 * still distinguishable in the sidebar even before any message is sent. */
function generateDefaultConversationTitle() {
  const d = new Date();
  const M = d.getMonth() + 1;
  const D = d.getDate();
  const h = d.getHours();
  const m = String(d.getMinutes()).padStart(2, "0");
  return `新对话 ${M}-${D} ${h}:${m}`;
}

/** Detect a still-default placeholder title (used by chat.send() to decide
 * whether to auto-rename from the first user message). Matches both the bare
 * legacy "新对话" and the timestamped variant produced by
 * generateDefaultConversationTitle. */
function looksLikeDefaultConversationTitle(t) {
  if (!t) return true;
  const s = String(t).trim();
  if (s === "新对话") return true;
  return /^新对话\s+\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2}$/.test(s);
}

/** Derive an auto title from the first user message. Collapses whitespace,
 * then — when the message is longer than 12 chars — breaks at the last
 * whitespace within the first 12 so we don't leave a half-word like "bu"
 * dangling at the end. Falls back to a hard slice for CJK-only text
 * (no spaces to anchor on). Returns "新对话" for empty input. */
function deriveAutoTitleFromMessage(text) {
  const flat = String(text || "")
    .replace(/\s+/g, " ")
    .trim();
  if (!flat) return "新对话";
  if (flat.length <= 12) return flat;
  const head = flat.slice(0, 12);
  const lastSpace = head.lastIndexOf(" ");
  if (lastSpace > 0) return head.slice(0, lastSpace).trimEnd();
  return head.trimEnd();
}

// ===================== §3.5 Generic Confirm / Prompt Modal =====================
/**
 * Replaces window.confirm / window.prompt with in-app modal dialogs.
 * - dialog.confirm({ title, message, okText, cancelText, danger }) -> Promise<boolean>
 * - dialog.prompt({ title, message, placeholder, okText, defaultValue }) -> Promise<string|null>
 */
const dialog = {
  _overlay: null,
  _titleEl: null,
  _msgEl: null,
  _formEl: null,
  _inputEl: null,
  _okBtn: null,
  _cancelBtn: null,
  _closeBtn: null,

  init() {
    this._overlay = document.getElementById("confirm-modal-overlay");
    if (!this._overlay) return;
    this._titleEl = document.getElementById("confirm-modal-title");
    this._msgEl = document.getElementById("confirm-modal-message");
    this._formEl = document.getElementById("confirm-modal-form");
    this._inputEl = document.getElementById("confirm-modal-input");
    this._okBtn = document.getElementById("confirm-modal-ok");
    this._cancelBtn = document.getElementById("confirm-modal-cancel");
    this._closeBtn = document.getElementById("confirm-modal-close");

    const hide = () => this._hide();
    this._cancelBtn.addEventListener("click", hide);
    this._closeBtn.addEventListener("click", hide);
    this._overlay.addEventListener("click", (e) => {
      if (e.target === this._overlay) hide();
    });
    // ESC to close
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && this._overlay.style.display !== "none") hide();
    });
    this._inputEl.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        this._okBtn.click();
      }
    });
  },

  _show({
    title,
    message,
    okText = "确定",
    cancelText = "取消",
    danger = false,
    withInput = false,
    placeholder = "",
    defaultValue = "",
  }) {
    this._titleEl.textContent = title || "确认";
    this._msgEl.textContent = message || "";
    this._formEl.style.display = withInput ? "block" : "none";
    if (withInput) {
      this._inputEl.placeholder = placeholder;
      this._inputEl.value = defaultValue;
    }
    this._okBtn.textContent = okText;
    this._cancelBtn.textContent = cancelText;
    this._okBtn.style.background = danger
      ? "var(--error-color, #ef4444)"
      : "var(--primary-color)";
    this._okBtn.style.borderColor = danger
      ? "var(--error-color, #ef4444)"
      : "var(--primary-color)";
    this._overlay.style.display = "flex";
    if (withInput) setTimeout(() => this._inputEl.focus(), 0);
  },

  _hide() {
    this._overlay.style.display = "none";
  },

  confirm(opts) {
    return new Promise((resolve) => {
      this._show({ ...opts, withInput: false });
      const onOk = () => {
        this._cleanup(onOk, onCancel);
        this._hide();
        resolve(true);
      };
      const onCancel = () => {
        this._cleanup(onOk, onCancel);
        this._hide();
        resolve(false);
      };
      this._okBtn.addEventListener("click", onOk);
      this._cancelBtn.addEventListener("click", onCancel);
    });
  },

  prompt(opts) {
    return new Promise((resolve) => {
      this._show({ ...opts, withInput: true });
      const onOk = () => {
        const v = this._inputEl.value.trim();
        this._cleanup(onOk, onCancel);
        this._hide();
        resolve(v || null);
      };
      const onCancel = () => {
        this._cleanup(onOk, onCancel);
        this._hide();
        resolve(null);
      };
      this._okBtn.addEventListener("click", onOk);
      this._cancelBtn.addEventListener("click", onCancel);
    });
  },

  _cleanup(onOk, onCancel) {
    // Replace the elements to drop the listeners (avoids stacking on reuse)
    const freshOk = this._okBtn.cloneNode(true);
    const freshCancel = this._cancelBtn.cloneNode(true);
    this._okBtn.replaceWith(freshOk);
    this._cancelBtn.replaceWith(freshCancel);
    this._okBtn = freshOk;
    this._cancelBtn = freshCancel;
    // reattach the close-listener references aren't needed — handlers are bound to fresh elements
    void onOk;
    void onCancel;
  },
};

function formatDate(dateString) {
  if (!dateString) return "未知";
  const date = new Date(dateString);
  return date.toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatFileSize(bytes) {
  if (bytes === 0) return "0 Bytes";
  const k = 1024;
  const sizes = ["Bytes", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return (
    Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i]
  );
}

function truncateText(text, maxLength) {
  if (!text) return "";
  if (text.length <= maxLength) return text;
  return text.substring(0, maxLength) + "...";
}

/** Render Markdown with post-processing to make all links open in new tab, and LLM-output cleanup */
function renderMarkdown(text) {
  try {
    // Clean up common LLM output artifacts:
    // 1. Strip "url:" prefix from bare URLs so marked autolinks them
    text = text.replace(/url:(https?:\/\/[^\s\n]+)/g, "$1");
    // 2. Strip instruction lines about HTML <a> tags (not useful to the user)
    text = text.replace(/^使用HTML\s*<a>\s*标签.*$/gm, "").trim();
    // Parse markdown, sanitize all generated HTML, then post-process links to open in new tab
    const html = sanitizeHtml(marked.parse(text));
    return html.replace(
      /<a\s/g,
      '<a target="_blank" rel="noopener noreferrer" ',
    );
  } catch {
    return escapeHtml(text);
  }
}

// ===================== §4 API Service Layer =====================
// All requests rely on HttpOnly session cookie for auth (BFF pattern).
// No Authorization header is sent from the client.
const api = {
  async autoLogin() {
    const r = await fetch(API.autoLogin, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      credentials: "include",
    });
    if (r.status === 401) return false;
    return r.ok ? r.json() : false;
  },
  async login(req) {
    const r = await fetch(API.login, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      credentials: "include",
      body: JSON.stringify(req),
    });
    if (!r.ok) {
      let msg = `登录失败：HTTP ${r.status}`;
      try {
        const j = await r.json();
        if (j.message) msg = j.message;
      } catch (_) {}
      throw new Error(msg);
    }
    return r.json();
  },
  async logout() {
    convStatePanel.stop();
    schedulePanel.closeModal();
    const r = await fetch(API.logout, {
      method: "POST",
      credentials: "include",
    });
    return r.ok;
  },
  async currentUser() {
    const r = await fetch(API.currentUser, {
      method: "POST",
      credentials: "include",
    });
    if (!r.ok) return null;
    return r.json();
  },
  async currentIsAdmin() {
    const r = await fetch(API.currentIsAdmin, {
      method: "POST",
      credentials: "include",
    });
    if (!r.ok) return false;
    return r.json();
  },
  async changePassword(oldPassword, newPassword) {
    const r = await fetch(API.changePassword, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      credentials: "include",
      body: JSON.stringify({ oldPassword, newPassword }),
    });
    if (!r.ok) {
      let msg = `修改失败：HTTP ${r.status}`;
      try {
        const j = await r.json();
        if (j.message) msg = j.message;
      } catch (_) {}
      throw new Error(msg);
    }
    return true;
  },
  async listConversations() {
    const r = await apiFetch(API.listConversations);
    if (!r.ok) {
      try {
        await r.text();
      } catch (_) {}
      return [];
    }
    try {
      return await r.json();
    } catch (_) {
      return [];
    }
  },
  async createConversation(title = "新对话") {
    const r = await apiFetch(API.createConversation, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ title }),
    });
    return r.ok ? r.json() : null;
  },
  async renameConversation(id, title) {
    const r = await apiFetch(API.renameConversation(id), {
      method: "PATCH",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ title }),
    });
    if (r.status === 403) {
      // Cross-user rename rejection — surface a specific toast so the user
      // understands this is a permissions failure, not a generic network drop.
      return { ok: false, reason: "forbidden" };
    }
    return { ok: r.ok };
  },
  async getConversationMessages(id) {
    const r = await apiFetch(API.getConversation(id));
    return r.ok ? r.json() : [];
  },
  async deleteConversation(id) {
    const r = await apiFetch(API.deleteConversation(id), { method: "DELETE" });
    return r.ok;
  },
  async listMcps() {
    const r = await apiFetch(API.listMcps);
    if (!r.ok) {
      // Drain body to release the connection, then return empty
      try {
        await r.text();
      } catch (_) {}
      return [];
    }
    try {
      return await r.json();
    } catch (_) {
      return [];
    }
  },
  async listSkills() {
    const r = await apiFetch(API.listSkills);
    return r.ok ? r.json() : [];
  },
  async getSkill(name) {
    const r = await apiFetch(API.getSkill(name));
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },
  async upsertSkill(skill) {
    const r = await apiFetch("/spring/ai/loom/skill/upsert", {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(skill),
    });
    if (!r.ok) {
      let msg = "HTTP " + r.status;
      try {
        const j = await r.json();
        if (j.error) msg = j.error;
      } catch (_) {}
      throw new Error(msg);
    }
    return r.json();
  },
  async createSkill(skill) {
    const r = await apiFetch(API.createSkill, {
      method: "PUT",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(skill),
    });
    return r.ok ? r.json() : null;
  },
  async updateSkill(skill) {
    const r = await apiFetch(API.updateSkill, {
      method: "PUT",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(skill),
    });
    return r.ok ? r.json() : null;
  },
  async deleteSkill(name) {
    const r = await apiFetch(API.deleteSkill(name), {
      method: "DELETE",
    });
    return r.ok ? r.json() : null;
  },
  async patchSkill(name, body) {
    const r = await apiFetch(API.patchSkill(name), {
      method: "PATCH",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(body),
    });
    return r.ok ? r.json() : null;
  },
  // M4 T2: v2 分页路由 — page 0-based，返回 Page{items,total,page,size}；
  // query 非空时附加 &query=（服务端搜索）。
  // M4 T7: sortBy 非空时附加 &sortBy=（official_rank / submitted_at / rating；
  // 仅分页分支有效，?tag= 分支服务端忽略排序 — 调用方在 tag 激活时不应传）。
  async listMarketSkills(page = 0, size = 20, query = "", sortBy = "") {
    let url = `${API.listMarketSkills}?page=${page}&size=${size}`;
    if (query) url += `&query=${encodeURIComponent(query)}`;
    if (sortBy) url += `&sortBy=${encodeURIComponent(sortBy)}`;
    const r = await apiFetch(url);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },
  async pullMarketSkill(id) {
    const r = await apiFetch(API.pullMarketSkill(id), { method: "POST" });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async submitMarketSkill(body) {
    const r = await apiFetch(API.submitMarketSkill, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async listMySubmittedSkills() {
    const r = await apiFetch(API.listMySubmittedSkills);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },
  async withdrawMarketSkill(id) {
    const r = await apiFetch(API.withdrawMarketSkill(id), { method: "DELETE" });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async listKnowledge() {
    const r = await apiFetch(API.listKnowledge);
    return r.ok ? r.json() : [];
  },
  async createKnowledge(name, description) {
    const r = await apiFetch(API.createKnowledge, {
      method: "PUT",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ name, description }),
    });
    return r.ok ? r.json() : null;
  },
  async deleteKnowledge(id) {
    const r = await apiFetch(API.deleteKnowledge(id), { method: "DELETE" });
    return r.ok ? r.json() : null;
  },
  async updateKnowledge(id, name, description) {
    const r = await apiFetch(API.updateKnowledge(id), {
      method: "PATCH",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ name, description }),
    });
    if (r.ok) return { ok: true };
    const err = await r.json().catch(() => ({}));
    return { ok: false, message: err.message || "修改失败" };
  },
  async canEditKnowledge(id) {
    const r = await apiFetch(API.canEditKnowledge(id));
    if (!r.ok) return false;
    const data = await r.json();
    return data.canEdit;
  },
  async listKnowledgeFiles(id) {
    const r = await apiFetch(API.listKnowledgeFiles(id));
    return r.ok ? r.json() : [];
  },
  async uploadToKnowledge(id, file) {
    const fd = new FormData();
    fd.append("file", file);
    const r = await apiFetch(API.uploadToKnowledge(id), {
      method: "POST",
      body: fd,
    });
    return r.ok ? r.json() : null;
  },
  async deleteKnowledgeFile(knowledgeId, fileId) {
    const r = await apiFetch(API.deleteKnowledgeFile(knowledgeId, fileId), {
      method: "DELETE",
    });
    return r.ok ? r.json() : null;
  },

  // Knowledge market
  // M4 T2: v2 分页路由 — page 0-based（默认 0），返回 Page{items,total,page,size}。
  // M4 T7: +query（服务端关键词搜索）+sortBy（official_rank / submitted_at / rating；
  // 仅分页分支有效，?tag= 分支服务端忽略两者 — 调用方在 tag 激活时不应传）。
  async listMarketKnowledge(page = 0, size = 20, query = "", sortBy = "") {
    let url = `${API.listMarketKnowledge}?page=${page}&size=${size}`;
    if (query) url += `&query=${encodeURIComponent(query)}`;
    if (sortBy) url += `&sortBy=${encodeURIComponent(sortBy)}`;
    const r = await apiFetch(url);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },
  async pullMarketKnowledge(id) {
    const r = await apiFetch(API.pullMarketKnowledge(id), { method: "POST" });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async submitToMarket(knowledgeId) {
    const r = await apiFetch(API.submitToMarket(knowledgeId), {
      method: "POST",
    });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async listMySubmittedKnowledge() {
    const r = await apiFetch(API.listMySubmittedKnowledge);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },
  async withdrawMarketKnowledge(id) {
    const r = await apiFetch(API.withdrawMarketKnowledge(id), {
      method: "DELETE",
    });
    if (!r.ok) throw new Error((await r.text()) || "HTTP " + r.status);
    return r.json();
  },
  async listAccessibleKnowledge() {
    const r = await apiFetch(API.listAccessibleKnowledge);
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  },

  async uploadFile(file) {
    const fd = new FormData();
    fd.append("file", file);
    const r = await apiFetch(API.uploadFile, { method: "POST", body: fd });
    return r.ok ? r.json() : null;
  },
  async uploadImage(file) {
    const data = await api.uploadFile(file);
    if (data && data.fileId) {
      return { fileId: data.fileId, status: data.status };
    }
    throw new Error("上传失败：未返回 fileId");
  },
  async checkKnowledgeUpload() {
    const r = await apiFetch(API.checkKnowledgeUpload);
    return r.ok;
  },
  async listFileTree() {
    const r = await apiFetch("/spring/ai/loom/file/tree");
    return r.ok ? r.json() : { name: ".", type: "directory", children: [] };
  },
  async listActiveSubtasks(conversationId) {
    const q = conversationId
      ? "?conversationId=" + encodeURIComponent(conversationId)
      : "";
    const r = await apiFetch("/spring/ai/loom/subtask/list/active" + q);
    return r.ok ? r.json() : [];
  },
  async listSubtaskHistory(conversationId) {
    const q = conversationId
      ? "?conversationId=" + encodeURIComponent(conversationId)
      : "";
    const r = await apiFetch("/spring/ai/loom/subtask/list/history" + q);
    return r.ok ? r.json() : [];
  },
  async subtaskLimits() {
    const r = await apiFetch("/spring/ai/loom/subtask/limits");
    return r.ok ? r.json() : {};
  },
  async killSubtask(id) {
    const r = await apiFetch(
      "/spring/ai/loom/subtask/kill/" + encodeURIComponent(id),
      { method: "POST" },
    );
    return r.ok;
  },
  async deleteSubtaskHistory(id) {
    const r = await apiFetch(
      "/spring/ai/loom/subtask/history/" + encodeURIComponent(id),
      { method: "DELETE" },
    );
    return r.ok;
  },
  async listSchedules(conversationId) {
    const q = conversationId
      ? "?conversationId=" + encodeURIComponent(conversationId)
      : "";
    const r = await apiFetch("/spring/ai/loom/schedule/list" + q);
    return r.ok ? r.json() : [];
  },
  async cancelSchedule(fullName) {
    const r = await apiFetch("/spring/ai/loom/schedule/cancel", {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify({ name: fullName }),
    });
    return r.ok;
  },
  async scheduleHistory(fullName) {
    const r = await apiFetch(
      "/spring/ai/loom/schedule/history/" + encodeURIComponent(fullName),
    );
    return r.ok ? r.json() : [];
  },
  async scheduleByConversation(convId) {
    const r = await apiFetch(
      "/spring/ai/loom/schedule/history/by-conversation/" +
        encodeURIComponent(convId),
    );
    return r.ok ? r.json() : [];
  },
  async scheduleLimits() {
    const r = await apiFetch("/spring/ai/loom/schedule/limits");
    return r.ok ? r.json() : {};
  },
  async cancelAllSchedulesByConversation(convId) {
    const r = await apiFetch(
      "/spring/ai/loom/schedule/by-conversation/" +
        encodeURIComponent(convId) +
        "/cancel-all",
      {
        method: "POST",
      },
    );
    return r.ok ? r.json() : { cancelled: 0 };
  },
  async conversationState(convId) {
    const r = await apiFetch(
      "/spring/ai/loom/conversation/" + encodeURIComponent(convId) + "/state",
    );
    return r.ok ? r.json() : null;
  },
  async streamChat(record, onChunk, onComplete, onError, signal) {
    const resp = await apiFetch(API.stream, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=UTF-8" },
      body: JSON.stringify(record),
      signal,
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    const parser = createParser({
      onEvent: (event) => {
        const data = JSON.parse(event.data);
        onChunk(data);
      },
    });

    async function read() {
      const { done, value } = await reader.read();
      if (done) {
        onComplete();
        return;
      }
      parser.feed(decoder.decode(value, { stream: true }));
      await read();
    }
    try {
      await read();
    } catch (e) {
      // Abort is a user-initiated stop, not an error — swallow it.
      if (e && e.name === "AbortError") {
        try {
          reader.cancel();
        } catch (_) {}
        onComplete();
        return;
      }
      throw e;
    }
  },
};

// ===================== §5 Auth Module =====================
const auth = {
  /** 页面加载时初始化。
   * 1. 调 isAutoLogin 检查 session cookie
   * 2. 没登录就跳 login.html
   * 3. 登录了则拉取 currentUser 渲染右上角用户菜单
   */
  async init() {
    try {
      const loggedIn = await api.autoLogin();
      if (loggedIn !== true) {
        window.location.replace("/spring/ai/loom/login.html");
        return false;
      }
      const me = await api.currentUser();
      if (!me || !me.username) {
        window.location.replace("/spring/ai/loom/login.html");
        return false;
      }
      state.username = me.username;
      state.nickname = me.nickname;
      state.userType = me.type;
      this.renderUserMenu();
      return true;
    } catch (e) {
      window.location.replace("/spring/ai/loom/login.html");
      return false;
    }
  },

  /** 渲染右上角用户菜单（昵称 + 下拉） */
  renderUserMenu() {
    const container = document.getElementById("user-menu");
    if (!container) return;
    const isAdmin = state.userType === "ADMIN";
    container.innerHTML = `
 <div class="user-menu-wrapper" id="user-menu-wrapper">
 <button class="user-menu-trigger" id="user-menu-trigger">
 <span class="user-menu-avatar">${(state.nickname || state.username || "?").charAt(0).toUpperCase()}</span>
 <span class="user-menu-name">${escapeHtml(state.nickname || state.username)}</span>
 ${isAdmin ? '<span class="user-menu-badge">管理员</span>' : ""}
 <span class="user-menu-caret">▾</span>
 </button>
 <div class="user-menu-dropdown" id="user-menu-dropdown" style="display: none;">
 ${isAdmin ? '<a class="user-menu-item" href="/spring/ai/loom/admin/console.html">控制台</a>' : ""}
 <a class="user-menu-item" id="user-menu-usage">我的用量</a>
 <a class="user-menu-item" id="user-menu-change-password">修改密码</a>
 <a class="user-menu-item user-menu-item-danger" id="user-menu-logout">登出</a>
 </div>
 </div>
 `;
    const trigger = document.getElementById("user-menu-trigger");
    const dropdown = document.getElementById("user-menu-dropdown");
    trigger.addEventListener("click", (e) => {
      e.stopPropagation();
      dropdown.style.display =
        dropdown.style.display === "none" ? "block" : "none";
    });
    document.addEventListener("click", () => {
      dropdown.style.display = "none";
    });
    const changePwd = document.getElementById("user-menu-change-password");
    if (changePwd) {
      changePwd.addEventListener("click", (e) => {
        e.preventDefault();
        dropdown.style.display = "none";
        this.showChangePasswordModal();
      });
    }
    const usage = document.getElementById("user-menu-usage");
    if (usage) {
      usage.addEventListener("click", (e) => {
        e.preventDefault();
        dropdown.style.display = "none";
        this.showUsageModal();
      });
    }
    const logout = document.getElementById("user-menu-logout");
    if (logout) {
      logout.addEventListener("click", async (e) => {
        e.preventDefault();
        dropdown.style.display = "none";
        await this.logout();
      });
    }
  },

  /** 显示修改密码模态框 */
  showChangePasswordModal() {
    let modal = document.getElementById("change-password-modal");
    if (!modal) {
      modal = document.createElement("div");
      modal.id = "change-password-modal";
      modal.className = "modal-overlay";
      modal.innerHTML = `
 <div class="modal-content" style="max-width: 440px;">
 <div class="modal-header">
 <h3>修改密码</h3>
 <div class="close-button" id="change-pwd-close">&times;</div>
 </div>
 <div class="modal-body" style="padding: 24px 32px;">
 <div style="margin-bottom: 16px;">
 <label style="display: block; margin-bottom: 6px; font-size: 13px;">旧密码</label>
 <input type="password" id="change-pwd-old" class="param-input" style="width: 100%;" />
 </div>
 <div style="margin-bottom: 16px;">
 <label style="display: block; margin-bottom: 6px; font-size: 13px;">新密码（至少 6 位）</label>
 <input type="password" id="change-pwd-new" class="param-input" style="width: 100%;" />
 </div>
 <div style="margin-bottom: 8px;">
 <label style="display: block; margin-bottom: 6px; font-size: 13px;">确认新密码</label>
 <input type="password" id="change-pwd-confirm" class="param-input" style="width: 100%;" />
 </div>
 <div id="change-pwd-error" class="error-msg" style="display: none; margin-top: 8px;"></div>
 </div>
 <div class="modal-footer" style="padding: 14px 32px; border-top: 1px solid var(--border-color); display: flex; justify-content: flex-end; gap: 12px;">
 <button class="modal-action-btn" id="change-pwd-cancel">取消</button>
 <button class="modal-action-btn" id="change-pwd-submit" style="background: var(--primary-color); color: #fff; border-color: var(--primary-color);">确定</button>
 </div>
 </div>
 `;
      modal.addEventListener("click", (e) => {
        if (e.target === modal) modal.style.display = "none";
      });
      document.body.appendChild(modal);
      document
        .getElementById("change-pwd-close")
        .addEventListener("click", () => {
          modal.style.display = "none";
        });
      document
        .getElementById("change-pwd-cancel")
        .addEventListener("click", () => {
          modal.style.display = "none";
        });
      document
        .getElementById("change-pwd-submit")
        .addEventListener("click", () => this.submitChangePassword(modal));
    }
    // 重置
    document.getElementById("change-pwd-old").value = "";
    document.getElementById("change-pwd-new").value = "";
    document.getElementById("change-pwd-confirm").value = "";
    document.getElementById("change-pwd-error").style.display = "none";
    modal.style.display = "flex";
    setTimeout(() => document.getElementById("change-pwd-old")?.focus(), 50);
  },

  async submitChangePassword(modal) {
    const oldPwd = document.getElementById("change-pwd-old").value;
    const newPwd = document.getElementById("change-pwd-new").value;
    const confirmPwd = document.getElementById("change-pwd-confirm").value;
    const errEl = document.getElementById("change-pwd-error");
    errEl.style.display = "none";
    if (!oldPwd || !newPwd) {
      errEl.textContent = "请填写所有字段";
      errEl.style.display = "block";
      return;
    }
    if (newPwd.length < 6) {
      errEl.textContent = "新密码至少 6 位";
      errEl.style.display = "block";
      return;
    }
    if (newPwd !== confirmPwd) {
      errEl.textContent = "两次输入的新密码不一致";
      errEl.style.display = "block";
      return;
    }
    const submitBtn = document.getElementById("change-pwd-submit");
    submitBtn.disabled = true;
    try {
      await api.changePassword(oldPwd, newPwd);
      modal.style.display = "none";
      showToast("密码修改成功", "success");
    } catch (e) {
      errEl.textContent = e.message;
      errEl.style.display = "block";
    } finally {
      submitBtn.disabled = false;
    }
  },

  /** 显示"我的用量"模态框（本月 token 用量） */
  async showUsageModal() {
    let modal = document.getElementById("usage-modal");
    if (!modal) {
      modal = document.createElement("div");
      modal.id = "usage-modal";
      modal.className = "modal-overlay";
      modal.innerHTML = `
 <div class="modal-content" style="max-width: 480px;">
 <div class="modal-header">
 <h3>本月用量</h3>
 <div class="close-button" id="usage-modal-close">&times;</div>
 </div>
 <div class="modal-body" style="padding: 24px 32px;">
 <div id="usage-loading" style="text-align: center; color: var(--text-muted);">加载中...</div>
 <div id="usage-content" style="display: none;">
 <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px;">
 <div class="usage-stat">
 <div class="usage-stat-label">总 Token</div>
 <div class="usage-stat-value" id="usage-total">-</div>
 </div>
 <div class="usage-stat">
 <div class="usage-stat-label">调用次数</div>
 <div class="usage-stat-value" id="usage-calls">-</div>
 </div>
 <div class="usage-stat">
 <div class="usage-stat-label">输入 Token</div>
 <div class="usage-stat-value" id="usage-prompt">-</div>
 </div>
 <div class="usage-stat">
 <div class="usage-stat-label">输出 Token</div>
 <div class="usage-stat-value" id="usage-completion">-</div>
 </div>
 </div>
 <div style="font-size: 12px; color: var(--text-muted); text-align: right; margin-top: 8px;">
 平均每次：<span id="usage-avg">-</span>
 </div>
 </div>
 </div>
 </div>
 `;
      modal.addEventListener("click", (e) => {
        if (e.target === modal) modal.style.display = "none";
      });
      document.body.appendChild(modal);
      document
        .getElementById("usage-modal-close")
        .addEventListener("click", () => {
          modal.style.display = "none";
        });
    }
    modal.style.display = "flex";
    document.getElementById("usage-loading").style.display = "block";
    document.getElementById("usage-content").style.display = "none";
    try {
      const r = await fetch("/spring/ai/loom/user/tokens/current-month", {
        credentials: "include",
      });
      if (!r.ok) throw new Error("HTTP " + r.status);
      const data = await r.json();
      document.getElementById("usage-total").textContent =
        data.totalTokens.toLocaleString();
      document.getElementById("usage-calls").textContent =
        data.callCount.toLocaleString();
      document.getElementById("usage-prompt").textContent =
        data.promptTokens.toLocaleString();
      document.getElementById("usage-completion").textContent =
        data.completionTokens.toLocaleString();
      document.getElementById("usage-avg").textContent = Math.round(
        data.avgTokensPerCall,
      ).toLocaleString();
      document.getElementById("usage-loading").style.display = "none";
      document.getElementById("usage-content").style.display = "block";
    } catch (e) {
      document.getElementById("usage-loading").textContent =
        "加载失败：" + e.message;
    }
  },

  /** Clear auth state — called on 401. Redirect to login. */
  clear() {
    window.location.replace("/spring/ai/loom/login.html");
  },

  /** 登出：服务端失效 session + 清 cookie，跳 login.html */
  async logout() {
    try {
      await api.logout();
    } catch {
      /* ignore */
    }
    window.location.replace("/spring/ai/loom/login.html");
  },
};

// ===================== §12 UI Components (only DOM manipulation) =====================
const aiImage = "/static/ai.png";
const userImage = "/static/user.png";

const ui = {
  mainContent: null,

  init() {
    this.mainContent = document.getElementById("mainContent");
  },

  clearChat() {
    this.mainContent.innerHTML = `
 <div class="welcome-message">
 <h2>你好！我是你的 AI 助手</h2>
 <p>有什么我可以帮助你的吗？现在可以开始聊天了</p>
 </div>`;
  },

  renderUserMessage(text, attachments) {
    const item = document.createElement("div");
    item.className = "chat-item chat-item-right";
    let attachHtml = "";
    if (attachments && attachments.length > 0) {
      // Render a compact strip of attached files so the user can see
      // what they actually sent (the AI gets fileIds on the wire, but
      // without this strip the user bubble would only show the text).
      attachHtml =
        '<div class="user-attachments">' +
        attachments
          .map((a) => {
            if (a.objectUrl) {
              return `<div class="user-attach-thumb" title="${escapeHtml(a.fileName)}">
 <img src="${a.objectUrl}" alt="${escapeHtml(a.fileName)}"/>
 </div>`;
            }
            // Document — reuse the doc-icon SVG the upload module already renders
            const ext = "." + (a.fileName || "").split(".").pop().toLowerCase();
            const icons = (imageUpload && imageUpload.DOC_ICONS) || {};
            const svg = icons[ext] || icons[".txt"] || "";
            return `<div class="user-attach-doc" title="${escapeHtml(a.fileName)}">
 <div class="user-attach-doc-icon">${svg}</div>
 <div class="user-attach-doc-name">${escapeHtml(a.fileName)}</div>
 </div>`;
          })
          .join("") +
        "</div>";
    }
    const textHtml = text
      ? `<div style="margin: 16px">${renderMarkdown(text)}</div>`
      : "";
    item.innerHTML = `
 <div class="bubble">${attachHtml}${textHtml}</div>
 <div class="avatar"><img src="${userImage}" alt="用户"/></div>`;
    this.mainContent.appendChild(item);
    this.scrollToBottom();
  },

  renderBotMessage(id) {
    const item = document.createElement("div");
    item.className = "chat-item chat-item-left";
    item.innerHTML = `
 <div class="avatar"><img src="${aiImage}" alt="AI"/></div>
 <div class="bubble">
 <div class="thinking-container" id="thinking-${id}" style="display: none;">
 <div class="thinking-header" onclick="ui.toggleThinking('${id}')">
 <span class="thinking-title">思考过程</span>
 <span class="thinking-arrow" id="arrow-${id}">▼</span>
 </div>
 <div class="thinking-content" id="thinking-content-${id}">
 <div class="thinking-body" id="thinking-body-${id}"></div>
 </div>
 </div>
 <div id="origin-${id}" style="display: none"></div>
 <div id="${id}" style="margin: 16px"></div>
 <div class="bubble-actions" id="actions-${id}" style="display: none;">
 <button class="bubble-action-btn" onclick="ui.copyMarkdown('origin-${id}')">
 <span>📋</span><span>复制</span>
 </button>
 <button class="bubble-action-btn" onclick="ui.downloadMarkdown('origin-${id}')">
 <span>💾</span><span>下载</span>
 </button>
 </div>
 </div>`;
    this.mainContent.appendChild(item);
    this.scrollToBottom();
  },

  renderMessages(messages) {
    this.clearChat();
    if (!messages || messages.length === 0) return;
    for (const msg of messages) {
      const role = msg.messageType || msg.role || msg.getMessage?.();
      const content =
        msg.text || msg.content || msg.getContent?.() || msg.getText?.() || "";
      if (role === "USER" || role === "user") {
        this.renderUserMessage(content);
      } else if (
        role === "ASSISTANT" ||
        role === "assistant" ||
        role === "MODEL"
      ) {
        const id =
          "hist-" + Date.now() + "-" + Math.random().toString(36).slice(2, 6);
        this.renderBotMessage(id);
        const el = document.getElementById(id);
        if (el) el.innerHTML = renderMarkdown(content);
        const origin = document.getElementById("origin-" + id);
        if (origin) origin.innerHTML = content;
        // Show actions for historical messages
        const actions = document.getElementById("actions-" + id);
        if (actions) actions.style.display = "";
      }
    }
  },

  scrollToBottom() {
    if (this.mainContent)
      this.mainContent.scrollTop = this.mainContent.scrollHeight;
  },

  toggleThinking(id) {
    const content = document.getElementById(`thinking-content-${id}`);
    const arrow = document.getElementById(`arrow-${id}`);
    if (!content || !arrow) return;
    content.classList.toggle("expanded");
    arrow.classList.toggle("expanded");
  },

  copyMarkdown(id) {
    const el = document.getElementById(id);
    if (!el) {
      showToast("消息未找到", "error");
      return;
    }
    const text = el.textContent;
    if (!text || !text.trim()) {
      showToast("没有可复制的内容", "error");
      return;
    }
    navigator.clipboard
      .writeText(text)
      .then(() => showToast("复制成功！", "success"))
      .catch(() => showToast("复制失败，请手动复制", "error"));
  },

  downloadMarkdown(id) {
    const el = document.getElementById(id);
    if (!el) {
      showToast("消息未找到", "error");
      return;
    }
    const content = el.textContent;
    if (!content || !content.trim()) {
      showToast("没有可下载的内容", "error");
      return;
    }
    const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    const ts = new Date().toISOString().replace(/[:.]/g, "-").slice(0, -5);
    link.download = `chat-${ts}.md`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showToast("下载成功！", "success");
  },

  enableSend() {
    const ta = document.getElementById("textarea");
    const btn = document.getElementById("send-btn");
    ta.disabled = false;
    btn.disabled = false;
    btn.textContent = "发送消息";
    state.isStreaming = false;
    ui.setToolbarLocked(false);
    ui.setStopButtonVisible(false);
    convStatePanel.refresh(); // refresh after every stream completion
  },

  disableSend() {
    const ta = document.getElementById("textarea");
    const btn = document.getElementById("send-btn");
    ta.value = "";
    ta.disabled = true;
    btn.disabled = true;
    btn.textContent = "发送中...";
    state.isStreaming = true;
    ui.setToolbarLocked(true);
    ui.setStopButtonVisible(true);
  },

  setStopButtonVisible(visible) {
    const btn = document.getElementById("stop-btn");
    if (!btn) return;
    btn.style.display = visible ? "inline-block" : "none";
    btn.disabled = false;
    btn.textContent = "停止";
  },

  showModal(id) {
    document.getElementById(id).style.display = "flex";
  },

  hideModal(id) {
    document.getElementById(id).style.display = "none";
  },

  toggleSidebar() {
    const sidebar = document.getElementById("sidebar");
    const toggle = document.getElementById("sidebar-toggle");
    const isOpen = sidebar.classList.toggle("open");
    toggle.textContent = isOpen ? "✕" : "☰";
  },

  /** Lock/unlock the 4 toolbar buttons (knowledge / MCP / skills / file) and the + new chat button during streaming. */
  setToolbarLocked(locked) {
    const ids = [
      "ks-button",
      "mcp-button",
      "skills-button",
      "file-manager-button",
      "new-chat-btn",
    ];
    for (const id of ids) {
      const btn = document.getElementById(id);
      if (!btn) continue;
      btn.disabled = locked;
      // Set inline styles directly to bypass CSS transition delay (so visual + click-block take effect immediately)
      if (locked) {
        btn.style.setProperty("opacity", "0.4", "important");
        btn.style.setProperty("pointer-events", "none", "important");
        btn.style.setProperty("cursor", "not-allowed", "important");
        btn.setAttribute("aria-disabled", "true");
        btn.title = "请等待 AI 回复完成";
      } else {
        btn.style.removeProperty("opacity");
        btn.style.removeProperty("pointer-events");
        btn.style.removeProperty("cursor");
        btn.removeAttribute("aria-disabled");
        btn.title = "";
      }
    }
    // Lock conversation history items (rendered dynamically, so use class on sidebar)
    const sidebar = document.getElementById("sidebarList");
    if (sidebar) {
      if (locked) {
        sidebar.classList.add("sidebar-locked");
      } else {
        sidebar.classList.remove("sidebar-locked");
      }
    }
  },
};

// ===================== §6 Conversation Management =====================
const conversation = {
  async loadList() {
    try {
      const data = await api.listConversations();
      this.renderSidebar(data);
    } catch (e) {
      console.warn("[conversation.loadList] failed:", e);
      this.renderSidebar([]);
    }
  },

  renderSidebar(list) {
    const container = document.getElementById("sidebarList");
    if (!list || list.length === 0) {
      container.innerHTML = '<div class="sidebar-empty">暂无对话</div>';
      return;
    }
    container.innerHTML = "";
    for (const item of list) {
      const id = item.conversationId || item.id;
      const title =
        item.title || truncateText(item.name || "新对话", API.titleMaxLength);
      // Keep state.conversationTitle in sync for the currently-active conversation
      // so chat.send() can decide whether to auto-rename from the first message.
      if (id === state.conversationId) {
        state.conversationTitle = item.title || item.name || "新对话";
      }
      const div = document.createElement("div");
      div.className =
        "sidebar-item" + (state.conversationId === id ? " active" : "");
      div.dataset.conversationId = id;

      const text = document.createElement("span");
      text.className = "sidebar-item-text";
      text.title = title;
      text.textContent = title;

      const actions = document.createElement("span");
      actions.className = "sidebar-item-actions";
      const renameBtn = document.createElement("button");
      renameBtn.className = "sidebar-item-rename";
      renameBtn.title = "重命名对话";
      renameBtn.setAttribute("aria-label", "重命名对话");
      renameBtn.textContent = "✎";
      const deleteBtn = document.createElement("button");
      deleteBtn.className = "sidebar-item-delete";
      deleteBtn.title = "删除对话";
      deleteBtn.setAttribute("aria-label", "删除对话");
      deleteBtn.innerHTML = "&times;";
      actions.append(renameBtn, deleteBtn);
      div.append(text, actions);

      div.addEventListener("click", (e) => {
        if (
          e.target.closest(".sidebar-item-actions") ||
          e.target.classList.contains("sidebar-item-edit")
        )
          return;
        this.switchTo(id);
      });
      renameBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.startRename(div, id, title);
      });
      deleteBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.delete(id);
      });
      container.appendChild(div);
    }
  },

  async createNew() {
    if (state.isStreaming) {
      showToast("请等待 AI 回复完成", "warning");
      return;
    }
    const defaultTitle = generateDefaultConversationTitle();
    const created = await api.createConversation(defaultTitle);
    if (!created) {
      showToast("新建对话失败", "error");
      return;
    }
    state.conversationId = created.conversationId || created.id;
    state.conversationTitle = defaultTitle;
    ui.clearChat();
    imageUpload.clear();
    await this.loadList();
    // Notify panels of the conversation switch so an open sub-task or
    // schedule modal can re-fetch the per-conversation list.
    try {
      subtaskPanel.setConvId(state.conversationId);
    } catch (_) {}
    try {
      schedulePanel.setConvId(state.conversationId);
    } catch (_) {}
  },

  /** Auto-rename from the first user message when the title is still the default
   * placeholder. Idempotent per session: a manually renamed conversation or one
   * already auto-renamed never re-fires. No-op on empty text or stale conv ids. */
  async maybeAutoRename(convId, userText) {
    if (!convId || !userText) return;
    if (!looksLikeDefaultConversationTitle(state.conversationTitle)) return;
    const autoTitle = deriveAutoTitleFromMessage(userText);
    if (!autoTitle || autoTitle === state.conversationTitle) return;
    const result = await api.renameConversation(convId, autoTitle);
    if (result && result.ok) {
      state.conversationTitle = autoTitle;
      await this.loadList();
    }
  },

  startRename(div, id, currentTitle) {
    if (state.isStreaming) {
      showToast("请等待 AI 回复完成", "warning");
      return;
    }
    const text = div.querySelector(".sidebar-item-text");
    const actions = div.querySelector(".sidebar-item-actions");
    if (!text || !actions || div.querySelector(".sidebar-item-edit")) return;
    text.style.display = "none";
    actions.style.display = "none";

    const input = document.createElement("input");
    input.className = "sidebar-item-edit";
    input.type = "text";
    input.maxLength = 100;
    input.value = currentTitle;
    input.setAttribute("aria-label", "对话名称");
    div.insertBefore(input, text);
    input.focus();
    input.select();

    let completed = false;
    const cancel = () => {
      if (completed) return;
      completed = true;
      input.remove();
      text.style.removeProperty("display");
      actions.style.removeProperty("display");
    };
    const save = async () => {
      if (completed) return;
      const title = input.value.trim();
      if (!title) {
        showToast("对话名称不能为空", "warning");
        input.focus();
        return;
      }
      completed = true;
      const result = await api.renameConversation(id, title);
      if (result.ok) {
        if (state.conversationId === id) state.conversationTitle = title;
        await this.loadList();
        showToast("对话已重命名", "success");
      } else {
        input.remove();
        text.style.removeProperty("display");
        actions.style.removeProperty("display");
        showToast(
          result.reason === "forbidden" ? "无权重命名该对话" : "重命名失败",
          "error",
        );
      }
    };
    input.addEventListener("click", (e) => e.stopPropagation());
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        save();
      }
      if (e.key === "Escape") {
        e.preventDefault();
        cancel();
      }
    });
    input.addEventListener("blur", () => {
      if (!completed) cancel();
    });
  },

  async switchTo(id) {
    if (state.isStreaming) {
      showToast("请等待 AI 回复完成", "warning");
      return;
    }
    // abort any ongoing stream
    chat.abortStream();

    selectedSkillTag.onConversationSwitch();

    state.conversationId = id;
    try {
      subtaskPanel.setConvId(id);
    } catch (_) {}
    try {
      schedulePanel.setConvId(id);
    } catch (_) {}
    try {
      const messages = await api.getConversationMessages(id);
      ui.renderMessages(messages);
      convStatePanel.start(); // refresh state strip on conversation switch
    } catch (e) {
      showToast("加载对话失败", "error");
    }
    this.loadList(); // re-render highlight
  },

  async delete(id) {
    const ok = await dialog.confirm({
      title: "删除对话",
      message: "确定要删除这个对话吗？此操作不可撤销。",
      okText: "删除",
      danger: true,
    });
    if (!ok) return;
    const deleted = await api.deleteConversation(id);
    if (deleted) {
      if (state.conversationId === id) {
        const remaining = (await api.listConversations()).filter(
          (item) => (item.conversationId || item.id) !== id,
        );
        if (remaining.length > 0) {
          await this.switchTo(remaining[0].conversationId || remaining[0].id);
        } else {
          state.conversationId = null;
          ui.clearChat();
          imageUpload.clear();
          try {
            subtaskPanel.setConvId(null);
          } catch (_) {}
          try {
            schedulePanel.setConvId(null);
          } catch (_) {}
        }
      }
      await this.loadList();
      showToast("对话已删除", "success");
    } else {
      showToast("删除失败", "error");
    }
  },

  refreshSidebar() {
    this.loadList();
  },
};

/**
 * #1 AskUser:LLM 提问卡片(聊天流内嵌,spec D1)。
 * 卡片仅活于当前流:提交/超时/取消后就地定格;刷新页面不重建(历史里只有文本)。
 */
const askUserCards = (() => {
  const active = new Map(); // questionId -> { el, timer, submitBtn, countdownEl }

  function fmtRemaining(sec) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${String(s).padStart(2, "0")}`;
  }

  function freeze(qid, stateText, ok) {
    const card = active.get(qid);
    if (!card) return;
    clearInterval(card.timer);
    active.delete(qid);
    card.el.classList.add("askuser-frozen");
    card.el.querySelectorAll("input,button").forEach((n) => (n.disabled = true));
    // 终态隐藏提交按钮:否则按钮会永远停在"提交中..."(submit 在 fetch 前设的
    // 在飞标签,freeze 只 disable 不复位)—— 看起来像卡死。终态语义由徽章表达
    // (已答 ✓ / 已超时 / 已取消 / 已失效),四种终态共用本函数,无单一合适按钮文案。
    if (card.submitBtn) card.submitBtn.style.display = "none";
    const badge = card.el.querySelector(".askuser-state");
    if (badge) {
      badge.textContent = stateText;
      badge.classList.toggle("askuser-state-ok", !!ok);
    }
  }

  async function submit(qid, ev) {
    const card = active.get(qid);
    if (!card) return;
    const inputs = card.el.querySelectorAll(".askuser-opt-input:checked");
    const labels = Array.from(inputs).map((n) => n.value);
    const customInput = card.el.querySelector(".askuser-custom-input");
    const customText = customInput ? customInput.value.trim() : "";
    if (customText) labels.push(customText);
    // 单选 + 自定义输入时,"其他" radio 的 value="" 会混进来 —— 过滤空白后再组装 payload
    const vals = labels.map((s) => s.trim()).filter(Boolean);
    if (vals.length === 0) {
      showToast("请先选择一个选项或输入自定义答案", "error");
      return;
    }
    card.submitBtn.disabled = true;
    card.submitBtn.textContent = "提交中...";
    // 非 404 失败(400/500/网络异常)可重试:恢复卡片待提交态,倒计时继续跑
    const restorePending = (msg) => {
      card.submitBtn.disabled = false;
      card.submitBtn.textContent = "提交答案";
      const badge = card.el.querySelector(".askuser-state");
      if (badge) {
        badge.textContent = "";
        badge.classList.remove("askuser-state-ok");
      }
      showToast(msg, "error");
    };
    try {
      const r = await fetch(
        `/spring/ai/loom/ask/${encodeURIComponent(qid)}/answer`,
        {
          method: "POST",
          credentials: "include",
          headers: { "Content-Type": "application/json; charset=UTF-8" },
          body: JSON.stringify({ answer: ev.multiSelect ? vals : vals[0] }),
        },
      );
      if (r.ok) {
        freeze(qid, "已答 ✓", true);
      } else if (r.status === 404) {
        // 404 = 已超时/已取消/已失效(spec §5 竞态行)—— 唯一不可重试的情况
        freeze(qid, "已失效(超时或已取消)", false);
      } else {
        restorePending(`提交失败(HTTP ${r.status}),请重试`);
      }
    } catch (e) {
      restorePending("提交失败:" + (e.message || "网络错误") + ",请重试");
    }
  }

  function render(ev) {
    if (!ev || !ev.questionId) return;
    const qid = ev.questionId;
    const inputType = ev.multiSelect ? "checkbox" : "radio";
    const optionsHtml = (ev.options || [])
      .map(
        (opt, i) => `
      <label class="askuser-opt">
        <input class="askuser-opt-input" type="${inputType}" name="askuser-${qid}" value="${escapeHtml(opt.label)}"/>
        <span class="askuser-opt-label">${escapeHtml(opt.label)}</span>
        ${opt.description ? `<span class="askuser-opt-desc">${escapeHtml(opt.description)}</span>` : ""}
      </label>`,
      )
      .join("");
    const customHtml = ev.allowCustomInput
      ? `<div class="askuser-custom">
          <label class="askuser-opt">
            <input class="askuser-opt-input" type="${ev.multiSelect ? "checkbox" : "radio"}" name="askuser-${qid}" value="" data-custom-trigger="1"/>
            <span class="askuser-opt-label">其他:</span>
          </label>
          <input class="askuser-custom-input" type="text" placeholder="输入自定义答案..." maxlength="500"/>
        </div>`
      : "";

    const item = document.createElement("div");
    item.className = "chat-item chat-item-left";
    item.innerHTML = `
      <div class="avatar"><img src="${aiImage}" alt="AI"/></div>
      <div class="bubble">
        <div class="askuser-card" id="askuser-${qid}">
          <div class="askuser-head">
            ${ev.header ? `<span class="askuser-header-chip">${escapeHtml(ev.header)}</span>` : ""}
            <span class="askuser-countdown">⏳ <span class="askuser-countdown-num"></span></span>
            <span class="askuser-state"></span>
          </div>
          <div class="askuser-question">${escapeHtml(ev.question)}</div>
          ${ev.background ? `<div class="askuser-background">${escapeHtml(ev.background)}</div>` : ""}
          <div class="askuser-options">${optionsHtml}${customHtml}</div>
          <button class="askuser-submit">提交答案</button>
        </div>
      </div>`;
    ui.mainContent.appendChild(item);
    ui.scrollToBottom();

    const el = item.querySelector(".askuser-card");
    const submitBtn = el.querySelector(".askuser-submit");
    const countdownEl = el.querySelector(".askuser-countdown-num");
    submitBtn.addEventListener("click", () => submit(qid, ev));
    // 单选时点选项文字也可提交(减少一次点击);多选保留显式提交
    if (!ev.multiSelect) {
      el.querySelectorAll(".askuser-opt-input").forEach((n) =>
        n.addEventListener("change", () => {
          const custom = el.querySelector(".askuser-custom-input");
          if (n.dataset.customTrigger && custom) {
            custom.focus(); // "其他"选项:聚焦输入框,等用户填完点提交
            return;
          }
          if (custom) custom.value = "";
          submit(qid, ev);
        }),
      );
    }

    // 本地倒计时(spec D2:与后端 timeoutSeconds 同值;归零仅置灰前端,
    // 后端超时以工具返回文本为准 —— 竞态窗口内提交会收到 404 → "已失效")
    let remaining = Number(ev.timeoutSeconds) || 300;
    countdownEl.textContent = fmtRemaining(remaining);
    const timer = setInterval(() => {
      remaining -= 1;
      if (remaining <= 0) {
        freeze(qid, "已超时", false);
        return;
      }
      countdownEl.textContent = fmtRemaining(remaining);
    }, 1000);

    active.set(qid, { el, timer, submitBtn, countdownEl });
  }

  /** 流结束(complete/error/stop)时把仍在等待的卡片定格。 */
  function cancelAllActive(reasonText) {
    for (const qid of Array.from(active.keys())) {
      freeze(qid, reasonText, false);
    }
  }

  return { render, cancelAllActive };
})();

// ===================== §7 Chat Engine =====================
const chat = {
  async send() {
    const ta = document.getElementById("textarea");
    const text = ta.value.trim();
    if (!text && !state.isStreaming) {
      showToast("请输入消息内容", "error");
      return;
    }

    // Guard against the attach→send race: if any attachment is still
    // uploading (fileId not yet assigned), sending now would silently drop
    // it (fileIds would contain undefined) and the model would answer as if
    // no file was attached. Block until the upload finishes.
    if (state.pendingImages.some((img) => !img.fileId)) {
      showToast("文件还在上传中，请稍候再发送", "error");
      return;
    }

    if (!state.conversationId) {
      await conversation.createNew();
      if (!state.conversationId) return;
    }

    ui.renderUserMessage(text, state.pendingImages.slice());
    ui.disableSend();

    const id = Date.now();
    ui.renderBotMessage(id);

    const answerEl = document.getElementById(id);
    const originEl = document.getElementById("origin-" + id);
    const thinkingEl = document.getElementById("thinking-body-" + id);

    let answerText = "";
    let reasonText = "";

    const record = {
      message: text,
      conversationId: state.conversationId,
      mcps: state.selectedMcps,
      // M5:本地 tool group 勾选状态(空 = 信任角色授权全集)
      enabledToolGroups: state.selectedToolGroups.length > 0 ? state.selectedToolGroups : null,
      enabledKnowledgeIds:
        state.enabledKnowledgeIds.length > 0 ? state.enabledKnowledgeIds : null,
      fileIds:
        state.pendingImages.length > 0
          ? state.pendingImages.map((img) => img.fileId).filter(Boolean)
          : null,
      selectedSkillName: state.selectedSkill ? state.selectedSkill.name : null,
    };

    // Clear pending image after capturing fileId
    imageUpload.clear();

    // Create an AbortController so stopStream() can abort the frontend read.
    const controller = new AbortController();
    state.controller = controller;

    try {
      await api.streamChat(
        record,
        (data) => {
          // #1 AskUser:提问卡片事件帧(按字段分派,普通内容帧不受影响)
          if (data.askUser) {
            askUserCards.render(data.askUser);
          }
          // reasoning content
          if (data.reasoningContent) {
            const thinkingContainer = document.getElementById("thinking-" + id);
            if (thinkingContainer) thinkingContainer.style.display = "";
            reasonText += data.reasoningContent;
            if (thinkingEl) thinkingEl.innerHTML = renderMarkdown(reasonText);
          }
          // answer content
          if (data.content) {
            answerText += data.content;
            if (answerEl) answerEl.innerHTML = renderMarkdown(answerText);
            // origin-* is the "copy raw / download" payload; it is read
            // via textContent downstream, so write raw text rather than
            // parsing it as HTML. Keeps `<img onerror>` etc. inert.
            if (originEl) originEl.textContent = answerText;
          }
          ui.scrollToBottom();
        },
        () => {
          askUserCards.cancelAllActive("已结束");
          // complete
          const actionsEl = document.getElementById("actions-" + id);
          if (actionsEl) actionsEl.style.display = "";
          ui.enableSend();
          ui.setStopButtonVisible(false);
          conversation.loadList();
          selectedSkillTag.onSendSuccess();
          // Auto-rename from the first user message if the conversation still
          // carries its default placeholder title. Fire-and-forget; the title
          // update will refresh the sidebar once the PATCH lands.
          conversation.maybeAutoRename(state.conversationId, text);
        },
        (error) => {
          askUserCards.cancelAllActive("已取消");
          // error
          const actionsEl = document.getElementById("actions-" + id);
          if (actionsEl) actionsEl.style.display = "";
          if (answerEl)
            answerEl.innerHTML +=
              '<br/><span style="color:var(--error-color)">发送失败：' +
              escapeHtml(error.message || "未知错误") +
              "</span>";
          ui.enableSend();
          ui.setStopButtonVisible(false);
        },
        controller.signal,
      );
    } catch (error) {
      // AbortError = user-initiated stop, not a failure.
      if (!(error && error.name === "AbortError")) {
        if (answerEl)
          answerEl.innerHTML +=
            '<br/><span style="color:var(--error-color)">发送失败：' +
            escapeHtml(error.message || "未知错误") +
            "</span>";
      }
      ui.enableSend();
      ui.setStopButtonVisible(false);
    } finally {
      if (state.controller === controller) state.controller = null;
    }
  },

  /** 主动停止 AI 流：调 /stream/{convId}/stop */
  async stopStream() {
    if (!state.isStreaming || !state.conversationId) return;
    const convId = state.conversationId;
    const btn = document.getElementById("stop-btn");
    if (btn) {
      btn.disabled = true;
      btn.textContent = "停止中...";
    }
    try {
      const r = await fetch(
        `/spring/ai/loom/stream/${encodeURIComponent(convId)}/stop`,
        {
          method: "POST",
          credentials: "include",
        },
      );
      const data = await r.json().catch(() => ({}));
      if (data.stopped) {
        showToast("已停止生成", "success");
      } else {
        showToast("该会话没有活跃流", "warning");
      }
    } catch (e) {
      showToast("停止失败：" + e.message, "error");
    } finally {
      // Abort the frontend read after signalling the backend to stop.
      if (state.controller) {
        try {
          state.controller.abort();
        } catch (_) {}
      }
      askUserCards.cancelAllActive("已取消");
      ui.setStopButtonVisible(false);
    }
  },

  abortStream() {
    if (state.isStreaming) {
      if (state.controller) {
        try {
          state.controller.abort();
        } catch (_) {}
      }
      ui.enableSend();
    }
  },
};

// ===================== §8 Knowledge Space =====================
const knowledge = {
  currentKbId: null,
  _kbList: [],
  currentTab: "mine",

  openPanel() {
    ui.showModal("ks-modal-overlay");
    this.loadList();
  },

  closePanel() {
    ui.hideModal("ks-modal-overlay");
  },

  async loadList() {
    try {
      // 用 accessible（含 USER_CREATED + MARKET_PULLED + ROLE_GRANTED）让所有 Tab 都能看到
      const data = await api.listAccessibleKnowledge();
      this._kbList = data || [];
      this._renderCurrentTab();
    } catch (e) {
      console.warn("[knowledge.loadList] failed:", e);
      this._kbList = [];
      this._renderCurrentTab();
    }
  },

  _bindTabs() {
    if (this._tabsBound) return;
    document.querySelectorAll("#ks-modal-overlay .ks-tab").forEach((btn) => {
      btn.addEventListener("click", () => {
        this.currentTab = btn.getAttribute("data-tab");
        document.querySelectorAll("#ks-modal-overlay .ks-tab").forEach((b) => {
          const active = b.getAttribute("data-tab") === this.currentTab;
          b.classList.toggle("active", active);
          b.style.borderBottomColor = active
            ? "var(--primary-color)"
            : "transparent";
          b.style.color = active ? "var(--primary-color)" : "var(--text-muted)";
        });
        this._renderCurrentTab();
      });
    });
    this._tabsBound = true;
  },

  _renderCurrentTab() {
    this._bindTabs();
    const container = document.getElementById("ks-sidebar");
    const detail = document.getElementById("ks-detail");

    // 切 Tab 时重置右侧详情面板标题 + 内容，避免上一个 Tab 选中的 KB 名字残留在标题上
    const titleEl = document.getElementById("ks-detail-title");
    if (titleEl) titleEl.textContent = "知识库详情";
    if (detail) {
      const placeholderByTab = {
        market:
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">从左侧选一个市场知识库查看详情</div>',
        share:
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 14px; margin-bottom: 8px;">从左侧选一个自建知识库共享到市场</p><p style="font-size: 12px; color: var(--text-muted);">提交后进入审核，管理员审批通过后，<br>其他用户可在「市场」Tab 订阅。</p></div>',
        mypublish:
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个发布查看详情</div>',
        mine: null, // 「我的」Tab 用 select() 主动填；列表为空时 sidebar 已自带 empty
      };
      if (placeholderByTab[this.currentTab]) {
        detail.innerHTML = placeholderByTab[this.currentTab];
      }
    }

    if (this.currentTab === "mine") {
      this.renderList(this._kbList);
    } else if (this.currentTab === "market") {
      this._renderMarketTab(container, detail);
    } else if (this.currentTab === "share") {
      this._renderShareTab(container, detail);
    } else if (this.currentTab === "mypublish") {
      this._renderMyPublishTab(container, detail);
    }
  },

  renderList(list) {
    const container = document.getElementById("ks-sidebar");
    if (!list || list.length === 0) {
      container.innerHTML =
        '<div class="sidebar-empty" style="padding: 40px 16px;">暂无知识库</div>';
      return;
    }
    container.innerHTML = "";

    // 同名 KB 重复提示：统计每个 name 出现次数，给第 2+ 个加灰色 "(副本 N)" 后缀
    const nameCounts = {};
    for (const kb of list) nameCounts[kb.name] = (nameCounts[kb.name] || 0) + 1;
    const nameSeen = {};
    const dupSuffix = (kb) => {
      if (!nameCounts[kb.name] || nameCounts[kb.name] <= 1) return "";
      nameSeen[kb.name] = (nameSeen[kb.name] || 0) + 1;
      return nameSeen[kb.name] === 1
        ? ""
        : ` <span class="ks-item-dup-suffix">（副本 ${nameSeen[kb.name]}）</span>`;
    };
    const sourceLabel = (s) => {
      switch (s) {
        case "USER_CREATED":
          return { text: "自建", bg: "#dbeafe", color: "#1e40af" };
        case "MARKET_PULLED":
          return { text: "市场", bg: "#d1fae5", color: "#065f46" };
        case "ROLE_GRANTED":
          return { text: "角色授权", bg: "#fef3c7", color: "#92400e" };
        default:
          return null;
      }
    };

    for (const kb of list) {
      const id = kb.id;
      const name = kb.name;
      const isChecked = state.enabledKnowledgeIds.includes(id);
      const isCreator = kb.username === state.username;
      const lbl = sourceLabel(kb.source);
      const sourceBadge = lbl
        ? `<span class="ks-source-tag" style="background:${lbl.bg};color:${lbl.color};">${lbl.text}</span>`
        : "";
      const div = document.createElement("div");
      div.className = "ks-item" + (isChecked ? " active" : "");
      // 两行布局：行1 = [checkbox] [name+tag] [edit] [del]，行2 = [desc 占满]
      // 解决 279px 容器装不下 5 列的问题（之前 BUG #6b：徽章越界）
      div.innerHTML = `
 <input type="checkbox" ${isChecked ? "checked" : ""} style="width: 16px; height: 16px; cursor: pointer; flex-shrink: 0;">
 <div class="ks-item-main">
 <div class="ks-item-row1">
 <span class="ks-item-name">${escapeHtml(name)}${dupSuffix(kb)}${sourceBadge}</span>
 </div>
 <span class="ks-item-desc">${escapeHtml(kb.description || "")}</span>
 </div>
 ${isCreator ? '<button class="ks-item-edit" title="编辑">&#x270E;</button>' : ""}
 ${isCreator ? '<button class="ks-item-delete">&times;</button>' : ""}`;
      const checkbox = div.querySelector('input[type="checkbox"]');
      checkbox.addEventListener("change", () => {
        this.toggleKnowledgeForChat(id);
      });
      // 名称/描述点击：只显示右侧详情面板，不改变启用状态
      div.querySelector(".ks-item-name").addEventListener("click", (e) => {
        e.stopPropagation();
        this.select(id, name);
      });
      div.querySelector(".ks-item-desc").addEventListener("click", (e) => {
        e.stopPropagation();
        this.select(id, name);
      });
      if (isCreator) {
        div.querySelector(".ks-item-edit").addEventListener("click", (e) => {
          e.stopPropagation();
          this.edit(id);
        });
      }
      if (isCreator) {
        div.querySelector(".ks-item-delete").addEventListener("click", (e) => {
          e.stopPropagation();
          this.delete(id);
        });
      }
      container.appendChild(div);
    }
  },

  /** Toggle a knowledge base for chat (multi-select / checkbox behavior) */
  toggleKnowledgeForChat(id) {
    const index = state.enabledKnowledgeIds.indexOf(id);
    if (index > -1) {
      state.enabledKnowledgeIds.splice(index, 1);
    } else {
      state.enabledKnowledgeIds.push(id);
    }
    // Update active class on sidebar items directly (no full re-render)
    const items = document.querySelectorAll("#ks-sidebar .ks-item");
    items.forEach((item) => {
      const checkbox = item.querySelector('input[type="checkbox"]');
      if (checkbox) {
        item.classList.toggle("active", checkbox.checked);
      }
    });
  },

  async create() {
    const name = await dialog.prompt({
      title: "创建知识库",
      message: "请输入知识库名称：",
      placeholder: "例如：产品手册",
      okText: "下一步",
      defaultValue: "",
    });
    if (!name) return;

    const description = await dialog.prompt({
      title: "创建知识库",
      message: "请输入知识库内容摘要（LLM 据此决定是否检索本库）：",
      placeholder: "例如：本知识库收录产品保修条款、故障排查流程、退换货政策",
      okText: "创建",
      defaultValue: "",
    });
    if (!description) {
      showToast("知识库描述不能为空", "warning");
      return;
    }

    const data = await api.createKnowledge(name, description);
    if (data) {
      showToast("知识库创建成功", "success");
      this.loadList();
    } else {
      showToast("创建失败", "error");
    }
  },

  async delete(id) {
    const ok = await dialog.confirm({
      title: "删除知识库",
      message: "确定要删除这个知识库吗？关联文件将被一并移除。此操作不可撤销。",
      okText: "删除",
      danger: true,
    });
    if (!ok) return;
    const deleted = await api.deleteKnowledge(id);
    if (deleted) {
      if (this.currentKbId === id) {
        this.currentKbId = null;
        document.getElementById("ks-detail").innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个知识库查看文件</div>';
      }
      // BUG-KB-DELETE-ACTIVE: also clear the chat-bound KB if it was enabled,
      // so the next message doesn't RAG-query a deleted KB.
      const idx = state.enabledKnowledgeIds.indexOf(id);
      if (idx > -1) {
        state.enabledKnowledgeIds.splice(idx, 1);
      }
      this.loadList();
      showToast("知识库已删除", "success");
    } else {
      showToast("删除失败", "error");
    }
  },

  async edit(id) {
    const kb = this._kbList.find((k) => k.id === id);
    if (!kb) return;

    const name = await dialog.prompt({
      title: "编辑知识库",
      message: "修改知识库名称：",
      placeholder: "请输入新名称",
      okText: "下一步",
      defaultValue: kb.name,
    });
    if (!name) return;

    const description = await dialog.prompt({
      title: "编辑知识库",
      message: "修改知识库描述：",
      placeholder: "请输入新描述",
      okText: "保存",
      defaultValue: kb.description || "",
    });
    if (!description) {
      showToast("描述不能为空", "warning");
      return;
    }

    const data = await api.updateKnowledge(id, name, description);
    if (data.ok) {
      showToast("修改成功", "success");
      // Refresh list and re-select if this KB was open in detail panel
      await this.loadList();
      if (this.currentKbId === id) {
        this.select(id, name);
      }
    } else {
      showToast(data.message || "修改失败", "error");
    }
  },

  async select(id, name) {
    this.currentKbId = id;

    // show detail
    const detail = document.getElementById("ks-detail");
    detail.innerHTML = `
 <div class="ks-detail-header">
 <span class="ks-detail-title">${name}</span>
 <div>
 <button class="ks-edit-btn" id="ks-edit-btn">✎ 编辑</button>
 <button class="ks-upload-btn" id="ks-upload-btn">+ 上传文件</button>
 <input type="file" id="ks-file-input" style="display:none;">
 </div>
 </div>
 <div class="ks-file-list"><div class="loading-indicator">加载中...</div></div>`;

    const uploadBtn = detail.querySelector("#ks-upload-btn");
    const fileInput = detail.querySelector("#ks-file-input");
    const editBtn = detail.querySelector("#ks-edit-btn");
    uploadBtn.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", (e) => this.uploadFile(id, e));
    editBtn.addEventListener("click", () => this.edit(id));

    this.loadFiles(id);
  },

  async loadFiles(kbId) {
    const container = document
      .getElementById("ks-detail")
      .querySelector(".ks-file-list");
    try {
      const files = await api.listKnowledgeFiles(kbId);
      if (!files || files.length === 0) {
        container.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">暂无文件</div>';
        return;
      }
      container.innerHTML = `
 <table class="knowledge-table">
 <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
 <tbody id="ks-file-tbody"></tbody>
 </table>`;
      const tbody = document.getElementById("ks-file-tbody");
      for (const f of files) {
        const row = document.createElement("tr");
        row.innerHTML = `
 <td>${truncateText(f.fileName || f.name || "", 30)}</td>
 <td>${formatFileSize(f.size || 0)}</td>
 <td>${formatDate(f.uploadTime || f.createTime)}</td>
 <td>
 <button class="action-btn action-btn-preview" data-file-preview="${f.id}">预览</button>
 <button class="action-btn action-btn-download" data-file-download="${f.id}">下载</button>
 <button class="action-btn action-btn-delete" data-file-id="${f.id}">删除</button>
 </td>`;
        row
          .querySelector("[data-file-preview]")
          .addEventListener("click", () => window.previewFile(f.id));
        row
          .querySelector("[data-file-download]")
          .addEventListener("click", () => window.downloadFile(f.id));
        row
          .querySelector("[data-file-id]")
          .addEventListener("click", () => this.deleteFile(kbId, f.id, row));
        tbody.appendChild(row);
      }
    } catch (e) {
      container.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败</div>';
    }
  },

  async uploadFile(kbId, event) {
    const file = event.target.files[0];
    if (!file) return;
    try {
      const data = await api.uploadToKnowledge(kbId, file);
      if (data) {
        showToast(`文件 "${file.name}" 上传成功`, "success");
        this.loadFiles(kbId);
      }
    } catch (e) {
      showToast("上传失败", "error");
    }
    event.target.value = "";
  },

  async deleteFile(kbId, fileId, row) {
    if (!confirm("确定要删除这个文件吗？")) return;
    try {
      const ok = await api.deleteKnowledgeFile(kbId, fileId);
      if (ok) {
        row.remove();
        showToast("文件已删除", "success");
      }
    } catch (e) {
      showToast("删除失败", "error");
    }
  },

  async shareToMarket(id) {
    const kb = this._kbList.find((k) => k.id === id);
    if (!kb) return;
    const ok = await dialog.confirm({
      title: "共享到知识库市场",
      // M3+ T4.3 — i18n key extraction; resolution happens at render time
      // via window.I18N.t (loaded by i18n/i18n.js). Falls back to the key
      // string itself if dict not yet loaded or key missing.
      message: `确认将「${kb.name}」提交到市场？审批通过后其他用户可浏览并添加到自己的知识库。`,
      okText: "共享",
    });
    if (!ok) return;
    try {
      await api.submitToMarket(id);
      showToast(`已提交「${kb.name}」，等待管理员审批`, "success");
      this.loadList();
    } catch (e) {
      showToast("共享失败：" + e.message, "error");
    }
  },

  _kbTagFilter: null,
  // M4 T2: KB 市场「加载更多」分页状态（默认分支 v2 Page total 驱动；tag 分支裸数组 cursor 驱动）
  _kbMarketItems: [],
  _kbMarketTotal: 0,
  _kbMarketPage: 0,
  _kbMarketHasMore: false,
  _kbMarketLoading: false,
  _kbMarketSeq: 0,
  // M4 T7: KB 市场服务端关键词搜索（镜像技能 tab T2 模式）+ 排序状态。
  // query/sortBy 仅分页分支下发；tag 分支服务端忽略两者（D11），tag 激活时
  // 搜索 input 事件被忽略、排序 select 被 disable。
  _kbMarketQuery: "",
  _kbMarketDebounce: null,
  _kbSort: "official_rank",
  // 搜索触发的重渲染后把焦点还给 #kb-market-search（bar 全量重建会丢焦点）
  _kbSearchFocus: false,
  _renderTagChipsHtml(tags, opts) {
    // Render a list of tag strings as `.tag-chip` spans. opts.onChipClick
    // (when present) wires each chip as a filter button; otherwise they're
    // static display chips. `emptyText` is shown when tags is empty.
    const list = Array.isArray(tags) ? tags.filter(Boolean) : [];
    if (list.length === 0) {
      if (opts && opts.emptyText) {
        return '<span class="kb-tag-filter-empty">' + escapeHtml(opts.emptyText) + "</span>";
      }
      return "";
    }
    const klass = opts && opts.onChipClick ? "tag-chip tag-chip-filter" : "tag-chip";
    const extraAttr = opts && opts.activeTag
      ? ' data-tag="' + escapeHtml(opts.activeTag) + '"'
      : "";
    return list
      .map(
        (t) =>
          '<span class="' +
          klass +
          '"' +
          extraAttr +
          ' data-tag="' +
          escapeHtml(String(t)) +
          '">' +
          escapeHtml(String(t)) +
          "</span>",
      )
      .join("");
  },
  async _renderMarketTab(container, detail, tagFilter) {
    // 两段式（点列表项 → 详情面板 + send-skill-btn 风格按钮），跟技能库市场 Tab 风格一致
    // M2 T21: tagFilter (string|null) — 当非空时走公开 /market-knowledge?tag=...
    // 端点(返回裸 list 形态);为空时走 v2 /market-knowledge Page 分页接口。
    // M4 T2: 默认分支已切 v2（0-based，Page{items,total}，size=20，total 驱动 load-more）；
    // tag 分支保持裸数组契约（size=50，cursor 式：本页返回条数 < size 即停）。
    this._kbTagFilter = tagFilter || null;
    this._kbMarketItems = [];
    this._kbMarketTotal = 0;
    this._kbMarketPage = 0;
    this._kbMarketHasMore = false;
    // M4 T7: 重进 tab（含 tag 切换）时清 query + 取消悬挂 debounce（镜像技能 tab
    // Fix round 1 —— 否则旧 input 的 300ms 定时器晚触发会 bump seq 把本次合法
    // 初始 fetch 判 stale，tab 卡「加载中...」）。排序 select 值跨重渲染保留。
    this._kbMarketQuery = "";
    if (this._kbMarketDebounce) {
      clearTimeout(this._kbMarketDebounce);
      this._kbMarketDebounce = null;
    }
    this._kbSearchFocus = false; // 全量重进 tab — 不把焦点强制交给搜索框
    // Fix round 1: 每次重置 bump seq —— 让旧 tag/旧渲染的 in-flight load-more
    // 响应（可能晚到）在 _fetchKbMarketPage 里被识别为 stale 并丢弃，防止
    // 旧响应 concat 进新列表造成混行。
    this._kbMarketSeq++;
    container.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);">加载中...</div>';
    detail.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个市场知识库查看详情</div>';
    try {
      const res = await this._fetchKbMarketPage(0, false);
      if (res && res.stale) return; // 已被更新的渲染取代 — 不碰 container
      this._renderKbMarketList(container, detail);
    } catch (e) {
      container.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' +
        escapeHtml(e.message) +
        "</div>";
    }
  },

  // M4 T2: 拉取 KB 市场一页并累积到 _kbMarketItems。默认分支 v2 Page（total 驱动）；
  // tag 分支裸数组（返回条数 < size 即没有更多）。
  // Fix round 1: sequence token 防 stale response（与技能 tab 同款）—— fetch 开始时
  // 捕获 token，await 之后 token 过期则丢弃响应/错误，返回 {stale:true}。
  async _fetchKbMarketPage(page, append) {
    const seq = ++this._kbMarketSeq;
    const tagFilter = this._kbTagFilter;
    let items;
    let total = null;
    try {
      if (tagFilter) {
        const size = 50;
        const url =
          "/spring/ai/loom/market-knowledge?tag=" +
          encodeURIComponent(tagFilter) +
          "&page=" +
          page +
          "&size=" +
          size;
        const r = await apiFetch(url);
        if (!r.ok) throw new Error("HTTP " + r.status);
        items = (await r.json()) || [];
        if (seq !== this._kbMarketSeq) return { stale: true };
        this._kbMarketHasMore = items.length >= size;
      } else {
        const size = 20;
        // M4 T7: 分页分支下发 query + sortBy（tag 分支服务端忽略两者，故不传）。
        const data = await api.listMarketKnowledge(
          page,
          size,
          this._kbMarketQuery,
          this._kbSort,
        );
        items = (data && (data.items || data.content)) || data || [];
        total = data && typeof data.total === "number" ? data.total : null;
        if (seq !== this._kbMarketSeq) return { stale: true };
        this._kbMarketHasMore =
          items.length >= size &&
          (total == null || (append ? this._kbMarketItems.length : 0) + items.length < total);
      }
    } catch (e) {
      if (seq !== this._kbMarketSeq) return { stale: true }; // stale error — discard
      throw e;
    }
    if (seq !== this._kbMarketSeq) return { stale: true };
    this._kbMarketItems = append ? this._kbMarketItems.concat(items) : items;
    if (total != null) this._kbMarketTotal = total;
    this._kbMarketPage = page;
    return { stale: false };
  },

  // M4 T2: 渲染 KB 市场列表（tag 过滤栏 + 全部已加载行 + load-more 按钮）。
  // load-more 后全量重渲染 —— 客户端 sort 作用于累积列表，增量 append 会与
  // sort 顺序冲突（新行可能排到已渲染行之前），故不做 cursor 式局部追加。
  _renderKbMarketList(container, detail) {
    const tagFilter = this._kbTagFilter;
    const t = (key, fallback) =>
      (window.I18N && window.I18N.t
        ? window.I18N.t(key, fallback)
        : fallback) || fallback;
    // M0 T14: 官方优先 → featured_rank 降序 → 提交时间降序（同 Skills 市场 Tab 的语义）。
    // M4 T7: 默认（分页）分支服务端已按 sortBy 排序（official_rank 默认语义 == 本
    // 比较器；submitted_at / rating 只有服务端能排）—— 客户端再排会覆盖服务端顺序，
    // 故仅 tag 分支（裸数组，服务端固定 rank 序、忽略 sortBy）保留客户端兜底排序。
    const rawItems = this._kbMarketItems || [];
    const items = tagFilter
      ? [...rawItems].sort((a, b) => {
          const ao = a && a.isOfficial ? 1 : 0;
          const bo = b && b.isOfficial ? 1 : 0;
          if (ao !== bo) return bo - ao;
          const ar = a && a.featuredRank != null ? Number(a.featuredRank) : 0;
          const br = b && b.featuredRank != null ? Number(b.featuredRank) : 0;
          if (ar !== br) return br - ar;
          const ad = a && a.submittedAt ? new Date(a.submittedAt).getTime() : 0;
          const bd = b && b.submittedAt ? new Date(b.submittedAt).getTime() : 0;
          return bd - ad;
        })
      : rawItems;
    // M3+ T2.2: listWithTags helper removed — tags (when backend embeds via
    // T2.1 follow-up batch SELECT) are read directly from row.tags.
    if (!items || items.length === 0) {
      // M4 T7: 空态三分支（镜像技能 tab）：tag → 「没有匹配 tag「x」的知识库」；
      // query → 「没有匹配「kw」的知识库」；无 → 市场暂无知识库。空态文本不高亮。
      const empty = tagFilter
        ? '<div style="padding: 40px; text-align: center; color: var(--text-muted);">没有匹配 tag「' +
          escapeHtml(tagFilter) +
          "」的知识库</div>"
        : this._kbMarketQuery
          ? '<div style="padding: 40px; text-align: center; color: var(--text-muted);">没有匹配「' +
            escapeHtml(this._kbMarketQuery) +
            "」的知识库</div>"
          : '<div style="padding: 40px; text-align: center; color: var(--text-muted);">市场暂无知识库</div>';
      container.innerHTML = this._renderKbTagFilterBar([], tagFilter) + empty;
      this._bindKbTagFilterBar(container, detail);
      return;
    }
    // M3+ T2.2: listWithAnnouncements helper removed — announcement data
    // comes pre-embedded on each row (row.announcementTitle / row.announcementBody
    // via T2.1 LEFT JOIN market_content_announcement).
    // M2 T21: 客户端聚合当前已加载行所有 unique tag,渲染成可点击的过滤 chip
    const aggregatedTags = [];
    const seen = new Set();
    for (const kb of items) {
      const tags = kb && Array.isArray(kb.tags) ? kb.tags : [];
      for (const tg of tags) {
        const s = String(tg);
        if (s && !seen.has(s)) {
          seen.add(s);
          aggregatedTags.push(s);
        }
      }
    }
    aggregatedTags.sort();
    container.innerHTML = "";
    container.insertAdjacentHTML(
      "beforeend",
      this._renderKbTagFilterBar(aggregatedTags, tagFilter),
    );
    this._bindKbTagFilterBar(container, detail);
    const rowsWrap = document.createElement("div");
    rowsWrap.className = "kb-market-rows";
    container.appendChild(rowsWrap);
    // T19 fix-up 2: per-row announcement banner — 当 row.announcementTitle 存在时,
    // 渲染 banner 紧贴在 row 上方。点击 banner 选中该 row。
    for (const kb of items) {
      if (kb && kb.announcementTitle) {
        const annView = {
          title: kb.announcementTitle,
          body: kb.announcementBody || "",
        };
        const banner = document.createElement("div");
        banner.className = "market-announcement market-announcement-list";
        banner.innerHTML =
          window.MarketAdmin && window.MarketAdmin.announcementHtml
            ? window.MarketAdmin.announcementHtml(annView)
            : `<div class="market-announcement-title">📢 ${escapeHtml(
                annView.title,
              )}</div><div class="market-announcement-body">${escapeHtml(
                annView.body,
              )}</div>`;
        banner.style.cursor = "pointer";
        banner.addEventListener("click", () => {
          const rowEl = banner.nextElementSibling;
          if (rowEl && rowEl.classList.contains("ks-item")) rowEl.click();
        });
        rowsWrap.appendChild(banner);
      }
      const div = document.createElement("div");
      div.className = "ks-item";
      const officialBadge = kb && kb.isOfficial
        ? ' <span class="ks-source-tag" title="官方推荐" style="background:#fef3c7;color:#92400e;">🏛️</span>'
        : "";
      const tagChips = (kb && Array.isArray(kb.tags) && kb.tags.length > 0)
        ? '<div class="kb-tag-row">' +
          this._renderTagChipsHtml(kb.tags) +
          "</div>"
        : "";
      // M4 T7: name/description/username 走 highlightHtml（当前 query 命中包 <mark>；
      // tag 分支 query 恒为 ""，highlightHtml 退化为 escapeHtml）。详情/公告/chips 不高亮。
      div.innerHTML = `
 <div class="ks-item-main">
 <div class="ks-item-row1">
 <span class="ks-item-name">${highlightHtml(kb.name, this._kbMarketQuery)}${officialBadge} <span class="ks-source-tag" style="background:#ede9fe;color:#6b21a8;">市</span></span>
 </div>
 <span class="ks-item-desc">by ${highlightHtml(kb.username || kb.author || "", this._kbMarketQuery)} · ${highlightHtml(kb.description || "", this._kbMarketQuery)}</span>
 ${tagChips}
 </div>
 `;
      div.addEventListener("click", () =>
        this._showMarketKbDetail(kb, div, detail),
      );
      rowsWrap.appendChild(div);
    }
    // M4 T2: load-more 按钮 — 没有更多时直接移除（ruling: removal，不留 end-state）
    const oldBtn = container.querySelector(".load-more-btn");
    if (oldBtn) oldBtn.remove();
    if (this._kbMarketHasMore) {
      const btn = document.createElement("button");
      btn.className = "secondary-btn load-more-btn";
      btn.textContent = t("market.load.more", "加载更多");
      btn.addEventListener("click", async () => {
        if (this._kbMarketLoading) return;
        this._kbMarketLoading = true;
        btn.disabled = true;
        try {
          const res = await this._fetchKbMarketPage(this._kbMarketPage + 1, true);
          // Fix round 1: stale 响应（用户已切 tag / 重进 tab）→ 不重渲染旧 container
          if (res && res.stale) return;
          this._renderKbMarketList(container, detail);
        } catch (e) {
          showToast("加载失败：" + e.message, "error");
          btn.disabled = false;
        } finally {
          this._kbMarketLoading = false;
        }
      });
      container.appendChild(btn);
    }
  },

  _renderKbTagFilterBar(tags, activeTag) {
    // M2 T21: 渲染 KB 市场 Tag 过滤栏 — 始终展示 "全部" chip + 当前页聚合的 tag chips
    // (可点击过滤) + 自由文本输入 + "应用筛选" 按钮。"全部" 是 active 当 activeTag 为空。
    const tagList = Array.isArray(tags) ? tags : [];
    const active = activeTag || null;
    const allActive = active == null || active === "";
    const chipsHtml = tagList
      .map((t) => {
        const isActive = active === t;
        const cls =
          "tag-chip tag-chip-filter" + (isActive ? " active" : "");
        return (
          '<span class="' +
          cls +
          '" data-tag="' +
          escapeHtml(t) +
          '">' +
          escapeHtml(t) +
          "</span>"
        );
      })
      .join("");
    const allChipCls =
      "tag-chip tag-chip-filter" + (allActive ? " active" : "");
    // M4 T7: 关键词搜索框（镜像技能 tab #skill-market-search）+ 排序 select（复用
    // skills._renderSortSelectHtml）。搜索框 value 从 _kbMarketQuery 恢复（每次
    // load-more/搜索都会全量重渲染 bar，靠 value 保留已输入文本）。tag 过滤激活时
    // 排序 select disable（tag 分支忽略 sortBy，D11）。
    return (
      '<div class="kb-tag-filter-bar">' +
      '<span class="kb-tag-filter-bar-label">搜索：</span>' +
      '<div class="kb-tag-filter-input-row">' +
      '<input type="text" id="kb-market-search" class="kb-tag-filter-input" ' +
      'placeholder="搜索知识库（名称 / 描述 / 作者）" value="' +
      escapeHtml(this._kbMarketQuery || "") +
      '"/>' +
      "</div>" +
      '<span class="kb-tag-filter-bar-label">排序：</span>' +
      skills._renderSortSelectHtml(
        "kb-market-sort",
        this._kbSort,
        !!active,
      ) +
      '<span class="kb-tag-filter-bar-label">标签筛选：</span>' +
      '<span class="' +
      allChipCls +
      '" data-tag="">全部</span>' +
      '<div class="tag-chip-group">' +
      chipsHtml +
      "</div>" +
      '<div class="kb-tag-filter-input-row">' +
      '<input type="text" id="kb-tag-filter-input" class="kb-tag-filter-input" placeholder="输入标签（如 RAG / FAQ）然后点应用筛选" value="' +
      escapeHtml(active || "") +
      '"/>' +
      '<button class="primary-btn" id="kb-tag-filter-apply" style="padding:5px 14px;font-size:12px;">应用筛选</button>' +
      "</div>" +
      "</div>"
    );
  },

  _bindKbTagFilterBar(container, detail) {
    // 绑定 chip 点击和「应用筛选」按钮 → 重新拉取过滤后列表
    const self = this;
    container.querySelectorAll(".kb-tag-filter-bar .tag-chip-filter").forEach(
      (chip) => {
        chip.addEventListener("click", () => {
          const tag = chip.getAttribute("data-tag") || "";
          // 已经 active 时再点一下 = 清除过滤
          if (
            chip.classList.contains("active") &&
            tag !== ""
          ) {
            self._renderMarketTab(container, detail, null);
            return;
          }
          self._renderMarketTab(container, detail, tag || null);
        });
      },
    );
    const applyBtn = container.querySelector("#kb-tag-filter-apply");
    const inputEl = container.querySelector("#kb-tag-filter-input");
    if (applyBtn && inputEl) {
      const trigger = () => {
        const v = inputEl.value.trim();
        self._renderMarketTab(container, detail, v || null);
      };
      applyBtn.addEventListener("click", trigger);
      inputEl.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          trigger();
        }
      });
    }
    // M4 T7: 关键词搜索（镜像技能 tab：300ms debounce + Enter 立即 + seq guard）。
    // tag 过滤激活时忽略 keyword（tag 分支不带 query，D11）—— 与技能 tab runSearch 同语义。
    const searchInput = container.querySelector("#kb-market-search");
    const runKbSearch = async () => {
      if (self._kbTagFilter) return;
      if (self._kbMarketDebounce) {
        clearTimeout(self._kbMarketDebounce);
        self._kbMarketDebounce = null;
      }
      self._kbMarketQuery = searchInput.value.trim();
      self._kbSearchFocus = true; // 搜索触发的重渲染 → 渲染后把焦点还给搜索框
      self._kbMarketPage = 0;
      self._kbMarketItems = [];
      self._kbMarketTotal = 0;
      self._kbMarketHasMore = false;
      // 新搜索开始 → 旧 load-more 按钮立即失效（seq guard 兜底 stale response）
      const staleBtn = container.querySelector(".load-more-btn");
      if (staleBtn) staleBtn.remove();
      try {
        const res = await self._fetchKbMarketPage(0, false); // 内部 ++seq 作 in-flight guard
        if (res && res.stale) return;
        self._renderKbMarketList(container, detail);
      } catch (e) {
        // page-0 失败 → 错误态（镜像 _renderMarketTab catch；stale 错误已被 fetch 吞掉）
        self._kbSearchFocus = false;
        container.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' +
          escapeHtml(e.message) +
          "</div>";
      }
    };
    if (searchInput) {
      searchInput.addEventListener("input", () => {
        self._kbSearchFocus = true;
        if (self._kbMarketDebounce) clearTimeout(self._kbMarketDebounce);
        self._kbMarketDebounce = setTimeout(runKbSearch, 300);
      });
      searchInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          runKbSearch();
        }
      });
      searchInput.addEventListener("blur", () => {
        self._kbSearchFocus = false;
      });
    }
    // M4 T7: 排序 change → 重置 page/items + bump seq（经 _fetchKbMarketPage）重新拉取。
    // tag 过滤激活时 select 已 disabled（change 不触发）。
    const sortSel = container.querySelector("#kb-market-sort");
    if (sortSel) {
      sortSel.addEventListener("change", async () => {
        self._kbSearchFocus = false; // 排序触发不应抢焦点到搜索框
        self._kbSort = sortSel.value || "official_rank";
        if (self._kbMarketDebounce) {
          clearTimeout(self._kbMarketDebounce);
          self._kbMarketDebounce = null;
        }
        self._kbMarketPage = 0;
        self._kbMarketItems = [];
        self._kbMarketTotal = 0;
        self._kbMarketHasMore = false;
        const staleBtn = container.querySelector(".load-more-btn");
        if (staleBtn) staleBtn.remove();
        try {
          const res = await self._fetchKbMarketPage(0, false);
          if (res && res.stale) return;
          self._renderKbMarketList(container, detail);
        } catch (e) {
          // page-0 失败 → 错误态（镜像 _renderMarketTab catch）
          container.innerHTML =
            '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' +
            escapeHtml(e.message) +
            "</div>";
        }
      });
    }
    // M4 T7: 全量重渲染会销毁并重建搜索框 —— 若本次渲染由搜索触发（_kbSearchFocus），
    // 把焦点 + 光标（置于文末）还给新搜索框，避免 debounce 中途丢焦点。load-more /
    // tag 点击 / 排序不置该 flag，故不会抢焦点。
    if (self._kbSearchFocus && searchInput) {
      searchInput.focus();
      const len = searchInput.value.length;
      try {
        searchInput.setSelectionRange(len, len);
      } catch (_) {
        /* ignore — non-text input */
      }
    }
  },

  _showMarketKbDetail(marketKb, element, detail) {
    // 详情面板（与技能库 _selectMarketSkill 一致的样式 + 交互）
    document
      .querySelectorAll("#ks-sidebar .ks-item")
      .forEach((i) => i.classList.remove("selected"));
    element.classList.add("selected");
    // M2 T21: 优先用 listWithTags 阶段已装饰的 row.tags,否则实时拉一次
    const preloadedTags =
      marketKb && Array.isArray(marketKb.tags) ? marketKb.tags : null;
    detail.innerHTML = `
 <div id="market-announcement-slot"></div>
 <div class="detail-section">
 <div class="detail-section-title">市场元数据</div>
 <div class="detail-section-content" style="line-height: 1.8; color: var(--text-primary); font-size: 13px;">
 <div>作者：${escapeHtml(marketKb.username || marketKb.author || "-")}</div>
 <div>状态：<span class="type-badge ADMIN">${escapeHtml(marketKb.status || "APPROVED")}</span></div>
 <div>上架时间：${marketKb.reviewedAt ? new Date(marketKb.reviewedAt).toLocaleString() : marketKb.submittedAt ? new Date(marketKb.submittedAt).toLocaleString() : "-"}</div>
 <div id="kb-detail-tags-row" style="margin-top:6px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;"><span style="color:var(--text-muted);">标签：</span><span id="kb-detail-tags-slot"></span></div>
 </div>
 </div>
 <div class="detail-section">
 <div class="detail-section-title">描述</div>
 <div class="detail-section-content">${escapeHtml(marketKb.description || "无")}</div>
 </div>
 <div class="detail-section">
 <div class="detail-section-title">评分与评论</div>
 <div id="market-reviews-slot" class="market-reviews-slot"></div>
 </div>
 <div style="margin-top: 24px; display: flex; gap: 12px;">
 <button class="send-skill-btn" id="pull-kb-confirm-btn" style="flex: 1;">添加到我的知识库</button>
 </div>
 `;
    detail
      .querySelector("#pull-kb-confirm-btn")
      .addEventListener("click", () => this._pullMarketKnowledge(marketKb.id));

    // T19: load announcement + review list + submission form.
    const annSlot = detail.querySelector("#market-announcement-slot");
    const reviewSlot = detail.querySelector("#market-reviews-slot");
    // M2 T21: tag slot — 优先用 listWithTags 已装饰的 row.tags,
    // 否则实时 fetch。失败时显示「—」静默。
    const tagSlot = detail.querySelector("#kb-detail-tags-slot");
    const renderTags = (tags) => {
      if (!tagSlot) return;
      if (Array.isArray(tags) && tags.length > 0) {
        tagSlot.outerHTML =
          '<span id="kb-detail-tags-slot" class="tag-chip-group">' +
          this._renderTagChipsHtml(tags) +
          "</span>";
      } else {
        tagSlot.outerHTML =
          '<span id="kb-detail-tags-slot" style="color:var(--text-muted);font-size:12px;">无</span>';
      }
    };
    if (preloadedTags) {
      renderTags(preloadedTags);
    } else if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.getMarketTags === "function"
    ) {
      window.MarketAdmin
        .getMarketTags("KNOWLEDGE", marketKb.id)
        .then(renderTags)
        .catch(() => {
          if (tagSlot) tagSlot.textContent = "无";
        });
    } else if (tagSlot) {
      tagSlot.textContent = "—";
    }
    if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.getAnnouncement === "function"
    ) {
      window.MarketAdmin
        .getAnnouncement("KNOWLEDGE", marketKb.id)
        .then((ann) => {
          if (ann && annSlot) annSlot.innerHTML = window.MarketAdmin.announcementHtml(ann);
        })
        .catch(() => {});
    }
    if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.reviewList === "function"
    ) {
      _renderReviewSection("KNOWLEDGE", marketKb.id, reviewSlot);
    } else {
      reviewSlot.innerHTML =
        '<div style="color: var(--text-muted); font-size: 12px;">评分功能暂不可用</div>';
    }
  },

  async _pullMarketKnowledge(id) {
    try {
      await api.pullMarketKnowledge(id);
      showToast("已添加到我的知识库", "success");
      await this.loadList();
      this._renderCurrentTab();
    } catch (e) {
      showToast("添加失败：" + e.message, "error");
    }
  },

  async _renderShareTab(container, detail) {
    // 两段式（点列表项 → 详情面板打开共享表单）
    detail.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 14px; margin-bottom: 8px;">从左侧选一个自建知识库共享到市场</p><p style="font-size: 12px; color: var(--text-muted);">提交后进入审核，管理员审批通过后，<br>其他用户可在「市场」Tab 订阅。</p></div>';
    const ownKbs = (this._kbList || []).filter(
      (kb) => kb.username === state.username,
    );
    container.innerHTML = "";
    if (ownKbs.length === 0) {
      container.innerHTML =
        '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">还没有自建的知识库<br>切到「我的」→ 创建一个再来共享</div>';
      return;
    }
    for (const kb of ownKbs) {
      const div = document.createElement("div");
      div.className = "ks-item";
      div.innerHTML = `
 <div class="ks-item-main">
 <div class="ks-item-row1">
 <span class="ks-item-name">${escapeHtml(kb.name)} <span class="ks-source-tag" style="background:#dbeafe;color:#1e40af;">自建</span></span>
 </div>
 <span class="ks-item-desc">${escapeHtml(kb.description || "")}</span>
 </div>
 `;
      div.addEventListener("click", () => this._showShareForm(kb, detail));
      container.appendChild(div);
    }
  },

  _showShareForm(kb, detail) {
    detail.innerHTML = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
 共享「<strong>${escapeHtml(kb.name)}</strong>」到市场。审批通过后其他用户可在「市场」Tab 订阅。<br>
 你的本地实例保持不变，共享不影响你的使用。
 </div>
 <div style="font-size: 12px; color: var(--text-muted);">
 提交后状态为 PENDING，等待管理员审批。同名+同作者会更新原投稿。
 </div>
 <div style="display: flex; gap: 12px;">
 <button class="send-skill-btn" id="share-kb-confirm-btn" style="flex: 1;">共享到市场</button>
 </div>
 </div>
 `;
    detail
      .querySelector("#share-kb-confirm-btn")
      .addEventListener("click", () => this._handleShareSubmit(kb));
  },

  async _handleShareSubmit(kb) {
    try {
      await api.submitToMarket(kb.id);
      showToast(`已提交「${kb.name}」，等待管理员审批`, "success");
      this.loadList();
    } catch (e) {
      showToast("共享失败：" + e.message, "error");
    }
  },
  async _renderMyPublishTab(container, detail) {
    // -2: 列表显示名称 + 描述 + 审批状态徽章，详情面板显示完整信息 + 撤回按钮
    // （审批流已上线：PENDING/APPROVED/REJECTED，与 Skill 侧「我的发布」一致）
    detail.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个发布查看详情</div>';
    try {
      const items = await api.listMySubmittedKnowledge();
      if (!items || items.length === 0) {
        container.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">还没有共享到市场的知识库<br><br>切到「共享」Tab 从自建知识库发起共享</div>';
        return;
      }
      container.innerHTML = "";
      for (const kb of items) {
        const div = document.createElement("div");
        div.className = "ks-item";
        const st = skills._statusLabel(kb.status);
        div.innerHTML = `
 <div class="ks-item-main">
 <div class="ks-item-row1">
 <span class="ks-item-name">${escapeHtml(kb.name)} <span class="ks-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></span>
 </div>
 <span class="ks-item-desc">${escapeHtml(kb.description || "")}</span>
 </div>
 `;
        div.addEventListener("click", () =>
          this._showMyPublishDetail(kb, detail),
        );
        container.appendChild(div);
      }
    } catch (e) {
      container.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' +
        escapeHtml(e.message) +
        "</div>";
    }
  },

  _showMyPublishDetail(kb, detail) {
    // -2: 详情面板显示完整信息 + 审批状态徽章 + 「撤回共享（下架）」按钮
    // M0 T14 / Task 8: 审批流已上线。镜像 Skill 侧「我的发布」详情：
    // 顶部显示状态徽章；REJECTED 时把审核意见做成醒目的红框块（拒绝原因），
    // 其他状态若有评论则降级显示为一行「审核意见」。
    // 注：_statusLabel 挂在 skills 对象上（knowledge 对象独立），显式跨对象引用。
    const st = skills._statusLabel(kb.status);
    // Spec §2 parity: withdraw 文案按状态区分（与 Skill 侧「我的发布」一致）。
    const withdrawLabel =
      kb.status === "APPROVED"
        ? "下架并删除"
        : kb.status === "REJECTED"
          ? "删除被拒记录"
          : "撤回投稿";
    let reviewCommentBlock = "";
    if (kb && kb.reviewComment) {
      if (kb.status === "REJECTED") {
        reviewCommentBlock =
          '<div style="background:#fef2f2;border:1px solid #fecaca;border-radius:6px;padding:10px 12px;color:#991b1b;font-size:12px;line-height:1.6;">' +
          "拒绝原因：" +
          escapeHtml(kb.reviewComment) +
          "</div>";
      } else {
        reviewCommentBlock =
          '<div style="background: var(--bg-secondary); border-radius:6px; padding:10px 12px; color: var(--text-muted); font-size:12px; line-height:1.6;">审核意见：' +
          escapeHtml(kb.reviewComment) +
          "</div>";
      }
    }
    detail.innerHTML = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
 <div>名称：<strong>${escapeHtml(kb.name)}</strong></div>
 <div>描述：${escapeHtml(kb.description || "无")}</div>
 <div>状态：<span class="ks-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></div>
 <div>上架时间：${kb.submittedAt ? new Date(kb.submittedAt).toLocaleString() : "-"}</div>
 ${kb && kb.reviewedAt ? "<div>审核时间：" + new Date(kb.reviewedAt).toLocaleString() + "</div>" : ""}
 ${kb && kb.reviewedBy ? "<div>审核人：" + escapeHtml(kb.reviewedBy) + "</div>" : ""}
 </div>
 ${reviewCommentBlock}
 <div style="font-size: 12px; color: var(--text-muted);">
 审批通过后即可被其他用户订阅。你的本地实例保持不变，可正常编辑或删除。
 </div>
 <div style="display: flex; gap: 12px;">
 <button class="send-skill-btn" id="my-publish-withdraw-btn" style="flex: 1; background: var(--warning-color, #f59e0b);">${escapeHtml(withdrawLabel)}</button>
 </div>
 </div>
 `;
    detail
      .querySelector("#my-publish-withdraw-btn")
      .addEventListener("click", () => this._withdrawMarketKnowledge(kb.id));
  },
  async _withdrawMarketKnowledge(id) {
    const ok = await dialog.confirm({
      title: "撤回共享",
      message: "确认撤回该知识库的市场共享？",
      okText: "撤回",
      danger: true,
    });
    if (!ok) return;
    try {
      await api.withdrawMarketKnowledge(id);
      showToast("已撤回共享", "success");
      this._renderCurrentTab();
    } catch (e) {
      showToast("撤回失败：" + e.message, "error");
    }
  },
};

/**
 * _renderReviewSection(kind, marketId, slot) — free helper used by both the
 * Skill and KB market detail panels. Loads the review list via
 * window.MarketAdmin.reviewList and renders an aggregate + submission form
 * + the rendered review list. The submission widget handles the
 * 403 (KB-without-access) case by showing a guidance message.
 *
 * Falls back to a simple "暂不可用" placeholder when window.MarketAdmin is
 * not loaded.
 */
async function _renderReviewSection(kind, marketId, slot) {
  if (!slot) return;
  if (
    !window.MarketAdmin ||
    typeof window.MarketAdmin.reviewList !== "function"
  ) {
    slot.innerHTML =
      '<div style="color: var(--text-muted); font-size: 12px;">评分功能暂不可用</div>';
    return;
  }

  const aggregateBlock = (agg) => {
    if (!agg || !agg.count)
      return '<div class="review-aggregate review-aggregate-empty">尚无评分</div>';
    const avg = Number(agg.avg || 0).toFixed(1);
    return (
      '<div class="review-aggregate">' +
      `<span class="review-aggregate-avg">${avg}</span>` +
      window.MarketAdmin.renderStarWidget(Math.round(agg.avg || 0), true) +
      `<span class="review-aggregate-count">${agg.count} 条评价</span>` +
      "</div>"
    );
  };

  const formBlock = () =>
    '<div class="review-form">' +
    '<div class="review-form-row">' +
    '<span style="font-size: 13px; color: var(--text-primary);">你的评分：</span>' +
    '<span class="star-rating star-rating-editable" data-rating="0">' +
    ["★", "★", "★", "★", "★"]
      .map(
        (_, i) =>
          `<button type="button" class="star-btn" data-value="${i + 1}">★</button>`,
      )
      .join("") +
    "</span>" +
    "</div>" +
    '<textarea class="review-comment-input form-input" rows="3" placeholder="说说你的使用感受（可选）" maxlength="500"></textarea>' +
    '<div class="review-form-actions">' +
    '<span class="review-form-msg" style="font-size: 12px;"></span>' +
    '<button type="button" class="primary-btn review-submit-btn" disabled>提交</button>' +
    "</div>" +
    "</div>";

  slot.innerHTML =
    '<div class="review-loading">加载评价...</div>';

  try {
    const result = await window.MarketAdmin.reviewList(kind, marketId);
    slot.innerHTML =
      aggregateBlock(result.aggregate) +
      formBlock() +
      '<div class="review-list-wrap">' +
      result.html +
      "</div>";

    const starBox = slot.querySelector(".star-rating-editable");
    const submitBtn = slot.querySelector(".review-submit-btn");
    const commentInput = slot.querySelector(".review-comment-input");
    const msgEl = slot.querySelector(".review-form-msg");

    let chosen = 0;
    const refreshStars = () => {
      slot.querySelectorAll(".star-btn").forEach((b) => {
        const v = Number(b.getAttribute("data-value"));
        const active = v <= chosen;
        b.classList.toggle("star-filled", active);
        b.classList.toggle("star-empty", !active);
      });
      submitBtn.disabled = chosen === 0;
    };
    starBox.addEventListener("click", (ev) => {
      const t = ev.target.closest(".star-btn");
      if (!t) return;
      chosen = Number(t.getAttribute("data-value")) || 0;
      refreshStars();
    });
    refreshStars();

    submitBtn.addEventListener("click", async () => {
      if (chosen === 0) return;
      msgEl.style.color = "var(--text-muted)";
      msgEl.textContent = "提交中...";
      submitBtn.disabled = true;
      try {
        await window.MarketAdmin.submitReview(
          kind,
          marketId,
          chosen,
          commentInput.value.trim(),
        );
        msgEl.style.color = "#16a34a";
        msgEl.textContent = "已提交，感谢你的评价！";
        commentInput.value = "";
        chosen = 0;
        refreshStars();
        // refresh list
        const fresh = await window.MarketAdmin.reviewList(kind, marketId);
        const listWrap = slot.querySelector(".review-list-wrap");
        if (listWrap) listWrap.innerHTML = fresh.html;
        const aggWrap = slot.querySelector(".review-aggregate");
        if (aggWrap && aggWrap.parentNode === slot) {
          aggWrap.outerHTML = aggregateBlock(fresh.aggregate);
        } else if (aggWrap) {
          aggWrap.outerHTML = aggregateBlock(fresh.aggregate);
        } else {
          slot.insertAdjacentHTML(
            "afterbegin",
            aggregateBlock(fresh.aggregate),
          );
        }
      } catch (e) {
        submitBtn.disabled = false;
        if (e && e.status === 403) {
          msgEl.style.color = "var(--error-color)";
          msgEl.textContent = "请先访问过该知识库再评";
        } else {
          msgEl.style.color = "var(--error-color)";
          msgEl.textContent =
            "提交失败：" + (e && e.message ? e.message : "未知错误");
        }
      }
    });
  } catch (e) {
    slot.innerHTML =
      '<div style="color: var(--error-color); font-size: 12px;">加载评价失败：' +
      escapeHtml((e && e.message) || "未知错误") +
      "</div>";
  }
}

// ===================== §8.5 File Manager =====================
const fileMgr = {
  openModal() {
    ui.showModal("file-modal-overlay");
    this.loadTree();
  },

  closeModal() {
    ui.hideModal("file-modal-overlay");
  },

  async loadTree() {
    const tree = await api.listFileTree();
    this.renderTree(tree);
  },

  renderTree(node) {
    const container = document.getElementById("file-list");
    if (!node || !node.children || node.children.length === 0) {
      container.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--text-muted);">目录为空</div>';
      return;
    }
    let html = '<div class="file-tree">';
    html += this.renderTreeNode(node, "");
    html += "</div>";
    container.innerHTML = html;
    this.bindTreeEvents(container);
  },

  renderTreeNode(node, path) {
    if (!node || node.type === "file") return "";
    let html = "";
    const children = node.children || [];
    // Sort: directories first, then files
    const dirs = children.filter((c) => c.type === "directory");
    const files = children.filter((c) => c.type === "file");

    for (const dir of dirs) {
      const dirPath = path ? path + "/" + dir.name : dir.name;
      const hasChildren = dir.children && dir.children.length > 0;
      html += `<details class="tree-dir" ${hasChildren ? "" : ""}>`;
      html += `<summary class="tree-dir-summary">📁 ${escapeHtml(dir.name)}</summary>`;
      html += `<div class="tree-children">`;
      html += this.renderTreeNode(dir, dirPath);
      html += `</div>`;
      html += `</details>`;
    }

    for (const file of files) {
      const filePath = path ? path + "/" + file.name : file.name;
      const size = file.size ? formatFileSize(file.size) : "";
      const icon = getFileIcon(file.name);
      html += `<div class="tree-file">`;
      html += `<span class="tree-file-icon">${icon}</span>`;
      html += `<span class="tree-file-name" title="${escapeHtml(filePath)}">${escapeHtml(file.name)}</span>`;
      html += `<span class="tree-file-size">${size}</span>`;
      html += `<button class="tree-file-btn tree-file-preview-btn" data-path="${escapeHtml(filePath)}">预览</button>`;
      html += `<button class="tree-file-btn tree-file-download-btn" data-path="${escapeHtml(filePath)}">下载</button>`;
      html += `</div>`;
    }

    return html;
  },

  bindTreeEvents(container) {
    for (const btn of container.querySelectorAll(".tree-file-preview-btn")) {
      btn.addEventListener("click", () => this.preview(btn.dataset.path));
    }
    for (const btn of container.querySelectorAll(".tree-file-download-btn")) {
      btn.addEventListener("click", () => this.download(btn.dataset.path));
    }
  },

  preview(path) {
    const url =
      window.location.origin +
      "/spring/ai/loom/file/by-path/view?path=" +
      encodeURIComponent(path);
    window.open(url, "_blank", "noopener,noreferrer");
  },

  download(path) {
    const url =
      window.location.origin +
      "/spring/ai/loom/file/by-path/download?path=" +
      encodeURIComponent(path);
    window.open(url, "_blank", "noopener,noreferrer");
  },
};

/** 子任务面板：打开时轮询 active + history，关闭时停止轮询 + ticker。
 * Operations-console surface (v4 redesign) — cold slate dark, status
 * pill counts, signature live-elapsed ticker on running rows. */
const subtaskPanel = {
  _timer: null,
  _ticker: null,
  _currentConvId: null,
  _expanded: new Set(),
  _activeRows: [], // cached for live elapsed-ticker updates

  openModal() {
    ui.showModal("subtask-modal-overlay");
    this._currentConvId =
      (typeof state !== "undefined" && state.conversationId) || "";
    this.refresh();
    this._timer = setInterval(() => this.refresh(), 2000);
    // Live elapsed ticker runs at 1Hz; touches only DOM textContent of
    // running rows (no full re-render). Skipped if no running rows.
    this._ticker = setInterval(() => this._tickElapse(), 1000);
  },

  closeModal() {
    ui.hideModal("subtask-modal-overlay");
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
    if (this._ticker) {
      clearInterval(this._ticker);
      this._ticker = null;
    }
    this._activeRows = [];
  },

  refresh() {
    const convId =
      this._currentConvId ||
      (typeof state !== "undefined" && state.conversationId) ||
      "";
    this._ensureLimits().then(() => {
      if (!convId) {
        this._renderEmpty("请先打开一个对话");
        return;
      }
      Promise.all([
        api.listActiveSubtasks(convId),
        api.listSubtaskHistory(convId),
      ]).then(([active, history]) =>
        this.render(active || [], history || [], convId),
      );
    });
  },

  _ensureLimits() {
    if (this._limits) return Promise.resolve(this._limits);
    return api
      .subtaskLimits()
      .then((l) => (this._limits = l || {}))
      .catch(() => (this._limits = {}));
  },

  _limitsHintHTML() {
    const n = (this._limits && this._limits.maxHistory) || 200;
    return `<div class="micro-hint">回车提交 · 历史最多保留 <code>${escapeHtml(String(n))}</code> 条</div>`;
  },

  setConvId(convId) {
    if (this._currentConvId === convId) return;
    this._currentConvId = convId;
    this.refresh();
  },

  /** Live tick: rewrite elapsed text for every RUNNING card without
   * re-rendering the panel. If modal is closed / no running rows,
   * work is essentially zero. */
  _tickElapse() {
    if (this._activeRows.length === 0) return;
    for (const rec of this._activeRows) {
      const cell = document.querySelector(
        `[data-elapsed="${cssEscape(rec.subTaskId)}"]`,
      );
      if (!cell) continue;
      cell.textContent = this._formatElapsed(Date.now() - rec.startedAt);
    }
  },

  _renderEmpty(msg) {
    const body = document.getElementById("subtask-panel-body");
    if (!body) return;
    body.innerHTML = `
 ${this._toolbarHTML(0, 0, 0)}
 <div class="console-body">
 <div class="console-empty">
 <div class="glyph subtask-glyph">
 <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
 <path d="M4 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9l-5 4v-4H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" fill="rgba(167,139,250,0.18)" stroke="currentColor" stroke-width="1.6"/>
 <circle cx="9" cy="10.5" r="1.4" fill="currentColor"/>
 <circle cx="13" cy="10.5" r="1.4" fill="currentColor"/>
 <circle cx="17" cy="10.5" r="1.4" fill="currentColor"/>
 </svg>
 </div>
 <h4>${escapeHtml(msg || "让 AI 帮你开一个")}</h4>
 <p>主对话里跟它说一句「调研…」，它会自动起子任务。或者直接在这里写你想让它做的事。</p>
 <div class="composer">
 <input data-composer placeholder="「例如:调研苹果公司在东南亚的供应链…」" />
 <button class="console-btn-primary" data-composer-submit style="height:32px;">新建子任务 ↗</button>
 </div>
 ${this._limitsHintHTML()}
 </div>
 </div>`;
    this._wireToolbar(body);
    this._wireComposer(body, "/subtask");
  },

  _toolbarHTML(running, done, failed) {
    const total = running + done + failed;
    return `
 <div class="console-bar">
 <div class="title">子任务<span class="sub">/ sub-task</span></div>
 <span class="console-pill ${running > 0 ? "running" : ""}"><span class="dot"></span><span class="num">${running}</span>&nbsp;运行中</span>
 <span class="console-pill ${done > 0 ? "done" : ""}"><span class="dot"></span><span class="num">${done}</span>&nbsp;已完成</span>
 <span class="console-pill ${failed > 0 ? "failed" : ""}"><span class="dot"></span><span class="num">${failed}</span>&nbsp;失败</span>
 <span class="grow"></span>
 <input class="search" placeholder="搜 prompt / id…" />
 <button class="console-btn-ghost" data-filter>过滤</button>
 ${
   total > 0
     ? `<button class="console-btn-primary" data-new>+ 新建</button>`
     : ""
 }
 <button class="console-close" data-close aria-label="收起"></button>
 </div>`;
  },

  render(active, history, convId) {
    const body = document.getElementById("subtask-panel-body");
    if (!body) return;
    // Counts
    const live = active || [];
    const done = (history || []).filter(
      (r) => (r.status || "").toUpperCase() === "COMPLETED",
    ).length;
    const failed = (history || []).filter(
      (r) => (r.status || "").toUpperCase() === "FAILED",
    ).length;

    // Whole sections: only render when there's content
    const allHistory = history || [];
    if (live.length === 0 && allHistory.length === 0) {
      this._renderEmpty("让 AI 帮你开一个");
      return;
    }

    // Cache for live ticker
    this._activeRows = live.slice();

    let cardsHtml = "";
    if (live.length > 0) {
      cardsHtml += `<div class="console-section">
 <div class="console-section-label">运行中<span class="count">${live.length}</span></div>
 <div class="console-card-list">${live.map((r) => this._rowHTML(r, "running")).join("")}</div>
 </div>`;
    }
    if (allHistory.length > 0) {
      const reversed = allHistory
        .slice()
        .sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0));
      cardsHtml += `<div class="console-section">
 <div class="console-section-label">历史<span class="count">${reversed.length}</span></div>
 <div class="console-card-list">${reversed.map((r) => this._rowHTML(r, (r.status || "").toLowerCase())).join("")}</div>
 </div>`;
    }

    body.innerHTML = `
 ${this._toolbarHTML(live.length, done, failed)}
 <div class="console-body">${cardsHtml}</div>`;

    this._wireToolbar(body);
    this._wireCardActions(body);
  },

  _rowHTML(r, kind) {
    const id = r.subTaskId || "";
    const sidShort = id.slice(0, 4) + "…" + id.slice(-4);
    const prompt = r.prompt || "";
    const meta = [];
    if (kind === "running") {
      const started = r.startedAt ? this._rel(r.startedAt) : "";
      meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
      if (started)
        meta.push(`<span>·</span><span>${escapeHtml(started)}</span>`);
    } else if (kind === "failed") {
      const errMsg = r.errorMessage
        ? r.errorMessage.slice(0, 60)
        : r.status || "FAILED";
      meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
      meta.push(
        `<span>·</span><span class="err">${escapeHtml(errMsg)}${errMsg.length > 60 ? "…" : ""}</span>`,
      );
    } else if (kind === "cancelled") {
      meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
      meta.push(`<span>·</span><span>用户取消</span>`);
    } else {
      // done (or lowercase status)
      const dur = this._rel(r.finishedAt);
      meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
      meta.push(`<span>·</span><span>${escapeHtml(dur)}</span>`);
    }

    const statusLabel =
      {
        running: "RUNNING",
        completed: "DONE",
        failed: "FAILED",
        cancelled: "CANCELLED",
      }[kind] || (kind || "").toUpperCase();

    // Action set varies by state
    let actions = "";
    if (kind === "running") {
      actions = `
 <button class="console-icon-btn" title="查看 stream 日志" data-stream="${escapeHtml(id)}">≡</button>
 <button class="console-icon-btn danger" title="停止" data-kill="${escapeHtml(id)}">■</button>`;
    } else if (kind === "failed" || kind === "cancelled") {
      actions = `
 <button class="console-icon-btn" title="查看详情" data-stream="${escapeHtml(id)}">i</button>
 <button class="console-icon-btn danger" title="从历史删除" data-history-del="${escapeHtml(id)}">×</button>`;
    } else {
      actions = `
 <button class="console-icon-btn" title="查看结果" data-stream="${escapeHtml(id)}">↗</button>
 <button class="console-icon-btn danger" title="从历史删除" data-history-del="${escapeHtml(id)}">×</button>`;
    }

    // Elapsed cell
    let elapsedHtml = "—";
    if (kind === "running" && r.startedAt) {
      elapsedHtml = `<span class="mono">${escapeHtml(this._formatElapsed(Date.now() - r.startedAt))}</span>`;
    } else if (r.startedAt && r.finishedAt) {
      const ms = r.finishedAt - r.startedAt;
      elapsedHtml = this._formatShortDuration(ms);
    }

    return `
 <div class="console-card status-${kind === "running" ? "running" : kind === "completed" ? "done" : kind === "failed" ? "failed" : kind === "cancelled" ? "cancel" : "done"}" data-row-id="${escapeHtml(id)}">
 <div class="stripe"></div>
 <div class="prompt-cell">
 <div class="prompt">${escapeHtml(prompt || "(no prompt)")}</div>
 <div class="meta">${meta.join(" ")}</div>
 </div>
 <div class="console-status"><span class="dot"></span>${escapeHtml(statusLabel)}</div>
 <div class="console-elapsed" data-elapsed="${escapeHtml(id)}">${elapsedHtml}</div>
 <div class="console-actions">${actions}</div>
 </div>`;
  },

  _wireToolbar(body) {
    const overlay = document.getElementById("subtask-modal-overlay");
    // Always-append: the close chevron was previously a nested #subtask-close-btn.
    // We use data-close so re-render doesn't break it.
    const closeBtn = body.querySelector("[data-close]");
    if (closeBtn) closeBtn.addEventListener("click", () => this.closeModal());
    // New-task button: jump to chat with a pre-filled prompt for the LLM
    const newBtn = body.querySelector("[data-new]");
    if (newBtn)
      newBtn.addEventListener("click", () => this._focusChatWithStub());
  },

  _wireCardActions(body) {
    for (const btn of body.querySelectorAll("[data-kill]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.kill(btn.dataset.kill, false);
      });
    }
    for (const btn of body.querySelectorAll("[data-history-del]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.deleteHistory(btn.dataset.historyDel);
      });
    }
    for (const btn of body.querySelectorAll("[data-stream]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.showStream(btn.dataset.stream);
      });
    }
  },

  _wireComposer(body, kind) {
    const input = body.querySelector("[data-composer]");
    const submit = body.querySelector("[data-composer-submit]");
    if (!input || !submit) return;
    const send = () => {
      const text = (input.value || "").trim();
      if (!text) return;
      this._focusChatWithStub(text);
      input.value = "";
    };
    submit.addEventListener("click", send);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") send();
    });
    // Live enable/disable
    const sync = () => {
      submit.disabled = !input.value.trim();
    };
    sync();
    input.addEventListener("input", sync);
  },

  /** Bridge into the main chat SPA: either pre-fill the textarea (if
   * user typed) or simply focus it. Either way the LLM ends up
   * calling start_sub_task / create_scheduled_task on its own. */
  _focusChatWithStub(prefilled) {
    const prompt = prefilled || "请帮我开一个子任务";
    try {
      const ta = document.querySelector("#textarea");
      if (ta) {
        ta.value = prompt;
        ta.dispatchEvent(new Event("input", { bubbles: true }));
        ta.focus();
      }
    } catch (e) {
      /* SPA not ready yet — silent */
    }
    this.closeModal();
  },

  /** Show stream logs for a sub-task id. For now: deep-link to that
   * sub-task's main-conversation entry via the toast (the LLM echoed
   * the tool-result back into the parent conversation). */
  async showStream(id) {
    showToast("子任务 " + id.slice(0, 8) + " 的执行流已在主对话中显示", "info");
  },

  /** mm:ss / h:mm:ss picker used in the live ticker */
  _formatElapsed(ms) {
    ms = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(ms / 3600);
    const m = Math.floor((ms % 3600) / 60);
    const s = ms % 60;
    const pad = (n) => String(n).padStart(2, "0");
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
  },
  _formatShortDuration(ms) {
    if (!ms || ms < 0) return "—";
    ms = Math.floor(ms / 1000);
    if (ms < 60) return ms + "s";
    const m = Math.floor(ms / 60);
    const s = ms % 60;
    return s > 0 ? `${m}m ${s}s` : `${m}m`;
  },

  async kill(id, alsoDeleteHistory) {
    const ok = await dialog.confirm({
      title: alsoDeleteHistory ? "停止并删除子任务" : "停止子任务",
      message:
        `确认${alsoDeleteHistory ? "停止该子任务并在历史中删除" : "停止子任务"} ${id.slice(0, 8)}?` +
        (alsoDeleteHistory
          ? ""
          : "\n\n被挂起等待子任务结果的 AI 调用会收到「子任务已取消 用户手动取消」并继续主对话。"),
      danger: true,
    });
    if (!ok) return;
    const killed = await api.killSubtask(id);
    if (alsoDeleteHistory) {
      let attempts = 0;
      let deleted = false;
      while (attempts < 5 && !deleted) {
        attempts++;
        await new Promise((r) => setTimeout(r, 250));
        deleted = await api.deleteSubtaskHistory(id);
      }
      showToast(
        `已停止并删除 ${id.slice(0, 8)}${deleted ? "" : "（历史行未找到，可能尚未落库）"}`,
        deleted ? "success" : "warning",
      );
    } else {
      showToast(
        killed ? "已停止" : "未找到该子任务",
        killed ? "success" : "warning",
      );
    }
    this.refresh();
  },

  async deleteHistory(id) {
    const ok = await dialog.confirm({
      title: "删除子任务历史记录",
      message: `确认删除历史子任务记录 ${id.slice(0, 8)}?`,
      danger: true,
    });
    if (!ok) return;
    const deleted = await api.deleteSubtaskHistory(id);
    showToast(
      deleted ? "已删除" : "未找到记录",
      deleted ? "success" : "warning",
    );
    this.refresh();
  },

  _rel(ms) {
    if (!ms) return "-";
    const d = Math.floor((Date.now() - ms) / 1000);
    if (d < 60) return `${d}s 前`;
    if (d < 3600) return `${Math.floor(d / 60)}m 前`;
    return `${Math.floor(d / 3600)}h 前`;
  },
};

// CSS.escape polyfill (tier-2) for selectors keyed on user-controlled ids
function cssEscape(s) {
  if (window.CSS && CSS.escape) return CSS.escape(s);
  return String(s).replace(/[^a-zA-Z0-9_-]/g, (c) => "\\" + c);
}

/** 定时任务面板：当前会话的运行中 + 历史,带全部停止按钮 + 每行操作按钮。
 * Operations-console surface (v4 redesign) — schedule rows use shape-
 * encoded status (⏲ scheduled · ✓ ended · — cancelled) so they don't
 * fight the sub-task palette. */
const schedulePanel = {
  _timer: null,
  _currentConvId: null,

  openModal() {
    ui.showModal("schedule-modal-overlay");
    this._currentConvId =
      (typeof state !== "undefined" && state.conversationId) || "";
    this.refresh();
    this._timer = setInterval(() => this.refresh(), 5000);
  },

  closeModal() {
    ui.hideModal("schedule-modal-overlay");
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
  },

  setConvId(convId) {
    if (this._currentConvId === convId) return;
    this._currentConvId = convId;
    if (
      document.getElementById("schedule-modal-overlay") &&
      getComputedStyle(document.getElementById("schedule-modal-overlay"))
        .display !== "none"
    ) {
      this.refresh();
    }
  },

  _resolveConvId() {
    if (typeof state !== "undefined" && state.conversationId)
      return state.conversationId;
    return (
      window.currentConversationId ||
      (window.appState && window.appState.currentConversationId) ||
      (window.chatState && window.chatState.currentConversationId) ||
      (location.hash.match(/conv[=:]([\w-]+)/) || [])[1] ||
      "" ||
      ""
    );
  },

  async refresh() {
    const convId = this._currentConvId || this._resolveConvId();
    this._currentConvId = convId;
    await this._ensureLimits();
    if (!convId) {
      this._renderEmpty("请先打开一个对话");
      return;
    }
    const tasks = await api.scheduleByConversation(convId);
    this.render(tasks || [], convId);
  },

  async _ensureLimits() {
    if (this._limits) return this._limits;
    try {
      this._limits = await api.scheduleLimits();
    } catch (_) {
      this._limits = {};
    }
    return this._limits;
  },

  _limitsHintHTML() {
    const L = this._limits || {};
    if (L.enforcing === false) {
      return `<div class="micro-hint">触发限制未启用</div>`;
    }
    const min = L.minInterval
      ? `最小间隔 <code>${escapeHtml(String(L.minInterval))}</code>`
      : "";
    const max = L.maxLifetime
      ? `最长存活 <code>${escapeHtml(String(L.maxLifetime))}</code>`
      : "";
    const parts = [min, max].filter(Boolean).join(" · ");
    return parts ? `<div class="micro-hint">${parts}</div>` : "";
  },

  _renderEmpty(msg) {
    const body = document.getElementById("schedule-panel-body");
    if (!body) return;
    body.innerHTML = `
 ${this._toolbarHTML(0)}
 <div class="console-body">
 <div class="console-empty">
 <div class="glyph sched-glyph">
 <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
 <circle cx="12" cy="12" r="9"/>
 <path d="M12 7v5l3 2"/>
 <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>
 </svg>
 </div>
 <h4>${escapeHtml(msg || "建一个提醒或周期性任务")}</h4>
 <p>主对话里说「每天 9 点提醒我」，AI 就自动创建。也可以直接写在这里。</p>
 <div class="composer">
 <input data-composer placeholder="「例如:每周一早上 9 点生成上周工作摘要」" />
 <button class="console-btn-primary" data-composer-submit style="height:32px;">新建定时任务 ↗</button>
 </div>
 ${this._limitsHintHTML()}
 </div>
 </div>`;
    this._wireToolbar(body);
    this._wireComposer(body);
  },

  _toolbarHTML(liveCount) {
    return `
 <div class="console-bar">
 <div class="title">定时任务<span class="sub">/ schedule</span></div>
 <span class="console-pill ${liveCount > 0 ? "running" : ""}"><span class="dot"></span><span class="num">${liveCount}</span>&nbsp;运行中</span>
 <span class="grow"></span>
 <input class="search" placeholder="搜任务名…" />
 <button class="console-btn-ghost" data-filter>过滤</button>
 ${
   liveCount > 0
     ? `<button class="console-btn-primary" data-new>+ 新建</button>`
     : ""
 }
 <button class="console-close" data-close aria-label="收起"></button>
 </div>`;
  },

  render(tasks, convId) {
    const body = document.getElementById("schedule-panel-body");
    if (!body) return;
    const live = tasks.filter((t) => t.live);
    const hist = tasks.filter((t) => !t.live);

    if (live.length === 0 && hist.length === 0) {
      this._renderEmpty("建一个提醒或周期性任务");
      return;
    }

    let cardsHtml = "";
    if (live.length > 0) {
      cardsHtml += `<div class="console-section">
 <div class="console-section-label">运行中<span class="count">${live.length}</span></div>
 <div class="console-card-list">${live.map((t) => this._rowHTML(t, true)).join("")}</div>
 </div>`;
    }
    if (hist.length > 0) {
      // Sort by most recent trigger
      const sorted = hist
        .slice()
        .sort((a, b) => (b.lastFireTime || 0) - (a.lastFireTime || 0));
      cardsHtml += `<div class="console-section">
 <div class="console-section-label">已结束<span class="count">${sorted.length}</span></div>
 <div class="console-card-list">${sorted.map((t) => this._rowHTML(t, false)).join("")}</div>
 </div>`;
    }

    body.innerHTML = `
 ${this._toolbarHTML(live.length)}
 <div class="console-body">${cardsHtml}</div>`;

    this._wireToolbar(body);
    this._wireCardActions(body);
  },

  _rowHTML(t, isLive) {
    const full = t.taskName || "";
    const shortName = this._shortName(full);
    const cadence = this._humanizeSchedule(t);
    const stats = this._humanizeStats(t, isLive);

    // Decide status shape (string used to render the glyph via ::before)
    // — schedule uses shape encoding, not color
    let shape, label, kind;
    if (isLive) {
      shape = "⏲";
      label = "SCHEDULED";
      kind = "running";
    } else {
      shape = "✓";
      label = "ENDED";
      kind = "done";
    }

    const elapsed = isLive
      ? this._nextTrigger(t)
      : this._rel(t.lastFireTime || 0);

    const actions = isLive
      ? `<button class="console-icon-btn" title="手动触发" data-trigger="${escapeHtml(full)}">▶</button>
 <button class="console-icon-btn danger" title="停止" data-cancel="${escapeHtml(full)}">■</button>`
      : `<button class="console-icon-btn" title="查看历史" data-history="${escapeHtml(full)}">≡</button>
 <button class="console-icon-btn danger" title="删除" data-cancel="${escapeHtml(full)}">×</button>`;

    return `
 <div class="console-card schedule-row status-${kind}" data-task="${escapeHtml(full)}">
 <div class="stripe"></div>
 <div class="prompt-cell">
 <div class="prompt">${escapeHtml(t.prompt || shortName || "(no prompt)")}</div>
 <div class="meta">
 <div class="cadence">${escapeHtml(cadence)}</div>
 <div class="stats">${stats}</div>
 </div>
 </div>
 <div class="console-status"><span class="glyph">${shape}</span>${escapeHtml(label)}</div>
 <div class="console-elapsed mono">${escapeHtml(elapsed)}</div>
 <div class="console-actions">${actions}</div>
 </div>`;
  },

  _wireToolbar(body) {
    const closeBtn = body.querySelector("[data-close]");
    if (closeBtn) closeBtn.addEventListener("click", () => this.closeModal());
    const newBtn = body.querySelector("[data-new]");
    if (newBtn)
      newBtn.addEventListener("click", () =>
        subtaskPanel._focusChatWithStub("请帮我创建一个定时任务"),
      );
  },

  _wireCardActions(body) {
    for (const btn of body.querySelectorAll("[data-cancel]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.cancel(btn.dataset.cancel);
      });
    }
    for (const btn of body.querySelectorAll("[data-history]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.history(btn.dataset.history);
      });
    }
    for (const btn of body.querySelectorAll("[data-trigger]")) {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this.trigger(btn.dataset.trigger);
      });
    }
  },

  _wireComposer(body) {
    const input = body.querySelector("[data-composer]");
    const submit = body.querySelector("[data-composer-submit]");
    if (!input || !submit) return;
    const send = () => {
      const text = (input.value || "").trim();
      if (!text) return;
      subtaskPanel._focusChatWithStub(text);
      input.value = "";
    };
    submit.addEventListener("click", send);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") send();
    });
    const sync = () => {
      submit.disabled = !input.value.trim();
    };
    sync();
    input.addEventListener("input", sync);
  },

  /** Translate fixed_delay / cron / one_shot + `schedule` string into
   * Chinese humanised form. */
  _humanizeSchedule(t) {
    const type = (t.taskType || "").toLowerCase();
    const sched = t.schedule || "";
    if (type === "one_shot") return "一次性 · " + (sched || "60s 后");
    if (type === "fixed_delay") {
      const m = sched.match(/(\d+)\s*(s|m|h|ms)?/);
      if (m) {
        const n = parseInt(m[1], 10);
        const unit = m[2] || "s";
        const label =
          unit === "s"
            ? `${n} 秒`
            : unit === "m"
              ? `${n} 分钟`
              : unit === "h"
                ? `${n} 小时`
                : `${n} ${unit}`;
        return `每 ${label}一次`;
      }
      return sched || "周期";
    }
    if (type === "cron") return sched || "周期";
    return sched || "—";
  },

  /** Stats line: 下次 / 已触发 count · 上次 fired-ago */
  _humanizeStats(t, isLive) {
    if (isLive) {
      const next = this._nextTrigger(t);
      const fired = t.fireCount || 0;
      const line = [];
      if (next && next !== "—")
        line.push(`<span class="next">下次 ${escapeHtml(next)}</span>`);
      if (fired) line.push(`<span>已触发 ${fired} 次</span>`);
      return line.join("") || "<span>—</span>";
    }
    // ended: 上次 fired ago
    const last = this._rel(t.lastFireTime || 0);
    return `<span>上次 ${escapeHtml(last)}</span>`;
  },

  /** next trigger — derive from now + fixed_delay interval when type is fixed_delay */
  _nextTrigger(t) {
    const type = (t.taskType || "").toLowerCase();
    if (type === "one_shot") return "—";
    if (type === "fixed_delay") {
      const sched = t.schedule || "";
      const m = sched.match(/(\d+)\s*(s|m|h)/);
      if (m) {
        const n = parseInt(m[1], 10);
        const ms =
          n * (m[2] === "m" ? 60_000 : m[2] === "h" ? 3_600_000 : 1000);
        const last = t.lastFireTime || Date.now();
        const next = last + ms;
        const hh = String(new Date(next).getHours()).padStart(2, "0");
        const mm = String(new Date(next).getMinutes()).padStart(2, "0");
        return `${hh}:${mm}`;
      }
    }
    return "—";
  },

  async trigger(fullName) {
    showToast("已请求触发 " + this._shortName(fullName), "info");
  },

  /**
   * BUG-SCHEDULE-SHORTNAME: Extract the user-supplied task name from the
   * namespaced full name (loom-sched-{username}-{conversationId}-{name}).
   * The previous implementation split on '-' and dropped the first 4
   * segments, but conversationId is a UUID containing 4 dashes of its own,
   * so the slice truncated halfway through the conv id and exposed a
   * tail like "884a-43ba-af9f-8304dab3fe32-tmp-test-schedule" instead of
   * the intended "tmp-test-schedule".
   *
   * Heuristic: since conversationId is a UUID (36 chars, format
   * 8-4-4-4-12 hex + 4 dashes), strip "loom-sched-" + first dash-separated
   * segment (username), then strip 36 more chars (the conv id), then
   * return whatever follows. Falls back to the previous best-effort slice
   * if the conv id length doesn't match.
   *
   * IMPORTANT: username MUST not contain dashes (NOT currently validated by
   * DefaultUser.createUser; if dashes are ever allowed, the heuristic below
   * will break — see TODO below).
   */
  // TODO: tighten DefaultUser.createUser to reject dashes in username, or refactor _shortName to iterate past additional dashes until 36 UUID chars consumed
  _shortName(full) {
    const f = full || "";
    const prefix = "loom-sched-";
    if (f.startsWith(prefix)) {
      const rest = f.substring(prefix.length); // "{username}-{convId}-{name}"
      const dashIdx = rest.indexOf("-");
      if (dashIdx > 0) {
        const convStart = dashIdx + 1;
        // UUID convId = 36 chars (e.g. "e5436384-039e-4a23-a35c-968e969e48b8")
        if (rest.length >= convStart + 36) {
          const afterConv = rest.substring(convStart + 36);
          if (afterConv.startsWith("-")) return afterConv.substring(1);
        }
      }
    }
    // Fallback: best-effort slice if format is unexpected.
    const parts = f.split("-");
    return parts.length > 4 ? parts.slice(4).join("-") : f;
  },

  async cancel(fullName) {
    const ok = await dialog.confirm({
      title: "停止定时器",
      message: "确认停止定时器 " + this._shortName(fullName) + " ?",
      danger: true,
    });
    if (!ok) return;
    await api.cancelSchedule(fullName);
    this.refresh();
  },

  async history(fullName) {
    const records = await api.scheduleHistory(fullName);
    const lines = (records || [])
      .slice(0, 20)
      .map((r) => {
        const status = r.success ? "成功" : "失败";
        return `${r.startTime || ""} ${status}${r.error ? "(" + r.error + ")" : ""}`;
      })
      .join(" · ");
    await dialog.confirm({
      title: "执行历史 · " + this._shortName(fullName),
      message: lines || "(暂无执行记录)",
      okText: "关闭",
    });
  },

  async cancelAll(convId) {
    const ok = await dialog.confirm({
      title: "全部停止",
      message: `确认停止当前对话的所有定时任务(包括未触发的和历史任务的配置)?\n\nconv: ${convId}`,
      danger: true,
    });
    if (!ok) return;
    const r = await api.cancelAllSchedulesByConversation(convId);
    await dialog.alert({
      title: "已停止",
      message: `运行时取消 ${r.cancelled} 条,删除 H2 配置 ${r.rowsDeleted} 条,删除执行历史 ${r.execRowsDeleted} 条`,
    });
    this.refresh();
  },
};

/** 对话状态条:在聊天输入框上方显示"运行中 / 触发 / 失败"等可操作的状态。
 * 颜色:绿色 = 有活动且无失败;红色 = 过去 7 天有失败;灰色 = 全部为 0 / 无活动。
 * 不发请求时隐藏。
 */
const convStatePanel = {
  _timer: null,
  _currentConvId: null,

  start() {
    if (this._timer) return;
    this.refresh();
    this._timer = setInterval(() => this.refresh(), 8000);
  },

  stop() {
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
    this._currentConvId = null;
    this._hide();
  },

  async refresh() {
    const convId = (typeof state !== "undefined" && state.conversationId) || "";
    if (!convId) {
      this._hide();
      return;
    }
    this._currentConvId = convId;
    const s = await api.conversationState(convId);
    if (!s) {
      this._hide();
      return;
    }
    this._render(s);
  },

  _hide() {
    const el = document.getElementById("conv-state-strip");
    if (el) el.style.display = "none";
  },

  _render(s) {
    const el = document.getElementById("conv-state-strip");
    if (!el) return;
    const fields = [
      "activeSchedules",
      "executionsLast7d",
      "executionsFailedLast7d",
      "activeSubTasks",
      "subTaskHistoryLast7d",
      "subTaskFailedLast7d",
    ];
    const activityKeys = [
      "activeSchedules",
      "executionsLast7d",
      "activeSubTasks",
      "subTaskHistoryLast7d",
    ];
    const failureKeys = ["executionsFailedLast7d", "subTaskFailedLast7d"];
    const hasActivity = activityKeys.some((k) => (s[k] || 0) > 0);
    const hasFailures = failureKeys.some((k) => (s[k] || 0) > 0);
    if (!hasActivity && !hasFailures) {
      el.style.display = "none";
      return;
    }
    el.style.display = "flex";
    for (const k of fields) {
      const item = el.querySelector(`[data-key="${k}"]`);
      if (!item) continue;
      const n = s[k] || 0;
      item.querySelector(".conv-state-num").textContent = String(n);
      item.classList.remove("has-activity", "has-failures");
      if (failureKeys.includes(k) && n > 0) item.classList.add("has-failures");
      else if (activityKeys.includes(k) && n > 0)
        item.classList.add("has-activity");
    }
    // Whole-strip border tint when failures present.
    el.style.borderTop = hasFailures
      ? "1px solid rgba(231, 76, 60, 0.4)"
      : "1px solid var(--border-color, rgba(255,255,255,0.06))";
  },
};

function getFileIcon(name) {
  const ext = name.split(".").pop().toLowerCase();
  const icons = {
    pdf: "📕",
    doc: "📘",
    docx: "📘",
    xls: "📗",
    xlsx: "📗",
    ppt: "📙",
    pptx: "📙",
    png: "🖼️",
    jpg: "🖼️",
    jpeg: "🖼️",
    gif: "🖼️",
    svg: "🖼️",
    mp3: "🎵",
    mp4: "🎬",
    wav: "🎵",
    zip: "📦",
    tar: "📦",
    gz: "📦",
    js: "📜",
    ts: "📜",
    py: "📜",
    java: "📜",
    go: "📜",
    rs: "📜",
    md: "📝",
    txt: "📄",
    csv: "📊",
    html: "🌐",
    css: "🎨",
    json: "📋",
    yaml: "⚙️",
    yml: "⚙️",
    xml: "📋",
    sql: "🗃️",
    sh: "⚡",
    bat: "⚡",
    ps1: "⚡",
  };
  return icons[ext] || "📄";
}

function escapeHtml(text) {
  const div = document.createElement("div");
  div.textContent = text;
  // M4 T7 (T6 review hardening): div.innerHTML 只转义 & < >，不转义引号 —
  // data-tag="..." 等属性上下文遇到含 " 的值可被注入。补 " / ' 转义。
  return div.innerHTML.replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// M4 T7: 搜索关键词高亮 — 必须先 escape 后 mark（kw 与 text 都走 escapeHtml，
// 再做正则转义），保证 <img onerror=...> 之类输入只会以转义文本呈现，
// 命中的子串被包进 <mark class="search-hit">。kw trim 后为空 → 等价 escapeHtml。
function highlightHtml(text, kw) {
  const esc = escapeHtml(text == null ? "" : String(text));
  const k = (kw || "").trim();
  if (!k) return esc;
  const ekw = escapeHtml(k).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return esc.replace(
    new RegExp(ekw, "gi"),
    (m) => '<mark class="search-hit">' + m + "</mark>",
  );
}

// ===================== §9 MCP Service =====================
const mcp = {
  /**
   * Build a per-user localStorage key for persisting MCP selection.
   * Namespace by username so selections do not leak across users sharing a browser
   * (BUG-MCP-PERSIST-LEAK). Falls back to '_anonymous' if state.username is null/empty.
   */
  _storageKey() {
    const u = state.username;
    return (
      "loom.mcp.selectedNames." +
      (u && typeof u === "string" && u.length ? u : "_anonymous")
    );
  },

  /** Read persisted selection; returns null if missing/corrupt. */
  _loadPersisted() {
    try {
      const raw = localStorage.getItem(this._storageKey());
      if (!raw) return null;
      const arr = JSON.parse(raw);
      return Array.isArray(arr)
        ? arr.filter((s) => typeof s === "string")
        : null;
    } catch (_) {
      return null;
    }
  },

  /** Persist current selection. */
  _savePersisted() {
    try {
      localStorage.setItem(
        this._storageKey(),
        JSON.stringify(state.selectedMcps),
      );
    } catch (_) {
      // localStorage may be disabled (private mode, quota) — fail silently
    }
  },

  openModal() {
    ui.showModal("mcp-modal-overlay");
    // 打开时如果还没加载(初次或登录后),异步拉一次再渲染
    if (state.capabilities.length === 0) {
      this.loadList().then(() => this.renderModal());
    } else {
      this.renderModal();
    }
  },

  closeModal() {
    ui.hideModal("mcp-modal-overlay");
  },

  renderModal() {
    const container = document.getElementById("mcp-list");
    const detail = document.getElementById("mcp-detail");
    detail.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个能力查看详情</p></div>';

    if (state.capabilities.length === 0) {
      container.innerHTML =
        '<div style="padding: 20px; text-align: center; color: var(--text-muted);">暂无可用能力</div>';
      return;
    }
    container.innerHTML = "";
    for (const c of state.capabilities) {
      const item = document.createElement("div");
      const isSelected = c.type === "LOCAL"
        ? state.selectedToolGroups.includes(c.id)
        : state.selectedMcps.includes(c.id);
      const enabled = c.effectiveEnabled === true;
      // 修复:未授权的能力强制 unchecked,避免 "checked + disabled" 死锁态。
      // localStorage 里残留的未授权 id 会在 loadList 清理(见 capability.loadList)
      const checked = enabled && isSelected;
      item.className = "skill-item" + (checked ? " selected" : "") + (enabled ? "" : " disabled");
      // 类型徽章:本地(M5 前是 MCP 唯一,现在要区分)
      const badge = c.type === "LOCAL"
        ? '<span style="background:#e0f2fe;color:#0369a1;padding:2px 8px;border-radius:4px;font-size:11px;margin-left:8px;">本地</span>'
        : '<span style="background:#fef3c7;color:#92400e;padding:2px 8px;border-radius:4px;font-size:11px;margin-left:8px;">MCP</span>';
      item.innerHTML = `
 <div style="display: flex; align-items: center; gap: 12px;">
 <input type="checkbox" ${checked ? "checked" : ""} ${enabled ? "" : "disabled"} style="width: 18px; height: 18px; cursor: ${enabled ? "pointer" : "not-allowed"};" class="mcp-checkbox">
 <div class="mcp-item-text" style="flex: 1; cursor: ${enabled ? "pointer" : "not-allowed"};">
 <div class="skill-item-name">${c.title || c.name}${badge}</div>
 <div style="font-size:11px;color:var(--text-muted);margin-top:2px;">${c.description || ""}</div>
 </div>
 </div>`;
      item.querySelector(".mcp-checkbox").addEventListener("click", (e) => {
        e.stopPropagation();
        if (!enabled) return;
        this.toggleSelect(c.id, item);
      });
      item
        .querySelector(".mcp-item-text")
        .addEventListener("click", () => {
          if (!enabled) return;
          this.showDetail(c);
        });
      container.appendChild(item);
    }
  },

  toggleSelect(id, element) {
    // id 是 capability id(本地用 "tool_xxx",MCP 用 live client name)
    const isLocal = id.startsWith("tool_");
    const arr = isLocal ? state.selectedToolGroups : state.selectedMcps;
    const idx = arr.indexOf(id);
    if (idx >= 0) {
      arr.splice(idx, 1);
      element.classList.remove("selected");
    } else {
      arr.push(id);
      element.classList.add("selected");
    }
    this._savePersisted();
    showToast(
      `已${arr.includes(id) ? "选中" : "取消"}能力`,
      "success",
    );
  },

  async showDetail(m) {
    document.getElementById("mcp-detail-title").textContent = m.title || m.name;
    const detail = document.getElementById("mcp-detail");
    let html = "";
    html += `<div class="detail-section">
 <div class="detail-section-title">基本信息</div>
 <div style="line-height: 1.8; color: var(--text-primary);">
 <div style="margin-bottom: 12px;"><strong>名称：</strong>${escapeHtml(m.name)}</div>
 <div><strong>描述：</strong>${escapeHtml(m.description || "无描述")}</div>
 </div>
 </div>`;
    // 工具数据已经在 /mcps 列表接口里（带 tools 字段），
    // 这里直接用 m.tools 渲染，不再二次请求 —— 避免 mcp 名含 @ 等特殊字符触发 Tomcat 400。
    const tools = m.tools || [];
    html += `<div class="detail-section">
 <div class="detail-section-title">包含工具 (${tools.length})</div>
 <div id="mcp-tools-list">`;
    if (tools.length === 0) {
      html +=
        '<span style="color: var(--text-muted); font-size: 13px;">无可用工具</span>';
    } else {
      html +=
        '<div style="display: flex; flex-direction: column; gap: 12px;">' +
        tools
          .map((tool) => {
            // 直接展示 SDK 原文（含已维护的覆盖）。空字符串就不显示 description 行
            const descHtml =
              tool.description && tool.description.trim()
                ? `<div style="font-size: 13px; color: var(--text-secondary); line-height: 1.6; white-space: pre-wrap;">${escapeHtml(tool.description)}</div>`
                : "";
            return `
 <div style="padding: 16px; background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 8px;">
 <div style="font-weight: 600; font-size: 14px; color: var(--primary-color); margin-bottom: 8px;">${escapeHtml(tool.name)}</div>
 ${descHtml}
 </div>`;
          })
          .join("") +
        "</div>";
    }
    html += "</div></div>";
    detail.innerHTML = html;
  },

  async loadList() {
    try {
      // M5:从 /api/capabilities 拉统一列表(本地 tool group + MCP server,带 type)
      const resp = await apiFetch(API.listCapabilities);
      const data = await resp.json();
      if (data && data.length > 0) {
        state.capabilities = data;
        // 兼容:state.mcps 保留原 MCP 列表,供详情面板 / 其他遗留代码用
        state.mcps = data.filter((c) => c.type === "MCP");
        // 按 id + effectiveEnabled 双过滤:localStorage 残留的(已撤销授权 / MCP 已下线)
        // id 会自动剔除,UI 也不再出现 "checked + disabled" 死锁态。
        const toolIds = data.filter((c) => c.type === "LOCAL" && c.effectiveEnabled === true).map((c) => c.id);
        const mcpIds = data.filter((c) => c.type === "MCP" && c.effectiveEnabled === true).map((c) => c.id);
        const persisted = this._loadPersisted();
        if (persisted && persisted.length > 0) {
          state.selectedToolGroups = persisted.filter((n) => toolIds.includes(n));
          state.selectedMcps = persisted.filter((n) => mcpIds.includes(n));
        } else {
          // 默认:effectiveEnabled=true 的全勾(角色授权的都能用)
          state.selectedToolGroups = toolIds.slice();
          state.selectedMcps = mcpIds.slice();
        }
        // 重新持久化一次,顺手清理残留(下次 refresh 就干净了)
        this._savePersisted();
        return;
      }
    } catch (e) {
      console.warn("[capability.loadList] failed:", e);
    }
    state.capabilities = [];
    state.mcps = [];
    state.selectedToolGroups = [];
    state.selectedMcps = [];
  },
};

// ===================== §10 Skills =====================
const skills = {
  editingSkill: null, // null = view mode, object = creating/editing
  currentTab: "mine", // 'mine' / 'market' / 'submit'

  openModal() {
    ui.showModal("skills-modal-overlay");
    // Tab 绑定（只绑一次）
    this._bindTabs();
    this.renderModal();
  },

  closeModal() {
    ui.hideModal("skills-modal-overlay");
    this.editingSkill = null;
  },

  _bindTabs() {
    if (this._tabsBound) return;
    document
      .querySelectorAll("#skills-modal-overlay .skill-tab")
      .forEach((btn) => {
        btn.addEventListener("click", () => {
          this.currentTab = btn.getAttribute("data-tab");
          document
            .querySelectorAll("#skills-modal-overlay .skill-tab")
            .forEach((b) => {
              const active = b.getAttribute("data-tab") === this.currentTab;
              b.classList.toggle("active", active);
              b.style.borderBottomColor = active
                ? "var(--primary-color)"
                : "transparent";
              b.style.color = active
                ? "var(--primary-color)"
                : "var(--text-muted)";
            });
          this.renderModal();
        });
      });
    this._tabsBound = true;
  },

  /** source 字段 → 中文标签 + 颜色 */
  _sourceLabel(source) {
    switch (source) {
      case "USER_CREATED":
        return { text: "自建", bg: "#dbeafe", color: "#1e40af" };
      case "MARKET_PULLED":
        return { text: "市场", bg: "#d1fae5", color: "#065f46" };
      case "ROLE_GRANTED":
        return { text: "角色授权", bg: "#fef3c7", color: "#92400e" };
      case "MARKET_VIEW":
        return { text: "市", bg: "#ede9fe", color: "#6b21a8" };
      case "embed":
        return { text: "内置", bg: "#f1f5f9", color: "#475569" };
      default:
        return { text: source, bg: "#f1f5f9", color: "#475569" };
    }
  },

  renderModal() {
    const container = document.getElementById("skills-list");
    const detail = document.getElementById("skills-detail");
    // 切 Tab 时重置详情标题（避免上一个 Tab 选中的 skill 名字残留在标题上）
    const titleEl = document.getElementById("skill-detail-title");
    if (titleEl) titleEl.textContent = "技能详情";
    detail.innerHTML =
      '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个技能查看详情，或点击「新增」创建新技能</p></div>';
    container.innerHTML =
      '<div style="padding: 20px; text-align: center; color: var(--text-muted);">加载中...</div>';

    if (this.currentTab === "mine") this._renderMineTab(container);
    else if (this.currentTab === "market") this._renderMarketTab(container);
    else if (this.currentTab === "submit")
      this._renderSubmitTab(container, detail);
    else if (this.currentTab === "mysubmit")
      this._renderMySubmissions(container, detail);
  },

  _renderMineTab(container) {
    api
      .listSkills()
      .then((data) => {
        container.innerHTML = "";
        if (!data || data.length === 0) {
          container.innerHTML =
            '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">暂无可用技能<br><br>点击「+ 新增」自建，或切到「市场」拉取</div>';
          return;
        }

        for (const skill of data) {
          const item = document.createElement("div");
          item.className = "skill-item";
          const lbl = this._sourceLabel(skill.source);
          item.innerHTML = `
 <div class="skill-item-name">${escapeHtml(skill.name)} <span class="skill-source-tag" style="background:${lbl.bg};color:${lbl.color};">${lbl.text}</span></div>
 <div class="skill-item-desc">${escapeHtml(skill.description || "")}</div>
 `;
          item.addEventListener("click", () => this.select(skill, item));
          container.appendChild(item);
        }
      })
      .catch(() => {
        container.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败</div>';
      });
  },

  // M4 T2: 技能市场服务端搜索 + load-more 分页状态
  //（v2 GET /market-skills?page=&size=&query=，Page{items,total,page,size}，0-based）
  _skillMarketQuery: "",
  _skillMarketPage: 0,
  _skillMarketItems: [],
  _skillMarketTotal: 0,
  _skillMarketHasMore: false,
  _skillMarketSeq: 0,
  _skillMarketLoading: false,
  _skillMarketDebounce: null,
  // M4 T6: tag 过滤状态（null/空 = 无 tag 过滤，走 T2 服务端 query+分页路径）
  _skillTagFilter: null,
  // M4 T7: 排序状态（official_rank 默认 / submitted_at / rating）。仅分页分支带 sortBy；
  // tag 过滤激活时 select 被 disable，sortBy 不下发（服务端 ?tag= 分支忽略排序 — D11）。
  _skillSort: "official_rank",

  // M4 T7: 排序 select 的 HTML（两 tab 复用同一构造器）。label 走 I18N.t（带 zh fallback）；
  // disabled=true 用于 tag 过滤激活时锁死 select（clearest UX，见 D11 裁决）。
  _renderSortSelectHtml(id, currentSort, disabled) {
    const t = (key, fallback) =>
      (window.I18N && window.I18N.t
        ? window.I18N.t(key, fallback)
        : fallback) || fallback;
    const opt = (val, label) =>
      '<option value="' +
      val +
      '"' +
      (currentSort === val ? " selected" : "") +
      ">" +
      escapeHtml(label) +
      "</option>";
    return (
      '<select id="' +
      id +
      '" class="market-sort-select"' +
      (disabled ? " disabled" : "") +
      ">" +
      opt("official_rank", t("market.sort.official", "官方优先")) +
      opt("submitted_at", t("market.sort.newest", "最新提交")) +
      opt("rating", t("market.sort.rating", "评分最高")) +
      "</select>"
    );
  },

  async _renderMarketTab(container, tagFilter) {
    // 两段式（点列表项 → 详情面板 + send-skill-btn 风格按钮），跟技能库市场 Tab 风格一致
    // M4 T2: 客户端过滤（_skillMarketAll）已被服务端搜索取代 —— 搜索框 input 事件
    // debounce 300ms → page=0 重新 fetch；Enter 立即 fetch。in-flight guard 用递增
    // sequence token，过期响应直接丢弃。highlight 留给 T7。
    // M4 T6: tagFilter (string|null) — 非空时走公开 GET /market-skills?tag=...
    // 分支（enriched 裸数组，keyword query 忽略 —— 与 KB tab D11/D12 相同语义）；
    // 为空时保持 T2 服务端 query + load-more 分页路径原样。
    this._skillTagFilter = tagFilter || null;
    this._skillMarketQuery = "";
    this._skillMarketPage = 0;
    this._skillMarketItems = [];
    this._skillMarketTotal = 0;
    this._skillMarketHasMore = false;
    // Fix round 1: 重进 tab 时取消悬挂的 debounce —— 否则 300ms 内切走再切回，
    // 旧 rowsWrap 的 runSearch 会晚触发并 bump seq，把本次合法的初始 fetch
    // 判为 stale 丢弃，tab 卡在「加载中...」。
    if (this._skillMarketDebounce) {
      clearTimeout(this._skillMarketDebounce);
      this._skillMarketDebounce = null;
    }
    // M4 T6: tag/no-tag 切换时立即 bump seq —— 让上一分支的 in-flight fetch
    //（旧 tag 的裸数组响应 / 旧 query 的分页响应）在 _fetchSkillMarketPage
    // 里被判 stale 丢弃，防止晚到响应污染新分支的 rowsWrap。
    this._skillMarketSeq++;
    // 搜索栏复用 kb-tag-filter-bar/input（已在 style.css 共享层）—— DOM/位置保持不变。
    // M4 T6: 同一个 .kb-tag-filter-bar 里合并「搜索 input 行 + tag chips 行」（镜像 KB 布局）。
    container.innerHTML = "";
    const bar = document.createElement("div");
    bar.className = "kb-tag-filter-bar";
    // M4 T7: 排序 select —— tag 过滤激活时 disable（?tag= 分支忽略 sortBy，D11）。
    // select 的 disabled 状态随每次 _renderMarketTab 重建（tag chip 点击会重进此函数）。
    bar.innerHTML =
      '<span class="kb-tag-filter-bar-label">搜索：</span>' +
      '<div class="kb-tag-filter-input-row">' +
      '<input type="text" id="skill-market-search" class="kb-tag-filter-input" ' +
      'placeholder="搜索技能（名称 / 描述 / 作者）"/>' +
      "</div>" +
      '<span class="kb-tag-filter-bar-label">排序：</span>' +
      this._renderSortSelectHtml(
        "skill-market-sort",
        this._skillSort,
        !!this._skillTagFilter,
      ) +
      '<span class="kb-tag-filter-bar-label">标签筛选：</span>' +
      '<span id="skill-market-tag-chips" class="tag-chip-group"></span>';
    container.appendChild(bar);
    // 初始 chips（items 已重置为空 → 只有「全部」chip）；每次 rows 渲染后按已加载行聚合刷新
    this._refreshSkillTagChips(container);
    const rowsWrap = document.createElement("div");
    rowsWrap.innerHTML =
      '<div style="padding: 20px; text-align: center; color: var(--text-muted);">加载中...</div>';
    container.appendChild(rowsWrap);
    const searchInput = bar.querySelector("#skill-market-search");
    const runSearch = () => {
      // M4 T6: tag 过滤激活时忽略 keyword 搜索（KB tab 同语义：tag 分支不带 query，
      // 排序固定 rank 序）—— input/Enter 不发 fetch，不发明新 UX。
      if (this._skillTagFilter) return;
      if (this._skillMarketDebounce) {
        clearTimeout(this._skillMarketDebounce);
        this._skillMarketDebounce = null;
      }
      this._skillMarketQuery = searchInput.value.trim();
      // 新搜索开始 → 旧 load-more 按钮立即失效（seq guard 兜底 stale response）
      const staleBtn = container.querySelector(".load-more-btn");
      if (staleBtn) staleBtn.disabled = true;
      this._fetchSkillMarketPage(0, false, rowsWrap, container);
    };
    searchInput.addEventListener("input", () => {
      if (this._skillMarketDebounce) clearTimeout(this._skillMarketDebounce);
      this._skillMarketDebounce = setTimeout(runSearch, 300);
    });
    searchInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        runSearch();
      }
    });
    // M4 T7: 排序 change → 重置 page/items/seq（bump seq 让 in-flight fetch 失效），
    // 再走 page 0 分页 fetch（tag 过滤激活时 select 已 disabled，不会触发）。
    const sortSelect = bar.querySelector("#skill-market-sort");
    if (sortSelect) {
      sortSelect.addEventListener("change", () => {
        this._skillSort = sortSelect.value || "official_rank";
        this._skillMarketPage = 0;
        this._skillMarketItems = [];
        this._skillMarketTotal = 0;
        this._skillMarketHasMore = false;
        this._skillMarketSeq++;
        const staleBtn = container.querySelector(".load-more-btn");
        if (staleBtn) staleBtn.remove();
        this._fetchSkillMarketPage(0, false, rowsWrap, container);
      });
    }
    await this._fetchSkillMarketPage(0, false, rowsWrap, container);
  },

  // M4 T6: 技能市场 tag 过滤 chips（镜像 KB _renderKbTagFilterBar 的 chip 构造 —
  // 「全部」chip + 每个聚合 tag 一个 chip；active chip 高亮）。
  // 复用说明：chips 的渲染/绑定是 skill-local 镜像，因为 KB 的 _renderKbTagFilterBar /
  // _bindKbTagFilterBar 硬编码 KB 的 input id（kb-tag-filter-input/apply）且回调
  // KB 自己的 _renderMarketTab —— 直接复用会触发 KB tab 重渲染。纯展示型的
  // _renderTagChipsHtml（row/详情 chips 用）则通过同作用域的 knowledge 对象复用。
  _renderSkillTagChipsHtml(tags, activeTag) {
    const tagList = Array.isArray(tags) ? tags : [];
    const active = activeTag || null;
    const allActive = active == null || active === "";
    const allChipCls = "tag-chip tag-chip-filter" + (allActive ? " active" : "");
    const chips = tagList
      .map((t) => {
        const cls =
          "tag-chip tag-chip-filter" + (active === t ? " active" : "");
        return (
          '<span class="' +
          cls +
          '" data-tag="' +
          escapeHtml(t) +
          '">' +
          escapeHtml(t) +
          "</span>"
        );
      })
      .join("");
    return (
      '<span class="' + allChipCls + '" data-tag="">全部</span>' + chips
    );
  },

  // M4 T6: 从当前已加载行聚合 distinct tags（null-safe，KB 2337-2349 同款）→
  // 刷新 #skill-market-tag-chips 并绑定 chip 点击（点 active chip / 「全部」= 清除过滤）。
  _refreshSkillTagChips(container) {
    const slot = container.querySelector("#skill-market-tag-chips");
    if (!slot) return;
    const aggregatedTags = [];
    const seen = new Set();
    for (const m of this._skillMarketItems || []) {
      const tags = m && Array.isArray(m.tags) ? m.tags : [];
      for (const tg of tags) {
        const s = String(tg);
        if (s && !seen.has(s)) {
          seen.add(s);
          aggregatedTags.push(s);
        }
      }
    }
    aggregatedTags.sort();
    slot.innerHTML = this._renderSkillTagChipsHtml(
      aggregatedTags,
      this._skillTagFilter,
    );
    slot.querySelectorAll(".tag-chip-filter").forEach((chip) => {
      chip.addEventListener("click", () => {
        const tag = chip.getAttribute("data-tag") || "";
        // 「全部」或再点已 active 的 chip = 清除过滤 → 回 T2 服务端分页路径（page 0，
        // _renderMarketTab 统一重置 items/page/query/debounce 并 bump seq）
        if (tag === "" || chip.classList.contains("active")) {
          this._renderMarketTab(container, null);
          return;
        }
        this._renderMarketTab(container, tag);
      });
    });
  },

  // M4 T2: 拉取技能市场一页（page 0-based，size=20）。append=true 时 concat 追加。
  // sequence token 防 stale response（搜索 debounce 期间旧请求晚到会覆盖新结果）。
  // 错误全部内部消化（带 seq guard）：page-0 失败渲染错误态；load-more 失败保留
  // 已加载行 + toast + 重新启用按钮。
  // M4 T6: _skillTagFilter 非空时走 ?tag= 分支 —— 公开 GET /market-skills?tag=...
  // 返回 enriched 裸数组（rows 带 tags + announcement；无 Page wrapper / total，
  // 单次 fetch size=100，无 load-more）；keyword query 在该分支被忽略（KB 同语义）。
  async _fetchSkillMarketPage(page, append, rowsWrap, container) {
    const seq = ++this._skillMarketSeq;
    try {
      if (this._skillTagFilter) {
        // tag 分支基址复用 API.listMarketSkills（T2 已接线的 API map 条目），镜像 KB tag 分支拼 URL
        const url =
          `${API.listMarketSkills}?tag=` +
          encodeURIComponent(this._skillTagFilter) +
          "&page=0&size=100";
        const r = await apiFetch(url);
        if (!r.ok) throw new Error("HTTP " + r.status);
        const items = (await r.json()) || [];
        if (seq !== this._skillMarketSeq) return; // stale response — discard
        this._skillMarketItems = Array.isArray(items) ? items : [];
        this._skillMarketTotal = this._skillMarketItems.length;
        this._skillMarketPage = 0;
        this._skillMarketHasMore = false; // 裸数组单次 fetch — tag 路径无 load-more
        this._renderSkillMarketRows(rowsWrap, container);
        return;
      }
      const data = await api.listMarketSkills(
        page,
        20,
        this._skillMarketQuery,
        this._skillSort,
      );
      if (seq !== this._skillMarketSeq) return; // stale response — discard
      const items = (data && (data.items || data.content)) || data || [];
      this._skillMarketItems = append
        ? this._skillMarketItems.concat(items)
        : items;
      this._skillMarketTotal =
        data && typeof data.total === "number"
          ? data.total
          : this._skillMarketItems.length;
      this._skillMarketPage = page;
      this._skillMarketHasMore =
        items.length >= 20 &&
        this._skillMarketItems.length < this._skillMarketTotal;
      this._renderSkillMarketRows(rowsWrap, container);
    } catch (e) {
      if (seq !== this._skillMarketSeq) return; // stale error — discard
      if (append) {
        showToast("加载失败：" + e.message, "error");
        const btn = container.querySelector(".load-more-btn");
        if (btn) btn.disabled = false;
        this._skillMarketLoading = false;
      } else {
        rowsWrap.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' +
          escapeHtml(e.message) +
          "</div>";
        // Fix round 1: page-0 搜索失败也要清掉旧 load-more 按钮（runSearch 只是
        // disabled 它）—— 与空态 early-return 的清理对称，避免残留 disabled 按钮。
        const staleLoadMore = container.querySelector(".load-more-btn");
        if (staleLoadMore) staleLoadMore.remove();
      }
    }
  },

  _renderSkillMarketRows(rowsWrap, container) {
    // M4 T2: 渲染 this._skillMarketItems 原样（服务端已按 query 过滤；不再客户端过滤）
    // M4 T6: tag 过滤激活时同样原样渲染（?tag= 分支返回 enriched 裸数组）——
    // rows 渲染后按已加载行聚合刷新 tag chips（bar 在 container 上，rows 在 rowsWrap）。
    const t = (key, fallback) =>
      (window.I18N && window.I18N.t
        ? window.I18N.t(key, fallback)
        : fallback) || fallback;
    const items = this._skillMarketItems || [];
    rowsWrap.innerHTML = "";
    // M4 T2: load-more 按钮 — 没有更多时移除（ruling: removal）；空态 early-return
    // 前也要移除，否则旧按钮残留在 container 上
    const oldBtn = container.querySelector(".load-more-btn");
    if (oldBtn) oldBtn.remove();
    if (items.length === 0) {
      // 空态：tag 过滤 → 「没有匹配 tag「x」的技能」（镜像 KB）；有 query →
      // 「没有匹配「kw」的技能」（沿用旧文案）；无 → 市场暂无技能
      rowsWrap.innerHTML = this._skillTagFilter
        ? '<div style="padding: 24px; text-align: center; color: var(--text-muted);">没有匹配 tag「' +
          escapeHtml(this._skillTagFilter) +
          '」的技能</div>'
        : this._skillMarketQuery
          ? '<div style="padding: 24px; text-align: center; color: var(--text-muted);">没有匹配「' +
            escapeHtml(this._skillMarketQuery) +
            '」的技能</div>'
          : '<div style="padding: 40px; text-align: center; color: var(--text-muted);">市场暂无技能</div>';
      // M4 T6: 空态也要刷新 chips —— tag 激活时聚合为空（仅「全部」+ active chip
      // 依赖 _skillTagFilter 渲染），点击「全部」可退出空态
      this._refreshSkillTagChips(container);
      return;
    }
    // T19 fix-up 2: per-row announcement banner — 当 row.announcementTitle 存在时,
    // 渲染 banner 紧贴在 row 上方。点击 banner 选中该 row。
    for (const m of items) {
      if (m && m.announcementTitle) {
        const annView = {
          title: m.announcementTitle,
          body: m.announcementBody || "",
        };
        const banner = document.createElement("div");
        banner.className = "market-announcement market-announcement-list";
        banner.innerHTML =
          window.MarketAdmin && window.MarketAdmin.announcementHtml
            ? window.MarketAdmin.announcementHtml(annView)
            : `<div class="market-announcement-title">📢 ${escapeHtml(
                annView.title,
              )}</div><div class="market-announcement-body">${escapeHtml(
                annView.body,
              )}</div>`;
        banner.style.cursor = "pointer";
        // 点击 banner 时 banner 自身不是 list item — 暂时用 placeholder,
        // 真正选中由下面 row 的 click 处理。
        banner.addEventListener("click", () => {
          const rowEl = banner.nextElementSibling;
          if (rowEl && rowEl.classList.contains("ks-item")) rowEl.click();
        });
        rowsWrap.appendChild(banner);
      }
      const item = document.createElement("div");
      item.className = "ks-item";
      const officialBadge = m && m.isOfficial
        ? ' <span class="ks-source-tag" title="官方推荐" style="background:#fef3c7;color:#92400e;">🏛️</span>'
        : "";
      // M4 T6: per-row tag chips（镜像 KB ~2389-2393）—— listPaged / ?tag= 分支的 rows 都带 tags
      const tagChips =
        m && Array.isArray(m.tags) && m.tags.length > 0
          ? '<div class="kb-tag-row">' +
            knowledge._renderTagChipsHtml(m.tags) +
            "</div>"
          : "";
      // M4 T7: name/description/author 走 highlightHtml（当前 query 命中包 <mark>；
      // tag 分支 query 恒为 ""，highlightHtml 退化为 escapeHtml）。详情/公告/chips 不高亮。
      item.innerHTML = `
 <div class="ks-item-main">
 <div class="ks-item-row1">
 <span class="ks-item-name">${highlightHtml(m.name, this._skillMarketQuery)}${officialBadge} <span class="ks-source-tag" style="background:#ede9fe;color:#6b21a8;">市</span></span>
 </div>
 <span class="ks-item-desc">by ${highlightHtml(m.author || "", this._skillMarketQuery)} · ${highlightHtml(m.description || "", this._skillMarketQuery)}</span>
 ${tagChips}
 </div>
 `;
      item.addEventListener("click", () => this._selectMarketSkill(m, item));
      rowsWrap.appendChild(item);
    }
    // M4 T6: rows 渲染完成后按已加载行聚合刷新 tag filter chips（null-safe）
    this._refreshSkillTagChips(container);
    // M4 T2: 有更多时追加 load-more 按钮（旧按钮已在函数开头移除）
    if (this._skillMarketHasMore) {
      const btn = document.createElement("button");
      btn.className = "secondary-btn load-more-btn";
      btn.textContent = t("market.load.more", "加载更多");
      btn.addEventListener("click", async () => {
        if (this._skillMarketLoading) return;
        this._skillMarketLoading = true;
        btn.disabled = true;
        // 错误已在 _fetchSkillMarketPage 内部消化（toast + 重新启用按钮）
        await this._fetchSkillMarketPage(
          this._skillMarketPage + 1,
          true,
          rowsWrap,
          container,
        );
        this._skillMarketLoading = false;
      });
      container.appendChild(btn);
    }
  },

  _selectMarketSkill(marketSkill, element, already) {
    document
      .querySelectorAll("#skills-list .skill-item")
      .forEach((i) => i.classList.remove("selected"));
    element.classList.add("selected");
    document.getElementById("skill-detail-title").textContent =
      marketSkill.name;
    const detail = document.getElementById("skills-detail");
    // M4 T6: 优先用列表已内嵌的 row.tags（listPaged / ?tag= 分支都带 tags），否则详情内实时拉一次
    const preloadedTags =
      marketSkill && Array.isArray(marketSkill.tags) ? marketSkill.tags : null;
    detail.innerHTML = `
 <div id="market-announcement-slot"></div>
 <div class="detail-section">
 <div class="detail-section-title">市场元数据</div>
 <div class="detail-section-content" style="line-height: 1.8; color: var(--text-primary); font-size: 13px;">
 <div>作者：${escapeHtml(marketSkill.author)}</div>
 <div>状态：<span class="type-badge ADMIN">${escapeHtml(marketSkill.status)}</span></div>
 <div id="skill-detail-tags-row" style="margin-top:6px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;"><span style="color:var(--text-muted);">标签：</span><span id="skill-detail-tags-slot"></span></div>
 </div>
 </div>
 <div class="detail-section">
 <div class="detail-section-title">描述</div>
 <div class="detail-section-content">${escapeHtml(marketSkill.description || "无")}</div>
 </div>
 <div class="detail-section">
 <div class="detail-section-title">内容</div>
 <div class="detail-section-content" style="max-height: 300px; overflow: auto; background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-family: var(--font-mono, monospace); font-size: 12px; white-space: pre-wrap;">${escapeHtml(marketSkill.content || "")}</div>
 </div>
 <div class="detail-section">
 <div class="detail-section-title">评分与评论</div>
 <div id="market-reviews-slot" class="market-reviews-slot"></div>
 </div>
 <div style="margin-top: 24px;">
 <button class="send-skill-btn" id="pull-skill-btn" style="flex: 1;">${already ? "已拉取（点击更新）" : "拉取到我的 Skill"}</button>
 <button class="skill-detail-secondary-btn" id="skill-detail-download-btn" title="下载为 .skill.md 文件">⬇ 下载</button>
 </div>
 `;
    detail
      .querySelector("#pull-skill-btn")
      .addEventListener("click", () => this.handlePull(marketSkill));
    const dlBtn = detail.querySelector("#skill-detail-download-btn");
    if (dlBtn)
      dlBtn.addEventListener("click", () => this.handleDownload(marketSkill));

    // T19: load announcement + review list + submission form.
    const annSlot = detail.querySelector("#market-announcement-slot");
    const reviewSlot = detail.querySelector("#market-reviews-slot");
    // M4 T6: tag slot（镜像 KB ~2553-2582）— 优先 row.tags，否则 MarketAdmin.getMarketTags
    // 实时拉一次；失败静默显示「无」。
    const tagSlot = detail.querySelector("#skill-detail-tags-slot");
    const renderTags = (tags) => {
      if (!tagSlot) return;
      if (Array.isArray(tags) && tags.length > 0) {
        tagSlot.outerHTML =
          '<span id="skill-detail-tags-slot" class="tag-chip-group">' +
          knowledge._renderTagChipsHtml(tags) +
          "</span>";
      } else {
        tagSlot.outerHTML =
          '<span id="skill-detail-tags-slot" style="color:var(--text-muted);font-size:12px;">无</span>';
      }
    };
    if (preloadedTags) {
      renderTags(preloadedTags);
    } else if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.getMarketTags === "function"
    ) {
      window.MarketAdmin
        .getMarketTags("SKILL", marketSkill.id)
        .then(renderTags)
        .catch(() => {
          if (tagSlot) tagSlot.textContent = "无";
        });
    } else if (tagSlot) {
      tagSlot.textContent = "—";
    }
    if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.getAnnouncement === "function"
    ) {
      window.MarketAdmin
        .getAnnouncement("SKILL", marketSkill.id)
        .then((ann) => {
          if (ann && annSlot) annSlot.innerHTML = window.MarketAdmin.announcementHtml(ann);
        })
        .catch(() => {});
    }
    if (
      window.MarketAdmin &&
      typeof window.MarketAdmin.reviewList === "function"
    ) {
      _renderReviewSection("SKILL", marketSkill.id, reviewSlot);
    } else {
      reviewSlot.innerHTML =
        '<div style="color: var(--text-muted); font-size: 12px;">评分功能暂不可用</div>';
    }
  },

  async handlePull(marketSkill) {
    try {
      await api.pullMarketSkill(marketSkill.id);
      showToast(
        `已${marketSkill.name ? "拉取 / 更新" : "拉取"}「${marketSkill.name}」`,
        "success",
      );
      // 切到"我的" Tab
      this.currentTab = "mine";
      document
        .querySelectorAll("#skills-modal-overlay .skill-tab")
        .forEach((b) => {
          const active = b.getAttribute("data-tab") === this.currentTab;
          b.classList.toggle("active", active);
          b.style.borderBottomColor = active
            ? "var(--primary-color)"
            : "transparent";
          b.style.color = active ? "var(--primary-color)" : "var(--text-muted)";
        });
      this.renderModal();
      state._skillListCache = null; // 市场拉取会写入/更新 Skill，slash picker 缓存需失效
    } catch (e) {
      showToast("拉取失败：" + e.message, "error");
    }
  },

  _renderSubmitTab(container, detail) {
    // 列出自建的 Skill（source=USER_CREATED）作为"可选共享"
    api.listSkills().then((mine) => {
      const userCreated = (mine || []).filter(
        (s) => s.source === "USER_CREATED",
      );
      container.innerHTML = "";
      if (userCreated.length === 0) {
        container.innerHTML =
          '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">还没有自建 Skill。<br>切到「我的」→ 新建一个再来共享</div>';
        detail.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">请选择要共享到市场的自建 Skill</div>';
        return;
      }
      for (const s of userCreated) {
        const item = document.createElement("div");
        item.className = "skill-item";
        item.innerHTML = `
 <div class="skill-item-name">${escapeHtml(s.name)}</div>
 <div class="skill-item-desc">${escapeHtml(s.description || "")}</div>
 `;
        item.addEventListener("click", () => this._showSubmitForm(s, detail));
        container.appendChild(item);
      }
      detail.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 14px; margin-bottom: 8px;">从左侧选一个自建 Skill 共享到市场</p><p style="font-size: 12px; color: var(--text-muted);">共享后状态为 PENDING，<br>需管理员在控制台「Skill 市场」审批通过后，<br>其他用户才能在「市场」Tab 拉取。</p></div>';
    });
  },

  _statusLabel(status) {
    // M3+ T4.3 — text pulled from window.I18N.t when available, falls back
    // to the Chinese label if the i18n dict hasn't loaded or the key is missing.
    const t = (key, fallback) => (window.I18N && window.I18N.t ? window.I18N.t(key) : fallback) || fallback;
    switch (status) {
      case "PENDING":
        return { text: t("market.admin.status.pending", "审核中"), bg: "#fef3c7", color: "#92400e" };
      case "APPROVED":
        return { text: t("market.admin.status.approved", "已通过"), bg: "#d1fae5", color: "#065f46" };
      case "REJECTED":
        return { text: t("market.admin.status.rejected", "已拒绝"), bg: "#fee2e2", color: "#991b1b" };
      default:
        return { text: status, bg: "#f1f5f9", color: "#475569" };
    }
  },

  _renderMySubmissions(container, detail) {
    api.listMySubmittedSkills().then((data) => {
      container.innerHTML = "";
      if (!data || data.length === 0) {
        container.innerHTML =
          '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">还没有向市场共享 Skill<br><br>切到「共享」Tab 从自建 Skill 发起共享</div>';
        detail.innerHTML =
          '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择左侧 Skill 查看详情</div>';
        return;
      }
      for (const s of data) {
        const item = document.createElement("div");
        item.className = "skill-item";
        const st = this._statusLabel(s.status);
        item.innerHTML = `
 <div class="skill-item-name">${escapeHtml(s.name)} <span class="skill-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></div>
 <div class="skill-item-desc">${escapeHtml(s.description || "")}</div>
 `;
        item.addEventListener("click", () =>
          this._showMySubmissionDetail(s, item, detail),
        );
        container.appendChild(item);
      }
      detail.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个 Skill 查看详情</div>';
    });
  },

  _showMySubmissionDetail(skill, element, detail) {
    document
      .querySelectorAll("#skills-list .skill-item")
      .forEach((i) => i.classList.remove("selected"));
    element.classList.add("selected");
    const st = this._statusLabel(skill.status);
    // M0 T14: REJECTED 时把审核意见做成醒目的红框块，避免被淹没在元信息文字流中。
    // 其他状态若有评论也降级显示在一行（保留历史兼容）。
    let reviewCommentBlock = "";
    if (skill.reviewComment) {
      if (skill.status === "REJECTED") {
        reviewCommentBlock =
          '<div style="background:#fef2f2;border:1px solid #fecaca;border-radius:6px;padding:10px 12px;color:#991b1b;font-size:12px;line-height:1.6;">' +
          "拒绝原因：" +
          escapeHtml(skill.reviewComment) +
          "</div>";
      } else {
        reviewCommentBlock =
          "<div>审核意见：" + escapeHtml(skill.reviewComment) + "</div>";
      }
    }
    // Spec §2: withdraw 在任意状态可用 — 按钮文案按状态区分（PENDING/APPROVED/REJECTED）。
    const t = (key, fallback) =>
      (window.I18N && window.I18N.t
        ? window.I18N.t(key, fallback)
        : fallback) || fallback;
    const withdrawLabel =
      skill.status === "APPROVED"
        ? t("market.withdraw.approved", "下架并删除")
        : skill.status === "REJECTED"
          ? t("market.withdraw.rejected", "删除被拒记录")
          : t("market.withdraw.pending", "撤回投稿");
    let html = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
 <div>名称：${escapeHtml(skill.name)}</div>
 <div>状态：<span class="skill-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></div>
 <div>共享时间：${skill.submittedAt ? new Date(skill.submittedAt).toLocaleString() : "-"}</div>
 ${skill.reviewedAt ? "<div>审核时间：" + new Date(skill.reviewedAt).toLocaleString() + "</div>" : ""}
 ${skill.reviewedBy ? "<div>审核人：" + escapeHtml(skill.reviewedBy) + "</div>" : ""}
 ${reviewCommentBlock}
 </div>
 <div style="font-size: 13px; color: var(--text-muted);">${escapeHtml(skill.description || "无说明")}</div>
 <div class="detail-section-content" style="max-height: 300px; overflow: auto; background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-family: var(--font-mono, monospace); font-size: 12px; white-space: pre-wrap;">${escapeHtml(skill.content || "")}</div>
 `;
    html += `
 <div style="display: flex; gap: 12px; margin-top: 8px;">
 <button class="send-skill-btn" id="withdraw-skill-btn" style="flex: 1; background: var(--warning-color, #f59e0b);">${escapeHtml(withdrawLabel)}</button>
 ${
   skill.status === "REJECTED"
     ? `<button class="send-skill-btn" id="resubmit-skill-btn" style="flex: 1; background: var(--bg-secondary); color: var(--text-primary); border: 1px solid var(--border-color);">${escapeHtml(t("market.resubmit", "重新投稿"))}</button>`
     : ""
 }
 </div>
 `;
    html += "</div>";
    detail.innerHTML = html;

    const withdrawBtn = document.getElementById("withdraw-skill-btn");
    if (withdrawBtn) {
      withdrawBtn.addEventListener("click", () => this.handleWithdraw(skill));
    }
    const resubmitBtn = document.getElementById("resubmit-skill-btn");
    if (resubmitBtn) {
      resubmitBtn.addEventListener("click", () => this.handleResubmit(skill));
    }
  },

  async handleWithdraw(skill) {
    // Spec §2: APPROVED 下架是破坏性操作（市场条目被删除，他人已拉取副本不再同步更新），用更强确认文案。
    const msg =
      skill.status === "APPROVED"
        ? `该技能已通过审批并被其他用户拉取，下架删除将移除市场条目（他人已拉取的副本保留但不再同步更新）。确认下架「${skill.name}」？`
        : `确认撤回「${skill.name}」的共享？`;
    if (!confirm(msg)) return;
    try {
      await api.withdrawMarketSkill(skill.id);
      showToast(`已撤回「${skill.name}」`, "success");
      this.renderModal();
    } catch (e) {
      showToast("撤回失败：" + e.message, "error");
    }
  },

  async handleResubmit(skill) {
    // REJECTED 行重新投稿：后端自动归档旧 REJECTED 行 + 新建 PENDING 行
    try {
      await api.submitMarketSkill({
        name: skill.name,
        description: skill.description,
        content: skill.content,
      });
      showToast("已重新投稿，等待管理员审批", "success");
      this.renderModal();
    } catch (e) {
      showToast("重新投稿失败：" + e.message, "error");
    }
  },

  _showSubmitForm(skill, detail) {
    document
      .querySelectorAll("#skills-list .skill-item")
      .forEach((i) => i.classList.remove("selected"));
    detail.innerHTML = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
 共享「<strong>${escapeHtml(skill.name)}</strong>」到市场。审批通过后其他用户可在「市场」Tab 拉取；<br>
 你的本地实例保持不变，共享不影响你的使用。
 </div>
 <div>
 <label class="param-label">版本号 <span style="color: var(--error-color);">*</span></label>
 <input type="text" id="submit-skill-version" class="param-input" placeholder="例如 1.0.0（语义化版本）" value="1.0.0">
 </div>
 <div style="font-size: 12px; color: var(--text-muted);">
 共享后状态为 PENDING，等待管理员审批。同名投稿会更新原记录。
 </div>
 <div style="display: flex; gap: 12px;">
 <button class="send-skill-btn" id="submit-confirm-btn" style="flex: 1;">共享到市场</button>
 </div>
 </div>
 `;
    detail
      .querySelector("#submit-confirm-btn")
      .addEventListener("click", () => this.handleSubmit(skill));
  },

  async handleSubmit(skill) {
    const version = document
      .getElementById("submit-skill-version")
      ?.value?.trim();
    if (!version) {
      showToast("请输入版本号", "error");
      return;
    }
    try {
      await api.submitMarketSkill({
        name: skill.name,
        description: skill.description,
        content: skill.content,
        version: version,
      });
      showToast(
        `已共享「${skill.name}」v${version}，等待管理员审批`,
        "success",
      );
      this.renderModal();
    } catch (e) {
      showToast("共享失败：" + e.message, "error");
    }
  },

  select(skill, element) {
    // 切换到其他 skill：自动放弃当前编辑模式（不保存）
    // 旧行为：if (this.editingSkill) return; —— 用户点列表完全没反应，体验差
    if (this.editingSkill && this.editingSkill.name === skill.name) {
      // 点的就是当前正在编辑的 skill，不打断
    } else if (this.editingSkill) {
      this.editingSkill = null;
    }
    state.selectedSkill = skill;
    const allItems = document.querySelectorAll("#skills-list .skill-item");
    allItems.forEach((i) => i.classList.remove("selected"));
    element.classList.add("selected");

    document.getElementById("skill-detail-title").textContent = skill.name;
    const detail = document.getElementById("skills-detail");
    // 是否可编辑：USER_CREATED 完全可改；MARKET_PULLED 可改 desc + defaultLoaded；ROLE_GRANTED / MARKET_VIEW 只读
    const isRoleGranted = skill.source === "ROLE_GRANTED";
    const isMarketView = skill.source === "MARKET_VIEW"; // admin 特权视图（市场 APPROVED）
    const isPulled = skill.source === "MARKET_PULLED"; // 自取
    const canEdit = skill.source === "USER_CREATED" || isPulled;
    const canDelete = canEdit;
    const lbl = this._sourceLabel(skill.source);
    let html = "";

    // 来源 + 状态
    html += `<div class="detail-section">
 <div class="detail-section-title">来源 / 状态</div>
 <div class="detail-section-content" style="font-size: 13px;">
 <span class="skill-source-tag" style="background:${lbl.bg};color:${lbl.color};">${lbl.text}</span>
 <span style="margin-left: 12px; color: ${skill.load ? "var(--success-color, #22c55e)" : "var(--text-muted)"};">
 ${skill.load ? "已加载" : "未加载"}
 </span>
 ${isRoleGranted ? ' · <span style="color: var(--text-muted)">已被角色授权锁定，不可编辑</span>' : ""}
 ${isMarketView ? ' · <span style="color: var(--text-muted)">市场视图（admin 特权），可去「市场」Tab 拉取</span>' : ""}
 </div>
 </div>`;

    // Description
    html += `<div class="detail-section">
 <div class="detail-section-title">技能说明内容</div>
 <div class="detail-section-content" style="line-height: 1.8; color: var(--text-primary);">
 ${skill.content ? renderMarkdown(skill.content) : '<span style="color: var(--text-muted);">无详细说明</span>'}
 </div>
 </div>`;

    // Actions
    if (canEdit) {
      html += `<div style="margin-top: 24px; display: flex; gap: 12px;">
 <button class="send-skill-btn" id="edit-skill-btn" style="flex: 1;">${isPulled ? "编辑描述 / 默认加载" : "编辑技能"}</button>
 <button class="delete-skill-btn" id="delete-skill-btn" style="flex: 1; background: var(--error-color, #ef4444);">删除技能</button>
 </div>`;
    }

    // Action buttons: 应用 / 复制 / 下载（所有 source 都能用）
    html += `<div style="margin-top: 12px; display: flex; gap: 12px;">
 <button class="send-skill-btn" id="apply-skill-btn" style="flex: 1;">应用</button>
 <button class="send-skill-btn" id="copy-skill-btn" style="flex: 1; background: var(--bg-secondary); color: var(--text-primary); border: 2px solid var(--border-color);">复制</button>
 <button class="skill-detail-secondary-btn" id="user-skill-download-btn" title="下载为 .skill.md 文件">⬇ 下载</button>
 </div>`;

    detail.innerHTML = html;

    const editBtn = detail.querySelector("#edit-skill-btn");
    if (editBtn)
      editBtn.addEventListener("click", () => this.showEditForm(skill));

    const deleteBtn = detail.querySelector("#delete-skill-btn");
    if (deleteBtn)
      deleteBtn.addEventListener("click", () => this.handleDelete(skill));

    detail
      .querySelector("#apply-skill-btn")
      .addEventListener("click", () => this.apply(skill, {}));
    detail
      .querySelector("#copy-skill-btn")
      .addEventListener("click", () => this.copyToTextarea(skill, {}));

    const userDlBtn = detail.querySelector("#user-skill-download-btn");
    if (userDlBtn)
      userDlBtn.addEventListener("click", () => this.handleDownload(skill));
  },

  showEditForm(skill) {
    this.editingSkill = skill;
    document.getElementById("skill-detail-title").textContent = "编辑技能";
    const detail = document.getElementById("skills-detail");
    // MARKET_PULLED 不能改 content（content 来自市场快照），只能改 desc + defaultLoaded
    const isPulled = skill.source === "MARKET_PULLED";
    const contentReadonly = isPulled;
    detail.innerHTML = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div>
 <label class="param-label">技能名称</label>
 <input type="text" id="edit-skill-name" class="param-input" value="${escapeHtml(skill.name)}" disabled placeholder="例如：周报生成">
 </div>
 <div>
 <label class="param-label">技能描述</label>
 <input type="text" id="edit-skill-desc" class="param-input" value="${escapeHtml(skill.description || "")}" placeholder="简要描述技能的功能">
 </div>
 <div>
 <label class="param-label">技能内容（Prompt 模板）${contentReadonly ? ' <span style="font-size: 11px; color: var(--text-muted);">（市场拉取的 Skill 不可改；想更新请去「市场」Tab 重新拉取）</span>' : ""}</label>
 <textarea id="edit-skill-content" class="param-input param-textarea" style="min-height: 200px; font-family: var(--font-mono, monospace); font-size: 13px;" placeholder="技能内容模板，支持 {param} 占位符"${contentReadonly ? " disabled" : ""}>${escapeHtml(skill.content || "")}</textarea>
 </div>
 <div style="display: flex; align-items: center; gap: 8px;">
 <input type="checkbox" id="edit-skill-load" ${skill.load ? "checked" : ""}>
 <label for="edit-skill-load">加载此技能</label>
 </div>
 <div style="margin-top: 8px; display: flex; gap: 12px;">
 <button class="send-skill-btn" id="save-skill-btn" style="flex: 1;">保存</button>
 <button class="send-skill-btn" id="cancel-edit-btn" style="flex: 1; background: var(--text-muted);">取消</button>
 </div>
 </div>
 `;

    detail
      .querySelector("#save-skill-btn")
      .addEventListener("click", () => this.handleSave(skill));
    detail.querySelector("#cancel-edit-btn").addEventListener("click", () => {
      this.editingSkill = null;
      const selectedItem = document.querySelector(
        "#skills-list .skill-item.selected",
      );
      if (selectedItem) this.select(skill, selectedItem);
    });
  },

  showCreateForm() {
    this.editingSkill = null;
    document.getElementById("skill-detail-title").textContent = "新增技能";
    // Clear selection
    document
      .querySelectorAll("#skills-list .skill-item")
      .forEach((i) => i.classList.remove("selected"));
    state.selectedSkill = null;
    const detail = document.getElementById("skills-detail");
    detail.innerHTML = `
 <div style="display: flex; flex-direction: column; gap: 16px;">
 <div>
 <label class="param-label">技能名称 <span style="color: var(--error-color);">*</span></label>
 <input type="text" id="edit-skill-name" class="param-input" placeholder="例如：周报生成">
 </div>
 <div>
 <label class="param-label">技能描述</label>
 <input type="text" id="edit-skill-desc" class="param-input" placeholder="简要描述技能的功能">
 </div>
 <div>
 <label class="param-label">技能内容（Prompt 模板）<span style="color: var(--error-color);">*</span></label>
 <textarea id="edit-skill-content" class="param-input param-textarea" style="min-height: 200px; font-family: var(--font-mono, monospace); font-size: 13px;" placeholder="技能内容模板，支持 {param} 占位符"></textarea>
 </div>
 <div style="display: flex; align-items: center; gap: 8px;">
 <input type="checkbox" id="edit-skill-load" checked>
 <label for="edit-skill-load">加载此技能</label>
 </div>
 <div style="margin-top: 8px; display: flex; gap: 12px;">
 <button class="send-skill-btn" id="save-skill-btn" style="flex: 1;">保存</button>
 <button class="send-skill-btn" id="cancel-edit-btn" style="flex: 1; background: var(--text-muted);">取消</button>
 </div>
 </div>
 `;

    detail
      .querySelector("#save-skill-btn")
      .addEventListener("click", () => this.handleCreate());
    detail.querySelector("#cancel-edit-btn").addEventListener("click", () => {
      this.editingSkill = null;
      document.getElementById("skill-detail-title").textContent = "";
      detail.innerHTML =
        '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个技能查看详情，或点击「新增」创建新技能</p></div>';
    });
  },

  async handleCreate() {
    const name = document.getElementById("edit-skill-name")?.value?.trim();
    const description = document
      .getElementById("edit-skill-desc")
      ?.value?.trim();
    const content = document
      .getElementById("edit-skill-content")
      ?.value?.trim();
    const load = document.getElementById("edit-skill-load")?.checked ?? true;

    if (!name) {
      showToast("请输入技能名称", "error");
      return;
    }
    if (!content) {
      showToast("请输入技能内容", "error");
      return;
    }

    const result = await api.createSkill({ name, description, load, content });
    if (result !== null) {
      showToast("技能创建成功", "success");
      this.editingSkill = null;
      this.renderModal();
      state._skillListCache = null; // 新建 Skill，slash picker 缓存需失效
    } else {
      showToast("创建失败，请重试", "error");
    }
  },

  async handleSave(originalSkill) {
    const name = document.getElementById("edit-skill-name")?.value?.trim();
    const description = document
      .getElementById("edit-skill-desc")
      ?.value?.trim();
    const content = document
      .getElementById("edit-skill-content")
      ?.value?.trim();
    const load = document.getElementById("edit-skill-load")?.checked ?? true;

    if (!name) {
      showToast("请输入技能名称", "error");
      return;
    }
    if (!content) {
      showToast("请输入技能内容", "error");
      return;
    }

    let result;
    if (originalSkill.source === "MARKET_PULLED") {
      // 自取的 Skill：仅改 desc + defaultLoaded（PATCH）
      result = await api.patchSkill(name, { description, defaultLoaded: load });
    } else {
      // 自建：全字段保存
      result = await api.updateSkill({ name, description, load, content });
    }
    if (result !== null) {
      showToast("技能保存成功", "success");
      this.editingSkill = null;
      this.renderModal();
    } else {
      showToast("保存失败，请重试", "error");
    }
  },

  async handleDelete(skill) {
    if (!confirm(`确定要删除技能「${skill.name}」吗？此操作不可撤销。`)) return;

    const result = await api.deleteSkill(skill.name);
    if (result !== null) {
      showToast("技能已删除", "success");
      this.renderModal();
      state._skillListCache = null; // 删除 Skill，slash picker 缓存需失效
    } else {
      showToast("删除失败，请重试", "error");
    }
  },

  /** 把 Skill 导出为标准 .skill.md（YAML frontmatter + body） */
  handleDownload(skill) {
    const name = (skill.name || "").replace(/[\r\n]+/g, " ").trim();
    const description = (skill.description || "")
      .replace(/[\r\n]+/g, " ")
      .trim();
    const body = (skill.content || "").replace(/\r\n/g, "\n");
    const md =
      "---\n" +
      `name: ${name}\n` +
      `description: ${description}\n` +
      "---\n" +
      "\n" +
      body +
      "\n";
    const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${name || "untitled"}.skill.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    if (skill.source === "ROLE_GRANTED" || skill.source === "MARKET_PULLED") {
      showToast("已下载；如需导入为可编辑 Skill，请使用新的名称", "success");
    } else {
      showToast("已下载 " + a.download, "success");
    }
  },

  /** 解析 .skill.md 的 YAML frontmatter + body。返回 {name, description, content} 或 {error} */
  _parseSkillFrontmatter(text) {
    const m = text.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
    if (!m)
      return {
        error:
          "请使用标准 Skill 格式：---\nname: x\ndescription: y\n---\n<内容>",
      };
    const yaml = m[1],
      body = m[2];
    const nameMatch = yaml.match(/^name:\s*(.+?)\s*$/m);
    const descMatch = yaml.match(/^description:\s*(.+?)\s*$/m);
    const name = nameMatch ? nameMatch[1].trim() : "";
    if (!name) return { error: "frontmatter 必须有 name: 字段" };
    if (name.length > 128) return { error: "name 长度超过 128" };
    const description = descMatch ? descMatch[1].trim() : "";
    if (!description) return { error: "frontmatter 必须有 description: 字段" };
    return { name, description, content: body.replace(/^\n+/, "") };
  },

  /** 触发隐藏 file input */
  handleImport() {
    const input = document.getElementById("skill-import-file-input");
    if (input) input.click();
  },

  async _doImportFile(file) {
    const text = await file.text();
    const parsed = this._parseSkillFrontmatter(text);
    if (parsed.error) {
      showToast("解析失败：" + parsed.error, "error");
      return;
    }
    // 探测当前是否已有同名 Skill
    let existing = null;
    try {
      existing = await api.getSkill(parsed.name);
    } catch (_) {
      /* 404 */
    }
    if (!existing) {
      await this._submitImport(parsed);
      return;
    }
    // 已存在：USER_CREATED 走三选项，锁定类型直接提示改名
    if (existing.source === "USER_CREATED") {
      this._showImportConflictDialog(parsed, existing);
    } else {
      showToast(
        `同名 Skill「${parsed.name}」由 ${existing.source === "ROLE_GRANTED" ? "角色授权" : "市场"}锁定，无法覆盖。请改名后导入。`,
        "error",
      );
    }
  },

  async _submitImport(parsed) {
    try {
      const r = await api.upsertSkill(parsed);
      const status = r && r.status ? r.status : "imported";
      showToast(
        `已${status === "updated" ? "覆盖" : "导入"}技能 ${parsed.name}`,
        "success",
      );
      this.renderModal();
      state._skillListCache = null; // 导入/覆盖 Skill，slash picker 缓存需失效
    } catch (e) {
      showToast("导入失败：" + (e.message || e), "error");
    }
  },

  _showImportConflictDialog(parsed, existing) {
    // index.html (line 320-334) 的 id 已重构为 confirm-modal-* 前缀；保持与 CSS/HTML 一致
    const m = document.getElementById("confirm-modal-overlay");
    const titleEl = document.getElementById("confirm-modal-title");
    const msgEl = document.getElementById("confirm-modal-message");
    const ok = document.getElementById("confirm-modal-ok");
    const cancel = document.getElementById("confirm-modal-cancel");
    const closeBtn = document.getElementById("confirm-modal-close");
    titleEl.textContent = `技能「${parsed.name}」已存在`;
    msgEl.innerHTML =
      `同名技能已存在，请选择处理方式：<br><br><b>覆盖</b>：直接替换现有内容<br>` +
      `<b>另存为 ${parsed.name}-2</b>：自动寻找下一个可用后缀并保存<br>` +
      `<b>取消</b>：不导入`;
    ok.textContent = "覆盖";
    ok.style.background = "var(--warning-color, #f59e0b)";
    ok.style.borderColor = "var(--warning-color, #f59e0b)";
    let altBtn = document.getElementById("confirm-save-as-btn");
    if (!altBtn) {
      altBtn = document.createElement("button");
      altBtn.id = "confirm-save-as-btn";
      altBtn.className = "primary-btn";
      altBtn.style.flex = "1";
      altBtn.textContent = "另存为";
      m.querySelector(".modal-footer").insertBefore(altBtn, ok);
    }
    altBtn.textContent = `另存为 ${parsed.name}-2`;
    altBtn.style.display = "";
    cancel.textContent = "取消";
    const cleanup = () => {
      // 标题/正文/按钮文案 全部还原，避免污染后续走 confirm-modal 的流程
      titleEl.textContent = "确认";
      msgEl.innerHTML = "";
      ok.textContent = "确定";
      ok.style.background = "";
      ok.style.borderColor = "";
      cancel.textContent = "取消";
      altBtn.style.display = "none";
      ok.removeEventListener("click", onOk);
      altBtn.removeEventListener("click", onAlt);
      cancel.removeEventListener("click", onCancel);
      closeBtn.removeEventListener("click", onCancel);
    };
    const onOk = async () => {
      cleanup();
      m.style.display = "none";
      await this._submitImport(parsed);
    };
    const onAlt = async () => {
      cleanup();
      m.style.display = "none";
      const newName = await this._findFreeName(parsed.name + "-2");
      await this._submitImport({ ...parsed, name: newName });
    };
    const onCancel = () => {
      cleanup();
      m.style.display = "none";
    };
    ok.addEventListener("click", onOk);
    altBtn.addEventListener("click", onAlt);
    cancel.addEventListener("click", onCancel);
    closeBtn.addEventListener("click", onCancel);
    m.style.display = "flex";
  },

  async _findFreeName(base) {
    let n = 2,
      name = base;
    while (true) {
      try {
        const r = await api.getSkill(name);
        if (!r) return name;
      } catch (_) {
        return name;
      }
      n++;
      name = base.replace(/-(\d+)$/, "") + "-" + n;
    }
  },

  send(skill, skillParams) {
    // Build prompt
    let promptContent = skill.content;
    if (skill.params && skill.params.length > 0) {
      for (const param of skill.params) {
        const regex = new RegExp(`\\{${param.name}\\}`, "g");
        promptContent = promptContent.replace(
          regex,
          skillParams[param.name] || "",
        );
      }
    }

    this.closeModal();
    document.getElementById("textarea").value = promptContent;

    // Auto-select associated MCPs
    if (skill.tools) {
      for (const toolName of skill.tools) {
        if (!state.selectedMcps.includes(toolName)) {
          state.selectedMcps.push(toolName);
        }
      }
      mcp._savePersisted();
    }

    chat.send();
  },

  /** "应用" 按钮：覆盖 textarea 内容 + 直接发送给大模型 */
  apply(skill, skillParams) {
    const promptContent = this._buildPrompt(skill, skillParams);
    this.closeModal();
    const ta = document.getElementById("textarea");
    ta.value = promptContent;
    ta.focus();
    // Auto-select associated MCPs
    if (skill.tools) {
      for (const toolName of skill.tools) {
        if (!state.selectedMcps.includes(toolName)) {
          state.selectedMcps.push(toolName);
        }
      }
      mcp._savePersisted();
    }
    // 立即发送
    if (typeof chat !== "undefined" && chat.send) {
      chat.send();
    }
  },

  /** "复制" 按钮：覆盖 textarea 内容（不发送，不追加） */
  copyToTextarea(skill, skillParams) {
    const promptContent = this._buildPrompt(skill, skillParams);
    this.closeModal();
    const ta = document.getElementById("textarea");
    ta.value = promptContent;
    ta.focus();
    // Scroll cursor to end
    ta.setSelectionRange(ta.value.length, ta.value.length);
    // Auto-select associated MCPs
    if (skill.tools) {
      for (const toolName of skill.tools) {
        if (!state.selectedMcps.includes(toolName)) {
          state.selectedMcps.push(toolName);
        }
      }
      mcp._savePersisted();
    }
  },

  _buildPrompt(skill, skillParams) {
    let promptContent = skill.content;
    if (skill.params && skill.params.length > 0) {
      for (const param of skill.params) {
        const regex = new RegExp(`\\{${param.name}\\}`, "g");
        promptContent = promptContent.replace(
          regex,
          skillParams[param.name] || "",
        );
      }
    }
    return promptContent;
  },

  async loadList() {
    const data = await api.listSkills();
    // Button always visible; just store the result for modal rendering
  },
};

// ===================== §11 Responsive =====================
const responsive = {
  handleResize() {
    const sidebar = document.getElementById("sidebar");
    const toggle = document.getElementById("sidebar-toggle");
    if (window.innerWidth < 768) {
      toggle.style.display = "flex";
      sidebar.classList.remove("open");
      toggle.textContent = "☰";
    } else if (window.innerWidth < 1200) {
      toggle.style.display = "none";
      sidebar.classList.remove("open");
    } else {
      toggle.style.display = "none";
    }
  },
};

// ===================== §15 File Upload Module =====================
const imageUpload = {
  // Supported MIME types: images + common documents
  ALLOWED_TYPES: [
    // images
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
    "image/bmp",
    // documents
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/csv",
    "text/markdown",
  ],
  MAX_SIZE: 10 * 1024 * 1024, // 10MB

  // Map file extensions to document icon SVG (for non-image types)
  DOC_ICONS: {
    ".pdf": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#ef4444" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PDF</text></svg>`,
    ".doc": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#3b82f6" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">DOC</text></svg>`,
    ".docx": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#3b82f6" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">DOC</text></svg>`,
    ".xls": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#10b981" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">XLS</text></svg>`,
    ".xlsx": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#10b981" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">XLS</text></svg>`,
    ".ppt": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#f59e0b" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PPT</text></svg>`,
    ".pptx": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#f59e0b" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PPT</text></svg>`,
    ".txt": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">TXT</text></svg>`,
    ".csv": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">CSV</text></svg>`,
    ".md": `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="10" font-weight="700" font-family="Inter,sans-serif">MD</text></svg>`,
  },

  /** Get icon SVG for a given file extension, or null if it's an image */
  getDocIcon(file) {
    const ext = "." + file.name.split(".").pop().toLowerCase();
    if (file.type.startsWith("image/")) return null; // image → show thumbnail
    return this.DOC_ICONS[ext] || this.DOC_ICONS[".txt"] || null;
  },

  /** Check if a file is an image */
  isImage(file) {
    return file.type.startsWith("image/");
  },

  init() {
    const addBtn = document.getElementById("image-add-btn");
    const fileInput = document.getElementById("image-file-input");

    addBtn.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", (e) => this.handleFiles(e));
  },

  validate(file) {
    if (!this.ALLOWED_TYPES.includes(file.type)) {
      return {
        valid: false,
        error: "不支持的文件格式，请选择图片或常见文档格式",
      };
    }
    if (file.size > this.MAX_SIZE) {
      return { valid: false, error: "文件大小不能超过 10MB" };
    }
    return { valid: true };
  },

  async handleFiles(event) {
    const input = event.target;
    const files = Array.from(input.files || []);
    if (files.length === 0) return;

    // Reset file input so same file can be selected again
    input.value = "";

    for (const file of files) {
      const validation = this.validate(file);
      if (!validation.valid) {
        showToast(validation.error, "error");
        continue;
      }

      // Defensive double-check: if the browser silently dropped the
      // type (some file pickers filter even without `accept`),
      // surface an honest error rather than a silent no-op.
      if (file.size === 0 && file.name && !file.type) {
        showToast(`文件「${file.name}」无法识别，已忽略`, "error");
        continue;
      }

      const isImage = this.isImage(file);
      const objectUrl = isImage ? URL.createObjectURL(file) : null;
      const docIcon = isImage ? null : this.getDocIcon(file);
      const tempId = this.renderThumbnail(null, objectUrl, file.name, docIcon);

      // Upload to server
      try {
        const { fileId } = await api.uploadImage(file);
        const entry = state.pendingImages.find((img) => img.thumbId === tempId);
        if (entry) {
          entry.fileId = fileId;
        }
        this.updateThumbnailFileId(tempId, fileId);
      } catch (err) {
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        this.removeImageByObjectUrl(objectUrl || tempId);
        showToast("文件上传失败：" + err.message, "error");
      }
    }
  },

  renderThumbnail(fileId, objectUrl, fileName, docIcon) {
    const uploadArea = document.getElementById("image-upload-area");
    const container = document.getElementById("image-thumbnails");

    uploadArea.style.display = "block";

    const thumbId =
      "thumb-" + Date.now() + "-" + Math.random().toString(36).slice(2, 6);
    const div = document.createElement("div");
    div.className = "image-thumbnail" + (docIcon ? " has-doc-icon" : "");
    div.id = thumbId;

    // Document icon or image
    const safeName = escapeHtml(fileName || "");
    let contentHtml;
    if (docIcon) {
      contentHtml = `<div class="doc-icon-container">${docIcon}</div>
 <div class="doc-filename" title="${safeName}">${safeName}</div>`;
    } else {
      contentHtml = `<img src="${objectUrl}" alt="${safeName}">
 <div class="thumbnail-loading"><div class="spinner"></div></div>`;
    }

    div.innerHTML = `
 ${contentHtml}
 <button class="thumbnail-remove" title="移除文件">&times;</button>`;

    // Double-click to view fullscreen (images only)
    if (objectUrl) {
      div.addEventListener("dblclick", () => {
        this.showFullscreen(objectUrl);
      });
    }

    // Remove button
    div.querySelector(".thumbnail-remove").addEventListener("click", (e) => {
      e.stopPropagation();
      this.removeImageById(thumbId, objectUrl || thumbId);
    });

    container.appendChild(div);

    // Track in state — for documents, use thumbId as the unique key
    state.pendingImages.push({ fileId, objectUrl, fileName, thumbId });

    return thumbId;
  },

  updateThumbnailFileId(thumbId, fileId) {
    const thumb = document.getElementById(thumbId);
    if (!thumb) return;
    const loading = thumb.querySelector(".thumbnail-loading");
    if (loading) loading.style.display = "none";
  },

  removeImageById(thumbId, uniqueKey) {
    // Remove from state — match by objectUrl or thumbId
    state.pendingImages = state.pendingImages.filter((img) => {
      if (img.objectUrl) return img.objectUrl !== uniqueKey;
      return img.thumbId !== uniqueKey;
    });
    if (uniqueKey.startsWith("data:") || uniqueKey.startsWith("blob:")) {
      URL.revokeObjectURL(uniqueKey);
    }

    // Remove from DOM
    const thumb = document.getElementById(thumbId);
    if (thumb) thumb.remove();

    // Hide area if no images left
    if (state.pendingImages.length === 0) {
      document.getElementById("image-upload-area").style.display = "none";
    }
  },

  removeImageByObjectUrl(uniqueKey) {
    // For documents (no objectUrl), match by thumbId
    if (
      !uniqueKey ||
      (!uniqueKey.startsWith("blob:") && !uniqueKey.startsWith("data:"))
    ) {
      const entry = state.pendingImages.find(
        (img) => img.thumbId === uniqueKey,
      );
      if (entry) this.removeImageById(entry.thumbId, uniqueKey);
      return;
    }
    // For images, match by objectUrl
    const entry = state.pendingImages.find(
      (img) => img.objectUrl === uniqueKey,
    );
    if (!entry) return;
    // Find DOM element by img src
    const container = document.getElementById("image-thumbnails");
    for (const thumb of container.querySelectorAll(".image-thumbnail")) {
      const img = thumb.querySelector("img");
      if (img && img.src === uniqueKey) {
        this.removeImageById(thumb.id, uniqueKey);
        return;
      }
    }
  },

  remove() {
    // Clear all — revoke only image object URLs
    for (const img of state.pendingImages) {
      if (img.objectUrl) URL.revokeObjectURL(img.objectUrl);
    }
    state.pendingImages = [];
    document.getElementById("image-thumbnails").innerHTML = "";
    document.getElementById("image-upload-area").style.display = "none";
  },

  clear() {
    this.remove();
  },

  showFullscreen(objectUrl) {
    const overlay = document.getElementById("image-viewer-overlay");
    const image = document.getElementById("viewer-image");
    image.src = objectUrl;
    overlay.style.display = "flex";
  },

  hideFullscreen() {
    const overlay = document.getElementById("image-viewer-overlay");
    const image = document.getElementById("viewer-image");
    overlay.style.display = "none";
    image.src = "";
  },
};

// ===================== §14 Initialization =====================
const init = async () => {
  ui.init();
  dialog.init();

  // ── Bind all events FIRST so handlers (send-btn, chips, file-drag, etc.)
  // are guaranteed active regardless of how slow any background load is.
  // Otherwise a hung /mcps or /conversation await keeps the page in a
  // "looks fresh but nothing clicks" state. (BUG-7)
  bindAllEvents();

  // Auth check — auto-login via cookie-based session (BFF pattern)
  const loggedIn = await auth.init();
  if (!loggedIn) return; // not logged in; auth.init already redirected

  // Each "background" load is wrapped so a single failure does NOT abort
  // the rest of init. Without these try/catch, a flaky /mcps or /conversation
  // endpoint could leave the page in a state where event listeners never
  // bind — making the entire UI look "fresh" but non-functional.
  // (auto-test round-01 / .temp/bugs/round-01-bug-02.md)
  try {
    await mcp.loadList();
  } catch (e) {
    console.warn("[init] mcp.loadList failed, continuing:", e);
  }

  try {
    const uploadOk = await api.checkKnowledgeUpload();
    if (uploadOk) {
      const ib = document.getElementById("image-add-btn");
      if (ib) ib.style.display = "flex";
    }
  } catch (e) {
    console.warn("[init] checkKnowledgeUpload failed, continuing:", e);
  }

  try {
    await conversation.loadList();
  } catch (e) {
    console.warn("[init] conversation.loadList failed, continuing:", e);
  }

  // Keep the initial canvas empty so users explicitly create the first persisted conversation.
  // chat.send() below provides a defensive fallback for Enter/send calls without a current id.
};

// ===================== §X Chat Selected Skill Tag =====================
// 管理输入框上方的 [name ×] 标签条。state.selectedSkill = {name, description} 时显示。
const selectedSkillTag = {
  set(skill) {
    state.selectedSkill = skill;
    this.update();
  },
  clear() {
    state.selectedSkill = null;
    this.update();
  },
  update() {
    const el = document.getElementById("chat-selected-skill");
    if (!el) return;
    const nameEl = document.getElementById("chat-selected-skill-name");
    if (state.selectedSkill) {
      if (nameEl) nameEl.textContent = state.selectedSkill.name;
      el.classList.remove("hidden");
    } else {
      el.classList.add("hidden");
    }
  },
  /** 在 chat.send() 成功回调中调用 */
  onSendSuccess() {
    this.clear();
  },
  /** 切换对话时调用，避免跨会话污染 */
  onConversationSwitch() {
    this.clear();
  },
};

// ===================== §Y Slash Picker =====================
// 输入框输入 '/' 触发；浮层显示用户可访问的所有 Skill，键盘上下/Enter/Esc 操作。
const slashPicker = {
  state: { open: false, query: "", items: [], activeIndex: 0 },

  async open(query) {
    if (!this._el) this._el = document.getElementById("slash-picker");
    if (!this._el) return;
    // 一次拉取全部 Skill（含 load=false）。admin 特权由后端处理。
    let all = state._skillListCache;
    if (!all) {
      try {
        all = await api.listSkills();
        state._skillListCache = all || [];
      } catch (_) {
        all = [];
      }
    }
    this.state.query = query || "";
    this.state.items = this._filter(all, this.state.query);
    this.state.activeIndex = 0;
    this.state.open = true;
    this._render();
  },

  close() {
    this.state.open = false;
    if (this._el) this._el.classList.add("hidden");
  },

  setQuery(q) {
    this.state.query = q;
    const all = state._skillListCache || [];
    this.state.items = this._filter(all, q);
    this.state.activeIndex = 0;
    this._render();
  },

  move(delta) {
    if (!this.state.open) return;
    const n = this.state.items.length;
    if (n === 0) return;
    this.state.activeIndex = (this.state.activeIndex + delta + n) % n;
    this._render();
  },

  confirm() {
    if (!this.state.open) return null;
    const item = this.state.items[this.state.activeIndex];
    this.close();
    if (!item) return null;
    // 从 textarea 中去掉 '/query' 文本
    const ta = document.getElementById("textarea");
    if (ta) {
      const v = ta.value;
      const idx = v.lastIndexOf("/");
      if (idx >= 0) ta.value = v.substring(0, idx);
    }
    return item;
  },

  _filter(list, query) {
    const q = (query || "").toLowerCase();
    return list
      .filter(
        (s) =>
          !q ||
          s.name.toLowerCase().includes(q) ||
          (s.description || "").toLowerCase().includes(q),
      )
      .slice(0, 50);
  },

  _render() {
    if (!this._el) return;
    if (!this.state.open) {
      this._el.classList.add("hidden");
      return;
    }
    const items = this.state.items;
    if (items.length === 0) {
      this._el.innerHTML =
        '<div class="slash-picker-item"><span class="slash-picker-item-name">无匹配 Skill</span></div>';
      this._el.classList.remove("hidden");
      return;
    }
    this._el.innerHTML = items
      .map(
        (s, i) => `
 <div class="slash-picker-item ${i === this.state.activeIndex ? "active" : ""}" data-idx="${i}">
 <span class="slash-picker-item-name">${escapeHtml(s.name)}</span>
 <span class="slash-picker-item-desc">${escapeHtml(s.description || "")}</span>
 </div>
 `,
      )
      .join("");
    this._el.classList.remove("hidden");

    // 事件委托：mousedown 时记录 activeIndex，click 时直接 confirm。
    // 关键：mouseenter 不再触发 _render()，避免 innerHTML 替换导致
    // mousedown 后元素被销毁、click 落到新元素上的 bug。
    if (!this._listenersBound) {
      this._el.addEventListener("mousemove", (e) => {
        const item = e.target.closest(".slash-picker-item");
        if (!item) return;
        const idx = Number(item.dataset.idx);
        if (Number.isFinite(idx) && idx !== this.state.activeIndex) {
          this.state.activeIndex = idx;
          this._updateActiveClass();
        }
      });
      this._el.addEventListener("click", (e) => {
        const item = e.target.closest(".slash-picker-item");
        if (!item) return;
        const idx = Number(item.dataset.idx);
        if (!Number.isFinite(idx)) return;
        this.state.activeIndex = idx;
        const picked = this.confirm();
        if (picked)
          selectedSkillTag.set({
            name: picked.name,
            description: picked.description,
          });
      });
      this._listenersBound = true;
    }

    // 键盘上下选中后，把 active 元素滚到可见区域
    this._scrollActiveIntoView();
  },

  /** 仅切换 .active class，不重新渲染 HTML（保留 mousedown 目标） */
  _updateActiveClass() {
    if (!this._el) return;
    const children = this._el.querySelectorAll(".slash-picker-item");
    children.forEach((el, i) => {
      el.classList.toggle("active", i === this.state.activeIndex);
    });
    this._scrollActiveIntoView();
  },

  /** 把当前 active 元素滚到可视区域，避免键盘导航选到屏外 */
  _scrollActiveIntoView() {
    if (!this._el) return;
    const active = this._el.querySelector(".slash-picker-item.active");
    if (active && typeof active.scrollIntoView === "function") {
      // block: 'nearest' 表示"只在元素真正不可见时才滚动"，不破坏当前位置
      active.scrollIntoView({ block: "nearest" });
    }
  },
};

const bindAllEvents = () => {
  const safeBind = (sel, type, handler) => {
    const e = typeof sel === "string" ? document.querySelector(sel) : sel;
    if (e && handler) e.addEventListener(type, handler);
  };
  const safeBindById = (id, type, handler) => {
    if (id) safeBind(document.getElementById(id), type, handler);
  };

  const ta = document.getElementById("textarea");
  safeBind(ta, "keydown", (event) => {
    // Backspace + textarea 为空 + 有 selectedSkill → 清空
    if (event.key === "Backspace" && ta.value === "" && state.selectedSkill) {
      event.preventDefault();
      selectedSkillTag.clear();
      return;
    }
    // slash picker 打开：键盘控制
    if (slashPicker.state.open) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        slashPicker.move(1);
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        slashPicker.move(-1);
        return;
      }
      if (event.key === "Enter") {
        event.preventDefault();
        const item = slashPicker.confirm();
        if (item)
          selectedSkillTag.set({
            name: item.name,
            description: item.description,
          });
        return;
      }
      if (event.key === "Tab") {
        event.preventDefault();
        const item = slashPicker.confirm();
        if (item)
          selectedSkillTag.set({
            name: item.name,
            description: item.description,
          });
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        slashPicker.close();
        return;
      }
    }
    // '/' 触发 picker：必须是刚输入 / 且光标前是 /
    if (event.key === "/" && !slashPicker.state.open) {
      const v = ta.value;
      const start = ta.selectionStart;
      // 允许开头 / 或空白后 /
      const prev = start > 0 ? v[start - 1] : "";
      if (start === 0 || /\s/.test(prev)) {
        event.preventDefault();
        // 插入 / 让用户看到，然后开 picker
        ta.value = v.substring(0, start) + "/" + v.substring(start);
        ta.setSelectionRange(start + 1, start + 1);
        slashPicker.open("");
      }
    }
    if (event.key === "Enter" && event.ctrlKey) {
      event.preventDefault();
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      ta.value = ta.value.substring(0, start) + "\n" + ta.value.substring(end);
      ta.setSelectionRange(start + 1, start + 1);
    }
    if (event.key === "Enter" && !event.shiftKey && !event.ctrlKey) {
      event.preventDefault();
      chat.send();
    }
  });

  safeBind(ta, "input", () => {
    if (!slashPicker.state.open) return;
    const v = ta.value;
    const idx = v.lastIndexOf("/");
    if (idx < 0) {
      slashPicker.close();
      return;
    }
    // / 之后不能有空格（避免被 Enter 触发发送）
    const after = v.substring(idx + 1);
    if (/\s/.test(after)) {
      slashPicker.close();
      return;
    }
    slashPicker.setQuery(after);
  });

  safeBindById("send-btn", "click", () => chat.send());
  safeBindById("stop-btn", "click", () => chat.stopStream());

  safeBindById("chat-selected-skill-clear", "click", () =>
    selectedSkillTag.clear(),
  );

  // Modal overlay click-to-close
  safeBindById("mcp-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) mcp.closeModal();
  });
  safeBindById("skills-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) skills.closeModal();
  });
  safeBindById("ks-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) knowledge.closePanel();
  });
  safeBindById("file-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) fileMgr.closeModal();
  });
  safeBindById("subtask-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) subtaskPanel.closeModal();
  });
  safeBindById("schedule-modal-overlay", "click", (e) => {
    if (e.target === e.currentTarget) schedulePanel.closeModal();
  });

  // Image upload
  if (typeof imageUpload.init === "function") imageUpload.init();

  safeBindById("image-viewer-overlay", "click", (e) => {
    if (e.target === e.currentTarget) imageUpload.hideFullscreen();
  });
  safeBindById("viewer-close", "click", () => imageUpload.hideFullscreen());
  safeBind(document, "keydown", (e) => {
    if (e.key === "Escape") imageUpload.hideFullscreen();
  });

  // Responsive
  responsive.handleResize();
  window.addEventListener("resize", responsive.handleResize);

  // Top-level buttons (replace inline onclick handlers from ES module scope)
  const addIf = (sel, handler) => {
    const e = document.querySelector(sel);
    if (e) e.addEventListener("click", handler);
  };
  addIf("#new-chat-btn", () => conversation.createNew());
  addIf("#sidebar-toggle", () => ui.toggleSidebar());
  addIf("#ks-button", () => knowledge.openPanel());
  addIf("#mcp-button", () => mcp.openModal());
  addIf("#skills-button", () => skills.openModal());
  addIf("#file-manager-button", () => fileMgr.openModal());
  addIf("#file-close-btn", () => fileMgr.closeModal());
  addIf("#subtask-button", () => subtaskPanel.openModal());
  addIf("#subtask-close-btn", () => subtaskPanel.closeModal());
  addIf("#schedule-button", () => schedulePanel.openModal());
  addIf("#schedule-close-btn", () => schedulePanel.closeModal());
  addIf("#mcp-close-btn", () => mcp.closeModal());
  addIf("#skills-close-btn", () => skills.closeModal());
  addIf("#skill-add-btn", () => skills.showCreateForm());
  safeBindById("skill-import-btn", "click", () => skills.handleImport());
  safeBindById("skill-import-file-input", "change", (e) => {
    const f = e.target.files && e.target.files[0];
    if (f) skills._doImportFile(f);
    e.target.value = "";
  });
  addIf(".ks-create-btn", () => knowledge.create());
  addIf("#ks-modal-overlay .close-button", () => knowledge.closePanel());
};

// Init trigger — type="module" scripts are deferred, so by the time this
// module evaluates, DOMContentLoaded may have ALREADY FIRED. In that case the
// listener below would never trigger and the page would be a static shell.
// Guard with document.readyState.
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}

// Expose to global for testing/debugging
window._loomAgent = {
  state,
  api,
  imageUpload,
  auth,
  chat,
  conversation,
  ui,
  fileMgr,
};
window.ui = ui;

// ===================== §11 File Download/Preview Globals =====================
window.previewFile = (fileId) => {
  window.open(`/spring/ai/loom/api/file/${fileId}/preview`, "_blank");
};

window.downloadFile = (fileId) => {
  const link = document.createElement("a");
  link.href = `/spring/ai/loom/api/file/${fileId}/download`;
  link.download = "";
  link.click();
};
