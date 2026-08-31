package cn.wubo.spring.ai.loom.agent.capability;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.CapabilityInfo;
import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.ToolRecord;
import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CapabilityService 核心过滤逻辑单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@link CapabilityService#visibleToolGroupsFor(String)} — 角色授权集合</li>
 *   <li>{@link CapabilityService#allowedCapabilityIdsFor(String, java.util.Set)} — role ∩ user_pick</li>
 *   <li>{@link CapabilityService#list(String)} — capability 列表 + effectiveEnabled</li>
 *   <li>{@link CapabilityService#toLocalCapability(IEmbedTool)} — 反射 @Tool 工具方法</li>
 * </ul>
 *
 * <p>直接 new CapabilityService(不走 Spring),用 Mockito 替 IMcp 和 IRoleService。
 * toLocalCapability 是 private,这里通过一个测试包内 helper 反射调用。
 */
class CapabilityServiceTest {

    // ===== 测试 fixture: IEmbedTool 接口 + 注解 =====
    @ToolGroup(value = "file", description = "file fixture")
    public interface ITestFileTool extends IEmbedTool {
        @Tool(description = "read text")
        String readText(@ToolParam(description = "path") String path);

        @Tool(description = "write text")
        String writeText(@ToolParam(description = "path") String path,
                        @ToolParam(description = "content") String content);
    }

    @ToolGroup("time")
    public interface ITestTimeTool extends IEmbedTool {
        @Tool(description = "current time")
        String getCurrentTime(@ToolParam(description = "tz") String timezone);
    }

    /**
     * M6 引入 {@code defaultGranted} 后,作为 fixture 的 universal 工具:
     * 不需要 role 授权即可被所有用户可见。
     */
    @ToolGroup(value = "skill", defaultGranted = true, description = "skill fixture (universal)")
    public interface ITestUniversalSkillTool extends IEmbedTool {
        @Tool(description = "get skill")
        String getSkill(@ToolParam(description = "name") String name);
    }

    // ===== mocks =====
    private IRoleService roleService;
    private IMcp mcp;

    @BeforeEach
    void setUp() {
        roleService = mock(IRoleService.class);
        mcp = mock(IMcp.class);
        when(mcp.mcps()).thenReturn(List.of());
    }

    private CapabilityService newService(IEmbedTool... tools) {
        return new CapabilityService(List.of(tools), mcp, roleService);
    }

    private CapabilityInfo toLocalCap(IEmbedTool tool) throws Exception {
        var m = CapabilityService.class.getDeclaredMethod("toLocalCapability", IEmbedTool.class);
        m.setAccessible(true);
        return (CapabilityInfo) m.invoke(newService(), tool);
    }

    // ============================================================
    //  visibleToolGroupsFor — 纯 RBAC 查询
    // ============================================================
    @Nested
    @DisplayName("visibleToolGroupsFor")
    class VisibleToolGroupsTests {
        @Test
        @DisplayName("空角色 → 只剩 universal(M6 引入 defaultGranted)")
        void empty() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(List.of());
            // 注册一个 universal fixture → 即便无 role,用户也能看到 tool_skill
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            Set<String> s = newService(skill).visibleToolGroupsFor("u");
            assertEquals(Set.of("tool_skill"), s);
        }

        @Test
        @DisplayName("单角色 3 个 tool group(无 universal 时)")
        void singleRole() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git", "tool_maven"));
            // 不注册 universal → 结果纯 RBAC 视角
            Set<String> s = newService().visibleToolGroupsFor("u");
            assertEquals(3, s.size());
            assertTrue(s.contains("tool_file"));
        }

        @Test
        @DisplayName("role ∪ universal — 新装用户也能调默认工具")
        void roleUnionUniversal() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git"));
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            Set<String> s = newService(skill).visibleToolGroupsFor("u");
            assertEquals(3, s.size());
            assertTrue(s.contains("tool_file"));
            assertTrue(s.contains("tool_git"));
            assertTrue(s.contains("tool_skill"));
        }
    }

    // ============================================================
    //  universalToolGroups — 直接反射 @ToolGroup(defaultGranted=true)
    // ============================================================
    @Nested
    @DisplayName("universalToolGroups")
    class UniversalToolGroups {
        @Test
        @DisplayName("无 defaultGranted → 空结果")
        void none() {
            assertTrue(newService().universalToolGroups().isEmpty());
        }

        @Test
        @DisplayName("一个 defaultGranted=true 工具 → 仅返回它")
        void oneDefault() {
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            Set<String> u = newService(skill).universalToolGroups();
            assertEquals(Set.of("tool_skill"), u);
        }

        @Test
        @DisplayName("混合 — RBAC 工具 + universal 工具 → 仅返回 universal")
        void mixedPickUniversal() {
            ITestFileTool file = mock(ITestFileTool.class);
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            Set<String> u = newService(file, skill).universalToolGroups();
            assertEquals(Set.of("tool_skill"), u);
        }
    }

    // ============================================================
    //  allowedCapabilityIdsFor — role ∩ user_pick + universal 强制
    // ============================================================
    @Nested
    @DisplayName("allowedCapabilityIdsFor")
    class AllowedCapabilityIds {
        @Test
        @DisplayName("空 pick → 只剩 universal(M6 起不再 fail-safe 为空)")
        void emptyPick() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git"));
            // 无 universal 时空 pick → 空结果(原行为保留)
            assertTrue(newService().allowedCapabilityIdsFor("u", new HashSet<>()).isEmpty());
            assertTrue(newService().allowedCapabilityIdsFor("u", null).isEmpty());
            // 注册 universal 后,空 pick 仍返回 universal(Q5 强制包含)
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            Set<String> r1 = newService(skill).allowedCapabilityIdsFor("u", new HashSet<>());
            Set<String> r2 = newService(skill).allowedCapabilityIdsFor("u", null);
            assertEquals(Set.of("tool_skill"), r1);
            assertEquals(Set.of("tool_skill"), r2);
        }

        @Test
        @DisplayName("pick ⊂ role → 交集(file)")
        void pickSubset() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git", "tool_maven"));
            // 授权 file/git/maven;pick file/compile → 交集 file
            Set<String> r = newService().allowedCapabilityIdsFor(
                    "u", Set.of("tool_file", "tool_compile"));
            assertEquals(Set.of("tool_file"), r);
        }

        @Test
        @DisplayName("pick ⊂ role 且有 universal → (role ∩ pick) ∪ universal")
        void pickSubsetWithUniversal() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git"));
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            // pick file + compile → 交集 file;再加 universal tool_skill
            Set<String> r = newService(skill).allowedCapabilityIdsFor(
                    "u", Set.of("tool_file", "tool_compile"));
            assertEquals(Set.of("tool_file", "tool_skill"), r);
        }

        @Test
        @DisplayName("pick 完全不在 role → 空结果 + universal")
        void pickNotInRole() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file"));
            ITestUniversalSkillTool skill = mock(ITestUniversalSkillTool.class);
            // pick 完全未授权 → 仅 universal 出现
            Set<String> r = newService(skill)
                    .allowedCapabilityIdsFor("u", Set.of("tool_xyz", "tool_abc"));
            assertEquals(Set.of("tool_skill"), r);
        }

        @Test
        @DisplayName("MCP pick 不在 role → 不出现(id 是 live client name,无前缀)")
        void mcpPickFiltered() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(List.of());
            when(roleService.getVisibleMcpsForUser("u")).thenReturn(List.of(
                    new McpSystemView("spring-ai-mcp-client - bing", null, null, false, true, List.of()),
                    new McpSystemView("spring-ai-mcp-client - memory", null, null, false, true, List.of())
            ));
            // pick 包含 1 个未授权的 mcp
            Set<String> r = newService().allowedCapabilityIdsFor(
                    "u",
                    Set.of("spring-ai-mcp-client - bing",
                           "spring-ai-mcp-client - memory",
                           "spring-ai-mcp-client - not-granted"));
            assertEquals(2, r.size());
            assertTrue(r.contains("spring-ai-mcp-client - bing"));
            assertFalse(r.contains("spring-ai-mcp-client - not-granted"));
        }
    }

    // ============================================================
    //  list — effectiveEnabled 反映 role
    // ============================================================
    @Nested
    @DisplayName("list")
    class ListCapabilities {
        @Test
        @DisplayName("9 LOCAL + N MCP,effectiveEnabled 只反映 role 授权")
        void effectiveEnabledReflectsRole() {
            ITestFileTool fileImpl = mock(ITestFileTool.class);
            ITestTimeTool timeImpl = mock(ITestTimeTool.class);
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_time"));
            when(roleService.getVisibleMcpsForUser("u")).thenReturn(List.of(
                    new McpSystemView("mcp-1", null, null, false, true, List.of()),
                    new McpSystemView("mcp-2", null, null, false, true, List.of())
            ));
            when(mcp.mcps()).thenReturn(List.of(
                    new McpRecord("mcp-1", "MCP 1", "1.0", "d1",
                            List.of(new ToolRecord("t1", "dt1"))),
                    new McpRecord("mcp-2", "MCP 2", "1.0", "d2",
                            List.of(new ToolRecord("t2", "dt2"))),
                    new McpRecord("mcp-3", "MCP 3", "1.0", "no perm",
                            List.of(new ToolRecord("t3", "dt3")))
            ));
            List<CapabilityInfo> caps = newService(fileImpl, timeImpl).list("u");
            // 2 LOCAL(file + time) + 3 MCP = 5(只 mock 了 2 个 embedTool,真 app 启动会有 9 LOCAL)
            assertEquals(5, caps.size());
            long enabled = caps.stream().filter(CapabilityInfo::effectiveEnabled).count();
            // tool_file + tool_time + mcp-1 + mcp-2 = 4
            assertEquals(4, enabled);
        }

        @Test
        @DisplayName("M6:universal 工具不出现在 list()(Q4 '完全不显示')")
        void listExcludesUniversal() {
            ITestFileTool fileImpl = mock(ITestFileTool.class);
            ITestTimeTool timeImpl = mock(ITestTimeTool.class);
            ITestUniversalSkillTool skillImpl = mock(ITestUniversalSkillTool.class);
            when(roleService.getVisibleToolsForUser("u")).thenReturn(List.of());  // 无 role
            when(roleService.getVisibleMcpsForUser("u")).thenReturn(List.of());

            List<CapabilityInfo> caps = newService(fileImpl, timeImpl, skillImpl).list("u");
            // 2 LOCAL(file + time, skill 被排除) + 0 MCP = 2
            assertEquals(2, caps.size());
            // universal 工具 tool_skill 不出现在列表里
            assertTrue(caps.stream().noneMatch(c -> "tool_skill".equals(c.id())),
                    () -> "universal 工具不应出现在聊天面板列表");
            // tool_file / tool_time 是 RBAC → 没 role = false
            caps.stream()
                    .filter(c -> "tool_file".equals(c.id()) || "tool_time".equals(c.id()))
                    .forEach(c -> assertFalse(c.effectiveEnabled(),
                            () -> c.id() + " 应为 false(无 role 授权)"));
        }

        @Test
        @DisplayName("M6:visibleToolGroupsFor 仍含 universal — LLM tool callback 仍可用")
        void visibleToolGroupsStillIncludesUniversal() {
            ITestFileTool fileImpl = mock(ITestFileTool.class);
            ITestTimeTool timeImpl = mock(ITestTimeTool.class);
            ITestUniversalSkillTool skillImpl = mock(ITestUniversalSkillTool.class);
            when(roleService.getVisibleToolsForUser("u")).thenReturn(List.of());

            CapabilityService svc = newService(fileImpl, timeImpl, skillImpl);
            // chat 面板 UI 看不到 skill,但 LLM 调 tool callback 时仍能拿到
            assertTrue(svc.list("u").stream().noneMatch(c -> "tool_skill".equals(c.id())));
            assertTrue(svc.visibleToolGroupsFor("u").contains("tool_skill"),
                    () -> "universal 必须仍在 visibleToolGroupsFor,否则 LLM 无法调用");
        }
    }

    // ============================================================
    //  toLocalCapability — 反射
    // ============================================================
    @Nested
    @DisplayName("toLocalCapability")
    class ToLocalCapabilityTests {
        @Test
        @DisplayName("@ToolGroup 在接口 → id='tool_'+value, description=注解值")
        void foundInterface() throws Exception {
            ITestFileTool impl = mock(ITestFileTool.class);
            CapabilityInfo ci = toLocalCap(impl);
            assertEquals("tool_file", ci.id());
            assertEquals("file", ci.title());
            assertEquals("file fixture", ci.description());
            // 2 个 @Tool 方法
            assertEquals(2, ci.tools().size());
        }

        @Test
        @DisplayName("@ToolGroup 在接口 + 缺 description → 走 'N 个工具' fallback")
        void descriptionFallback() throws Exception {
            ITestTimeTool impl = mock(ITestTimeTool.class);
            CapabilityInfo ci = toLocalCap(impl);
            assertEquals("tool_time", ci.id());
            // 注解里没 description → 走 N 个工具
            assertEquals("1 个本地工具", ci.description());
        }
    }
}
