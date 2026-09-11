package cn.wubo.spring.ai.loom.agent.browser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 纯 Java 逐像素 PNG diff(零新增依赖,只用 ImageIO):
 * <ul>
 *   <li>单像素 RGB 欧氏距离(归一化到 0-1)&gt; {@code perPixelThreshold} 记为差异像素;</li>
 *   <li>差异像素比例 &gt; {@code ratioThreshold} 判 FAIL;</li>
 *   <li>尺寸不一致直接判 FAIL(ratio=1.0);</li>
 *   <li>判 FAIL 或有非零差异时输出 diff 热力图(红=差异像素,暗色=变暗的实际图)。</li>
 * </ul>
 *
 * <p>仅供 {@code VisualBaselineBrowserIT} L2 截图基线回归使用(Task 13)。
 */
public final class PngDiff {

    private PngDiff() {
    }

    /** @param sizeMismatch 两图尺寸是否不一致(不一致时 diffRatio 恒为 1.0) */
    public record Result(boolean pass, double diffRatio, int width, int height,
                         boolean sizeMismatch) {
    }

    /**
     * 比较 expected(基线)与 actual(本次截图)。
     *
     * @param expected          基线 PNG
     * @param actual            本次截图 PNG
     * @param diffOut           diff 热力图输出路径(可为 null;仅在有差异像素时写出)
     * @param perPixelThreshold 单像素 RGB 归一化距离阈值(超过记差异,抗锯齿容忍)
     * @param ratioThreshold    差异像素比例阈值(超过判 FAIL)
     */
    public static Result compare(File expected, File actual, File diffOut,
                                 double perPixelThreshold, double ratioThreshold) throws IOException {
        BufferedImage e = ImageIO.read(expected);
        BufferedImage a = ImageIO.read(actual);
        if (e == null) {
            throw new IOException("无法读取基线 PNG: " + expected);
        }
        if (a == null) {
            throw new IOException("无法读取截图 PNG: " + actual);
        }
        int w = Math.min(e.getWidth(), a.getWidth());
        int h = Math.min(e.getHeight(), a.getHeight());
        boolean sizeMismatch = e.getWidth() != a.getWidth() || e.getHeight() != a.getHeight();
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ep = e.getRGB(x, y);
                int ap = a.getRGB(x, y);
                int dr = (ep >> 16 & 0xff) - (ap >> 16 & 0xff);
                int dg = (ep >> 8 & 0xff) - (ap >> 8 & 0xff);
                int db = (ep & 0xff) - (ap & 0xff);
                // sqrt(3*255^2) = 441.67,归一化到 0-1
                double dist = Math.sqrt(dr * dr + dg * dg + db * db) / 441.67;
                if (dist > perPixelThreshold) {
                    changed++;
                    diff.setRGB(x, y, 0xff0000);
                } else {
                    diff.setRGB(x, y, (ap & 0xfefefe) >> 1); // 变暗的实际图,差异区更醒目
                }
            }
        }
        double ratio = sizeMismatch ? 1.0 : (double) changed / ((long) w * h);
        if (changed > 0 && diffOut != null) {
            File parent = diffOut.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(diff, "png", diffOut);
        }
        return new Result(ratio <= ratioThreshold, ratio, w, h, sizeMismatch);
    }
}
