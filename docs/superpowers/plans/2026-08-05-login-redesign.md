# 灵梭登录页重设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把登录页从模板感紫色渐变重设计为「明亮清爽 · 对齐主应用」的 A+B 组合布局（主应用同款顶栏 + 居中品牌卡片 + 织线纹理 + 轻动效）。

**Architecture:** 纯前端静态改动 —— 重写 `login.css`（新 token 体系）、重写 `login.html`（顶栏 + 品牌卡片 + 内联织线 SVG）、`login.js` 仅一处按钮复位文案。视觉定稿参考 `docs/superpowers/specs/login-redesign-mockup.html`，规格见 `docs/superpowers/specs/2026-08-05-login-redesign-design.md`。

**Tech Stack:** 原生 HTML/CSS（内联 SVG），Spring Boot 静态资源（`META-INF/resources`），验证靠启动 test 应用 + Chrome 截图。

## Global Constraints

- **只允许改 3 个文件**：`login.html`、`login.css`、`login.js`（仅 `finally` 中 `'登 录'` → `'登录'` 一处文案）。**禁止**动 `index.html`、`style.css`、后端、API。
- **保留 `login.js` 依赖的 DOM id**：`login-form`、`username`、`password`、`submit-btn`、`error-msg`。
- **颜色 token 原样复用主应用值，不引入新颜色**：`--primary #6366f1`、`--primary-hover #4f46e5`、文本 `#1e293b / #64748b / #94a3b8`、边框 `#e2e8f0`、页面背景 `#f8fafc`、卡片/顶栏 `#ffffff`、错误 `#ef4444`（错误条底 `#fef2f2` 边 `#fecaca`）。
- **所有 logo 用法必须 `object-fit: cover`**（logo.png 是宽幅图，防压扁）；引用路径与主应用一致 `../../../static/logo.png`。
- **织线 SVG 内联在 login.html**，不新增资源文件。
- **文案原样**：顶栏「灵梭」+ 右「Spring AI LoomAgent」；卡片 h1「灵梭」+ p「Spring AI LoomAgent」；label「用户名」「密码」；placeholder「请输入用户名」「请输入密码」；按钮「登录」；页脚「一线一梭 · 织就智能」。
- 字体 stack：`'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif`。
- 提交信息风格跟随仓库：`feat(login): ...` / `fix(login): ...`，结尾带 `Co-Authored-By: Claude <noreply@anthropic.com>`。

---

### Task 1: 重写 login.css（新视觉体系）

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.css`（整体重写）

**Interfaces:**
- Consumes: 无（首任务）
- Produces: 新 class 契约供 Task 2 的 HTML 使用 —— `.threads`、`.app-header`/`.header-left`/`.header-title`/`.header-right`、`.login-main`、`.login-card`、`.login-header`/`.brand-logo`、`.form-group`、`.error-msg`、`.submit-btn`、`.login-footer`、`@keyframes fadeUp`

- [ ] **Step 1: 整体重写 login.css**

用以下内容**完整替换** `login.css`：

```css
* { box-sizing: border-box; margin: 0; padding: 0; }

:root {
    --primary: #6366f1;
    --primary-hover: #4f46e5;
    --text-primary: #1e293b;
    --text-secondary: #64748b;
    --text-muted: #94a3b8;
    --bg-page: #f8fafc;
    --bg-card: #ffffff;
    --border-color: #e2e8f0;
    --error: #ef4444;
}

body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
    background: var(--bg-page);
    height: 100vh;
    display: flex;
    flex-direction: column;
    color: var(--text-primary);
    overflow: hidden;
}

/* 织线纹理：内联 SVG（见 login.html），静态不流动 */
.threads {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
}

/* 与主应用 .header 同款：60px 白顶栏 */
.app-header {
    height: 60px;
    padding: 0 20px;
    background: var(--bg-card);
    border-bottom: 1px solid var(--border-color);
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    position: relative;
    z-index: 1;
}

.header-left {
    display: flex;
    align-items: center;
    gap: 12px;
}

.header-left img {
    width: 40px;
    height: 40px;
    border-radius: 10px;
    object-fit: cover;
}

.header-title {
    font-size: 20px;
    font-weight: 700;
    letter-spacing: 1px;
}

.header-right {
    font-size: 13px;
    color: var(--text-muted);
}

.login-main {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
}

.login-card {
    width: 100%;
    max-width: 400px;
    margin: 20px;
    background: var(--bg-card);
    border: 1px solid var(--border-color);
    border-radius: 16px;
    box-shadow: 0 8px 24px rgba(15, 23, 42, .06);
    padding: 36px 40px;
    animation: fadeUp .45s ease-out;
}

@keyframes fadeUp {
    from { opacity: 0; transform: translateY(16px); }
    to { opacity: 1; transform: translateY(0); }
}

.login-header {
    text-align: center;
    margin-bottom: 26px;
}

.login-header .brand-logo {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    object-fit: cover;
    box-shadow: 0 4px 14px rgba(99, 102, 241, .28);
    margin-bottom: 12px;
}

.login-header h1 {
    font-size: 21px;
    font-weight: 700;
    letter-spacing: 1px;
}

.login-header p {
    margin-top: 4px;
    font-size: 13px;
    color: var(--text-muted);
    letter-spacing: .3px;
}

.form-group {
    margin-bottom: 16px;
}

.form-group label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    font-weight: 500;
    color: var(--text-secondary);
}

.form-group input {
    width: 100%;
    padding: 11px 14px;
    font-size: 14px;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    background: #fff;
    color: var(--text-primary);
    transition: border-color .15s, box-shadow .15s;
}

.form-group input::placeholder {
    color: var(--text-muted);
}

.form-group input:focus {
    outline: none;
    border-color: var(--primary);
    box-shadow: 0 0 0 3px rgba(99, 102, 241, .12);
}

.error-msg {
    color: var(--error);
    background: #fef2f2;
    border: 1px solid #fecaca;
    padding: 10px 12px;
    border-radius: 10px;
    font-size: 13px;
    margin-bottom: 16px;
}

.submit-btn {
    width: 100%;
    margin-top: 6px;
    padding: 12px;
    border: none;
    border-radius: 10px;
    background: var(--primary);
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    transition: background .15s, transform .15s, box-shadow .15s;
}

.submit-btn:hover:not(:disabled) {
    background: var(--primary-hover);
    transform: translateY(-1px);
    box-shadow: 0 6px 16px rgba(99, 102, 241, .25);
}

.submit-btn:disabled {
    opacity: .6;
    cursor: not-allowed;
}

.login-footer {
    margin-top: 26px;
    padding-top: 14px;
    border-top: 1px solid var(--border-color);
    text-align: center;
    font-size: 12px;
    color: var(--text-muted);
}
```

- [ ] **Step 2: 功能回归（中间态）**

此时 HTML 仍是旧结构，页面外观为中间态（卡片靠左、无顶栏）属预期；**功能必须正常**。

Run: 确认服务在跑（本会话通常已启动）：`curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/spring/ai/loom/login.html`，期望 `200`。若未跑：后台 `mvn spring-boot:run -pl spring-ai-loom-agent-test -Dgpg.skip=true` 并轮询至 200。
Chrome 打开登录页，输错密码提交 → 仍能看到浅红错误条（新 `.error-msg` 样式生效）；输 `wb04307201` / `123456` → 跳转 `index.html`。

- [ ] **Step 3: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.css
git commit -m "feat(login): 重写 login.css 为明亮清爽视觉体系

复用主应用设计 token（indigo 主色/slate 文本/白卡），去紫色渐变；
新增顶栏/织线/品牌卡片/圆角体系样式，卡片入场 fadeUp 轻动效。"
```

---

### Task 2: 重写 login.html + login.js 文案

**Files:**
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.html`（整体重写）
- Modify: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.js:63`（`'登 录'` → `'登录'`）

**Interfaces:**
- Consumes: Task 1 的 class 契约（`.threads`/`.app-header`/...）
- Produces: 定稿页面；DOM id 保持不变供 `login.js` 消费

- [ ] **Step 1: 整体重写 login.html**

用以下内容**完整替换** `login.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <title>登录 - 灵梭</title>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="login.css">
</head>
<body>
<svg class="threads" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <g fill="none" stroke-linecap="round">
        <path d="M-60 640 C 260 560, 520 720, 860 640 S 1300 540, 1500 620" stroke="#6366f1" stroke-width="1.5" opacity=".10"/>
        <path d="M-60 668 C 260 588, 520 748, 860 668 S 1300 568, 1500 648" stroke="#6366f1" stroke-width="1.5" opacity=".08"/>
        <path d="M-60 696 C 260 616, 520 776, 860 696 S 1300 596, 1500 676" stroke="#818cf8" stroke-width="1.5" opacity=".07"/>
        <path d="M-60 724 C 260 644, 520 804, 860 724 S 1300 624, 1500 704" stroke="#a78bfa" stroke-width="1.5" opacity=".06"/>
        <path d="M-60 752 C 260 672, 520 832, 860 752 S 1300 652, 1500 732" stroke="#a855f7" stroke-width="1.5" opacity=".05"/>
        <path d="M900 -40 C 1000 160, 1200 120, 1500 220" stroke="#6366f1" stroke-width="1.5" opacity=".07"/>
        <path d="M960 -60 C 1060 140, 1260 100, 1560 200" stroke="#a78bfa" stroke-width="1.5" opacity=".06"/>
        <path d="M1020 -80 C 1120 120, 1320 80, 1620 180" stroke="#a855f7" stroke-width="1.5" opacity=".05"/>
    </g>
</svg>
<header class="app-header">
    <div class="header-left">
        <img src="../../../static/logo.png" alt="灵梭 logo">
        <span class="header-title">灵梭</span>
    </div>
    <div class="header-right">Spring AI LoomAgent</div>
</header>
<main class="login-main">
    <div class="login-card">
        <div class="login-header">
            <img class="brand-logo" src="../../../static/logo.png" alt="灵梭 logo">
            <h1>灵梭</h1>
            <p>Spring AI LoomAgent</p>
        </div>
        <form id="login-form" autocomplete="off">
            <div class="form-group">
                <label for="username">用户名</label>
                <input type="text" id="username" name="username" placeholder="请输入用户名" required autofocus>
            </div>
            <div class="form-group">
                <label for="password">密码</label>
                <input type="password" id="password" name="password" placeholder="请输入密码" required>
            </div>
            <div id="error-msg" class="error-msg" style="display: none;"></div>
            <button type="submit" id="submit-btn" class="submit-btn">登录</button>
        </form>
        <div class="login-footer">一线一梭 · 织就智能</div>
    </div>
</main>
<script src="login.js"></script>
</body>
</html>
```

- [ ] **Step 2: login.js 按钮复位文案去空格**

把 `login.js` `finally` 块中的：

```js
            submitBtn.textContent = '登 录';
```

改为：

```js
            submitBtn.textContent = '登录';
```

- [ ] **Step 3: 快速目视检查**

Chrome 刷新登录页（硬刷新 Ctrl+Shift+R 防 CSS 缓存）：应见白顶栏（logo + 灵梭）、居中白卡（圆形 logo 不压扁、织线纹理在背景、页脚 slogan、纯色「登录」按钮）。

- [ ] **Step 4: Commit**

```bash
git add spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.html spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.js
git commit -m "feat(login): 登录页结构重写为 A+B 组合布局

主应用同款顶栏 + 居中品牌卡片；内联织线 SVG 纹理；
真实 logo（object-fit cover 裁剪圆形徽章）；页脚 slogan；
login.js 仅按钮复位文案去空格。"
```

---

### Task 3: 端到端视觉验证（对照定稿 mockup）

**Files:**
- Read（对照）: `docs/superpowers/specs/login-redesign-mockup.html`
- 可能 Modify: `login.css` / `login.html`（仅当发现偏差需修正时）

**Interfaces:**
- Consumes: Task 1+2 的定稿页面
- Produces: 验证结论；若有修正则一个 fix commit

- [ ] **Step 1: 桌面截图对照 mockup**

Chrome（chrome-devtools MCP）打开 `http://localhost:8080/spring/ai/loom/login.html` 截图保存；再打开 `docs/superpowers/specs/login-redesign-mockup.html`（file://）截图。Read 两张图对照，逐项核对：
1. 顶栏：60px 白底、左 logo+「灵梭」、右「Spring AI LoomAgent」；
2. 卡片：居中 400px、16px 圆角、软阴影；圆形 logo 无压扁变形；
3. 背景：`#f8fafc` + 底部/右上低对比织线；
4. 按钮纯色 `#6366f1`、文案「登录」；页脚「一线一梭 · 织就智能」。

- [ ] **Step 2: 错误态**

输 `wb04307201` + 错误密码提交 → 错误条为浅红底圆角框、位于密码框与按钮之间；按钮提交后复位文案为「登录」（无空格）。截图确认。

- [ ] **Step 3: 窄窗口**

`resize_page` 至 480×800 截图：卡片随宽度收缩、两侧留 20px、顶栏不溢出不换行错乱。

- [ ] **Step 4: 登录跳转回归**

输 `wb04307201` / `123456` 提交 → 跳转 `index.html` 且主应用顶栏正常。再回开 `login.html` → 因 session 有效应自动跳回 `index.html`（`isAutoLogin` 逻辑未受影响）。

- [ ] **Step 5: 偏差修正（如有）并 commit**

若 Step 1-4 发现与 mockup 的偏差，修正 `login.css`/`login.html` 后：

```bash
git commit -m "fix(login): 登录页视觉偏差修正"
```

若无偏差，此步跳过（不产生 commit）。
