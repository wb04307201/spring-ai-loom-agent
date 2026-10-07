package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * HTTP 调用工具 —— <b>只读用面</b>。
 *
 * <p><b>刻意不暴露的能力</b>:profile / system / endpoint 的注册与修改。
 * 那部分走 REST 写面({@code HttpProfileRouter} / {@code HttpSystemRouter}),
 * 因为 profile 内含 API 凭据 —— 若让 LLM 通过工具写,凭据会进入对话历史、
 * 持久化到 {@code SPRING_AI_CHAT_MEMORY} 并渲染在 UI 上。
 *
 * <p><b>RBAC</b>:{@code defaultGranted = false} —— 能对外发请求是特权能力,
 * 须 admin 在控制台显式授权(group = {@code tool_http})。
 *
 * <p><b>存储隔离</b>:全部数据落在 {@code {usersBasePath}/{username}/http/},
 * 由实现经 {@code LoomPaths.userHttpDir} 派生,用户之间互不可见。
 */
@ToolGroup(value = "http", defaultGranted = false,
            description = "HTTP 调用能力:invokeEndpoint / httpBatch / listEndpoints / "
                        + "getEndpoint / getRequestHistory,共 5 个工具(需管理员授权)")
public interface IHttpTool extends IEmbedTool {

    String invokeEndpoint(String system, String method, String path,
                          Map<String, Object> params, Map<String, Object> body, String bodyRaw,
                          Map<String, String> headers, List<Map<String, Object>> assertions,
                          Map<String, String> extract, String responseMode, ToolContext toolContext);

    String httpBatch(List<Map<String, Object>> operations, Integer concurrency,
                     String failPolicy, String responseMode,
                     Integer firstN, ToolContext toolContext);

    String listEndpoints(String system, String tag, String source, ToolContext toolContext);

    String getEndpoint(String system, String method, String path, ToolContext toolContext);

    String getRequestHistory(String system, Integer limit, String statusFilter,
                             String since, ToolContext toolContext);
}