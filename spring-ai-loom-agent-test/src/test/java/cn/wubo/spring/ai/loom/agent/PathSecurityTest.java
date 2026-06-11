package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.git.DefaultGitTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for path sandbox in DefaultGitTool.
 * Verifies that all path parameters are properly restricted to {fileBasePath}/{username}/.
 */
class PathSecurityTest {

    private DefaultGitTool tool;
    private Map<String, Object> context;

    @BeforeEach
    void setUp() {
        LoomAgentProperties props = new LoomAgentProperties();
        props.setFileBasePath(".local/file");
        props.setGitUsername("testuser");
        tool = new DefaultGitTool(props);
        context = new HashMap<>();
        context.put("username", "testuser");
    }

    private static org.springframework.ai.chat.model.ToolContext tc(Map<String, Object> ctx) {
        return new org.springframework.ai.chat.model.ToolContext(ctx);
    }

    /**
     * Helper: invoke method and unwrap InvocationTargetException to check for SecurityException
     */
    private static void assertSecurityExceptionOnInvoke(Method method, Object obj, Object... args) {
        try {
            method.invoke(obj, args);
            fail("Expected SecurityException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) {
                return; // Expected
            }
            throw new AssertionError("Expected SecurityException but got: " + e.getCause(), e);
        } catch (Exception e) {
            throw new AssertionError("Unexpected exception: " + e, e);
        }
    }

    /**
     * Test: resolvePath must reject absolute paths
     */
    @Test
    void resolvePathRejectsAbsolutePath() throws Exception {
        Method resolvePath = DefaultGitTool.class.getDeclaredMethod(
                "resolvePath", org.springframework.ai.chat.model.ToolContext.class, String.class);
        resolvePath.setAccessible(true);

        assertSecurityExceptionOnInvoke(resolvePath, tool, tc(context), "C:/Windows/System32");
        assertSecurityExceptionOnInvoke(resolvePath, tool, tc(context), "/etc/passwd");

        // Relative path should succeed
        Object result = resolvePath.invoke(tool, tc(context), "my-repo");
        assertNotNull(result);
        assertTrue(result.toString().contains("my-repo"));
    }

    /**
     * Test: resolvePath must prevent directory traversal (../)
     */
    @Test
    void resolvePathPreventsTraversal() throws Exception {
        Method resolvePath = DefaultGitTool.class.getDeclaredMethod(
                "resolvePath", org.springframework.ai.chat.model.ToolContext.class, String.class);
        resolvePath.setAccessible(true);

        assertSecurityExceptionOnInvoke(resolvePath, tool, tc(context), "../../../etc/passwd");
        assertSecurityExceptionOnInvoke(resolvePath, tool, tc(context), "..\\..\\..\\Windows");
    }

    /**
     * Test: validatePathInUserDir must reject absolute paths outside user dir
     */
    @Test
    void validatePathInUserDirRejectsAbsolutePath() throws Exception {
        Method validate = DefaultGitTool.class.getDeclaredMethod(
                "validatePathInUserDir", org.springframework.ai.chat.model.ToolContext.class, String.class, String.class);
        validate.setAccessible(true);

        assertSecurityExceptionOnInvoke(validate, tool, tc(context), "C:/Windows/System32", "worktreePath");
    }

    /**
     * Test: getWorkingDir must reject stored path outside user dir
     */
    @Test
    void getWorkingDirRejectsStoredPath() throws Exception {
        context.put("gitWorkingDir", "C:/Windows/System32");

        Method getWd = DefaultGitTool.class.getDeclaredMethod("getWorkingDir", org.springframework.ai.chat.model.ToolContext.class);
        getWd.setAccessible(true);

        try {
            getWd.invoke(tool, tc(context));
            fail("Expected SecurityException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) {
                return; // Expected
            }
            throw new AssertionError("Expected SecurityException but got: " + e.getCause(), e);
        }
    }

    /**
     * Test: getWorkingDir must accept valid stored path
     */
    @Test
    void getWorkingDirAcceptsValidPath() throws Exception {
        context.put("gitWorkingDir", ".local/file/testuser/my-repo");

        Method getWd = DefaultGitTool.class.getDeclaredMethod("getWorkingDir", org.springframework.ai.chat.model.ToolContext.class);
        getWd.setAccessible(true);

        Object result = getWd.invoke(tool, tc(context));
        assertNotNull(result);
        // getWorkingDir now returns absolute path
        assertEquals(Paths.get(".local/file/testuser/my-repo").toAbsolutePath().normalize(), result);
    }

    /**
     * Test: validatePathInUserDir accepts relative paths
     */
    @Test
    void validatePathInUserDirAcceptsRelativePath() throws Exception {
        Method validate = DefaultGitTool.class.getDeclaredMethod(
                "validatePathInUserDir", org.springframework.ai.chat.model.ToolContext.class, String.class, String.class);
        validate.setAccessible(true);

        Object result = validate.invoke(tool, tc(context), "worktrees/feature-branch", "worktreePath");
        assertNotNull(result);
        assertTrue(result.toString().contains("feature-branch"));
    }

    /**
     * Test: gitClone with absolute path is rejected
     */
    @Test
    void gitCloneRejectsAbsolutePath() {
        String result = tool.gitClone(
                "https://example.com/repo.git",
                "C:/Windows/System32/malicious",
                null, null, null, null,
                tc(context)
        );
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "gitClone should reject absolute path, got: " + result);
    }

    /**
     * Test: gitInit with absolute path is rejected
     */
    @Test
    void gitInitRejectsAbsolutePath() {
        String result = tool.gitInit(
                "C:/Windows/System32/malicious",
                null, null,
                tc(context)
        );
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "gitInit should reject absolute path, got: " + result);
    }

    /**
     * Test: gitSetWorkingDir with absolute path is rejected
     */
    @Test
    void gitSetWorkingDirRejectsAbsolutePath() {
        String result = tool.gitSetWorkingDir(
                "C:/Windows/System32",
                null, null,
                tc(context)
        );
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "gitSetWorkingDir should reject absolute path, got: " + result);
    }
}
