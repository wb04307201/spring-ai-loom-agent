package cn.wubo.spring.ai.loom.agent.capability;

import cn.wubo.spring.ai.loom.agent.model.CapabilityInfo;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import cn.wubo.spring.ai.loom.agent.tool.http.IHttpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("tool_http 元数据:RBAC 工具,不得进 universal")
class HttpToolGroupMetadataTest {

    @Autowired private IHttpTool httpTool;
    @Autowired private CapabilityService capabilityService;

    @Test
    @DisplayName("@ToolGroup 值为 http 且 defaultGranted=false")
    void toolGroupAnnotation() {
        ToolGroup g = IHttpTool.class.getAnnotation(ToolGroup.class);
        assertThat(g).isNotNull();
        assertThat(g.value()).isEqualTo("http");
        assertThat(g.defaultGranted()).isFalse();
    }

    @Test
    @DisplayName("tool_http 不在 universal 集合中 —— 新装 admin 不得自动获得对外请求权")
    void notUniversal() {
        assertThat(capabilityService.universalToolGroups()).doesNotContain("tool_http");
    }

    @Test
    @DisplayName("capability 列表里含 tool_http 本地工具组")
    void appearsInCapabilityList() {
        assertThat(capabilityService.listAll())
                .anyMatch(c -> "tool_http".equals(c.id())
                        && c.type() == CapabilityInfo.Type.LOCAL);
    }
}