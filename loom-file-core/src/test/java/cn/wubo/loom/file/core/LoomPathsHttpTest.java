package cn.wubo.loom.file.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoomPaths.userHttpDir 用户树路径派生")
class LoomPathsHttpTest {

    @Test
    @DisplayName("http 目录与 file / compile-workspaces 平级")
    void httpIsSiblingOfFileAndCompile(@TempDir Path tmp) {
        String base = tmp.toString();
        Path userRoot = LoomPaths.userRoot(base, "alice");
        assertThat(LoomPaths.userHttpDir(base, "alice")).isEqualTo(userRoot.resolve("http"));
        assertThat(LoomPaths.userFileDir(base, "alice")).isEqualTo(userRoot.resolve("file"));
        assertThat(LoomPaths.userCompileWorkspacesDir(base, "alice"))
                .isEqualTo(userRoot.resolve("compile-workspaces"));
    }

    @Test
    @DisplayName("不同用户互相看不到对方的 http 目录")
    void usersAreIsolated(@TempDir Path tmp) {
        String base = tmp.toString();
        assertThat(LoomPaths.userHttpDir(base, "alice"))
                .isNotEqualTo(LoomPaths.userHttpDir(base, "bob"));
    }

    @Test
    @DisplayName("username 消毒：路径不逃出 usersBasePath")
    void usernameIsSanitized(@TempDir Path tmp) {
        // Brief 的 doesNotContain("..") 与 sanitizeForDirName 现状不一致
        // (sanitize 显式允许 . 单字符,所以 .. 被保留为合法目录名片段;
        // 真正的安全属性是路径不逃出 base — 由 CompileWorkspaceRootResolutionTest
        // 的 usernameSanitized_preventsTraversalViaDirName 同模式锁)。
        Path evil = LoomPaths.userHttpDir(tmp.toString(), "../../etc");
        assertThat(evil.normalize().startsWith(tmp.toAbsolutePath().normalize())).isTrue();
        // 关键属性:用户目录一定落在 tmp 之内
        assertThat(evil.startsWith(tmp.toAbsolutePath())).isTrue();
    }

    @Test
    @DisplayName("username 为空回退 anonymous，不抛异常")
    void blankUsernameFallsBack(@TempDir Path tmp) {
        // Brief 原稿用 userHttpDir(...)=userRoot(...),少了 /http 后缀 —— 修正为
        // userHttpDir(...)=userRoot(...).resolve("http") 才符合 userHttpDir 的契约
        assertThat(LoomPaths.userHttpDir(tmp.toString(), null))
                .isEqualTo(LoomPaths.userRoot(tmp.toString(), "anonymous").resolve("http"));
        assertThat(LoomPaths.userHttpDir(tmp.toString(), "  "))
                .isEqualTo(LoomPaths.userRoot(tmp.toString(), "anonymous").resolve("http"));
    }
}
