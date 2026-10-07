package cn.wubo.spring.ai.loom.agent.util;

import org.apache.tika.Tika;

/**
 * 共享的 {@link Tika} 实例。
 * <p>
 * Tika 初始化需要加载全部 MIMETypes 与 parser，开销约 100-500ms；
 * 且 {@link Tika} facade 是 thread-safe（每次 parseToString 内部创建独立 ParseContext），
 * 因此可以在 JVM 范围内共享同一个实例，避免每个持有方各自承担一次冷启动成本。
 */
public final class TikaUtils {

    public static final Tika TIKA = new Tika();

    private TikaUtils() {
    }
}