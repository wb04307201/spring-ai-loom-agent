package cn.wubo.spring.ai.loom.agent.capability;

import cn.wubo.spring.ai.loom.agent.model.CapabilityInfo;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import cn.wubo.spring.ai.loom.agent.tool.http.IHttpManageTool;
import cn.wubo.spring.ai.loom.agent.web.http.HttpManageGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tool_http_manage} 元数据回归锁。
 *
 * <p><b>锁的是什么</b>:写面组必须能被 admin 控制台授权。此前它只是
 * {@code HttpManageGuard} 的一个字符串常量,没有 {@code @ToolGroup} 声明 →
 * {@code /admin/capabilities} 列不出它 → 控制台无法授权(只能手写 SQL),
 * 且控制台每次保存工具都会因 {@code setRoleTools} 全量替换而静默撤销该授权。
 */
@SpringBootTest
@DisplayName("tool_http_manage 元数据:REST 写面必须可授权,且不进 universal / 不进聊天面板")
class HttpManageToolGroupMetadataTest {

    @Autowired private CapabilityService capabilityService;

    @Test
    @DisplayName("@ToolGroup 值为 http_manage 且 defaultGranted=false")
    void toolGroupAnnotation() {
        ToolGroup g = IHttpManageTool.class.getAnnotation(ToolGroup.class);
        assertThat(g).isNotNull();
        assertThat(g.value()).isEqualTo("http_manage");
        assertThat(g.defaultGranted()).isFalse();
    }

    @Test
    @DisplayName("group id 常量与 @ToolGroup 拼接一致 —— 防两处字面量漂移")
    void groupConstantMatchesAnnotation() {
        ToolGroup g = IHttpManageTool.class.getAnnotation(ToolGroup.class);
        assertThat(IHttpManageTool.GROUP).isEqualTo("tool_" + g.value());
        // HttpManageGuard.GROUP 直接引用 IHttpManageTool.GROUP,不是另抄一份
        assertThat(HttpManageGuard.GROUP).isEqualTo(IHttpManageTool.GROUP);
    }

    @Test
    @DisplayName("capability 列表里含 tool_http_manage —— 控制台据此渲染可勾选项")
    void appearsInAdminCapabilityList() {
        assertThat(capabilityService.listAll())
                .anyMatch(c -> IHttpManageTool.GROUP.equals(c.id())
                        && c.type() == CapabilityInfo.Type.LOCAL
                        && c.description() != null && !c.description().isBlank());
    }

    @Test
    @DisplayName("不在 universal 集合 —— 新装 admin 不得自动获得写凭据文件的能力")
    void notUniversal() {
        assertThat(capabilityService.universalToolGroups()).doesNotContain(IHttpManageTool.GROUP);
    }

    @Test
    @DisplayName("零 @Tool 方法 → 不出现在聊天面板列表(避免空条目)")
    void absentFromChatPanel() {
        assertThat(capabilityService.list("any-user"))
                .noneMatch(c -> IHttpManageTool.GROUP.equals(c.id()));
    }
}