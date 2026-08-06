package cn.wubo.spring.ai.loom.agent.tool.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.skill.ISkillStorage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class DefaultSkillTool implements ISkillTool {

    private final ISkillStorage skillStorage;

    public DefaultSkillTool(ISkillStorage skillStorage) {
        this.skillStorage = skillStorage;
    }

    /** 默认最多返回 200 条，避免 LLM 单次工具调用塞爆上下文。 */
    private static final int DEFAULT_MAX_COUNT = 200;

    @Override
    @Tool(description = "列出当前用户可访问的技能（仅 name + 描述，不含完整内容；完整内容请用 @getSkill）。默认返回全部（上限 200）。可选按 keyword 模糊匹配 name/description，或按 source 过滤。")
    public String listSkills(
        @ToolParam(description = "模糊匹配关键词，匹配技能名或描述，可选", required = false) String keyword,
        @ToolParam(description = "按 source 过滤：USER_CREATED / MARKET_VIEW / ROLE_GRANTED / MARKET_PULLED，可选", required = false) String source,
        @ToolParam(description = "最多返回数量，默认 200，可选", required = false) Integer maxCount,
        ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        List<SkillRecord> allSkills = skillStorage.list(username).stream()
            .filter(SkillRecord::load)
            .toList();

        int total = allSkills.size();
        int cap = (maxCount == null || maxCount <= 0) ? DEFAULT_MAX_COUNT : Math.min(maxCount, total);

        // 过滤：keyword 模糊匹配（不区分大小写）+ source 精确匹配
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        String src = source == null ? null : source.trim();
        List<SkillRecord> filtered = allSkills.stream()
            .filter(s -> {
                if (kw != null && !kw.isEmpty()) {
                    boolean hit = s.name().toLowerCase().contains(kw)
                        || (s.description() != null && s.description().toLowerCase().contains(kw));
                    if (!hit) return false;
                }
                if (src != null && !src.isEmpty()) {
                    if (!src.equals(s.source())) return false;
                }
                return true;
            })
            .limit(cap)
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("技能目录（命中 %d / 共 %d 个，上限 %d）:%n%n", filtered.size(), total, cap));
        sb.append(String.format("%-20s %-12s %-50s%n", "技能名", "来源", "技能描述"));
        sb.append("-".repeat(86)).append("\n");

        for (SkillRecord skill : filtered) {
            sb.append(String.format("%-20s %-12s %-50s%n", skill.name(), skill.source(), skill.description()));
        }

        if (filtered.size() < total) {
            sb.append(String.format("%n提示：结果已截断（命中 %d / 共 %d）。", filtered.size(), total));
            if (kw == null && src == null) {
                sb.append(" 建议用 keyword 缩小范围（如 keyword=\"部署\"），或用 source 过滤（如 source=\"USER_CREATED\"）。");
            }
        }

        return sb.toString();
    }

    @Override
    @Tool(description = "根据技能名称获取详细的技能信息，包含技能名称、描述和完整内容。")
    public String getSkill(@ToolParam(description = "技能名") String name, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        SkillRecord skill = skillStorage.get(name, username);

        return String.format("技能名:%s%n", skill.name()) +
                String.format("技能描述:%s%n", skill.description()) +
                String.format("技能内容:%s%n", skill.content());
    }

    @Override
    @Tool(description = "创建或更新一个属于当前用户的技能。name 重复时覆盖现有内容（已审批的市场技能和已锁定的角色授权技能除外）。")
    public String createOrUpdateSkill(
            @ToolParam(description = "技能名（必填，字母/数字/中划线/下划线/中文，最长 128）") String name,
            @ToolParam(description = "技能描述（必填）") String description,
            @ToolParam(description = "技能 Prompt 内容（必填）") String content,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        if (name == null || name.isBlank()) {
            throw new LoomAgentRuntimeException(400, "name 不能为空");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() > 128) {
            throw new LoomAgentRuntimeException(400, "name 长度超过 128");
        }
        if (description == null) description = "";
        if (content == null || content.isBlank()) {
            throw new LoomAgentRuntimeException(400, "content 不能为空");
        }

        // 探测是否已存在（不存在则 status=created，已存在则 updated）
        boolean existed;
        try {
            skillStorage.get(trimmedName, username);
            existed = true;
        } catch (LoomAgentRuntimeException notFound) {
            existed = false;
        }

        // 锁检查交由 skillStorage.save 内部处理：ROLE_GRANTED / MARKET_PULLED 锁定会抛 403
        skillStorage.save(new SkillRecord(trimmedName, description, true, content, "USER_CREATED"), username);
        return String.format("已%s技能 %s，描述：%s", existed ? "更新" : "创建", trimmedName, description);
    }
}
