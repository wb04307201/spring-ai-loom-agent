package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.loom.git.core.GitOperations;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.common.PathSecurityUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

public class DefaultGitTool implements IGitTool {

 private static final String BASE_PATH = ".local/file";
 private static final String GIT_SUBDIR = "git";
 static final String WORKING_DIR_KEY = "gitWorkingDir";

 private final GitOperations gitOps;
 private final String fileBasePath;

 /**
 * 测试场景回退存储：当 ToolContext.getContext() 返回 unmodifiable Map（Spring AI 默认）时，
 * 用此 Map 作为 working dir 的回退存储。生产中 ToolContext.getContext() 是可写 Map，
 * 仍以 ctx 为准；测试中用工具对象本身上下文，绕过不可变限制。
 */
 private final Map<String, String> fallbackWorkingDir = new java.util.concurrent.ConcurrentHashMap<>();

 public DefaultGitTool(LoomAgentProperties properties) {
 this.fileBasePath = properties.getFileBasePath() != null ? properties.getFileBasePath() : BASE_PATH;
 String gitUsername = properties.getGitUsername();
 String gitToken = properties.getGitToken();
 int t = properties.getGit() != null ? properties.getGit().getRemoteTimeoutSeconds() : 60;
 int remoteTimeoutSeconds = t > 0 ? t : 60;
 this.gitOps = new GitOperations(gitUsername, gitToken, remoteTimeoutSeconds);
 }

 // ==================== Repository lifecycle ====================

 @Tool(description = "Initialize a new Git repository. Creates a .git directory under the user's file directory and sets up the initial branch.")
 @Override
 public String gitInit(
 @ToolParam(description = "Repository name (relative to user file dir)") String path,
 @ToolParam(description = "Name of the initial branch (default: main)", required = false) String initialBranch,
 @ToolParam(description = "Create a bare repository (no working directory)", required = false) Boolean bare,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 String result = gitOps.gitInit(workingDir, path, initialBranch, bare);
 try {
 Path repoDir = resolvePath(toolContext, path);
 setWorkingDirInContext(toolContext, repoDir.toString());
 } catch (SecurityException ignored) {
 }
 return result;
 }

 @Tool(description = "Clone a repository from a remote URL or local path. Accepts HTTP(S), SSH, git://, file://, and bare filesystem paths, with optional shallow cloning. The cloned repo is stored under the user's file directory.")
 @Override
 public String gitClone(
 @ToolParam(description = "Remote URL or local path to clone from") String url,
 @ToolParam(description = "Repository name (relative to user file dir)") String path,
 @ToolParam(description = "Specific branch to clone (default: remote HEAD)", required = false) String branch,
 @ToolParam(description = "Shallow clone depth", required = false) Integer depth,
 @ToolParam(description = "Create a bare repository", required = false) Boolean bare,
 @ToolParam(description = "Create a mirror clone (implies bare)", required = false) Boolean mirror,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 String result = gitOps.gitClone(workingDir, url, path, branch, depth, bare, mirror);
 if (!result.startsWith("错误")) {
 try {
 Path repoDir = resolvePath(toolContext, path);
 setWorkingDirInContext(toolContext, repoDir.toString());
 } catch (SecurityException ignored) {
 }
 }
 return result;
 }


 // ==================== Basic operations ====================

 @Tool(description = "Show the working tree status including staged, unstaged, and untracked files.")
 @Override
 public String gitStatus(
 @ToolParam(description = "Include untracked files in the output (default: true)", required = false) Boolean includeUntracked,
 ToolContext toolContext) {
 Path workingDir;
 try {
 workingDir = getWorkingDir(toolContext);
 } catch (SecurityException e) {
 return "错误：" + e.getMessage();
 }
 return gitOps.gitStatus(workingDir, includeUntracked);
 }

 @Tool(description = "Stage files for commit. Add file contents to the staging area (index) to prepare for the next commit.")
 @Override
 public String gitAdd(
 @ToolParam(description = "File or directory paths to stage. Use [\".\"] to stage all changes.", required = false) List<String> paths,
 @ToolParam(description = "Stage only modified and deleted files (skip untracked files)", required = false) Boolean update,
 @ToolParam(description = "Stage all files including untracked and ignored", required = false) Boolean all,
 @ToolParam(description = "Allow adding otherwise ignored files", required = false) Boolean force,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitAdd(workingDir, paths, update, all, force);
 }

 @Tool(description = "Create a new commit with staged changes. Records a snapshot of the staging area with a commit message.")
 @Override
 public String gitCommit(
 @ToolParam(description = "Commit message. Multi-line messages use \\n for newlines.") String message,
 @ToolParam(description = "Override commit author name", required = false) String authorName,
 @ToolParam(description = "Override commit author email", required = false) String authorEmail,
 @ToolParam(description = "Amend the previous commit", required = false) Boolean amend,
 @ToolParam(description = "Allow creating a commit with no changes", required = false) Boolean allowEmpty,
 @ToolParam(description = "Bypass pre-commit and commit-msg hooks", required = false) Boolean noVerify,
 @ToolParam(description = "File paths to stage before committing (atomic stage+commit)", required = false) List<String> filesToStage,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitCommit(workingDir, message, authorName, authorEmail, amend, allowEmpty, noVerify, filesToStage);
 }

 @Tool(description = "View differences between commits, branches, or working tree. Shows changes in unified diff format.")
 @Override
 public String gitDiff(
 @ToolParam(description = "Source commit/branch to compare from", required = false) String source,
 @ToolParam(description = "Target commit/branch to compare against. If not specified, shows unstaged changes.", required = false) String target,
 @ToolParam(description = "Limit diff to specific file paths", required = false) List<String> paths,
 @ToolParam(description = "Show staged diff instead of unstaged", required = false) Boolean staged,
 @ToolParam(description = "Show only names of changed files", required = false) Boolean nameOnly,
 @ToolParam(description = "Show diffstat summary instead of full diff", required = false) Boolean stat,
 @ToolParam(description = "Number of context lines (default: 3)", required = false) Integer contextLines,
 @ToolParam(description = "Auto-exclude lock files (default: true)", required = false) Boolean autoExclude,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitDiff(workingDir, source, target, paths, staged, nameOnly, stat, contextLines, autoExclude);
 }

 @Tool(description = "View commit history with optional filtering by author, date range, file path, or commit message pattern.")
 @Override
 public String gitLog(
 @ToolParam(description = "Maximum number of commits (default: 20)", required = false) Integer maxCount,
 @ToolParam(description = "Number of commits to skip", required = false) Integer skip,
 @ToolParam(description = "Show commits more recent than a specific date (ISO 8601)", required = false) String since,
 @ToolParam(description = "Show commits older than a specific date (ISO 8601)", required = false) String until,
 @ToolParam(description = "Filter by author name or email", required = false) String author,
 @ToolParam(description = "Filter by commit message pattern (regex)", required = false) String grep,
 @ToolParam(description = "Show commits from a specific branch/ref", required = false) String branch,
 @ToolParam(description = "Show commits that affected a specific file", required = false) String filePath,
 @ToolParam(description = "Abbreviated output: hash + subject only", required = false) Boolean oneline,
 @ToolParam(description = "Include file change statistics", required = false) Boolean stat,
 @ToolParam(description = "Include full diff patch for each commit", required = false) Boolean patch,
 @ToolParam(description = "Show GPG signature verification info", required = false) Boolean showSignature,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitLog(workingDir, maxCount, skip, since, until, author, grep, branch, filePath, oneline, stat, patch, showSignature);
 }

 // ==================== Branch management ====================

 @Tool(description = "Manage branches: list all branches, show current branch, create a new branch, delete a branch, or rename a branch.")
 @Override
 public String gitBranch(
 @ToolParam(description = "Mode: list, create, delete, rename, show-current") String mode,
 @ToolParam(description = "Branch name for create/delete/rename", required = false) String branchName,
 @ToolParam(description = "New branch name for rename", required = false) String newBranchName,
 @ToolParam(description = "Starting point (commit/branch) for new branch", required = false) String startPoint,
 @ToolParam(description = "Force delete branch", required = false) Boolean force,
 @ToolParam(description = "Show both local and remote branches (for list)", required = false) Boolean all,
 @ToolParam(description = "Show only remote branches (for list)", required = false) Boolean remote,
 @ToolParam(description = "Show only branches merged into HEAD or specified ref (for list)", required = false) String merged,
 @ToolParam(description = "Show only branches not merged into HEAD or specified ref (for list)", required = false) String noMerged,
 @ToolParam(description = "Cap number of branches returned (for list)", required = false) Integer limit,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitBranch(workingDir, mode, branchName, newBranchName, startPoint, force, all, remote, merged, noMerged, limit);
 }

 @Tool(description = "Switch branches or restore working tree files. Can checkout an existing branch, create a new branch, or restore specific files.")
 @Override
 public String gitCheckout(
 @ToolParam(description = "Branch name, commit hash, or tag to checkout") String target,
 @ToolParam(description = "Create a new branch with the specified name", required = false) Boolean createBranch,
 @ToolParam(description = "Force checkout (discard local changes)", required = false) Boolean force,
 @ToolParam(description = "Specific file paths to checkout/restore", required = false) List<String> paths,
 @ToolParam(description = "Set up tracking relationship with remote (for new branch)", required = false) Boolean track,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitCheckout(workingDir, target, createBranch, force, paths, track);
 }

 // ==================== Remote operations ====================

 @Tool(description = "Pull changes from a remote repository. Fetches and integrates changes into the current branch.")
 @Override
 public String gitPull(
 @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
 @ToolParam(description = "Branch name (default: current branch)", required = false) String branch,
 @ToolParam(description = "Use rebase instead of merge", required = false) Boolean rebase,
 @ToolParam(description = "Fail if can't fast-forward (no merge commit)", required = false) Boolean fastForwardOnly,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitPull(workingDir, remote, branch, rebase, fastForwardOnly);
 }

 @Tool(description = "Push changes to a remote repository. Uploads local commits to the remote branch.")
 @Override
 public String gitPush(
 @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
 @ToolParam(description = "Branch name (default: current branch)", required = false) String branch,
 @ToolParam(description = "Force push (overwrites remote history)", required = false) Boolean force,
 @ToolParam(description = "Safer force push - only succeeds if remote branch is at expected state", required = false) Boolean forceWithLease,
 @ToolParam(description = "Set upstream tracking relationship", required = false) Boolean setUpstream,
 @ToolParam(description = "Push all tags to the remote", required = false) Boolean tags,
 @ToolParam(description = "Dry run - preview without actually pushing", required = false) Boolean dryRun,
 @ToolParam(description = "Delete the specified remote branch", required = false) Boolean delete,
 @ToolParam(description = "Remote branch name to push to (if different from local)", required = false) String remoteBranch,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitPush(workingDir, remote, branch, force, forceWithLease, setUpstream, tags, dryRun, delete, remoteBranch);
 }

 @Tool(description = "Fetch updates from a remote repository. Downloads objects and refs without merging them.")
 @Override
 public String gitFetch(
 @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
 @ToolParam(description = "Prune deleted remote refs", required = false) Boolean prune,
 @ToolParam(description = "Fetch all tags from the remote", required = false) Boolean tags,
 @ToolParam(description = "Shallow fetch depth", required = false) Integer depth,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitFetch(workingDir, remote, prune, tags, depth);
 }

 @Tool(description = "Merge branches together. Integrates changes from another branch into the current branch.")
 @Override
 public String gitMerge(
 @ToolParam(description = "Branch to merge into current branch") String branch,
 @ToolParam(description = "Merge strategy: ours, theirs, recursive, resolve", required = false) String strategy,
 @ToolParam(description = "Prevent fast-forward merge (create merge commit)", required = false) Boolean noFastForward,
 @ToolParam(description = "Squash all commits from the branch into a single commit", required = false) Boolean squash,
 @ToolParam(description = "Custom merge commit message", required = false) String message,
 @ToolParam(description = "Abort an in-progress merge", required = false) Boolean abort,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitMerge(workingDir, branch, strategy, noFastForward, squash, message, abort);
 }

 @Tool(description = "Rebase commits onto another branch. Reapplies commits on top of another base tip for a cleaner history.")
 @Override
 public String gitRebase(
 @ToolParam(description = "Rebase mode: start, continue, abort, skip", required = false) String mode,
 @ToolParam(description = "Upstream branch to rebase onto (required for start mode)", required = false) String upstream,
 @ToolParam(description = "Branch to rebase (default: current branch)", required = false) String branch,
 @ToolParam(description = "Interactive rebase (not fully supported by JGit)", required = false) Boolean interactive,
 @ToolParam(description = "Rebase onto different commit than upstream", required = false) String onto,
 @ToolParam(description = "Preserve merge commits during rebase", required = false) Boolean preserve,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitRebase(workingDir, mode, upstream, branch, interactive, onto, preserve);
 }

 @Tool(description = "Reset current HEAD to specified state. Can be used to unstage files (soft), discard commits (mixed), or discard all changes (hard).")
 @Override
 public String gitReset(
 @ToolParam(description = "Reset mode: soft, mixed, hard, merge, keep (default: mixed)") String mode,
 @ToolParam(description = "Target commit/ref (default: HEAD)", required = false) String target,
 @ToolParam(description = "Specific file paths to reset (leaves HEAD unchanged)", required = false) List<String> paths,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitReset(workingDir, mode, target, paths);
 }

 // ==================== Stash / Tag / Remote ====================

 @Tool(description = "Manage stashes: list stashes, save current changes (push), restore changes (pop/apply), or remove stashes (drop/clear).")
 @Override
 public String gitStash(
 @ToolParam(description = "Mode: list, push, pop, apply, drop, clear") String mode,
 @ToolParam(description = "Stash message (for push mode)", required = false) String message,
 @ToolParam(description = "Stash reference like stash@{0} (for pop/apply/drop)", required = false) String stashRef,
 @ToolParam(description = "Include untracked files in the stash (for push)", required = false) Boolean includeUntracked,
 @ToolParam(description = "Don't revert staged changes (for push)", required = false) Boolean keepIndex,
 @ToolParam(description = "Cap number of stash entries returned (for list)", required = false) Integer limit,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitStash(workingDir, mode, message, stashRef, includeUntracked, keepIndex, limit);
 }

 @Tool(description = "Manage tags: list all tags, create a new tag, delete a tag, or verify a signed tag.")
 @Override
 public String gitTag(
 @ToolParam(description = "Mode: list, create, delete, verify") String mode,
 @ToolParam(description = "Tag name for create/delete/verify", required = false) String tagName,
 @ToolParam(description = "Commit to tag (default: HEAD for create)", required = false) String commit,
 @ToolParam(description = "Tag message (annotated tag)", required = false) String message,
 @ToolParam(description = "Create annotated tag with default message", required = false) Boolean annotated,
 @ToolParam(description = "Overwrite existing tag (create mode)", required = false) Boolean force,
 @ToolParam(description = "Cap number of tags returned (for list)", required = false) Integer limit,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitTag(workingDir, mode, tagName, commit, message, annotated, force, limit);
 }

 @Tool(description = "Manage remote repositories: list remotes, add new remotes, remove remotes, rename remotes, or get/set remote URLs.")
 @Override
 public String gitRemote(
 @ToolParam(description = "Mode: list, add, remove, rename, get-url, set-url") String mode,
 @ToolParam(description = "Remote name", required = false) String name,
 @ToolParam(description = "Remote URL (for add/set-url)", required = false) String url,
 @ToolParam(description = "New remote name (for rename)", required = false) String newName,
 @ToolParam(description = "Set push URL separately", required = false) Boolean push,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitRemote(workingDir, mode, name, url, newName, push);
 }

 // ==================== Inspection ====================

 @Tool(description = "Show line-by-line authorship information for a file (git blame). For large files, use startLine/endLine to limit output.")
 @Override
 public String gitBlame(
 @ToolParam(description = "File path relative to repo root") String filePath,
 @ToolParam(description = "Start line number (1-based)", required = false) Integer startLine,
 @ToolParam(description = "End line number (1-based)", required = false) Integer endLine,
 @ToolParam(description = "Ignore whitespace changes", required = false) Boolean ignoreWhitespace,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitBlame(workingDir, filePath, startLine, endLine, ignoreWhitespace);
 }

 @Tool(description = "Show details of a git object (commit, tree, blob, or tag). Displays commit info and diff for commits, content for blobs.")
 @Override
 public String gitShow(
 @ToolParam(description = "Git object to show (commit hash, branch, tag, tree, or blob)") String object,
 @ToolParam(description = "Output format: raw", required = false) String format,
 @ToolParam(description = "Show diffstat instead of full diff", required = false) Boolean stat,
 @ToolParam(description = "View specific file at given commit reference", required = false) String filePath,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitShow(workingDir, object, format, stat, filePath);
 }

 @Tool(description = "View the reference logs (reflog) to track when branch tips and other references were updated. Useful for recovering lost commits.")
 @Override
 public String gitReflog(
 @ToolParam(description = "Reference whose reflog to show (default: HEAD)", required = false) String ref,
 @ToolParam(description = "Maximum number of entries (default: 25)", required = false) Integer maxCount,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitReflog(workingDir, ref, maxCount);
 }

 @Tool(description = "Remove untracked files from the working directory. Requires force flag for safety. Use dry-run to preview files that would be removed.")
 @Override
 public String gitClean(
 @ToolParam(description = "Force remove files (or use dryRun to preview)", required = false) Boolean force,
 @ToolParam(description = "Preview only - do not actually delete (default: true)", required = false) Boolean dryRun,
 @ToolParam(description = "Remove untracked directories as well", required = false) Boolean directories,
 @ToolParam(description = "Remove ignored files as well", required = false) Boolean ignored,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitClean(workingDir, force, dryRun, directories, ignored);
 }

 @Tool(description = "Cherry-pick commits from other branches. Apply specific commits to the current branch without merging entire branches.")
 @Override
 public String gitCherryPick(
 @ToolParam(description = "Commit hashes to cherry-pick") List<String> commits,
 @ToolParam(description = "Don't create commit (stage changes only)", required = false) Boolean noCommit,
 @ToolParam(description = "Continue cherry-pick after resolving conflicts", required = false) Boolean continueOperation,
 @ToolParam(description = "Abort cherry-pick operation", required = false) Boolean abort,
 @ToolParam(description = "For merge commits, specify which parent to follow", required = false) Integer mainline,
 @ToolParam(description = "Merge strategy: recursive, ours", required = false) String strategy,
 @ToolParam(description = "Add Signed-off-by line to the commit message", required = false) Boolean signoff,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitCherryPick(workingDir, commits, noCommit, continueOperation, abort, mainline, strategy, signoff);
 }

 @Tool(description = "Manage multiple working trees: list worktrees, add new worktrees for parallel work, remove worktrees, move worktrees, or prune stale worktrees. Uses system git command since JGit doesn't support worktrees natively.")
 @Override
 public String gitWorktree(
 @ToolParam(description = "Mode: list, add, remove, move, prune") String mode,
 @ToolParam(description = "Path for the worktree (for add/remove/move), relative to user file dir", required = false) String worktreePath,
 @ToolParam(description = "Create a NEW branch with this name in the new worktree (for add)", required = false) String branch,
 @ToolParam(description = "Check out existing branch/commit/tag in the new worktree (for add)", required = false) String commitish,
 @ToolParam(description = "Force operation (for remove with uncommitted changes)", required = false) Boolean force,
 @ToolParam(description = "New path for the worktree (for move), relative to user file dir", required = false) String newPath,
 @ToolParam(description = "Create worktree with detached HEAD (for add)", required = false) Boolean detach,
 @ToolParam(description = "Provide detailed output (for list/move)", required = false) Boolean verbose,
 @ToolParam(description = "Preview without executing (for prune)", required = false) Boolean dryRun,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitWorktree(workingDir, mode, worktreePath, branch, commitish, force, newPath, detach, verbose, dryRun);
 }

 // ==================== Working dir management ====================

 @Tool(description = "Set the session working directory for all git operations. Accepts a repo name (relative to user's file dir). Always returns a repository snapshot.")
 @Override
 public String gitSetWorkingDir(
 @ToolParam(description = "Repo name (relative to user's file dir)") String path,
 @ToolParam(description = "Validate that the path is a Git repository (default: true)", required = false) Boolean validateGitRepo,
 @ToolParam(description = "If not a Git repo, initialize it with git init", required = false) Boolean initializeIfNotPresent,
 ToolContext toolContext) {
 try {
 Path resolved = resolvePath(toolContext, path);
 boolean validate = validateGitRepo == null || validateGitRepo;
 if (validate) {
 if (!Files.exists(resolved.resolve(".git"))) {
 if (Boolean.TRUE.equals(initializeIfNotPresent)) {
 Files.createDirectories(resolved);
 org.eclipse.jgit.api.Git.init().setDirectory(resolved.toFile()).setInitialBranch("main").call().close();
 } else {
 return "错误：不是 Git 仓库 - " + resolved + "\n可设置 initializeIfNotPresent=true 自动初始化";
 }
 }
 }
 setWorkingDirInContext(toolContext, resolved.toString());
 return "已设置工作目录：" + resolved + "\n" + repoSnapshot(resolved);
 } catch (SecurityException e) {
 return e.getMessage();
 } catch (Exception e) {
 return "设置工作目录失败：" + e.getMessage();
 }
 }

 @Tool(description = "Clear the session working directory setting. This resets the context without restarting the server. Subsequent git operations will require an explicit path parameter.")
 @Override
 public String gitClearWorkingDir(
 @ToolParam(description = "Explicit confirmation: Y, y, Yes, or yes") String confirm,
 ToolContext toolContext) {
 if (confirm == null || !(confirm.equals("Y") || confirm.equals("y") || confirm.equals("Yes") || confirm.equals("yes"))) {
 return "错误：需要确认。请输入 Y、y、Yes 或 yes。";
 }
 String previousPath = (String) toolContext.getContext().get(WORKING_DIR_KEY);
 if (previousPath == null) previousPath = fallbackWorkingDir.get(currentUsername(toolContext));
 try {
 toolContext.getContext().remove(WORKING_DIR_KEY);
 } catch (UnsupportedOperationException e) {
 // unmodifiable Map — 用工具自身存储
 fallbackWorkingDir.remove(currentUsername(toolContext));
 }
 return "已清除工作目录设置" + (previousPath != null ? "。之前的路径：" + previousPath : "")
 + "。后续 git 操作需要显式指定路径或调用 git_set_working_dir。";
 }

 // ==================== Analysis & Wrap-up ====================

 @Tool(description = "Gather git history context (commits, tags) for changelog analysis. Returns structured commit data the LLM can use to generate changelogs.")
 @Override
 public String gitChangelogAnalyze(
 @ToolParam(description = "Start ref (e.g. v1.0.0, or commit hash)", required = false) String from,
 @ToolParam(description = "End ref (e.g. HEAD, v2.0.0)", required = false) String to,
 @ToolParam(description = "Review types: security, features, storyline, gaps, breaking_changes, quality", required = false) List<String> reviewTypes,
 @ToolParam(description = "Maximum commits to fetch (default: 200)", required = false) Integer maxCommits,
 @ToolParam(description = "Maximum tags to fetch (default: 100)", required = false) Integer maxTags,
 @ToolParam(description = "Only include history since this tag", required = false) String sinceTag,
 @ToolParam(description = "Branch to analyze (default: current)", required = false) String branch,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitChangelogAnalyze(workingDir, from, to, reviewTypes, maxCommits, maxTags, sinceTag, branch);
 }

 @Tool(description = "Returns a Git wrap-up protocol: an acceptance-criteria checklist the agent must satisfy before the session is considered shipped.")
 @Override
 public String gitWrapupInstructions(
 @ToolParam(description = "Acknowledgement: Y, y, Yes, or yes", required = false) String acknowledgement,
 @ToolParam(description = "Include tag criterion in the protocol (default: true)", required = false) Boolean createTag,
 ToolContext toolContext) {
 Path workingDir = getWorkingDir(toolContext);
 return gitOps.gitWrapupInstructions(workingDir, acknowledgement, createTag);
 }

 // ==================== Helpers ====================

 private String username(ToolContext toolContext) {
 Object u = toolContext.getContext().get("username");
 if (u == null || u.toString().isBlank()) {
 throw new SecurityException("缺少 username 上下文");
 }
 return u.toString();
 }

 private Path getGitBaseDir(String username) {
 return getUserFileDir(username);
 }

 private Path getUserFileDir(String username) {
 return Paths.get(fileBasePath, username);
 }

 /**
 * Resolve a relative path (repo name) against the user's file directory.
 * Only accepts relative paths — absolute paths are rejected to prevent sandbox escape.
 * After resolution, the result is validated with {@link PathSecurityUtils} to prevent
 * directory traversal AND symlink-based escape (a user-owned dir could contain a
 * symlink pointing outside the user dir).
 */
 private Path resolvePath(ToolContext toolContext, String path) {
 String username = username(toolContext);
 Path inputPath = Paths.get(path == null ? "" : path);
 if (inputPath.isAbsolute()) {
 throw new SecurityException("路径不能为绝对路径，必须相对于用户文件目录：" + getUserFileDir(username));
 }
 Path resolved = getUserFileDir(username).resolve(path == null ? "" : path).toAbsolutePath().normalize();
 if (!resolved.startsWith(getUserFileDir(username).toAbsolutePath().normalize())) {
 throw new SecurityException("路径不能超出用户文件目录：" + getUserFileDir(username));
 }
 try {
 PathSecurityUtils.assertInsideUserDir(resolved, getUserFileDir(username), true);
 } catch (IOException e) {
 throw new SecurityException("路径 symlink 校验失败：" + e.getMessage());
 }
 return resolved;
 }

 private Path getWorkingDir(ToolContext toolContext) {
 String username = username(toolContext);
 String ctxWd = (String) toolContext.getContext().get(WORKING_DIR_KEY);
 if (ctxWd == null) {
 // 回退到工具自身存储（处理 Spring AI unmodifiable Map 场景）
 ctxWd = fallbackWorkingDir.get(username);
 }
 if (ctxWd != null) {
 Path resolved = Paths.get(ctxWd).toAbsolutePath().normalize();
 // Validate that stored working dir is within user's file directory
 if (!resolved.startsWith(getUserFileDir(username).toAbsolutePath().normalize())) {
 throw new SecurityException("工作目录不在用户文件目录内：" + resolved);
 }
 try {
 // symlink 防御：万一 working dir 路径里有软链指外
 PathSecurityUtils.assertInsideUserDir(resolved, getUserFileDir(username), true);
 } catch (SecurityException | IOException e) {
 throw new SecurityException("工作目录 symlink 校验失败：" + e.getMessage());
 }
 return resolved;
 }
 return getGitBaseDir(username);
 }

 private void setWorkingDirInContext(ToolContext toolContext, String path) {
 try {
 toolContext.getContext().put(WORKING_DIR_KEY, path);
 } catch (UnsupportedOperationException e) {
 // Spring AI 的 ToolContext 默认返回 unmodifiable Map，回退到工具自身存储
 fallbackWorkingDir.put(currentUsername(toolContext), path);
 }
 }

 private String currentUsername(ToolContext toolContext) {
 return (String) toolContext.getContext().get("username");
 }

 private String repoSnapshot(Path repoDir) {
 try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoDir.toFile())) {
 StringBuilder sb = new StringBuilder();
 sb.append("当前分支：").append(git.getRepository().getBranch()).append("\n");

 org.eclipse.jgit.api.Status status = git.status().call();
 int changes = status.getChanged().size() + status.getModified().size()
 + status.getAdded().size() + status.getRemoved().size()
 + status.getUntracked().size() + status.getMissing().size();
 sb.append("工作区状态：").append(changes == 0 ? "干净" : changes + " 个文件变更").append("\n");

 sb.append("\n最近提交：\n");
 int count = 0;
 try {
 for (org.eclipse.jgit.revwalk.RevCommit c : git.log().setMaxCount(5).call()) {
 sb.append(" ").append(c.abbreviate(7).name())
 .append(" ").append(c.getShortMessage()).append("\n");
 count++;
 }
 } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
 // 空仓库没有任何提交，跳过 log
 }
 if (count == 0) sb.append(" (无提交)\n");

 java.util.List<org.eclipse.jgit.lib.Ref> tags = git.tagList().call();
 sb.append("\n标签：");
 if (tags.isEmpty()) {
 sb.append("(无)");
 } else {
 for (org.eclipse.jgit.lib.Ref tag : tags) {
 sb.append(tag.getName().replace("refs/tags/", "")).append(" ");
 }
 }
 sb.append("\n");

 java.util.Set<String> remotes = git.getRepository().getConfig().getSubsections("remote");
 sb.append("远程仓库：");
 if (remotes.isEmpty()) {
 sb.append("(无)");
 } else {
 for (String r : remotes) {
 sb.append(r).append(" ");
 }
 }

 return sb.toString();
 } catch (Exception e) {
 return "仓库快照获取失败：" + e.getMessage();
 }
 }
}
