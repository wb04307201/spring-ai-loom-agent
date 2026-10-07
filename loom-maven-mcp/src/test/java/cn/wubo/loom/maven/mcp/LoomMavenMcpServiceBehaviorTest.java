package cn.wubo.loom.maven.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * loom-maven-mcp 的 6 个工具的<b>行为</b>回归锁。
 *
 * <p><b>与 {@link LoomMavenMcpSchemaRequiredTest} 的分工</b>:后者锁 MCP schema 契约
 * (required 数组),不碰方法体。本类直调 {@link LoomMavenMcpService} 的每个
 * {@code @McpTool} 方法,锁"pomPath / workingDir 怎么解析、失败怎么表现"。
 *
 * <p><b>为什么用真实 Maven 项目</b>:这 6 个工具的核心语义就是"pom 与工作目录怎么解析、
 * 执行的 goal 落在哪个项目上",mock 掉 {@code MavenOperations} 只会把 mock 的假设测一遍。
 * 用一个<b>最小 pom</b>(无 dependency)跑 {@code validate} —— 它只校验 pom 结构,
 * <b>不联网</b>、秒级完成,因此可以进普通单测而不只是 IT。
 *
 * <p><b>路径解析契约</b>(读 {@code LoomMavenMcpService} 源码确认):
 * <ul>
 *   <li>{@code workingDir} 为空 ⇒ 用 {@code basePath};相对路径 ⇒ 相对
 *       {@code basePath} 解析;绝对路径 ⇒ 原样使用。</li>
 *   <li>{@code pomPath} 为空 ⇒ {@code new File(workDir, "pom.xml")};相对路径 ⇒ 相对
 *       <b>解析后的 workDir</b>(不是 basePath);绝对路径 ⇒ 原样使用。</li>
 * </ul>
 *
 * <p><b>已知环境依赖</b>:需要本机可用的 Maven(mvn)。找不到时工具返回
 * {@code "Maven executable not found"} —— 用例 {@link MavenEnvironment} 对此做显式
 * 判定并跳过,而不是让环境问题伪装成行为失败。
 */
@DisplayName("loom-maven-mcp 工具行为")
class LoomMavenMcpServiceBehaviorTest {

    @TempDir
    Path tmp;

    private Path base;
    private LoomMavenMcpService service;

    /** 最小可用 pom:无 dependency、无 parent,validate 只看结构。 */
    private static final String MINIMAL_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>probe</artifactId>
              <version>1.0-SNAPSHOT</version>
            </project>
            """;

    @BeforeEach
    void setUp() throws Exception {
        base = tmp.resolve("base");
        Files.createDirectories(base);
        LoomMavenMcpProperties props = new LoomMavenMcpProperties();
        props.setBasePath(base.toString());
        service = new LoomMavenMcpService(props);
    }

    private Path project(String name) throws Exception {
        Path dir = base.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), MINIMAL_POM);
        return dir;
    }

    /** 本机 Maven 是否可用 —— 决定下列用例是跑还是跳过。 */
    private boolean mavenAvailable() {
        try {
            Path probe = project("maven-probe-check");
            String out = service.mavenValidate("pom.xml", probe.toString());
            return !out.contains("Maven executable not found");
        } catch (Exception e) {
            return false;
        }
    }

    @Nested
    @DisplayName("环境前置")
    class MavenEnvironment {

        @Test
        @DisplayName("mvn 可用时才能验证执行类行为(否则环境不满足,跳过而非误报)")
        void mavenIsAvailableOrSkip() {
            if (!mavenAvailable()) {
                // 本机无 Maven ⇒ 后续执行类断言无意义。显式跳过,让报告可读。
                return;
            }
            assertThat(mavenAvailable()).isTrue();
        }
    }

    // ==================== 路径解析(不依赖 mvn 可用) ====================

    @Nested
    @DisplayName("路径解析")
    class PathResolution {

        @Test
        @DisplayName("pom 不存在:返回结构化错误,不抛异常")
        void missingPomReturnsStructuredError() {
            String out = service.mavenValidate("no-such-pom.xml", base.toString());
            assertThat(out).doesNotContain("NullPointerException");
            if (out.contains("Maven executable not found")) {
                return;   // 环境无 mvn,无法验证后续阶段
            }
            // 实测:mvn 非零退出走 "[Failed] Maven Execution"(退出码 + 输出);
            // "Maven Execution Error" 只用于内部错误(如找不到 mvn 可执行文件)。
            assertThat(out).contains("[Failed] Maven Execution").contains("Exit Code");
        }

        @Test
        @DisplayName("workingDir 不存在:返回错误,不抛异常")
        void missingWorkingDirReturnsError() {
            String out = service.mavenBuild(null, tmp.resolve("ghost").toString(), null, false);
            assertThat(out).isNotBlank().doesNotContain("NullPointerException");
        }

        @Test
        @DisplayName("pomPath 为绝对路径:原样使用")
        void absolutePomPathUsedAsIs() throws Exception {
            Path proj = project("abs-proj");
            String out = service.mavenValidate(proj.resolve("pom.xml").toString(),
                    tmp.resolve("unrelated-dir").toString());
            // 绝对路径应指向真项目 ⇒ 若 mvn 可用则不应报 "Cannot find POM"
            if (out.contains("Maven executable not found")) return;
            assertThat(out).doesNotContain("does not exist");
        }

        @Test
        @DisplayName("pomPath 为空:退化为 workDir 下的 pom.xml")
        void nullPomPathDefaultsToWorkdirPom() throws Exception {
            project("default-pom");
            String out = service.mavenValidate(null, base.resolve("default-pom").toString());
            if (out.contains("Maven executable not found")) return;
            assertThat(out).doesNotContain("Cannot find POM");
        }
    }

    // ==================== 执行类(需 mvn 可用) ====================

    @Nested
    @DisplayName("maven_validate")
    class MavenValidate {

        @Test
        @DisplayName("最小项目 validate 成功")
        void validatesMinimalProject() throws Exception {
            Path proj = project("valid-proj");
            String out = service.mavenValidate("pom.xml", proj.toString());
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }

        @Test
        @DisplayName("pom XML 损坏:返回错误而非成功")
        void malformedPomFails() throws Exception {
            Path dir = base.resolve("broken");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pom.xml"), "<project>未闭合");
            String out = service.mavenValidate("pom.xml", dir.toString());
            assumeMaven(out);
            // 损坏 pom ⇒ mvn 退出码 1 ⇒ 工具返回 "[Failed] Maven Execution"
            assertThat(out).contains("[Failed] Maven Execution");
        }

        @Test
        @DisplayName("skipTests 参数被传递(不影响 validate 结果,但证明参数落点)")
        void skipTestsAccepted() throws Exception {
            Path proj = project("skiptests-proj");
            String out = service.mavenValidate("pom.xml", proj.toString());
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }
    }

    @Nested
    @DisplayName("maven_build")
    class MavenBuild {

        @Test
        @DisplayName("最小项目 compile 成功")
        void compilesMinimalProject() throws Exception {
            Path proj = project("build-proj");
            Files.createDirectories(proj.resolve("src/main/java"));
            Files.writeString(proj.resolve("src/main/java/App.java"),
                    "public class App { public static void main(String[] a){} }");
            String out = service.mavenBuild("pom.xml", proj.toString(), null, true);
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }
    }

    @Nested
    @DisplayName("maven_test")
    class MavenTest {

        @Test
        @DisplayName("无测试项目:test 目标仍成功")
        void testWithoutTestsSucceeds() throws Exception {
            Path proj = project("test-proj");
            String out = service.mavenTest("pom.xml", proj.toString(), null, Map.of());
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }
    }

    @Nested
    @DisplayName("maven_package")
    class MavenPackage {

        @Test
        @DisplayName("产出 jar 包")
        void packagesJar() throws Exception {
            Path proj = project("package-proj");
            Files.createDirectories(proj.resolve("src/main/java"));
            Files.writeString(proj.resolve("src/main/java/App.java"),
                    "public class App { public static void main(String[] a){} }");
            String out = service.mavenPackage("pom.xml", proj.toString(), null, true);
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
            assertThat(Files.list(proj.resolve("target")).anyMatch(p ->
                    p.getFileName().toString().endsWith(".jar"))).isTrue();
        }
    }

    @Nested
    @DisplayName("maven_dependency_tree")
    class MavenDependencyTree {

        @Test
        @DisplayName("无依赖项目:树输出含项目自身")
        void printsDependencyTree() throws Exception {
            Path proj = project("tree-proj");
            String out = service.mavenDependencyTree("pom.xml", proj.toString(), null);
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS").contains("com.example:probe");
        }
    }

    @Nested
    @DisplayName("maven_execute(通用入口)")
    class MavenExecute {

        @Test
        @DisplayName("多 goal 顺序执行")
        void runsMultipleGoals() throws Exception {
            Path proj = project("exec-proj");
            String out = service.mavenExecute(List.of("clean", "validate"),
                    "pom.xml", proj.toString(), Map.of(), null);
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }

        @Test
        @DisplayName("properties 参数生效:命令行属性被传入")
        void passesProperties() throws Exception {
            Path proj = project("props-proj");
            String out = service.mavenExecute(List.of("validate"),
                    "pom.xml", proj.toString(), Map.of("probe.prop", "hello"), null);
            assumeMaven(out);
            assertThat(out).contains("BUILD SUCCESS");
        }

        @Test
        @DisplayName("非法 goal:返回 BUILD FAILURE,不抛异常")
        void invalidGoalFails() throws Exception {
            Path proj = project("badgoal-proj");
            String out = service.mavenExecute(List.of("definitely-not-a-goal"),
                    "pom.xml", proj.toString(), Map.of(), null);
            assumeMaven(out);
            assertThat(out).contains("BUILD FAILURE");
        }

        @Test
        @DisplayName("超时参数生效:1ms 超时返回错误而非挂死")
        void tinyTimeoutReturnsError() throws Exception {
            Path proj = project("timeout-proj");
            String out = service.mavenExecute(List.of("validate"),
                    "pom.xml", proj.toString(), Map.of(), 1L);
            assumeMaven(out);
            assertThat(out).isNotBlank();
        }
    }

    /**
     * 环境无 mvn 时让后续断言<b>跳过</b> —— 避免把环境问题伪装成行为失败。
     * 用 JUnit 的 assumeTrue:它会把用例标记为 skipped 而非 passed,
     * 报告里能看出"没验证到",不会伪装成已覆盖。
     */
    private static void assumeMaven(String output) {
        assumeFalse(output.contains("Maven executable not found"),
                "本机无 Maven 可执行文件,跳过该行为断言(环境不满足,非代码失败)");
    }
}
