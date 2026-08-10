package cn.wubo.loom.compile.core.strategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto-select BuildStrategy by buildTool alias or marker file detection.
 */
public final class BuildStrategyFactory {

    private static final Map<String, BuildStrategy> BY_ALIAS = Map.of(
            "maven", new MavenBuildStrategy(),
            "mvn", new MavenBuildStrategy(),
            "npm", new NpmBackendBuildStrategy(),
            "node", new NpmBackendBuildStrategy(),
            "npm-frontend", new NpmFrontendBuildStrategy(),
            "node-frontend", new NpmFrontendBuildStrategy(),
            "frontend", new NpmFrontendBuildStrategy(),
            "pip", new PythonBuildStrategy(),
            "python", new PythonBuildStrategy()
    );

    private static final List<MarkerEntry> AUTO_DETECT = List.of(
            new MarkerEntry("pom.xml", new MavenBuildStrategy()),
            new MarkerEntry("package.json", new NpmBackendBuildStrategy()),
            new MarkerEntry("requirements.txt", new PythonBuildStrategy()),
            new MarkerEntry("pyproject.toml", new PythonBuildStrategy())
    );

    private BuildStrategyFactory() {
    }

    public static BuildStrategy forBuildTool(String tool) {
        if (tool == null || tool.isBlank()) return null;
        String key = tool.trim().toLowerCase(Locale.ROOT);
        BuildStrategy s = BY_ALIAS.get(key);
        if (s == null) {
            throw new IllegalArgumentException(
                    "Unknown buildTool='" + tool + "', supported: maven / npm / npm-frontend / pip");
        }
        return s;
    }

    public static BuildStrategy autoDetect(Path projectDir) {
        for (MarkerEntry e : AUTO_DETECT) {
            if (Files.isRegularFile(projectDir.resolve(e.marker()))) {
                return e.strategy();
            }
        }
        throw new IllegalArgumentException(
                "Cannot detect project type: no pom.xml / package.json / requirements.txt / pyproject.toml in " + projectDir
                        + ". Specify buildTool explicitly (maven / npm / npm-frontend / pip)");
    }

    private record MarkerEntry(String marker, BuildStrategy strategy) {
    }
}
