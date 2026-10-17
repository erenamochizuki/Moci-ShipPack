package data.scripts.util;

import java.awt.Color;

/**
 * 颜色数据常量类
 * 提供各种预定义的颜色，用于视觉效果
 */
public class Moci_ColorData {

	/** 透明色 */
	public static final Color NONE = new Color(0, 0, 0, 0);

	// ==================== 文本颜色 ====================
	/** 文本灰色 */
	public static final Color TEXT_GREY = new Color(150, 150, 150);
	/** 文本水蓝色 */
	public static final Color TEXT_WATER_BLUE = new Color(160, 213, 225);
	/** 文本浅蓝色 */
	public static final Color TEXT_LIGHT_BLUE = new Color(73, 214, 230);
	/** 文本红色 */
	public static final Color TEXT_RED = new Color(255, 55, 55);

	// ==================== 基础颜色 ====================
	/** 深灰色 */
	public static final Color DARK_GREY = new Color(100, 100, 100, 100);
	/** 浅灰色 */
	public static final Color LIGHT_GREY = new Color(200, 220, 230, 100);
	/** 浅灰色2 */
	public static final Color LIGHT_GREY2 = new Color(200, 220, 230);
	/** 浅蓝色 */
	public static final Color SHALLOW_BLUE = new Color(50, 145, 200);
	/** 标准蓝色 */
	public static final Color NORMAL_BLUE = new Color(0, 162, 255);
	/** 深蓝色 */
	public static final Color DEEP_BLUE = new Color(46, 72, 182);
	/** 浅黄色 */
	public static final Color LIGHT_YELLOW = new Color(255, 255, 190);
	/** 闪亮黄色 */
	public static final Color SHINY_YELLOW = new Color(255, 238, 80);
	/** 浅绿色 */
	public static final Color LIGHT_GREEN = new Color(225, 255, 230);
	/** 红色 */
	public static final Color RED = new Color(255, 0, 40);
	/** 浅红色 */
	public static final Color LIGHT_RED = new Color(255, 45, 75);

	// ==================== 相位颜色 ====================
	/** 相位主色 */
	public static final Color PHASE_MAIN = new Color(255, 175, 255, 255);
	/** 相位光晕 */
	public static final Color PHASE_GLOW = new Color(255, 0, 255, 150);

	// ==================== EMP电弧颜色 ====================
	/** 标准EMP电弧边缘色 */
	public static final Color NORMAL_EMP_ARC_FRINGE = new Color(25, 100, 155);
	/** 标准EMP电弧核心色 */
	public static final Color NORMAL_EMP_ARC_CORE = new Color(200, 255, 250);
	/** 明亮EMP电弧边缘色 */
	public static final Color BRIGHT_EMP_ARC_FRINGE = new Color(0, 210, 255);
	/** 明亮EMP电弧核心色 */
	public static final Color BRIGHT_EMP_ARC_CORE = new Color(255, 255, 255);
	/** 浅色EMP电弧边缘色 */
	public static final Color SHALLOW_EMP_ARC_FRINGE = new Color(70, 170, 255);
	/** 浅色EMP电弧核心色 */
	public static final Color SHALLOW_EMP_ARC_CORE = new Color(40, 180, 200);
	/** 暗淡EMP电弧边缘色 */
	public static final Color GRIM_EMP_ARC_FRINGE = new Color(50, 90, 95, 180);
	/** 暗淡EMP电弧核心色 */
	public static final Color GRIM_EMP_ARC_CORE = new Color(180, 220, 215);
	/** 鲜艳EMP电弧边缘色 */
	public static final Color VIVID_EMP_ARC_FRINGE = new Color(150, 230, 255);
	/** 鲜艳EMP电弧核心色 */
	public static final Color VIVID_EMP_ARC_CORE = new Color(208, 240, 255);

	// ==================== 引擎颜色 ====================
	/** 绿色引擎 */
	public static final Color GREEN_ENGINE = new Color(100, 255, 100, 200);
	/** 深蓝色引擎 */
	public static final Color DARK_BLUE_ENGINE = new Color(40, 240, 255, 55);

	// ==================== 抖动效果颜色 ====================
	/** 排气抖动 */
	public static final Color VENT_JITTER = new Color(40, 60, 80, 200);
	/** 深陶土色抖动 */
	public static final Color DARK_CLAY_JITTER = new Color(200, 220, 255, 25);
	/** 浅黄色抖动 */
	public static final Color LIGHT_YELLOW_JITTER = new Color(255, 255, 190, 25);
	/** 浅黄色底层抖动 */
	public static final Color LIGHT_YELLOW_JITTER_UNDER = new Color(255, 255, 190, 125);
	/** 深蓝色抖动 */
	public static final Color DARK_BLUE_JITTER = new Color(115, 255, 255, 60);
	/** 深蓝色底层抖动 */
	public static final Color DARK_BLUE_JITTER_UNDER = new Color(0, 60, 255, 155);
	/** 橙色抖动 */
	public static final Color ORANGE_JITTER = new Color(255, 152, 31, 60);
	/** 橙色底层抖动 */
	public static final Color ORANGE_JITTER_UNDER = new Color(255, 169, 138, 155);
	/** 蓝色底层抖动 */
	public static final Color BLUE_JITTER_UNDER = new Color(90, 165, 255, 125);
	/** 深蓝色底层抖动 */
	public static final Color DEEP_BLUE_JITTER_UNDER = new Color(105, 86, 200, 50);
	/** 紫色底层抖动 */
	public static final Color VIOLET_JITTER_UNDER = new Color(255, 0, 255, 120);
	/** 白色底层抖动 */
	public static final Color WHITE_JITTER_UNDER = new Color(255, 255, 255, 200);

	// ==================== 残影颜色 ====================
	/** 深蓝色残影 */
	public static final Color DARK_BLUE_AE = new Color(130, 200, 240, 80);
	/** 深绿色残影 */
	public static final Color DARK_GREEN_AE = new Color(130, 240, 200, 80);
	/** 深红色残影 */
	public static final Color DARK_RED_AE = new Color(240, 10, 10, 80);

	private Moci_ColorData() {}
}
