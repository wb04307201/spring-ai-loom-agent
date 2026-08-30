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
        @DisplayName("空角色 → 空集合")
        void empty() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(List.of());
            assertTrue(newService().visibleToolGroupsFor("u").isEmpty());
        }

        @Test
        @DisplayName("单角色 3 个 tool group")
        void singleRole() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git", "tool_maven"));
            Set<String> s = newService().visibleToolGroupsFor("u");
            assertEquals(3, s.size());
            assertTrue(s.contains("tool_file"));
        }
    }

    // ============================================================
    //  allowedCapabilityIdsFor — role ∩ user_pick
    // ============================================================
    @Nested
    @DisplayName("allowedCapabilityIdsFor")
    class AllowedCapabilityIds {
        @Test
        @DisplayName("空 pick → 空结果(fail-safe)")
        void emptyPick() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file", "tool_git"));
            assertTrue(newService().allowedCapabilityIdsFor("u", new HashSet<>()).isEmpty());
            assertTrue(newService().allowedCapabilityIdsFor("u", null).isEmpty());
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
        @DisplayName("pick 完全不在 role → 空结果")
        void pickNotInRole() {
            when(roleService.getVisibleToolsForUser("u")).thenReturn(
                    List.of("tool_file"));
            assertTrue(newService()
                    .allowedCapabilityIdsFor("u", Set.of("tool_xyz", "tool_abc"))
                    .isEmpty());
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
