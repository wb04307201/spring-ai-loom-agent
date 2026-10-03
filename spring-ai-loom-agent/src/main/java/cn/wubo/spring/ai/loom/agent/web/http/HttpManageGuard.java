package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;

import java.util.Map;

/**
 * HTTP 写面授权守卫。
 *
 * <p><b>为什么需要独立守卫,而不是复用 {@code AuthenticationFilter}</b>:
 * filter 只保证"已登录",不保证"有权管理 HTTP 配置"。{@code role_tool} 体系原本
 * 只管 LLM 工具 callback,本类把已有授权结论用到一个新位置 —— 加工具组
 * {@link #GROUP} 由 admin 在控制台授权,不新增表、不新增 SQL。
 *
 * <p>与 {@code tool_http}(调用面)分开的原因:写面能写入<b>含 API 凭据的文件</b>,
 * 调用面只能发请求。风险等级不同,应能分别授予。
 */
public final class HttpManageGuard {

    /** RBAC 工具组名,与 admin 控制台中显示的一致。 */
    public static final String GROUP = "tool_http_manage";

    private final IRoleService roleService;

    public HttpManageGuard(IRoleService roleService) {
        this.roleService = roleService;
    }

    public boolean isAllowed(String username) {
        if (username == null || username.isBlank()) return false;
        return roleService.getVisibleToolsForUser(username).contains(GROUP);
    }

    /** 未授权时统一返回 403 文案,供 router 直接使用。 */
    public static Map<String, String> forbidden() {
        return Map.of("error", "未授权 HTTP 管理能力（需要 tool_http_manage）");
    }
}
