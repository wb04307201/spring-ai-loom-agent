# 灵梭登录页重设计 — 设计规格

- **日期**: 2026-08-05
- **范围**: `spring-ai-loom-agent/src/main/resources/META-INF/resources/spring/ai/loom/login.html` + `login.css`
- **视觉参考**: 同目录 `login-redesign-mockup.html`（定稿 A+B 组合高保真稿，浏览器打开即可预览）

## 背景

现登录页（紫色渐变背景 + 居中白卡）的问题诊断：

1. **logo 是占位符**：卡片顶部是空紫色圆角方块，未使用真实品牌 logo（`META-INF/resources/static/logo.png`，主应用 `index.html` 已在用）。
2. **模板感背景**：`#667eea → #764ba2` 是泛滥的默认紫色渐变，无辨识度。
3. **登录前后割裂**：登录前重紫渐变，登录后是白 + slate 灰的干净 UI，品牌感断裂。
4. **品牌故事缺失**：「灵梭」的梭/织线意象与 slogan 在页面上完全没有体现。
5. **细节债**：`.login-footer` 样式定义了但 HTML 未用；按钮文案 "登 录" 带空格；宽 logo 图若直接塞进方块会压扁变形。

## 方向决策（brainstorming 结论）

| 决策点 | 选择 |
|---|---|
| 视觉方向 | 明亮清爽 · 对齐主应用 |
| 装饰与动效 | 织线纹理 + 轻动效（静态纹理 + 入场动画，不做流动动画） |
| 布局方案 | A+B 组合：主应用同款顶栏 + 居中品牌卡片 |

## 设计细节

### 布局与品牌

- **顶栏**：与主应用 `.header` 同款 — 60px 高、白底、下边框 `--border`；左侧 40px logo（`border-radius: 10px`）+ 「灵梭」20px/700；右侧 「Spring AI LoomAgent」13px `--text-3`。
- **页面背景**：`--bg: #f8fafc`，全屏固定内联 SVG 织线纹理 — 底部一束 5 条 + 右上角一束 3 条贝塞尔曲线，stroke 1.5，颜色 `#6366f1/#818cf8/#a78bfa/#a855f7`，opacity 0.05–0.10（低对比不干扰阅读）。SVG 直接内联在 `login.html`，不新增资源文件。
- **卡片**：居中，`max-width: 400px`，四周 20px margin；白底、`border-radius: 16px`、1px `--border` 边框、软阴影 `0 8px 24px rgba(15,23,42,.06)`；padding 36px 40px。
- **卡片内容顺序**：56px 圆形 logo → 「灵梭」21px/700 → 「Spring AI LoomAgent」13px `--text-3` → 表单（用户名/密码/登录按钮）→ 分隔线 → 页脚 slogan「一线一梭 · 织就智能」12px `--text-3`（启用现 CSS 中已定义但未用的 footer 槽位）。

### Logo 变形修复

`logo.png` 是宽幅图（圆形徽章居中）。所有 logo 用法统一 `object-fit: cover`（或等效 background 裁剪）取中心圆形徽章，杜绝压扁：

- 顶栏：40px 圆角方块 + `object-fit: cover`
- 卡片：56px 圆形 + `object-fit: cover` + 光晕阴影 `0 4px 14px rgba(99,102,241,.28)`
- 引用路径与主应用一致：`../../../static/logo.png`

### 视觉 token（全部复用 `style.css` 现值，不引入新颜色）

| token | 值 | 用途 |
|---|---|---|
| `--primary` | `#6366f1` | 按钮、focus ring、织线 |
| `--primary-hover` | `#4f46e5` | 按钮 hover |
| `--text-1/2/3` | `#1e293b / #64748b / #94a3b8` | 标题 / label / 辅助文字 |
| `--border` | `#e2e8f0` | 边框、分隔线 |
| `--bg` / `--card` | `#f8fafc / #ffffff` | 页面背景 / 卡片顶栏 |

- **按钮**：纯色 `--primary`（去掉现紫渐变），`border-radius: 10px`，600 字重，文案「登录」（去空格）。
- **输入框**：白底、10px 圆角、1px `--border`；focus 时 `--primary` 边框 + `0 0 0 3px rgba(99,102,241,.12)` ring。
- **字体**：与主应用同 stack — `'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif`。

### 动效（克制）

- 卡片入场：`fadeUp .45s ease-out`（opacity 0→1，translateY 16px→0）。
- 按钮 hover：背景转 `--primary-hover` + `translateY(-1px)` + 轻阴影 `0 6px 16px rgba(99,102,241,.25)`。
- 织线纹理**静态**，不做流动动画。

### 状态

- **错误态**：`login.js` 逻辑不变；错误条样式与新圆角体系一致（浅红底 `#fef2f2` + `#fecaca` 边框 + 10px 圆角 + 13px 字）。
- **加载态**：沿用 `login.js` 现有「登录中...」+ disabled 样式（opacity .6）。
- **已登录跳转**：`login.js` 现有 `isAutoLogin` 逻辑不变。

### 响应式

- 卡片 `max-width: 400px` + 20px margin，窄屏自然收缩；顶栏结构简单天然响应式安全；不新增媒体查询断点。

## 改动范围（刻意收窄）

**只改 2 个文件**：

1. `login.html` — 结构重写：加顶栏、换真实 logo（两处）、加 footer slogan、内联织线 SVG；表单 id/结构保持与 `login.js` 兼容（`login-form`/`username`/`password`/`submit-btn`/`error-msg`）。
2. `login.css` — 整体重写：新 token、顶栏/卡片/输入/按钮/错误条/footer 样式、fadeUp 动画；移除紫色渐变与空 logo 方块样式。

**几乎不动**：`login.js` 仅改一处纯文案 —— `finally` 里按钮复位文本 `'登 录'` → `'登录'`（与去空格文案一致；登录/自动登录/错误处理逻辑零改动）。

**不动**：`index.html`、`style.css`、后端、API、其他页面。

## 验证计划

1. `mvn spring-boot:run -pl spring-ai-loom-agent-test` 启动服务。
2. Chrome 打开登录页截图，与 `login-redesign-mockup.html` 定稿稿目视对比（布局/配色/logo 裁剪）。
3. 错误态：输入错误密码，确认错误条样式与位置。
4. 窄窗口（约 480px 宽）截图，确认卡片收缩正常、顶栏不溢出。
5. 登录成功跳转 `index.html` 回归确认。
