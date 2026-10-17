package data.shipsystems.scripts;

import java.awt.Color;

import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.dark.shaders.distortion.WaveDistortion;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_MathPersonal;

/**
 * 相位穿越舰船系统
 * 实现舰船向前方瞬移的功能，包含丰富的视觉效果
 * 支持PolariphaseDrive船插的特殊爆炸效果
 * 
 * 目标选择逻辑：
 * - 玩家手动控制：使用鼠标指向位置作为跳跃目标（最大2000单位）
 * - AI控制（包括玩家舰船的AI模式）：使用舰船当前朝向进行跳跃（固定1000单位）
 */
public class Moci_PhaseCrossing extends BaseShipSystemScript {
    // === 视觉效果常量 ===
    private WaveDistortion wave = null; // 波形扭曲效果实例

    // 光晕颜色配置
    public static final Color GLOW_1 = new Color(68, 82, 255, 100); // 光晕主色调
    public static final Color GLOW_2 = new Color(58, 150, 255, 150); // 光晕次色调
    public static final Color GLOW_CORE = new Color(161, 216, 255, 150); // 光晕内核颜色

    // 彩虹烟雾颜色
    public static final Color RAINBOW_COLOR_1 = new Color(255, 100, 100, 40); // 红色
    public static final Color RAINBOW_COLOR_2 = new Color(100, 255, 100, 30); // 绿色
    public static final Color RAINBOW_COLOR_3 = new Color(100, 100, 255, 35); // 蓝色

    private static final Color EXPLODE_COLOR = new Color(62, 123, 237, 75); // 爆炸效果颜色

    // === 跳跃参数 ===
    private static final float JUMP_DISTANCE = 2000; // 玩家跳跃距离（单位：游戏单位）
    private static final float AI_JUMP_DISTANCE = 1000; // AI跳跃距离（单位：游戏单位）

    // === AI安全跳跃参数 ===
    private static final float AI_DISTANCE_REDUCTION_STEP = 100f; // AI每次尝试减少的距离
    private static final float AI_MIN_JUMP_DISTANCE = 300f; // AI最小跳跃距离
    private static final float AI_SAFETY_MARGIN = 50f; // AI安全边距（避免与其他舰船过近）

    // === 最终爆发效果参数 ===
    private static final float FINAL_BURST_MULTIPLIER = 2.5f; // effectLevel 0.9-1.0时的尺寸倍率（可调整）

    // === 扭曲效果配置 ===
    private static final int DISTORTION_MODE = 1; // 扭曲效果模式：0=不渲染，1=新扭曲(0.85-1.0)，2=原扭曲

    // === 光晕效果配置 ===
    private static final float FLARE_START_TIME = 0f; // 光晕开始渲染的effectLevel时间点（0-1范围）
    private static final float FLARE_MIN_SIZE_MULTIPLIER = 0f; // 光晕最小尺寸倍率（可调整）
    private static final int FLARE_RENDERING_MODE = 3; // 光晕渲染模式：0=MagicLensFlare，1=简单光晕，2=fx_6贴图，3=混合模式(fx_6+MagicLensFlare)

    // === 各渲染模式的独立配置 ===
    // 简单光晕（模式1）配置
    private static final float SIMPLE_FLARE_SIZE_MULTIPLIER = 0.5f; // 简单光晕尺寸倍率（相对于MagicLensFlare）

    // fx_6贴图（模式2）配置
    private static final float FX6_SIZE_MULTIPLIER = 1.5f; // fx_6贴图尺寸倍率（相对于MagicLensFlare）
    private static final float FX6_BURST_MULTIPLIER = 4.5f; // fx_6贴图爆发倍率（独立于FINAL_BURST_MULTIPLIER）
    private static final Color FX6_SHIP_COLOR = new Color(100, 150, 255, 200); // fx_6舰船位置颜色
    private static final Color FX6_TARGET_COLOR = new Color(150, 100, 255, 180); // fx_6目标位置颜色

    // 混合模式（模式3）配置
    private static final float HYBRID_FLARE_START_TIME = 0.9f; // 混合模式中MagicLensFlare开始渲染的时间点
    private static final float HYBRID_FLARE_SIZE_MULTIPLIER = 0.8f; // 混合模式中MagicLensFlare的尺寸倍率
    private static final Color HYBRID_GLOW_1 = new Color(100, 149, 255, 120); // 混合模式光晕颜色1（橙红色）
    private static final Color HYBRID_GLOW_2 = new Color(150, 236, 255, 180); // 混合模式光晕颜色2（橙黄色）
    private static final Color HYBRID_GLOW_CORE = new Color(200, 253, 255, 200); // 混合模式光晕内核颜色（亮黄色）

    // === 视觉效果开关 ===
    private static final boolean ENABLE_SMOKE_EFFECTS = false; // 是否启用烟雾效果（true=复杂效果，false=简洁效果）

    private Vector2f recordedLocation = new Vector2f(); // 记录原始位置，用于PolariphaseDrive爆炸效果
    private Vector2f targetLocation = new Vector2f(); // 跳跃目标位置
    private Vector2f storedMousePos = null; // 存储系统启动时的鼠标位置
    private WaveDistortion targetWave = null; // 目标位置的扭曲效果

    // === 状态控制变量 ===
    private boolean hasJumped = false; // 防止在同一次激活中重复跳跃
    private boolean Once = false; // 控制PolariphaseDrive特殊效果只触发一次
    private boolean targetCalculated = false; // 标记是否已计算目标位置

    // === fx贴图特效状态变量 ===
    private float fx1Size = 0f; // Moci_Nadesico_fx_1 当前尺寸
    private float fx3Size = 0f; // Moci_Nadesico_fx_3 当前尺寸
    private float fx2Size = 1f; // Moci_Nadesico_fx_2 呼吸效果基础尺寸
    private float fx4Size = 1f; // Moci_Nadesico_fx_4 呼吸效果基础尺寸
    private float fx2Alpha = 0f; // Moci_Nadesico_fx_2 透明度
    private float fx4Alpha = 0f; // Moci_Nadesico_fx_4 透明度
    private float fx5Alpha = 0f; // Moci_Nadesico_fx_5 透明度（固定1f尺寸）
    private float breathingTimer = 0f; // 呼吸效果计时器
    private boolean isEffectLevel1Reached = false; // 标记是否达到effectLevel=1
    private float originalShipColorMult = 1f; // 记录舰船原始颜色倍数

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI the_ship = (ShipAPI) stats.getEntity();
        Vector2f ship_loc = the_ship.getLocation();
        CombatEngineAPI engine = Global.getCombatEngine();
        float amount = engine.getElapsedInLastFrame();

        if (engine != null) {
            // === 更新和渲染fx贴图特效 ===
            if (state == State.IN) {
                updateFxEffects(amount, effectLevel);
                renderFxEffects(engine, the_ship, effectLevel);
            }

            // === 创建跃迁光晕效果 ===
            if (state == State.IN && effectLevel >= FLARE_START_TIME) {
                // 计算光晕的实际进度（从FLARE_START_TIME开始计算）
                float flareProgress = (effectLevel - FLARE_START_TIME) / (1f - FLARE_START_TIME);

                // 计算光晕尺寸倍率（从FLARE_MIN_SIZE_MULTIPLIER开始增长到1f）
                float flareSizeMultiplier = FLARE_MIN_SIZE_MULTIPLIER
                        + (1f - FLARE_MIN_SIZE_MULTIPLIER) * flareProgress;

                // 计算最终爆发倍率（effectLevel 0.9-0.95时显著增大，0.95-1.0时快速缩小）
                float flareMultiplier = 1f;
                float flareAlphaMultiplier = 1f;
                if (effectLevel >= 0.9f && effectLevel < 0.95f) {
                    // 0.9-0.95阶段：爆发增大
                    float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
                    flareMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
                } else if (effectLevel >= 0.95f) {
                    // 0.95-1.0阶段：快速缩小到0
                    float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                    flareMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
                    flareAlphaMultiplier = 1f - shrinkProgress; // 透明度也同时减少
                }

                // 综合尺寸倍率
                float totalSizeMultiplier = flareSizeMultiplier * flareMultiplier;

                if (FLARE_RENDERING_MODE == 0) {
                    // 模式0：使用MagicLensFlare渲染
                    // 外圈光晕（较大，颜色较暗）
                    MagicLensFlare.createSharpFlare(
                            engine, the_ship, ship_loc,
                            9.0F, // 光晕尺寸基数
                            the_ship.getCollisionRadius() * 3.5F * totalSizeMultiplier, // 应用综合尺寸倍率
                            the_ship.getFacing() + 90.0F, // 光晕角度 (垂直于舰船朝向)
                            GLOW_1, GLOW_2 // 外圈渐变效果
                    );

                    // 内核光晕（较小，亮核心）
                    Color coreColor = new Color(
                            GLOW_CORE.getRed(),
                            GLOW_CORE.getGreen(),
                            GLOW_CORE.getBlue(),
                            (int) (GLOW_CORE.getAlpha() * flareProgress * flareAlphaMultiplier)); // 应用透明度倍率
                    MagicLensFlare.createSharpFlare(
                            engine, the_ship, ship_loc,
                            5.0F, // 核心尺寸基数（较小）
                            the_ship.getCollisionRadius() * 3.5F * 0.20F * totalSizeMultiplier, // 应用综合尺寸倍率
                            the_ship.getFacing() + 90.0F, // 光晕角度
                            coreColor, coreColor // 内核颜色
                    );
                } else if (FLARE_RENDERING_MODE == 1) {
                    // 模式1：使用简单光晕，应用独立尺寸倍率
                    Color flareColor = new Color(
                            GLOW_CORE.getRed(),
                            GLOW_CORE.getGreen(),
                            GLOW_CORE.getBlue(),
                            (int) (GLOW_CORE.getAlpha() * flareProgress * flareAlphaMultiplier));

                    // 计算简单光晕的独立尺寸
                    float simpleFlareTotalSize = totalSizeMultiplier * SIMPLE_FLARE_SIZE_MULTIPLIER;

                    // 使用addSmoothParticle创建简单光晕效果
                    engine.addSmoothParticle(
                            ship_loc,
                            the_ship.getVelocity(),
                            the_ship.getCollisionRadius() * 3.5F * simpleFlareTotalSize,
                            1.0f, // 亮度
                            0.1f, // 持续时间
                            flareColor);

                    // 添加内核光晕
                    engine.addSmoothParticle(
                            ship_loc,
                            the_ship.getVelocity(),
                            the_ship.getCollisionRadius() * 3.5F * 0.20F * simpleFlareTotalSize,
                            1.0f, // 亮度
                            0.1f, // 持续时间
                            new Color(255, 255, 255, (int) (200 * flareProgress * flareAlphaMultiplier)) // 白色内核
                    );
                } else if (FLARE_RENDERING_MODE == 2) {
                    // 模式2：使用fx_6贴图模式，应用独立配置
                    SpriteAPI fx6Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_6");
                    if (fx6Sprite != null) {
                        // 计算fx_6独立的爆发倍率（0.9-0.95爆发，0.95-1.0缩小）
                        float fx6BurstMultiplier = 1f;
                        float fx6AlphaMultiplier = 1f;
                        if (effectLevel >= 0.9f && effectLevel < 0.95f) {
                            // 0.9-0.95阶段：爆发增大
                            float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
                            fx6BurstMultiplier = 1f + (FX6_BURST_MULTIPLIER - 1f) * burstProgress;
                        } else if (effectLevel >= 0.95f) {
                            // 0.95-1.0阶段：快速缩小到0
                            float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                            fx6BurstMultiplier = FX6_BURST_MULTIPLIER * (1f - shrinkProgress);
                            fx6AlphaMultiplier = 1f - shrinkProgress; // 透明度也同时减少
                        }

                        // 计算fx_6的独立尺寸
                        float fx6TotalSize = flareSizeMultiplier * fx6BurstMultiplier * FX6_SIZE_MULTIPLIER;
                        float fx6Width = fx6Sprite.getWidth() * fx6TotalSize;
                        float fx6Height = fx6Sprite.getHeight() * fx6TotalSize;

                        // 使用独立的舰船位置颜色
                        Color fx6Color = new Color(
                                FX6_SHIP_COLOR.getRed(),
                                FX6_SHIP_COLOR.getGreen(),
                                FX6_SHIP_COLOR.getBlue(),
                                (int) (FX6_SHIP_COLOR.getAlpha() * flareProgress * fx6AlphaMultiplier));

                        org.magiclib.util.MagicRender.singleframe(
                                fx6Sprite,
                                ship_loc,
                                new Vector2f(fx6Width, fx6Height),
                                the_ship.getFacing() + 90.0F, // 跟随舰船朝向
                                fx6Color,
                                true // additive混合
                        );
                    }
                } else {
                    // 模式3：混合模式（fx_6贴图 + 0.9-1.0阶段的MagicLensFlare）
                    SpriteAPI fx6Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_6");
                    if (fx6Sprite != null) {
                        // === 第一部分：fx_6贴图渲染（与模式2相同） ===
                        float fx6BurstMultiplier = 1f;
                        float fx6AlphaMultiplier = 1f;
                        if (effectLevel >= 0.9f && effectLevel < 0.95f) {
                            // 0.9-0.95阶段：爆发增大
                            float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
                            fx6BurstMultiplier = 1f + (FX6_BURST_MULTIPLIER - 1f) * burstProgress;
                        } else if (effectLevel >= 0.95f) {
                            // 0.95-1.0阶段：快速缩小到0
                            float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                            fx6BurstMultiplier = FX6_BURST_MULTIPLIER * (1f - shrinkProgress);
                            fx6AlphaMultiplier = 1f - shrinkProgress; // 透明度也同时减少
                        }

                        // 计算fx_6的独立尺寸
                        float fx6TotalSize = flareSizeMultiplier * fx6BurstMultiplier * FX6_SIZE_MULTIPLIER;
                        float fx6Width = fx6Sprite.getWidth() * fx6TotalSize;
                        float fx6Height = fx6Sprite.getHeight() * fx6TotalSize;

                        // 使用独立的舰船位置颜色
                        Color fx6Color = new Color(
                                FX6_SHIP_COLOR.getRed(),
                                FX6_SHIP_COLOR.getGreen(),
                                FX6_SHIP_COLOR.getBlue(),
                                (int) (FX6_SHIP_COLOR.getAlpha() * flareProgress * fx6AlphaMultiplier));

                        org.magiclib.util.MagicRender.singleframe(
                                fx6Sprite,
                                ship_loc,
                                new Vector2f(fx6Width, fx6Height),
                                the_ship.getFacing() + 90.0F, // 跟随舰船朝向
                                fx6Color,
                                true // additive混合
                        );

                        // === 第二部分：0.9-1.0阶段的MagicLensFlare光晕 ===
                        if (effectLevel >= HYBRID_FLARE_START_TIME) {
                            // 计算混合光晕的进度（从0.9开始计算）
                            float hybridFlareProgress = (effectLevel - HYBRID_FLARE_START_TIME)
                                    / (1f - HYBRID_FLARE_START_TIME);

                            // 计算混合光晕的尺寸和透明度倍率
                            float hybridFlareMultiplier = 1f;
                            float hybridAlphaMultiplier = 1f;
                            if (effectLevel >= 0.9f && effectLevel < 0.95f) {
                                // 0.9-0.95阶段：快速增大
                                float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
                                hybridFlareMultiplier = burstProgress * FINAL_BURST_MULTIPLIER;
                            } else if (effectLevel >= 0.95f) {
                                // 0.95-1.0阶段：快速缩小到0
                                float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                                hybridFlareMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
                                hybridAlphaMultiplier = 1f - shrinkProgress;
                            }

                            // 综合尺寸倍率（应用混合模式的独立倍率）
                            float hybridTotalSizeMultiplier = hybridFlareMultiplier * HYBRID_FLARE_SIZE_MULTIPLIER;

                            // 外圈光晕（橙红色调）
                            Color hybridGlow1 = new Color(
                                    HYBRID_GLOW_1.getRed(),
                                    HYBRID_GLOW_1.getGreen(),
                                    HYBRID_GLOW_1.getBlue(),
                                    (int) (HYBRID_GLOW_1.getAlpha() * hybridFlareProgress * hybridAlphaMultiplier));
                            Color hybridGlow2 = new Color(
                                    HYBRID_GLOW_2.getRed(),
                                    HYBRID_GLOW_2.getGreen(),
                                    HYBRID_GLOW_2.getBlue(),
                                    (int) (HYBRID_GLOW_2.getAlpha() * hybridFlareProgress * hybridAlphaMultiplier));

                            MagicLensFlare.createSharpFlare(
                                    engine, the_ship, ship_loc,
                                    9.0F, // 光晕尺寸基数
                                    the_ship.getCollisionRadius() * 3.5F * hybridTotalSizeMultiplier,
                                    the_ship.getFacing() + 90.0F, // 光晕角度
                                    hybridGlow1, hybridGlow2 // 橙红渐变效果
                            );

                            // 内核光晕（亮黄色）
                            Color hybridCore = new Color(
                                    HYBRID_GLOW_CORE.getRed(),
                                    HYBRID_GLOW_CORE.getGreen(),
                                    HYBRID_GLOW_CORE.getBlue(),
                                    (int) (HYBRID_GLOW_CORE.getAlpha() * hybridFlareProgress * hybridAlphaMultiplier));
                            MagicLensFlare.createSharpFlare(
                                    engine, the_ship, ship_loc,
                                    5.0F, // 核心尺寸基数（较小）
                                    the_ship.getCollisionRadius() * 3.5F * 0.20F * hybridTotalSizeMultiplier,
                                    the_ship.getFacing() + 90.0F, // 光晕角度
                                    hybridCore, hybridCore // 亮黄色内核
                            );
                        }
                    }
                }

                // 彩虹烟雾环效果（可选）
                if (ENABLE_SMOKE_EFFECTS) {
                    createRainbowSmokeRing(engine, ship_loc, the_ship.getCollisionRadius() * 2.5F * effectLevel,
                            effectLevel);
                }

                // 目标位置视觉效果（如果已计算目标位置，effectLevel<1.0时显示）
                if (targetCalculated && effectLevel < 1.0f) {
                    createTargetLocationEffects(engine, the_ship, effectLevel);
                }
            }
            // OUT阶段不显示光晕

            // === 设置舰船透明度效果 ===
            the_ship.setExtraAlphaMult(1.0F - effectLevel);

            // === 处理激活阶段的扭曲效果 ===
            if (state == State.IN) {
                // 在系统开始时计算目标位置（只计算一次）
                if (!targetCalculated) {
                    // === 区分玩家手动控制和AI控制的目标选择逻辑 ===
                    boolean isPlayerControlled = (the_ship.getShipAI() == null);

                    if (isPlayerControlled) {
                        // === 玩家手动控制：使用鼠标目标位置 ===
                        this.storedMousePos = the_ship.getMouseTarget();

                        if (this.storedMousePos != null) {
                            // 计算到鼠标位置的距离和方向
                            Vector2f toMouse = Vector2f.sub(this.storedMousePos, ship_loc, null);
                            float distanceToMouse = toMouse.length();

                            // 限制跳跃距离最大2000单位
                            float jumpDistance = Moci_MathPersonal.clamp(0f, JUMP_DISTANCE, distanceToMouse);

                            // 计算实际跳跃目标位置
                            if (distanceToMouse > 0) {
                                toMouse.normalise();
                                toMouse.scale(jumpDistance);
                                this.targetLocation = Vector2f.add(ship_loc, toMouse, null);
                            } else {
                                // 如果鼠标在舰船位置，使用朝向方向
                                Vector2f direction = Misc.getUnitVectorAtDegreeAngle(the_ship.getFacing());
                                Vector2f jumpOffset = new Vector2f(direction.x * jumpDistance,
                                        direction.y * jumpDistance);
                                this.targetLocation = Vector2f.add(ship_loc, jumpOffset, null);
                            }
                        } else {
                            // 如果没有鼠标位置，使用朝向方向
                            Vector2f direction = Misc.getUnitVectorAtDegreeAngle(the_ship.getFacing());
                            Vector2f jumpOffset = new Vector2f(direction.x * JUMP_DISTANCE,
                                    direction.y * JUMP_DISTANCE);
                            this.targetLocation = Vector2f.add(ship_loc, jumpOffset, null);
                        }
                    } else {
                        // === AI控制：不计算跳跃目标（与PhaseCrossing_new版本一致） ===
                        // AI模式下不执行跳跃，只保留视觉效果
                        // 注释掉原有的AI跳跃计算逻辑
                        // Vector2f direction = Misc.getUnitVectorAtDegreeAngle(the_ship.getFacing());
                        // Vector2f jumpOffset = new Vector2f(direction.x * AI_JUMP_DISTANCE,
                        // direction.y * AI_JUMP_DISTANCE);
                        // this.targetLocation = Vector2f.add(ship_loc, jumpOffset, null);

                        // 设置一个虚拟目标位置用于视觉效果（不会实际使用）
                        this.targetLocation = new Vector2f(ship_loc);
                    }

                    // === AI安全位置搜索（已注释） ===
                    // AI模式下不需要安全位置搜索
                    // if (the_ship.getShipAI() != null) {
                    // this.targetLocation = findSafeJumpLocation(engine, the_ship, ship_loc,
                    // this.targetLocation);
                    // }

                    targetCalculated = true; // 标记已计算目标位置
                }

                // 根据扭曲模式处理起始位置的扭曲效果
                if (DISTORTION_MODE != 0) { // 0=不渲染扭曲
                    if (DISTORTION_MODE == 1) {
                        // 新扭曲模式：0.85-1.0时快速扭曲
                        if (effectLevel >= 0.85f) {
                            float newDistortionProgress = (effectLevel - 0.85f) / 0.15f; // 0-1范围

                            if (this.wave == null) {
                                this.wave = new WaveDistortion(ship_loc, new Vector2f(0, 0));
                                this.wave.setSize(the_ship.getCollisionRadius() * 2.0F);
                                this.wave.setIntensity(15.0F);
                                this.wave.setArc(0.0F, 360.0F);
                                this.wave.flip(true);
                                DistortionShader.addDistortion(this.wave);
                            } else {
                                // 快速达到与原扭曲相同的强度
                                float intensity = newDistortionProgress * 75.0F; // 快速增长到75强度
                                this.wave.setLocation(ship_loc);
                                this.wave.setSize(the_ship.getCollisionRadius() - newDistortionProgress * 50.0F);
                                this.wave.setIntensity(intensity + 15.0F);
                            }
                        }
                    } else {
                        // 原扭曲模式（DISTORTION_MODE == 2或其他值）
                        if (this.wave == null) {
                            this.wave = new WaveDistortion(ship_loc, new Vector2f(0, 0));
                            this.wave.setSize(the_ship.getCollisionRadius() * 2.0F);
                            this.wave.setIntensity(15.0F);
                            this.wave.setArc(0.0F, 360.0F);
                            this.wave.flip(true);
                            DistortionShader.addDistortion(this.wave);
                        } else {
                            // 更新起始位置扭曲效果
                            float intensity = (float) (Moci_MathPersonal.sqrt(effectLevel) * 60.0F);
                            this.wave.setLocation(ship_loc);
                            this.wave.setSize(the_ship.getCollisionRadius() - effectLevel * 50.0F);
                            this.wave.setIntensity(intensity + 15.0F);
                        }
                    }
                }

                // 根据扭曲模式处理目标位置的扭曲效果
                if (DISTORTION_MODE != 0) { // 0=不渲染扭曲
                    if (DISTORTION_MODE == 1) {
                        // 新扭曲模式：0.85-1.0时快速扭曲
                        if (effectLevel >= 0.85f) {
                            float newDistortionProgress = (effectLevel - 0.85f) / 0.15f; // 0-1范围

                            if (this.targetWave == null) {
                                this.targetWave = new WaveDistortion(this.targetLocation, new Vector2f(0, 0));
                                this.targetWave.setSize(the_ship.getCollisionRadius() * 1.5F);
                                this.targetWave.setIntensity(10.0F);
                                this.targetWave.setArc(0.0F, 360.0F);
                                this.targetWave.flip(true);
                                DistortionShader.addDistortion(this.targetWave);
                            } else {
                                // 快速达到与原扭曲相同的强度
                                float targetIntensity = newDistortionProgress * 50.0F; // 快速增长
                                this.targetWave.setLocation(this.targetLocation);
                                this.targetWave
                                        .setSize(the_ship.getCollisionRadius() * (1.5F + newDistortionProgress * 0.5F));
                                this.targetWave.setIntensity(targetIntensity + 10.0F);
                            }
                        }
                    } else {
                        // 原扭曲模式（DISTORTION_MODE == 2或其他值）
                        if (this.targetWave == null) {
                            this.targetWave = new WaveDistortion(this.targetLocation, new Vector2f(0, 0));
                            this.targetWave.setSize(the_ship.getCollisionRadius() * 1.5F);
                            this.targetWave.setIntensity(10.0F);
                            this.targetWave.setArc(0.0F, 360.0F);
                            this.targetWave.flip(true); // IN阶段也是正向扭曲
                            DistortionShader.addDistortion(this.targetWave);
                        } else {
                            // 更新目标位置扭曲效果
                            float targetIntensity = effectLevel * 40.0F; // 随时间增强
                            this.targetWave.setLocation(this.targetLocation);
                            this.targetWave.setSize(the_ship.getCollisionRadius() * (1.5F + effectLevel * 0.5F));
                            this.targetWave.setIntensity(targetIntensity + 10.0F);
                        }
                    }
                }
            }

        }

        // === 执行跳跃逻辑 ===
        if (state == State.OUT && !hasJumped) {
            hasJumped = true; // 标记已完成跳跃，防止重复执行

            // === 区分玩家操作和AI操作的跳跃行为 ===
            boolean isPlayerControlled = (the_ship.getShipAI() == null);

            if (isPlayerControlled) {
                // === 玩家操作：执行实际跳跃 ===
                // 使用IN阶段预计算的目标位置
                the_ship.getLocation().set(this.targetLocation);
                // 重置速度，防止惯性导致的意外碰撞
                the_ship.getVelocity().set(0, 0);
            } else {
                // === AI操作：不执行跳跃（与PhaseCrossing_new版本一致） ===
                // 只保留视觉效果，不实际移动舰船
            }

            // === 处理PolariphaseDrive船插的特殊效果 ===
            // 在跳跃后记录新位置（用于爆炸效果）
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (ship.getVariant().hasHullMod("Moci_PhaseShiftArmor")) {
                if (!this.Once) {
                    // 记录跳跃后的位置，用于后续爆炸效果
                    this.recordedLocation = new Vector2f(the_ship.getLocation());
                    this.Once = true;
                }
            }
        }

        // === 处理OUT阶段的fx5快速消失和舰船颜色恢复 ===
        if (state == State.OUT) {
            // fx5快速变透明并消失
            float fadeSpeed = 8f; // 快速消失速度
            fx5Alpha = Moci_MathPersonal.clamp(0f, 255f, fx5Alpha - amount * fadeSpeed * 255f);
        }

        // === 处理OUT阶段的扭曲效果 ===
        if (state == State.OUT && DISTORTION_MODE != 0) {
            // 处理起始位置扭曲（淡出）
            if (this.wave != null) {
                if (!hasJumped) {
                    // 跳跃前：起始位置扭曲淡出
                    float fadeIntensity = (1.0f - effectLevel) * 60.0F;
                    this.wave.setLocation(ship_loc);
                    this.wave.setIntensity(fadeIntensity);
                } else {
                    // 跳跃后：清理起始位置扭曲
                    this.wave.fadeOutSize(0.2F);
                    this.wave = null;
                }
            }

            // 处理目标位置扭曲（增强然后淡出）
            if (this.targetWave != null) {
                if (hasJumped) {
                    // 跳跃后：目标位置扭曲改为反向并淡出
                    this.targetWave.flip(false); // 改为反向扭曲
                    float reverseIntensity = (1.0f - effectLevel) * 50.0F;
                    this.targetWave.setLocation(the_ship.getLocation());
                    this.targetWave.setSize(the_ship.getCollisionRadius() * (2.0f - effectLevel));
                    this.targetWave.setIntensity(reverseIntensity);
                } else {
                    // 跳跃前：目标位置扭曲继续增强（保持正向）
                    float buildupIntensity = effectLevel * 50.0F;
                    this.targetWave.setIntensity(buildupIntensity + 10.0F);
                }

                // 在OUT阶段结束时清理
                if (effectLevel < 0.1f) {
                    this.targetWave.fadeOutSize(0.2F);
                    this.targetWave = null;
                }
            }
        }

        // === 重置状态标记 ===
        if (state == State.IN) {
            hasJumped = false;
        }

        // === 系统重新启动时重置所有状态 ===
        if (state == State.IN && effectLevel < 0.1f) {
            targetCalculated = false;
            storedMousePos = null;
            resetFxEffects(); // 重置fx特效状态
        }
    }

    /**
     * 创建彩虹烟雾环效果
     * 在指定位置周围生成多色柔和的烟雾粒子
     */
    private void createRainbowSmokeRing(CombatEngineAPI engine, Vector2f center, float radius, float intensity) {
        int particleCount = 15; // 总粒子数量

        for (int i = 0; i < particleCount; i++) {
            // 在圆周上随机分布粒子
            float angle = (360.0f / particleCount) * i + Moci_MathPersonal.randBetween(-25f, 25f);
            float distance = radius + Moci_MathPersonal.randBetween(-radius * 0.4f, radius * 0.4f);

            Vector2f particlePos = Moci_MathPersonal.getVector(center, angle, distance);

            // 粒子向外缓慢扩散的速度
            Vector2f velocity = Vector2f.sub(particlePos, center, null);
            velocity.normalise();
            velocity.scale(Moci_MathPersonal.randBetween(15f, 35f));

            // 粒子持续时间
            float duration = Moci_MathPersonal.randBetween(1.0f, 1.8f);

            // 随机选择彩虹颜色，让颜色混合在一起
            Color particleColor;
            float colorChoice = Moci_MathPersonal.randBetween(0f, 1f);
            if (colorChoice < 0.33f) {
                particleColor = RAINBOW_COLOR_1; // 红色
            } else if (colorChoice < 0.66f) {
                particleColor = RAINBOW_COLOR_2; // 绿色
            } else {
                particleColor = RAINBOW_COLOR_3; // 蓝色
            }

            // 调整透明度
            particleColor = new Color(
                    particleColor.getRed(),
                    particleColor.getGreen(),
                    particleColor.getBlue(),
                    (int) (particleColor.getAlpha() * intensity));

            // 添加柔和粒子
            engine.addSmoothParticle(
                    particlePos, // 位置
                    velocity, // 速度
                    Moci_MathPersonal.randBetween(radius * 0.3f, radius * 0.6f), // 尺寸
                    Moci_MathPersonal.randBetween(0.7f, 1.1f), // 亮度
                    duration, // 持续时间
                    particleColor // 颜色
            );
        }
    }

    /**
     * 创建目标位置的视觉效果
     * 包含浅紫浅蓝烟雾和光晕效果，跟随IN阶段变化
     */
    private void createTargetLocationEffects(CombatEngineAPI engine, ShipAPI ship, float effectLevel) {
        // 计算目标光晕的特殊时间轴：0.85-0.95放大，0.95-1.0缩小消失
        float targetFlareMultiplier = 1f;
        float targetAlphaMultiplier = 1f;

        if (effectLevel >= 0.85f && effectLevel < 0.95f) {
            // 0.85-0.95阶段：突然放大
            float burstProgress = (effectLevel - 0.85f) / 0.1f; // 0-1范围
            targetFlareMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
        } else if (effectLevel >= 0.95f) {
            // 0.95-1.0阶段：缩小消失
            float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
            targetFlareMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
            targetAlphaMultiplier = 1f - shrinkProgress; // 透明度也同时减少
        }

        if (FLARE_RENDERING_MODE == 0) {
            // 模式0：使用MagicLensFlare渲染
            // 目标位置外圈光晕（与起始位置相似但颜色不同）
            Color targetGlow1 = new Color(150, 120, 255, (int) (135 * effectLevel * targetAlphaMultiplier)); // 浅紫色
            Color targetGlow2 = new Color(120, 180, 255, (int) (200 * effectLevel * targetAlphaMultiplier)); // 浅蓝色

            MagicLensFlare.createSharpFlare(
                    engine, ship, this.targetLocation,
                    9.0F, // 与起始位置相同的光晕尺寸基数
                    ship.getCollisionRadius() * 3.5F * targetFlareMultiplier, // 应用目标光晕倍率
                    90.0F, // 固定角度
                    targetGlow1, targetGlow2 // 浅紫到浅蓝渐变
            );

            // 目标位置内核光晕（与起始位置相似）
            Color targetCore = new Color(200, 180, 255, (int) (185 * effectLevel * targetAlphaMultiplier)); // 浅色内核
            MagicLensFlare.createSharpFlare(
                    engine, ship, this.targetLocation,
                    5.0F, // 与起始位置相同的核心尺寸基数
                    ship.getCollisionRadius() * 3.5F * 0.20F * targetFlareMultiplier, // 应用目标光晕倍率
                    90.0F, // 固定角度
                    targetCore, targetCore // 浅色内核
            );
        } else if (FLARE_RENDERING_MODE == 1) {
            // 模式1：使用简单光晕，应用独立尺寸倍率
            Color targetFlareColor = new Color(150, 150, 255, (int) (200 * effectLevel * targetAlphaMultiplier));

            // 计算目标位置简单光晕的独立尺寸
            float targetSimpleFlareSize = targetFlareMultiplier * SIMPLE_FLARE_SIZE_MULTIPLIER;

            // 外圈光晕
            engine.addSmoothParticle(
                    this.targetLocation,
                    new Vector2f(0, 0), // 静止
                    ship.getCollisionRadius() * 3.5F * targetSimpleFlareSize,
                    1.0f, // 亮度
                    0.1f, // 持续时间
                    targetFlareColor);

            // 内核光晕
            engine.addSmoothParticle(
                    this.targetLocation,
                    new Vector2f(0, 0), // 静止
                    ship.getCollisionRadius() * 3.5F * 0.20F * targetSimpleFlareSize,
                    1.0f, // 亮度
                    0.1f, // 持续时间
                    new Color(255, 255, 255, (int) (150 * effectLevel * targetAlphaMultiplier)) // 白色内核
            );
        } else if (FLARE_RENDERING_MODE == 2) {
            // 模式2：使用fx_6贴图模式，应用独立配置
            SpriteAPI fx6Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_6");
            if (fx6Sprite != null) {
                // 计算目标位置fx_6独立的爆发倍率
                float targetFx6BurstMultiplier = 1f;
                if (effectLevel >= 0.85f && effectLevel < 0.95f) {
                    // 0.85-0.95阶段：突然放大
                    float burstProgress = (effectLevel - 0.85f) / 0.1f; // 0-1范围
                    targetFx6BurstMultiplier = 1f + (FX6_BURST_MULTIPLIER - 1f) * burstProgress;
                } else if (effectLevel >= 0.95f) {
                    // 0.95-1.0阶段：缩小消失
                    float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                    targetFx6BurstMultiplier = FX6_BURST_MULTIPLIER * (1f - shrinkProgress);
                }

                // 计算目标位置fx_6的独立尺寸
                float targetFx6TotalSize = targetFx6BurstMultiplier * FX6_SIZE_MULTIPLIER;
                float fx6Width = fx6Sprite.getWidth() * targetFx6TotalSize;
                float fx6Height = fx6Sprite.getHeight() * targetFx6TotalSize;

                // 使用独立的目标位置颜色
                Color fx6Color = new Color(
                        FX6_TARGET_COLOR.getRed(),
                        FX6_TARGET_COLOR.getGreen(),
                        FX6_TARGET_COLOR.getBlue(),
                        (int) (FX6_TARGET_COLOR.getAlpha() * effectLevel * targetAlphaMultiplier));

                org.magiclib.util.MagicRender.singleframe(
                        fx6Sprite,
                        this.targetLocation,
                        new Vector2f(fx6Width, fx6Height),
                        90.0F, // 固定角度
                        fx6Color,
                        true // additive混合
                );
            }
        } else {
            // 模式3：混合模式（fx_6贴图 + 0.85-1.0阶段的MagicLensFlare）
            SpriteAPI fx6Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_6");
            if (fx6Sprite != null) {
                // === 第一部分：fx_6贴图渲染（与模式2相同） ===
                float targetFx6BurstMultiplier = 1f;
                if (effectLevel >= 0.85f && effectLevel < 0.95f) {
                    // 0.85-0.95阶段：突然放大
                    float burstProgress = (effectLevel - 0.85f) / 0.1f; // 0-1范围
                    targetFx6BurstMultiplier = 1f + (FX6_BURST_MULTIPLIER - 1f) * burstProgress;
                } else if (effectLevel >= 0.95f) {
                    // 0.95-1.0阶段：缩小消失
                    float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                    targetFx6BurstMultiplier = FX6_BURST_MULTIPLIER * (1f - shrinkProgress);
                }

                // 计算目标位置fx_6的独立尺寸
                float targetFx6TotalSize = targetFx6BurstMultiplier * FX6_SIZE_MULTIPLIER;
                float fx6Width = fx6Sprite.getWidth() * targetFx6TotalSize;
                float fx6Height = fx6Sprite.getHeight() * targetFx6TotalSize;

                // 使用独立的目标位置颜色
                Color fx6Color = new Color(
                        FX6_TARGET_COLOR.getRed(),
                        FX6_TARGET_COLOR.getGreen(),
                        FX6_TARGET_COLOR.getBlue(),
                        (int) (FX6_TARGET_COLOR.getAlpha() * effectLevel * targetAlphaMultiplier));

                org.magiclib.util.MagicRender.singleframe(
                        fx6Sprite,
                        this.targetLocation,
                        new Vector2f(fx6Width, fx6Height),
                        90.0F, // 固定角度
                        fx6Color,
                        true // additive混合
                );

                // === 第二部分：0.85-1.0阶段的MagicLensFlare光晕 ===
                if (effectLevel >= 0.85f) {
                    // 计算目标位置混合光晕的进度（从0.85开始计算）
                    float targetHybridFlareProgress = (effectLevel - 0.85f) / (1f - 0.85f);

                    // 使用与舰船位置不同的颜色（紫色调，与橙色形成对比）
                    Color targetHybridGlow1 = new Color(180, 100, 255,
                            (int) (120 * targetHybridFlareProgress * targetAlphaMultiplier)); // 紫色
                    Color targetHybridGlow2 = new Color(150, 150, 255,
                            (int) (180 * targetHybridFlareProgress * targetAlphaMultiplier)); // 浅紫色
                    Color targetHybridCore = new Color(200, 180, 255,
                            (int) (200 * targetHybridFlareProgress * targetAlphaMultiplier)); // 浅紫核心

                    // 外圈光晕（紫色调）
                    MagicLensFlare.createSharpFlare(
                            engine, ship, this.targetLocation,
                            9.0F, // 光晕尺寸基数
                            ship.getCollisionRadius() * 3.5F * targetFlareMultiplier * HYBRID_FLARE_SIZE_MULTIPLIER,
                            90.0F, // 固定角度
                            targetHybridGlow1, targetHybridGlow2 // 紫色渐变效果
                    );

                    // 内核光晕（浅紫色）
                    MagicLensFlare.createSharpFlare(
                            engine, ship, this.targetLocation,
                            5.0F, // 核心尺寸基数（较小）
                            ship.getCollisionRadius() * 3.5F * 0.20F * targetFlareMultiplier
                                    * HYBRID_FLARE_SIZE_MULTIPLIER,
                            90.0F, // 固定角度
                            targetHybridCore, targetHybridCore // 浅紫色内核
                    );
                }
            }
        }

        // 目标位置烟雾效果（可选）
        if (ENABLE_SMOKE_EFFECTS) {
            createTargetSmokeRing(engine, this.targetLocation, ship.getCollisionRadius() * 2.5F * effectLevel,
                    effectLevel);
        }
    }

    /**
     * 创建目标位置的烟雾环效果
     * 浅紫浅蓝色调的柔和烟雾
     */
    private void createTargetSmokeRing(CombatEngineAPI engine, Vector2f center, float radius, float intensity) {
        int particleCount = 8; // 较少的粒子数量，避免过于显眼

        // 目标位置烟雾颜色（浅紫浅蓝）
        Color targetSmoke1 = new Color(180, 150, 255, (int) (60 * intensity)); // 浅紫色
        Color targetSmoke2 = new Color(150, 200, 255, (int) (50 * intensity)); // 浅蓝色

        for (int i = 0; i < particleCount; i++) {
            // 在圆周上随机分布粒子
            float angle = (360.0f / particleCount) * i + Moci_MathPersonal.randBetween(-30f, 30f);
            float distance = radius + Moci_MathPersonal.randBetween(-radius * 0.5f, radius * 0.5f);

            Vector2f particlePos = Moci_MathPersonal.getVector(center, angle, distance);

            // 粒子向外缓慢扩散的速度
            Vector2f velocity = Vector2f.sub(particlePos, center, null);
            velocity.normalise();
            velocity.scale(Moci_MathPersonal.randBetween(8f, 20f)); // 较慢的速度

            // 粒子持续时间
            float duration = Moci_MathPersonal.randBetween(1.2f, 2.0f); // 较长的持续时间

            // 随机选择浅紫或浅蓝色
            Color particleColor = Moci_MathPersonal.randBetween(0f, 1f) < 0.5f ? targetSmoke1 : targetSmoke2;

            // 添加柔和粒子
            engine.addSmoothParticle(
                    particlePos, // 位置
                    velocity, // 速度
                    Moci_MathPersonal.randBetween(radius * 0.4f, radius * 0.8f), // 尺寸
                    Moci_MathPersonal.randBetween(0.5f, 0.8f), // 较低的亮度
                    duration, // 持续时间
                    particleColor // 颜色
            );
        }
    }

    /**
     * 系统结束时的清理和特殊效果处理
     * 主要处理PolariphaseDrive船插的爆炸效果
     * 
     * @param stats 舰船属性接口
     * @param id    系统ID
     */
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        CombatEngineAPI engine = Global.getCombatEngine();

        // === 处理PolariphaseDrive船插的特殊爆炸效果 ===
        if (ship.getVariant().hasHullMod("Moci_PhaseShiftArmor")) {
            if (this.Once) {
                this.Once = false; // 重置标记，防止重复触发

                // === 爆炸参数设置 ===
                float radius = 400.0F; // 爆炸半径
                float coreRadius = 200.0F; // 核心伤害半径
                float duration = 1.0F; // 爆炸持续时间
                float maxDamage = 1500.0F; // 最大伤害值
                float particleSizeMin = 6.0F; // 粒子最小尺寸
                float particleSizeRange = 10.0F; // 粒子尺寸范围
                float particleDuration = 0.5F; // 粒子持续时间
                int particleCount = 150; // 粒子数量

                // === 创建伤害爆炸效果 ===
                DamagingExplosionSpec explosionSpec = new DamagingExplosionSpec(
                        duration, radius, coreRadius, maxDamage, 0.0F,
                        CollisionClass.PROJECTILE_NO_FF, CollisionClass.PROJECTILE_NO_FF,
                        particleSizeMin, particleSizeRange, particleDuration, particleCount,
                        EXPLODE_COLOR, EXPLODE_COLOR);
                explosionSpec.setDamageType(DamageType.ENERGY); // 设置为能量伤害
                explosionSpec.setUseDetailedExplosion(true); // 启用详细爆炸效果
                explosionSpec.setSoundSetId("Moci_WarpSound"); // 音效

                // 在记录的位置生成爆炸
                engine.spawnDamagingExplosion(explosionSpec, ship, this.recordedLocation);

                // === 创建额外的粒子效果 ===
                // 生成200个随机分布的粒子，增强视觉效果
                for (int i = 0; i < 200; ++i) {
                    // 在爆炸中心周围随机生成粒子位置
                    Vector2f point = Moci_MathPersonal.getVector(
                            this.recordedLocation,
                            Moci_MathPersonal.getRandomAngle(),
                            Moci_MathPersonal.randBetween(50.0F, 200.0F));

                    // 计算粒子速度（从中心向外扩散）
                    Vector2f vel = Vector2f.sub(point, this.recordedLocation, null);
                    float dur = Moci_MathPersonal.randBetween(0.3F, 0.6F);
                    vel.scale(1.0F / dur);

                    // 设置粒子尺寸和增长参数
                    Vector2f size = new Vector2f(
                            Moci_MathPersonal.randBetween(80.0F, 160.0F),
                            Moci_MathPersonal.randBetween(20.0F, 30.0F));
                    Vector2f growth = new Vector2f(
                            Moci_MathPersonal.randBetween(-4.0F, -8.0F),
                            Moci_MathPersonal.randBetween(-2.0F, 0.0F));

                    float angle = VectorUtils.getFacing(vel);
                }

                // === 创建涟漪扭曲效果 ===
                float lifetime = 0.8F;
                RippleDistortion Rip = new RippleDistortion();
                Rip.setLocation(this.recordedLocation); // 设置扭曲中心位置
                Rip.setIntensity(150.0F); // 扭曲强度
                Rip.setLifetime(lifetime); // 效果持续时间
                Rip.setFrameRate(60.0F / lifetime); // 动画帧率
                Rip.setCurrentFrame(0.0F); // 起始帧
                Rip.setSize(radius); // 扭曲范围
                Rip.fadeInSize(0.25F * lifetime); // 淡入动画时间
                Rip.fadeOutIntensity(lifetime); // 淡出动画时间
                Rip.flip(true); // 反转扭曲效果
                DistortionShader.addDistortion(Rip); // 添加到渲染管线
            }
        }

        // 注意：舰船透明度等其他状态会由游戏引擎自动重置
    }

    public ShipSystemStatsScript.StatusData getStatusData(int index, ShipSystemStatsScript.State state,
            float effectLevel) {
        // 只显示第一个状态条目，其他索引返回null
        return index == 0 ? new ShipSystemStatsScript.StatusData(
                "玻色子跳跃 - 跳跃中", // 固定显示文本
                false // false表示非进度条类型的状态显示
        ) : null;
    }

    /**
     * 更新fx贴图特效状态
     * 根据effectLevel控制各个贴图的显示逻辑
     */
    private void updateFxEffects(float amount, float effectLevel) {
        // 检测是否达到effectLevel=1，触发快速缩小效果
        if (effectLevel >= 1.0f && !isEffectLevel1Reached) {
            isEffectLevel1Reached = true;
        }

        // 如果已达到effectLevel=1，开始快速缩小所有贴图
        if (isEffectLevel1Reached) {
            float shrinkSpeed = 8f; // 快速缩小速度
            fx1Size = Moci_MathPersonal.clamp(0f, Float.MAX_VALUE, fx1Size - amount * shrinkSpeed);
            fx3Size = Moci_MathPersonal.clamp(0f, Float.MAX_VALUE, fx3Size - amount * shrinkSpeed);
            fx2Alpha = Moci_MathPersonal.clamp(0f, 255f, fx2Alpha - amount * shrinkSpeed * 255f);
            fx4Alpha = Moci_MathPersonal.clamp(0f, 255f, fx4Alpha - amount * shrinkSpeed * 255f);
            fx5Alpha = Moci_MathPersonal.clamp(0f, 255f, fx5Alpha - amount * shrinkSpeed * 255f);
            return; // 缩小阶段不执行其他逻辑
        }

        // 更新fx1和fx3的尺寸（从系统开始就增长，0.5时达到1f，0.9-1.0时显著增大）
        if (effectLevel <= 0.5f) {
            fx1Size = effectLevel / 0.5f; // 0-1范围
            fx3Size = effectLevel / 0.5f; // 0-1范围
        } else if (effectLevel <= 0.9f) {
            fx1Size = 1f; // 保持最大尺寸
            fx3Size = 1f; // 保持最大尺寸
        } else if (effectLevel <= 0.95f) {
            // effectLevel 0.9-0.95时显著增大
            float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
            float burstMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
            fx1Size = burstMultiplier;
            fx3Size = burstMultiplier;
        } else {
            // effectLevel 0.95-1.0时快速缩小到0
            float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
            float burstMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
            fx1Size = burstMultiplier;
            fx3Size = burstMultiplier;
        }

        // 更新fx5的透明度（IN阶段随effectLevel增长，从155到255，0.95-1.0时快速消失）
        if (effectLevel <= 0.5f) {
            // 0-0.5 effectLevel时，从155增长到255
            fx5Alpha = 155f + (255f - 155f) * (effectLevel / 0.5f); // 155-255范围
        } else if (effectLevel <= 0.95f) {
            // 0.5-0.95 effectLevel时，保持255透明度
            fx5Alpha = 255f;
        } else {
            // 0.95-1.0 effectLevel时，快速消失到0
            float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
            fx5Alpha = 255f * (1f - shrinkProgress);
        }

        // 当effectLevel达到0.5时，开始渲染fx2和fx4
        if (effectLevel >= 0.5f) {
            // 更新呼吸效果计时器
            breathingTimer += amount * 4f; // 呼吸频率控制

            // 计算基础呼吸效果（在0.8到1.2之间变化）
            float breathingEffect = 1f + 0.2f * (float) Math.sin(breathingTimer); // 这里保持Math.sin，因为Moci_MathPersonal没有sin方法

            // 在effectLevel 0.9-0.95时应用最终爆发倍率，0.95-1.0时快速缩小
            if (effectLevel >= 0.9f && effectLevel <= 0.95f) {
                float burstProgress = (effectLevel - 0.9f) / 0.05f; // 0-1范围
                float burstMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
                fx2Size = breathingEffect * burstMultiplier;
                fx4Size = breathingEffect * burstMultiplier;
            } else if (effectLevel > 0.95f) {
                // 0.95-1.0时快速缩小到0
                float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                float burstMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
                fx2Size = breathingEffect * burstMultiplier;
                fx4Size = breathingEffect * burstMultiplier;
            } else {
                fx2Size = breathingEffect;
                fx4Size = breathingEffect;
            }

            // 渐入效果（effectLevel 0.5-0.95时从0渐入到最大透明度，0.95-1.0时快速消失）
            if (effectLevel <= 0.95f) {
                float fadeInProgress = (effectLevel - 0.5f) / 0.45f; // 0-1范围（0.5到0.95）
                fx2Alpha = (150f + 105f * fadeInProgress); // 150-255范围
                fx4Alpha = (150f + 105f * fadeInProgress); // 150-255范围
            } else {
                // 0.95-1.0时快速消失到0
                float shrinkProgress = (effectLevel - 0.95f) / 0.05f; // 0-1范围
                fx2Alpha = 255f * (1f - shrinkProgress);
                fx4Alpha = 255f * (1f - shrinkProgress);
            }
        } else {
            // effectLevel小于0.5时，fx2和fx4不显示
            fx2Alpha = 0f;
            fx4Alpha = 0f;
        }
    }

    /**
     * 渲染fx贴图特效
     * 在舰船中心位置渲染所有fx贴图
     */
    private void renderFxEffects(CombatEngineAPI engine, ShipAPI ship, float effectLevel) {
        Vector2f shipCenter = ship.getLocation();

        // 设置各贴图的缩放倍率 - 所有贴图都使用1f
        float fx1Scale = 1f; // fx_1缩放倍率：1f
        float fx2Scale = 1f; // fx_2缩放倍率：1f
        float fx3Scale = 1f; // fx_3缩放倍率：1f
        float fx4Scale = 1f; // fx_4缩放倍率：1f
        float fx5Scale = 1f; // fx_5缩放倍率：1f（贴图已调整为合适尺寸）

        // 渲染fx1（从系统开始就显示，透明度从150到255）
        if (fx1Size > 0f) {
            float fx1Alpha = 150f + 105f * effectLevel; // 150-255范围
            Color fx1Color = new Color(255, 255, 255, (int) fx1Alpha);

            // fx_1: 自动获取贴图原始尺寸并应用缩放倍率
            SpriteAPI fx1Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_1");
            if (fx1Sprite != null) {
                float fx1Width = fx1Sprite.getWidth() * fx1Scale * fx1Size;
                float fx1Height = fx1Sprite.getHeight() * fx1Scale * fx1Size;

                org.magiclib.util.MagicRender.singleframe(
                        fx1Sprite,
                        shipCenter,
                        new Vector2f(fx1Width, fx1Height),
                        0f, // 角度
                        fx1Color,
                        true // additive混合
                );
            }
        }

        // 渲染fx3（从系统开始就显示，透明度从150到255）
        if (fx3Size > 0f) {
            float fx3Alpha = 150f + 105f * effectLevel; // 150-255范围
            Color fx3Color = new Color(255, 255, 255, (int) fx3Alpha);

            // fx_3: 自动获取贴图原始尺寸并应用缩放倍率
            SpriteAPI fx3Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_3");
            if (fx3Sprite != null) {
                float fx3Width = fx3Sprite.getWidth() * fx3Scale * fx3Size;
                float fx3Height = fx3Sprite.getHeight() * fx3Scale * fx3Size;

                org.magiclib.util.MagicRender.singleframe(
                        fx3Sprite,
                        shipCenter,
                        new Vector2f(fx3Width, fx3Height),
                        0f, // 角度
                        fx3Color,
                        true // additive混合
                );
            }
        }

        // 渲染fx2（effectLevel 0.5时开始显示，带呼吸效果）
        if (fx2Alpha > 0f) {
            Color fx2Color = new Color(255, 255, 255, (int) fx2Alpha);

            // fx_2: 自动获取贴图原始尺寸并应用缩放倍率和呼吸效果
            SpriteAPI fx2Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_2");
            if (fx2Sprite != null) {
                float fx2Width = fx2Sprite.getWidth() * fx2Scale * fx2Size;
                float fx2Height = fx2Sprite.getHeight() * fx2Scale * fx2Size;

                org.magiclib.util.MagicRender.singleframe(
                        fx2Sprite,
                        shipCenter,
                        new Vector2f(fx2Width, fx2Height),
                        0f, // 角度
                        fx2Color,
                        true // additive混合
                );
            }
        }

        // 渲染fx4（effectLevel 0.5时开始显示，带呼吸效果）
        if (fx4Alpha > 0f) {
            Color fx4Color = new Color(255, 255, 255, (int) fx4Alpha);

            // fx_4: 自动获取贴图原始尺寸并应用缩放倍率和呼吸效果
            SpriteAPI fx4Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_4");
            if (fx4Sprite != null) {
                float fx4Width = fx4Sprite.getWidth() * fx4Scale * fx4Size;
                float fx4Height = fx4Sprite.getHeight() * fx4Scale * fx4Size;

                org.magiclib.util.MagicRender.singleframe(
                        fx4Sprite,
                        shipCenter,
                        new Vector2f(fx4Width, fx4Height),
                        0f, // 角度
                        fx4Color,
                        true // additive混合
                );
            }
        }

        // 渲染fx5（1f尺寸，只有透明度变化，跟随舰船船头方向）
        if (fx5Alpha > 0f) {
            Color fx5Color = new Color(255, 255, 255, (int) fx5Alpha);

            // fx_5: 自动获取贴图原始尺寸并应用1f缩放倍率，跟随舰船朝向
            SpriteAPI fx5Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_5");
            if (fx5Sprite != null) {
                float fx5Width = fx5Sprite.getWidth() * fx5Scale;
                float fx5Height = fx5Sprite.getHeight() * fx5Scale;

                org.magiclib.util.MagicRender.singleframe(
                        fx5Sprite,
                        shipCenter,
                        new Vector2f(fx5Width, fx5Height),
                        ship.getFacing() - 90f, // 跟随舰船船头方向，修正90度偏移
                        fx5Color,
                        false // 使用normal混合而不是additive，确保覆盖效果
                );
            }
        }
    }

    /**
     * 重置fx特效状态
     * 在系统重新启动时调用
     */
    private void resetFxEffects() {
        fx1Size = 0f;
        fx3Size = 0f;
        fx2Size = 1f;
        fx4Size = 1f;
        fx2Alpha = 0f;
        fx4Alpha = 0f;
        fx5Alpha = 0f;
        breathingTimer = 0f;
        isEffectLevel1Reached = false;
        originalShipColorMult = 1f;
    }

    /**
     * AI安全跳跃位置搜索
     * 为AI控制的舰船寻找安全的跳跃落点，避免碰撞和越界
     * 
     * @param engine         战斗引擎
     * @param ship           执行跳跃的舰船
     * @param startPos       起始位置
     * @param originalTarget 原始目标位置
     * @return 安全的跳跃目标位置
     */
    private Vector2f findSafeJumpLocation(CombatEngineAPI engine, ShipAPI ship, Vector2f startPos,
            Vector2f originalTarget) {
        // 计算原始跳跃方向和距离
        Vector2f jumpDirection = Vector2f.sub(originalTarget, startPos, null);
        float originalDistance = jumpDirection.length();

        if (originalDistance <= 0) {
            return originalTarget; // 如果距离为0，返回原始目标
        }

        jumpDirection.normalise(); // 标准化方向向量

        // 首先检查原始目标位置是否安全
        if (isLocationSafe(engine, ship, originalTarget)) {
            return originalTarget;
        }

        // 如果原始位置不安全，尝试减少距离寻找安全位置
        float currentDistance = originalDistance;
        while (currentDistance >= AI_MIN_JUMP_DISTANCE) {
            currentDistance -= AI_DISTANCE_REDUCTION_STEP;

            // 计算新的目标位置
            Vector2f testTarget = new Vector2f(
                    startPos.x + jumpDirection.x * currentDistance,
                    startPos.y + jumpDirection.y * currentDistance);

            // 检查这个位置是否安全
            if (isLocationSafe(engine, ship, testTarget)) {
                return testTarget;
            }
        }

        // 如果减少距离仍然找不到安全位置，尝试在周围搜索
        return findAlternativeSafeLocation(engine, ship, startPos, jumpDirection, AI_MIN_JUMP_DISTANCE);
    }

    /**
     * 检查指定位置是否安全
     * 仅进行舰船碰撞检测和边界检测
     * 
     * @param engine    战斗引擎
     * @param ship      执行跳跃的舰船
     * @param targetPos 要检查的目标位置
     * @return true如果位置安全，false如果不安全
     */
    private boolean isLocationSafe(CombatEngineAPI engine, ShipAPI ship, Vector2f targetPos) {
        // === 边界检测 ===
        float mapWidth = engine.getMapWidth() * 0.5f;
        float mapHeight = engine.getMapHeight() * 0.5f;
        float safetyMargin = ship.getCollisionRadius() + AI_SAFETY_MARGIN;

        // 检查是否超出战场边界
        if (Math.abs(targetPos.x) > mapWidth - safetyMargin ||
                Math.abs(targetPos.y) > mapHeight - safetyMargin) {
            return false;
        }

        // === 舰船碰撞检测 ===
        // 检查目标位置周围是否有其他舰船
        for (ShipAPI otherShip : engine.getShips()) {
            if (otherShip == ship)
                continue; // 跳过自己
            if (otherShip.isHulk())
                continue; // 跳过残骸
            if (otherShip.getCollisionClass() != CollisionClass.SHIP)
                continue; // 只检测碰撞类型为SHIP的舰船

            // 计算与其他舰船的距离
            float distance = MathUtils.getDistance(targetPos, otherShip.getLocation());
            float requiredDistance = ship.getCollisionRadius() + otherShip.getCollisionRadius() + AI_SAFETY_MARGIN;

            if (distance < requiredDistance) {
                return false; // 距离太近，不安全
            }
        }

        return true; // 位置安全
    }

    /**
     * 寻找替代的安全位置
     * 当主要方向不安全时，在周围搜索其他安全位置
     * 
     * @param engine             战斗引擎
     * @param ship               执行跳跃的舰船
     * @param startPos           起始位置
     * @param preferredDirection 首选方向
     * @param distance           跳跃距离
     * @return 找到的安全位置，如果找不到则返回起始位置附近的安全点
     */
    private Vector2f findAlternativeSafeLocation(CombatEngineAPI engine, ShipAPI ship, Vector2f startPos,
            Vector2f preferredDirection, float distance) {
        // 在首选方向左右各尝试几个角度
        float[] angleOffsets = { 0f, 30f, -30f, 60f, -60f, 90f, -90f, 120f, -120f, 150f, -150f, 180f };

        for (float angleOffset : angleOffsets) {
            // 计算新方向
            float newAngle = VectorUtils.getFacing(preferredDirection) + angleOffset;
            Vector2f newDirection = Misc.getUnitVectorAtDegreeAngle(newAngle);

            // 计算新的目标位置
            Vector2f testTarget = new Vector2f(
                    startPos.x + newDirection.x * distance,
                    startPos.y + newDirection.y * distance);

            // 检查这个位置是否安全
            if (isLocationSafe(engine, ship, testTarget)) {
                return testTarget;
            }
        }

        // 如果所有方向都不安全，返回一个最小距离的安全位置
        // 尝试在舰船周围找一个很近的安全点
        for (float testDistance = 200f; testDistance >= 100f; testDistance -= 50f) {
            for (float angle = 0f; angle < 360f; angle += 45f) {
                Vector2f direction = Misc.getUnitVectorAtDegreeAngle(angle);
                Vector2f testTarget = new Vector2f(
                        startPos.x + direction.x * testDistance,
                        startPos.y + direction.y * testDistance);

                if (isLocationSafe(engine, ship, testTarget)) {
                    return testTarget;
                }
            }
        }

        // 如果实在找不到安全位置，返回原始起始位置（不跳跃）
        return new Vector2f(startPos);
    }

}