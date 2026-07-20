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

- [ ] (功能/UI 不匹配) 定时任务 modal 的 "手动触发" 按钮 (▶ 图标, `data-trigger`) 当前仅 `showToast('已请求触发 X', 'info')`,**没有真正调用后端** — `schedulePanel.trigger()` (app.js ~2210) 是空操作,后台没有 `POST /schedule/{name}/trigger` 之类的路由;用户点 ▶ 期望立即看到子任务启动,实际只是 toast 提示。**⚠ Peer audit 澄清:** `flex-schedule` 的 `FlexScheduledTaskService` / `TaskBuilder` 公开 API 仅包含 `task / schedule / add / fixed / cron / cancel` 等,**没有 `triggerNow(name)`,也没有 `Task.triggerNow()`** — 之前 todo 提到的"新增 triggerNow"是基于臆测,不可行。可执行的最小方案：① **保持 `POST /spring/ai/loom/schedule/trigger` 路由 + `api.triggerSchedule(fullName)` 前端调用 + RBAC 校验(只能触发当前 username 的任务,与 `handleScheduleCancel` 一致)**;② 路由实现里复用现有 `add(...)` + 最小间隔(如 100ms `fixed_delay`)构造**一次性**任务立即 fire,然后由 hook(`IScheduleTool` 现有的 trigger 实现里命名空间 `loom-sched-{user}-{conv}-{name}`)认领执行 — 这能在不动 flex-schedule 上游的前提下立刻跑通;真正的 `triggerNow` 需要给 flex-schedule 提 PR(loom-agent 仓库外),记一个 follow-up issue 即可。建议：③ 如果短期不实现,先把按钮 disable + 加 `title="敬请期待"`,避免误导
- [ ] (历史可见性 + RBAC) 定时任务 modal **运行中** 的行没有"查看执行历史"按钮(只有 ENDED 才有 `data-history`),但运维场景下"这个 fixed_delay 已经触发过几次 / 上次成功失败"是高优先级信息。**Peer audit RBAC 风险**：原始建议的 `api.scheduleHistory(fullName)` → `GET /spring/ai/loom/schedule/history/{name}` (`LoomAgentConfiguration.java:972-974` + `app.js:486-488`) 把调用者控制的 `name` 直接透传给 `flexService.getExecutionHistory(...)`,**未校验 `UserContextHolder`**,任何已登录用户只要知道别人的 namespaced 任务名(格式 `loom-sched-{user}-{conv}-{name}`)就能读到全部 execution history,等同于一个跨用户数据泄漏。修复方案（任选其一）：① **优先** 复用 `GET /spring/ai/loom/schedule/history/by-conversation/{conversationId}` (`app.js:490-493` / `LoomAgentConfiguration.java:984+`),该端点按 conversation 范围返回每条任务的 execution 列表,服务端 scope 在当前 username 拥有的 conversation,前端 UI 改造为"在 by-conversation 结果里过滤该 taskId,渲染最近 5 条 execution row(时间/结果/耗时)",inline 折叠面板/小抽屉展示,不弹 confirm();② 或者**保留** `api.scheduleHistory(fullName)`,但**强制** `LoomAgentConfiguration.java:972-974` 的 handler 在调用 `flexService.getExecutionHistory` 之前,先用 `UserContextHolder.getCurrentUser()` 取当前用户,要求任务名按 `loom-sched-{currentUser}-{conv}-{name}` 前缀匹配(或在 H2 `loom_scheduled_task` 上做 `WHERE name=? AND username=?` 双重校验),失败返回 403,**禁止**直接透传 caller 提供的 `name`。**任一方案落地前不要在 UI 上加 ≡ 历史按钮**,否则会暴露上述越权读取
- [ ] (RBAC) 定时任务 modal 没有"全部停止"按钮,虽然 `schedulePanel.cancelAll(convId)` 方法 + 后端 `POST /schedule/by-conversation/{convId}/cancel-all` 路由都已实现(Task 8 todo 第 1 条已提及,schedule 端同样缺失),但 `_toolbarHTML` (app.js ~2033) 不渲染 `data-cancel-all`;运维场景"这个对话一堆 1m 间隔的 polling 任务占资源,一键停掉"是常见诉求。建议：在 schedule modal toolbar 增加 `data-cancel-all` 按钮,UI 文案「全部停止 (N)」,disabled 条件 `live.length === 0`;同样要 username-scoped(后端已正确接收 `UserContextHolder.getCurrentUser()`,前端只调 API 即可)
- [ ] (UI 规范) 定时任务 modal 的"新建"按钮(`_toolbarHTML` 中 `data-new`)和子任务 modal 一样,`_wireToolbar` 直接调 `subtaskPanel._focusChatWithStub('请帮我创建一个定时任务')` —— 实际是把 stub 塞回主聊天让 LLM 调 `create_scheduled_task`,而不是直接打开一个"创建定时任务"表单(Task 7 子任务 modal 的同类问题已记录)。建议：① 在 modal 内加一个 `<form>` 含 名称/类型(cron/fixed_delay/fixed_rate/one_shot)/间隔/cron 表达式/prompt 字段,前端直接 `POST` 给一个新的 `POST /spring/ai/loom/schedule/create` 路由(LLM 工具调用那条路绕过,纯 REST);② 或者在空状态 composer 旁加 "⚠ 直达模式(高级)" 开关
- [ ] (空状态文案) 定时任务 modal 空状态(无 conversation 选中时)的 prompt 是 `请先打开一个对话` 但 toolbar `运行中` 数字还显示 `0`;两个状态对一个没选对话的用户来说有点冗余。建议：在 `_renderEmpty('请先打开一个对话')` 模式下隐藏整个 toolbar(只保留关闭按钮),让空状态更聚焦;或把 `运行中 0` 替换为"暂无可管理的对话"
- [ ] (交互一致性) 定时任务 modal 的 ▶ (手动触发) 和 ■ (停止) 按钮都是 `console-icon-btn` 类,子任务 modal 是 `▶` 和 `■`,图标对照一致;但**两个 modal 的 ≡ 历史按钮含义不同**(schedule = 查看历史,subtask = 查看流式日志),用户跨 modal 切换时 tooltip 容易混淆。建议：统一 ≡ 按钮的 title 为 `查看历史`(两个 modal 都用它来打开一个 execution log 抽屉),不再混用"流式日志"语义;task 8 todo 第 7 条也提及该不一致

## Task 10 (Admin Console — 用户管理 CRUD)

- [ ] (功能缺口) 用户管理表格只有「分配角色」和「删除」按钮,**没有"编辑昵称"和"重置密码"**入口 — brief Step 4 / Step 5 期望这两个操作,但 console.js 完全没有对应的 modal / fetch;后端 IUser 接口也缺 `updateUser(username, nickname, type)` 和 `adminResetPassword(username, newPassword)` 两个方法,LoomAgentConfiguration 里没有对应的 PUT/POST 路由。建议:① 后端 `IUser` 新增 `updateUser(username, nickname, type)` + `adminResetPassword(username, newPassword)`,LoomAgentConfiguration 加 `PUT /spring/ai/loom/admin/users/{username}` 和 `POST /spring/ai/loom/admin/users/{username}/reset-password`,RBAC 校验同 delete (admin only + 不能操作最后一个 ADMIN);② 前端 console.html 操作列在「删除」前加「编辑」「重置密码」两个 secondary-btn,点击分别打开 edit-nickname modal 和 reset-password modal,复用现有 confirm + toast 流程;③ user.html 详情页同步加同样的入口,这样管理员从用户列表点用户名进来也能操作
- [ ] (UI 一致性) 「分配角色」modal 标题是 `分配角色:{username}`,但「删除」modal 标题是 `删除用户`,两者文案不一致;brief 强调"角色分配 user ↔ admin 可切换",但 `分配角色` 实际上分配的是**业务角色**(由角色管理页维护),不是用户类型(管理员/普通用户)。新用户**类型**只能在「+ 新建用户」时选择,**创建后无法升级/降级** —— 这是产品设计缺陷,建议在操作列加一个「修改类型」按钮,允许 USER ↔ ADMIN 切换
- [ ] (扩展行 UX) 用户名点击展开行内显示该用户会话列表,但 brief Step 4 期望编辑用户时进入一个**独立的编辑页**,展开行主要承担"查看会话"职责。建议:把行展开区拆为两个 tab — 「基本信息」(可改昵称/类型/重置密码) 和「会话」,基本信息 tab 内嵌表单 + 保存按钮,避免跳页或弹 5+ 个 modal
- [ ] (列表排序) 用户列表按后端返回顺序渲染(看起来是 username 字典序),没有按「本月 TOKEN」倒序、按「类型」分组等常见排序;管理员想看"哪些用户最费 token"只能全表扫。建议:加表头点击排序(列: 用户名/昵称/类型/本月 TOKEN),记住用户上次排序选择
- [ ] (删除安全网) 「删除」按钮直接弹 confirm modal,确认就 `DELETE /admin/users/{username}`;但没有检查该用户是否有进行中的会话/文件/技能,也没有提供「软删除」选项。建议:① 删除前先 `GET /admin/users/{username}/stats` 显示「该用户有 N 个会话 / M 个文件 / K 个角色」让管理员二次确认;② 提供「软删除」开关(同会话的 deletedAt 模式),保留 30 天可恢复

## Task 11 (Admin Console — 角色管理 CRUD)

- [ ] (UI 不一致) 角色管理页「授权 MCP 服务」和「授权 Skill」两个区域样式几乎一致(列表行 + 上下移动 + 移除/添加),但**「授权 MCP」的行索引用阿拉伯数字「1./2./3.」+ 移除按钮**,**「授权 Skill」同样也是「1./2./3.」+ 移除按钮**——两个区域的"默认启用"/"默认加载"checkbox 文字也不同 (MCP=「默认启用」, Skill=「默认加载」),且行高度、上下移动列宽不一致;建议统一为同一 `role-auth-row` class,让两个区块视觉对称
- [ ] (可发现性) 「删除角色」按钮放在 modal footer 左侧,文字「删除角色」+ 红色样式,但**与「保存」按钮放在一起,管理员容易误把"删除"当成"取消保存"快速点击**(尤其在刚改完授权还没保存的场景下);建议删除按钮用 icon (🗑) + text,或在 modal 加一道确认横栏(类似 git revert 的二次确认)
- [ ] (产品定义) 「新建角色」表单里只有 MCP 服务多选,没有"绑定技能"的入口(只能创建后再"编辑/授权"添加 Skill);导致"创建 + 给技能"是两步操作,中间要关掉创建 modal 重开编辑 modal。建议把「已选 Skill」区域也加到新建角色 modal,或者在创建成功后自动弹出详情 modal 让用户继续授权
- [ ] (空状态) 角色列表为空时只显示「暂无角色」,没有任何引导文案(如"还没有业务角色,点 + 新建角色 创建第一个");非技术管理员第一次访问可能不知如何开始。建议把空状态从纯文本升级为 `+ 新建角色` 大按钮 + 简短说明
- [ ] (UX) 角色详情 modal 的「授权 MCP 服务」和「授权 Skill」两个 scrollable 容器最大高度 280px / 200px,在 MCP 数量较多(>=10)时滚动条可见,但滚动条样式是浏览器默认(粗黑边)且与 console.css 整体风格不一致;建议加自定义 thin scrollbar (webkit + firefox) 或用 `overflow-y: overlay` 让滚动条不占布局空间
- [ ] (性能) 打开角色详情 modal 时同时拉 5 个端点(`roles`, `roles/{code}/mcps`, `roles/{code}/skills`, `mcp-system`, `market-skills`),如果 MCP/Skill 数量大,5 次串行 await 都完成后才显示 modal —— 实测在本地 (curl) 5 个端点 P50 < 100ms,无明显卡顿,但**`Promise.all` 包裹的所有请求都是 admin 鉴权(每次都带 cookie)**,如果其中一个 401 全部失败且没有任何部分渲染;建议至少对非关键的 `market-skills` 做 try/catch fallback ([]),让 modal 在某些端点失败时仍能展示基础数据

## Task 12 (Admin Console — MCP 描述维护)

- [ ] (功能缺口, 来自 brief Step 5) MCP 管理页 mcps.html **没有 "default-selected 状态切换" UI** —— 页面只有"编辑标题/描述 + 工具描述"功能。Brief Step 5 期望能在系统级或服务级开关"普通用户首次进入聊天界面时是否默认勾选该 MCP",但当前 `McpSystemView.defaultSelected` 是 read-only 字段(admin 始终 true),V7 设计 `mcp 是否可用完全由角色授权决定` 移除了 `is_active`,所以系统级 defaultSelected 也无字段可写。可执行方案:① 短期:在 mcps.html 服务列表加一列 "默认选中 (系统)" checkbox,新增 `PUT /admin/mcps/{name}/default-selected` 端点 + `mcp_server.default_selected BOOLEAN DEFAULT TRUE` 字段(ALTER + Flyway V14),前端 read+write,聊天界面 app.js 拉 /mcps 时返回该字段(而非计算 defaultSelected);② 长期:让普通用户的 defaultSelected 由 role_mcp.default_enabled 聚合计算(任务 11 已经写了 role 级 toggle),mcps.html 加列展示聚合结果(read-only,只显示"该 MCP 在 N 个角色里被默认启用");③ 同步在 mcps.html 上方加一段说明,避免管理员误以为"我在这里改会立刻影响普通用户"
- [ ] (功能缺口) mcps.html 服务列表只有"编辑"按钮,**没有"删除维护"按钮(只针对工具提供)** —— 删除整个 MCP 的维护行(回到"未维护"状态)需要 admin 清空所有字段再保存,没有一步到位的入口。建议在操作列加"删除维护"按钮(类似工具的「删除维护」):弹确认 modal 后 `DELETE /admin/mcps/{name}`(新端点,后端 `DELETE FROM mcp_server WHERE name = ?` + 返回 404 if not maintained);同时把"删除维护"和工具上的按钮都加 `disabled` 状态(当行是"未维护"时,按钮置灰 + title="未维护, 无需删除")
- [ ] (UI 不一致) mcps.html 服务表的 5 列(服务名/标题/描述/状态/操作)在 1920px 桌面视口下 "描述" 列 `max-width:300px; overflow:hidden; text-overflow:ellipsis`,长描述(如 "一个强大的MCP服务器,可以轻松地将网页内容抓取并转换为各种格式...")被截断,管理员无法在不点开编辑 modal 的情况下预览完整描述。建议:① 描述列 hover 时显示原生 title 属性(或加 CSS `:hover::after` 浮窗),② 或者把 max-width 提到 480px,代价是横向滚动条出现;建议折中:把服务名/标题列宽 200px,描述列宽自适应 `min(60ch, calc(100vw - 800px))`
- [ ] (产品定义) mcps.html 表格列名 "状态" 实际只反映 "mcp_server 行是否存在",文案 "已维护" / "未维护" 容易让管理员误以为是 "MCP 服务本身是否启用"。事实上 mcps.html 是**纯元数据维护页面**,不影响 MCP 服务是否可用。建议:① 把列名从 "状态" 改成 "维护状态",② 或者直接在列里加 tooltip 解释"这里只改显示用的标题/描述,服务是否启用请到角色管理"
- [ ] (错误处理) mcps.js 编辑 modal 的 `saveEdit()` 顺序 PUT server + 并行 PUT 多个 tool:虽然 UI 上 4xx 会弹 `showErr(t)`,但**如果 server PUT 成功而 tool PUT 失败**,JS 仍弹 "保存成功" toast(`closeEdit()` + `loadList()` 已执行)——管理员以为全成功,实际 tool 描述丢失。建议:① `await Promise.all(toolUpdates)` 后检查每个响应的 status,任一非 2xx 抛错进 catch 弹 "部分工具描述保存失败";② 或者把 server PUT 失败也进 catch(目前只在 network 错误时进 catch,4xx 也走 await 后检查 r.ok,但 4xx 分支只 showErr 不阻止后续 tool PUT)
- [ ] (可发现性) mcps.html **没有"操作列宽"提示** —— 编辑 modal 打开时只显示一个 form,没有任何文字告诉管理员"可以单独保存每个工具的描述,无需保存服务元数据"。新手管理员可能不知道工具描述能独立维护。建议在工具描述区上方加一行小字:"每个工具的描述可单独保存,无需同时保存服务元数据",或者工具描述区做成可折叠 `<details>` 默认展开
- [ ] (安全性) PUT /admin/mcp-tools/{toolId} 的 toolId 是 path variable,Long.parseLong 直接解析:如果传非数字会抛 NumberFormatException → 500;如果传负数会 UPDATE 一行(虽然不可能匹配上 id<0,但更稳健是 400/404)。建议:① 改用 `try { Long.parseLong(...) } catch { return 400 }`,② 或者在 route handler 里显式 `toolId > 0` 校验(0 是合法值,代表"INSERT")
