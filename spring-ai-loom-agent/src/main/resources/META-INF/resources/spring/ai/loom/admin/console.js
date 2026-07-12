(function () {
    'use strict';

    const tableContainer = document.getElementById('user-table-container');
    const adminUsername = document.getElementById('admin-username');
    const createBtn = document.getElementById('create-user-btn');
    const refreshBtn = document.getElementById('refresh-btn');

    const createModal = document.getElementById('create-user-modal');
    const createClose = document.getElementById('create-user-close');
    const createCancel = document.getElementById('create-cancel-btn');
    const createSubmit = document.getElementById('create-submit-btn');
    const newUsername = document.getElementById('new-username');
    const newNickname = document.getElementById('new-nickname');
    const newPassword = document.getElementById('new-password');
    const newType = document.getElementById('new-type');
    const createError = document.getElementById('create-error');

    const confirmOverlay = document.getElementById('confirm-modal');
    const confirmTitle = document.getElementById('confirm-title');
    const confirmMessage = document.getElementById('confirm-message');
    const confirmOk = document.getElementById('confirm-ok');
    const confirmCancel = document.getElementById('confirm-cancel');
    const confirmClose = document.getElementById('confirm-close');

    const toastEl = document.getElementById('toast-notification');

    // 1. 进入页面：先校验管理员身份
    async function bootstrap() {
        try {
            const resp = await fetch('/spring/ai/loom/user/currentIsAdmin', {
                method: 'POST', credentials: 'include', redirect: 'manual',
            });
            // 0 = opaque redirect (browser blocked it due to credentials/cookie). Treat as "not admin"
            if (resp.type === 'opaqueredirect' || resp.status === 0) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            if (resp.status === 401 || resp.status === 302 || resp.status === 303) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            // If response is HTML (redirect target), body is index.html — treat as not admin
            const ct = resp.headers.get('content-type') || '';
            if (ct.includes('text/html')) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            const isAdmin = await resp.json();
            if (isAdmin !== true) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            const me = await postJson('/spring/ai/loom/user/currentUser');
            adminUsername.textContent = `${me.nickname || me.username}（${me.type === 'ADMIN' ? '管理员' : '用户'}）`;
            await loadUsers();
        } catch (e) {
            // 网络错误 / JSON 解析错误 = 强制跳走
            window.location.replace('/spring/ai/loom/index.html');
        }
    }

    async function loadUsers() {
        tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const list = await fetch('/spring/ai/loom/admin/users', {credentials: 'include'});
            if (list.status === 401) {
                window.location.replace('/spring/ai/loom/login.html');
                return;
            }
            if (!list.ok) {
                tableContainer.innerHTML = `<div class="empty-state">加载失败：HTTP ${list.status}</div>`;
                return;
            }
            const users = await list.json();
            renderTable(users);
            // 拉本月 token 统计
            loadMonthlyTokens();
        } catch (e) {
            tableContainer.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
        }
    }

    async function loadMonthlyTokens() {
        try {
            const now = new Date();
            const r = await fetch(`/spring/ai/loom/admin/stats/tokens/monthly?year=${now.getFullYear()}&month=${now.getMonth() + 1}`, {
                method: 'POST', credentials: 'include',
            });
            if (!r.ok) return;
            const list = await r.json();
            const byUser = {};
            for (const row of list) byUser[row.username] = row.totalTokens;
            // 填到表格
            tableContainer.querySelectorAll('.usage-cell').forEach(cell => {
                const u = cell.getAttribute('data-username');
                const t = byUser[u] || 0;
                cell.textContent = t.toLocaleString();
            });
            // 月份标签
            const label = document.getElementById('current-month-label');
            if (label) label.textContent = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')} 月用量`;
        } catch (e) {
            // 静默失败
        }
    }

    function renderTable(users) {
        if (!users || users.length === 0) {
            tableContainer.innerHTML = '<div class="empty-state">暂无用户</div>';
            return;
        }
        const rows = users.map(u => {
            const typeLabel = u.type === 'ADMIN' ? '管理员' : '普通用户';
            return `<tr data-username="${escapeHtml(u.username)}" data-type="${escapeHtml(u.type)}" data-expanded="false">
                <td><a class="user-link user-name-toggle" data-username="${escapeHtml(u.username)}" href="user.html?username=${encodeURIComponent(u.username)}" onclick="event.preventDefault();"><strong>${escapeHtml(u.username)}</strong> ▾</a></td>
                <td>${escapeHtml(u.nickname || '')}</td>
                <td><span class="type-badge ${u.type}">${typeLabel}</span></td>
                <td class="usage-cell" data-username="${escapeHtml(u.username)}">-</td>
                <td>
                    <button class="secondary-btn assign-role-btn" data-username="${escapeHtml(u.username)}" data-type="${escapeHtml(u.type)}">分配角色</button>
                    <button class="delete-btn" data-username="${escapeHtml(u.username)}">删除</button>
                </td>
            </tr>`;
        }).join('');
        tableContainer.innerHTML = `
            <table class="user-table">
                <thead>
                    <tr><th>用户名</th><th>昵称</th><th>类型</th><th>本月 Token</th><th>操作</th></tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>`;
        // 绑定删除按钮
        tableContainer.querySelectorAll('.delete-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const username = btn.getAttribute('data-username');
                confirmDialog({
                    title: '删除用户',
                    message: `确定要删除用户「${username}」吗？此操作不可撤销。`,
                    okText: '删除',
                }).then(ok => {
                    if (!ok) return;
                    deleteUser(username);
                });
            });
        });
        // 绑定用户名点击 → 行内展开该用户会话
        tableContainer.querySelectorAll('.user-name-toggle').forEach(a => {
            a.addEventListener('click', (e) => {
                e.preventDefault();
                const username = a.getAttribute('data-username');
                const tr = a.closest('tr');
                const expanded = tr.getAttribute('data-expanded') === 'true';
                if (expanded) {
                    collapseUserRow(tr);
                } else {
                    expandUserRow(tr, username);
                }
            });
        });
        // 绑定"分配角色"按钮
        tableContainer.querySelectorAll('.assign-role-btn').forEach(btn => {
            btn.addEventListener('click', () => openAssignRole(btn.getAttribute('data-username'), btn.getAttribute('data-type')));
        });
    }

    function collapseUserRow(tr) {
        const next = tr.nextElementSibling;
        if (next && next.classList.contains('user-detail-row')) next.remove();
        tr.setAttribute('data-expanded', 'false');
        const arrow = tr.querySelector('.user-name-toggle');
        if (arrow) arrow.innerHTML = arrow.innerHTML.replace('▴', '▾');
    }

    async function expandUserRow(tr, username) {
        tr.setAttribute('data-expanded', 'true');
        const arrow = tr.querySelector('.user-name-toggle');
        if (arrow) arrow.innerHTML = arrow.innerHTML.replace('▾', '▴');
        // 插入占位行
        const detailTr = document.createElement('tr');
        detailTr.className = 'user-detail-row';
        detailTr.innerHTML = `<td colspan="5" style="background: #f8fafc; padding: 12px 24px;">
            <div data-user-conv="${escapeHtml(username)}"><div class="loading-indicator">加载中...</div></div>
        </td>`;
        tr.after(detailTr);
        // 拉该用户全部会话
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/conversations`, {credentials: 'include'});
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const list = await r.json();
            renderUserConversations(detailTr, username, list);
        } catch (e) {
            detailTr.innerHTML = `<td colspan="5" style="background: #f8fafc; color: var(--error-color);">加载失败：${escapeHtml(e.message)}</td>`;
        }
    }

    function renderUserConversations(detailTr, username, list) {
        if (!list || list.length === 0) {
            detailTr.innerHTML = `<td colspan="5" style="background: #f8fafc; padding: 12px 24px; color: var(--text-muted);">该用户暂无会话</td>`;
            return;
        }
        // 按内容状态分组：未清理 vs 已清理
        const cleanable = list.filter(c => !c.contentCleaned);
        const cleaned = list.filter(c => c.contentCleaned);
        let html = '<div style="background: #f8fafc; padding: 12px 24px;">';
        html += `<div style="display: flex; align-items: center; gap: 12px; margin-bottom: 8px;">
            <strong>${escapeHtml(username)}</strong>
            <span style="font-size: 12px; color: var(--text-muted);">共 ${list.length} 条</span>
        </div>`;
        const renderItem = (c) => {
            const state = c.deletedAt ? '已软删' : '正常';
            const stateColor = c.deletedAt ? '#fee2e2' : '#d1fae5';
            const stateText = c.deletedAt ? '#991b1b' : '#065f46';
            const cleanedTag = c.contentCleaned ? '<span class="type-badge USER" style="background:#d1fae5;color:#065f46;">已清理</span>' : '<span class="type-badge USER" style="background:#fef3c7;color:#92400e;">待清理</span>';
            return `<tr>
                <td><a class="user-link" href="conversation.html?id=${encodeURIComponent(c.conversationId)}&username=${encodeURIComponent(username)}">${escapeHtml(c.conversationId.substring(0, 8))}…</a></td>
                <td>${escapeHtml(c.preview || '')}</td>
                <td><span class="type-badge" style="background:${stateColor};color:${stateText};">${state}</span> ${cleanedTag}</td>
                <td>${escapeHtml(c.deletedAt ? new Date(c.deletedAt).toLocaleString('zh-CN') : '-')}</td>
            </tr>`;
        };
        html += '<table class="user-table" style="background: white; margin-top: 8px;"><thead><tr><th>会话 ID</th><th>预览</th><th>状态</th><th>删除时间</th></tr></thead><tbody>';
        if (cleanable.length > 0) {
            html += '<tr><td colspan="4" style="background: #fef3c7; font-size: 12px; color: #92400e; padding: 4px 12px;">▼ 待清理 (' + cleanable.length + ' 条)</td></tr>';
            cleanable.forEach(c => { html += renderItem(c); });
        }
        if (cleaned.length > 0) {
            html += '<tr><td colspan="4" style="background: #d1fae5; font-size: 12px; color: #065f46; padding: 4px 12px;">▼ 已清理 (' + cleaned.length + ' 条)</td></tr>';
            cleaned.forEach(c => { html += renderItem(c); });
        }
        html += '</tbody></table></div>';
        detailTr.innerHTML = `<td colspan="5" style="padding: 0;">${html}</td>`;
        // 清理入口：整合到控制台顶部"批量清理"按钮（user.html / conversation.html / 这里都不再单独触发清理弹窗）
    }

    async function deleteUser(username) {
        try {
            const resp = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}`, {
                method: 'DELETE',
                credentials: 'include',
            });
            const text = await resp.text();
            if (!resp.ok) {
                let msg = `删除失败：HTTP ${resp.status}`;
                try { msg = JSON.parse(text).message || msg; } catch (_) {}
                showToast(msg, 'error');
                return;
            }
            showToast('用户已删除', 'success');
            await loadUsers();
        } catch (e) {
            showToast('删除失败：' + e.message, 'error');
        }
    }

    function openCreate() {
        newUsername.value = '';
        newNickname.value = '';
        newPassword.value = '';
        newType.value = 'USER';
        createError.style.display = 'none';
        createModal.style.display = 'flex';
        setTimeout(() => newUsername.focus(), 0);
    }

    function closeCreate() {
        createModal.style.display = 'none';
    }

    async function submitCreate() {
        const username = newUsername.value.trim();
        const nickname = newNickname.value.trim();
        const password = newPassword.value;
        const type = newType.value;
        if (!username || !nickname || !password) {
            createError.textContent = '请填写所有字段';
            createError.style.display = 'block';
            return;
        }
        if (password.length < 6) {
            createError.textContent = '密码至少 6 位';
            createError.style.display = 'block';
            return;
        }
        createSubmit.disabled = true;
        try {
            const resp = await fetch('/spring/ai/loom/admin/users', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'include',
                body: JSON.stringify({username, nickname, password, type}),
            });
            const text = await resp.text();
            if (!resp.ok) {
                let msg = `创建失败：HTTP ${resp.status}`;
                try { msg = JSON.parse(text).message || msg; } catch (_) {}
                createError.textContent = msg;
                createError.style.display = 'block';
                return;
            }
            closeCreate();
            showToast('用户创建成功', 'success');
            await loadUsers();
        } catch (e) {
            createError.textContent = '网络错误：' + e.message;
            createError.style.display = 'block';
        } finally {
            createSubmit.disabled = false;
        }
    }

    // 通用确认弹窗
    function confirmDialog({title, message, okText = '确定', cancelText = '取消'}) {
        return new Promise(resolve => {
            confirmTitle.textContent = title;
            confirmMessage.textContent = message;
            confirmOk.textContent = okText;
            confirmCancel.textContent = cancelText;
            confirmOverlay.style.display = 'flex';
            const onOk = () => { cleanup(); confirmOverlay.style.display = 'none'; resolve(true); };
            const onCancel = () => { cleanup(); confirmOverlay.style.display = 'none'; resolve(false); };
            confirmOk.onclick = onOk;
            confirmCancel.onclick = onCancel;
            confirmClose.onclick = onCancel;
            function cleanup() {
                confirmOk.onclick = null;
                confirmCancel.onclick = null;
                confirmClose.onclick = null;
            }
        });
    }

    // Toast
    function showToast(text, type = 'success') {
        toastEl.textContent = text;
        toastEl.className = 'toast show ' + type;
        setTimeout(() => { toastEl.className = 'toast'; }, 2500);
    }

    async function postJson(url, body) {
        const resp = await fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'include',
            body: body ? JSON.stringify(body) : null,
        });
        if (resp.status === 401) {
            window.location.replace('/spring/ai/loom/login.html');
            throw new Error('未登录');
        }
        if (!resp.ok) {
            const text = await resp.text();
            throw new Error(`HTTP ${resp.status} ${text.substring(0, 200)}`);
        }
        return await resp.json();
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // 事件绑定
    createBtn.addEventListener('click', openCreate);
    refreshBtn.addEventListener('click', loadUsers);
    createClose.addEventListener('click', closeCreate);
    createCancel.addEventListener('click', closeCreate);
    createSubmit.addEventListener('click', submitCreate);
    document.getElementById('batch-clean-btn').addEventListener('click', openBatchClean);
    document.getElementById('batch-clean-close').addEventListener('click', closeBatchClean);
    document.getElementById('batch-clean-cancel').addEventListener('click', closeBatchClean);
    document.getElementById('batch-clean-confirm').addEventListener('click', confirmBatchCleanFromModal);
    confirmOverlay.addEventListener('click', (e) => {
        if (e.target === confirmOverlay) confirmClose.click();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (createModal.style.display === 'flex') closeCreate();
            if (confirmOverlay.style.display === 'flex') confirmClose.click();
        }
    });

    // ========== 批量清理 ==========
    async function openBatchClean() {
        document.getElementById('batch-clean-list').innerHTML = '<div class="loading-indicator">加载中...</div>';
        document.getElementById('batch-clean-modal').style.display = 'flex';
        try {
            // 改用 listAllCleanable（含未软删 + 已软删未清理）
            const usersResp = await fetch('/spring/ai/loom/admin/users', {credentials: 'include'});
            if (!usersResp.ok) throw new Error('加载用户失败');
            const users = await usersResp.json();
            const all = [];
            for (const u of users) {
                const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(u.username)}/conversations`, {credentials: 'include'});
                if (!r.ok) continue;
                const list = await r.json();
                for (const c of list) {
                    if (!c.contentCleaned) {
                        all.push({
                            username: u.username,
                            conversationId: c.conversationId,
                            preview: c.preview,
                            deletedAt: c.deletedAt,
                        });
                    }
                }
            }
            renderBatchList(all);
        } catch (e) {
            document.getElementById('batch-clean-list').innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
        }
    }

    function closeBatchClean() {
        document.getElementById('batch-clean-modal').style.display = 'none';
    }

    function renderBatchList(items) {
        const container = document.getElementById('batch-clean-list');
        if (!items || items.length === 0) {
            container.innerHTML = '<div class="empty-state" style="padding: 40px;">无可清理的会话</div>';
            return;
        }
        // 按用户分组
        const byUser = {};
        items.forEach((it, idx) => {
            it._idx = idx;
            (byUser[it.username] = byUser[it.username] || []).push(it);
        });
        let html = '<div class="batch-toolbar" style="padding: 10px 16px; border-bottom: 1px solid var(--border-color); background: #f8fafc; display: flex; align-items: center; gap: 8px;">';
        html += '<label style="font-size: 13px;"><input type="checkbox" id="batch-select-all" checked> 全选</label>';
        html += `<span style="margin-left: auto; font-size: 12px; color: var(--text-muted);">共 ${items.length} 条（${Object.keys(byUser).length} 用户）</span>`;
        html += '</div>';
        html += '<div class="batch-list">';
        for (const [user, list] of Object.entries(byUser)) {
            html += `<div class="batch-user-group">
                <div class="batch-user-header" data-username="${escapeHtml(user)}">
                    <label><input type="checkbox" class="batch-user-all" checked> <strong>${escapeHtml(user)}</strong></label>
                    <span style="font-size: 11px; color: var(--text-muted);">(${list.length} 条)</span>
                </div>`;
            list.forEach((it) => {
                const stateTag = it.deletedAt
                    ? '<span class="type-badge USER" style="background:#fee2e2;color:#991b1b;">已软删</span>'
                    : '<span class="type-badge USER" style="background:#dbeafe;color:#1e40af;">正常</span>';
                const date = it.deletedAt ? new Date(it.deletedAt).toLocaleString('zh-CN') : '-';
                html += `<label class="batch-item">
                    <input type="checkbox" class="batch-check" data-idx="${it._idx}" checked>
                    <span class="batch-meta">
                        <span class="batch-conv">${escapeHtml(it.conversationId.substring(0, 8))}…</span>
                        <span class="batch-preview">${escapeHtml(it.preview || '')}</span>
                        ${stateTag}
                        <span class="batch-date">${date}</span>
                    </span>
                </label>`;
            });
            html += '</div>';
        }
        html += '</div>';
        container.innerHTML = html;
        container._items = items;

        // 绑定全选 / 组选
        container.querySelector('#batch-select-all').addEventListener('change', (e) => {
            const checked = e.target.checked;
            container.querySelectorAll('.batch-check, .batch-user-all').forEach(c => c.checked = checked);
        });
        container.querySelectorAll('.batch-user-all').forEach(cb => {
            cb.addEventListener('change', (e) => {
                const userGroup = e.target.closest('.batch-user-group');
                const checked = e.target.checked;
                userGroup.querySelectorAll('.batch-check').forEach(c => c.checked = checked);
            });
        });
    }

    function getSelectedItems() {
        const container = document.getElementById('batch-clean-list');
        const items = container._items || [];
        return Array.from(container.querySelectorAll('.batch-check:checked'))
            .map(c => items[parseInt(c.getAttribute('data-idx'))])
            .filter(Boolean);
    }

    // confirmBatchClean: 可被"批量清理"按钮和"行内一键清理"按钮共用
    // 接受 items 数组（行内调用时直接传）和 message（确认弹窗文案）
    window._confirmBatchClean = async function (items, message) {
        if (!items || items.length === 0) {
            showToast('请至少选择一条', 'error');
            return;
        }
        const ok = await confirmDialog({
            title: '批量清理',
            message: message || `确定要清理 ${items.length} 条会话吗？未软删的会先软删再清理内容。token 用量记录会保留。`,
            okText: '清理',
        });
        if (!ok) return;
        try {
            const r = await fetch('/spring/ai/loom/admin/conversations/clean-batch', {
                method: 'POST', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({items: items.map(s => ({username: s.username, conversationId: s.conversationId}))}),
            });
            const data = await r.json();
            if (r.ok) {
                showToast(`清理完成：成功 ${data.ok}，失败 ${data.fail}`, data.fail > 0 ? 'error' : 'success');
                closeBatchClean();
                loadUsers();
            } else {
                showToast('清理失败：' + (data.error || r.status), 'error');
            }
        } catch (e) {
            showToast('网络错误：' + e.message, 'error');
        }
    };

    async function confirmBatchCleanFromModal() {
        const items = getSelectedItems();
        await window._confirmBatchClean(items, null);
    }

    // ========== 分配角色（在控制台内直接弹窗，不用跳页） ==========
    const assignModal = document.getElementById('assign-role-modal');
    const assignTitle = document.getElementById('assign-role-title');
    const assignHint = document.getElementById('assign-role-hint');
    const assignList = document.getElementById('assign-role-list');
    const assignErr = document.getElementById('assign-role-error');
    const assignSave = document.getElementById('assign-role-save');
    const assignClose = document.getElementById('assign-role-close');
    const assignCancel = document.getElementById('assign-role-cancel');
    let assignTarget = null; // {username, type}

    async function openAssignRole(username, type) {
        assignTarget = {username, type};
        assignErr.style.display = 'none';
        assignTitle.textContent = `分配角色：${username}`;
        if (type === 'ADMIN') {
            assignHint.textContent = '管理员账号默认拥有全部 MCP 服务，无需分配角色。';
            assignList.innerHTML = '';
            assignSave.style.display = 'none';
            assignModal.style.display = 'flex';
            return;
        }
        assignHint.textContent = '勾选要分配给该用户的角色（可多选）。用户实际可用的 MCP = 所有已选角色授权 MCP 的并集。';
        assignSave.style.display = '';
        assignList.innerHTML = '<div class="loading-indicator">加载中...</div>';
        assignModal.style.display = 'flex';
        try {
            const [allRoles, myRoles] = await Promise.all([
                fetch('/spring/ai/loom/admin/roles', {credentials: 'include'}).then(r => r.ok ? r.json() : []),
                fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/roles`, {credentials: 'include'}).then(r => r.ok ? r.json() : []),
            ]);
            renderAssignRoleList(allRoles || [], myRoles || []);
        } catch (e) {
            assignList.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function renderAssignRoleList(allRoles, myRoles) {
        if (!allRoles || allRoles.length === 0) {
            assignList.innerHTML = '<div style="color: var(--text-muted); padding: 8px;">系统暂无任何角色，请先到<a href="roles.html">角色管理</a>创建。</div>';
            return;
        }
        const mySet = new Set(myRoles);
        assignList.innerHTML = allRoles.map(r => `
            <label style="display: flex; align-items: center; gap: 8px; padding: 8px 12px; border: 1px solid var(--border-color); border-radius: 6px; cursor: pointer; background: ${mySet.has(r.code) ? '#f0fdf4' : '#fff'};">
                <input type="checkbox" class="assign-role-cb" value="${escapeHtml(r.code)}" ${mySet.has(r.code) ? 'checked' : ''}>
                <span style="flex: 1;">
                    <strong style="font-size: 13px;">${escapeHtml(r.code)}</strong>
                    <span style="color: var(--text-muted); font-size: 12px; margin-left: 8px;">${escapeHtml(r.name || '')}</span>
                    ${r.system ? '<span class="type-badge ADMIN" style="margin-left: 6px;">系统</span>' : ''}
                </span>
                <span style="color: var(--text-muted); font-size: 12px;">${escapeHtml(r.description || '')}</span>
            </label>
        `).join('');
    }

    function closeAssignRole() {
        assignModal.style.display = 'none';
        assignTarget = null;
    }

    assignSave.addEventListener('click', async () => {
        if (!assignTarget) return;
        const checked = Array.from(assignList.querySelectorAll('.assign-role-cb:checked'))
            .map(cb => cb.value);
        assignSave.disabled = true;
        assignErr.style.display = 'none';
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(assignTarget.username)}/roles`, {
                method: 'PUT', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({roleCodes: checked}),
            });
            if (!r.ok) {
                const t = await r.text();
                assignErr.textContent = `保存失败：${t || 'HTTP ' + r.status}`;
                assignErr.style.display = 'block';
                return;
            }
            showToast(`已为「${assignTarget.username}」分配 ${checked.length} 个角色`, 'success');
            closeAssignRole();
        } catch (e) {
            assignErr.textContent = '网络错误：' + e.message;
            assignErr.style.display = 'block';
        } finally {
            assignSave.disabled = false;
        }
    });

    assignClose.addEventListener('click', closeAssignRole);
    assignCancel.addEventListener('click', closeAssignRole);

    bootstrap();
})();
