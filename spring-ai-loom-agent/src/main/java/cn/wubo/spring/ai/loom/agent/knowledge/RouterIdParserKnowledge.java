package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.market.RouterIdParser;
import org.springframework.stereotype.Component;

/**
 * KB 市场 router id 解析器 (M3+ T1.4)。
 *
 * <p>{@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID — KB router
 * 严禁 {@code Long.parseLong} 把 UUID 转 Long。本实现是 passthrough:
 * 直接返回原 path-variable 字符串,不做任何解析。这样:
 * <ul>
 *   <li>UUID 字符串原样到达 service 层(UUID 永远不会被 coerce 成 Long)</li>
 *   <li>numeric 测试 id 仍按 String 走(JdbcTemplate 按 VARCHAR(36) coerce 兼容)</li>
 *   <li>旧的"Long.parseLong(UUID) → 4xx"逻辑消失,真实 KB UUID 路径可达 service</li>
 * </ul>
 *
 * <p>注意:本 bean 解决的是 router 层的"解析器抽象",并不解决 service 层
 * {@code /reviews} / {@code /stats} 端点内部仍存在的 schema mismatch —
 * KB review / stats 表 PK 是 {@code BIGINT} 而 KB 主表是 UUID,真实 UUID
 * 在这些端点仍走 graceful-degradation (返回空 row / 404);T1.6 会单独修。
 *
 * @author LoomAgent M3+ T1.4
 */
@Component
public class RouterIdParserKnowledge implements RouterIdParser<String> {

    @Override
    public String parse(String rawId) {
        return rawId;
    }

    @Override
    public Class<String> idType() {
        return String.class;
    }
}
