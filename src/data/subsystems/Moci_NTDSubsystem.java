package data.subsystems;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.boxutil.config.BoxConfigs;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.util.TransformUtil;
import org.lwjgl.util.vector.Matrix2f;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.MagicSubsystem;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_MobileSuitRepairTracker;
import data.scripts.util.Moci_TextLoader;

/**
 * NTD模式子系统 - 觉醒变形系统
 * 
 * 控制机体的觉醒变形动画和武器发光效果：
 * - effectLevel 0.0-0.8: 变形动画（0-6帧，共7帧）
 * - effectLevel 0.8-1.0: 完全觉醒状态
 * - effectLevel 1.0: 武器发光效果
 * - effectLevel 1.0-0.2: 变形还原（6-0帧）
 */
public class Moci_NTDSubsystem extends MagicSubsystem {
    private static final Logger log = Global.getLogger(Moci_NTDSubsystem.class);
    private static final String TEXT_ID = "Moci_NTDSubsystem";
    
    // ========== 效果参数 ==========
    private static final float DEFAULT_SPEED_BONUS = 50f; // 默认速度提升（平直）
    private static final float DEFAULT_ACCEL_BONUS = 50f; // 默认加速度提升（百分比）
    private static final float DEFAULT_DECEL_BONUS = 50f; // 默认减速度提升（百分比）
    private static final float DEFAULT_TURN_ACCEL_BONUS = 50f; // 默认转向加速度提升（百分比）
    private static final float DEFAULT_MAX_TURN_RATE_FLAT_BONUS = 15f; // 默认最大转向速度平直加成
    private static final float DEFAULT_MAX_TURN_RATE_PERCENT_BONUS = 100f; // 默认最大转向速度百分比加成
    private static final float DEFAULT_TIME_MULT = 2f; // 默认时流倍率（舰船自身）
    private static final float DEFAULT_HARD_FLUX_DISSIPATION_FRACTION = 0.5f; // 默认硬幅耗散比例
    
    private float maxSpeedBonus = DEFAULT_SPEED_BONUS; // 最大速度提升
    private float maxAccelerationBonus = DEFAULT_ACCEL_BONUS; // 最大加速度提升
    private float maxDecelerationBonus = DEFAULT_DECEL_BONUS; // 最大减速度提升
    private float maxTurnAccelerationBonus = DEFAULT_TURN_ACCEL_BONUS; // 最大转向加速度提升
    
    // ========== 充能系统参数 ==========
    private int maxCharges = 1; // 最大充能数
    private float chargeRegenTime = 50f; // 充能恢复时间（秒）
    
    // ========== 时间参数 ==========
    private float inDuration = 1.0f; // 启动时间（变形动画）
    private float activeDuration = 20.0f; // 激活持续时间
    private float outDuration = 1.0f; // 结束时间（还原动画）
    
    // ========== 幅能消耗参数 ==========
    private float fluxPerSecond = 50f; // 每秒幅能消耗
    private float activationFluxFlat = 500f; // 激活时固定幅能消耗
    private boolean isHardFlux = false; // 是否为硬通量
    
    // ========== 其他参数 ==========
    private boolean canUseWhileOverloaded = false;
    private boolean canUseWhileVenting = false;
    private boolean usesChargesOnActivate = true;
    private boolean startWithNoCharges = false; // 开局是否清空充能
    
    // ========== 内部状态 ==========
    private String effectId = "moci_ntd_subsystem";
    private boolean isAwakened = false; // 是否已觉醒（用于控制腿部动画切换）
    
    // 武器发光效果参数
    private static final Color DEFAULT_GLOW_COLOR = new Color(255, 0, 0, 255);
    private static final Color RX_0_2_GLOW_COLOR = new Color(238, 155, 25, 255);
    private static final Color RX_0_3_GLOW_COLOR = new Color(29, 126, 237, 255);
    // BoxUtil这里使用的是半尺寸，直接传原图宽高会放大一倍。
    private static final float GLOW_SPRITE_SIZE_SCALE = 0.5f;
    // Starsector舰船/武器默认朝向Y轴，Box贴图这里需要额外减去90度。
    private static final float GLOW_SPRITE_ANGLE_OFFSET = -90f;
    // 基础贴图透明度。
    private static final float GLOW_SPRITE_COLOR_ALPHA = 1.0f;
    // 自发光贴图透明度。
    private static final float GLOW_SPRITE_EMISSIVE_ALPHA = 1.0f;
    
    // 粒子喷射效果参数
    private static final int PARTICLE_COUNT = 120; // 粒子数量
    private static final float PARTICLE_SPAWN_RADIUS = 35f; // 粒子起始位置随机半径
    private static final float PARTICLE_SPEED_MIN = 150f; // 最小速度
    private static final float PARTICLE_SPEED_MAX = 300f; // 最大速度
    private static final float PARTICLE_SIZE_MIN = 8f; // 最小大小
    private static final float PARTICLE_SIZE_MAX = 12f; // 最大大小
    private static final float PARTICLE_DURATION_MIN = 0.4f; // 最小持续时间
    private static final float PARTICLE_DURATION_MAX = 0.6f; // 最大持续时间
    
    // 粒子喷射状态标记
    private boolean hasSpawnedParticles = false;
    
    // 武器发光默认使用 BoxUtil 渲染；若渲染不可用，则自动回退到装饰武器开火。
    
    // 需要渲染glow的装饰武器槽位
    private static final String[] GLOW_WEAPON_SLOTS = {
        "ARM_L", "ARM_R", "BODY", "HEAD", 
        "SHOULDER_L", "SHOULDER_R", "BACKPACK", "SHIELD_L_COVER"
    };
    
    // BoxUtil渲染实体缓存
    private final Map<String, SpriteEntity> glowSpriteEntities = new HashMap<>();
    
    public Moci_NTDSubsystem(ShipAPI ship) {
        super(ship);
        // 使用默认参数，不再区分船体级别
    }
    
    /**
     * 获取描述参数（供船插使用）
     */
    public static String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0: // 速度加成
                return "" + (int) DEFAULT_SPEED_BONUS;
            case 1: // 加速度加成
                return "" + (int) DEFAULT_ACCEL_BONUS;
            case 2: // 持续时间
                return "20";
            case 3: // 充能恢复时间
                return "30";
        }
        return null;
    }
    
    // ========== 基础系统参数 ==========
    
    @Override
    public int getOrder() {
        return ORDER_SHIP_MODULAR;
    }
    
    @Override
    public float getBaseInDuration() {
        return inDuration;
    }
    
    @Override
    public float getBaseActiveDuration() {
        return activeDuration;
    }
    
    @Override
    public float getBaseOutDuration() {
        return outDuration;
    }
    
    @Override
    public float getBaseCooldownDuration() {
        return 0f; // 使用充能系统
    }
    
    @Override
    public boolean isToggle() {
        return false;
    }
    
    @Override
    public boolean canAssignKey() {
        return true;
    }

    @Override
    protected void init() {
        super.init();

        if (startWithNoCharges) {
            charges = 0;
            if (chargeInterval != null) {
                chargeInterval.setElapsed(0f);
            }
        }
    }
    
    // ========== 充能系统参数 ==========
    
    @Override
    public int getMaxCharges() {
        return maxCharges;
    }
    
    @Override
    public float getBaseChargeRechargeDuration() {
        return chargeRegenTime;
    }
    
    @Override
    public boolean usesChargesOnActivate() {
        return usesChargesOnActivate;
    }
    
    // ========== 幅能消耗参数 ==========
    
    @Override
    public float getFluxCostFlatOnActivation() {
        return activationFluxFlat;
    }
    
    @Override
    public float getFluxCostPercentOnActivation() {
        return 0f;
    }
    
    @Override
    public boolean isHardFluxForActivation() {
        return isHardFlux;
    }
    
    @Override
    public float getFluxCostFlatPerSecondWhileActive() {
        return fluxPerSecond;
    }
    
    @Override
    public float getFluxCostPercentPerSecondWhileActive() {
        return 0f;
    }
    
    @Override
    public boolean isHardFluxPerSecondWhileActive() {
        return isHardFlux;
    }
    
    // ========== 其他系统参数 ==========
    
    @Override
    public boolean canUseWhileOverloaded() {
        return canUseWhileOverloaded;
    }
    
    @Override
    public boolean canUseWhileVenting() {
        return canUseWhileVenting;
    }
    
    @Override
    public float getSystemStatsEffectMult() {
        return 1f;
    }
    
    @Override
    public boolean canActivate() {
        if (!ship.isAlive() || ship.isHulk() || isBlockedDuringRepair()) {
            return false;
        }
        
        if (ship.getFluxTracker().isOverloaded() || ship.getFluxTracker().isVenting()) {
            return false;
        }
        
        return true;
    }
    
    @Override
    public boolean shouldActivateAI(float amount) {
        if (!canActivate()) {
            return false;
        }
        
        // AI在战斗激烈时激活
        ShipAPI target = ship.getShipTarget();
        if (target != null) {
            float distance = Vector2f.sub(target.getLocation(), ship.getLocation(), null).length();
            // 中距离战斗时激活
            if (distance > 500f && distance < 1500f) {
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public void onActivate() {
        isAwakened = false; // 重置觉醒状态
        hasSpawnedParticles = false; // 重置粒子喷射标记
        Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1f, 1f,
                ship.getLocation(), ship.getVelocity());
    }
    
    @Override
    public void onFinished() {
        removeAllEffects();
        isAwakened = false;
        hasSpawnedParticles = false;
    }
    
    @Override
    public void onShipDeath() {
        removeAllEffects();
        isAwakened = false;
        hasSpawnedParticles = false;
    }
    
    @Override
    public void advance(float amount, boolean isPaused) {
        if (isPaused) return;

        if (isBlockedDuringRepair()) {
            forceStopForRepair();
            return;
        }
        
        applyDynamicEffects();
        updateAnimations();
        applyWeaponGlow();
    }
    
    /**
     * 动态应用效果
     */
    private void applyDynamicEffects() {
        float effectLevel = getEffectLevel();
        
        if (effectLevel > 0f) {
            float currentSpeedBonus = maxSpeedBonus * effectLevel;
            float currentAccelBonus = maxAccelerationBonus * effectLevel;
            float currentDecelBonus = maxDecelerationBonus * effectLevel;
            float currentTurnAccelBonus = maxTurnAccelerationBonus * effectLevel;
            float currentMaxTurnRateFlatBonus = DEFAULT_MAX_TURN_RATE_FLAT_BONUS * effectLevel;
            float currentMaxTurnRatePercentBonus = DEFAULT_MAX_TURN_RATE_PERCENT_BONUS * effectLevel;
            float currentHardFluxDissipationFraction = DEFAULT_HARD_FLUX_DISSIPATION_FRACTION * effectLevel;
            float currentTimeMult = 1f + (DEFAULT_TIME_MULT - 1f) * effectLevel;
            
            ship.getMutableStats().getMaxSpeed().modifyFlat(effectId, currentSpeedBonus);
            ship.getMutableStats().getAcceleration().modifyPercent(effectId, currentAccelBonus);
            ship.getMutableStats().getDeceleration().modifyPercent(effectId, currentDecelBonus);
            ship.getMutableStats().getTurnAcceleration().modifyPercent(effectId, currentTurnAccelBonus);
            ship.getMutableStats().getMaxTurnRate().modifyFlat(effectId, currentMaxTurnRateFlatBonus);
            ship.getMutableStats().getMaxTurnRate().modifyPercent(effectId, currentMaxTurnRatePercentBonus);
            ship.getMutableStats().getTimeMult().modifyMult(effectId, currentTimeMult);
            ship.getMutableStats().getHardFluxDissipationFraction().modifyFlat(effectId, currentHardFluxDissipationFraction);

            applyTimeFlow(currentTimeMult);
        } else {
            removeAllEffects();
        }
    }

    /**
     * 参考 TemporalShellStats：舰船自身加速，玩家舰同时压低全局时流，
     * 这样能获得相对于战场的 2 倍时间流速。
     */
    private void applyTimeFlow(float shipTimeMult) {
        if (Global.getCombatEngine() == null) {
            return;
        }

        String timeMultId = getTimeMultId();
        if (ship == Global.getCombatEngine().getPlayerShip()) {
            Global.getCombatEngine().getTimeMult().modifyMult(timeMultId, 1f / shipTimeMult);
        } else {
            Global.getCombatEngine().getTimeMult().unmodify(timeMultId);
        }
    }

    private String getTimeMultId() {
        return effectId + "_" + ship.getId();
    }
    
    /**
     * 更新动画帧
     */
    private void updateAnimations() {
        float effectLevel = getEffectLevel();
        
        // 计算动画帧
        int animFrame = 0;
        boolean shouldBeAwakened = false;
        
        if (state == State.IN) {
            // 启动阶段：0.0-0.8 播放 0-6 帧（共7帧）
            float animProgress = Math.min(effectLevel / 0.8f, 1.0f);
            animFrame = Math.round(animProgress * 6);
            
            // 当动画播放到第6帧时，标记为已觉醒
            if (animFrame >= 6) {
                shouldBeAwakened = true;
            }
        } else if (state == State.ACTIVE) {
            // 激活阶段：保持第6帧
            animFrame = 6;
            shouldBeAwakened = true;
        } else if (state == State.OUT) {
            // 结束阶段：1.0-0.2 播放 6-0 帧
            float animProgress = Math.max((effectLevel - 0.2f) / 0.8f, 0.0f);
            animFrame = Math.round(animProgress * 6);
            
            // 当动画还原到第0帧时，标记为未觉醒
            if (animFrame <= 0) {
                shouldBeAwakened = false;
            }
        }
        
        // 更新觉醒状态
        if (shouldBeAwakened && !isAwakened) {
            isAwakened = true;
            
            // 在觉醒完成的瞬间喷射粒子
            if (!hasSpawnedParticles) {
                spawnAwakenParticles();
                hasSpawnedParticles = true;
            }
        } else if (!shouldBeAwakened && isAwakened) {
            isAwakened = false;
        }
        
        // 更新所有部位的动画
        updateWeaponAnimation("ARM_L", animFrame);
        updateWeaponAnimation("ARM_R", animFrame);
        updateWeaponAnimation("BODY", animFrame);
        updateWeaponAnimation("HEAD", animFrame);
        updateWeaponAnimation("SHOULDER_L", animFrame);
        updateWeaponAnimation("SHOULDER_R", animFrame);
        updateWeaponAnimation("BACKPACK", animFrame);
        // SHIELD_L_COVER 由 Moci_MechProjDecoWeaponEffect_shield_arm_RX0 独立控制：
        // 护盾存活时响应NTD变形（0-6帧），护盾死亡时固定第7帧。
        // 此处不再覆盖，否则护盾死亡后帧7会被NTD每帧重置为0。

        // 腿部动画由 Moci_MechFullBodyAnimationController_RX0 控制
        // 这里只需要通知觉醒状态
    }
    
    /**
     * 应用武器发光效果
     */
    private void applyWeaponGlow() {
        // 与变形完成状态同步：updateAnimations() 会先更新 isAwakened，再决定本帧是否显示 glow。
        if (isAwakened) {
            if (!renderWeaponGlowSprites()) {
                cleanupGlowSpriteEntities();
                forceWeaponsFire();
            }
        } else {
            // 不在激活状态，清理所有渲染实体
            if (!glowSpriteEntities.isEmpty()) {
                cleanupGlowSpriteEntities();
            }
        }
    }
    
    /**
     * 强制指定槽位的装饰武器开火（作为Box渲染失败时的兜底）
     */
    private void forceWeaponsFire() {
        for (String slotId : GLOW_WEAPON_SLOTS) {
            WeaponAPI weapon = getWeaponInSlot(slotId);
            if (weapon != null && weapon.isDecorative()) {
                // 强制武器开火
                weapon.setForceFireOneFrame(true);
            }
        }
    }
    
    /**
     * 手动渲染武器glow贴图
     *
     * @return true 表示本帧Box渲染成功，false 表示需要回退到武器开火
     */
    private boolean renderWeaponGlowSprites() {
        if (!BoxConfigs.isShaderEnable()) {
            return false;
        }
        
        for (String slotId : GLOW_WEAPON_SLOTS) {
            WeaponAPI weapon = getWeaponInSlot(slotId);
            if (weapon == null || !weapon.isDecorative()) {
                cleanupGlowSpriteEntity(slotId);
                continue;
            }
            
            // 检查武器是否有glow贴图
            if (weapon.getGlowSpriteAPI() == null) {
                cleanupGlowSpriteEntity(slotId);
                return false;
            }
            
            try {
                // 获取或创建渲染实体
                SpriteEntity spriteEntity = glowSpriteEntities.get(slotId);
                if (spriteEntity == null || spriteEntity.hasDelete()) {
                    spriteEntity = createGlowSpriteEntity(weapon);
                    glowSpriteEntities.put(slotId, spriteEntity);
                }
                
                // 每帧更新位置和旋转
                updateGlowSpriteTransform(spriteEntity, weapon);
            } catch (Exception ex) {
                cleanupGlowSpriteEntity(slotId);
                log.warn("NTD模式：Box glow渲染失败，回退到武器开火，槽位=" + slotId, ex);
                return false;
            }
        }

        return true;
    }
    
    /**
     * 创建glow精灵渲染实体
     */
    private SpriteEntity createGlowSpriteEntity(WeaponAPI weapon) {
        // 使用武器的glow贴图
        SpriteAPI glowSprite = weapon.getGlowSpriteAPI();
        
        // 使用SpriteAPI创建SpriteEntity
        SpriteEntity sprite = new SpriteEntity(glowSprite);
        
        // 设置基础尺寸（使用glow贴图的实际尺寸）
        float width = glowSprite.getWidth() * GLOW_SPRITE_SIZE_SCALE;
        float height = glowSprite.getHeight() * GLOW_SPRITE_SIZE_SCALE;
        sprite.setBaseSizePerTiles(width, height);
        
        // 设置加法混合模式（发光效果）
        sprite.setAdditiveBlend();
        
        // 设置自发光贴图（使用相同的glow贴图）
        sprite.setEmissiveSprite(glowSprite);
        
        Color glowColor = getGlowColor();
        // 使用船体对应的NTD颜色对贴图进行染色。
        float r = glowColor.getRed() / 255f;
        float g = glowColor.getGreen() / 255f;
        float b = glowColor.getBlue() / 255f;
        
        // 基础颜色（漫反射）
        sprite.getMaterialData().setColor(r, g, b, GLOW_SPRITE_COLOR_ALPHA);
        sprite.getMaterialData().setColorAlpha(GLOW_SPRITE_COLOR_ALPHA);

        // 自发光颜色（更亮）
        sprite.getMaterialData().setEmissiveColor(r * 2.0f, g * 2.0f, b * 2.0f, GLOW_SPRITE_EMISSIVE_ALPHA);
        sprite.getMaterialData().setEmissiveColorAlpha(GLOW_SPRITE_EMISSIVE_ALPHA);
        
        // 发光增强设置
        sprite.getMaterialData().setGlowPower(1.0f);
        sprite.getMaterialData().setColorToEmissive(1.0f);
        sprite.getMaterialData().setAlphaToEmissive(0.0f);
        sprite.getMaterialData().setAdditionEmissive(true);
        sprite.getMaterialData().setIgnoreIllumination(true);
        
        // 设置渲染层级（在船体上方）
        sprite.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER);
        
        // 设置定时器（无淡入淡出，持续显示）
        sprite.setGlobalTimer(0.0f, 9999.0f, 0.0f);
        
        // 添加到渲染管理器
        CombatRenderingManager.addEntity(BoxEnum.ENTITY_SPRITE, sprite);

        return sprite;
    }
    
    /**
     * 更新glow精灵的位置和旋转
     */
    private void updateGlowSpriteTransform(SpriteEntity sprite, WeaponAPI weapon) {
        // 获取武器的世界坐标位置
        Vector2f weaponLoc = weapon.getLocation();
        sprite.setLocation(weaponLoc.x, weaponLoc.y);
        
        // Starsector的美术资源默认沿Y轴朝向，Box渲染这里补一个-90度偏移。
        float facing = Misc.normalizeAngle(weapon.getCurrAngle() + GLOW_SPRITE_ANGLE_OFFSET);
        Matrix2f transform = new Matrix2f();
        TransformUtil.createSimpleRotateMatrix(facing, transform);
        
        sprite.getModelMatrix().m00 = transform.m00;
        sprite.getModelMatrix().m01 = transform.m01;
        sprite.getModelMatrix().m10 = transform.m10;
        sprite.getModelMatrix().m11 = transform.m11;
        
        // 同步位置到模型矩阵
        sprite.getModelMatrix().m30 = weaponLoc.x;
        sprite.getModelMatrix().m31 = weaponLoc.y;
    }
    
    /**
     * 获取指定槽位的武器
     */
    private WeaponAPI getWeaponInSlot(String slotId) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && slotId.equals(weapon.getSlot().getId())) {
                return weapon;
            }
        }
        return null;
    }
    
    /**
     * 更新指定槽位武器的动画帧
     */
    private void updateWeaponAnimation(String slotId, int frame) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() != null && slotId.equals(w.getSlot().getId())) {
                if (w.getAnimation() != null) {
                    int maxFrame = w.getAnimation().getNumFrames() - 1;
                    int targetFrame = Math.min(frame, maxFrame);
                    w.getAnimation().setFrame(targetFrame);
                }
                break;
            }
        }
    }
    
    /**
     * 喷射觉醒粒子效果
     */
    private void spawnAwakenParticles() {
        Vector2f shipLoc = ship.getLocation();
        
        // 360度均匀分布喷射粒子
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float angle = (360f / PARTICLE_COUNT) * i;
            float angleRad = (float) Math.toRadians(angle);
            
            // 随机起始位置偏移（0到35范围内）
            float spawnRadius = (float) Math.random() * PARTICLE_SPAWN_RADIUS;
            float spawnAngle = (float) Math.random() * 360f;
            float spawnAngleRad = (float) Math.toRadians(spawnAngle);
            
            // 计算粒子起始位置
            Vector2f particleLoc = new Vector2f(
                shipLoc.x + (float) Math.cos(spawnAngleRad) * spawnRadius,
                shipLoc.y + (float) Math.sin(spawnAngleRad) * spawnRadius
            );
            
            // 随机速度
            float speed = PARTICLE_SPEED_MIN + 
                         (float) Math.random() * (PARTICLE_SPEED_MAX - PARTICLE_SPEED_MIN);
            
            // 计算速度向量
            Vector2f velocity = new Vector2f(
                (float) Math.cos(angleRad) * speed,
                (float) Math.sin(angleRad) * speed
            );
            
            // 随机大小
            float size = PARTICLE_SIZE_MIN + 
                        (float) Math.random() * (PARTICLE_SIZE_MAX - PARTICLE_SIZE_MIN);
            
            // 随机持续时间
            float duration = PARTICLE_DURATION_MIN + 
                           (float) Math.random() * (PARTICLE_DURATION_MAX - PARTICLE_DURATION_MIN);
            
            // 使用原版API添加粒子（与Moci_MobilitySubsystem相同）
            Global.getCombatEngine().addHitParticle(
                particleLoc,
                velocity,
                size,
                1.0f, // 亮度
                duration,
                getGlowColor()
            );
        }
        
    }

    private Color getGlowColor() {
        if (ship != null && ship.getHullSpec() != null) {
            if (isHullIdMatch("Moci_RX_0_2")) {
                return RX_0_2_GLOW_COLOR;
            }
            if (isHullIdMatch("Moci_RX_0_3")) {
                return RX_0_3_GLOW_COLOR;
            }
        }
        return DEFAULT_GLOW_COLOR;
    }

    private boolean isHullIdMatch(String targetHullId) {
        return matchesHullId(ship.getHullSpec().getDParentHullId(), targetHullId)
                || matchesHullId(ship.getHullSpec().getBaseHullId(), targetHullId)
                || matchesHullId(ship.getHullSpec().getHullId(), targetHullId);
    }

    private boolean matchesHullId(String hullId, String targetHullId) {
        if (hullId == null || targetHullId == null) {
            return false;
        }
        if (targetHullId.equals(hullId)) {
            return true;
        }
        return hullId.startsWith(targetHullId + "_");
    }
    
    /**
     * 移除所有效果
     */
    private void removeAllEffects() {
        ship.getMutableStats().getMaxSpeed().unmodify(effectId);
        ship.getMutableStats().getAcceleration().unmodify(effectId);
        ship.getMutableStats().getDeceleration().unmodify(effectId);
        ship.getMutableStats().getTurnAcceleration().unmodify(effectId);
        ship.getMutableStats().getMaxTurnRate().unmodify(effectId);
        ship.getMutableStats().getTimeMult().unmodify(effectId);
        ship.getMutableStats().getHardFluxDissipationFraction().unmodify(effectId);

        if (Global.getCombatEngine() != null) {
            Global.getCombatEngine().getTimeMult().unmodify(getTimeMultId());
        }
        
        // 清理BoxUtil渲染实体
        cleanupGlowSpriteEntities();
    }

    private boolean isBlockedDuringRepair() {
        return Moci_MobileSuitRepairTracker.isShipRepairing(ship);
    }

    private void forceStopForRepair() {
        removeAllEffects();
        isAwakened = false;
        hasSpawnedParticles = false;
        if (state != State.READY) {
            setState(State.READY);
        }
    }
    
    /**
     * 清理所有glow渲染实体
     */
    private void cleanupGlowSpriteEntities() {
        for (SpriteEntity sprite : glowSpriteEntities.values()) {
            if (sprite != null && !sprite.hasDelete()) {
                sprite.delete();
            }
        }
        glowSpriteEntities.clear();
    }

    /**
     * 清理指定槽位的glow渲染实体
     */
    private void cleanupGlowSpriteEntity(String slotId) {
        SpriteEntity sprite = glowSpriteEntities.remove(slotId);
        if (sprite != null && !sprite.hasDelete()) {
            sprite.delete();
        }
    }
    
    @Override
    public void renderWorld(ViewportAPI viewport) {
        // 可以在这里添加额外的视觉效果
    }
    
    @Override
    public String getDisplayText() {
        return text("display", "NT-D Mode");
    }
    
    @Override
    public String getBriefText() {
        return text("brief", "Awakened mode");
    }
    
    @Override
    public String getStateText() {
        switch (state) {
            case READY:
                return text("state.ready", "Ready");
            case IN:
                return text("state.in", "Awakening");
            case ACTIVE:
                return text("state.active", "Awakened");
            case OUT:
                return text("state.out", "Reverting");
            case COOLDOWN:
                return text("state.cooldown", "Cooldown");
            default:
                return "";
        }
    }
    
    @Override
    public String getExtraInfoText() {
        if (isOn()) {
            return isAwakened ? text("extra.awakened", "Awakened form") : text("extra.awakening", "Awakening");
        }
        return null;
    }

    private String text(String key, String fallback) {
        return Moci_TextLoader.getText(TEXT_ID, key, fallback);
    }
    
    @Override
    public Color getHUDColor() {
        if (isOn()) {
            return new Color(100, 200, 255); // 觉醒时青色
        } else if (isCooldown()) {
            return Color.ORANGE;
        } else {
            return Color.GREEN;
        }
    }
    
    @Override
    public float getBarFill() {
        if (state == State.ACTIVE) {
            return 1f;
        } else if (state == State.IN) {
            return getStateCompleteRatio();
        } else if (state == State.OUT) {
            return 1f - getStateCompleteRatio();
        } else if (state == State.COOLDOWN) {
            return 1f - getStateCompleteRatio();
        }
        return 0f;
    }
    
    /**
     * 获取是否已觉醒（供动画控制器使用）
     */
    public boolean isAwakened() {
        return isAwakened;
    }
    
    /**
     * 获取当前effectLevel（供动画控制器使用）
     */
    public float getCurrentEffectLevel() {
        return getEffectLevel();
    }
    
    /**
     * 获取当前状态（供动画控制器使用）
     */
    public State getCurrentState() {
        return state;
    }
    
    // ========== 参数设置方法（供船插使用） ==========
    
    // 效果参数
    public void setMaxSpeedBonus(float maxSpeedBonus) {
        this.maxSpeedBonus = maxSpeedBonus;
    }
    
    public void setMaxAccelerationBonus(float maxAccelerationBonus) {
        this.maxAccelerationBonus = maxAccelerationBonus;
    }
    
    public void setMaxDecelerationBonus(float maxDecelerationBonus) {
        this.maxDecelerationBonus = maxDecelerationBonus;
    }

    public void setMaxTurnAccelerationBonus(float maxTurnAccelerationBonus) {
        this.maxTurnAccelerationBonus = maxTurnAccelerationBonus;
    }
    
    // 充能系统参数
    public void setMaxCharges(int maxCharges) {
        this.maxCharges = maxCharges;
    }
    
    public void setChargeRegenTime(float chargeRegenTime) {
        this.chargeRegenTime = chargeRegenTime;
    }
    
    // 时间参数
    public void setInDuration(float inDuration) {
        this.inDuration = inDuration;
    }
    
    public void setActiveDuration(float activeDuration) {
        this.activeDuration = activeDuration;
    }
    
    public void setOutDuration(float outDuration) {
        this.outDuration = outDuration;
    }
    
    // 幅能消耗参数
    public void setFluxPerSecond(float fluxPerSecond) {
        this.fluxPerSecond = fluxPerSecond;
    }
    
    public void setActivationFluxFlat(float activationFluxFlat) {
        this.activationFluxFlat = activationFluxFlat;
    }
    
    public void setActivationFluxPercent(float activationFluxPercent) {
    }
    
    public void setHardFlux(boolean isHardFlux) {
        this.isHardFlux = isHardFlux;
    }
    
    // 目标系统参数
    public void setRequiresTarget(boolean requiresTarget) {
    }
    
    public void setTargetOnlyEnemies(boolean targetOnlyEnemies) {
    }
    
    public void setRange(float range) {
    }
    
    // 其他参数
    public void setCanUseWhileOverloaded(boolean canUseWhileOverloaded) {
        this.canUseWhileOverloaded = canUseWhileOverloaded;
    }
    
    public void setCanUseWhileVenting(boolean canUseWhileVenting) {
        this.canUseWhileVenting = canUseWhileVenting;
    }
    
    public void setUsesChargesOnActivate(boolean usesChargesOnActivate) {
        this.usesChargesOnActivate = usesChargesOnActivate;
    }

    public void setStartWithNoCharges(boolean startWithNoCharges) {
        this.startWithNoCharges = startWithNoCharges;
    }
    
    public void setSystemStatsEffectMult(float systemStatsEffectMult) {
    }
}

