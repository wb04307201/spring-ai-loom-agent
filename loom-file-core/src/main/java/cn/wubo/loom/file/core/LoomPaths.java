package cn.wubo.loom.file.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全仓共享的 Loom 本地存储路径真源（单一来源）。
 *
 * <p>存储布局（用户树模型）：
 * <pre>
 * ~/.loom/                          ← loomHome（唯一根）
 * ├── datasource/                   ← 全局 H2
 * └── users/{username}/             ← usersBasePath（默认 {loomHome}/users）
 *     ├── file/                     ← 上传 + file/git/maven/render 工具沙箱根
 *     └── compile-workspaces/       ← 编译部署 workspace（成功即删）
 * </pre>
 *
 * <p>路径双轨制：主库（多租户嵌入）一切用户路径经本类从 usersBasePath 派生，
 * 零逃生门；独立 MCP server（单租户进程）走各自 {@code basePath} 参数配置，
 * 不使用用户树。</p>
 *
 * <p>历史教训：早期各工具用 cwd 相对的 {@code .local/file} 作为兜底 basePath，
 * 导致 {@code mvn spring-boot:run} 从父工程 vs test 模块启动时路径漂移。
 * 所有默认值必须是 {@code ${user.home}/.loom/...} 绝对路径。</p>
 */
public final class LoomPaths {

    private LoomPaths() {
    }

    /** loom 本地存储单根目录默认值：{@code ${user.home}/.loom}。 */
    public static final String DEFAULT_LOOM_HOME = System.getProperty("user.home") + "/.loom";

    /** 用户树根目录默认值：{@code ${user.home}/.loom/users}。usersBasePath 为 null/blank 时回退到本常量。 */
    public static final String DEFAULT_USERS_BASE = DEFAULT_LOOM_HOME + "/users";

    /** 用户树下的文件名：file 沙箱子目录。 */
    public static final String FILE_SUBDIR = "file";

    /** 用户树下的目录名：compile workspace 子目录。 */
    public static final String COMPILE_WORKSPACES_SUBDIR = "compile-workspaces";

    /**
     * 归一化 usersBasePath：null/blank → {@link #DEFAULT_USERS_BASE}，否则原样返回。
     */
    public static String orDefaultUsersBase(String usersBasePath) {
        return (usersBasePath != null && !usersBasePath.isBlank()) ? usersBasePath : DEFAULT_USERS_BASE;
    }

    /**
     * 用户根目录：{@code {usersBase}/{username}}。username 经 {@link #sanitizeForDirName}
     * 消毒（防 {@code ..} / 路径分隔符逃逸，filesystem-safe）。
     */
    public static Path userRoot(String usersBasePath, String username) {
        return Paths.get(orDefaultUsersBase(usersBasePath), sanitizeForDirName(username));
    }

    /**
     * 用户上传 + 工具沙箱目录：{@code {usersBase}/{username}/file}。
     * file/git/maven/render 工具与 DefaultUpload 的公共沙箱根。
     */
    public static Path userFileDir(String usersBasePath, String username) {
        return userRoot(usersBasePath, username).resolve(FILE_SUBDIR);
    }

    /**
     * 用户编译部署 workspace 目录：{@code {usersBase}/{username}/compile-workspaces}。
     * 与 file/ 平级（兄弟目录），file 工具沙箱够不到、UI 不混列。
     */
    public static Path userCompileWorkspacesDir(String usersBasePath, String username) {
        return userRoot(usersBasePath, username).resolve(COMPILE_WORKSPACES_SUBDIR);
    }

    /**
     * 把 username 消毒成合法目录名：filesystem-hostile 字符（空格/斜杠/冒号等）→ {@code _}；
     * 空/null → {@code anonymous}；字母数字与 {@code . _ -} 原样保留（Unicode 字母放行）。
     *
     * <p>与 {@code CompileAndDeployOperations.sanitizeForDirName} 同规则 —— 该私有方法
     * 服务于单次运行目录名，本方法服务于用户树目录名，两处独立实现但语义一致。</p>
     */
    public static String sanitizeForDirName(String raw) {
        if (raw == null) {
            return "anonymous";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "anonymous";
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String result = sb.toString();
        return result.isEmpty() ? "anonymous" : result;
    }
}
