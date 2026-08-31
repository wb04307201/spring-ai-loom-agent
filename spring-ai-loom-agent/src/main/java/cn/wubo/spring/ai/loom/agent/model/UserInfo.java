package cn.wubo.spring.ai.loom.agent.model;

import java.util.List;

public record UserInfo(
        String username,
        String nickname,
        String type,
        // 已分配的角色 code 列表 — admin 控制台展示用,普通用户视角保留空列表也行
        List<String> roles
) {
}
