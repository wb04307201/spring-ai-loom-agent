package cn.wubo.spring.ai.loom.agent.tool.compile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@link ICompileAndDeployTool#compileAndDeploy} 的结构化结果。
 * <p>
 * 设计为扁平 record 便于 Spring AI {@code ObjectMapper} 直接序列化：
 * <ul>
 * <li>{@code success}：整条流水线是否成功（克隆 + 编译 + 镜像 + 容器 + 健康检查）</li>
 * <li>{@code accessUrl}：成功后服务可访问的 URL（{@code http://localhost:&lt;port&gt;}），
 * LLM 可直接放入 {@code <a href="...">}</li>
 * <li>{@code steps}：每一步的执行摘要（"✅ 克隆：xxx"、"❌ 编译：xxx"），
 * 失败时便于 LLM 在聊天中向用户说明卡在哪一步</li>
 * <li>{@code errorMessage}：失败原因（仅失败时非空）</li>
 * </ul>
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

    /**
     * 成功结果的便捷构造。
     */
    public static CompileAndDeployResult ok(String workspace, String repo, String branch,
                                            String image, String container, int port,
                                            String accessUrl, String healthPath,
                                            List<String> steps) {
        return new CompileAndDeployResult(true, workspace, repo, branch, image, container,
                port, accessUrl, healthPath, steps, null);
    }

    /**
     * 失败结果的便捷构造。
     */
    public static CompileAndDeployResult fail(String workspace, String repo, String image,
                                              String container, Integer port, String healthPath,
                                              List<String> steps, String errorMessage) {
        return new CompileAndDeployResult(false, workspace, repo, null, image, container,
                port, null, healthPath, steps, errorMessage);
    }
}
