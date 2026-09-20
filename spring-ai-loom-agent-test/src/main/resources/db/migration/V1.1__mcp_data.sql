-- =============================================================
-- V1.1 MCP 演示数据（test 模块 / 应用方 seed,非库层内容）
-- =============================================================
-- 内容:4 个 mcp_server 元数据 + 34 个 mcp_tool 中文描述 + base 角色 4 条 role_mcp 授权。
-- 依赖:V1.0(库)先建表并 seed 默认基础角色 base;Flyway 同实例按版本顺序执行。
-- 命名约定:mcp_name = 运行时 SDK client 名 "spring-ai-mcp-client - <mcp-servers.json 的 key>",
--   必须与 src/main/resources/mcp-servers.json 保持一致,否则 getVisibleMcpsForUser 匹配不上。
-- 全新库政策:本文件只在 fresh init 跑一次,无需幂等守卫。
-- =============================================================


-- ============== 1. MCP 服务元数据(4 个) ==============

INSERT INTO mcp_server (name, title, description, is_active) VALUES
    ('spring-ai-mcp-client - bing-search', '必应搜索',
     '一个集成了微软必应搜索API的模型上下文协议服务', FALSE),
    ('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', '网页内容抓取',
     '一个强大的MCP服务器,可以轻松地将网页内容抓取并转换为各种格式(HTML、JSON、Markdown、纯文本)', FALSE),
    ('spring-ai-mcp-client - sequential-thinking', '顺序思维',
     '一种MCP服务器实现，提供动态且反射性的解决问题的工具', FALSE),
    ('spring-ai-mcp-client - mcp-server-chart', '图表生成',
     '一个基于 AntV 的图表生成 MCP 服务器，提供柱状图、折线图、饼图、地图、思维导图、流程图等 20+ 种可视化图表的生成能力', FALSE);


-- ============== 2. 工具中文描述(34 个) ==============

-- 2.1 基础工具:网页抓取(4)+ 顺序思维(1)+ 必应搜索(2)
-- bing-cn-mcp 上游 description 是 GBK 编码，Spring AI 当 UTF-8 读会乱码。
-- 在 DB 层覆盖（DB 描述优先于 SDK fallback）。
INSERT INTO mcp_tool (mcp_name, name, description, sort_order) VALUES
    ('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_html', '获取网页内容，并以 HTML 格式返回', 1),
    ('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_markdown', '取网页内容，并以 Markdown 格式返回', 2),
    ('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_txt', '取网页内容，并以纯文本格式返回（不含 HTML）', 3),
    ('spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 'fetch_json', '从指定 URL 获取 JSON 文件', 4),
    ('spring-ai-mcp-client - sequential-thinking', 'sequentialthinking', '本工具通过可适应与演进的灵活思维流程，协助对问题进行深入分析', 1),
    ('spring-ai-mcp-client - bing-search', 'bing_search', '使用必应（Bing）搜索引擎执行中英文网页搜索，返回搜索结果摘要与链接。支持关键词组合与自然语言查询。', 1),
    ('spring-ai-mcp-client - bing-search', 'crawl_webpage', '根据搜索结果中的 UUID 抓取对应网页的正文内容。支持批量抓取多个网页，自动跳过黑名单站点（如知乎、小红书等）。', 2);

-- 2.2 图表生成工具(27)
INSERT INTO mcp_tool (mcp_name, name, description, sort_order) VALUES
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_area_chart',
     '生成面积图：在连续自变量下展示数据趋势并观察整体走向，例如以时间为横轴、瞬时速度为纵轴时，可通过面积大小推断位移', 1),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_bar_chart',
     '生成条形图（横向柱状图）：用于在不同类别之间进行数值横向比较', 2),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_boxplot_chart',
     '生成箱线图：用于在不同类别之间展示统计汇总分布，比较各类别数据点的分布情况', 3),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_column_chart',
     '生成柱状图（纵向）：用于类别数据的比较；当各数值接近时，柱状图比面积图、角度图更易判读高度差异', 4),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_district_map',
     '生成区域分布地图：用于展示数据集覆盖的行政区划与分布范围（如全国各省/市 GDP 分布）。仅支持生成中国境内的数据地图，不适合展示具体点位的分布', 5),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_dual_axes_chart',
     '生成双轴图：组合两种图表类型（通常是柱状图+折线图）的复合图，同时展示趋势与对比，例如销售与利润随时间的变化', 6),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_fishbone_diagram',
     '生成鱼骨图：以鱼骨结构展示核心问题的成因或结果（鱼头为问题、鱼骨为成因/结果），适用于可拆解为多因素的问题分析', 7),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_flow_diagram',
     '生成流程图：用于展示流程或系统的步骤与决策点，适合需要线性呈现处理过程的场景', 8),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_funnel_chart',
     '生成漏斗图：用于可视化数据在经过各阶段时的逐级减少，例如用户从访问网站到完成购买的转化率', 9),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_histogram_chart',
     '生成直方图：用于展示数据在某个区间内的频次分布，可观察正态、偏态等分布形态及数据集中区域与极值点', 10),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_line_chart',
     '生成折线图：用于展示数据随时间的趋势变化，例如苹果电脑销量与利润占比从 2000 年至 2016 年的演变', 11),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_liquid_chart',
     '生成水波图（液位图）：将单个数值以百分比形式可视化展示，例如水库当前蓄水率、项目完成百分比', 12),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_mind_map',
     '生成思维导图：以中心主题向外辐射的层级结构组织与展示信息，例如展示主主题与各子主题之间的关系', 13),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_network_graph',
     '生成网络关系图：用于展示实体（节点）之间的关系（边），例如社交网络中的人际关系', 14),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_organization_chart',
     '生成组织架构图：用于可视化组织的层级结构，例如展示 CEO 与直属下级的关系', 15),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_path_map',
     '生成路线图：用于展示用户的规划路线，例如旅行攻略中的路线规划', 16),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_pie_chart',
     '生成饼图：用于展示部分占整体的比例，例如市场份额、预算分配', 17),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_pin_map',
     '生成点位地图：用于在地图上展示点位数据的分布，例如景点、医院、超市等的位置分布', 18),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_radar_chart',
     '生成雷达图：用于展示多维度数据（四个或以上维度），例如从易用性、功能、拍照、跑分、续航五个维度比较华为与苹果手机', 19),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_sankey_chart',
     '生成桑基图：用于可视化数据在不同阶段或类别之间的流动，例如用户从落地页到完成购买的旅程', 20),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_scatter_chart',
     '生成散点图：用于展示两个变量之间的关系，帮助发现它们的相关性或趋势，例如相关强度、数据分布模式', 21),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_spreadsheet',
     '生成表格或透视表：用于展示表格数据。当提供 rows 或 values 字段时渲染为透视表（交叉表），否则渲染为普通表格，适合展示结构化数据、跨类别比较数值、生成数据汇总', 22),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_treemap_chart',
     '生成矩形树图：用于展示层级数据，可直观展示同层级项目之间的比较，例如用树图展示磁盘空间使用情况', 23),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_venn_chart',
     '生成韦恩图：用于可视化不同集合之间的关系，展示它们的交集与重叠，例如不同群组之间的共性与差异', 24),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_violin_chart',
     '生成小提琴图：用于在不同类别之间展示统计汇总分布，比较各类别数据点的分布情况（与箱线图类似，但额外展示密度分布形状）', 25),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_waterfall_chart',
     '生成瀑布图：用于可视化连续引入的正负值对初始值的累积影响，例如展示初始值经过一系列中间正负值影响后到达的最终结果。瀑布图常用于财务分析、预算跟踪、损益分析以及理解跨时间或跨类别变化的构成', 26),
    ('spring-ai-mcp-client - mcp-server-chart', 'generate_word_cloud_chart',
     '生成词云图：通过文字大小变化展示词频或权重，例如分析社交媒体、评论或反馈中的高频词', 27);


-- ============== 3. base 角色 MCP 授权(4 条) ==============
-- 聊天面板默认勾选启动(default_enabled=TRUE);sort_order 决定展示顺序。

INSERT INTO role_mcp (role_code, mcp_name, sort_order, default_enabled) VALUES
    ('base', 'spring-ai-mcp-client - sequential-thinking', 0, TRUE),
    ('base', 'spring-ai-mcp-client - bing-search', 1, TRUE),
    ('base', 'spring-ai-mcp-client - @tokenizin-agency/mcp-npx-fetch', 2, TRUE),
    ('base', 'spring-ai-mcp-client - mcp-server-chart', 3, TRUE);
