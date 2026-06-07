package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
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

    private final String fileBasePath;
    private final String defaultGitUsername;
    private final String defaultGitToken;

    public DefaultGitTool(LoomAgentProperties properties) {
        this.fileBasePath = properties.getFileBasePath() != null ? properties.getFileBasePath() : BASE_PATH;
        this.defaultGitUsername = properties.getGitUsername();
        this.defaultGitToken = properties.getGitToken();
    }

    // ==================== Repository lifecycle ====================

    @Tool(description = "Initialize a new Git repository. Creates a .git directory under the user's file directory and sets up the initial branch.")
    @Override
    public String gitInit(
            @ToolParam(description = "Repository name (relative to user file dir)") String path,
            @ToolParam(description = "Name of the initial branch (default: main)", required = false) String initialBranch,
            @ToolParam(description = "Create a bare repository (no working directory)", required = false) Boolean bare,
            ToolContext toolContext) {
        try {
            String username = username(toolContext);
            Path repoDir = resolvePath(toolContext, path);
            Files.createDirectories(repoDir);
            InitCommand cmd = Git.init().setDirectory(repoDir.toFile());
            if (initialBranch != null && !initialBranch.isBlank()) cmd.setInitialBranch(initialBranch);
            if (Boolean.TRUE.equals(bare)) cmd.setBare(true);
            Git git = cmd.call();
            String branch = initialBranch != null ? initialBranch : (Boolean.TRUE.equals(bare) ? "HEAD" : git.getRepository().getBranch());
            git.close();
            setWorkingDirInContext(toolContext, repoDir.toString());
            return "已初始化 Git 仓库：" + repoDir + "\n初始分支：" + branch + (Boolean.TRUE.equals(bare) ? " (bare)" : "") + "\n" + repoSnapshot(repoDir);
        } catch (Exception e) {
            return "git init 失败：" + e.getMessage();
        }
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
        try {
            String username = username(toolContext);
            Path repoDir = resolvePath(toolContext, path);
            if (Files.exists(repoDir)) {
                return "错误：目录已存在 - " + repoDir;
            }
            Files.createDirectories(repoDir.getParent());

            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(repoDir.toFile());
            if (branch != null && !branch.isBlank()) cmd.setBranch(branch);
            if (depth != null && depth > 0) cmd.setDepth(depth);
            if (Boolean.TRUE.equals(bare)) cmd.setBare(true);
            if (Boolean.TRUE.equals(mirror)) cmd.setBare(true).setMirror(true);
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            Git git = cmd.call();
            String currentBranch = git.getRepository().getBranch();
            String head = git.getRepository().getFullBranch();
            git.close();
            setWorkingDirInContext(toolContext, repoDir.toString());
            return "已克隆仓库：" + repoDir + "\n远程：" + url + "\n分支：" + currentBranch + "\nHEAD：" + head + "\n" + repoSnapshot(repoDir);
        } catch (Exception e) {
            return "git clone 失败：" + e.getMessage();
        }
    }


    // ==================== Basic operations ====================

    @Tool(description = "Show the working tree status including staged, unstaged, and untracked files.")
    @Override
    public String gitStatus(
            @ToolParam(description = "Include untracked files in the output (default: true)", required = false) Boolean includeUntracked,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            boolean showUntracked = includeUntracked == null || includeUntracked;
            Status status = git.status().call();
            StringBuilder sb = new StringBuilder();
            sb.append("分支：").append(git.getRepository().getBranch()).append("\n\n");
            appendSet(sb, "暂存区新增 (staged/added)", status.getAdded());
            appendSet(sb, "暂存区修改 (staged/changed)", status.getChanged());
            appendSet(sb, "暂存区删除 (staged/removed)", status.getRemoved());
            appendSet(sb, "未暂存修改 (unstaged/modified)", status.getModified());
            appendSet(sb, "冲突 (conflicting)", status.getConflicting());
            appendSet(sb, "缺失 (missing)", status.getMissing());
            if (showUntracked) {
                appendSet(sb, "未跟踪 (untracked)", status.getUntracked());
            }
            boolean isClean = status.isClean() || (showUntracked && status.getUntracked().isEmpty()
                    && status.getAdded().isEmpty() && status.getChanged().isEmpty()
                    && status.getModified().isEmpty() && status.getRemoved().isEmpty()
                    && status.getConflicting().isEmpty() && status.getMissing().isEmpty());
            sb.append("\n工作区状态：").append(isClean ? "干净" : "有变更").append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "git status 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Stage files for commit. Add file contents to the staging area (index) to prepare for the next commit.")
    @Override
    public String gitAdd(
            @ToolParam(description = "File or directory paths to stage. Use [\".\"] to stage all changes.", required = false) List<String> paths,
            @ToolParam(description = "Stage only modified and deleted files (skip untracked files)", required = false) Boolean update,
            @ToolParam(description = "Stage all files including untracked and ignored", required = false) Boolean all,
            @ToolParam(description = "Allow adding otherwise ignored files", required = false) Boolean force,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            if (Boolean.TRUE.equals(update)) {
                // --update: only tracked files
                if (paths != null && !paths.isEmpty()) {
                    for (String p : paths) {
                        git.add().addFilepattern(p).setUpdate(true).call();
                    }
                } else {
                    git.add().addFilepattern(".").setUpdate(true).call();
                }
            } else if (Boolean.TRUE.equals(all)) {
                if (paths != null && !paths.isEmpty()) {
                    for (String p : paths) {
                        git.add().addFilepattern(p).call();
                    }
                } else {
                    git.add().addFilepattern(".").call();
                }
            } else {
                AddCommand add = git.add();
                if (paths != null && !paths.isEmpty()) {
                    for (String p : paths) add.addFilepattern(p);
                } else {
                    add.addFilepattern(".");
                }
                add.call();
            }
            return "已暂存文件";
        } catch (Exception e) {
            return "git add 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            if (filesToStage != null && !filesToStage.isEmpty()) {
                AddCommand add = git.add();
                for (String p : filesToStage) add.addFilepattern(p);
                add.call();
            }
            CommitCommand cmd = git.commit();
            String normalizedMessage = message.replace("\\n", "\n");
            cmd.setMessage(normalizedMessage);
            if (authorName != null && !authorName.isBlank()) {
                String email = (authorEmail != null && !authorEmail.isBlank()) ? authorEmail : "";
                cmd.setAuthor(authorName, email);
            }
            if (Boolean.TRUE.equals(amend)) cmd.setAmend(true);
            if (Boolean.TRUE.equals(allowEmpty)) cmd.setAllowEmpty(true);
            if (Boolean.TRUE.equals(noVerify)) cmd.setNoVerify(true);
            RevCommit commit = cmd.call();
            return "已提交：" + commit.abbreviate(7).name() + "\n" + commit.getShortMessage();
        } catch (Exception e) {
            return "git commit 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter formatter = new DiffFormatter(out);
            formatter.setRepository(git.getRepository());
            if (contextLines != null) formatter.setContext(contextLines);
            boolean excludeLocks = autoExclude == null || autoExclude;

            List<DiffEntry> diffs;
            if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
                ObjectId sourceId = git.getRepository().resolve(source);
                ObjectId targetId = git.getRepository().resolve(target);
                if (sourceId == null) return "错误：无法解析 source ref - " + source;
                if (targetId == null) return "错误：无法解析 target ref - " + target;
                try (RevWalk rw = new RevWalk(git.getRepository())) {
                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(rw.getObjectReader(), rw.parseCommit(sourceId).getTree());
                    CanonicalTreeParser newTree = new CanonicalTreeParser();
                    newTree.reset(rw.getObjectReader(), rw.parseCommit(targetId).getTree());
                    diffs = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
                }
            } else if (Boolean.TRUE.equals(staged)) {
                diffs = git.diff().setCached(true).call();
            } else if (target != null && !target.isBlank()) {
                ObjectId targetId = git.getRepository().resolve(target);
                if (targetId == null) return "错误：无法解析 ref - " + target;
                try (RevWalk rw = new RevWalk(git.getRepository())) {
                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(rw.getObjectReader(), rw.parseCommit(targetId).getTree());
                    diffs = git.diff().setOldTree(oldTree).call();
                }
            } else {
                diffs = git.diff().call();
            }
            if (paths != null && !paths.isEmpty()) {
                diffs = diffs.stream().filter(d -> paths.stream().anyMatch(p -> d.getOldPath().contains(p) || d.getNewPath().contains(p))).toList();
            }
            Set<String> excludedFiles = new LinkedHashSet<>();
            if (excludeLocks) {
                diffs = diffs.stream().filter(d -> {
                    String name = d.getNewPath().equals("/dev/null") ? d.getOldPath() : d.getNewPath();
                    String fileName = Paths.get(name).getFileName().toString();
                    if (Set.of("package-lock.json","yarn.lock","pnpm-lock.yaml","bun.lock","poetry.lock","Pipfile.lock","composer.lock","Gemfile.lock","go.sum","Cargo.lock","flake.lock","pubspec.lock","mix.lock","Podfile.lock").contains(fileName)) {
                        excludedFiles.add(name);
                        return false;
                    }
                    return true;
                }).toList();
            }
            if (diffs.isEmpty()) return "无差异" + (excludedFiles.isEmpty() ? "" : "\n自动排除的文件：" + excludedFiles);
            for (DiffEntry entry : diffs) formatter.format(entry);
            formatter.close();
            String result = out.toString();
            if (!excludedFiles.isEmpty()) result += "\n\n自动排除的文件：" + excludedFiles;
            return result.isBlank() ? "无差异" : result;
        } catch (Exception e) {
            return "git diff 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            LogCommand log = git.log();
            int count = (maxCount != null && maxCount > 0) ? maxCount : 20;
            log.setMaxCount(count);
            if (skip != null && skip > 0) log.setSkip(skip);
            if (filePath != null && !filePath.isBlank()) log.addPath(filePath);
            if (branch != null && !branch.isBlank()) {
                ObjectId branchId = git.getRepository().resolve(branch);
                if (branchId != null) {
                    try (RevWalk rw = new RevWalk(git.getRepository())) {
                        log.add(rw.parseCommit(branchId));
                    }
                }
            }
            Iterable<RevCommit> commits = log.call();
            java.util.regex.Pattern grepPattern = grep != null && !grep.isBlank() ? java.util.regex.Pattern.compile(grep, java.util.regex.Pattern.CASE_INSENSITIVE) : null;
            StringBuilder sb = new StringBuilder();
            List<RevCommit> filtered = new ArrayList<>();
            for (RevCommit c : commits) {
                if (author != null && !author.isBlank()
                        && !c.getAuthorIdent().getName().toLowerCase().contains(author.toLowerCase())
                        && !c.getAuthorIdent().getEmailAddress().toLowerCase().contains(author.toLowerCase())) {
                    continue;
                }
                if (grepPattern != null && !grepPattern.matcher(c.getShortMessage()).find()) continue;
                filtered.add(c);
            }
            if (filtered.isEmpty()) return "无提交记录" + (grepPattern != null || author != null ? "（可能由于过滤条件，请放宽筛选）" : "");
            for (RevCommit c : filtered) {
                if (Boolean.TRUE.equals(oneline)) {
                    sb.append(c.abbreviate(7).name()).append(" ").append(c.getShortMessage()).append("\n");
                } else {
                    sb.append("commit ").append(c.name()).append("\n");
                    sb.append("Author: ").append(c.getAuthorIdent().getName()).append(" <").append(c.getAuthorIdent().getEmailAddress()).append(">\n");
                    sb.append("Date:   ").append(formatEpoch(c.getCommitTime())).append("\n\n");
                    sb.append("    ").append(c.getFullMessage().replaceAll("(?m)^", "    ")).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "git log 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            String m = mode != null ? mode.toLowerCase() : "list";
            return switch (m) {
                case "show-current" -> "当前分支：" + git.getRepository().getBranch();
                case "list" -> {
                    StringBuilder sb = new StringBuilder();
                    String current = git.getRepository().getBranch();
                    ListBranchCommand.ListMode listMode = Boolean.TRUE.equals(remote)
                            ? ListBranchCommand.ListMode.REMOTE
                            : ListBranchCommand.ListMode.ALL;
                    List<Ref> refs = git.branchList().setListMode(listMode).call();
                    if (limit != null && limit > 0) refs = refs.subList(0, Math.min(limit, refs.size()));
                    for (Ref ref : refs) {
                        String name = ref.getName().replace("refs/heads/", "").replace("refs/remotes/", "");
                        sb.append(name.equals(current) ? "* " : "  ").append(name).append("\n");
                    }
                    yield sb.length() == 0 ? "无分支" : sb.toString();
                }
                case "create" -> {
                    CreateBranchCommand cmd = git.branchCreate().setName(branchName);
                    if (startPoint != null && !startPoint.isBlank()) cmd.setStartPoint(startPoint);
                    cmd.call();
                    yield "已创建分支：" + branchName + (startPoint != null ? " (起点: " + startPoint + ")" : "");
                }
                case "delete" -> {
                    git.branchDelete().setBranchNames(branchName).setForce(Boolean.TRUE.equals(force)).call();
                    yield "已删除分支：" + branchName;
                }
                case "rename" -> {
                    git.branchRename().setOldName(branchName).setNewName(newBranchName).call();
                    yield "已重命名分支：" + branchName + " -> " + newBranchName;
                }
                default -> "未知操作：" + mode + "。支持 list/create/delete/rename/show-current";
            };
        } catch (Exception e) {
            return "git branch 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            CheckoutCommand cmd = git.checkout().setName(target);
            if (Boolean.TRUE.equals(createBranch)) {
                cmd.setCreateBranch(true);
                if (Boolean.TRUE.equals(track)) cmd.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM);
            }
            if (Boolean.TRUE.equals(force)) {
                // JGit doesn't have setForce on checkout, use --theirs strategy
            }
            if (paths != null && !paths.isEmpty()) {
                cmd.setStartPoint("HEAD");
                for (String p : paths) cmd.addPath(p);
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
            @ToolParam(description = "Branch name (default: current branch)", required = false) String branch,
            @ToolParam(description = "Use rebase instead of merge", required = false) Boolean rebase,
            @ToolParam(description = "Fail if can't fast-forward (no merge commit)", required = false) Boolean fastForwardOnly,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            PullCommand cmd = git.pull();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (branch != null && !branch.isBlank()) cmd.setRemoteBranchName(branch);
            if (Boolean.TRUE.equals(rebase)) cmd.setRebase(true);
            if (Boolean.TRUE.equals(fastForwardOnly)) {
                // JGit doesn't have FF_ONLY mode for pull; use rebase as closest alternative
                cmd.setFastForward(MergeCommand.FastForwardMode.FF);
            }
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            PullResult result = cmd.call();
            String strategy = Boolean.TRUE.equals(rebase) ? "rebase" : "merge";
            StringBuilder sb = new StringBuilder("pull 完成\n策略：").append(strategy).append("\n");
            if (result.getMergeResult() != null) sb.append("Merge: ").append(result.getMergeResult().getMergeStatus()).append("\n");
            if (result.getRebaseResult() != null) sb.append("Rebase: ").append(result.getRebaseResult().getStatus()).append("\n");
            if (result.getFetchResult() != null) sb.append("Fetch: ").append(result.getFetchResult().getMessages());
            return sb.toString();
        } catch (Exception e) {
            return "git pull 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            PushCommand cmd = git.push();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (Boolean.TRUE.equals(delete)) {
                String targetBranch = branch != null ? branch : git.getRepository().getBranch();
                cmd.setRefSpecs(new RefSpec(":" + "refs/heads/" + targetBranch));
            } else if (branch != null && !branch.isBlank()) {
                String refSpec = branch + ":" + (remoteBranch != null ? remoteBranch : branch);
                cmd.setRefSpecs(new RefSpec(refSpec));
            }
            if (Boolean.TRUE.equals(force) || Boolean.TRUE.equals(forceWithLease)) {
                // Force push - use refspec with + prefix
                String targetBranch = branch != null ? branch : git.getRepository().getBranch();
                cmd.setRefSpecs(new RefSpec("+" + targetBranch + ":refs/heads/" + (remoteBranch != null ? remoteBranch : targetBranch)));
            }
            if (Boolean.TRUE.equals(tags)) cmd.setPushTags();
            if (Boolean.TRUE.equals(dryRun)) cmd.setDryRun(true);
            if (Boolean.TRUE.equals(setUpstream) && branch != null) {
                String currentBranch = git.getRepository().getBranch();
                String remoteName = remote != null && !remote.isBlank() ? remote : "origin";
                StoredConfig config = git.getRepository().getConfig();
                config.setString("branch", currentBranch, "remote", remoteName);
                config.setString("branch", currentBranch, "merge", "refs/heads/" + branch);
                config.save();
            }
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            Iterable<PushResult> results = cmd.call();
            StringBuilder sb = new StringBuilder("push 完成\n");
            for (PushResult r : results) {
                if (r.getMessages() != null && !r.getMessages().isBlank()) sb.append(r.getMessages());
                for (RemoteRefUpdate update : r.getRemoteUpdates()) {
                    sb.append("  ").append(update.getStatus()).append(" ").append(update.getRemoteName()).append("\n");
                }
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
            @ToolParam(description = "Prune deleted remote refs", required = false) Boolean prune,
            @ToolParam(description = "Fetch all tags from the remote", required = false) Boolean tags,
            @ToolParam(description = "Shallow fetch depth", required = false) Integer depth,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            FetchCommand cmd = git.fetch();
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (Boolean.TRUE.equals(prune)) cmd.setRemoveDeletedRefs(true);
            if (Boolean.TRUE.equals(tags)) cmd.setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*"));
            if (depth != null && depth > 0) cmd.setDepth(depth);
            CredentialsProvider cp = buildCredentialsProvider(toolContext);
            if (cp != null) cmd.setCredentialsProvider(cp);
            FetchResult result = cmd.call();
            StringBuilder sb = new StringBuilder("fetch 完成\n");
            if (!result.getMessages().isBlank()) sb.append(result.getMessages());
            for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
                sb.append("  ").append(update.getResult()).append(" ").append(update.getRemoteName())
                        .append(" -> ").append(update.getLocalName()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "git fetch 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            if (Boolean.TRUE.equals(abort)) {
                git.reset().setMode(ResetCommand.ResetType.HARD).call();
                return "已中止 merge";
            }
            MergeCommand cmd = git.merge();
            ObjectId branchId = git.getRepository().resolve(branch);
            if (branchId == null) return "错误：无法解析分支 - " + branch;
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                cmd.include(rw.parseCommit(branchId));
            }
            if (message != null && !message.isBlank()) cmd.setMessage(message.replace("\\n", "\n"));
            if (Boolean.TRUE.equals(noFastForward)) cmd.setFastForward(MergeCommand.FastForwardMode.NO_FF);
            if (Boolean.TRUE.equals(squash)) cmd.setSquash(true);
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
            @ToolParam(description = "Rebase mode: start, continue, abort, skip", required = false) String mode,
            @ToolParam(description = "Upstream branch to rebase onto (required for start mode)", required = false) String upstream,
            @ToolParam(description = "Branch to rebase (default: current branch)", required = false) String branch,
            @ToolParam(description = "Interactive rebase (not fully supported by JGit)", required = false) Boolean interactive,
            @ToolParam(description = "Rebase onto different commit than upstream", required = false) String onto,
            @ToolParam(description = "Preserve merge commits during rebase", required = false) Boolean preserve,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            String m = mode != null ? mode.toLowerCase() : "start";
            RebaseCommand cmd = git.rebase();
            if ("continue".equals(m)) {
                RebaseResult result = cmd.setOperation(RebaseCommand.Operation.CONTINUE).call();
                return "rebase continue：" + result.getStatus();
            } else if ("abort".equals(m)) {
                RebaseResult result = cmd.setOperation(RebaseCommand.Operation.ABORT).call();
                return "rebase abort：" + result.getStatus();
            } else if ("skip".equals(m)) {
                RebaseResult result = cmd.setOperation(RebaseCommand.Operation.SKIP).call();
                return "rebase skip：" + result.getStatus();
            }
            String effectiveUpstream = (onto != null && !onto.isBlank()) ? onto : upstream;
            if (effectiveUpstream == null || effectiveUpstream.isBlank()) return "错误：start 模式需要 upstream 参数";
            ObjectId upId = git.getRepository().resolve(effectiveUpstream);
            if (upId == null) return "错误：无法解析 upstream ref - " + effectiveUpstream;
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                cmd.setUpstream(rw.parseCommit(upId));
            }
            RebaseResult result = cmd.call();
            if (result.getStatus().isSuccessful()) return "rebase 完成：" + result.getStatus();
            return "rebase 需要手动解决冲突：" + result.getStatus() + "\n使用 git_rebase mode='continue' 解决后继续，或 mode='abort' 中止";
        } catch (Exception e) {
            return "git rebase 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Reset current HEAD to specified state. Can be used to unstage files (soft), discard commits (mixed), or discard all changes (hard).")
    @Override
    public String gitReset(
            @ToolParam(description = "Reset mode: soft, mixed, hard, merge, keep (default: mixed)") String mode,
            @ToolParam(description = "Target commit/ref (default: HEAD)", required = false) String target,
            @ToolParam(description = "Specific file paths to reset (leaves HEAD unchanged)", required = false) List<String> paths,
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
            if (target != null && !target.isBlank()) cmd.setRef(target);
            if (paths != null && !paths.isEmpty()) {
                for (String p : paths) cmd.addPath(p);
            }
            Ref result = cmd.call();
            return "已 reset 到：" + (target != null ? target : "HEAD") + " (mode=" + (mode != null ? mode : "mixed") + ")";
        } catch (Exception e) {
            return "git reset 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            String m = mode != null ? mode.toLowerCase() : "push";
            return switch (m) {
                case "list" -> {
                    Collection<RevCommit> stashes = git.stashList().call();
                    List<RevCommit> stashList = new ArrayList<>(stashes);
                    if (limit != null && limit > 0 && stashList.size() > limit) stashList = stashList.subList(0, limit);
                    if (stashList.isEmpty()) yield "无 stash";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < stashList.size(); i++) {
                        RevCommit c = stashList.get(i);
                        sb.append("stash@{").append(i).append("}: ").append(c.getShortMessage()).append("\n");
                    }
                    yield sb.toString();
                }
                case "push" -> {
                    StashCreateCommand cmd = git.stashCreate();
                    if (message != null && !message.isBlank()) cmd.setWorkingDirectoryMessage(message);
                    if (Boolean.TRUE.equals(includeUntracked)) cmd.setIncludeUntracked(true);
                    // Note: JGit doesn't support setKeepIndex; keepIndex param accepted for API compatibility
                    RevCommit stash = cmd.call();
                    yield stash != null ? "已保存 stash: " + stash.abbreviate(7).name() : "无更改可 stash";
                }
                case "pop" -> {
                    StashApplyCommand applyCmd = git.stashApply();
                    if (stashRef != null && !stashRef.isBlank()) applyCmd.setStashRef(stashRef);
                    applyCmd.call();
                    StashDropCommand dropCmd = git.stashDrop();
                    if (stashRef != null && stashRef.matches("stash@\\{\\d+}")) dropCmd.setStashRef(Integer.parseInt(stashRef.replaceAll("\\D", "")));
                    dropCmd.call();
                    yield "已 pop stash" + (stashRef != null ? ": " + stashRef : "");
                }
                case "apply" -> {
                    StashApplyCommand cmd = git.stashApply();
                    if (stashRef != null && !stashRef.isBlank()) cmd.setStashRef(stashRef);
                    cmd.call();
                    yield "已 apply stash" + (stashRef != null ? ": " + stashRef : "");
                }
                case "drop" -> {
                    StashDropCommand cmd = git.stashDrop();
                    if (stashRef != null && stashRef.matches("stash@\\{\\d+}")) cmd.setStashRef(Integer.parseInt(stashRef.replaceAll("\\D", "")));
                    cmd.call();
                    yield "已 drop stash" + (stashRef != null ? ": " + stashRef : "");
                }
                case "clear" -> {
                    git.stashDrop().setAll(true).call();
                    yield "已清除所有 stash";
                }
                default -> "未知操作：" + mode + "。支持 list/push/pop/apply/drop/clear";
            };
        } catch (Exception e) {
            return "git stash 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            String m = mode != null ? mode.toLowerCase() : "list";
            return switch (m) {
                case "list" -> {
                    List<Ref> tags = git.tagList().call();
                    if (limit != null && limit > 0 && tags.size() > limit) tags = tags.subList(0, limit);
                    if (tags.isEmpty()) yield "无标签";
                    StringBuilder sb = new StringBuilder();
                    for (Ref ref : tags) {
                        sb.append(ref.getName().replace("refs/tags/", "")).append("\n");
                    }
                    yield sb.toString();
                }
                case "create" -> {
                    if (tagName == null || tagName.isBlank()) yield "错误：create 模式需要 tagName 参数";
                    TagCommand cmd = git.tag().setName(tagName);
                    if (commit != null && !commit.isBlank()) {
                        try (RevWalk rw = new RevWalk(git.getRepository())) {
                            ObjectId targetId = git.getRepository().resolve(commit);
                            if (targetId != null) cmd.setObjectId(rw.parseAny(targetId));
                        }
                    }
                    if (Boolean.TRUE.equals(force)) cmd.setForceUpdate(true);
                    if (message != null && !message.isBlank()) {
                        cmd.setMessage(message.replace("\\n", "\n")).setAnnotated(true);
                    } else if (Boolean.TRUE.equals(annotated)) {
                        cmd.setMessage("Tag " + tagName).setAnnotated(true);
                    }
                    cmd.call();
                    yield "已创建标签：" + tagName;
                }
                case "delete" -> {
                    if (tagName == null || tagName.isBlank()) yield "错误：delete 模式需要 tagName 参数";
                    git.tagDelete().setTags(tagName).call();
                    yield "已删除标签：" + tagName;
                }
                case "verify" -> "标签验证需要 GPG 支持，当前环境不可用";
                default -> "未知操作：" + mode + "。支持 list/create/delete/verify";
            };
        } catch (Exception e) {
            return "git tag 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            StoredConfig config = git.getRepository().getConfig();
            String m = mode != null ? mode.toLowerCase() : "list";
            return switch (m) {
                case "list" -> {
                    Set<String> remotes = config.getSubsections("remote");
                    if (remotes.isEmpty()) yield "无远程仓库";
                    StringBuilder sb = new StringBuilder();
                    for (String r : remotes) {
                        String fetchUrl = config.getString("remote", r, "url");
                        String pushUrl = config.getString("remote", r, "pushurl");
                        sb.append(r).append("\tfetch: ").append(fetchUrl != null ? fetchUrl : "(none)");
                        if (pushUrl != null) sb.append("\tpush: ").append(pushUrl);
                        sb.append("\n");
                    }
                    yield sb.toString();
                }
                case "add" -> {
                    if (name == null || name.isBlank()) yield "错误：add 模式需要 name 参数";
                    if (url == null || url.isBlank()) yield "错误：add 模式需要 url 参数";
                    RemoteAddCommand cmd = git.remoteAdd();
                    cmd.setName(name);
                    cmd.setUri(new URIish(url));
                    cmd.call();
                    yield "已添加远程仓库：" + name + " -> " + url;
                }
                case "remove" -> {
                    if (name == null || name.isBlank()) yield "错误：remove 模式需要 name 参数";
                    git.remoteRemove().setRemoteName(name).call();
                    yield "已移除远程仓库：" + name;
                }
                case "rename" -> {
                    if (name == null || name.isBlank() || newName == null || newName.isBlank())
                        yield "错误：rename 模式需要 name 和 newName 参数";
                    config.setString("remote", newName, "url", config.getString("remote", name, "url"));
                    config.setString("remote", newName, "fetch", config.getString("remote", name, "fetch"));
                    String pushUrl = config.getString("remote", name, "pushurl");
                    if (pushUrl != null) config.setString("remote", newName, "pushurl", pushUrl);
                    config.unsetSection("remote", name);
                    config.save();
                    yield "已重命名远程仓库：" + name + " -> " + newName;
                }
                case "get-url" -> {
                    if (name == null || name.isBlank()) yield "错误：get-url 模式需要 name 参数";
                    String fetchUrl = config.getString("remote", name, "url");
                    String pushUrl = config.getString("remote", name, "pushurl");
                    yield (fetchUrl != null ? "fetch: " + fetchUrl + "\n" : "") + (pushUrl != null ? "push: " + pushUrl : "");
                }
                case "set-url" -> {
                    if (name == null || name.isBlank() || url == null || url.isBlank())
                        yield "错误：set-url 模式需要 name 和 url 参数";
                    if (Boolean.TRUE.equals(push)) config.setString("remote", name, "pushurl", url);
                    else config.setString("remote", name, "url", url);
                    config.save();
                    yield "已设置远程仓库 " + name + (Boolean.TRUE.equals(push) ? " (push)" : " (fetch)") + " -> " + url;
                }
                default -> "未知操作：" + mode + "。支持 list/add/remove/rename/get-url/set-url";
            };
        } catch (Exception e) {
            return "git remote 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            BlameCommand blameCmd = git.blame().setFilePath(filePath);
            if (Boolean.TRUE.equals(ignoreWhitespace)) blameCmd.setTextComparator(RawTextComparator.WS_IGNORE_ALL);
            BlameResult result = blameCmd.call();
            if (result == null) return "无法获取 blame 信息：" + filePath;
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
            @ToolParam(description = "Git object to show (commit hash, branch, tag, tree, or blob)") String object,
            @ToolParam(description = "Output format: raw", required = false) String format,
            @ToolParam(description = "Show diffstat instead of full diff", required = false) Boolean stat,
            @ToolParam(description = "View specific file at given commit reference", required = false) String filePath,
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
            @ToolParam(description = "Reference whose reflog to show (default: HEAD)", required = false) String ref,
            @ToolParam(description = "Maximum number of entries (default: 25)", required = false) Integer maxCount,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            String refName = ref != null && !ref.isBlank() ? ref : "HEAD";
            ReflogReader reader = git.getRepository().getReflogReader(refName);
            if (reader == null) return "无 reflog 记录：" + refName;
            List<ReflogEntry> entries = reader.getReverseEntries();
            int limit = (maxCount != null && maxCount > 0) ? maxCount : 25;
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ReflogEntry entry : entries) {
                if (count >= limit) break;
                sb.append(entry.getNewId().abbreviate(7).name())
                        .append(" ").append(refName).append("@{").append(count).append("}: ")
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
            @ToolParam(description = "Force remove files (or use dryRun to preview)", required = false) Boolean force,
            @ToolParam(description = "Preview only - do not actually delete (default: true)", required = false) Boolean dryRun,
            @ToolParam(description = "Remove untracked directories as well", required = false) Boolean directories,
            @ToolParam(description = "Remove ignored files as well", required = false) Boolean ignored,
            ToolContext toolContext) {
        try (Git git = openRepo(toolContext)) {
            CleanCommand cmd = git.clean();
            boolean isDryRun = (dryRun == null || dryRun);
            if (!Boolean.TRUE.equals(force) && !isDryRun) {
                return "错误：必须设置 force=true 或 dryRun=true（默认）来清理文件";
            }
            cmd.setForce(Boolean.TRUE.equals(force) && !isDryRun);
            if (isDryRun) cmd.setDryRun(true);
            if (Boolean.TRUE.equals(ignored)) cmd.setIgnore(false);
            Set<String> cleaned = cmd.call();
            if (cleaned.isEmpty()) return "无需清理的文件";
            StringBuilder sb = new StringBuilder(isDryRun ? "预览将被清理的文件（dry-run）：\n" : "已清理：\n");
            for (String f : cleaned) sb.append("  ").append(f).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "git clean 失败：" + e.getMessage();
        }
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
        try (Git git = openRepo(toolContext)) {
            if (Boolean.TRUE.equals(abort)) {
                git.reset().setMode(ResetCommand.ResetType.HARD).call();
                return "已中止 cherry-pick";
            }
            if (Boolean.TRUE.equals(continueOperation)) {
                try {
                    RevCommit commit = git.commit().setMessage("cherry-pick: resolved conflicts").call();
                    return "cherry-pick 继续完成：" + commit.abbreviate(7).name();
                } catch (Exception e) {
                    return "cherry-pick 继续失败：" + e.getMessage();
                }
            }
            if (commits == null || commits.isEmpty()) return "错误：需要提供 commit hashes";
            CherryPickCommand cmd = git.cherryPick();
            try (RevWalk rw = new RevWalk(git.getRepository())) {
                for (String hash : commits) {
                    ObjectId id = git.getRepository().resolve(hash);
                    if (id == null) return "错误：无法解析 commit - " + hash;
                    cmd.include(rw.parseCommit(id));
                }
            }
            if (Boolean.TRUE.equals(noCommit)) cmd.setNoCommit(true);
            CherryPickResult result = cmd.call();
            String statusName = result.getStatus().name();
            if (statusName.contains("CONFLICT")) {
                return "cherry-pick 暂停，存在冲突。解决后使用 git_cherry_pick continueOperation=true 继续，或 abort=true 中止";
            }
            return "cherry-pick 完成：" + result.getStatus();
        } catch (Exception e) {
            return "git cherry-pick 失败：" + e.getMessage();
        }
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
        try {
            Path repoDir = getWorkingDir(toolContext);
            if (!Files.exists(repoDir.resolve(".git"))) {
                return "错误：当前目录不是 Git 仓库 - " + repoDir;
            }
            String m = mode != null ? mode.toLowerCase() : "list";
            // Validate worktree paths
            Path validatedWorktreePath = null;
            if (worktreePath != null && !worktreePath.isBlank()) {
                validatedWorktreePath = validatePathInUserDir(toolContext, worktreePath, "worktreePath");
            }
            Path validatedNewPath = null;
            if (newPath != null && !newPath.isBlank()) {
                validatedNewPath = validatePathInUserDir(toolContext, newPath, "newPath");
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("worktree");
            switch (m) {
                case "list" -> cmd.add("list");
                case "add" -> {
                    cmd.add("add");
                    if (validatedWorktreePath == null) return "错误：add 模式需要 worktreePath 参数";
                    cmd.add(validatedWorktreePath.toString());
                    if (branch != null && !branch.isBlank()) { cmd.add("-b"); cmd.add(branch); }
                    else if (commitish != null && !commitish.isBlank()) cmd.add(commitish);
                    if (Boolean.TRUE.equals(detach)) cmd.add("--detach");
                }
                case "remove" -> {
                    cmd.add("remove");
                    if (validatedWorktreePath == null) return "错误：remove 模式需要 worktreePath 参数";
                    cmd.add(validatedWorktreePath.toString());
                    if (Boolean.TRUE.equals(force)) cmd.add("--force");
                }
                case "move" -> {
                    cmd.add("move");
                    if (validatedWorktreePath == null || validatedNewPath == null) return "错误：move 模式需要 worktreePath 和 newPath 参数";
                    cmd.add(validatedWorktreePath.toString());
                    cmd.add(validatedNewPath.toString());
                }
                case "prune" -> {
                    cmd.add("prune");
                    if (Boolean.TRUE.equals(dryRun)) cmd.add("--dry-run");
                    if (Boolean.TRUE.equals(verbose)) cmd.add("--verbose");
                }
                default -> {
                    return "未知操作：" + mode + "。支持 list/add/remove/move/prune";
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) return "git worktree 失败 (exit " + exitCode + ")：\n" + output;
            return output.isBlank() ? "git worktree " + m + " 完成" : output;
        } catch (SecurityException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "git worktree 失败：" + e.getMessage();
        }
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
                        Git.init().setDirectory(resolved.toFile()).setInitialBranch("main").call().close();
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
        toolContext.getContext().remove(WORKING_DIR_KEY);
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
        try (Git git = openRepo(toolContext)) {
            LogCommand log = git.log();
            int mc = (maxCommits != null && maxCommits > 0) ? maxCommits : 200;
            log.setMaxCount(mc);
            if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                ObjectId fromId = git.getRepository().resolve(from);
                ObjectId toId = git.getRepository().resolve(to);
                if (fromId == null) return "错误：无法解析 from ref - " + from;
                if (toId == null) return "错误：无法解析 to ref - " + to;
                log.addRange(fromId, toId);
            }
            if (branch != null && !branch.isBlank()) {
                ObjectId branchId = git.getRepository().resolve(branch);
                if (branchId != null) {
                    try (RevWalk rw = new RevWalk(git.getRepository())) {
                        log.add(rw.parseCommit(branchId));
                    }
                }
            }
            Iterable<RevCommit> commits = log.call();

            int mt = (maxTags != null && maxTags > 0) ? maxTags : 100;
            List<Ref> allTags = git.tagList().call();
            if (allTags.size() > mt) allTags = allTags.subList(0, mt);
            Map<String, String> tagMap = new LinkedHashMap<>();
            for (Ref tag : allTags) {
                String tagName = tag.getName().replace("refs/tags/", "");
                ObjectId peeled = git.getRepository().getRefDatabase().peel(tag).getPeeledObjectId();
                ObjectId target = peeled != null ? peeled : tag.getObjectId();
                tagMap.put(target.name(), tagName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Changelog Context ===\n\n");

            if (reviewTypes != null && !reviewTypes.isEmpty()) {
                sb.append("## Review Instructions\n");
                for (String type : reviewTypes) {
                    sb.append("- **").append(type).append("**: Review for ").append(type).append("\n");
                }
                sb.append("\n");
            }

            sb.append("## Commits\n");
            Map<String, List<String>> categorized = new LinkedHashMap<>();
            for (String key : List.of("features", "fixes", "breaking", "docs", "refactor", "other")) categorized.put(key, new ArrayList<>());
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
            if (reviewTypes == null || reviewTypes.isEmpty()) reviewTypes = List.of("features", "fixes", "breaking", "docs", "refactor", "other");
            for (String type : reviewTypes) {
                List<String> items = categorized.getOrDefault(type, List.of());
                if (!items.isEmpty()) {
                    sb.append("\n### ").append(type.toUpperCase()).append("\n");
                    for (String item : items) sb.append("- ").append(item).append("\n");
                }
            }
            sb.append("\n## Tags\n");
            if (tagMap.isEmpty()) sb.append("(none)\n");
            else for (var entry : tagMap.entrySet()) sb.append("- ").append(entry.getValue()).append(" (").append(entry.getKey().substring(0, 7)).append(")\n");
            return sb.toString();
        } catch (Exception e) {
            return "changelog analyze 失败：" + e.getMessage();
        }
    }

    @Tool(description = "Returns a Git wrap-up protocol: an acceptance-criteria checklist the agent must satisfy before the session is considered shipped.")
    @Override
    public String gitWrapupInstructions(
            @ToolParam(description = "Acknowledgement: Y, y, Yes, or yes", required = false) String acknowledgement,
            @ToolParam(description = "Include tag criterion in the protocol (default: true)", required = false) Boolean createTag,
            ToolContext toolContext) {
        try {
            Path repoDir = getWorkingDir(toolContext);
            StringBuilder sb = new StringBuilder();
            sb.append("=== Git Wrap-up Protocol ===\n\n");
            sb.append("## Repository Snapshot\n");
            sb.append(repoSnapshot(repoDir)).append("\n\n");
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
            if (createTag == null || createTag) sb.append("- [ ] Annotated tag created at project convention (e.g. v<version>)\n");
            sb.append("\n## Commit & Release Steps\n");
            sb.append("1. git status — verify working tree is clean\n");
            sb.append("2. git log — verify commit history is clean\n");
            sb.append("3. git push — upload to remote\n");
            if (createTag == null || createTag) {
                sb.append("4. Create tag: git tag v<version>\n");
                sb.append("5. git push --tags — upload tags\n");
            }
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

    /**
     * Resolve a relative path (repo name) against the user's file directory.
     * Only accepts relative paths — absolute paths are rejected to prevent sandbox escape.
     * After resolution, the result is validated with startsWith to prevent directory traversal.
     */
    private Path resolvePath(ToolContext toolContext, String path) {
        String username = username(toolContext);
        Path inputPath = Paths.get(path);
        if (inputPath.isAbsolute()) {
            throw new SecurityException("路径不能为绝对路径，必须相对于用户文件目录：" + getUserFileDir(username));
        }
        Path resolved = getUserFileDir(username).resolve(path).normalize();
        if (!resolved.startsWith(getUserFileDir(username))) {
            throw new SecurityException("路径不能超出用户文件目录：" + getUserFileDir(username));
        }
        return resolved;
    }

    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private Path getWorkingDir(ToolContext toolContext) throws IOException {
        String username = username(toolContext);
        String ctxWd = (String) toolContext.getContext().get(WORKING_DIR_KEY);
        if (ctxWd != null) {
            Path resolved = Paths.get(ctxWd).normalize();
            // Validate that stored working dir is within user's file directory
            if (!resolved.startsWith(getUserFileDir(username))) {
                throw new SecurityException("工作目录不在用户文件目录内：" + resolved);
            }
            return resolved;
        }
        return getGitBaseDir(username);
    }

    /**
     * Validate that a path is within the user's file directory.
     * Used for gitWorktree and other user-supplied paths.
     */
    private Path validatePathInUserDir(ToolContext toolContext, String path, String paramName) {
        if (path == null || path.isBlank()) return null;
        String username = username(toolContext);
        Path inputPath = Paths.get(path);
        if (inputPath.isAbsolute()) {
            Path resolved = inputPath.normalize();
            if (!resolved.startsWith(getUserFileDir(username))) {
                throw new SecurityException(paramName + " 不能超出用户文件目录：" + getUserFileDir(username));
            }
            return resolved;
        }
        // Relative path: resolve and validate
        Path resolved = getUserFileDir(username).resolve(path).normalize();
        if (!resolved.startsWith(getUserFileDir(username))) {
            throw new SecurityException(paramName + " 不能超出用户文件目录：" + getUserFileDir(username));
        }
        return resolved;
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
