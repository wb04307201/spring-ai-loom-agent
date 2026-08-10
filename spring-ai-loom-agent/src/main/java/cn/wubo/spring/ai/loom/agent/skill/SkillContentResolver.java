package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 解析 skill content 的 {@code classpath:...} 占位符。
 * V10 把 yml 内嵌 skill seed 进 market_skill 时，content 字段写的是
 * {@code classpath:skills/news-watch.st} 等路径，读取时再解析为真实文本。
 * 用户编辑保存后用纯文本（不带 classpath: 前缀），表示已经"脱钩"模板。
 */
public final class SkillContentResolver {

    private SkillContentResolver() {
    }

    public static String resolve(String raw, ResourceLoader resourceLoader) {
        if (raw == null) return "";
        if (raw.startsWith("classpath:")) {
            try {
                return resourceLoader.getResource(raw).getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new LoomAgentRuntimeException("Failed to load skill content from " + raw + ": " + e.getMessage());
            }
        }
        return raw;
    }
}
