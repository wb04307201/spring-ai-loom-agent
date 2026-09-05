package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.market.RouterIdParser;
import org.springframework.stereotype.Component;

/**
 * Skill 市场 router id 解析器 (M3+ T1.4)。
 *
 * <p>{@code market_skill.id} 是 {@code BIGINT},path-variable 走标准
 * {@link Long#parseLong(String)}。非数字走 4xx 而不是 500 — 失败由 router 层
 * {@code try/catch NumberFormatException} 翻译,与既有 v1 router 行为一致。
 *
 * <p>T1.4 scope 范围内,KB router 全部走 {@link cn.wubo.spring.ai.loom.agent.knowledge.RouterIdParserKnowledge}
 * (passthrough,返回原 String);本 bean 主要为 T8.7 重构(共享 skill/KB admin router 样板)
 * 预留入口,T1.4 不替换既有 skill router 的 {@code Long.parseLong} 调用。
 *
 * @author LoomAgent M3+ T1.4
 */
@Component
public class RouterIdParserSkill implements RouterIdParser<Long> {

    @Override
    public Long parse(String rawId) {
        return Long.parseLong(rawId);
    }

    @Override
    public Class<Long> idType() {
        return Long.class;
    }
}
