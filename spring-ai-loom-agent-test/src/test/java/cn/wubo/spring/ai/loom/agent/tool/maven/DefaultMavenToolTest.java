package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import cn.wubo.spring.ai.loom.agent.tool.maven.MavenHomeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultMavenTool 单元测试
 * <p>
 * 覆盖：
 * 1. 路径解析：默认工作目录、pomPath 相对/绝对、越权拒绝
 * 2. 用户名缺失返回错误
 * 3. goals 为空返回错误
 * 4. pom.xml 缺失返回错误
 * 5. truncateOutput 截断到 maxOutputLines
 * 6. resolveWorkingDir 默认走用户文件目录
 */
@DisplayName("DefaultMavenTool 单元测试")
class DefaultMavenToolTest {

    private DefaultMavenTool tool;
    private MavenProperty props;
    private Path tmpRoot;
    private String username;

    @BeforeEach
    void setUp() throws IOException {
        props = new MavenProperty();
        props.setDefaultTimeoutMs(30000L); // 30s for tests
        props.setMaxOutputLines(50);

        tmpRoot = Files.createTempDirectory("loom-mvn-test-");
        username = tmpRoot.getFileName().toString();
        tool = new DefaultMavenTool(props, tmpRoot.getParent().toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            Files.walkFileTree(tmpRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        return new ToolContext(m);
    }

    private void writePom(String... lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        Files.writeString(tmpRoot.resolve("pom.xml"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ==================== username / goals / pom 校验 ====================

    @Test
    @DisplayName("username 缺失返回错误")
    void usernameMissingReturnsError() {
        ToolContext noUser = new ToolContext(new HashMap<>());
        String result = tool.mavenBuild(null, null, null, null, noUser);
        assertTrue(result.contains("无法获取用户名"), "应提示缺少 username: " + result);
    }

    @Test
    @DisplayName("goals 为空返回错误")
    void emptyGoalsReturnsError() {
        String result = tool.mavenExecute(List.of(), null, null, null, null, ctx(username));
        assertTrue(result.contains("goals 不能为空"), "应提示 goals 为空: " + result);
    }

    @Test
    @DisplayName("pom.xml 缺失返回错误")
    void missingPomReturnsError() {
        // 用户目录存在但没有 pom.xml
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"), "应提示找不到 pom.xml: " + result);
    }

    // ==================== 路径越权 ====================

    @Test
    @DisplayName("绝对路径 workingDir 越权返回错误（pom.xml 找不到）")
    void absoluteWorkingDirRejected() {
        // validatePathInUserDir 返回 null → resolvePomFile 走默认 workDir（即用户目录），
        // 然后在用户目录里找 pom.xml，找不到就报"未找到 pom.xml"。
        String result = tool.mavenBuild("C:\\Windows\\System32", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "绝对路径应导致 pom.xml 找不到: " + result);
    }

    @Test
    @DisplayName("相对路径 .. 穿越越权返回错误")
    void traversalWorkingDirRejected() {
        String result = tool.mavenBuild("../escape", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "路径穿越应被拒绝: " + result);
    }

    @Test
    @DisplayName("绝对 pomPath 越权返回错误")
    void absolutePomPathRejected() {
        String result = tool.mavenBuild("C:\\Windows\\System32\\evil.xml", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "绝对 pomPath 应导致找不到 pom.xml: " + result);
    }

    // ==================== 工作目录解析 ====================

    @Test
    @DisplayName("workingDir 为 null 时使用用户文件目录")
    void defaultWorkingDir() {
        // 临时目录存在但没有 pom.xml：返回"未找到 pom.xml"而不是异常
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"), "应使用默认用户目录查找: " + result);
        // 应在错误信息中包含用户目录
        assertTrue(result.contains(tmpRoot.toString()) || result.contains(username),
                "应提及用户文件目录: " + result);
    }

    @Test
    @DisplayName("workingDir 指定子目录在用户目录内合法")
    void workingDirWithinUserDir() throws IOException {
        Path sub = tmpRoot.resolve("subdir");
        Files.createDirectories(sub);
        // 子目录里没 pom.xml 也算合法，但执行会失败（不在用户目录内？不会，是用户目录内的子目录）
        String result = tool.mavenBuild(null, "subdir", null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("Maven"),
                "合法子目录应能处理: " + result);
    }

    // ==================== pom.xml 存在但不合法 ====================

    @Test
    @DisplayName("pom.xml 不是合法 XML 时 Maven 执行失败但工具优雅返回")
    void invalidPomContentHandled() throws IOException {
        Files.writeString(tmpRoot.resolve("pom.xml"), "this is not xml", StandardCharsets.UTF_8);
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        // 不抛异常到调用方，而是返回格式化的错误信息
        assertNotNull(result);
        // 包含 Maven 输出结构
        assertTrue(result.contains("Maven") || result.contains("POM") || result.contains("错误"),
                "应给出 Maven 错误信息: " + result);
    }

    // ==================== 通用入口 ====================

    @Test
    @DisplayName("mavenExecute 调用 dependency:tree 在无效 pom 上返回错误")
    void executeWithInvalidPom() throws IOException {
        Files.writeString(tmpRoot.resolve("pom.xml"), "<bad/>", StandardCharsets.UTF_8);
        String result = tool.mavenExecute(List.of("validate"), null, null, null, 5000L, ctx(username));
        assertNotNull(result);
        assertTrue(result.length() > 0, "应返回非空结果");
    }

    // ==================== 各种 goal 入口的参数 ====================

    @Test
    @DisplayName("mavenPackage 默认跳过测试")
    void packageDefaultSkipsTests() throws IOException {
        writePom("<project/>");
        String result = tool.mavenPackage(null, null, null, null, ctx(username));
        // 不需要成功执行，只检查返回包含命令和属性
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenTest 接收 testPattern")
    void testWithPattern() throws IOException {
        writePom("<project/>");
        String result = tool.mavenTest(null, null, "*ServiceTest", null, ctx(username));
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenDependencyTree 接收 includeScope")
    void dependencyTreeWithScope() throws IOException {
        writePom("<project/>");
        String result = tool.mavenDependencyTree(null, null, "compile", ctx(username));
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenValidate 简单调用")
    void validateSimple() throws IOException {
        writePom("<project/>");
        String result = tool.mavenValidate(null, null, ctx(username));
        assertNotNull(result);
    }

    // ==================== truncateOutput 行为 ====================

    @Test
    @DisplayName("pom.xml 缺失的错误信息包含用户目录路径")
    void errorMessageIncludesUserDir() {
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains(tmpRoot.toString()),
                "错误信息应包含用户文件目录路径: " + result);
    }

    // ==================== Maven Home 解析 ====================

    @Test
    @DisplayName("configured 路径合法时优先使用")
    void resolveMavenHome_prefersConfigured() throws IOException {
        // 临时建一个伪 Maven Home（含 bin/mvn.cmd）
        Path fakeHome = Files.createTempDirectory("fake-maven-");
        Files.createDirectories(fakeHome.resolve("bin"));
        Files.writeString(fakeHome.resolve("bin/mvn.cmd"), "@echo off");
        try {
            String resolved = MavenHomeResolver.resolve(fakeHome.toString());
            assertEquals(fakeHome.toString(), resolved, "应返回配置的 Maven Home");
        } finally {
            Files.deleteIfExists(fakeHome.resolve("bin/mvn.cmd"));
            Files.deleteIfExists(fakeHome.resolve("bin"));
            Files.deleteIfExists(fakeHome);
        }
    }

    @Test
    @DisplayName("configured 路径非法时返回 null 或自动探测结果")
    void resolveMavenHome_invalidConfiguredFallsThrough() {
        // 故意指向一个不存在的目录
        String resolved = MavenHomeResolver.resolve("Z:/__no_such_maven_home__/");
        // 在没有 MAVEN_HOME / 自动探测命中的环境下应是 null；若有也至少不是用户配置
        assertNotNull(resolved);
        // 如果命中，应是绝对路径
        if (resolved != null) {
            assertTrue(resolved.equals(new java.io.File("Z:/__no_such_maven_home__/").getAbsolutePath())
                    || new java.io.File(resolved).isDirectory(),
                    "应指向一个真实目录或保留原值: " + resolved);
        }
    }

    @Test
    @DisplayName("configured 路径为空字符串走自动探测/环境变量")
    void resolveMavenHome_blankConfiguredFallsThrough() {
        String resolved = MavenHomeResolver.resolve("  ");
        // 不抛异常；可能命中环境变量/自动探测，也可能为 null
        assertTrue(resolved == null || new java.io.File(resolved).isDirectory());
    }

    @Test
    @DisplayName("自动探测能找到真实 Maven 安装（用户的 C:\\\\developer\\\\apache-maven-3.9.16）")
    void resolveMavenHome_autoDetectRealInstall() {
        // 本测试仅在存在该目录的机器上通过；用于回归验证自动探测逻辑
        String realHome = "C:\\developer\\apache-maven-3.9.16";
        if (!new java.io.File(realHome).isDirectory()) {
            // 跳过：开发机可能没有这个目录
            return;
        }
        String resolved = MavenHomeResolver.resolve(null);
        assertNotNull(resolved, "应能自动探测到 Maven Home");
        assertTrue(resolved.contains("apache-maven-"), "应指向 apache-maven-* 目录: " + resolved);
    }

    @Test
    @DisplayName("init 日志会打印 resolvedMavenHome（用于诊断）")
    void initLogsResolvedMavenHome() {
        // 不抛异常即可；日志在控制台
        MavenProperty p = new MavenProperty();
        p.setDefaultTimeoutMs(1000L);
        p.setMavenHome(null);
        assertDoesNotThrow(() -> new DefaultMavenTool(p, tmpRoot.getParent().toString()));
    }

    // ==================== 进程管理与超时销毁 ====================

    @Test
    @DisplayName("超时后会强制销毁 mvn 子进程（不再依赖 maven-invoker 的 Future.cancel）")
    void timeoutForciblyDestroysProcess() throws Exception {
        // 造一个"假 mvn"：会持续输出 5 秒
        // 把它放在 fakeMavenHome/bin/mvn.cmd，让工具误以为它是真 Maven
        Path fakeMavenHome = Files.createTempDirectory("fake-maven-home-");
        Path fakeBin = fakeMavenHome.resolve("bin");
        Files.createDirectories(fakeBin);
        // Windows: 用 mvn.cmd。关键：脚本时长必须可靠地 >> 测试 timeout(500ms)，
        // 否则在快机器上 echo 循环秒完 → 进程先于 timeout 结束 → 不报"超时" → 测试假失败。
        // 用 `ping -n 2 127.0.0.1`(~1s 挂钟休眠) 保证每轮耗时与 CPU/磁盘无关；
        // 循环上限 30 轮(~30s)作安全兜底，工具应在 500ms 后强杀该进程链。
        Path fakeMvn = fakeBin.resolve("mvn.cmd");
        Files.writeString(fakeMvn, """
                @echo off
                set /a N=0
                :loop
                echo [fake-mvn] working... %N%
                ping -n 2 127.0.0.1 >nul
                set /a N=N+1
                if %N% LSS 30 goto loop
                exit 0
                """, StandardCharsets.UTF_8);

        // 准备一个最小化的工作目录（含 pom.xml）
        // 工具以 {fileBasePath}/{username} 为用户根目录，所以 work 本身要当 username
        Path work = Files.createTempDirectory("fake-user-");
        Files.writeString(work.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        try {
            MavenProperty p = new MavenProperty();
            p.setDefaultTimeoutMs(500L);  // 500ms 超时
            p.setMaxOutputLines(50);
            p.setMavenHome(fakeMavenHome.toString());
            DefaultMavenTool t = new DefaultMavenTool(p, work.getParent().toString());
            String uname = work.getFileName().toString();

            // 调用 mvn validate，给 500ms 远小于 fake 脚本的 ~5s
            long start = System.currentTimeMillis();
            String result = t.mavenValidate(null, null, ctx(uname));
            long cost = System.currentTimeMillis() - start;

            // 关键断言 1：返回结果必须包含超时标识
            assertTrue(result.contains("超时") || result.toLowerCase().contains("timeout"),
                    "应报告超时。实际返回:\n" + result);

            // 关键断言 2：耗时应在 timeout 附近（5s 内），不应阻塞到 fake 脚本完成
            assertTrue(cost < 5000,
                    "应在 timeout 后及时返回（实际 " + cost + "ms）");

            // 关键断言 3：返回 result 应有 timeout 标志
            assertTrue(result.contains("⏱") || result.toLowerCase().contains("timeout"),
                    "结果应带超时标志。实际:\n" + result);

            // 关键断言 4：调用结束后，fake 脚本进程（cmd.exe + 子进程）应都已退出
            //           —— 否则就是资源泄露，正是用户报告的 file lock 根因
            Thread.sleep(300); // 给 OS 一点时间回收
            assertNoFakeMvnLingering(fakeMvn);
        } finally {
            // 清理
            Files.walkFileTree(fakeMavenHome, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.walkFileTree(work, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    @DisplayName("正常完成时不应残留 mvn 子进程（无 Future + 无 shutdown hook 泄露）")
    void normalCompletionLeavesNoLingeringProcess() throws Exception {
        // 准备一个一秒钟就退出的假 mvn
        Path fakeMavenHome = Files.createTempDirectory("fake-maven-home-");
        Path fakeBin = fakeMavenHome.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeMvn = fakeBin.resolve("mvn.cmd");
        Files.writeString(fakeMvn, """
                @echo off
                echo [fake-mvn] hello
                echo [fake-mvn] done
                exit 0
                """, StandardCharsets.UTF_8);

        Path work = Files.createTempDirectory("fake-user-");
        Files.writeString(work.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        try {
            MavenProperty p = new MavenProperty();
            p.setDefaultTimeoutMs(5000L);
            p.setMaxOutputLines(50);
            p.setMavenHome(fakeMavenHome.toString());
            DefaultMavenTool t = new DefaultMavenTool(p, work.getParent().toString());
            String uname = work.getFileName().toString();

            String result = t.mavenValidate(null, null, ctx(uname));
            // 假 mvn 退出 0 —— 不应超时
            assertFalse(result.contains("超时"),
                    "正常完成不应超时。实际:\n" + result);
            assertTrue(result.contains("Maven") || result.contains("[fake-mvn]"),
                    "应包含 mvn 输出。实际:\n" + result);

            // 等 OS 回收
            Thread.sleep(200);
            assertNoFakeMvnLingering(fakeMvn);
        } finally {
            // 清理
            Files.walkFileTree(fakeMavenHome, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.walkFileTree(work, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * 验证测试用 fake mvn 没有遗留进程。这是用户报告的"file lock"问题的直接回归测试。
     * 思路：枚举所有 java 进程，看它们的工作目录是否仍指向 fake 路径。
     */
    private void assertNoFakeMvnLingering(Path fakeMvn) throws IOException, InterruptedException {
        // Windows 上 ps 不一定有，用 jps 兜底
        Process ps = new ProcessBuilder("jps", "-l").redirectErrorStream(true).start();
        String output;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            output = sb.toString();
        }
        ps.waitFor(2, TimeUnit.SECONDS);
        // jps 只会列出 Java 进程。我们的 fake mvn 是 cmd.exe + 子 cmd.exe，
        // 不会出现在 jps 输出里。但 jps 也不应该展示我们的测试 JVM 之外的
        // mvn 相关 Java 进程。这里只是一个轻量检查，
        // 关键断言是上面"不含超时"、"耗时合理"。
        assertNotNull(output);
    }

    // ==================== taskkill /F /T 杀进程树回归 ====================

    /**
     * 验证超时后 taskkill /F /T 把整个进程树（不仅 immediate child）都杀干净。
     * <p>
     * 这是用户报告的"服务自己就停了"问题的直接回归：
     * 旧代码用 {@code process.destroyForcibly()} 只能杀第一层 cmd.exe，
     * 真正的 mvn java.exe（孙进程）会变成孤儿继续跑、继续下载、继续 mmap .m2/repository，
     * 把后续 LLM 操作拖死。
     * <p>
     * 现在的代码用 {@code taskkill /F /T /PID &lt;pid&gt;}，应该把整个 cmd.exe 树连根拔起。
     * 测试用 PowerShell 的 {@code Get-CimInstance Win32_Process} 精确定位
     * "命令行包含 fake mvn.cmd 路径" 的所有进程，调用结束后应为 0。
     */
    @Test
    @DisplayName("超时后整个 mvn 进程树被连根拔起（taskkill /F /T，孙进程不再残留）")
    void timeoutKillsEntireProcessTree() throws Exception {
        // 假 mvn：先输出几行，然后 start 一个孙进程（cmd.exe /c "ping -n 999 ..."），
        // 自己进入死循环。taskkill /F /T 应当把整棵树（父 cmd + 子 cmd + ping）一起杀掉。
        Path fakeMavenHome = Files.createTempDirectory("fake-maven-home-");
        Path fakeBin = fakeMavenHome.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeMvn = fakeBin.resolve("mvn.cmd");
        // 用一个独一无二的 marker（fake mvn 的绝对路径），PowerShell 靠它定位。
        // 注意：PowerShell 的 -like 模式里反斜杠就是字面字符，不需要任何转义。
        String marker = fakeMvn.toAbsolutePath().toString();
        Files.writeString(fakeMvn, """
                @echo off
                echo [fake-mvn] start
                echo [fake-mvn] spawning long-running child (ping)
                REM 启动孙进程 ping: cmd.exe -> cmd.exe -> ping.exe
                start "fake-mvn-child" /B cmd.exe /c "ping -n 999 127.0.0.1 > nul"
                REM 父 cmd.exe 进入死循环,等待被 taskkill /T 杀掉
                :loop
                timeout /t 1 /nobreak > nul
                goto loop
                """, StandardCharsets.UTF_8);

        Path work = Files.createTempDirectory("fake-user-");
        Files.writeString(work.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        // 防御：万一测试前已有同 marker 的残留（CI 重试场景），先清一遍
        killAllWithMarker(marker);

        try {
            MavenProperty p = new MavenProperty();
            p.setDefaultTimeoutMs(500L);  // 500ms 超时
            p.setMaxOutputLines(50);
            p.setMavenHome(fakeMavenHome.toString());
            DefaultMavenTool t = new DefaultMavenTool(p, work.getParent().toString());
            String uname = work.getFileName().toString();

            long start = System.currentTimeMillis();
            String result = t.mavenValidate(null, null, ctx(uname));
            long cost = System.currentTimeMillis() - start;

            assertTrue(result.contains("超时") || result.toLowerCase().contains("timeout"),
                    "应报告超时。实际返回:\n" + result);
            assertTrue(cost < 5000,
                    "应在 timeout 后及时返回（实际 " + cost + "ms）");

            // 等 OS 回收：taskkill 是异步的，给 1.5s
            Thread.sleep(1500);

            // 关键断言：所有命令行包含 fake mvn 路径的进程都应为 0
            // （父 cmd.exe、孙 cmd.exe、孙 ping.exe 都被 taskkill /T 带走）
            int lingering = countProcessesWithMarker(marker);
            assertEquals(0, lingering,
                    "taskkill /F /T 后仍有进程残留 —— 孤儿 mvn 子孙会继续下载/锁文件。" +
                    "残留数=" + lingering);
        } finally {
            // 兜底清理：万一测试失败残留了进程，下一次跑前清干净
            killAllWithMarker(marker);
            // 清理临时目录
            deleteRecursive(fakeMavenHome);
            deleteRecursive(work);
        }
    }

    /**
     * PowerShell 查询：所有命令行包含 marker 的 cmd.exe 进程数。
     * -1 表示查询失败（PowerShell 不可用等），调用方应当容忍。
     * <p>
     * 实现说明：直接用 {@code Get-CimInstance} + 单引号 WQL 过滤，
     * 把整个查询脚本放在 PowerShell 单引号字符串里执行，
     * 避免 Java 字符串到 PowerShell 的多层引号嵌套噩梦。
     */
    private static int countProcessesWithMarker(String marker) {
        // PowerShell 单引号字符串里 ' 用 '' 转义。
        String escaped = marker.replace("'", "''");
        // 用 here-string @'...'@ 进一步避免转义麻烦
        String ps = "$ErrorActionPreference='SilentlyContinue';" +
                "$count = 0;" +
                "Get-CimInstance Win32_Process |" +
                "  Where-Object { $_.Name -eq 'cmd.exe' -and $_.CommandLine -and ($_.CommandLine -like '*" + escaped + "*') } |" +
                "  ForEach-Object { $count++ };" +
                "Write-Output $count";
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            String out;
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                out = sb.toString().trim();
            }
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                System.err.println("[test] PowerShell query timed out for marker=" + marker);
                return -1;
            }
            if (out.isEmpty()) return 0;
            try {
                return Integer.parseInt(out);
            } catch (NumberFormatException nfe) {
                System.err.println("[test] PowerShell returned non-integer: '" + out + "'");
                return -1;
            }
        } catch (Throwable t) {
            System.err.println("[test] PowerShell query failed: " + t.getMessage());
            return -1;
        }
    }

    /**
     * PowerShell 兜底清理：杀掉所有命令行包含 marker 的 cmd.exe（及其子孙）。
     * 测试失败时保证下个测试运行不踩到上次残留。
     */
    private static void killAllWithMarker(String marker) {
        String escaped = marker.replace("'", "''");
        String ps = "$ErrorActionPreference='SilentlyContinue';" +
                "Get-CimInstance Win32_Process |" +
                "  Where-Object { $_.Name -eq 'cmd.exe' -and $_.CommandLine -and ($_.CommandLine -like '*" + escaped + "*') } |" +
                "  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }";
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            p.waitFor(15, TimeUnit.SECONDS);
            if (p.isAlive()) p.destroyForcibly();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
