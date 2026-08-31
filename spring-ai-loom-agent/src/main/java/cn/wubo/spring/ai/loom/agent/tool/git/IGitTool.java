package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

@ToolGroup(value = "git", description = "gitInit / gitStatus / gitCommit / gitClone / gitPush 等 28 个 Git 命令")
public interface IGitTool extends IEmbedTool {

    // ==================== Repository lifecycle ====================

    String gitInit(String repoName, String initialBranch, Boolean bare, ToolContext toolContext);

    String gitClone(String url, String repoName, String branch, Integer depth, Boolean bare, Boolean mirror, ToolContext toolContext);

    // ==================== Basic operations ====================

    String gitStatus(Boolean includeUntracked, ToolContext toolContext);

    String gitAdd(List<String> paths, Boolean update, Boolean all, Boolean force, ToolContext toolContext);

    String gitCommit(String message, String authorName, String authorEmail, Boolean amend, Boolean allowEmpty, Boolean noVerify, List<String> filesToStage, ToolContext toolContext);

    String gitDiff(String source, String target, List<String> paths, Boolean staged, Boolean nameOnly, Boolean stat, Integer contextLines, Boolean autoExclude, ToolContext toolContext);

    String gitLog(Integer maxCount, Integer skip, String since, String until, String author, String grep, String branch, String filePath, Boolean oneline, Boolean stat, Boolean patch, Boolean showSignature, ToolContext toolContext);

    // ==================== Branch management ====================

    String gitBranch(String mode, String branchName, String newBranchName, String startPoint, Boolean force, Boolean all, Boolean remote, String merged, String noMerged, Integer limit, ToolContext toolContext);

    String gitCheckout(String target, Boolean createBranch, Boolean force, List<String> paths, Boolean track, ToolContext toolContext);

    // ==================== Remote operations ====================

    String gitPull(String remote, String branch, Boolean rebase, Boolean fastForwardOnly, ToolContext toolContext);

    String gitPush(String remote, String branch, Boolean force, Boolean forceWithLease, Boolean setUpstream, Boolean tags, Boolean dryRun, Boolean delete, String remoteBranch, ToolContext toolContext);

    String gitFetch(String remote, Boolean prune, Boolean tags, Integer depth, ToolContext toolContext);

    String gitMerge(String branch, String strategy, Boolean noFastForward, Boolean squash, String message, Boolean abort, ToolContext toolContext);

    String gitRebase(String mode, String upstream, String branch, Boolean interactive, String onto, Boolean preserve, ToolContext toolContext);

    String gitReset(String mode, String target, List<String> paths, ToolContext toolContext);

    // ==================== Stash / Tag / Remote ====================

    String gitStash(String mode, String message, String stashRef, Boolean includeUntracked, Boolean keepIndex, Integer limit, ToolContext toolContext);

    String gitTag(String mode, String tagName, String commit, String message, Boolean annotated, Boolean force, Integer limit, ToolContext toolContext);

    String gitRemote(String mode, String name, String url, String newName, Boolean push, ToolContext toolContext);

    // ==================== Inspection ====================

    String gitBlame(String filePath, Integer startLine, Integer endLine, Boolean ignoreWhitespace, ToolContext toolContext);

    String gitShow(String object, String format, Boolean stat, String filePath, ToolContext toolContext);

    String gitReflog(String ref, Integer maxCount, ToolContext toolContext);

    String gitClean(Boolean force, Boolean dryRun, Boolean directories, Boolean ignored, ToolContext toolContext);

    String gitCherryPick(List<String> commits, Boolean noCommit, Boolean continueOperation, Boolean abort, Integer mainline, String strategy, Boolean signoff, ToolContext toolContext);

    String gitWorktree(String mode, String worktreePath, String branch, String commitish, Boolean force, String newPath, Boolean detach, Boolean verbose, Boolean dryRun, ToolContext toolContext);

    // ==================== Working dir management ====================

    String gitSetWorkingDir(String path, Boolean validateGitRepo, Boolean initializeIfNotPresent, ToolContext toolContext);

    String gitClearWorkingDir(String confirm, ToolContext toolContext);

    // ==================== Analysis & Wrap-up ====================

    String gitChangelogAnalyze(String from, String to, List<String> reviewTypes, Integer maxCommits, Integer maxTags, String sinceTag, String branch, ToolContext toolContext);

    String gitWrapupInstructions(String acknowledgement, Boolean createTag, ToolContext toolContext);
}
