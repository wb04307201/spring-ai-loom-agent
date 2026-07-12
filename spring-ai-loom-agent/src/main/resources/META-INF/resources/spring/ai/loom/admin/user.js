(function () {
    'use strict';

    const params = new URLSearchParams(window.location.search);
    const username = params.get('username');
    if (!username) {
        window.location.replace('console.html');
        return;
    }

    const titleEl = document.getElementById('user-title');
    const metaEl = document.getElementById('user-meta');
    const listContainer = document.getElementById('conv-list-container');
    const barChart = document.getElementById('bar-chart');
    const monthTotalEl = document.getElementById('month-total');

    titleEl.textContent = `用户详情：${username}`;

    let userType = null; // 'ADMIN' | 'USER'，从 loadUserInfo 写入

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

    function confirmDialog({title, message, okText = '确定'}) {
        return new Promise(resolve => {
            document.getElementById('confirm-title').textContent = title;
            document.getElementById('confirm-message').textContent = message;
            document.getElementById('confirm-ok').textContent = okText;
            const overlay = document.getElementById('confirm-modal');
            overlay.style.display = 'flex';
            const ok = document.getElementById('confirm-ok');
            const cancel = document.getElementById('confirm-cancel');
            const close = document.getElementById('confirm-close');
            const onOk = () => { cleanup(); overlay.style.display = 'none'; resolve(true); };
            const onCancel = () => { cleanup(); overlay.style.display = 'none'; resolve(false); };
            ok.onclick = onOk; cancel.onclick = onCancel; close.onclick = onCancel;
            function cleanup() {
                ok.onclick = null; cancel.onclick = null; close.onclick = null;
            }
        });
    }

    async function loadBarChart() {
        // 拉最近 6 个月的统计
        const now = new Date();
        const months = [];
        for (let i = 5; i >= 0; i--) {
            const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
            months.push({year: d.getFullYear(), month: d.getMonth() + 1, label: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`});
        }
        const results = [];
        for (const m of months) {
            try {
                const r = await fetch(`/spring/ai/loom/admin/stats/tokens/monthly?year=${m.year}&month=${m.month}`, {
                    credentials: 'include',
                });
                if (!r.ok) { results.push({label: m.label, total: 0}); continue; }
                const list = await r.json();
                const row = list.find(x => x.username === username);
                results.push({label: m.label, total: row ? row.totalTokens : 0});
            } catch (e) {
                results.push({label: m.label, total: 0});
            }
        }
        const max = Math.max(1, ...results.map(r => r.total));
        barChart.innerHTML = results.map(r => {
            const pct = (r.total / max * 100).toFixed(1);
            return `<div class="bar-row">
                <div class="bar-label">${r.label}</div>
                <div class="bar-track"><div class="bar-fill" style="width: ${pct}%"></div></div>
                <div class="bar-value">${r.total.toLocaleString()}</div>
            </div>`;
        }).join('');
        // 显示当月总数
        const nowData = results[results.length - 1];
        if (monthTotalEl && nowData) monthTotalEl.textContent = `本月用量：${nowData.total.toLocaleString()} tokens`;
    }

    async function loadConversations() {
        listContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/conversations`, {credentials: 'include'});
            if (r.status === 401) {
                window.location.replace('/spring/ai/loom/login.html');
                return;
            }
            if (!r.ok) {
                listContainer.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
                return;
            }
            const list = await r.json();
            renderConvList(list);
        } catch (e) {
            listContainer.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
        }
    }

    function renderConvList(list) {
        if (!list || list.length === 0) {
            listContainer.innerHTML = '<div class="empty-state">该用户暂无会话</div>';
            return;
        }
        // 加载每会话的 token 总量
        loadConvTokens(list);
    }

    async function loadConvTokens(list) {
        const now = new Date();
        const fromI = new Date(now.getFullYear(), now.getMonth() - 5, 1).toISOString();
        const toI = new Date(now.getFullYear(), now.getMonth() + 1, 1).toISOString();
        const tokensByConv = {};
        try {
            const r = await fetch(`/spring/ai/loom/admin/stats/tokens/monthly?year=${now.getFullYear()}&month=${now.getMonth() + 1}`, {
                credentials: 'include',
            });
            // 月度接口按用户聚合，不够细。直接从 byUser 拉
            const r2 = await fetch('/spring/ai/loom/admin/users', {credentials: 'include'});
            // 简化：只显示最近 6 个月的总量作为参考
        } catch (e) {}

        const rows = list.map(c => {
            const deleted = c.deletedAt ? new Date(c.deletedAt).toLocaleString('zh-CN') : '-';
            const cleaned = c.contentCleaned ? '<span class="type-badge USER" style="background: #d1fae5; color: #065f46;">已清理</span>' : '';
            const stateTag = c.deletedAt ? '<span class="type-badge USER" style="background: #fee2e2; color: #991b1b;">已软删</span>' : '<span class="type-badge ADMIN">正常</span>';
            return `<tr data-username="${escapeHtml(c.username)}" data-conv="${escapeHtml(c.conversationId)}">
                <td><a class="user-link" href="conversation.html?id=${encodeURIComponent(c.conversationId)}&username=${encodeURIComponent(username)}">${escapeHtml(c.conversationId.substring(0, 8))}…</a></td>
                <td>${escapeHtml(c.preview || '')}</td>
                <td>${stateTag}</td>
                <td>${cleaned}</td>
                <td>${deleted}</td>
                <td>${c.contentCleaned ? '已清理' : '-'}</td>
            </tr>`;
        }).join('');
        listContainer.innerHTML = `
            <table class="user-table">
                <thead>
                    <tr><th>会话 ID</th><th>预览</th><th>状态</th><th>内容</th><th>删除时间</th></tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>`;
        // 清理入口整合到控制台顶部"批量清理"按钮（这里不再单独触发清理弹窗）
    }

    // 拉用户元信息
    async function loadUserInfo() {
        try {
            // 顶部右侧渲染当前用户名（统一 header 风格）
            fetch('/spring/ai/loom/user/currentUser', {method: 'POST', credentials: 'include'})
                .then(r => r.ok ? r.json() : null)
                .then(me => {
                    if (me) {
                        const el = document.getElementById('admin-username');
                        if (el) el.textContent = `${me.nickname || me.username}（${me.type === 'ADMIN' ? '管理员' : '用户'}）`;
                    }
                }).catch(() => {});
            // 从 list 拉所有用户，匹配
            const r = await fetch('/spring/ai/loom/admin/users', {credentials: 'include'});
            if (!r.ok) return;
            const list = await r.json();
            const u = list.find(x => x.username === username);
            if (u) {
                userType = u.type;
                const typeLabel = u.type === 'ADMIN' ? '管理员' : '普通用户';
                metaEl.textContent = `${typeLabel} · 昵称：${u.nickname || username}`;
            } else {
                metaEl.textContent = '（用户不存在）';
            }
        } catch (e) {}
    }

    // ===== 角色分配 =====
    const roleCardBody = document.getElementById('role-card-body');
    const saveRolesBtn = document.getElementById('save-roles-btn');
    const roleSaveError = document.getElementById('role-save-error');

    async function loadRoles() {
        roleCardBody.innerHTML = '<div class="loading-indicator">加载中...</div>';
        saveRolesBtn.style.display = 'none';
        roleSaveError.style.display = 'none';
        if (userType === null) {
            // 还没拿到用户类型，先等等
            setTimeout(loadRoles, 100);
            return;
        }
        if (userType === 'ADMIN') {
            roleCardBody.innerHTML = '<div style="color: var(--text-muted); padding: 4px 0;">管理员账号默认拥有全部 MCP 服务，无需分配角色。</div>';
            return;
        }
        try {
            const [allRoles, myRoles] = await Promise.all([
                fetch('/spring/ai/loom/admin/roles', {credentials: 'include'}).then(r => r.ok ? r.json() : []),
                fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/roles`, {credentials: 'include'}).then(r => r.ok ? r.json() : []),
            ]);
            renderRoleCard(allRoles || [], myRoles || []);
        } catch (e) {
            roleCardBody.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function renderRoleCard(allRoles, myRoles) {
        if (!allRoles || allRoles.length === 0) {
            roleCardBody.innerHTML = '<div style="color: var(--text-muted); padding: 4px 0;">系统暂无任何角色，请先到<a href="roles.html">角色管理</a>创建。</div>';
            return;
        }
        const mySet = new Set(myRoles);
        roleCardBody.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 6px;">
                ${allRoles.map(r => `
                    <label style="display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--border-color); border-radius: 6px; cursor: pointer; background: ${mySet.has(r.code) ? '#f0fdf4' : '#fff'};">
                        <input type="checkbox" class="role-cb" value="${escapeHtml(r.code)}" ${mySet.has(r.code) ? 'checked' : ''}>
                        <span style="flex: 1;">
                            <strong style="font-size: 13px;">${escapeHtml(r.code)}</strong>
                            <span style="color: var(--text-muted); font-size: 12px; margin-left: 8px;">${escapeHtml(r.name || '')}</span>
                            ${r.system ? '<span class="type-badge ADMIN" style="margin-left: 6px;">系统</span>' : ''}
                        </span>
                        <span style="color: var(--text-muted); font-size: 12px;">${escapeHtml(r.description || '')}</span>
                    </label>
                `).join('')}
            </div>
            <div style="font-size: 12px; color: var(--text-muted); margin-top: 8px;">
                普通用户的 MCP 服务 = 所有已勾选角色授权 mcp 的并集。
                <a href="roles.html">角色管理</a>里可调整每个角色授权的 mcp 及默认启用项。
            </div>
        `;
        saveRolesBtn.style.display = '';
    }

    function showRoleError(msg) {
        roleSaveError.textContent = msg;
        roleSaveError.style.display = 'block';
    }

    saveRolesBtn.addEventListener('click', async () => {
        const checked = Array.from(roleCardBody.querySelectorAll('.role-cb:checked'))
            .map(cb => cb.value);
        saveRolesBtn.disabled = true;
        roleSaveError.style.display = 'none';
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/roles`, {
                method: 'PUT', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({roleCodes: checked}),
            });
            if (!r.ok) {
                const t = await r.text();
                showRoleError(`保存失败：${t || 'HTTP ' + r.status}`);
                return;
            }
            showToast(`已为「${username}」分配 ${checked.length} 个角色`, 'success');
        } catch (e) {
            showRoleError('网络错误：' + e.message);
        } finally {
            saveRolesBtn.disabled = false;
        }
    });

    document.getElementById('refresh-btn').addEventListener('click', () => {
        loadConversations();
        loadBarChart();
        loadRoles();
    });

    loadUserInfo().then(loadRoles);
    loadBarChart();
    loadConversations();
})();
