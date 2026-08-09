(function () {
 'use strict';

 const tableContainer = document.getElementById('mcp-table-container');
 const API = {
 list: '/spring/ai/loom/admin/mcp-system',
 update: (name) => `/spring/ai/loom/admin/mcps/${encodeURIComponent(name)}`,
 // 工具列表 / 更新改用 query string + 独立路径，避免 mcp 名含 @ / / 等特殊字符触发 Tomcat 400
 tools: (name) => `/spring/ai/loom/admin/mcps/tools?name=${encodeURIComponent(name)}`,
 updateTool: (id) => `/spring/ai/loom/admin/mcp-tools/${id}`,
 };

 let currentEdit = null; // {name, origTitle, origDesc}

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
 currentEdit = {name, origTitle: '', origDesc: ''};
 document.getElementById('em-name').value = name;
 document.getElementById('em-title').value = '';
 document.getElementById('em-desc').value = '';
 document.getElementById('em-error').style.display = 'none';
 document.getElementById('em-tools').innerHTML = '加载中...';
 document.getElementById('edit-mcp-modal').style.display = 'flex';
 // 加载未完成前禁用保存按钮，避免在 origTitle/origDesc 仍为 '' 时点击保存把字段当"清空"发出
 // (BUG-12-MCP-SERVER-PUT-CLEAR-RACE)
 const saveBtn = document.getElementById('em-save');
 if (saveBtn) saveBtn.disabled = true;

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
 // 记录原始值，用于 saveEdit 判断"是否被用户改过"：
 // 未改过的字段发 null（COALESCE 保留旧值），改过的字段发新值（null 表示"清空"）
 // 之前固定发 {title, description} 会让 COALESCE 把所有 null 都当成"无修改"，导致清空失效
 // (BUG-12-MCP-SERVER-PUT-CLEAR)
 currentEdit.origTitle = m.title || '';
 currentEdit.origDesc = m.description || '';
 }
 renderTools(tools || []);
 } catch (e) {
 document.getElementById('em-tools').innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
 } finally {
 // 加载完成（或失败）后启用保存按钮，让用户可以重试或基于加载结果显示保存
 // (BUG-12-MCP-SERVER-PUT-CLEAR-RACE)
 if (saveBtn) saveBtn.disabled = false;
 }
 }

 function renderTools(tools) {
 if (!tools || tools.length === 0) {
 document.getElementById('em-tools').innerHTML = '<div style="color: var(--text-muted); font-size: 13px; padding: 8px;">此服务无工具</div>';
 return;
 }
 document.getElementById('em-tools').innerHTML = tools.map(t => {
 // id=0 表示 DB 还没维护记录（仍展示 SDK 默认描述）
 const unmaintained = !t.id;
 const statusTag = unmaintained
 ? '<span style="font-size: 11px; color: var(--text-muted); margin-left: 6px;">(未维护，显示 SDK 默认)</span>'
 : '';
 const deleteBtn = unmaintained ? '' :
 `<button class="delete-btn delete-tool-btn btn-sm" data-tool-id="${t.id}">删除维护</button>`;
 return `<div style="padding: 12px; border: 1px solid var(--border-color); border-radius: 6px; margin-bottom: 8px;">
 <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px;">
 <span style="font-weight: 600; font-size: 13px; color: var(--primary-color);">${escapeHtml(t.name)}</span>
 ${statusTag}
 <span style="margin-left: auto;">${deleteBtn}</span>
 </div>
 <textarea data-tool-id="${t.id}" data-mcp-name="${escapeHtml(t.mcpName)}" data-tool-name="${escapeHtml(t.name)}" data-original="${escapeHtml(t.description || '')}" class="form-input" rows="2"
 style="font-size: 12px;">${escapeHtml(t.description || '')}</textarea>
 </div>`;
 }).join('');

 // 绑定删除按钮
 document.querySelectorAll('.delete-tool-btn').forEach(btn => {
 btn.addEventListener('click', async () => {
 const id = btn.getAttribute('data-tool-id');
 const ok = await confirmDialog({
 title: '删除工具维护',
 message: '确认删除该工具的自定义描述？删除后回退到 SDK 默认描述。',
 okText: '删除',
 });
 if (!ok) return;
 try {
 const r = await fetch(API.updateTool(id), {method: 'DELETE', credentials: 'include'});
 if (!r.ok) {
 showToast('删除失败：HTTP ' + r.status, 'error');
 return;
 }
 showToast('已删除', 'success');
 // 重新加载该 mcp 的工具列表
 if (currentEdit) await openEdit(currentEdit.name);
 } catch (e) {
 showToast('网络错误：' + e.message, 'error');
 }
 });
 });
 }

 function closeEdit() {
 document.getElementById('edit-mcp-modal').style.display = 'none';
 }

 async function saveEdit() {
 // 保存按钮在 openEdit 加载完成前被禁用；如意外在加载阶段被触发则直接返回，避免发出空值触发后端清空逻辑
 // (BUG-12-MCP-SERVER-PUT-CLEAR-RACE)
 const saveBtn = document.getElementById('em-save');
 if (saveBtn && saveBtn.disabled) return;
 const name = currentEdit.name;
 const title = document.getElementById('em-title').value.trim();
 const desc = document.getElementById('em-desc').value.trim();
 // 后端语义：null/空串 = 清空该字段（= 用 SDK 默认）
 // 未改的字段必须显式发"原值"（不能发 null，否则会被清掉）
 // (BUG-12-MCP-SERVER-PUT-CLEAR)
 const titleChanged = title !== (currentEdit.origTitle || '');
 const descChanged = desc !== (currentEdit.origDesc || '');
 const body = {
 title: titleChanged ? title : (currentEdit.origTitle || ''),
 description: descChanged ? desc : (currentEdit.origDesc || ''),
 };
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
 const id = el.getAttribute('data-tool-id') || '0';
 const mcpName = el.getAttribute('data-mcp-name') || name;
 const toolName = el.getAttribute('data-tool-name') || '';
 const original = el.getAttribute('data-original') || '';
 const current = el.value;
 if (current !== original) {
 toolUpdates.push(
 fetch(API.updateTool(id), {
 method: 'PUT', credentials: 'include',
 headers: {'Content-Type': 'application/json'},
 body: JSON.stringify({
 mcpName: mcpName,
 name: toolName,
 description: current || null,
 }),
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
