(function () {
    'use strict';

    const params = new URLSearchParams(window.location.search);
    const conversationId = params.get('id');
    const username = params.get('username');
    if (!conversationId) {
        window.location.replace('console.html');
        return;
    }

    const titleEl = document.getElementById('conv-title');
    const metaEl = document.getElementById('conv-meta');
    const turnsContainer = document.getElementById('turns-container');
    const backLink = document.getElementById('back-link');
    if (username) backLink.href = `user.html?username=${encodeURIComponent(username)}`;

    titleEl.textContent = `会话：${conversationId.substring(0, 8)}…`;

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function showToast(text, type = 'success') {
        const el = document.getElementById('toast-notification');
        el.textContent = text;
        el.className = 'toast show ' + type;
        setTimeout(() => { el.className = 'toast'; }, 2500);
    }

    async function loadTurns() {
        turnsContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const r = await fetch(`/spring/ai/loom/admin/conversations/${encodeURIComponent(conversationId)}/turns`, {credentials: 'include'});
            if (r.status === 401) {
                window.location.replace('/spring/ai/loom/login.html');
                return;
            }
            if (!r.ok) {
                turnsContainer.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
                return;
            }
            const turns = await r.json();
            renderTurns(turns);
            // 顺便加载消息内容
            loadMessages();
        } catch (e) {
            turnsContainer.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
        }
    }

    function renderTurns(turns) {
        // 统计
        let total = 0, calls = 0, prompt = 0, completion = 0;
        for (const t of turns) {
            total += t.totalTokens;
            prompt += t.promptTokens;
            completion += t.completionTokens;
            calls++;
        }
        document.getElementById('stat-total').textContent = total.toLocaleString();
        document.getElementById('stat-calls').textContent = calls.toLocaleString();
        document.getElementById('stat-avg-prompt').textContent = calls > 0 ? Math.round(prompt / calls).toLocaleString() : '-';
        document.getElementById('stat-avg-completion').textContent = calls > 0 ? Math.round(completion / calls).toLocaleString() : '-';

        if (!turns || turns.length === 0) {
            turnsContainer.innerHTML = '<div class="empty-state">无 turn 记录（可能已被清理）</div>';
            return;
        }

        const rows = turns.map(t => {
            const date = t.createdAt ? new Date(t.createdAt).toLocaleString('zh-CN') : '-';
            const dur = t.durationMs ? `${t.durationMs} ms` : '-';
            return `<tr>
                <td>${escapeHtml(t.role)}</td>
                <td>${escapeHtml(t.model || '-')}</td>
                <td>${t.promptTokens.toLocaleString()}</td>
                <td>${t.completionTokens.toLocaleString()}</td>
                <td><strong>${t.totalTokens.toLocaleString()}</strong></td>
                <td>${dur}</td>
                <td>${date}</td>
            </tr>`;
        }).join('');
        turnsContainer.innerHTML = `
            <table class="user-table">
                <thead>
                    <tr><th>角色</th><th>模型</th><th>输入</th><th>输出</th><th>总</th><th>耗时</th><th>时间</th></tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
            <div style="padding: 16px; border-top: 1px solid var(--border-color);">
                <h4 style="margin: 0 0 12px 0; font-size: 14px;">消息内容</h4>
                <div id="messages-list">加载中...</div>
            </div>`;
    }

    async function loadMessages() {
        const target = document.getElementById('messages-list');
        if (!target) return;
        try {
            const messagePath = username
                ? `/spring/ai/loom/admin/conversations/${encodeURIComponent(conversationId)}/messages?username=${encodeURIComponent(username)}`
                : `/spring/ai/loom/conversation/${encodeURIComponent(conversationId)}`;
            const r = await fetch(messagePath, {credentials: 'include'});
            if (!r.ok) {
                target.innerHTML = `<div class="empty-state">内容已清理或加载失败</div>`;
                return;
            }
            const msgs = await r.json();
            if (!msgs || msgs.length === 0) {
                target.innerHTML = '<div class="empty-state">无消息</div>';
                return;
            }
            target.innerHTML = msgs.map(m => {
                const role = m.messageType || '?';
                return `<div class="msg-row">
                    <div class="msg-role role-${role.toLowerCase()}">${escapeHtml(role)}</div>
                    <div class="msg-text">${escapeHtml(m.text || '').replace(/\n/g, '<br>')}</div>
                </div>`;
            }).join('');
        } catch (e) {
            target.innerHTML = `<div class="empty-state">${e.message}</div>`;
        }
    }

    // 元信息
    async function loadMeta() {
        if (!username) return;
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/conversations`, {credentials: 'include'});
            if (!r.ok) return;
            const list = await r.json();
            const conv = list.find(c => c.conversationId === conversationId);
            if (conv) {
                const state = conv.deletedAt ? '已软删' : '正常';
                const cleaned = conv.contentCleaned ? '已清理' : '未清理';
                const date = conv.deletedAt ? new Date(conv.deletedAt).toLocaleString('zh-CN') : '-';
                metaEl.textContent = `用户：${username} · 状态：${state} · 内容：${cleaned} · 删除时间：${date}`;
            }
        } catch (e) {}
    }

    document.getElementById('refresh-btn').addEventListener('click', loadTurns);

    // 顶部右侧渲染当前用户名（统一 header 风格）
    fetch('/spring/ai/loom/user/currentUser', {method: 'POST', credentials: 'include'})
        .then(r => r.ok ? r.json() : null)
        .then(me => {
            if (me) {
                const el = document.getElementById('admin-username');
                if (el) el.textContent = `${me.nickname || me.username}（${me.type === 'ADMIN' ? '管理员' : '用户'}）`;
            }
        }).catch(() => {});

    loadMeta();
    loadTurns();
})();
