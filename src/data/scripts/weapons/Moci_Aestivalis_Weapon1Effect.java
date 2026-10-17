package data.scripts.weapons;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_BoxBasedUtil;
import data.scripts.visual.Moci_TipVortexVisual;

/**
 * Aestivalis专用武器效果
 * 基于Moci_Weapon1Effect但移除了碰撞类型修改，添加了强制开盾、穿透停止
 * 以及红金色Travel Drive风格的突进特效
 */
public class Moci_Aestivalis_Weapon1Effect implements EveryFrameWeaponEffectPlugin {
    private boolean init = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (!init) {
            init = true;
            Global.getCombatEngine().addLayeredRenderingPlugin(new listener(weapon));
        }
    }

    private static class listener extends BaseCombatLayeredRenderingPlugin {
        final ShipAPI ship;
        final WeaponAPI weapon;
        Set<Vector2f> mineLoc = new HashSet<>();
        Set<ShipAPI> targets = new HashSet<>();
        Vector2f startPos; // 记录武器启动时的位置

        // 武器状态管理
        boolean isWeaponActive = false;
        final String weaponId;

        // 穿透停止逻辑
        boolean hasHitTarget = false; // 是否已击中目标
        float stopTimer = 0f; // 停止计时器
        private static final float STOP_DURATION = 0.2f; // 停止持续时间

        // 残影效果相关
        private static final float AFTERIMAGE_INTERVAL = 0.06f; // 残影产生间隔(秒)
        private static final float AFTERIMAGE_FADE_IN = 0f;    // 残影淡入时间
        private static final float AFTERIMAGE_FADE_OUT = 0.4f; // 残影淡出时间
        private static final float AFTERIMAGE_ALPHA_START = 0.65f; // 初始透明度
        private static final float AFTERIMAGE_ALPHA_END = 0f;   // 结束透明度
        private float afterimageAccumulator = 0f;

        // Travel Drive同款涡旋特效
        private static final String VORTEX_SPRITE_CATEGORY = "misc";
        private static final String VORTEX_OUTLINE_SPRITE_ID = "Moci_slipstream2";
        private static final String VORTEX_SMOKE_SPRITE_ID = "Moci_slipstream3";

        private static final Color LIGHT_COLOR = new Color(255, 205, 90, 255);

        private static final Color OUTLINE_CENTER_COLOR = new Color(255, 210, 120, 150);
        private static final Color OUTLINE_UNDER_COLOR = new Color(170, 45, 25, 150);
        private static final Color OUTLINE_TIP_COLOR = new Color(255, 235, 180, 155);
        private static final Color OUTLINE_TOP_COLOR = new Color(255, 135, 80, 150);

        private static final Color SMOKE_CENTER_COLOR = new Color(255, 150, 85, 200);
        private static final Color SMOKE_UNDER_COLOR = new Color(130, 20, 10, 180);
        private static final Color SMOKE_TIP_COLOR = new Color(255, 210, 105, 200);
        private static final Color SMOKE_TOP_COLOR = new Color(255, 180, 80, 180);

        private static final float LIGHT_INTENSITY_MIN = 0f;
        private static final float LIGHT_INTENSITY_MAX = 1.8f;
        private static final float LIGHT_SIZE_MIN = 0f;
        private static final float LIGHT_SIZE_MAX = 680f;

        private static final float WIDTH_STANDARD_SPEED = 100f;
        private static final float WIDTH_MIN_MULT = 0.5f;
        private static final float WIDTH_MAX_MULT = 3f;

        private static final float LENGTH_STANDARD_SPEED = 55f;
        private static final float LENGTH_MIN_MULT = 1.3f;
        private static final float LENGTH_MAX_MULT = 1.3f;

        private static final float OUTLINE_START_OFFSET = -30f;
        private static final float OUTLINE_END_OFFSET = -45f;
        private static final float SMOKE_START_OFFSET = -30f;
        private static final float SMOKE_END_OFFSET = -60f;

        private static final float SMOKE_WIDTH_START_MULT = 0.5f;
        private static final float SMOKE_WIDTH_END_MULT = 1.2f;
        private static final float SMOKE_LENGTH_START_MULT = 0.9f;
        private static final float SMOKE_LENGTH_END_MULT = 1.0f;

        private static final float OUTLINE_WIDTH_START_MULT = 0.5f;
        private static final float OUTLINE_WIDTH_END_MULT = 1.2f;
        private static final float OUTLINE_LENGTH_START_MULT = 0.9f;
        private static final float OUTLINE_LENGTH_END_MULT = 1.0f;

        private static final float PARTICLE_SIZE = 90f;
        private static final float PARTICLE_DURATION = 2.2f;
        private static final float PARTICLE_ALPHA = 0.24f;
        private static final float TRAVEL_DRIVE_FADE_DURATION = 0.5f;
        private final IntervalUtil particleInterval = new IntervalUtil(0.07f, 0.09f);

        private Moci_TipVortexVisual visualOutline;
        private Moci_TipVortexVisual visualSmoke;
        private StandardLight light;
        private boolean isTravelDriveFadingOut = false;
        private boolean travelDriveFadeJustStarted = false;
        private float travelDriveFadeTimer = 0f;

        // 缓存激活阶段最后一帧参数，用于结束时做0.2秒缩小渐隐
        private float lastTravelDriveEffectLevel = 0f;
        private float lastTravelDriveBaseSizeMult = 1f;
        private float lastOutlineOffset = OUTLINE_START_OFFSET;
        private float lastOutlineWidthMult = OUTLINE_WIDTH_START_MULT;
        private float lastOutlineLengthMult = OUTLINE_LENGTH_START_MULT;
        private float lastSmokeOffset = SMOKE_START_OFFSET;
        private float lastSmokeWidthMult = SMOKE_WIDTH_START_MULT;
        private float lastSmokeLengthMult = SMOKE_LENGTH_START_MULT;
        private float lastLightIntensity = LIGHT_INTENSITY_MIN;
        private float lastLightSize = LIGHT_SIZE_MIN;

        private listener(WeaponAPI weapon) {
            this.ship = weapon.getShip();
            this.weapon = weapon;
            this.weaponId = ship.getId() + ":" + weapon.getSlot().getId();
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
            this.sprite = Global.getSettings().getSprite("fx", "Moci_engine_partical");
            this.startPos = new Vector2f(ship.getLocation()); // 初始化时记录起始位置
        }

        @Override
        public float getRenderRadius() {
            return Float.MAX_VALUE;
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();

            if (engine == null || engine.isPaused()) {
                return;
            }

            // 飞船停机则移除，但要先恢复状态
            if (!ship.isAlive() || ship.isHulk()) {
                if (isWeaponActive) {
                    restoreShipState();
                }
                cleanupTravelDriveVisuals();
                isExpired = true;
                return;
            }

            // 检查各种可能的中断条件
            boolean shouldInterrupt = false;
            if (isWeaponActive) {
                // 检查舰船是否过载
                if (ship.getFluxTracker().isOverloaded()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否被强制排幅
                else if (ship.getFluxTracker().isVenting()) {
                    shouldInterrupt = true;
                }
                // 检查武器是否被禁用或损坏
                else if (weapon.isDisabled() || weapon.isPermanentlyDisabled()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否被EMP
                else if (ship.getEngineController().isDisabled()) {
                    shouldInterrupt = true;
                }
                // 检查舰船是否处于相位状态
                else if (ship.isPhased()) {
                    shouldInterrupt = true;
                }

                // 如果检测到中断条件，立即恢复状态
                if (shouldInterrupt) {
                    restoreShipState();
                    isWeaponActive = false;
                    hasHitTarget = false;
                    stopTimer = 0f;
                    // 清理速度和加速度修正
                    ship.getMutableStats().getMaxSpeed().unmodify(weaponId);
                    ship.getMutableStats().getAcceleration().unmodify(weaponId);
                    beginTravelDriveFadeOut();
                    // 清空目标记录
                    if (!mineLoc.isEmpty()) {
                        mineLoc.clear();
                        targets.clear();
                    }
                    return;
                }
            }

            // 当武器启动时更新起始位置
            if (weapon.isFiring() && weapon.getChargeLevel() < 0.1f) {
                startPos.set(ship.getLocation());
                hasHitTarget = false; // 重置击中状态
                stopTimer = 0f; // 重置停止计时器
            }

            // 检查是否需要因穿透而停止
            boolean shouldStopDueToPenetration = false;
            if (isWeaponActive && hasHitTarget) {
                stopTimer += amount;
                if (stopTimer >= STOP_DURATION) {
                    shouldStopDueToPenetration = true;
                }
            }

            // 当武器处于开火状态时，但考虑穿透停止逻辑
            if (weapon.isFiring() && weapon.getCooldownRemaining() == 0 && !shouldInterrupt && !shouldStopDueToPenetration) {
                // 如果武器刚开始激活，应用特殊状态
                if (!isWeaponActive) {
                    isWeaponActive = true;
                    // 强制开启护盾
                    if (ship.getShield() != null) {
                        ship.getShield().toggleOn();
                    }
                    // 禁用强制排幅，防止打断武器
                    ship.getMutableStats().getVentRateMult().modifyMult(weaponId, 0f);
                }


                // 舰船加速
                ship.getMutableStats().getMaxSpeed().modifyFlat(weaponId, 700);
                ship.getMutableStats().getAcceleration().modifyFlat(weaponId, 3000);
                // 禁用停止，后退指令
                ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
                ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                // 持续加速
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);

                // 最近的敌人距离小于双方碰撞半径和 视作进入范围，添加炸弹猫点
                ShipAPI nearest = AIUtils.getNearestEnemy(ship);
                if (nearest != null && !targets.contains(nearest)) {
                    float rangeSQ = ship.getCollisionRadius() + nearest.getCollisionRadius();
                    rangeSQ *= rangeSQ;
                    if (Misc.getDistanceSq(nearest.getLocation(), ship.getLocation()) < rangeSQ) {
                        // 纪录位置
                        mineLoc.add(new Vector2f(nearest.getLocation()));
                        targets.add(nearest);
                        hasHitTarget = true; // 标记已击中目标
                    }
                }

                // 残影效果
                if (isWeaponActive) {
                    afterimageAccumulator += amount;
                    if (afterimageAccumulator > AFTERIMAGE_INTERVAL) {
                        createAfterimage();
                        afterimageAccumulator = 0f;
                    }
                }

                updateTravelDriveVisuals(engine, amount);
            } else {

                // 处理爆炸效果（当武器停止开火且有地雷位置时）
                if (isWeaponActive && !mineLoc.isEmpty()) {
                    // 不渲染电弧特效，只播放爆炸
                    // 计算伤害值
                    float dmg = Math.max(
                            weapon.getDamage().getDamage(),
                            weapon.getDamage().getDamage() *
                                    weapon.getSpec().getBurstDuration() *
                                    (weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod() +
                                            weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod()
                                                    / 100f));

                    // 遍历坐标并释放爆炸
                    for (Vector2f loc : mineLoc) {
                        DamagingExplosionSpec spec = new DamagingExplosionSpec(
                                0.1f, // 爆炸持续时间
                                100f, // 伤害半径
                                50, // 核心伤害半径
                                dmg, // 最大伤害
                                dmg * 0.5f, // 最小伤害
                                CollisionClass.MISSILE_NO_FF, // 碰撞类型
                                CollisionClass.MISSILE_NO_FF, // 对战机碰撞类型
                                5, // 粒子尺寸
                                10, // 粒子尺寸随机数
                                1.0f, // 粒子持续时间
                                20, // 粒子数量
                                new Color(26, 125, 255, 255), // 粒子颜色
                                new Color(138, 209, 255, 255) // 爆炸颜色
                        );
                        engine.spawnDamagingExplosion(spec, ship, loc);
                        Global.getSoundPlayer().playSound("Moci_Light_1", 1f, 0.5f, loc, new Vector2f());
                    }
                    mineLoc.clear();
                    targets.clear();
                }

                // 还原状态
                if (isWeaponActive) {
                    restoreShipState();
                    isWeaponActive = false;
                    hasHitTarget = false;
                    stopTimer = 0f;
                    beginTravelDriveFadeOut();
                    // 清理速度和加速度修正
                    ship.getMutableStats().getMaxSpeed().unmodify(weaponId);
                    ship.getMutableStats().getAcceleration().unmodify(weaponId);
                    // 直接设置速度为默认最大速度
                    float defaultMaxSpeed = ship.getMaxSpeed();
                    if (ship.getVelocity().length() > defaultMaxSpeed) {
                        Vector2f velocity = ship.getVelocity();
                        velocity.normalise();
                        velocity.scale(defaultMaxSpeed);
                        ship.getVelocity().set(velocity);
                    }
                }
            }

            if (!isWeaponActive && isTravelDriveFadingOut) {
                if (travelDriveFadeJustStarted) {
                    travelDriveFadeJustStarted = false;
                } else {
                    updateTravelDriveFadeOut(amount);
                }
            }
        }

        // 恢复舰船状态的方法（移除碰撞类型修改）
        private void restoreShipState() {
            // 恢复强制排幅
            ship.getMutableStats().getVentRateMult().unmodify(weaponId);
            // 确保清理所有相关的状态修正
            ship.getMutableStats().getMaxSpeed().unmodify(weaponId);
            ship.getMutableStats().getAcceleration().unmodify(weaponId);

            // 规范化舰船速度
            float defaultMaxSpeed = ship.getMaxSpeed();
            if (ship.getVelocity().length() > defaultMaxSpeed) {
                Vector2f velocity = ship.getVelocity();
                velocity.normalise();
                velocity.scale(defaultMaxSpeed);
                ship.getVelocity().set(velocity);
            }
        }

        private void updateTravelDriveVisuals(CombatEngineAPI engine, float amount) {
            float speedLevel = Math.min(1f, ship.getVelocity().length() / 350f);
            float effectLevel = Math.max(0.35f, Math.max(speedLevel, weapon.getChargeLevel()));
            if (hasHitTarget) {
                effectLevel = 1f;
            }

            isTravelDriveFadingOut = false;
            travelDriveFadeJustStarted = false;
            travelDriveFadeTimer = 0f;

            if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                return;
            }

            ensureTravelDriveVisuals(engine);

            if (visualOutline == null || visualSmoke == null) {
                return;
            }

            visualOutline.setEffectLevel(effectLevel);
            visualSmoke.setEffectLevel(effectLevel);

            float currentSpeed = ship.getVelocity().length();
            float widthSizeMult = Math.max(WIDTH_MIN_MULT, Math.min(WIDTH_MAX_MULT, currentSpeed / WIDTH_STANDARD_SPEED));
            float lengthSizeMult = Math.max(LENGTH_MIN_MULT, Math.min(LENGTH_MAX_MULT, currentSpeed / LENGTH_STANDARD_SPEED));
            float baseSizeMult = Math.max(widthSizeMult, lengthSizeMult);

            visualOutline.setSizeMult(baseSizeMult);
            float outlineOffset = OUTLINE_START_OFFSET + (OUTLINE_END_OFFSET - OUTLINE_START_OFFSET) * effectLevel;
            visualOutline.setOffset(outlineOffset);

            float outlineWidthMult = OUTLINE_WIDTH_START_MULT
                    + (OUTLINE_WIDTH_END_MULT - OUTLINE_WIDTH_START_MULT) * effectLevel;
            float outlineLengthMult = OUTLINE_LENGTH_START_MULT
                    + (OUTLINE_LENGTH_END_MULT - OUTLINE_LENGTH_START_MULT) * effectLevel;
            visualOutline.setWidthMult((widthSizeMult / baseSizeMult) * outlineWidthMult);
            visualOutline.setLengthMult((lengthSizeMult / baseSizeMult) * outlineLengthMult);

            visualSmoke.setSizeMult(baseSizeMult);
            float smokeOffset = SMOKE_START_OFFSET + (SMOKE_END_OFFSET - SMOKE_START_OFFSET) * effectLevel;
            visualSmoke.setOffset(smokeOffset);

            float smokeWidthMult = SMOKE_WIDTH_START_MULT + (SMOKE_WIDTH_END_MULT - SMOKE_WIDTH_START_MULT) * effectLevel;
            float smokeLengthMult = SMOKE_LENGTH_START_MULT + (SMOKE_LENGTH_END_MULT - SMOKE_LENGTH_START_MULT) * effectLevel;
            visualSmoke.setWidthMult((widthSizeMult / baseSizeMult) * smokeWidthMult);
            visualSmoke.setLengthMult((lengthSizeMult / baseSizeMult) * smokeLengthMult);

            lastTravelDriveEffectLevel = effectLevel;
            lastTravelDriveBaseSizeMult = baseSizeMult;
            lastOutlineOffset = outlineOffset;
            lastOutlineWidthMult = (widthSizeMult / baseSizeMult) * outlineWidthMult;
            lastOutlineLengthMult = (lengthSizeMult / baseSizeMult) * outlineLengthMult;
            lastSmokeOffset = smokeOffset;
            lastSmokeWidthMult = (widthSizeMult / baseSizeMult) * smokeWidthMult;
            lastSmokeLengthMult = (lengthSizeMult / baseSizeMult) * smokeLengthMult;
            lastLightIntensity = LIGHT_INTENSITY_MIN + (LIGHT_INTENSITY_MAX - LIGHT_INTENSITY_MIN) * effectLevel;
            lastLightSize = LIGHT_SIZE_MIN + (LIGHT_SIZE_MAX - LIGHT_SIZE_MIN) * effectLevel;

            particleInterval.advance(amount);
            if (particleInterval.intervalElapsed()) {
                spawnTravelDriveParticles(effectLevel);
            }

            if (light != null) {
                light.setColor(LIGHT_COLOR);
                light.setIntensity(lastLightIntensity);
                light.setSize(lastLightSize);
                light.setLocation(ship.getLocation());
            }
        }

        private void beginTravelDriveFadeOut() {
            if (visualOutline == null && visualSmoke == null && light == null) {
                cleanupTravelDriveVisuals();
                return;
            }
            if (lastTravelDriveEffectLevel <= 0f) {
                cleanupTravelDriveVisuals();
                return;
            }

            isTravelDriveFadingOut = true;
            travelDriveFadeJustStarted = true;
            travelDriveFadeTimer = 0f;
        }

        private void updateTravelDriveFadeOut(float amount) {
            if (visualOutline == null || visualSmoke == null) {
                cleanupTravelDriveVisuals();
                return;
            }

            travelDriveFadeTimer += amount;
            float progress = Math.min(1f, travelDriveFadeTimer / TRAVEL_DRIVE_FADE_DURATION);
            float fadeLevel = 1f - progress;

            visualOutline.setEffectLevel(lastTravelDriveEffectLevel * fadeLevel);
            visualSmoke.setEffectLevel(lastTravelDriveEffectLevel * fadeLevel);

            visualOutline.setSizeMult(lastTravelDriveBaseSizeMult);
            visualOutline.setOffset(lastOutlineOffset * fadeLevel);
            visualOutline.setWidthMult(lastOutlineWidthMult * fadeLevel);
            visualOutline.setLengthMult(lastOutlineLengthMult * fadeLevel);

            visualSmoke.setSizeMult(lastTravelDriveBaseSizeMult);
            visualSmoke.setOffset(lastSmokeOffset * fadeLevel);
            visualSmoke.setWidthMult(lastSmokeWidthMult * fadeLevel);
            visualSmoke.setLengthMult(lastSmokeLengthMult * fadeLevel);

            if (light != null) {
                light.setColor(LIGHT_COLOR);
                light.setIntensity(lastLightIntensity * fadeLevel);
                light.setSize(lastLightSize * fadeLevel);
                light.setLocation(ship.getLocation());
            }

            if (progress >= 1f) {
                cleanupTravelDriveVisuals();
            }
        }

        private void ensureTravelDriveVisuals(CombatEngineAPI engine) {
            if (visualOutline != null && visualSmoke != null) {
                return;
            }

            SpriteAPI outlineSprite = Global.getSettings().getSprite(VORTEX_SPRITE_CATEGORY, VORTEX_OUTLINE_SPRITE_ID);
            SpriteAPI smokeSprite = Global.getSettings().getSprite(VORTEX_SPRITE_CATEGORY, VORTEX_SMOKE_SPRITE_ID);

            visualOutline = new Moci_TipVortexVisual(ship, outlineSprite, outlineSprite, outlineSprite);
            visualOutline.setCenterColor(OUTLINE_CENTER_COLOR);
            visualOutline.setUnderColor(OUTLINE_UNDER_COLOR);
            visualOutline.setTipColor(OUTLINE_TIP_COLOR);
            visualOutline.setTopColor(OUTLINE_TOP_COLOR);
            engine.addLayeredRenderingPlugin(visualOutline);

            visualSmoke = new Moci_TipVortexVisual(ship, smokeSprite, outlineSprite, smokeSprite);
            visualSmoke.setCenterColor(SMOKE_CENTER_COLOR);
            visualSmoke.setUnderColor(SMOKE_UNDER_COLOR);
            visualSmoke.setTipColor(SMOKE_TIP_COLOR);
            visualSmoke.setTopColor(SMOKE_TOP_COLOR);
            engine.addLayeredRenderingPlugin(visualSmoke);

            if (light == null) {
                try {
                    light = new StandardLight(ship.getLocation(), new Vector2f(), new Vector2f(), ship);
                    light.setColor(LIGHT_COLOR);
                    light.setIntensity(LIGHT_INTENSITY_MIN);
                    light.setSize(LIGHT_SIZE_MIN);
                    light.makePermanent();
                    LightShader.addLight(light);
                } catch (Exception ex) {
                    light = null;
                }
            }
        }

        private void spawnTravelDriveParticles(float effectLevel) {
            Color particleColor = hasHitTarget ? OUTLINE_CENTER_COLOR : SMOKE_TOP_COLOR;
            Vector2f particleLoc = MathUtils.getPoint(ship.getLocation(), ship.getCollisionRadius() * 0.45f, ship.getFacing() + 180f);
            Vector2f particleVel = MathUtils.getPoint(new Vector2f(), ship.getVelocity().length() * 0.18f + 35f, ship.getFacing() + 180f);
            particleVel.x += ship.getVelocity().x;
            particleVel.y += ship.getVelocity().y;

            try {
                Moci_BoxBasedUtil.addNebulaSmoothParticle(
                        particleLoc,
                        particleVel,
                        PARTICLE_SIZE,
                        2.8f,
                        0.5f,
                        effectLevel * PARTICLE_ALPHA,
                        PARTICLE_DURATION,
                        particleColor);
            } catch (NoClassDefFoundError ex) {
                // BoxUtil被移除时静默跳过，避免影响武器逻辑
            }
        }

        private void cleanupTravelDriveVisuals() {
            isTravelDriveFadingOut = false;
            travelDriveFadeJustStarted = false;
            travelDriveFadeTimer = 0f;
            lastTravelDriveEffectLevel = 0f;

            if (visualOutline != null) {
                visualOutline.setEffectLevel(0f);
                visualOutline.setValid(false);
                visualOutline = null;
            }
            if (visualSmoke != null) {
                visualSmoke.setEffectLevel(0f);
                visualSmoke.setValid(false);
                visualSmoke = null;
            }
            if (light != null) {
                LightShader.removeLight(light);
                light = null;
            }
        }

        // 创建残影效果
        private void createAfterimage() {
            // 获取偏移量
            SpriteAPI shipSprite = ship.getSpriteAPI();
            float offsetX = shipSprite.getWidth()/2 - shipSprite.getCenterX();
            float offsetY = shipSprite.getHeight()/2 - shipSprite.getCenterY();

            // 计算旋转后的偏移量
            float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
                    (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
            float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
                    (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;

            // 为舰船创建残影
            SpriteAPI shipSpriteTexture = Global.getSettings().getSprite(ship.getHullSpec().getSpriteName());
            shipSpriteTexture.setSize(ship.getSpriteAPI().getWidth(), ship.getSpriteAPI().getHeight());

            MagicRender.battlespace(
                    shipSpriteTexture,
                    new Vector2f(ship.getLocation().x + trueOffsetX, ship.getLocation().y + trueOffsetY),
                    new Vector2f(0, 0),
                    new Vector2f(ship.getSpriteAPI().getWidth(), ship.getSpriteAPI().getHeight()),
                    new Vector2f(0, 0),
                    ship.getFacing()-90f,
                    0f,
                    new Color(150, 200, 255, (int)(AFTERIMAGE_ALPHA_START * 255)),
                    true,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    AFTERIMAGE_FADE_IN,
                    AFTERIMAGE_FADE_OUT,
                    AFTERIMAGE_ALPHA_END,
                    CombatEngineLayers.BELOW_SHIPS_LAYER
            );

            // 为所有武器创建残影
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getSprite() == null) continue;

                // 检查武器是否被设置为完全透明
                boolean isHidden = false;
                if (w.getSprite() != null && w.getSprite().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (w.getBarrelSpriteAPI() != null && w.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (w.getUnderSpriteAPI() != null && w.getUnderSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }
                if (w.getGlowSpriteAPI() != null && w.getGlowSpriteAPI().getColor().getAlpha() == 0) {
                    isHidden = true;
                }

                if (isHidden) continue;

                // 获取武器贴图路径
                String spritePath;
                if (w.getAnimation() != null) {
                    // 对于动画武器，获取当前帧的贴图路径
                    int frame = w.getAnimation().getFrame();
                    String basePath = w.getSlot().isTurret() ?
                            w.getSpec().getTurretSpriteName() :
                            w.getSpec().getHardpointSpriteName();
                    // 移除.png后缀
                    if (basePath.endsWith(".png")) {
                        basePath = basePath.substring(0, basePath.length() - 7);
                    }
                    // 添加帧号和后缀
                    spritePath = String.format("%s_%02d.png", basePath, frame);
                } else {
                    // 无动画的武器使用默认贴图
                    spritePath = w.getSlot().isTurret() ?
                            w.getSpec().getTurretSpriteName() :
                            w.getSpec().getHardpointSpriteName();
                }

                // 获取新的Sprite实例
                SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
                weaponSprite.setSize(w.getSprite().getWidth(), w.getSprite().getHeight());

                // 创建残影
                MagicRender.battlespace(
                        weaponSprite,
                        new Vector2f(w.getLocation().x + trueOffsetX, w.getLocation().y + trueOffsetY),
                        new Vector2f(0, 0),
                        new Vector2f(w.getSprite().getWidth(), w.getSprite().getHeight()),
                        new Vector2f(0, 0),
                        w.getCurrAngle()-90f,
                        0f,
                        new Color(150, 200, 255, (int)(AFTERIMAGE_ALPHA_START * 255)),
                        true,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        AFTERIMAGE_FADE_IN,
                        AFTERIMAGE_FADE_OUT,
                        AFTERIMAGE_ALPHA_END,
                        CombatEngineLayers.BELOW_SHIPS_LAYER
                );
            }
        }

        private final SpriteAPI sprite;

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (Global.getCombatEngine().isPaused()) {
                return;
            }

            if (!mineLoc.isEmpty()) {
                for (Vector2f loc : mineLoc) {
                    sprite.renderAtCenter(loc.x, loc.y);
                }
            }
        }

        private boolean isExpired = false;

        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }
}
