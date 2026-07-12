(function () {
    'use strict';

    const form = document.getElementById('login-form');
    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const submitBtn = document.getElementById('submit-btn');
    const errorMsg = document.getElementById('error-msg');

    // 1. 页面加载时检查是否已登录（session 有效就直接跳主页）
    fetch('/spring/ai/loom/user/isAutoLogin', {method: 'POST', credentials: 'include'})
        .then(r => r.json())
        .then(loggedIn => {
            if (loggedIn === true) {
                window.location.replace('/spring/ai/loom/index.html');
            }
        })
        .catch(() => { /* ignore */ });

    function showError(text) {
        errorMsg.textContent = text;
        errorMsg.style.display = 'block';
    }

    function hideError() {
        errorMsg.style.display = 'none';
    }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideError();
        const username = usernameInput.value.trim();
        const password = passwordInput.value;
        if (!username || !password) {
            showError('请输入用户名和密码');
            return;
        }
        submitBtn.disabled = true;
        submitBtn.textContent = '登录中...';
        try {
            const resp = await fetch('/spring/ai/loom/user/login', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'include',
                body: JSON.stringify({username, password}),
            });
            if (resp.ok) {
                window.location.replace('/spring/ai/loom/index.html');
                return;
            }
            // 401 = 凭据错；其他 = 异常
            const text = await resp.text();
            let msg = '登录失败';
            try {
                const j = JSON.parse(text);
                if (j.message) msg = j.message;
            } catch (_) { /* not JSON */ }
            showError(msg);
        } catch (err) {
            showError('网络错误：' + (err.message || err));
        } finally {
            submitBtn.disabled = false;
            submitBtn.textContent = '登 录';
        }
    });
})();
