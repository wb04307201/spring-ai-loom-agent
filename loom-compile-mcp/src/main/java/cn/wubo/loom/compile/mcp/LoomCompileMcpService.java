package cn.wubo.loom.compile.mcp;

import cn.wubo.loom.compile.core.CompileAndDeployOperations;
import cn.wubo.loom.compile.core.CompileAndDeployResult;
import cn.wubo.loom.compile.core.CompileConfig;
import cn.wubo.loom.compile.core.ImageTemplate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译部署 MCP 服务端。
 */
public class LoomCompileMcpService {

    private final CompileAndDeployOperations operations;
    private final Path basePath;

    public LoomCompileMcpService(LoomCompileMcpProperties props) {
        this.basePath = Paths.get(props.getBasePath()).toAbsolutePath().normalize();

        Map<String, ImageTemplate> templates = new HashMap<>();
        if (props.getImageTemplates() != null) {
            props.getImageTemplates().forEach((k, v) ->
                    templates.put(k, new ImageTemplate(v.getImage(), v.getCommand())));
        }

        CompileConfig config = new CompileConfig(
                props.getMavenTimeoutMs(),
                props.getDockerBuildTimeoutMs(),
                props.getDockerRunTimeoutMs(),
                props.getHealthCheckMaxWaitMs(),
                props.getHealthCheckIntervalMs(),
                props.isKeepWorkspace(),
                props.getExtraRunArgs(),
                templates
        );
        this.operations = new CompileAndDeployOperations(props.getMavenHome(), config);
    }

    @Tool(description = "端到端编译部署：git clone → 构建 → docker build → docker run → 健康检查。"
            + "一次调用完成整个部署流水线。")
    public String compileAndDeploy(
            @ToolParam(description = "参数 Map，包含：gitUrl（必填）、port（必填）、containerPort（必填）、"
                    + "branch、subDir、imageName、containerName、healthPath、buildTool（maven/npm/npm-frontend/pip）、"
                    + "baseImage、runCommand、gitUsername、gitPassword") Map<String, Object> params) {
        // Username doesn't exist in the MCP auth context here; fall back to
        // "anonymous" so the workspace dir name still has an ownership hint
        // (rather than empty / "mcp"). MCP callers can override via the
        // params map if they want a specific label.
        String user = params.get("__user") instanceof String s && !s.isBlank() ? s : "anonymous";
        CompileAndDeployResult result = operations.compileAndDeploy(basePath, user, params);
        return formatResult(result);
    }

    private String formatResult(CompileAndDeployResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.success() ? "✅ 部署成功\n" : "❌ 部署失败\n");
        if (r.gitRepo() != null) sb.append("仓库: ").append(r.gitRepo()).append("\n");
        if (r.branch() != null) sb.append("分支: ").append(r.branch()).append("\n");
        if (r.imageName() != null) sb.append("镜像: ").append(r.imageName()).append("\n");
        if (r.containerName() != null) sb.append("容器: ").append(r.containerName()).append("\n");
        if (r.port() != null) sb.append("端口: ").append(r.port()).append("\n");
        if (r.accessUrl() != null) sb.append("访问地址: ").append(r.accessUrl()).append("\n");
        if (r.steps() != null) {
            sb.append("步骤:\n");
            for (String step : r.steps()) {
                sb.append("  ").append(step).append("\n");
            }
        }
        if (r.errorMessage() != null) sb.append("错误: ").append(r.errorMessage()).append("\n");
        return sb.toString();
    }
}
