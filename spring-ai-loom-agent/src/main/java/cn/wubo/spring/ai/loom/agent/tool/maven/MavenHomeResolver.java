package cn.wubo.spring.ai.loom.agent.tool.maven;

import java.io.File;

/**
 * Maven 安装目录解析工具。
 * <p>
 * 多处工具（{@link DefaultMavenTool}、{@link cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool}）
 * 都需要根据配置 / 环境变量 / 自动探测找到一个可用的 {@code mvn} 可执行文件，
 * 把这部分逻辑集中到这里避免各工具各自实现、行为漂移。
 */
public final class MavenHomeResolver {

    private MavenHomeResolver() {
    }

    /**
     * 解析出可用的 Maven 安装目录。
     * <p>
     * 优先级：
     * <ol>
     * <li>显式配置的路径（{@code configured}）</li>
     * <li>环境变量 {@code MAVEN_HOME}</li>
     * <li>环境变量 {@code M2_HOME}</li>
     * <li>Windows 常见路径下自动探测：
     * {@code C:\developer\apache-maven-*},
     * {@code C:\Program Files\Apache Maven},
     * {@code C:\Program Files (x86)\Apache Maven},
     * {@code C:\apache-maven-*},
     * {@code C:\Tools\maven*}，
     * 以及 {@code ~/.m2/wrapper/dists}</li>
     * </ol>
     * <p>
     * 故意不依赖系统 {@code PATH} 上的 {@code mvn} 命令——部分 Windows 环境下
     * {@code PATH} 中的 {@code mvn} 是 npm 包装脚本或已损坏的旧版本目录，
     * 调它会让 ProcessBuilder / maven-invoker 抛
     * "Error configuring command line" 或者意外把控制台切换到 Node 报错的乱码。
     *
     * @return Maven 安装目录的绝对路径，找不到返回 {@code null}
     */
    public static String resolve(String configured) {
        if (isValidMavenHome(configured)) {
            return new File(configured).getAbsolutePath();
        }
        String env = System.getenv("MAVEN_HOME");
        if (isValidMavenHome(env)) {
            return new File(env).getAbsolutePath();
        }
        env = System.getenv("M2_HOME");
        if (isValidMavenHome(env)) {
            return new File(env).getAbsolutePath();
        }
        File auto = autoDetectMavenHome();
        return auto != null ? auto.getAbsolutePath() : null;
    }

    /**
     * 校验给定路径是否是一个合法的 Maven 安装目录
     * （含 {@code bin/mvn} 或 {@code bin/mvn.cmd}）。
     */
    public static boolean isValidMavenHome(String path) {
        if (path == null || path.isBlank()) return false;
        File home = new File(path);
        if (!home.isDirectory()) return false;
        return new File(home, "bin/mvn").isFile()
                || new File(home, "bin/mvn.cmd").isFile()
                || new File(home, "bin/mvn.bat").isFile();
    }

    /**
     * 在 Windows 常见路径下扫描 Maven 安装目录。
     */
    public static File autoDetectMavenHome() {
        String[] roots = {
                "C:\\developer",
                "C:\\Program Files",
                "C:\\Program Files (x86)",
                "C:\\",
                "C:\\Tools",
                System.getProperty("user.home") + "\\.m2\\wrapper\\dists"
        };
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            File[] children = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.startsWith("apache-maven-") || n.equalsIgnoreCase("Apache Maven")
                        || n.equalsIgnoreCase("maven");
            });
            if (children == null) continue;
            // 排序后取最新（按目录名降序，版本号靠后的排前面）
            java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName).reversed());
            for (File child : children) {
                if (isValidMavenHome(child.getAbsolutePath())) {
                    return child;
                }
            }
        }
        return null;
    }
}
