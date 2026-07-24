package cn.wubo.loom.compile.core;

import cn.wubo.loom.compile.core.strategy.BuildStrategy;
import cn.wubo.loom.compile.core.strategy.BuildStrategyFactory;
import cn.wubo.loom.compile.core.strategy.MavenBuildStrategy;
import cn.wubo.loom.compile.core.strategy.NpmBackendBuildStrategy;
import cn.wubo.loom.compile.core.strategy.NpmFrontendBuildStrategy;
import cn.wubo.loom.process.core.ProcessUtils;
import cn.wubo.loom.process.core.ProcessUtils.ExecOutcome;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Compile and deploy core operations: clone + build + Docker build + container run + health check.
 * No Spring, ToolContext, or framework-specific dependencies.
 */
public class CompileAndDeployOperations {

    private static final Logger log = LoggerFactory.getLogger(CompileAndDeployOperations.class);

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private final String mavenHome;
    private final CompileConfig config;

    public CompileAndDeployOperations(String mavenHome, CompileConfig config) {
        this.mavenHome = mavenHome;
        this.config = config != null ? config : new CompileConfig();
    }

    // ==================== Main Entry ====================

    public CompileAndDeployResult compileAndDeploy(Path workspaceBasePath, String user, Map<String, Object> params) {
        List<String> steps = new ArrayList<>();
        long startMs = System.currentTimeMillis();

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
        String paramBuildTool = str(flat, "buildTool", "build_tool", "projectType", "stack");

        if (gitUrl == null || gitUrl.isBlank()) {
            return CompileAndDeployResult.fail(null, null, null, null, null, null, steps,
                    "Parameter error: gitUrl is required");
        }
        if (port == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "Parameter error: port is required (host port)");
        }
        if (containerPort == null) {
            return CompileAndDeployResult.fail(null, gitUrl, null, null, null, null, steps,
                    "Parameter error: containerPort is required (container listen port)");
        }

        int effectivePort = port;
        int effectiveContainerPort = containerPort;
        // Workspace name encodes the username and a short timestamp so:
        //   1) ops can identify the owning user just by `ls` (defense-in-depth
        //      against the user dir being shared);
        //   2) ops can age workspaces by name when deciding what to prune;
        //   3) the random UUID suffix keeps concurrent invocations collision-free.
        // Format: compile-deploy-<username>-<yyyyMMddHHmmss>-<uuid8>
        String workspaceName = "compile-deploy-"
                + sanitizeForDirName(user)
                + "-"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path workspace = workspaceBasePath.resolve(workspaceName);
        String repoName = deriveRepoName(gitUrl);
        Path projectDir = workspace.resolve(repoName);
        String subDir = str(flat, "subDir", "sub_dir", "module", "submodule");
        String effectiveImage = (imageName != null && !imageName.isBlank())
                ? imageName : ("compile-deploy-" + Long.toString(System.currentTimeMillis(), 36));
        String effectiveContainer = (containerName != null && !containerName.isBlank())
                ? containerName : effectiveImage;
        String effectiveHealthPath = (healthPath != null && !healthPath.isBlank()) ? healthPath : "/";

        String paramBaseImage = str(flat, "baseImage", "base_image");
        List<String> paramRunCommand = listStr(flat, "runCommand", "run_command", "command");
        ResolvedImage resolvedImage = resolveBaseImage(paramBuildTool, paramBaseImage, paramRunCommand);
        steps.add("Image: " + (resolvedImage.alias() != null
                ? resolvedImage.alias() + " (" + resolvedImage.image() + ")"
                : resolvedImage.image()) + " | ENTRYPOINT=" + String.join(" ", resolvedImage.command()));

        try {
            Files.createDirectories(workspace);

            boolean cloned = cloneRepo(gitUrl, projectDir, branch, gitUsername, gitPassword);
            if (!cloned) {
                steps.add("Clone failed: " + gitUrl);
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "git clone failed");
            }
            steps.add("Cloned: " + projectDir);

            Path effectiveDir;
            try {
                effectiveDir = resolveEffectiveProjectDir(projectDir, repoName, subDir);
            } catch (IllegalArgumentException subErr) {
                steps.add("Submodule resolution failed: " + subErr.getMessage());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps,
                        subErr.getMessage());
            }
            if (!effectiveDir.equals(projectDir)) {
                log.info("Multi-module layout: projectDir={}, effectiveDir={}", projectDir, effectiveDir);
            }

            BuildStrategy strategy;
            try {
                if (paramBuildTool != null && !paramBuildTool.isBlank()) {
                    strategy = BuildStrategyFactory.forBuildTool(paramBuildTool);
                } else {
                    strategy = BuildStrategyFactory.autoDetect(effectiveDir);
                }
            } catch (IllegalArgumentException strategyErr) {
                steps.add("Build type detection failed: " + strategyErr.getMessage());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps,
                        strategyErr.getMessage());
            }
            steps.add("Build strategy: " + strategy.getClass().getSimpleName());

            String buildLog;
            try {
                buildLog = buildArtifact(strategy, effectiveDir);
            } catch (BuildStageException bse) {
                // Build command ran but exited non-zero. Surface the tail of the build output
                // in errorMessage so the LLM can diagnose the failure instead of retrying blindly.
                String tail = ProcessUtils.tail(bse.buildOutput(), 60);
                String msg = "Build failed: " + strategy.getClass().getSimpleName()
                        + (tail.isBlank() ? "" : "\n--- build log (last 60 lines) ---\n" + tail);
                steps.add("Build failed: " + strategy.getClass().getSimpleName());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, msg);
            }
            if (buildLog == null) {
                // null = build tool not found or timed out (no output to surface)
                steps.add("Build failed: " + strategy.getClass().getSimpleName());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "Build failed");
            }
            steps.add("Build: " + strategy.getClass().getSimpleName());

            Path artifact = findArtifact(strategy, effectiveDir);
            if (artifact == null) {
                steps.add("Artifact not found in " + strategy.artifactCandidates());
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "Artifact not found");
            }
            steps.add("Artifact: " + artifact);

            String artifactForDocker = artifact.toString().replace(File.separatorChar, '/');
            File dockerfile = strategy.writeDockerfile(effectiveDir, resolvedImage, effectiveContainerPort, artifactForDocker);
            steps.add("Dockerfile: " + dockerfile.getName());

            String builtImage;
            try {
                builtImage = dockerBuild(effectiveDir, effectiveImage, resolvedImage);
            } catch (DockerBuildException e) {
                steps.add("Docker build failed (image=" + resolvedImage.image()
                        + ", alias=" + (resolvedImage.alias() == null ? "<none>" : resolvedImage.alias()) + ")");
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, e.getMessage());
            }
            steps.add("Docker image: " + builtImage);

            String runningContainer = dockerRun(effectiveImage, effectiveContainer, effectivePort, effectiveContainerPort);
            if (runningContainer == null) {
                steps.add("Docker run failed: " + effectiveContainer);
                return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                        effectiveContainer, effectivePort, effectiveHealthPath, steps, "docker run failed");
            }
            steps.add("Docker container: " + runningContainer + " (port " + effectivePort + ")");

            boolean healthy = waitForHealthy(effectivePort, effectiveHealthPath);
            String accessUrl = buildAccessUrl(effectivePort, effectiveHealthPath);
            if (!healthy) {
                steps.add("Health check timeout after " + config.healthCheckMaxWaitMs() + "ms, container retained. Access: " + accessUrl + effectiveHealthPath);
                log.warn("Health check timeout. port={}, path={}", effectivePort, effectiveHealthPath);
            } else {
                steps.add("Health check passed: " + accessUrl + effectiveHealthPath);
            }

            log.info("compileAndDeploy done. elapsed={}ms, workspace={}", System.currentTimeMillis() - startMs, workspace);
            CompileAndDeployResult ok = CompileAndDeployResult.ok(workspace.toString(), gitUrl, branch, effectiveImage,
                    effectiveContainer, effectivePort, accessUrl, effectiveHealthPath, steps);
            // Default keepWorkspace=false → clean up on success (workspace was
            // an ephemeral scratch space for the docker build context, not a
            // user-owned artifact). Failure paths keep the workspace regardless
            // (for post-mortem; the workspace dir name embeds username +
            // timestamp so it's easy to correlate to a given run).
            if (!config.keepWorkspace()) {
                try {
                    deleteRecursively(workspace);
                    log.info("Cleaned up workspace after success: {}", workspace);
                } catch (Exception cleanupErr) {
                    log.warn("Workspace cleanup failed for {}: {}", workspace, cleanupErr.getMessage());
                }
            } else {
                log.info("Keeping successful workspace (keepWorkspace=true): {}", workspace);
            }
            return ok;
        } catch (Exception e) {
            log.error("compileAndDeploy unexpected failure. workspace={}", workspace, e);
            steps.add("Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // Failure path always keeps the workspace (regardless of
            // keepWorkspace) — see CompileConfig docstring ("失败时保留供排障").
            log.info("Failure — workspace preserved at: {}", workspace);
            return CompileAndDeployResult.fail(workspace.toString(), gitUrl, effectiveImage,
                    effectiveContainer, effectivePort, effectiveHealthPath, steps,
                    "Internal error: " + e.getMessage());
        }
    }

    /**
     * Strip characters that are illegal / awkward in directory names on common
     * filesystems: anything outside {@code [A-Za-z0-9._-]} becomes {@code _}.
     * Empty username collapses to {@code anonymous} so the directory name is
     * never empty.
     */
    private static String sanitizeForDirName(String username) {
        if (username == null || username.isBlank()) return "anonymous";
        StringBuilder sb = new StringBuilder(username.length());
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') ? c : '_');
        }
        String sanitized = sb.toString();
        return sanitized.isEmpty() ? "anonymous" : sanitized;
    }

    // ==================== Step Implementations ====================

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
            if (isPlausibleBranch(branch, deriveRepoName(gitUrl))) {
                cmd.setBranch(branch);
            } else if (branch != null && !branch.isBlank()) {
                log.warn("Ignoring implausible branch '{}' (repo={}); letting JGit pick default HEAD",
                        branch, deriveRepoName(gitUrl));
            }
            CredentialsProvider cp = buildCredentialsProvider(username, password);
            if (cp != null) cmd.setCredentialsProvider(cp);

            try (Git ignored = cmd.call()) {
            }
            return Files.isDirectory(projectDir.resolve(".git"));
        } catch (Exception e) {
            log.error("git clone failed. url={}, target={}", gitUrl, projectDir, e);
            return false;
        }
    }

    private static boolean isPlausibleBranch(String branch, String repoName) {
        if (branch == null || branch.isBlank()) return false;
        String b = branch.trim();
        if (b.equalsIgnoreCase(repoName)) return false;
        if (b.contains("/") || b.contains("\\")) return false;
        if (b.endsWith(".git")) return false;
        return b.matches("[A-Za-z0-9_./-]+");
    }

    private String buildArtifact(BuildStrategy strategy, Path effectiveDir) {
        List<List<String>> commands = strategy.buildCommands();
        if (commands.isEmpty()) {
            return "";
        }
        if (strategy instanceof MavenBuildStrategy) {
            return mavenPackage(effectiveDir);
        }
        if (strategy instanceof NpmBackendBuildStrategy || strategy instanceof NpmFrontendBuildStrategy) {
            return runNpmPipeline(strategy, effectiveDir);
        }
        throw new IllegalArgumentException(
                "buildArtifact not yet supported for " + strategy.getClass().getSimpleName()
                        + " (add alias in BuildStrategyFactory.forBuildTool or add if branch here)");
    }

    private String runNpmPipeline(BuildStrategy strategy, Path effectiveDir) {
        for (List<String> cmd : strategy.buildCommands()) {
            ExecOutcome out = ProcessUtils.runProcess(cmd, effectiveDir.toFile(), config.mavenTimeoutMs());
            if (out.timeout()) {
                log.error("npm pipeline timed out after {}ms. cmd={}", config.mavenTimeoutMs(),
                        String.join(" ", cmd));
                return null;
            }
            if (out.exitCode() != 0) {
                log.error("npm pipeline failed. exitCode={}, cmd={}, outputTail=\n{}",
                        out.exitCode(), String.join(" ", cmd), ProcessUtils.tail(out.output(), 60));
                throw new BuildStageException("npm pipeline failed (exitCode=" + out.exitCode() + ", cmd=" + String.join(" ", cmd) + ")", out.output());
            }
        }
        return "npm pipeline ok (" + strategy.buildCommands().size() + " steps)";
    }

    private Path findArtifact(BuildStrategy strategy, Path effectiveDir) {
        for (String candidate : strategy.artifactCandidates()) {
            Path dir = effectiveDir.resolve(candidate);
            if (!Files.isDirectory(dir)) continue;
            Path picked = strategy.findArtifact(dir);
            if (picked == null) continue;
            return effectiveDir.relativize(picked);
        }
        return null;
    }

    private String mavenPackage(Path projectDir) {
        Path[] effective = resolveMavenTarget(projectDir);
        Path effectiveDir = effective[0];
        Path pom = effective[1];
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

        File mvnExe = ProcessUtils.findMavenExecutable(mavenHome);
        if (mvnExe == null) {
            log.error("Maven executable not found. mavenHome={}", mavenHome);
            return null;
        }

        List<String> cmd = wrapForWindows(mvnExe.getAbsolutePath(), mvnArgs);

        ExecOutcome out = ProcessUtils.runProcess(cmd, effectiveDir.toFile(), config.mavenTimeoutMs());
        if (out.timeout()) {
            log.error("mvn package timed out after {}ms", config.mavenTimeoutMs());
            return null;
        }
        if (out.exitCode() != 0) {
            log.error("mvn package failed. exitCode={}, outputLen={}, tail=\n{}",
                    out.exitCode(), out.output() == null ? 0 : out.output().length(), ProcessUtils.tail(out.output(), 60));
            // Throw instead of returning null so the caller can surface the actual Maven output
            // (compiler errors, dependency resolution failures, etc.) to the LLM.
            throw new BuildStageException("mvn package failed (exitCode=" + out.exitCode() + ")", out.output());
        }
        return out.output();
    }

    private Path[] resolveMavenTarget(Path projectDir) {
        Path rootPom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            return new Path[]{projectDir, rootPom};
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir)) {
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

    Path resolveEffectiveProjectDir(Path projectDir, String repoName, String subDir) {
        if (subDir != null && !subDir.isBlank()) {
            Path target = projectDir.resolve(subDir);
            if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("pom.xml"))) {
                log.info("resolveEffectiveProjectDir: subDir='{}' picked", subDir);
                return target;
            }
            List<Path> candidates = listChildDirs(projectDir);
            String names = formatNames(candidates);
            throw new IllegalArgumentException(
                    "Parameter error: subDir='" + subDir + "' not found in cloned repo. Available: " + names);
        }

        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return projectDir;
        }

        List<Path> children = listChildDirs(projectDir);
        if (children.isEmpty()) {
            return projectDir;
        }
        if (repoName != null && !repoName.isBlank()) {
            for (Path c : children) {
                if (c.getFileName().toString().equals(repoName)) {
                    log.info("resolveEffectiveProjectDir: picked submodule matching repo name. submodule={}", c);
                    return c;
                }
            }
        }
        if (children.size() == 1) {
            Path only = children.get(0);
            log.info("resolveEffectiveProjectDir: only one submodule, falling back to {}", only.getFileName());
            return only;
        }
        String names = formatNames(children);
        throw new IllegalArgumentException(
                "Parameter error: no pom.xml at repo root, multiple submodules found: " + names
                        + ". Please specify subDir parameter.");
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

    // ==================== Docker ====================

    private String dockerBuild(Path projectDir, String imageName, ResolvedImage resolved) {
        String docker = resolveDockerCmd();
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add("-t");
        args.add(imageName);
        args.add(".");
        List<String> cmd = wrapForWindows(docker, args);
        ExecOutcome out = ProcessUtils.runProcess(cmd, projectDir.toFile(), config.dockerBuildTimeoutMs());
        if (out.timeout() || out.exitCode() != 0) {
            String tailOutput = ProcessUtils.tail(out.output(), 100);
            String msg = String.format(
                    "docker build failed (exitCode=%d, timeout=%s, image=%s, alias=%s)\n--- last 100 lines ---\n%s",
                    out.exitCode(), out.timeout(), resolved.image(),
                    resolved.alias() == null ? "<none>" : resolved.alias(), tailOutput);
            log.error(msg);
            throw new DockerBuildException(msg);
        }
        return imageName;
    }

    public static class DockerBuildException extends RuntimeException {
        public DockerBuildException(String message) { super(message); }
    }

    private String dockerRun(String imageName, String containerName, int port, int containerPort) {
        String docker = resolveDockerCmd();
        List<String> rmArgs = new ArrayList<>();
        rmArgs.add("rm");
        rmArgs.add("-f");
        rmArgs.add(containerName);
        ProcessUtils.runProcess(wrapForWindows(docker, rmArgs), null, 15_000L);

        List<String> runArgs = buildDockerRunCommand(imageName, containerName, port, containerPort,
                config.extraRunArgs());
        List<String> cmd = wrapForWindows(docker, runArgs);
        ExecOutcome out = ProcessUtils.runProcess(cmd, null, config.dockerRunTimeoutMs());
        if (out.timeout() || out.exitCode() != 0) {
            log.error("docker run failed. exitCode={}, timeout={}, tail=\n{}",
                    out.exitCode(), out.timeout(), ProcessUtils.tail(out.output(), 60));
            return null;
        }
        return containerName;
    }

    public static List<String> buildDockerRunCommand(String imageName, String containerName, int port,
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

    private boolean waitForHealthy(int port, String healthPath) {
        long deadline = System.currentTimeMillis() + config.healthCheckMaxWaitMs();
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
                    conn.disconnect();
                    sleep(config.healthCheckIntervalMs());
                    continue;
                }
                conn.disconnect();
                if (code >= 200 && code < 500) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("health check iteration failed. url={}, err={}", urlStr, e.getMessage());
            }
            sleep(config.healthCheckIntervalMs());
        }
        return false;
    }

    // ==================== Helpers ====================

    private static String resolveDockerCmd() {
        String configured = System.getenv("DOCKER_CMD");
        if (configured != null && !configured.isBlank()) return configured;
        return "docker";
    }

    public static List<String> wrapForWindows(String exe, List<String> args) {
        if (ProcessUtils.isWindows() && (exe.toLowerCase().endsWith(".cmd") || exe.toLowerCase().endsWith(".bat"))) {
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

    private static String deriveRepoName(String gitUrl) {
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

    public static String buildAccessUrl(int port, String healthPath) {
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

    // ==================== Image Resolution ====================

    public record ResolvedImage(String alias, String image, List<String> command) {}

    ResolvedImage resolveBaseImage(String paramBuildTool, String paramBaseImage, List<String> paramRunCommand) {
        Map<String, ImageTemplate> templates = config.imageTemplates();

        ImageTemplate fallbackTpl = templates.get("java17");
        if (paramBuildTool != null) {
            String tool = paramBuildTool.toLowerCase(Locale.ROOT);
            if (tool.equals("npm-frontend") || tool.equals("node-frontend") || tool.equals("frontend")) {
                fallbackTpl = templates.getOrDefault("node20-serve", fallbackTpl);
            } else if (tool.equals("npm") || tool.equals("node")) {
                fallbackTpl = templates.getOrDefault("node20", fallbackTpl);
            } else if (tool.equals("pip") || tool.equals("python")) {
                fallbackTpl = templates.getOrDefault("python3", fallbackTpl);
            } else if (tool.equals("maven") || tool.equals("mvn")) {
                fallbackTpl = templates.getOrDefault("java17", fallbackTpl);
            }
        }
        if (fallbackTpl == null) {
            fallbackTpl = new ImageTemplate("eclipse-temurin:17-jre-alpine", List.of("java", "-jar", "app.jar"));
        }

        String alias = null;
        String image;
        List<String> command;
        String fallbackImage = fallbackTpl.image();

        if (paramBaseImage != null && !paramBaseImage.isBlank()) {
            if (templates.containsKey(paramBaseImage)) {
                ImageTemplate tpl = templates.get(paramBaseImage);
                alias = paramBaseImage;
                image = tpl.image();
                command = new ArrayList<>(tpl.command());
            } else if (paramBaseImage.contains(":") || paramBaseImage.contains("/")) {
                image = paramBaseImage;
                command = new ArrayList<>(fallbackTpl.command());
            } else {
                image = fallbackImage;
                command = new ArrayList<>(fallbackTpl.command());
            }
        } else {
            image = fallbackImage;
            command = new ArrayList<>(fallbackTpl.command());
        }

        if (paramRunCommand != null && !paramRunCommand.isEmpty()) {
            command = new ArrayList<>(paramRunCommand);
        }
        return new ResolvedImage(alias, image, command);
    }

    // ==================== JSON Serialization ====================

    public static String toJsonArray(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("ResolvedImage.command must not be empty");
        }
        try {
            return LENIENT_MAPPER.writeValueAsString(parts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ENTRYPOINT to JSON: " + e.getMessage(), e);
        }
    }

    // ==================== Param Parsing ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return Map.of();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> inner) {
                Map<String, Object> candidate = (Map<String, Object>) inner;
                if (candidate.keySet().stream().anyMatch(k -> k.toString().toLowerCase(Locale.ROOT)
                        .matches("giturl|username|password|port|branch"))) {
                    return candidate;
                }
            }
        }
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
                        return Arrays.asList(s.split("\\s*,\\s*"));
                    }
                }
            }
        }
        return null;
    }

    private static CredentialsProvider buildCredentialsProvider(String username, String password) {
        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(
                username == null ? "" : username,
                password == null ? "" : password);
    }
}
