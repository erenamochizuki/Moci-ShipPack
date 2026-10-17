package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import data.scripts.util.Moci_MathPersonal;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.WaveDistortion;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;

import java.awt.Color;

/**
 * 相位穿越舰船系统 - 简化版
 * 保留完整视觉效果，跳跃逻辑由游戏引擎通过 "aiType":"PHASE_DISPLACER" 处理
 */
public class Moci_PhaseCrossing_Simple extends BaseShipSystemScript {
    // === 视觉效果常量 ===
    private WaveDistortion wave = null;
    
    // 光晕颜色配置
    public static final Color GLOW_1 = new Color(68, 82, 255, 100);
    public static final Color GLOW_2 = new Color(58, 150, 255, 150);
    public static final Color GLOW_CORE = new Color(161, 216, 255, 150);
    
    // 船插爆炸效果颜色
    private static final Color EXPLODE_COLOR = new Color(62, 123, 237, 75);
    
    // === 最终爆发效果参数 ===
    private static final float FINAL_BURST_MULTIPLIER = 1f; // 光晕爆发时的最大尺寸倍率
    
    // === fx贴图特效状态变量 ===
    private float fx1Size = 0f;
    private float fx3Size = 0f;
    private float fx2Size = 1f;
    private float fx4Size = 1f;
    private float fx2Alpha = 0f;
    private float fx4Alpha = 0f;
    private float fx5Alpha = 0f;
    private float breathingTimer = 0f;
    private boolean isEffectLevel1Reached = false;
    
    // === 状态控制变量 ===
    private boolean hasJumped = false;
    private boolean Once = false;
    private Vector2f recordedLocation = new Vector2f();

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
            
            // === 创建跃迁光晕效果（IN和OUT阶段） ===
            if (state == State.IN || state == State.OUT) {
                // 计算光晕尺寸倍率
                float flareMultiplier = 1f;
                float flareAlphaMultiplier = 1f;
                
                if (state == State.IN) {
                    // IN阶段：0.9-0.95爆发增大，0.95-1.0快速缩小
                    if (effectLevel >= 0.9f && effectLevel < 0.95f) {
                        float burstProgress = (effectLevel - 0.9f) / 0.05f;
                        flareMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
                    } else if (effectLevel >= 0.95f) {
                        float shrinkProgress = (effectLevel - 0.95f) / 0.05f;
                        flareMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
                        flareAlphaMultiplier = 1f - shrinkProgress;
                    }
                } else {
                    // OUT阶段：从最大尺寸逐渐缩小到0
                    // effectLevel在OUT阶段从1.0递减到0，所以直接使用effectLevel
                    flareMultiplier = FINAL_BURST_MULTIPLIER * effectLevel;
                    flareAlphaMultiplier = effectLevel;
                }
                
                // 外圈光晕
                Color outGlow1 = new Color(
                        GLOW_1.getRed(),
                        GLOW_1.getGreen(),
                        GLOW_1.getBlue(),
                        (int) (GLOW_1.getAlpha() * flareAlphaMultiplier));
                Color outGlow2 = new Color(
                        GLOW_2.getRed(),
                        GLOW_2.getGreen(),
                        GLOW_2.getBlue(),
                        (int) (GLOW_2.getAlpha() * flareAlphaMultiplier));
                
                MagicLensFlare.createSharpFlare(
                        engine, the_ship, ship_loc,
                        9.0F,
                        the_ship.getCollisionRadius() * 3.5F * flareMultiplier,
                        the_ship.getFacing() + 90.0F,
                        outGlow1, outGlow2
                );
                
                // 内核光晕
                float coreAlpha = state == State.IN ? effectLevel * flareAlphaMultiplier : flareAlphaMultiplier;
                Color coreColor = new Color(
                        GLOW_CORE.getRed(),
                        GLOW_CORE.getGreen(),
                        GLOW_CORE.getBlue(),
                        (int) (GLOW_CORE.getAlpha() * coreAlpha));
                MagicLensFlare.createSharpFlare(
                        engine, the_ship, ship_loc,
                        5.0F,
                        the_ship.getCollisionRadius() * 3.5F * 0.20F * flareMultiplier,
                        the_ship.getFacing() + 90.0F,
                        coreColor, coreColor
                );
            }
            
            // === 设置舰船透明度效果 ===
            the_ship.setExtraAlphaMult(1.0F - effectLevel);
            
            // === 处理激活阶段的扭曲效果 ===
            if (state == State.IN) {
                if (this.wave == null) {
                    this.wave = new WaveDistortion(ship_loc, new Vector2f(0, 0));
                    this.wave.setSize(the_ship.getCollisionRadius() * 2.0F);
                    this.wave.setIntensity(15.0F);
                    this.wave.setArc(0.0F, 360.0F);
                    this.wave.flip(true);
                    DistortionShader.addDistortion(this.wave);
                } else {
                    float intensity = (float) (Math.sqrt(effectLevel) * 60.0F);
                    this.wave.setLocation(ship_loc);
                    this.wave.setSize(the_ship.getCollisionRadius() - effectLevel * 50.0F);
                    this.wave.setIntensity(intensity + 15.0F);
                }
            }
            
            // === 处理OUT阶段的扭曲效果 ===
            if (state == State.OUT) {
                if (this.wave != null) {
                    // OUT阶段：扭曲淡出
                    float fadeIntensity = (1.0f - effectLevel) * 60.0F;
                    this.wave.setLocation(ship_loc);
                    this.wave.setIntensity(fadeIntensity);
                    
                    // 在OUT阶段结束时清理
                    if (effectLevel < 0.1f) {
                        this.wave.fadeOutSize(0.5F);
                        this.wave = null;
                    }
                }
            }
        }

        // === 处理OUT阶段的fx5快速消失 ===
        if (state == State.OUT) {
            float fadeSpeed = 8f;
            fx5Alpha = Moci_MathPersonal.clamp(0f, 255f, fx5Alpha - amount * fadeSpeed * 255f);
        }

        // === 标记跳跃状态（供船插使用） ===
        if (state == State.OUT && !hasJumped) {
            hasJumped = true;
            
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (ship.getVariant().hasHullMod("Moci_PhaseShiftArmor")) {
                if (!this.Once) {
                    this.recordedLocation = new Vector2f(stats.getEntity().getLocation());
                    this.Once = true;
                }
            }
        }

        // === 重置跳跃标记 ===
        if (state == State.IN) {
            hasJumped = false;
        }
        
        // === 系统重新启动时重置所有状态 ===
        if (state == State.IN && effectLevel < 0.1f) {
            resetFxEffects();
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        CombatEngineAPI engine = Global.getCombatEngine();
        
        // === 应用1秒质量翻倍效果 ===
        if (engine != null) {
            engine.addPlugin(new MassDoubleEffect(ship, 1f));
        }
        
        // === 处理船插的特殊爆炸效果 ===
        if (ship.getVariant().hasHullMod("Moci_PhaseShiftArmor")) {
            if (this.Once) {
                this.Once = false;
                
                // === 爆炸参数设置 ===
                float radius = 400.0F;
                float coreRadius = 200.0F;
                float duration = 1.0F;
                float maxDamage = 1500.0F;
                float particleSizeMin = 6.0F;
                float particleSizeRange = 10.0F;
                float particleDuration = 0.5F;
                int particleCount = 150;
                
                // === 创建伤害爆炸效果 ===
                com.fs.starfarer.api.loading.DamagingExplosionSpec explosionSpec = 
                    new com.fs.starfarer.api.loading.DamagingExplosionSpec(
                        duration, radius, coreRadius, maxDamage, 0.0F,
                        com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_NO_FF,
                        com.fs.starfarer.api.combat.CollisionClass.PROJECTILE_NO_FF,
                        particleSizeMin, particleSizeRange, particleDuration, particleCount,
                        EXPLODE_COLOR, EXPLODE_COLOR);
                explosionSpec.setDamageType(com.fs.starfarer.api.combat.DamageType.ENERGY);
                explosionSpec.setUseDetailedExplosion(true);
                explosionSpec.setSoundSetId("Moci_WarpSound");
                
                engine.spawnDamagingExplosion(explosionSpec, ship, this.recordedLocation);
                
                // === 创建额外的粒子效果 ===
                for (int i = 0; i < 200; i++) {
                    Vector2f point = Moci_MathPersonal.getVector(
                        this.recordedLocation,
                        Moci_MathPersonal.getRandomAngle(),
                        Moci_MathPersonal.randBetween(50.0F, 200.0F));
                    
                    Vector2f vel = Vector2f.sub(point, this.recordedLocation, null);
                    float dur = Moci_MathPersonal.randBetween(0.3F, 0.6F);
                    vel.scale(1.0F / dur);
                }
                
                // === 创建涟漪扭曲效果 ===
                float lifetime = 0.8F;
                org.dark.shaders.distortion.RippleDistortion Rip = 
                    new org.dark.shaders.distortion.RippleDistortion();
                Rip.setLocation(this.recordedLocation);
                Rip.setIntensity(150.0F);
                Rip.setLifetime(lifetime);
                Rip.setFrameRate(60.0F / lifetime);
                Rip.setCurrentFrame(0.0F);
                Rip.setSize(radius);
                Rip.fadeInSize(0.25F * lifetime);
                Rip.fadeOutIntensity(lifetime);
                Rip.flip(true);
                DistortionShader.addDistortion(Rip);
            }
        }
    }

    @Override
    public ShipSystemStatsScript.StatusData getStatusData(int index, ShipSystemStatsScript.State state, float effectLevel) {
        return index == 0 ? new ShipSystemStatsScript.StatusData(
                "玻色子跳跃 - 跳跃中",
                false
        ) : null;
    }
    
    /**
     * 更新fx贴图特效状态
     */
    private void updateFxEffects(float amount, float effectLevel) {
        // 检测是否达到effectLevel=1
        if (effectLevel >= 1.0f && !isEffectLevel1Reached) {
            isEffectLevel1Reached = true;
        }

        // 如果已达到effectLevel=1，开始快速缩小所有贴图
        if (isEffectLevel1Reached) {
            float shrinkSpeed = 8f;
            fx1Size = Moci_MathPersonal.clamp(0f, Float.MAX_VALUE, fx1Size - amount * shrinkSpeed);
            fx3Size = Moci_MathPersonal.clamp(0f, Float.MAX_VALUE, fx3Size - amount * shrinkSpeed);
            fx2Alpha = Moci_MathPersonal.clamp(0f, 255f, fx2Alpha - amount * shrinkSpeed * 255f);
            fx4Alpha = Moci_MathPersonal.clamp(0f, 255f, fx4Alpha - amount * shrinkSpeed * 255f);
            fx5Alpha = Moci_MathPersonal.clamp(0f, 255f, fx5Alpha - amount * shrinkSpeed * 255f);
            return;
        }

        // 更新fx1和fx3的尺寸
        if (effectLevel <= 0.5f) {
            fx1Size = effectLevel / 0.5f;
            fx3Size = effectLevel / 0.5f;
        } else if (effectLevel <= 0.9f) {
            fx1Size = 1f;
            fx3Size = 1f;
        } else if (effectLevel <= 0.95f) {
            float burstProgress = (effectLevel - 0.9f) / 0.05f;
            float burstMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
            fx1Size = burstMultiplier;
            fx3Size = burstMultiplier;
        } else {
            float shrinkProgress = (effectLevel - 0.95f) / 0.05f;
            float burstMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
            fx1Size = burstMultiplier;
            fx3Size = burstMultiplier;
        }

        // 更新fx5的透明度
        if (effectLevel <= 0.5f) {
            fx5Alpha = 155f + (255f - 155f) * (effectLevel / 0.5f);
        } else if (effectLevel <= 0.95f) {
            fx5Alpha = 255f;
        } else {
            float shrinkProgress = (effectLevel - 0.95f) / 0.05f;
            fx5Alpha = 255f * (1f - shrinkProgress);
        }

        // 当effectLevel达到0.5时，开始渲染fx2和fx4
        if (effectLevel >= 0.5f) {
            breathingTimer += amount * 4f;
            float breathingEffect = 1f + 0.2f * (float) Math.sin(breathingTimer);

            if (effectLevel >= 0.9f && effectLevel <= 0.95f) {
                float burstProgress = (effectLevel - 0.9f) / 0.05f;
                float burstMultiplier = 1f + (FINAL_BURST_MULTIPLIER - 1f) * burstProgress;
                fx2Size = breathingEffect * burstMultiplier;
                fx4Size = breathingEffect * burstMultiplier;
            } else if (effectLevel > 0.95f) {
                float shrinkProgress = (effectLevel - 0.95f) / 0.05f;
                float burstMultiplier = FINAL_BURST_MULTIPLIER * (1f - shrinkProgress);
                fx2Size = breathingEffect * burstMultiplier;
                fx4Size = breathingEffect * burstMultiplier;
            } else {
                fx2Size = breathingEffect;
                fx4Size = breathingEffect;
            }

            if (effectLevel <= 0.95f) {
                float fadeInProgress = (effectLevel - 0.5f) / 0.45f;
                fx2Alpha = (150f + 105f * fadeInProgress);
                fx4Alpha = (150f + 105f * fadeInProgress);
            } else {
                float shrinkProgress = (effectLevel - 0.95f) / 0.05f;
                fx2Alpha = 255f * (1f - shrinkProgress);
                fx4Alpha = 255f * (1f - shrinkProgress);
            }
        } else {
            fx2Alpha = 0f;
            fx4Alpha = 0f;
        }
    }

    /**
     * 渲染fx贴图特效
     */
    private void renderFxEffects(CombatEngineAPI engine, ShipAPI ship, float effectLevel) {
        Vector2f shipCenter = ship.getLocation();
        float fx1Scale = 1f;
        float fx2Scale = 1f;
        float fx3Scale = 1f;
        float fx4Scale = 1f;
        float fx5Scale = 1f;

        // 渲染fx1
        if (fx1Size > 0f) {
            float fx1Alpha = 150f + 105f * effectLevel;
            Color fx1Color = new Color(255, 255, 255, (int) fx1Alpha);
            SpriteAPI fx1Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_1");
            if (fx1Sprite != null) {
                float fx1Width = fx1Sprite.getWidth() * fx1Scale * fx1Size;
                float fx1Height = fx1Sprite.getHeight() * fx1Scale * fx1Size;
                org.magiclib.util.MagicRender.singleframe(
                        fx1Sprite, shipCenter, new Vector2f(fx1Width, fx1Height),
                        0f, fx1Color, true);
            }
        }

        // 渲染fx3
        if (fx3Size > 0f) {
            float fx3Alpha = 150f + 105f * effectLevel;
            Color fx3Color = new Color(255, 255, 255, (int) fx3Alpha);
            SpriteAPI fx3Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_3");
            if (fx3Sprite != null) {
                float fx3Width = fx3Sprite.getWidth() * fx3Scale * fx3Size;
                float fx3Height = fx3Sprite.getHeight() * fx3Scale * fx3Size;
                org.magiclib.util.MagicRender.singleframe(
                        fx3Sprite, shipCenter, new Vector2f(fx3Width, fx3Height),
                        0f, fx3Color, true);
            }
        }

        // 渲染fx2
        if (fx2Alpha > 0f) {
            Color fx2Color = new Color(255, 255, 255, (int) fx2Alpha);
            SpriteAPI fx2Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_2");
            if (fx2Sprite != null) {
                float fx2Width = fx2Sprite.getWidth() * fx2Scale * fx2Size;
                float fx2Height = fx2Sprite.getHeight() * fx2Scale * fx2Size;
                org.magiclib.util.MagicRender.singleframe(
                        fx2Sprite, shipCenter, new Vector2f(fx2Width, fx2Height),
                        0f, fx2Color, true);
            }
        }

        // 渲染fx4
        if (fx4Alpha > 0f) {
            Color fx4Color = new Color(255, 255, 255, (int) fx4Alpha);
            SpriteAPI fx4Sprite = Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_4");
            if (fx4Sprite != null) {
                float fx4Width = fx4Sprite.getWidth() * fx4Scale * fx4Size;
                float fx4Height = fx4Sprite.getHeight() * fx4Scale * fx4Size;
                org.magiclib.util.MagicRender.singleframe(
                        fx4Sprite, shipCenter, new Vector2f(fx4Width, fx4Height),
                        0f, fx4Color, true);
            }
        }

        // 渲染fx5（仅限Moci_Nadesico及其变体和D-mod型号）
        if (fx5Alpha > 0f) {
            String hullId = ship.getHullSpec().getHullId();
            
            // 获取真实船体ID（处理D-mod船只）
            // D-mod船只的ID格式为：原ID + "_default_D"
            String trueHullId = hullId.endsWith("_default_D") 
                ? hullId.substring(0, hullId.length() - "_default_D".length()) 
                : hullId;
            
            // 根据舰船ID选择对应的fx5贴图
            String fx5SpriteName = null;
            if (trueHullId.equals("Moci_Nadesico")) {
                fx5SpriteName = "Moci_Nadesico_fx_5";
            } else if (trueHullId.equals("Moci_Nadesico_A")) {
                fx5SpriteName = "Moci_Nadesico_fx_5_2";
            }
            
            // 如果找到对应的贴图，则渲染
            if (fx5SpriteName != null) {
                Color fx5Color = new Color(255, 255, 255, (int) fx5Alpha);
                SpriteAPI fx5Sprite = Global.getSettings().getSprite("fx", fx5SpriteName);
                if (fx5Sprite != null) {
                    float fx5Width = fx5Sprite.getWidth() * fx5Scale;
                    float fx5Height = fx5Sprite.getHeight() * fx5Scale;
                    org.magiclib.util.MagicRender.singleframe(
                            fx5Sprite, shipCenter, new Vector2f(fx5Width, fx5Height),
                            ship.getFacing() - 90f, fx5Color, false);
                }
            }
        }
    }

    /**
     * 重置fx特效状态
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
    }
    
    /**
     * 质量翻倍效果插件
     * 在跃迁结束后给舰船施加持续的质量翻倍效果
     */
    private static class MassDoubleEffect implements com.fs.starfarer.api.combat.EveryFrameCombatPlugin {
        private final ShipAPI ship;
        private final float duration;
        private float elapsed = 0f;
        private Float originalMass = null;
        
        public MassDoubleEffect(ShipAPI ship, float duration) {
            this.ship = ship;
            this.duration = duration;
        }
        
        @Override
        public void advance(float amount, java.util.List<com.fs.starfarer.api.input.InputEventAPI> events) {
            if (ship == null || !Global.getCombatEngine().isEntityInPlay(ship)) {
                // 恢复原始质量
                if (originalMass != null) {
                    ship.setMass(originalMass);
                }
                Global.getCombatEngine().removePlugin(this);
                return;
            }
            
            elapsed += amount;
            
            // 应用质量翻倍效果
            if (elapsed < duration) {
                // 第一次执行时记录原始质量并翻倍
                if (originalMass == null) {
                    originalMass = ship.getMass();
                    ship.setMass(originalMass * 2f);
                }
            } else {
                // 时间到，恢复原始质量
                if (originalMass != null) {
                    ship.setMass(originalMass);
                }
                Global.getCombatEngine().removePlugin(this);
            }
        }
        
        @Override
        public void renderInWorldCoords(com.fs.starfarer.api.combat.ViewportAPI viewport) {
        }
        
        @Override
        public void renderInUICoords(com.fs.starfarer.api.combat.ViewportAPI viewport) {
        }
        
        @Override
        public void init(CombatEngineAPI engine) {
        }
        
        @Override
        public void processInputPreCoreControls(float amount, java.util.List<com.fs.starfarer.api.input.InputEventAPI> events) {
        }
    }
}
