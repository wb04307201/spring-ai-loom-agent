package cn.wubo.spring.ai.loom.agent.capability;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.CapabilityInfo;
import cn.wubo.spring.ai.loom.agent.model.CapabilityInfo.ToolInfo;
import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.McpToolSystemView;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一 capability 服务：把"本地 9 个 I*Tool 组" + "MCP server"合并成同一形状
 * ({@link CapabilityInfo}),并按角色授权 + 服务状态计算 {@code effectiveEnabled}。
 *
 * <p><b>调用方:</b>
 * <ul>
 *   <li>前端 GET /api/capabilities —— 渲染"工具"面板</li>
 *   <li>DefaultChat.stream() —— 计算"这次会话能调哪些 capability",再分流到:
 *     本地 tool 通过 enableToolGroups + role 过滤;MCP 通过 {@link IMcp#getVisibleToolCallbackProvider} 现有过滤</li>
 * </ul>
 *
 * <p><b>命名空间:</b>
 * <ul>
 *   <li>本地 capability id: {@code "tool_" + @ToolGroup value} (e.g. {@code "tool_file"})</li>
 *   <li>MCP capability id: 直接用 {@link IMcp} 拉到的 live client name
 *     (e.g. {@code "spring-ai-mcp-client - bing-search"})。<b>不要 REPLACE 前缀</b>:
 *     DB role_mcp.mcp_name 必须跟 live client name 完全一致,否则 SyncMcp 过滤会失配。</li>
 * </ul>
 */
@Service
public class CapabilityService {

    private final List<IEmbedTool> embedTools;
    private final IMcp mcp;
    private final IRoleService roleService;

    public CapabilityService(List<IEmbedTool> embedTools, IMcp mcp, IRoleService roleService) {
        this.embedTools = embedTools;
        this.mcp = mcp;
        this.roleService = roleService;
    }

    /**
     * 列出当前用户可见的所有 capability,按 role/默认 + 服务端状态计算 effectiveEnabled。
     * <p>
     * 实现要点:roleService.getVisibleMcpsForUser 现在还会返回 admin 全集 (待 M3 去掉 bypass
     * 之后才会真走 RBAC),这里直接读它的结果做 MCP 可见集合;本地 tool 通过
     * roleService.getVisibleToolsForUser (M3 新增) 计算。
     */
    public List<CapabilityInfo> list(String username) {
        List<CapabilityInfo> all = new ArrayList<>();
        Set<String> visibleMcpNames = mcpNamesFor(username);
        Set<String> visibleToolGroups = toolGroupsFor(username);
        Set<String> universalGroups = universalToolGroups();

        // 本地 tool — M6 起,universal 工具不返回给聊天面板(Q4 "完全不显示" 决定)。
        // 它们仍然参与 tool callback filter(visibleToolGroupsFor 包含 universal),
        // LLM 可正常调用,只是 UI 上不展示 checkbox。
        for (IEmbedTool tool : embedTools) {
            CapabilityInfo ci = toLocalCapability(tool);
            if (universalGroups.contains(ci.id())) continue;
            // RBAC 工具 effectiveEnabled 跟随 role
            boolean enabled = visibleToolGroups.contains(ci.id());
            all.add(new CapabilityInfo(
                    ci.id(), ci.type(), ci.name(), ci.title(), ci.description(), ci.tools(),
                    enabled));
        }
        // MCP server
        List<McpRecord> mcpRecords = mcp.mcps();
        for (McpRecord rec : mcpRecords) {
            String id = rec.name(); // live client name (不要改!)
            all.add(new CapabilityInfo(
                    id,
                    CapabilityInfo.Type.MCP,
                    id,
                    rec.title(),
                    rec.description(),
                    rec.tools().stream()
                            .map(t -> new ToolInfo(t.name(), t.description()))
                            .toList(),
                    visibleMcpNames.contains(id)
            ));
        }
        // 稳定排序:本地在前 + MCP 在后,各自按 group_name / client_name 字典序
        all.sort(Comparator
                .comparing((CapabilityInfo c) -> c.type() == CapabilityInfo.Type.LOCAL ? 0 : 1)
                .thenComparing(CapabilityInfo::name));
        return all;
    }

    /**
     * 列出所有 capability(不管用户权限),admin 控制台用。
     * 跟 {@link #list(String)} 的差别:不去掉被 role 屏蔽的 capability,
     * 但仍把 {@code effectiveEnabled = role ∩ 服务状态} 标出来。
     * <p>
     * M6 起:universal 工具同样不返回(Q3 "完全隐藏" 决定) — 它们对所有用户默认可见,
     * 没有"是否授权"的语义,admin UI 没必要展示入口。
     */
    public List<CapabilityInfo> listAll() {
        List<CapabilityInfo> all = new ArrayList<>();
        Set<String> universalGroups = universalToolGroups();
        for (IEmbedTool tool : embedTools) {
            CapabilityInfo ci = toLocalCapability(tool);
            if (universalGroups.contains(ci.id())) continue;
            all.add(ci);
        }
        for (McpRecord rec : mcp.mcps()) {
            String id = rec.name();
            all.add(new CapabilityInfo(
                    id, CapabilityInfo.Type.MCP, id,
                    rec.title(), rec.description(),
                    rec.tools().stream().map(t -> new ToolInfo(t.name(), t.description())).toList(),
                    null // admin 控制台自己判断,不预先计算
            ));
        }
        all.sort(Comparator
                .comparing((CapabilityInfo c) -> c.type() == CapabilityInfo.Type.LOCAL ? 0 : 1)
                .thenComparing(CapabilityInfo::name));
        return all;
    }

    /**
     * 当前用户本次会话实际可调的 capability id 集合(供 DefaultChat 用)。
     * <p>
     * 计算口径:
     * <ul>
     *   <li>本地:role 授权集合 ∩ chatRequestRecord.enabledToolGroups[] (后者来自前端 state)</li>
     *   <li>MCP:role 授权集合 ∩ chatRequestRecord.mcps[] (现有 SyncMcp.getVisibleToolCallbackProvider 行为)</li>
     * </ul>
     * 如果用户没传 requestedPick,行为是"角色授权的全部"(默认勾选 default_enabled=true
     * 的会出现在 visible 里,但调用层仍需传 requestedPick 才能真正注入 tool callback)。
     * <p>
     * 注意:本方法不区分 local / mcp,统一返回 capability id 集合;
     * 调用方按 id 前缀("tool_") 路由到本地 / MCP 过滤。
     */
    public Set<String> allowedCapabilityIdsFor(String username, Set<String> requestedPick) {
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> visibleMcpNames = mcpNamesFor(username);
        Set<String> visibleToolGroups = toolGroupsFor(username);
        Set<String> universalGroups = universalToolGroups();

        if (requestedPick != null && !requestedPick.isEmpty()) {
            for (String id : requestedPick) {
                if (id.startsWith("tool_")) {
                    if (visibleToolGroups.contains(id)) allowed.add(id);
                } else {
                    // MCP: id 就是 live client name,直接 in 检查
                    if (visibleMcpNames.contains(id)) allowed.add(id);
                }
            }
        }
        // 强制包含 universal:Q5 决定 — universal 工具对所有用户可见,与 user_pick / role_tool 无关。
        // 注解层硬约束：admin UI 不展示入口,role_tool 历史行被 V2.4 迁移清理。
        allowed.addAll(universalGroups);
        return allowed;
    }

    // ==================== private helpers ====================

    private Set<String> mcpNamesFor(String username) {
        Set<String> s = new HashSet<>();
        for (McpSystemView v : roleService.getVisibleMcpsForUser(username)) {
            s.add(v.name());
        }
        return s;
    }

    /**
     * 收集类实现的所有接口（含父接口），用于扫接口方法上的 @Tool。
     */
    private static Set<Class<?>> collectInterfaces(Class<?> cls) {
        Set<Class<?>> all = new LinkedHashSet<>();
        for (Class<?> i : cls.getInterfaces()) {
            collectInterfaces0(i, all);
        }
        return all;
    }
    private static void collectInterfaces0(Class<?> iface, Set<Class<?>> out) {
        if (out.add(iface)) {
            for (Class<?> p : iface.getInterfaces()) {
                collectInterfaces0(p, out);
            }
        }
    }

    private Set<String> toolGroupsFor(String username) {
        // M3 已实现：合并用户所有 role 授权的 group_name（"tool_xxx" 形式）。
        // M6 扩展：再 union 上 universal 工具（@ToolGroup(defaultGranted=true)），
        // 任何登录用户都能调这些工具,与 role_tool 表无关。
        Set<String> s = new HashSet<>();
        for (String g : roleService.getVisibleToolsForUser(username)) {
            s.add(g);
        }
        s.addAll(universalToolGroups());
        return s;
    }

    /**
     * 公开版本：供 {@code DefaultChat} 在"用户没传 enabledToolGroups"路径下作为全集 fallback。
     * 等价于 {@link #toolGroupsFor}：返回该用户最终可见的工具 group 集合 = role 授权 ∪ universal。
     */
    public Set<String> visibleToolGroupsFor(String username) {
        return toolGroupsFor(username);
    }

    /**
     * 扫描所有 embedTools 的 @ToolGroup 注解,返回 {@code defaultGranted=true} 的 group_name 集合
     * （{@code "tool_xxx"} 形式）。在 Spring 容器初始化后第一次调用时构建缓存。
     * <p>
     * universal 工具是平台默认能力,对所有用户可见 — 与 role_tool RBAC 完全解耦。
     */
    public Set<String> universalToolGroups() {
        Set<String> u = new HashSet<>();
        for (IEmbedTool tool : embedTools) {
            Class<?> iface = findToolInterface(tool);
            if (iface == null) continue;
            ToolGroup ann = iface.getAnnotation(ToolGroup.class);
            if (ann != null && ann.defaultGranted()) {
                u.add("tool_" + ann.value());
            }
        }
        return u;
    }

    /**
     * 从 IEmbedTool bean 实例反射出 capability 信息:
     * <ul>
     *   <li>id = "tool_" + 找到带 @ToolGroup 注解的接口 value</li>
     *   <li>title / description:暂无(可在后续 @ToolGroup 加 title/desc 字段,或写专门的元数据表)</li>
     *   <li>tools = bean 类里所有带 @Tool 注解的方法(method + 注解 description)
     *       — 这里扫 <b>bean 类</b>(实现类),不是接口,因为 @Tool 通常写在实现类方法上,
     *       接口上 getDeclaredMethods() 看不到继承注解。</li>
     * </ul>
     */
    private CapabilityInfo toLocalCapability(IEmbedTool tool) {
        Class<?> ifaceClass = findToolInterface(tool);
        ToolGroup ann = ifaceClass.getAnnotation(ToolGroup.class);
        String rawName = ann != null ? ann.value() : ifaceClass.getSimpleName();
        String id = "tool_" + rawName;
        // 反射 @Tool 方法：
//   1) 扫 bean 类自身的 public 方法(覆盖写在实现类上的 @Tool,比如 DefaultFileTool)
//   2) 扫所有接口的 public 方法(继承的 @Tool,比如 ICompileAndDeployTool.compileAndDeploy)
//   两边结果去重(by 方法签名),保证 Spring AI 注册的 tool 列表跟我列的对得上
//   (Spring AI 的 ToolCallbacks.from() 也是同样的双重扫描策略)
        Set<String> seen = new HashSet<>();
        List<ToolInfo> toolInfos = new ArrayList<>();

        // bean 类自身的 public 方法
        for (Method m : tool.getClass().getMethods()) {
            if ((m.getModifiers() & Modifier.PUBLIC) == 0) continue;
            Tool t = m.getAnnotation(Tool.class);
            if (t == null) continue;
            String key = m.getName() + java.util.Arrays.asList(m.getParameterTypes());
            if (seen.add(key)) {
                toolInfos.add(new ToolInfo(m.getName(), t.description()));
            }
        }

        // 所有接口的 public 方法（递归走到所有父接口）
        for (Class<?> iface : collectInterfaces(tool.getClass())) {
            for (Method m : iface.getMethods()) {
                Tool t = m.getAnnotation(Tool.class);
                if (t == null) continue;
                String key = m.getName() + java.util.Arrays.asList(m.getParameterTypes());
                if (seen.add(key)) {
                    toolInfos.add(new ToolInfo(m.getName(), t.description()));
                }
            }
        }
        toolInfos.sort(Comparator.comparing(ToolInfo::name));
        // description 优先用 @ToolGroup.description,缺省用 "N 个工具" 占位
        String desc = (ann != null && ann.description() != null && !ann.description().isBlank())
                ? ann.description()
                : (toolInfos.size() + " 个本地工具");
        return new CapabilityInfo(
                id, CapabilityInfo.Type.LOCAL, id,
                rawName,
                desc,
                toolInfos,
                null
        );
    }

    /**
     * 在 bean 实例的接口里找带 @ToolGroup 注解的那个。
     * 因为 LoomAgentConfiguration.ToolConfiguration 用 @ConditionalOnMissingBean
     * 注入的是具体实现(类),{@code getClass().getInterfaces()} 能拿到 I*Tool 接口。
     * <p>
     * 返回 null(不抛异常)是为了老实现可平滑降级：DefaultChat 端会把它当"未授权"放行
     * (向后兼容)。
     */
    private Class<?> findToolInterface(Object bean) {
        for (Class<?> iface : bean.getClass().getInterfaces()) {
            if (iface.isAnnotationPresent(ToolGroup.class)) return iface;
        }
        return null;
    }
}