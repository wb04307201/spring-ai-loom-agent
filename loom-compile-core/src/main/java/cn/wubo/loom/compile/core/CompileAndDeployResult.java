package cn.wubo.loom.compile.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured result from compileAndDeploy pipeline.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompileAndDeployResult(
        boolean success,
        String workspacePath,
        String gitRepo,
        String branch,
        String imageName,
        String containerName,
        Integer port,
        String accessUrl,
        String healthPath,
        List<String> steps,
        String errorMessage
) {

    public static CompileAndDeployResult ok(String workspace, String repo, String branch,
                                            String image, String container, int port,
                                            String accessUrl, String healthPath,
                                            List<String> steps) {
        return new CompileAndDeployResult(true, workspace, repo, branch, image, container,
                port, accessUrl, healthPath, steps, null);
    }

    public static CompileAndDeployResult fail(String workspace, String repo, String image,
                                              String container, Integer port, String healthPath,
                                              List<String> steps, String errorMessage) {
        return new CompileAndDeployResult(false, workspace, repo, null, image, container,
                port, null, healthPath, steps, errorMessage);
    }
}
