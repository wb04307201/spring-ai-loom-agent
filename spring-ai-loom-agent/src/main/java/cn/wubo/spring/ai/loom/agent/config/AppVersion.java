package cn.wubo.spring.ai.loom.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * 读取本库的实际版本号（单一真源 = 构建产物）。
 *
 * <p><b>为什么不用硬编码</b>：版本号此前散落在前端 {@code ABOUT_INFO.version}、
 * {@code docs/API.md} 顶部标注、README 依赖示例三处，实测互相不一致
 * （{@code pom.xml} 是 {@code 1.0-SNAPSHOT}，文档写 {@code 1.2.0}）。
 * 从构建产物读取后，**改 {@code pom.xml} 即生效**，无需再改前端。
 *
 * <p><b>数据来源与降级链</b>：
 * <ol>
 *   <li>{@code META-INF/maven/{groupId}/{artifactId}/pom.properties} —— Maven
 *       **默认**在 jar 里生成，无需配置 {@code spring-boot-maven-plugin} 的
 *       {@code build-info} goal（后者要求打包阶段，{@code mvn spring-boot:run}
 *       下不一定存在）</li>
 *   <li>MANIFEST 的 {@code Implementation-Version} —— pom.properties 缺失时的兜底</li>
 *   <li>{@code "unknown"} —— 两者都没有（例如以 classes 目录而非 jar 运行）</li>
 * </ol>
 *
 * <p>结果缓存 —— 构建产物的版本在一次运行内不会变。
 */
public final class AppVersion {

    private static final Logger log = LoggerFactory.getLogger(AppVersion.class);

    private static final String GROUP_ID = "io.github.wb04307201";
    private static final String ARTIFACT_ID = "spring-ai-loom-agent";
    private static final String POM_PROPERTIES =
            "META-INF/maven/" + GROUP_ID + "/" + ARTIFACT_ID + "/pom.properties";

    /** 读不到时的占位值。前端据此显示「未知」而非空白。 */
    public static final String UNKNOWN = "unknown";

    private static volatile String cached;

    private AppVersion() {
    }

    /**
     * @return 版本号；读不到时返回 {@link #UNKNOWN}（不抛异常 —— 版本缺失不应让「关于」弹窗崩掉）
     */
    public static String get() {
        String v = cached;
        if (v != null) return v;
        synchronized (AppVersion.class) {
            if (cached != null) return cached;
            cached = resolve();
            return cached;
        }
    }

    private static String resolve() {
        String fromPom = fromPomProperties();
        if (fromPom != null && !fromPom.isBlank()) return fromPom.trim();

        String fromManifest = AppVersion.class.getPackage() == null
                ? null
                : AppVersion.class.getPackage().getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) return fromManifest.trim();

        log.debug("版本号读取失败（既无 {} 也无 MANIFEST Implementation-Version），返回 {}",
                POM_PROPERTIES, UNKNOWN);
        return UNKNOWN;
    }

    private static String fromPomProperties() {
        try (InputStream in = AppVersion.class.getClassLoader().getResourceAsStream(POM_PROPERTIES)) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (Exception e) {
            log.debug("读取 {} 失败: {}", POM_PROPERTIES, e.getMessage());
            return null;
        }
    }
}
