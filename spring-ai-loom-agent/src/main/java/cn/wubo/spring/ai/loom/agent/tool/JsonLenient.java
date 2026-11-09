package cn.wubo.spring.ai.loom.agent.tool;

/**
 * 工具调用 JSON 容错:剥离字符串字面量之外的 JS 风格注释。
 *
 * <p><b>为什么存在</b>:qwen 系模型在 tool_use 的 arguments JSON 里经常带
 * {@code //} / <code>/* *&#47;</code> 注释。Spring AI 2.x 用 Jackson 3,其共享
 * {@code JsonMapper} <b>不可变</b>(旧版靠反射开 ALLOW_COMMENTS 的路子在 2.x 上
 * 从未生效:访问器已改名 + Jackson 3 无 in-place enable + 枚举改名),而
 * {@code MethodToolCallback} 解析 arguments 时注释直接抛 JsonParseException、
 * 整条工具链断。与其反射硬改上游私有状态,不如在<b>自有层</b>解析前把注释剥掉 ——
 * 零反射、不依赖任何 Spring AI/Jackson 内部结构。
 *
 * <p><b>调用点</b>:
 * <ul>
 *   <li>{@link LoggingToolCallback#call(String, org.springframework.ai.chat.model.ToolContext)}
 *       —— embed 工具与 MCP 工具的唯一包装点,delegate(→MethodToolCallback)之前剥净;</li>
 *   <li>{@code DefaultAskUserTool.parseOptions} —— optionsJson 解析前剥净。</li>
 * </ul>
 *
 * <p><b>契约</b>(JsonLenientTest 锁定):
 * <ul>
 *   <li>只剥字符串<b>外</b>的注释;字符串内的 {@code //}(URL)、转义引号 {@code \"}、
 *       块注释内的引号都不得误伤;</li>
 *   <li>无注释输入原样返回(no-op 不变性);</li>
 *   <li>null 透传、空串安全。</li>
 * </ul>
 *
 * <p>刻意用字符扫描状态机而非正则:正则在"字符串里的 //""块注释里的引号"等
 * 交错场景下必然误判;状态机一遍扫描 O(n),且语义可被单测穷举锁定。
 */
public final class JsonLenient {

    private JsonLenient() {
    }

    /**
     * 剥离字符串字面量之外的 {@code //} 行注释与 <code>/* *&#47;</code> 块注释。
     *
     * @param json 原始文本(可能为 null)
     * @return 无注释文本;null 输入返回 null
     */
    public static String sanitize(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        int n = json.length();
        StringBuilder sb = new StringBuilder(n);
        boolean inString = false;   // 是否在 "..." 字面量内
        boolean escaped = false;    // 前一个字符是否是未消费的 '\'(仅 inString 时有效)
        for (int i = 0; i < n; i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            // 字符串外
            if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (c == '/' && i + 1 < n && json.charAt(i + 1) == '/') {
                // 行注释:跳到行尾(\n 保留,维持行结构)
                while (i < n && json.charAt(i) != '\n') {
                    i++;
                }
                if (i < n) {
                    sb.append('\n');
                }
            } else if (c == '/' && i + 1 < n && json.charAt(i + 1) == '*') {
                // 块注释:跳到 */(未闭合则吞到结尾)
                int end = json.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 1; // for 的 i++ 会再 +1
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
