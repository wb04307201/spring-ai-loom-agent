package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 {@code buildTool} 入参或 marker 文件自动选 {@link BuildStrategy}。
 * <p>
 * 解析顺序：
 * <ol>
 *   <li>{@code forBuildTool(tool)} 显式：tool 非空 → 按别名精确匹配</li>
 *   <li>{@code autoDetect(dir)} 隐式：按 marker 文件优先级 pom.xml > package.json > requirements.txt/pyproject.toml</li>
 *   <li>两个方法都返回 null / 抛 IAE —— 调用方决定是否向上抛</li>
 * </ol>
 */
public final class BuildStrategyFactory {

    /** {@code forBuildTool} 的别名 → strategy 映射。 */
    private static final Map<String, BuildStrategy> BY_ALIAS = Map.of(
            "maven",         new MavenBuildStrategy(),
            "mvn",           new MavenBuildStrategy(),
            "npm",           new NpmBackendBuildStrategy(),
            "node",          new NpmBackendBuildStrategy(),
            "npm-frontend",  new NpmFrontendBuildStrategy(),
            "node-frontend", new NpmFrontendBuildStrategy(),
            "frontend",      new NpmFrontendBuildStrategy(),
            "pip",           new PythonBuildStrategy(),
            "python",        new PythonBuildStrategy()
    );

    /** {@code autoDetect} 的 marker 文件 → strategy 映射（按优先级排列）。 */
    private static final List<MarkerEntry> AUTO_DETECT = List.of(
            new MarkerEntry("pom.xml",         new MavenBuildStrategy()),
            new MarkerEntry("package.json",    new NpmBackendBuildStrategy()),
            new MarkerEntry("requirements.txt", new PythonBuildStrategy()),
            new MarkerEntry("pyproject.toml",  new PythonBuildStrategy())
    );

    private BuildStrategyFactory() {}

    /**
     * 显式按别名解析。tool 为 null / blank 时返回 null（让调用方走 autoDetect）。
     * 未知别名抛 {@link IllegalArgumentException}（让 LLM 看到错误信息后改用正确别名）。
     */
    public static BuildStrategy forBuildTool(String tool) {
        if (tool == null || tool.isBlank()) return null;
        String key = tool.trim().toLowerCase(Locale.ROOT);
        BuildStrategy s = BY_ALIAS.get(key);
        if (s == null) {
            throw new IllegalArgumentException(
                    "未知的 buildTool='" + tool + "'，支持：maven / npm / npm-frontend / pip");
        }
        return s;
    }

    /**
     * 按目录中第一个匹配的 marker 文件选 strategy。
     * <p>
     * 优先级：{@code pom.xml} > {@code package.json} > {@code requirements.txt} > {@code pyproject.toml}。
     * 多个同时存在时返回第一个（见上）—— 用户应传 {@code buildTool} 显式覆盖歧义。
     * 全部缺失抛 {@link IllegalArgumentException}。
     */
    public static BuildStrategy autoDetect(Path projectDir) {
        for (MarkerEntry e : AUTO_DETECT) {
            if (Files.isRegularFile(projectDir.resolve(e.marker()))) {
                return e.strategy();
            }
        }
        throw new IllegalArgumentException(
                "无法识别项目类型：项目根目录 " + projectDir + " 中没有 pom.xml / package.json / requirements.txt / pyproject.toml 任何一个。"
                        + "请通过 buildTool 入参显式指定（maven / npm / npm-frontend / pip）");
    }

    private record MarkerEntry(String marker, BuildStrategy strategy) {}
}
