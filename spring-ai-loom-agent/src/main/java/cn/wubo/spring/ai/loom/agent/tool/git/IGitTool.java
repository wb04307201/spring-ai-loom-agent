package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

public interface IGitTool extends IEmbedTool {

    String gitInit(String repoName, ToolContext toolContext);

    String gitClone(String url, String repoName, Boolean shallow, String branch, ToolContext toolContext);

    String gitDeleteRepo(String repoName, ToolContext toolContext);

    String gitStatus(ToolContext toolContext);

    String gitAdd(List<String> filePatterns, ToolContext toolContext);

    String gitCommit(String message, ToolContext toolContext);

    String gitDiff(String cached, String base, ToolContext toolContext);

    String gitLog(Integer maxCount, String author, String path, ToolContext toolContext);

    String gitBranch(String action, String name, String newName, ToolContext toolContext);

    String gitCheckout(String target, Boolean createNew, List<String> paths, ToolContext toolContext);

    String gitPull(String remote, String branch, String rebase, ToolContext toolContext);

    String gitPush(String remote, String branch, Boolean force, ToolContext toolContext);

    String gitFetch(String remote, List<String> branches, ToolContext toolContext);

    String gitMerge(String branch, String strategy, String message, ToolContext toolContext);

    String gitRebase(String upstream, String onto, ToolContext toolContext);

    String gitReset(String mode, String target, ToolContext toolContext);

    String gitStash(String action, String message, String stashRef, ToolContext toolContext);

    String gitTag(String action, String name, String message, ToolContext toolContext);

    String gitRemote(String action, String name, String url, String newName, ToolContext toolContext);

    String gitBlame(String path, Integer startLine, Integer endLine, ToolContext toolContext);

    String gitShow(String object, ToolContext toolContext);

    String gitReflog(Integer maxCount, ToolContext toolContext);

    String gitClean(Boolean force, Boolean dryRun, ToolContext toolContext);

    String gitCherryPick(List<String> commits, ToolContext toolContext);

    String gitWorktree(String action, String path, String branch, ToolContext toolContext);

    String gitSetWorkingDir(String path, ToolContext toolContext);

    String gitChangelogAnalyze(String from, String to, List<String> reviewTypes, ToolContext toolContext);

    String gitWrapupInstructions(ToolContext toolContext);
}
