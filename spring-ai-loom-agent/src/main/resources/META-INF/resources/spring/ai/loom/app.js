/**
 * app.js — Spring AI LoomAgent Frontend
 * 14-partition modular architecture.
 * §4 API service layer = only fetch() calls.
 * §12 UI components = only DOM manipulation.
 * §2 Global state = only state writes.
 */

import { sanitizeHtml } from './markdown-renderer.js';

// ===================== §1 Constants & Configuration =====================
const API_PREFIX = '';
const API = {
    autoLogin: '/spring/ai/loom/user/isAutoLogin',
    login: '/spring/ai/loom/user/login',
    logout: '/spring/ai/loom/user/logout',
    currentUser: '/spring/ai/loom/user/currentUser',
    currentIsAdmin: '/spring/ai/loom/user/currentIsAdmin',
    changePassword: '/spring/ai/loom/user/changePassword',
    listUsers: '/spring/ai/loom/admin/users',
    createUser: '/spring/ai/loom/admin/users',
    deleteUser: (username) => `/spring/ai/loom/admin/users/${encodeURIComponent(username)}`,
    listConversations: '/spring/ai/loom/conversation',
    createConversation: '/spring/ai/loom/user-conversations',
    renameConversation: (id) => `/spring/ai/loom/user-conversations/${id}`,
    getConversation: (id) => `/spring/ai/loom/conversation/${id}`,
    deleteConversation: (id) => `/spring/ai/loom/conversation/${id}`,
    stream: '/spring/ai/loom/stream',
    listMcps: '/spring/ai/loom/mcps',
    mcpTools: (name) => `/spring/ai/loom/mcps/${encodeURIComponent(name)}/tools`,
    listSkills: '/spring/ai/loom/skill',
    getSkill: (name) => `/spring/ai/loom/skill/${name}`,
    createSkill: '/spring/ai/loom/skill',
    updateSkill: '/spring/ai/loom/skill',
    patchSkill: (name) => `/spring/ai/loom/skill/${name}`,
    deleteSkill: (name) => `/spring/ai/loom/skill/${name}`,
    listMarketSkills: '/spring/ai/loom/market-skills',
    pullMarketSkill: (id) => `/spring/ai/loom/market-skills/${id}/pull`,
    submitMarketSkill: '/spring/ai/loom/user/market-skills',
    listMySubmittedSkills: '/spring/ai/loom/user/market-skills',
    withdrawMarketSkill: (id) => `/spring/ai/loom/user/market-skills/${id}`,
    listKnowledge: '/spring/ai/loom/knowledge',
    createKnowledge: '/spring/ai/loom/knowledge',
    deleteKnowledge: (id) => `/spring/ai/loom/knowledge/${id}`,
    updateKnowledge: (id) => `/spring/ai/loom/knowledge/${id}`,
    uploadToKnowledge: (id) => `/spring/ai/loom/knowledge/${id}/upload`,
    listKnowledgeFiles: (id) => `/spring/ai/loom/knowledge/${id}/file`,
    deleteKnowledgeFile: (knowledgeId, fileId) => `/spring/ai/loom/knowledge/${knowledgeId}/file/${fileId}`,
    uploadFile: '/spring/ai/loom/file/upload',
    checkKnowledgeUpload: '/spring/ai/loom/knowledge/checkKnowledgeUpload',
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
    selectedMcps: [],
    enabledKnowledgeIds: [],
    selectedSkill: null,
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
    let buffer = '';
    return {
        feed(chunk) {
            buffer += chunk;
            const lines = buffer.split(/\r?\n/);
            buffer = lines.pop(); // keep incomplete last line
            let data = '';
            for (const line of lines) {
                if (line.startsWith('data:')) {
                    data += line.slice(5).replace(/^\s/, '');
                } else if (line === '' && data) {
                    handlers.onEvent?.({data});
                    data = '';
                }
            }
        }
    };
}

function showToast(message, type = 'success') {
    const toast = document.getElementById('toast-notification');
    toast.textContent = message;
    toast.className = 'show ' + type;
    setTimeout(() => { toast.className = toast.className.replace('show', ''); }, 3000);
}

/** Wrapper for fetch that clears state on 401 */
async function apiFetch(url, options = {}) {
    let resp;
    try {
        resp = await fetch(url, options);
    } catch (e) {
        if (e && e.name === 'AbortError') throw e;
        // 网络错误：返回一个合成的 Response，让上层走 r.ok === false 分支
        console.warn('[apiFetch] network error for', url, e);
        return new Response(null, { status: 599, statusText: e.message || 'network error' });
    }
    if (resp.status === 401 && state.username) {
        // Session expired or invalidated — clear client-side state
        auth.clear();
    }
    return resp;
}

/** Build a unique-by-moment default title for newly-created conversations.
 *  e.g. "新对话 7-24 14:32" — two conversations opened in the same minute are
 *  still distinguishable in the sidebar even before any message is sent. */
function generateDefaultConversationTitle() {
    const d = new Date();
    const M = d.getMonth() + 1;
    const D = d.getDate();
    const h = d.getHours();
    const m = String(d.getMinutes()).padStart(2, '0');
    return `新对话 ${M}-${D} ${h}:${m}`;
}

/** Detect a still-default placeholder title (used by chat.send() to decide
 *  whether to auto-rename from the first user message). Matches both the bare
 *  legacy "新对话" and the timestamped variant produced by
 *  generateDefaultConversationTitle. */
function looksLikeDefaultConversationTitle(t) {
    if (!t) return true;
    const s = String(t).trim();
    if (s === '新对话') return true;
    return /^新对话\s+\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2}$/.test(s);
}

/** Derive an auto title from the first user message. Collapses whitespace,
 *  then — when the message is longer than 12 chars — breaks at the last
 *  whitespace within the first 12 so we don't leave a half-word like "bu"
 *  dangling at the end. Falls back to a hard slice for CJK-only text
 *  (no spaces to anchor on). Returns "新对话" for empty input. */
function deriveAutoTitleFromMessage(text) {
    const flat = String(text || '').replace(/\s+/g, ' ').trim();
    if (!flat) return '新对话';
    if (flat.length <= 12) return flat;
    const head = flat.slice(0, 12);
    const lastSpace = head.lastIndexOf(' ');
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
        this._overlay = document.getElementById('confirm-modal-overlay');
        if (!this._overlay) return;
        this._titleEl = document.getElementById('confirm-modal-title');
        this._msgEl = document.getElementById('confirm-modal-message');
        this._formEl = document.getElementById('confirm-modal-form');
        this._inputEl = document.getElementById('confirm-modal-input');
        this._okBtn = document.getElementById('confirm-modal-ok');
        this._cancelBtn = document.getElementById('confirm-modal-cancel');
        this._closeBtn = document.getElementById('confirm-modal-close');

        const hide = () => this._hide();
        this._cancelBtn.addEventListener('click', hide);
        this._closeBtn.addEventListener('click', hide);
        this._overlay.addEventListener('click', (e) => { if (e.target === this._overlay) hide(); });
        // ESC to close
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && this._overlay.style.display !== 'none') hide();
        });
        this._inputEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); this._okBtn.click(); }
        });
    },

    _show({ title, message, okText = '确定', cancelText = '取消', danger = false, withInput = false, placeholder = '', defaultValue = '' }) {
        this._titleEl.textContent = title || '确认';
        this._msgEl.textContent = message || '';
        this._formEl.style.display = withInput ? 'block' : 'none';
        if (withInput) {
            this._inputEl.placeholder = placeholder;
            this._inputEl.value = defaultValue;
        }
        this._okBtn.textContent = okText;
        this._cancelBtn.textContent = cancelText;
        this._okBtn.style.background = danger ? 'var(--error-color, #ef4444)' : 'var(--primary-color)';
        this._okBtn.style.borderColor = danger ? 'var(--error-color, #ef4444)' : 'var(--primary-color)';
        this._overlay.style.display = 'flex';
        if (withInput) setTimeout(() => this._inputEl.focus(), 0);
    },

    _hide() {
        this._overlay.style.display = 'none';
    },

    confirm(opts) {
        return new Promise((resolve) => {
            this._show({ ...opts, withInput: false });
            const onOk = () => { this._cleanup(onOk, onCancel); this._hide(); resolve(true); };
            const onCancel = () => { this._cleanup(onOk, onCancel); this._hide(); resolve(false); };
            this._okBtn.addEventListener('click', onOk);
            this._cancelBtn.addEventListener('click', onCancel);
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
            const onCancel = () => { this._cleanup(onOk, onCancel); this._hide(); resolve(null); };
            this._okBtn.addEventListener('click', onOk);
            this._cancelBtn.addEventListener('click', onCancel);
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
        void onOk; void onCancel;
    },
};

function formatDate(dateString) {
    if (!dateString) return '未知';
    const date = new Date(dateString);
    return date.toLocaleDateString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit'
    });
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function truncateText(text, maxLength) {
    if (!text) return '';
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength) + '...';
}

/** Render Markdown with post-processing to make all links open in new tab, and LLM-output cleanup */
function renderMarkdown(text) {
    try {
        // Clean up common LLM output artifacts:
        // 1. Strip "url:" prefix from bare URLs so marked autolinks them
        text = text.replace(/url:(https?:\/\/[^\s\n]+)/g, '$1');
        // 2. Strip instruction lines about HTML <a> tags (not useful to the user)
        text = text.replace(/^使用HTML\s*<a>\s*标签.*$/gm, '').trim();
        // Parse markdown, sanitize all generated HTML, then post-process links to open in new tab
        const html = sanitizeHtml(marked.parse(text));
        return html.replace(/<a\s/g, '<a target="_blank" rel="noopener noreferrer" ');
    }
    catch { return escapeHtml(text); }
}

// ===================== §4 API Service Layer =====================
// All requests rely on HttpOnly session cookie for auth (BFF pattern).
// No Authorization header is sent from the client.
const api = {
    async autoLogin() {
        const r = await fetch(API.autoLogin, { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include' });
        if (r.status === 401) return false;
        return r.ok ? r.json() : false;
    },
    async login(req) {
        const r = await fetch(API.login, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify(req),
        });
        if (!r.ok) {
            let msg = `登录失败：HTTP ${r.status}`;
            try { const j = await r.json(); if (j.message) msg = j.message; } catch (_) {}
            throw new Error(msg);
        }
        return r.json();
    },
    async logout() {
        convStatePanel.stop();
        schedulePanel.closeModal();
        const r = await fetch(API.logout, { method: 'POST', credentials: 'include' });
        return r.ok;
    },
    async currentUser() {
        const r = await fetch(API.currentUser, { method: 'POST', credentials: 'include' });
        if (!r.ok) return null;
        return r.json();
    },
    async currentIsAdmin() {
        const r = await fetch(API.currentIsAdmin, { method: 'POST', credentials: 'include' });
        if (!r.ok) return false;
        return r.json();
    },
    async changePassword(oldPassword, newPassword) {
        const r = await fetch(API.changePassword, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({oldPassword, newPassword}),
        });
        if (!r.ok) {
            let msg = `修改失败：HTTP ${r.status}`;
            try { const j = await r.json(); if (j.message) msg = j.message; } catch (_) {}
            throw new Error(msg);
        }
        return true;
    },
    async listConversations() {
        const r = await apiFetch(API.listConversations);
        if (!r.ok) { try { await r.text(); } catch (_) {} return []; }
        try { return await r.json(); } catch (_) { return []; }
    },
    async createConversation(title = '新对话') {
        const r = await apiFetch(API.createConversation, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title }),
        });
        return r.ok ? r.json() : null;
    },
    async renameConversation(id, title) {
        const r = await apiFetch(API.renameConversation(id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title }),
        });
        if (r.status === 403) {
            // Cross-user rename rejection — surface a specific toast so the user
            // understands this is a permissions failure, not a generic network drop.
            return { ok: false, reason: 'forbidden' };
        }
        return { ok: r.ok };
    },
    async getConversationMessages(id) {
        const r = await apiFetch(API.getConversation(id));
        return r.ok ? r.json() : [];
    },
    async deleteConversation(id) {
        const r = await apiFetch(API.deleteConversation(id), {method: 'DELETE'});
        return r.ok;
    },
    async listMcps() {
        const r = await apiFetch(API.listMcps);
        if (!r.ok) {
            // Drain body to release the connection, then return empty
            try { await r.text(); } catch (_) {}
            return [];
        }
        try { return await r.json(); } catch (_) { return []; }
    },
    async listSkills() {
        const r = await apiFetch(API.listSkills);
        return r.ok ? r.json() : [];
    },
    async createSkill(skill) {
        const r = await apiFetch(API.createSkill, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(skill),
        });
        return r.ok ? r.json() : null;
    },
    async updateSkill(skill) {
        const r = await apiFetch(API.updateSkill, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(skill),
        });
        return r.ok ? r.json() : null;
    },
    async deleteSkill(name) {
        const r = await apiFetch(API.deleteSkill(name), {
            method: 'DELETE',
        });
        return r.ok ? r.json() : null;
    },
    async patchSkill(name, body) {
        const r = await apiFetch(API.patchSkill(name), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        return r.ok ? r.json() : null;
    },
    async listMarketSkills() {
        const r = await apiFetch(API.listMarketSkills);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    },
    async pullMarketSkill(id) {
        const r = await apiFetch(API.pullMarketSkill(id), {method: 'POST'});
        if (!r.ok) throw new Error((await r.text()) || 'HTTP ' + r.status);
        return r.json();
    },
    async submitMarketSkill(body) {
        const r = await apiFetch(API.submitMarketSkill, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!r.ok) throw new Error((await r.text()) || 'HTTP ' + r.status);
        return r.json();
    },
    async listMySubmittedSkills() {
        const r = await apiFetch(API.listMySubmittedSkills);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    },
    async withdrawMarketSkill(id) {
        const r = await apiFetch(API.withdrawMarketSkill(id), {method: 'DELETE'});
        if (!r.ok) throw new Error((await r.text()) || 'HTTP ' + r.status);
        return r.json();
    },
    async listKnowledge() {
        const r = await apiFetch(API.listKnowledge);
        return r.ok ? r.json() : [];
    },
    async createKnowledge(name, description) {
        const r = await apiFetch(API.createKnowledge, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description }),
        });
        return r.ok ? r.json() : null;
    },
    async deleteKnowledge(id) {
        const r = await apiFetch(API.deleteKnowledge(id), {method: 'DELETE'});
        return r.ok ? r.json() : null;
    },
    async updateKnowledge(id, name, description) {
        const r = await apiFetch(API.updateKnowledge(id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, description }),
        });
        if (r.ok) return { ok: true };
        const err = await r.json().catch(() => ({}));
        return { ok: false, message: err.message || '修改失败' };
    },
    async listKnowledgeFiles(id) {
        const r = await apiFetch(API.listKnowledgeFiles(id));
        return r.ok ? r.json() : [];
    },
    async uploadToKnowledge(id, file) {
        const fd = new FormData();
        fd.append('file', file);
        const r = await apiFetch(API.uploadToKnowledge(id), {method: 'POST', body: fd});
        return r.ok ? r.json() : null;
    },
    async deleteKnowledgeFile(knowledgeId, fileId) {
        const r = await apiFetch(API.deleteKnowledgeFile(knowledgeId, fileId), {
            method: 'DELETE',
        });
        return r.ok ? r.json() : null;
    },
    async uploadFile(file) {
        const fd = new FormData();
        fd.append('file', file);
        const r = await apiFetch(API.uploadFile, {method: 'POST', body: fd});
        return r.ok ? r.json() : null;
    },
    async uploadImage(file) {
        const data = await api.uploadFile(file);
        if (data && data.fileId) {
            return { fileId: data.fileId, status: data.status };
        }
        throw new Error('上传失败：未返回 fileId');
    },
    async checkKnowledgeUpload() {
        const r = await apiFetch(API.checkKnowledgeUpload);
        return r.ok;
    },
    async listFileTree() {
        const r = await apiFetch('/spring/ai/loom/file/tree');
        return r.ok ? r.json() : { name: '.', type: 'directory', children: [] };
    },
    async listActiveSubtasks(conversationId) {
        const q = conversationId ? '?conversationId=' + encodeURIComponent(conversationId) : '';
        const r = await apiFetch('/spring/ai/loom/subtask/list/active' + q);
        return r.ok ? r.json() : [];
    },
    async listSubtaskHistory(conversationId) {
        const q = conversationId ? '?conversationId=' + encodeURIComponent(conversationId) : '';
        const r = await apiFetch('/spring/ai/loom/subtask/list/history' + q);
        return r.ok ? r.json() : [];
    },
    async subtaskLimits() {
        const r = await apiFetch('/spring/ai/loom/subtask/limits');
        return r.ok ? r.json() : {};
    },
    async killSubtask(id) {
        const r = await apiFetch('/spring/ai/loom/subtask/kill/' + encodeURIComponent(id), { method: 'POST' });
        return r.ok;
    },
    async deleteSubtaskHistory(id) {
        const r = await apiFetch('/spring/ai/loom/subtask/history/' + encodeURIComponent(id), { method: 'DELETE' });
        return r.ok;
    },
    async listSchedules(conversationId) {
        const q = conversationId ? '?conversationId=' + encodeURIComponent(conversationId) : '';
        const r = await apiFetch('/spring/ai/loom/schedule/list' + q);
        return r.ok ? r.json() : [];
    },
    async cancelSchedule(fullName) {
        const r = await apiFetch('/spring/ai/loom/schedule/cancel', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: fullName }),
        });
        return r.ok;
    },
    async scheduleHistory(fullName) {
        const r = await apiFetch('/spring/ai/loom/schedule/history/' + encodeURIComponent(fullName));
        return r.ok ? r.json() : [];
    },
    async scheduleByConversation(convId) {
        const r = await apiFetch('/spring/ai/loom/schedule/history/by-conversation/' + encodeURIComponent(convId));
        return r.ok ? r.json() : [];
    },
    async scheduleLimits() {
        const r = await apiFetch('/spring/ai/loom/schedule/limits');
        return r.ok ? r.json() : {};
    },
    async cancelAllSchedulesByConversation(convId) {
        const r = await apiFetch('/spring/ai/loom/schedule/by-conversation/' + encodeURIComponent(convId) + '/cancel-all', {
            method: 'POST',
        });
        return r.ok ? r.json() : { cancelled: 0 };
    },
    async conversationState(convId) {
        const r = await apiFetch('/spring/ai/loom/conversation/' + encodeURIComponent(convId) + '/state');
        return r.ok ? r.json() : null;
    },
    async streamChat(record, onChunk, onComplete, onError, signal) {
        const resp = await apiFetch(API.stream, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
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
            }
        });

        async function read() {
            const { done, value } = await reader.read();
            if (done) { onComplete(); return; }
            parser.feed(decoder.decode(value, { stream: true }));
            await read();
        }
        try {
            await read();
        } catch (e) {
            // Abort is a user-initiated stop, not an error — swallow it.
            if (e && e.name === 'AbortError') {
                try { reader.cancel(); } catch (_) {}
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
     *  1. 调 isAutoLogin 检查 session cookie
     *  2. 没登录就跳 login.html
     *  3. 登录了则拉取 currentUser 渲染右上角用户菜单
     */
    async init() {
        try {
            const loggedIn = await api.autoLogin();
            if (loggedIn !== true) {
                window.location.replace('/spring/ai/loom/login.html');
                return false;
            }
            const me = await api.currentUser();
            if (!me || !me.username) {
                window.location.replace('/spring/ai/loom/login.html');
                return false;
            }
            state.username = me.username;
            state.nickname = me.nickname;
            state.userType = me.type;
            this.renderUserMenu();
            return true;
        } catch (e) {
            window.location.replace('/spring/ai/loom/login.html');
            return false;
        }
    },

    /** 渲染右上角用户菜单（昵称 + 下拉） */
    renderUserMenu() {
        const container = document.getElementById('user-menu');
        if (!container) return;
        const isAdmin = state.userType === 'ADMIN';
        container.innerHTML = `
            <div class="user-menu-wrapper" id="user-menu-wrapper">
                <button class="user-menu-trigger" id="user-menu-trigger">
                    <span class="user-menu-avatar">${(state.nickname || state.username || '?').charAt(0).toUpperCase()}</span>
                    <span class="user-menu-name">${escapeHtml(state.nickname || state.username)}</span>
                    ${isAdmin ? '<span class="user-menu-badge">管理员</span>' : ''}
                    <span class="user-menu-caret">▾</span>
                </button>
                <div class="user-menu-dropdown" id="user-menu-dropdown" style="display: none;">
                    ${isAdmin ? '<a class="user-menu-item" href="/spring/ai/loom/admin/console.html">控制台</a>' : ''}
                    <a class="user-menu-item" id="user-menu-usage">我的用量</a>
                    <a class="user-menu-item" id="user-menu-change-password">修改密码</a>
                    <a class="user-menu-item user-menu-item-danger" id="user-menu-logout">登出</a>
                </div>
            </div>
        `;
        const trigger = document.getElementById('user-menu-trigger');
        const dropdown = document.getElementById('user-menu-dropdown');
        trigger.addEventListener('click', (e) => {
            e.stopPropagation();
            dropdown.style.display = dropdown.style.display === 'none' ? 'block' : 'none';
        });
        document.addEventListener('click', () => { dropdown.style.display = 'none'; });
        const changePwd = document.getElementById('user-menu-change-password');
        if (changePwd) {
            changePwd.addEventListener('click', (e) => {
                e.preventDefault();
                dropdown.style.display = 'none';
                this.showChangePasswordModal();
            });
        }
        const usage = document.getElementById('user-menu-usage');
        if (usage) {
            usage.addEventListener('click', (e) => {
                e.preventDefault();
                dropdown.style.display = 'none';
                this.showUsageModal();
            });
        }
        const logout = document.getElementById('user-menu-logout');
        if (logout) {
            logout.addEventListener('click', async (e) => {
                e.preventDefault();
                dropdown.style.display = 'none';
                await this.logout();
            });
        }
    },

    /** 显示修改密码模态框 */
    showChangePasswordModal() {
        let modal = document.getElementById('change-password-modal');
        if (!modal) {
            modal = document.createElement('div');
            modal.id = 'change-password-modal';
            modal.className = 'modal-overlay';
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
            modal.addEventListener('click', (e) => { if (e.target === modal) modal.style.display = 'none'; });
            document.body.appendChild(modal);
            document.getElementById('change-pwd-close').addEventListener('click', () => { modal.style.display = 'none'; });
            document.getElementById('change-pwd-cancel').addEventListener('click', () => { modal.style.display = 'none'; });
            document.getElementById('change-pwd-submit').addEventListener('click', () => this.submitChangePassword(modal));
        }
        // 重置
        document.getElementById('change-pwd-old').value = '';
        document.getElementById('change-pwd-new').value = '';
        document.getElementById('change-pwd-confirm').value = '';
        document.getElementById('change-pwd-error').style.display = 'none';
        modal.style.display = 'flex';
        setTimeout(() => document.getElementById('change-pwd-old')?.focus(), 50);
    },

    async submitChangePassword(modal) {
        const oldPwd = document.getElementById('change-pwd-old').value;
        const newPwd = document.getElementById('change-pwd-new').value;
        const confirmPwd = document.getElementById('change-pwd-confirm').value;
        const errEl = document.getElementById('change-pwd-error');
        errEl.style.display = 'none';
        if (!oldPwd || !newPwd) {
            errEl.textContent = '请填写所有字段';
            errEl.style.display = 'block';
            return;
        }
        if (newPwd.length < 6) {
            errEl.textContent = '新密码至少 6 位';
            errEl.style.display = 'block';
            return;
        }
        if (newPwd !== confirmPwd) {
            errEl.textContent = '两次输入的新密码不一致';
            errEl.style.display = 'block';
            return;
        }
        const submitBtn = document.getElementById('change-pwd-submit');
        submitBtn.disabled = true;
        try {
            await api.changePassword(oldPwd, newPwd);
            modal.style.display = 'none';
            showToast('密码修改成功', 'success');
        } catch (e) {
            errEl.textContent = e.message;
            errEl.style.display = 'block';
        } finally {
            submitBtn.disabled = false;
        }
    },

    /** 显示"我的用量"模态框（本月 token 用量） */
    async showUsageModal() {
        let modal = document.getElementById('usage-modal');
        if (!modal) {
            modal = document.createElement('div');
            modal.id = 'usage-modal';
            modal.className = 'modal-overlay';
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
            modal.addEventListener('click', (e) => { if (e.target === modal) modal.style.display = 'none'; });
            document.body.appendChild(modal);
            document.getElementById('usage-modal-close').addEventListener('click', () => { modal.style.display = 'none'; });
        }
        modal.style.display = 'flex';
        document.getElementById('usage-loading').style.display = 'block';
        document.getElementById('usage-content').style.display = 'none';
        try {
            const r = await fetch('/spring/ai/loom/user/tokens/current-month', {
                credentials: 'include',
            });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const data = await r.json();
            document.getElementById('usage-total').textContent = data.totalTokens.toLocaleString();
            document.getElementById('usage-calls').textContent = data.callCount.toLocaleString();
            document.getElementById('usage-prompt').textContent = data.promptTokens.toLocaleString();
            document.getElementById('usage-completion').textContent = data.completionTokens.toLocaleString();
            document.getElementById('usage-avg').textContent = Math.round(data.avgTokensPerCall).toLocaleString();
            document.getElementById('usage-loading').style.display = 'none';
            document.getElementById('usage-content').style.display = 'block';
        } catch (e) {
            document.getElementById('usage-loading').textContent = '加载失败：' + e.message;
        }
    },

    /** Clear auth state — called on 401. Redirect to login. */
    clear() {
        window.location.replace('/spring/ai/loom/login.html');
    },

    /** 登出：服务端失效 session + 清 cookie，跳 login.html */
    async logout() {
        try { await api.logout(); } catch { /* ignore */ }
        window.location.replace('/spring/ai/loom/login.html');
    },
};

// ===================== §12 UI Components (only DOM manipulation) =====================
const aiImage = '/static/ai.png';
const userImage = '/static/user.png';

const ui = {
    mainContent: null,

    init() {
        this.mainContent = document.getElementById('mainContent');
    },

    clearChat() {
        this.mainContent.innerHTML = `
            <div class="welcome-message">
                <h2>你好！我是你的 AI 助手</h2>
                <p>有什么我可以帮助你的吗？现在可以开始聊天了</p>
            </div>`;
    },

    renderUserMessage(text, attachments) {
        const item = document.createElement('div');
        item.className = 'chat-item chat-item-right';
        let attachHtml = '';
        if (attachments && attachments.length > 0) {
            // Render a compact strip of attached files so the user can see
            // what they actually sent (the AI gets fileIds on the wire, but
            // without this strip the user bubble would only show the text).
            attachHtml = '<div class="user-attachments">' + attachments.map(a => {
                if (a.objectUrl) {
                    return `<div class="user-attach-thumb" title="${escapeHtml(a.fileName)}">
                        <img src="${a.objectUrl}" alt="${escapeHtml(a.fileName)}"/>
                    </div>`;
                }
                // Document — reuse the doc-icon SVG the upload module already renders
                const ext = '.' + (a.fileName || '').split('.').pop().toLowerCase();
                const icons = (imageUpload && imageUpload.DOC_ICONS) || {};
                const svg = icons[ext] || icons['.txt'] || '';
                return `<div class="user-attach-doc" title="${escapeHtml(a.fileName)}">
                    <div class="user-attach-doc-icon">${svg}</div>
                    <div class="user-attach-doc-name">${escapeHtml(a.fileName)}</div>
                </div>`;
            }).join('') + '</div>';
        }
        const textHtml = text ? `<div style="margin: 16px">${renderMarkdown(text)}</div>` : '';
        item.innerHTML = `
            <div class="bubble">${attachHtml}${textHtml}</div>
            <div class="avatar"><img src="${userImage}" alt="用户"/></div>`;
        this.mainContent.appendChild(item);
        this.scrollToBottom();
    },

    renderBotMessage(id) {
        const item = document.createElement('div');
        item.className = 'chat-item chat-item-left';
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
            const content = msg.text || msg.content || msg.getContent?.() || msg.getText?.() || '';
            if (role === 'USER' || role === 'user') {
                this.renderUserMessage(content);
            } else if (role === 'ASSISTANT' || role === 'assistant' || role === 'MODEL') {
                const id = 'hist-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
                this.renderBotMessage(id);
                const el = document.getElementById(id);
                if (el) el.innerHTML = renderMarkdown(content);
                const origin = document.getElementById('origin-' + id);
                if (origin) origin.innerHTML = content;
                // Show actions for historical messages
                const actions = document.getElementById('actions-' + id);
                if (actions) actions.style.display = '';
            }
        }
    },

    scrollToBottom() {
        if (this.mainContent) this.mainContent.scrollTop = this.mainContent.scrollHeight;
    },

    toggleThinking(id) {
        const content = document.getElementById(`thinking-content-${id}`);
        const arrow = document.getElementById(`arrow-${id}`);
        if (!content || !arrow) return;
        content.classList.toggle('expanded');
        arrow.classList.toggle('expanded');
    },

    copyMarkdown(id) {
        const el = document.getElementById(id);
        if (!el) { showToast('消息未找到', 'error'); return; }
        const text = el.textContent;
        if (!text || !text.trim()) { showToast('没有可复制的内容', 'error'); return; }
        navigator.clipboard.writeText(text)
            .then(() => showToast('复制成功！', 'success'))
            .catch(() => showToast('复制失败，请手动复制', 'error'));
    },

    downloadMarkdown(id) {
        const el = document.getElementById(id);
        if (!el) { showToast('消息未找到', 'error'); return; }
        const content = el.textContent;
        if (!content || !content.trim()) { showToast('没有可下载的内容', 'error'); return; }
        const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, -5);
        link.download = `chat-${ts}.md`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
        showToast('下载成功！', 'success');
    },

    enableSend() {
        const ta = document.getElementById('textarea');
        const btn = document.getElementById('send-btn');
        ta.disabled = false;
        btn.disabled = false;
        btn.textContent = '发送消息';
        state.isStreaming = false;
        ui.setToolbarLocked(false);
        ui.setStopButtonVisible(false);
        convStatePanel.refresh();   // refresh after every stream completion
    },

    disableSend() {
        const ta = document.getElementById('textarea');
        const btn = document.getElementById('send-btn');
        ta.value = '';
        ta.disabled = true;
        btn.disabled = true;
        btn.textContent = '发送中...';
        state.isStreaming = true;
        ui.setToolbarLocked(true);
        ui.setStopButtonVisible(true);
    },

    setStopButtonVisible(visible) {
        const btn = document.getElementById('stop-btn');
        if (!btn) return;
        btn.style.display = visible ? 'inline-block' : 'none';
        btn.disabled = false;
        btn.textContent = '停止';
    },

    showModal(id) {
        document.getElementById(id).style.display = 'flex';
    },

    hideModal(id) {
        document.getElementById(id).style.display = 'none';
    },

    toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        const toggle = document.getElementById('sidebar-toggle');
        const isOpen = sidebar.classList.toggle('open');
        toggle.textContent = isOpen ? '✕' : '☰';
    },

    /** Lock/unlock the 4 toolbar buttons (knowledge / MCP / skills / file) and the + new chat button during streaming. */
    setToolbarLocked(locked) {
        const ids = ['ks-button', 'mcp-button', 'skills-button', 'file-manager-button', 'new-chat-btn'];
        for (const id of ids) {
            const btn = document.getElementById(id);
            if (!btn) continue;
            btn.disabled = locked;
            // Set inline styles directly to bypass CSS transition delay (so visual + click-block take effect immediately)
            if (locked) {
                btn.style.setProperty('opacity', '0.4', 'important');
                btn.style.setProperty('pointer-events', 'none', 'important');
                btn.style.setProperty('cursor', 'not-allowed', 'important');
                btn.setAttribute('aria-disabled', 'true');
                btn.title = '请等待 AI 回复完成';
            } else {
                btn.style.removeProperty('opacity');
                btn.style.removeProperty('pointer-events');
                btn.style.removeProperty('cursor');
                btn.removeAttribute('aria-disabled');
                btn.title = '';
            }
        }
        // Lock conversation history items (rendered dynamically, so use class on sidebar)
        const sidebar = document.getElementById('sidebarList');
        if (sidebar) {
            if (locked) {
                sidebar.classList.add('sidebar-locked');
            } else {
                sidebar.classList.remove('sidebar-locked');
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
            console.warn('[conversation.loadList] failed:', e);
            this.renderSidebar([]);
        }
    },

    renderSidebar(list) {
        const container = document.getElementById('sidebarList');
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="sidebar-empty">暂无对话</div>';
            return;
        }
        container.innerHTML = '';
        for (const item of list) {
            const id = item.conversationId || item.id;
            const title = item.title || truncateText(item.name || '新对话', API.titleMaxLength);
            // Keep state.conversationTitle in sync for the currently-active conversation
            // so chat.send() can decide whether to auto-rename from the first message.
            if (id === state.conversationId) {
                state.conversationTitle = item.title || item.name || '新对话';
            }
            const div = document.createElement('div');
            div.className = 'sidebar-item' + (state.conversationId === id ? ' active' : '');
            div.dataset.conversationId = id;

            const text = document.createElement('span');
            text.className = 'sidebar-item-text';
            text.title = title;
            text.textContent = title;

            const actions = document.createElement('span');
            actions.className = 'sidebar-item-actions';
            const renameBtn = document.createElement('button');
            renameBtn.className = 'sidebar-item-rename';
            renameBtn.title = '重命名对话';
            renameBtn.setAttribute('aria-label', '重命名对话');
            renameBtn.textContent = '✎';
            const deleteBtn = document.createElement('button');
            deleteBtn.className = 'sidebar-item-delete';
            deleteBtn.title = '删除对话';
            deleteBtn.setAttribute('aria-label', '删除对话');
            deleteBtn.innerHTML = '&times;';
            actions.append(renameBtn, deleteBtn);
            div.append(text, actions);

            div.addEventListener('click', (e) => {
                if (e.target.closest('.sidebar-item-actions') || e.target.classList.contains('sidebar-item-edit')) return;
                this.switchTo(id);
            });
            renameBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.startRename(div, id, title);
            });
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.delete(id);
            });
            container.appendChild(div);
        }
    },

    async createNew() {
        if (state.isStreaming) {
            showToast('请等待 AI 回复完成', 'warning');
            return;
        }
        const defaultTitle = generateDefaultConversationTitle();
        const created = await api.createConversation(defaultTitle);
        if (!created) {
            showToast('新建对话失败', 'error');
            return;
        }
        state.conversationId = created.conversationId || created.id;
        state.conversationTitle = defaultTitle;
        ui.clearChat();
        imageUpload.clear();
        await this.loadList();
        // Notify panels of the conversation switch so an open sub-task or
        // schedule modal can re-fetch the per-conversation list.
        try { subtaskPanel.setConvId(state.conversationId); } catch (_) {}
        try { schedulePanel.setConvId(state.conversationId); } catch (_) {}
    },

    /** Auto-rename from the first user message when the title is still the default
     *  placeholder. Idempotent per session: a manually renamed conversation or one
     *  already auto-renamed never re-fires. No-op on empty text or stale conv ids. */
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
            showToast('请等待 AI 回复完成', 'warning');
            return;
        }
        const text = div.querySelector('.sidebar-item-text');
        const actions = div.querySelector('.sidebar-item-actions');
        if (!text || !actions || div.querySelector('.sidebar-item-edit')) return;
        text.style.display = 'none';
        actions.style.display = 'none';

        const input = document.createElement('input');
        input.className = 'sidebar-item-edit';
        input.type = 'text';
        input.maxLength = 100;
        input.value = currentTitle;
        input.setAttribute('aria-label', '对话名称');
        div.insertBefore(input, text);
        input.focus();
        input.select();

        let completed = false;
        const cancel = () => {
            if (completed) return;
            completed = true;
            input.remove();
            text.style.removeProperty('display');
            actions.style.removeProperty('display');
        };
        const save = async () => {
            if (completed) return;
            const title = input.value.trim();
            if (!title) {
                showToast('对话名称不能为空', 'warning');
                input.focus();
                return;
            }
            completed = true;
            const result = await api.renameConversation(id, title);
            if (result.ok) {
                if (state.conversationId === id) state.conversationTitle = title;
                await this.loadList();
                showToast('对话已重命名', 'success');
            } else {
                input.remove();
                text.style.removeProperty('display');
                actions.style.removeProperty('display');
                showToast(result.reason === 'forbidden' ? '无权重命名该对话' : '重命名失败', 'error');
            }
        };
        input.addEventListener('click', (e) => e.stopPropagation());
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); save(); }
            if (e.key === 'Escape') { e.preventDefault(); cancel(); }
        });
        input.addEventListener('blur', () => { if (!completed) cancel(); });
    },

    async switchTo(id) {
        if (state.isStreaming) {
            showToast('请等待 AI 回复完成', 'warning');
            return;
        }
        // abort any ongoing stream
        chat.abortStream();

        state.conversationId = id;
        try { subtaskPanel.setConvId(id); } catch (_) {}
        try { schedulePanel.setConvId(id); } catch (_) {}
        try {
            const messages = await api.getConversationMessages(id);
            ui.renderMessages(messages);
            convStatePanel.start();   // refresh state strip on conversation switch
        } catch (e) {
            showToast('加载对话失败', 'error');
        }
        this.loadList(); // re-render highlight
    },

    async delete(id) {
        const ok = await dialog.confirm({
            title: '删除对话',
            message: '确定要删除这个对话吗？此操作不可撤销。',
            okText: '删除',
            danger: true,
        });
        if (!ok) return;
        const deleted = await api.deleteConversation(id);
        if (deleted) {
            if (state.conversationId === id) {
                const remaining = (await api.listConversations()).filter(item =>
                    (item.conversationId || item.id) !== id);
                if (remaining.length > 0) {
                    await this.switchTo(remaining[0].conversationId || remaining[0].id);
                } else {
                    state.conversationId = null;
                    ui.clearChat();
                    imageUpload.clear();
                    try { subtaskPanel.setConvId(null); } catch (_) {}
                    try { schedulePanel.setConvId(null); } catch (_) {}
                }
            }
            await this.loadList();
            showToast('对话已删除', 'success');
        } else {
            showToast('删除失败', 'error');
        }
    },

    refreshSidebar() {
        this.loadList();
    },
};

// ===================== §7 Chat Engine =====================
const chat = {
    async send() {
        const ta = document.getElementById('textarea');
        const text = ta.value.trim();
        if (!text && !state.isStreaming) {
            showToast('请输入消息内容', 'error');
            return;
        }

        // Guard against the attach→send race: if any attachment is still
        // uploading (fileId not yet assigned), sending now would silently drop
        // it (fileIds would contain undefined) and the model would answer as if
        // no file was attached. Block until the upload finishes.
        if (state.pendingImages.some(img => !img.fileId)) {
            showToast('文件还在上传中，请稍候再发送', 'error');
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
        const originEl = document.getElementById('origin-' + id);
        const thinkingEl = document.getElementById('thinking-body-' + id);

        let answerText = '';
        let reasonText = '';

        const record = {
            message: text,
            conversationId: state.conversationId,
            mcps: state.selectedMcps,
            enabledKnowledgeIds: state.enabledKnowledgeIds.length > 0 ? state.enabledKnowledgeIds : null,
            fileIds: state.pendingImages.length > 0 ? state.pendingImages.map(img => img.fileId).filter(Boolean) : null,
        };

        // Clear pending image after capturing fileId
        imageUpload.clear();

        // Create an AbortController so stopStream() can abort the frontend read.
        const controller = new AbortController();
        state.controller = controller;

        try {
            await api.streamChat(record,
                (data) => {
                    // reasoning content
                    if (data.reasoningContent) {
                        const thinkingContainer = document.getElementById('thinking-' + id);
                        if (thinkingContainer) thinkingContainer.style.display = '';
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
                    // complete
                    const actionsEl = document.getElementById('actions-' + id);
                    if (actionsEl) actionsEl.style.display = '';
                    ui.enableSend();
                    ui.setStopButtonVisible(false);
                    conversation.loadList();
                    // Auto-rename from the first user message if the conversation still
                    // carries its default placeholder title. Fire-and-forget; the title
                    // update will refresh the sidebar once the PATCH lands.
                    conversation.maybeAutoRename(state.conversationId, text);
                },
                (error) => {
                    // error
                    const actionsEl = document.getElementById('actions-' + id);
                    if (actionsEl) actionsEl.style.display = '';
                    if (answerEl) answerEl.innerHTML += '<br/><span style="color:var(--error-color)">发送失败：' + escapeHtml(error.message || '未知错误') + '</span>';
                    ui.enableSend();
                    ui.setStopButtonVisible(false);
                },
                controller.signal
            );
        } catch (error) {
            // AbortError = user-initiated stop, not a failure.
            if (!(error && error.name === 'AbortError')) {
                if (answerEl) answerEl.innerHTML += '<br/><span style="color:var(--error-color)">发送失败：' + escapeHtml(error.message || '未知错误') + '</span>';
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
        const btn = document.getElementById('stop-btn');
        if (btn) {
            btn.disabled = true;
            btn.textContent = '停止中...';
        }
        try {
            const r = await fetch(`/spring/ai/loom/stream/${encodeURIComponent(convId)}/stop`, {
                method: 'POST', credentials: 'include',
            });
            const data = await r.json().catch(() => ({}));
            if (data.stopped) {
                showToast('已停止生成', 'success');
            } else {
                showToast('该会话没有活跃流', 'warning');
            }
        } catch (e) {
            showToast('停止失败：' + e.message, 'error');
        } finally {
            // Abort the frontend read after signalling the backend to stop.
            if (state.controller) {
                try { state.controller.abort(); } catch (_) {}
            }
            ui.setStopButtonVisible(false);
        }
    },

    abortStream() {
        if (state.isStreaming) {
            if (state.controller) {
                try { state.controller.abort(); } catch (_) {}
            }
            ui.enableSend();
        }
    },
};

// ===================== §8 Knowledge Space =====================
const knowledge = {
    currentKbId: null,
    _kbList: [],

    openPanel() {
        ui.showModal('ks-modal-overlay');
        this.loadList();
    },

    closePanel() {
        ui.hideModal('ks-modal-overlay');
    },

    async loadList() {
        try {
            const data = await api.listKnowledge();
            this._kbList = data || [];
            this.renderList(this._kbList);
        } catch (e) {
            console.warn('[knowledge.loadList] failed:', e);
            this._kbList = [];
            this.renderList([]);
        }
    },

    renderList(list) {
        const container = document.getElementById('ks-sidebar');
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="sidebar-empty" style="padding: 40px 16px;">暂无知识库</div>';
            return;
        }
        container.innerHTML = '';

        for (const kb of list) {
            const id = kb.id;
            const name = kb.name;
            const isChecked = state.enabledKnowledgeIds.includes(id);
            const div = document.createElement('div');
            div.className = 'ks-item' + (isChecked ? ' active' : '');
            div.innerHTML = `
                <input type="checkbox" ${isChecked ? 'checked' : ''} style="width: 16px; height: 16px; cursor: pointer; flex-shrink: 0;">
                <span class="ks-item-name">${name}</span>
                <span class="ks-item-desc">${kb.description || ''}</span>
                <button class="ks-item-edit" title="编辑">✎</button>
                <button class="ks-item-delete">&times;</button>`;
            const checkbox = div.querySelector('input[type="checkbox"]');
            checkbox.addEventListener('change', () => {
                this.toggleKnowledgeForChat(id);
            });
            // 名称/描述点击：只显示右侧详情面板，不改变启用状态
            div.querySelector('.ks-item-name').addEventListener('click', (e) => {
                e.stopPropagation();
                this.select(id, name);
            });
            div.querySelector('.ks-item-desc').addEventListener('click', (e) => {
                e.stopPropagation();
                this.select(id, name);
            });
            div.querySelector('.ks-item-edit').addEventListener('click', (e) => {
                e.stopPropagation();
                this.edit(id);
            });
            div.querySelector('.ks-item-delete').addEventListener('click', (e) => {
                e.stopPropagation();
                this.delete(id);
            });
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
        const items = document.querySelectorAll('#ks-sidebar .ks-item');
        items.forEach(item => {
            const checkbox = item.querySelector('input[type="checkbox"]');
            if (checkbox) {
                item.classList.toggle('active', checkbox.checked);
            }
        });
    },

    async create() {
        const name = await dialog.prompt({
            title: '创建知识库',
            message: '请输入知识库名称：',
            placeholder: '例如：产品手册',
            okText: '下一步',
            defaultValue: '',
        });
        if (!name) return;

        const description = await dialog.prompt({
            title: '创建知识库',
            message: '请输入知识库描述：',
            placeholder: '例如：包含公司产品文档和使用手册',
            okText: '创建',
            defaultValue: '',
        });
        if (!description) {
            showToast('知识库描述不能为空', 'warning');
            return;
        }

        const data = await api.createKnowledge(name, description);
        if (data) {
            showToast('知识库创建成功', 'success');
            this.loadList();
        } else {
            showToast('创建失败', 'error');
        }
    },

    async delete(id) {
        const ok = await dialog.confirm({
            title: '删除知识库',
            message: '确定要删除这个知识库吗？关联文件将被一并移除。此操作不可撤销。',
            okText: '删除',
            danger: true,
        });
        if (!ok) return;
        const deleted = await api.deleteKnowledge(id);
        if (deleted) {
            if (this.currentKbId === id) {
                this.currentKbId = null;
                document.getElementById('ks-detail').innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个知识库查看文件</div>';
            }
            // BUG-KB-DELETE-ACTIVE: also clear the chat-bound KB if it was enabled,
            // so the next message doesn't RAG-query a deleted KB.
            const idx = state.enabledKnowledgeIds.indexOf(id);
            if (idx > -1) {
                state.enabledKnowledgeIds.splice(idx, 1);
            }
            this.loadList();
            showToast('知识库已删除', 'success');
        } else {
            showToast('删除失败', 'error');
        }
    },

    async edit(id) {
        const kb = this._kbList.find(k => k.id === id);
        if (!kb) return;

        const name = await dialog.prompt({
            title: '编辑知识库',
            message: '修改知识库名称：',
            placeholder: '请输入新名称',
            okText: '下一步',
            defaultValue: kb.name,
        });
        if (!name) return;

        const description = await dialog.prompt({
            title: '编辑知识库',
            message: '修改知识库描述：',
            placeholder: '请输入新描述',
            okText: '保存',
            defaultValue: kb.description || '',
        });
        if (!description) {
            showToast('描述不能为空', 'warning');
            return;
        }

        const data = await api.updateKnowledge(id, name, description);
        if (data.ok) {
            showToast('修改成功', 'success');
            // Refresh list and re-select if this KB was open in detail panel
            await this.loadList();
            if (this.currentKbId === id) {
                this.select(id, name);
            }
        } else {
            showToast(data.message || '修改失败', 'error');
        }
    },

    async select(id, name) {
        this.currentKbId = id;

        // show detail
        const detail = document.getElementById('ks-detail');
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

        const uploadBtn = detail.querySelector('#ks-upload-btn');
        const fileInput = detail.querySelector('#ks-file-input');
        const editBtn = detail.querySelector('#ks-edit-btn');
        uploadBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', (e) => this.uploadFile(id, e));
        editBtn.addEventListener('click', () => this.edit(id));

        this.loadFiles(id);
    },

    async loadFiles(kbId) {
        const container = document.getElementById('ks-detail').querySelector('.ks-file-list');
        try {
            const files = await api.listKnowledgeFiles(kbId);
            if (!files || files.length === 0) {
                container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">暂无文件</div>';
                return;
            }
            container.innerHTML = `
                <table class="knowledge-table">
                    <thead><tr><th>文件名</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
                    <tbody id="ks-file-tbody"></tbody>
                </table>`;
            const tbody = document.getElementById('ks-file-tbody');
            for (const f of files) {
                const row = document.createElement('tr');
                row.innerHTML = `
                    <td>${truncateText(f.fileName || f.name || '', 30)}</td>
                    <td>${formatFileSize(f.size || 0)}</td>
                    <td>${formatDate(f.uploadTime || f.createTime)}</td>
                    <td>
                        <button class="action-btn" onclick="previewFile('${f.id}')">预览</button>
                        <button class="action-btn" onclick="downloadFile('${f.id}')">下载</button>
                        <button class="action-btn" data-file-id="${f.id}">删除</button>
                    </td>`;
                row.querySelector('[data-file-id]').addEventListener('click', () => this.deleteFile(kbId, f.id, row));
                tbody.appendChild(row);
            }
        } catch (e) {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败</div>';
        }
    },

    async uploadFile(kbId, event) {
        const file = event.target.files[0];
        if (!file) return;
        try {
            const data = await api.uploadToKnowledge(kbId, file);
            if (data) {
                showToast(`文件 "${file.name}" 上传成功`, 'success');
                this.loadFiles(kbId);
            }
        } catch (e) {
            showToast('上传失败', 'error');
        }
        event.target.value = '';
    },

    async deleteFile(kbId, fileId, row) {
        if (!confirm('确定要删除这个文件吗？')) return;
        try {
            const ok = await api.deleteKnowledgeFile(kbId, fileId);
            if (ok) {
                row.remove();
                showToast('文件已删除', 'success');
            }
        } catch (e) {
            showToast('删除失败', 'error');
        }
    },

};

// ===================== §8.5 File Manager =====================
const fileMgr = {
    openModal() {
        ui.showModal('file-modal-overlay');
        this.loadTree();
    },

    closeModal() {
        ui.hideModal('file-modal-overlay');
    },

    async loadTree() {
        const tree = await api.listFileTree();
        this.renderTree(tree);
    },

    renderTree(node) {
        const container = document.getElementById('file-list');
        if (!node || !node.children || node.children.length === 0) {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">目录为空</div>';
            return;
        }
        let html = '<div class="file-tree">';
        html += this.renderTreeNode(node, '');
        html += '</div>';
        container.innerHTML = html;
        this.bindTreeEvents(container);
    },

    renderTreeNode(node, path) {
        if (!node || node.type === 'file') return '';
        let html = '';
        const children = node.children || [];
        // Sort: directories first, then files
        const dirs = children.filter(c => c.type === 'directory');
        const files = children.filter(c => c.type === 'file');

        for (const dir of dirs) {
            const dirPath = path ? path + '/' + dir.name : dir.name;
            const hasChildren = dir.children && dir.children.length > 0;
            html += `<details class="tree-dir" ${hasChildren ? '' : ''}>`;
            html += `<summary class="tree-dir-summary">📁 ${escapeHtml(dir.name)}</summary>`;
            html += `<div class="tree-children">`;
            html += this.renderTreeNode(dir, dirPath);
            html += `</div>`;
            html += `</details>`;
        }

        for (const file of files) {
            const filePath = path ? path + '/' + file.name : file.name;
            const size = file.size ? formatFileSize(file.size) : '';
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
        for (const btn of container.querySelectorAll('.tree-file-preview-btn')) {
            btn.addEventListener('click', () => this.preview(btn.dataset.path));
        }
        for (const btn of container.querySelectorAll('.tree-file-download-btn')) {
            btn.addEventListener('click', () => this.download(btn.dataset.path));
        }
    },

    preview(path) {
        const url = window.location.origin + '/spring/ai/loom/file/by-path/view?path=' + encodeURIComponent(path);
        window.open(url, '_blank', 'noopener,noreferrer');
    },

    download(path) {
        const url = window.location.origin + '/spring/ai/loom/file/by-path/download?path=' + encodeURIComponent(path);
        window.open(url, '_blank', 'noopener,noreferrer');
    },
};

/** 子任务面板：打开时轮询 active + history，关闭时停止轮询 + ticker。
 *   Operations-console surface (v4 redesign) — cold slate dark, status
 *   pill counts, signature live-elapsed ticker on running rows. */
const subtaskPanel = {
    _timer: null,
    _ticker: null,
    _currentConvId: null,
    _expanded: new Set(),
    _activeRows: [],   // cached for live elapsed-ticker updates

    openModal() {
        ui.showModal('subtask-modal-overlay');
        this._currentConvId = (typeof state !== 'undefined' && state.conversationId) || '';
        this.refresh();
        this._timer = setInterval(() => this.refresh(), 2000);
        // Live elapsed ticker runs at 1Hz; touches only DOM textContent of
        // running rows (no full re-render). Skipped if no running rows.
        this._ticker = setInterval(() => this._tickElapse(), 1000);
    },

    closeModal() {
        ui.hideModal('subtask-modal-overlay');
        if (this._timer) { clearInterval(this._timer); this._timer = null; }
        if (this._ticker) { clearInterval(this._ticker); this._ticker = null; }
        this._activeRows = [];
    },

    refresh() {
        const convId = this._currentConvId || ((typeof state !== 'undefined' && state.conversationId) || '');
        this._ensureLimits().then(() => {
            if (!convId) {
                this._renderEmpty('请先打开一个对话');
                return;
            }
            Promise.all([
                api.listActiveSubtasks(convId),
                api.listSubtaskHistory(convId),
            ]).then(([active, history]) => this.render(active || [], history || [], convId));
        });
    },

    _ensureLimits() {
        if (this._limits) return Promise.resolve(this._limits);
        return api.subtaskLimits().then(l => (this._limits = l || {})).catch(() => (this._limits = {}));
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
     *  re-rendering the panel. If modal is closed / no running rows,
     *  work is essentially zero. */
    _tickElapse() {
        if (this._activeRows.length === 0) return;
        for (const rec of this._activeRows) {
            const cell = document.querySelector(
                `[data-elapsed="${cssEscape(rec.subTaskId)}"]`);
            if (!cell) continue;
            cell.textContent = this._formatElapsed(Date.now() - rec.startedAt);
        }
    },

    _renderEmpty(msg) {
        const body = document.getElementById('subtask-panel-body');
        if (!body) return;
        body.innerHTML = `
            ${this._toolbarHTML(0, 0, 0)}
            <div class="console-body">
                <div class="console-empty">
                    <div class="glyph subtask-glyph">
                        <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
                            <path d="M4 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9l-5 4v-4H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" fill="rgba(167,139,250,0.18)" stroke="currentColor" stroke-width="1.6"/>
                            <circle cx="9"  cy="10.5" r="1.4" fill="currentColor"/>
                            <circle cx="13" cy="10.5" r="1.4" fill="currentColor"/>
                            <circle cx="17" cy="10.5" r="1.4" fill="currentColor"/>
                        </svg>
                    </div>
                    <h4>${escapeHtml(msg || '让 AI 帮你开一个')}</h4>
                    <p>主对话里跟它说一句「调研…」，它会自动起子任务。或者直接在这里写你想让它做的事。</p>
                    <div class="composer">
                        <input data-composer placeholder="「例如:调研苹果公司在东南亚的供应链…」" />
                        <button class="console-btn-primary" data-composer-submit style="height:32px;">新建子任务 ↗</button>
                    </div>
                    ${this._limitsHintHTML()}
                </div>
            </div>`;
        this._wireToolbar(body);
        this._wireComposer(body, '/subtask');
    },

    _toolbarHTML(running, done, failed) {
        const total = running + done + failed;
        return `
        <div class="console-bar">
            <div class="title">子任务<span class="sub">/ sub-task</span></div>
            <span class="console-pill ${running > 0 ? 'running' : ''}"><span class="dot"></span><span class="num">${running}</span>&nbsp;运行中</span>
            <span class="console-pill ${done > 0 ? 'done' : ''}"><span class="dot"></span><span class="num">${done}</span>&nbsp;已完成</span>
            <span class="console-pill ${failed > 0 ? 'failed' : ''}"><span class="dot"></span><span class="num">${failed}</span>&nbsp;失败</span>
            <span class="grow"></span>
            <input class="search" placeholder="搜 prompt / id…" />
            <button class="console-btn-ghost" data-filter>过滤</button>
            ${total > 0
                ? `<button class="console-btn-primary" data-new>+ 新建</button>`
                : ''}
            <button class="console-close" data-close aria-label="收起"></button>
        </div>`;
    },

    render(active, history, convId) {
        const body = document.getElementById('subtask-panel-body');
        if (!body) return;
        // Counts
        const live = active || [];
        const done = (history || []).filter(r => (r.status || '').toUpperCase() === 'COMPLETED').length;
        const failed = (history || []).filter(r => (r.status || '').toUpperCase() === 'FAILED').length;

        // Whole sections: only render when there's content
        const allHistory = history || [];
        if (live.length === 0 && allHistory.length === 0) {
            this._renderEmpty('让 AI 帮你开一个');
            return;
        }

        // Cache for live ticker
        this._activeRows = live.slice();

        let cardsHtml = '';
        if (live.length > 0) {
            cardsHtml += `<div class="console-section">
                <div class="console-section-label">运行中<span class="count">${live.length}</span></div>
                <div class="console-card-list">${live.map(r => this._rowHTML(r, 'running')).join('')}</div>
            </div>`;
        }
        if (allHistory.length > 0) {
            const reversed = allHistory.slice().sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0));
            cardsHtml += `<div class="console-section">
                <div class="console-section-label">历史<span class="count">${reversed.length}</span></div>
                <div class="console-card-list">${reversed.map(r => this._rowHTML(r, (r.status || '').toLowerCase())).join('')}</div>
            </div>`;
        }

        body.innerHTML = `
            ${this._toolbarHTML(live.length, done, failed)}
            <div class="console-body">${cardsHtml}</div>`;

        this._wireToolbar(body);
        this._wireCardActions(body);
    },

    _rowHTML(r, kind) {
        const id = r.subTaskId || '';
        const sidShort = id.slice(0, 4) + '…' + id.slice(-4);
        const prompt = (r.prompt || '');
        const meta = [];
        if (kind === 'running') {
            const started = r.startedAt ? this._rel(r.startedAt) : '';
            meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
            if (started) meta.push(`<span>·</span><span>${escapeHtml(started)}</span>`);
        } else if (kind === 'failed') {
            const errMsg = r.errorMessage ? r.errorMessage.slice(0, 60) : (r.status || 'FAILED');
            meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
            meta.push(`<span>·</span><span class="err">${escapeHtml(errMsg)}${errMsg.length > 60 ? '…' : ''}</span>`);
        } else if (kind === 'cancelled') {
            meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
            meta.push(`<span>·</span><span>用户取消</span>`);
        } else {
            // done (or lowercase status)
            const dur = this._rel(r.finishedAt);
            meta.push(`<span class="id mono">${escapeHtml(sidShort)}</span>`);
            meta.push(`<span>·</span><span>${escapeHtml(dur)}</span>`);
        }

        const statusLabel = ({
            running: 'RUNNING',
            completed: 'DONE',
            failed: 'FAILED',
            cancelled: 'CANCELLED',
        })[kind] || (kind || '').toUpperCase();

        // Action set varies by state
        let actions = '';
        if (kind === 'running') {
            actions = `
                <button class="console-icon-btn" title="查看 stream 日志" data-stream="${escapeHtml(id)}">≡</button>
                <button class="console-icon-btn danger" title="停止" data-kill="${escapeHtml(id)}">■</button>`;
        } else if (kind === 'failed' || kind === 'cancelled') {
            actions = `
                <button class="console-icon-btn" title="查看详情" data-stream="${escapeHtml(id)}">i</button>
                <button class="console-icon-btn danger" title="从历史删除" data-history-del="${escapeHtml(id)}">×</button>`;
        } else {
            actions = `
                <button class="console-icon-btn" title="查看结果" data-stream="${escapeHtml(id)}">↗</button>
                <button class="console-icon-btn danger" title="从历史删除" data-history-del="${escapeHtml(id)}">×</button>`;
        }

        // Elapsed cell
        let elapsedHtml = '—';
        if (kind === 'running' && r.startedAt) {
            elapsedHtml = `<span class="mono">${escapeHtml(this._formatElapsed(Date.now() - r.startedAt))}</span>`;
        } else if (r.startedAt && r.finishedAt) {
            const ms = r.finishedAt - r.startedAt;
            elapsedHtml = this._formatShortDuration(ms);
        }

        return `
            <div class="console-card status-${kind === 'running' ? 'running' : kind === 'completed' ? 'done' : kind === 'failed' ? 'failed' : kind === 'cancelled' ? 'cancel' : 'done'}" data-row-id="${escapeHtml(id)}">
                <div class="stripe"></div>
                <div class="prompt-cell">
                    <div class="prompt">${escapeHtml(prompt || '(no prompt)')}</div>
                    <div class="meta">${meta.join(' ')}</div>
                </div>
                <div class="console-status"><span class="dot"></span>${escapeHtml(statusLabel)}</div>
                <div class="console-elapsed" data-elapsed="${escapeHtml(id)}">${elapsedHtml}</div>
                <div class="console-actions">${actions}</div>
            </div>`;
    },

    _wireToolbar(body) {
        const overlay = document.getElementById('subtask-modal-overlay');
        // Always-append: the close chevron was previously a nested #subtask-close-btn.
        // We use data-close so re-render doesn't break it.
        const closeBtn = body.querySelector('[data-close]');
        if (closeBtn) closeBtn.addEventListener('click', () => this.closeModal());
        // New-task button: jump to chat with a pre-filled prompt for the LLM
        const newBtn = body.querySelector('[data-new]');
        if (newBtn) newBtn.addEventListener('click', () => this._focusChatWithStub());
    },

    _wireCardActions(body) {
        for (const btn of body.querySelectorAll('[data-kill]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.kill(btn.dataset.kill, false); });
        }
        for (const btn of body.querySelectorAll('[data-history-del]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.deleteHistory(btn.dataset.historyDel); });
        }
        for (const btn of body.querySelectorAll('[data-stream]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.showStream(btn.dataset.stream); });
        }
    },

    _wireComposer(body, kind) {
        const input = body.querySelector('[data-composer]');
        const submit = body.querySelector('[data-composer-submit]');
        if (!input || !submit) return;
        const send = () => {
            const text = (input.value || '').trim();
            if (!text) return;
            this._focusChatWithStub(text);
            input.value = '';
        };
        submit.addEventListener('click', send);
        input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
        // Live enable/disable
        const sync = () => { submit.disabled = !input.value.trim(); };
        sync();
        input.addEventListener('input', sync);
    },

    /** Bridge into the main chat SPA: either pre-fill the textarea (if
     *  user typed) or simply focus it. Either way the LLM ends up
     *  calling start_sub_task / create_scheduled_task on its own. */
    _focusChatWithStub(prefilled) {
        const prompt = prefilled || '请帮我开一个子任务';
        try {
            const ta = document.querySelector('#textarea');
            if (ta) {
                ta.value = prompt;
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                ta.focus();
            }
        } catch (e) { /* SPA not ready yet — silent */ }
        this.closeModal();
    },

    /** Show stream logs for a sub-task id. For now: deep-link to that
     *  sub-task's main-conversation entry via the toast (the LLM echoed
     *  the tool-result back into the parent conversation). */
    async showStream(id) {
        showToast('子任务 ' + id.slice(0, 8) + ' 的执行流已在主对话中显示', 'info');
    },

    /** mm:ss / h:mm:ss picker used in the live ticker */
    _formatElapsed(ms) {
        ms = Math.max(0, Math.floor(ms / 1000));
        const h = Math.floor(ms / 3600);
        const m = Math.floor((ms % 3600) / 60);
        const s = ms % 60;
        const pad = n => String(n).padStart(2, '0');
        return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
    },
    _formatShortDuration(ms) {
        if (!ms || ms < 0) return '—';
        ms = Math.floor(ms / 1000);
        if (ms < 60) return ms + 's';
        const m = Math.floor(ms / 60);
        const s = ms % 60;
        return s > 0 ? `${m}m ${s}s` : `${m}m`;
    },

    async kill(id, alsoDeleteHistory) {
        const ok = await dialog.confirm({
            title: alsoDeleteHistory ? '停止并删除子任务' : '停止子任务',
            message: `确认${alsoDeleteHistory ? '停止该子任务并在历史中删除' : '停止子任务'} ${id.slice(0, 8)}?`
                + (alsoDeleteHistory ? '' : '\n\n被挂起等待子任务结果的 AI 调用会收到「子任务已取消 用户手动取消」并继续主对话。'),
            danger: true,
        });
        if (!ok) return;
        const killed = await api.killSubtask(id);
        if (alsoDeleteHistory) {
            let attempts = 0;
            let deleted = false;
            while (attempts < 5 && !deleted) {
                attempts++;
                await new Promise(r => setTimeout(r, 250));
                deleted = await api.deleteSubtaskHistory(id);
            }
            showToast(`已停止并删除 ${id.slice(0, 8)}${deleted ? '' : '（历史行未找到，可能尚未落库）'}`, deleted ? 'success' : 'warning');
        } else {
            showToast(killed ? '已停止' : '未找到该子任务', killed ? 'success' : 'warning');
        }
        this.refresh();
    },

    async deleteHistory(id) {
        const ok = await dialog.confirm({
            title: '删除子任务历史记录',
            message: `确认删除历史子任务记录 ${id.slice(0, 8)}?`,
            danger: true,
        });
        if (!ok) return;
        const deleted = await api.deleteSubtaskHistory(id);
        showToast(deleted ? '已删除' : '未找到记录', deleted ? 'success' : 'warning');
        this.refresh();
    },

    _rel(ms) {
        if (!ms) return '-';
        const d = Math.floor((Date.now() - ms) / 1000);
        if (d < 60) return `${d}s 前`;
        if (d < 3600) return `${Math.floor(d / 60)}m 前`;
        return `${Math.floor(d / 3600)}h 前`;
    },
};

// CSS.escape polyfill (tier-2) for selectors keyed on user-controlled ids
function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, c => '\\' + c);
}

/** 定时任务面板：当前会话的运行中 + 历史,带全部停止按钮 + 每行操作按钮。
 *   Operations-console surface (v4 redesign) — schedule rows use shape-
 *   encoded status (⏲ scheduled · ✓ ended · — cancelled) so they don't
 *   fight the sub-task palette. */
const schedulePanel = {
    _timer: null,
    _currentConvId: null,

    openModal() {
        ui.showModal('schedule-modal-overlay');
        this._currentConvId = (typeof state !== 'undefined' && state.conversationId) || '';
        this.refresh();
        this._timer = setInterval(() => this.refresh(), 5000);
    },

    closeModal() {
        ui.hideModal('schedule-modal-overlay');
        if (this._timer) { clearInterval(this._timer); this._timer = null; }
    },

    setConvId(convId) {
        if (this._currentConvId === convId) return;
        this._currentConvId = convId;
        if (document.getElementById('schedule-modal-overlay')
            && getComputedStyle(document.getElementById('schedule-modal-overlay')).display !== 'none') {
            this.refresh();
        }
    },

    _resolveConvId() {
        if (typeof state !== 'undefined' && state.conversationId) return state.conversationId;
        return (window.currentConversationId
            || (window.appState && window.appState.currentConversationId)
            || (window.chatState && window.chatState.currentConversationId)
            || (location.hash.match(/conv[=:]([\w-]+)/) || [])[1]
            || '') || '';
    },

    async refresh() {
        const convId = this._currentConvId || this._resolveConvId();
        this._currentConvId = convId;
        await this._ensureLimits();
        if (!convId) {
            this._renderEmpty('请先打开一个对话');
            return;
        }
        const tasks = await api.scheduleByConversation(convId);
        this.render(tasks || [], convId);
    },

    async _ensureLimits() {
        if (this._limits) return this._limits;
        try { this._limits = await api.scheduleLimits(); } catch (_) { this._limits = {}; }
        return this._limits;
    },

    _limitsHintHTML() {
        const L = this._limits || {};
        if (L.enforcing === false) {
            return `<div class="micro-hint">触发限制未启用</div>`;
        }
        const min = L.minInterval ? `最小间隔 <code>${escapeHtml(String(L.minInterval))}</code>` : '';
        const max = L.maxLifetime ? `最长存活 <code>${escapeHtml(String(L.maxLifetime))}</code>` : '';
        const parts = [min, max].filter(Boolean).join(' · ');
        return parts ? `<div class="micro-hint">${parts}</div>` : '';
    },

    _renderEmpty(msg) {
        const body = document.getElementById('schedule-panel-body');
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
                    <h4>${escapeHtml(msg || '建一个提醒或周期性任务')}</h4>
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
            <span class="console-pill ${liveCount > 0 ? 'running' : ''}"><span class="dot"></span><span class="num">${liveCount}</span>&nbsp;运行中</span>
            <span class="grow"></span>
            <input class="search" placeholder="搜任务名…" />
            <button class="console-btn-ghost" data-filter>过滤</button>
            ${liveCount > 0
                ? `<button class="console-btn-primary" data-new>+ 新建</button>`
                : ''}
            <button class="console-close" data-close aria-label="收起"></button>
        </div>`;
    },

    render(tasks, convId) {
        const body = document.getElementById('schedule-panel-body');
        if (!body) return;
        const live = tasks.filter(t => t.live);
        const hist = tasks.filter(t => !t.live);

        if (live.length === 0 && hist.length === 0) {
            this._renderEmpty('建一个提醒或周期性任务');
            return;
        }

        let cardsHtml = '';
        if (live.length > 0) {
            cardsHtml += `<div class="console-section">
                <div class="console-section-label">运行中<span class="count">${live.length}</span></div>
                <div class="console-card-list">${live.map(t => this._rowHTML(t, true)).join('')}</div>
            </div>`;
        }
        if (hist.length > 0) {
            // Sort by most recent trigger
            const sorted = hist.slice().sort((a, b) => (b.lastFireTime || 0) - (a.lastFireTime || 0));
            cardsHtml += `<div class="console-section">
                <div class="console-section-label">已结束<span class="count">${sorted.length}</span></div>
                <div class="console-card-list">${sorted.map(t => this._rowHTML(t, false)).join('')}</div>
            </div>`;
        }

        body.innerHTML = `
            ${this._toolbarHTML(live.length)}
            <div class="console-body">${cardsHtml}</div>`;

        this._wireToolbar(body);
        this._wireCardActions(body);
    },

    _rowHTML(t, isLive) {
        const full = t.taskName || '';
        const shortName = this._shortName(full);
        const cadence = this._humanizeSchedule(t);
        const stats = this._humanizeStats(t, isLive);

        // Decide status shape (string used to render the glyph via ::before)
        // — schedule uses shape encoding, not color
        let shape, label, kind;
        if (isLive) { shape = '⏲'; label = 'SCHEDULED'; kind = 'running'; }
        else        { shape = '✓'; label = 'ENDED';     kind = 'done'; }

        const elapsed = isLive ? this._nextTrigger(t) : this._rel(t.lastFireTime || 0);

        const actions = isLive
            ? `<button class="console-icon-btn" title="手动触发" data-trigger="${escapeHtml(full)}">▶</button>
               <button class="console-icon-btn danger" title="停止" data-cancel="${escapeHtml(full)}">■</button>`
            : `<button class="console-icon-btn" title="查看历史" data-history="${escapeHtml(full)}">≡</button>
               <button class="console-icon-btn danger" title="删除" data-cancel="${escapeHtml(full)}">×</button>`;

        return `
            <div class="console-card schedule-row status-${kind}" data-task="${escapeHtml(full)}">
                <div class="stripe"></div>
                <div class="prompt-cell">
                    <div class="prompt">${escapeHtml(t.prompt || shortName || '(no prompt)')}</div>
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
        const closeBtn = body.querySelector('[data-close]');
        if (closeBtn) closeBtn.addEventListener('click', () => this.closeModal());
        const newBtn = body.querySelector('[data-new]');
        if (newBtn) newBtn.addEventListener('click', () => subtaskPanel._focusChatWithStub('请帮我创建一个定时任务'));
    },

    _wireCardActions(body) {
        for (const btn of body.querySelectorAll('[data-cancel]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.cancel(btn.dataset.cancel); });
        }
        for (const btn of body.querySelectorAll('[data-history]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.history(btn.dataset.history); });
        }
        for (const btn of body.querySelectorAll('[data-trigger]')) {
            btn.addEventListener('click', e => { e.stopPropagation(); this.trigger(btn.dataset.trigger); });
        }
    },

    _wireComposer(body) {
        const input = body.querySelector('[data-composer]');
        const submit = body.querySelector('[data-composer-submit]');
        if (!input || !submit) return;
        const send = () => {
            const text = (input.value || '').trim();
            if (!text) return;
            subtaskPanel._focusChatWithStub(text);
            input.value = '';
        };
        submit.addEventListener('click', send);
        input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
        const sync = () => { submit.disabled = !input.value.trim(); };
        sync();
        input.addEventListener('input', sync);
    },

    /** Translate fixed_delay / cron / one_shot + `schedule` string into
     *  Chinese humanised form. */
    _humanizeSchedule(t) {
        const type = (t.taskType || '').toLowerCase();
        const sched = t.schedule || '';
        if (type === 'one_shot') return '一次性 · ' + (sched || '60s 后');
        if (type === 'fixed_delay') {
            const m = sched.match(/(\d+)\s*(s|m|h|ms)?/);
            if (m) {
                const n = parseInt(m[1], 10);
                const unit = (m[2] || 's');
                const label = unit === 's' ? `${n} 秒` : unit === 'm' ? `${n} 分钟` : unit === 'h' ? `${n} 小时` : `${n} ${unit}`;
                return `每 ${label}一次`;
            }
            return sched || '周期';
        }
        if (type === 'cron') return sched || '周期';
        return sched || '—';
    },

    /** Stats line: 下次 / 已触发 count · 上次 fired-ago */
    _humanizeStats(t, isLive) {
        if (isLive) {
            const next = this._nextTrigger(t);
            const fired = t.fireCount || 0;
            const line = [];
            if (next && next !== '—') line.push(`<span class="next">下次 ${escapeHtml(next)}</span>`);
            if (fired) line.push(`<span>已触发 ${fired} 次</span>`);
            return line.join('') || '<span>—</span>';
        }
        // ended: 上次 fired ago
        const last = this._rel(t.lastFireTime || 0);
        return `<span>上次 ${escapeHtml(last)}</span>`;
    },

    /** next trigger — derive from now + fixed_delay interval when type is fixed_delay */
    _nextTrigger(t) {
        const type = (t.taskType || '').toLowerCase();
        if (type === 'one_shot') return '—';
        if (type === 'fixed_delay') {
            const sched = t.schedule || '';
            const m = sched.match(/(\d+)\s*(s|m|h)/);
            if (m) {
                const n = parseInt(m[1], 10);
                const ms = n * (m[2] === 'm' ? 60_000 : m[2] === 'h' ? 3_600_000 : 1000);
                const last = t.lastFireTime || Date.now();
                const next = last + ms;
                const hh = String(new Date(next).getHours()).padStart(2, '0');
                const mm = String(new Date(next).getMinutes()).padStart(2, '0');
                return `${hh}:${mm}`;
            }
        }
        return '—';
    },

    async trigger(fullName) {
        showToast('已请求触发 ' + this._shortName(fullName), 'info');
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
        const f = full || '';
        const prefix = 'loom-sched-';
        if (f.startsWith(prefix)) {
            const rest = f.substring(prefix.length);   // "{username}-{convId}-{name}"
            const dashIdx = rest.indexOf('-');
            if (dashIdx > 0) {
                const convStart = dashIdx + 1;
                // UUID convId = 36 chars (e.g. "e5436384-039e-4a23-a35c-968e969e48b8")
                if (rest.length >= convStart + 36) {
                    const afterConv = rest.substring(convStart + 36);
                    if (afterConv.startsWith('-')) return afterConv.substring(1);
                }
            }
        }
        // Fallback: best-effort slice if format is unexpected.
        const parts = f.split('-');
        return parts.length > 4 ? parts.slice(4).join('-') : f;
    },

    async cancel(fullName) {
        const ok = await dialog.confirm({
            title: '停止定时器',
            message: '确认停止定时器 ' + this._shortName(fullName) + ' ?',
            danger: true,
        });
        if (!ok) return;
        await api.cancelSchedule(fullName);
        this.refresh();
    },

    async history(fullName) {
        const records = await api.scheduleHistory(fullName);
        const lines = (records || []).slice(0, 20).map(r => {
            const status = r.success ? '成功' : '失败';
            return `${r.startTime || ''} ${status}${r.error ? '(' + r.error + ')' : ''}`;
        }).join('  ·  ');
        await dialog.confirm({
            title: '执行历史 · ' + this._shortName(fullName),
            message: lines || '(暂无执行记录)',
            okText: '关闭',
        });
    },

    async cancelAll(convId) {
        const ok = await dialog.confirm({
            title: '全部停止',
            message: `确认停止当前对话的所有定时任务(包括未触发的和历史任务的配置)?\n\nconv: ${convId}`,
            danger: true,
        });
        if (!ok) return;
        const r = await api.cancelAllSchedulesByConversation(convId);
        await dialog.alert({
            title: '已停止',
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
        if (this._timer) { clearInterval(this._timer); this._timer = null; }
        this._currentConvId = null;
        this._hide();
    },

    async refresh() {
        const convId = (typeof state !== 'undefined' && state.conversationId) || '';
        if (!convId) { this._hide(); return; }
        this._currentConvId = convId;
        const s = await api.conversationState(convId);
        if (!s) { this._hide(); return; }
        this._render(s);
    },

    _hide() {
        const el = document.getElementById('conv-state-strip');
        if (el) el.style.display = 'none';
    },

    _render(s) {
        const el = document.getElementById('conv-state-strip');
        if (!el) return;
        const fields = ['activeSchedules', 'executionsLast7d', 'executionsFailedLast7d',
            'activeSubTasks', 'subTaskHistoryLast7d', 'subTaskFailedLast7d'];
        const activityKeys = ['activeSchedules', 'executionsLast7d', 'activeSubTasks', 'subTaskHistoryLast7d'];
        const failureKeys = ['executionsFailedLast7d', 'subTaskFailedLast7d'];
        const hasActivity = activityKeys.some(k => (s[k] || 0) > 0);
        const hasFailures = failureKeys.some(k => (s[k] || 0) > 0);
        if (!hasActivity && !hasFailures) { el.style.display = 'none'; return; }
        el.style.display = 'flex';
        for (const k of fields) {
            const item = el.querySelector(`[data-key="${k}"]`);
            if (!item) continue;
            const n = s[k] || 0;
            item.querySelector('.conv-state-num').textContent = String(n);
            item.classList.remove('has-activity', 'has-failures');
            if (failureKeys.includes(k) && n > 0) item.classList.add('has-failures');
            else if (activityKeys.includes(k) && n > 0) item.classList.add('has-activity');
        }
        // Whole-strip border tint when failures present.
        el.style.borderTop = hasFailures ? '1px solid rgba(231, 76, 60, 0.4)'
                                          : '1px solid var(--border-color, rgba(255,255,255,0.06))';
    },
};

function getFileIcon(name) {
    const ext = name.split('.').pop().toLowerCase();
    const icons = {
        pdf: '📕', doc: '📘', docx: '📘', xls: '📗', xlsx: '📗',
        ppt: '📙', pptx: '📙', png: '🖼️', jpg: '🖼️', jpeg: '🖼️',
        gif: '🖼️', svg: '🖼️', mp3: '🎵', mp4: '🎬', wav: '🎵',
        zip: '📦', tar: '📦', gz: '📦', js: '📜', ts: '📜',
        py: '📜', java: '📜', go: '📜', rs: '📜', md: '📝',
        txt: '📄', csv: '📊', html: '🌐', css: '🎨', json: '📋',
        yaml: '⚙️', yml: '⚙️', xml: '📋', sql: '🗃️', sh: '⚡',
        bat: '⚡', ps1: '⚡'
    };
    return icons[ext] || '📄';
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
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
        return 'loom.mcp.selectedNames.' + (u && typeof u === 'string' && u.length ? u : '_anonymous');
    },

    /** Read persisted selection; returns null if missing/corrupt. */
    _loadPersisted() {
        try {
            const raw = localStorage.getItem(this._storageKey());
            if (!raw) return null;
            const arr = JSON.parse(raw);
            return Array.isArray(arr) ? arr.filter(s => typeof s === 'string') : null;
        } catch (_) {
            return null;
        }
    },

    /** Persist current selection. */
    _savePersisted() {
        try {
            localStorage.setItem(this._storageKey(), JSON.stringify(state.selectedMcps));
        } catch (_) {
            // localStorage may be disabled (private mode, quota) — fail silently
        }
    },

    openModal() {
        ui.showModal('mcp-modal-overlay');
        this.renderModal();
    },

    closeModal() {
        ui.hideModal('mcp-modal-overlay');
    },

    renderModal() {
        const container = document.getElementById('mcp-list');
        const detail = document.getElementById('mcp-detail');
        detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个MCP服务查看详情</p></div>';

        if (state.mcps.length === 0) {
            container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted);">暂无可用MCP服务</div>';
            return;
        }
        container.innerHTML = '';
        for (const m of state.mcps) {
            const item = document.createElement('div');
            item.className = 'skill-item' + (state.selectedMcps.includes(m.name) ? ' selected' : '');
            item.innerHTML = `
                <div style="display: flex; align-items: center; gap: 12px;">
                    <input type="checkbox" ${state.selectedMcps.includes(m.name) ? 'checked' : ''} style="width: 18px; height: 18px; cursor: pointer;" class="mcp-checkbox">
                    <div class="mcp-item-text" style="flex: 1; cursor: pointer;">
                        <div class="skill-item-name">${m.title || m.name}</div>
                    </div>
                </div>`;
            item.querySelector('.mcp-checkbox').addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleSelect(m.name, item);
            });
            item.querySelector('.mcp-item-text').addEventListener('click', () => this.showDetail(m));
            container.appendChild(item);
        }
    },

    toggleSelect(name, element) {
        const idx = state.selectedMcps.indexOf(name);
        if (idx >= 0) {
            state.selectedMcps.splice(idx, 1);
            element.classList.remove('selected');
        } else {
            state.selectedMcps.push(name);
            element.classList.add('selected');
        }
        this._savePersisted();
        showToast(`已${state.selectedMcps.includes(name) ? '选中' : '取消'}MCP服务`, 'success');
    },

    async showDetail(m) {
        document.getElementById('mcp-detail-title').textContent = m.title || m.name;
        const detail = document.getElementById('mcp-detail');
        let html = '';
        html += `<div class="detail-section">
            <div class="detail-section-title">基本信息</div>
            <div style="line-height: 1.8; color: var(--text-primary);">
                <div style="margin-bottom: 12px;"><strong>名称：</strong>${escapeHtml(m.name)}</div>
                <div><strong>描述：</strong>${escapeHtml(m.description || '无描述')}</div>
            </div>
        </div>`;
        // 工具数据已经在 /mcps 列表接口里（带 tools 字段），
        // 这里直接用 m.tools 渲染，不再二次请求 —— 避免 mcp 名含 @ 等特殊字符触发 Tomcat 400。
        const tools = m.tools || [];
        html += `<div class="detail-section">
            <div class="detail-section-title">包含工具 (${tools.length})</div>
            <div id="mcp-tools-list">`;
        if (tools.length === 0) {
            html += '<span style="color: var(--text-muted); font-size: 13px;">无可用工具</span>';
        } else {
            html += '<div style="display: flex; flex-direction: column; gap: 12px;">' +
                tools.map(tool => {
                    // 直接展示 SDK 原文（含已维护的覆盖）。空字符串就不显示 description 行
                    const descHtml = tool.description && tool.description.trim()
                        ? `<div style="font-size: 13px; color: var(--text-secondary); line-height: 1.6; white-space: pre-wrap;">${escapeHtml(tool.description)}</div>`
                        : '';
                    return `
                    <div style="padding: 16px; background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 8px;">
                        <div style="font-weight: 600; font-size: 14px; color: var(--primary-color); margin-bottom: 8px;">${escapeHtml(tool.name)}</div>
                        ${descHtml}
                    </div>`;
                }).join('') + '</div>';
        }
        html += '</div></div>';
        detail.innerHTML = html;
    },

    async loadList() {
        try {
            const data = await api.listMcps();
            if (data && data.length > 0) {
                state.mcps = data;
                // Restore persisted selection if available; otherwise use defaults.
                // Filter against current server list so deleted servers don't linger. (BUG-MCP-PERSIST)
                const validNames = new Set(data.map(m => m.name));
                const persisted = this._loadPersisted();
                if (persisted && persisted.length > 0) {
                    state.selectedMcps = persisted.filter(n => validNames.has(n));
                } else {
                    state.selectedMcps = data.filter(m => m.defaultSelected).map(m => m.name);
                }
                return;
            }
        } catch (e) {
            console.warn('[mcp.loadList] failed:', e);
        }
        state.mcps = [];
        state.selectedMcps = [];
    },
};

// ===================== §10 Skills =====================
const skills = {
    editingSkill: null, // null = view mode, object = creating/editing
    currentTab: 'mine', // 'mine' / 'market' / 'submit'

    openModal() {
        ui.showModal('skills-modal-overlay');
        // Tab 绑定（只绑一次）
        this._bindTabs();
        this.renderModal();
    },

    closeModal() {
        ui.hideModal('skills-modal-overlay');
        this.editingSkill = null;
    },

    _bindTabs() {
        if (this._tabsBound) return;
        document.querySelectorAll('#skills-modal-overlay .skill-tab').forEach(btn => {
            btn.addEventListener('click', () => {
                this.currentTab = btn.getAttribute('data-tab');
                document.querySelectorAll('#skills-modal-overlay .skill-tab').forEach(b => {
                    const active = b.getAttribute('data-tab') === this.currentTab;
                    b.classList.toggle('active', active);
                    b.style.borderBottomColor = active ? 'var(--primary-color)' : 'transparent';
                    b.style.color = active ? 'var(--primary-color)' : 'var(--text-muted)';
                });
                this.renderModal();
            });
        });
        this._tabsBound = true;
    },

    /** source 字段 → 中文标签 + 颜色 */
    _sourceLabel(source) {
        switch (source) {
            case 'USER_CREATED':  return {text: '自建',   bg: '#dbeafe', color: '#1e40af'};
            case 'MARKET_PULLED': return {text: '市场',   bg: '#d1fae5', color: '#065f46'};
            case 'ROLE_GRANTED':  return {text: '角色授权', bg: '#fef3c7', color: '#92400e'};
            case 'MARKET_VIEW':   return {text: '市',     bg: '#ede9fe', color: '#6b21a8'};
            case 'embed':         return {text: '内置',   bg: '#f1f5f9', color: '#475569'};
            default:              return {text: source,  bg: '#f1f5f9', color: '#475569'};
        }
    },

    renderModal() {
        const container = document.getElementById('skills-list');
        const detail = document.getElementById('skills-detail');
        detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个技能查看详情，或点击「新增」创建新技能</p></div>';
        container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted);">加载中...</div>';

        if (this.currentTab === 'mine') this._renderMineTab(container);
        else if (this.currentTab === 'market') this._renderMarketTab(container);
        else if (this.currentTab === 'submit') this._renderSubmitTab(container, detail);
        else if (this.currentTab === 'mysubmit') this._renderMySubmissions(container, detail);
    },

    _renderMineTab(container) {
        api.listSkills().then(data => {
            container.innerHTML = '';
            if (!data || data.length === 0) {
                container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">暂无可用技能<br><br>点击「+ 新增」自建，或切到「市场」拉取</div>';
                return;
            }

            for (const skill of data) {
                const item = document.createElement('div');
                item.className = 'skill-item';
                const lbl = this._sourceLabel(skill.source);
                item.innerHTML = `
                    <div class="skill-item-name">${escapeHtml(skill.name)} <span class="skill-source-tag" style="background:${lbl.bg};color:${lbl.color};">${lbl.text}</span></div>
                    <div class="skill-item-desc">${escapeHtml(skill.description || '')}</div>
                `;
                item.addEventListener('click', () => this.select(skill, item));
                container.appendChild(item);
            }
        }).catch(() => {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败</div>';
        });
    },

    async _renderMarketTab(container) {
        container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted);">加载中...</div>';
        try {
            const list = await api.listMarketSkills();
            container.innerHTML = '';
            if (!list || list.length === 0) {
                container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">市场暂无 Skill</div>';
                return;
            }
            // 拉一次"我的"用来对比哪些已拉过
            const mine = await api.listSkills();
            const mineNames = new Set((mine || []).map(s => s.name));
            for (const m of list) {
                const item = document.createElement('div');
                item.className = 'skill-item';
                const already = mineNames.has(m.name);
                item.innerHTML = `
                    <div class="skill-item-name">${escapeHtml(m.name)} <span style="font-size: 11px; color: var(--text-muted);">v${escapeHtml(m.version)} · @${escapeHtml(m.author)}</span></div>
                    <div class="skill-item-desc">${escapeHtml(m.description || '')}</div>
                `;
                item.addEventListener('click', () => this._selectMarketSkill(m, item, already));
                container.appendChild(item);
            }
        } catch (e) {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败：' + escapeHtml(e.message) + '</div>';
        }
    },

    _selectMarketSkill(marketSkill, element, already) {
        document.querySelectorAll('#skills-list .skill-item').forEach(i => i.classList.remove('selected'));
        element.classList.add('selected');
        document.getElementById('skill-detail-title').textContent = marketSkill.name;
        const detail = document.getElementById('skills-detail');
        detail.innerHTML = `
            <div class="detail-section">
                <div class="detail-section-title">市场元数据</div>
                <div class="detail-section-content" style="line-height: 1.8; color: var(--text-primary); font-size: 13px;">
                    <div>作者：${escapeHtml(marketSkill.author)}</div>
                    <div>版本：v${escapeHtml(marketSkill.version)}</div>
                    <div>状态：<span class="type-badge ADMIN">${escapeHtml(marketSkill.status)}</span></div>
                </div>
            </div>
            <div class="detail-section">
                <div class="detail-section-title">描述</div>
                <div class="detail-section-content">${escapeHtml(marketSkill.description || '无')}</div>
            </div>
            <div class="detail-section">
                <div class="detail-section-title">内容</div>
                <div class="detail-section-content" style="max-height: 300px; overflow: auto; background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-family: var(--font-mono, monospace); font-size: 12px; white-space: pre-wrap;">${escapeHtml(marketSkill.content || '')}</div>
            </div>
            <div style="margin-top: 24px;">
                <button class="send-skill-btn" id="pull-skill-btn" style="flex: 1;">${already ? '已拉取（点击更新）' : '拉取到我的 Skill'}</button>
            </div>
        `;
        detail.querySelector('#pull-skill-btn').addEventListener('click', () => this.handlePull(marketSkill));
    },

    async handlePull(marketSkill) {
        try {
            await api.pullMarketSkill(marketSkill.id);
            showToast(`已${marketSkill.name ? '拉取 / 更新' : '拉取'}「${marketSkill.name}」`, 'success');
            // 切到"我的" Tab
            this.currentTab = 'mine';
            document.querySelectorAll('#skills-modal-overlay .skill-tab').forEach(b => {
                const active = b.getAttribute('data-tab') === this.currentTab;
                b.classList.toggle('active', active);
                b.style.borderBottomColor = active ? 'var(--primary-color)' : 'transparent';
                b.style.color = active ? 'var(--primary-color)' : 'var(--text-muted)';
            });
            this.renderModal();
        } catch (e) {
            showToast('拉取失败：' + e.message, 'error');
        }
    },

    _renderSubmitTab(container, detail) {
        // 列出自建的 Skill（source=USER_CREATED）作为"可选提交"
        api.listSkills().then(mine => {
            const userCreated = (mine || []).filter(s => s.source === 'USER_CREATED');
            container.innerHTML = '';
            if (userCreated.length === 0) {
                container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">还没有自建 Skill。<br>切到「我的」→ 新建一个再来提交</div>';
                detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">请选择要提交到市场的自建 Skill</div>';
                return;
            }
            for (const s of userCreated) {
                const item = document.createElement('div');
                item.className = 'skill-item';
                item.innerHTML = `
                    <div class="skill-item-name">${escapeHtml(s.name)}</div>
                    <div class="skill-item-desc">${escapeHtml(s.description || '')}</div>
                `;
                item.addEventListener('click', () => this._showSubmitForm(s, detail));
                container.appendChild(item);
            }
            detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 14px; margin-bottom: 8px;">从左侧选一个自建 Skill 提交到市场</p><p style="font-size: 12px; color: var(--text-muted);">提交后状态为 PENDING，<br>需管理员在控制台「Skill 市场」审批通过后，<br>其他用户才能在「市场」Tab 拉取。</p></div>';
        });
    },

    _statusLabel(status) {
        switch (status) {
            case 'PENDING':   return {text: '审核中', bg: '#fef3c7', color: '#92400e'};
            case 'APPROVED':  return {text: '已通过', bg: '#d1fae5', color: '#065f46'};
            case 'REJECTED':  return {text: '已拒绝', bg: '#fee2e2', color: '#991b1b'};
            default:          return {text: status, bg: '#f1f5f9', color: '#475569'};
        }
    },

    _renderMySubmissions(container, detail) {
        api.listMySubmittedSkills().then(data => {
            container.innerHTML = '';
            if (!data || data.length === 0) {
                container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;">还没有向市场发布 Skill<br><br>切到「提交」Tab 从自建 Skill 发起提交</div>';
                detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择左侧 Skill 查看详情</div>';
                return;
            }
            for (const s of data) {
                const item = document.createElement('div');
                item.className = 'skill-item';
                const st = this._statusLabel(s.status);
                item.innerHTML = `
                    <div class="skill-item-name">${escapeHtml(s.name)} <span class="skill-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></div>
                    <div class="skill-item-desc">${escapeHtml(s.description || '')}</div>
                `;
                item.addEventListener('click', () => this._showMySubmissionDetail(s, item, detail));
                container.appendChild(item);
            }
            detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个 Skill 查看详情</div>';
        });
    },

    _showMySubmissionDetail(skill, element, detail) {
        document.querySelectorAll('#skills-list .skill-item').forEach(i => i.classList.remove('selected'));
        element.classList.add('selected');
        const st = this._statusLabel(skill.status);
        let html = `
            <div style="display: flex; flex-direction: column; gap: 16px;">
                <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
                    <div>名称：${escapeHtml(skill.name)}</div>
                    <div>版本：v${escapeHtml(skill.version)}</div>
                    <div>状态：<span class="skill-source-tag" style="background:${st.bg};color:${st.color};">${st.text}</span></div>
                    <div>提交时间：${skill.submittedAt ? new Date(skill.submittedAt).toLocaleString() : '-'}</div>
                    ${skill.reviewedAt ? '<div>审核时间：' + new Date(skill.reviewedAt).toLocaleString() + '</div>' : ''}
                    ${skill.reviewedBy ? '<div>审核人：' + escapeHtml(skill.reviewedBy) + '</div>' : ''}
                    ${skill.reviewComment ? '<div>审核意见：' + escapeHtml(skill.reviewComment) + '</div>' : ''}
                </div>
                <div style="font-size: 13px; color: var(--text-muted);">${escapeHtml(skill.description || '无说明')}</div>
                <div class="detail-section-content" style="max-height: 300px; overflow: auto; background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-family: var(--font-mono, monospace); font-size: 12px; white-space: pre-wrap;">${escapeHtml(skill.content || '')}</div>
        `;
        if (skill.status === 'PENDING') {
            html += `
                <div style="display: flex; gap: 12px; margin-top: 8px;">
                    <button class="send-skill-btn" id="withdraw-skill-btn" style="flex: 1; background: var(--warning-color, #f59e0b);">撤回提交</button>
                </div>
            `;
        }
        html += '</div>';
        detail.innerHTML = html;

        const withdrawBtn = document.getElementById('withdraw-skill-btn');
        if (withdrawBtn) {
            withdrawBtn.addEventListener('click', () => this.handleWithdraw(skill));
        }
    },

    async handleWithdraw(skill) {
        if (!confirm(`确认撤回「${skill.name}」的提交？`)) return;
        try {
            await api.withdrawMarketSkill(skill.id);
            showToast(`已撤回「${skill.name}」`, 'success');
            this.renderModal();
        } catch (e) {
            showToast('撤回失败：' + e.message, 'error');
        }
    },

    _showSubmitForm(skill, detail) {
        document.querySelectorAll('#skills-list .skill-item').forEach(i => i.classList.remove('selected'));
        detail.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 16px;">
                <div style="background: var(--bg-secondary); padding: 12px; border-radius: 6px; font-size: 12px; color: var(--text-muted);">
                    提交「<strong>${escapeHtml(skill.name)}</strong>」到市场。审批通过后其他用户可在「市场」Tab 拉取；<br>
                    你的本地实例保持不变，提交不影响你的使用。
                </div>
                <div>
                    <label class="param-label">版本号 <span style="color: var(--error-color);">*</span></label>
                    <input type="text" id="submit-skill-version" class="param-input" placeholder="例如 1.0.0（语义化版本）" value="1.0.0">
                </div>
                <div style="font-size: 12px; color: var(--text-muted);">
                    提交后状态为 PENDING。同名+同版本号不能重复提交。
                </div>
                <div style="display: flex; gap: 12px;">
                    <button class="send-skill-btn" id="submit-confirm-btn" style="flex: 1;">提交到市场</button>
                </div>
            </div>
        `;
        detail.querySelector('#submit-confirm-btn').addEventListener('click', () => this.handleSubmit(skill));
    },

    async handleSubmit(skill) {
        const version = document.getElementById('submit-skill-version')?.value?.trim();
        if (!version) { showToast('请输入版本号', 'error'); return; }
        try {
            await api.submitMarketSkill({
                name: skill.name,
                description: skill.description,
                content: skill.content,
                version: version,
            });
            showToast(`已提交「${skill.name}」v${version}，等待管理员审批`, 'success');
            this.renderModal();
        } catch (e) {
            showToast('提交失败：' + e.message, 'error');
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
        const allItems = document.querySelectorAll('#skills-list .skill-item');
        allItems.forEach(i => i.classList.remove('selected'));
        element.classList.add('selected');

        document.getElementById('skill-detail-title').textContent = skill.name;
        const detail = document.getElementById('skills-detail');
        // 是否可编辑：USER_CREATED 完全可改；MARKET_PULLED 可改 desc + defaultLoaded；ROLE_GRANTED / MARKET_VIEW 只读
        const isRoleGranted = skill.source === 'ROLE_GRANTED';
        const isMarketView = skill.source === 'MARKET_VIEW';   // admin 特权视图（市场 APPROVED）
        const isPulled = skill.source === 'MARKET_PULLED';      // 自取
        const canEdit = skill.source === 'USER_CREATED' || isPulled;
        const canDelete = canEdit;
        const lbl = this._sourceLabel(skill.source);
        let html = '';

        // 来源 + 状态
        html += `<div class="detail-section">
            <div class="detail-section-title">来源 / 状态</div>
            <div class="detail-section-content" style="font-size: 13px;">
                <span class="skill-source-tag" style="background:${lbl.bg};color:${lbl.color};">${lbl.text}</span>
                <span style="margin-left: 12px; color: ${skill.load ? 'var(--success-color, #22c55e)' : 'var(--text-muted)'};">
                    ${skill.load ? '已加载' : '未加载'}
                </span>
                ${isRoleGranted ? ' · <span style="color: var(--text-muted)">已被角色授权锁定，不可编辑</span>' : ''}
                ${isMarketView ? ' · <span style="color: var(--text-muted)">市场视图（admin 特权），可去「市场」Tab 拉取</span>' : ''}
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
                <button class="send-skill-btn" id="edit-skill-btn" style="flex: 1;">${isPulled ? '编辑描述 / 默认加载' : '编辑技能'}</button>
                <button class="delete-skill-btn" id="delete-skill-btn" style="flex: 1; background: var(--error-color, #ef4444);">删除技能</button>
            </div>`;
        }

        // Action buttons: 应用 / 复制（所有 source 都能用）
        html += `<div style="margin-top: 12px; display: flex; gap: 12px;">
            <button class="send-skill-btn" id="apply-skill-btn" style="flex: 1;">应用</button>
            <button class="send-skill-btn" id="copy-skill-btn" style="flex: 1; background: var(--bg-secondary); color: var(--text-primary); border: 2px solid var(--border-color);">复制</button>
        </div>`;

        detail.innerHTML = html;

        const editBtn = detail.querySelector('#edit-skill-btn');
        if (editBtn) editBtn.addEventListener('click', () => this.showEditForm(skill));

        const deleteBtn = detail.querySelector('#delete-skill-btn');
        if (deleteBtn) deleteBtn.addEventListener('click', () => this.handleDelete(skill));

        detail.querySelector('#apply-skill-btn').addEventListener('click', () => this.apply(skill, {}));
        detail.querySelector('#copy-skill-btn').addEventListener('click', () => this.copyToTextarea(skill, {}));
    },

    showEditForm(skill) {
        this.editingSkill = skill;
        document.getElementById('skill-detail-title').textContent = '编辑技能';
        const detail = document.getElementById('skills-detail');
        // MARKET_PULLED 不能改 content（content 来自市场快照），只能改 desc + defaultLoaded
        const isPulled = skill.source === 'MARKET_PULLED';
        const contentReadonly = isPulled;
        detail.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 16px;">
                <div>
                    <label class="param-label">技能名称</label>
                    <input type="text" id="edit-skill-name" class="param-input" value="${escapeHtml(skill.name)}" disabled placeholder="例如：周报生成">
                </div>
                <div>
                    <label class="param-label">技能描述</label>
                    <input type="text" id="edit-skill-desc" class="param-input" value="${escapeHtml(skill.description || '')}" placeholder="简要描述技能的功能">
                </div>
                <div>
                    <label class="param-label">技能内容（Prompt 模板）${contentReadonly ? ' <span style="font-size: 11px; color: var(--text-muted);">（市场拉取的 Skill 不可改；想更新请去「市场」Tab 重新拉取）</span>' : ''}</label>
                    <textarea id="edit-skill-content" class="param-input param-textarea" style="min-height: 200px; font-family: var(--font-mono, monospace); font-size: 13px;" placeholder="技能内容模板，支持 {param} 占位符"${contentReadonly ? ' disabled' : ''}>${escapeHtml(skill.content || '')}</textarea>
                </div>
                <div style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" id="edit-skill-load" ${skill.load ? 'checked' : ''}>
                    <label for="edit-skill-load">加载此技能</label>
                </div>
                <div style="margin-top: 8px; display: flex; gap: 12px;">
                    <button class="send-skill-btn" id="save-skill-btn" style="flex: 1;">保存</button>
                    <button class="send-skill-btn" id="cancel-edit-btn" style="flex: 1; background: var(--text-muted);">取消</button>
                </div>
            </div>
        `;

        detail.querySelector('#save-skill-btn').addEventListener('click', () => this.handleSave(skill));
        detail.querySelector('#cancel-edit-btn').addEventListener('click', () => {
            this.editingSkill = null;
            const selectedItem = document.querySelector('#skills-list .skill-item.selected');
            if (selectedItem) this.select(skill, selectedItem);
        });
    },

    showCreateForm() {
        this.editingSkill = null;
        document.getElementById('skill-detail-title').textContent = '新增技能';
        // Clear selection
        document.querySelectorAll('#skills-list .skill-item').forEach(i => i.classList.remove('selected'));
        state.selectedSkill = null;
        const detail = document.getElementById('skills-detail');
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

        detail.querySelector('#save-skill-btn').addEventListener('click', () => this.handleCreate());
        detail.querySelector('#cancel-edit-btn').addEventListener('click', () => {
            this.editingSkill = null;
            document.getElementById('skill-detail-title').textContent = '';
            detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个技能查看详情，或点击「新增」创建新技能</p></div>';
        });
    },

    async handleCreate() {
        const name = document.getElementById('edit-skill-name')?.value?.trim();
        const description = document.getElementById('edit-skill-desc')?.value?.trim();
        const content = document.getElementById('edit-skill-content')?.value?.trim();
        const load = document.getElementById('edit-skill-load')?.checked ?? true;

        if (!name) { showToast('请输入技能名称', 'error'); return; }
        if (!content) { showToast('请输入技能内容', 'error'); return; }

        const result = await api.createSkill({ name, description, load, content });
        if (result !== null) {
            showToast('技能创建成功', 'success');
            this.editingSkill = null;
            this.renderModal();
        } else {
            showToast('创建失败，请重试', 'error');
        }
    },

    async handleSave(originalSkill) {
        const name = document.getElementById('edit-skill-name')?.value?.trim();
        const description = document.getElementById('edit-skill-desc')?.value?.trim();
        const content = document.getElementById('edit-skill-content')?.value?.trim();
        const load = document.getElementById('edit-skill-load')?.checked ?? true;

        if (!name) { showToast('请输入技能名称', 'error'); return; }
        if (!content) { showToast('请输入技能内容', 'error'); return; }

        let result;
        if (originalSkill.source === 'MARKET_PULLED') {
            // 自取的 Skill：仅改 desc + defaultLoaded（PATCH）
            result = await api.patchSkill(name, {description, defaultLoaded: load});
        } else {
            // 自建：全字段保存
            result = await api.updateSkill({ name, description, load, content });
        }
        if (result !== null) {
            showToast('技能保存成功', 'success');
            this.editingSkill = null;
            this.renderModal();
        } else {
            showToast('保存失败，请重试', 'error');
        }
    },

    async handleDelete(skill) {
        if (!confirm(`确定要删除技能「${skill.name}」吗？此操作不可撤销。`)) return;

        const result = await api.deleteSkill(skill.name);
        if (result !== null) {
            showToast('技能已删除', 'success');
            this.renderModal();
        } else {
            showToast('删除失败，请重试', 'error');
        }
    },

    send(skill, skillParams) {
        // Build prompt
        let promptContent = skill.content;
        if (skill.params && skill.params.length > 0) {
            for (const param of skill.params) {
                const regex = new RegExp(`\\{${param.name}\\}`, 'g');
                promptContent = promptContent.replace(regex, skillParams[param.name] || '');
            }
        }

        this.closeModal();
        document.getElementById('textarea').value = promptContent;

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
        const ta = document.getElementById('textarea');
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
        if (typeof chat !== 'undefined' && chat.send) {
            chat.send();
        }
    },

    /** "复制" 按钮：覆盖 textarea 内容（不发送，不追加） */
    copyToTextarea(skill, skillParams) {
        const promptContent = this._buildPrompt(skill, skillParams);
        this.closeModal();
        const ta = document.getElementById('textarea');
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
                const regex = new RegExp(`\\{${param.name}\\}`, 'g');
                promptContent = promptContent.replace(regex, skillParams[param.name] || '');
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
        const sidebar = document.getElementById('sidebar');
        const toggle = document.getElementById('sidebar-toggle');
        if (window.innerWidth < 768) {
            toggle.style.display = 'flex';
            sidebar.classList.remove('open');
            toggle.textContent = '☰';
        } else if (window.innerWidth < 1200) {
            toggle.style.display = 'none';
            sidebar.classList.remove('open');
        } else {
            toggle.style.display = 'none';
        }
    },
};

// ===================== §15 File Upload Module =====================
const imageUpload = {
    // Supported MIME types: images + common documents
    ALLOWED_TYPES: [
        // images
        'image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp',
        // documents
        'application/pdf',
        'application/msword',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.ms-excel',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.ms-powerpoint',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        'text/plain',
        'text/csv',
        'text/markdown',
    ],
    MAX_SIZE: 10 * 1024 * 1024, // 10MB

    // Map file extensions to document icon SVG (for non-image types)
    DOC_ICONS: {
        '.pdf':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#ef4444" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PDF</text></svg>`,
        '.doc':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#3b82f6" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">DOC</text></svg>`,
        '.docx': `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#3b82f6" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">DOC</text></svg>`,
        '.xls':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#10b981" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">XLS</text></svg>`,
        '.xlsx': `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#10b981" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">XLS</text></svg>`,
        '.ppt':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#f59e0b" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PPT</text></svg>`,
        '.pptx': `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#f59e0b" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">PPT</text></svg>`,
        '.txt':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">TXT</text></svg>`,
        '.csv':  `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="11" font-weight="700" font-family="Inter,sans-serif">CSV</text></svg>`,
        '.md':   `<svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="8" y="4" width="32" height="40" rx="3" fill="#6b7280" opacity="0.9"/><path d="M8 16h32" stroke="#fff" stroke-opacity="0.3" stroke-width="1"/><text x="24" y="32" text-anchor="middle" fill="#fff" font-size="10" font-weight="700" font-family="Inter,sans-serif">MD</text></svg>`,
    },

    /** Get icon SVG for a given file extension, or null if it's an image */
    getDocIcon(file) {
        const ext = '.' + file.name.split('.').pop().toLowerCase();
        if (file.type.startsWith('image/')) return null; // image → show thumbnail
        return this.DOC_ICONS[ext] || this.DOC_ICONS['.txt'] || null;
    },

    /** Check if a file is an image */
    isImage(file) {
        return file.type.startsWith('image/');
    },

    init() {
        const addBtn = document.getElementById('image-add-btn');
        const fileInput = document.getElementById('image-file-input');

        addBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', (e) => this.handleFiles(e));
    },

    validate(file) {
        if (!this.ALLOWED_TYPES.includes(file.type)) {
            return { valid: false, error: '不支持的文件格式，请选择图片或常见文档格式' };
        }
        if (file.size > this.MAX_SIZE) {
            return { valid: false, error: '文件大小不能超过 10MB' };
        }
        return { valid: true };
    },

    async handleFiles(event) {
        const input = event.target;
        const files = Array.from(input.files || []);
        if (files.length === 0) return;

        // Reset file input so same file can be selected again
        input.value = '';

        for (const file of files) {
            const validation = this.validate(file);
            if (!validation.valid) {
                showToast(validation.error, 'error');
                continue;
            }

            // Defensive double-check: if the browser silently dropped the
            // type (some file pickers filter even without `accept`),
            // surface an honest error rather than a silent no-op.
            if (file.size === 0 && file.name && !file.type) {
                showToast(`文件「${file.name}」无法识别，已忽略`, 'error');
                continue;
            }

            const isImage = this.isImage(file);
            const objectUrl = isImage ? URL.createObjectURL(file) : null;
            const docIcon = isImage ? null : this.getDocIcon(file);
            const tempId = this.renderThumbnail(null, objectUrl, file.name, docIcon);

            // Upload to server
            try {
                const { fileId } = await api.uploadImage(file);
                const entry = state.pendingImages.find(img => img.thumbId === tempId);
                if (entry) {
                    entry.fileId = fileId;
                }
                this.updateThumbnailFileId(tempId, fileId);
            } catch (err) {
                if (objectUrl) URL.revokeObjectURL(objectUrl);
                this.removeImageByObjectUrl(objectUrl || tempId);
                showToast('文件上传失败：' + err.message, 'error');
            }
        }
    },

    renderThumbnail(fileId, objectUrl, fileName, docIcon) {
        const uploadArea = document.getElementById('image-upload-area');
        const container = document.getElementById('image-thumbnails');

        uploadArea.style.display = 'block';

        const thumbId = 'thumb-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
        const div = document.createElement('div');
        div.className = 'image-thumbnail' + (docIcon ? ' has-doc-icon' : '');
        div.id = thumbId;

        // Document icon or image
        const safeName = escapeHtml(fileName || '');
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
            div.addEventListener('dblclick', () => {
                this.showFullscreen(objectUrl);
            });
        }

        // Remove button
        div.querySelector('.thumbnail-remove').addEventListener('click', (e) => {
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
        const loading = thumb.querySelector('.thumbnail-loading');
        if (loading) loading.style.display = 'none';
    },

    removeImageById(thumbId, uniqueKey) {
        // Remove from state — match by objectUrl or thumbId
        state.pendingImages = state.pendingImages.filter(img => {
            if (img.objectUrl) return img.objectUrl !== uniqueKey;
            return img.thumbId !== uniqueKey;
        });
        if (uniqueKey.startsWith('data:') || uniqueKey.startsWith('blob:')) {
            URL.revokeObjectURL(uniqueKey);
        }

        // Remove from DOM
        const thumb = document.getElementById(thumbId);
        if (thumb) thumb.remove();

        // Hide area if no images left
        if (state.pendingImages.length === 0) {
            document.getElementById('image-upload-area').style.display = 'none';
        }
    },

    removeImageByObjectUrl(uniqueKey) {
        // For documents (no objectUrl), match by thumbId
        if (!uniqueKey || (!uniqueKey.startsWith('blob:') && !uniqueKey.startsWith('data:'))) {
            const entry = state.pendingImages.find(img => img.thumbId === uniqueKey);
            if (entry) this.removeImageById(entry.thumbId, uniqueKey);
            return;
        }
        // For images, match by objectUrl
        const entry = state.pendingImages.find(img => img.objectUrl === uniqueKey);
        if (!entry) return;
        // Find DOM element by img src
        const container = document.getElementById('image-thumbnails');
        for (const thumb of container.querySelectorAll('.image-thumbnail')) {
            const img = thumb.querySelector('img');
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
        document.getElementById('image-thumbnails').innerHTML = '';
        document.getElementById('image-upload-area').style.display = 'none';
    },

    clear() {
        this.remove();
    },

    showFullscreen(objectUrl) {
        const overlay = document.getElementById('image-viewer-overlay');
        const image = document.getElementById('viewer-image');
        image.src = objectUrl;
        overlay.style.display = 'flex';
    },

    hideFullscreen() {
        const overlay = document.getElementById('image-viewer-overlay');
        const image = document.getElementById('viewer-image');
        overlay.style.display = 'none';
        image.src = '';
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
        console.warn('[init] mcp.loadList failed, continuing:', e);
    }

    try {
        const uploadOk = await api.checkKnowledgeUpload();
        if (uploadOk) {
            const ib = document.getElementById('image-add-btn');
            if (ib) ib.style.display = 'flex';
        }
    } catch (e) {
        console.warn('[init] checkKnowledgeUpload failed, continuing:', e);
    }

    try {
        await conversation.loadList();
    } catch (e) {
        console.warn('[init] conversation.loadList failed, continuing:', e);
    }

    // Keep the initial canvas empty so users explicitly create the first persisted conversation.
    // chat.send() below provides a defensive fallback for Enter/send calls without a current id.
};

const bindAllEvents = () => {
    const safeBind = (sel, type, handler) => {
        const e = typeof sel === 'string' ? document.querySelector(sel) : sel;
        if (e && handler) e.addEventListener(type, handler);
    };
    const safeBindById = (id, type, handler) => {
        if (id) safeBind(document.getElementById(id), type, handler);
    };

    const ta = document.getElementById('textarea');
    safeBind(ta, 'keydown', (event) => {
        if (event.key === 'Enter' && event.ctrlKey) {
            event.preventDefault();
            const start = ta.selectionStart;
            const end = ta.selectionEnd;
            ta.value = ta.value.substring(0, start) + '\n' + ta.value.substring(end);
            ta.setSelectionRange(start + 1, start + 1);
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey) {
            event.preventDefault();
            chat.send();
        }
    });

    safeBindById('send-btn', 'click', () => chat.send());
    safeBindById('stop-btn', 'click', () => chat.stopStream());

    // Modal overlay click-to-close
    safeBindById('mcp-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) mcp.closeModal();
    });
    safeBindById('skills-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) skills.closeModal();
    });
    safeBindById('ks-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) knowledge.closePanel();
    });
    safeBindById('file-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) fileMgr.closeModal();
    });
    safeBindById('subtask-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) subtaskPanel.closeModal();
    });
    safeBindById('schedule-modal-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) schedulePanel.closeModal();
    });

    // Image upload
    if (typeof imageUpload.init === 'function') imageUpload.init();

    safeBindById('image-viewer-overlay', 'click', (e) => {
        if (e.target === e.currentTarget) imageUpload.hideFullscreen();
    });
    safeBindById('viewer-close', 'click', () => imageUpload.hideFullscreen());
    safeBind(document, 'keydown', (e) => {
        if (e.key === 'Escape') imageUpload.hideFullscreen();
    });

    // Responsive
    responsive.handleResize();
    window.addEventListener('resize', responsive.handleResize);

    // Top-level buttons (replace inline onclick handlers from ES module scope)
    const addIf = (sel, handler) => {
        const e = document.querySelector(sel);
        if (e) e.addEventListener('click', handler);
    };
    addIf('#new-chat-btn', () => conversation.createNew());
    addIf('#sidebar-toggle', () => ui.toggleSidebar());
    addIf('#ks-button', () => knowledge.openPanel());
    addIf('#mcp-button', () => mcp.openModal());
    addIf('#skills-button', () => skills.openModal());
    addIf('#file-manager-button', () => fileMgr.openModal());
    addIf('#file-close-btn', () => fileMgr.closeModal());
    addIf('#subtask-button', () => subtaskPanel.openModal());
    addIf('#subtask-close-btn', () => subtaskPanel.closeModal());
    addIf('#schedule-button', () => schedulePanel.openModal());
    addIf('#schedule-close-btn', () => schedulePanel.closeModal());
    addIf('#mcp-close-btn', () => mcp.closeModal());
    addIf('#skills-close-btn', () => skills.closeModal());
    addIf('#skill-add-btn', () => skills.showCreateForm());
    addIf('.ks-create-btn', () => knowledge.create());
    addIf('#ks-modal-overlay .close-button', () => knowledge.closePanel());
};

// Init trigger — type="module" scripts are deferred, so by the time this
// module evaluates, DOMContentLoaded may have ALREADY FIRED. In that case the
// listener below would never trigger and the page would be a static shell.
// Guard with document.readyState.
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

// Expose to global for testing/debugging
window._loomAgent = {state, api, imageUpload, auth, chat, conversation, ui, fileMgr};
window.ui = ui;

// ===================== §11 File Download/Preview Globals =====================
window.previewFile = (fileId) => {
    window.open(`/spring/ai/loom/api/file/${fileId}/preview`, '_blank');
};

window.downloadFile = (fileId) => {
    const link = document.createElement('a');
    link.href = `/spring/ai/loom/api/file/${fileId}/download`;
    link.download = '';
    link.click();
};
