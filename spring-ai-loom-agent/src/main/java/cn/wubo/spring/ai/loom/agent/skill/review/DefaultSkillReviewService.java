package cn.wubo.spring.ai.loom.agent.skill.review;

import cn.wubo.spring.ai.loom.agent.market.AbstractMarketReviewService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Skill 市场评价服务 — 面向 {@code market_skill_review} (PK: market_skill_id, username)。
 *
 * <p>继承 {@link AbstractMarketReviewService} 实现全部 IMarketContentReviewService 方法,
 * 通过 hook 提供表名 / 主键列 / 主键读取方式。无 KB 端"先访问过"门槛,可直接调用父类实现。
 *
 * <p>类型参数 pin 到 {@code Long} — {@code market_skill_review.market_skill_id}
 * 在 V1.0 中仍是 {@code BIGINT}(T1.1 schema 迁移只 ALTER 了三张 KB 侧表),
 * Long 绑定 BIGINT 类型对齐,行为与泛型化前完全一致。
 *
 * <p>行级权限模型:谁提交谁能改 (PK 第二段 = username);
 * {@code deleteAsAdmin} 由路由层在 {@code user.isAdmin(...)} 校验通过后再调用本方法,
 * 服务层不做重复校验。
 *
 * <p>Wiring: 在 {@code LoomAgentConfiguration.StorageConfiguration} 注册为 {@code @Bean},
 * 名字 {@code skillReviewService} (供路由器按名注入)。本类不带 {@code @Component},
 * 因为配置类已经显式按名注册;同时存在两个 Bean 会导致 Spring "no unique bean"。
 */
public class DefaultSkillReviewService extends AbstractMarketReviewService<Long> {

    public static final String TABLE = "market_skill_review";
    public static final String KEY_COL = "market_skill_id";

    public DefaultSkillReviewService(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    protected String tableName() {
        return TABLE;
    }

    @Override
    protected String keyCol() {
        return KEY_COL;
    }

    @Override
    protected Long readKey(ResultSet rs) throws SQLException {
        return rs.getLong(1);
    }
}
