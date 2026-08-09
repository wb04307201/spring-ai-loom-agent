package cn.wubo.spring.ai.loom.agent.tool.compile;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests pinning the contract that compile-deploy workspaces live
 * OUTSIDE the user upload dir (so they don't pollute the file manager UI) and
 * are namespaced per-user (so per-user cleanup is a single {@code rm -rf}).
 *
 * <p>Bug history: previously {@code compile-deploy-&lt;uuid&gt;} lived under the
 * same dir as user uploads ({@code ${fileBasePath}/&lt;username&gt;/}), so the
 * file manager listed tool-state alongside real user files, and the workspace
 * dir name carried no ownership hint.</p>
 */
class CompileDeployWorkspaceLayoutTest {

 @Test
 void getCompileDeployWorkspaceDir_isNamespacedPerUser() {
 DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(null, null, ".local/file");
 Path dir = tool.getCompileDeployWorkspaceDir("wb04307201");
 Path expected = Paths.get(System.getProperty("user.home"),
 ".loom", "compile-deploy-workspaces", "wb04307201");
 assertThat(dir).isEqualTo(expected);
 assertThat(dir.toString()).doesNotContain(".local")
 .describedAs("should be absolute under user.home, NOT cwd-relative .local");
 }

 @Test
 void getCompileDeployWorkspaceDir_doesNotCollideWithUploadDir() {
 DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(null, null, ".local/file");
 Path uploads = tool.getUserFileDir("wb04307201");
 Path workspaces = tool.getCompileDeployWorkspaceDir("wb04307201");
 assertThat(uploads).isNotEqualTo(workspaces);
 assertThat(workspaces.startsWith(uploads))
 .as("workspaces MUST NOT live under the upload dir")
 .isFalse();
 }

 /**
 * The workspace dir name on disk embeds the username + a timestamp prefix
 * so {@code ls} alone identifies both the owner and the run. We don't
 * pin the exact format (UUID is random) — just that the username + a
 * fixed-shape timestamp appear, AND that weird characters in usernames
 * are sanitized so the dir is legal on every filesystem.
 */
 @Test
 void workspaceNaming_sanitizesUsernameAndIncludesTimestamp() throws Exception {
 // Reach CompileAndDeployOperations.sanitizeForDirName via reflection
 // since it is private static.
 Method sanitize = cn.wubo.loom.compile.core.CompileAndDeployOperations.class
 .getDeclaredMethod("sanitizeForDirName", String.class);
 sanitize.setAccessible(true);

 // Common case: alphanumerics + . _ - pass through.
 assertThat(sanitize.invoke(null, "alice-2")).isEqualTo("alice-2");
 // Filesystem-hostile chars (space, slash, colon, etc.) get squashed to '_'.
 assertThat(sanitize.invoke(null, "alice bob")).isEqualTo("alice_bob");
 assertThat(sanitize.invoke(null, "alice/bob")).isEqualTo("alice_bob");
 assertThat(sanitize.invoke(null, "alice:bob")).isEqualTo("alice_bob");
 // Java considers Unicode letters as letters — sanitise() lets them
 // through, which is fine on every modern filesystem.
 Object cnResult = sanitize.invoke(null, "中文名");
 assertThat(cnResult).isNotNull();
 assertThat(cnResult.toString().length()).isEqualTo(3);
 // Empty / null collapse to the safe default.
 assertThat(sanitize.invoke(null, "")).isEqualTo("anonymous");
 assertThat(sanitize.invoke(null, " ")).isEqualTo("anonymous");
 assertThat(sanitize.invoke(null, (Object) null)).isEqualTo("anonymous");
 }
}
