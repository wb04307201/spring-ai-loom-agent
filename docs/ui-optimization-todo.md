# LoomAgent UI 优化待办 (2026-07-20 测试)

## 体验问题

- [x] (来源: Task 1 截图, Task 2 已定位) 侧边栏较窄,会话条目只显示气泡样式,看不到对话名称 → 根因是 `@media (max-width: 1200px)` 强制把侧栏压到 60px 并隐藏标题/名称；Task 2 补齐了重命名能力，但建议后续将桌面折叠改为用户可控，或至少在图标上提供可键盘访问的 tooltip
- [ ] (体验) 侧边栏: 1200px 以下桌面视口会强制折叠且没有展开入口（`sidebar-toggle` 仅在 768px 以下显示），用户无法恢复对话名称；建议统一为可切换的折叠/展开状态
- [ ] (来源: Task 1) CLAUDE.md 中写默认账号 `admin / admin`,但实际 V1.0__init.sql seed 的是 `wb04307201 / 123456`,登录页会拒绝 admin/admin → 文档需更新
- [x] (Task 3 修复) 消息气泡: 用户发送带附件的消息时,文件 ID 已经发给后端但用户气泡里只显示文字,看不到附件 → Task 3 commit 2b98297 修复: `renderUserMessage(text, attachments)` 接受附件数组,在气泡顶部渲染图片缩略图或文档图标条
- [ ] (来源: Task 3) 消息流: 当 SSE 连接中途断开(后端进程被杀)时,前端会显示 "发送失败:network error",但没有"重试"按钮,用户只能刷新整页;建议在失败气泡上加一个"重发"按钮
- [ ] (来源: Task 3) 消息流: 没有任何 SSE 超时控制(`API.sseTimeout = 0` 但未被使用),如果后端挂起不关闭连接、也不发数据,前端会无限等待;建议加 60s 静默超时(无 chunk 收到即视为挂起,主动断开并提示)
- [ ] (来源: Task 3) 消息流: 用户输入超长消息(实测 500KB "A" 仍被发到 LLM)既无前端字数统计、也无后端字数硬限制;建议 textarea 加字符计数 + 软上限(>50KB 弹 toast 提示),后端加硬上限(>200KB 拒绝并返回明确错误)
- [ ] (来源: Task 3) 工具调用: 当前只有 `reasoningContent` 流式渲染,tool_call 中间步骤只在 LLM 思维链里以文字形式出现,用户看不到结构化的"调用了哪个工具/参数/返回";服务端 `ChatResponseRecord` 只有 content + reasoningContent,需要扩展为含 toolCalls 列表,前端用折叠卡片展示

## 视觉/一致性

- [ ] (来源: Task 3) Markdown: 代码块(`<pre><code class="language-xxx">`)没有语法高亮(index.html 只引了 marked.umd.js,没有 highlight.js 或 prism.js);也没有复制按钮,用户只能手动选中复制;建议在 index.html 加 highlight.js CDN,并在 renderMarkdown 之后给所有 `pre` 注入复制按钮
- [ ] (来源: Task 3) Markdown: `<pre>` 背景是透明的 (CSS `pre { background: transparent }`),代码块在深色气泡上对比度差;建议给 pre 加上浅色/深色背景区分

## 性能/动效

- [ ] (来源: Task 3) 长消息自动滚动: 实测 streaming 期间滚到底部 OK,但用户向上滚动后没有"跳到底部"按钮(常见聊天 UX 模式);建议在 `#mainContent` 滚动距离底部 > 200px 时显示右下角浮动按钮
- [ ] (来源: Task 3) 流式渲染: 每收到一个 chunk 就 `answerEl.innerHTML = renderMarkdown(...)` 整段重渲染,长消息下 marked.parse 反复执行是 O(n²);建议增量写入(textContent 累加) + 节流 50ms 一次 marked.parse

## 可访问性/响应式

- [ ] (可访问性) 侧边栏: 1200px 以下折叠态只显示多个相同的“💬”图标，名称和操作按钮完全隐藏，屏幕阅读器/键盘用户难以辨别及管理会话；建议保留 `aria-label`/tooltip，并提供键盘可达的展开控件
- [ ] (来源: Task 3) 文件附件 a11y: 用户附件条 `user-attach-thumb` / `user-attach-doc` 缺少 `alt` / `aria-label` 详细说明文件用途;建议加上 `aria-label="${fileName} (附件, 大小 ${size})"`
- [x] (Task 4 修复) 知识库: 删除当前选中的知识库时只清理了 detail 面板的 `currentKbId`,没有清理 `state.selectedKnowledgeId`,下次聊天仍会把已删除 KB id 传给 RAG advisor → Task 4 commit 6851f94 修复
- [ ] (来源: Task 4) 知识库: 上传时没有任何"向量化中 / 向量化完成"进度提示,大文件下用户会以为接口卡死;`uploadWithKnowledge` 已经是同步向量化的,但 UI 只在 200 后弹成功 toast;建议加进度条或阶段提示
- [ ] (来源: Task 4) 知识库: 删除知识库时只删了 KB 行 + file_info 行 + 向量,KB 目录 `~/.loom/knowledge/{user}/{kbId}/` 留下空文件夹(`Files.deleteIfExists` 只删 file,没删父目录);建议在 `deleteAllKnowledge` 末尾 `Files.deleteIfExists(knowledgeDir)`,空目录自动清理
- [ ] (来源: Task 4) 知识库: `state.selectedKnowledgeId` 不持久化,刷新页面后丢失,K radio 回到"不使用知识库";建议存到 `localStorage` 或后端会话上下文,刷新后自动恢复
- [ ] (来源: Task 4) 知识库: 模态框左侧 KB 列表用 radio 单选,但 radio 的圆点视觉上跟旁边 × 删除按钮靠得很近(尤其在 KB 名称较长时),容易误点;建议加 `padding-right` 或把 radio 缩到 12px
