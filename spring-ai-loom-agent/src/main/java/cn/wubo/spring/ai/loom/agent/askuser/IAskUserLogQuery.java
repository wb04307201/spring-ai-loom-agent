package cn.wubo.spring.ai.loom.agent.askuser;

import java.util.List;

/**
 * §2 提问卡片日志只读查询(spec 2026-09-08-askuser-followups-design.md)。
 *
 * <p>数据已由 {@code LoggingToolCallback} 全量写入 {@code loom_tool_call_log}
 * (tool_name='askUser'),本接口只做读取+解析,不新增表、不改写入路径(spec D2)。
 * 消费方是 admin 日志页路由 {@code GET /spring/ai/loom/admin/ask-logs}
 * (adminPathPatterns 门禁自动 admin-only)。
 */
public interface IAskUserLogQuery {

    /**
     * 最近的提问日志,按 created_at 倒序。
     *
     * @param limit           返回条数;实现必须钳制到 [1, 200](防滥用)
     * @param offset          跳过前 N 条;实现必须钳制到 [0, ∞);offset >= total → 返回空列表
     * @param usernameOrNull  可选用户名过滤;null/blank = 全部用户
     */
    List<AskUserLogRecord> recent(int limit, int offset, String usernameOrNull);
}
