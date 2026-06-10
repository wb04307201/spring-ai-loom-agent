package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.CompileProperty;
import cn.wubo.spring.ai.loom.agent.tool.maven.MavenHomeResolver;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link ICompileAndDeployTool} 的默认实现：克隆 + Maven 打包 + Docker 构建 + 容器启动 + 健康检查。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>每次调用在用户文件目录下创建 {@code compile-deploy-<uuid>/} 唯一工作区，
 *       隔离多次调用；</li>
 *   <li>JGit 克隆采用 try-with-resources 立即关闭 pack 句柄，
 *       规避 Windows 上 {@code .git/objects/pack} 文件锁；</li>
 *   <li>所有子进程（mvn / docker）均通过 {@link ProcessBuilder} 启，
 *       守护线程消费 stdout/stderr 防止管道写满阻塞；超时用 taskkill /F /T 杀整棵树；</li>
 *   <li>同名容器已存在时先 {@code docker rm -f} 清理；镜像构建失败时直接返回错误不强行启动；</li>
 *   <li>容器启动后做 HTTP GET 探活，未在 {@code healthCheckMaxWaitMs} 内就绪
 *       视为失败（容器保留供排障，accessUrl 仍返回给用户便于手动确认）。</li>
 * </ul>
 */
public class DefaultCompileAndDeployTool implements ICompileAndDeployTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultCompileAndDeployTool.class);

    /** 用户文件根目录相对路径前缀，与其他工具保持一致 */
    private static final String DEFAULT_FILE_BASE_PATH = ".local/file";
    /** 编译部署工作区子目录名 */
    private static final String WORKSPACE_SUBDIR = "compile-deploy";

    private final CompileProperty compile;
    private final String resolvedMavenHome;
    private final String fileBasePath;

    /**
     * 工具内部使用的极宽松 JSON 解析器。
     * <p>
     * LLM（特别是 qwen 系列）在工具调用时输出的 JSON 时常带 JS 风格注释
     * （单行 //、块注释 星号斜杠）或未平衡注释、未平衡引号。
     * Spring AI 内部 Jackson 配了 {@code ALLOW_COMMENTS} 仍会在遇到
     * 块注释起始符没有对应结束符时崩溃 —— 因此本工具自己维护一份
     * 容错解析器。
     */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    public DefaultCompileAndDeployTool(LoomAgentProperties properties) {
        this(properties.getCompile(), properties.getMaven() != null ? properties.getMaven().getMavenHome() : null,
                properties.getFileBasePath());
    }

    /** 供测试直接注入 */
    DefaultCompileAndDeployTool(CompileProperty compile, String mavenHome, String fileBasePath) {
        this.compile = compile;
        this.fileBasePath = (fileBasePath != null && !fileBasePath.isBlank()) ? fileBasePath : DEFAULT_FILE_BASE_PATH;
        // 编译工具的 mavenHome 优先于全局 maven.mavenHome —— 这是项目特定的偏好
        String configured = compile != null ? compile.getMavenHome() : mavenHome;
        this.resolvedMavenHome = MavenHomeResolver.resolve(configured);
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, fileBasePath={}",
                compile != null && compile.isEnabled(), resolvedMavenHome, this.fileBasePath);
    }

    // ==================== Tool Entry ====================

    @Override
    public CompileAndDeployResult compileAndDeploy(Map<String, Object> params, ToolContext toolContext) {
        List<String> steps = new ArrayList<>();
        long startMs = System.currentTimeMillis();
        String username = username(toolContext);

        // 兜底：LLM 偶尔会把参数塞在一个嵌套对象里，再做一次展平
        Map<String, Object> flat = flatten(params);

        String gitUrl = str(flat, "gitUrl", "git_url", "url");
        String gitUsername = str(flat, "gitUsername", "git_username", "username", "user");
        String gitPassword = str(flat, "gitPassword", "git_password", "password", "token");
        String branch = str(flat, "branch", "ref");
        Integer port = intOrNull(flat, "port");
        Integer containerPort = intOrNull(flat, "containerPort", "container_port");
        String imageName = str(flat, "imageName", "image_name", "image");
        String containerName = str(flat, "containerName", "container_name", "container");
        String healthPath = str(flat, "healthPath", "health_path");

        if (gitUrl == null || gitUrl.isBlank()) {
            return CompileAndDeployResult.fail(null, null, null, null, null, null, steps,
                    "参数错误：gitUrl 不能为空，请向用户索取仓库 URL");
        }
        if (port == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "参数错误：port 不能为空，请向用户索取宿主机端口（对外暴露的端口）");
        }
        if (containerPort == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "参数错误：containerPort 不能为空，请向用户索取应用监听端口（参考 application.yml 的 server.port）");
        }
        if (username == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "无法获取用户名，请通过登录态调用");
        }

        // 上面已 fail-fast，port/containerPort 必非 null；effectiveContainerPort 暂未使用，Task 4/5 接入
        int effectivePort = port;
        int effectiveContainerPort = containerPort;
        String workspaceName = WORKSPACE_SUBDIR + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path workspace = getUserFileDir(username).resolve(workspaceName);
        String repoName = deriveRepoName(gitUrl);
        Path projectDir = workspace.resolve(repoName);
        // 解析 subDir —— 多模块仓显式选子模块
        String subDir = str(flat, "subDir", "sub_dir", "module", "submodule");
        String effectiveImage = (imageName != null && !imageName.isBlank())
                ? imageName : ("compile-deploy-" + Long.toString(System.currentTimeMillis(), 36));
        String effectiveContainer = (containerName != null && !containerName.isBlank())
                ? containerName : effectiveImage;
        String effectiveHealthPath = (healthPath != null && !healthPath.isBlank()) ? healthPath : "/";

        // 解析 baseImage + runCommand —— 模板化核心
        String paramBaseImage = str(flat, "baseImage", "base_image");
        List<String> paramRunCommand = listStr(flat, "runCommand", "run_command", "command");
        ResolvedImage resolvedImage = resolveBaseImage(paramBaseImage, paramRunCommand);
        steps.add("✅ 镜像模板：" + (resolvedImage.alias() != null
                ? resolvedImage.alias() + " (" + resolvedImage.image() + ")"
                : resolvedImage.image()) + " | ENTRYPOINT=" + String.join(" ", resolvedImage.command()));

        try {
            Files.createDirectories(workspace);

            // Step 1: clone
            boolean cloned = cloneRepo(gitUrl, projectDir, branch, gitUsername, gitPassword);
            if (!cloned) {
                steps.add("❌ 克隆：" + gitUrl);
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "git clone 失败");
            }
            steps.add("✅ 克隆：" + projectDir);

            // Step 2: mvn package
            // 多模块项目根目录无 pom.xml —— 解析出真正的工作目录
            Path effectiveDir;
            try {
                effectiveDir = resolveEffectiveProjectDir(projectDir, repoName, subDir);
            } catch (IllegalArgumentException subErr) {
                steps.add("❌ 子模块解析失败：" + subErr.getMessage());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps,
                        subErr.getMessage());
            }
            if (!effectiveDir.equals(projectDir)) {
                log.info("Multi-module layout detected. projectDir={}, effectiveDir={}", projectDir, effectiveDir);
            }
            String mvnLog = mavenPackage(effectiveDir);
            if (mvnLog == null) {
                steps.add("❌ 编译：mvn clean package -DskipTests");
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "Maven 编译失败");
            }
            steps.add("✅ 编译：mvn clean package");

            // Step 3: pick the Spring Boot jar (or any executable jar) —— 在 effectiveDir 下找
            File jar = findBuiltJar(effectiveDir);
            if (jar == null) {
                steps.add("❌ 查找 jar：未在 target/ 下找到可执行 jar");
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "target/ 下未找到 jar");
            }
            steps.add("✅ 产物：" + jar.getName());

            // Step 4: write Dockerfile —— 必须写到 effectiveDir，否则 COPY target/ 路径对不上
            // EXPOSE 端口用 effectiveContainerPort（容器内应用端口），与 effectivePort（宿主机对外端口）解耦
            File dockerfile = writeDockerfile(effectiveDir, jar, resolvedImage, effectiveContainerPort);
            steps.add("✅ Dockerfile：" + dockerfile.getName());

            // Step 5: docker build —— 必须在 effectiveDir 下执行，构建上下文才能找到 target/
            String builtImage;
            try {
                builtImage = dockerBuild(effectiveDir, effectiveImage, resolvedImage);
            } catch (DockerBuildException e) {
                steps.add("❌ Docker 构建失败（image=" + resolvedImage.image()
                        + ", alias=" + (resolvedImage.alias() == null ? "<none>" : resolvedImage.alias())
                        + "），详见 errorMessage");
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, e.getMessage());
            }
            steps.add("✅ Docker 镜像：" + builtImage);

            // Step 6: docker run
            String runningContainer = dockerRun(effectiveImage, effectiveContainer, effectivePort, effectiveContainerPort);
            if (runningContainer == null) {
                steps.add("❌ Docker 启动：" + effectiveContainer);
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "docker run 失败");
            }
            steps.add("✅ Docker 容器：" + runningContainer + " (port " + effectivePort + ")");

            // Step 7: health check
            boolean healthy = waitForHealthy(effectivePort, effectiveHealthPath);
            String accessUrl = buildAccessUrl(effectivePort, effectiveHealthPath);
            if (!healthy) {
                steps.add("⚠ 健康检查未在 " + compile.getHealthCheckMaxWaitMs() + "ms 内通过，"
                        + "容器已保留，可手动访问 " + accessUrl + effectiveHealthPath);
                log.warn("Health check timeout. port={}, path={}", effectivePort, effectiveHealthPath);
            } else {
                steps.add("✅ 健康检查通过：" + accessUrl + effectiveHealthPath);
            }

            log.info("compileAndDeploy done. elapsed={}ms, workspace={}", System.currentTimeMillis() - startMs, workspace);
            return CompileAndDeployResult.ok(workspace.toString(), gitUrl, branch, effectiveImage,
                    effectiveContainer, effectivePort, accessUrl, effectiveHealthPath, steps);
        } catch (Exception e) {
            log.error("compileAndDeploy unexpected failure. workspace={}", workspace, e);
            steps.add("❌ 异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                    effectiveContainer, effectivePort, effectiveHealthPath, steps,
                    "工具内部异常: " + e.getMessage());
        }
    }

    // ==================== Step Implementations ====================

    /**
     * JGit 克隆。采用 try-with-resources 立即关闭 pack 句柄，
     * 避免 Windows 上 {@code .git/objects/pack} 文件锁。
     */
    private boolean cloneRepo(String gitUrl, Path projectDir, String branch,
                              String username, String password) {
        try {
            if (Files.exists(projectDir)) {
                log.warn("cloneRepo target already exists, removing: {}", projectDir);
                deleteRecursively(projectDir);
            }
            Files.createDirectories(projectDir.getParent());

            CloneCommand cmd = Git.cloneRepository()
                    .setURI(gitUrl)
                    .setDirectory(projectDir.toFile())
                    .setTimeout(60);
            // 仅在 branch 看起来是合法 git ref 时才设置 —— 防御 LLM 把仓库名当分支传
            if (isPlausibleBranch(branch, deriveRepoName(gitUrl))) {
                cmd.setBranch(branch);
            } else if (branch != null && !branch.isBlank()) {
                log.warn("Ignoring implausible branch '{}' (repo={}); letting JGit pick default HEAD",
                        branch, deriveRepoName(gitUrl));
            }
            CredentialsProvider cp = buildCredentialsProvider(username, password);
            if (cp != null) cmd.setCredentialsProvider(cp);

            try (Git ignored = cmd.call()) {
                // try-with-resources ensures repo is closed, releasing pack file handles
            }
            return Files.isDirectory(projectDir.resolve(".git"));
        } catch (Exception e) {
            log.error("git clone failed. url={}, target={}", gitUrl, projectDir, e);
            return false;
        }
    }

    /**
     * 校验 LLM 传过来的 branch 字段是否可信。
     * <p>
     * 防御 LLM 的两类典型错误：
     * <ul>
     *   <li>把仓库名当成分支（{@code sql-forge-demo}）—— JGit 报
     *       "Remote branch 'xxx' not found"</li>
     *   <li>把 URL 当成分支（{@code gitee.com/xxx/demo.git}）</li>
     * </ul>
     * 规则：必须以字母/数字/下划线/点/连字符开头，不能含路径分隔符、不能等于仓库名。
     */
    private static boolean isPlausibleBranch(String branch, String repoName) {
        if (branch == null || branch.isBlank()) return false;
        String b = branch.trim();
        if (b.equalsIgnoreCase(repoName)) return false;
        if (b.contains("/") || b.contains("\\")) return false;
        if (b.endsWith(".git")) return false;
        // 常见合法前缀
        return b.matches("[A-Za-z0-9_./-]+");
    }

    /**
     * mvn clean package -DskipTests。
     * 直接走 ProcessBuilder 避开 maven-invoker 的 Windows 句柄泄漏问题。
     * <p>
     * 多模块项目（如 gitee.com/xxx/demo，包含 demo-api、demo-web 等子模块），
     * 仓库根目录往往没有 pom.xml。这种情况下走"找到第一个含 pom.xml 的子目录
     * 作为工作目录"的兜底策略；构建产物同样会落到子目录的 target/ 下。
     */
    private String mavenPackage(Path projectDir) {
        // 解析出真正要执行 mvn 的目录和 pom.xml
        Path[] effective = resolveMavenTarget(projectDir);
        Path effectiveDir = effective[0];
        Path pom = effective[1];
        // mvn -f 的路径解析以 *workDir* 为基准 —— 用相对路径避免绝对路径
        // 在 workDir 之外的"找不到文件"问题。
        Path pomArg = effectiveDir.relativize(pom);
        log.info("Maven target resolved. dir={}, pom={} (mvn -f arg={})", effectiveDir, pom, pomArg);

        List<String> mvnArgs = new ArrayList<>();
        mvnArgs.add("-B");
        mvnArgs.add("-e");
        mvnArgs.add("-f");
        mvnArgs.add(pomArg.toString());
        mvnArgs.add("clean");
        mvnArgs.add("package");
        mvnArgs.add("-DskipTests");

        List<String> cmd = mavenCommand(mvnArgs);
        if (cmd == null) {
            log.error("Maven executable not found. resolvedMavenHome={}", resolvedMavenHome);
            return null;
        }

        ExecOutcome out = runProcess(cmd, effectiveDir.toFile(), compile.getMavenTimeoutMs());
        if (out.timeout) {
            log.error("mvn package timed out after {}ms", compile.getMavenTimeoutMs());
            return null;
        }
        if (out.exitCode != 0) {
            log.error("mvn package failed. exitCode={}, outputLen={}, tail=\n{}",
                    out.exitCode, out.output == null ? 0 : out.output.length(), tail(out.output, 60));
            return null;
        }
        return out.output;
    }

    /**
     * 找到真正要执行 mvn 的目录和 pom.xml。
     * <p>
     * 策略：
     * <ol>
     *   <li>如果 {@code projectDir/pom.xml} 存在，返回 ({@code projectDir}, pom)</li>
     *   <li>否则遍历 {@code projectDir} 的一层子目录，
     *       第一个含 {@code pom.xml} 的子目录作为工作目录</li>
     *   <li>若都找不到，返回原始 projectDir 让 mvn 自己报错</li>
     * </ol>
     * <p>
     * 注意：故意不递归更深的层级。多模块项目的约定是"根目录 + 一层子模块"，
     * 递归查找容易误中 utility/lib 之类的子模块。
     */
    private Path[] resolveMavenTarget(Path projectDir) {
        Path rootPom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            return new Path[]{projectDir, rootPom};
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir)) {
            // 按目录名排序，让结果稳定可复现（用户在日志里能看出挑了哪个）
            List<Path> children = new ArrayList<>();
            for (Path c : stream) {
                if (Files.isDirectory(c)) children.add(c);
            }
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path child : children) {
                if (Files.isRegularFile(child.resolve("pom.xml"))) {
                    return new Path[]{child, child.resolve("pom.xml")};
                }
            }
        } catch (IOException ignored) {
        }
        return new Path[]{projectDir, rootPom};
    }

    /**
     * 解析出整个构建流程真正要操作的目录（mvn / 写 Dockerfile / docker build 都用这个）。
     * <p>
     * 多模块项目仓库根目录往往没有 pom.xml（也没有可执行 jar），
     * 而第一层子目录里才是真正的 Spring Boot 工程。
     * 这一步选错子模块会导致 COPY target/xxx.jar 找不到文件、镜像构建失败。
     * <p>
     * 规则（按顺序）：
     * <ol>
     *   <li>{@code subDir} 非空 → 校验 {@code <projectDir>/<subDir>/pom.xml} 存在；
     *       存在返回该子目录；不存在抛 {@link IllegalArgumentException}，
     *       errorMessage 列出实际候选子目录，让 LLM 追问</li>
     *   <li>{@code subDir} 缺省 + {@code projectDir/pom.xml} 存在 → 单模块，返回 projectDir</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式名字匹配（子目录名 = repoName）唯一 → 选该子目录</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式无匹配 + 子目录数 = 1 → 兜底取该子目录</li>
     *   <li>{@code subDir} 缺省 + 根无 pom.xml + 启发式无匹配 + 子目录数 ≥ 2 →
     *       抛 {@link IllegalArgumentException}，errorMessage 列出候选子目录，让 LLM 追问</li>
     * </ol>
     */
    Path resolveEffectiveProjectDir(Path projectDir, String repoName, String subDir) {
        // 规则 1：subDir 显式
        if (subDir != null && !subDir.isBlank()) {
            Path target = projectDir.resolve(subDir);
            if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("pom.xml"))) {
                log.info("resolveEffectiveProjectDir: subDir='{}' picked", subDir);
                return target;
            }
            List<Path> candidates = listChildDirs(projectDir);
            String names = formatNames(candidates);
            throw new IllegalArgumentException(
                    "参数错误：subDir='" + subDir + "' 在克隆后的仓库中不存在，可选子目录：" + names
                            + "，请向用户确认");
        }

        // 规则 2：单模块
        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return projectDir;
        }

        // 规则 3-5：多模块启发式
        List<Path> children = listChildDirs(projectDir);
        if (children.isEmpty()) {
            return projectDir;
        }
        // 规则 3：名字匹配
        if (repoName != null && !repoName.isBlank()) {
            for (Path c : children) {
                if (c.getFileName().toString().equals(repoName)) {
                    log.info("resolveEffectiveProjectDir: picked submodule matching repo name. submodule={}", c);
                    return c;
                }
            }
        }
        // 规则 4 vs 5：单子目录兜底 vs 多子目录歧义
        if (children.size() == 1) {
            Path only = children.get(0);
            log.info("resolveEffectiveProjectDir: only one submodule, falling back to {}", only.getFileName());
            return only;
        }
        // 规则 5：歧义
        String names = formatNames(children);
        throw new IllegalArgumentException(
                "参数错误：仓库根目录无 pom.xml，且多个子模块无法自动选择，可选子目录：" + names
                        + "，请向用户确认要部署哪个");
    }

    private static String formatNames(List<Path> paths) {
        List<String> names = new ArrayList<>();
        for (Path p : paths) {
            names.add(p.getFileName().toString());
        }
        return names.toString();
    }

    private static List<Path> listChildDirs(Path parent) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path c : stream) {
                if (Files.isDirectory(c) && !c.getFileName().toString().startsWith(".")
                        && !c.getFileName().toString().equals("target")
                        && !c.getFileName().toString().equals("node_modules")) {
                    out.add(c);
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /**
     * 在指定目录的 {@code target/} 下查找 Spring Boot fat jar
     * （包含所有依赖的可执行 jar），并跳过 repackage 前的原始 jar。
     * <p>
     * 调用方应当传入 {@link #resolveEffectiveProjectDir} 解析出的目录，
     * 多模块项目这样 jar 才会落在 {@code target/} 下。
     */
    File findBuiltJar(Path effectiveDir) {
        Path target = effectiveDir.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        return pickJarFromTarget(target);
    }

    private File pickJarFromTarget(Path target) {
        File dir = target.toFile();
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar") && !name.endsWith(".original.jar")
                && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
        if (jars == null || jars.length == 0) return null;
        // 取体积最大的（Spring Boot repackage jar 一定比普通 jar 大）
        Arrays.sort(jars, Comparator.comparingLong(File::length).reversed());
        return jars[0];
    }

    /**
     * 在 projectDir 下生成 Dockerfile。FROM/ENTRYPOINT 来自 {@link ResolvedImage}。
     * <p>
     * command 序列化为 JSON 数组（Dockerfile exec 形式）：
     * {@code ["java", "-jar", "app.jar"]} 或 {@code ["nginx", "-g", "daemon off;"]}。
     */
    File writeDockerfile(Path projectDir, File jar, ResolvedImage resolved, int containerPort) {
        File dockerfile = projectDir.resolve("Dockerfile").toFile();
        String entrypoint = toJsonArray(resolved.command());
        String content = String.format("""
                # Auto-generated by DefaultCompileAndDeployTool
                FROM %s
                WORKDIR /app
                COPY target/%s app.jar
                EXPOSE %d
                ENTRYPOINT %s
                """, resolved.image(), jar.getName(), containerPort, entrypoint);
        try {
            Files.writeString(dockerfile.toPath(), content, StandardCharsets.UTF_8);
            return dockerfile;
        } catch (IOException e) {
            throw new RuntimeException("写 Dockerfile 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把 {@code ["java", "-jar", "app.jar"]} 序列化成 Dockerfile exec 形式（JSON 数组）。
     * <p>
     * 复用类内已存在的 {@link #LENIENT_MAPPER} 序列化，不再手写转义逻辑。
     * 失败时抛 {@link IllegalStateException}（writeDockerfile 会包装成 RuntimeException）。
     */
    static String toJsonArray(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("ResolvedImage.command must not be empty");
        }
        try {
            return LENIENT_MAPPER.writeValueAsString(parts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ENTRYPOINT to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * {@code docker build -t <image> <projectDir>}。失败时返回 null，并通过抛出
     * {@link DockerBuildException} 把详细错误（exitCode/timeout/输出尾部）传给上层。
     */
    private String dockerBuild(Path projectDir, String imageName, ResolvedImage resolved) {
        String docker = resolveDockerCmd();
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add("-t");
        args.add(imageName);
        args.add(".");
        List<String> cmd = wrapForWindows(docker, args);
        ExecOutcome out = runProcess(cmd, projectDir.toFile(), compile.getDockerBuildTimeoutMs());
        if (out.timeout || out.exitCode != 0) {
            String tailOutput = tail(out.output, 100);
            String msg = String.format(
                    "docker build 失败（exitCode=%d, timeout=%s, image=%s, alias=%s）\n--- last 100 lines of build output ---\n%s",
                    out.exitCode, out.timeout, resolved.image(),
                    resolved.alias() == null ? "<none>" : resolved.alias(), tailOutput);
            log.error(msg);
            throw new DockerBuildException(msg);
        }
        return imageName;
    }

    /** {@link #dockerBuild} 失败时抛出的异常，message 即 {@code errorMessage} 内容。 */
    static class DockerBuildException extends RuntimeException {
        DockerBuildException(String message) { super(message); }
    }

    /**
     * {@code docker rm -f <name> ; docker run -d -p <port>:<containerPort> --name <name> <image> [extraArgs]}。
     * 同名容器已存在时先清理。
     * <p>
     * 故意不加 {@code -e SERVER_PORT=...} —— 仓里 application.yml 的 server.port 原样生效；
     * 用户应在对话里告诉 LLM "server.port 是 X"，LLM 再传 containerPort=X。
     */
    private String dockerRun(String imageName, String containerName, int port, int containerPort) {
        String docker = resolveDockerCmd();
        // 先清理同名容器（force remove）
        List<String> rmArgs = new ArrayList<>();
        rmArgs.add("rm");
        rmArgs.add("-f");
        rmArgs.add(containerName);
        // 不关心 exitCode —— 不存在时也会非 0
        runProcess(wrapForWindows(docker, rmArgs), null, 15_000L);

        List<String> runArgs = buildDockerRunCommand(imageName, containerName, port, containerPort,
                compile.getExtraRunArgs());
        List<String> cmd = wrapForWindows(docker, runArgs);
        ExecOutcome out = runProcess(cmd, null, compile.getDockerRunTimeoutMs());
        if (out.timeout || out.exitCode != 0) {
            log.error("docker run failed. exitCode={}, timeout={}, tail=\n{}",
                    out.exitCode, out.timeout, tail(out.output, 60));
            return null;
        }
        return containerName;
    }

    /**
     * 构造 {@code docker run} 的参数列表（不含 docker 本身），便于测试断言。
     * 暴露为 package-private 静态是因为单元测试需要拿到 list 校验。
     */
    static List<String> buildDockerRunCommand(String imageName, String containerName, int port,
                                              int containerPort, List<String> extraRunArgs) {
        List<String> runArgs = new ArrayList<>();
        runArgs.add("run");
        runArgs.add("-d");
        runArgs.add("-p");
        runArgs.add(port + ":" + containerPort);
        runArgs.add("--name");
        runArgs.add(containerName);
        if (extraRunArgs != null && !extraRunArgs.isEmpty()) {
            runArgs.addAll(extraRunArgs);
        }
        runArgs.add(imageName);
        return runArgs;
    }

    /**
     * 轮询 {@code GET http://localhost:<port><healthPath>}，
     * 直到 2xx / 3xx / 404 / connection refused-后-成功 中之一出现。
     * <p>
     * 4xx 也算"端口在监听、应用已就绪"——避免应用没有根路径时误判超时。
     */
    private boolean waitForHealthy(int port, String healthPath) {
        long deadline = System.currentTimeMillis() + compile.getHealthCheckMaxWaitMs();
        String urlStr = buildAccessUrl(port, healthPath);
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                int code;
                try {
                    code = conn.getResponseCode();
                } catch (IOException connectRefused) {
                    // 端口还没起来，正常
                    conn.disconnect();
                    sleep(compile.getHealthCheckIntervalMs());
                    continue;
                }
                conn.disconnect();
                if (code >= 200 && code < 500) {
                    // 2xx/3xx/4xx 全部视作"在监听、应用已起来"
                    return true;
                }
            } catch (Exception e) {
                log.debug("health check iteration failed. url={}, err={}", urlStr, e.getMessage());
            }
            sleep(compile.getHealthCheckIntervalMs());
        }
        return false;
    }

    // ==================== Process Helpers ====================

    /**
     * 启动子进程 + 守护线程消费 stdout/stderr + 超时杀整棵进程树。
     * 任何异常都不能逃出方法 —— 失败一律返回 exitCode=-1。
     * <p>
     * <b>Windows 下的命令构造坑：</b>
     * <p>
     * 用 {@code ProcessBuilder} 把 {@code ["cmd.exe", "/c", "mvn.cmd", "-B", ...]}
     * 序列化到 Windows 命令行时，Java 会按 Windows 规则把列表拼成字符串；
     * 但 {@code cmd.exe} 解析 {@code /c} 后面的内容用自己的一套规则，
     * 两边在路径含空格/特殊字符时经常对不上，导致 cmd.exe 立即以 code=1
     * 退出、什么也不输出。
     * <p>
     * 解决：当命令行包含 {@code cmd.exe /c} 时，用 {@code Runtime.exec(String)}
     * 走单字符串路径，绕开 Java 的转义，让 cmd.exe 自己解析。
     */
    private ExecOutcome runProcess(List<String> cmd, File workDir, long timeoutMs) {
        long start = System.currentTimeMillis();
        Process process = null;
        long pid = -1;
        StringBuilder output = new StringBuilder();
        Thread stdoutThread = null;
        boolean finished = false;
        boolean timeoutHit = false;
        int exitCode = -1;
        log.info("runProcess start. cmd={}, workDir={}, timeoutMs={}", cmd, workDir, timeoutMs);
        try {
            process = startProcess(cmd, workDir);
            try {
                pid = process.pid();
            } catch (Throwable ignored) {
            }
            log.info("runProcess started. pid={}", pid);
            // 合并 stderr 到 stdout，让 mvn 这种同时打两路输出的工具
            // 能在一条流里看到全部信息，不会丢日志。
            stdoutThread = startStreamPump(process.getInputStream(), output, "stdout");
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            timeoutHit = !finished;
            if (timeoutHit) {
                log.warn("process timeout ({}ms), killing tree. cmd={}", timeoutMs, String.join(" ", cmd));
                killProcessTree(process, pid);
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            joinQuietly(stdoutThread, 5000);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                exitCode = -1;
            }
            log.info("runProcess done. exitCode={}, outputBytes={}, elapsed={}ms",
                    exitCode, output.length(), System.currentTimeMillis() - start);
            if (exitCode != 0) {
                // 调试：把原始输出 dump 到日志，方便定位诡异失败
                log.error("runProcess FULL OUTPUT (exit={}): <<{}>>", exitCode, output);
            }
            return new ExecOutcome(exitCode, output.toString(), timeoutHit);
        } catch (Throwable t) {
            log.error("runProcess failed. cmd={}, err={}", cmd, t.getMessage(), t);
            if (process != null) {
                killProcessTree(process, pid);
            }
            return new ExecOutcome(-1,
                    "启动子进程失败: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                            + "\n--- output ---\n" + output,
                    false);
        }
    }

    /**
     * 启动子进程。
     * <p>
     * <b>Windows 下的 cmd.exe /c 解析坑：</b>
     * <p>
     * 用 {@code ProcessBuilder} 传 {@code ["cmd.exe", "/c", "mvn.cmd", "-B", ...]}
     * 序列化到 Windows 命令行时，Java 用 {@code CommandLineToArgvW} 规则拼字符串；
     * 但 {@code cmd.exe} 解析 {@code /c} 后面的内容用自己的一套规则，
     * 两边在路径含空格/特殊字符时经常对不上，导致 cmd.exe 立即以 code=1
     * 退出、什么也不输出。
     * <p>
     * 解决：把整段命令写到临时 {@code .bat} 文件，直接 ProcessBuilder 启这个 bat。
     * {@code .bat} 文件本身是 cmd.exe 解释执行的标准输入，绕开命令行解析的所有坑。
     * 任务结束后删除临时文件。
     */
    private Process startProcess(List<String> cmd, File workDir) throws IOException {
        boolean isCmdExe = isWindows() && cmd.size() >= 2
                && cmd.get(0).equalsIgnoreCase("cmd.exe")
                && cmd.get(1).equalsIgnoreCase("/c");
        if (isCmdExe) {
            File bat = createTempBatch(cmd.subList(2, cmd.size()));
            try {
                log.debug("startProcess via temp .bat: {}", bat.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder(bat.getAbsolutePath());
                if (workDir != null) pb.directory(workDir);
                pb.redirectErrorStream(true);
                return pb.start();
            } finally {
                bat.deleteOnExit();
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * 把命令行参数写到临时 {@code .bat} 文件。
     * <p>
     * 第一行是 {@code @echo off}，后面每行是 {@code "arg" "arg" ...}，
     * 最后一行是 {@code %*}（展开所有传入参数）。这样从 ProcessBuilder
     * 启动时只传 bat 路径，不传任何参数，bat 自己用 {@code %*} 引用位置参数。
     * <p>
     * 注意：这里只把第 1 个 token（脚本绝对路径）作为 bat 的"程序"，其余 token
     * 是该脚本的位置参数。Windows 上 bat 文件接受外部参数的方式是
     * {@code call script.bat arg1 arg2} —— 所以 bat 内部第一行是
     * {@code @echo off}，第二行是 {@code %~1 %* %~f2 ...} 这种自调结构。
     * <p>
     * 为了最简化：bat 直接调 {@code call "<mvn>" <args...>}：
     * <pre>
     *   @echo off
     *   call "C:\path\mvn.cmd" -B -e -f "C:\path\pom.xml" clean package -DskipTests
     * </pre>
     */
    static File createTempBatch(List<String> args) throws IOException {
        File bat = File.createTempFile("loom-run-", ".bat");
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("chcp 65001 >nul\r\n");
        // 第 0 个参数是要执行的脚本（绝对路径），用 call 调起来
        sb.append("call ");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append('"').append(args.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append("\r\nexit /B %ERRORLEVEL%\r\n");
        Files.writeString(bat.toPath(), sb.toString(), StandardCharsets.UTF_8);
        return bat;
    }

    private record ExecOutcome(int exitCode, String output, boolean timeout) {
    }

    private static Thread startStreamPump(java.io.InputStream in, StringBuilder sink, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t, long millis) {
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private void killProcessTree(Process process, long pid) {
        if (process == null) return;
        try {
            if (isWindows()) {
                List<String> cmd = new ArrayList<>();
                cmd.add("taskkill");
                cmd.add("/F");
                cmd.add("/T");
                if (pid > 0) {
                    cmd.add("/PID");
                    cmd.add(String.valueOf(pid));
                } else {
                    process.destroyForcibly();
                    return;
                }
                Process tk = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                tk.waitFor(10, TimeUnit.SECONDS);
            } else {
                process.destroyForcibly();
            }
        } catch (Throwable t) {
            log.warn("killProcessTree failed (non-fatal). pid={}", pid, t);
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== Path / Maven Helpers ====================

    private static CredentialsProvider buildCredentialsProvider(String username, String password) {
        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(
                username == null ? "" : username,
                password == null ? "" : password);
    }

    private static String resolveDockerCmd() {
        String configured = System.getenv("DOCKER_CMD");
        if (configured != null && !configured.isBlank()) return configured;
        return isWindows() ? "docker" : "docker";
    }

    /**
     * 构造一个能在 {@link ProcessBuilder} 里正常工作的 mvn 命令行。
     * <p>
     * Windows 上 {@code mvn.cmd} / {@code mvn.bat} 是 cmd 批处理文件，
     * 直接用 ProcessBuilder 启会被 Java 当成"可执行文件"——进程在
     * 批处理还没把 java 拉起来就退出了，{@code process.waitFor()}
     * 会立即返回 1，输出也是空的。
     * <p>
     * 解决办法：显式用 {@code cmd.exe /c} 包一层，让 cmd 解释器
     * 真正负责跑完整个批处理。Unix 下 {@code mvn} 是 shell 脚本，
     * 直接传可执行文件路径即可。
     */
    private List<String> mavenCommand(List<String> mvnArgs) {
        File mvnExe = findMavenExecutable();
        if (mvnExe == null) {
            return null;
        }
        return wrapForWindows(mvnExe.getAbsolutePath(), mvnArgs);
    }

    /**
     * Windows 下 cmd 批处理（{@code .cmd} / {@code .bat}）的进程包装。
     * <p>
     * 如果给定可执行文件以 {@code .cmd} / {@code .bat} 结尾，用
     * {@code cmd.exe /c} 包一层返回新命令行；否则原样返回。
     * <p>
     * docker CLI 在 Windows 既有 {@code docker.exe}（无需包装，
     * 比如 Docker Desktop 安装后）也有 {@code docker.bat}（少数
     * 自定义安装），这一层防御让两种情况都能正常工作。
     */
    static List<String> wrapForWindows(String exe, List<String> args) {
        if (isWindows() && (exe.toLowerCase().endsWith(".cmd") || exe.toLowerCase().endsWith(".bat"))) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(exe);
            cmd.addAll(args);
            return cmd;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(args);
        return cmd;
    }

    private File findMavenExecutable() {
        File home = resolvedMavenHome != null ? new File(resolvedMavenHome) : null;
        if (home == null || !home.isDirectory()) return null;
        File bin = new File(home, "bin");
        String[] candidates = isWindows()
                ? new String[]{"mvn.cmd", "mvn.bat"}
                : new String[]{"mvn"};
        for (String c : candidates) {
            File exe = new File(bin, c);
            if (exe.isFile()) return exe;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String deriveRepoName(String gitUrl) {
        // 去掉尾部 / 或 \，取最后一段路径，再去 .git 后缀
        String name = gitUrl;
        while (name.endsWith("/") || name.endsWith("\\")) {
            name = name.substring(0, name.length() - 1);
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        if (name.isBlank()) name = "repo";
        return name;
    }

    /**
     * 把 {@code localhost:port + healthPath} 拼成完整 URL，healthPath 缺前导 / 时自动补。
     * 供 {@code accessUrl} 和 {@link #waitForHealthy} 共用，保证两者一致。
     */
    static String buildAccessUrl(int port, String healthPath) {
        String path = (healthPath == null || healthPath.isEmpty()) ? "/" : healthPath;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return "http://localhost:" + port + suffix;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(String s, int lines) {
        if (s == null || s.isEmpty()) return "";
        String[] arr = s.split("\n");
        int from = Math.max(0, arr.length - lines);
        return String.join("\n", Arrays.copyOfRange(arr, from, arr.length));
    }

    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private static String username(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object u = toolContext.getContext().get("username");
        return u == null ? null : u.toString();
    }

    // ==================== Image Resolution ====================

    /**
     * 解析工具入参 + yml 配置后的最终基础镜像结果。
     *  - alias: 命中的模板别名（如 "java17"），无别名时为 null
     *  - image: 完整镜像名（必填，最终写入 Dockerfile FROM）
     *  - command: exec 形式启动命令（写入 Dockerfile ENTRYPOINT）
     */
    record ResolvedImage(String alias, String image, List<String> command) {}

    /**
     * 解析工具入参 baseImage / runCommand + yml 模板，给出最终镜像配置。
     * <p>
     * 规则：
     * <ol>
     *   <li>入参 baseImage 命中 yml 模板 key → 用模板的 image + command</li>
     *   <li>入参 baseImage 含 {@code :} 或 {@code /}（典型镜像名形式）→ 直接用入参作 image，command 走 java17 兜底</li>
     *   <li>入参 baseImage 为空/null → 用 {@code imageTemplates.java17.image} 兜底，command 走 java17 兜底</li>
     *   <li>入参非空但既不命中模板也不像完整镜像名（防御 LLM 拼错）→ 同分支 3 回退到 java17 模板</li>
     *   <li>入参 runCommand 非空 → 覆盖上面解析出的 command</li>
     * </ol>
     */
    ResolvedImage resolveBaseImage(String paramBaseImage, List<String> paramRunCommand) {
        Map<String, LoomAgentProperties.CompileProperty.ImageTemplate> templates =
                compile != null && compile.getImageTemplates() != null
                        ? compile.getImageTemplates()
                        : Map.of();
        LoomAgentProperties.CompileProperty.ImageTemplate java17Fallback = templates.get("java17");
        if (java17Fallback == null) {
            java17Fallback = new LoomAgentProperties.CompileProperty.ImageTemplate(
                    "eclipse-temurin:17-jre-alpine", List.of("java", "-jar", "app.jar"));
        }

        String alias = null;
        String image;
        List<String> command;
        String fallbackImage = java17Fallback.getImage();

        if (paramBaseImage != null && !paramBaseImage.isBlank()) {
            if (templates.containsKey(paramBaseImage)) {
                // 分支 1：模板别名命中
                LoomAgentProperties.CompileProperty.ImageTemplate tpl = templates.get(paramBaseImage);
                alias = paramBaseImage;
                image = tpl.getImage();
                command = new ArrayList<>(tpl.getCommand());
            } else if (paramBaseImage.contains(":") || paramBaseImage.contains("/")) {
                // 分支 2：完整镜像名
                image = paramBaseImage;
                command = new ArrayList<>(java17Fallback.getCommand());
            } else {
                // 不在模板里也不像完整镜像名（防御 LLM 拼错），回退到 java17 模板
                image = fallbackImage;
                command = new ArrayList<>(java17Fallback.getCommand());
            }
        } else {
            // 分支 3：入参为空
            image = fallbackImage;
            command = new ArrayList<>(java17Fallback.getCommand());
        }

        if (paramRunCommand != null && !paramRunCommand.isEmpty()) {
            // 分支 4：runCommand 覆盖
            command = new ArrayList<>(paramRunCommand);
        }
        return new ResolvedImage(alias, image, command);
    }

    // ==================== Param Parsing Helpers ====================

    /**
     * 把 LLM 给的参数 Map 展平。
     * <p>
     * LLM 偶尔会把所有键塞在一个嵌套对象里（{@code {"compileAndDeploy": {"gitUrl": "..."}}}），
     * 这种情况下 {@code params} 本身是个 Map 但 value 是另一个 Map；
     * Spring AI 的 MethodToolCallback 不会替我们展平。
     * 同时若 {@code params} 实际是 JSON 字符串（LLM 行为），也尝试二次解析。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return Map.of();
        // 1) 嵌套对象：找一个 value 是 Map 的 key 并展平它
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> inner) {
                Map<String, Object> candidate = (Map<String, Object>) inner;
                if (candidate.keySet().stream().anyMatch(k -> k.toString().toLowerCase(Locale.ROOT)
                        .matches("giturl|username|password|port|branch"))) {
                    return candidate;
                }
            }
        }
        // 2) 字符串值：尝试作为 JSON 再解析一次
        for (Object v : params.values()) {
            if (v instanceof String s && s.trim().startsWith("{")) {
                try {
                    JsonParser p = LENIENT_MAPPER.getFactory().createParser(s);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = LENIENT_MAPPER.readValue(p, Map.class);
                    if (parsed != null && !parsed.isEmpty()) return parsed;
                } catch (Throwable ignored) {
                }
            }
        }
        return params;
    }

    private static String str(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k)) {
                    Object v = e.getValue();
                    if (v == null) return null;
                    String s = v.toString().trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            }
        }
        return null;
    }

    private static Integer intOrNull(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k)) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (v instanceof Number n) return n.intValue();
                    try {
                        return Integer.parseInt(v.toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 Map 里读取字符串数组，支持大小写不敏感 + 多别名。
     * 值可能是 {@code List<String>}（Jackson 标准）也可能是单字符串（拆成单元素数组）。
     */
    @SuppressWarnings("unchecked")
    private static List<String> listStr(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k)) {
                    Object v = e.getValue();
                    if (v == null) return null;
                    if (v instanceof List<?> list) {
                        List<String> out = new ArrayList<>();
                        for (Object item : list) {
                            if (item != null) out.add(item.toString());
                        }
                        return out.isEmpty() ? null : out;
                    }
                    if (v instanceof String s && !s.isBlank()) {
                        // 接受 "a,b,c" 形式作为兜底（罕见 LLM 行为）
                        return Arrays.asList(s.split("\\s*,\\s*"));
                    }
                }
            }
        }
        return null;
    }
}
