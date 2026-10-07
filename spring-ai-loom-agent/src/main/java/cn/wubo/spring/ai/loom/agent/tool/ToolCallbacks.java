package cn.wubo.spring.ai.loom.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 薄封装 Spring AI 1.1.7 的 {@code org.springframework.ai.support.ToolCallbacks.from}，
 * 方便在业务代码中调用而不需要直接引用上游包（上游类可能被 spring-ai-commons
 * 多次搬家 —— 不同版本路径不同）。
 *
 * <p>功能：把 IEmbedTool 数组（{@code @Tool} 注解的方法）转为 {@code ToolCallback[]}
 * 数组，便于包 {@code LoggingToolCallback} 后用
 * {@code requestSpec.toolCallbacks(wrapped)} 统一注册 —— 替代
 * {@code requestSpec.tools(embedTools.toArray())}。
 *
 * <p>原因：{@code tools()} 注册的 MethodToolCallback 走 Spring AI 默认 ObservationHandler
 * 路径，Spring AI 1.1.7 DashScope 流式 chunk 让 onStart+onStop 各写一次 → 1 次实际工具
 * 调用产生 2 行 DB。统一用 {@code LoggingToolCallback} 后 DB 行数 = 真实调用次数。
 *
 * <p><b>零 {@code @Tool} 方法的对象会被静默跳过</b>（而不是抛异常）。上游
 * {@code MethodToolCallbackProvider} 对"扫描不到任何 @Tool 方法"的对象抛
 * {@code IllegalArgumentException}；本库存在<b>刻意零 {@code @Tool} 方法</b>的
 * {@code IEmbedTool}（{@code IHttpManageTool} —— 纯 RBAC 授权标记，让 admin 控制台能枚举到
 * {@code tool_http_manage} 这一组）。它会随 {@code List<IEmbedTool>} 一路流到这里，
 * 若直接透传，一旦该组被授权，用户<b>每一次对话</b>都会炸在这里。
 * 回归锁：{@code HttpManageToolInertnessTest}。
 */
public final class ToolCallbacks {

    private ToolCallbacks() {
    }

    /**
     * 把工具对象数组转为 {@link org.springframework.ai.tool.ToolCallback} 数组。
     *
     * <p>过滤掉<b>没有任何 {@code @Tool} 方法</b>的对象（纯授权标记 bean），其余原样透传上游。
     * 这样"能出现在工具列表里"与"能产生 LLM 可调用工具"两件事解耦 ——
     * 前者由 {@code @ToolGroup} 决定（RBAC 需要），后者由 {@code @Tool} 方法决定（LLM 需要）。
     */
    public static ToolCallback[] from(Object... toolObjects) {
        if (toolObjects == null || toolObjects.length == 0) {
            return new ToolCallback[0];
        }
        Object[] withTools = Arrays.stream(toolObjects)
                .filter(Objects::nonNull)
                .filter(ToolCallbacks::hasToolMethods)
                .toArray();
        if (withTools.length == 0) {
            return new ToolCallback[0];
        }
        return org.springframework.ai.support.ToolCallbacks.from(withTools);
    }

    /**
     * 对象上是否存在至少一个 {@code @Tool} 方法。
     *
     * <p><b>判据必须与上游同源</b>:上游 {@code MethodToolCallbackProvider} 判定
     * "有没有 {@code @Tool} 方法"用的是
     * {@code AnnotationUtils.findAnnotation(method, Tool.class)} —— 它会沿
     * <b>元注解</b>、父类、接口一起找。本方法同样用 {@code findAnnotation},
     * 绝不能用 {@code method.getAnnotation(Tool.class)}:后者只认<b>直接标注</b>,
     * 比上游<b>窄</b> ⇒ 一个用自定义元注解间接标注 {@code @Tool} 的方法,
     * 上游会正常注册,却被本过滤器静默吞掉(能力消失且无任何异常信号)。
     * 缺陷来源:deep audit {@code aud_6fZ4zETVSf}(primary f3 / google f2 / feat f1),
     * 回归锁 {@code HttpManageToolInertnessTest.metaAnnotatedToolIsNotSilentlyDropped}。
     *
     * <p><b>双重扫描</b>(bean 类自身的 public 方法 + 所有接口的 public 方法):
     * {@code getClass().getMethods()} 只返回被实现类覆写的方法,拿不到接口上仅声明的
     * {@code @Tool}(如 {@code IHttpTool.invokeEndpoint}),所以接口这一遍不能省
     * ({@code findAnnotation} 已能覆盖大部分,但保留它作为显式兜底,代价可忽略)。
     */
    private static boolean hasToolMethods(Object tool) {
        for (Method m : typeOf(tool).getMethods()) {
            if ((m.getModifiers() & Modifier.PUBLIC) != 0 && isTool(m)) {
                return true;
            }
        }
        for (Class<?> iface : collectInterfaces(typeOf(tool))) {
            for (Method m : iface.getMethods()) {
                if (isTool(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 与上游 {@code MethodToolCallbackProvider.isToolAnnotatedMethod} 同判据:认元注解。 */
    private static boolean isTool(Method m) {
        return AnnotationUtils.findAnnotation(m, Tool.class) != null;
    }

    /** 取运行时类，剥掉可能的 CGLIB 代理壳（容器 bean 通常无代理，但代价为零）。 */
    private static Class<?> typeOf(Object tool) {
        Class<?> type = AopUtils.getTargetClass(tool);
        return type != null ? type : tool.getClass();
    }

    /** 递归收集全部接口（含父接口）。 */
    private static List<Class<?>> collectInterfaces(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> cur = queue.poll();
            for (Class<?> i : cur.getInterfaces()) {
                if (!out.contains(i)) {
                    out.add(i);
                    queue.add(i);
                }
            }
            Class<?> sup = cur.getSuperclass();
            if (sup != null && !Object.class.equals(sup)) {
                queue.add(sup);
            }
        }
        return out;
    }
}
