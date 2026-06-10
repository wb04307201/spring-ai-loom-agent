package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 一站式编译并部署工具。
 * <p>
 * 将传统 7 步流程（删除目录 → 克隆 → 写 Dockerfile → maven package → docker build
 * → docker run → 健康检查）封装为单次 {@code @Tool} 调用，规避 LLM 在
 * 多步编排中常见的参数拼写 / 端口 schema 误判，让上层 skill 模板只需关心
 * "收参数 + 调用 + 展示 URL" 三件事。
 * <p>
 * 行为契约：
 * <ul>
 *   <li>在用户文件目录下创建唯一工作区（{@code compile-deploy-<uuid>}），
 *       隔离多次调用互不污染；</li>
 *   <li>JGit 克隆采用 try-with-resources 立即关闭 pack 句柄，规避
 *       Windows 上 {@code .git/objects/pack} 文件锁；</li>
 *   <li>maven 与 docker 均通过 {@link ProcessBuilder} 启子进程、注册守护线程
 *       消费输出，超时后整棵树强杀（taskkill /F /T），避免孤儿进程；</li>
 *   <li>容器启动后做 HTTP 健康检查（{@code GET /}），未在
 *       {@code maxWaitMs} 内就绪则视为失败但容器保留供排障；</li>
 *   <li>返回结构化 {@link CompileAndDeployResult}，LLM 可直接渲染为 Markdown
 *       并提取 {@code accessUrl} 放入 {@code <a>} 标签。</li>
 * </ul>
 * <p>
 * <b>为什么参数是 {@code Map} 而不是多个 {@code @ToolParam}？</b>
 * <p>
 * Spring AI 内部用 Jackson 把 LLM 输出的 JSON 反序列化成方法形参对应的类型，
 * 整个链路对 JSON 容错很差 —— qwen 系列有时会输出含 {@code //}、{@code /*}{@code *}{@code /} 注释
 * 或未平衡括号的非标 JSON，触发 {@code JsonParseException} 后整条工具链直接断掉、
 * 整个聊天会话卡死。
 * <p>
 * 把所有入参收成单个 {@code Map<String,Object>} 后，本工具用自带的预清洗
 * （剥离未平衡注释、尾随逗号）再解析，完全绕开 Spring AI 的脆弱路径。
 */
public interface ICompileAndDeployTool extends IEmbedTool {

    /**
     * 克隆代码仓库、本地 Maven 打包、构建 Docker 镜像并启动容器，最后返回访问 URL。
     *
     * @param params       工具入参 Map，支持以下键（大小写不敏感）：
     *                     <ul>
     *                       <li>{@code gitUrl}        — Git 仓库 URL（必填）</li>
     *                       <li>{@code gitUsername}   — Git 用户名（公开仓库可省略）</li>
     *                       <li>{@code gitPassword}   — Git 密码或 token（公开仓库可省略）</li>
     *                       <li>{@code branch}        — 克隆分支（可选，默认为远程 HEAD）</li>
     *                       <li>{@code port}          — 服务端口（可选，默认 8080）</li>
     *                       <li>{@code imageName}     — Docker 镜像名（可选）</li>
     *                       <li>{@code containerName} — Docker 容器名（可选）</li>
     *                       <li>{@code healthPath}    — 健康检查路径（可选，默认 {@code /}）</li>
     *                       <li>{@code baseImage}     — 基础镜像（可选）；支持模板别名 {@code java17/java21/nginx/python3}，
     *                                                  或完整镜像名如 {@code openjdk:17-slim}。默认 java17</li>
     *                       <li>{@code runCommand}    — 容器启动命令（可选）；缺省按 baseImage 模板自动生成</li>
     *                     </ul>
     * @param toolContext  Spring AI 工具上下文（注入 username）
     * @return 编译部署结果
     */
    @Tool(description = "克隆 Git 仓库、运行 mvn 打包、构建 Docker 镜像并启动容器，返回访问 URL。"
            + "适用于 Spring Boot / 标准 Maven 项目的端到端编译部署。"
            + "入参是 Map：gitUrl 必填，其余按需提供（gitUsername、gitPassword、branch、port、"
            + "imageName、containerName、healthPath、baseImage、runCommand）。"
            + "baseImage 支持模板别名（java17 / java21 / nginx / python3，默认 java17）或完整镜像名（如 openjdk:17-slim）；"
            + "runCommand 极少用，缺省即可（会按模板自动生成 ENTRYPOINT）。"
            + "端口默认 8080，容器名与镜像名一致（同名容器已存在时自动删除）。"
            + "healthPath 既作探活路径也作访问 URL 路径（如 healthPath=sql-forge-demo 则访问 http://localhost:<port>/sql-forge-demo）。")
    CompileAndDeployResult compileAndDeploy(
            @ToolParam(description = "工具入参 Map，包含 gitUrl 等键。支持的键（大小写不敏感）："
                    + "gitUrl（必填）、gitUsername、gitPassword、branch、port、imageName、containerName、"
                    + "healthPath、baseImage（java17/java21/nginx/python3 或完整镜像名）、runCommand（字符串数组，覆盖模板 ENTRYPOINT）") Map<String, Object> params,
            ToolContext toolContext);
}
