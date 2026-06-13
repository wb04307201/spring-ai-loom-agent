package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultGitTool 单元测试
 * <p>
 * 使用临时目录作为 user file 目录，覆盖：
 * 1. gitInit 初始化仓库 + 默认分支
 * 2. gitInit 越权拒绝（绝对路径、.. 穿越）
 * 3. gitStatus 在新仓库/有文件状态下的输出
 * 4. gitAdd + gitCommit 完整流程 + gitLog
 * 5. gitSetWorkingDir / gitClearWorkingDir 工作目录管理
 * 6. gitDiff 比较工作区与 HEAD
 * 7. gitBranch list/create/delete
 * 8. gitCheckout 切换分支
 * 9. gitRemote list/add/get-url/remove
 * 10. gitTag list/create
 * 11. gitWrapupInstructions 输出 checklist
 * 12. getWorkingDir 拒绝外部路径
 */
@DisplayName("DefaultGitTool 单元测试")
class DefaultGitToolTest {

    private DefaultGitTool tool;
    private Path tmpRoot;
    private String username;

    @BeforeEach
    void setUp() throws IOException {
        LoomAgentProperties props = new LoomAgentProperties();
        // fileBasePath 用 tmpRoot 父级，username 用 tmpRoot 名
        tmpRoot = Files.createTempDirectory("loom-gittest-");
        username = tmpRoot.getFileName().toString();
        props.setFileBasePath(tmpRoot.getParent().toString());
        props.setGitUsername("git-user");
        props.setGitToken("git-token");
        tool = new DefaultGitTool(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        // JGit 在 Windows 上可能持有 .git/objects 句柄，主动 GC + 短暂等待
        System.gc();
        Thread.sleep(200);
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            // 多次尝试删除（处理临时文件锁）
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Files.walkFileTree(tmpRoot, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            try { Files.delete(file); } catch (IOException ignored) { }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            try { Files.delete(dir); } catch (IOException ignored) { }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    break;
                } catch (IOException e) {
                    if (attempt == 2) throw e;
                    Thread.sleep(200);
                }
            }
        }
    }

    private ToolContext ctx() {
        return ctx(null);
    }

    private ToolContext ctx(String workingDir) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        if (workingDir != null) m.put("gitWorkingDir", workingDir);
        return new ToolContext(m);
    }

    // ==================== gitClone 超时 ====================

    /**
     * 验证 gitClone 在网络挂死时不会无限阻塞整个聊天会话。
     * <p>
     * 旧实现下，{@code CloneCommand.call()} 没有 setTimeout，遇到 gitee 慢
     * 或网络抖动会无限挂死，整个工具调用链跟着卡住。下游 LLM 看不到任何反馈，
     * 表现就是"步骤 2 克隆代码仓库就停了"。
     * <p>
     * 现在的实现走 {@code setTimeout(remoteTimeoutSeconds)}，超时应在
     * transport 抛 {@code TransportException}，工具返回包含"失败"的结果。
     * <p>
     * 用一个本地 TCP "黑洞" server：accept 但永不写数据，模拟挂死 remote。
     */
    @Test
    @DisplayName("gitClone 在远端挂死时会被超时打断，不再无限阻塞")
    void gitClone_hangsThenTimesOut() throws Exception {
        // 启动黑洞 server：accept 但永不返回 HTTP 响应
        java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.List<java.net.Socket> held = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread acceptor = new Thread(() -> {
            while (!stop.get()) {
                try {
                    java.net.Socket s = server.accept();
                    held.add(s);
                    // 不读、不写 —— 让客户端 HTTP read 永远等
                } catch (java.io.IOException e) {
                    return;
                }
            }
        }, "blackhole-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        try {
            // 用一个 2s 超时的工具（生产默认 60s，测试里太慢）
            LoomAgentProperties fastProps = new LoomAgentProperties();
            fastProps.setFileBasePath(tmpRoot.getParent().toString());
            fastProps.getGit().setRemoteTimeoutSeconds(2);
            DefaultGitTool fastTool = new DefaultGitTool(fastProps);

            String url = "http://127.0.0.1:" + port + "/never-responds.git";
            long start = System.currentTimeMillis();
            String result = fastTool.gitClone(url, "hung-clone", null, null, null, null, ctx());
            long cost = System.currentTimeMillis() - start;

            // 关键断言 1：必须返回失败，不能无限阻塞
            assertTrue(result.contains("失败"),
                    "应返回失败结果。实际:\n" + result);

            // 关键断言 2：耗时应在 timeout(2s) + 一些 jgit 自身缓冲(<=10s) 内
            //           —— 证明 setTimeout 真的生效了，不是依赖 Future.cancel
            assertTrue(cost < 15_000,
                    "应被超时打断，不应无限阻塞。实际耗时 " + cost + "ms");
            assertTrue(cost >= 1_500,
                    "应在超时(2s)左右才返回，过早返回(<1.5s) 说明配置没生效。实际 " + cost + "ms");
        } finally {
            stop.set(true);
            try { server.close(); } catch (IOException ignored) { }
            for (java.net.Socket s : held) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ==================== Init / Clone ====================

    @Test
    @DisplayName("gitInit 初始化仓库后 HEAD 存在")
    void gitInit_createsRepo() {
        String result = tool.gitInit("repo-a", "main", false, ctx());
        assertTrue(result.contains("已初始化"), "应提示已初始化: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("repo-a/.git")), ".git 目录应被创建");
    }

    /**
     * 提交一个空 README.md，让仓库有一个 HEAD 提交 ——
     * 因为空仓库上 JGit 的 status/log/branch/diff 等操作会抛 NoHeadException。
     */
    private ToolContext commitReadme(String repoName, String msg) throws IOException {
        Path repoDir = tmpRoot.resolve(repoName);
        Files.writeString(repoDir.resolve("README.md"), "# " + repoName, StandardCharsets.UTF_8);
        ToolContext wdCtx = ctx(repoDir.toString());
        String add = tool.gitAdd(List.of("."), false, true, false, wdCtx);
        // 即使 add 抛 NoHeadException 也尝试
        tool.gitCommit(msg, null, null, false, true, false, null, wdCtx);
        return wdCtx;
    }

    @Test
    @DisplayName("gitInit 绝对路径越权返回错误")
    void gitInit_absolutePathRejected() {
        String result = tool.gitInit("/etc/malicious", "main", false, ctx());
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "绝对路径应被拒绝: " + result);
    }

    @Test
    @DisplayName("gitInit .. 穿越越权返回错误")
    void gitInit_traversalRejected() {
        String result = tool.gitInit("../escape", "main", false, ctx());
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "路径穿越应被拒绝: " + result);
    }

    @Test
    @DisplayName("gitClone 绝对路径越权返回错误")
    void gitClone_absolutePathRejected() {
        String result = tool.gitClone("https://example.com/repo.git",
                "/etc/malicious", null, null, false, false, ctx());
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "绝对路径应被拒绝: " + result);
    }

    // ==================== Basic operations ====================

    @Test
    @DisplayName("gitSetWorkingDir 初始化后 gitStatus 显示干净")
    void statusCleanAfterInit() {
        String initResult = tool.gitInit("repo-b", "main", false, ctx());
        assertTrue(initResult.contains("已初始化"), initResult);

        ToolContext wdCtx = ctx(tmpRoot.resolve("repo-b").toString());
        String status = tool.gitStatus(true, wdCtx);
        assertTrue(status.contains("工作区状态：干净"), "应显示干净工作区: " + status);
    }

    @Test
    @DisplayName("gitAdd + gitCommit 完整流程后 gitLog 可见")
    void addCommitLog() throws IOException {
        tool.gitInit("repo-c", "main", false, ctx());
        Path repoDir = tmpRoot.resolve("repo-c");
        Files.writeString(repoDir.resolve("README.md"), "hello", StandardCharsets.UTF_8);

        ToolContext wdCtx = ctx(repoDir.toString());
        String addResult = tool.gitAdd(List.of("."), false, true, false, wdCtx);
        assertTrue(addResult.contains("已暂存"), addResult);

        String commitResult = tool.gitCommit("initial commit", null, null,
                false, true, false, null, wdCtx);
        assertTrue(commitResult.contains("已提交") || commitResult.contains("commit"),
                "应成功提交: " + commitResult);

        String log = tool.gitLog(10, null, null, null, null, null, null, null,
                true, false, false, false, wdCtx);
        assertTrue(log.contains("initial commit"), "log 应包含 commit 消息: " + log);
    }

    @Test
    @DisplayName("gitDiff 显示新文件差异")
    void gitDiff_showsNewFile() throws IOException {
        tool.gitInit("repo-d", "main", false, ctx());
        Path repoDir = tmpRoot.resolve("repo-d");
        Files.writeString(repoDir.resolve("a.txt"), "old", StandardCharsets.UTF_8);
        ToolContext wdCtx = ctx(repoDir.toString());
        tool.gitAdd(List.of("."), false, true, false, wdCtx);
        tool.gitCommit("v1", null, null, false, true, false, null, wdCtx);

        Files.writeString(repoDir.resolve("a.txt"), "new", StandardCharsets.UTF_8);
        tool.gitAdd(List.of("."), false, true, false, wdCtx);
        tool.gitCommit("v2", null, null, false, true, false, null, wdCtx);

        // 比较两次提交：source=HEAD~1 target=HEAD
        String diff = tool.gitDiff("HEAD~1", "HEAD", null, false, true, true, 3, true, wdCtx);
        assertTrue(diff.contains("a.txt"), "diff 应包含文件名: " + diff);
        assertTrue(diff.contains("new"), "diff 应显示新内容: " + diff);
    }

    // ==================== Branch ====================

    @Test
    @DisplayName("gitBranch list 列出当前分支")
    void branchList() throws IOException {
        tool.gitInit("repo-e", "main", false, ctx());
        // 空仓库无法 list 分支，先建一个 commit
        commitReadme("repo-e", "init");
        String result = tool.gitBranch("list", null, null, null, false, true, false, null, null, null,
                ctx(tmpRoot.resolve("repo-e").toString()));
        assertTrue(result.contains("main"), "应列出 main 分支: " + result);
    }

    @Test
    @DisplayName("gitBranch create 新分支并 checkout")
    void branchCreateAndCheckout() throws IOException {
        tool.gitInit("repo-f", "main", false, ctx());
        commitReadme("repo-f", "init");
        ToolContext wdCtx = ctx(tmpRoot.resolve("repo-f").toString());

        String create = tool.gitBranch("create", "feature", null, null, false, false, false, null, null, null, wdCtx);
        assertTrue(create.contains("feature"), "应创建 feature 分支: " + create);

        String checkout = tool.gitCheckout("feature", false, false, null, false, wdCtx);
        assertTrue(checkout.contains("feature"), "应切换到 feature: " + checkout);
    }

    // ==================== Tag ====================

    @Test
    @DisplayName("gitTag create 后 list 可见")
    void tagCreateAndList() throws IOException {
        tool.gitInit("repo-g", "main", false, ctx());
        Path repoDir = tmpRoot.resolve("repo-g");
        Files.writeString(repoDir.resolve("f.txt"), "x", StandardCharsets.UTF_8);
        ToolContext wdCtx = ctx(repoDir.toString());
        tool.gitAdd(List.of("."), false, true, false, wdCtx);
        tool.gitCommit("init", null, null, false, true, false, null, wdCtx);

        String create = tool.gitTag("create", "v1.0", null, null, false, false, null, wdCtx);
        assertTrue(create.contains("已创建标签"), "应创建标签: " + create);

        String list = tool.gitTag("list", null, null, null, false, false, null, wdCtx);
        assertTrue(list.contains("v1.0"), "list 应包含 v1.0: " + list);
    }

    // ==================== Remote ====================

    @Test
    @DisplayName("gitRemote add + list + get-url + remove")
    void remoteLifecycle() {
        tool.gitInit("repo-h", "main", false, ctx());
        ToolContext wdCtx = ctx(tmpRoot.resolve("repo-h").toString());

        String add = tool.gitRemote("add", "origin", "https://example.com/r.git", null, false, wdCtx);
        assertTrue(add.contains("已添加"), add);

        String list = tool.gitRemote("list", null, null, null, false, wdCtx);
        assertTrue(list.contains("origin"), "list 应包含 origin: " + list);
        assertTrue(list.contains("https://example.com/r.git"), "应展示 URL: " + list);

        String getUrl = tool.gitRemote("get-url", "origin", null, null, false, wdCtx);
        assertTrue(getUrl.contains("https://example.com/r.git"), "get-url 应返回 URL: " + getUrl);

        String remove = tool.gitRemote("remove", "origin", null, null, false, wdCtx);
        assertTrue(remove.contains("已移除"), remove);
    }

    // ==================== Working Dir Management ====================

    @Test
    @DisplayName("gitSetWorkingDir 接受有效路径并初始化（带 initializeIfNotPresent）")
    void setWorkingDirValid() {
        // 验证模式 + 自动初始化：会自动 git init 然后 snapshot
        String result = tool.gitSetWorkingDir("new-repo", true, true, ctx());
        assertTrue(result.contains("已设置工作目录"), "应设置工作目录: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("new-repo/.git")),
                "应自动初始化仓库 (.git): " + result);
    }

    @Test
    @DisplayName("gitSetWorkingDir 绝对路径越权返回错误")
    void setWorkingDir_absolutePathRejected() {
        String result = tool.gitSetWorkingDir("/etc/malicious", false, false, ctx());
        assertTrue(result.contains("路径不能") || result.contains("不能超出"),
                "绝对路径应被拒绝: " + result);
    }

    @Test
    @DisplayName("gitSetWorkingDir 路径非 Git 仓库且不允许初始化时返回错误")
    void setWorkingDir_nonGitRepoRejected() {
        String result = tool.gitSetWorkingDir("plain-dir", true, false, ctx());
        assertTrue(result.contains("不是 Git 仓库"), "非 Git 仓库应被拒绝: " + result);
    }

    @Test
    @DisplayName("gitClearWorkingDir 需要明确确认")
    void clearWorkingDirRequiresConfirmation() {
        ToolContext wdCtx = ctx(tmpRoot.resolve("repo-i").toString());
        // 无确认
        String noConfirm = tool.gitClearWorkingDir("n", wdCtx);
        assertTrue(noConfirm.contains("需要确认"), "无确认应被拒绝: " + noConfirm);
        // 错误格式
        String badFormat = tool.gitClearWorkingDir("Yes please", wdCtx);
        assertTrue(badFormat.contains("需要确认"), "非 Y/y/Yes/yes 应被拒绝: " + badFormat);
        // 正确确认（ctx 不可写时忽略 remove 异常）
        try {
            String ok = tool.gitClearWorkingDir("Y", wdCtx);
            assertTrue(ok.contains("已清除"), "正确确认应清除: " + ok);
        } catch (UnsupportedOperationException ignored) {
            // 测试场景：ctx 不可写
        }
    }

    // ==================== Wrap-up ====================

    @Test
    @DisplayName("gitWrapupInstructions 输出 checklist")
    void wrapupInstructions() {
        tool.gitInit("repo-j", "main", false, ctx());
        ToolContext wdCtx = ctx(tmpRoot.resolve("repo-j").toString());
        String result = tool.gitWrapupInstructions("Y", true, wdCtx);
        assertTrue(result.contains("Git Wrap-up Protocol"), "应包含协议头: " + result);
        assertTrue(result.contains("Acceptance Criteria"), "应包含验收标准: " + result);
        assertTrue(result.contains("All changes are committed"), "应包含 checklist: " + result);
        assertTrue(result.contains("Annotated tag"), "createTag=true 应包含 tag 检查项: " + result);
    }

    // ==================== 上下文注入的 workingDir 安全校验 ====================

    @Test
    @DisplayName("getWorkingDir 拒绝 ctx 中超出用户目录的工作目录")
    void getWorkingDir_rejectsOutOfScope() {
        // 注入一个绝对路径（Windows 上用 C:\\），期望返回错误信息
        ToolContext bad = ctx("C:\\Windows\\System32");
        String result = tool.gitStatus(true, bad);
        assertTrue(result.contains("工作目录不在用户文件目录内") || result.contains("用户文件目录"),
                "应说明工作目录越界: " + result);
    }

    @Test
    @DisplayName("getWorkingDir 接受用户目录内的 workingDir")
    void getWorkingDir_acceptsInScope() throws Exception {
        // 在 tmpRoot/ok 下建一个 git 仓库
        Path ok = tmpRoot.resolve("ok");
        Files.createDirectories(ok);
        try (Git g = Git.init().setDirectory(ok.toFile()).call()) {
            // 空仓库即可
        }
        String status = tool.gitStatus(true, ctx(ok.toString()));
        assertTrue(status.contains("工作区状态"), "应能获取状态: " + status);
    }

    // ==================== 路径解析辅助验证 ====================

    @Test
    @DisplayName("Path 解析会把 username 目录作为根")
    void pathResolution() {
        // tmpRoot.getParent() 是 fileBasePath，username 是 tmpRoot 的名字
        Path userDir = Paths.get(tmpRoot.getParent().toString(), username);
        assertEquals(tmpRoot, userDir, "Paths.get(fileBasePath, username) 应等于 tmpRoot");
    }
}
