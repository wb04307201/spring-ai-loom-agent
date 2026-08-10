package cn.wubo.loom.file.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 路径安全校验工具。统一处理三类越权：
 * <ol>
 * <li>{@code ..} 路径穿越（{@code Path.normalize} 即可防）</li>
 * <li>软链接越界（{@code Path.toRealPath} 跟链 → 检查父链是否仍在 baseDir 内）</li>
 * <li>大小写不敏感文件系统下的大小写绕过（Windows / macOS）</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * Path baseDir = getBaseDir();
 * Path resolved = baseDir.resolve(path).normalize();
 * PathSecurityUtils.assertInsideBaseDir(resolved, baseDir, true); // 读 / 删除
 * PathSecurityUtils.assertInsideBaseDir(resolved, baseDir, false); // 写 / 创建
 * }</pre>
 *
 * <h2>设计要点</h2>
 * <ul>
 * <li><b>mustExist=true</b>（读 / 删除 / 查询）：解析后的路径必须真实存在，
 * 用 {@code Path.toRealPath} 跟软链后判断是否仍在 baseDir 内。</li>
 * <li><b>mustExist=false</b>（写 / 创建）：路径可能还不存在。沿祖先链向上
 * 找到第一个真实存在的祖先，对祖先做 toRealPath 跟链检查，再确认
 * 不存在部分不含 {@code ..}。这样可以安全创建新文件，
 * 同时防御"软链在 baseDir 里指向外面"的越权。</li>
 * <li>所有失败路径都抛 {@link SecurityException}，调用方按业务需要 catch
 * 并转成工具结果字符串。</li>
 * </ul>
 */
public final class PathSecurityUtils {

    private PathSecurityUtils() {
        // utility
    }

    /**
     * 校验 {@code resolved} 在 {@code baseDir} 内。
     *
     * @param resolved  已经过 {@code normalize()} 的解析路径（相对 baseDir）
     * @param baseDir   基础目录根
     * @param mustExist true=路径必须存在（读 / 删 / 查）；false=路径可能还不存在（写 / 创建）
     * @throws SecurityException 路径越界（{@code ..} 越权 / 软链越界 / 大小写绕过）
     * @throws IOException       真实 I/O 错误
     */
    public static void assertInsideBaseDir(Path resolved, Path baseDir, boolean mustExist)
            throws IOException {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(baseDir, "baseDir");

        Path baseReal = toRealPathOrThrow(baseDir, "baseDir");
        Path baseNorm = baseDir.toAbsolutePath().normalize();

        if (mustExist) {
            // 1) 先做 .. 越权检查（normalize 后就足够判断 .. 越权）
            Path norm = resolved.toAbsolutePath().normalize();
            if (!norm.startsWith(baseNorm)) {
                throw new SecurityException(
                        "路径超出基础目录（.. 越权）：" + resolved + " → " + norm);
            }
            if (!Files.exists(resolved)) {
                // 不存在不算"越界"，让上层方法报"文件不存在"更友好
                return;
            }
            Path real = toRealPathOrThrow(resolved, "resolved");
            if (!real.startsWith(baseReal)) {
                throw new SecurityException(
                        "路径通过 symlink 越界：" + resolved + " (real=" + real + ", baseDir=" + baseReal + ")");
            }
            return;
        }

        // mustExist=false：路径可能还不存在
        // 1) 先做最便宜的 normalized 校验，防 .. 越权
        Path norm = resolved.toAbsolutePath().normalize();
        if (!norm.startsWith(baseNorm)) {
            throw new SecurityException(
                    "路径超出基础目录（.. 越权）：" + resolved + " → " + norm);
        }
        // 2) 沿祖先链向上找到第一个真实存在的祖先，对它做 toRealPath 跟链
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new SecurityException("无法解析路径祖先：" + resolved);
        }
        Path realExisting = toRealPathOrThrow(existing, "ancestor");
        if (!realExisting.startsWith(baseReal)) {
            throw new SecurityException(
                    "路径通过 symlink 越界：" + resolved + " (ancestor=" + realExisting + ")");
        }
        // 3) 不存在部分不能含 ..（防御性：normalize 后本来就没 ..，但再 check 一次）
        String suffix = existing.relativize(resolved).toString();
        if (suffix.contains("..")) {
            throw new SecurityException("路径包含 ..：" + resolved);
        }
    }

    /**
     * 把路径转为可比较的"物理"路径：
     * <ul>
     * <li>若存在 → {@code toRealPath}（跟软链）</li>
     * <li>若不存在（典型：baseDir 还没创建）→ 沿祖先链向上找第一个真实存在的祖先，
     * 对它做 {@code toRealPath}，再把不存在部分拼回去。
     * 这避免了"baseDir 不存在 → 不跟链 → real 与 baseReal 不匹配 → 误判越界"的问题。</li>
     * </ul>
     * 被校验路径（label=resolved）必须存在；不存在不算越界，让上层报"文件不存在"。
     */
    private static Path toRealPathOrThrow(Path p, String label) throws IOException {
        if (Files.exists(p)) {
            try {
                return p.toRealPath();
            } catch (NoSuchFileException e) {
                throw new IOException(label + " 路径不存在：" + p, e);
            }
        }
        // 沿祖先链向上找第一个真实存在的祖先
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            // 完全找不到任何祖先（几乎不可能 —— 根目录总是存在），
            // 退化为 toAbsolutePath + normalize
            return p.toAbsolutePath().normalize();
        }
        Path realExisting = existing.toRealPath();
        String suffix = existing.relativize(p).toString();
        if (suffix.isEmpty()) {
            return realExisting;
        }
        return realExisting.resolve(suffix);
    }
}
