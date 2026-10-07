package cn.wubo.loom.git.mcp;

import cn.wubo.loom.git.core.GitOperations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Git MCP 服务端。basePath 从配置读取，workingDir 由调用方（LLM）显式传递。
 */
public class LoomGitMcpService {

    private final GitOperations gitOps;
    private final Path basePath;

    public LoomGitMcpService(LoomGitMcpProperties props) {
        this.basePath = Paths.get(props.getBasePath()).toAbsolutePath().normalize();
        this.gitOps = new GitOperations(props.getGitUsername(), props.getGitToken(), props.getRemoteTimeoutSeconds());
    }

    @McpTool(name = "git_init", description = "初始化一个新的 Git 仓库")
    public String gitInit(
            @McpToolParam(description = "仓库路径（相对于基础目录）") String path,
            @McpToolParam(description = "初始分支名", required = false) String initialBranch,
            @McpToolParam(description = "是否为 bare 仓库", required = false) Boolean bare) {
        return gitOps.gitInit(basePath, path, initialBranch, bare);
    }

    @McpTool(name = "git_clone", description = "克隆远程仓库")
    public String gitClone(
            @McpToolParam(description = "远程仓库 URL") String url,
            @McpToolParam(description = "本地路径（相对于基础目录）") String path,
            @McpToolParam(description = "分支名", required = false) String branch,
            @McpToolParam(description = "浅克隆深度", required = false) Integer depth,
            @McpToolParam(description = "是否为 bare 克隆", required = false) Boolean bare,
            @McpToolParam(description = "是否为镜像克隆", required = false) Boolean mirror) {
        return gitOps.gitClone(basePath, url, path, branch, depth, bare, mirror);
    }

    @McpTool(name = "git_status", description = "查看工作区状态")
    public String gitStatus(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "是否包含未跟踪文件", required = false) Boolean includeUntracked) {
        return gitOps.gitStatus(Paths.get(workingDir), includeUntracked);
    }

    @McpTool(name = "git_add", description = "暂存文件")
    public String gitAdd(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "要暂存的文件路径列表", required = false) List<String> paths,
            @McpToolParam(description = "仅暂存已跟踪文件的修改", required = false) Boolean update,
            @McpToolParam(description = "暂存所有文件（含未跟踪）", required = false) Boolean all,
            @McpToolParam(description = "强制暂存", required = false) Boolean force) {
        return gitOps.gitAdd(Paths.get(workingDir), paths, update, all, force);
    }

    @McpTool(name = "git_commit", description = "创建提交")
    public String gitCommit(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "提交信息") String message,
            @McpToolParam(description = "作者名", required = false) String authorName,
            @McpToolParam(description = "作者邮箱", required = false) String authorEmail,
            @McpToolParam(description = "修改上次提交", required = false) Boolean amend,
            @McpToolParam(description = "允许空提交", required = false) Boolean allowEmpty,
            @McpToolParam(description = "跳过 pre-commit hook", required = false) Boolean noVerify,
            @McpToolParam(description = "提交前暂存的文件", required = false) List<String> filesToStage) {
        return gitOps.gitCommit(Paths.get(workingDir), message, authorName, authorEmail, amend, allowEmpty, noVerify, filesToStage);
    }

    @McpTool(name = "git_diff", description = "查看 diff")
    public String gitDiff(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "源引用", required = false) String source,
            @McpToolParam(description = "目标引用", required = false) String target,
            @McpToolParam(description = "限定路径", required = false) List<String> paths,
            @McpToolParam(description = "仅已暂存", required = false) Boolean staged,
            @McpToolParam(description = "仅文件名", required = false) Boolean nameOnly,
            @McpToolParam(description = "统计信息", required = false) Boolean stat,
            @McpToolParam(description = "上下文行数", required = false) Integer contextLines,
            @McpToolParam(description = "自动排除锁文件", required = false) Boolean autoExclude) {
        return gitOps.gitDiff(Paths.get(workingDir), source, target, paths, staged, nameOnly, stat, contextLines, autoExclude);
    }

    @McpTool(name = "git_log", description = "查看提交历史")
    public String gitLog(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "最大条数", required = false) Integer maxCount,
            @McpToolParam(description = "跳过条数", required = false) Integer skip,
            @McpToolParam(description = "起始时间", required = false) String since,
            @McpToolParam(description = "截止时间", required = false) String until,
            @McpToolParam(description = "作者", required = false) String author,
            @McpToolParam(description = "消息匹配", required = false) String grep,
            @McpToolParam(description = "分支", required = false) String branch,
            @McpToolParam(description = "文件路径", required = false) String filePath,
            @McpToolParam(description = "单行格式", required = false) Boolean oneline,
            @McpToolParam(description = "统计信息", required = false) Boolean stat,
            @McpToolParam(description = "补丁格式", required = false) Boolean patch,
            @McpToolParam(description = "显示签名", required = false) Boolean showSignature) {
        return gitOps.gitLog(Paths.get(workingDir), maxCount, skip, since, until, author, grep, branch, filePath, oneline, stat, patch, showSignature);
    }

    @McpTool(name = "git_blame", description = "查看文件逐行修改历史")
    public String gitBlame(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "文件路径") String filePath,
            @McpToolParam(description = "起始行", required = false) Integer startLine,
            @McpToolParam(description = "结束行", required = false) Integer endLine,
            @McpToolParam(description = "忽略空白", required = false) Boolean ignoreWhitespace) {
        return gitOps.gitBlame(Paths.get(workingDir), filePath, startLine, endLine, ignoreWhitespace);
    }

    @McpTool(name = "git_checkout", description = "切换分支或恢复文件")
    public String gitCheckout(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "目标分支或提交") String target,
            @McpToolParam(description = "创建新分支", required = false) Boolean createBranch,
            @McpToolParam(description = "强制切换", required = false) Boolean force,
            @McpToolParam(description = "要恢复的文件", required = false) List<String> paths,
            @McpToolParam(description = "设置上游跟踪", required = false) Boolean track) {
        return gitOps.gitCheckout(Paths.get(workingDir), target, createBranch, force, paths, track);
    }

    @McpTool(name = "git_pull", description = "拉取远程更新")
    public String gitPull(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "远程名称", required = false) String remote,
            @McpToolParam(description = "分支名", required = false) String branch,
            @McpToolParam(description = "使用 rebase", required = false) Boolean rebase,
            @McpToolParam(description = "仅快进", required = false) Boolean fastForwardOnly) {
        return gitOps.gitPull(Paths.get(workingDir), remote, branch, rebase, fastForwardOnly);
    }

    @McpTool(name = "git_push", description = "推送到远程")
    public String gitPush(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "远程名称", required = false) String remote,
            @McpToolParam(description = "分支名", required = false) String branch,
            @McpToolParam(description = "强制推送", required = false) Boolean force,
            @McpToolParam(description = "force-with-lease", required = false) Boolean forceWithLease,
            @McpToolParam(description = "设置上游", required = false) Boolean setUpstream,
            @McpToolParam(description = "推送标签", required = false) Boolean tags,
            @McpToolParam(description = "干运行", required = false) Boolean dryRun,
            @McpToolParam(description = "删除远程分支", required = false) Boolean delete,
            @McpToolParam(description = "远程分支名", required = false) String remoteBranch) {
        return gitOps.gitPush(Paths.get(workingDir), remote, branch, force, forceWithLease, setUpstream, tags, dryRun, delete, remoteBranch);
    }

    @McpTool(name = "git_branch", description = "管理分支")
    public String gitBranch(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "操作模式：list/create/delete/rename/show-current") String mode,
            @McpToolParam(description = "分支名", required = false) String branchName,
            @McpToolParam(description = "新分支名（rename 用）", required = false) String newBranchName,
            @McpToolParam(description = "起始点", required = false) String startPoint,
            @McpToolParam(description = "强制", required = false) Boolean force,
            @McpToolParam(description = "列出所有分支", required = false) Boolean all,
            @McpToolParam(description = "远程分支", required = false) Boolean remote,
            @McpToolParam(description = "已合并的分支", required = false) String merged,
            @McpToolParam(description = "未合并的分支", required = false) String noMerged,
            @McpToolParam(description = "限制数量", required = false) Integer limit) {
        return gitOps.gitBranch(Paths.get(workingDir), mode, branchName, newBranchName, startPoint, force, all, remote, merged, noMerged, limit);
    }

    @McpTool(name = "git_merge", description = "合并分支")
    public String gitMerge(
            @McpToolParam(description = "工作目录路径") String workingDir,
            @McpToolParam(description = "要合并的分支") String branch,
            @McpToolParam(description = "合并策略", required = false) String strategy,
            @McpToolParam(description = "禁止快进", required = false) Boolean noFastForward,
            @McpToolParam(description = "压缩提交", required = false) Boolean squash,
            @McpToolParam(description = "合并信息", required = false) String message,
            @McpToolParam(description = "中止合并", required = false) Boolean abort) {
        return gitOps.gitMerge(Paths.get(workingDir), branch, strategy, noFastForward, squash, message, abort);
    }

    @McpTool(name = "git_set_working_dir", description = "设置工作目录（返回绝对路径，后续操作传入此路径）")
    public String gitSetWorkingDir(
            @McpToolParam(description = "目录路径") String path) {
        Path resolved = basePath.resolve(path).toAbsolutePath().normalize();
        return "工作目录已设置：" + resolved + "\n后续操作请将此路径作为 workingDir 参数传入。";
    }
}
