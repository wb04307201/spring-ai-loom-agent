package cn.wubo.spring.ai.loom.agent.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the path-default contract: every filesystem-backed property should
 * land under {@code ${user.home}/.loom/}, never a cwd-relative
 * {@code .local/...}. A single {@code rm -rf ~/.loom/} should reach
 * everything; if a future change reintroduces {@code .local} defaults, this
 * test fails loudly.
 */
class LoomAgentPropertiesDefaultsTest {

 @Test
 void fileBasePath_defaultsUnderUserHome_dot_loom() {
 LoomAgentProperties p = new LoomAgentProperties();
 assertThat(p.getFileBasePath()).startsWith(System.getProperty("user.home"));
 assertThat(p.getFileBasePath()).contains(".loom").contains("file");
 assertThat(p.getFileBasePath()).doesNotContain(".local");
 }

 @Test
 void knowledgeBasePath_defaultsUnderUserHome_dot_loom() {
 LoomAgentProperties p = new LoomAgentProperties();
 assertThat(p.getKnowledgeBasePath())
 .startsWith(System.getProperty("user.home"))
 .contains(".loom").contains("knowledge")
 .doesNotContain(".local");
 }

 @Test
 void datasourceDir_defaultsUnderUserHome_dot_loom() {
 LoomAgentProperties p = new LoomAgentProperties();
 assertThat(p.getDatasourceDir())
 .startsWith(System.getProperty("user.home"))
 .contains(".loom").contains("datasource")
 .doesNotContain(".local");
 }

 @Test
 void jvectorIndexPath_defaultsUnderUserHome_dot_loom() {
 LoomAgentProperties p = new LoomAgentProperties();
 assertThat(p.getJvector().getIndexPath())
 .startsWith(System.getProperty("user.home"))
 .contains(".loom").contains("jvector-index")
 .doesNotContain(".local");
 }

 @Test
 void loomHome_isExposedAndAbsolute() {
 LoomAgentProperties p = new LoomAgentProperties();
 // Compare via Path so OS-specific separators don't matter (Windows
 // emits backslashes for the user.home part and our string concat emits
 // forward slashes — both resolve to the same Path).
 assertThat(Paths.get(p.getLoomHome()))
 .isEqualTo(Paths.get(System.getProperty("user.home"), ".loom"));
 }

 @Test
 void singleRm_rfTargets_allUserState() {
 LoomAgentProperties p = new LoomAgentProperties();
 String loomHome = p.getLoomHome();
 // String compare (not Path.startsWith) so the test doesn't try to
 // resolve on disk — the knowledge-dir may not exist yet on a fresh
 // user setup, and the contract we want to pin is purely about the
 // configured default, not the filesystem state.
 for (String path : new String[]{
 p.getFileBasePath(),
 p.getKnowledgeBasePath(),
 p.getDatasourceDir(),
 p.getJvector().getIndexPath()
 }) {
 assertThat(path)
 .as("path %s must live under loomHome %s", path, loomHome)
 .startsWith(loomHome + "/");
 }
 }
}
