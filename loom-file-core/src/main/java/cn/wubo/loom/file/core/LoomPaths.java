package cn.wubo.loom.file.core;

/**
 * 全仓共享的 Loom 本地存储路径常量。
 *
 * <p>历史教训：早期各工具用 cwd 相对的 {@code .local/file} 作为兜底 basePath，
 * 导致 {@code mvn spring-boot:run} 从父工程 vs test 模块启动时路径漂移
 * （UI 列出项目源码而非用户文件）。所有兜底默认值必须是
 * {@code ${user.home}/.loom/...} 绝对路径，且单一来源在本类。</p>
 */
public final class LoomPaths {

    private LoomPaths() {
    }

    /** 用户文件根目录默认值：{@code ${user.home}/.loom/file}。basePath 为 null/blank 时统一回退到本常量。 */
    public static final String DEFAULT_FILE_BASE = System.getProperty("user.home") + "/.loom/file";

    /** loom 本地存储单根目录默认值：{@code ${user.home}/.loom}。 */
    public static final String DEFAULT_LOOM_HOME = System.getProperty("user.home") + "/.loom";

    /** compile-deploy workspace 根目录名（相对 loomHome）。 */
    public static final String COMPILE_WORKSPACES_DIR = "compile-deploy-workspaces";

    /**
     * 归一化 basePath：null/blank → {@link #DEFAULT_FILE_BASE}，否则原样返回。
     */
    public static String orDefaultFileBase(String basePath) {
        return (basePath != null && !basePath.isBlank()) ? basePath : DEFAULT_FILE_BASE;
    }
}
