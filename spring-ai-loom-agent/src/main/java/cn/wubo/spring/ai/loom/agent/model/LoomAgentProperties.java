package cn.wubo.spring.ai.loom.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class LoomAgentProperties {

    private String defaultSystem = """
            你是一个智能助手。以下是你当前可用的能力：

            【技能】
            （由系统动态注入）

            【知识库】
            （由系统动态注入）

            【工具】
            （MCP 工具列表，由系统动态注入）

            当用户意图匹配某项技能时，调用 @getSkill 获取详细执行指南。
            当用户问题涉及上方【知识库】中列出的知识库时，调用 @searchKnowledge 检索相关信息（knowledgeId 使用列出的 ID）。
            都不匹配时，直接基于通用知识回答。
            """;
    private boolean init = true;
    private RagProperty rag = new RagProperty();
    // mcps 数据源已迁移到 V5__add_roles_and_mcp_metadata.sql (mcp_server / mcp_tool 表)
    // 通过 cn.wubo.spring.ai.loom.agent.rbac 包下的服务管理
    // skills 已迁移到 V10__skill_market.sql (market_skill / user_skill / role_skill)
    // 通过 cn.wubo.spring.ai.loom.agent.skill 包下的服务管理
    private JVectorProperties jvector = new JVectorProperties();
    private String timezone = "Asia/Shanghai";

    /**
     * Root directory under the user's home that hosts ALL loom-agent local
     * state (file uploads, knowledge files, H2 DB, jvector index, compile
     * workspaces). Putting everything under {@code ${user.home}/.loom/} means
     * a single {@code rm -rf ~/.loom/} wipes a user's complete local
     * footprint — and means cwd-relative paths no longer fire, so the
     * "spring-boot:run from test module vs parent module" inconsistency
     * disappears across the board.
     *
     * <p>Sub-paths ({@link #fileBasePath}, {@link #knowledgeBasePath},
     * {@link #jvector}, etc.) are derived from this root via field defaults,
     * but Spring's {@code @ConfigurationProperties} does NOT auto-resolve
     * field-init {@code ${...}} placeholders, so the actual integration
     * point is in the consumer side: each property either reads the literal
     * value above OR — when {@code loomHome} is overridden in yml — the
     * consumer rebuilds the path. Override the sub-paths directly in yml if
     * you only need to relocate a specific state category.</p>
     *
     * <p>Override via {@code spring.ai.loom.agent.loom-home} in
     * application.yml.</p>
     */
    private String loomHome = System.getProperty("user.home") + "/.loom";

    /**
     * User files root directory. Defaulted to an ABSOLUTE path under the
     * user's home directory (NOT a cwd-relative path) so the directory the
     * file manager UI shows is always the same as the directory IUpload
     * writes to — regardless of whether spring-boot:run is launched from the
     * parent project root or the test module root.
     *
     * <p>Overrideable via {@code spring.ai.loom.agent.file-base-path} in
     * application.yml.</p>
     */
    private String fileBasePath = System.getProperty("user.home") + "/.loom/file";
    /**
     * Knowledge-base file root. Defaulted to absolute for the same reason as
     * {@link #fileBasePath}. Override via
     * {@code spring.ai.loom.agent.knowledge-base-path}.
     */
    private String knowledgeBasePath = System.getProperty("user.home") + "/.loom/knowledge";
    /**
     * Absolute root for the H2 file database. Set via yml
     * {@code spring.datasource.url} as
     * {@code jdbc:h2:file:${spring.ai.loom-agent.datasource-dir}/db}. Default
     * value is exposed here so consumers can derive the URL if they want to.
     */
    private String datasourceDir = System.getProperty("user.home") + "/.loom/datasource";
    private String gitUsername;
    private String gitToken;

    // 工具启用开关。yml 可通过 spring.ai.loom.agent.{time,file,skill,git,maven,compile}.enabled=false 关闭对应工具。
    // 默认 time/file/skill/compile 开启，git/maven 关闭 —— 编译部署走 ICompileAndDeployTool，
    // 单点 git/maven 命令是 opt-in。
    private ToolGroupProperty time = new ToolGroupProperty();
    private FileToolProperty file = new FileToolProperty();
    private ToolGroupProperty skill = new ToolGroupProperty();
    private GitProperty git = new GitProperty();
    private CompileProperty compile = new CompileProperty();
    /**
     * Sub-task feature (main conversation delegates work to a "sub-model").
     * <p>
     * yml: {@code spring.ai.loom.agent.subtask.*}。
     */
    private SubTaskProperty subtask = new SubTaskProperty();

    /**
     * Scheduled-task feature (LLM creates timers that fire sub-tasks).
     * <p>
     * yml: {@code spring.ai.loom.agent.schedule.*}。Trigger limits (min-interval /
     * max-lifetime) are configured under {@code flex.schedule.limits.*}.
     */
    private ScheduleProperty schedule = new ScheduleProperty();

    @Data
    public static class RagProperty {
        private double similarityThreshold = 0.0F;
        private int topK = 4;
    }

    // McpProperty / ToolProperty / SkillProperty 已删除：
    //   mcp 元数据现由 rbac 包的 IMcpServerAdmin 服务管理（数据库 mcp_server / mcp_tool 表）
    //   skill 现由 skill 包的 ISkillMarketService / ISkillRoleAdmin / ISkillStorage 管理（数据库 market_skill / user_skill / role_skill 表）

    @Data
    public static class JVectorProperties {
        /**
         * HNSW index directory. Defaulted to an absolute path under
         * {@code ~/.loom/jvector-index/} so the vector-store is owned by
         * the user regardless of cwd. Override via
         * {@code spring.ai.loom-agent.jvector.index-path} in yml.
         */
        private String indexPath = System.getProperty("user.home") + "/.loom/jvector-index";
        private int m = 16;
        private int efConstruction = 100;
        private int efSearch = 10;
    }

    @Data
    public static class AuthProperty {
        private boolean enabled = true;
        /**
         * 需要鉴权的路径模式（Ant 风格）。默认只鉴权 API 路径，
         * 静态资源（index.html/app.js/style.css）和用户登录接口不在此列。
         *
         * <p>Includes {@code /wopi/**} and {@code /file/view/**} so the
         * file-view library's wopi / preview routes also flow through
         * {@code AuthenticationFilter}. file-view's own
         * {@code OncePerRequestFilter} uses {@code String.equals} against
         * its config, so its default {@code [/file/view, /wopi]} never
         * matches {@code /file/view/{id}} or {@code /wopi/files/{id}}
         * — authentication falls back to {@code AuthenticationFilter},
         * which uses Ant matching and sets {@code UserContextHolder}.
         * Without this entry, downstream {@code IFileStorage.findById}
         * sees an empty user context (BUG-RBAC-FILE-WOPI surface).
         */
        private List<String> pathPatterns = List.of("/spring/ai/loom/**", "/wopi/**", "/file/view/**");
        private List<String> excludePathPatterns = List.of(
                "/spring/ai/loom/user/login",
                "/spring/ai/loom/user/isAutoLogin",
                "/spring/ai/loom/user/logout",
                "/spring/ai/loom/index.html",
                "/spring/ai/loom/app.js",
                // app.js (line 9) does `import { sanitizeHtml } from './markdown-renderer.js'`,
                // so the module must be reachable without a session cookie; otherwise
                // the very first page load on a logged-out browser fails to import
                // the module and the chat UI is broken.
                "/spring/ai/loom/markdown-renderer.js",
                "/spring/ai/loom/style.css",
                "/spring/ai/loom/login.html",
                "/spring/ai/loom/login.js",
                "/spring/ai/loom/login.css",
                // admin 静态资源（js / css / 字体等）不鉴权，让未登录也能加载前端资源
                "/spring/ai/loom/admin/**/*.js",
                "/spring/ai/loom/admin/**/*.css",
                "/spring/ai/loom/favicon.ico"
        );
        /**
         * 仅管理员可访问的路径模式（Ant 风格）。匹配时除了登录态，
         * 还会校验当前用户 type=ADMIN，否则 302 重定向到 /spring/ai/loom/index.html。
         */
        private List<String> adminPathPatterns = List.of(
                "/spring/ai/loom/admin/**",
                "/spring/ai/loom/user/currentIsAdmin"
        );
        private CookieProperty cookie = new CookieProperty();

        @Data
        public static class CookieProperty {
            private String name = "loom-agent-session";
            private String path = "/";
            private String domain = "";
            private boolean secure = false;
            private String sameSite = "Lax";
            private int maxAge = 86400;
        }
    }

    private AuthProperty auth = new AuthProperty();

    private MavenProperty maven = new MavenProperty();

    @Data
    public static class MavenProperty {
        /**
         * 是否注册 IMavenTool bean。默认 false —— 编译/打包请走 ICompileAndDeployTool。
         * 设 true 时再暴露 6 个 mvn 命令给 LLM。
         */
        private boolean enabled = false;
        /** Maven 安装目录（可选，空则用系统 PATH） */
        private String mavenHome;
        /** 本地仓库路径（可选，空则用默认 ~/.m2） */
        private String localRepository;
        /** 输出最大行数，默认 200 */
        private int maxOutputLines = 200;
        /** 默认超时毫秒数，默认 300000（5 分钟） */
        private long defaultTimeoutMs = 300000L;
    }

    /**
     * 通用工具启用开关。yml 通过 spring.ai.loom.agent.{time,skill}.enabled=false 关闭对应工具。
     * 默认全部为 true。{@code file} 走 {@link FileToolProperty}，有更细的配置。
     */
    @Data
    public static class ToolGroupProperty {
        private boolean enabled = true;
    }

    /**
     * 文件工具配置。yml 通过 {@code spring.ai.loom.agent.file.*} 配置。
     * <p>
     * 默认值针对 LLM 工具调用场景做了保守约束：
     * <ul>
     *   <li>{@code maxFileSize} = 5MB — 超过则拒绝读取 / 写入（防 OOM + 防 LLM context 溢出）</li>
     *   <li>{@code maxMediaSize} = 1MB — 媒体文件 base64 后体积翻倍，更严</li>
     *   <li>{@code maxWalkDepth} = 5 — 目录树 / 搜索递归上限</li>
     *   <li>{@code maxWalkEntries} = 1000 — 单次列出 / 树节点上限</li>
     *   <li>{@code excludedDirs} — 默认排除常见大目录（{@code .git}/{@code node_modules}/
     *       {@code target}/{@code build}/{@code dist}/...）</li>
     *   <li>{@code maxSearchResults} = 500 — {@code searchFiles} 命中上限</li>
     *   <li>{@code deleteConfirmToken} = {@code I_CONFIRM_DELETE} —
     *       {@code deleteFileOrDirectory} 必须传这个字符串才执行（防 LLM 误删）</li>
     * </ul>
     */
    @Data
    public static class FileToolProperty {
        private boolean enabled = true;
        /** 单次读取 / 写入文件大小上限（字节）。超过返回错误而非抛 OOM。 */
        private long maxFileSize = 5L * 1024 * 1024;
        /** 媒体文件（图片 / 音频）大小上限。base64 后体积 ≈ 4/3，更严。 */
        private long maxMediaSize = 1L * 1024 * 1024;
        /** 目录树 / 递归列出 / 搜索的深度上限。 */
        private int maxWalkDepth = 5;
        /** 单次目录遍历返回的条目数上限（防 LLM context 溢出）。 */
        private int maxWalkEntries = 1000;
        /** 单次 searchFiles 返回的命中数上限。 */
        private int maxSearchResults = 500;
        /** 删除操作必须传的确认字符串（防 LLM 误删 + 误传 confirm="Y"）。 */
        private String deleteConfirmToken = "I_CONFIRM_DELETE";
        /**
         * 目录遍历时跳过的目录名（精确匹配，非 glob）。Spring Boot 项目的
         * {@code target/}、前端的 {@code node_modules/}、仓库的 {@code .git/}
         * 会让单次列表爆掉，默认排除。
         */
        private List<String> excludedDirs = List.of(
                ".git", "node_modules", "target", "build", "dist",
                ".idea", ".vscode", ".gradle", "out", "bin");
    }

    /**
     * Git 工具配置。yml 通过 spring.ai.loom.agent.git.{enabled,username,token,remoteTimeoutSeconds} 配置。
     * 为保持向后兼容，旧的顶层字段 gitUsername / gitToken 仍然可用。
     */
    @Data
    public static class GitProperty {
        /**
         * 是否注册 IGitTool bean。默认 false —— 端到端部署请走 ICompileAndDeployTool。
         * 设 true 时再暴露 31 个 git 命令给 LLM（适合需要 git status/log/blame/branch 等单点操作的场景）。
         */
        private boolean enabled = false;
        private String username;
        private String token;
        /**
         * 远程操作（clone / pull / fetch / push）底层 transport 的超时秒数。
         * 默认 60s。设得过短可能误杀慢仓库；设得过长会让卡死的连接占住 SSE 链路。
         */
        private int remoteTimeoutSeconds = 60;
    }

    /**
     * 一站式编译部署工具配置。
     * <p>
     * yml 通过 {@code spring.ai.loom.agent.compile.*} 配置（仅运维参数）：
     * <ul>
     *   <li>{@code enabled} — 是否启用该工具（默认 true）</li>
     *   <li>{@code mavenHome} — 可选；不配则复用 {@code MavenProperty#getMavenHome()}，
     *       再不行就环境变量自动探测</li>
     *   <li>{@code dockerCmd} — 可选；不配则用 PATH 上的 {@code docker}</li>
     *   <li>{@code mavenTimeoutMs} — maven 编译超时（默认 600000 = 10 分钟）</li>
     *   <li>{@code dockerBuildTimeoutMs} — docker build 超时（默认 600000）</li>
     *   <li>{@code dockerRunTimeoutMs} — docker run 启动等待超时（默认 60000）</li>
     *   <li>{@code healthCheckMaxWaitMs} — 容器启动后健康检查总等待（默认 60000）</li>
     *   <li>{@code healthCheckIntervalMs} — 健康检查轮询间隔（默认 2000）</li>
     *   <li>{@code keepWorkspace} — 是否保留工作区目录（默认 false）</li>
     *   <li>{@code extraRunArgs} — {@code docker run} 额外参数（默认空）</li>
     *   <li>{@code imageTemplates} — 预置基础镜像模板（key=别名，value=ImageTemplate），
     *       工具入参 {@code baseImage} 命中 key 时使用；缺省回退到 {@code java17}</li>
     * </ul>
     * <p>
     * 业务参数（{@code port}、{@code containerPort}、{@code subDir}、{@code healthPath}、
     * {@code baseImage}、{@code runCommand}）一律从对话给到 AI，不在 yml 中配置。
     */
    @Data
    public static class CompileProperty {
        private boolean enabled = true;
        private String mavenHome;
        private String dockerCmd;
        private long mavenTimeoutMs = 600000L;
        private long dockerBuildTimeoutMs = 600000L;
        private long dockerRunTimeoutMs = 60000L;
        private long healthCheckMaxWaitMs = 60000L;
        private long healthCheckIntervalMs = 2000L;
        private boolean keepWorkspace = false;
        /**
         * 注入到 {@code docker run} 命令的额外参数，例如 {@code ["--network=host", "-e", "TZ=Asia/Shanghai"]}。
         * 顺序敏感，会被插在 {@code -d -p ... --name ...} 之后、镜像名之前。
         */
        private List<String> extraRunArgs = new ArrayList<>();
        /**
         * 预置基础镜像模板。key 是模板别名（如 "java17" / "nginx"），
         * value 是 {@link ImageTemplate{image, command}}。
         * 工具入参 baseImage 传别名时匹配这里的 key；
         * 传完整镜像名时等同直接覆盖 FROM，command 走 java17 模板兜底。
         */
        private Map<String, ImageTemplate> imageTemplates = new LinkedHashMap<>(Map.of(
                "java17",      new ImageTemplate("eclipse-temurin:17-jre-alpine",
                                                 List.of("java", "-jar", "app.jar")),
                "java21",      new ImageTemplate("eclipse-temurin:21-jre-alpine",
                                                 List.of("java", "-jar", "app.jar")),
                "nginx",       new ImageTemplate("nginx:1.27-alpine",
                                                 List.of("nginx", "-g", "daemon off;")),
                "python3",     new ImageTemplate("python:3.12-slim",
                                                 List.of("python", "app.py")),
                "node20",      new ImageTemplate("node:20-alpine",
                                                 List.of("node", "dist/index.js")),
                "node20-serve", new ImageTemplate("nginx:1.27-alpine",
                                                  List.of("nginx", "-g", "daemon off;"))
        ));

        /**
         * 预置基础镜像模板：完整镜像名 + exec 形式启动命令。
         */
        @Data
        @lombok.AllArgsConstructor
        @lombok.NoArgsConstructor
        public static class ImageTemplate {
            /** 完整镜像名（含 tag）。 */
            private String image;
            /** exec 形式启动命令，会被序列化为 Dockerfile 的 ENTRYPOINT JSON 数组。 */
            private List<String> command;
        }
    }

    /**
     * 子任务功能配置。yml 通过 {@code spring.ai.loom.agent.subtask.*} 配置。
     * <ul>
     *   <li>{@code enabled} — 是否注册子任务相关 bean（默认 true）</li>
     *   <li>{@code maxConcurrent} — 同时在飞子任务数上限；超过则启动请求被拒（默认 4）</li>
     *   <li>{@code maxHistory} — 每用户历史保留条数；超出 FIFO 丢弃最旧的（默认 200）</li>
     * </ul>
     */
    @Data
    public static class SubTaskProperty {
        private boolean enabled = true;
        private int maxConcurrent = 4;
        private int maxHistory = 200;
    }

    /**
     * 定时任务功能配置。yml 通过 {@code spring.ai.loom.agent.schedule.*} 配置。
     * <ul>
     *   <li>{@code enabled} — 是否注册定时任务相关 bean（默认 true）</li>
     * </ul>
     * 触发间隔/存活上限由 flex-schedule 的 {@code flex.schedule.limits.*} 强校验。
     */
    @Data
    public static class ScheduleProperty {
        private boolean enabled = true;
    }
}
