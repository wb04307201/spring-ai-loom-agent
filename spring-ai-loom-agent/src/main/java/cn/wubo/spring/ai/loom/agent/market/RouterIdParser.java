package cn.wubo.spring.ai.loom.agent.market;

/**
 * 路由器 path-variable id 解析策略接口 (M3+ T1.4)。
 *
 * <p>market 内容有两种主键形态:
 * <ul>
 *   <li>{@link Long} — {@code market_skill.id} 是 {@code BIGINT}
 *       ({@code RouterIdParserSkill} 实现)</li>
 *   <li>{@link String} — {@code loom_market_knowledge.id} 是 {@code VARCHAR(36)} UUID
 *       ({@code RouterIdParserKnowledge} 实现,parse 直接返回原字符串,不解析)</li>
 * </ul>
 *
 * <p>Spring 路由函数 ({@code RouterFunctions.Builder}) 中,path-variable 总是
 * {@code String}。需要按目标 service 的主键类型,把原始字符串转成对应类型;
 * 解析失败时由实现类自行决定抛 {@link NumberFormatException} 还是接受原值。
 *
 * <p>本接口让 router 通过构造器注入 {@code RouterIdParser<X>} 而不是写死
 * {@code Long.parseLong(request.pathVariable(...))},把"id 类型"集中到一个 bean,
 * 便于 T8.7 重构时在 Skill/KB router 间共享通用样板代码。
 *
 * @param <K> 主键类型 — {@link Long} for skill, {@link String} for KB
 * @author LoomAgent M3+ T1.4
 */
public interface RouterIdParser<K> {

    /**
     * 把 router path-variable 原始字符串解析成目标主键类型。
     *
     * @param rawId router 提取出的 path-variable 字符串(必非 null,可空字符串)
     * @return 主键对象 — {@link Long} for skill, {@link String} for KB
     * @throws NumberFormatException 若 rawId 无法解析为目标类型(实现选择是否抛)
     */
    K parse(String rawId);

    /**
     * 返回本解析器支持的主键类型 — 反射 / 调试 / 测试用。
     *
     * @return 主键类型的 {@link Class} 对象
     */
    Class<K> idType();
}
