package cn.wubo.loom.git.core;

import cn.wubo.loom.file.core.PathSecurityUtils;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Pure-Java Git operations based on JGit.
 * <p>
 * No Spring, ToolContext, or LoomAgentProperties dependencies.
 * All methods accept {@code Path workingDir} as the first parameter.
 */
public class GitOperations {

    private static final Logger log = LoggerFactory.getLogger(GitOperations.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String gitUsername;
    private final String gitToken;
    private final int remoteTimeoutSeconds;

    public GitOperations(String gitUsername, String gitToken, int remoteTimeoutSeconds) {
        this.gitUsername = gitUsername;
        this.gitToken = gitToken;
        this.remoteTimeoutSeconds = remoteTimeoutSeconds > 0 ? remoteTimeoutSeconds : 60;
    }

    // ==================== Repository lifecycle ====================

    public String gitInit(Path workingDir, String path, String initialBranch, Boolean bare) {
        try {
            Path repoDir = resolvePath(workingDir, path);
            Files.createDirectories(repoDir);
            InitCommand cmd = Git.init().setDirectory(repoDir.toFile());
            if (initialBranch != null && !initialBranch.isBlank()) cmd.setInitialBranch(initialBranch);
            if (Boolean.TRUE.equals(bare)) cmd.setBare(true);
            Git git = cmd.call();
            String branch = initialBranch != null ? initialBranch : (Boolean.TRUE.equals(bare) ? "HEAD" : safeGetBranch(git));
            git.close();
            String snapshot = "";
            try {
                snapshot = "\n" + repoSnapshot(repoDir);
            } catch (Exception ignored) {
            }
            return "已初始化 Git 仓库：" + repoDir + "\n初始分支：" + branch + (Boolean.TRUE.equals(bare) ? " (bare)" : "") + snapshot;
        } catch (Exception e) {
            return "git init 失败：" + e.getMessage();
        }
    }

    public String gitClone(Path workingDir, String url, String path, String branch, Integer depth, Boolean bare, Boolean mirror) {
        try {
            Path repoDir = resolvePath(workingDir, path);
            if (Files.exists(repoDir)) {
                return "错误：目录已存在 - " + repoDir;
            }
            Files.createDirectories(repoDir.getParent());

            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(repoDir.toFile())
                    .setTimeout(remoteTimeoutSeconds);
            if (branch != null && !branch.isBlank()) cmd.setBranch(branch);
            if (depth != null && depth > 0) cmd.setDepth(depth);
            if (Boolean.TRUE.equals(bare)) cmd.setBare(true);
            if (Boolean.TRUE.equals(mirror)) cmd.setBare(true).setMirror(true);
            CredentialsProvider cp = buildCredentialsProvider();
            if (cp != null) cmd.setCredentialsProvider(cp);
            Git git = cmd.call();
            String currentBranch = safeGetBranch(git);
            String head;
            try {
                head = git.getRepository().getFullBranch();
            } catch (Exception e) {
                head = "refs/heads/" + currentBranch;
            }
            git.close();
            String snapshot = "";
            try {
                snapshot = "\n" + repoSnapshot(repoDir);
            } catch (Exception ignored) {
            }
            return "已克隆仓库：" + repoDir + "\n远程：" + url + "\n分支：" + currentBranch + "\nHEAD：" + head + snapshot;
        } catch (Exception e) {
            return "git clone 失败：" + e.getMessage();
        }
    }

    // ==================== Basic operations ====================

    public String gitStatus(Path workingDir, Boolean includeUntracked) {
        try (Git git = openRepo(workingDir)) {
            boolean showUntracked = includeUntracked == null || includeUntracked;
            Status status;
            try {
                status = git.status().call();
            } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
                return "分支：(无 HEAD — 空仓库)\n\n工作区状态：有变更 (未创建任何提交)\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("分支：").append(safeGetBranch(git)).append("\n\n");
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

    public String gitAdd(Path workingDir, List<String> paths, Boolean update, Boolean all, Boolean force) {
        try (Git git = openRepo(workingDir)) {
            if (Boolean.TRUE.equals(update)) {
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

    public String gitCommit(Path workingDir, String message, String authorName, String authorEmail, Boolean amend, Boolean allowEmpty, Boolean noVerify, List<String> filesToStage) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitDiff(Path workingDir, String source, String target, List<String> paths, Boolean staged, Boolean nameOnly, Boolean stat, Integer contextLines, Boolean autoExclude) {
        try (Git git = openRepo(workingDir)) {
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
                    if (Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lock", "poetry.lock", "Pipfile.lock", "composer.lock", "Gemfile.lock", "go.sum", "Cargo.lock", "flake.lock", "pubspec.lock", "mix.lock", "Podfile.lock").contains(fileName)) {
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

    public String gitLog(Path workingDir, Integer maxCount, Integer skip, String since, String until, String author, String grep, String branch, String filePath, Boolean oneline, Boolean stat, Boolean patch, Boolean showSignature) {
        try (Git git = openRepo(workingDir)) {
            LogCommand logCmd = git.log();
            int count = (maxCount != null && maxCount > 0) ? maxCount : 20;
            logCmd.setMaxCount(count);
            if (skip != null && skip > 0) logCmd.setSkip(skip);
            if (filePath != null && !filePath.isBlank()) logCmd.addPath(filePath);
            if (branch != null && !branch.isBlank()) {
                ObjectId branchId = git.getRepository().resolve(branch);
                if (branchId != null) {
                    try (RevWalk rw = new RevWalk(git.getRepository())) {
                        logCmd.add(rw.parseCommit(branchId));
                    }
                }
            }
            Iterable<RevCommit> commits = logCmd.call();
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

    public String gitBranch(Path workingDir, String mode, String branchName, String newBranchName, String startPoint, Boolean force, Boolean all, Boolean remote, String merged, String noMerged, Integer limit) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitCheckout(Path workingDir, String target, Boolean createBranch, Boolean force, List<String> paths, Boolean track) {
        try (Git git = openRepo(workingDir)) {
            CheckoutCommand cmd = git.checkout().setName(target);
            if (Boolean.TRUE.equals(createBranch)) {
                cmd.setCreateBranch(true);
                if (Boolean.TRUE.equals(track)) cmd.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM);
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

    public String gitPull(Path workingDir, String remote, String branch, Boolean rebase, Boolean fastForwardOnly) {
        try (Git git = openRepo(workingDir)) {
            PullCommand cmd = git.pull().setTimeout(remoteTimeoutSeconds);
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (branch != null && !branch.isBlank()) cmd.setRemoteBranchName(branch);
            if (Boolean.TRUE.equals(rebase)) cmd.setRebase(true);
            if (Boolean.TRUE.equals(fastForwardOnly)) {
                cmd.setFastForward(MergeCommand.FastForwardMode.FF);
            }
            CredentialsProvider cp = buildCredentialsProvider();
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

    public String gitPush(Path workingDir, String remote, String branch, Boolean force, Boolean forceWithLease, Boolean setUpstream, Boolean tags, Boolean dryRun, Boolean delete, String remoteBranch) {
        try (Git git = openRepo(workingDir)) {
            PushCommand cmd = git.push().setTimeout(remoteTimeoutSeconds);
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (Boolean.TRUE.equals(delete)) {
                String targetBranch = branch != null ? branch : git.getRepository().getBranch();
                cmd.setRefSpecs(new RefSpec(":" + "refs/heads/" + targetBranch));
            } else if (branch != null && !branch.isBlank()) {
                String refSpec = branch + ":" + (remoteBranch != null ? remoteBranch : branch);
                cmd.setRefSpecs(new RefSpec(refSpec));
            }
            if (Boolean.TRUE.equals(force) || Boolean.TRUE.equals(forceWithLease)) {
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
            CredentialsProvider cp = buildCredentialsProvider();
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

    public String gitFetch(Path workingDir, String remote, Boolean prune, Boolean tags, Integer depth) {
        try (Git git = openRepo(workingDir)) {
            FetchCommand cmd = git.fetch().setTimeout(remoteTimeoutSeconds);
            if (remote != null && !remote.isBlank()) cmd.setRemote(remote);
            if (Boolean.TRUE.equals(prune)) cmd.setRemoveDeletedRefs(true);
            if (Boolean.TRUE.equals(tags)) cmd.setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*"));
            if (depth != null && depth > 0) cmd.setDepth(depth);
            CredentialsProvider cp = buildCredentialsProvider();
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

    public String gitMerge(Path workingDir, String branch, String strategy, Boolean noFastForward, Boolean squash, String message, Boolean abort) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitRebase(Path workingDir, String mode, String upstream, String branch, Boolean interactive, String onto, Boolean preserve) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitReset(Path workingDir, String mode, String target, List<String> paths) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitStash(Path workingDir, String mode, String message, String stashRef, Boolean includeUntracked, Boolean keepIndex, Integer limit) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitTag(Path workingDir, String mode, String tagName, String commit, String message, Boolean annotated, Boolean force, Integer limit) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitRemote(Path workingDir, String mode, String name, String url, String newName, Boolean push) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitBlame(Path workingDir, String filePath, Integer startLine, Integer endLine, Boolean ignoreWhitespace) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitShow(Path workingDir, String object, String format, Boolean stat, String filePath) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitReflog(Path workingDir, String ref, Integer maxCount) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitClean(Path workingDir, Boolean force, Boolean dryRun, Boolean directories, Boolean ignored) {
        try (Git git = openRepo(workingDir)) {
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

    public String gitWorktree(Path workingDir, String mode, String worktreePath, String branch, String commitish, Boolean force, String newPath, Boolean detach, Boolean verbose, Boolean dryRun) {
        try {
            if (!Files.exists(workingDir.resolve(".git"))) {
                return "错误：当前目录不是 Git 仓库 - " + workingDir;
            }
            String m = mode != null ? mode.toLowerCase() : "list";
            // Validate worktree paths against workingDir
            Path validatedWorktreePath = null;
            if (worktreePath != null && !worktreePath.isBlank()) {
                validatedWorktreePath = resolvePath(workingDir, worktreePath);
            }
            Path validatedNewPath = null;
            if (newPath != null && !newPath.isBlank()) {
                validatedNewPath = resolvePath(workingDir, newPath);
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
                    if (branch != null && !branch.isBlank()) {
                        cmd.add("-b");
                        cmd.add(branch);
                    } else if (commitish != null && !commitish.isBlank()) cmd.add(commitish);
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
                    if (validatedWorktreePath == null || validatedNewPath == null)
                        return "错误：move 模式需要 worktreePath 和 newPath 参数";
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
            pb.directory(workingDir.toFile());
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

    public String gitCherryPick(Path workingDir, List<String> commits, Boolean noCommit, Boolean continueOperation, Boolean abort, Integer mainline, String strategy, Boolean signoff) {
        try (Git git = openRepo(workingDir)) {
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

    // ==================== Analysis & Wrap-up ====================

    public String gitChangelogAnalyze(Path workingDir, String from, String to, List<String> reviewTypes, Integer maxCommits, Integer maxTags, String sinceTag, String branch) {
        try (Git git = openRepo(workingDir)) {
            LogCommand logCmd = git.log();
            int mc = (maxCommits != null && maxCommits > 0) ? maxCommits : 200;
            logCmd.setMaxCount(mc);
            if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                ObjectId fromId = git.getRepository().resolve(from);
                ObjectId toId = git.getRepository().resolve(to);
                if (fromId == null) return "错误：无法解析 from ref - " + from;
                if (toId == null) return "错误：无法解析 to ref - " + to;
                logCmd.addRange(fromId, toId);
            }
            if (branch != null && !branch.isBlank()) {
                ObjectId branchId = git.getRepository().resolve(branch);
                if (branchId != null) {
                    try (RevWalk rw = new RevWalk(git.getRepository())) {
                        logCmd.add(rw.parseCommit(branchId));
                    }
                }
            }
            Iterable<RevCommit> commits = logCmd.call();

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
            else for (var entry : tagMap.entrySet())
                sb.append("- ").append(entry.getValue()).append(" (").append(entry.getKey().substring(0, 7)).append(")\n");
            return sb.toString();
        } catch (Exception e) {
            return "changelog analyze 失败：" + e.getMessage();
        }
    }

    public String gitWrapupInstructions(Path workingDir, String acknowledgement, Boolean createTag) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Git Wrap-up Protocol ===\n\n");
            sb.append("## Repository Snapshot\n");
            sb.append(repoSnapshot(workingDir)).append("\n\n");
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

    /**
     * Resolve a relative path against basePath with security validation.
     */
    private Path resolvePath(Path basePath, String path) {
        Path inputPath = Paths.get(path == null ? "" : path);
        if (inputPath.isAbsolute()) {
            throw new SecurityException("路径不能为绝对路径，必须相对于基础目录：" + basePath);
        }
        Path baseNorm = basePath.toAbsolutePath().normalize();
        Path resolved = baseNorm.resolve(path == null ? "" : path).toAbsolutePath().normalize();
        if (!resolved.startsWith(baseNorm)) {
            throw new SecurityException("路径不能超出基础目录：" + basePath);
        }
        try {
            PathSecurityUtils.assertInsideBaseDir(resolved, baseNorm, true);
        } catch (IOException e) {
            throw new SecurityException("路径 symlink 校验失败：" + e.getMessage());
        }
        return resolved;
    }

    private Git openRepo(Path workingDir) throws IOException {
        if (!Files.exists(workingDir.resolve(".git"))) {
            throw new IOException("当前目录不是 Git 仓库：" + workingDir + "。请先初始化或克隆仓库。");
        }
        return Git.open(workingDir.toFile());
    }

    private CredentialsProvider buildCredentialsProvider() {
        if (gitUsername != null && !gitUsername.isBlank() && gitToken != null && !gitToken.isBlank()) {
            return new UsernamePasswordCredentialsProvider(gitUsername, gitToken);
        }
        return null;
    }

    private String repoSnapshot(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            StringBuilder sb = new StringBuilder();
            sb.append("当前分支：").append(git.getRepository().getBranch()).append("\n");

            Status status = git.status().call();
            int changes = status.getChanged().size() + status.getModified().size()
                    + status.getAdded().size() + status.getRemoved().size()
                    + status.getUntracked().size() + status.getMissing().size();
            sb.append("工作区状态：").append(changes == 0 ? "干净" : changes + " 个文件变更").append("\n");

            sb.append("\n最近提交：\n");
            int count = 0;
            try {
                for (RevCommit c : git.log().setMaxCount(5).call()) {
                    sb.append("  ").append(c.abbreviate(7).name())
                            .append(" ").append(c.getShortMessage()).append("\n");
                    count++;
                }
            } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
                // empty repo
            }
            if (count == 0) sb.append("  (无提交)\n");

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

    private String safeGetBranch(Git git) {
        try {
            return git.getRepository().getBranch();
        } catch (Exception e) {
            try {
                String initHead = git.getRepository().getConfig().getString("init", "default", "branch");
                return initHead != null ? initHead : "main";
            } catch (Exception e2) {
                return "main";
            }
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
