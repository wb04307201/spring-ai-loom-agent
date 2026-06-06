/**
 * app.js — Spring AI LoomAgent Frontend
 * 14-partition modular architecture.
 * §4 API service layer = only fetch() calls.
 * §12 UI components = only DOM manipulation.
 * §2 Global state = only state writes.
 */

// ===================== §1 Constants & Configuration =====================
const API_PREFIX = '';
const API = {
    autoLogin: '/spring/ai/loom/user/isAutoLogin',
    login: '/spring/ai/loom/user/login',
    listConversations: '/spring/ai/loom/conversation',
    getConversation: (id) => `/spring/ai/loom/conversation/${id}`,
    deleteConversation: (id) => `/spring/ai/loom/conversation/${id}`,
    stream: '/spring/ai/loom/stream',
    listMcps: '/spring/ai/chat/loom/mcp',
    listSkills: '/spring/ai/loom/skill',
    getSkill: (name) => `/spring/ai/loom/skill/${name}`,
    createSkill: '/spring/ai/loom/skill',
    updateSkill: '/spring/ai/loom/skill',
    deleteSkill: (name) => `/spring/ai/loom/skill/${name}`,
    listKnowledge: '/spring/ai/loom/knowledge',
    createKnowledge: '/spring/ai/loom/knowledge',
    deleteKnowledge: (id) => `/spring/ai/loom/knowledge/${id}`,
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
    conversationId: null,
    selectedMcps: [],
    selectedKnowledgeId: null,
    selectedSkill: null,
    enableRag: false,
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
    const resp = await fetch(url, options);
    if (resp.status === 401 && state.username) {
        // Session expired or invalidated — clear client-side state
        auth.clear();
    }
    return resp;
}

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
        // Parse markdown normally, then post-process all links to open in new tab
        const html = marked.parse(text);
        return html.replace(/<a\s/g, '<a target="_blank" rel="noopener noreferrer" ');
    }
    catch { return text; }
}

// ===================== §4 API Service Layer =====================
// All requests rely on HttpOnly session cookie for auth (BFF pattern).
// No Authorization header is sent from the client.
const api = {
    async autoLogin() {
        const r = await fetch(API.autoLogin, { method: 'POST', headers: { 'Content-Type': 'application/json' } });
        return r.ok ? r.json() : null;
    },
    async login(req) {
        const r = await fetch(API.login, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(req),
        });
        return r.ok ? r.json() : null;
    },
    async listConversations() {
        const r = await apiFetch(API.listConversations);
        return r.ok ? r.json() : [];
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
        return r.ok ? r.json() : [];
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
    async listKnowledge() {
        const r = await apiFetch(API.listKnowledge);
        return r.ok ? r.json() : [];
    },
    async createKnowledge(name) {
        const r = await apiFetch(API.createKnowledge, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name }),
        });
        return r.ok ? r.json() : null;
    },
    async deleteKnowledge(id) {
        const r = await apiFetch(API.deleteKnowledge(id), {method: 'DELETE'});
        return r.ok ? r.json() : null;
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
    async listAllFiles() {
        const r = await apiFetch('/spring/ai/loom/file');
        return r.ok ? r.json() : [];
    },
    async deleteFile(fileId) {
        const r = await apiFetch(`/spring/ai/loom/file/${fileId}`, {method: 'DELETE'});
        return r.ok;
    },
    async streamChat(record, onChunk, onComplete, onError) {
        const resp = await apiFetch(API.stream, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(record),
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
        await read();
    },
};

// ===================== §5 Auth Module =====================
const auth = {

    /** Initialize auth state on page load.
     *  BFF pattern: browser automatically sends session cookie.
     *  1. Call isAutoLogin → checks server-side session validity
     *  2. If true (supports auto-login or has valid session) → auto-login
     *  3. Else show "未登录"
     */
    async init() {
        try {
            const autoLogin = await api.autoLogin();
            if (autoLogin === true) {
                const data = await api.login({username: '', verified: ''});
                if (data) {
                    state.username = data.nickname || '用户';
                    this.renderUserState(data.nickname || '用户');
                    return true;
                }
            }
            this.renderLoggedOut();
        } catch (e) {
            this.renderLoggedOut();
        }
        return false;
    },

    /** Render the user state in the top-right corner */
    renderUserState(nickname) {
        let el = document.getElementById('user-info-display');
        if (!el) {
            el = document.createElement('div');
            el.id = 'user-info-display';
            const headerActions = document.querySelector('.header-actions');
            if (headerActions) {
                headerActions.prepend(el);
            }
        }
        el.textContent = nickname;
        el.className = 'user-info-display';
        el.onclick = () => this.showLoggedOutMessage();
    },

    /** Show "未登录" in the top-right corner */
    renderLoggedOut() {
        let el = document.getElementById('user-info-display');
        if (!el) {
            el = document.createElement('div');
            el.id = 'user-info-display';
            const headerActions = document.querySelector('.header-actions');
            if (headerActions) {
                headerActions.prepend(el);
            }
        }
        el.textContent = '未登录';
        el.className = 'user-info-display user-logged-out';
        el.onclick = () => this.showLoggedOutMessage();
    },

    /** Show login modal when clicking "未登录" */
    showLoggedOutMessage() {
        if (state.username) return; // already logged in
        let modal = document.getElementById('login-modal');
        if (!modal) {
            modal = document.createElement('div');
            modal.id = 'login-modal';
            modal.className = 'modal-overlay';
            modal.innerHTML = `
                <div class="modal-content" style="max-width: 400px;">
                    <div class="modal-header">
                        <h3>登录</h3>
                        <div class="close-button" id="login-modal-close">&times;</div>
                    </div>
                    <div class="modal-body">
                        <div style="margin-bottom: 16px;">
                            <label style="display: block; margin-bottom: 6px; font-size: 14px; color: var(--text-secondary);">用户名</label>
                            <input type="text" id="login-username-input" placeholder="请输入用户名" style="width: 100%; padding: 10px 12px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; box-sizing: border-box;" />
                        </div>
                        <div style="margin-bottom: 24px;">
                            <label style="display: block; margin-bottom: 6px; font-size: 14px; color: var(--text-secondary);">验证码</label>
                            <input type="password" id="login-verified-input" placeholder="请输入验证码" style="width: 100%; padding: 10px 12px; border: 1px solid var(--border-color); border-radius: 6px; font-size: 14px; box-sizing: border-box;" />
                        </div>
                        <button id="login-submit-btn" style="width: 100%; padding: 10px; background: var(--primary-color); color: #fff; border: none; border-radius: 6px; font-size: 14px; cursor: pointer;">登录</button>
                    </div>
                </div>
            `;
            modal.addEventListener('click', (e) => {
                if (e.target === modal) modal.style.display = 'none';
            });
            document.body.appendChild(modal);

            // Bind events
            modal.querySelector('#login-modal-close').addEventListener('click', () => {
                modal.style.display = 'none';
            });
            modal.querySelector('#login-submit-btn').addEventListener('click', async () => {
                const username = modal.querySelector('#login-username-input').value.trim();
                const verified = modal.querySelector('#login-verified-input').value.trim();
                if (!username) {
                    showToast('请输入用户名', 'error');
                    return;
                }
                const data = await api.login({username, verified});
                if (data) {
                    state.username = data.nickname || username;
                    this.renderUserState(data.nickname || username);
                    modal.style.display = 'none';
                    showToast('登录成功', 'success');
                    // Reload data after login
                    await mcp.loadList();
                    await skills.loadList();
                    await conversation.loadList();
                } else {
                    showToast('登录失败，请检查用户名和验证码', 'error');
                }
            });

            // Enter key to submit
            modal.querySelector('#login-verified-input').addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    modal.querySelector('#login-submit-btn').click();
                }
            });
        }
        modal.style.display = 'flex';
        // Focus username input
        setTimeout(() => modal.querySelector('#login-username-input')?.focus(), 100);
    },

    /** Clear auth state — called on 401 or logout.
     *  Cookie is HttpOnly and managed by browser; clear client-side state only.
     */
    clear() {
        state.username = null;
        this.renderLoggedOut();
    },

    /** Logout: call server to invalidate session, then clear client state */
    async logout() {
        try {
            await fetch(API.login.replace('/login', '/logout'), {method: 'POST'});
        } catch { /* ignore */ }
        this.clear();
    },
};

// ===================== §12 UI Components (only DOM manipulation) =====================
const aiImage = '/static/ai.jpg';
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

    renderUserMessage(text) {
        const item = document.createElement('div');
        item.className = 'chat-item chat-item-right';
        item.innerHTML = `
            <div class="bubble"><div style="margin: 16px">${renderMarkdown(text)}</div></div>
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
    },

    disableSend() {
        const ta = document.getElementById('textarea');
        const btn = document.getElementById('send-btn');
        ta.value = '';
        ta.disabled = true;
        btn.disabled = true;
        btn.textContent = '发送中...';
        state.isStreaming = true;
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
};

// ===================== §6 Conversation Management =====================
const conversation = {
    async loadList() {
        const data = await api.listConversations();
        this.renderSidebar(data);
    },

    renderSidebar(list) {
        const container = document.getElementById('sidebarList');
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="sidebar-empty">暂无对话</div>';
            return;
        }
        container.innerHTML = '';
        for (const item of list) {
            const id = item.conversationId || item.id || item.id;
            const title = item.title || truncateText(item.name || '新对话', API.titleMaxLength);
            const div = document.createElement('div');
            div.className = 'sidebar-item' + (state.conversationId === id ? ' active' : '');
            div.innerHTML = `
                <span class="sidebar-item-text" title="${title}">${title}</span>
                <button class="sidebar-item-delete" title="删除对话">&times;</button>`;
            // Listen on parent .sidebar-item so it works in both full-width and collapsed (icon-only) modes
            div.addEventListener('click', (e) => {
                if (e.target.classList.contains('sidebar-item-delete')) return;
                this.switchTo(id);
            });
            div.querySelector('.sidebar-item-delete').addEventListener('click', (e) => {
                e.stopPropagation();
                this.delete(id);
            });
            container.appendChild(div);
        }
    },

    createNew() {
        state.conversationId = crypto.randomUUID();
        ui.clearChat();
        imageUpload.clear();
        // refresh sidebar highlight
        this.loadList();
    },

    async switchTo(id) {
        // abort any ongoing stream
        chat.abortStream();

        state.conversationId = id;
        try {
            const messages = await api.getConversationMessages(id);
            ui.renderMessages(messages);
        } catch (e) {
            showToast('加载对话失败', 'error');
        }
        this.loadList(); // re-render highlight
    },

    async delete(id) {
        if (!confirm('确定要删除这个对话吗？')) return;
        const ok = await api.deleteConversation(id);
        if (ok) {
            if (state.conversationId === id) {
                this.createNew();
            }
            this.loadList();
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

        ui.renderUserMessage(text);
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
            enableRag: state.enableRag,
            knowledgeId: state.selectedKnowledgeId || null,
            fileIds: state.pendingImages.length > 0 ? state.pendingImages.map(img => img.fileId) : null,
        };

        // Clear pending image after capturing fileId
        imageUpload.clear();

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
                        if (originEl) originEl.innerHTML = answerText;
                    }
                    ui.scrollToBottom();
                },
                () => {
                    // complete
                    const actionsEl = document.getElementById('actions-' + id);
                    if (actionsEl) actionsEl.style.display = '';
                    ui.enableSend();
                    conversation.loadList();
                },
                (error) => {
                    // error
                    const actionsEl = document.getElementById('actions-' + id);
                    if (actionsEl) actionsEl.style.display = '';
                    if (answerEl) answerEl.innerHTML += '<br/><span style="color:var(--error-color)">发送失败：' + (error.message || '未知错误') + '</span>';
                    ui.enableSend();
                }
            );
        } catch (error) {
            if (answerEl) answerEl.innerHTML += '<br/><span style="color:var(--error-color)">发送失败：' + error.message + '</span>';
            ui.enableSend();
        }
    },

    abortStream() {
        if (state.isStreaming) {
            ui.enableSend();
        }
    },
};

// ===================== §8 Knowledge Space =====================
const knowledge = {
    currentKbId: null,

    openPanel() {
        ui.showModal('ks-modal-overlay');
        this.loadList();
    },

    closePanel() {
        ui.hideModal('ks-modal-overlay');
    },

    async loadList() {
        const data = await api.listKnowledge();
        this.renderList(data);
    },

    renderList(list) {
        const container = document.getElementById('ks-sidebar');
        if (!list || list.length === 0) {
            container.innerHTML = '<div class="sidebar-empty" style="padding: 40px 16px;">暂无知识库</div>';
            return;
        }
        container.innerHTML = '';

        // "No knowledge base" option at top
        const noneDiv = document.createElement('div');
        noneDiv.className = 'ks-item' + (state.selectedKnowledgeId === null ? ' active' : '');
        noneDiv.innerHTML = `
            <input type="radio" name="ks-select" value="" ${state.selectedKnowledgeId === null ? 'checked' : ''} style="width: 16px; height: 16px; cursor: pointer; flex-shrink: 0;">
            <span class="ks-item-name">不使用知识库</span>`;
        noneDiv.querySelector('input[type="radio"]').addEventListener('change', () => this.selectKnowledgeForChat(null));
        noneDiv.querySelector('.ks-item-name').addEventListener('click', () => this.selectKnowledgeForChat(null));
        container.appendChild(noneDiv);

        for (const kb of list) {
            const id = kb.id;
            const name = kb.name;
            const div = document.createElement('div');
            div.className = 'ks-item' + (state.selectedKnowledgeId === id ? ' active' : '');
            div.innerHTML = `
                <input type="radio" name="ks-select" value="${id}" ${state.selectedKnowledgeId === id ? 'checked' : ''} style="width: 16px; height: 16px; cursor: pointer; flex-shrink: 0;">
                <span class="ks-item-name">${name}</span>
                <button class="ks-item-delete">&times;</button>`;
            div.querySelector('input[type="radio"]').addEventListener('change', () => {
                this.selectKnowledgeForChat(id);
                this.select(id, name);
            });
            div.querySelector('.ks-item-name').addEventListener('click', (e) => {
                e.stopPropagation();
                this.selectKnowledgeForChat(id);
                // Also open detail panel to show files
                this.select(id, name);
            });
            div.querySelector('.ks-item-delete').addEventListener('click', (e) => {
                e.stopPropagation();
                this.delete(id);
            });
            container.appendChild(div);
        }
    },

    /** Select a knowledge base for chat (single-select / radio behavior) */
    selectKnowledgeForChat(id) {
        state.selectedKnowledgeId = id;
        // Update active class on sidebar items directly (no re-render to preserve event listeners)
        const items = document.querySelectorAll('#ks-sidebar .ks-item');
        items.forEach(item => {
            const radio = item.querySelector('input[type="radio"]');
            if (radio) {
                const itemId = radio.value === '' ? null : radio.value;
                const isActive = itemId === id;
                item.classList.toggle('active', isActive);
                radio.checked = isActive;
            }
        });
        // If selecting "no knowledge base", clear the detail panel
        if (id === null) {
            const detail = document.getElementById('ks-detail');
            detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个知识库查看文件</div>';
        }
    },

    async create() {
        const name = prompt('请输入知识库名称：');
        if (!name || !name.trim()) return;
        const data = await api.createKnowledge(name.trim());
        if (data) {
            showToast('知识库创建成功', 'success');
            this.loadList();
        } else {
            showToast('创建失败', 'error');
        }
    },

    async delete(id) {
        if (!confirm('确定要删除这个知识库吗？关联文件将被一并移除。')) return;
        const ok = await api.deleteKnowledge(id);
        if (ok) {
            if (this.currentKbId === id) {
                this.currentKbId = null;
                document.getElementById('ks-detail').innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">选择一个知识库查看文件</div>';
            }
            this.loadList();
            showToast('知识库已删除', 'success');
        } else {
            showToast('删除失败', 'error');
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
                    <button class="ks-upload-btn" id="ks-upload-btn">+ 上传文件</button>
                    <input type="file" id="ks-file-input" style="display:none;">
                </div>
            </div>
            <div class="ks-file-list"><div class="loading-indicator">加载中...</div></div>`;

        const uploadBtn = detail.querySelector('#ks-upload-btn');
        const fileInput = detail.querySelector('#ks-file-input');
        uploadBtn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', (e) => this.uploadFile(id, e));

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
                    <td><button class="action-btn" data-file-id="${f.id}">删除</button></td>`;
                row.querySelector('.action-btn').addEventListener('click', () => this.deleteFile(kbId, f.id, row));
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
        this.loadList();
    },

    closeModal() {
        ui.hideModal('file-modal-overlay');
    },

    async loadList() {
        const files = await api.listAllFiles();
        this.renderList(files);
    },

    renderList(files) {
        const container = document.getElementById('file-list');
        if (!files || files.length === 0) {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);">暂无文件</div>';
            return;
        }
        let html = `
            <table class="knowledge-table">
                <thead><tr><th>文件名</th><th>来源</th><th>大小</th><th>上传时间</th><th>操作</th></tr></thead>
                <tbody></tbody>
            </table>`;
        container.innerHTML = html;
        const tbody = container.querySelector('tbody');
        for (const f of files) {
            const row = document.createElement('tr');
            const isGit = f.usage === 'git';
            const isToolOrUpload = f.usage === 'tool' || f.usage === 'upload';
            const usageLabel = f.usage === 'tool' ? 'tool' : f.usage === 'upload' ? 'upload' : f.usage === 'git' ? 'git' : (f.usage || '未知');
            row.innerHTML = `
                <td title="${f.fileName || ''}">${truncateText(f.fileName || f.path || '', 30)}</td>
                <td><code>${usageLabel}</code></td>
                <td>${isGit ? '—' : formatFileSize(f.size || 0)}</td>
                <td>${formatDate(f.uploadTime || f.createTime)}</td>
                <td>
                    ${isGit ? '<span style="color:var(--text-muted);font-size:12px;">git仓库</span>' : ''}
                    ${isToolOrUpload ? `
                        <button class="action-btn file-preview-btn" data-file-id="${f.id}">预览</button>
                        <button class="action-btn file-download-btn" data-file-id="${f.id}">下载</button>
                        <button class="action-btn file-delete-btn" data-file-id="${f.id}">删除</button>
                    ` : ''}
                </td>`;
            tbody.appendChild(row);
        }
        // Bind buttons
        for (const btn of container.querySelectorAll('.file-preview-btn')) {
            btn.addEventListener('click', () => this.preview(btn.dataset.fileId));
        }
        for (const btn of container.querySelectorAll('.file-download-btn')) {
            btn.addEventListener('click', () => this.download(btn.dataset.fileId));
        }
        for (const btn of container.querySelectorAll('.file-delete-btn')) {
            btn.addEventListener('click', () => this.delete(btn.dataset.fileId));
        }
    },

    preview(fileId) {
        // Open preview in new tab
        const url = window.location.origin + '/file/view/' + fileId;
        window.open(url, '_blank', 'noopener,noreferrer');
    },

    download(fileId) {
        // Trigger download in new tab
        const url = window.location.origin + '/spring/ai/loom/file/' + fileId + '/download';
        window.open(url, '_blank', 'noopener,noreferrer');
    },

    async delete(fileId) {
        if (!confirm('确定要删除这个文件吗？')) return;
        try {
            const ok = await api.deleteFile(fileId);
            if (ok) {
                showToast('文件已删除', 'success');
                this.loadList();
            } else {
                showToast('删除失败', 'error');
            }
        } catch (e) {
            showToast('删除失败：' + e.message, 'error');
        }
    },
};

// ===================== §9 MCP Service =====================
const mcp = {
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
        showToast(`已${state.selectedMcps.includes(name) ? '选中' : '取消'}MCP服务`, 'success');
    },

    showDetail(m) {
        document.getElementById('mcp-detail-title').textContent = m.title || m.name;
        const detail = document.getElementById('mcp-detail');
        let html = '';

        // Basic info
        html += `<div class="detail-section">
            <div class="detail-section-title">基本信息</div>
            <div style="line-height: 1.8; color: var(--text-primary);">
                <div style="margin-bottom: 12px;"><strong>名称：</strong>${m.name}</div>
                <div style="margin-bottom: 12px;"><strong>版本：</strong>${m.version || '1.0.0'}</div>
                <div><strong>描述：</strong>${m.description || '无描述'}</div>
            </div>
        </div>`;

        // Tools
        const tools = m.tools || [];
        html += `<div class="detail-section">
            <div class="detail-section-title">包含工具 (${tools.length})</div>
            <div style="display: flex; flex-direction: column; gap: 12px;">`;
        if (tools.length > 0) {
            for (const tool of tools) {
                html += `<div style="padding: 16px; background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 8px;">
                    <div style="font-weight: 600; font-size: 14px; color: var(--primary-color); margin-bottom: 8px;">${tool.title || tool.name}</div>
                    <div style="font-size: 13px; color: var(--text-secondary); line-height: 1.6; white-space: pre-wrap;">${tool.description || '无描述'}</div>
                </div>`;
            }
        } else {
            html += '<span style="color: var(--text-muted); font-size: 13px;">无可用工具</span>';
        }
        html += '</div></div>';
        detail.innerHTML = html;
    },

    async loadList() {
        const data = await api.listMcps();
        if (data && data.length > 0) {
            state.mcps = data;
            state.selectedMcps = data.filter(m => m.defaultSelected).map(m => m.name);
        } else {
            state.mcps = [];
            state.selectedMcps = [];
        }
    },
};

// ===================== §10 Skills =====================
const skills = {
    editingSkill: null, // null = view mode, object = creating/editing

    openModal() {
        ui.showModal('skills-modal-overlay');
        this.renderModal();
    },

    closeModal() {
        ui.hideModal('skills-modal-overlay');
        this.editingSkill = null;
    },

    renderModal() {
        const container = document.getElementById('skills-list');
        const detail = document.getElementById('skills-detail');
        detail.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--text-muted);"><p style="font-size: 16px; margin-bottom: 8px;">请选择一个技能查看详情，或点击「新增」创建新技能</p></div>';
        container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted);">加载中...</div>';

        api.listSkills().then(data => {
            container.innerHTML = '';
            if (!data || data.length === 0) {
                container.innerHTML = '<div style="padding: 20px; text-align: center; color: var(--text-muted);">暂无可用技能</div>';
                return;
            }

            for (const skill of data) {
                const item = document.createElement('div');
                item.className = 'skill-item';
                item.innerHTML = `
                    <div class="skill-item-name">${skill.name}</div>
                    <div class="skill-item-desc">${skill.description || ''}</div>
                    ${skill.source === 'embed' ? '<span class="skill-source-tag">内置</span>' : ''}
                `;
                item.addEventListener('click', () => this.select(skill, item));
                container.appendChild(item);
            }
        }).catch(() => {
            container.innerHTML = '<div style="padding: 40px; text-align: center; color: var(--error-color);">加载失败</div>';
        });
    },

    select(skill, element) {
        if (this.editingSkill) return; // don't switch while editing
        state.selectedSkill = skill;
        const allItems = document.querySelectorAll('#skills-list .skill-item');
        allItems.forEach(i => i.classList.remove('selected'));
        element.classList.add('selected');

        document.getElementById('skill-detail-title').textContent = skill.name;
        const detail = document.getElementById('skills-detail');
        const isEmbed = skill.source === 'embed';
        let html = '';

        // Description
        html += `<div class="detail-section">
            <div class="detail-section-title">技能说明</div>
            <div class="detail-section-content" style="line-height: 1.8; color: var(--text-primary);">
                ${skill.content ? renderMarkdown(skill.content) : '<span style="color: var(--text-muted);">无详细说明</span>'}
            </div>
        </div>`;

        // Status
        html += `<div class="detail-section">
            <div class="detail-section-title">状态</div>
            <div class="detail-section-content">
                <span style="color: ${skill.load ? 'var(--success-color, #22c55e)' : 'var(--text-muted)'}">
                    ${skill.load ? '已加载' : '未加载'}
                </span>
                ${isEmbed ? ' · <span style="color: var(--text-muted)">内嵌技能，不可编辑</span>' : ''}
            </div>
        </div>`;

        // Actions
        if (!isEmbed) {
            html += `<div style="margin-top: 24px; display: flex; gap: 12px;">
                <button class="send-skill-btn" id="edit-skill-btn" style="flex: 1;">编辑技能</button>
                <button class="delete-skill-btn" id="delete-skill-btn" style="flex: 1; background: var(--error-color, #ef4444);">删除技能</button>
            </div>`;
        }

        // Send button (apply skill to chat)
        html += `<div style="margin-top: 12px;">
            <button class="send-skill-btn" id="send-skill-btn">应用技能并发送</button>
        </div>`;

        detail.innerHTML = html;

        // Edit button
        const editBtn = detail.querySelector('#edit-skill-btn');
        if (editBtn) editBtn.addEventListener('click', () => this.showEditForm(skill));

        // Delete button
        const deleteBtn = detail.querySelector('#delete-skill-btn');
        if (deleteBtn) deleteBtn.addEventListener('click', () => this.handleDelete(skill));

        // Send button
        detail.querySelector('#send-skill-btn').addEventListener('click', () => this.send(skill, {}));
    },

    showEditForm(skill) {
        this.editingSkill = skill;
        document.getElementById('skill-detail-title').textContent = '编辑技能';
        const detail = document.getElementById('skills-detail');
        detail.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 16px;">
                <div>
                    <label class="param-label">技能名称</label>
                    <input type="text" id="edit-skill-name" class="param-input" value="${skill.name}" ${skill.source === 'embed' ? 'disabled' : ''} placeholder="例如：周报生成">
                </div>
                <div>
                    <label class="param-label">技能描述</label>
                    <input type="text" id="edit-skill-desc" class="param-input" value="${skill.description || ''}" placeholder="简要描述技能的功能">
                </div>
                <div>
                    <label class="param-label">技能内容（Prompt 模板）</label>
                    <textarea id="edit-skill-content" class="param-input param-textarea" style="min-height: 200px; font-family: var(--font-mono, monospace); font-size: 13px;" placeholder="技能内容模板，支持 {param} 占位符">${skill.content || ''}</textarea>
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

        const result = await api.updateSkill({ name, description, load, content });
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
        }

        chat.send();
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
        let contentHtml;
        if (docIcon) {
            contentHtml = `<div class="doc-icon-container">${docIcon}</div>
                <div class="doc-filename" title="${fileName}">${fileName}</div>`;
        } else {
            contentHtml = `<img src="${objectUrl}" alt="${fileName}">
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

    // Auth check — auto-login via cookie-based session (BFF pattern)
    const loggedIn = await auth.init();

    // Only load protected resources after successful login
    if (loggedIn) {
        // Load MCPs
        await mcp.loadList();
    }

    // Feature detection (image upload)
    try {
        const uploadOk = await api.checkKnowledgeUpload();
        if (uploadOk) {
            document.getElementById('image-add-btn').style.display = 'flex';
        }
    } catch { /* upload not available */
    }

    // Load conversations
    await conversation.loadList();

    // Create initial conversation if none
    if (!state.conversationId) {
        conversation.createNew();
    }

    // Event bindings
    const textarea = document.getElementById('textarea');
    const sendBtn = document.getElementById('send-btn');

    textarea.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && event.ctrlKey) {
            event.preventDefault();
            const start = textarea.selectionStart;
            const end = textarea.selectionEnd;
            textarea.value = textarea.value.substring(0, start) + '\n' + textarea.value.substring(end);
            textarea.setSelectionRange(start + 1, start + 1);
        }
        if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey) {
            event.preventDefault();
            chat.send();
        }
    });

    sendBtn.addEventListener('click', () => chat.send());

    // Modal overlay click-to-close
    document.getElementById('mcp-modal-overlay').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) mcp.closeModal();
    });
    document.getElementById('skills-modal-overlay').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) skills.closeModal();
    });
    document.getElementById('ks-modal-overlay').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) knowledge.closePanel();
    });
    document.getElementById('file-modal-overlay').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) fileMgr.closeModal();
    });

    // Image upload
    imageUpload.init();

    // Image viewer close handlers
    document.getElementById('image-viewer-overlay').addEventListener('click', (e) => {
        if (e.target === e.currentTarget) imageUpload.hideFullscreen();
    });
    document.getElementById('viewer-close').addEventListener('click', () => imageUpload.hideFullscreen());
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') imageUpload.hideFullscreen();
    });

    // Responsive
    responsive.handleResize();
    window.addEventListener('resize', responsive.handleResize);

    // Event listeners (replaces inline onclick handlers from ES module scope)
    const el = (sel) => document.querySelector(sel);
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
    addIf('#mcp-close-btn', () => mcp.closeModal());
    addIf('#skills-close-btn', () => skills.closeModal());
    addIf('#skill-add-btn', () => skills.showCreateForm());
    addIf('.ks-create-btn', () => knowledge.create());
    addIf('#ks-modal-overlay .close-button', () => knowledge.closePanel());
};

document.addEventListener('DOMContentLoaded', init);

// Expose to global for testing/debugging
window._loomAgent = {state, api, imageUpload, auth, chat, conversation, ui, fileMgr};
window.ui = ui;
