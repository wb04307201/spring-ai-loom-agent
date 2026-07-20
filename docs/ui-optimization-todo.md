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
- [ ] (Task 5 已修复) MCP: 模态框 checkbox 切换只更新内存 `state.selectedMcps`,`mcp.loadList()` 每次重置为 `defaultSelected` 默认值,刷新页面后用户的选择全部丢失 → Task 5 commit 6a964ac 修复: 引入 `STORAGE_KEY='loom.mcp.selectedNames'`,`toggleSelect` 后立即持久化,`loadList()` 优先读 localStorage,并过滤掉已删除的服务
- [ ] (来源: Task 5) MCP: 服务列表 5 个 MCP 服务(`/mcps` 返回值) `defaultSelected` 全部为 `true`(没有任何可取消的默认项),造成"全部勾选 vs 默认勾选"无差别;建议至少 1-2 个默认关(如 `memory` / `sequential-thinking` 这类高级工具),让用户首次打开能感受到"勾选 vs 取消"的实际效果
- [ ] (来源: Task 5) MCP: 详情面板的"包含工具"列表只展示工具名 + 描述,但工具是否被勾选(受 MCP 服务级 checkbox 控制,所有工具都被一起勾/取消)没有可视化指示;如果未来支持工具级粒度勾选,需要单独渲染每个工具的 checkbox;目前是服务级粒度,UI 文案可以加一行提示"该服务下所有工具会作为一个整体被启用/禁用"
- [ ] (来源: Task 6) 技能库: 删除 `skills.send()` (app.js:2913) 重复实现 — apply/copyToTextarea 已共享 `_buildPrompt` + `_savePersisted`,send() 是死代码 (Task 6 实现者 concerns 提及)
- [ ] (来源: Task 6) 技能库: 模态框的 `currentTab` 跨 close/reopen 保持，用户上次停在「提交」Tab，再次打开就停在「提交」——但「提交」Tab 内容是一次性的(选 skill 后才有提交表单)，容易让人摸不着头脑；建议每次 `openModal()` 重置 `currentTab = 'mine'`
- [ ] (来源: Task 6) 技能库: 关闭模态框时 `state.selectedSkill` 未清空、`#skill-detail-title` 未重置；下次进入若尚未渲染 detail 会保留上次的 skill 名作为标题；建议在 `closeModal()` 里 `document.getElementById('skill-detail-title').textContent = '技能详情'; state.selectedSkill = null;`
- [ ] (来源: Task 6) 技能库: 提交 `tmp-test-skill` 后,「提交」Tab 列表里仍能看到它(因为它是 USER_CREATED source,而 submit tab 列的是 USER_CREATED 全部)——已提交到市场等审批的 skill 仍可被重复提交多次；建议提交成功后从「提交」Tab 过滤掉已 PENDING/APPROVED/REJECTED 的同名 skill，或在每个 skill 旁加一个"PENDING"小标识

## Task 7 (文件管理 / file-modal)

- [ ] (来源: Task 7) 文件模态框: 仅暴露 预览/下载 两个按钮，**没有"上传"入口也没有"删除"按钮**；后端 `loomAgentFileRouter` 也只有 GET (tree / by-path/view / by-path/download / {id}/download) 与 POST /file/upload，没有 DELETE。Task 7 期望的"上传 3 个文件 + 删除 + 二次确认"流程在文件模态框里**根本无 UI 支持**；当前唯一上传入口是聊天输入框旁边的"上传图片/文档"按钮（`imageUpload` 单文件），不展示多选、也不显示在文件模态框中。建议：① 在 `file-modal-overlay` 头部加 `<input type="file" multiple>` + "上传" 按钮，复用 `api.uploadFile()`；② 在每个 `.tree-file` 行添加"删除"按钮（弹二次 `confirm()`），新增 `DELETE /spring/ai/loom/file/{id}` 路由，按 `UserContextHolder.getCurrentUser()` 校验所有权后再删除磁盘文件 + file_info 行
- [ ] (来源: Task 7) 文件模态框: 目录树里没有空状态以外的"无文件"提示；当前代码 `if (!node.children || node.children.length === 0)` 才显示「目录为空」，但如果子树有内容而顶层为空，子目录（如 `admin-task/`）会被收起且无展开提示，用户不知道里面有没有东西。建议在 `<summary>` 后接 `(${childCount})` 计数，并默认展开有文件的目录（去掉 `details` 默认折叠属性）
- [ ] (来源: Task 7) 文件模态框: 打开时未显示当前用户名/根路径；管理员和普通用户共享同一份 UI，但磁盘根 `${user.home}/.loom/file/{username}/` 是隔离的。在 modal 标题下方加一行小字 `当前根目录: ~/.loom/file/{username}/`，让用户（特别是管理员）看到自己的隔离边界；这同时是 RBAC 透明化提示
- [ ] (来源: Task 7) 跨用户文件读取返回 500 而不是 404：`LoomAgentFileStorageImpl.findById` 已经 throw `LoomAgentRuntimeException(404, "文件不存在")`，但 wopi / file-view 库不识别这个 status code，最终渲染成 Spring 默认 500 响应体 `{"timestamp":..,"status":500,"error":"Internal Server Error"}`。commit 061b1d9 注释说"Refuse with 404 (not 500)"，但实际行为仍是 500——不是内容泄漏（已 verified：body 不含文件字节），但与 fix message 的承诺不符；建议在 `LoomAgentRuntimeException` 加 `@ResponseStatus(HttpStatus.NOT_FOUND)` 或在 loomAgentConfiguration 内补一个 `@ExceptionHandler`，把 `(404, ...)` 真正映射到 404
- [ ] (来源: Task 7) 文件上传（`POST /file/upload`）无 MIME / 大小 / 后缀校验：当前 `IUpload.upload(is, name, mime)` 把任意字节写入 `${fileBasePath}/{username}/` 并注册 file_info，没有任何上限；用户误传 1GB 文件或 `.exe` 不会拒绝。建议加 `application.yml` 开关 `spring.ai.loom.agent.file.max-size` + 后缀黑/白名单；这是 RBAC 之外的资源耗尽防护

## Task 8 (子任务 modal — operations console 设计)

- [ ] (体验) 子任务 modal **没有"取消全部" toolbar 按钮**：brief Step 5 期望一键停止当前对话所有运行中的子任务，但目前 toolbar 只有「运行中 N / 已完成 N / 失败 N / 搜 / 过滤 / + 新建 / 收起」，每行要单独点 ■ 取消。schedule 模态框同理 — `schedulePanel.cancelAll()` 方法 (app.js ~2243-2256) 和后端 `cancelAllSchedulesByConversation` 路由都已在仓库内但 UI 未暴露按钮（`schedulePanel._toolbarHTML` 在 app.js ~2033-2045 不渲染 `data-cancel-all`）；建议两端都加上：① 子任务 toolbar 加 `[data-cancel-all]` 按钮，调用新接口 `POST /spring/ai/loom/subtask/by-conversation/{id}/cancel-all`；**⚠ RBAC：禁止直接复用 `SubTaskRegistry.killAllByConversation(conversationId)` —— 该方法按 conversationId 一把清，不做 owner 检查，对外暴露相当于 BUG-RBAC-SUBTASK-KILL 同类漏洞（任何已登录用户可借此取消任意 conversation 的全部子任务）。新接口/新 helper 必须显式接 `(username, conversationId)` 双参数、并校验 `username` 拥有该 conversation（参考 `SubTaskRegistry.kill(id, username)` 在 BUG-RBAC-SUBTASK-KILL commit abf976d 之后的安全写法）；**② schedule modal 同步暴露已有 `schedulePanel.cancelAll(convId)` 按钮（同样要 username-scoped）；③ 当 `live.length === 0` 时按钮 disabled；④ UI 文案「全部停止 (N)」
- [ ] (体验) 子任务 modal empty state 的 composer (`_focusChatWithStub`) 不会真的建子任务，只是把 stub 文本塞回主聊天让 LLM 调 `start_sub_task`；用户期望是 "我写一段 prompt 按回车 → 子任务就启动了"，结果却要先要点发送、再等 LLM 决定是否调用工具。建议：① 在空状态 composer 上加一个"⚠ 直达模式(高级)"开关，开启后用新的 `POST /subtask/start?prompt=...` 直达 endpoint（绕过主对话 LLM）；② 或者把空状态 composer 替换为「请在主对话里对 AI 说"开一个子任务..."」的纯说明
- [ ] (视觉一致性) 子任务 modal 颜色方案与 schedule modal **几乎完全一致**（同样 toolbar、同 panel-body、同 composer），但 schedule modal 用 `⏲ ✓ —` 形状区分状态，子任务用 `RUNNING / DONE / FAILED / CANCELLED` 文字+ 颜色 stripe；如果用户并行打开两个 modal，会发现 toolbar 几乎相同但 stripe 配色不一致（两个 modal 的 `_toolbarHTML` 都用同一组 `console-pill.running/.done/.failed` shared status classes，色板是 cyan / green / red —— 子任务额外多一个 cancelled = amber，见 style.css `--ops-running #22D3EE` / `--ops-done #34D399` / `--ops-failed #FB7185` / `--ops-cancel #FBBF24`，schedule 没有 cancelled stripe 也没有同色系映射）。建议：两个 modal 的状态色板统一到 `data-status` attribute + CSS 变量里（--ops-running / --ops-done / --ops-failed / --ops-cancel 全套 4 色），避免再次发散
- [ ] (体验) 子任务 toolbar 的"过滤"按钮（`data-filter`）当前只是 placeholder，绑定 `console-btn-ghost` 但没有任何 `click` handler，也没有输入框 `search` 联动；点击无反应。建议：① 把 `search` 输入框的 `input` 事件接到 `render(active, history, filter)` 上做客户端即时过滤；② 或暂不实现，先把按钮 disable + 加 title="敬请期待" 避免用户上当
- [ ] (体验) 子任务 history rows 没有"展开看结果"的能力：done 行的 `data-stream` 按钮当前只是弹一个 toast「子任务 ... 的执行流已在主对话中显示」（`showStream` 实现），无法就地看 resultText；cancelled/failed 行只有 `i` info 按钮 + × delete 按钮，没有详情入口。建议：把 `data-stream` 重定向为打开一个 inline 折叠面板（复用现有结果文本），或在 modal 内加一个右下角"详情抽屉"
- [ ] (可访问性) 子任务 history 的 status label (`RUNNING / DONE / FAILED / CANCELLED`) 已经以文字形式给出（`_rowHTML` 渲染 `<span class="console-status">…</span>` 包住状态文本，见 app.js ~1756-），所以**颜色不是唯一信号**；但当前没有任何 unicode glyph / 图标，色盲用户仍只能靠文本+位置区分，对屏幕阅读器也缺语义。建议：① 每个状态加一个 unicode glyph（`▶ ✓ ✗ ◯`），并把状态 chip 的 `::before` 同时显示色 + glyph；② 在 `.console-card` 根节点加 `aria-label="状态:已完成, 子任务 <id>, 耗时 <elapsed>"`，并把 status chip 包 `aria-live="polite"` 让状态变更能被读屏播报；③ stripe 保留但仅作为冗余视觉提示（已经有文本了，所以这里是增强而非修复）
- [ ] (体验) 子任务运行中行的"查看 stream 日志"按钮（≡）和 schedule 模态框里同一个 ≡ icon 含义不一致（schedule 是查看历史），用户跨 modal 切换会困惑。建议：两个 modal 的 ≡ 按钮都用同一个 title="查看流式日志"（子任务）或"查看历史"（schedule），并 tooltip 一致
- [ ] (性能) 子任务 modal 每 2s 触发一次 `Promise.all([listActive, listHistory])` + 全量重渲染（`body.innerHTML = ...`），即使没有新数据也会重建所有 DOM；running rows 的 1Hz ticker 单独维护已规避重渲染，但 done/failed 行的 stripe、icon、glyph 仍会被销毁重建。建议：① 改为 diff render（按 subTaskId 复用 row），或② 退一步，至少在 `lastActive === active && lastHistory === history` 时跳过 render

## Task 9 (定时任务 modal — operations console 设计 + V13 H2 持久化)

- [ ] (功能/UI 不匹配) 定时任务 modal 的 "手动触发" 按钮 (▶ 图标, `data-trigger`) 当前仅 `showToast('已请求触发 X', 'info')`,**没有真正调用后端** — `schedulePanel.trigger()` (app.js ~2210) 是空操作,后台没有 `POST /schedule/{name}/trigger` 之类的路由;用户点 ▶ 期望立即看到子任务启动,实际只是 toast 提示。建议：① 后端新增 `POST /spring/ai/loom/schedule/trigger` 接 `{name}` 参数,在 `FlexScheduledTaskService` 上新增 `triggerNow(name)` 方法(用 `flex-schedule` 的 `Task.triggerNow()` 或构造一个立即 fire 的子任务),要求同 `handleScheduleCancel` 的 RBAC 校验(只能触发自己 username 的任务);② 前端 `schedulePanel.trigger(fullName)` 改为 `await api.triggerSchedule(fullName)` + 真实 toast 含 trigger 状态;③ 如果短期不实现,先把按钮 disable + 加 `title="敬请期待"`,避免误导
- [ ] (历史可见性) 定时任务 modal **运行中** 的行没有"查看执行历史"按钮(只有 ENDED 才有 `data-history`),但运维场景下"这个 fixed_delay 已经触发过几次 / 上次成功失败"是高优先级信息。建议：在所有 live 行也加一个 ≡ 历史按钮(等同 ended 行),调用 `api.scheduleHistory(fullName)`,并在 modal 内用一个 inline 折叠面板/小抽屉展示最近 5 条 execution row(时间/结果/耗时),而不是弹 confirm() 对话框 — confirm() 是 modal-on-modal 的反模式
- [ ] (RBAC) 定时任务 modal 没有"全部停止"按钮,虽然 `schedulePanel.cancelAll(convId)` 方法 + 后端 `POST /schedule/by-conversation/{convId}/cancel-all` 路由都已实现(Task 8 todo 第 1 条已提及,schedule 端同样缺失),但 `_toolbarHTML` (app.js ~2033) 不渲染 `data-cancel-all`;运维场景"这个对话一堆 1m 间隔的 polling 任务占资源,一键停掉"是常见诉求。建议：在 schedule modal toolbar 增加 `data-cancel-all` 按钮,UI 文案「全部停止 (N)」,disabled 条件 `live.length === 0`;同样要 username-scoped(后端已正确接收 `UserContextHolder.getCurrentUser()`,前端只调 API 即可)
- [ ] (UI 规范) 定时任务 modal 的"新建"按钮(`_toolbarHTML` 中 `data-new`)和子任务 modal 一样,`_wireToolbar` 直接调 `subtaskPanel._focusChatWithStub('请帮我创建一个定时任务')` —— 实际是把 stub 塞回主聊天让 LLM 调 `create_scheduled_task`,而不是直接打开一个"创建定时任务"表单(Task 7 子任务 modal 的同类问题已记录)。建议：① 在 modal 内加一个 `<form>` 含 名称/类型(cron/fixed_delay/fixed_rate/one_shot)/间隔/cron 表达式/prompt 字段,前端直接 `POST` 给一个新的 `POST /spring/ai/loom/schedule/create` 路由(LLM 工具调用那条路绕过,纯 REST);② 或者在空状态 composer 旁加 "⚠ 直达模式(高级)" 开关
- [ ] (空状态文案) 定时任务 modal 空状态(无 conversation 选中时)的 prompt 是 `请先打开一个对话` 但 toolbar `运行中` 数字还显示 `0`;两个状态对一个没选对话的用户来说有点冗余。建议：在 `_renderEmpty('请先打开一个对话')` 模式下隐藏整个 toolbar(只保留关闭按钮),让空状态更聚焦;或把 `运行中 0` 替换为"暂无可管理的对话"
- [ ] (交互一致性) 定时任务 modal 的 ▶ (手动触发) 和 ■ (停止) 按钮都是 `console-icon-btn` 类,子任务 modal 是 `▶` 和 `■`,图标对照一致;但**两个 modal 的 ≡ 历史按钮含义不同**(schedule = 查看历史,subtask = 查看流式日志),用户跨 modal 切换时 tooltip 容易混淆。建议：统一 ≡ 按钮的 title 为 `查看历史`(两个 modal 都用它来打开一个 execution log 抽屉),不再混用"流式日志"语义;task 8 todo 第 6 条也提及该不一致
