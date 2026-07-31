(function () {
    'use strict';

    const tableContainer = document.getElementById('skill-table-container');
    const pendingCountLabel = document.getElementById('pending-count-label');
    const API = {
        list: '/spring/ai/loom/admin/market-skills',
        pending: '/spring/ai/loom/admin/market-skills/pending',
        create: '/spring/ai/loom/admin/market-skills',
        update: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
        del: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
        approve: (id) => `/spring/ai/loom/admin/market-skills/${id}/approve`,
        reject: (id) => `/spring/ai/loom/admin/market-skills/${id}/reject`,
    };

    let currentEdit = null; // null = 新建; {id} = 编辑
    let currentReject = null; // {id}
    let allSkills = [];

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function showToast(text, type = 'success') {
        const el = document.getElementById('toast-notification');
        el.textContent = text;
        el.className = 'toast show ' + type;
        setTimeout(() => { el.className = 'toast'; }, 2500);
    }

    function showErr(msg) {
        const e = document.getElementById('es-error');
        e.textContent = msg; e.style.display = 'block';
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
            function cleanup() { ok.onclick = null; cancel.onclick = null; close.onclick = null; }
        });
    }

    async function loadList() {
        tableContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const r = await fetch(API.list, {credentials: 'include'});
            if (r.status === 401 || r.status === 403) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            if (!r.ok) throw new Error('HTTP ' + r.status);
            allSkills = await r.json();
            renderTable();
        } catch (e) {
            tableContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function statusBadge(s) {
        if (s === 'APPROVED') return '<span class="type-badge ADMIN">已审批</span>';
        if (s === 'PENDING')  return '<span class="type-badge USER" style="background:#fef3c7;color:#92400e;">待审批</span>';
        if (s === 'REJECTED') return '<span class="type-badge" style="background:#fee2e2;color:#991b1b;">已拒绝</span>';
        return s;
    }

    function renderTable() {
        if (!allSkills || allSkills.length === 0) {
            tableContainer.innerHTML = '<div class="empty-state">市场暂无任何技能</div>';
            pendingCountLabel.textContent = '';
            return;
        }
        const pendingCount = allSkills.filter(m => m.status === 'PENDING').length;
        pendingCountLabel.textContent = pendingCount > 0 ? `待审批：${pendingCount}` : '';
        // PENDING 排前面
        const sorted = [...allSkills].sort((a, b) => {
            const order = {PENDING: 0, APPROVED: 1, REJECTED: 2};
            const oa = order[a.status] ?? 9;
            const ob = order[b.status] ?? 9;
            if (oa !== ob) return oa - ob;
            return (a.name + a.version).localeCompare(b.name + b.version);
        });
        const rows = sorted.map(m => {
            const displayName = m.name;
            const authorTag = m.author === 'system' ? '<span class="type-badge" style="background:#f1f5f9;color:#475569;margin-left:6px;">系统</span>' : '';
            return `<tr data-id="${m.id}">
                <td><strong>${escapeHtml(displayName)}</strong>${authorTag}<br><span style="font-size:11px;color:var(--text-muted);">v${escapeHtml(m.version)}</span></td>
                <td>${escapeHtml(m.description || '（无）')}</td>
                <td>${escapeHtml(m.author)}</td>
                <td>${statusBadge(m.status)}${m.reviewComment ? '<br><span style="font-size:11px;color:var(--text-muted);">' + escapeHtml(m.reviewComment) + '</span>' : ''}</td>
                <td>${m.reviewedBy ? escapeHtml(m.reviewedBy) + '<br><span style="font-size:11px;color:var(--text-muted);">' + (m.reviewedAt || '').slice(0, 16).replace('T', ' ') + '</span>' : '-'}</td>
                <td>
                    ${m.status === 'PENDING' ? `<button class="primary-btn approve-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-right:4px;">通过</button>
                       <button class="secondary-btn reject-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;color:var(--error-color,#ef4444);">拒绝</button>` : ''}
                    <button class="secondary-btn edit-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;margin-top:4px;">编辑</button>
                    <button class="secondary-btn del-btn" data-id="${m.id}" style="padding:4px 10px;font-size:12px;color:var(--error-color,#ef4444);margin-top:4px;">删除</button>
                </td>
            </tr>`;
        }).join('');
        tableContainer.innerHTML = `
            <table class="user-table">
                <thead><tr><th>名称</th><th>描述</th><th>作者</th><th>状态</th><th>审批人 / 时间</th><th>操作</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
        bindRowActions();
    }

    function bindRowActions() {
        tableContainer.querySelectorAll('.approve-btn').forEach(btn => {
            btn.addEventListener('click', () => approveSkill(parseInt(btn.getAttribute('data-id'))));
        });
        tableContainer.querySelectorAll('.reject-btn').forEach(btn => {
            btn.addEventListener('click', () => openReject(parseInt(btn.getAttribute('data-id'))));
        });
        tableContainer.querySelectorAll('.edit-btn').forEach(btn => {
            btn.addEventListener('click', () => openEdit(parseInt(btn.getAttribute('data-id'))));
        });
        tableContainer.querySelectorAll('.del-btn').forEach(btn => {
            btn.addEventListener('click', () => deleteSkill(parseInt(btn.getAttribute('data-id'))));
        });
    }

    async function approveSkill(id) {
        try {
            const r = await fetch(API.approve(id), {
                method: 'POST', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({comment: null}),
            });
            if (!r.ok) {
                showToast('审批失败：HTTP ' + r.status, 'error');
                return;
            }
            showToast('已通过审批', 'success');
            loadList();
        } catch (e) {
            showToast('网络错误：' + e.message, 'error');
        }
    }

    function openReject(id) {
        currentReject = {id};
        document.getElementById('reject-comment').value = '';
        document.getElementById('reject-modal').style.display = 'flex';
    }

    function closeReject() {
        document.getElementById('reject-modal').style.display = 'none';
        currentReject = null;
    }

    async function confirmReject() {
        if (!currentReject) return;
        const comment = document.getElementById('reject-comment').value.trim();
        try {
            const r = await fetch(API.reject(currentReject.id), {
                method: 'POST', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({comment: comment || null}),
            });
            if (!r.ok) { showToast('拒绝失败：HTTP ' + r.status, 'error'); return; }
            showToast('已拒绝', 'success');
            closeReject();
            loadList();
        } catch (e) {
            showToast('网络错误：' + e.message, 'error');
        }
    }

    function openEdit(id) {
        const m = allSkills.find(x => x.id === id);
        if (!m) return;
        currentEdit = {id};
        document.getElementById('edit-skill-title').textContent = '编辑技能';
        document.getElementById('es-name').value = m.name;
        document.getElementById('es-name').disabled = true;  // 编辑模式：name 不能改（PK 关联）
        document.getElementById('es-desc').value = m.description || '';
        document.getElementById('es-version').value = m.version;
        document.getElementById('es-version').disabled = true;
        document.getElementById('es-content').value = m.content || '';
        document.getElementById('es-error').style.display = 'none';
        document.getElementById('edit-skill-modal').style.display = 'flex';
    }

    function openCreate() {
        currentEdit = null;
        document.getElementById('edit-skill-title').textContent = '新建技能（直接 APPROVED）';
        document.getElementById('es-name').value = '';
        document.getElementById('es-name').disabled = false;
        document.getElementById('es-desc').value = '';
        document.getElementById('es-version').value = '1.0.0';
        document.getElementById('es-version').disabled = false;
        document.getElementById('es-content').value = '';
        document.getElementById('es-error').style.display = 'none';
        document.getElementById('edit-skill-modal').style.display = 'flex';
    }

    function closeEdit() {
        document.getElementById('edit-skill-modal').style.display = 'none';
        currentEdit = null;
    }

    async function saveEdit() {
        const name = document.getElementById('es-name').value.trim();
        const desc = document.getElementById('es-desc').value.trim();
        const version = document.getElementById('es-version').value.trim();
        const content = document.getElementById('es-content').value;
        if (!name || !version || !content.trim()) {
            showErr('名称、版本号、内容均不能为空');
            return;
        }
        const body = {name, description: desc, content, version, status: 'APPROVED'};
        try {
            let r;
            if (currentEdit) {
                r = await fetch(API.update(currentEdit.id), {
                    method: 'PUT', credentials: 'include',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(body),
                });
            } else {
                r = await fetch(API.create, {
                    method: 'POST', credentials: 'include',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(body),
                });
            }
            if (!r.ok) {
                const t = await r.text();
                showErr('保存失败：' + (t || 'HTTP ' + r.status));
                return;
            }
            showToast('已保存', 'success');
            closeEdit();
            loadList();
        } catch (e) {
            showErr('网络错误：' + e.message);
        }
    }

    async function deleteSkill(id) {
        const m = allSkills.find(x => x.id === id);
        if (!m) return;
        const ok = await confirmDialog({
            title: '删除技能',
            message: `确定要删除「${m.name} v${m.version}」？\n\n会同时清理所有 user_skill / role_skill 里对它的引用。`,
            okText: '删除',
        });
        if (!ok) return;
        try {
            const r = await fetch(API.del(id), {method: 'DELETE', credentials: 'include'});
            if (!r.ok) {
                const t = await r.text();
                showToast('删除失败：' + (t || 'HTTP ' + r.status), 'error');
                return;
            }
            showToast('已删除', 'success');
            loadList();
        } catch (e) {
            showToast('网络错误：' + e.message, 'error');
        }
    }

    // 事件
    document.getElementById('create-skill-btn').addEventListener('click', openCreate);
    document.getElementById('refresh-btn').addEventListener('click', loadList);
    document.getElementById('edit-skill-close').addEventListener('click', closeEdit);
    document.getElementById('es-cancel').addEventListener('click', closeEdit);
    document.getElementById('es-save').addEventListener('click', saveEdit);
    document.getElementById('reject-close').addEventListener('click', closeReject);
    document.getElementById('reject-cancel').addEventListener('click', closeReject);
    document.getElementById('reject-confirm').addEventListener('click', confirmReject);

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadList);
    } else {
        loadList();
    }

    // 顶部右侧渲染当前用户名（统一 header 风格）
    fetch('/spring/ai/loom/user/currentUser', {method: 'POST', credentials: 'include'})
        .then(r => r.ok ? r.json() : null)
        .then(me => {
            if (me) {
                const el = document.getElementById('admin-username');
                if (el) el.textContent = `${me.nickname || me.username}（${me.type === 'ADMIN' ? '管理员' : '用户'}）`;
            }
        }).catch(() => {});
})();
