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
    const metaContent = document.getElementById('meta-content');
    const flowContainer = document.getElementById('flow-container');
    const flowPager = document.getElementById('flow-pager');
    const loadMoreBtn = document.getElementById('load-more-btn');
    const searchEl = document.getElementById('flow-search');
    const refreshBtn = document.getElementById('refresh-btn');
    const backLink = document.getElementById('back-link');
    if (username) backLink.href = `user.html?username=${encodeURIComponent(username)}`;

    const FLOW_SIZE = 200;
    let allEvents = [];
    let currentTypes = new Set();
    let searchKeyword = '';

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function fmtTs(ts) {
        if (!ts) return '-';
        const d = new Date(ts);
        if (isNaN(d.getTime())) return '-';
        return d.toLocaleString('zh-CN', { hour12: false });
    }

    async function loadAll() {
        flowContainer.innerHTML = '<div class="loading-indicator">加载中...</div>';
        allEvents = [];
        try {
            const url = `/spring/ai/loom/admin/conversations/${encodeURIComponent(conversationId)}/flow?username=${encodeURIComponent(username || '')}&page=0&size=${FLOW_SIZE}` +
                (currentTypes.size > 0 ? `&types=${Array.from(currentTypes).join(',')}` : '');
            const r = await fetch(url, { credentials: 'include' });
            if (r.status === 401) {
                window.location.replace('/spring/ai/loom/login.html');
                return;
            }
            if (!r.ok) {
                flowContainer.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
                return;
            }
            const data = await r.json();
            const meta = data.meta || {};
            const shortId = (conversationId || '').substring(0, 8);
            titleEl.textContent = `会话：${meta.title || (shortId + '…')}`;
            metaEl.textContent = username ? `用户：${username} · ${shortId}…` : `${shortId}…`;
            renderMeta(meta);
            renderStats(data.stats);
            allEvents = data.events || [];
            render();
        } catch (e) {
            flowContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    function renderMeta(meta) {
        if (!meta) {
            metaContent.textContent = '（无元数据）';
            return;
        }
        const rows = [
            ['会话 ID', meta.conversationId],
            ['标题', meta.title || '（无）'],
            ['用户名', meta.username || '（无）'],
            ['创建时间', fmtTs(meta.createdAt)],
            ['最后活跃', fmtTs(meta.updatedAt)],
        ];
        metaContent.innerHTML = '<table class="conv-meta-table"><tbody>' +
            rows.map(([k, v]) => '<tr><th>' + escapeHtml(k) + '</th><td>' + escapeHtml(v) + '</td></tr>').join('') +
            '</tbody></table>';
    }

    function renderStats(stats) {
        if (!stats) return;
        document.getElementById('stat-total').textContent = (stats.totalTokens || 0).toLocaleString();
        document.getElementById('stat-calls').textContent = (stats.callCount || 0).toLocaleString();
        document.getElementById('stat-tools').textContent = (stats.toolCallCount || 0).toLocaleString();
        document.getElementById('stat-subtasks').textContent = (stats.subtaskCount || 0).toLocaleString();
        document.getElementById('stat-schedules').textContent = (stats.scheduleCount || 0).toLocaleString();
        document.getElementById('stat-errors').textContent = (stats.errorCount || 0).toLocaleString();
    }

    function matchesFilter(ev) {
        if (currentTypes.size > 0 && !currentTypes.has(ev.type)) return false;
        if (searchKeyword) {
            const k = searchKeyword.toLowerCase();
            const data = ev.data || {};
            const blob = JSON.stringify(data).toLowerCase();
            if (!blob.includes(k)) return false;
        }
        return true;
    }

    function render() {
        const filtered = allEvents.filter(matchesFilter);
        if (filtered.length === 0) {
            flowContainer.innerHTML = '<div class="empty-state">无匹配事件</div>';
            flowPager.style.display = 'none';
            return;
        }
        flowContainer.innerHTML = filtered.map(renderEvent).join('');
        flowPager.style.display = 'none';
    }

    function renderEvent(ev) {
        const ts = fmtTs(ev.ts);
        const t = ev.type;
        const d = ev.data || {};
        let icon = '·';
        let body = '';
        switch (t) {
            case 'USER':
                icon = '👤';
                body = '<div class="flow-content">' + escapeHtml(d.content || '') + '</div>';
                break;
            case 'ASSISTANT': {
                icon = '🤖';
                let thinking = '';
                if (d.thinking) {
                    thinking = '<details class="flow-thinking"><summary>思考</summary><pre>' + escapeHtml(d.thinking) + '</pre></details>';
                }
                let metaLine = '';
                if (d.model) metaLine += '<div class="flow-meta">model: ' + escapeHtml(d.model) + '</div>';
                if (d.promptTokens != null || d.completionTokens != null) {
                    metaLine += '<div class="flow-meta">tokens: ' + (d.promptTokens || 0) + ' + ' + (d.completionTokens || 0) + '</div>';
                }
                body = '<div class="flow-content">' + escapeHtml(d.content || '') + '</div>' + thinking + metaLine;
                break;
            }
            case 'TOOL_CALL': {
                icon = '🔧';
                let argsHtml = '';
                if (d.args) {
                    argsHtml = '<details class="flow-args"><summary>参数</summary><pre>' + escapeHtml(d.args) + '</pre></details>';
                }
                body = '<div class="flow-event-name">' + escapeHtml(d.name || '') + '</div>' +
                    '<div class="flow-meta">id: ' + escapeHtml(d.id || '') + '</div>' +
                    argsHtml;
                break;
            }
            case 'TOOL_RESULT': {
                icon = d.isError ? '❌' : '✅';
                let resultHtml = '';
                if (d.result) {
                    resultHtml = '<details class="flow-result"><summary>返回值</summary><pre>' + escapeHtml(d.result) + '</pre></details>';
                }
                let metaLine = '';
                if (d.durationMs != null) metaLine += '<div class="flow-meta">耗时: ' + d.durationMs + ' ms</div>';
                if (d.source) metaLine += '<div class="flow-meta">来源: ' + escapeHtml(d.source) + '</div>';
                body = '<div class="flow-event-name">' + escapeHtml(d.name || '') + '</div>' + metaLine + resultHtml;
                break;
            }
            case 'SUBTASK': {
                icon = '🧩';
                let subMeta = '';
                if (d.status) subMeta += '<div class="flow-meta">状态: ' + escapeHtml(d.status) + '</div>';
                if (d.durationMs != null) subMeta += '<div class="flow-meta">耗时: ' + d.durationMs + ' ms</div>';
                if (d.startedAt) subMeta += '<div class="flow-meta">开始: ' + fmtTs(d.startedAt) + '</div>';
                if (d.finishedAt) subMeta += '<div class="flow-meta">结束: ' + fmtTs(d.finishedAt) + '</div>';
                let promptHtml = '';
                if (d.prompt) promptHtml = '<details class="flow-prompt"><summary>prompt</summary><pre>' + escapeHtml(d.prompt) + '</pre></details>';
                let resultHtml = '';
                if (d.result) resultHtml = '<details class="flow-result"><summary>result</summary><pre>' + escapeHtml(d.result) + '</pre></details>';
                let errorHtml = '';
                if (d.error) errorHtml = '<details class="flow-error"><summary>error</summary><pre>' + escapeHtml(d.error) + '</pre></details>';
                body = '<div class="flow-event-name">' + escapeHtml(d.id || '') + '</div>' + subMeta + promptHtml + resultHtml + errorHtml;
                break;
            }
            case 'SCHEDULE': {
                icon = '⏰';
                let sMeta = '';
                if (d.taskName) sMeta += '<div class="flow-meta">任务: ' + escapeHtml(d.taskName) + '</div>';
                if (d.fireTime) sMeta += '<div class="flow-meta">触发: ' + fmtTs(d.fireTime) + '</div>';
                if (d.durationMs != null) sMeta += '<div class="flow-meta">耗时: ' + d.durationMs + ' ms</div>';
                if (d.success === false) sMeta += '<div class="flow-meta" style="color:var(--error-color);">失败</div>';
                let sPrompt = '';
                if (d.prompt) sPrompt = '<details class="flow-prompt"><summary>prompt</summary><pre>' + escapeHtml(d.prompt) + '</pre></details>';
                let sError = '';
                if (d.error) sError = '<details class="flow-error"><summary>error</summary><pre>' + escapeHtml(d.error) + '</pre></details>';
                body = sMeta + sPrompt + sError;
                break;
            }
        }
        return '<div class="flow-event flow-event-' + t + '">' +
            '<span class="flow-icon">' + icon + '</span>' +
            '<span class="flow-time">' + escapeHtml(ts) + '</span>' +
            '<span class="flow-type">' + t + '</span>' +
            '<div class="flow-body">' + body + '</div>' +
            '</div>';
    }

    document.querySelectorAll('.conv-filter-label input[type=checkbox]').forEach(cb => {
        cb.addEventListener('change', () => {
            const t = cb.dataset.type;
            if (cb.checked) currentTypes.add(t); else currentTypes.delete(t);
            render();
        });
        if (cb.checked) currentTypes.add(cb.dataset.type);
    });

    searchEl.addEventListener('input', () => {
        searchKeyword = searchEl.value.trim();
        render();
    });

    refreshBtn.addEventListener('click', loadAll);
    if (loadMoreBtn) loadMoreBtn.addEventListener('click', loadAll);

    loadAll();
})();
