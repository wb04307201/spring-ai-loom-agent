package cn.wubo.loom.maven.core;

import cn.wubo.loom.file.core.PathSecurityUtils;
import cn.wubo.loom.process.core.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Core Maven execution operations.
 * <p>
 * Provides Maven process management, timeout control, output formatting,
 * and path security — without depending on Spring, ToolContext, or
 * LoomAgentProperties.
 */
public class MavenOperations {

    private static final Logger log = LoggerFactory.getLogger(MavenOperations.class);

    private final String mavenHome;
    private final String localRepository;
    private final int maxOutputLines;
    private final long defaultTimeoutMs;
    /**
     * Resolved Maven Home (user-configured or auto-detected).
     */
    private final String resolvedMavenHome;

    /**
     * @param mavenHome        Maven installation directory (may be null for auto-detect)
     * @param localRepository  local Maven repository path (may be null/blank)
     * @param maxOutputLines   maximum output lines to show before truncation
     * @param defaultTimeoutMs default timeout in milliseconds
     */
    public MavenOperations(String mavenHome, String localRepository, int maxOutputLines, long defaultTimeoutMs) {
        this.mavenHome = mavenHome;
        this.localRepository = localRepository;
        this.maxOutputLines = maxOutputLines;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.resolvedMavenHome = resolveMavenHome(mavenHome);
        log.info("MavenOperations initialized: mavenHome={}, localRepository={}, maxOutputLines={}, defaultTimeoutMs={}",
                resolvedMavenHome, localRepository, maxOutputLines, defaultTimeoutMs);
    }

    // ==================== Main Execute Method ====================

    /**
     * Resolve Maven home directory.
     * Falls back to auto-detection (common Maven installation paths) if not configured.
     */
    private static String resolveMavenHome(String configuredHome) {
        if (configuredHome != null && !configuredHome.isBlank()) {
            return configuredHome;
        }
        // Auto-detect common Maven installation paths
        String[] commonPaths = {
                System.getenv("MAVEN_HOME"),
                System.getenv("M2_HOME"),
                // Windows common locations
                "C:\\developer\\apache-maven-latest",
                "C:\\apache-maven",
                "C:\\Program Files\\apache-maven",
                // Linux common locations
                "/usr/share/maven",
                "/opt/maven",
                // macOS (Homebrew)
                "/opt/homebrew/opt/maven/libexec",
                "/usr/local/Cellar/maven",
        };
        for (String p : commonPaths) {
            if (p != null && !p.isBlank() && new File(p).isDirectory()) {
                return p;
            }
        }
        return null;
    }

    /**
     * Get user file directory: {basePath}/{username}/
     */
    private static Path getUserFileDir(String basePath, String username) {
        return Paths.get(basePath != null ? basePath : ".local/file", username);
    }

    // ==================== Path Resolution ====================

    /**
     * Validate that a path is within the user's file directory ({basePath}/{username}/).
     * Prevents directory traversal and symlink escape.
     *
     * @return validated File, or null if out of bounds
     */
    private static File validatePathInBaseDir(String basePath, String username,
                                              String path, String paramName) {
        if (username == null || username.isBlank()) {
            return null;
        }
        Path userDir = getUserFileDir(basePath, username);
        Path inputPath = Paths.get(path);
        Path resolved;

        if (inputPath.isAbsolute()) {
            resolved = inputPath.toAbsolutePath().normalize();
        } else {
            resolved = userDir.resolve(path).toAbsolutePath().normalize();
        }

        Path baseNorm = userDir.toAbsolutePath().normalize();
        if (!resolved.startsWith(baseNorm)) {
            log.warn("{} out of user file directory: {} (userDir={})", paramName, resolved, userDir);
            return null;
        }
        // Symlink defense
        try {
            PathSecurityUtils.assertInsideBaseDir(resolved, userDir, true);
        } catch (IOException | SecurityException e) {
            log.warn("{} symlink check failed: {} - {}", paramName, resolved, e.getMessage());
            return null;
        }
        return resolved.toFile();
    }

    /**
     * Execute Maven goals.
     * <p>
     * Core Maven execution: locate mvn, build command line, start subprocess,
     * pump stdout/stderr, enforce timeout, kill process tree on timeout,
     * format result.
     *
     * @param goals      Maven goals (e.g. ["clean", "package"])
     * @param workDir    working directory
     * @param pomFile    pom.xml file
     * @param properties Maven properties (-D key=value), may be null
     * @param timeoutMs  timeout in milliseconds (null for default)
     * @return formatted result string
     */
    public String execute(List<String> goals, File workDir, File pomFile,
                          Map<String, String> properties, Long timeoutMs) {
        long startTime = System.currentTimeMillis();
        try {
            return executeInternal(goals, workDir, pomFile, properties, timeoutMs, startTime);
        } catch (Throwable t) {
            log.error("Maven unexpected failure. workDir={}, goals={}", workDir, goals, t);
            return formatError(goals, workDir, pomFile, startTime,
                    "Internal error: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    "Full stack trace logged; report with stacktrace if recurring");
        }
    }

    // ==================== Formatting ====================

    private String executeInternal(List<String> goals, File workDir, File pomFile,
                                   Map<String, String> props, Long timeoutMs, long startTime) {
        String goalStr = String.join(" ", goals);
        long timeout = timeoutMs != null && timeoutMs > 0
                ? timeoutMs
                : defaultTimeoutMs;
        log.info("Maven execute start. workDir={}, goals=[{}], timeoutMs={}",
                workDir, goalStr, timeout);

        File mvnExe = ProcessUtils.findMavenExecutable(resolvedMavenHome);
        if (mvnExe == null) {
            log.warn("Maven executable not found. resolvedMavenHome={}", resolvedMavenHome);
            return formatError(goals, workDir, pomFile, startTime,
                    "Maven executable not found (resolvedMavenHome=" + resolvedMavenHome + ")",
                    "Check mavenHome configuration points to a real Maven installation");
        }

        // 1. Build command line
        List<String> cmd = new ArrayList<>();
        cmd.add(mvnExe.getAbsolutePath());
        cmd.add("-B"); // batch mode
        cmd.add("-e"); // show full errors
        cmd.add("-f");
        cmd.add(pomFile.getAbsolutePath());
        if (props != null) {
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                cmd.add("-D");
                cmd.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (localRepository != null && !localRepository.isBlank()) {
            cmd.add("-D");
            cmd.add("maven.repo.local=" + new File(localRepository).getAbsolutePath());
        }
        cmd.addAll(goals);

        // 2. Start subprocess
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            process = pb.start();
        } catch (IOException e) {
            log.warn("Maven process start failed. workDir={}, error={}", workDir, e.getMessage());
            return formatError(goals, workDir, pomFile, startTime,
                    "Failed to start mvn: " + e.getMessage(),
                    buildMavenNotFoundHint(e));
        }

        long pid = ProcessUtils.getProcessPidSafely(process);
        log.info("Maven process started. pid={}, mvnExe={}", pid, mvnExe.getAbsolutePath());

        // 3. Pump stdout/stderr (must consume, otherwise mvn blocks when pipe is full)
        StringBuilder output = new StringBuilder();
        Thread stdoutThread = ProcessUtils.startStreamPump(process.getInputStream(), output, "mvn-stdout");
        Thread stderrThread = ProcessUtils.startStreamPump(process.getErrorStream(), output, "mvn-stderr");

        // 4. Wait with timeout
        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        // 5. Timeout: kill entire process tree (Windows: taskkill /F /T; Unix: destroyForcibly + recursive)
        boolean timeoutHit = !finished;
        if (timeoutHit) {
            log.warn("Maven execution exceeded {}ms, killing process tree. workDir={}, pid={}",
                    timeout, workDir, pid);
            ProcessUtils.killProcessTree(process, pid);
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 6. Wait for pump threads to finish (prevent losing trailing output)
        ProcessUtils.joinQuietly(stdoutThread, 2000);
        ProcessUtils.joinQuietly(stderrThread, 2000);

        // 7. Explicitly close streams (release named pipe handles immediately — key for Windows file locks)
        ProcessUtils.closeQuietly(process.getInputStream());
        ProcessUtils.closeQuietly(process.getErrorStream());
        ProcessUtils.closeQuietly(process.getOutputStream());

        // 8. Format result
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }
        String errorMsg;
        if (timeoutHit) {
            errorMsg = "Timeout (" + timeout + "ms), mvn process tree force-killed (including child processes)";
        } else if (exitCode != 0) {
            errorMsg = "Maven returned non-zero exit code: " + exitCode;
        } else {
            errorMsg = null;
        }
        String result = formatResult(goals, workDir, pomFile, startTime,
                exitCode, errorMsg, ProcessUtils.truncateOutput(output.toString(), maxOutputLines), timeoutHit);
        log.info("Maven execute done. workDir={}, exitCode={}, timeout={}, costMs={}",
                workDir, exitCode, timeoutHit, System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * Resolve working directory, scoped within basePath/{username}/.
     *
     * @param workingDir explicit working dir (may be null for default)
     * @param username   username
     * @param basePath   base file storage path
     * @return resolved working directory
     */
    public File resolveWorkingDir(String workingDir, String username, String basePath) {
        if (workingDir != null && !workingDir.isBlank()) {
            File f = validatePathInBaseDir(basePath, username, workingDir, "workingDir");
            if (f != null) return f;
        }
        return getUserFileDir(basePath, username).toFile();
    }

    /**
     * Resolve pom.xml file, scoped within basePath/{username}/.
     *
     * @param pomPath  explicit pom path (may be null to auto-detect)
     * @param username username
     * @param basePath base file storage path
     * @param workDir  working directory (may be null)
     * @return resolved pom file, or null if not found / out of bounds
     */
    public File resolvePomFile(String pomPath, String username, String basePath, File workDir) {
        if (pomPath != null && !pomPath.isBlank()) {
            Path userDir = getUserFileDir(basePath, username);
            Path inputPath = Paths.get(pomPath);
            Path resolved;

            if (inputPath.isAbsolute()) {
                resolved = inputPath.toAbsolutePath().normalize();
            } else if (workDir != null) {
                resolved = workDir.toPath().toAbsolutePath().resolve(pomPath).normalize();
            } else {
                resolved = userDir.resolve(pomPath).toAbsolutePath().normalize();
            }

            Path baseNorm = userDir.toAbsolutePath().normalize();
            if (!resolved.startsWith(baseNorm)) {
                log.warn("pomPath out of user directory: {} (userDir={})", resolved, userDir);
                return null;
            }
            // Symlink defense
            try {
                PathSecurityUtils.assertInsideBaseDir(resolved, userDir, true);
            } catch (IOException | SecurityException e) {
                log.warn("POM path symlink check failed: {} - {}", resolved, e.getMessage());
                return null;
            }
            return resolved.toFile();
        }

        // Auto-detect: look for pom.xml in workDir (no existence check here, caller handles null)
        if (workDir != null) {
            return new File(workDir, "pom.xml");
        }
        return new File(getUserFileDir(basePath, username).toFile(), "pom.xml");
    }

    // ==================== Internal Helpers ====================

    /**
     * Format error result.
     */
    private String formatError(List<String> goals, File workDir, File pomFile,
                               long startTime, String errorMsg, String hint) {
        return String.format("""
                        Maven Execution Error
                        Command: mvn %s
                        Working Directory: %s
                        POM: %s
                        Elapsed: %dms
                        Error: %s
                        %s
                        """, String.join(" ", goals), workDir, pomFile,
                System.currentTimeMillis() - startTime, errorMsg,
                hint == null ? "" : hint);
    }

    /**
     * Format execution result.
     */
    private String formatResult(List<String> goals, File workDir, File pomFile,
                                long startTime, int exitCode, String errorMsg,
                                String output, boolean timeout) {
        long duration = System.currentTimeMillis() - startTime;
        String goalStr = String.join(" ", goals);

        String statusLine;
        if (timeout) {
            statusLine = "[Timeout]";
        } else if (exitCode == 0) {
            statusLine = "[Success]";
        } else {
            statusLine = "[Failed]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s Maven Execution%n", statusLine));
        sb.append(String.format("Command: mvn %s%n", goalStr));
        sb.append(String.format("Working Directory: %s%n", workDir));
        sb.append(String.format("POM: %s%n", pomFile));
        sb.append(String.format("Elapsed: %dms%n", duration));
        if (errorMsg != null) {
            sb.append(String.format("Error: %s%n", errorMsg));
        }
        sb.append(String.format("Exit Code: %d%n", exitCode));
        sb.append("\n--- Output ---\n");
        sb.append(output.isEmpty() ? "(no output)" : output);
        return sb.toString();
    }

    /**
     * Build diagnostic hint when Maven is not found.
     */
    private String buildMavenNotFoundHint(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (!msg.contains("Error configuring command line")) {
            return "";
        }
        String envM2 = System.getenv("MAVEN_HOME");
        String envM1 = System.getenv("M2_HOME");
        return String.format("""
                        Hint: maven-invoker could not find a usable Maven executable. Possible causes:
                        1) mvn/mvn.cmd on system PATH is broken or not real Maven
                        2) MAVEN_HOME / M2_HOME environment variable not set
                        3) mavenHome not configured
                        Current state:
                        - Configured mavenHome : %s
                        - Resolved mavenHome : %s
                        - MAVEN_HOME env : %s
                        - M2_HOME env : %s
                        Solutions:
                        A) Configure mavenHome explicitly:
                        spring.ai.loom.agent.maven.mavenHome: C:\\developer\\apache-maven-3.9.16
                        B) Set MAVEN_HOME environment variable to the real Maven installation directory
                        """,
                mavenHome,
                resolvedMavenHome,
                envM2 == null ? "<not set>" : envM2,
                envM1 == null ? "<not set>" : envM1);
    }
}
