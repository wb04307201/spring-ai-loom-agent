(function () {
    'use strict';

    const yearInput = document.getElementById('year-input');
    const monthInput = document.getElementById('month-input');
    const barChart = document.getElementById('bar-chart');
    const statsTable = document.getElementById('stats-table');
    const monthLabel = document.getElementById('month-label');

    const now = new Date();
    yearInput.value = now.getFullYear();
    monthInput.value = now.getMonth() + 1;

    function escapeHtml(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    async function load() {
        const year = parseInt(yearInput.value);
        const month = parseInt(monthInput.value);
        if (!year || !month || month < 1 || month > 12) {
            alert('请输入有效的年月');
            return;
        }
        monthLabel.textContent = `${year}-${String(month).padStart(2, '0')} 月用量`;
        statsTable.innerHTML = '<div class="loading-indicator">加载中...</div>';
        barChart.innerHTML = '<div class="loading-indicator">加载中...</div>';
        try {
            const r = await fetch(`/spring/ai/loom/admin/stats/tokens/monthly?year=${year}&month=${month}`, {
                credentials: 'include',
            });
            if (r.status === 401) {
                window.location.replace('/spring/ai/loom/login.html');
                return;
            }
            if (!r.ok) {
                statsTable.innerHTML = `<div class="empty-state">加载失败：HTTP ${r.status}</div>`;
                barChart.innerHTML = '<div class="empty-state">-</div>';
                return;
            }
            const list = await r.json();
            renderBarChart(list);
            renderTable(list, year, month);
        } catch (e) {
            statsTable.innerHTML = `<div class="empty-state">加载失败：${e.message}</div>`;
            barChart.innerHTML = '<div class="empty-state">-</div>';
        }
    }

    function renderBarChart(list) {
        if (!list || list.length === 0) {
            barChart.innerHTML = '<div class="empty-state">本月无用量</div>';
            return;
        }
        const max = Math.max(1, ...list.map(r => r.totalTokens));
        barChart.innerHTML = list.map(r => {
            const pct = (r.totalTokens / max * 100).toFixed(1);
            return `<div class="bar-row">
                <div class="bar-label">${escapeHtml(r.username)}</div>
                <div class="bar-track"><div class="bar-fill" style="width: ${pct}%"></div></div>
                <div class="bar-value">${r.totalTokens.toLocaleString()}</div>
            </div>`;
        }).join('');
    }

    function renderTable(list, year, month) {
        if (!list || list.length === 0) {
            statsTable.innerHTML = `<div class="empty-state">${year}-${month} 无用量记录</div>`;
            return;
        }
        const totalAll = list.reduce((s, r) => s + r.totalTokens, 0);
        const rows = list.map(r => {
            const pct = totalAll > 0 ? (r.totalTokens / totalAll * 100).toFixed(1) : '0.0';
            return `<tr>
                <td><a class="user-link" href="user.html?username=${encodeURIComponent(r.username)}">${escapeHtml(r.username)}</a></td>
                <td>${r.callCount.toLocaleString()}</td>
                <td>${r.promptTokens.toLocaleString()}</td>
                <td>${r.completionTokens.toLocaleString()}</td>
                <td><strong>${r.totalTokens.toLocaleString()}</strong></td>
                <td>${pct}%</td>
            </tr>`;
        }).join('');
        statsTable.innerHTML = `
            <table class="user-table">
                <thead>
                    <tr><th>用户</th><th>调用次数</th><th>输入 Token</th><th>输出 Token</th><th>总 Token</th><th>占比</th></tr>
                </thead>
                <tbody>${rows}</tbody>
                <tfoot>
                    <tr style="font-weight: 600; background: var(--bg-secondary);">
                        <td>合计</td>
                        <td>${list.reduce((s, r) => s + r.callCount, 0).toLocaleString()}</td>
                        <td>${list.reduce((s, r) => s + r.promptTokens, 0).toLocaleString()}</td>
                        <td>${list.reduce((s, r) => s + r.completionTokens, 0).toLocaleString()}</td>
                        <td>${totalAll.toLocaleString()}</td>
                        <td>100%</td>
                    </tr>
                </tfoot>
            </table>`;
    }

    document.getElementById('reload-btn').addEventListener('click', load);
    document.getElementById('refresh-btn').addEventListener('click', load);

    // 顶部右侧渲染当前用户名（统一 header 风格）
    fetch('/spring/ai/loom/user/currentUser', {method: 'POST', credentials: 'include'})
        .then(r => r.ok ? r.json() : null)
        .then(me => {
            if (me) {
                const el = document.getElementById('admin-username');
                if (el) el.textContent = `${me.nickname || me.username}（${me.type === 'ADMIN' ? '管理员' : '用户'}）`;
            }
        }).catch(() => {});

    load();
})();
