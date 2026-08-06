/**
 * 管理台 · 知识库市场
 * 列表 + 新建 / 编辑 / 删除（绕过审批）+ 审批 / 拒绝 PENDING
 */
(function () {
    'use strict';

    const ENDPOINTS = {
        list: '/spring/ai/loom/admin/market-skills',
        pending: '/spring/ai/loom/admin/market-skills/pending',
        create: '/spring/ai/loom/admin/market-skills',
        upsert: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
        remove: (id) => `/spring/ai/loom/admin/market-skills/${id}`,
        approve: (id) => `/spring/ai/loom/admin/market-skills/${id}/approve`,
        reject: (id) => `/spring/ai/loom/admin/market-skills/${id}/reject`,
        username: '/spring/ai/loom/user/currentUser',
    };

    /** 通用 fetch 封装：带 cookie，错误统一捕获 */
    async function api(url, options = {}) {
        const resp = await fetch(url, {
            credentials: 'include',
            ...options,
            headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
        });
        let body = null;
        try { body = await resp.json(); } catch (_) { body = null; }
        if (!resp.ok) {
            const msg = (body && body.error) || `HTTP ${resp.status}`;
            throw new Error(msg);
        }
        return body;
    }

    /** Toast 提示 */
    function toast(text, type) {
        const t = document.getElementById('toast-notification');
        if (!t) return;
        t.textContent = text;
        t.className = 'toast' + (type === 'error' ? ' error' : type === 'success' ? ' success' : '');
        t.style.opacity = '1';
        clearTimeout(toast._t);
        toast._t = setTimeout(() => { t.style.opacity = '0'; }, 2500);
    }

    /** 截断长文本 */
    function clamp(s, n) {
        if (s == null) return '';
        s = String(s);
        return s.length > n ? s.slice(0, n) + '…' : s;
    }

    /** 转义 HTML */
    function esc(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /** 渲染整张表格 */
    function render(items) {
        const pendingCount = items.filter(i => i.status === 'PENDING').length;
        const label = document.getElementById('pending-count-label');
        if (label) label.textContent = pendingCount > 0 ? `待审批 ${pendingCount} 条` : '';

        const c = document.getElementById('knowledge-table-container');
        if (!c) return;
        if (!items || items.length === 0) {
            c.innerHTML = '<div class="empty-state">暂无市场知识库</div>';
            return;
        }

        const rows = items.map((k) => {
            const isPending = k.status === 'PENDING';
            const reviewCells = (k.reviewedAt || k.reviewedBy)
                ? `<span title="${esc(k.reviewedAt || '')}">${esc(k.reviewedBy || '')} · ${esc((k.reviewedAt || '').toString().slice(0,10))}</span>`
                : '<span class="text-muted">—</span>';
            const reviewComment = k.reviewComment ? `<div class="text-muted" style="font-size:11px;">${esc(clamp(k.reviewComment, 60))}</div>` : '';
            return `
                <tr data-id="${k.id}">
                    <td><div style="font-weight:500;">${esc(k.name || '')}</div><div class="text-muted" style="font-size:11px;">v${esc(k.version || '')}</div></td>
                    <td><div style="max-width:300px;">${esc(clamp(k.description || '', 80))}</div></td>
                    <td>${esc(k.author || '')}</td>
                    <td><span class="status-badge status-${esc(k.status || '').toLowerCase()}">${esc(k.status || '')}</span></td>
                    <td>${reviewCells}${reviewComment}</td>
                    <td class="row-actions">
                        ${isPending ? `<button class="primary-btn" data-act="approve" data-id="${k.id}">通过</button>
                                       <button class="secondary-btn" data-act="reject" data-id="${k.id}">拒绝</button>` : ''}
                        <button class="secondary-btn" data-act="edit" data-id="${k.id}">编辑</button>
                        <button class="delete-btn" data-act="delete" data-id="${k.id}">删除</button>
                    </td>
                </tr>`;
        }).join('');

        c.innerHTML = `
            <table class="user-table">
                <thead>
                    <tr>
                        <th style="width:18%;">名称 / 版本</th>
                        <th style="width:30%;">描述</th>
                        <th style="width:10%;">作者</th>
                        <th style="width:10%;">状态</th>
                        <th style="width:18%;">审批人 / 时间</th>
                        <th style="width:14%;">操作</th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>`;
    }

    /** 加载列表 */
    async function load() {
        const c = document.getElementById('knowledge-table-container');
        if (c) c.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const items = await api(ENDPOINTS.list);
            render(items || []);
        } catch (e) {
            toast('加载失败：' + e.message, 'error');
            if (c) c.innerHTML = `<div class="empty-state error">加载失败：${esc(e.message)}</div>`;
        }
    }

    /** 显示 / 隐藏编辑模态框 */
    function openEditModal(rec) {
        const m = document.getElementById('edit-knowledge-modal');
        const title = document.getElementById('edit-knowledge-title');
        const errBox = document.getElementById('edit-knowledge-error');
        errBox.style.display = 'none';
        errBox.textContent = '';
        if (rec) {
            title.textContent = `编辑知识库 #${rec.id}`;
            document.getElementById('ek-name').value = rec.name || '';
            document.getElementById('ek-description').value = rec.description || '';
            document.getElementById('ek-content').value = rec.content || '';
            document.getElementById('ek-version').value = rec.version || '1.0.0';
            document.getElementById('ek-status').value = rec.status || 'APPROVED';
            m.dataset.editId = String(rec.id);
        } else {
            title.textContent = '新建知识库';
            document.getElementById('ek-name').value = '';
            document.getElementById('ek-description').value = '';
            document.getElementById('ek-content').value = '';
            document.getElementById('ek-version').value = '1.0.0';
            document.getElementById('ek-status').value = 'APPROVED';
            delete m.dataset.editId;
        }
        m.style.display = 'flex';
    }
    function closeEditModal() {
        const m = document.getElementById('edit-knowledge-modal');
        m.style.display = 'none';
    }

    /** 保存（新建或更新） */
    async function saveEdit() {
        const m = document.getElementById('edit-knowledge-modal');
        const errBox = document.getElementById('edit-knowledge-error');
        const body = {
            name: document.getElementById('ek-name').value.trim(),
            description: document.getElementById('ek-description').value.trim(),
            content: document.getElementById('ek-content').value,
            version: document.getElementById('ek-version').value.trim() || '1.0.0',
            status: document.getElementById('ek-status').value,
        };
        if (!body.name) { errBox.textContent = '名称必填'; errBox.style.display = 'block'; return; }
        if (!body.content) { errBox.textContent = '内容必填'; errBox.style.display = 'block'; return; }
        try {
            const id = m.dataset.editId;
            if (id) {
                await api(ENDPOINTS.upsert(id), { method: 'PUT', body: JSON.stringify(body) });
                toast('已更新', 'success');
            } else {
                await api(ENDPOINTS.create, { method: 'POST', body: JSON.stringify(body) });
                toast('已创建', 'success');
            }
            closeEditModal();
            await load();
        } catch (e) {
            errBox.textContent = '保存失败：' + e.message;
            errBox.style.display = 'block';
        }
    }

    /** 确认对话框（Promise 风格） */
    function confirmDialog(title, message) {
        return new Promise((resolve) => {
            const m = document.getElementById('confirm-modal');
            document.getElementById('confirm-title').textContent = title;
            document.getElementById('confirm-message').textContent = message;
            const ok = document.getElementById('confirm-ok');
            const cancel = document.getElementById('confirm-cancel');
            const closeBtn = document.getElementById('confirm-close');
            const cleanup = (result) => {
                m.style.display = 'none';
                ok.removeEventListener('click', onOk);
                cancel.removeEventListener('click', onCancel);
                closeBtn.removeEventListener('click', onCancel);
                resolve(result);
            };
            const onOk = () => cleanup(true);
            const onCancel = () => cleanup(false);
            ok.addEventListener('click', onOk);
            cancel.addEventListener('click', onCancel);
            closeBtn.addEventListener('click', onCancel);
            m.style.display = 'flex';
        });
    }

    /** 删除 */
    async function doDelete(id, name) {
        const ok = await confirmDialog('删除知识库', `确定要删除市场知识库「${name}」吗？此操作不可撤销。`);
        if (!ok) return;
        try {
            await api(ENDPOINTS.remove(id), { method: 'DELETE' });
            toast('已删除', 'success');
            await load();
        } catch (e) {
            toast('删除失败：' + e.message, 'error');
        }
    }

    /** 审批 */
    async function doApprove(id) {
        try {
            await api(ENDPOINTS.approve(id), { method: 'POST', body: '{}' });
            toast('已通过', 'success');
            await load();
        } catch (e) {
            toast('通过失败：' + e.message, 'error');
        }
    }
    async function doReject(id) {
        try {
            await api(ENDPOINTS.reject(id), { method: 'POST', body: '{}' });
            toast('已拒绝', 'success');
            await load();
        } catch (e) {
            toast('拒绝失败：' + e.message, 'error');
        }
    }

    /** 事件绑定 */
    function bind() {
        document.getElementById('refresh-btn')?.addEventListener('click', load);
        document.getElementById('new-knowledge-btn')?.addEventListener('click', () => openEditModal(null));
        document.getElementById('edit-knowledge-close')?.addEventListener('click', closeEditModal);
        document.getElementById('edit-knowledge-cancel')?.addEventListener('click', closeEditModal);
        document.getElementById('edit-knowledge-submit')?.addEventListener('click', saveEdit);
        document.getElementById('knowledge-table-container')?.addEventListener('click', async (e) => {
            const btn = e.target.closest('button[data-act]');
            if (!btn) return;
            const id = btn.dataset.id;
            const act = btn.dataset.act;
            if (act === 'edit') {
                try {
                    const items = await api(ENDPOINTS.list);
                    const rec = (items || []).find((x) => String(x.id) === String(id));
                    if (rec) openEditModal(rec);
                    else toast('记录不存在', 'error');
                } catch (err) { toast('读取失败：' + err.message, 'error'); }
            } else if (act === 'delete') {
                const items = await api(ENDPOINTS.list).catch(() => []);
                const rec = (items || []).find((x) => String(x.id) === String(id));
                doDelete(id, rec ? rec.name : `#${id}`);
            } else if (act === 'approve') {
                doApprove(id);
            } else if (act === 'reject') {
                doReject(id);
            }
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        bind();
        load();
    });
})();