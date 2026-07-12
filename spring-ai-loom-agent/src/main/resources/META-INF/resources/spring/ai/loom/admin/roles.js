(function () {
    'use strict';

    const tableContainer = document.getElementById('role-table-container');
    const API = {
        list: '/spring/ai/loom/admin/roles',
        create: '/spring/ai/loom/admin/roles',
        del: (code) => `/spring/ai/loom/admin/roles/${encodeURIComponent(code)}`,
        getMcps: (code) => `/spring/ai/loom/admin/roles/${encodeURIComponent(code)}/mcps`,
        setMcps: (code) => `/spring/ai/loom/admin/roles/${encodeURIComponent(code)}/mcps`,
        getSkills: (code) => `/spring/ai/loom/admin/roles/${encodeURIComponent(code)}/skills`,
        setSkills: (code) => `/spring/ai/loom/admin/roles/${encodeURIComponent(code)}/skills`,
        marketApproved: '/spring/ai/loom/market-skills',
        system: '/spring/ai/loom/admin/mcp-system',
    };

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
        const e = document.getElementById('create-role-error');
        e.textContent = msg; e.style.display = 'block';
    }

    function showRdError(msg) {
        const e = document.getElementById('rd-error');
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

    async function loadRoles() {
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
            tableContainer.innerHTML = '<div class="empty-state">暂无角色</div>';
            return;
        }
        const rows = list.map(r => {
            const sysTag = r.system ? '<span class="type-badge ADMIN" style="margin-left: 8px;">系统</span>' : '';
            return `<tr data-code="${escapeHtml(r.code)}">
                <td><strong>${escapeHtml(r.code)}</strong>${sysTag}</td>
                <td>${escapeHtml(r.name)}</td>
                <td>${escapeHtml(r.description || '')}</td>
                <td><button class="secondary-btn edit-role-btn" data-code="${escapeHtml(r.code)}">编辑 / 授权</button></td>
            </tr>`;
        }).join('');
        tableContainer.innerHTML = `
            <table class="user-table">
                <thead><tr><th>code</th><th>名称</th><th>描述</th><th>操作</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>`;
        tableContainer.querySelectorAll('.edit-role-btn').forEach(btn => {
            btn.addEventListener('click', () => openDetail(btn.getAttribute('data-code')));
        });
    }

    // ===== 新建角色 =====
    async function openCreate() {
        document.getElementById('new-role-code').value = '';
        document.getElementById('new-role-name').value = '';
        document.getElementById('new-role-desc').value = '';
        document.getElementById('create-role-error').style.display = 'none';
        const mcps = await loadAllSystemMcps();
        renderMcpCheckboxes(document.getElementById('new-role-mcps'), mcps, new Set());
        document.getElementById('create-role-modal').style.display = 'flex';
    }
    function closeCreate() {
        document.getElementById('create-role-modal').style.display = 'none';
    }
    async function submitCreate() {
        const code = document.getElementById('new-role-code').value.trim();
        const name = document.getElementById('new-role-name').value.trim();
        const desc = document.getElementById('new-role-desc').value.trim();
        if (!code || !name) {
            showErr('code 和名称必填');
            return;
        }
        const checkedMcps = collectCheckedMcps(document.getElementById('new-role-mcps'));
        try {
            const r = await fetch(API.create, {
                method: 'POST', credentials: 'include',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({code, name, description: desc, mcpNames: Array.from(checkedMcps)}),
            });
            if (!r.ok) {
                const txt = await r.text();
                showErr(txt || 'HTTP ' + r.status);
                return;
            }
            showToast('角色创建成功', 'success');
            closeCreate();
            loadRoles();
        } catch (e) {
            showErr('网络错误：' + e.message);
        }
    }

    // ===== 角色详情（编辑/授权） =====
    let currentDetail = null;
    // currentAllowed = [{name, defaultEnabled}, ...]，按 sort_order 排序；
    // 后端 /admin/roles/{code}/mcps 直接返回 RoleMcpItem 列表
    let currentAllowed = [];
    let currentSystemMcps = []; // 系统全部 mcp 视图
    // 技能授权
    // currentRoleSkills = [{marketSkillId, defaultLoaded}, ...]
    let currentRoleSkills = [];
    let currentMarketSkills = []; // 市场里 APPROVED 的所有 skill

    function allowedNameSet() {
        return new Set(currentAllowed.map(it => it.name));
    }

    function findAllowed(name) {
        return currentAllowed.find(it => it.name === name);
    }

    function roleSkillById(id) {
        return currentRoleSkills.find(it => it.marketSkillId === id);
    }

    async function openDetail(code) {
        currentDetail = {code};
        document.getElementById('rd-name').value = '';
        document.getElementById('rd-desc').value = '';
        document.getElementById('rd-error').style.display = 'none';
        document.getElementById('rd-mcps').innerHTML = '加载中...';
        document.getElementById('rd-skills').innerHTML = '加载中...';
        document.getElementById('role-detail-modal').style.display = 'flex';

        try {
            const [allRoles, allowed, system, roleSkills, market] = await Promise.all([
                (await fetch(API.list, {credentials: 'include'})).json(),
                (await fetch(API.getMcps(code), {credentials: 'include'})).json(),
                loadAllSystemMcps(),
                (await fetch(API.getSkills(code), {credentials: 'include'})).json().catch(() => []),
                loadMarketApproved(),
            ]);
            const role = allRoles.find(x => x.code === code);
            if (!role) { showRdError('角色不存在：' + code); return; }
            document.getElementById('role-detail-title').textContent = `角色：${role.code} ${role.system ? '（系统）' : ''}`;
            document.getElementById('rd-name').value = role.name || '';
            document.getElementById('rd-desc').value = role.description || '';
            currentSystemMcps = system;
            currentAllowed = allowed || [];
            currentRoleSkills = (roleSkills || []).map(it => ({marketSkillId: it.marketSkillId, defaultLoaded: it.defaultLoaded !== false}));
            currentMarketSkills = market || [];
            renderRoleMcpList();
            renderRoleSkillList();
        } catch (e) {
            showRdError('加载失败：' + e.message);
        }
    }

    function closeDetail() {
        document.getElementById('role-detail-modal').style.display = 'none';
    }

    /**
     * 两段式渲染：
     *  ① 上方：已授权 mcp（按 sort_order 排序）—— 可见"默认启用"复选框 + 上下移动按钮 + 移除授权按钮
     *  ② 下方：未授权 mcp（按名称排序）—— 仅显示名称 + 维护状态 + 「添加授权」按钮
     * currentAllowed = [{name, defaultEnabled}, ...]，需同步维护。
     */
    function renderRoleMcpList() {
        const container = document.getElementById('rd-mcps');
        if (!currentSystemMcps || currentSystemMcps.length === 0) {
            container.innerHTML = '<div style="color: var(--text-muted); padding: 8px;">无可用 mcp</div>';
            return;
        }
        const allowed = currentAllowed
            .map(item => ({item, m: currentSystemMcps.find(x => x.name === item.name)}))
            .filter(p => p.m);
        const notAllowed = currentSystemMcps.filter(m => !allowedNameSet().has(m.name));

        const allowedHtml = allowed.length === 0
            ? '<div style="color: var(--text-muted); font-size: 12px; padding: 8px;">尚未授权任何 mcp。点击下方"添加授权"加入。</div>'
            : allowed.map(({item, m}, idx) => {
                const defaultSel = item.defaultEnabled !== false;
                const isFirst = idx === 0;
                const isLast = idx === allowed.length - 1;
                const maintainedTag = !m.maintained
                    ? '<span class="type-badge" style="background:#fef3c7;color:#92400e;margin-left:6px;">未维护</span>' : '';
                return `<div style="display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--border-color); background: #f0fdf4;">
                    <span style="width: 24px; text-align: center; color: var(--text-muted); font-size: 12px;">${idx + 1}.</span>
                    <span style="flex: 1;">
                        <div style="font-weight: 500; font-size: 13px;">${escapeHtml(m.title || m.name)}${maintainedTag}</div>
                        <div style="font-size: 11px; color: var(--text-muted);">${escapeHtml(m.name)}</div>
                    </span>
                    <label style="display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-primary); cursor: pointer; user-select: none;">
                        <input type="checkbox" class="default-sel-cb" data-mcp-name="${escapeHtml(m.name)}" ${defaultSel ? 'checked' : ''}>
                        <strong>默认启用</strong>
                    </label>
                    <span style="display: flex; flex-direction: column; gap: 2px;">
                        <button class="secondary-btn move-up-btn" data-idx="${idx}" style="padding: 0 6px; font-size: 11px;" ${isFirst ? 'disabled' : ''}>↑</button>
                        <button class="secondary-btn move-down-btn" data-idx="${idx}" style="padding: 0 6px; font-size: 11px;" ${isLast ? 'disabled' : ''}>↓</button>
                    </span>
                    <button class="secondary-btn remove-auth-btn" data-mcp-name="${escapeHtml(m.name)}" style="padding: 4px 8px; font-size: 12px; color: var(--error-color, #ef4444);">移除</button>
                </div>`;
            }).join('');

        const notAllowedHtml = notAllowed.length === 0
            ? '<div style="color: var(--text-muted); font-size: 12px; padding: 8px;">所有 mcp 都已授权。</div>'
            : notAllowed.map(m => {
                const displayName = m.title || m.name;
                const maintainedTag = !m.maintained
                    ? '<span class="type-badge" style="background:#fef3c7;color:#92400e;margin-left:6px;">未维护</span>' : '';
                return `<div style="display: flex; align-items: center; gap: 12px; padding: 8px 12px; border-bottom: 1px solid var(--border-color);">
                    <span style="flex: 1;">
                        <div style="font-weight: 500; font-size: 13px;">${escapeHtml(displayName)}${maintainedTag}</div>
                        <div style="font-size: 11px; color: var(--text-muted);">${escapeHtml(m.name)}</div>
                    </span>
                    <button class="primary-btn add-auth-btn" data-mcp-name="${escapeHtml(m.name)}" style="padding: 4px 12px; font-size: 12px;">添加授权</button>
                </div>`;
            }).join('');

        container.innerHTML = `
            <div style="margin-bottom: 6px; font-size: 12px; color: var(--text-muted);">① 已授权 MCP（${allowed.length}）—— 调整顺序、勾选「默认启用」、或移除</div>
            <div id="rd-allowed-list" style="border: 1px solid var(--border-color); border-radius: 6px; margin-bottom: 16px; max-height: 280px; overflow-y: auto;">${allowedHtml}</div>
            <div style="margin-bottom: 6px; font-size: 12px; color: var(--text-muted);">② 可选 MCP（${notAllowed.length}）—— 点击「添加授权」纳入上方</div>
            <div id="rd-available-list" style="border: 1px solid var(--border-color); border-radius: 6px; max-height: 220px; overflow-y: auto;">${notAllowedHtml}</div>
        `;

        // 事件绑定
        const allowedBox = container.querySelector('#rd-allowed-list');
        const availBox = container.querySelector('#rd-available-list');

        allowedBox.querySelectorAll('.default-sel-cb[data-mcp-name]').forEach(cb => {
            cb.addEventListener('change', () => {
                const name = cb.getAttribute('data-mcp-name');
                const item = findAllowed(name);
                if (item) item.defaultEnabled = cb.checked;
            });
        });
        allowedBox.querySelectorAll('.move-up-btn').forEach(btn => {
            btn.addEventListener('click', () => moveItem(parseInt(btn.getAttribute('data-idx')), -1));
        });
        allowedBox.querySelectorAll('.move-down-btn').forEach(btn => {
            btn.addEventListener('click', () => moveItem(parseInt(btn.getAttribute('data-idx')), +1));
        });
        allowedBox.querySelectorAll('.remove-auth-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const name = btn.getAttribute('data-mcp-name');
                currentAllowed = currentAllowed.filter(x => x.name !== name);
                renderRoleMcpList();
            });
        });
        availBox.querySelectorAll('.add-auth-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const name = btn.getAttribute('data-mcp-name');
                if (!findAllowed(name)) {
                    // 新授权默认 defaultEnabled=true
                    currentAllowed.push({name, defaultEnabled: true});
                    renderRoleMcpList();
                }
            });
        });
    }

    function moveItem(idx, dir) {
        // idx 是"已授权列表"的索引（与 currentAllowed 一一对应）
        const newIdx = idx + dir;
        if (newIdx < 0 || newIdx >= currentAllowed.length) return;
        const t = currentAllowed[idx];
        currentAllowed[idx] = currentAllowed[newIdx];
        currentAllowed[newIdx] = t;
        renderRoleMcpList();
    }

    async function saveDetail() {
        const code = currentDetail.code;
        // MCP
        const mcpItems = currentAllowed.map(it => ({
            name: it.name,
            defaultEnabled: it.defaultEnabled !== false,
        }));
        // Skill
        const skillItems = currentRoleSkills.map(it => ({
            marketSkillId: it.marketSkillId,
            defaultLoaded: it.defaultLoaded !== false,
        }));
        try {
            const [r1, r2] = await Promise.all([
                fetch(API.setMcps(code), {
                    method: 'PUT', credentials: 'include',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({items: mcpItems}),
                }),
                fetch(API.setSkills(code), {
                    method: 'PUT', credentials: 'include',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({items: skillItems}),
                }),
            ]);
            if (r1.ok && r2.ok) {
                showToast(`已保存：${mcpItems.length} 项 MCP + ${skillItems.length} 项 Skill`, 'success');
                closeDetail();
                loadRoles();
            } else {
                const t = !r1.ok ? await r1.text() : await r2.text();
                showRdError('保存失败：' + (t || 'HTTP'));
            }
        } catch (e) {
            showRdError('网络错误：' + e.message);
        }
    }

    async function deleteRole(code) {
        const ok = await confirmDialog({
            title: '删除角色',
            message: `确定要删除角色「${code}」吗？已分配该角色的用户会失去对应权限。`,
            okText: '删除',
        });
        if (!ok) return;
        try {
            const r = await fetch(API.del(code), {method: 'DELETE', credentials: 'include'});
            if (r.ok) {
                showToast('角色已删除', 'success');
                closeDetail();
                loadRoles();
            } else {
                const t = await r.text();
                showRdError(t || 'HTTP ' + r.status);
            }
        } catch (e) {
            showRdError('网络错误：' + e.message);
        }
    }

    // ===== 辅助 =====
    async function loadAllSystemMcps() {
        try {
            const r = await fetch(API.system, {credentials: 'include'});
            if (!r.ok) return [];
            return await r.json();
        } catch (e) { return []; }
    }

    async function loadMarketApproved() {
        try {
            const r = await fetch(API.marketApproved, {credentials: 'include'});
            if (!r.ok) return [];
            return await r.json();
        } catch (e) { return []; }
    }

    /**
     * 技能授权两段式（参考 renderRoleMcpList 风格）：
     *  ① 已授权（按 currentRoleSkills 顺序）—— 默认加载复选框 + 移除
     *  ② 可选（市场 APPROVED 中未授权的）—— 添加授权
     */
    function renderRoleSkillList() {
        const container = document.getElementById('rd-skills');
        if (!currentMarketSkills || currentMarketSkills.length === 0) {
            container.innerHTML = '<div style="color: var(--text-muted); padding: 8px;">市场暂无已审批的 Skill。请先到<a href="skills-market.html">Skill 市场</a>创建。</div>';
            return;
        }
        const granted = currentRoleSkills
            .map(rs => ({rs, m: currentMarketSkills.find(s => s.id === rs.marketSkillId)}))
            .filter(p => p.m);
        const grantedSet = new Set(granted.map(p => p.m.id));
        const available = currentMarketSkills.filter(m => !grantedSet.has(m.id));

        const grantedHtml = granted.length === 0
            ? '<div style="color: var(--text-muted); font-size: 12px; padding: 8px;">尚未授权任何 Skill。</div>'
            : granted.map(({rs, m}, idx) => {
                const isFirst = idx === 0;
                const isLast = idx === granted.length - 1;
                return `<div style="display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--border-color); background: #f0fdf4;">
                    <span style="width: 24px; text-align: center; color: var(--text-muted); font-size: 12px;">${idx + 1}.</span>
                    <span style="flex: 1;">
                        <div style="font-weight: 500; font-size: 13px;">${escapeHtml(m.name)} <span style="color: var(--text-muted); font-size: 11px;">v${escapeHtml(m.version)} · @${escapeHtml(m.author)}</span></div>
                        <div style="font-size: 11px; color: var(--text-muted);">${escapeHtml((m.description || '').slice(0, 80))}</div>
                    </span>
                    <label style="display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-primary); cursor: pointer; user-select: none;">
                        <input type="checkbox" class="rs-default-cb" data-skill-id="${m.id}" ${rs.defaultLoaded ? 'checked' : ''}>
                        <strong>默认加载</strong>
                    </label>
                    <span style="display: flex; flex-direction: column; gap: 2px;">
                        <button class="secondary-btn rs-move-up" data-idx="${idx}" style="padding: 0 6px; font-size: 11px;" ${isFirst ? 'disabled' : ''}>↑</button>
                        <button class="secondary-btn rs-move-down" data-idx="${idx}" style="padding: 0 6px; font-size: 11px;" ${isLast ? 'disabled' : ''}>↓</button>
                    </span>
                    <button class="secondary-btn rs-remove" data-skill-id="${m.id}" style="padding: 4px 8px; font-size: 12px; color: var(--error-color, #ef4444);">移除</button>
                </div>`;
            }).join('');

        const availableHtml = available.length === 0
            ? '<div style="color: var(--text-muted); font-size: 12px; padding: 8px;">所有市场 Skill 都已授权。</div>'
            : available.map(m => `
                <div style="display: flex; align-items: center; gap: 12px; padding: 8px 12px; border-bottom: 1px solid var(--border-color);">
                    <span style="flex: 1;">
                        <div style="font-weight: 500; font-size: 13px;">${escapeHtml(m.name)} <span style="color: var(--text-muted); font-size: 11px;">v${escapeHtml(m.version)} · @${escapeHtml(m.author)}</span></div>
                        <div style="font-size: 11px; color: var(--text-muted);">${escapeHtml((m.description || '').slice(0, 80))}</div>
                    </span>
                    <button class="primary-btn rs-add" data-skill-id="${m.id}" style="padding: 4px 12px; font-size: 12px;">添加授权</button>
                </div>`).join('');

        container.innerHTML = `
            <div style="margin-bottom: 6px; font-size: 12px; color: var(--text-muted);">① 已授权 Skill（${granted.length}）—— 调整顺序、勾选「默认加载」、或移除</div>
            <div id="rs-granted-list" style="border: 1px solid var(--border-color); border-radius: 6px; margin-bottom: 12px; max-height: 200px; overflow-y: auto;">${grantedHtml}</div>
            <div style="margin-bottom: 6px; font-size: 12px; color: var(--text-muted);">② 可选 Skill（${available.length}）—— 点击「添加授权」纳入上方</div>
            <div id="rs-available-list" style="border: 1px solid var(--border-color); border-radius: 6px; max-height: 160px; overflow-y: auto;">${availableHtml}</div>
        `;

        const grantedBox = container.querySelector('#rs-granted-list');
        const availBox = container.querySelector('#rs-available-list');
        grantedBox.querySelectorAll('.rs-default-cb').forEach(cb => {
            cb.addEventListener('change', () => {
                const id = parseInt(cb.getAttribute('data-skill-id'));
                const item = roleSkillById(id);
                if (item) item.defaultLoaded = cb.checked;
            });
        });
        grantedBox.querySelectorAll('.rs-move-up').forEach(b => b.addEventListener('click', () => moveRoleSkill(parseInt(b.getAttribute('data-idx')), -1)));
        grantedBox.querySelectorAll('.rs-move-down').forEach(b => b.addEventListener('click', () => moveRoleSkill(parseInt(b.getAttribute('data-idx')), +1)));
        grantedBox.querySelectorAll('.rs-remove').forEach(b => b.addEventListener('click', () => {
            const id = parseInt(b.getAttribute('data-skill-id'));
            currentRoleSkills = currentRoleSkills.filter(it => it.marketSkillId !== id);
            renderRoleSkillList();
        }));
        availBox.querySelectorAll('.rs-add').forEach(b => b.addEventListener('click', () => {
            const id = parseInt(b.getAttribute('data-skill-id'));
            if (!roleSkillById(id)) {
                currentRoleSkills.push({marketSkillId: id, defaultLoaded: true});
                renderRoleSkillList();
            }
        }));
    }

    function moveRoleSkill(idx, dir) {
        const newIdx = idx + dir;
        if (newIdx < 0 || newIdx >= currentRoleSkills.length) return;
        const t = currentRoleSkills[idx];
        currentRoleSkills[idx] = currentRoleSkills[newIdx];
        currentRoleSkills[newIdx] = t;
        renderRoleSkillList();
    }

    function renderMcpCheckboxes(container, mcps, checkedSet) {
        if (!mcps || mcps.length === 0) {
            container.innerHTML = '<div style="color: var(--text-muted); font-size: 12px; padding: 8px;">无可用 MCP 服务</div>';
            return;
        }
        container.innerHTML = mcps.map(m => {
            const checked = checkedSet.has(m.name);
            const displayName = m.title || m.name;
            return `<label style="display: flex; align-items: center; gap: 8px; padding: 4px 8px; border-radius: 4px; cursor: pointer;">
                <input type="checkbox" value="${escapeHtml(m.name)}" ${checked ? 'checked' : ''}>
                <span style="flex: 1; font-size: 13px;">${escapeHtml(displayName)}</span>
            </label>`;
        }).join('');
    }

    function collectCheckedMcps(container) {
        return Array.from(container.querySelectorAll('input[type=checkbox]:checked'))
            .map(i => i.value);
    }

    // ===== 事件绑定 =====
    document.getElementById('create-role-btn').addEventListener('click', openCreate);
    document.getElementById('refresh-btn').addEventListener('click', loadRoles);
    document.getElementById('create-role-close').addEventListener('click', closeCreate);
    document.getElementById('create-role-cancel').addEventListener('click', closeCreate);
    document.getElementById('create-role-submit').addEventListener('click', submitCreate);
    document.getElementById('role-detail-close').addEventListener('click', closeDetail);
    document.getElementById('rd-save').addEventListener('click', saveDetail);
    document.getElementById('rd-delete').addEventListener('click', () => {
        if (currentDetail) deleteRole(currentDetail.code);
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadRoles);
    } else {
        loadRoles();
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
