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

    const FLOW_SIZE = 500;  // V5.4 P3：默认拉 500 条覆盖长对话（之前 200 会截断 282+ event 的对话）

// V5.4 P5 B4：按"逻辑时间"分组排序 — SYSTEM → USER → tool → ASSISTANT → 后续
// 解决 DB 时间戳错位（chat_memory.timestamp 是流结束时刻，晚于真实 tool_call 时间），
// 同时支持多轮对话（USER1 → tool → AS1 → USER2 → tool → AS2）按"轮"分组。
const TYPE_PRIORITY = { SYSTEM: 0, USER: 1, TOOL_CALL: 2, TOOL_RESULT: 3, ASSISTANT: 4, SUBTASK: 5, SCHEDULE: 6 };

function logicalSort(events) {
    // 第一步：按 (typePriority, ts) 全局排序，把 USER 排到 TOOL 之前
    const byPriority = events.slice().sort((a, b) => {
        const pa = TYPE_PRIORITY[a.type] ?? 99;
        const pb = TYPE_PRIORITY[b.type] ?? 99;
        if (pa !== pb) return pa - pb;
        return new Date(a.ts).getTime() - new Date(b.ts).getTime();
    });
    // 第二步：多轮对话分组 — 每个 USER 之后到下一个 USER 之前的 events 属该轮
    const userIdx = [];
    byPriority.forEach((e, i) => { if (e.type === 'USER') userIdx.push(i); });
    if (userIdx.length <= 1) return byPriority;
    const rounds = userIdx.map((start, k) => {
        const end = k + 1 < userIdx.length ? userIdx[k + 1] : byPriority.length;
        return byPriority.slice(start, end);
    });
    return rounds.flat();
}
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
            allEvents = logicalSort(data.events || []);
            render();
            loadNav();
        } catch (e) {
            flowContainer.innerHTML = `<div class="empty-state">加载失败：${escapeHtml(e.message)}</div>`;
        }
    }

    /* V5.4：上下个会话导航 — 拉该用户所有会话，按 updatedAt 排序，
       找当前 conversationId 的上/下一个 conversationId。 */
    async function loadNav() {
        if (!username) return;
        try {
            const r = await fetch(`/spring/ai/loom/admin/users/${encodeURIComponent(username)}/conversations`, { credentials: 'include' });
            if (!r.ok) return;
            const list = await r.json();
            const sorted = list.slice().sort((a, b) => {
                const ta = a.updatedAt ? new Date(a.updatedAt).getTime() : 0;
                const tb = b.updatedAt ? new Date(b.updatedAt).getTime() : 0;
                return tb - ta;
            });
            const idx = sorted.findIndex(c => c.conversationId === conversationId);
            if (idx < 0) return;
            const prev = idx < sorted.length - 1 ? sorted[idx + 1] : null;  // 新→旧：上一个是更新的
            const next = idx > 0 ? sorted[idx - 1] : null;                  // 旧→新：下一个是更早的
            const prevLink = document.getElementById('prev-conv-link');
            const nextLink = document.getElementById('next-conv-link');
            if (prev) {
                prevLink.href = `conversation.html?id=${encodeURIComponent(prev.conversationId)}&username=${encodeURIComponent(username)}`;
                prevLink.style.display = '';
            }
            if (next) {
                nextLink.href = `conversation.html?id=${encodeURIComponent(next.conversationId)}&username=${encodeURIComponent(username)}`;
                nextLink.style.display = '';
            }
        } catch (e) { /* ignore nav load failure */ }
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
        // 增强：底部加 消息数 / 总耗时 / 平均间隔 摘要行
        const summaryHtml = '<div style="margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--border-color, #e2e8f0); display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; font-size: 12px;">' +
            '<div><span style="color: var(--text-muted);">消息数：</span><strong id="meta-msg-count">-</strong></div>' +
            '<div><span style="color: var(--text-muted);">总耗时：</span><strong id="meta-duration">-</strong></div>' +
            '<div><span style="color: var(--text-muted);">平均间隔：</span><strong id="meta-avg">-</strong></div>' +
            '</div>';
        metaContent.innerHTML = '<table class="conv-meta-table"><tbody>' +
            rows.map(([k, v]) => '<tr><th>' + escapeHtml(k) + '</th><td>' + escapeHtml(v) + '</td></tr>').join('') +
            '</tbody></table>' + summaryHtml;
    }

    function updateMetaSummary() {
        const userMsgs = allEvents.filter(e => e.type === 'USER').length;
        const asstMsgs = allEvents.filter(e => e.type === 'ASSISTANT').length;
        const total = userMsgs + asstMsgs;
        const countEl = document.getElementById('meta-msg-count');
        if (countEl) countEl.textContent = `${total} 条 (用户 ${userMsgs} / 助手 ${asstMsgs})`;
        // 总耗时 = 最后一条事件 - 第一条事件
        const durationEl = document.getElementById('meta-duration');
        const avgEl = document.getElementById('meta-avg');
        if (allEvents.length >= 2) {
            const first = new Date(allEvents[0].ts).getTime();
            const last = new Date(allEvents[allEvents.length - 1].ts).getTime();
            const durMs = last - first;
            const durStr = durMs < 60000 ? `${Math.round(durMs / 1000)}s` : `${Math.round(durMs / 60000)}m${Math.round((durMs % 60000) / 1000)}s`;
            if (durationEl) durationEl.textContent = durStr;
            if (avgEl) avgEl.textContent = total > 1 ? `${Math.round(durMs / (total - 1) / 1000)}s` : '-';
        } else {
            if (durationEl) durationEl.textContent = '-';
            if (avgEl) avgEl.textContent = '-';
        }
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
        updateMetaSummary();
        const filtered = allEvents.filter(matchesFilter);
        if (filtered.length === 0) {
            // V5.4：友好空状态 — 区分两种情况
            const hint = allEvents.length === 0
                ? '这条对话还没有任何事件 — 试发条消息看看？'
                : '当前筛选条件下没有事件 — 调整复选框或搜索关键词';
            flowContainer.innerHTML = '<div class="empty-state" style="padding: 40px 16px; color: var(--text-muted); font-size: 14px;">' +
                '<div style="font-size: 32px; margin-bottom: 12px;">📭</div>' +
                '<div>' + escapeHtml(hint) + '</div></div>';
            flowPager.style.display = 'none';
            return;
        }
        flowContainer.innerHTML = filtered.map(renderEvent).join('');
        flowContainer.classList.toggle('compact', document.body.classList.contains('compact-mode'));
        flowPager.style.display = 'none';
        // V5.4：搜索高亮 — walk 文本节点，匹配处用 <mark> 包裹
        if (searchKeyword) highlightMatches(flowContainer, searchKeyword);
    }

    function highlightMatches(root, keyword) {
        if (!keyword) return;
        const re = new RegExp(keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
        const targets = [];
        let n;
        while ((n = walker.nextNode())) {
            // 跳过 <summary>/<style>/<script> 内的文本
            const p = n.parentNode;
            if (p && p.closest && p.closest('style, script, summary')) continue;
            if (!n.nodeValue || !re.test(n.nodeValue)) continue;
            targets.push(n);
        }
        targets.forEach(text => {
            const html = text.nodeValue.replace(re, m => `<mark class="flow-search-hit">${m}</mark>`);
            const span = document.createElement('span');
            span.innerHTML = html;
            const parent = text.parentNode;
            while (span.firstChild) parent.insertBefore(span.firstChild, text);
            parent.removeChild(text);
        });
    }

    // V5.4：全展开 / 全折叠 / 紧凑模式 三个按钮
    document.getElementById('expand-all-btn').addEventListener('click', () => {
        flowContainer.querySelectorAll('details').forEach(d => d.open = true);
    });
    document.getElementById('collapse-all-btn').addEventListener('click', () => {
        flowContainer.querySelectorAll('details').forEach(d => d.open = false);
    });
    document.getElementById('compact-toggle-btn').addEventListener('click', () => {
        document.body.classList.toggle('compact-mode');
        render();  // re-render with compact class
    });
    // V5.4：全屏模式 — 隐藏 meta + stats 卡片，让 conversation 占全部高度
    document.getElementById('fullscreen-btn').addEventListener('click', () => {
        const isFull = document.body.classList.toggle('fullscreen-mode');
        const btn = document.getElementById('fullscreen-btn');
        btn.textContent = isFull ? '退出全屏' : '全屏';
        // V5.4 P5：全屏时 max-height 92vh（默认已 75vh，无需全屏也能看大部分事件）
        flowContainer.style.maxHeight = isFull ? '92vh' : '75vh';
    });

    function renderEvent(ev) {
        const ts = fmtTs(ev.ts);
        const t = ev.type;
        const d = ev.data || {};
        let icon = '·';
        let body = '';
        switch (t) {
        case 'SYSTEM': {
                // V5.4 P3：系统提示词事件 — 展示该对话实际下发的 dynamic system prompt
                icon = '⚙️';
                const len = d.length || (d.content || '').length;
                let content = d.content || '';
                let rendered = '';
                if (content && window.marked) {
                    try {
                        const raw = window.marked.parse(content, { gfm: true, breaks: true });
                        rendered = window.sanitizeHtml ? window.sanitizeHtml(raw) : raw;
                    } catch (e) {
                        rendered = escapeHtml(content);
                    }
                }
                body = '<div class="flow-meta">长度: ' + (len) + ' 字符 · 动态拼装（含工具/技能/知识库）</div>' +
                    '<details class="flow-system" open><summary>系统提示词</summary>' +
                    '<div class="flow-content flow-markdown">' + rendered + '</div>' +
                    '</details>';
                break;
            }
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
                // V5.4：用 marked 把 content 渲染成 Markdown（标题/列表/代码块都好看）
                // marked.parse 出来的 HTML 通过 sanitizeHtml 加白名单过滤（防止 XSS）
                let rendered = '';
                if (d.content && window.marked) {
                    try {
                        const raw = window.marked.parse(d.content, { gfm: true, breaks: true });
                        rendered = window.sanitizeHtml ? window.sanitizeHtml(raw) : raw;
                    } catch (e) {
                        rendered = escapeHtml(d.content);  // fallback 到 plain text
                    }
                }
                body = '<div class="flow-content flow-markdown">' + rendered + '</div>' + thinking + metaLine;
                break;
            }
            case 'TOOL_CALL': {
                icon = '🔧';
                let argsHtml = '';
                if (d.args) {
                    // V5.4：尝试 JSON 美化 args，失败回退到原始字符串
                    let prettyArgs = d.args;
                    try {
                        const obj = JSON.parse(d.args);
                        prettyArgs = JSON.stringify(obj, null, 2);
                    } catch (e) { /* 不是 JSON，原样展示 */ }
                    argsHtml = '<details class="flow-args"><summary>参数</summary><pre>' + escapeHtml(prettyArgs) + '</pre></details>';
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
