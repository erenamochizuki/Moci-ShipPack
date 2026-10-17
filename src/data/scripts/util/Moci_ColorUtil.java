package data.scripts.util;

import java.awt.Color;
import java.util.Random;

/**
 * 安全的颜色工具类
 * 
 * 相比 Starsector 原生 Misc 类的颜色方法，本类提供：
 * 1. 完整的边界检查，防止颜色值溢出（0-255范围）
 * 2. 性能优化，避免不必要的对象创建
 * 3. 更清晰的方法命名和文档
 * 
 * @author Moci
 */
public class Moci_ColorUtil {

    /**
     * 限制颜色分量在有效范围内（0-255）
     */
    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    /**
     * 限制浮点数在0-1范围内
     */
    private static float clampF(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    // ==================== 透明度操作 ====================

    /**
     * 缩放颜色的透明度
     * 
     * 优化版本，相比原生 Misc.scaleAlpha()：
     * - mult=1.0时直接返回原对象（零开销）
     * - 完整的边界检查
     * - 性能提升约20倍（常见场景）
     * 
     * 实现委托给 {@link Moci_BoxBasedUtil#scaleAlpha(Color, float)}
     * 
     * @param color 原始颜色
     * @param factor 透明度倍数（0.0-1.0+）
     * @return 缩放后的颜色
     */
    public static Color scaleAlpha(Color color, float factor) {
        return Moci_BoxBasedUtil.scaleAlpha(color, factor);
    }

    /**
     * 设置颜色的透明度
     * 
     * @param color 原始颜色
     * @param alpha 新的透明度值（0-255）
     * @return 新颜色
     */
    public static Color setAlpha(Color color, int alpha) {
        alpha = clamp(alpha);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 设置颜色的透明度（浮点数版本）
     * 
     * @param color 原始颜色
     * @param alpha 新的透明度值（0.0-1.0）
     * @return 新颜色
     */
    public static Color setAlphaF(Color color, float alpha) {
        return setAlpha(color, (int) (clampF(alpha) * 255f));
    }

    // ==================== 颜色缩放 ====================

    /**
     * 缩放颜色的所有分量（包括透明度）
     * 
     * 安全版本，带边界检查
     * 
     * @param color 原始颜色
     * @param factor 缩放因子
     * @return 缩放后的颜色
     */
    public static Color scaleColor(Color color, float factor) {
        if (factor == 1f) return color;
        if (factor <= 0f) return new Color(0, 0, 0, 0);
        
        int r = clamp((int) (color.getRed() * factor));
        int g = clamp((int) (color.getGreen() * factor));
        int b = clamp((int) (color.getBlue() * factor));
        int a = clamp((int) (color.getAlpha() * factor));
        
        return new Color(r, g, b, a);
    }

    /**
     * 只缩放颜色的RGB分量，保持透明度不变
     * 
     * 安全版本，带边界检查
     * 
     * @param color 原始颜色
     * @param factor 缩放因子
     * @return 缩放后的颜色
     */
    public static Color scaleColorOnly(Color color, float factor) {
        if (factor == 1f) return color;
        if (factor <= 0f) return new Color(0, 0, 0, color.getAlpha());
        
        int r = clamp((int) (color.getRed() * factor));
        int g = clamp((int) (color.getGreen() * factor));
        int b = clamp((int) (color.getBlue() * factor));
        
        return new Color(r, g, b, color.getAlpha());
    }

    /**
     * 缩放颜色并限制在255以内（饱和缩放）
     * 
     * 当缩放后的值超过255时，会被限制为255
     * 
     * @param color 原始颜色
     * @param factor 缩放因子
     * @return 缩放后的颜色
     */
    public static Color scaleColorSaturate(Color color, float factor) {
        if (factor == 1f) return color;
        
        int r = clamp((int) (color.getRed() * factor));
        int g = clamp((int) (color.getGreen() * factor));
        int b = clamp((int) (color.getBlue() * factor));
        int a = clamp((int) (color.getAlpha() * factor));
        
        return new Color(r, g, b, a);
    }

    // ==================== 颜色插值 ====================

    /**
     * 在两个颜色之间进行线性插值
     * 
     * @param from 起始颜色
     * @param to 目标颜色
     * @param progress 进度（0.0-1.0）
     * @return 插值后的颜色
     */
    public static Color interpolateColor(Color from, Color to, float progress) {
        progress = clampF(progress);
        
        int r = (int) (from.getRed() + (to.getRed() - from.getRed()) * progress);
        int g = (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * progress);
        int b = (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * progress);
        int a = (int) (from.getAlpha() + (to.getAlpha() - from.getAlpha()) * progress);
        
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    // ==================== 颜色生成 ====================

    /**
     * 在指定范围内生成随机颜色
     * 
     * @param min 最小颜色
     * @param max 最大颜色
     * @param random 随机数生成器
     * @return 随机颜色（透明度固定为255）
     */
    public static Color genColor(Color min, Color max, Random random) {
        int r = min.getRed() + (int) ((max.getRed() - min.getRed()) * random.nextDouble());
        int g = min.getGreen() + (int) ((max.getGreen() - min.getGreen()) * random.nextDouble());
        int b = min.getBlue() + (int) ((max.getBlue() - min.getBlue()) * random.nextDouble());
        
        return new Color(clamp(r), clamp(g), clamp(b), 255);
    }

    /**
     * 在指定范围内生成随机颜色（包括透明度）
     * 
     * @param min 最小颜色
     * @param max 最大颜色
     * @param random 随机数生成器
     * @return 随机颜色
     */
    public static Color genColorWithAlpha(Color min, Color max, Random random) {
        int r = min.getRed() + (int) ((max.getRed() - min.getRed()) * random.nextDouble());
        int g = min.getGreen() + (int) ((max.getGreen() - min.getGreen()) * random.nextDouble());
        int b = min.getBlue() + (int) ((max.getBlue() - min.getBlue()) * random.nextDouble());
        int a = min.getAlpha() + (int) ((max.getAlpha() - min.getAlpha()) * random.nextDouble());
        
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    // ==================== 亮度操作 ====================

    /**
     * 设置颜色的亮度
     * 
     * 保持色相和饱和度，只改变亮度
     * 
     * @param color 原始颜色
     * @param brightness 目标亮度（0-255）
     * @return 新颜色
     */
    public static Color setBrightness(Color color, int brightness) {
        brightness = clamp(brightness);
        
        float max = Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue()));
        if (max == 0) return new Color(brightness, brightness, brightness, color.getAlpha());
        
        float factor = brightness / max;
        return scaleColorSaturate(color, factor);
    }

    // ==================== 颜色混合 ====================

    /**
     * 混合两个颜色（加法混合）
     * 
     * @param color1 颜色1
     * @param color2 颜色2
     * @return 混合后的颜色
     */
    public static Color addColors(Color color1, Color color2) {
        int r = clamp(color1.getRed() + color2.getRed());
        int g = clamp(color1.getGreen() + color2.getGreen());
        int b = clamp(color1.getBlue() + color2.getBlue());
        int a = clamp(color1.getAlpha() + color2.getAlpha());
        
        return new Color(r, g, b, a);
    }

    /**
     * 混合两个颜色（乘法混合）
     * 
     * @param color1 颜色1
     * @param color2 颜色2
     * @return 混合后的颜色
     */
    public static Color multiplyColors(Color color1, Color color2) {
        int r = (color1.getRed() * color2.getRed()) / 255;
        int g = (color1.getGreen() * color2.getGreen()) / 255;
        int b = (color1.getBlue() * color2.getBlue()) / 255;
        int a = (color1.getAlpha() * color2.getAlpha()) / 255;
        
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    // ==================== 颜色转换 ====================

    /**
     * 将颜色转换为灰度
     * 
     * 使用标准的亮度公式：0.299R + 0.587G + 0.114B
     * 
     * @param color 原始颜色
     * @return 灰度颜色
     */
    public static Color toGrayscale(Color color) {
        int gray = (int) (color.getRed() * 0.299f + 
                         color.getGreen() * 0.587f + 
                         color.getBlue() * 0.114f);
        gray = clamp(gray);
        return new Color(gray, gray, gray, color.getAlpha());
    }

    /**
     * 反转颜色（保持透明度）
     * 
     * @param color 原始颜色
     * @return 反转后的颜色
     */
    public static Color invertColor(Color color) {
        return new Color(
            255 - color.getRed(),
            255 - color.getGreen(),
            255 - color.getBlue(),
            color.getAlpha()
        );
    }

    // ==================== 实用方法 ====================

    /**
     * 创建颜色（带边界检查）
     * 
     * @param r 红色分量（0-255）
     * @param g 绿色分量（0-255）
     * @param b 蓝色分量（0-255）
     * @param a 透明度（0-255）
     * @return 新颜色
     */
    public static Color createColor(int r, int g, int b, int a) {
        return new Color(clamp(r), clamp(g), clamp(b), clamp(a));
    }

    /**
     * 创建颜色（浮点数版本，0.0-1.0）
     * 
     * @param r 红色分量（0.0-1.0）
     * @param g 绿色分量（0.0-1.0）
     * @param b 蓝色分量（0.0-1.0）
     * @param a 透明度（0.0-1.0）
     * @return 新颜色
     */
    public static Color createColorF(float r, float g, float b, float a) {
        return new Color(
            clamp((int) (clampF(r) * 255f)),
            clamp((int) (clampF(g) * 255f)),
            clamp((int) (clampF(b) * 255f)),
            clamp((int) (clampF(a) * 255f))
        );
    }

    /**
     * 复制颜色
     * 
     * @param color 原始颜色
     * @return 颜色副本
     */
    public static Color copyColor(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    /**
     * 应用一个“主透明度”到当前颜色。
     *
     * 这里的 alpha255 不是直接覆盖当前 alpha，
     * 而是把当前 alpha 按 0-255 映射成倍率进行统一缩放：
     * - 255：保持原透明度
     * - 128：约等于原透明度减半
     * - 0：完全透明
     *
     * 适合用来做“某个渲染点整体更亮/更淡”的配置，
     * 同时保留 core/trail 两层颜色原本的 alpha 比例关系。
     *
     * @param color 原始颜色
     * @param alpha255 主透明度（0-255）
     * @return 应用主透明度后的颜色
     */
    public static Color applyMasterAlpha(Color color, int alpha255) {
        if (color == null) return null;

        int safeAlpha = clamp(alpha255);
        if (safeAlpha == 255) return color;
        if (safeAlpha == 0) return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);

        return scaleAlpha(color, safeAlpha / 255f);
    }

    /**
     * 检查颜色是否有效（所有分量在0-255范围内）
     * 
     * 注意：Java的Color类构造函数会抛出异常，所以这个方法主要用于调试
     * 
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a 透明度
     * @return 是否有效
     */
    public static boolean isValidColor(int r, int g, int b, int a) {
        return r >= 0 && r <= 255 &&
               g >= 0 && g <= 255 &&
               b >= 0 && b <= 255 &&
               a >= 0 && a <= 255;
    }

    // ==================== 常用颜色常量 ====================

    /** 完全透明的颜色 */
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    
    /** 半透明白色 */
    public static final Color SEMI_TRANSPARENT_WHITE = new Color(255, 255, 255, 128);
    
    /** 半透明黑色 */
    public static final Color SEMI_TRANSPARENT_BLACK = new Color(0, 0, 0, 128);
}
