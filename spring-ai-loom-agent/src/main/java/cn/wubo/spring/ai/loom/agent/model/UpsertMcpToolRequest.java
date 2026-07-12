package cn.wubo.spring.ai.loom.agent.model;

/** Upsert 工具描述请求。toolId=0 表示 DB 没记录（INSERT），否则 UPDATE。 */
public record UpsertMcpToolRequest(
        String mcpName,
        String name,
        String description
) {
}
