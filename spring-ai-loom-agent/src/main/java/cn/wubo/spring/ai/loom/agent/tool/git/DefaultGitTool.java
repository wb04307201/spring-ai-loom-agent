package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class DefaultGitTool implements IGitTool {

    private static final String BASE_PATH = ".local/file";
    private static final String GIT_SUBDIR = "git";
    private static final String WORKING_DIR_KEY = "gitWorkingDir";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IFile file;
    private final String defaultGitUsername;
    private final String defaultGitToken;

    public DefaultGitTool(IFile file, LoomAgentProperties properties) {
        this.file = file;
        this.defaultGitUsername = properties.getGitUsername();
        this.defaultGitToken = properties.getGitToken();
    }

    // ==================== Repository lifecycle ====================

    @Tool(description = "Initialize a new Git repository. Creates a .git directory and sets up the initial branch. The repo is created under the user's git directory.")
    @Override
    public String gitInit(
            @ToolParam(description = "Repository name (directory name under user's git dir)") String repoName,
            ToolContext toolContext) {
        try {
            String username = username(toolContext);
            Path baseDir = getGitBaseDir(username);
            Path repoDir = baseDir.resolve(repoName).normalize();
            if (!repoDir.startsWith(baseDir)) {
                return "错误：仓库名不合法";
            }
            Files.createDirectories(repoDir);
            Git.init().setDirectory(repoDir.toFile()).call().close();
            registerRepo(repoDir, repoName, username);
            setWorkingDirInContext(toolContext, repoDir.toString());
            return "已初始化 Git 仓库：" + repoDir + "\n" + repoSnapshot(repoDir);
        } catch (Exception e) {
            return "git init 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Clone a repository from a remote URL or local path. Accepts HTTP(S), SSH, git://, file://, and bare filesystem paths, with optional shallow cloning. The cloned repo is stored under the user's git directory.")
    @Override
    public String gitClone(
            @ToolParam(description = "Remote URL or local path to clone from") String url,
            @ToolParam(description = "Local repository name (directory name under user's git dir)") String repoName,
            @ToolParam(description = "If true, perform a shallow clone (depth=1)", required = false) Boolean shallow,
            @ToolParam(description = "Branch to clone (default: remote HEAD)", required = false) String branch,
            ToolContext toolContext) {
        try {
            String username = username(toolContext);
            Path baseDir = getGitBaseDir(username);
            Path repoDir = baseDir.resolve(repoName).normalize();
            if (!repoDir.startsWith(baseDir)) {
                return "错误：仓库名不合法";
            }
            if (Files.exists(repoDir)) {
                return "错误：目录已存在 - " + repoDir;
            }
            Files.createDirectories(baseDir);

            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(repoDir.toFile());
            if (Boolean.TRUE.equals(shallow)) {
                cmd.setDepth(1);
            }
            if (branch != null && !branch.isBlank()) {
                cmd.setBranch(branch);
            }
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) {
                cmd.setCredentialsProvider(cp);
            }
            cmd.call().close();
            registerRepo(repoDir, repoName, username);
            setWorkingDirInContext(toolContext, repoDir.toString());
            return "已克隆仓库：" + repoDir + "\n" + repoSnapshot(repoDir);
        } catch (Exception e) {
            return "git clone 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Delete a git repository directory and remove its FileRecord. Use with caution - this permanently deletes all files and history.")
    @Override
    public String gitDeleteRepo(
            @ToolParam(description = "Repository name to delete") String repoName,
            ToolContext toolContext) {
        try {
            String username = username(toolContext);
            Path baseDir = getGitBaseDir(username);
            Path repoDir = baseDir.resolve(repoName).normalize();
            if (!repoDir.startsWith(baseDir)) {
                return "错误：仓库名不合法";
            }
            if (!Files.exists(repoDir)) {
                return "错误：仓库不存在 - " + repoName;
            }
            deleteDirectoryRecursive(repoDir);
            String repoPathStr = repoDir.toString();
            try {
                FileRecord record = file.getByExactPath(repoPathStr, username);
                if (record != null && "git".equals(record.usage())) {
                    file.delete(record.id(), username);
                }
            } catch (Exception ignored) {
            }
            // Clear working dir if it was this repo
            String currentWd = (String) toolContext.getContext().get(WORKING_DIR_KEY);
            if (repoPathStr.equals(currentWd)) {
                toolContext.getContext().remove(WORKING_DIR_KEY);
            }
            return "已删除仓库：" + repoName;
        } catch (Exception e) {
            return "删除仓库失败：" + e.getMessage();
        }
    }

    // ==================== Basic operations ====================

    @Tool(description = "Show the working tree status including staged, unstaged, and untracked files.")
    @Override
    public String gitStatus(ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            Status status = git.status().call();
            StringBuilder sb = new StringBuilder();
            sb.append("分支：").append(git.getRepository().getBranch()).append("\n\n");
            appendSet(sb, "暂存区新增 (staged/added)", status.getAdded());
            appendSet(sb, "暂存区修改 (staged/changed)", status.getChanged());
            appendSet(sb, "暂存区删除 (staged/removed)", status.getRemoved());
            appendSet(sb, "未暂存修改 (unstaged/modified)", status.getModified());
            appendSet(sb, "未跟踪 (untracked)", status.getUntracked());
            appendSet(sb, "冲突 (conflicting)", status.getConflicting());
            appendSet(sb, "缺失 (missing)", status.getMissing());
            return sb.toString();
        } catch (Exception e) {
            return "git status 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Stage files for commit. Add file contents to the staging area (index) to prepare for the next commit.")
    @Override
    public String gitAdd(
            @ToolParam(description = "File patterns to stage. Use [\".\"] to stage all changes.") List<String> filePatterns,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            AddCommand add = git.add();
            for (String pattern : filePatterns) {
                add.addFilepattern(pattern);
            }
            add.call();
            return "已暂存文件：" + String.join(", ", filePatterns);
        } catch (Exception e) {
            return "git add 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Create a new commit with staged changes. Records a snapshot of the staging area with a commit message. Multi-line messages supported via \\n escape sequences.")
    @Override
    public String gitCommit(
            @ToolParam(description = "Commit message. Multi-line messages use \\n for newlines.") String message,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            String normalizedMessage = message.replace("\\n", "\n");
            RevCommit commit = git.commit().setMessage(normalizedMessage).call();
            return "已提交：" + commit.abbreviate(7).name() + "\n" + commit.getShortMessage();
        } catch (Exception e) {
            return "git commit 失败：" + e.getMessage();
        }
    }

    @Tool(description = "View differences between commits, branches, or working tree. Shows changes in unified diff format.")
    @Override
    public String gitDiff(
            @ToolParam(description = "If true, show staged (cached) diff instead of working tree diff", required = false) String cached,
            @ToolParam(description = "Base ref to compare against (e.g. HEAD~1, branch name). Empty for working tree diff.", required = false) String base,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter formatter = new DiffFormatter(out);
            formatter.setRepository(git.getRepository());

            if (base != null && !base.isBlank()) {
                ObjectId baseId = git.getRepository().resolve(base);
                if (baseId == null) return "错误：无法解析 ref - " + base;
                try (RevWalk rw = new RevWalk(git.getRepository())) {
                    RevCommit baseCommit = rw.parseCommit(baseId);
                    RevCommit headCommit = rw.parseCommit(git.getRepository().resolve("HEAD"));
                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(rw.getObjectReader(), baseCommit.getTree());
                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(rw.getObjectReader(), headCommit.getTree());
                    formatter.format(oldTree, newTree);
                }
            } else if ("true".equalsIgnoreCase(cached)) {
                try (ObjectReader reader = git.getRepository().newObjectReader()) {
                    ObjectId headTree = git.getRepository().resolve("HEAD^{tree}");
                    CanonicalTreeParser headTreeParser = new CanonicalTreeParser(null, reader, headTree);
                    formatter.format(new EmptyTreeIterator(), headTreeParser);
                }
            } else {
                List<DiffEntry> diffs = git.diff().call();
                for (DiffEntry entry : diffs) {
                    formatter.format(entry);
                }
            }
            formatter.close();
            String result = out.toString();
            return result.isBlank() ? "无差异" : result;
        } catch (Exception e) {
            return "git diff 失败：" + e.getMessage();
        }
    }

    @Tool(description = "View commit history with optional filtering by author, max count, or file path.")
    @Override
    public String gitLog(
            @ToolParam(description = "Maximum number of commits to show (default 20)", required = false) Integer maxCount,
            @ToolParam(description = "Filter by author name", required = false) String author,
            @ToolParam(description = "Filter by file path", required = false) String path,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            LogCommand log = git.log();
            if (maxCount != null && maxCount > 0) {
                log.setMaxCount(maxCount);
            } else {
                log.setMaxCount(20);
            }
            if (path != null && !path.isBlank()) {
                log.addPath(path);
            }
            Iterable<RevCommit> commits = log.call();
            StringBuilder sb = new StringBuilder();
            for (RevCommit c : commits) {
                if (author != null && !author.isBlank()
                        && !c.getAuthorIdent().getName().toLowerCase().contains(author.toLowerCase())) {
                    continue;
                }
                sb.append("commit ").append(c.name()).append("\n");
                sb.append("Author: ").append(c.getAuthorIdent().getName())
                        .append(" <").append(c.getAuthorIdent().getEmailAddress()).append(">\n");
                sb.append("Date:   ").append(formatEpoch(c.getCommitTime())).append("\n");
                sb.append("    ").append(c.getShortMessage()).append("\n\n");
            }
            return sb.length() == 0 ? "无提交记录" : sb.toString();
        } catch (Exception e) {
            return "git log 失败：" + e.getMessage();
        }
    }

    // ==================== Branch management ====================

    @Tool(description = "Manage branches: list all branches, show current branch, create a new branch, delete a branch, or rename a branch.")
    @Override
    public String gitBranch(
            @ToolParam(description = "Action: list, create, delete, rename") String action,
            @ToolParam(description = "Branch name (for create/delete) or source name (for rename)", required = false) String name,
            @ToolParam(description = "New name (for rename action only)", required = false) String newName,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            return switch (action.toLowerCase()) {
                case "list" -> {
                    StringBuilder sb = new StringBuilder();
                    String current = git.getRepository().getBranch();
                    for (Ref ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
                        String branchName = ref.getName().replace("refs/heads/", "").replace("refs/remotes/", "");
                        sb.append(branchName.equals(current) ? "* " : "  ").append(branchName).append("\n");
                    }
                    yield sb.toString();
                }
                case "create" -> {
                    git.branchCreate().setName(name).call();
                    yield "已创建分支：" + name;
                }
                case "delete" -> {
                    git.branchDelete().setBranchNames(name).setForce(true).call();
                    yield "已删除分支：" + name;
                }
                case "rename" -> {
                    git.branchRename().setOldName(name).setNewName(newName).call();
                    yield "已重命名分支：" + name + " -> " + newName;
                }
                default -> "未知操作：" + action + "。支持 list/create/delete/rename";
            };
        } catch (Exception e) {
            return "git branch 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Switch branches or restore working tree files. Can checkout an existing branch, create a new branch, or restore specific files.")
    @Override
    public String gitCheckout(
            @ToolParam(description = "Branch name or commit to checkout") String target,
            @ToolParam(description = "If true, create the branch before checking out (-b)", required = false) Boolean createNew,
            @ToolParam(description = "Specific file paths to restore (leave empty to checkout branch)", required = false) List<String> paths,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            CheckoutCommand cmd = git.checkout().setName(target);
            if (Boolean.TRUE.equals(createNew)) {
                cmd.setCreateBranch(true);
            }
            if (paths != null && !paths.isEmpty()) {
                cmd.setStartPoint("HEAD");
                for (String p : paths) {
                    cmd.addPath(p);
                }
            }
            cmd.call();
            return "已切换到：" + target;
        } catch (Exception e) {
            return "git checkout 失败：" + e.getMessage();
        }
    }

    // ==================== Remote operations ====================

    @Tool(description = "Pull changes from a remote repository. Fetches and integrates changes into the current branch.")
    @Override
    public String gitPull(
            @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
            @ToolParam(description = "Remote branch to pull (default: tracking branch)", required = false) String branch,
            @ToolParam(description = "If true, use rebase instead of merge", required = false) String rebase,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            PullCommand cmd = git.pull();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (branch != null && !branch.isBlank()) cmd.setRemoteBranchName(branch);
            if ("true".equalsIgnoreCase(rebase)) cmd.setRebase(true);
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            PullResult result = cmd.call();
            return "pull 完成\n" +
                    (result.getMergeResult() != null ? "Merge: " + result.getMergeResult().getMergeStatus() + "\n" : "") +
                    (result.getRebaseResult() != null ? "Rebase: " + result.getRebaseResult().getStatus() + "\n" : "") +
                    (result.getFetchResult() != null ? "Fetch: " + result.getFetchResult().getMessages() : "");
        } catch (Exception e) {
            return "git pull 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Push changes to a remote repository. Uploads local commits to the remote branch.")
    @Override
    public String gitPush(
            @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
            @ToolParam(description = "Branch to push (default: current branch)", required = false) String branch,
            @ToolParam(description = "If true, force push", required = false) Boolean force,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            PushCommand cmd = git.push();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (branch != null && !branch.isBlank()) cmd.setRefSpecs(new RefSpec(branch));
            if (Boolean.TRUE.equals(force)) cmd.setForce(true);
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            Iterable<PushResult> results = cmd.call();
            StringBuilder sb = new StringBuilder("push 完成\n");
            for (PushResult r : results) {
                sb.append(r.getMessages());
            }
            return sb.toString();
        } catch (Exception e) {
            return "git push 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Fetch updates from a remote repository. Downloads objects and refs without merging them.")
    @Override
    public String gitFetch(
            @ToolParam(description = "Remote name (default: origin)", required = false) String remote,
            @ToolParam(description = "Branches to fetch (e.g. [\"main\", \"develop\"])", required = false) List<String> branches,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            FetchCommand cmd = git.fetch();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (branches != null && !branches.isEmpty()) {
                List<RefSpec> specs = branches.stream().map(b -> new RefSpec("+refs/heads/" + b + ":refs/remotes/origin/" + b)).toList();
                cmd.setRefSpecs(specs);
            }
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            FetchResult result = cmd.call();
            return "fetch 完成\n" + result.getMessages();
        } catch (Exception e) {
            return "git fetch 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Merge branches together. Integrates changes from another branch into the current branch.")
    @Override
    public String gitMerge(
            @ToolParam(description = "Branch to merge into current branch") String branch,
            @ToolParam(description = "Merge strategy: ours, theirs, recursive, resolve (default: recursive)", required = false) String strategy,
            @ToolParam(description = "Custom merge commit message", required = false) String message,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            MergeCommand cmd = git.merge();
            ObjectId branchId = git.getRepository().resolve(branch);
            if (branchId == null) return "错误：无法解析分支 - " + branch;
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                cmd.include(rw.parseCommit(branchId));
            }
            if (message != null && !message.isBlank()) {
                cmd.setMessage(message.replace("\\n", "\n"));
            }
            if (strategy != null && !strategy.isBlank()) {
                MergeStrategy ms = switch (strategy.toLowerCase()) {
                    case "ours" -> MergeStrategy.OURS;
                    case "theirs" -> MergeStrategy.THEIRS;
                    case "resolve" -> MergeStrategy.RESOLVE;
                    default -> MergeStrategy.RECURSIVE;
                };
                cmd.setStrategy(ms);
            }
            MergeResult result = cmd.call();
            return "merge 完成：" + result.getMergeStatus();
        } catch (Exception e) {
            return "git merge 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Rebase commits onto another branch. Reapplies commits on top of another base tip for a cleaner history.")
    @Override
    public String gitRebase(
            @ToolParam(description = "Upstream branch or commit to rebase onto") String upstream,
            @ToolParam(description = "Use --onto: rebase onto this instead of upstream", required = false) String onto,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            RebaseCommand cmd = git.rebase();
            String effectiveUpstream = (onto != null && !onto.isBlank()) ? onto : upstream;
            ObjectId upId = git.getRepository().resolve(effectiveUpstream);
            if (upId == null) return "错误：无法解析 upstream ref - " + effectiveUpstream;
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                cmd.setUpstream(rw.parseCommit(upId));
            }
            RebaseResult result = cmd.call();
            return "rebase 完成：" + result.getStatus();
        } catch (Exception e) {
            return "git rebase 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Reset current HEAD to specified state. soft=unstage only, mixed=unstage+discard index, hard=discard all changes.")
    @Override
    public String gitReset(
            @ToolParam(description = "Reset mode: soft, mixed, hard (default: mixed)") String mode,
            @ToolParam(description = "Target commit/ref (default: HEAD)", required = false) String target,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            ResetCommand cmd = git.reset();
            ResetCommand.ResetType resetType = switch (mode != null ? mode.toLowerCase() : "mixed") {
                case "soft" -> ResetCommand.ResetType.SOFT;
                case "hard" -> ResetCommand.ResetType.HARD;
                case "merge" -> ResetCommand.ResetType.MERGE;
                case "keep" -> ResetCommand.ResetType.KEEP;
                default -> ResetCommand.ResetType.MIXED;
            };
            cmd.setMode(resetType);
            if (target != null && !target.isBlank()) {
                cmd.setRef(target);
            }
            cmd.call();
            return "已 reset 到：" + (target != null ? target : "HEAD") + " (mode=" + mode + ")";
        } catch (Exception e) {
            return "git reset 失败：" + e.getMessage();
        }
    }

    // ==================== Stash / Tag / Remote ====================

    @Tool(description = "Manage stashes: list, save (push), restore (pop/apply), or remove (drop/clear).")
    @Override
    public String gitStash(
            @ToolParam(description = "Action: list, push, pop, apply, drop, clear") String action,
            @ToolParam(description = "Stash message (for push action)", required = false) String message,
            @ToolParam(description = "Stash ref like stash@{0} (for apply/drop action)", required = false) String stashRef,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            return switch (action.toLowerCase()) {
                case "list" -> {
                    Collection<RevCommit> stashes = git.stashList().call();
                    if (stashes.isEmpty()) yield "无 stash";
                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (RevCommit c : stashes) {
                        sb.append("stash@{").append(i++).append("}: ").append(c.getShortMessage()).append("\n");
                    }
                    yield sb.toString();
                }
                case "push" -> {
                    StashCreateCommand cmd = git.stashCreate();
                    if (message != null && !message.isBlank()) cmd.setWorkingDirectoryMessage(message);
                    RevCommit stash = cmd.call();
                    yield stash != null ? "已保存 stash: " + stash.abbreviate(7).name() : "无更改可 stash";
                }
                case "pop" -> {
                    git.stashApply().call();
                    git.stashDrop().call();
                    yield "已 pop 最新 stash";
                }
                case "apply" -> {
                    StashApplyCommand cmd = git.stashApply();
                    if (stashRef != null && !stashRef.isBlank()) cmd.setStashRef(stashRef);
                    cmd.call();
                    yield "已 apply stash" + (stashRef != null ? ": " + stashRef : "");
                }
                case "drop" -> {
                    StashDropCommand cmd = git.stashDrop();
                    if (stashRef != null && stashRef.matches("stash@\\{\\d+}")) {
                        int idx = Integer.parseInt(stashRef.replaceAll("\\D", ""));
                        cmd.setStashRef(idx);
                    }
                    cmd.call();
                    yield "已 drop stash" + (stashRef != null ? ": " + stashRef : "");
                }
                case "clear" -> {
                    git.stashDrop().setAll(true).call();
                    yield "已清除所有 stash";
                }
                default -> "未知操作：" + action + "。支持 list/push/pop/apply/drop/clear";
            };
        } catch (Exception e) {
            return "git stash 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Manage tags: list all tags, create a new tag, or delete a tag.")
    @Override
    public String gitTag(
            @ToolParam(description = "Action: list, create, delete") String action,
            @ToolParam(description = "Tag name (for create/delete)", required = false) String name,
            @ToolParam(description = "Tag message (for annotated tags)", required = false) String message,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            return switch (action.toLowerCase()) {
                case "list" -> {
                    List<Ref> tags = git.tagList().call();
                    if (tags.isEmpty()) yield "无标签";
                    StringBuilder sb = new StringBuilder();
                    for (Ref ref : tags) {
                        sb.append(ref.getName().replace("refs/tags/", "")).append("\n");
                    }
                    yield sb.toString();
                }
                case "create" -> {
                    TagCommand cmd = git.tag().setName(name);
                    if (message != null && !message.isBlank()) {
                        cmd.setMessage(message).setAnnotated(true);
                    }
                    cmd.call();
                    yield "已创建标签：" + name;
                }
                case "delete" -> {
                    git.tagDelete().setTags(name).call();
                    yield "已删除标签：" + name;
                }
                default -> "未知操作：" + action + "。支持 list/create/delete";
            };
        } catch (Exception e) {
            return "git tag 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Manage remote repositories: list, add, remove, or rename remotes.")
    @Override
    public String gitRemote(
            @ToolParam(description = "Action: list, add, remove, rename") String action,
            @ToolParam(description = "Remote name", required = false) String name,
            @ToolParam(description = "Remote URL (for add action)", required = false) String url,
            @ToolParam(description = "New remote name (for rename action)", required = false) String newName,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            StoredConfig config = git.getRepository().getConfig();
            return switch (action.toLowerCase()) {
                case "list" -> {
                    Set<String> remotes = config.getSubsections("remote");
                    if (remotes.isEmpty()) yield "无远程仓库";
                    StringBuilder sb = new StringBuilder();
                    for (String r : remotes) {
                        sb.append(r).append("\t").append(config.getString("remote", r, "url")).append("\n");
                    }
                    yield sb.toString();
                }
                case "add" -> {
                    RemoteAddCommand cmd = git.remoteAdd();
                    cmd.setName(name);
                    cmd.setUri(new URIish(url));
                    cmd.call();
                    yield "已添加远程仓库：" + name + " -> " + url;
                }
                case "remove" -> {
                    git.remoteRemove().setRemoteName(name).call();
                    yield "已移除远程仓库：" + name;
                }
                case "rename" -> {
                    config.setString("remote", newName, "url", config.getString("remote", name, "url"));
                    config.setString("remote", newName, "fetch", config.getString("remote", name, "fetch"));
                    config.unsetSection("remote", name);
                    config.save();
                    yield "已重命名远程仓库：" + name + " -> " + newName;
                }
                default -> "未知操作：" + action + "。支持 list/add/remove/rename";
            };
        } catch (Exception e) {
            return "git remote 失败：" + e.getMessage();
        }
    }

    // ==================== Inspection ====================

    @Tool(description = "Show line-by-line authorship information for a file (git blame). For large files, use startLine/endLine to limit output.")
    @Override
    public String gitBlame(
            @ToolParam(description = "File path relative to repo root") String path,
            @ToolParam(description = "Start line number (1-based, default: 1)", required = false) Integer startLine,
            @ToolParam(description = "End line number (default: end of file)", required = false) Integer endLine,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            BlameResult result = git.blame().setFilePath(path).call();
            if (result == null) return "无法获取 blame 信息：" + path;
            int total = result.getResultContents().size();
            int start = (startLine != null && startLine > 0) ? startLine - 1 : 0;
            int end = (endLine != null && endLine > 0) ? Math.min(endLine, total) : total;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                RevCommit c = result.getSourceCommit(i);
                String author = c != null ? c.getAuthorIdent().getName() : "(unknown)";
                String hash = c != null ? c.abbreviate(7).name() : "0000000";
                int srcLine = result.getSourceLine(i);
                sb.append(String.format("%-7s %-20s %4d %s%n",
                        hash, author, srcLine + 1, result.getResultContents().getString(i)));
            }
            return sb.toString();
        } catch (Exception e) {
            return "git blame 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Show details of a git object (commit, tree, blob, or tag). Displays commit info and diff for commits, content for blobs.")
    @Override
    public String gitShow(
            @ToolParam(description = "Object to show (e.g. HEAD, HEAD~1, commit hash, tag)") String object,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            ObjectId id = git.getRepository().resolve(object);
            if (id == null) return "错误：无法解析 object - " + object;
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                RevCommit commit = rw.parseCommit(id);
                StringBuilder sb = new StringBuilder();
                sb.append("commit ").append(commit.name()).append("\n");
                sb.append("Author: ").append(commit.getAuthorIdent().getName())
                        .append(" <").append(commit.getAuthorIdent().getEmailAddress()).append(">\n");
                sb.append("Date:   ").append(formatEpoch(commit.getCommitTime())).append("\n\n");
                sb.append("    ").append(commit.getFullMessage()).append("\n\n");
                // Show diff
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(out);
                formatter.setRepository(git.getRepository());
                if (commit.getParentCount() > 0) {
                    RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(rw.getObjectReader(), parent.getTree());
                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(rw.getObjectReader(), commit.getTree());
                    formatter.format(oldTree, newTree);
                } else {
                    formatter.format(new EmptyTreeIterator(),
                            new CanonicalTreeParser(null, rw.getObjectReader(), commit.getTree()));
                }
                formatter.close();
                sb.append(out);
                return sb.toString();
            }
        } catch (Exception e) {
            return "git show 失败：" + e.getMessage();
        }
    }

    @Tool(description = "View the reference logs (reflog) to track when branch tips and other references were updated. Useful for recovering lost commits.")
    @Override
    public String gitReflog(
            @ToolParam(description = "Maximum number of entries (default: 20)", required = false) Integer maxCount,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            ReflogReader reader = git.getRepository().getReflogReader("HEAD");
            if (reader == null) return "无 reflog 记录";
            List<ReflogEntry> entries = reader.getReverseEntries();
            int limit = (maxCount != null && maxCount > 0) ? maxCount : 20;
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ReflogEntry entry : entries) {
                if (count >= limit) break;
                sb.append(entry.getNewId().abbreviate(7).name())
                        .append(" HEAD@{").append(count).append("}: ")
                        .append(entry.getComment()).append("\n");
                count++;
            }
            return sb.toString();
        } catch (Exception e) {
            return "git reflog 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Remove untracked files from the working directory. Requires force flag for safety. Use dry-run to preview files that would be removed.")
    @Override
    public String gitClean(
            @ToolParam(description = "If true, actually remove files. If false, just list what would be removed.", required = false) Boolean force,
            @ToolParam(description = "If true, preview only - do not actually delete (default: true)", required = false) Boolean dryRun,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            CleanCommand cmd = git.clean().setForce(Boolean.TRUE.equals(force));
            boolean isDryRun = (dryRun == null || dryRun);
            if (isDryRun) cmd.setDryRun(true);
            Set<String> cleaned = cmd.call();
            if (cleaned.isEmpty()) return "无需清理的文件";
            StringBuilder sb = new StringBuilder(isDryRun ? "预览将被清理的文件（dry-run）：\n" : "已清理：\n");
            for (String f : cleaned) {
                sb.append("  ").append(f).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "git clean 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Cherry-pick commits from other branches. Apply specific commits to the current branch without merging entire branches.")
    @Override
    public String gitCherryPick(
            @ToolParam(description = "List of commit hashes to cherry-pick") List<String> commits,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            CherryPickCommand cmd = git.cherryPick();
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                for (String hash : commits) {
                    ObjectId id = git.getRepository().resolve(hash);
                    if (id == null) return "错误：无法解析 commit - " + hash;
                    cmd.include(rw.parseCommit(id));
                }
            }
            CherryPickResult result = cmd.call();
            return "cherry-pick 完成：" + result.getStatus();
        } catch (Exception e) {
            return "git cherry-pick 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Manage multiple working trees: list worktrees, add new worktrees for parallel work, remove worktrees, or move worktrees to new locations. Uses system git command since JGit doesn't support worktrees natively.")
    @Override
    public String gitWorktree(
            @ToolParam(description = "Action: list, add, remove, move") String action,
            @ToolParam(description = "Worktree path (for add/remove/move)", required = false) String path,
            @ToolParam(description = "Branch name (for add action) or new path (for move action)", required = false) String branch,
            ToolContext toolContext) {
        try {
            Path repoDir = getWorkingDir(toolContext);
            if (!Files.exists(repoDir.resolve(".git"))) {
                return "错误：当前目录不是 Git 仓库 - " + repoDir;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("worktree");
            switch (action.toLowerCase()) {
                case "list" -> cmd.add("list");
                case "add" -> {
                    cmd.add("add");
                    cmd.add(path);
                    if (branch != null && !branch.isBlank()) cmd.add(branch);
                }
                case "remove" -> {
                    cmd.add("remove");
                    cmd.add(path);
                }
                case "move" -> {
                    cmd.add("move");
                    cmd.add(path);
                    if (branch != null && !branch.isBlank()) cmd.add(branch);
                }
                default -> {
                    return "未知操作：" + action + "。支持 list/add/remove/move";
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return "git worktree 失败 (exit " + exitCode + ")：\n" + output;
            }
            return output.isBlank() ? "git worktree " + action + " 完成" : output;
        } catch (Exception e) {
            return "git worktree 失败：" + e.getMessage();
        }
    }

    // ==================== Working dir management ====================

    @Tool(description = "Set the session working directory for all git operations. Accepts a repo name (relative to user's git dir) or an absolute path. Always returns a repository snapshot (status, recent commits, recent tags, remotes).")
    @Override
    public String gitSetWorkingDir(
            @ToolParam(description = "Repo name (relative to user git dir) or absolute path to a git repo") String path,
            ToolContext toolContext) {
        try {
            String username = username(toolContext);
            Path resolved;
            Path inputPath = Paths.get(path);
            if (inputPath.isAbsolute()) {
                resolved = inputPath.normalize();
            } else {
                resolved = getGitBaseDir(username).resolve(path).normalize();
            }
            if (!Files.exists(resolved)) {
                return "错误：目录不存在 - " + resolved;
            }
            if (!Files.exists(resolved.resolve(".git"))) {
                return "错误：不是 Git 仓库 - " + resolved;
            }
            setWorkingDirInContext(toolContext, resolved.toString());
            return "已设置工作目录：" + resolved + "\n" + repoSnapshot(resolved);
        } catch (Exception e) {
            return "设置工作目录失败：" + e.getMessage();
        }
    }

    // ==================== Analysis & Wrap-up ====================

    @Tool(description = "Gather git history context (commits, tags) for changelog analysis. Returns structured commit data the LLM can use to generate changelogs. Changelog file should be read separately.")
    @Override
    public String gitChangelogAnalyze(
            @ToolParam(description = "Start ref (e.g. v1.0.0, or commit hash)", required = false) String from,
            @ToolParam(description = "End ref (e.g. HEAD, v2.0.0)", required = false) String to,
            @ToolParam(description = "Review types: features, fixes, breaking, docs, refactor", required = false) List<String> reviewTypes,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            LogCommand log = git.log();
            if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                ObjectId fromId = git.getRepository().resolve(from);
                ObjectId toId = git.getRepository().resolve(to);
                if (fromId == null) return "错误：无法解析 from ref - " + from;
                if (toId == null) return "错误：无法解析 to ref - " + to;
                log.addRange(fromId, toId);
            } else {
                log.setMaxCount(50);
            }
            Iterable<RevCommit> commits = log.call();

            // Gather tags
            Map<String, String> tagMap = new HashMap<>();
            for (Ref tag : git.tagList().call()) {
                String tagName = tag.getName().replace("refs/tags/", "");
                ObjectId peeled = git.getRepository().getRefDatabase().peel(tag).getPeeledObjectId();
                ObjectId target = peeled != null ? peeled : tag.getObjectId();
                tagMap.put(target.name(), tagName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Changelog Context ===\n\n");

            // Commits
            sb.append("## Commits\n");
            Map<String, List<String>> categorized = new LinkedHashMap<>();
            categorized.put("features", new ArrayList<>());
            categorized.put("fixes", new ArrayList<>());
            categorized.put("breaking", new ArrayList<>());
            categorized.put("docs", new ArrayList<>());
            categorized.put("refactor", new ArrayList<>());
            categorized.put("other", new ArrayList<>());

            for (RevCommit c : commits) {
                String msg = c.getShortMessage().toLowerCase();
                String line = c.abbreviate(7).name() + " " + c.getAuthorIdent().getName() + " " + c.getShortMessage();
                if (msg.startsWith("feat") || msg.startsWith("feature")) categorized.get("features").add(line);
                else if (msg.startsWith("fix")) categorized.get("fixes").add(line);
                else if (msg.startsWith("break") || msg.contains("breaking change")) categorized.get("breaking").add(line);
                else if (msg.startsWith("doc")) categorized.get("docs").add(line);
                else if (msg.startsWith("refactor")) categorized.get("refactor").add(line);
                else categorized.get("other").add(line);
            }

            if (reviewTypes == null || reviewTypes.isEmpty()) {
                reviewTypes = List.of("features", "fixes", "breaking", "docs", "refactor", "other");
            }
            for (String type : reviewTypes) {
                List<String> items = categorized.getOrDefault(type, List.of());
                if (!items.isEmpty()) {
                    sb.append("\n### ").append(type.toUpperCase()).append("\n");
                    for (String item : items) {
                        sb.append("- ").append(item).append("\n");
                    }
                }
            }

            // Tags
            sb.append("\n## Tags\n");
            if (tagMap.isEmpty()) {
                sb.append("(none)\n");
            } else {
                for (var entry : tagMap.entrySet()) {
                    sb.append("- ").append(entry.getValue()).append(" (").append(entry.getKey().substring(0, 7)).append(")\n");
                }
            }

            // Analysis framework
            sb.append("\n## Review Instructions\n");
            sb.append("1. Group related commits into logical changelog entries\n");
            sb.append("2. Use user-facing language, not implementation details\n");
            sb.append("3. Highlight breaking changes prominently\n");
            sb.append("4. Include commit references for traceability\n");

            return sb.toString();
        } catch (Exception e) {
            return "changelog analyze 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Returns a Git wrap-up protocol: an acceptance-criteria checklist the agent must satisfy before the session is considered shipped. Includes a repository snapshot (status, recent commits, recent tags).")
    @Override
    public String gitWrapupInstructions(ToolContext toolContext) {
        try {
            Path repoDir = getWorkingDir(toolContext);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Git Wrap-up Protocol ===\n\n");

            sb.append("## Repository Snapshot\n");
            sb.append(repoSnapshot(repoDir)).append("\n");

            sb.append("## Acceptance Criteria Checklist\n");
            sb.append("Before considering this session shipped, verify:\n\n");
            sb.append("- [ ] All changes are committed (working tree clean)\n");
            sb.append("- [ ] Commit messages follow convention (type: description)\n");
            sb.append("- [ ] No debug/temporary code in commits\n");
            sb.append("- [ ] Tests pass locally\n");
            sb.append("- [ ] Changes pushed to remote (if applicable)\n");
            sb.append("- [ ] PR/MR created (if applicable)\n");
            sb.append("- [ ] Changelog updated (if applicable)\n");
            sb.append("- [ ] Documentation updated (if applicable)\n");

            sb.append("\n## Commit & Release Steps\n");
            sb.append("1. git status — verify working tree is clean\n");
            sb.append("2. git log — verify commit history is clean\n");
            sb.append("3. git push — upload to remote\n");
            sb.append("4. Create tag if release: git tag v<version>\n");
            sb.append("5. git push --tags — upload tags\n");

            return sb.toString();
        } catch (Exception e) {
            return "wrapup instructions 失败：" + e.getMessage();
        }
    }

    // ==================== Helpers ====================

    private String username(ToolContext toolContext) {
        return (String) toolContext.getContext().get("username");
    }

    private Path getGitBaseDir(String username) {
        return Paths.get(BASE_PATH, username, GIT_SUBDIR);
    }

    private Path getWorkingDir(ToolContext toolContext) throws IOException {
        String username = username(toolContext);
        String ctxWd = (String) toolContext.getContext().get(WORKING_DIR_KEY);
        if (ctxWd != null) {
            return Paths.get(ctxWd);
        }
        return getGitBaseDir(username);
    }

    private void setWorkingDirInContext(ToolContext toolContext, String path) {
        toolContext.getContext().put(WORKING_DIR_KEY, path);
    }

    private Git openRepo(ToolContext toolContext) throws IOException {
        Path wd = getWorkingDir(toolContext);
        if (!Files.exists(wd.resolve(".git"))) {
            throw new IOException("当前目录不是 Git 仓库：" + wd + "。请先调用 git_set_working_dir 或 git_init/git_clone。");
        }
        return Git.open(wd.toFile());
    }

    private CredentialsProvider buildCredentialsProvider(ToolContext toolContext) {
        // Try ToolContext overrides first
        String user = (String) toolContext.getContext().get("gitUsername");
        String token = (String) toolContext.getContext().get("gitToken");
        if (user == null) user = defaultGitUsername;
        if (token == null) token = defaultGitToken;
        if (user != null && !user.isBlank() && token != null && !token.isBlank()) {
            return new UsernamePasswordCredentialsProvider(user, token);
        }
        return null;
    }

    private void registerRepo(Path repoDir, String repoName, String username) {
        try {
            String pathStr = repoDir.toString();
            FileRecord existing = file.getByExactPath(pathStr, username);
            if (existing != null) return;
            String fileId = UUID.randomUUID().toString();
            file.insert(new FileRecord(
                    fileId,
                    null,
                    repoName,
                    0,
                    LocalDateTime.now(),
                    pathStr,
                    "git",
                    null
            ), username);
        } catch (Exception ignored) {
        }
    }

    private void deleteDirectoryRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String repoSnapshot(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            StringBuilder sb = new StringBuilder();
            // Branch
            sb.append("当前分支：").append(git.getRepository().getBranch()).append("\n");

            // Status summary
            Status status = git.status().call();
            int changes = status.getChanged().size() + status.getModified().size()
                    + status.getAdded().size() + status.getRemoved().size()
                    + status.getUntracked().size() + status.getMissing().size();
            sb.append("工作区状态：").append(changes == 0 ? "干净" : changes + " 个文件变更").append("\n");

            // Recent commits
            sb.append("\n最近提交：\n");
            int count = 0;
            for (RevCommit c : git.log().setMaxCount(5).call()) {
                sb.append("  ").append(c.abbreviate(7).name())
                        .append(" ").append(c.getShortMessage()).append("\n");
                count++;
            }
            if (count == 0) sb.append("  (无提交)\n");

            // Tags
            List<Ref> tags = git.tagList().call();
            sb.append("\n标签：");
            if (tags.isEmpty()) {
                sb.append("(无)");
            } else {
                for (Ref tag : tags) {
                    sb.append(tag.getName().replace("refs/tags/", "")).append(" ");
                }
            }
            sb.append("\n");

            // Remotes
            Set<String> remotes = git.getRepository().getConfig().getSubsections("remote");
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

    private void appendSet(StringBuilder sb, String label, Set<String> items) {
        if (items != null && !items.isEmpty()) {
            sb.append(label).append("：\n");
            for (String item : items) {
                sb.append("  ").append(item).append("\n");
            }
        }
    }

    private String formatEpoch(int epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDateTime().format(DTF);
    }
}
