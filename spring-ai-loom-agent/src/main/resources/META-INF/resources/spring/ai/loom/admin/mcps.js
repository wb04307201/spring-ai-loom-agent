(function () {
    'use strict';

    const tableContainer = document.getElementById('mcp-table-container');
    const API = {
        list: '/spring/ai/loom/admin/mcp-system',
        update: (name) => `/spring/ai/loom/admin/mcps/${encodeURIComponent(name)}`,
        tools: (name) => `/spring/ai/loom/admin/mcps/${encodeURIComponent(name)}/tools`,
        updateTool: (name, id) => `/spring/ai/loom/admin/mcps/${encodeURIComponent(name)}/tools/${id}`,
    };

    let currentEdit = null;

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
        const e = document.getElementById('em-error');
        e.textContent = msg; e.style.display = 'block';
    }

    function confirmDialog({ title, message, okText = '确定' }) {
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
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const list = await r.json();
            renderTable(list);
        } catch (e) {
            tableContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function renderTable(list) {
        if (!list || list.length === 0) {
            tableContainer.innerHTML = '<div class="empty-state">系统未连接任何 MCP 服务</div>';
            return;
        }
        const rows = list.map(m => {
            const maintainedTag = m.maintained
                ? '<span class="type-badge" style="background: #dbeafe; color: #1e40af;">已维护</span>'
                : '<span class="type-badge" style="background: #fef3c7; color: #92400e;">未维护</span>';
            const displayName = m.title || m.name;
            return `<tr>
                <td><strong>${escapeHtml(m.name)}</strong></td>
                <td>${escapeHtml(displayName)}</td>
                <td style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${escapeHtml(m.description || (m.maintained ? '' : '（使用 SDK 默认描述）'))}</td>
                <td>${maintainedTag}</td>
                <td>
                    <button class="secondary-btn edit-mcp-btn" data-name="${escapeHtml(m.name)}">编辑</button>
                </td>
            </tr>`;
        }).join('');
        tableContainer.innerHTML = `
            <table class="user-table">
                <thead><tr><th>服务名</th><th>标题</th><th>描述</th><th>状态</th><th>操作</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
        tableContainer.querySelectorAll('.edit-mcp-btn').forEach(btn => {
            btn.addEventListener('click', () => openEdit(btn.getAttribute('data-name')));
        });
    }

    async function openEdit(name) {
        currentEdit = {name};
        document.getElementById('em-name').value = name;
        document.getElementById('em-title').value = '';
        document.getElementById('em-desc').value = '';
        document.getElementById('em-error').style.display = 'none';
        document.getElementById('em-tools').innerHTML = '加载中...';
        document.getElementById('edit-mcp-modal').style.display = 'flex';

        try {
            const [list, tools] = await Promise.all([
                (await fetch(API.list, {credentials: 'include'})).json(),
                (await fetch(API.tools(name), {credentials: 'include'})).json(),
            ]);
            const m = list.find(x => x.name === name);
            if (m) {
                document.getElementById('edit-mcp-title').textContent = `编辑 MCP：${m.name}${m.title ? '（' + m.title + '）' : ''}`;
                document.getElementById('em-title').value = m.title || '';
                document.getElementById('em-desc').value = m.description || '';
            }
            renderTools(tools || []);
        } catch (e) {
            document.getElementById('em-tools').innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function renderTools(tools) {
        if (!tools || tools.length === 0) {
            document.getElementById('em-tools').innerHTML = '<div style="color: var(--text-muted); font-size: 13px; padding: 8px;">此服务无工具</div>';
            return;
        }
        document.getElementById('em-tools').innerHTML = tools.map(t => `
            <div style="padding: 12px; border: 1px solid var(--border-color); border-radius: 6px; margin-bottom: 8px;">
                <div style="font-weight: 600; font-size: 13px; color: var(--primary-color); margin-bottom: 6px;">${escapeHtml(t.name)}</div>
                <textarea data-tool-id="${t.id}" data-original="${escapeHtml(t.description || '')}" class="form-input" rows="2"
                    style="font-size: 12px;">${escapeHtml(t.description || '')}</textarea>
            </div>
        `).join('');
    }

    function closeEdit() {
        document.getElementById('edit-mcp-modal').style.display = 'none';
    }

    async function saveEdit() {
        const name = currentEdit.name;
        const title = document.getElementById('em-title').value.trim();
        const desc = document.getElementById('em-desc').value.trim();
        // 只提交有改动的字段：title/desc 空则视为清空（服务端存 NULL）
        const body = {title: title || null, description: desc || null};
        try {
            const r = await fetch(API.update(name), {
                method: 'PUT', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body),
            });
            if (!r.ok) {
                const t = await r.text();
                showErr(t || 'HTTP ' + r.status);
                return;
            }
        } catch (e) {
            showErr('保存服务失败：' + e.message);
            return;
        }
        // 保存工具描述（只提交改动的）
        const toolEls = document.querySelectorAll('#em-tools textarea[data-tool-id]');
        const toolUpdates = [];
        toolEls.forEach(el => {
            const id = el.getAttribute('data-tool-id');
            const original = el.getAttribute('data-original') || '';
            const current = el.value;
            if (current !== original) {
                toolUpdates.push(
                    fetch(API.updateTool(name, id), {
                        method: 'PUT', credentials: 'include',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({description: current || null}),
                    })
                );
            }
        });
        try {
            await Promise.all(toolUpdates);
        } catch (e) {
            showErr('部分工具描述保存失败：' + e.message);
        }
        showToast('保存成功', 'success');
        closeEdit();
        loadList();
    }

    document.getElementById('refresh-btn').addEventListener('click', loadList);
    document.getElementById('edit-mcp-close').addEventListener('click', closeEdit);
    document.getElementById('em-cancel').addEventListener('click', closeEdit);
    document.getElementById('em-save').addEventListener('click', saveEdit);

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadList);
    } else {
        loadList();
    }
})();
