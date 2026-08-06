-- =============================================================
-- Spring AI LoomAgent demo 业务数据
-- =============================================================
-- 业务模块（test / 真实开发者的应用）需要自己 seed 的数据。
-- 库模块 V1__init.sql 只建表 + 默认 admin 账号，业务数据归应用方。
-- Flyway 默认配置：classpath:db/migration + flyway_schema_history（与库模块的 loomAgent_schema_history 独立）。
-- =============================================================


-- =============================================================
-- MCP 服务元数据：12 个内置 mcp 服务的中文标题/描述
-- =============================================================

INSERT INTO mcp_server (name, title, description, is_active) VALUES
('spring-ai-mcp-client - bing-search',                 '必应搜索',       '一个集成了微软必应搜索API的模型上下文协议服务',                                                                                                                                                                                                FALSE),
('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', '网页内容抓取',  '一个强大的MCP服务器,可以轻松地将网页内容抓取并转换为各种格式(HTML、JSON、Markdown、纯文本)',                                                          FALSE),
('spring-ai-mcp-client - sequential-thinking',         '顺序思维',       '一种MCP服务器实现，提供动态且反射性的解决问题的工具',                                                                                                                            FALSE),
('spring-ai-mcp-client - memory',                     '记忆',           '使用本地知识图谱实现持久化记忆',                                                                                                                                                FALSE),
('spring-ai-mcp-client - cn-weather-mcp',             '中国天气',       '自研中国城市实时天气服务',                                                                                                                                                    FALSE),
('spring-ai-mcp-client - http-mcp',                   'HTTP 客户端',    'HTTP客户端MCP服务，提供通用HTTP请求功能',                                                                                                                                        FALSE),
('spring-ai-mcp-client - playwright',                  'Playwright',     '通过结构化可访问性快照与网页交互',                                                                                                                                              FALSE),
('spring-ai-mcp-client - chrome-devtools',            'Chrome DevTools','控制和检查Chrome浏览器，实现自动化操作和调试',                                                                                                                                FALSE),
('spring-ai-mcp-client - context7',                   'Context7',       NULL,                                                                                                                                                                    FALSE),
('spring-ai-mcp-client - git-mcp-server',              'Git MCP',        NULL,                                                                                                                                                                    FALSE),
('spring-ai-mcp-client - mcp-server-docker',          'Docker MCP',     NULL,                                                                                                                                                                    FALSE),
('spring-ai-mcp-client - desktop-commander',          'Desktop Commander', NULL,                                                                                                                                                              FALSE);


-- 工具中文描述
INSERT INTO mcp_tool (mcp_name, name, description, sort_order) VALUES
('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_html',    '获取网页内容，并以 HTML 格式返回',     1),
('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_markdown','取网页内容，并以 Markdown 格式返回',  2),
('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_txt',     '取网页内容，并以纯文本格式返回（不含 HTML）', 3),
('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_json',    '从指定 URL 获取 JSON 文件',              4),
('spring-ai-mcp-client - sequential-thinking', 'sequentialthinking',     '本工具通过可适应与演进的灵活思维流程，协助对问题进行深入分析', 1),
('spring-ai-mcp-client - memory', 'create_entities',   '在知识图谱中创建多个新实体',       1),
('spring-ai-mcp-client - memory', 'create_relations',  '在知识图谱的实体之间创建多个新关系', 2),
('spring-ai-mcp-client - memory', 'add_observations',  '向知识图谱中现有实体添加新的观察信息', 3),
('spring-ai-mcp-client - memory', 'delete_entities',   '从知识图谱中删除多个实体及其关联的关系', 4),
('spring-ai-mcp-client - memory', 'delete_observations','从知识图谱的实体中删除特定的观察信息', 5),
('spring-ai-mcp-client - memory', 'delete_relations',  '从知识图谱中删除多个关系',         6),
('spring-ai-mcp-client - memory', 'read_graph',        '读取整个知识图谱',                  7),
('spring-ai-mcp-client - memory', 'search_nodes',      '基于查询在知识图谱中搜索节点',       8),
('spring-ai-mcp-client - memory', 'open_nodes',        '通过名称打开知识图谱中的特定节点',   9),

-- bing-cn-mcp 上游 description 是 GBK 编码，Spring AI 当 UTF-8 读会乱码。
-- 在 DB 层覆盖（DB 描述优先于 SDK fallback）。
('spring-ai-mcp-client - bing-search', 'bing_search',    '使用必应（Bing）搜索引擎执行中英文网页搜索，返回搜索结果摘要与链接。支持关键词组合与自然语言查询。', 1),
('spring-ai-mcp-client - bing-search', 'crawl_webpage',  '根据搜索结果中的 UUID 抓取对应网页的正文内容。支持批量抓取多个网页，自动跳过黑名单站点（如知乎、小红书等）。', 2);


-- =============================================================
-- 6 个 system skill 进 Skill 市场（author='system'，version='1.0.0'，status='APPROVED'）
-- content 字段直接 hardcode 字符串（不再用 classpath: 占位符，不再依赖 .st 文件）
-- =============================================================

INSERT INTO market_skill (name, description, content, version, author, status, reviewed_at, reviewed_by) VALUES
('网络月度事件报告',
 '用户说"梳理 xxx 月度事件/年度时间线/事件复盘/xxx 的网络事件"时触发：围绕 {topic} 按月梳理当年的重要事件，做跨月因果关联与下一年趋势预判，产出 HTML 报告并返回预览链接（{topic} 来自用户当前对话）',
 '用户希望"按月梳理一个主题的年度重要事件，并产出洞察报告"——围绕用户当前对话里聊到的主题，
按月搜索、跨月关联、最终用 HTML 报告 + 预览地址呈现。

⛔ 最重要的执行纪律（必读，否则你会陷入自白死循环）：
1. **不要描述你打算做什么**——"我将为您梳理 / 我打算搜索 / 主题分析如下"这类自然语言自白
   对用户毫无意义，**只会吃光你的 completion_tokens，最终 finishReason=STOP + 空 content 收尾**。
   用户唯一能看到的是：(a) 工具执行结果（用户后台能看到工具调用记录），(b) 你最后给的那份
   HTML 报告 + 预览链接。中间所有"我打算 / 我分析 / 时间窗口"的自白请一律删除，**直接调工具**。
2. **本 skill 不写死工具名**——工具名一律从系统下发的 function declaration 里挑（系统已把
   当前可用的工具都注册好了），下面的描述只是告诉你要达成什么能力——具体调用哪个工具、
   参数怎么填，由你根据 function schema 自行决定。硬写 "@bing_search" 之类的名字经常和
   实际注册名（带短横线 / 前缀）对不上，导致 tool_call 静默失败。
3. **无论 phase 2 搜到多少结果，phase 3 必须执行**——搜索信息稀薄 / 全部返回官网首页 /
   没有任何 2026 年新闻等情况下，**直接进 phase 3**，空月份写"当月无显著公开事件"即可，
   千万**不要在 phase 2 死磕等"好结果"**。把已搜到的边角料 + 你的通用知识整合成报告就行。
4. **思考是按需的，不是 KPI**——不要为了"凑够 5 轮思考"而生成无意义的自白。每轮搜索后简单
   反思"还要不要继续 / 要不要换 query"即可，信息够了就推进 phase 3。

请按以下三阶段执行：

1. 第一阶段：理解与校准用户的主题（必做，1-2 步即可，不要展开成大段自白）
   用户当前对话里聊的主题暂记为 {topic}。注意：{topic} 不是字面替换变量，是「用户最近在问的那个主题」的代称。
   拿到上下文后，**只在脑内**判断它属于哪一类，决定后续搜索策略——**不要把判断结果写成大段自然语言自白**：

   - 实体明确型（如"特斯拉"、"宁德时代"、"小米 SU7"）→ 直接搜该实体在当月的事件
   - 领域宏观型（如"人工智能"、"新能源汽车"、"碳中和"）→ 拆 3-5 个子主题分别搜
     （例：「人工智能」→ 大模型 / 算力芯片 / 监管政策 / 投融资 / 典型应用）
   - 抽象概念型（如"创新"、"数字化转型"）→ 反问用户，提示换更具体的主题
   - 多义歧义型（如"党"、"苹果"、"华为"）→ 列出 2-3 种合理解释让用户选
   - 跨年事件（如"2023 年的"、"去年发生的"）→ 按用户给的时间窗口，不要死守当前年

   ⚠️ 不要把 {topic} 当成"必须逐字搜索的关键词"——它是入口，不是约束。

2. 第二阶段：搜索与分析（**按需推进，不要凑数**）
   a. 调用"获取当前时间"类工具拿到当前年（和当前月），确定要扫的月份窗口
   b. 调用"顺序思考"类工具规划月度扫描骨架（1-12 月 + 每子主题的 query），**只在工具里思考，
      不要回到对话里再讲一遍"我打算搜索…"**
   c. 每月 1-2 次调用"必应搜索"类工具搜索该月该主题的重要事件
      关键搜索后再用"网页抓取"类工具拿详情
   d. 跨月 / 跨子主题事件要做关联分析，串成因果链

   ⏱️ 何时停止 phase 2 推进 phase 3：
   - ✅ 已搜遍计划月份 / 已累计 ≥ 5 轮搜索但没新结果 → **立刻推进 phase 3**
   - ❌ 不要反复换 query 死磕同一个没结果的关键词
   - ❌ 不要"先看看 1 月、2 月、3 月…（自白 12 段）"再搜索

3. 第三阶段：报告生成（**必做，phase 2 搜不到数据也必须执行**）
   ⚠️ 关键前置（必做，否则会触发"路径通过 symlink 越界"）：
   - 第一步必须真的调用"列出当前用户文件目录"那个工具（其 description 里会写明"返回的是
     **绝对路径**"），用工具返回的字符串原样作为基准。**绝对不要**自己拼绝对路径（如把
     ".local\file\username" 前面加 "C:\..."）—— LLM 自己猜的项目根可能跟进程真实工作目录
     不一致；工具返回的就是 ground truth。
   - 后续所有"写入文件"/"创建目录"类工具的 path 参数都**相对于该目录**传，工具会自动 join，
     例如 path 传 "reports/小米SU7-2026.html" 即可
   - ⚠️ 工具返回里出现字面量 "wb04307201" 之类的用户名是正常的（LoomAgentProperties.UserProperty
     的默认值），不是占位符，不要替换

   a. 调用"创建目录"类工具建子目录（reports 不存在时"写文件"会失败）：
      path 传 "reports"

   b. 调用"写文件"类工具把报告写成 HTML：
      path 传 "reports/{topic}-当前年.html"（{topic} 必须替换为第一阶段确定的主题词，
      例如 "小米SU7"，不要保留字面量），content 传完整的 HTML 字符串

      内容结构：
      - 顶部摘要（≤ 200 字，概述全年主题脉络；信息源稀薄时诚实写"信息源覆盖有限"）
      - 按月分段（1 月、2 月、…、12 月），每月列出 0-5 条核心事件 + 来源链接
        **没搜到的月份写"当月无显著公开事件"，不要略过该月**
      - 末尾：年度关联洞察（跨月因果 / 跨子主题串联）+ 下一年趋势预判
        **信息源不足时，基于已搜到的内容 + 通用知识做合理推断，但要说清这是推断**
      - 样式：内联 CSS 简单排版（标题 / 列表 / 表格），深色 / 浅色兼容

   c. 调用"生成文件预览链接"类工具拿到预览链接（**必做，否则用户拿不到报告**）：
      path 跟步骤 b 用的完全一致，{topic} 也必须已替换为真实主题词

      工具返回里通常会带 "预览链接:http://..." 或 "markdown格式:[预览:xxx](http://...)" 这种字段，
      把那条链接（用上面 markdown 格式那行，或 [文字](URL) 包一下）直接展示给用户，
      这是用户**唯一**能直接点开报告的方式。**整条 skill 在这步才算交付完成**——
      **写完文件 → 立刻调预览链接工具 → 把链接展示给用户**，三步连成一次输出，不要中间
      插入大段"报告已生成 / 报告内容如下…"的自白。

注意：
- 主题过于抽象 / 歧义时，第一阶段必须先反问用户，不要硬搜
- 报告保存路径写到用户文件目录下的 reports/（相对路径，不要拼绝对路径），不要写到知识库目录
- 不要把仓库名 / 用户 ID / 文件名当成主题——{topic} 是用户当前对话里真正想聊的那个名词短语
- 最重要的"红线"：**写 HTML 报告 + 拿预览链接 是这个 skill 唯一的交付物**，
  没看到预览链接就等于没完成，无论你自白得多么详细',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),('http测试',
 '用户说"测试/调用 xxx 接口/http 探测/服务连通性"时触发：自定义 http 服务测试',
 '1. 使用 @httpGet 从 https://jsonplaceholder.typicode.com/todos/1 获取数据，headers为空
2. 使用 @httpPost 向 https://jsonplaceholder.typicode.com/todos 发送数据，headers为空，body为 {"title": "foo", "body": "bar", "userId": 1}
3. 使用 @httpPut 向 https://jsonplaceholder.typicode.com/todos/1 发送数据，headers为空，body为 {"title": "foo", "body": "bar", "userId": 1}
4. 使用 @httpDelete 删除 https://jsonplaceholder.typicode.com/todos/1 的数据，headers为空',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),('测试保存、下载、预览1',
 '测试保存、下载、预览1',
 '你能说明勾股定理么？并保存成md，并给我下载和和预览地址',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),('测试保存、下载、预览2',
 '测试保存、下载、预览2',
 '你能说明微积分么？并保存成html，并给我下载和和预览地址',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),('部署项目',
 '用户说"部署/上线/跑起来/启动 xxx 项目"时触发：从 git 仓库拉取代码 → 编译 → 构建 Docker 镜像 → 启动容器 → 返回访问链接，支持 maven / npm / npm-frontend / pip 多栈',
 '请严格按以下三步执行：

1. 从用户消息里提取以下参数。port 和 containerPort 任何一个缺失都必须先向用户追问，
   不要瞎猜、不要用 8080 之类的兜底：

   - gitUrl：Git 仓库 URL（必填，URL 形式）
   - gitUsername：Git 用户名（公开仓库可省略，私有仓库必填）
   - gitPassword：Git 密码或 token（同上）
   - port：宿主机对外端口（必填，例如用户说"我要在 9090 访问" → port=9090）
   - containerPort：应用在容器内实际监听的端口（必填，工具无 yml 兜底，缺失会 fail）
      如果用户没明说，提示"请确认仓里 application.yml 的 server.port 是多少"，
      让用户回答后再调用。**不要替用户猜默认值**——工具对缺 port / containerPort
      会 fail-fast 反问，你直接传一个 8080 兜底反而把责任揽到自己身上
   - subDir（可选）：多模块仓库指定子模块名。如果不指定，工具会自动尝试，但可能选错——
      多模块场景下请主动向用户确认要部署哪个
   - buildTool（可选）：maven / npm / npm-frontend / pip
      缺省时工具按仓里的 marker 文件自动探测（pom.xml→maven、package.json→npm、
      requirements.txt / pyproject.toml→pip）。多模块仓同时有 pom.xml 和 package.json 时
      必须显式指定。前端项目（Vue/React 静态构建）用 npm-frontend；Node 长驻后端用 npm；
      Python 后端用 pip。
   - baseImage（可选）：java17 / java21 / nginx / python3 / node20 / node20-serve，
      或完整镜像名（如 openjdk:17-slim）。缺省按 buildTool 自动选：
      maven→java17、npm→node20、npm-frontend→node20-serve（nginx 服务静态文件）、
      pip→python3
   - runCommand（可选）：覆盖 ENTRYPOINT 的字符串数组。Node 前端通常不需要；
      Python 后端常用 ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
   - healthPath（可选）：默认 "/"；没有 context-path 时传 "/"
      容器内端口 = containerPort（已在上文设），宿主机端口 = port

   ⚠️ 重要：不要把仓库名当成 branch！只有当用户**明确说出** "用 main 分支" / "用 develop 分支" / "切到 feature-xxx 分支" 等具体分支名时才传 branch 字段。
   仓库名 ≠ 分支名（"sql-forge-demo" 是仓库名，不是分支名）。

2. 把上述参数传给 @compileAndDeploy 工具，不要在工具外做任何额外的 shell / mvn / docker 操作：
   {
     "gitUrl": "用户提供的 URL",
     "gitUsername": "<可选，私有仓库填>",
     "gitPassword": "<可选，私有仓库填>",
     "port": <port>,
     "containerPort": <containerPort>,
     "subDir": "<可选>",
     "buildTool": "<可选>",
     "baseImage": "<可选>",
     "runCommand": [<可选>],
     "healthPath": "<可选，默认 ''/''; 有 context-path 时填实际路径如 sql-forge-demo>"
   }

3. 工具返回结果后：
   - 若 result.success == true：用 <a href="result.accessUrl" target="_blank">result.accessUrl</a> 展示访问链接
   - 若 result.success == false：把 result.steps 里的每一步用 Markdown 列表展示给用户，
     并在末尾附上 result.errorMessage，让用户知道卡在哪一步

注意：不要手动调用 git / mvn / docker / npm 工具，也不要拆分 @compileAndDeploy。
整个流程是 LLM → 单次工具调用 → 渲染结果。',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system'),('测试自动E2E功能验证',
 '测试使用浏览器对功能进行E2E测试，并生成验证报告',
 '按照下面的步骤执行，并在每步完成后回复用户
1. 打开 chrome 浏览器
2. 访问 http://localhost:8081/sql/forge/web
3. 使用 用户名: alice 密码: 123456 登录
4. 点击左侧菜单 商品管理 ，打开商品列表页面
5. 测试 商品列表页面 全部功能
6. 根据根据测试结果生成功能验证报告，格式为 *.md，使用 @writeFile 保存到，然后用 @downloadFileUrl 和 @viewFileUrl 获取链接',
 '1.0.0', 'system', 'APPROVED', CURRENT_TIMESTAMP, 'system')
