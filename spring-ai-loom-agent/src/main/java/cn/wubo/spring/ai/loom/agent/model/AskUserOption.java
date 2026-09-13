package cn.wubo.spring.ai.loom.agent.model;

/**
 * askUser 问题卡片的一个选项。
 *
 * @param label       选项文本(必填非空)
 * @param description 可选补充说明(渲染在 label 下方,可为 null)
 */
public record AskUserOption(String label, String description) {
}
